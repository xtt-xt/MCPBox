// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpRequest(
    val method: String,
    val path: String,
    val rawQuery: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: String,
    val version: String
) {
    /** 读一个 cookie 值（网页登录会话用）。 */
    fun cookie(name: String): String? = header("cookie")
        ?.split(';')
        ?.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }
        ?.firstOrNull { it.first.equals(name, ignoreCase = true) }
        ?.second

    val query: Map<String, String> by lazy {
        if (rawQuery.isBlank()) emptyMap() else rawQuery.split('&').mapNotNull {
            if (it.isEmpty()) return@mapNotNull null
            val i = it.indexOf('=')
            if (i < 0) decode(it) to "" else decode(it.substring(0, i)) to decode(it.substring(i + 1))
        }.toMap()
    }

    fun header(name: String): String? = headers[name.lowercase()]

    fun bodyText(): String = String(body, Charsets.UTF_8)

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

interface SseWriter {
    val closed: Boolean
    fun send(data: String, event: String? = null): Boolean
    fun comment(text: String): Boolean
    fun close()
}

interface Exchange {
    val request: HttpRequest
    /** Send a complete response. Returns true when the connection stays usable. */
    fun respond(
        status: Int,
        contentType: String?,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
        keepAlive: Boolean = true
    ): Boolean

    fun respondText(
        status: Int,
        contentType: String?,
        text: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Boolean = respond(status, contentType, text.toByteArray(Charsets.UTF_8), extraHeaders)

    /** Upgrade the connection to a `text/event-stream` (chunked) channel. */
    fun openSse(extraHeaders: Map<String, String> = emptyMap(), retryMs: Int? = null): SseWriter
}

private class SseWriterImpl(
    private val out: BufferedOutputStream,
    private val onClose: () -> Unit
) : SseWriter {
    private val closedFlag = AtomicBoolean(false)

    override val closed: Boolean get() = closedFlag.get()

    override fun send(data: String, event: String?): Boolean = writeChunk(
        buildString {
            if (event != null) append("event: ").append(event).append('\n')
            data.split('\n').forEach { append("data: ").append(it).append('\n') }
            append('\n')
        }
    )

    override fun comment(text: String): Boolean = writeChunk(": $text\n\n")

    private fun writeChunk(text: String): Boolean {
        if (closedFlag.get()) return false
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
            out.flush()
            true
        } catch (e: IOException) {
            close()
            false
        }
    }

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            runCatching {
                out.write("0\r\n\r\n".toByteArray())
                out.flush()
            }
            onClose()
        }
    }
}

/**
 * Minimal HTTP/1.1 server: keep-alive, chunked request bodies and chunked SSE
 * responses, no external dependencies (works on Android's JVM subset).
 */
class HttpServer(
    private val handler: (HttpRequest, Exchange) -> Boolean,
    private val serverName: String = "MCPBox"
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private val connCount = AtomicLong(0)
    private val openSockets: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile var onError: ((String) -> Unit)? = null

    val activeConnections: Long get() = connCount.get()

    fun start(port: Int, bindAll: Boolean) {
        if (running.get()) throw IllegalStateException("服务已在运行")
        val address = if (bindAll) InetSocketAddress(port) else InetSocketAddress("127.0.0.1", port)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(address, 50)
        serverSocket = ss
        // core == max so that keep-alive / SSE connections never starve new clients
        pool = ThreadPoolExecutor(
            48, 48, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
            { r -> Thread(r, "mcp-http-${connCount.incrementAndGet()}").apply { isDaemon = true } }
        ).apply { allowCoreThreadTimeOut(true) }
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    pool?.execute { serveConnection(socket) }
                } catch (e: IOException) {                    if (running.get()) onError?.invoke("accept 失败：${e.message}")
                } catch (e: Exception) {
                    if (running.get()) onError?.invoke("连接处理异常：${e.message}")
                }
            }
        }, "mcp-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
        pool?.shutdownNow()
        pool = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------ internals

    private fun serveConnection(socket: Socket) {
        openSockets.add(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = socket.getInputStream().buffered(16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            val remote = socket.inetAddress?.hostAddress ?: "?"
            var keepAlive = true
            while (keepAlive && running.get()) {
                val request = readRequest(input, output, remote) ?: break
                socket.soTimeout = 120_000
                val exchange = ExchangeImpl(socket, output, request)
                keepAlive = try {
                    handler(request, exchange)
                } catch (e: Exception) {
                    runCatching {
                        exchange.respondText(
                            500, "application/json; charset=utf-8",
                            jo("error" to (e.message ?: e.javaClass.simpleName)).toString()
                        )
                    }
                    false
                }
                if (exchange.upgraded) break
                socket.soTimeout = 20_000
            }
        } catch (e: Exception) {
            // client gone / timeout: nothing to do
        } finally {
            openSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream, output: BufferedOutputStream, remote: String): HttpRequest? {
        val requestLine = readLine(input, 8192) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, 16384) ?: return null
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > 262_144) return null
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
        }

        val bodyLen = headers["content-length"]?.toLongOrNull() ?: 0L
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            runCatching {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                output.flush()
            }
        }

        val body: ByteArray = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunked(input, 64L * 1024 * 1024)
            bodyLen > 0 -> readExactly(input, bodyLen.coerceAtMost(64L * 1024 * 1024).toInt())
            else -> ByteArray(0)
        }

        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) target.substring(qIdx + 1) else ""
        return HttpRequest(
            method = method,
            path = URLDecoder.decode(path, "UTF-8"),
            rawQuery = query,
            headers = headers,
            body = body,
            remote = remote,
            version = version
        )
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > limit) throw IOException("请求头过长")
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) throw IOException("请求体不完整")
            read += n
        }
        return buf
    }

    private fun readChunked(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, 512) ?: break
            if (sizeLine.isBlank()) continue
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: break
            if (size == 0L) {
                // consume trailer
                while (true) {
                    val l = readLine(input, 1024) ?: break
                    if (l.isEmpty()) break
                }
                break
            }
            if (out.size() + size > limit) throw IOException("请求体过大")
            val chunk = readExactly(input, size.toInt())
            out.write(chunk)
            readLine(input, 512) // trailing CRLF
        }
        return out.toByteArray()
    }

    private inner class ExchangeImpl(
        private val socket: Socket,
        private val out: BufferedOutputStream,
        override val request: HttpRequest
    ) : Exchange {

        var upgraded = false
        private var responded = false

        override fun respond(
            status: Int,
            contentType: String?,
            body: ByteArray,
            extraHeaders: Map<String, String>,
            keepAlive: Boolean
        ): Boolean {
            if (responded) return false
            responded = true
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            sb.append("Date: ").append(httpDate()).append("\r\n")
            sb.append("Server: ").append(serverName).append("\r\n")
            if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            if (request.method == "HEAD") {
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())
            } else {
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())
                out.write(body)
            }
            out.flush()
            return keepAlive
        }

        override fun openSse(extraHeaders: Map<String, String>, retryMs: Int?): SseWriter {
            responded = true
            upgraded = true
            val sb = StringBuilder()
            sb.append("HTTP/1.1 200 OK\r\n")
            sb.append("Date: ").append(httpDate()).append("\r\n")
            sb.append("Server: ").append(serverName).append("\r\n")
            sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
            sb.append("Cache-Control: no-cache, no-store, no-transform\r\n")
            sb.append("X-Accel-Buffering: no\r\n")
            sb.append("Transfer-Encoding: chunked\r\n")
            extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("Connection: keep-alive\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            if (retryMs != null) {
                val chunk = "retry: $retryMs\n\n".toByteArray(Charsets.UTF_8)
                out.write((Integer.toHexString(chunk.size) + "\r\n").toByteArray())
                out.write(chunk)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            return SseWriterImpl(out) { runCatching { socket.close() } }
        }

        private fun httpDate(): String = java.time.format.DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())

        private fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }
}

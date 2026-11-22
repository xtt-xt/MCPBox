// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.IOException
import java.io.InputStream
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 插件联网的闸门：域名规则 → 审批 → 真正发请求。
 *
 * 用 [HttpURLConnection]（Android 和老 JVM 都有），不引第三方库。
 * 重定向自己跟着走，每跳一次重新过一遍域名检查 —— 否则一个已批准的域名
 * 可以 302 到别的机器上把请求带走。
 */
class PluginNetwork(
    private val permissions: PermissionStore,
    private val approval: ApprovalCenter,
    private val config: Config
) {

    companion object {
        /** 一次响应的正文上限，超了就截断（不报错，让脚本知道 `truncated`）。 */
        const val MAX_BODY_BYTES = 4L * 1024 * 1024

        /** 最多跟几跳重定向。 */
        const val MAX_REDIRECTS = 3

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 300_000L

        const val USER_AGENT = "MCPBox-Plugin/1.0"

        /**
         * [HttpURLConnection] 认得的方法。
         *
         * PATCH 不在这个列表里，Android 和 JDK 上都要反射改内部字段，
         * 失败就如实报「系统不支持」——不偷偷改成 POST。
         */
        val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")

        /** 从 URL 里取主机名（小写、去端口、去结尾点）。取不出来返回 null。 */
        fun parseHost(url: String): String? {
            val raw = url.trim()
            if (raw.isEmpty()) return null
            val afterScheme = when {
                raw.startsWith("http://", ignoreCase = true) -> raw.substring(7)
                raw.startsWith("https://", ignoreCase = true) -> raw.substring(8)
                else -> return null
            }
            var authority = afterScheme
            val slash = authority.indexOfAny(charArrayOf('/', '?', '#'))
            if (slash >= 0) authority = authority.substring(0, slash)
            authority = authority.substringAfterLast('@')
            if (authority.startsWith("[")) {
                // IPv6 字面量：[::1]:8720
                val end = authority.indexOf(']')
                if (end < 0) return null
                return authority.substring(1, end).lowercase(Locale.ROOT)
            }
            val host = authority.substringBefore(':').trim().lowercase(Locale.ROOT)
            return host.removeSuffix(".").ifEmpty { null }
        }

        /**
         * 域名规则匹配。
         *
         * - 精确：`api.example.com` 只放行它自己，不放行子域名。
         * - 通配：`*.example.com` 放行 `example.com` 和它下面所有子域名。
         * - 纯 `*`（不建议用）：放行一切。
         */
        fun hostMatches(pattern: String, host: String): Boolean {
            val p = pattern.trim().lowercase(Locale.ROOT).removeSuffix(".")
            val h = host.trim().lowercase(Locale.ROOT).removeSuffix(".")
            if (p.isEmpty() || h.isEmpty()) return false
            if (p == "*") return true
            if (p.startsWith("*.")) {
                val base = p.substring(2)
                if (base.isEmpty()) return false
                return h == base || h.endsWith(".$base")
            }
            return p == h
        }

        /** 校验一个方法名，不合法返回 null。 */
        fun methodOf(raw: String): String? {
            val m = raw.trim().uppercase(Locale.ROOT)
            return if (m in METHODS) m else null
        }
    }

    /**
     * 发一次请求，正文一次性读完。
     *
     * @param tool 审批 / 日志里显示的调用方（如 `plugin.demo__forecast`）。
     * @param label 给用户看的名字（如「插件「天气」」）。
     */
    fun request(
        tool: String,
        label: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxBytes: Long = MAX_BODY_BYTES
    ): PluginHttpResponse {
        val stream = open(tool, label, method, url, headers, body, timeoutMs)
        return try {
            val text = stream.readAll(maxBytes)
            PluginHttpResponse(
                status = stream.status,
                headers = stream.headers,
                body = text,
                url = stream.url,
                truncated = stream.truncated
            )
        } finally {
            stream.close()
        }
    }

    /**
     * 发一次请求，正文按行读（SSE 转发用）。
     *
     * 返回时 HTTP 头已经拿到；[PluginHttpStream.readLine] 会阻塞到下一行可读。
     */
    fun open(
        tool: String,
        label: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PluginHttpStream {
        val m = methodOf(method)
            ?: throw PluginRuntimeException(L("不支持的请求方法：%s").format(method))
        var current = url.trim()
        var hop = 0
        var bodyToSend = body
        while (true) {
            val host = parseHost(current)
                ?: throw PluginRuntimeException(L("网址不合法：%s").format(current))
            gate(tool, label, m, current, host)

            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw PluginRuntimeException(L("打不开网址：%s").format(e.message ?: current))
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
            conn.readTimeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // 不让服务端压：插件拿到的就是要原样转发给客户端的内容
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) ->
                val name = k.trim()
                if (name.isEmpty() || name.equals("content-length", ignoreCase = true)) return@forEach
                runCatching { conn.setRequestProperty(name, v) }
            }
            try {
                conn.requestMethod = m
            } catch (e: Exception) {
                // PATCH：反射改内部字段（JDK 17 的 java.base 没 opens，会失败）
                if (m == "PATCH") {
                    val ok = runCatching {
                        val f = HttpURLConnection::class.java.getDeclaredField("method")
                        f.isAccessible = true
                        f.set(conn, "PATCH")
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        runCatching { conn.disconnect() }
                        throw PluginRuntimeException(L("当前系统不支持 PATCH 请求，请改用 POST"))
                    }
                } else {
                    runCatching { conn.disconnect() }
                    throw PluginRuntimeException(L("不支持的请求方法：%s").format(m))
                }
            }
            if (bodyToSend != null && m != "GET" && m != "HEAD") {
                val bytes = bodyToSend.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                if (!headers.keys.any { it.equals("content-type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                runCatching { conn.setFixedLengthStreamingMode(bytes.size) }
                try {
                    conn.outputStream.use { it.write(bytes) }
                } catch (e: IOException) {
                    runCatching { conn.disconnect() }
                    throw PluginRuntimeException(L("请求没发出去：%s").format(e.message ?: ""))
                }
            }

            val status = try {
                conn.responseCode
            } catch (e: IOException) {
                runCatching { conn.disconnect() }
                throw PluginRuntimeException(L("请求失败：%s").format(e.message ?: ""))
            }

            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                runCatching { conn.disconnect() }
                if (location.isNullOrBlank()) {
                    throw PluginRuntimeException(L("服务器返回 %s 但没有给跳转地址").format(status))
                }
                if (hop >= MAX_REDIRECTS) {
                    throw PluginRuntimeException(L("跳转太多次（超过 %s 次）").format(MAX_REDIRECTS))
                }
                hop++
                current = if (location.startsWith("http://", true) || location.startsWith("https://", true)) {
                    location
                } else {
                    resolveLocation(current, location)
                }
                // 303 和 302 的语义：改用 GET，不带原正文
                if (status == 303 || (status == 302 && m == "POST")) {
                    bodyToSend = null
                }
                continue
            }

            val headersOut = headerMap(conn)
            val input: InputStream = try {
                conn.inputStream
            } catch (e: IOException) {
                conn.errorStream
            } ?: java.io.ByteArrayInputStream(ByteArray(0))
            return PluginHttpStream(
                status = status,
                headers = headersOut,
                reader = input.bufferedReader(Charsets.UTF_8),
                connection = conn,
                url = current
            )
        }
    }

    private fun headerMap(conn: HttpURLConnection): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        conn.headerFields?.forEach { (key, values) ->
            if (key.isNullOrBlank() || values.isNullOrEmpty()) return@forEach
            val name = key.lowercase(Locale.ROOT)
            if (name == "date" || name == "server" || name == "content-length") return@forEach
            out[name] = values.joinToString(", ")
        }
        return out
    }

    private fun resolveLocation(base: String, location: String): String = runCatching {
        java.net.URI(base).resolve(location).toString()
    }.getOrDefault(location)

    /** 过一遍域名规则与审批；被拒绝会抛 [PermissionDeniedException]。 */
    private fun gate(tool: String, label: String, method: String, url: String, host: String) {
        approval.guard(
            perm = PermKey.NETWORK,
            tool = tool,
            path = null,
            summary = L("%s 访问网络 %s").format(label, host),
            detail = L("%s %s").format(method, url),
            domain = host
        )
    }
}

/** 一个还没读完的响应。 */
class PluginHttpStream internal constructor(
    val status: Int,
    val headers: Map<String, String>,
    private val reader: BufferedReader,
    private val connection: HttpURLConnection?,
    val url: String
) {
    private var closed = false

    @Volatile
    var truncated: Boolean = false
        private set

    val isClosed: Boolean get() = closed

    /** 读一行（去掉行尾 CR/LF）。读完返回 null。 */
    fun readLine(): String? {
        if (closed) return null
        return try {
            reader.readLine()
        } catch (e: IOException) {
            close()
            null
        }
    }

    /** 把剩下的正文一次读完（超过 [maxBytes] 截断）。 */
    fun readAll(maxBytes: Long = PluginNetwork.MAX_BODY_BYTES): String {
        if (closed) return ""
        val out = StringBuilder()
        val buf = CharArray(8 * 1024)
        var total = 0L
        try {
            while (true) {
                if (total >= maxBytes) {
                    // 探一下还有没有：读到一个字符才算截断
                    if (reader.read(buf, 0, 1) > 0) truncated = true
                    break
                }
                val n = reader.read(buf)
                if (n < 0) break
                val keep = minOf(n.toLong(), maxBytes - total).toInt()
                out.append(buf, 0, keep)
                total += keep
                if (keep < n) {
                    truncated = true
                    break
                }
            }
        } catch (e: IOException) {
            // 连接断了：已读到的部分照给
        }
        return out.toString()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
        runCatching { connection?.disconnect() }
    }
}

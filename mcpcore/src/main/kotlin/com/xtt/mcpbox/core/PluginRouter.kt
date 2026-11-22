// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** 流式连接超过了「单插件同时几个」的上限。 */
class PluginStreamLimitException(message: String) : Exception(message)

/**
 * 服务类插件的 HTTP 路由。
 *
 * 分派顺序：内置路由都不认的路径才轮到它（[McpServer.dispatch] 把它放在 404 之前）。
 * 鉴权用插件自己的 Key，**不走** MCPBox 的访问令牌 —— 客户端填的是 `sk-xxx`。
 */
class PluginRouter(
    private val store: PluginStore,
    private val runtime: PluginRuntime?,
    private val network: PluginNetwork?,
    private val sandbox: PathSandbox,
    private val bridge: FileBridge,
    private val config: Config,
    private val approval: ApprovalCenter,
    private val log: EventLog
) {

    companion object {
        /** 单插件同时最多几条流式连接。 */
        const val MAX_SSE_PER_PLUGIN = 16

        /** 路由脚本的准备时间上限（流式阶段阻塞在 host 调用上不计时）。 */
        const val ROUTE_TIMEOUT_MS = 30_000L

        /** 一次性响应的正文上限。 */
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

        /**
         * 路径匹配：精确，或尾部 `*` 前缀通配。
         *
         * 也就是 `/v1/` 加一个星号：匹配 `/v1/models`、`/v1/chat/completions`，不匹配 `/v1` 本身。
         */
        fun pathMatches(pattern: String, path: String): Boolean {
            val p = pattern.trim()
            if (p.isEmpty()) return false
            val normalized = path.trimEnd('/').ifEmpty { "/" }
            if (p.endsWith("*")) return normalized.startsWith(p.dropLast(1))
            return normalized == p.trimEnd('/')
        }

        /** 来源地址算不算本机。和 [McpServer] 用的是同一份判断。 */
        fun isLoopback(remote: String): Boolean {
            val ip = remote.trim().removeSurrounding("[", "]")
            if (isLoopbackHost(ip)) return true
            if (ip.count { it == ':' } == 1) return isLoopbackHost(ip.substringBeforeLast(':'))
            return false
        }

        private fun isLoopbackHost(host: String): Boolean =
            host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
                host.equals("localhost", ignoreCase = true) || host.startsWith("127.")

        /** 从请求里取 Key：`Authorization: Bearer` 或 `X-API-Key`。 */
        fun apiKeyOf(req: HttpRequest): String? {
            val direct = req.header("x-api-key")?.trim()
            if (!direct.isNullOrBlank()) return direct
            val auth = req.header("authorization")?.trim() ?: return null
            if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
            return auth.substring(7).trim().ifEmpty { null }
        }

        private fun sameKey(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    /** 每个插件当前开着的流式连接。 */
    private val sseCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** 每个插件当前开着的流对象（停用 / 卸载 / 换 Key 时统一关掉）。 */
    private val openStreams = ConcurrentHashMap<String, MutableSet<PluginSse>>()

    init {
        // 插件目录每次重新扫描都来对一遍：不再对外服务的插件，连接全部断掉
        store.onReload = { closeInactive() }
    }

    private fun cors(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization, X-API-Key",
        "Access-Control-Max-Age" to "600"
    )

    /** 这个路径是不是某个正在服务的插件认领的（用来跳过网页登录那一层）。 */
    fun owns(path: String): Boolean =
        findRoute(path.trimEnd('/').ifEmpty { "/" }) != null

    /**
     * 试着自己处理这个请求。
     *
     * @return false = 没有插件认这个路径，交给调用方继续走（最后是 404）。
     */
    fun handle(req: HttpRequest, ex: Exchange): Boolean {
        if (runtime == null || !runtime.available()) return false
        val path = req.path.trimEnd('/').ifEmpty { "/" }
        val hit = findRoute(path) ?: return false
        val plugin = hit.first
        val route = hit.second
        val httpDecl = plugin.manifest.http ?: return false
        val cors = cors()

        // ① 绑定范围：loopback 的插件只接本机，哪怕全局监听在 0.0.0.0
        val loopback = isLoopback(req.remote)
        if (httpDecl.bind != PluginHttpDecl.BIND_LAN && !loopback) {
            return ex.respondText(
                403, "application/json; charset=utf-8",
                jo("error" to L("这个插件只接受本机访问")).toString(), cors
            )
        }

        // ② 鉴权
        when (route.auth.trim().lowercase(Locale.ROOT)) {
            "none" -> if (!loopback) {
                return ex.respondText(
                    403, "application/json; charset=utf-8",
                    jo("error" to L("这条路由只能从本机调用")).toString(), cors
                )
            }
            else -> {
                val expected = store.keyOf(plugin.id)
                val given = apiKeyOf(req)
                if (expected.isNullOrBlank() || given == null || !sameKey(expected, given)) {
                    return ex.respondText(
                        401, "application/json; charset=utf-8",
                        jo("error" to L("Key 不对")).toString(), cors
                    )
                }
            }
        }

        // ③ 方法
        val method = req.method.uppercase(Locale.ROOT)
        if (route.methods.none { it.equals(method, ignoreCase = true) }) {
            return ex.respondText(
                405, "application/json; charset=utf-8",
                jo("error" to L("这个地址不支持 %s").format(method)).toString(),
                cors + mapOf("Allow" to route.methods.joinToString(", "))
            )
        }

        // ④ 流式连接数
        if (route.stream && sseCount(plugin.id) >= MAX_SSE_PER_PLUGIN) {
            return ex.respondText(
                429, "application/json; charset=utf-8",
                jo("error" to L("这个插件的流式连接太多了（上限 %s）").format(MAX_SSE_PER_PLUGIN)).toString(),
                cors
            )
        }

        // ⑤ 脚本
        val scriptFile = File(plugin.dir, route.handler)
        if (!scriptFile.isFile) {
            return ex.respondText(
                500, "application/json; charset=utf-8",
                jo("error" to L("插件的路由脚本不存在：%s").format(route.handler)).toString(), cors
            )
        }
        val script = runCatching { scriptFile.readText() }.getOrNull()
        if (script == null) {
            return ex.respondText(
                500, "application/json; charset=utf-8",
                jo("error" to L("读不出路由脚本：%s").format(route.handler)).toString(), cors
            )
        }

        val tool = "plugin.${plugin.id}.route"
        val started = System.currentTimeMillis()
        val host = RouteHostImpl(plugin, route, req, ex, cors, tool)

        var status = 200
        var message = ""
        try {
            runtime.runRoute(script, route.handler, host, ROUTE_TIMEOUT_MS)
        } catch (e: PermissionDeniedException) {
            status = 403
            message = e.message ?: L("被拒绝")
            if (!host.responded) {
                ex.respondText(403, "application/json; charset=utf-8", jo("error" to message).toString(), cors)
            }
        } catch (e: PluginStreamLimitException) {
            status = 429
            message = e.message ?: L("流式连接太多")
            if (!host.responded) {
                ex.respondText(429, "application/json; charset=utf-8", jo("error" to message).toString(), cors)
            }
        } catch (e: PluginTimeoutException) {
            status = 504
            message = L("插件「%s」执行超时").format(plugin.manifest.name)
            if (!host.responded) {
                ex.respondText(504, "application/json; charset=utf-8", jo("error" to message).toString(), cors)
            }
        } catch (e: PluginRuntimeException) {
            status = 500
            message = e.message ?: L("脚本报错")
            if (!host.responded) {
                ex.respondText(500, "application/json; charset=utf-8", jo("error" to message).toString(), cors)
            }
        } catch (e: Exception) {
            status = 500
            message = e.javaClass.simpleName + ": " + (e.message ?: "")
            if (!host.responded) {
                ex.respondText(500, "application/json; charset=utf-8", jo("error" to message).toString(), cors)
            }
        } finally {
            host.finish()
            if (!host.responded) {
                // 脚本什么都没写：不能就这么挂着，回一个明确的 500
                ex.respondText(
                    500, "application/json; charset=utf-8",
                    jo("error" to L("插件脚本没有给出响应")).toString(), cors
                )
            }
            log.add(
                LogKind.REQUEST,
                tool = "${plugin.id}__route",
                path = path,
                client = req.remote,
                ok = status in 200..299,
                message = "$method $path" + if (message.isNotBlank()) " → $message" else "",
                durationMs = System.currentTimeMillis() - started
            )
        }
        return true
    }

    /** 把所有流式连接关掉（流式阶段结束、或插件被停用）。 */
    fun closeAll(pluginId: String? = null) {
        val ids = if (pluginId != null) listOf(pluginId) else openStreams.keys.toList()
        ids.forEach { id ->
            openStreams[id]?.toList()?.forEach { runCatching { it.close() } }
            openStreams.remove(id)
        }
    }

    private fun closeInactive() {
        openStreams.keys.toList().forEach { id ->
            val plugin = store.byId(id)
            if (plugin == null || !plugin.serving) {
                openStreams[id]?.toList()?.forEach { runCatching { it.close() } }
                openStreams.remove(id)
            }
        }
    }

    private fun sseCount(pluginId: String): Int = sseCounts[pluginId]?.get() ?: 0

    private fun acquireSse(pluginId: String): Boolean {
        val counter = sseCounts.getOrPut(pluginId) { AtomicInteger(0) }
        while (true) {
            val now = counter.get()
            if (now >= MAX_SSE_PER_PLUGIN) return false
            if (counter.compareAndSet(now, now + 1)) return true
        }
    }

    private fun releaseSse(pluginId: String) {
        sseCounts[pluginId]?.let { if (it.decrementAndGet() < 0) it.set(0) }
    }

    private fun findRoute(path: String): Pair<InstalledPlugin, PluginRouteDecl>? {
        var best: Pair<InstalledPlugin, PluginRouteDecl>? = null
        var bestLen = -1
        for (plugin in store.plugins) {
            if (!plugin.serving) continue
            for (route in plugin.routes()) {
                if (!pathMatches(route.path, path)) continue
                val len = route.path.trim().length
                if (len > bestLen) {
                    best = plugin to route
                    bestLen = len
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ host

    private fun pluginGate(tool: String, remote: String) = object : PluginGate {
        override fun guard(
            perm: PermKey,
            path: File?,
            summary: String,
            detail: String?,
            bytes: Long?,
            mediaType: String?,
            command: String?,
            backend: String?
        ) {
            approval.guard(
                perm = perm,
                tool = tool,
                path = path?.path,
                summary = summary,
                detail = detail,
                client = remote,
                mediaType = mediaType,
                byteSize = bytes,
                command = command,
                backend = backend
            )
        }
    }


    private inner class RouteHostImpl(
        private val plugin: InstalledPlugin,
        private val route: PluginRouteDecl,
        httpReq: HttpRequest,
        private val ex: Exchange,
        private val cors: Map<String, String>,
        tool: String
    ) : PluginHostImpl(
        plugin = plugin,
        store = store,
        args = JsonObject(emptyMap()),
        toolName = tool,
        sandbox = sandbox,
        bridge = bridge,
        config = config,
        gate = pluginGate(tool, httpReq.remote),
        network = network,
        toolsCall = null
    ), PluginRouteHost {

        override val req = PluginRouteRequest(
            method = httpReq.method.uppercase(Locale.ROOT),
            path = httpReq.path,
            query = httpReq.query,
            headers = httpReq.headers,
            body = httpReq.bodyText(),
            remote = httpReq.remote
        )

        private var pending: PluginRouteResponse? = null
        private var wroteSse = false

        override val responded: Boolean get() = pending != null || wroteSse

        override val response: PluginRouteResponse? get() = pending

        override val sse: PluginSse? = if (route.stream) SseBridge() else null

        override fun respond(
            status: Int,
            body: String,
            headers: Map<String, String>,
            contentType: String
        ) {
            if (responded) return
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw PluginRuntimeException(
                    L("响应太大（%s MB 上限）").format(MAX_RESPONSE_BYTES / 1024 / 1024)
                )
            }
            val declared = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                ?.value
            val ct = declared ?: contentType
            val merged = LinkedHashMap<String, String>()
            headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true)) merged[k] = v }
            merged.putAll(cors)
            ex.respond(status, ct, bytes, merged)
            pending = PluginRouteResponse(status, merged, bytes, ct)
        }

        /** 流式结束时由路由层调：把没关的连接关掉。 */
        fun finish() {
            sse?.let { runCatching { it.close() } }
        }

        private inner class SseBridge : PluginSse {
            private var writer: SseWriter? = null
            private val released = AtomicInteger(0)

            override val closed: Boolean get() = writer?.closed ?: false

            override fun open(extraHeaders: Map<String, String>): Boolean {
                val current = writer
                if (current != null) return !current.closed
                if (!acquireSse(plugin.id)) {
                    throw PluginStreamLimitException(
                        L("这个插件的流式连接太多了（上限 %s）").format(MAX_SSE_PER_PLUGIN)
                    )
                }
                val merged = LinkedHashMap<String, String>()
                merged.putAll(cors)
                merged.putAll(extraHeaders)
                val w = ex.openSse(merged)
                writer = w
                wroteSse = true
                openStreams.getOrPut(plugin.id) { CopyOnWriteArraySet() }.add(this)
                return true
            }

            override fun write(data: String): Boolean {
                if (writer == null) open()
                return writer?.send(data) ?: false
            }

            override fun comment(text: String): Boolean {
                if (writer == null) open()
                return writer?.comment(text) ?: false
            }

            override fun close() {
                if (released.compareAndSet(0, 1)) {
                    runCatching { writer?.close() }
                    openStreams[plugin.id]?.remove(this)
                    releaseSse(plugin.id)
                }
            }
        }

    }
}

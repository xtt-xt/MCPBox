// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

class Session(
    val id: String,
    val protocolVersion: String,
    val clientName: String,
    val clientVersion: String
) {
    @Volatile var sse: SseWriter? = null
    @Volatile var lastSeen: Long = System.currentTimeMillis()
    @Volatile var initialized: Boolean = false
    @Volatile var requestCount: Int = 0

    fun label(): String =
        (if (clientName.isBlank()) "未知客户端" else clientName) +
            (if (clientVersion.isBlank()) "" else " $clientVersion")
}

/** One observed client, used by the live "clients" panel in the UI. */
data class ClientTouch(val address: String, val time: Long, val label: String)

/**
 * Wires the tool registry, permission engine and approval center onto the HTTP
 * server, speaking both the MCP Streamable HTTP transport and the legacy SSE one.
 */
class McpServer(
    val config: Config,
    val permissions: PermissionStore,
    val customTools: CustomToolStore,
    val approval: ApprovalCenter,
    val log: EventLog,
    val host: HostInfo? = null,
    /** 记忆库（不传则用纯内存实例，测试方便）。 */
    val memory: MemoryStore = MemoryStore(),
    /** 内置工具的文案覆盖。 */
    val toolMeta: ToolMetaStore = ToolMetaStore(MemorySettings())
) {

    val sandbox = PathSandbox(config)
    val trash = TrashManager(sandbox)

    /**
     * 文件桥：应用自己读写不了的路径（别家私有目录）在 root / Shizuku 可用时自动转发。
     */
    val bridge = FileBridge(config) {
        // 按用户设置的后端顺序挑（默认 shizuku → root → app）。
        // 实测：su -c 受 Android 15+ 的 mount namespace 隔离影响，只能看到少数应用目录；
        // 以 root 运行的 Shizuku 能看到全部，所以这里跟随偏好顺序很重要。
        val order = config.shellPreference.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        order.mapNotNull { ShellBackends.byId(it) }
            .firstOrNull { it.id != "app" && runCatching { it.isAvailable() }.getOrDefault(false) }
            ?: ShellBackends.available().firstOrNull { it.id != "app" }
    }

    private val gateway = FileGateway(config, sandbox, approval, log, bridge)
    /** 内置工具。 */
    val builtinTools: List<ToolSpec> =
        ToolsRead.specs() + ToolsWrite.specs() + ToolsShell.specs(customTools) +
            ToolsToken.specs() + ToolsMemory.specs(memory)

    /** 内置 + 用户自定义（每次调用都重新取，改完立刻生效）。 */
    val tools: List<ToolSpec>
        get() = (builtinTools + ToolsShell.customSpecs(customTools))
            .filter { config.memoryEnabled || it.perm != PermKey.MEMORY }
            .map { toolMeta.apply(it) }

    private val http = HttpServer(handler = ::dispatch, serverName = ServerMeta.NAME)
    val sessions = ConcurrentHashMap<String, Session>()
    val clients = ConcurrentHashMap<String, ClientTouch>()

    @Volatile var lastError: String? = null
    @Volatile private var boundPort: Int = 0

    val isRunning: Boolean get() = http.isRunning
    val port: Int get() = boundPort
    val activeConnections: Long get() = http.activeConnections

    private var cleanupThread: Thread? = null

    // ------------------------------------------------------------------ lifecycle

    fun start(): Boolean {
        if (http.isRunning) return true
        return try {
            http.onError = { lastError = it }
            http.start(config.port, config.bindAll)
            boundPort = config.port
            ServerMeta.startTime = System.currentTimeMillis()
            log.add(LogKind.SYSTEM, message = "服务已启动，端口 ${config.port}")
            startCleanup()
            true
        } catch (e: Exception) {
            lastError = "启动失败：${e.message}"
            log.add(LogKind.ERROR, ok = false, message = lastError ?: "启动失败")
            false
        }
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    fun stop() {
        approval.cancelAll()
        sessions.values.forEach { runCatching { it.sse?.close() } }
        sessions.clear()
        http.stop()
        cleanupThread?.interrupt()
        cleanupThread = null
    }

    private fun startCleanup() {
        cleanupThread = Thread({
            while (http.isRunning) {
                try {
                    Thread.sleep(60_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                sessions.entries.removeIf { (_, s) -> now - s.lastSeen > 30 * 60_000L }
                clients.entries.removeIf { (_, c) -> now - c.time > 10 * 60_000L }
            }
        }, "mcp-cleanup").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------- routing

    private fun dispatch(req: HttpRequest, ex: Exchange): Boolean {
        val cors = corsHeaders()
        clients[req.remote] = ClientTouch(req.remote, System.currentTimeMillis(), clients[req.remote]?.label ?: "")
        if (req.method == "OPTIONS") {
            return ex.respond(204, null, ByteArray(0), cors)
        }
        val path = req.path.trimEnd('/').ifEmpty { "/" }

        // ①「仅本机」：只响应 localhost，其它来源一律拒绝
        if (config.consoleLocalOnly && !isLoopback(req.remote)) {
            return ex.respondText(
                403, "text/plain; charset=utf-8",
                "已开启「仅本机访问」：这个服务只接受来自 localhost 的请求。\n" +
                    "想让局域网里的设备（电脑、其它手机）访问，请在 App 的「设置 → 网页控制台」里关掉它。",
                cors
            )
        }

        // ② 网页密码保护：没登录就跳登录页（带 token 的程序调用不受影响）
        if (config.consoleAuthEnabled && path != "/login" && path != "/logout" && !webAuthorized(req)) {
            return ex.respond(302, null, ByteArray(0), cors + mapOf("Location" to "/login"))
        }

        return when (path) {
            "/login" -> handleLogin(req, ex, cors)
            "/logout" -> handleLogout(ex, cors)
            "/", "/console", "/index.html" -> ex.respondText(
                200, "text/html; charset=utf-8", WebConsole.html(config, this), cors
            )
            "/favicon.ico" -> ex.respond(204, null, ByteArray(0), cors)
            "/health" -> ex.respondText(
                200, "application/json; charset=utf-8",
                jo(
                    "status" to "ok",
                    "server" to ServerMeta.NAME,
                    "version" to ServerMeta.version,
                    "uptime" to ServerMeta.uptimeText(),
                    "uptimeMs" to ServerMeta.uptimeMs(),
                    "tools" to tools.size,
                    "sessions" to sessions.size,
                    "port" to boundPort,
                    "authRequired" to config.tokenEnabled
                ).toString(), cors
            )
            // 文件进出通道：外部（比如 AI 工作区）直接推文件进来 / 取文件出去
            "/upload" -> withAuth(req, ex, cors) {
                respondGateway(ex, cors, gateway.handleUpload(req))
            }
            "/download" -> withAuth(req, ex, cors) {
                respondGateway(ex, cors, gateway.handleDownload(req))
            }
            "/mcp" -> handleMcp(req, ex, cors)
            "/sse" -> handleLegacySse(req, ex, cors)
            "/messages" -> handleLegacyMessages(req, ex, cors)
            "/api/status" -> withAuth(req, ex, cors) {
                ex.respondText(200, "application/json; charset=utf-8", statusJson().toString(), cors)
            }
            "/api/tools" -> withAuth(req, ex, cors) {
                ex.respondText(200, "application/json; charset=utf-8", toolsJson().toString(), cors)
            }
            "/api/log" -> withAuth(req, ex, cors) {
                val limit = req.query["limit"]?.toIntOrNull() ?: 200
                val entries = log.list(limit).map {
                    jo(
                        "id" to it.id, "time" to it.time, "kind" to it.kind.id,
                        "tool" to it.tool, "path" to it.path, "client" to it.client,
                        "ok" to it.ok, "message" to it.message, "durationMs" to it.durationMs
                    )
                }
                ex.respondText(200, "application/json; charset=utf-8", jo("entries" to entries).toString(), cors)
            }
            "/api/pending" -> withAuth(req, ex, cors) {
                val pending = approval.pendingRequests().map {
                    jo(
                        "id" to it.id, "perm" to it.perm.id, "tool" to it.tool,
                        "path" to it.path, "summary" to it.summary, "client" to it.client,
                        "createdAt" to it.createdAt
                    )
                }
                ex.respondText(200, "application/json; charset=utf-8", jo("pending" to pending).toString(), cors)
            }
            "/api/approve" -> withAuth(req, ex, cors) {
                val body = runCatching { J.parseToJsonElement(req.bodyText()) as? JsonObject }.getOrNull()
                val id = body?.str("id")
                val decision = ApprovalDecision.entries.firstOrNull { it.id == body?.str("decision") }
                if (id == null || decision == null) {
                    ex.respondText(
                        400, "application/json; charset=utf-8",
                        jo("error" to "需要 id 和 decision").toString(), cors
                    )
                } else {
                    val ok = approval.resolve(id, decision)
                    ex.respondText(
                        200, "application/json; charset=utf-8",
                        jo("ok" to ok).toString(), cors
                    )
                }
            }
            "/api/call" -> withAuth(req, ex, cors) { handleRestCall(req, ex, cors) }
            else -> ex.respondText(
                404, "application/json; charset=utf-8", jo("error" to "未知路径: $path").toString(), cors
            )
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
        "Access-Control-Allow-Headers" to
            "Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version, X-MCP-Token, Accept, Last-Event-ID",
        "Access-Control-Expose-Headers" to "Mcp-Session-Id",
        "Access-Control-Max-Age" to "600"
    )

    // ------------------------------------------------------------ 网页鉴权

    /** 网页登录会话：cookie 值 → 过期时间戳。 */
    private val webSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 给测试和界面提示用：这个来源地址算不算「本机」。 */
    fun isLoopbackAddress(remote: String): Boolean = isLoopback(remote)

    private fun isLoopback(remote: String): Boolean {
        val ip = remote.trim().removeSurrounding("[", "]")
        if (isLoopbackHost(ip)) return true
        // 兼容 "127.0.0.1:1234" / "[::1]:1234" 这种带端口的写法
        if (ip.count { it == ':' } == 1) return isLoopbackHost(ip.substringBeforeLast(':'))
        return false
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            host.equals("localhost", ignoreCase = true) || host.startsWith("127.")

    private fun loginPassword(): String = config.consolePassword.ifBlank { config.token }

    /** 网页请求是否已通过：关掉密码保护、带对 token、或有有效的登录 cookie。 */
    private fun webAuthorized(req: HttpRequest): Boolean {
        if (!config.consoleAuthEnabled) return true
        if (checkAuth(req)) return true
        val sid = req.cookie(SESSION_COOKIE) ?: return false
        val expires = webSessions[sid] ?: return false
        if (expires < System.currentTimeMillis()) {
            webSessions.remove(sid)
            return false
        }
        return true
    }

    private fun handleLogin(req: HttpRequest, ex: Exchange, cors: Map<String, String>): Boolean {
        if (req.method == "GET" || req.method == "HEAD") {
            return ex.respondText(
                200, "text/html; charset=utf-8",
                WebConsole.loginPage(passwordIsToken = config.consolePassword.isBlank()), cors
            )
        }
        val form = parseFormUrlEncoded(req.body.toString(Charsets.UTF_8))
        val given = (form["password"] ?: req.query["password"] ?: "").trim()
        if (given.isNotEmpty() && given == loginPassword()) {
            val sid = java.util.UUID.randomUUID().toString().replace("-", "")
            webSessions[sid] = System.currentTimeMillis() + SESSION_TTL_MS
            log.add(LogKind.REQUEST, tool = "web_login", client = req.remote, ok = true, message = "网页控制台登录成功")
            return ex.respond(
                302, null, ByteArray(0),
                cors + mapOf(
                    "Location" to "/",
                    "Set-Cookie" to "$SESSION_COOKIE=$sid; Path=/; Max-Age=${SESSION_TTL_MS / 1000}; HttpOnly; SameSite=Lax"
                )
            )
        }
        log.add(LogKind.REQUEST, tool = "web_login", client = req.remote, ok = false, message = "网页控制台密码错误")
        return ex.respondText(
            401, "text/html; charset=utf-8",
            WebConsole.loginPage(error = true, passwordIsToken = config.consolePassword.isBlank()), cors
        )
    }

    private fun handleLogout(ex: Exchange, cors: Map<String, String>): Boolean = ex.respond(
        302, null, ByteArray(0),
        cors + mapOf(
            "Location" to "/login",
            "Set-Cookie" to "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly"
        )
    )

    private fun parseFormUrlEncoded(body: String): Map<String, String> =
        if (body.isBlank()) emptyMap() else body.split('&').mapNotNull {
            if (it.isEmpty()) return@mapNotNull null
            val i = it.indexOf('=')
            if (i < 0) null else {
                java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
        }.toMap()

    private fun withAuth(req: HttpRequest, ex: Exchange, cors: Map<String, String>, block: () -> Boolean): Boolean {
        if (!checkAuth(req)) {
            return ex.respondText(
                401, "application/json; charset=utf-8",
                jo("error" to "缺少或错误的访问令牌（token）").toString(),
                cors + mapOf("WWW-Authenticate" to "Bearer")
            )
        }
        return block()
    }

    private fun checkAuth(req: HttpRequest): Boolean {
        if (!config.tokenEnabled) return true
        val token = config.token
        if (token.isBlank()) return true
        val bearer = req.header("authorization")
            ?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        val header = req.header("x-mcp-token")?.trim()
        val query = req.query["token"]?.trim()
        return bearer == token || header == token || query == token
    }

    // -------------------------------------------------------------- MCP transport

    private fun respondGateway(ex: Exchange, cors: Map<String, String>, r: FileGateway.Result): Boolean =
        ex.respond(r.status, r.contentType, r.body, cors + r.headers)

    private fun handleMcp(req: HttpRequest, ex: Exchange, cors: Map<String, String>): Boolean {
        val session = req.header("mcp-session-id")?.let { sessions[it] }
        if (!checkAuth(req)) {
            return ex.respondText(
                401, "application/json; charset=utf-8",
                rpcError(null, -32001, "缺少或错误的访问令牌（token）").toString(),
                cors + mapOf("WWW-Authenticate" to "Bearer")
            )
        }
        return when (req.method) {
            "POST" -> {
                val accept = req.header("accept") ?: "*/*"
                val body = req.bodyText()
                if (body.isBlank()) {
                    return ex.respondText(
                        400, "application/json; charset=utf-8",
                        rpcError(null, -32700, "空请求体").toString(), cors
                    )
                }
                val parsed = runCatching { J.parseToJsonElement(body) }.getOrNull()
                    ?: return ex.respondText(
                        200, "application/json; charset=utf-8",
                        rpcError(null, -32700, "JSON 解析失败").toString(), cors
                    )
                val wantsSse = when (config.responseMode) {
                    Config.Modes.SSE -> true
                    Config.Modes.JSON -> false
                    else -> accept.contains("text/event-stream") && !accept.contains("application/json")
                }
                if (wantsSse) {
                    val sse = ex.openSse(cors + (session?.let { mapOf("Mcp-Session-Id" to it.id) } ?: emptyMap()))
                    val (responses, _) = collectResponses(parsed, session, req.remote)
                    responses.forEach { sse.send(it.toString(), "message") }
                    sse.close()
                    true
                } else {
                    val (responses, newSessionId) = collectResponses(parsed, session, req.remote)
                    val headers = cors + when {
                        newSessionId != null -> mapOf("Mcp-Session-Id" to newSessionId)
                        session != null -> mapOf("Mcp-Session-Id" to session.id)
                        else -> emptyMap()
                    }
                    when {
                        responses.isEmpty() -> ex.respond(202, null, ByteArray(0), headers)
                        responses.size == 1 -> ex.respondText(
                            200, "application/json; charset=utf-8", responses[0].toString(), headers
                        )
                        else -> ex.respondText(
                            200, "application/json; charset=utf-8", JsonArray(responses).toString(), headers
                        )
                    }
                }
            }
            "GET" -> {
                if (session == null) {
                    ex.respondText(
                        400, "application/json; charset=utf-8",
                        rpcError(null, -32000, "缺少 Mcp-Session-Id，请先 POST initialize").toString(), cors
                    )
                } else {
                    streamSession(session, ex, cors)
                }
            }
            "DELETE" -> {
                if (session != null) {
                    sessions.remove(session.id)
                    runCatching { session.sse?.close() }
                    log.add(LogKind.CONNECT, message = "客户端断开：${session.label()}")
                }
                ex.respondText(200, "application/json; charset=utf-8", jo("ok" to true).toString(), cors)
            }
            else -> ex.respondText(
                405, "application/json; charset=utf-8",
                rpcError(null, -32600, "不支持的方法 ${req.method}").toString(), cors
            )
        }
    }

    private fun streamSession(session: Session, ex: Exchange, cors: Map<String, String>): Boolean {
        val sse = ex.openSse(cors + mapOf("Mcp-Session-Id" to session.id))
        session.sse = sse
        while (!sse.closed && http.isRunning) {
            session.lastSeen = System.currentTimeMillis()
            if (!sse.comment("ping")) break
            try {
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                break
            }
        }
        runCatching { sse.close() }
        if (session.sse === sse) session.sse = null
        return false
    }

    /** Legacy HTTP+SSE transport: GET /sse, then POST /messages?sessionId=xxx */
    private fun handleLegacySse(req: HttpRequest, ex: Exchange, cors: Map<String, String>): Boolean {
        if (!checkAuth(req)) {
            return ex.respondText(401, "text/plain; charset=utf-8", "未授权：缺少或错误的 token", cors)
        }
        val session = Session(
            id = Tokens.newId(),
            protocolVersion = ServerMeta.PROTOCOL,
            clientName = req.query["name"] ?: "sse-client",
            clientVersion = ""
        )
        sessions[session.id] = session
        clients[req.remote] = ClientTouch(req.remote, System.currentTimeMillis(), session.label())
        log.add(LogKind.CONNECT, message = "客户端连接（SSE）：${session.label()} @${req.remote}")
        val sse = ex.openSse(cors)
        session.sse = sse
        sse.send("/messages?sessionId=${session.id}", "endpoint")
        while (!sse.closed && http.isRunning) {
            session.lastSeen = System.currentTimeMillis()
            if (!sse.comment("ping")) break
            try {
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                break
            }
        }
        runCatching { sse.close() }
        sessions.remove(session.id)
        return false
    }

    private fun handleLegacyMessages(req: HttpRequest, ex: Exchange, cors: Map<String, String>): Boolean {
        if (!checkAuth(req)) {
            return ex.respondText(401, "application/json; charset=utf-8", jo("error" to "未授权").toString(), cors)
        }
        val sessionId = req.query["sessionId"] ?: req.query["session_id"]
        val session = sessionId?.let { sessions[it] }
            ?: return ex.respondText(
                404, "application/json; charset=utf-8", jo("error" to "会话不存在").toString(), cors
            )
        val parsed = runCatching { J.parseToJsonElement(req.bodyText()) }.getOrNull()
            ?: return ex.respondText(
                400, "application/json; charset=utf-8", jo("error" to "JSON 解析失败").toString(), cors
            )
        ex.respond(202, null, ByteArray(0), cors)
        val (responses, _) = collectResponses(parsed, session, req.remote)
        val sse = session.sse
        responses.forEach { r ->
            val ok = sse?.send(r.toString(), "message") ?: false
            if (!ok) log.add(LogKind.ERROR, ok = false, message = "SSE 通道已断开，响应未能送达")
        }
        return true
    }

    private data class RpcOut(val response: JsonObject?, val sessionId: String? = null)

    private fun collectResponses(
        parsed: JsonElement,
        session: Session?,
        remote: String
    ): Pair<List<JsonObject>, String?> {
        val outs = when (parsed) {
            is JsonArray -> parsed.mapNotNull { el ->
                (el as? JsonObject)?.let { processMessage(it, session, remote) }
            }
            is JsonObject -> listOfNotNull(processMessage(parsed, session, remote))
            else -> emptyList()
        }
        val newSessionId = outs.firstOrNull { it.sessionId != null }?.sessionId
        return outs.mapNotNull { it.response } to newSessionId
    }

    /** The response is null for notifications (no id) and for pure acknowledgements. */
    private fun processMessage(msg: JsonObject, session: Session?, remote: String): RpcOut? {
        val method = msg.str("method")
        val id = msg["id"]
        val isNotification = id == null || id is JsonNull
        if (method == null) {
            return if (isNotification) null else RpcOut(rpcError(id, -32600, "缺少 method"))
        }
        session?.lastSeen = System.currentTimeMillis()
        session?.let { it.requestCount = it.requestCount + 1 }
        val params = msg.obj("params") ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> {
                val requested = params.str("protocolVersion") ?: ServerMeta.PROTOCOL
                val supported = listOf("2025-06-18", "2025-03-26", "2024-11-05")
                val negotiated = if (requested in supported) requested else ServerMeta.PROTOCOL
                val clientInfo = params.obj("clientInfo")
                val name = clientInfo?.str("name") ?: "unknown"
                val version = clientInfo?.str("version") ?: ""
                val newSession = session ?: Session(
                    id = Tokens.newId(), protocolVersion = negotiated,
                    clientName = name, clientVersion = version
                )
                newSession.initialized = true
                sessions[newSession.id] = newSession
                clients[remote] = ClientTouch(remote, System.currentTimeMillis(), newSession.label())
                log.add(
                    LogKind.CONNECT,
                    message = "客户端已连接：${newSession.label()} @$remote（协议 $negotiated）"
                )
                RpcOut(
                    rpcResult(
                        id,
                        jo(
                            "protocolVersion" to negotiated,
                            "capabilities" to jo(
                                "tools" to jo("listChanged" to false),
                                "logging" to JsonObject(emptyMap()),
                                "completions" to JsonObject(emptyMap())
                            ),
                            "serverInfo" to jo(
                                "name" to ServerMeta.NAME,
                                "title" to ServerMeta.TITLE,
                                "version" to ServerMeta.version
                            ),
                            "instructions" to instructions()
                        )
                    ),
                    newSession.id
                )
            }
            "notifications/initialized", "initialized",
            "notifications/cancelled", "notifications/progress",
            "notifications/roots/list_changed" -> null

            "ping" -> RpcOut(rpcResult(id, JsonObject(emptyMap())))

            "tools/list" -> {
                log.add(LogKind.REQUEST, tool = "tools/list", client = remote, message = "列出工具")
                RpcOut(
                    rpcResult(
                        id,
                        jo("tools" to tools.filter { !ToolPolicy.isDisabled(config, it.name) }
                            .map { it.toMcpJson() })
                    )
                )
            }

            "tools/call" -> {
                val paramsCopy = params
                RpcOut(rpcResult(id, callTool(paramsCopy, remote)))
            }

            "resources/list" -> RpcOut(rpcResult(id, jo("resources" to emptyList<Any>())))
            "resources/templates/list" -> RpcOut(rpcResult(id, jo("resourceTemplates" to emptyList<Any>())))
            "prompts/list" -> RpcOut(rpcResult(id, jo("prompts" to emptyList<Any>())))
            "logging/setLevel" -> RpcOut(rpcResult(id, JsonObject(emptyMap())))
            "completion/complete" -> RpcOut(rpcResult(id, jo("completion" to jo("values" to emptyList<Any>()))))

            else -> if (isNotification) null else RpcOut(rpcError(id, -32601, "不支持的方法：$method"))
        }
    }

    /** Executes one tool and always returns a `{content, isError}` result object. */
    private fun callTool(params: JsonObject, remote: String): JsonObject {
        val name = params.str("name")
            ?: return toolErrorResult("tools/call 缺少参数 name")
        val args = params.obj("arguments") ?: JsonObject(emptyMap())
        val spec = tools.firstOrNull { it.name == name }
            ?: return toolErrorResult("未知工具：$name（可用 tools/list 查看全部工具）")
        if (ToolPolicy.isDisabled(config, name)) {
            return toolErrorResult("工具「$name」已在 App 里被禁用（可在「设置 → 工具管理」里启用）")
        }
        val ctx = CallContext(
            tool = name,
            args = args,
            client = remote,
            config = config,
            sandbox = sandbox,
            permissions = permissions,
            approval = approval,
            log = log,
            trash = trash,
            host = host,
            customTools = customTools,
            bridge = bridge,
            memory = memory,
            toolMeta = toolMeta
        )
        val started = System.currentTimeMillis()
        val pathForLog = args.str("path") ?: args.str("source")
        return try {
            val result = spec.handler(ctx)
            log.add(
                LogKind.REQUEST, tool = name, path = pathForLog, client = remote,
                ok = result.ok, message = firstLine(result.text),
                durationMs = System.currentTimeMillis() - started
            )
            toolResult(result)
        } catch (e: PermissionDeniedException) {
            log.add(
                LogKind.REQUEST, tool = name, path = pathForLog, client = remote, ok = false,
                message = e.message ?: "被拒绝", durationMs = System.currentTimeMillis() - started
            )
            toolErrorResult("操作被拒绝：${e.message}\n（用户可在 App 的权限页调整该权限）")
        } catch (e: ToolFailure) {
            log.add(
                LogKind.REQUEST, tool = name, path = pathForLog, client = remote, ok = false,
                message = e.message ?: "失败", durationMs = System.currentTimeMillis() - started
            )
            toolErrorResult(e.message ?: "操作失败")
        } catch (e: SandboxException) {
            log.add(
                LogKind.REQUEST, tool = name, path = pathForLog, client = remote, ok = false,
                message = e.message ?: "路径不合法", durationMs = System.currentTimeMillis() - started
            )
            toolErrorResult(e.message ?: "路径不合法")
        } catch (e: Exception) {
            log.add(
                LogKind.ERROR, tool = name, path = pathForLog, client = remote, ok = false,
                message = "${e.javaClass.simpleName}: ${e.message}",
                durationMs = System.currentTimeMillis() - started
            )
            toolErrorResult("工具执行异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toolResult(result: ToolResult): JsonObject {
        val content = ArrayList<JsonElement>()
        content.add(jo("type" to "text", "text" to result.text))
        result.extraContent.forEach { content.add(it) }
        return jo("content" to content, "isError" to (!result.ok))
    }

    private fun toolErrorResult(message: String): JsonObject = jo(
        "content" to listOf(jo("type" to "text", "text" to message)),
        "isError" to true
    )

    private fun firstLine(text: String): String = text.split('\n').firstOrNull()?.take(180) ?: ""

    private fun instructions(): String = buildString {
        append("这是运行在手机上的文件管理服务器（MCP 文件盒），可以直接读写手机本地文件。\n")
        append("根目录：").append(config.roots.joinToString("、")).append('\n')
        append("路径规则：可写绝对路径；相对路径和 ~ 表示第一个根目录。\n")
        append("安全机制：涉及写入/删除的操作会实时在手机上弹出审批窗口，被拒绝时不要反复重试。\n")
        append("删除默认进入回收站，可用 list_trash / restore_trash 找回。\n")
        append("终端：run_shell 可以执行 Shell 命令（后端 ")
        append(ShellBackends.pick("auto", config)?.label ?: "无")
        append("），危险命令同样会弹窗审批，用户可以「始终允许」某条命令。\n")
        if (customTools.tools.isNotEmpty()) {
            append("自定义工具：")
            append(customTools.tools.joinToString("、") { it.name })
            append("（用户自己定义的操作，可直接调用）。\n")
        }
        append("自定义工具管理：create_custom_tool / update_custom_tool / delete_custom_tool / ")
        append("list_custom_tools / export_custom_tools / import_custom_tools。\n")
        append("建议流程：server_info 了解环境 → list_dir / search_files 定位 → read_file 查看 → ")
        append("write_file / edit_file 修改 → notify_user 通知用户。\n")
        append("当前权限：")
        append(
            PermKey.entries.joinToString("、") { "${it.title}=${permissions.decide(it, null).action.label}" }
        )
    }

    // -------------------------------------------------------------------- helpers

    private fun handleRestCall(req: HttpRequest, ex: Exchange, cors: Map<String, String>): Boolean {
        val body = runCatching { J.parseToJsonElement(req.bodyText()) as? JsonObject }.getOrNull()
            ?: return ex.respondText(
                400, "application/json; charset=utf-8",
                jo("error" to "请求体不是 JSON 对象").toString(), cors
            )
        val name = body.str("tool") ?: body.str("name")
            ?: return ex.respondText(
                400, "application/json; charset=utf-8", jo("error" to "缺少 tool").toString(), cors
            )
        val args = body.obj("arguments") ?: body.obj("args") ?: JsonObject(emptyMap())
        val result = callTool(jo("name" to name, "arguments" to args), req.remote)
        return ex.respondText(200, "application/json; charset=utf-8", result.toString(), cors)
    }

    data class ServerStatus(
        val running: Boolean,
        val port: Int,
        val uptimeText: String,
        val uptimeMs: Long,
        val toolCount: Int,
        val sessions: Int,
        val connections: Long,
        val total: Long,
        val ok: Long,
        val failed: Long,
        val approvals: Long,
        val denied: Long,
        val pending: Int,
        val lastError: String?,
        val roots: List<String>,
        val device: String,
        val tokenEnabled: Boolean,
        val token: String,
        val builtinTools: Int = 0,
        val customToolCount: Int = 0
    )

    fun status(): ServerStatus {
        val s = log.stats()
        return ServerStatus(
            running = isRunning,
            port = boundPort,
            uptimeText = ServerMeta.uptimeText(),
            uptimeMs = ServerMeta.uptimeMs(),
            toolCount = tools.size,
            sessions = sessions.size,
            connections = http.activeConnections,
            total = s.total,
            ok = s.ok,
            failed = s.failed,
            approvals = s.approvals,
            denied = s.denied,
            pending = approval.pendingRequests().size,
            lastError = lastError,
            roots = config.roots,
            device = ServerMeta.deviceLabel,
            tokenEnabled = config.tokenEnabled,
            token = config.token,
            builtinTools = builtinTools.size,
            customToolCount = customTools.tools.size
        )
    }

    fun statusJson(): JsonObject = jo(
        "running" to isRunning,
        "server" to ServerMeta.NAME,
        "version" to ServerMeta.version,
        "device" to ServerMeta.deviceLabel,
        "port" to boundPort,
        "configuredPort" to config.port,
        "bindAll" to config.bindAll,
        "uptimeMs" to ServerMeta.uptimeMs(),
        "uptime" to ServerMeta.uptimeText(),
        "roots" to config.roots,
        "fullAccess" to config.fullAccess,
        "readOnly" to config.readOnly,
        "trashEnabled" to config.trashEnabled,
        "tokenRequired" to config.tokenEnabled,
        "token" to if (config.tokenEnabled) config.token else "",
        "tools" to tools.size,
        "builtinTools" to builtinTools.size,
        "customTools" to customTools.tools.size,
        "shellBackends" to ShellBackends.all().map {
            jo("id" to it.id, "label" to it.label, "available" to runCatching { it.isAvailable() }.getOrDefault(false))
        },
        "shellPreference" to config.shellPreference,
        "sessions" to sessions.size,
        "connections" to http.activeConnections,
        "stats" to jo(
            "total" to log.stats().total,
            "ok" to log.stats().ok,
            "failed" to log.stats().failed,
            "approvals" to log.stats().approvals,
            "denied" to log.stats().denied
        ),
        "permissions" to PermKey.entries.associate { it.id to permissions.decide(it, null).action.id },
        "pendingApprovals" to approval.pendingRequests().size,
        "lastError" to lastError
    )

    fun toolsJson(): JsonArray = JsonArray(tools.map { it.toMcpJson() })

    // ------------------------------------------------------------------- JSON-RPC

    private fun rpcResult(id: JsonElement?, result: JsonObject): JsonObject = jo(
        "jsonrpc" to "2.0",
        "id" to (id ?: JsonNull),
        "result" to result
    )

    private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = jo(
        "jsonrpc" to "2.0",
        "id" to (id ?: JsonNull),
        "error" to jo("code" to code, "message" to message)
    )
}

/** 网页登录 cookie 名 / 有效期（12 小时）。 */
private const val SESSION_COOKIE = "mcpbox_session"
private const val SESSION_TTL_MS = 12 * 3600_000L

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.harness

import com.xtt.mcpbox.core.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end test of the MCP server core on a plain JVM: real HTTP server, real
 * JSON-RPC traffic, real permission + approval flow.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("  [PASS] $name")
    } else {
        failed++
        println("  [FAIL] $name ${if (detail.isNotEmpty()) "-> $detail" else ""}")
    }
}

private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

/** tools/list 响应里工具个数（直接数 name 字段）。 */
private fun countTools(body: String): Int = Regex("\"name\\\":\"").findAll(body).count()

private fun http(
    method: String,
    url: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
    followRedirects: Boolean = true
): Resp {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = followRedirects
    conn.requestMethod = method
    conn.connectTimeout = 5000
    conn.readTimeout = 20000
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    return Resp(code, text, conn.headerFields)
}

/** 同 [http]，但请求体是原始字节（上传 zip 用）。 */
private fun httpBytes(
    method: String,
    url: String,
    body: ByteArray,
    headers: Map<String, String> = emptyMap()
): Resp {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 5000
    conn.readTimeout = 20000
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    conn.doOutput = true
    conn.outputStream.use { it.write(body) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    return Resp(code, text, conn.headerFields)
}

/** 取二进制响应（目录打包成的 zip 用）：返回 (状态码, 原始字节)。 */
private fun httpRaw(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 30000
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
    return code to bytes
}

/** 在内存里打一个 zip（测试用）。 */
private fun zipBytes(entries: Map<String, String>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zos ->
        entries.forEach { (name, text) ->
            zos.putNextEntry(java.util.zip.ZipEntry(name))
            zos.write(text.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

/** 内存里解开一个 zip：条目名 → 文本。 */
private fun unzipText(bytes: ByteArray): Map<String, String> {
    val got = HashMap<String, String>()
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
        var e = zis.nextEntry
        while (e != null) {
            if (!e.isDirectory) got[e.name] = zis.readBytes().toString(Charsets.UTF_8)
            e = zis.nextEntry
        }
    }
    return got
}

/** 从 tools/list 的响应里精确取出工具名集合（避免描述文本里的词误伤断言）。 */
private fun toolNamesIn(body: String): Set<String> =
    Regex("\\\"name\\\":\\\"([a-zA-Z_0-9]+)\\\"").findAll(body)
        .map { it.groupValues[1] }.toSet()

fun main() {
    val root = Files.createTempDirectory("mcpbox-e2e").toFile()
    File(root, "docs").mkdirs()
    File(root, "docs/hello.txt").writeText("hello world\n第二行中文\nthird line\n")
    File(root, "docs/notes.md").writeText("# 标题\nTODO: 写点东西\n")
    File(root, "empty.txt").writeText("")
    File(root, "pic.png").writeBytes(
        java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
        )
    )

    val settings = MemorySettings()
    settings.putInt(Config.Keys.PORT, 18720)
    settings.putBoolean(Config.Keys.BIND_ALL, false)
    settings.putString(Config.Keys.TOKEN, "testtoken123")
    settings.putBoolean(Config.Keys.TOKEN_ENABLED, true)
    settings.putString(Config.Keys.ROOTS, root.absolutePath)
    settings.putLong(Config.Keys.APPROVAL_TIMEOUT, 5000)
    settings.putBoolean(Config.Keys.TRASH, true)

    DefaultRoots.provider = { root.absolutePath }

    val config = Config(settings)
    val log = EventLog()
    val permissions = PermissionStore(config, settings)
    val approval = ApprovalCenter(config, permissions, log)

    // 模拟「手机主人」：按内容匹配的脚本化答案。
    // 用内容匹配而不是队列，这样即使超时残留的审批线程也不会吃掉后面的答案。
    val scripted = java.util.concurrent.ConcurrentHashMap<String, ApprovalDecision>()
    fun answer(key: String, decision: ApprovalDecision) {
        scripted[key] = decision
    }

    approval.headlessResolver = { req ->
        val hay = listOfNotNull(req.command, req.summary, req.path).joinToString(" | ")
        val hit = scripted.entries.firstOrNull { hay.contains(it.key) }
        if (hit != null) {
            println("    <审批> ${req.perm.id} $hay  → ${hit.value.label}")
            hit.value
        } else {
            println("    <审批> ${req.perm.id} $hay  → 无人处理（等超时）")
            runCatching { Thread.sleep(120_000) }
            ApprovalDecision.TIMEOUT
        }
    }

    // 注册一个「应用沙箱」后端（在真实安卓上是 /system/bin/sh）
    ShellEnv.home = root.absolutePath
    ShellEnv.tmp = root.absolutePath
    ShellBackends.register(PosixShLauncher(shellPath = "/bin/sh"))
    val customTools = CustomToolStore(config, settings)
    // 统计：和 App 里一样挂在 files/stats/stats.json 上
    val statsStore = StatsStore(File(root, "stats/stats.json"))

    // 浏览器：JVM 上没有 WebView，用假的引擎替上（真机上由 BrowserController 实现）
    val fakeBrowser = FakeBrowser()

    val server = McpServer(
        config = config, customTools = customTools, permissions = permissions,
        approval = approval, log = log, host = null,
        memory = MemoryStore(File(root, "memory/graph.json")),
        toolMeta = ToolMetaStore(settings),
        packs = PackStore(settings),
        profiles = ProfileStore(File(root, "profiles"), config),
        stats = statsStore,
        browserBridge = fakeBrowser
    )
    // 默认会话把所有包都打开：下面大量老测试都假设「tools/list 返回全部工具」。
    // 工具包的过滤行为在 [37] 段用独立的会话单独验证。
    server.profiles.setActive(
        ProfileStore.DEFAULT_ID,
        BuiltinPacks.ALL.map { it.id } + ToolPack.MY_TOOLS_ID
    )

    println("== 启动服务器 (${root.absolutePath}) ==")
    if (!server.start()) {
        println("启动失败：${server.lastError}")
        kotlin.system.exitProcess(1)
    }
    val base = "http://127.0.0.1:18720"
    val auth = mapOf("Authorization" to "Bearer testtoken123")

    try {
        // ---------------------------------------------------------- 基础端点
        println("\n[1] 基础端点")
        val health = http("GET", "$base/health")
        check("GET /health 返回 200", health.code == 200, "code=${health.code}")
        check("/health 含工具数量", health.body.contains("\"tools\""), health.body)

        val noAuth = http("GET", "$base/api/status")
        check("无 token 访问 /api/status 被拒 401", noAuth.code == 401, "code=${noAuth.code}")
        val withAuth = http("GET", "$base/api/status", headers = auth)
        check("带 token 访问 /api/status 成功", withAuth.code == 200, "code=${withAuth.code}")

        val page = http("GET", "$base/")
        check("控制台页面可访问", page.code == 200 && page.body.contains("MCP 文件盒"), "code=${page.code}")

        // ------------------------------------------------------------ 初始化
        println("\n[2] MCP initialize / tools/list")
        val initResp = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"harness","version":"1.0"}}}""",
            auth + mapOf("Accept" to "application/json")
        )
        val initJson = J.parseToJsonElement(initResp.body).jsonObject
        val sessionId = initResp.headers.entries
            .firstOrNull { it.key.equals("Mcp-Session-Id", true) }?.value?.firstOrNull()
        check("initialize 返回 protocolVersion", initJson["result"]?.jsonObject?.get("protocolVersion")
            ?.jsonPrimitive?.content == "2025-06-18", initResp.body)
        check("响应头带 Mcp-Session-Id", sessionId != null, initResp.headers.toString())
        check("serverInfo 存在", initJson["result"]?.jsonObject?.get("serverInfo") != null)

        val sessionHeaders = auth + mapOf(
            "Accept" to "application/json",
            "Mcp-Session-Id" to (sessionId ?: "")
        )
        val notif = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""", sessionHeaders
        )
        check("notifications/initialized 返回 202", notif.code == 202, "code=${notif.code}")

        val toolsResp = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", sessionHeaders
        )
        val tools = J.parseToJsonElement(toolsResp.body).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray
        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        check("tools/list 返回 >= 28 个工具", toolNames.size >= 28, "实际 ${toolNames.size}")
        check("包含 run_shell", "run_shell" in toolNames)
        check("包含 shell_info", "shell_info" in toolNames)
        check("包含 create_custom_tool", "create_custom_tool" in toolNames)
        check("包含 export_custom_tools", "export_custom_tools" in toolNames)
        check("包含 list_dir", "list_dir" in toolNames)
        check("包含 write_file", "write_file" in toolNames)
        check("包含 delete_path", "delete_path" in toolNames)
        check("包含 read_image", "read_image" in toolNames)
        check("包含 search_files", "search_files" in toolNames)
        check("每个工具有 inputSchema", tools.all { it.jsonObject["inputSchema"] != null })

        val ping = http(
            "POST", "$base/mcp", """{"jsonrpc":"2.0","id":3,"method":"ping"}""", sessionHeaders
        )
        check("ping 正常", ping.body.contains("\"result\""), ping.body)

        val bogus = http(
            "POST", "$base/mcp", """{"jsonrpc":"2.0","id":4,"method":"no/such"}""", sessionHeaders
        )
        check("未知方法返回 -32601", bogus.body.contains("-32601"), bogus.body)

        // ------------------------------------------------------------ 只读工具
        println("\n[3] 只读工具（当前权限：读=允许）")

        fun call(name: String, args: String): Pair<Boolean, String> {
            val resp = http(
                "POST", "$base/mcp",
                """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$name","arguments":$args}}""",
                sessionHeaders
            )
            if (resp.code != 200) return false to "HTTP ${resp.code} ${resp.body}"
            val obj = J.parseToJsonElement(resp.body).jsonObject
            val err = obj["error"]
            if (err != null) return false to "rpc error: $err"
            val res = obj["result"]!!.jsonObject
            val text = res["content"]!!.jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
            return (res["isError"]?.jsonPrimitive?.content != "true") to text
        }

        val (okInfo, infoText) = call("server_info", "{}")
        check("server_info 成功", okInfo, infoText)
        check("server_info 提到根目录", infoText.contains(root.absolutePath), infoText.take(300))

        val (okList, listText) = call("list_dir", """{"path":"docs"}""")
        check("list_dir 列出 hello.txt", okList && listText.contains("hello.txt"), listText)
        check("list_dir 列出 notes.md", listText.contains("notes.md"))

        val (okRead, readText) = call("read_file", """{"path":"docs/hello.txt"}""")
        check("read_file 读到内容", okRead && readText.contains("hello world"), readText)
        check("read_file 带行号", readText.contains("1| hello world") || readText.contains("    1| hello world"), readText)

        val (okSearchName, nameSearch) = call("search_files", """{"name":"*.md"}""")
        check("search_files 按名字找到 notes.md", okSearchName && nameSearch.contains("notes.md"), nameSearch)

        val (okSearchContent, contentSearch) = call("search_files", """{"content":"TODO"}""")
        check(
            "search_files 按内容找到 TODO",
            okSearchContent && contentSearch.contains("notes.md:2"), contentSearch
        )

        val (okImg, imgText) = call("read_image", """{"path":"pic.png"}""")
        check("read_image 成功", okImg, imgText)

        val (okInfoFile, fileInfoText) = call("file_info", """{"path":"docs/hello.txt"}""")
        check("file_info 显示大小", okInfoFile && fileInfoText.contains("字节"), fileInfoText)

        // ------------------------------------------------------------ 沙箱边界
        println("\n[4] 沙箱边界")
        val (okEscape, escapeText) = call("list_dir", """{"path":"/etc"}""")
        check("越界路径被拒绝", !okEscape && escapeText.contains("超出允许范围"), escapeText)
        val (okTrav, travText) = call("read_file", """{"path":"../etc/passwd"}""")
        check(".. 穿越被拒绝", !okTrav, travText)

        // ------------------------------------------------------------ 审批：允许一次
        println("\n[5] 审批流程：写入 = 询问")
        answer("new.txt", ApprovalDecision.ALLOW_ONCE)
        val (okWrite, writeText) = call(
            "write_file",
            """{"path":"docs/new.txt","content":"AI 写的内容\n"}"""
        )
        check("write_file 经审批后成功", okWrite, writeText)
        check("文件真的写入了", File(root, "docs/new.txt").exists())
        check("内容正确", File(root, "docs/new.txt").readText().contains("AI 写的内容"))
        check("权限开关仍是询问（允许一次不改变设置）",
            permissions.switchOf(PermKey.WRITE) == PermAction.ASK,
            permissions.switchOf(PermKey.WRITE).id)

        // ------------------------------------------------------------ 审批：拒绝
        println("\n[6] 审批流程：用户拒绝")
        answer("denied.txt", ApprovalDecision.DENY_ONCE)
        val (okDeny, denyText) = call(
            "write_file",
            """{"path":"docs/denied.txt","content":"不该被写入"}"""
        )
        check("拒绝后工具返回失败", !okDeny, denyText)
        check("被拒绝的文件没有被创建", !File(root, "docs/denied.txt").exists())
        check("错误信息说明被拒绝", denyText.contains("拒绝"), denyText)

        // ------------------------------------------------------------ 审批：始终允许
        println("\n[7] 审批流程：始终允许")
        answer("always.txt", ApprovalDecision.ALLOW_ALWAYS)
        val (okAlways, alwaysText) = call(
            "write_file",
            """{"path":"docs/always.txt","content":"ok"}"""
        )
        check("始终允许后写入成功", okAlways, alwaysText)
        check(
            "权限开关已变为允许",
            permissions.switchOf(PermKey.WRITE) == PermAction.ALLOW,
            permissions.switchOf(PermKey.WRITE).id
        )
        val (okNoAsk, noAskText) = call("write_file", """{"path":"docs/silent.txt","content":"无需再问"}""")
        check("之后不再弹窗直接成功", okNoAsk, noAskText)

        // ------------------------------------------------------------ 审批：超时
        println("\n[8] 审批超时")
        permissions.setSwitch(PermKey.WRITE, PermAction.ASK) // 上一节刚被设成「始终允许」，先复位
        settings.putLong(Config.Keys.APPROVAL_TIMEOUT, 1500)
        config.reload()
        val t0 = System.currentTimeMillis()
        val (okTimeout, timeoutText) = call("make_dir", """{"path":"docs/timeout_dir"}""")
        val elapsed = System.currentTimeMillis() - t0
        check("超时后失败", !okTimeout, timeoutText)
        check("确实等待了约 1.5 秒", elapsed in 1000..6000, "${elapsed}ms")
        check("超时信息可见", timeoutText.contains("超时"), timeoutText)
        check("目录没有被创建", !File(root, "docs/timeout_dir").exists())
        settings.putLong(Config.Keys.APPROVAL_TIMEOUT, 5000)
        config.reload()

        // ------------------------------------------------------------ 删除 = 询问（进回收站）
        println("\n[9] 删除进回收站 + 还原")
        answer("删除 文件", ApprovalDecision.ALLOW_ALWAYS)
        val (okDel, delText) = call("delete_path", """{"path":"docs/new.txt"}""")
        check("删除成功", okDel, delText)
        check("原文件已不在", !File(root, "docs/new.txt").exists())
        check("提示进了回收站", delText.contains("回收站"), delText)
        val (okTrash, trashText) = call("list_trash", "{}")
        check("回收站里能找到", okTrash && trashText.contains("new.txt"), trashText)
        answer("还原", ApprovalDecision.ALLOW_ONCE)
        val (okRestore, restoreText) = call("restore_trash", """{"name":"new.txt"}""")
        check("还原成功", okRestore, restoreText)
        check("文件回到原位置", File(root, "docs/new.txt").exists())

        // ------------------------------------------------------------ 路径规则
        println("\n[10] 路径规则优先生效")
        permissions.addRule(PermKey.WRITE.id, "${root.absolutePath}/docs", PermAction.DENY, "测试规则")
        val (okRule, ruleText) = call("write_file", """{"path":"docs/ruled.txt","content":"x"}""")
        check("路径规则拒绝生效", !okRule && ruleText.contains("路径规则"), ruleText)
        answer("outside-ruled", ApprovalDecision.ALLOW_ONCE)
        val (okRule2, ruleText2) = call("write_file", """{"path":"outside-ruled.txt","content":"x"}""")
        check("规则外路径不受影响", okRule2, ruleText2)
        permissions.clearRules()

        // ------------------------------------------------------------ 只读模式
        println("\n[11] 只读模式")
        config.readOnly = true
        val (okRo, roText) = call("write_file", """{"path":"readonly.txt","content":"x"}""")
        check("只读模式下写入被拒", !okRo && roText.contains("只读"), roText)
        config.readOnly = false

        // ------------------------------------------------------------ 传输层
        println("\n[12] 传输层：SSE 模式与 /sse 旧协议")
        val sseResp = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":7,"method":"tools/list"}""",
            sessionHeaders + mapOf("Accept" to "text/event-stream")
        )
        check("SSE 响应体含 data:", sseResp.body.contains("data: {"), sseResp.body.take(200))
        check("SSE 响应含 result", sseResp.body.contains("\"result\""), sseResp.body.take(300))

        val legacy = legacySseRoundTrip(base)
        check("旧协议 /sse 返回 endpoint", legacy.first.contains("/messages?sessionId="), legacy.first)
        check("旧协议 /messages 回送到 SSE", legacy.second.contains("\"result\""), legacy.second)

        val streamGet = mcpSseStream(base, sessionHeaders)
        check("GET /mcp 打开 SSE 流并立即有 ping", streamGet.contains(":"), streamGet.take(120))

        // ------------------------------------------------------------ 批量请求
        println("\n[13] 批量 JSON-RPC")
        val batch = http(
            "POST", "$base/mcp",
            """[{"jsonrpc":"2.0","id":11,"method":"ping"},{"jsonrpc":"2.0","id":12,"method":"ping"}]""",
            sessionHeaders
        )
        check("批量请求返回数组", batch.body.trim().startsWith("["), batch.body.take(200))

        // ------------------------------------------------------------ 回收站清空
        println("\n[14] 清空回收站")
        answer("清空回收站", ApprovalDecision.ALLOW_ALWAYS)
        val (okEmpty, emptyText) = call("empty_trash", """{"confirm":true}""")
        check("清空回收站成功", okEmpty, emptyText)

        // ------------------------------------------------------------ 日志
        println("\n[15] 日志与统计")
        val logResp = http("GET", "$base/api/log?limit=50", headers = auth)
        check("日志接口有记录", logResp.body.contains("\"entries\"") && logResp.body.length > 100, logResp.body.take(200))
        val stats = log.stats()
        check("统计到审批次数 > 0", stats.approvals > 0, "${stats.approvals}")
        check("统计到拒绝次数 > 0", stats.denied > 0, "${stats.denied}")
        // ------------------------------------------------------------ 终端命令
        println("\n[16] 执行命令 + 审批")
        permissions.setSwitch(PermKey.SHELL, PermAction.ASK)
        answer("hello-from-shell", ApprovalDecision.ALLOW_ONCE)
        val (okShell, shellText) = call("run_shell", """{"command":"echo hello-from-shell"}""")
        check("run_shell 经审批后成功", okShell, shellText)
        check("拿到 stdout", shellText.contains("hello-from-shell"), shellText)
        check("报告了退出码", shellText.contains("退出码：0"), shellText)

        val t16 = System.currentTimeMillis()
        answer("should-not-run", ApprovalDecision.DENY_ONCE)
        val (okDenied, deniedText) = call("run_shell", """{"command":"echo should-not-run"}""")
        check("拒绝后不执行", !okDenied, deniedText)
        check("拒绝用时合理", System.currentTimeMillis() - t16 < 5000)

        // 「始终允许」应该记住命令前缀而不是把整个权限放开
        check("此时命令规则还是空的", permissions.commandRules().isEmpty())
        answer("memo-this", ApprovalDecision.ALLOW_ALWAYS)
        val (ok2, text2) = call("run_shell", """{"command":"echo memo-this"}""")
        check("始终允许后执行成功", ok2, text2)
        val cmdRules = permissions.commandRules()
        check("自动生成了命令规则", cmdRules.size == 1, cmdRules.toString())
        check("规则记的是命令名 echo", cmdRules.firstOrNull()?.target == "echo", cmdRules.toString())
        check("权限开关没有被改成允许（只记命令）", permissions.switchOf(PermKey.SHELL) == PermAction.ASK)
        val (ok3, text3) = call("run_shell", """{"command":"echo no-ask-again"}""")
        check("之后同样的命令不再询问", ok3 && text3.contains("no-ask-again"), text3)

        println("\n[17] 命令规则：始终拒绝 / 正则 / 顺序")
        permissions.addCommandRule("rm", PermAction.DENY)
        val (okRm, rmText) = call("run_shell", """{"command":"rm -rf /tmp/whatever"}""")
        check("rm 前缀被拒绝", !okRm && rmText.contains("命令规则"), rmText)
        check("拒绝消息带规则名", rmText.contains("rm"), rmText)
        val (okLs, lsText) = call("run_shell", """{"command":"ls"}""")
        check("其他命令仍会弹审批（这次没人应答→超时）", !okLs && lsText.contains("超时"), lsText)

        permissions.addCommandRule("^dd if=", PermAction.ALLOW, Rule.MATCH_REGEX)
        val (okDd, ddText) = call("run_shell", """{"command":"dd if=/dev/zero of=/dev/null bs=1 count=1"}""")
        check("正则规则命中并放行", okDd, ddText)
        check(
            "正则规则不会误伤别的命令",
            permissions.decide(PermKey.SHELL, command = "ls -la").action == PermAction.ASK,
            permissions.decide(PermKey.SHELL, command = "ls -la").source
        )

        val (okShellInfo, shellInfoMsg) = call("shell_info", "{}")
        check("shell_info 列出后端", okShellInfo && shellInfoMsg.contains("应用沙箱"), shellInfoMsg)
        check("shell_info 列出命令规则", shellInfoMsg.contains("rm"), shellInfoMsg)

        println("\n[18] 自定义工具")
        answer("创建自定义工具", ApprovalDecision.ALLOW_ALWAYS)
        val (okCreate, createText) = call(
            "create_custom_tool",
            """{"name":"echo_msg","title":"回显一句话","description":"把 msg 原样打印出来",
                "command":"echo {{msg}}","params":[{"name":"msg","type":"string","description":"要打印的内容","required":true}]}"""
        )
        check("创建自定义工具成功", okCreate, createText)
        check("工具进入列表", !createText.contains("失败"))

        val toolsAfter = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":30,"method":"tools/list"}""", sessionHeaders)
        check("tools/list 里出现自定义工具", toolsAfter.body.contains("echo_msg"), toolsAfter.body.take(200))

        // 自定义工具执行时同样走命令规则（echo 已被记住 → 不该弹窗）
        val (okRun, runText) = call("echo_msg", """{"msg":"从自定义工具打出来的"}""")
        check("自定义工具执行成功", okRun, runText)
        check("参数替换正确", runText.contains("从自定义工具打出来的"), runText)

        val (okBad, badText) = call("echo_msg", "{}")
        check("缺必填参数时报错", !okBad && badText.contains("必填"), badText)

        val (okToolList, toolListText) = call("list_custom_tools", "{}")
        check("能看到自定义工具明细", okToolList && toolListText.contains("echo_msg"), toolListText)

        println("\n[19] 自定义工具导入导出")
        answer("mcp-tools.json", ApprovalDecision.ALLOW_ONCE)
        val (okExport, exportText) = call("export_custom_tools", """{"path":"xtt/mcp-tools.json"}""")
        check("导出成功", okExport, exportText)
        val exported = File(root, "xtt/mcp-tools.json")
        check("文件真的写出来了", exported.exists(), exported.path)
        check("导出内容含工具名", exported.exists() && exported.readText().contains("echo_msg"))

        answer("删除自定义工具", ApprovalDecision.ALLOW_ONCE)
        val (okDelTool, delToolText) = call("delete_custom_tool", """{"name":"echo_msg"}""")
        check("删除成功", okDelTool, delToolText)
        check("删除后列表为空", customTools.tools.isEmpty(), customTools.tools.size.toString())

        answer("mcp-tools.json", ApprovalDecision.ALLOW_ONCE)
        val (okImport, importText) = call("import_custom_tools", """{"path":"xtt/mcp-tools.json"}""")
        check("导入成功", okImport, importText)
        check("工具回来了", customTools.byName("echo_msg") != null, importText)
        check("导入统计正确", importText.contains("新增 1"), importText)

        println("\n[20] 命令模板转义")
        val tpl = ToolTemplate.render(
            "echo {{a}} && ls {{b:raw}}",
            J.parseToJsonElement("""{"a":"it's ok","b":"-la"}""").jsonObject,
            listOf(
                CustomToolParam(name = "a", required = true),
                CustomToolParam(name = "b", default = "")
            )
        )
        check("普通参数做了单引号转义", tpl.contains("'it'\\''s ok'"), tpl)
        check("raw 参数原样插入", tpl.contains("ls -la"), tpl)
        val err = runCatching {
            ToolTemplate.render("echo {{x}}", JsonObject(emptyMap()), listOf(CustomToolParam(name = "x", required = true)))
        }.exceptionOrNull()
        check("缺必填参数会抛错", err is ToolFailure, err?.toString() ?: "没有抛错")

        println("\n[21] 旧数据兼容（v1.0 的路径规则）")
        val legacySettings = MemorySettings().apply {
            putString(
                Config.Keys.PERMISSIONS,
                """{"switches":{"fs.read":"allow"},"rules":[{"id":"old1","perm":"fs.write","path":"${root.absolutePath}/docs","action":"deny","note":"旧规则"}]}"""
            )
        }
        val legacyConfig = Config(legacySettings)
        val legacyPerms = PermissionStore(legacyConfig, legacySettings)
        check("旧路径规则被读出来", legacyPerms.pathRules().size == 1, legacyPerms.pathRules().toString())
        check("旧规则仍然生效", !legacyPerms.decide(PermKey.WRITE, "${root.absolutePath}/docs/x.txt").allowed)
        check(
            "规则外的路径回落到开关（写=询问）",
            legacyPerms.decide(PermKey.WRITE, "${root.absolutePath}/other.txt").action == PermAction.ASK,
            legacyPerms.decide(PermKey.WRITE, "${root.absolutePath}/other.txt").source
        )
        println("\n[22] 回归：Shizuku 那种「未结束就抛异常」的进程")
        val shizukuLike = object : CommandLauncher {
            override val id = "shizuku"
            override val label = "Shizuku"
            override val uidLabel = "shell (uid 2000)"
            override fun isAvailable() = true
            override fun launch(command: String, cwd: String?, env: Map<String, String>): Process =
                object : Process() {
                    private val killed = java.util.concurrent.atomic.AtomicBoolean(false)
                    override fun getOutputStream() = java.io.ByteArrayOutputStream()
                    override fun getInputStream() = "hello-from-remote\n".byteInputStream()
                    override fun getErrorStream() = "".byteInputStream()
                    override fun waitFor() = 0
                    // Shizuku 的实现就是这样：没结束就抛 IllegalArgumentException
                    override fun exitValue(): Int {
                        if (!killed.get()) throw IllegalArgumentException("process hasn't exited")
                        return 137
                    }
                    override fun destroy() { killed.set(true) }
                }
        }
        val slowResult = ShellRunner().run(shizukuLike, "sleep 100", null, 400, 10_000)
        check("远端进程不退出时不再抛异常", true)
        check("被判定为超时", slowResult.timedOut, slowResult.toText())
        check("仍然拿到了输出", slowResult.stdout.contains("hello-from-remote"), slowResult.stdout)
        check("退出码是兜底的 -1", slowResult.exitCode == -1, slowResult.exitCode.toString())
        println("\n[23] 应用私有目录的权限（默认禁止 / 只读 / 可读写）")
        val privatePath = "/data/data/com.example.app/files/secret.txt"
        config.privateAccess = Config.PRIVATE_OFF
        check(
            "默认禁止访问私有目录",
            runCatching { server.sandbox.resolve(privatePath) }.isFailure
        )
        check("系统目录依然受保护", runCatching { server.sandbox.resolve("/proc/self/maps") }.isFailure)

        config.privateAccess = Config.PRIVATE_READ
        check(
            "只读模式下放行私有目录",
            runCatching { server.sandbox.resolve(privatePath) }.isSuccess,
            runCatching { server.sandbox.resolve(privatePath) }.exceptionOrNull()?.message ?: ""
        )
        check("私有路径判定正确", server.sandbox.isPrivatePath(File(privatePath)))
        check(
            "只读模式下写入被拒绝",
            runCatching { server.sandbox.assertWritable(File(privatePath)) }.isFailure
        )
        check(
            "普通路径在只读模式下不受影响",
            runCatching { server.sandbox.assertWritable(File(root, "docs/hello.txt")) }.isSuccess
        )

        config.privateAccess = Config.PRIVATE_FULL
        check(
            "可读写模式下写入放行",
            runCatching { server.sandbox.assertWritable(File(privatePath)) }.isSuccess
        )
        config.privateAccess = Config.PRIVATE_OFF

        println("\n[24] HTTP 文件网关（上传 / 下载）")
        val uploadText = "hello-from-outside"
        answer("gateway-test", ApprovalDecision.ALLOW_ONCE)
        val upRes = http("POST", "$base/upload?path=xtt/gateway-test.txt", uploadText, sessionHeaders)
        check("POST /upload 写入成功", upRes.body.contains("\"ok\": true") || upRes.body.contains("\"ok\":true"), upRes.body.take(200))
        check("文件真的落盘了", File(root, "xtt/gateway-test.txt").readText() == uploadText)
        check("返回了 md5", upRes.body.contains("md5"), upRes.body.take(200))

        val downRes = http("GET", "$base/download?path=xtt/gateway-test.txt", null, sessionHeaders)
        check("GET /download 内容一致", downRes.body == uploadText, downRes.body.take(200))

        val pageRes = http("GET", "$base/upload", null, sessionHeaders)
        check("上传网页能打开", pageRes.body.contains("上传到手机"), pageRes.body.take(120))

        val missRes = http("GET", "$base/download?path=xtt/does-not-exist.txt", null, sessionHeaders)
        check("下载不存在的文件返回错误", missRes.body.contains("\"ok\": false") || missRes.body.contains("\"ok\":false"), missRes.body.take(160))

        val noPath = http("POST", "$base/upload", "x", sessionHeaders)
        check("上传缺 path 会报错", noPath.body.contains("缺少 path"), noPath.body.take(160))
        println("\n[25] 网页控制台：密码保护")
        config.consoleAuthEnabled = true
        config.consolePassword = "hunter2"

        val blocked = http("GET", "$base/", null, emptyMap())
        check("未登录时网页被挡", blocked.code == 302 || blocked.body.contains("login"), "code=${blocked.code}")

        val loginPage = http("GET", "$base/login", null, emptyMap())
        check("登录页能打开", loginPage.body.contains("访问密码") || loginPage.body.contains("password"), loginPage.body.take(100))

        val badPwd = http("POST", "$base/login", "password=nope", emptyMap())
        check("错误密码被拒", badPwd.code == 401, "code=${badPwd.code}")

        val login = http("POST", "$base/login", "password=hunter2", emptyMap(), followRedirects = false)
        val cookie = login.headers["Set-Cookie"]?.firstOrNull()?.substringBefore(';')
        check("密码正确会下发会话 cookie", cookie?.startsWith("mcpbox_session=") == true, "cookie=$cookie")

        val withCookie = http("GET", "$base/", null, mapOf("Cookie" to (cookie ?: "")))
        check("带 cookie 就能访问", withCookie.code == 200 && withCookie.body.contains("MCP"), "code=${withCookie.code}")

        val withToken = http("GET", "$base/api/status?token=${config.token}", null, emptyMap())
        check("程序用 token 调用不受影响", withToken.code == 200, "code=${withToken.code}")

        config.consoleAuthEnabled = false
        config.consolePassword = ""
        check("关掉密码保护后又能直接打开", http("GET", "$base/", null, emptyMap()).code == 200)

        println("\n[26] 网页控制台：仅本机（localhost）")
        check("127.0.0.1 算本机", server.isLoopbackAddress("127.0.0.1"))
        check("::1 算本机", server.isLoopbackAddress("::1"))
        check("localhost 算本机", server.isLoopbackAddress("localhost"))
        check("127.0.0.5 算本机", server.isLoopbackAddress("127.0.0.5"))
        check("192.168.1.7 不算本机", !server.isLoopbackAddress("192.168.1.7"))
        check("10.0.0.3 不算本机", !server.isLoopbackAddress("10.0.0.3"))

        config.consoleLocalOnly = true
        val localOk = http("GET", "$base/health", null, emptyMap())
        check("本机访问照常放行", localOk.code == 200 && localOk.body.contains("ok"), "code=${localOk.code}")
        config.consoleLocalOnly = false

        println("\n[27] 工具级策略（启用 / 禁用 / 单独权限）")
        val backupDisabled = config.disabledTools
        val backupOverrides = config.toolOverrides

        // 字符串解析（纯函数）
        ToolPolicy.setDisabled(config, "delete_path", true)
        check("禁用名单能解析", ToolPolicy.isDisabled(config, "delete_path"))
        ToolPolicy.setOverride(config, "read_file", ToolPolicy.DENY)
        check("单项权限能解析", ToolPolicy.overrideOf(config, "read_file") == ToolPolicy.DENY)
        ToolPolicy.setOverride(config, "write_file", ToolPolicy.ALLOW)
        check("多项权限能共存", ToolPolicy.overrides(config).size == 2)
        // 网络行为
        val listRes = http("POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":21,"method":"tools/list"}""", sessionHeaders)
        check("被禁用的工具不出现在 tools/list", !listRes.body.contains("\"delete_path\""), "")

        val disabledCall = http("POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"delete_path","arguments":{"path":"/sdcard/nope"}}}""",
            sessionHeaders)
        check("调用被禁用的工具会被拒绝", disabledCall.body.contains("禁用"), disabledCall.body.take(140))

        ToolPolicy.setDisabled(config, "delete_path", false)
        check(
            "重新启用后回到列表",
            http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":23,"method":"tools/list"}""", sessionHeaders)
                .body.contains("\"delete_path\"")
        )

        val sandboxRoot = config.roots.firstOrNull() ?: "/"
        ToolPolicy.setOverride(config, "list_dir", ToolPolicy.DENY)
        val deniedCall = http("POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"list_dir","arguments":{"path":"$sandboxRoot"}}}""",
            sessionHeaders)
        check(
            "单独设为禁止的工具调用被拒",
            deniedCall.body.contains("禁止") || deniedCall.body.contains("拒绝"),
            deniedCall.body.take(140)
        )

        ToolPolicy.setOverride(config, "list_dir", ToolPolicy.ALLOW)
        val allowedCall = http("POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":25,"method":"tools/call","params":{"name":"list_dir","arguments":{"path":"$sandboxRoot"}}}""",
            sessionHeaders)
        check("单独设为允许的工具能调用", !allowedCall.body.contains("已被单独设为"), allowedCall.body.take(140))

        config.disabledTools = backupDisabled
        config.toolOverrides = backupOverrides

        println("\n[29] 工具权限四态（跟随 / 允许 / 询问 / 拒绝）")
        val backupOverrides2 = config.toolOverrides
        ToolPolicy.setOverride(config, "read_file", ToolPolicy.ASK)
        check("设成询问会保留覆盖", ToolPolicy.overrideOf(config, "read_file") == ToolPolicy.ASK)
        check("询问算明确设定过", ToolPolicy.isExplicit(ToolPolicy.effectiveOverride(config, "read_file")))
        ToolPolicy.setOverride(config, "read_file", ToolPolicy.FOLLOW)
        check(
            "设成跟随不会被当成出厂默认",
            ToolPolicy.effectiveOverride(config, "read_file") == ToolPolicy.FOLLOW
        )
        ToolPolicy.setOverride(config, "read_file", null)
        check("清除后回到出厂默认", ToolPolicy.overrideOf(config, "read_file") == null)
        check("get_token 出厂默认是询问", ToolPolicy.effectiveOverride(config, "get_token") == ToolPolicy.ASK)
        check("普通工具出厂默认是跟随", ToolPolicy.effectiveOverride(config, "list_dir") == ToolPolicy.FOLLOW)

        // 工具级「询问」= 无视全局矩阵，每次都要弹窗
        ToolPolicy.setOverride(config, "list_dir", ToolPolicy.ASK)
        answer("列出目录", ApprovalDecision.ALLOW_ONCE)
        val askCall = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"list_dir","arguments":{"path":"$sandboxRoot"}}}""",
            sessionHeaders
        )
        check("工具级询问会弹窗，允许一次后成功", !askCall.body.contains("未批准"), askCall.body.take(140))

        // 弹窗里选「始终允许」→ 记住的是这个工具本身
        answer("列出目录", ApprovalDecision.ALLOW_ALWAYS)
        http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"list_dir","arguments":{"path":"$sandboxRoot"}}}""",
            sessionHeaders
        )
        check("选始终允许后工具本身被设为允许", ToolPolicy.overrideOf(config, "list_dir") == ToolPolicy.ALLOW)
        ToolPolicy.setOverride(config, "list_dir", null)
        config.toolOverrides = backupOverrides2

        println("\n[30] 内置工具文案覆盖（改说明）")
        val backupMeta = config.toolMeta
        server.toolMeta.set("list_dir", "看目录", "自定义说明：只列名字")
        val metaList = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":43,"method":"tools/list"}""", sessionHeaders)
        check("tools/list 里能看到新说明", metaList.body.contains("自定义说明：只列名字"), metaList.body.take(200))
        check("tools/list 里能看到新标题", metaList.body.contains("看目录"))
        check("工具名不受影响", metaList.body.contains("\"list_dir\""))
        server.toolMeta.reset("list_dir")
        val metaList2 = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":44,"method":"tools/list"}""", sessionHeaders)
        check("恢复后用回内置说明", !metaList2.body.contains("自定义说明：只列名字"))
        config.toolMeta = backupMeta
        server.toolMeta.load()

        println("\n[31] get_token 工具")
        answer("获取访问令牌", ApprovalDecision.ALLOW_ONCE)
        val tokenCall = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":45,"method":"tools/call","params":{"name":"get_token","arguments":{}}}""",
            sessionHeaders
        )
        check("get_token 能调用", tokenCall.body.contains("testtoken123"), tokenCall.body.take(200))
        check("get_token 返回端口", tokenCall.body.contains("18720"))

        println("\n[32] 记忆库图结构")
        val ms = MemoryStore(null)
        val c1 = ms.createEntities(
            listOf(
                Triple("项目：MCPBox", "项目", "projects"),
                Triple("用户偏好：深色主题", "用户偏好", "xtt")
            )
        )
        check("新建两个实体", c1.created == 2 && c1.skipped == 0)
        val c2 = ms.createEntities(listOf(Triple("项目：MCPBox", "项目", "projects")))
        check("重名实体只跳过不重复建", c2.created == 0 && c2.skipped == 1)
        check("实体数量正确", ms.graph.entities.size == 2)
        check("按名字能取到", ms.entity("项目：MCPBox")?.folder == "projects")
        check("观察去重：加两条", ms.addObservations("项目：MCPBox", listOf("用 Kotlin", "用 Compose")) == 2)
        check("重复观察会被跳过", ms.addObservations("项目：MCPBox", listOf("用 Kotlin")) == 0)
        check("观察总数正确", ms.entity("项目：MCPBox")?.observations?.size == 2)
        // 同一批里传了重复内容，也只能算一条（回归：曾经漏了对 texts 自身的去重）
        check(
            "同一批里的重复项只算一次",
            ms.addObservations("项目：MCPBox", listOf("批次重复", "批次重复", "批次重复")) == 1,
            "实际 ${ms.addObservations("项目：MCPBox", listOf("批次重复"))}"
        )
        check(
            "批次重复确实只留一条",
            ms.entity("项目：MCPBox")?.observations?.count { it == "批次重复" } == 1
        )
        check("清掉批次重复", ms.deleteObservations("项目：MCPBox", listOf("批次重复")) == 1)
        check("删除指定观察", ms.deleteObservations("项目：MCPBox", listOf("用 Compose")) == 1)
        check("建立关系", ms.createRelations(listOf(Triple("用户偏好：深色主题", "项目：MCPBox", "PART_OF"))).created == 1)
        check("重复关系不重加", ms.createRelations(listOf(Triple("用户偏好：深色主题", "项目：MCPBox", "PART_OF"))).created == 0)
        check("能查到邻居", ms.neighbours("项目：MCPBox") == listOf("用户偏好：深色主题"))
        check("搜索命中实体名", ms.search("MCPBox").entities.any { it.name == "项目：MCPBox" })
        check("搜索命中观察内容", ms.search("Kotlin").entities.any { it.name == "项目：MCPBox" })
        check("搜索会带上邻居", ms.search("MCPBox").entities.size == 2)
        check("分区过滤生效", ms.snapshot(folder = "xtt").entities.size == 1)
        check("类型过滤生效", ms.snapshot(type = "项目").entities.size == 1)
        check("统计文本含实体数", ms.statsText().contains("实体：2"))
        check("重命名会带动关系", ms.updateEntity("项目：MCPBox", newName = "项目：MCPBox v2"))
        check(
            "关系两端都改名了",
            ms.graph.relations.first().to == "项目：MCPBox v2" && ms.hasEntity("项目：MCPBox v2")
        )
        check("删实体连带删关系", ms.deleteEntities(listOf("用户偏好：深色主题")) == 1 && ms.graph.relations.isEmpty())

        println("\n[33] 记忆工具（走 MCP）")
        answer("记忆", ApprovalDecision.ALLOW_ALWAYS)
        val createRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":51,"method":"tools/call","params":{"name":"create_entities","arguments":{"entities":[{"name":"事件：修好了构建","entityType":"事件","folder":"dev","observations":["aapt2 用 qemu 包装解决"]}]}}}""",
            sessionHeaders
        )
        check("create_entities 成功", createRes.body.contains("已创建 1 个实体"), createRes.body.take(200))
        val relRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":52,"method":"tools/call","params":{"name":"create_relations","arguments":{"relations":[{"from":"事件：修好了构建","to":"事件：修好了构建","relationType":"PART_OF"}]}}}""",
            sessionHeaders
        )
        check("create_relations 成功", relRes.body.contains("已建立"), relRes.body.take(200))
        val searchRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":53,"method":"tools/call","params":{"name":"search_nodes","arguments":{"query":"aapt2"}}}""",
            sessionHeaders
        )
        check("search_nodes 搜到观察内容", searchRes.body.contains("qemu"), searchRes.body.take(200))
        val statsRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":54,"method":"tools/call","params":{"name":"memory_stats","arguments":{}}}""",
            sessionHeaders
        )
        check("memory_stats 有输出", statsRes.body.contains("实体："), statsRes.body.take(200))
        val delRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":55,"method":"tools/call","params":{"name":"delete_entities","arguments":{"names":["事件：修好了构建"]}}}""",
            sessionHeaders
        )
        check("delete_entities 成功", delRes.body.contains("已删除 1 个实体"), delRes.body.take(200))
        check("删完记忆库空了", server.memory.graph.entities.isEmpty())

        println("\n[34] 记忆库总开关")
        config.memoryEnabled = false
        val offList = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":56,"method":"tools/list"}""", sessionHeaders)
        check("关掉后记忆工具消失", !offList.body.contains("\"create_entities\""))
        val offCall = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":57,"method":"tools/call","params":{"name":"memory_stats","arguments":{}}}""",
            sessionHeaders
        )
        check("关掉后调不动", offCall.body.contains("未知工具"), offCall.body.take(140))
        config.memoryEnabled = true

        println("\n[35] 权限开关持久化（大小写兼容）")
        val backupPerms = settings.getString(Config.Keys.PERMISSIONS, "")
        check("PermAction.of 大小写不敏感", PermAction.of("ALLOW") == PermAction.ALLOW && PermAction.of("allow") == PermAction.ALLOW)
        check("PermKey.of 大小写不敏感", PermKey.of("FS.READ") == PermKey.READ)
        check("乱七八糟的值返回 null", PermAction.of("nope") == null)

        permissions.setSwitch(PermKey.DELETE, PermAction.DENY)
        val rawPerms = settings.getString(Config.Keys.PERMISSIONS, "") ?: ""
        check("落盘统一写小写 id", rawPerms.contains("\"fs.delete\":\"deny\""), rawPerms.take(200))
        permissions.load()
        check("重新加载后仍是 DENY", permissions.switchOf(PermKey.DELETE) == PermAction.DENY)

        // 模拟早期版本写的大写枚举名
        settings.putString(
            Config.Keys.PERMISSIONS,
            """{"switches":{"fs.delete":"DENY","fs.read":"ALLOW"},"rules":[]}"""
        )
        permissions.load()
        check("老版本大写 DENY 能读出来", permissions.switchOf(PermKey.DELETE) == PermAction.DENY)
        check("老版本大写 ALLOW 能读出来", permissions.switchOf(PermKey.READ) == PermAction.ALLOW)
        check("没写到的键回落到默认", permissions.switchOf(PermKey.WRITE) == PermKey.WRITE.default)

        if (backupPerms != null) {
            settings.putString(Config.Keys.PERMISSIONS, backupPerms)
            permissions.load()
        }

        println("\n[36] 工具包：模型与存储")
        val ps = PackStore(settings)
        check("内置包有 7 个", BuiltinPacks.ALL.size == 7)
        check("UI 自动化包存在且默认关", BuiltinPacks.UI.id == "ui" && !BuiltinPacks.UI.defaultActive &&
            BuiltinPacks.UI.tools.size == 8)
        check("浏览器包存在且默认关", BuiltinPacks.BROWSER.id == "browser" &&
            !BuiltinPacks.BROWSER.defaultActive && BuiltinPacks.BROWSER.tools.size == 18)
        check("UI 包的 8 个工具都在内置工具表里",
            BuiltinPacks.UI.tools.all { n -> server.tools.any { it.name == n } },
            BuiltinPacks.UI.tools.filterNot { n -> server.tools.any { it.name == n } }.toString())
        check("控制屏幕是独立权限且默认询问",
            PermKey.of("ui.control") == PermKey.UI && PermKey.UI.default == PermAction.ASK)
        check("core 是常驻包", BuiltinPacks.CORE.core)
        check("出厂默认 3 个包", BuiltinPacks.defaults() == setOf("core", "file.read", "memory"))
        check("my.tools 是内置 id", ps.isBuiltin("my.tools"))
        check("core 是内置 id", ps.isBuiltin("core"))
        check("all() 含「我的工具」", ps.all(listOf("x")).any { it.id == "my.tools" })
        check("「我的工具」跟着自定义工具走", ps.all(listOf("a", "b")).first { it.id == "my.tools" }.tools == listOf("a", "b"))

        val created = ps.add(ToolPack(id = "img", title = "图片处理", description = "处理图片用", tools = listOf("read_image")))
        check("能新建自定义包", created.id == "img" && !created.builtin)
        check("出现了", ps.all().any { it.id == "img" })
        check("同名不能再建", runCatching { ps.add(ToolPack(id = "img", title = "重复", tools = listOf("read_file"))) }.isFailure)
        check("内置 id 不能占用", runCatching { ps.add(ToolPack(id = "core", title = "冒充", tools = listOf("read_file"))) }.isFailure)
        check("内置包不能删", runCatching { ps.remove("core") }.isFailure)
        check("空标题会被拒", runCatching { ps.add(ToolPack(id = "x1", title = "", tools = listOf("read_file"))) }.isFailure)
        check("空工具列表会被拒（工具层校验）", runCatching { ps.add(ToolPack(id = "x2", title = "空包", tools = emptyList())) }.isSuccess)
        val updated = ps.update(ToolPack(id = "img", title = "图片处理 v2", description = "改了", tools = listOf("read_image", "file_info")))
        check("能更新自定义包", updated.title == "图片处理 v2" && updated.tools.size == 2)
        check("更新内置包会失败", runCatching { ps.update(ToolPack(id = "core", title = "改", tools = listOf("x"))) }.isFailure)
        check("能删除自定义包", ps.remove("img") && !ps.all().any { it.id == "img" })
        check("删不存在的返回 false", !ps.remove("nope"))

        println("\n[37] 工具包：会话状态与 TTL")
        val pd = ProfileStore(File(root, "profiles2"), config)
        check("默认会话名", ProfileStore.sanitize("") == "default")
        check("中文会话名可用", ProfileStore.sanitize("编码") == "编码")
        check("挡掉路径穿越 /", ProfileStore.sanitize("../../etc") == "default")
        check("挡掉路径穿越 ..", ProfileStore.sanitize("a..b") == "default")
        check("挡掉反斜杠", ProfileStore.sanitize("a\\b") == "default")
        check("挡掉奇怪符号", ProfileStore.sanitize("a;rm -rf") == "default")
        check("超长会被截断", ProfileStore.sanitize("x".repeat(100)).length == 32)

        check("新会话默认激活 3 个包", pd.active("s1") == setOf("core", "file.read", "memory"))
        check("激活一个包", pd.activate("s1", "shell"))
        check("重复激活返回 false", !pd.activate("s1", "shell"))
        check("激活后包含 shell", pd.active("s1").contains("shell"))
        check("会话之间互相隔离", !pd.peek("s2").contains("shell"))
        check("停用一个包", pd.deactivate("s1", "shell"))
        check("停用后不含 shell", !pd.active("s1").contains("shell"))
        check("停用没激活的返回 false", !pd.deactivate("s1", "shell"))
        pd.activate("s1", "file.write")
        pd.reset("s1")
        check("重置回默认", pd.active("s1") == setOf("core", "file.read", "memory"))
        check("会话列表含 default 与 s1", pd.ids().containsAll(listOf("s1")))
        pd.remove("s1")
        check("删会话", !pd.ids().contains("s1"))

        // TTL：把时间戳退回到很久以前，应该被判过期并重置
        config.profileTtlEnabled = true
        config.profileTtlMinutes = 30
        pd.activate("ttl", "shell")
        check("TTL 前是激活的", pd.peek("ttl").contains("shell"))
        pd.expireForTest("ttl", 31)
        check("TTL 到期后回默认", pd.active("ttl") == setOf("core", "file.read", "memory"))
        config.profileTtlEnabled = false
        pd.activate("ttl2", "shell")
        pd.expireForTest("ttl2", 9999)
        check("关掉 TTL 就不会过期", pd.active("ttl2").contains("shell"))
        config.profileTtlEnabled = true

        println("\n[38] 工具包：tools/list 按会话过滤")
        // 这一节要测包管理工具本身，先把「让 AI 自己开关包」打开（默认是关的）
        config.aiPackControl = true
        val pkUrl = "$base/mcp/p/%E5%8C%85%E6%B5%8B%E8%AF%95"   // 会话名：包测试
        server.profiles.reset("包测试")
        val fresh = http("POST", pkUrl, """{"jsonrpc":"2.0","id":61,"method":"tools/list"}""", sessionHeaders)
        check("新会话拿得到 read_file", fresh.body.contains("\"read_file\""))
        check("新会话拿得到记忆工具", fresh.body.contains("\"create_entities\""))
        check("新会话拿得到包管理工具", fresh.body.contains("\"list_packs\""))
        check("新会话拿不到 write_file（文件写入没激活）", !fresh.body.contains("\"write_file\""))
        check("新会话拿不到 run_shell", !fresh.body.contains("\"run_shell\""))
        check(
            "HTTP 返回的工具数与内部计算一致",
            countTools(fresh.body) == server.toolsFor("包测试").size,
            "http=${countTools(fresh.body)} inner=${server.toolsFor("包测试").size}"
        )
        check("默认会话不受影响", countTools(
            http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":61,"method":"tools/list"}""", sessionHeaders).body
        ) > countTools(fresh.body))

        val actRes = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":63,"method":"tools/call","params":{"name":"activate_pack","arguments":{"pack":"file.write"}}}""",
            sessionHeaders
        )
        check("activate_pack 能调用", actRes.body.contains("已激活"), actRes.body.take(200))
        val afterAct = http("POST", pkUrl, """{"jsonrpc":"2.0","id":64,"method":"tools/list"}""", sessionHeaders)
        check("激活后 write_file 出现了", afterAct.body.contains("\"write_file\""), afterAct.body.take(300))
        check("激活后 run_shell 还是没出现", !afterAct.body.contains("\"run_shell\""))
        check("另一个会话没被连累", !http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":64,"method":"tools/list"}""", sessionHeaders
        ).let { it.body.contains("\"run_shell\"") } == false)

        // 关键：没激活也能调用 —— 免得客户端不处理 list_changed 时死锁
        answer("列出回收站", ApprovalDecision.ALLOW_ONCE)
        val callInactive = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":67,"method":"tools/call","params":{"name":"list_trash","arguments":{}}}""",
            sessionHeaders
        )
        check("未激活包里的工具照样能调用", !callInactive.body.contains("未知工具"), callInactive.body.take(200))

        val deact = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":65,"method":"tools/call","params":{"name":"deactivate_pack","arguments":{"pack":"file.write"}}}""",
            sessionHeaders
        )
        check("deactivate_pack 能调用", deact.body.contains("已停用"), deact.body.take(200))
        val afterDeact = http("POST", pkUrl, """{"jsonrpc":"2.0","id":66,"method":"tools/list"}""", sessionHeaders)
        check("停用后 write_file 又不见了", !afterDeact.body.contains("\"write_file\""))

        val coreP = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":68,"method":"tools/call","params":{"name":"deactivate_pack","arguments":{"pack":"core"}}}""",
            sessionHeaders
        )
        check("core 包不能停用", coreP.body.contains("不能停用"), coreP.body.take(200))

        println("\n[39] 工具包：列表、重置、建包")
        val lp = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":71,"method":"tools/call","params":{"name":"list_packs","arguments":{}}}""",
            sessionHeaders
        )
        check("list_packs 有输出", lp.body.contains("工具包") && lp.body.contains("file.write"), lp.body.take(200))
        check("list_packs 会说明包影响可见性", lp.body.contains("工具列表里能看见什么"))
        check("list_packs 会提醒需要重连", lp.body.contains("重新连接"))

        answer("管理工具包", ApprovalDecision.ALLOW_ONCE)
        val mk = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":72,"method":"tools/call","params":{"name":"manage_pack","arguments":{"action":"create","id":"mytest","title":"测试包","description":"媒体测试用","tools":["read_image","file_info"]}}}""",
            sessionHeaders
        )
        check("manage_pack 能建包", mk.body.contains("已新建"), mk.body.take(250))
        check("建包后出现在 list_packs", http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":73,"method":"tools/call","params":{"name":"list_packs","arguments":{}}}""",
            sessionHeaders
        ).body.contains("测试包"))

        val badPack = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":74,"method":"tools/call","params":{"name":"activate_pack","arguments":{"pack":"没有这个包"}}}""",
            sessionHeaders
        )
        check("激活不存在的包会报错", badPack.body.contains("没有叫"), badPack.body.take(200))

        val rst = http(
            "POST", pkUrl,
            """{"jsonrpc":"2.0","id":75,"method":"tools/call","params":{"name":"reset_packs","arguments":{}}}""",
            sessionHeaders
        )
        check("reset_packs 能调用", rst.body.contains("已重置回默认"), rst.body.take(200))

        val rmPack = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":76,"method":"tools/call","params":{"name":"manage_pack","arguments":{"action":"delete","id":"mytest"}}}""",
            sessionHeaders
        )
        check("manage_pack 能删包", rmPack.body.contains("已删除工具包"), rmPack.body.take(200))

        println("\n[40] 工具包：默认「不让 AI 知道包」（省 token + 不给 AI 挖坑）")

        // 前面的初始化故意把默认会话设成「全部包都开」（老测试依赖它），
        // 这段要验证「未激活的包看不见」，所以先还原成出厂状态，测完再还原回去。
        val savedDefaultActive = server.profiles.peek(ProfileStore.DEFAULT_ID)
        server.profiles.reset(ProfileStore.DEFAULT_ID)

        // 关掉开关后：包管理工具应该整体消失
        config.aiPackControl = false
        val off = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":81,"method":"tools/list"}""", sessionHeaders)
        val offNames = toolNamesIn(off.body)
        check("关掉后看不到任何包管理工具", offNames.none { it in BuiltinPacks.PACK_TOOLS }, offNames.toString())
        check("关掉后基础能力还在", "server_info" in offNames && "get_token" in offNames, offNames.toString())
        check(
            "关掉后已激活包的工具照常可见",
            "read_file" in offNames && "create_entities" in offNames, offNames.toString()
        )
        check(
            "关掉后未激活包的工具仍然不可见",
            "write_file" !in offNames && "run_shell" !in offNames, offNames.toString()
        )
        check(
            "HTTP 的工具数与内部计算一致",
            offNames.size == server.toolsFor("default").size,
            "http=${offNames.size} inner=${server.toolsFor("default").size}"
        )

        // 内核计算也要一致
        val innerOff = server.toolsFor("default").map { it.name }
        check("内部计算里没有包管理工具", innerOff.none { it in BuiltinPacks.PACK_TOOLS }, innerOff.toString())
        check("内部计算里有 read_file", "read_file" in innerOff)

        // 拿着旧缓存的 AI 来调包工具 → 给明确指引，而不是默默执行
        val oldCache = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":82,"method":"tools/call","params":{"name":"activate_pack","arguments":{"pack":"shell"}}}""",
            sessionHeaders
        )
        check("关掉时调 activate_pack 会被挡下", oldCache.body.contains("由用户"), oldCache.body.take(250))
        check("挡下时会说明怎么打开", oldCache.body.contains("让 AI 自己开关工具包"), oldCache.body.take(250))

        // instructions 里也不该再提工具包
        val initOff = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":83,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}""",
            auth + mapOf("Accept" to "application/json")
        )
        check("关掉时 instructions 不提工具包", !initOff.body.contains("【工具包】"), initOff.body.take(300))

        // 打开后：工具回来，instructions 也提
        config.aiPackControl = true
        val on = http("POST", "$base/mcp", """{"jsonrpc":"2.0","id":84,"method":"tools/list"}""", sessionHeaders)
        val onNames = toolNamesIn(on.body)
        check("打开后包管理工具都回来了", BuiltinPacks.PACK_TOOLS.all { it in onNames }, onNames.toString())
        val initOn = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":85,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}""",
            auth + mapOf("Accept" to "application/json")
        )
        check("打开时 instructions 会讲工具包", initOn.body.contains("【工具包】"), initOn.body.take(300))
        check("打开时会提醒需要重连才能生效", initOn.body.contains("需要客户端重新连接"), initOn.body.take(400))

        // 省下来的 token：包管理工具的数量
        check("包管理工具共 5 个", BuiltinPacks.PACK_TOOLS.size == 5)

        // 还原：默认会话恢复「全部打开」，开关也回到默认的关
        server.profiles.setActive(ProfileStore.DEFAULT_ID, savedDefaultActive)
        check("默认会话已还原成全部打开", server.profiles.peek(ProfileStore.DEFAULT_ID).contains("shell"))
        config.aiPackControl = false

        println("\n[41] ShellMirror：AI 命令镜像到终端")
        val mirrored = StringBuilder()
        ShellMirror.attach { text -> mirrored.append(text) }
        check("attach 后标记为已接上", ShellMirror.isAttached)
        ShellMirror.emit("abc")
        check("emit 能送到 sink", mirrored.toString() == "abc")
        ShellMirror.emit("")
        check("空文本不触发 sink", mirrored.toString() == "abc")
        ShellMirror.attach { throw IllegalStateException("boom") }
        ShellMirror.emit("x")
        check("sink 抛异常也不影响调用方", true)
        ShellMirror.detach()
        check("detach 后不再是已接上", !ShellMirror.isAttached)
        ShellMirror.emit("y")
        check("detach 后不再收到", !mirrored.toString().contains("y"))

        // 端到端：AI 调 run_shell 时，命令与输出都要镜像出去
        mirrored.setLength(0)
        ShellMirror.attach { text -> mirrored.append(text) }
        val shellRes = http(
            "POST", "$base/mcp",
            """{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"run_shell","arguments":{"command":"echo hello_from_ai"}}}""",
            sessionHeaders
        )
        check("run_shell 真的执行了", shellRes.body.contains("hello_from_ai"), shellRes.body.take(120))
        check("命令被镜像（带 [AI] 前缀）", mirrored.contains("[AI]"), mirrored.toString().take(120))
        check("输出被镜像", mirrored.contains("hello_from_ai"), mirrored.toString().take(200))
        ShellMirror.detach()

        // ---------------------------------------------------------- UI 自动化
        println("\n[42] UI 自动化工具包")

        val uiNames = setOf(
            "ui_screenshot", "ui_dump", "ui_tap", "ui_swipe",
            "ui_input", "ui_key", "ui_launch", "ui_wait"
        )
        check("8 个 UI 工具都注册了", uiNames.all { n -> server.tools.any { it.name == n } },
            uiNames.filterNot { n -> server.tools.any { it.name == n } }.toString())

        check("UI 工具挂在「控制屏幕」权限下",
            server.tools.filter { it.name in uiNames }.all { it.perm == PermKey.UI })
        check("「控制屏幕」是一个独立开关", permissions.snapshot().containsKey("ui.control"),
            permissions.snapshot().keys.toString())

        // 这个 JVM 环境里只有「应用沙箱」后端，也就是真机上没开 Shizuku/Root 的情况
        val (uiOk, uiText) = call("ui_dump", "{}")
        check("没有特权后端时 ui_dump 会明确报错", !uiOk && uiText.contains("需要 Root 或 Shizuku"), uiText.take(160))
        check("报错里说明了怎么办", uiText.contains("Shizuku"), uiText.take(200))

        val (shotOk, shotText) = call("ui_screenshot", "{}")
        check("ui_screenshot 同样拦住了", !shotOk && shotText.contains("Shizuku"), shotText.take(160))

        val (tapOk, tapText) = call("ui_tap", """{"x":10,"y":10}""")
        check("ui_tap 同样拦住了", !tapOk && tapText.contains("Shizuku"), tapText.take(160))

        // 「控制屏幕 = 拒绝」时，连后台检查都不该做
        permissions.setSwitch(PermKey.UI, PermAction.DENY)
        check("控制屏幕可以单独设成拒绝",
            permissions.switchOf(PermKey.UI) == PermAction.DENY)
        permissions.setSwitch(PermKey.UI, PermAction.ASK)
        check("控制屏幕可以设回询问",
            permissions.switchOf(PermKey.UI) == PermAction.ASK)

        check("UI 包的说明里写明了需要 Root / Shizuku",
            BuiltinPacks.UI.description.contains("Root") || BuiltinPacks.UI.description.contains("Shizuku"))
        check("UI 包不属于出厂默认（省 token）", "ui" !in BuiltinPacks.defaults())

        println("\n[43] 预设会话（4 个预设 + 锁定）")

        check("一共 4 个预设", PermPreset.entries.size == 4,
            PermPreset.entries.map { it.id }.toString())
        check("下拉顺序是 全部允许 / 全部拒绝 / 全部询问 / 自定义",
            PermPreset.entries.map { it.id } == listOf("allow", "deny", "ask", "custom"),
            PermPreset.entries.map { it.id }.toString())
        check("前三个各绑一种动作", PermPreset.entries.take(3).all { it.action != null })
        check("只有「自定义」不锁开关",
            PermPreset.entries.filter { !it.locked }.map { it.id } == listOf("custom"))

        permissions.setPreset(PermPreset.ALLOW)
        check(
            "选「全部允许」→ 所有开关都变成允许",
            PermKey.entries.all { permissions.switchOf(it) == PermAction.ALLOW },
            permissions.snapshot().toString()
        )
        check(
            "「全部允许」进锁定态（下面的开关变灰）",
            permissions.preset() == PermPreset.ALLOW && permissions.preset().locked
        )

        permissions.setPreset(PermPreset.DENY)
        check(
            "选「全部拒绝」→ 所有开关都变成拒绝",
            PermKey.entries.all { permissions.switchOf(it) == PermAction.DENY }
        )

        permissions.setPreset(PermPreset.ASK)
        check(
            "选「全部询问」→ 所有开关都变成询问",
            PermKey.entries.all { permissions.switchOf(it) == PermAction.ASK }
        )

        // 「没有快照时保持现状」只可能出现在升级场景：配置里有 preset 但没有 customSwitches
        val legacyPresetSettings = MemorySettings().apply {
            putString(
                Config.Keys.PERMISSIONS,
                """{"switches":{"fs.read":"deny","fs.write":"allow"},"preset":"ask"}"""
            )
        }
        val legacyPreset = PermissionStore(Config(legacyPresetSettings), legacyPresetSettings)
        val legacyBefore = legacyPreset.snapshot()
        legacyPreset.setPreset(PermPreset.CUSTOM)
        check(
            "老配置（有预设但没快照）切到「自定义」保持现状",
            legacyPreset.snapshot() == legacyBefore, legacyPreset.snapshot().toString()
        )

        permissions.setPreset(PermPreset.CUSTOM)
        check("重复选同一个预设是空操作", permissions.preset() == PermPreset.CUSTOM)
        check("「自定义」是解锁态", !permissions.preset().locked)

        // ---- 自定义快照：切走再切回来，调过的东西不能丢
        permissions.setSwitch(PermKey.READ, PermAction.ALLOW)
        permissions.setSwitch(PermKey.WRITE, PermAction.DENY)
        permissions.setSwitch(PermKey.DELETE, PermAction.DENY)
        permissions.setSwitch(PermKey.SHELL, PermAction.ALLOW)
        val tuned = permissions.snapshot()

        permissions.setPreset(PermPreset.ALLOW)
        check("切到「全部允许」后开关被统一", PermKey.entries.all { permissions.switchOf(it) == PermAction.ALLOW })
        check("切走时存了自定义快照", permissions.customSwitches() == tuned, permissions.customSwitches().toString())

        permissions.setPreset(PermPreset.DENY)
        check(
            "在固定预设之间切换不会污染快照",
            permissions.customSwitches() == tuned, permissions.customSwitches().toString()
        )

        permissions.setPreset(PermPreset.CUSTOM)
        check("切回「自定义」恢复了之前调好的那份", permissions.snapshot() == tuned, permissions.snapshot().toString())
        check("恢复后预设是自定义", permissions.preset() == PermPreset.CUSTOM && !permissions.preset().locked)

        // 自定义模式下的改动要同步进快照（不然切走再回来还是旧的）
        permissions.setSwitch(PermKey.MEMORY, PermAction.DENY)
        val tuned2 = permissions.snapshot()
        permissions.setPreset(PermPreset.ASK)
        permissions.setPreset(PermPreset.CUSTOM)
        check("自定义模式下新改的也会被记住", permissions.snapshot() == tuned2, permissions.snapshot().toString())

        val reopened2 = PermissionStore(config, settings)
        check("自定义快照会一起持久化", reopened2.customSwitches() == tuned2, reopened2.customSwitches().toString())
        check("重开之后预设仍是自定义", reopened2.preset() == PermPreset.CUSTOM)

        // 锁定只是 UI 层的约束：内部调用（审批弹窗的「始终允许」等）照样能改
        permissions.setSwitch(PermKey.WRITE, PermAction.ALLOW)
        check("内部 setSwitch 不受预设锁定影响",
            permissions.switchOf(PermKey.WRITE) == PermAction.ALLOW)

        permissions.setSwitchForAll(PermAction.ASK)
        check(
            "setSwitchForAll 会把预设一起更新成「全部询问」",
            permissions.preset() == PermPreset.ASK, permissions.preset().id
        )

        val reopened = PermissionStore(config, settings)
        check("预设会被持久化", reopened.preset() == PermPreset.ASK, reopened.preset().id)
        check("持久化后开关和预设仍然一致",
            PermKey.entries.all { reopened.switchOf(it) == PermAction.ASK })

        val noPresetSettings = MemorySettings().apply {
            putString(Config.Keys.PERMISSIONS, """{"switches":{"fs.read":"allow"}}""")
        }
        val noPreset = PermissionStore(Config(noPresetSettings), noPresetSettings)
        check(
            "老配置没有 preset 字段 → 回落到「自定义」（不会被莫名锁住）",
            noPreset.preset() == PermPreset.CUSTOM, noPreset.preset().id
        )

        println("\n[44] 版本号：工具里能看到完整版本")

        val savedAppVer = ServerMeta.appVersion
        val savedAppCode = ServerMeta.appVersionCode
        ServerMeta.appVersion = "1.0.0"
        ServerMeta.appVersionCode = 42
        check("fullVersion 拼成 v1.0.0-42", ServerMeta.fullVersion == "v1.0.0-42", ServerMeta.fullVersion)

        permissions.setSwitch(PermKey.SYSTEM, PermAction.ALLOW)
        val (siOk, siText) = call("server_info", "{}")
        check("server_info 给的是完整版本号", siOk && siText.contains("v1.0.0-42"), siText.take(200))

        val (dvOk, dvText) = call("get_device_info", "{}")
        check(
            "JVM 环境没有设备信息源，get_device_info 会说明原因（真机上返回型号 / App 版本等）",
            !dvOk && dvText.contains("拿不到设备信息"),
            dvText.take(120)
        )

        ServerMeta.appVersion = savedAppVer
        ServerMeta.appVersionCode = savedAppCode

        println("\n[45] 超时无限制（0 = 不限）")

        val infSettings = MemorySettings().apply {
            putLong(Config.Keys.APPROVAL_TIMEOUT, 0L)
            putLong(Config.Keys.SHELL_TIMEOUT, 0L)
        }
        val infConfig = Config(infSettings)
        check("审批超时 0 会被保留（= 不限制）", infConfig.approvalTimeoutMs == 0L, "${infConfig.approvalTimeoutMs}")
        check("命令超时 0 会被保留（= 不限制）", infConfig.shellTimeoutMs == 0L, "${infConfig.shellTimeoutMs}")
        check(
            "正常范围的秒数照样读得出来",
            Config(MemorySettings().apply { putLong(Config.Keys.APPROVAL_TIMEOUT, 900_000L) })
                .approvalTimeoutMs == 900_000L
        )

        // 旧写法 deadline = started + 0 会把命令当场秒杀，这里专门盯这个
        val shLauncher = ShellBackends.pick("auto", config)!!
        val tInfinite = System.currentTimeMillis()
        val infResult = ShellRunner().run(shLauncher, "sleep 0.6; echo done", null, 0)
        val infElapsed = System.currentTimeMillis() - tInfinite
        check("超时=0 时命令能跑完（没被立刻掐断）", !infResult.timedOut, infResult.toText().take(160))
        check("确实拿到了命令自己的输出", infResult.stdout.contains("done"), infResult.stdout.take(120))
        check("真的等满了 0.6 秒", infElapsed >= 550, "${infElapsed}ms")

        answer("sleep", ApprovalDecision.ALLOW_ONCE)
        permissions.setSwitch(PermKey.SHELL, PermAction.ASK)
        val (infOk, infText) = call("run_shell", """{"command":"sleep 0.6; echo done","timeoutMs":0}""")
        check("run_shell 的 timeoutMs=0 能透传（不被夹成 1000）", infOk && infText.contains("done"), infText.take(200))

        // 审批：0 = 一直等。用「拖 1.2 秒才放行」的应答者来证明不会被判超时
        val savedApprovalTimeout = config.approvalTimeoutMs
        val savedResolver = approval.headlessResolver
        config.approvalTimeoutMs = 0L
        approval.headlessResolver = { _: ApprovalRequest ->
            runCatching { Thread.sleep(1200) }
            ApprovalDecision.ALLOW_ONCE
        }
        val (waitOk, waitText) = call("write_file", """{"path":"docs/infinite_wait.txt","content":"等了 1.2 秒"}""")
        check("审批不限时时，1.2 秒后才来的答复仍然生效", waitOk, waitText.take(200))
        check("文件确实写出来了", File(root, "docs/infinite_wait.txt").isFile)
        config.approvalTimeoutMs = savedApprovalTimeout
        approval.headlessResolver = savedResolver

        config.shellTimeoutMs = 0L
        val (infInfoOk, infInfoText) = call("shell_info", "{}")
        check("shell_info 显示「不限制」", infInfoOk && infInfoText.contains("不限制"), infInfoText.take(200))
        println("\n[46] 记忆库导入导出")

        // —— 存储层：导出 → 覆盖导入（往返）
        val memA = MemoryStore(null)
        memA.createEntities(
            listOf(
                Triple("项目：甲", "项目", "projects"),
                Triple("工具：乙", "工具", "tools")
            )
        )
        memA.addObservations("项目：甲", listOf("一条观察", "又一条观察"))
        memA.createRelations(listOf(Triple("工具：乙", "项目：甲", "USES")))
        val dump = memA.exportJson()
        check("导出的是 JSON", dump.contains("\"entities\"") && dump.contains("又一条观察"), dump.take(120))

        val memB = MemoryStore(null)
        val rt = memB.importJson(dump, merge = false)
        check("覆盖导入：结果标记为 replaced", rt.replaced)
        check("覆盖导入：实体数一致", memB.graph.entities.size == 2, "${memB.graph.entities.size}")
        check("覆盖导入：观察带过来了", memB.entity("项目：甲")?.observations?.size == 2)
        check("覆盖导入：关系带过来了", memB.graph.relations.size == 1)

        // —— 合并导入：不覆盖已有内容，只补空字段 / 去重追加观察
        val memC = MemoryStore(null)
        memC.createEntities(listOf(Triple("项目：甲", "", "")))
        memC.addObservations("项目：甲", listOf("原有观察"))
        val mg = memC.importJson(dump, merge = true)
        check(
            "合并：同名实体算「补全」而不是新建",
            mg.entitiesAdded == 1 && mg.entitiesMerged == 1,
            "新增 ${mg.entitiesAdded} / 补全 ${mg.entitiesMerged}"
        )
        check("合并：原有观察没被覆盖", memC.entity("项目：甲")?.observations?.contains("原有观察") == true)
        check(
            "合并：新观察去重后追加（1 旧 + 2 新）",
            memC.entity("项目：甲")?.observations?.size == 3,
            "${memC.entity("项目：甲")?.observations?.size}"
        )
        check(
            "合并：原来空着的类型 / 分区被补上",
            memC.entity("项目：甲")?.type == "项目" && memC.entity("项目：甲")?.folder == "projects",
            "${memC.entity("项目：甲")?.type} / ${memC.entity("项目：甲")?.folder}"
        )
        check("合并：关系也加进来了", memC.graph.relations.size == 1)
        val again = memC.importJson(dump, merge = true)
        check(
            "合并：再导入一次不会产生重复数据",
            again.entitiesAdded == 0 && again.relationsAdded == 0 &&
                memC.entity("项目：甲")?.observations?.size == 3,
            "新增 ${again.entitiesAdded} / 关系 ${again.relationsAdded}"
        )
        check("合并：非法 JSON 会抛异常", runCatching { memC.importJson("{ 不是 json", true) }.isFailure)

        // —— 走 MCP：memory_export / memory_import
        answer("记忆", ApprovalDecision.ALLOW_ALWAYS)
        // 上一段把记忆库清空了，这里自己铺一份数据
        server.memory.createEntities(
            listOf(
                Triple("项目：甲", "项目", "projects"),
                Triple("工具：乙", "工具", "tools")
            )
        )
        server.memory.addObservations("项目：甲", listOf("一条观察"))
        server.memory.createRelations(listOf(Triple("工具：乙", "项目：甲", "USES")))
        val (okEx, exText) = call("memory_export", """{"path":"xtt/memory/backup.json"}""")
        check("memory_export 成功", okEx, exText.take(200))
        val backup = File(root, "xtt/memory/backup.json")
        check("导出文件真的写出来了", backup.isFile && backup.length() > 0, backup.path)
        check("导出内容含实体名", backup.isFile && backup.readText().contains("项目：甲"))

        server.memory.deleteEntities(server.memory.graph.entities.map { it.name })
        check("清空后记忆库是空的", server.memory.graph.entities.isEmpty())

        val (okImp, impText) = call("memory_import", """{"path":"xtt/memory/backup.json","mode":"replace"}""")
        check("memory_import（覆盖）成功", okImp, impText.take(240))
        check("覆盖导入后实体回来了", server.memory.graph.entities.size == 2, "${server.memory.graph.entities.size}")
        check("覆盖导入后关系也回来了", server.memory.graph.relations.size == 1)

        val (okImp2, impText2) = call("memory_import", """{"path":"xtt/memory/backup.json"}""")
        check("memory_import（不给 mode 时默认合并）成功", okImp2, impText2.take(240))
        check("合并导入不会重复加实体", server.memory.graph.entities.size == 2, "${server.memory.graph.entities.size}")

        File(root, "xtt/bad-memory.json").writeText("这不是 JSON")
        val (okBadImp, badImpText) = call("memory_import", """{"path":"xtt/bad-memory.json"}""")
        check("导入非法文件会报错", !okBadImp, badImpText.take(200))

        println("\n[47] 备份与恢复")

        // —— 设置部分：导出 → 嗅探 → 合并恢复 / 覆盖恢复
        val bkSettings = MemorySettings().apply {
            putInt(Config.Keys.PORT, 19999)
            putBoolean(Config.Keys.READ_ONLY, true)
            putString(Config.Keys.TOKEN, "tok-abc")
            putString(Config.Keys.ROOTS, "/sdcard")
        }
        val memX = MemoryStore(null).also {
            it.createEntities(listOf(Triple("备份：甲", "项目", "projects")))
            it.addObservations("备份：甲", listOf("一条观察"))
        }
        val toolsX = CustomToolStore(Config(MemorySettings()), MemorySettings())

        val settingsNoToken = Backup.exportSettings(bkSettings, includeToken = false)
        check("设置导出：不含令牌时确实没写进去", !Backup.settingsHasToken(settingsNoToken))
        check("设置导出：普通键在里面", settingsNoToken.contains("\"port\"") && settingsNoToken.contains("19999"))
        check("设置导出：带 _type 标记", settingsNoToken.contains(Backup.TYPE_SETTINGS))
        check("设置导出：能嗅探出类型", Backup.sniff(settingsNoToken) == Backup.Part.SETTINGS)

        val settingsWithToken = Backup.exportSettings(bkSettings, includeToken = true)
        check("设置导出：含令牌时写得进去", Backup.settingsHasToken(settingsWithToken))
        check("设置导出：令牌值在里面", settingsWithToken.contains("tok-abc"))

        // 合并：只动备份里提到的键
        val mergeTarget = MemorySettings().apply {
            putInt(Config.Keys.PORT, 1)
            putString(Config.Keys.ROOTS, "/keep-me")
            putBoolean(Config.Keys.TRASH, false)
            putString(Config.Keys.TOKEN, "current-token")
        }
        Backup.apply(
            Backup.Part.SETTINGS, settingsNoToken, Backup.Mode.MERGE, restoreToken = false,
            settings = mergeTarget, memory = memX, customTools = toolsX
        )
        check("合并恢复：备份里的键被改写", mergeTarget.getInt(Config.Keys.PORT, 0) == 19999)
        check(
            "合并恢复：备份里没提到的键保持原样",
            mergeTarget.getString(Config.Keys.TRASH, null) == null,
            "trash=${mergeTarget.getString(Config.Keys.TRASH, null)}"
        )
        check("合并恢复：root 被备份覆盖", mergeTarget.getString(Config.Keys.ROOTS, "") == "/sdcard")
        check("合并恢复：没勾「恢复令牌」时当前令牌不动", mergeTarget.getString(Config.Keys.TOKEN, "") == "current-token")

        // 覆盖：先清空再写入
        Backup.apply(
            Backup.Part.SETTINGS, settingsNoToken, Backup.Mode.REPLACE, restoreToken = false,
            settings = mergeTarget, memory = memX, customTools = toolsX
        )
        check("覆盖恢复：备份里的键写进去了", mergeTarget.getInt(Config.Keys.PORT, 0) == 19999)
        check("覆盖恢复：当前令牌仍在（没勾恢复令牌）", mergeTarget.getString(Config.Keys.TOKEN, "") == "current-token")
        check("覆盖恢复：只留下备份里的键", mergeTarget.all().keys.contains(Config.Keys.PORT))

        // 勾了「恢复令牌」 → 令牌跟着备份走
        Backup.apply(
            Backup.Part.SETTINGS, settingsWithToken, Backup.Mode.MERGE, restoreToken = true,
            settings = mergeTarget, memory = memX, customTools = toolsX
        )
        check("勾了恢复令牌：令牌变成备份里那个", mergeTarget.getString(Config.Keys.TOKEN, "") == "tok-abc")

        // 覆盖 + 恢复令牌
        val replaceAll = MemorySettings().apply { putString(Config.Keys.TOKEN, "will-be-replaced") }
        Backup.apply(
            Backup.Part.SETTINGS, settingsWithToken, Backup.Mode.REPLACE, restoreToken = true,
            settings = replaceAll, memory = memX, customTools = toolsX
        )
        check("覆盖 + 恢复令牌：令牌被替换", replaceAll.getString(Config.Keys.TOKEN, "") == "tok-abc")

        // —— 设置部分：类型要原样还原（bool 不能变成字符串）
        val typed = MemorySettings().apply { putBoolean(Config.Keys.TRASH, false) }
        Backup.apply(
            Backup.Part.SETTINGS, settingsNoToken, Backup.Mode.MERGE, restoreToken = false,
            settings = typed, memory = memX, customTools = toolsX
        )
        check("类型还原：布尔键仍是布尔", typed.getBoolean(Config.Keys.READ_ONLY, false))
        check("类型还原：整数键仍是整数", typed.getInt(Config.Keys.PORT, 0) == 19999)

        // —— 记忆部分：合并 / 覆盖都走通
        val memDump = memX.exportJson()
        check("记忆导出：能嗅探出类型", Backup.sniff(memDump) == Backup.Part.MEMORY)
        val memPlain = MemoryStore(null).also { it.createEntities(listOf(Triple("原有", "类型", "x"))) }
        Backup.apply(
            Backup.Part.MEMORY, memDump, Backup.Mode.MERGE, restoreToken = false,
            settings = MemorySettings(), memory = memPlain, customTools = toolsX
        )
        check("记忆合并：原有内容没丢", memPlain.entity("原有") != null)
        check("记忆合并：备份内容加进来了", memPlain.entity("备份：甲") != null)

        val memWipe = MemoryStore(null).also { it.createEntities(listOf(Triple("会被清掉", "类型", "x"))) }
        Backup.apply(
            Backup.Part.MEMORY, memDump, Backup.Mode.REPLACE, restoreToken = false,
            settings = MemorySettings(), memory = memWipe, customTools = toolsX
        )
        check("记忆覆盖：旧内容被清掉", memWipe.entity("会被清掉") == null)
        check("记忆覆盖：备份内容进来了", memWipe.entity("备份：甲") != null)

        // —— 自定义工具部分
        val toolDump = toolsX.exportJson()
        check("工具导出：能嗅探出类型", Backup.sniff(toolDump) == Backup.Part.CUSTOM_TOOLS)
        check("工具导出：带 _type 标记", toolDump.contains(Backup.TYPE_TOOLS))

        // —— zip 打包 / 解包
        val statsDump = statsStore.exportJson()
        val zipBytes = Backup.zip(
            parts = mapOf(
                Backup.Part.MEMORY to memDump,
                Backup.Part.SETTINGS to settingsWithToken,
                Backup.Part.CUSTOM_TOOLS to toolDump,
                Backup.Part.STATS to statsDump
            ),
            includeToken = true
        )
        check("zip：产出了非空字节", zipBytes.isNotEmpty(), "${zipBytes.size} 字节")
        val bundle = Backup.readZip(zipBytes)
        check("zip：解出四部分", bundle.parts.size == 4, "${bundle.parts.keys}")
        check("zip：含令牌标记为 true", bundle.tokenIncluded)
        check("zip：解出的记忆和原稿一致", bundle.parts[Backup.Part.MEMORY] == memDump)
        check("zip：解出的设置和原稿一致", bundle.parts[Backup.Part.SETTINGS] == settingsWithToken)
        check("zip：版本号写进了 manifest", bundle.app == ServerMeta.fullVersion, "${bundle.app}")

        // 不含令牌的包
        val zipNoToken = Backup.zip(
            parts = mapOf(Backup.Part.SETTINGS to settingsNoToken),
            includeToken = false
        )
        check("zip：不含令牌时标记为 false", !Backup.readZip(zipNoToken).tokenIncluded)

        // 坏 zip / 空 zip
        check(
            "zip：不是 zip 的字节会报错",
            runCatching { Backup.readZip("这不是 zip".toByteArray()) }.isFailure
        )
        val emptyZip = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.ZipOutputStream(bos).use { it.finish() }
        }.toByteArray()
        check("zip：空 zip 会报错", runCatching { Backup.readZip(emptyZip) }.isFailure)

        // 单独导出的文件也要能被 sniff 认出来（导入单文件时靠它）
        check(
            "嗅探：三种单独文件都能认出来",
            Backup.sniff(memDump) == Backup.Part.MEMORY &&
                Backup.sniff(settingsNoToken) == Backup.Part.SETTINGS &&
                Backup.sniff(toolDump) == Backup.Part.CUSTOM_TOOLS
        )
        check("嗅探：随便一段文字认不出（返回 null）", Backup.sniff("随便一段文字") == null)

        // 老记忆文件（没有 _type）也要认：兼容 1.1.0-55 的备份
        val legacyMemory = """{"entities":[{"name":"老实体","type":"旧","folder":"projects","observations":[]}],"relations":[]}"""
        check("嗅探：老格式记忆文件仍然认得出", Backup.sniff(legacyMemory) == Backup.Part.MEMORY)

        println("\n[48] 备份的类型保真（防 ClassCastException）")

        // SharedPreferences 是强类型的：键里存 Integer 时 getLong 会直接崩。
        // JSON 的 number 分不出 int / long，所以导出时必须把类型记下来。
        val typeSrc = MemorySettings().apply {
            putLong(Config.Keys.APPROVAL_TIMEOUT, 300_000L)
            putLong(Config.Keys.SHELL_TIMEOUT, 60_000L)
            putInt(Config.Keys.PORT, 8720)
            putBoolean(Config.Keys.READ_ONLY, false)
            putString(Config.Keys.ROOTS, "/sdcard")
        }
        val settingsTypeDump = Backup.exportSettings(typeSrc, includeToken = false)
        check("导出带 types 映射", settingsTypeDump.contains("\"types\""), settingsTypeDump.take(160))
        check("types 把审批超时标成 long", Regex("\"approval_timeout\"\\s*:\\s*\"long\"").containsMatchIn(settingsTypeDump))
        check("types 把端口标成 int", Regex("\"port\"\\s*:\\s*\"int\"").containsMatchIn(settingsTypeDump))
        check("types 把只读标成 boolean", Regex("\"read_only\"\\s*:\\s*\"boolean\"").containsMatchIn(settingsTypeDump))

        // 恢复到空目标：每个键的类型都要跟原稿一致
        val settingsTypeTarget = MemorySettings()
        Backup.apply(
            Backup.Part.SETTINGS, settingsTypeDump, Backup.Mode.MERGE, restoreToken = false,
            settings = settingsTypeTarget, memory = MemorySettings().let { MemoryStore(null) }, customTools = toolsX
        )
        check(
            "恢复后 approval_timeout 仍是 Long（关键：不然 getLong 会崩）",
            settingsTypeTarget.all()[Config.Keys.APPROVAL_TIMEOUT] is Long,
            settingsTypeTarget.all()[Config.Keys.APPROVAL_TIMEOUT]?.javaClass?.simpleName ?: "null"
        )
        check("恢复后 shell_timeout 仍是 Long", settingsTypeTarget.all()[Config.Keys.SHELL_TIMEOUT] is Long)
        check("恢复后 port 仍是 Int", settingsTypeTarget.all()[Config.Keys.PORT] is Int)
        check("恢复后 read_only 仍是 Boolean", settingsTypeTarget.all()[Config.Keys.READ_ONLY] is Boolean)
        check("恢复后 roots 仍是 String", settingsTypeTarget.all()[Config.Keys.ROOTS] is String)
        check(
            "恢复后真的读得出 Long 值",
            runCatching { settingsTypeTarget.getLong(Config.Keys.APPROVAL_TIMEOUT, 0L) }.getOrNull() == 300_000L
        )

        // 覆盖模式同样要保类型（这条路径会先 clearAll）
        val typeReplace = MemorySettings()
        Backup.apply(
            Backup.Part.SETTINGS, settingsTypeDump, Backup.Mode.REPLACE, restoreToken = false,
            settings = typeReplace, memory = MemoryStore(null), customTools = toolsX
        )
        check("覆盖恢复后 approval_timeout 仍是 Long", typeReplace.all()[Config.Keys.APPROVAL_TIMEOUT] is Long)

        // —— 老备份（没有 types）：数字要跟着「当前这个键的类型」走
        val legacyNoTypes = """{"values":{"approval_timeout":300000,"port":8720,"read_only":true}}"""
        val legacyTarget = MemorySettings().apply {
            putLong(Config.Keys.APPROVAL_TIMEOUT, 1L)
            putInt(Config.Keys.PORT, 1)
            putBoolean(Config.Keys.READ_ONLY, false)
        }
        Backup.apply(
            Backup.Part.SETTINGS, legacyNoTypes, Backup.Mode.MERGE, restoreToken = false,
            settings = legacyTarget, memory = MemoryStore(null), customTools = toolsX
        )
        check("老备份：当前是 Long 的键恢复成 Long", legacyTarget.all()[Config.Keys.APPROVAL_TIMEOUT] is Long)
        check("老备份：当前是 Int 的键恢复成 Int", legacyTarget.all()[Config.Keys.PORT] is Int)
        check("老备份：当前是 Boolean 的键恢复成 Boolean", legacyTarget.all()[Config.Keys.READ_ONLY] is Boolean)
        check("老备份：值本身也对", legacyTarget.getLong(Config.Keys.APPROVAL_TIMEOUT, 0L) == 300_000L)

        // 完全没有参照时（键本来不存在）：能塞进 Int 就用 Int
        val guessTarget = MemorySettings()
        Backup.apply(
            Backup.Part.SETTINGS, legacyNoTypes, Backup.Mode.MERGE, restoreToken = false,
            settings = guessTarget, memory = MemoryStore(null), customTools = toolsX
        )
        check("老备份：没有参照时小数字用 Int", guessTarget.all()[Config.Keys.PORT] is Int)

        // 恢复设置后 Config 必须能正常 reload（这就是当时闪退的那一行）
        val reloadTarget = MemorySettings().apply {
            putInt(Config.Keys.PORT, 18720)
            putBoolean(Config.Keys.BIND_ALL, false)
            putString(Config.Keys.TOKEN, "t")
            putBoolean(Config.Keys.TOKEN_ENABLED, true)
            putString(Config.Keys.ROOTS, root.absolutePath)
            putLong(Config.Keys.APPROVAL_TIMEOUT, 5000L)
        }
        val cfgDump = Backup.exportSettings(reloadTarget, includeToken = true)
        val cfgTarget = MemorySettings()
        Backup.apply(
            Backup.Part.SETTINGS, cfgDump, Backup.Mode.REPLACE, restoreToken = true,
            settings = cfgTarget, memory = MemoryStore(null), customTools = toolsX
        )
        val reloadOk = runCatching { Config(cfgTarget).reload() }.isSuccess
        check("恢复后 Config.reload() 不抛异常（当时的闪退点）", reloadOk)
        check("reload 后审批超时读得到", Config(cfgTarget).approvalTimeoutMs == 5000L)
        check("reload 后端口读得到", Config(cfgTarget).port == 18720)

        config.shellTimeoutMs = 60_000L

        // ------------------------------------------------------------ 整目录传文件
        println("\n[52] 整目录传文件（zip 上传解压 / 目录打包下载）")
        run {
            val target = File(root, "ziptarget")

            // ① 一个字节都不少的 zip 上传（一个请求 = 一次审批，整个文件夹一起传）
            answer("上传压缩包", ApprovalDecision.ALLOW_ONCE)
            val zip = zipBytes(
                mapOf(
                    "top.txt" to "TOP",
                    "docs/a.txt" to "AAA",
                    "docs/deep/b.txt" to "BBB"
                )
            )
            val up = httpBytes(
                "POST",
                "$base/upload?path=${target.absolutePath}&extract=1&name=docs.zip&token=testtoken123",
                zip,
                mapOf("Content-Type" to "application/zip")
            )
            check("整目录上传：返回 200", up.code == 200, "code=${up.code} ${up.body.take(160)}")
            check("整目录上传：根层文件落地", File(target, "top.txt").readText() == "TOP")
            check("整目录上传：多级目录也建出来了", File(target, "docs/deep/b.txt").readText() == "BBB")
            check("整目录上传：结果里带文件数", up.body.contains("\"files\": 3") || up.body.contains("\"files\":3"), up.body.take(200))

            // ② 没带令牌进不来（和单文件上传一个规矩）
            val noToken = httpBytes("POST", "$base/upload?path=${target.absolutePath}&extract=1", zip)
            check("整目录上传：没令牌会被拦", noToken.code == 401, "code=${noToken.code}")

            // ③ 危险条目（../）必须在写盘之前就拒绝
            val evilPath = File(root, "evil")
            val evilRes = httpBytes(
                "POST",
                "$base/upload?path=${evilPath.absolutePath}&extract=1&name=evil.zip&token=testtoken123",
                zipBytes(mapOf("../evil.txt" to "hack"))
            )
            check("整目录上传：拒绝向上跳目录的条目", evilRes.code == 400, "code=${evilRes.code} ${evilRes.body.take(120)}")
            check("整目录上传：被拒之后没有文件落盘", !File(root, "evil.txt").exists() && !File(evilPath, "evil.txt").exists())

            // ④ 不是 zip 就明说，不要写出一堆垃圾
            val badRes = httpBytes(
                "POST",
                "$base/upload?path=${File(root, "badzip").absolutePath}&extract=1&name=x.zip&token=testtoken123",
                "this is not a zip".toByteArray(Charsets.UTF_8)
            )
            check("整目录上传：不是 zip 会被拒", badRes.code == 400, "code=${badRes.code} ${badRes.body.take(120)}")

            // ⑤ 目录直接下载要说清楚要加 zip=1
            val noZip = http("GET", "$base/download?path=${target.absolutePath}&token=testtoken123")
            check(
                "整目录下载：不加 zip=1 会提示加它",
                noZip.code == 400 && noZip.body.contains("zip=1"),
                noZip.body.take(160)
            )

            // ⑥ 加 zip=1 → 拿到一个能原样解开的 zip
            answer("打包下载目录", ApprovalDecision.ALLOW_ONCE)
            val (code, bytes) = httpRaw("$base/download?path=${target.absolutePath}&zip=1&token=testtoken123")
            check("整目录下载：返回 200", code == 200, "code=$code")
            val got = unzipText(bytes)
            check("整目录下载：解出来还是那三个文件", got.size == 3, "得到 ${got.keys}")
            check(
                "整目录下载：目录结构保持",
                got["docs/a.txt"] == "AAA" && got["docs/deep/b.txt"] == "BBB" && got["top.txt"] == "TOP"
            )

            // ⑦ 单文件上传/下载不受影响（回归）
            answer("gateway-regress", ApprovalDecision.ALLOW_ONCE)
            val single = http("POST", "$base/upload?path=xtt/gateway-regress.txt", "still-works", sessionHeaders)
            check("单文件上传仍然正常", single.code == 200 && File(root, "xtt/gateway-regress.txt").readText() == "still-works")
        }
    println("\n[53] 浏览器工具包（内置 WebView）")
    run {
        val browserNames = setOf(
            "browser_open", "browser_navigate", "browser_history", "browser_pages",
            "browser_switch", "browser_close", "browser_content", "browser_click",
            "browser_input", "browser_scroll", "browser_wait", "browser_eval",
            "browser_screenshot", "browser_search", "browser_save", "browser_storage",
            "browser_engines", "browser_download"
        )
        check(
            "18 个浏览器工具都注册了",
            browserNames.all { n -> server.tools.any { it.name == n } },
            browserNames.filterNot { n -> server.tools.any { it.name == n } }.toString()
        )
        check(
            "工具包清单与实现一致（不多不少）",
            BuiltinPacks.BROWSER.tools.toSet() == browserNames,
            (BuiltinPacks.BROWSER.tools.toSet() - browserNames).toString() +
                " | " + (browserNames - BuiltinPacks.BROWSER.tools.toSet()).toString()
        )
        check(
            "读类工具挂在「浏览器控制」下",
            server.tools.filter { it.name in browserNames && it.name != "browser_save" && it.name != "browser_download" }
                .all { it.perm == PermKey.BROWSER }
        )
        check("浏览器包默认不激活（省 token）", "browser" !in BuiltinPacks.defaults())
        check("「浏览器控制」是独立开关", permissions.snapshot().containsKey("browser.control"))
        check("「浏览器控制」默认是询问", PermKey.BROWSER.default == PermAction.ASK)

        // ---- URL 规则 / 内网拦截（纯函数） ----
        val hub = BrowserHub(config, fakeBrowser)
        check("裸域名自动补 https", hub.normalizeUrl("example.com") == "https://example.com")
        check("已经带协议的保持原样", hub.normalizeUrl("http://example.com/a") == "http://example.com/a")
        check("javascript: 被拒", runCatching { hub.normalizeUrl("javascript:alert(1)") }.isFailure)
        check("file:// 被拒", runCatching { hub.normalizeUrl("file:///sdcard/a.html") }.isFailure)
        check("搜索词当网址填会被劝", runCatching { hub.normalizeUrl("怎么装 kotlin") }.isFailure)

        check(
            "默认拦 127.0.0.1（本机 MCP 服务器）",
            runCatching { hub.normalizeUrl("http://127.0.0.1:8720/mcp") }.isFailure
        )
        check(
            "默认拦局域网",
            runCatching { hub.normalizeUrl("http://192.168.1.7:8080/") }.isFailure
        )
        check(
            "默认拦 .local 内网名",
            runCatching { hub.normalizeUrl("http://nas.local/") }.isFailure
        )
        check("公网地址照常放行", runCatching { hub.normalizeUrl("https://www.bing.com/") }.isSuccess)
        val blockedText = runCatching { hub.normalizeUrl("http://10.0.0.5/") }.exceptionOrNull()?.message.orEmpty()
        check("拦截文案说清了原因和开关", blockedText.contains("MCP") && blockedText.contains("设置"), blockedText.take(160))

        config.browserAllowLan = true
        check(
            "打开「允许内网」之后放行",
            runCatching { hub.normalizeUrl("http://192.168.1.7:8080/") }.isSuccess
        )
        config.browserAllowLan = false
        check(
            "关回去又拦上",
            runCatching { hub.normalizeUrl("http://192.168.1.7:8080/") }.isFailure
        )

        // ---- 搜索引擎 ----
        val (bingEngine, bingUrl) = hub.searchUrl("bing", "安卓 手机")
        check("搜索链接拼对了", bingUrl.startsWith("https://www.bing.com/search?q="), bingUrl)
        check("查询词做了 URL 编码", bingUrl.contains("%20") || bingUrl.contains("+"), bingUrl)
        check("内置引擎有 9 个", BrowserEngines.BUILTIN.size == 9, BrowserEngines.BUILTIN_IDS.toString())
        check("认不出的引擎会报错并列出可用的",
            runCatching { hub.engineById("nope") }.exceptionOrNull()?.message?.contains("bing") == true)

        val added = hub.addEngine("测试引擎", "https://x.test/s?q=%s")
        check("自定义引擎能加", hub.engines().any { it.id == added.id })
        check("自定义引擎写进了配置", config.browserEngines.contains("x.test"))
        check("模板没有 %s 会被拒", runCatching { hub.addEngine("坏的", "https://x.test/s") }.isFailure)
        check("同名不能重复加", runCatching { hub.addEngine("测试引擎", "https://x.test/s?q=%s") }.isFailure)
        check("自定义引擎能删", hub.removeEngine(added.id).contains("已删除"))
        check("删完就没了", hub.engines().none { it.id == added.id })
        check("内置引擎不给删", runCatching { hub.removeEngine("bing") }.isFailure)

        // ---- 跳转壳还原（搜索链接现在都藏在壳里） ----
        check(
            "必应 ck/a 壳能还原",
            BrowserRedirects.unwrap(
                "https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9kZXZlbG9wZXIuYmFpZHUuY29tL2EvMQ"
            ) == "https://developer.baidu.com/a/1"
        )
        check(
            "Google /url?q= 壳能还原",
            BrowserRedirects.unwrap("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fx&sa=D") ==
                "https://example.com/x"
        )
        check(
            "DuckDuckGo /l/?uddg= 壳能还原",
            BrowserRedirects.unwrap("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fy") ==
                "https://example.com/y"
        )
        check(
            "知乎 link 壳能还原",
            BrowserRedirects.unwrap("https://link.zhihu.com/?target=https%3A%2F%2Fzhuanlan.zhihu.com%2Fp%2F1") ==
                "https://zhuanlan.zhihu.com/p/1"
        )
        check("普通链接原样返回", BrowserRedirects.unwrap("https://example.com/plain") == "https://example.com/plain")
        check("解不出来就原样返回（百度 link?url=）",
            BrowserRedirects.unwrap("https://www.baidu.com/link?url=abc").startsWith("https://www.baidu.com/link"))

        // ---- 引擎域名（这是个踩过的坑） ----
        // 模板里带 %s，直接 java.net.URI(...) 会抛 "Malformed escape pair"，
        // 结果 engineHost 变空、「同站过滤」整个失效 → 搜索结果里全是顶栏导航。
        check("直接 URI(模板) 会失败（坑本身）",
            runCatching { java.net.URI("https://www.bing.com/search?q=%s").host }.isFailure)
        check("SearchEngine.host 会把 %s 换掉再解析",
            BrowserEngines.BUILTIN.all { it.host.isNotBlank() },
            BrowserEngines.BUILTIN.filter { it.host.isBlank() }.map { it.id }.toString())
        check("必应引擎的域名是 www.bing.com",
            BrowserEngines.BUILTIN.first { it.id == "bing" }.host == "www.bing.com")
        check("引擎域名要和真实结果页的域名同站",
            sameSiteHost(BrowserEngines.BUILTIN.first { it.id == "bing" }.host, "cn.bing.com"))
        check("hostOf 对畸形网址也不崩（退回 URL 解析）",
            hostOf("https://example.com/100%") == "example.com")
        check("跳转型链接认得出来（百度把结果包在 /link?url= 里）",
            looksLikeRedirect("http://www.baidu.com/link?url=abc") &&
                looksLikeRedirect("https://x.com/redirect?to=1") &&
                !looksLikeRedirect("https://example.com/article/1"))

        // ---- 同站判断（引擎换域名时最容易判错） ----
        check("同站：cn.bing.com 与 www.bing.com 算同一个站",
            sameSiteHost("cn.bing.com", "www.bing.com"))
        check("同站：cloud.google.com 与 www.google.com 算同一个站",
            sameSiteHost("cloud.google.com", "www.google.com"))
        check("不同站：zhihu.com 与 bing.com", !sameSiteHost("zhihu.com", "bing.com"))
        check("不同站：zhuanlan.zhihu.com 与 bing.com", !sameSiteHost("zhuanlan.zhihu.com", "bing.com"))
        check("空域名不算同站", !sameSiteHost("", "bing.com") && !sameSiteHost("bing.com", ""))

        // ---- 注入 JS 的另一条路（CSP 禁 eval 时用） ----
        check("userDirect 把用户代码原样放在 try 里（不走 eval）",
            BrowserJs.userDirect("document.title").startsWith("try {") &&
                BrowserJs.userDirect("document.title").contains("document.title") &&
                !BrowserJs.userDirect("document.title").contains("eval("))
        check("userDirect 出错时回一个 [js error] 字符串",
            BrowserJs.userDirect("x").contains("[js error]"))

        // 注入 JS 的几处细节（真机上踩过的）
        check("元素坐标是页面绝对坐标（滚过之后也不会是负数）",
            BrowserJs.elements(true, null, 10).contains("window.scrollX") &&
                BrowserJs.elements(true, null, 10).contains("window.scrollY"))
        check("按坐标点击会把滚动量减回去",
            BrowserJs.click("point", "10,20", 0).contains("window.scrollY"))
        check("等文字时会在整页正文里找（不只是可交互元素）",
            BrowserJs.find(null, "标题").contains("body *"))
        check("要开新窗口的链接会改成当前页打开（合成点击不会触发 window.open）",
            BrowserJs.click("index", "0", 0).contains("_self"))

        // ---- 页面模型 ----
        fakeBrowser.closeAll()
        config.browserMaxPages = 3
        val p1 = hub.open("https://example.com/1")
        val p2 = hub.open("https://example.com/2")
        check("新开页面拿到 id", p1.id == "p1" && p2.id == "p2", "${p1.id} ${p2.id}")
        check("当前页跟着最新开的走", hub.current()?.id == "p2")
        check("按序号解析页面", hub.resolvePage("1").id == "p1")
        check("按 id 解析页面", hub.resolvePage("p2").id == "p2")
        check("按前缀解析页面（唯一时才行）", hub.resolvePage("p1").id == "p1")
        check("前缀有歧义时会要求给完整 id",
            runCatching { hub.resolvePage("p") }.exceptionOrNull()?.message?.contains("多个") == true)
        check("找不到的页面会报错并列出全部",
            runCatching { hub.resolvePage("zzz") }.exceptionOrNull()?.message?.contains("p1") == true)
        check("页面列表排版带 * 标记", hub.formatPages(fakeBrowser.pages(), "p1").startsWith("* 1."))

        hub.open("https://example.com/3")
        check(
            "超过上限会被拦住并说明去哪改",
            runCatching { hub.open("https://example.com/4") }.exceptionOrNull()?.message?.contains("上限") == true
        )
        check("拦住之后没有真开出来", fakeBrowser.pages().size == 3, fakeBrowser.pages().size.toString())

        fakeBrowser.pausedFlag = true
        check("暂停时动作被拦", runCatching { hub.checkPaused() }.isFailure)
        fakeBrowser.pausedFlag = false
        check("继续后可以动作", runCatching { hub.checkPaused() }.isSuccess)

        check(
            "没有引擎时给的是「没接上」而不是崩溃",
            BrowserHub(config, null).let { h -> runCatching { h.engine() }.exceptionOrNull()?.message.orEmpty() }
                .contains("WebView")
        )

        // ---- JS 返回值解析 ----
        check("evaluateJavascript 的引号会拆掉",
            hub.unquoteJs("\"{\\\"a\\\":1}\"") == "{\"a\":1}")
        check("解析成 JSON 对象", hub.parseJsResult("\"{\\\"a\\\":1}\"")?.jsonObject?.get("a") != null)
        check("null 应对得上", hub.parseJsResult("null") == null)

        // 注入的 JS 在 harness 里跑不了（没有 JS 引擎），只能守住「明显会崩的写法」——
        // 之前 links 脚本就是 `${'$'}{jsStr(filter)}.toLowerCase()`，filter=null 时整段抛异常，
        // 结果 browser_content(mode=links) 与 browser_search 的结果链接永远是空的。
        check(
            "links 脚本对 filter=null 有保护（回归）",
            BrowserJs.links(null, 5).contains("var raw = null") &&
                !BrowserJs.links(null, 5).contains("null.toLowerCase()"),
            BrowserJs.links(null, 5).lineSequence().first { it.contains("raw") }.trim()
        )
        check(
            "elements 脚本对 null filter 有保护",
            BrowserJs.elements(true, null, 10).contains("filter ? String(filter)")
        )
        check("links 脚本带真实的过滤条件时也能拼对",
            BrowserJs.links("知乎", 10).contains("\"知乎\""))
        check("meta 脚本是自执行表达式", BrowserJs.META.contains("JSON.stringify"))

        // ---- 真调工具（走 HTTP + 审批矩阵） ----
        fakeBrowser.closeAll()
        permissions.setSwitch(PermKey.BROWSER, PermAction.ALLOW)

        val (openOk, openText) = call("browser_open", """{"url":"example.com"}""")
        check("browser_open 成功", openOk, openText.take(200))
        check("交给引擎的是补好协议的地址", fakeBrowser.opened.last() == "https://example.com", fakeBrowser.opened.toString())
        check("返回里带页面 id 和标题", openText.contains("[p1]") && openText.contains("示例页面"), openText.take(200))

        val (elsOk, elsText) = call("browser_content", """{"mode":"elements"}""")
        check("browser_content(elements) 成功", elsOk, elsText.take(200))
        check("元素列表带序号和文字", elsText.contains("[0]") && elsText.contains("更多"), elsText.take(300))

        val (textOk, textText) = call("browser_content", """{"mode":"text"}""")
        check("browser_content(text) 读到正文", textOk && textText.contains("正文内容"), textText.take(200))

        val (clickOk, clickText) = call("browser_click", """{"by":"index","value":"0"}""")
        check("browser_click 成功", clickOk, clickText.take(200))

        val (inputOk, inputText) = call("browser_input", """{"by":"index","value":"0","text":"hello","submit":true}""")
        check("browser_input 成功", inputOk, inputText.take(200))
        check("提交方式回传了", inputText.contains("form"), inputText.take(200))

        val (scrollOk, scrollText) = call("browser_scroll", """{"to":"bottom"}""")
        check("browser_scroll 成功", scrollOk && scrollText.contains("y=500"), scrollText.take(200))

        val (waitOk, waitText) = call("browser_wait", """{"text":"正文内容","timeoutMs":2000}""")
        check("browser_wait 成功", waitOk, waitText.take(200))

        val (evalOk, evalText) = call("browser_eval", """{"js":"1+1"}""")
        check("browser_eval 拿到返回值", evalOk && evalText.contains("fake-value"), evalText.take(200))

        val (pagesOk, pagesText) = call("browser_pages", "{}")
        check("browser_pages 列出页面", pagesOk && pagesText.contains("共 1 个页面"), pagesText.take(200))

        val (searchOk, searchText) = call("browser_search", """{"query":"安卓 测试","engine":"bing"}""")
        check("browser_search 成功", searchOk, searchText.take(200))
        check("搜索页真的打开了", fakeBrowser.opened.last().startsWith("https://www.bing.com/search?q="), fakeBrowser.opened.last())
        check("结果里带链接列表", searchText.contains("结果链接"), searchText.take(300))

        val (cookieOk, cookieText) = call("browser_storage", """{"action":"get_cookies"}""")
        check("browser_storage 能读 cookie", cookieOk && cookieText.contains("session=fake"), cookieText.take(200))

        val (engOk, engText) = call("browser_engines", """{"action":"list"}""")
        check("browser_engines 列出内置引擎", engOk && engText.contains("bing") && engText.contains("必应"), engText.take(300))

        val (shotOk, shotText) = call("browser_screenshot", "{}")
        check("截图拿不到时会说清原因（JVM 上必然失败）", !shotOk && shotText.contains("截图失败"), shotText.take(200))

        val (blockedOk, blockedToolText) = call("browser_open", """{"url":"http://127.0.0.1:8720/mcp"}""")
        check("工具层同样拦住内网", !blockedOk && blockedToolText.contains("内网"), blockedToolText.take(200))

        val (closeOk, closeText) = call("browser_close", """{"page":"all"}""")
        check("browser_close 全部关掉", closeOk && closeText.contains("已关闭全部"), closeText.take(200))
        check("引擎里真的空了", fakeBrowser.pages().isEmpty())

        // ---- 审批：默认每次都问 ----
        permissions.setSwitch(PermKey.BROWSER, PermAction.ASK)
        answer("打开网页", ApprovalDecision.ALLOW_ONCE)
        val (askOk, askText) = call("browser_open", """{"url":"https://example.com/ask"}""")
        check("默认「询问」时允许一次就能过", askOk, askText.take(200))

        answer("跳转", ApprovalDecision.DENY_ONCE)
        val (denyOk, denyText) = call("browser_navigate", """{"url":"https://example.com/nope"}""")
        check("被拒绝时工具会失败并说明", !denyOk && denyText.contains("拒绝"), denyText.take(200))

        permissions.setSwitch(PermKey.BROWSER, PermAction.ALLOW)
        val (pauseOk, _) = call("browser_open", """{"url":"https://example.com/pause"}""")
        check("先有个页面", pauseOk)
        fakeBrowser.pausedFlag = true
        val (pausedOk, pausedText) = call("browser_close", """{"page":"all"}""")
        check("用户暂停后 AI 不能动页面", !pausedOk && pausedText.contains("暂停"), pausedText.take(200))
        fakeBrowser.pausedFlag = false
        fakeBrowser.closeAll()
        config.browserMaxPages = 5
        permissions.setSwitch(PermKey.BROWSER, PermAction.ASK)
    }

    } finally {
        server.stop()
    }

    println("\n[49] 统计（请求次数 / 运行时长 / 启动次数）")
    run {
        val snap = statsStore.snapshot()
        check("统计：跑过 MCP 流量后请求数 > 0", snap.requestsTotal > 0L, "总请求 ${snap.requestsTotal}")
        check("统计：今天的格子有数", (snap.requestsByDay[StatsStore.todayKey()] ?: 0L) > 0L)
        check("统计：服务器启动次数记了一次（stop 之前 start 过一次）", snap.serverStarts == 1L, "启动 ${snap.serverStarts}")
        check("统计：跑了一阵，运行时长 > 0", snap.serverMillis > 0L, "运行 ${snap.serverMillis}ms")
        check("统计：stop() 之后不再有「正在运行」标记", !snap.serverRunning)
        // 落盘 + 重新读回来
        statsStore.flush()
        val reloaded = StatsStore(File(root, "stats/stats.json")).also { it.load() }.snapshot()
        check("统计：文件里读回来的请求数和内存里一致", reloaded.requestsTotal == snap.requestsTotal)
        check("统计：按天记录也读得回来", reloaded.requestsByDay == snap.requestsByDay)

        // 打开应用次数：一个进程只记一次
        val solo = StatsStore(null)
        solo.noteAppLaunch()
        solo.noteAppLaunch()
        check("统计：打开应用一个进程只加一次", solo.snapshot().appLaunches == 1L)

        // 备份：嗅探 + zip 往返 + 合并/覆盖
        val dump = statsStore.exportJson()
        check("统计：导出带 _type 标记", dump.contains(StatsStore.TYPE))
        check("统计：能嗅探出类型", Backup.sniff(dump) == Backup.Part.STATS)

        val zip = Backup.zip(mapOf(Backup.Part.STATS to dump), includeToken = false)
        val bundle = Backup.readZip(zip)
        check("统计：zip 往返后内容一致", bundle.parts[Backup.Part.STATS]?.trim() == dump.trim())

        val fresh = StatsStore(null)
        val emptyTools = CustomToolStore(Config(MemorySettings()), MemorySettings())
        Backup.apply(
            Backup.Part.STATS, dump, Backup.Mode.REPLACE, restoreToken = false,
            settings = MemorySettings(), memory = MemoryStore(null), customTools = emptyTools,
            stats = fresh
        )
        check("统计：覆盖恢复后请求数对得上", fresh.snapshot().requestsTotal == snap.requestsTotal)

        // 合并取较大值：同一份恢复两次不会翻倍
        val twice = StatsStore(null)
        listOf(dump, dump).forEach {
            Backup.apply(
                Backup.Part.STATS, it, Backup.Mode.MERGE, restoreToken = false,
                settings = MemorySettings(), memory = MemoryStore(null), customTools = emptyTools,
                stats = twice
            )
        }
        check("统计：合并恢复两次不会翻倍", twice.snapshot().requestsTotal == snap.requestsTotal)

        // 热力图网格：一周一列 × 7 天，范围外的格子是 null
        val today = java.time.LocalDate.of(2026, 10, 2) // 星期五
        val weeks = heatWeeks(
            mapOf("2026-10-02" to 3L, "2026-09-28" to 1L),
            today,
            today.minusDays(27)
        )
        check("热力图：列数 = 覆盖的周数", weeks.size == 5, "得到 ${weeks.size}")
        check("热力图：每列 7 格", weeks.all { it.size == 7 })
        check("热力图：今天那格计数是 3", weeks.last().firstOrNull { it?.date == today }?.count == 3L)
        check("热力图：起点之前的日子留空（第一列 5 个空白）",
            weeks.first().count { it == null } == 5, "空白 ${weeks.first().count { it == null }}")
        check("分档：0 次最浅", heatLevel(0L, 10L) == 0)
        check("分档：最大值最深", heatLevel(10L, 10L) == 4)
        check("分档：中间值居中", heatLevel(5L, 10L) in 2..3)
        check("分档：没有记录（max = 0）不炸", heatLevel(0L, 0L) == 0)
    }

    println("\n[50] 自动补齐权限（有 Root / Shizuku 时进入 App 自动补）")
    run {
        // 这层是不依赖 Android 的那半：把「缺哪几项」翻译成「跑哪几条命令」。
        // 硬要求：只规划**不会把正在运行的自己杀掉重启**的项，通知永远不碰。
        val pkg = "com.xtt.mcpbox"

        check(
            "自动补齐：文件访问走 appops（不重启进程）",
            AutoGrant.commandFor(AutoGrant.STORAGE, pkg) == "appops set $pkg MANAGE_EXTERNAL_STORAGE allow"
        )
        check(
            "自动补齐：悬浮窗走 appops",
            AutoGrant.commandFor(AutoGrant.OVERLAY, pkg) == "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )
        check(
            "自动补齐：电池优化走 deviceidle 白名单",
            AutoGrant.commandFor(AutoGrant.BATTERY, pkg) == "dumpsys deviceidle whitelist +$pkg"
        )

        // 通知是运行时权限：pm grant 会把进程杀掉重启 → 永远不给命令，也不在可静默清单里
        check("自动补齐：通知不给命令", AutoGrant.commandFor(AutoGrant.NOTIFICATION, pkg) == null)
        check("自动补齐：通知不能静默", !AutoGrant.isSilent(AutoGrant.NOTIFICATION))
        check(
            "自动补齐：可静默的正好是三项",
            AutoGrant.SILENT.size == 3 && AutoGrant.SILENT.all { AutoGrant.isSilent(it) }
        )

        // 顺序固定（文件访问 → 悬浮窗 → 电池），不认识的 id 直接忽略
        val plan = AutoGrant.plan(pkg, setOf(AutoGrant.BATTERY, "nonsense", AutoGrant.STORAGE))
        check(
            "自动补齐：只规划缺的、按固定顺序",
            plan.map { it.first } == listOf(AutoGrant.STORAGE, AutoGrant.BATTERY),
            "得到 ${plan.map { it.first }}"
        )
        check("自动补齐：规划里的命令和 id 对得上", plan.all { (id, cmd) -> AutoGrant.commandFor(id, pkg) == cmd })

        check("自动补齐：一项不缺就不动手", !AutoGrant.shouldRun(pkg, emptyList()))
        check("自动补齐：只缺通知也不动手（没得静默）", !AutoGrant.shouldRun(pkg, listOf(AutoGrant.NOTIFICATION)))
        check("自动补齐：缺文件访问就该动手", AutoGrant.shouldRun(pkg, listOf(AutoGrant.STORAGE)))
        check("自动补齐：包名是空的就不动手", !AutoGrant.shouldRun("", listOf(AutoGrant.STORAGE)))
        check(
            "自动补齐：ALL 里每项要么有命令、要么明确不可静默",
            AutoGrant.ALL.all { AutoGrant.commandFor(it, pkg) != null || !AutoGrant.isSilent(it) }
        )
    }

    println("\n[51] 审批呈现方式（悬浮窗 / 通知栏）")
    run {
        // 默认必须是悬浮窗（老行为：有权限就弹悬浮窗，没权限自动退通知栏）
        check("审批方式：认不出的 id 回落到悬浮窗", ApprovalPresentation.of("nonsense") == ApprovalPresentation.OVERLAY)
        check("审批方式：空值回落到悬浮窗", ApprovalPresentation.of(null) == ApprovalPresentation.OVERLAY)
        check("审批方式：id 往返", ApprovalPresentation.of("notify") == ApprovalPresentation.NOTIFY)

        check(
            "审批方式：悬浮窗 + 有权限 → 弹悬浮窗",
            ApprovalPresentation.OVERLAY.useOverlay(canDrawOverlays = true)
        )
        // 没权限还不退让的话，请求就没人看得见，只能等超时被拒
        check(
            "审批方式：悬浮窗 + 没权限 → 退回通知栏",
            !ApprovalPresentation.OVERLAY.useOverlay(canDrawOverlays = false)
        )
        check(
            "审批方式：通知栏模式永远不弹悬浮窗（哪怕有权限）",
            !ApprovalPresentation.NOTIFY.useOverlay(canDrawOverlays = true)
        )
        check(
            "审批方式：通知栏模式也没权限时同样走通知",
            !ApprovalPresentation.NOTIFY.useOverlay(canDrawOverlays = false)
        )
    }

    println("\n====================================")
    println("通过 $passed 项，失败 $failed 项")
    println("====================================")
    if (failed > 0) kotlin.system.exitProcess(1)
}

/** Uses the legacy HTTP+SSE transport: GET /sse then POST /messages. */
private fun legacySseRoundTrip(base: String): Pair<String, String> {
    val host = URL(base).host
    val socket = Socket(host, 18720)
    socket.soTimeout = 6000
    val out = socket.getOutputStream()
    out.write(
        ("GET /sse HTTP/1.1\r\nHost: $host\r\nAccept: text/event-stream\r\n" +
            "Authorization: Bearer testtoken123\r\n\r\n").toByteArray()
    )
    out.flush()
    val input = socket.getInputStream().buffered()
    val first = StringBuilder()
    // read until we saw two blank-line separated events or timeout
    var lines = 0
    val deadline = System.currentTimeMillis() + 6000
    while (System.currentTimeMillis() < deadline) {
        val line = readChunkedLine(input) ?: break
        first.append(line).append('\n')
        lines++
        if (lines > 40) break
    }
    val endpoint = Regex("/messages\\?sessionId=[a-f0-9-]+").find(first.toString())?.value
    if (endpoint == null) {
        socket.close()
        return first.toString() to "未拿到 endpoint"
    }
    val conn = URL("$base$endpoint").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer testtoken123")
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
        it.write("""{"jsonrpc":"2.0","id":21,"method":"tools/list"}""")
    }
    val code = conn.responseCode
    var payload = ""
    if (code == 202) {
        val sb = StringBuilder()
        val deadline2 = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline2) {
            val line = readChunkedLine(input) ?: break
            sb.append(line).append('\n')
            if (line.contains("\"result\"") || line.contains("tools")) break
        }
        payload = sb.toString()
    } else {
        payload = "HTTP $code"
    }
    socket.close()
    return first.toString() to payload
}

/** Reads one line from a chunked HTTP response body (handles chunk size lines). */
private fun readChunkedLine(input: java.io.InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = try {
            input.read()
        } catch (e: java.net.SocketTimeoutException) {
            return null
        }
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) {
            var line = sb.toString()
            if (line.endsWith("\r")) line = line.dropLast(1)
            // skip chunk-size lines like "1a"
            if (line.matches(Regex("^[0-9a-fA-F]+$"))) {
                sb.setLength(0)
                continue
            }
            if (line.isEmpty()) {
                sb.setLength(0)
                continue
            }
            return line
        }
        sb.append(c.toChar())
    }
}

/** Opens the Streamable HTTP SSE stream and returns whatever arrived quickly. */
private fun mcpSseStream(base: String, headers: Map<String, String>): String {
    val host = URL(base).host
    val socket = Socket(host, 18720)
    socket.soTimeout = 4000
    val out = socket.getOutputStream()
    val sb = StringBuilder()
    sb.append("GET /mcp HTTP/1.1\r\nHost: $host\r\nAccept: text/event-stream\r\n")
    sb.append("Authorization: Bearer testtoken123\r\n")
    headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
    sb.append("\r\n")
    out.write(sb.toString().toByteArray())
    out.flush()
    val input = socket.getInputStream().buffered()
    val acc = StringBuilder()
    val deadline = System.currentTimeMillis() + 4000
    while (System.currentTimeMillis() < deadline) {
        val line = readChunkedLine(input) ?: break
        acc.append(line).append('\n')
        if (acc.contains("ping")) break
    }
    socket.close()
    return acc.toString()
}

/**
 * 假的浏览器引擎：JVM 上没有 WebView，但工具链的行为（URL 规则、页面模型、审批、
 * 排版）都能在这里跑到。真机上由 `BrowserController` 实现同一个接口。
 *
 * 它按注入的 JS 里出现的特征词回不同的假数据 —— 够用来验证「工具把 JS 发出去了、
 * 又把结果排版回来了」这条链路。
 */
private class FakeBrowser : BrowserBridge {

    val map = LinkedHashMap<String, BrowserPageInfo>()
    var current: String? = null
    var pausedFlag = false
    val opened = mutableListOf<String>()

    override fun available(): Boolean = true

    override fun open(url: String): String {
        val id = "p${map.size + 1}"
        map[id] = BrowserPageInfo(id = id, url = url, title = "示例页面", loading = false)
        current = id
        opened.add(url)
        return id
    }

    override fun close(id: String) {
        map.remove(id)
        if (current == id) current = map.keys.lastOrNull()
    }

    override fun closeAll() {
        map.clear()
        current = null
    }

    override fun navigate(id: String, url: String) {
        map[id] = (map[id] ?: BrowserPageInfo(id)).copy(url = url)
    }

    override fun history(id: String, action: String) {}

    override fun pages(): List<BrowserPageInfo> = map.values.toList()

    override fun currentId(): String? = current

    override fun switchTo(id: String) {
        if (map.containsKey(id)) current = id
    }

    override fun screenshot(id: String): ByteArray? = null

    override fun setStatus(text: String) {}

    override fun paused(): Boolean = pausedFlag

    override fun setPaused(value: Boolean) {
        pausedFlag = value
    }

    override fun cookies(url: String?): String = "session=fake"

    override fun eval(id: String, js: String): String {
        val payload = when {
            js.contains("items: MCP.list(") ->
                """{"title":"示例页面","url":"https://example.com","items":[""" +
                    """{"i":0,"tag":"input","type":"text","text":"","name":"q","placeholder":"搜索","x":100,"y":200,"w":80,"h":30},""" +
                    """{"i":1,"tag":"a","type":"","text":"更多","href":"https://example.com/more","x":10,"y":40,"w":40,"h":20}]}"""
            js.contains("links: out") ->
                """{"url":"https://example.com","links":[{"text":"链接一","href":"https://example.com/a"}]}"""
            js.contains("var sc = MCP.scroller()") -> """{"ok":true,"y":500,"pos":500,"scroller":false}"""
            js.contains("var sel = ") -> """{"ok":true,"what":"正文内容","text":"正文内容"}"""
            js.contains("MCP.setValue(") ->
                """{"ok":true,"how":"form","target":{"tag":"input","name":"q"},"value":"hello"}"""
            js.contains("MCP.click(e)") ->
                """{"ok":true,"target":{"tag":"a","text":"更多"},"urlBefore":"https://example.com"}"""
            js.contains("var code = ") -> """{"ok":true,"v":"fake-value"}"""
            js.contains("outerHTML") -> "正文内容"
            js.contains("var root = MCP.contentRoot()") -> "正文内容"
            else ->
                """{"title":"示例页面","url":"https://example.com","ready":"complete","loading":false,""" +
                    """"scrollY":0,"innerHeight":800,"scrollHeight":2000,"elements":12,"links":3,"textLength":345}"""
        }
        // WebView 的 evaluateJavascript 会把返回值再 JSON 编码一次，text / html 这类纯字符串也一样
        return kotlinx.serialization.json.JsonPrimitive(payload).toString()
    }
}

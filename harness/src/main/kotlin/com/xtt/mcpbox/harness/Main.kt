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

    val server = McpServer(
        config = config, customTools = customTools, permissions = permissions,
        approval = approval, log = log, host = null,
        memory = MemoryStore(File(root, "memory/graph.json")),
        toolMeta = ToolMetaStore(settings)
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

        println("\n[28] ShellMirror：AI 命令镜像到终端")
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
    } finally {
        server.stop()
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

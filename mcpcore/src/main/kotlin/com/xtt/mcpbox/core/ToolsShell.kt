// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Shell / 终端 / 自定义工具相关的工具。 */
object ToolsShell {

    private val runner = ShellRunner()

    fun specs(store: CustomToolStore): List<ToolSpec> = listOf(
        runShell(),
        shellInfo(),
        createTool(store),
        updateTool(store),
        deleteTool(store),
        listTools(store),
        exportTools(store),
        importTools(store)
    )

    /** 用户自定义的工具，动态出现在 tools/list 里。 */
    fun customSpecs(store: CustomToolStore): List<ToolSpec> =
        store.tools.filter { it.enabled }.map { specOf(it) }

    // ----------------------------------------------------------------- 通用执行

    private fun execute(
        ctx: CallContext,
        toolLabel: String,
        command: String,
        backendPref: String,
        cwd: String?,
        timeoutMs: Long,
        forCustom: Boolean
    ): ShellResult {
        val launcher = ShellBackends.pick(backendPref, ctx.config)
            ?: ctx.fail(
                "没有可用的 Shell 后端。\n" +
                    "应用沙箱后端应该总是可用；如果用 Shizuku，请先在 App 的「终端」页申请授权。"
            )
        val workdir = cwd?.takeIf { it.isNotBlank() }?.let {
            runCatching { ctx.sandbox.resolve(it).path }.getOrElse { ctx.fail(it.message ?: "工作目录不合法") }
        }
        ctx.guard(
            perm = PermKey.SHELL,
            path = workdir?.let { File(it) },
            summary = if (forCustom) "$toolLabel：$command" else "执行命令：$command",
            detail = buildString {
                append("后端：").append(launcher.label).append("（").append(launcher.uidLabel).append("）")
                if (workdir != null) append("\n工作目录：").append(workdir)
                append("\n超时：").append(timeoutMs / 1000).append(" 秒")
            },
            command = command,
            backend = launcher.id
        )
        // 镜像到 App 的终端页：让 AI 干的活也能看见
        ShellMirror.emit("\n[AI] \$ $command\n")
        val result = runner.run(launcher, command, workdir, timeoutMs)
        ShellMirror.emit(buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) append(result.stderr)
            if (result.timedOut) append("\n[超时，已中断]\n")
            if (result.truncated) append("\n[输出过长已截断]\n")
        })
        ctx.log.add(
            LogKind.REQUEST, tool = ctx.tool, path = workdir, client = ctx.client,
            ok = result.ok,
            message = (if (result.ok) "命令执行成功" else "命令失败（退出码 ${result.exitCode}）") +
                "：" + command.take(120),
            durationMs = result.durationMs
        )
        return result
    }

    // ---------------------------------------------------------------- run_shell

    private fun runShell() = ToolSpec(
        name = "run_shell",
        title = "执行终端命令",
        description = "在手机上执行一条 Shell 命令并返回输出（就像在 Termux 里敲命令）。" +
            "可以带上 cwd 指定工作目录。涉及危险操作（删除、改系统设置、安装应用等）时会在手机上弹窗审批；" +
            "用户可以选择「始终允许某条命令」以免每次都问。",
        perm = PermKey.SHELL,
        schema = Schema.obj(
            mapOf(
                "command" to Schema.str("要执行的命令，例如 ls -la /sdcard/Download 或 pm list packages"),
                "cwd" to Schema.str("工作目录（可选），必须在你允许的目录里"),
                "timeoutMs" to Schema.int("超时毫秒数，默认 60000", 60_000, 1_000, 1_800_000),
                "backend" to Schema.str("执行后端", "auto", listOf("auto", "shizuku", "root", "app"))
            ),
            listOf("command")
        )
    ) { ctx ->
        val command = ctx.args.str("command")?.trim().orEmpty()
        if (command.isBlank()) ctx.fail("命令不能为空")
        val timeout = ctx.args.longOr("timeoutMs", ctx.config.shellTimeoutMs)
            .coerceIn(1_000L, 1_800_000L)
        val result = execute(
            ctx = ctx,
            toolLabel = "执行命令",
            command = command,
            backendPref = ctx.args.strOr("backend", "auto"),
            cwd = ctx.args.str("cwd"),
            timeoutMs = timeout,
            forCustom = false
        )
        ToolResult(result.toText(), ok = result.ok)
    }

    // --------------------------------------------------------------- shell_info

    private fun shellInfo() = ToolSpec(
        name = "shell_info",
        title = "Shell 环境",
        description = "查看可用的命令执行后端（应用沙箱 / Root / Shizuku）、当前身份、环境变量和超时设置。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "查看 Shell 环境")
        val sb = StringBuilder()
        sb.append("Shell 后端\n")
        ShellBackends.all().forEach { l ->
            val ok = runCatching { l.isAvailable() }.getOrDefault(false)
            sb.append("  ").append(if (ok) "[可用] " else "[不可用] ")
                .append(l.label).append("（").append(l.id).append("） · ").append(l.uidLabel).append('\n')
            if (!ok) sb.append("         ").append(l.hint).append('\n')
        }
        val picked = ShellBackends.pick("auto", ctx.config)
        sb.append("当前 auto 会选中：").append(picked?.label ?: "无（只能做文件操作）").append('\n')
        sb.append("优先级设置：").append(ctx.config.shellPreference).append('\n')
        sb.append("\n环境\n")
        ShellEnv.build().forEach { (k, v) -> sb.append("  ").append(k).append('=').append(v).append('\n') }
        sb.append("  默认超时=").append(ctx.config.shellTimeoutMs / 1000).append(" 秒\n")
        sb.append("\n命令规则（按顺序匹配，第一条命中生效）\n")
        val rules = ctx.permissions.commandRules()
        if (rules.isEmpty()) {
            sb.append("  （无，所有命令都走「执行命令」权限开关：")
                .append(ctx.permissions.switchOf(PermKey.SHELL).label).append("）\n")
        } else {
            rules.forEach {
                sb.append("  ").append(it.target)
                    .append("  [").append(it.match).append("] → ").append(it.actionEnum.label).append('\n')
            }
        }
        val custom = ctx.customTools.tools
        sb.append("\n自定义工具：").append(custom.size).append(" 个")
        if (custom.isNotEmpty()) sb.append("（").append(custom.joinToString("、") { it.name }).append("）")
        ToolResult(sb.toString().trimEnd())
    }

    // ------------------------------------------------------------ 自定义工具 CRUD

    private fun parseParams(ctx: CallContext, key: String = "params"): List<CustomToolParam> {
        val arr = ctx.args.arr(key) ?: return emptyList()
        return runCatching {
            J.decodeFromJsonElement(ListSerializer(CustomToolParam.serializer()), arr)
        }.getOrElse { ctx.fail("params 格式不对，需要 [{name, type, description, required}]：${it.message}") }
    }

    private fun buildTool(ctx: CallContext, existing: CustomTool?): CustomTool {
        val name = (ctx.args.str("name") ?: existing?.name ?: "").trim()
        if (name.isBlank()) ctx.fail("缺少 name")
        val command = ctx.args.str("command") ?: existing?.command ?: ""
        return CustomTool(
            id = existing?.id ?: "",
            name = name,
            title = ctx.args.str("title") ?: existing?.title ?: name,
            description = ctx.args.str("description") ?: existing?.description ?: "",
            params = if (ctx.args.arr("params") != null) parseParams(ctx) else (existing?.params ?: emptyList()),
            kind = CustomTool.KIND_SHELL,
            command = command,
            cwd = ctx.args.str("cwd") ?: existing?.cwd ?: "",
            backend = ctx.args.str("backend") ?: existing?.backend ?: CustomTool.BACKEND_AUTO,
            timeoutMs = ctx.args.longOr("timeoutMs", existing?.timeoutMs ?: ctx.config.shellTimeoutMs),
            enabled = ctx.args.boolOr("enabled", existing?.enabled ?: true),
            createdAt = existing?.createdAt ?: 0L,
            runCount = existing?.runCount ?: 0L,
            note = ctx.args.str("note") ?: existing?.note ?: ""
        )
    }

    private fun createTool(store: CustomToolStore) = ToolSpec(
        name = "create_custom_tool",
        title = "创建自定义工具",
        description = "给自己造一个新的 MCP 工具：填好名称、说明、参数和命令模板，之后它就会出现在 tools/list 里。" +
            "命令模板里用 {{参数名}} 插入参数（会做 shell 转义），{{参数名:raw}} 表示原样插入。" +
            "例：name=disk_usage, command=\"du -sh {{path}}\", params=[{name:path,type:string,required:true}]",
        perm = PermKey.TOOLS,
        schema = Schema.obj(
            mapOf(
                "name" to Schema.str("工具名（小写字母开头，可含数字和下划线），例：disk_usage"),
                "title" to Schema.str("简短中文标题"),
                "description" to Schema.str("给 AI 看的说明：这个工具做什么、参数怎么填"),
                "command" to Schema.str("命令模板，例：du -sh {{path}}"),
                "params" to jo(
                    "type" to "array",
                    "description" to "参数列表：[{name, type(string|integer|boolean), description, required, default}]",
                    "items" to jo(
                        "type" to "object",
                        "properties" to jo(
                            "name" to jo("type" to "string"),
                            "type" to jo("type" to "string"),
                            "description" to jo("type" to "string"),
                            "required" to jo("type" to "boolean"),
                            "default" to jo("type" to "string")
                        )
                    )
                ),
                "cwd" to Schema.str("工作目录（可选）"),
                "backend" to Schema.str("执行后端", "auto", listOf("auto", "shizuku", "root", "app")),
                "timeoutMs" to Schema.int("超时毫秒", 60_000, 1_000, 1_800_000),
                "note" to Schema.str("备注（可选）")
            ),
            listOf("name", "command")
        )
    ) { ctx ->
        ctx.guard(PermKey.TOOLS, null, "创建自定义工具 ${ctx.args.str("name") ?: "?"}")
        val tool = buildTool(ctx, null)
        val created = store.add(tool)
        ToolResult(
            "已创建自定义工具：${created.name}\n" +
                "标题：${created.title}\n参数：${created.params.size} 个\n命令模板：${created.command}\n" +
                "现在开始，客户端 tools/list 里就会出现它。"
        )
    }

    private fun updateTool(store: CustomToolStore) = ToolSpec(
        name = "update_custom_tool",
        title = "修改自定义工具",
        description = "修改已有自定义工具（按 name 定位，没传的字段保持原样）。",
        perm = PermKey.TOOLS,
        schema = Schema.obj(
            mapOf(
                "name" to Schema.str("要修改的工具名"),
                "title" to Schema.str("新的标题"),
                "description" to Schema.str("新的说明"),
                "command" to Schema.str("新的命令模板"),
                "params" to jo("type" to "array", "items" to jo("type" to "object")),
                "cwd" to Schema.str("工作目录"),
                "backend" to Schema.str("执行后端"),
                "timeoutMs" to Schema.int("超时毫秒"),
                "enabled" to Schema.bool("是否启用", true)
            ),
            listOf("name")
        )
    ) { ctx ->
        val name = ctx.args.str("name") ?: ctx.fail("缺少 name")
        val existing = store.byName(name) ?: ctx.fail("找不到工具：$name")
        ctx.guard(PermKey.TOOLS, null, "修改自定义工具 $name")
        val updated = store.update(buildTool(ctx, existing))
        ToolResult("已更新 ${updated.name}\n命令模板：${updated.command}")
    }

    private fun deleteTool(store: CustomToolStore) = ToolSpec(
        name = "delete_custom_tool",
        title = "删除自定义工具",
        description = "删除一个自定义工具。",
        perm = PermKey.TOOLS,
        schema = Schema.obj(mapOf("name" to Schema.str("工具名")), listOf("name"))
    ) { ctx ->
        val name = ctx.args.str("name") ?: ctx.fail("缺少 name")
        ctx.guard(PermKey.TOOLS, null, "删除自定义工具 $name")
        if (!store.remove(name)) ctx.fail("找不到工具：$name")
        ToolResult("已删除自定义工具：$name")
    }

    private fun listTools(store: CustomToolStore) = ToolSpec(
        name = "list_custom_tools",
        title = "自定义工具列表",
        description = "列出所有自定义工具及其定义（可以据此修改或导出）。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "查看自定义工具")
        val list = store.tools
        if (list.isEmpty()) {
            return@ToolSpec ToolResult("还没有自定义工具。可以用 create_custom_tool 造一个。")
        }
        val sb = StringBuilder("自定义工具（${list.size} 个）\n")
        list.forEach { t ->
            sb.append("\n● ").append(t.name).append(if (t.enabled) "" else "（已停用）").append('\n')
            sb.append("  标题：").append(t.title).append('\n')
            if (t.description.isNotBlank()) sb.append("  说明：").append(t.description).append('\n')
            sb.append("  命令：").append(t.command).append('\n')
            if (t.params.isNotEmpty()) {
                sb.append("  参数：").append(
                    t.params.joinToString("、") { p ->
                        p.name + ":" + p.type + if (p.required) "(必填)" else ""
                    }
                ).append('\n')
            }
            if (t.cwd.isNotBlank()) sb.append("  工作目录：").append(t.cwd).append('\n')
            sb.append("  后端：").append(t.backend).append("　超时：").append(t.timeoutMs / 1000).append(" 秒")
            sb.append("　运行次数：").append(t.runCount).append('\n')
        }
        ToolResult(sb.toString().trimEnd())
    }

    // ------------------------------------------------------------ 导入 / 导出

    private fun exportTools(store: CustomToolStore) = ToolSpec(
        name = "export_custom_tools",
        title = "导出自定义工具",
        description = "把所有自定义工具导出成一个 JSON 文件（可以分享给别的设备，或用 import_custom_tools 再导回来）。" +
            "path 省略时导出到 根目录/xtt/mcp-tools.json。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf("path" to Schema.str("导出到的文件路径，省略则用 根目录/xtt/mcp-tools.json"))
        )
    ) { ctx ->
        val defaultPath = File(ctx.sandbox.primaryRoot(), "xtt/mcp-tools.json").path
        val file = ctx.path("path", default = defaultPath)
        if (file.isDirectory) ctx.fail("目标是一个目录：${file.path}")
        val json = store.exportJson()
        ctx.guard(
            PermKey.WRITE, file,
            "导出自定义工具（${store.tools.size} 个）到 ${file.name}",
            file.path,
            json.length.toLong()
        )
        file.parentFile?.mkdirs()
        file.writeText(json)
        ToolResult(
            "已导出 ${store.tools.size} 个自定义工具\n文件：${file.path}\n" +
                "大小：${ctx.sandbox.humanSize(file.length())}"
        )
    }

    private fun importTools(store: CustomToolStore) = ToolSpec(
        name = "import_custom_tools",
        title = "导入自定义工具",
        description = "从 JSON 文件导入自定义工具（export_custom_tools 导出的格式，或直接给一个工具数组）。" +
            "同名工具会被更新。replace=true 表示先清空现有的再导入。",
        perm = PermKey.TOOLS,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("JSON 文件路径"),
                "replace" to Schema.bool("是否先清空现有工具", false)
            ),
            listOf("path")
        )
    ) { ctx ->
        val file = ctx.path(mustExist = true)
        if (file.isDirectory) ctx.fail("这是一个目录：${file.path}")
        if (file.length() > 8L * 1024 * 1024) ctx.fail("文件太大（${ctx.sandbox.humanSize(file.length())}）")
        ctx.guard(
            PermKey.TOOLS, file,
            "导入自定义工具（${file.name}）",
            "文件：${file.path}\n大小：${ctx.sandbox.humanSize(file.length())}",
            file.length()
        )
        val result = store.importJson(file.readText(), ctx.args.boolOr("replace", false))
        ToolResult(result.message + "\n当前共 ${store.tools.size} 个自定义工具")
    }

    // ------------------------------------------------------------- 自定义工具执行

    private fun specOf(tool: CustomTool): ToolSpec = ToolSpec(
        name = tool.name,
        title = tool.title.ifBlank { tool.name },
        description = buildString {
            append(tool.description.ifBlank { "自定义工具" })
            append("\n（这是用户自定义的工具，实际执行命令：")
            append(tool.command)
            append("）")
        },
        perm = PermKey.SHELL,
        schema = ToolTemplate.schemaOf(tool),
        handler = { ctx ->
            val rendered = ToolTemplate.render(tool.command, ctx.args, tool.params)
            val result = execute(
                ctx = ctx,
                toolLabel = "自定义工具「${tool.title.ifBlank { tool.name }}」",
                command = rendered,
                backendPref = tool.backend,
                cwd = tool.cwd.takeIf { it.isNotBlank() },
                timeoutMs = tool.timeoutMs.coerceIn(1_000L, 1_800_000L),
                forCustom = true
            )
            runCatching { ctx.customTools.countRun(tool.id) }
            ToolResult(result.toText(), ok = result.ok)
        }
    )
}

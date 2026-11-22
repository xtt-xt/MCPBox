// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 把插件的 `tools[]` 声明变成 MCP 工具。
 *
 * 一个插件一条 `ToolSpec`，对外名 `<插件id>__<工具名>`。
 * 权限不写在工具上：每次调用真正过审批的是脚本实际用到的 host API
 * （读文件走 `fs.read`、跑命令走 `shell.exec`……），这里只挂一个「自定义工具」标签
 * 给界面显示。
 */
object ToolsPlugin {

    /** 一个插件同一时间只跑一个工具调用（文档 6.4：每插件 1 个任务）。 */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private val runner = ShellRunner()

    /**
     * 当前所有可用的插件工具。
     *
     * @param toolsCall 调其它工具的能力（`ctx.tools.call`）；不传 = 该 API 报「不支持」。
     * @param engineAvailable 引擎不可用时一个工具都不给（插件页会写「引擎不可用」）。
     */
    fun specs(
        store: PluginStore,
        runtime: PluginRuntime?,
        toolsCall: ((String, JsonObject) -> ToolResult?)? = null,
        engineAvailable: Boolean = runtime?.available() ?: false
    ): List<ToolSpec> {
        if (runtime == null || !engineAvailable) return emptyList()
        return store.active().flatMap { plugin ->
            plugin.tools().mapNotNull { decl -> specOf(plugin, decl, store, runtime, toolsCall) }
        }
    }

    private fun specOf(
        plugin: InstalledPlugin,
        decl: PluginToolDecl,
        store: PluginStore,
        runtime: PluginRuntime,
        toolsCall: ((String, JsonObject) -> ToolResult?)?
    ): ToolSpec = ToolSpec(
        name = plugin.manifest.externalToolName(decl.name),
        title = decl.title.ifBlank { decl.name },
        description = buildString {
            append(decl.description.trim())
            append("\n\n（")
            append(L("来自插件「%s」v%s").format(plugin.manifest.name, plugin.manifest.version))
            append("）")
        },
        perm = PermKey.TOOLS,
        schema = PluginSchema.of(decl),
        pluginId = plugin.id
    ) { ctx ->
        run(plugin, decl, store, runtime, toolsCall, ctx)
    }

    // ------------------------------------------------------------------ 执行

    private fun run(
        plugin: InstalledPlugin,
        decl: PluginToolDecl,
        store: PluginStore,
        runtime: PluginRuntime,
        toolsCall: ((String, JsonObject) -> ToolResult?)?,
        ctx: CallContext
    ): ToolResult {
        val display = L("插件「%s」的工具 %s").format(plugin.manifest.name, decl.name)
        val scriptFile = File(plugin.dir, decl.file)
        if (!scriptFile.isFile) {
            throw ToolFailure(
                L("%s 的脚本不存在：%s（插件文件被改动过？重新安装一次）").format(display, decl.file)
            )
        }
        val script = runCatching { scriptFile.readText() }.getOrElse {
            throw ToolFailure(L("读不出 %s：%s").format(decl.file, it.message ?: ""))
        }
        val args = fillDefaults(decl, ctx.args)
        val host = HostImpl(ctx, plugin, store, args, toolsCall)

        val lock = locks.getOrPut(plugin.id) { ReentrantLock() }
        if (!lock.tryLock()) {
            throw ToolFailure(
                L("插件「%s」正忙（同时只能跑一个工具），等它跑完再试").format(plugin.manifest.name)
            )
        }
        try {
            val text = runtime.runTool(script, decl.file, host, decl.timeoutMs)
            return toResult(text)
        } catch (e: PluginTimeoutException) {
            throw ToolFailure(
                L("插件「%s」执行超时（%s 秒）").format(plugin.manifest.name, decl.timeoutMs / 1000)
            )
        } catch (e: PluginRuntimeException) {
            throw ToolFailure(
                L("插件「%s」执行失败：%s").format(plugin.manifest.name, e.message ?: "")
            )
        } finally {
            lock.unlock()
        }
    }

    /** 脚本返回值 → MCP 结果。`{ "error": "..." }` 算失败。 */
    private fun toResult(text: String): ToolResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "undefined" || trimmed == "null") {
            return ToolResult(L("（脚本没有返回内容）"))
        }
        if (trimmed.startsWith("{")) {
            val obj = runCatching { J.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            val err = obj?.get("error")
            if (err is JsonPrimitive && err.isString && err.content.isNotBlank()) {
                throw ToolFailure(err.content)
            }
        }
        return ToolResult(trimmed)
    }

    /** 按声明把默认值填进参数；必填项缺了直接报错。 */
    private fun fillDefaults(decl: PluginToolDecl, raw: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        decl.params.forEach { p ->
            val given = raw[p.name]
            when {
                given != null && given !is JsonNull -> out[p.name] = given
                p.default.isNotBlank() -> out[p.name] = paramDefault(p)
                p.required -> throw ToolFailure(L("缺少必填参数：%s").format(p.name))
            }
        }
        // 声明之外的参数也原样带上：脚本可能按需读
        raw.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    private fun paramDefault(p: PluginParamDecl): JsonElement = when (p.type) {
        "integer" -> JsonPrimitive(p.default.toIntOrNull() ?: 0)
        "number" -> JsonPrimitive(p.default.toDoubleOrNull() ?: 0.0)
        "boolean" -> JsonPrimitive(p.default.equals("true", ignoreCase = true))
        else -> JsonPrimitive(p.default)
    }

    // ------------------------------------------------------------------ host 实现

    /**
     * 把 host API 接到现有能力上：
     *  - 相对路径 → 插件自己的 data/，不审批，且出不去
     *  - 绝对路径 → [PathSandbox] + 权限矩阵
     *  - 命令 → 现有三种后端
     *  - 设置 → 插件的 config.json
     */
    private class HostImpl(
        private val ctx: CallContext,
        private val plugin: InstalledPlugin,
        private val store: PluginStore,
        override val args: JsonObject,
        private val toolsCall: ((String, JsonObject) -> ToolResult?)?
    ) : PluginHost {

        override val pluginId: String = plugin.id
        override val pluginVersion: String = plugin.manifest.version
        override val dataDirPath: String = store.dataDir(plugin.id).absolutePath

        private val dataRoot: File = File(dataDirPath)
        private val label: String get() = L("插件「%s」").format(plugin.manifest.name)

        // ------------------------------------------------------------ 设置

        override fun settingsGet(key: String): JsonElement? =
            store.settingValues(plugin.id)[key]

        override fun settingsSet(key: String, value: JsonElement) {
            store.setSetting(plugin.id, key, value)
        }

        // ------------------------------------------------------------ 文件

        override fun fsRead(path: String): String {
            if (!File(path).isAbsolute) {
                val f = insideData(path)
                if (!f.isFile) throw PluginRuntimeException(L("文件不存在：%s").format(path))
                return runCatching { f.readText() }.getOrElse {
                    throw PluginRuntimeException(L("读不出文件：%s").format(path))
                }
            }
            val f = ctx.sandbox.resolve(path, mustExist = true)
            ctx.guard(PermKey.READ, f, L("%s 读取文件").format(label) + "：" + f.path)
            return ctx.bridge.readText(f)
                ?: throw PluginRuntimeException(L("读不到文件：%s").format(path))
        }

        override fun fsWrite(path: String, text: String) {
            if (!File(path).isAbsolute) {
                val f = insideData(path)
                f.parentFile?.mkdirs()
                if (!runCatching { f.writeText(text) }.isSuccess) {
                    throw PluginRuntimeException(L("写不进文件：%s").format(path))
                }
                return
            }
            val f = ctx.sandbox.resolve(path)
            ctx.guard(
                PermKey.WRITE, f, L("%s 写入文件").format(label) + "：" + f.path,
                bytes = text.toByteArray().size.toLong(), mediaType = "text/plain"
            )
            if (!ctx.bridge.writeBytes(f, text.toByteArray())) {
                throw PluginRuntimeException(L("写不进文件：%s").format(path))
            }
        }

        override fun fsList(path: String): JsonElement {
            val dir = if (!File(path).isAbsolute) {
                insideData(path)
            } else {
                val f = ctx.sandbox.resolve(path, mustExist = true)
                ctx.guard(PermKey.READ, f, L("%s 列目录").format(label) + "：" + f.path)
                f
            }
            val entries = ctx.bridge.listDir(dir)
                ?: throw PluginRuntimeException(L("列不出目录：%s").format(path))
            return jarr(entries.map { e ->
                jo(
                    "name" to e.name, "path" to e.path, "dir" to e.dir,
                    "size" to e.size, "modified" to e.modified
                )
            })
        }

        override fun fsRemove(path: String) {
            if (!File(path).isAbsolute) {
                val f = insideData(path)
                if (!f.exists()) return
                if (!f.deleteRecursively()) {
                    throw PluginRuntimeException(L("删不掉：%s").format(path))
                }
                return
            }
            val f = ctx.sandbox.resolve(path, mustExist = true)
            ctx.guard(PermKey.DELETE, f, L("%s 删除").format(label) + "：" + f.path)
            if (!ctx.bridge.delete(f, recursive = true)) {
                throw PluginRuntimeException(L("删不掉：%s").format(path))
            }
        }

        // ------------------------------------------------------------ 命令

        override fun shellRun(command: String, cwd: String?, timeoutMs: Long): ShellResult {
            if (command.isBlank()) throw PluginRuntimeException(L("命令不能为空"))
            val launcher = ShellBackends.pick("auto", ctx.config)
                ?: throw PluginRuntimeException(L("没有可用的 Shell 后端"))
            val workdir = cwd?.takeIf { it.isNotBlank() }?.let {
                if (!File(it).isAbsolute) insideData(it)
                else ctx.sandbox.resolve(it)
            }
            ctx.guard(
                PermKey.SHELL,
                workdir,
                L("%s 执行命令").format(label) + "：" + command,
                detail = buildString {
                    append(L("后端：%s").format(launcher.label))
                    if (workdir != null) append("\n").append(L("工作目录：%s").format(workdir.path))
                    append("\n").append(L("超时：%s 秒").format(timeoutMs / 1000))
                },
                command = command,
                backend = launcher.id
            )
            ShellMirror.emit("\n[插件 ${plugin.id}] \$ $command\n")
            val result = runner.run(
                launcher, command, workdir?.path, timeoutMs.coerceIn(1_000L, 300_000L)
            )
            ShellMirror.emit(buildString {
                append(result.stdout)
                if (result.stderr.isNotBlank()) append(result.stderr)
            })
            return result
        }

        // ------------------------------------------------------------ 网络

        override fun httpRequest(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): PluginHttpResponse {
            throw PluginRuntimeException(
                L("这个版本的插件还不支持网络访问，等插件功能更新后再用")
            )
        }

        // ------------------------------------------------------------ 其它工具

        override fun callTool(name: String, args: JsonObject): ToolResult? =
            toolsCall?.invoke(name, args)

        // ------------------------------------------------------------ 日志

        private val logLock = Any()

        override fun log(level: String, message: String) {
            val file = store.logFile(plugin.id)
            val line = buildString {
                append(System.currentTimeMillis())
                append(" [")
                append(level.uppercase().take(8))
                append("] ")
                append(message.take(4000))
            }
            synchronized(logLock) {
                runCatching {
                    val lines = if (file.isFile) file.readLines() else emptyList()
                    val next = (lines + line).takeLast(MAX_LOG_LINES)
                    file.writeText(next.joinToString("\n"))
                }
            }
        }

        // ------------------------------------------------------------ 路径

        /** 相对路径只能落在插件自己的 data/ 里。 */
        private fun insideData(path: String): File {
            val base = dataRoot.canonicalFile
            val f = File(base, path).canonicalFile
            if (f != base && !f.path.startsWith(base.path + File.separator)) {
                throw PluginRuntimeException(
                    L("只能访问插件自己的数据目录（data/），不能访问 %s").format(path)
                )
            }
            return f
        }
    }

    const val MAX_LOG_LINES = 500
}

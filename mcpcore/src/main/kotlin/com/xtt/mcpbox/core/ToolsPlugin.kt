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
        network: PluginNetwork? = null,
        engineAvailable: Boolean = runtime?.available() ?: false
    ): List<ToolSpec> {
        if (runtime == null || !engineAvailable) return emptyList()
        return store.active().flatMap { plugin ->
            plugin.tools().mapNotNull { decl -> specOf(plugin, decl, store, runtime, toolsCall, network) }
        }
    }

    private fun specOf(
        plugin: InstalledPlugin,
        decl: PluginToolDecl,
        store: PluginStore,
        runtime: PluginRuntime,
        toolsCall: ((String, JsonObject) -> ToolResult?)?,
        network: PluginNetwork?
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
        run(plugin, decl, store, runtime, toolsCall, network, ctx)
    }

    // ------------------------------------------------------------------ 执行

    private fun run(
        plugin: InstalledPlugin,
        decl: PluginToolDecl,
        store: PluginStore,
        runtime: PluginRuntime,
        toolsCall: ((String, JsonObject) -> ToolResult?)?,
        network: PluginNetwork?,
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
        val toolName = plugin.manifest.externalToolName(decl.name)
        val host = PluginHostImpl(
            plugin = plugin,
            store = store,
            args = args,
            toolName = toolName,
            sandbox = ctx.sandbox,
            bridge = ctx.bridge,
            config = ctx.config,
            gate = object : PluginGate {
                override fun guard(
                    perm: PermKey,
                    path: File?,
                    summary: String,
                    detail: String?,
                    bytes: Long?,
                    mediaType: String?,
                    command: String?,
                    backend: String?
                ) = ctx.guard(perm, path, summary, detail, bytes, mediaType, command, backend)
            },
            network = network,
            toolsCall = toolsCall
        )

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

    const val MAX_LOG_LINES = 500
}

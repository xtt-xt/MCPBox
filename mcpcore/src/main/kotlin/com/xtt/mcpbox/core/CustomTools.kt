package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 自定义工具的一个参数。 */
@Serializable
data class CustomToolParam(
    val name: String = "",
    val type: String = "string", // string | integer | boolean
    val description: String = "",
    val required: Boolean = false,
    val default: String = ""
)

/**
 * 用户（或 AI 自己）定义的工具。
 * 目前 kind=shell：把命令模板里的 {{参数}} 替换后丢给 Shell 执行。
 */
@Serializable
data class CustomTool(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val params: List<CustomToolParam> = emptyList(),
    val kind: String = KIND_SHELL,
    val command: String = "",
    val cwd: String = "",
    val backend: String = BACKEND_AUTO,
    val timeoutMs: Long = 60_000,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val runCount: Long = 0,
    val note: String = ""
) {
    companion object {
        const val KIND_SHELL = "shell"
        const val BACKEND_AUTO = "auto"
    }
}

object ToolTemplate {

    /** 把 {{name}} 换成 shell 安全的字面量；{{name:raw}} 原样插入。 */
    fun render(template: String, args: JsonObject, params: List<CustomToolParam>): String {
        val provided = HashMap<String, String>()
        params.forEach { p ->
            val el = args[p.name]
            val value = when {
                el == null || el.toString() == "null" -> p.default
                el is JsonPrimitive -> el.content
                else -> el.toString()
            }
            provided[p.name] = value
        }
        val missing = params.filter { it.required && provided[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw ToolFailure("缺少必填参数：" + missing.joinToString("、") { it.name })
        }
        val regex = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(:\\s*raw\\s*)?}}")
        return regex.replace(template) { m ->
            val key = m.groupValues[1]
            val raw = m.groupValues[2].isNotEmpty()
            val value = provided[key]
                ?: throw ToolFailure("模板里用到了参数 {{$key}}，但它没有定义")
            if (raw) value else shellQuote(value)
        }
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 校验工具定义，返回错误信息（null = 通过）。 */
    fun validate(tool: CustomTool, others: List<CustomTool>): String? {
        if (!tool.name.matches(Regex("[a-z][a-z0-9_]{1,40}"))) {
            return "工具名要小写字母开头，只能包含小写字母/数字/下划线，长度 2-41"
        }
        if (others.any { it.name == tool.name && it.id != tool.id }) {
            return "已存在同名工具：${tool.name}"
        }
        if (tool.command.isBlank()) return "命令模板不能为空"
        val declared = tool.params.map { it.name }.toSet()
        val used = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)").findAll(tool.command)
            .map { it.groupValues[1] }.toSet()
        val undefined = used - declared
        if (undefined.isNotEmpty()) {
            return "命令里用到了未定义的参数：" + undefined.joinToString("、")
        }
        tool.params.forEach { p ->
            if (!p.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return "参数名不合法：${p.name}"
        }
        return null
    }

    fun schemaOf(tool: CustomTool): JsonObject = Schema.obj(
        props = tool.params.associate { p ->
            p.name to when (p.type) {
                "integer" -> Schema.int(p.description.ifBlank { p.name })
                "boolean" -> Schema.bool(p.description.ifBlank { p.name })
                else -> Schema.str(
                    (p.description.ifBlank { p.name }) +
                        if (p.default.isNotBlank()) "（默认 ${p.default}）" else ""
                )
            }
        },
        required = tool.params.filter { it.required }.map { it.name }
    )
}

/** 自定义工具的存储（跟着权限一起放在 SharedPreferences 的 JSON 里）。 */
class CustomToolStore(private val config: Config, private val src: SettingsSource) {

    @Volatile var tools: List<CustomTool> = emptyList()
        private set

    init { load() }

    fun load() {
        val raw = src.getString(Config.Keys.CUSTOM_TOOLS, null)
        if (raw.isNullOrBlank()) {
            tools = emptyList()
            return
        }
        tools = runCatching {
            val el = J.parseToJsonElement(raw)
            J.decodeFromJsonElement(ListSerializer(CustomTool.serializer()), el)
        }.getOrElse { emptyList() }
    }

    fun persist() {
        val el = J.encodeToJsonElement(ListSerializer(CustomTool.serializer()), tools)
        src.putString(Config.Keys.CUSTOM_TOOLS, el.toString())
    }

    fun byName(name: String): CustomTool? = tools.firstOrNull { it.name == name }

    fun byId(id: String): CustomTool? = tools.firstOrNull { it.id == id }

    fun add(tool: CustomTool): CustomTool {
        ToolTemplate.validate(tool, tools)?.let { throw ToolFailure(it) }
        val created = tool.copy(
            id = tool.id.ifBlank { Tokens.newId().take(8) },
            createdAt = if (tool.createdAt == 0L) System.currentTimeMillis() else tool.createdAt
        )
        tools = tools + created
        persist()
        return created
    }

    fun update(tool: CustomTool): CustomTool {
        val existing = tools.firstOrNull { it.id == tool.id || it.name == tool.name }
            ?: throw ToolFailure("找不到工具：${tool.name}")
        val merged = tool.copy(id = existing.id, createdAt = existing.createdAt)
        ToolTemplate.validate(merged, tools)?.let { throw ToolFailure(it) }
        tools = tools.map { if (it.id == existing.id) merged else it }
        persist()
        return merged
    }

    fun remove(idOrName: String): Boolean {
        val existing = tools.firstOrNull { it.id == idOrName || it.name == idOrName } ?: return false
        tools = tools.filterNot { it.id == existing.id }
        persist()
        return true
    }

    fun countRun(id: String) {
        tools = tools.map { if (it.id == id) it.copy(runCount = it.runCount + 1) else it }
        persist()
    }

    fun exportJson(): String {
        val el = J.encodeToJsonElement(ListSerializer(CustomTool.serializer()), tools)
        return jo(
            "version" to 1,
            "exportedAt" to System.currentTimeMillis(),
            "note" to "MCP 文件盒 · 自定义工具导出",
            "tools" to el
        ).let { J_PRETTY.encodeToString(JsonObject.serializer(), it) }
    }

    data class ImportResult(val added: Int, val updated: Int, val skipped: Int, val message: String)

    fun importJson(text: String, replace: Boolean = false): ImportResult {
        val el = runCatching { J.parseToJsonElement(text) }.getOrElse {
            throw ToolFailure("不是合法的 JSON：${it.message}")
        }
        val arr = when {
            el is kotlinx.serialization.json.JsonArray -> el
            el is JsonObject && el["tools"] != null -> el["tools"] as? kotlinx.serialization.json.JsonArray
                ?: throw ToolFailure("JSON 里 tools 字段不是数组")
            else -> throw ToolFailure("JSON 结构不对，需要数组或 {\"tools\":[...]}")
        }
        val incoming = arr.mapNotNull { item ->
            runCatching { J.decodeFromJsonElement(CustomTool.serializer(), item) }.getOrNull()
        }
        if (incoming.isEmpty()) throw ToolFailure("没有解析出任何工具")
        var added = 0
        var updated = 0
        var skipped = 0
        var list = if (replace) emptyList() else tools
        incoming.forEach { t ->
            val err = ToolTemplate.validate(t.copy(id = t.id.ifBlank { Tokens.newId().take(8) }), list)
            if (err != null && list.none { it.name == t.name }) {
                skipped++
                return@forEach
            }
            val same = list.firstOrNull { it.name == t.name }
            if (same != null) {
                list = list.map { if (it.id == same.id) t.copy(id = same.id, createdAt = same.createdAt) else it }
                updated++
            } else {
                list = list + t.copy(
                    id = t.id.ifBlank { Tokens.newId().take(8) },
                    createdAt = t.createdAt.takeIf { it != 0L } ?: System.currentTimeMillis()
                )
                added++
            }
        }
        tools = list
        persist()
        return ImportResult(
            added = added, updated = updated, skipped = skipped,
            message = "导入完成：新增 $added，更新 $updated" + if (skipped > 0) "，跳过 $skipped" else ""
        )
    }

    companion object {
        private val J_PRETTY = kotlinx.serialization.json.Json { prettyPrint = true }
    }
}

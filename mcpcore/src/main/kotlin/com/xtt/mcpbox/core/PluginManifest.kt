// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 插件相关的错误：manifest 不合规、目录缺失、版本不满足……消息直接给用户看。 */
class PluginException(message: String) : Exception(message)

// ------------------------------------------------------------------ plugin.json 数据模型

/** 设置项声明。界面由 App 自动渲染，插件作者不写 UI。 */
@Serializable
data class PluginSettingDecl(
    val key: String = "",
    /** string / secret / integer / number / boolean / enum */
    val type: String = TYPE_STRING,
    val title: String = "",
    val description: String = "",
    /** 统一按字符串存，读的时候按类型转换；空 = 没有默认值。 */
    val default: String = "",
    /** type = enum 时的候选值。 */
    val options: List<String> = emptyList()
) {
    companion object {
        const val TYPE_STRING = "string"
        const val TYPE_SECRET = "secret"
        const val TYPE_INTEGER = "integer"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_ENUM = "enum"

        val TYPES = setOf(TYPE_STRING, TYPE_SECRET, TYPE_INTEGER, TYPE_NUMBER, TYPE_BOOLEAN, TYPE_ENUM)
    }
}

/** 工具参数声明。 */
@Serializable
data class PluginParamDecl(
    val name: String = "",
    /** string / integer / number / boolean / enum */
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val default: String = "",
    val options: List<String> = emptyList()
) {
    companion object {
        val TYPES = setOf("string", "integer", "number", "boolean", "enum")
    }
}

/** 一个工具声明。 */
@Serializable
data class PluginToolDecl(
    val name: String = "",
    val title: String = "",
    val description: String = "",
    /** 相对插件目录的脚本路径，必须在 tools/ 下。 */
    val file: String = "",
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val params: List<PluginParamDecl> = emptyList()
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 300_000L
    }
}

/** 一条 HTTP 路由。P2 才会真正分派，P1 只做校验。 */
@Serializable
data class PluginRouteDecl(
    val path: String = "",
    /** 相对插件目录的脚本路径，必须在 api/ 下。 */
    val handler: String = "",
    val methods: List<String> = listOf("GET"),
    /** key（插件自己的 Key）/ none（只有绑 loopback 时可用） */
    val auth: String = "key",
    /** 允许把这次响应升级成 chunked / SSE 流。 */
    val stream: Boolean = false
)

@Serializable
data class PluginKeyDecl(
    val label: String = "API Key",
    /** 生成 Key 时加的前缀，好让用户一眼认出这是干什么的（如 sk-）。 */
    val prefix: String = ""
)

@Serializable
data class PluginHttpDecl(
    val enabledByDefault: Boolean = false,
    /** loopback（只监听 127.0.0.1）/ lan（绑 0.0.0.0） */
    val bind: String = BIND_LOOPBACK,
    val routes: List<PluginRouteDecl> = emptyList(),
    val key: PluginKeyDecl = PluginKeyDecl()
) {
    companion object {
        const val BIND_LOOPBACK = "loopback"
        const val BIND_LAN = "lan"
    }
}

/** 版本要求。 */
@Serializable
data class PluginRequires(
    /** 形如 >=1.7.0 / ^1.7.0 / ~1.7.0 / 1.7.0；空 = 不限制。 */
    val mcpbox: String = "",
    /** 插件 API 版本；0 = 不限制。 */
    val api: Int = 0
)

/** plugin.json 的完整内容。 */
@Serializable
data class PluginManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val permissions: List<String> = emptyList(),
    val settings: List<PluginSettingDecl> = emptyList(),
    val tools: List<PluginToolDecl> = emptyList(),
    val http: PluginHttpDecl? = null,
    val requires: PluginRequires? = null
) {

    /** 对外注册的工具名前缀：`<插件id>__`。 */
    val toolPrefix: String get() = "${id}__"

    /** 插件工具在 MCP 里的完整名字。 */
    fun externalToolName(tool: String): String = toolPrefix + tool

    companion object {
        const val MAX_EXTERNAL_TOOL_NAME = 64
        const val ID_PATTERN = "[a-z][a-z0-9_]{1,31}"
        const val SETTING_KEY_PATTERN = "[a-z][a-z0-9_]*"
        const val TOOL_NAME_PATTERN = "[a-z][a-z0-9_]{0,40}"
        const val PARAM_NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*"
        const val MAX_NAME = 40
        const val MAX_DESCRIPTION = 200
        const val MAX_TOOL_DESCRIPTION = 4000
    }
}

// ------------------------------------------------------------------ 解析与校验

object PluginManifestParser {

    /** 当前 MCPBox 支持的插件 API 版本。 */
    const val API_VERSION = 1

    /** 插件不许占用的路径前缀（MCPBox 自己用的）。 */
    val RESERVED_PREFIXES = listOf(
        "/mcp", "/sse", "/messages", "/api", "/web", "/upload", "/download", "/console"
    )

    /**
     * 解析 plugin.json 正文。
     *
     * 只做「能解析 + 结构对」；跨插件冲突（工具重名、路由重名）由
     * [PluginStore.install] 检查，因为那里才有别的插件的信息。
     */
    fun parse(text: String): PluginManifest {
        val el = runCatching { J.parseToJsonElement(text) }.getOrElse {
            throw PluginException(L("plugin.json 不是合法的 JSON：%s").format(it.message ?: ""))
        }
        val obj = el as? JsonObject
            ?: throw PluginException(L("plugin.json 的最外层必须是一个对象"))
        val manifest = runCatching {
            J.decodeFromJsonElement(PluginManifest.serializer(), obj)
        }.getOrElse {
            throw PluginException(L("plugin.json 字段类型不对：%s").format(it.message ?: ""))
        }
        validate(manifest)
        return manifest
    }

    /** 结构校验，不通过直接抛 [PluginException]。 */
    fun validate(m: PluginManifest) {
        // ---- 基本信息
        if (!m.id.matches(Regex(PluginManifest.ID_PATTERN))) {
            throw PluginException(
                L("插件 id「%s」不合法：要小写字母开头，只能包含小写字母、数字、下划线，长度 2-32")
                    .format(m.id)
            )
        }
        if (m.name.isBlank()) throw PluginException(L("插件名不能为空"))
        if (m.name.length > PluginManifest.MAX_NAME) {
            throw PluginException(L("插件名太长（最多 %s 字）").format(PluginManifest.MAX_NAME))
        }
        if (m.description.isBlank()) throw PluginException(L("插件说明不能为空"))
        if (m.description.length > PluginManifest.MAX_DESCRIPTION) {
            throw PluginException(L("插件说明太长（最多 %s 字）").format(PluginManifest.MAX_DESCRIPTION))
        }
        if (!m.version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
            throw PluginException(L("插件版本要写成 1.0.0 这样（当前是「%s」）").format(m.version))
        }

        // ---- 设置项
        val settingKeys = HashSet<String>()
        m.settings.forEach { s ->
            if (!s.key.matches(Regex(PluginManifest.SETTING_KEY_PATTERN))) {
                throw PluginException(
                    L("设置项 key「%s」不合法：要小写字母开头，只能包含小写字母、数字、下划线").format(s.key)
                )
            }
            if (!settingKeys.add(s.key)) throw PluginException(L("设置项 key 重复：%s").format(s.key))
            if (s.type !in PluginSettingDecl.TYPES) {
                throw PluginException(
                    L("设置项「%s」的类型「%s」不认识").format(s.key, s.type)
                )
            }
            if (s.type == PluginSettingDecl.TYPE_ENUM) {
                if (s.options.isEmpty()) {
                    throw PluginException(L("设置项「%s」是 enum，但没给 options").format(s.key))
                }
                if (s.default.isNotBlank() && s.default !in s.options) {
                    throw PluginException(
                        L("设置项「%s」的默认值「%s」不在 options 里").format(s.key, s.default)
                    )
                }
            }
            if (s.title.isBlank()) throw PluginException(L("设置项「%s」缺少 title").format(s.key))
        }

        // ---- 工具
        val toolNames = HashSet<String>()
        m.tools.forEach { t ->
            if (!t.name.matches(Regex(PluginManifest.TOOL_NAME_PATTERN))) {
                throw PluginException(
                    L("工具名「%s」不合法：要小写字母开头，只能包含小写字母、数字、下划线").format(t.name)
                )
            }
            if (!toolNames.add(t.name)) throw PluginException(L("工具名重复：%s").format(t.name))
            if (m.externalToolName(t.name).length > PluginManifest.MAX_EXTERNAL_TOOL_NAME) {
                throw PluginException(
                    L("工具「%s」对外注册的名字太长（超过 %s 字符）")
                        .format(t.name, PluginManifest.MAX_EXTERNAL_TOOL_NAME)
                )
            }
            if (t.description.isBlank()) {
                throw PluginException(L("工具「%s」缺少 description（AI 靠它判断什么时候用）").format(t.name))
            }
            if (t.description.length > PluginManifest.MAX_TOOL_DESCRIPTION) {
                throw PluginException(L("工具「%s」的 description 太长").format(t.name))
            }
            val file = t.file.trim()
            if (file.isEmpty()) throw PluginException(L("工具「%s」缺少 file").format(t.name))
            if (!file.startsWith("tools/")) {
                throw PluginException(L("工具「%s」的脚本要放在 tools/ 下（当前是 %s）").format(t.name, file))
            }
            if (file.contains("..")) {
                throw PluginException(L("工具「%s」的脚本路径不能包含 ..").format(t.name))
            }
            if (t.timeoutMs <= 0 || t.timeoutMs > PluginToolDecl.MAX_TIMEOUT_MS) {
                throw PluginException(
                    L("工具「%s」的超时要在 1~%s 毫秒之间").format(t.name, PluginToolDecl.MAX_TIMEOUT_MS)
                )
            }
            val paramNames = HashSet<String>()
            t.params.forEach { p ->
                if (!p.name.matches(Regex(PluginManifest.PARAM_NAME_PATTERN))) {
                    throw PluginException(L("工具「%s」的参数名「%s」不合法").format(t.name, p.name))
                }
                if (!paramNames.add(p.name)) {
                    throw PluginException(L("工具「%s」的参数名重复：%s").format(t.name, p.name))
                }
                if (p.type !in PluginParamDecl.TYPES) {
                    throw PluginException(
                        L("工具「%s」的参数「%s」类型「%s」不认识").format(t.name, p.name, p.type)
                    )
                }
                if (p.type == "enum" && p.options.isEmpty()) {
                    throw PluginException(L("参数「%s」是 enum，但没给 options").format(p.name))
                }
                if (p.default.isNotBlank() && p.type == "enum" && p.default !in p.options) {
                    throw PluginException(
                        L("参数「%s」的默认值「%s」不在 options 里").format(p.name, p.default)
                    )
                }
            }
        }

        // ---- HTTP
        m.http?.let { http ->
            if (http.bind != PluginHttpDecl.BIND_LOOPBACK && http.bind != PluginHttpDecl.BIND_LAN) {
                throw PluginException(L("http.bind 只能是 loopback 或 lan"))
            }
            val paths = HashSet<String>()
            http.routes.forEach { r ->
                if (!r.path.startsWith("/")) {
                    throw PluginException(L("路由「%s」必须以 / 开头").format(r.path))
                }
                if (r.path.contains("..")) {
                    throw PluginException(L("路由「%s」不能包含 ..").format(r.path))
                }
                val reserved = RESERVED_PREFIXES.firstOrNull { r.path == it || r.path.startsWith("$it/") }
                if (reserved != null) {
                    throw PluginException(
                        L("路由「%s」占用了 MCPBox 自己的路径前缀 %s").format(r.path, reserved)
                    )
                }
                if (!paths.add(r.path)) throw PluginException(L("路由重复：%s").format(r.path))
                if (r.handler.isBlank()) throw PluginException(L("路由「%s」缺少 handler").format(r.path))
                if (!r.handler.startsWith("api/")) {
                    throw PluginException(
                        L("路由「%s」的处理脚本要放在 api/ 下（当前是 %s）").format(r.path, r.handler)
                    )
                }
                if (r.handler.contains("..")) {
                    throw PluginException(L("路由「%s」的处理脚本路径不能包含 ..").format(r.path))
                }
                if (r.methods.isEmpty()) {
                    throw PluginException(L("路由「%s」至少要给一个 method").format(r.path))
                }
                r.methods.forEach { method ->
                    if (method != method.uppercase() || method !in SUPPORTED_METHODS) {
                        throw PluginException(
                            L("路由「%s」的 method「%s」不认识（只支持 %s）")
                                .format(r.path, method, SUPPORTED_METHODS.joinToString("/"))
                        )
                    }
                }
                if (r.auth != "key" && r.auth != "none") {
                    throw PluginException(L("路由「%s」的 auth 只能是 key 或 none").format(r.path))
                }
                if (r.auth == "none" && http.bind != PluginHttpDecl.BIND_LOOPBACK) {
                    throw PluginException(
                        L("路由「%s」不要 Key 又绑在局域网上，等于谁都能调，请改成 auth = key 或 bind = loopback")
                            .format(r.path)
                    )
                }
            }
            if (http.routes.isEmpty()) {
                throw PluginException(L("写了 http 但一条 routes 都没有"))
            }
            if (http.routes.any { it.auth == "key" } && http.key.prefix.length > 12) {
                throw PluginException(L("Key 前缀太长（最多 12 个字符）"))
            }
        }

        // ---- 版本要求
        m.requires?.let { req ->
            if (req.mcpbox.isNotBlank() && PluginVersion.parseRequirement(req.mcpbox) == null) {
                throw PluginException(
                    L("requires.mcpbox「%s」看不懂，只支持 >=1.7.0、^1.7.0、~1.7.0、1.7.0 这几种写法")
                        .format(req.mcpbox)
                )
            }
            if (req.api < 0) throw PluginException(L("requires.api 不能是负数"))
        }
    }

    val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /**
     * 判断插件能不能在这个版本的 MCPBox 上跑。
     * @return 不满足时返回给用户看的原因；满足返回 null。
     */
    fun checkRequirement(m: PluginManifest, appVersion: String): String? {
        val req = m.requires ?: return null
        if (req.api > API_VERSION) {
            return L("这个插件需要插件 API 版本 %s，当前支持到 %s，请升级 MCPBox")
                .format(req.api, API_VERSION)
        }
        if (req.mcpbox.isNotBlank()) {
            val ok = PluginVersion.satisfies(req.mcpbox, appVersion)
            if (!ok) {
                return L("这个插件需要 MCPBox %s，当前是 %s").format(req.mcpbox, appVersion)
            }
        }
        return null
    }
}

// ------------------------------------------------------------------ 版本比较

/**
 * 语义化版本比较。
 *
 * 只用 versionName（`1.5.0` 这种），不用 versionCode —— 后者只是构建递增计数，
 * 跨分支不可比。比较时按数字段对齐，缺的段当 0（`1.5` == `1.5.0`）。
 */
object PluginVersion {

    /**
     * 把 `1.5.0` / `1.5` / `v1.5.0-172` 拆成数字段；有非数字段就返回 null。
     * `-` / `+` 之后的是构建后缀（核心版本号），不参与比较。
     */
    fun segments(v: String): List<Int>? {
        val clean = v.trim().removePrefix("v")
            .substringBefore('-').substringBefore('+')
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        val out = ArrayList<Int>(parts.size)
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0) return null
            out.add(n)
        }
        return out
    }

    /** a > b 返回正数，相等 0，a < b 返回负数；任一方不合法返回 null。 */
    fun compare(a: String, b: String): Int? {
        val x = segments(a) ?: return null
        val y = segments(b) ?: return null
        val size = maxOf(x.size, y.size)
        for (i in 0 until size) {
            val xi = x.getOrElse(i) { 0 }
            val yi = y.getOrElse(i) { 0 }
            if (xi != yi) return xi - yi
        }
        return 0
    }

    /** 解析 `>=1.7.0` / `^1.7.0` / `~1.7.0` / `1.7.0`。 */
    fun parseRequirement(expr: String): Requirement? {
        val e = expr.trim()
        if (e.isEmpty()) return null
        val (op, ver) = when {
            e.startsWith(">=") -> ">=" to e.removePrefix(">=")
            e.startsWith("^") -> "^" to e.removePrefix("^")
            e.startsWith("~") -> "~" to e.removePrefix("~")
            e.startsWith("=") -> "=" to e.removePrefix("=")
            else -> "=" to e
        }
        val seg = segments(ver.trim()) ?: return null
        return Requirement(op, seg)
    }

    data class Requirement(val op: String, val segments: List<Int>)

    /** 当前版本是否满足要求表达式；表达式不合法时返回 false。 */
    fun satisfies(expr: String, current: String): Boolean {
        val req = parseRequirement(expr) ?: return false
        val cur = segments(current) ?: return false
        fun cmp(): Int {
            val size = maxOf(req.segments.size, cur.size)
            for (i in 0 until size) {
                val a = req.segments.getOrElse(i) { 0 }
                val b = cur.getOrElse(i) { 0 }
                if (a != b) return b - a
            }
            return 0
        }
        val c = cmp()
        return when (req.op) {
            ">=" -> c >= 0
            "=" -> c == 0
            // ^1.7.0：主版本相同且不低于 1.7.0
            "^" -> c >= 0 && cur.getOrElse(0) { 0 } == req.segments.getOrElse(0) { 0 }
            // ~1.7.0：主次版本相同且不低于 1.7.0
            "~" -> c >= 0 &&
                cur.getOrElse(0) { 0 } == req.segments.getOrElse(0) { 0 } &&
                cur.getOrElse(1) { 0 } == req.segments.getOrElse(1) { 0 }
            else -> false
        }
    }
}

// ------------------------------------------------------------------ 工具 schema

object PluginSchema {

    /** 把参数声明转成 MCP 的 inputSchema。 */
    fun of(tool: PluginToolDecl): JsonObject = Schema.obj(
        props = tool.params.associate { p ->
            p.name to when (p.type) {
                "integer" -> Schema.int(desc(p))
                "number" -> jo("type" to "number", "description" to desc(p))
                "boolean" -> Schema.bool(desc(p))
                "enum" -> Schema.str(desc(p), enum = p.options)
                else -> Schema.str(desc(p))
            }
        },
        required = tool.params.filter { it.required }.map { it.name }
    )

    private fun desc(p: PluginParamDecl): String = buildString {
        append(p.description.ifBlank { p.name })
        if (p.default.isNotBlank()) append(L("（默认 %s）").format(p.default))
    }
}

/** 设置项默认值转成 JSON。字符串一律按声明类型解析，解析不了就退回字符串。 */
fun PluginSettingDecl.defaultJson(): JsonElement? {
    if (default.isBlank()) return null
    return when (type) {
        PluginSettingDecl.TYPE_INTEGER -> default.toIntOrNull()?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(default)
        PluginSettingDecl.TYPE_NUMBER -> default.toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(default)
        PluginSettingDecl.TYPE_BOOLEAN -> when (default.lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(default)
        }
        else -> JsonPrimitive(default)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 用户可单独控制的权限开关。 */
enum class PermKey(val id: String, val title: String, val desc: String, val default: PermAction) {
    READ(
        "fs.read", "读取文件",
        "列目录、读文件、搜索、看图",
        PermAction.ALLOW
    ),
    WRITE(
        "fs.write", "写入文件",
        "写入、编辑、复制、移动",
        PermAction.ASK
    ),
    DELETE(
        "fs.delete", "删除文件",
        "删除文件或目录、清空回收站",
        PermAction.ASK
    ),
    SHELL(
        "shell.exec", "执行命令",
        "终端 / Shell 命令、自定义工具",
        PermAction.ASK
    ),
    TOOLS(
        "tools.manage", "自定义工具",
        "创建、修改、删除自定义工具",
        PermAction.ASK
    ),
    SYSTEM(
        "system.info", "系统信息",
        "设备信息与服务器状态",
        PermAction.ALLOW
    ),
    MEMORY(
        "memory", "记忆库",
        "创建 / 修改 / 查询 AI 的长期记忆",
        PermAction.ALLOW
    );

    companion object {
        /** 大小写不敏感：老版本存的是大写（ALLOW / ASK / DENY），新版本统一小写，两种都要认。 */
        fun of(id: String?): PermKey? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

enum class PermAction(val id: String, val label: String) {
    ALLOW("allow", "允许"),
    ASK("ask", "询问"),
    DENY("deny", "拒绝");

    companion object {
        /**
         * 大小写不敏感：早期版本把枚举直接 toString 落盘（大写 `ASK`），
         * 而 id 是小写 `ask`。不兼容的话用户改过的开关重启后会静默回落到默认值。
         */
        fun of(id: String?): PermAction? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * 一条规则，两种类型：
 *  - path    ：按路径前缀匹配（`fs.read` @ /sdcard/DCIM）
 *  - command ：按命令匹配（`shell.exec` @ pm，prefix / exact / regex）
 *
 * 早期版本只存路径规则，字段 path 保留用于兼容旧数据。
 */
@Serializable
data class Rule(
    val id: String = "",
    val type: String = TYPE_PATH,
    val perm: String = "*",
    val pattern: String = "",
    val path: String = "",
    val match: String = MATCH_PREFIX,
    val action: String = PermAction.ASK.id,
    val note: String = ""
) {
    /** pattern 优先，旧数据回落到 path。 */
    val target: String get() = pattern.ifBlank { path }

    val actionEnum: PermAction get() = PermAction.of(action) ?: PermAction.ASK
    val isCommand: Boolean get() = type == TYPE_COMMAND

    companion object {
        const val TYPE_PATH = "path"
        const val TYPE_COMMAND = "command"
        const val MATCH_PREFIX = "prefix"
        const val MATCH_EXACT = "exact"
        const val MATCH_REGEX = "regex"
    }
}

/** 旧名字，保持源码兼容。 */
typealias PathRule = Rule

data class PermDecision(
    val action: PermAction,
    val source: String,
    val ruleId: String? = null
) {
    val allowed: Boolean get() = action == PermAction.ALLOW
}

/**
 * 权限矩阵：每种权限的三态开关 + 路径规则 + 命令规则。
 * 判定顺序：
 *   1. 只读模式 → 写/删直接拒绝
 *   2. 命令规则（按列表顺序，第一条命中的生效）
 *   3. 路径规则（最长前缀优先）
 *   4. 权限开关
 */
class PermissionStore(private val config: Config, private val src: SettingsSource) {

    @Volatile private var switches: Map<String, PermAction> = defaultSwitches()
    @Volatile private var rules: List<Rule> = emptyList()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    init { load() }

    private fun defaultSwitches(): Map<String, PermAction> =
        PermKey.entries.associate { it.id to it.default }

    fun load() {
        val raw = src.getString(Config.Keys.PERMISSIONS, null)
        if (raw.isNullOrBlank()) {
            switches = defaultSwitches()
            rules = emptyList()
            return
        }
        runCatching {
            val root = J.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            if (root != null) {
                val sw = root["switches"] as? kotlinx.serialization.json.JsonObject
                val map = LinkedHashMap<String, PermAction>()
                PermKey.entries.forEach { key ->
                    map[key.id] = PermAction.of(sw?.str(key.id)) ?: key.default
                }
                switches = map
                val ruleArr = root.arr("rules")
                rules = ruleArr?.mapNotNull { el ->
                    runCatching { JSON_DEC.decodeFromJsonElement(Rule.serializer(), el) }.getOrNull()
                } ?: emptyList()
            }
        }.onFailure {
            switches = defaultSwitches()
            rules = emptyList()
        }
        notifyChanged()
    }

    fun persist() {
        val obj = jo(
            // 统一写小写 id（老数据可能是大写，load 时大小写都能认）
            "switches" to switches.mapValues { it.value.id },
            "rules" to rules.map { JSON_DEC.encodeToJsonElement(Rule.serializer(), it) }
        )
        src.putString(Config.Keys.PERMISSIONS, obj.toString())
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    fun switchOf(key: PermKey): PermAction = switches[key.id] ?: key.default

    fun setSwitch(key: PermKey, action: PermAction) {
        switches = switches.toMutableMap().apply { put(key.id, action) }
        persist()
        notifyChanged()
    }

    fun setSwitchForAll(action: PermAction) {
        switches = PermKey.entries.associate { it.id to action }
        persist()
        notifyChanged()
    }

    fun rules(): List<Rule> = rules

    fun pathRules(): List<Rule> = rules.filter { !it.isCommand }

    fun commandRules(): List<Rule> = rules.filter { it.isCommand }

    fun addRule(
        perm: String,
        pattern: String,
        action: PermAction,
        type: String = Rule.TYPE_PATH,
        match: String = Rule.MATCH_PREFIX,
        note: String = ""
    ): Rule {
        val rule = Rule(
            id = Tokens.newId().take(8),
            type = type,
            perm = perm.ifBlank { "*" },
            pattern = pattern.trim(),
            match = match,
            action = action.id,
            note = note
        )
        rules = rules + rule
        persist()
        notifyChanged()
        return rule
    }

    /** 便捷：给某条命令（前缀）加一条允许/拒绝规则。 */
    fun addCommandRule(command: String, action: PermAction, match: String = Rule.MATCH_PREFIX, note: String = ""): Rule =
        addRule(PermKey.SHELL.id, command, action, type = Rule.TYPE_COMMAND, match = match, note = note)

    fun updateRule(rule: Rule) {
        rules = rules.map { if (it.id == rule.id) rule else it }
        persist()
        notifyChanged()
    }

    fun removeRule(id: String) {
        rules = rules.filterNot { it.id == id }
        persist()
        notifyChanged()
    }

    fun clearRules(type: String? = null) {
        rules = if (type == null) emptyList() else rules.filterNot { it.type == type || (type == Rule.TYPE_PATH && !it.isCommand) }
        persist()
        notifyChanged()
    }

    /** 规则在列表里上下移动（命令规则按顺序匹配，顺序很重要）。 */
    fun moveRule(id: String, delta: Int) {
        val list = rules.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, list.size - 1)
        if (target == idx) return
        val item = list.removeAt(idx)
        list.add(target, item)
        rules = list
        persist()
        notifyChanged()
    }

    fun snapshot(): Map<String, PermAction> = switches

    fun decide(perm: PermKey, path: String? = null, command: String? = null): PermDecision {
        if (config.readOnly && (perm == PermKey.WRITE || perm == PermKey.DELETE)) {
            return PermDecision(PermAction.DENY, "只读模式已开启")
        }
        // 1) 命令规则：按顺序，第一条命中生效
        if (!command.isNullOrBlank()) {
            val hit = rules.firstOrNull { r ->
                r.isCommand && (r.perm == "*" || r.perm == perm.id) && commandMatches(r, command)
            }
            if (hit != null) {
                val how = when (hit.match) {
                    Rule.MATCH_EXACT -> "完全匹配"
                    Rule.MATCH_REGEX -> "正则匹配"
                    else -> "前缀匹配"
                }
                return PermDecision(hit.actionEnum, "命令规则「${hit.target}」（$how）", hit.id)
            }
        }
        // 2) 路径规则：最长前缀优先
        if (!path.isNullOrBlank()) {
            val normalized = normalize(path)
            val matching = rules.filter { r ->
                !r.isCommand && (r.perm == "*" || r.perm == perm.id) && matchesPrefix(r.target, normalized)
            }
            if (matching.isNotEmpty()) {
                val best = matching.sortedWith(
                    compareByDescending<Rule> { it.target.length }
                        .thenByDescending { if (it.perm == perm.id) 1 else 0 }
                ).first()
                return PermDecision(best.actionEnum, "路径规则 ${best.target}", best.id)
            }
        }
        // 3) 权限开关
        val sw = switchOf(perm)
        return PermDecision(sw, "权限开关「${perm.title}」")
    }

    private fun commandMatches(rule: Rule, command: String): Boolean {
        val target = rule.target.trim()
        if (target.isEmpty()) return false
        val cmd = command.trim()
        return when (rule.match) {
            Rule.MATCH_EXACT -> cmd == target
            Rule.MATCH_REGEX -> runCatching {
                Regex(target, RegexOption.IGNORE_CASE).containsMatchIn(cmd)
            }.getOrDefault(false)
            else -> cmd == target || cmd.startsWith("$target ")
        }
    }

    private fun normalize(p: String): String = p.replace('\\', '/').trimEnd('/')

    private fun matchesPrefix(rulePath: String, path: String): Boolean {
        if (rulePath.isBlank()) return false
        val rp = normalize(rulePath)
        if (rp == "/" || rp.isEmpty()) return true
        if (path == rp) return true
        return path.startsWith("$rp/")
    }

    companion object {
        private val JSON_DEC = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

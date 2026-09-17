// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * 工具级策略：**每个工具可以单独启用/禁用、单独设定权限**。
 *
 * 存储用最朴素的字符串（SharedPreferences 友好）：
 *  - `config.disabledTools`  —— `read_file,write_file`
 *  - `config.toolOverrides`  —— `read_file=allow;write_file=deny`
 *
 * 权限四态：
 *  - [ALLOW]  该工具的所有操作直接放行，不弹审批
 *  - [ASK]    **无视全局权限矩阵**，这个工具的每次操作都要弹窗问一次
 *  - [DENY]   无论全局怎么设，这个工具一律拒绝
 *  - [FOLLOW] 跟随全局权限矩阵（默认值）
 *
 * 四态都会明确写进配置（包括 [FOLLOW]），这样才能把「出厂默认」和
 * 「用户手动改回跟随」区分开：配置里没条目时才回落到 [FACTORY_DEFAULTS]。
 */
object ToolPolicy {

    const val ALLOW = "allow"
    const val ASK = "ask"
    const val DENY = "deny"

    /** 跟随全局：等价于"没有单独设过权限"。 */
    const val FOLLOW = "follow"

    /** 四种权限的排列顺序，UI 直接照这个渲染。 */
    val ALL_VALUES = listOf(FOLLOW, ALLOW, ASK, DENY)

    /**
     * 出厂就带单独权限的工具。
     * `get_token` 能拿到访问令牌，属于敏感信息，默认每次都问一下。
     */
    val FACTORY_DEFAULTS: Map<String, String> = mapOf("get_token" to ASK)

    /** 权限四态的中文标签。 */
    fun label(value: String?): String = when (value) {
        ALLOW -> "允许"
        ASK -> "询问"
        DENY -> "拒绝"
        else -> "跟随"
    }

    /** 是否为「明确设定过」的权限（跟随 = 没设过）。 */
    fun isExplicit(value: String?): Boolean = value != null && value != FOLLOW

    fun disabled(config: Config): Set<String> =
        config.disabledTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isDisabled(config: Config, tool: String): Boolean = tool in disabled(config)

    fun setDisabled(config: Config, tool: String, disabled: Boolean) {
        val set = disabled(config).toMutableSet()
        if (disabled) set.add(tool) else set.remove(tool)
        config.disabledTools = set.joinToString(",")
    }

    fun overrides(config: Config): Map<String, String> =
        config.toolOverrides.split(';').mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) null
            // 值统一小写：兼容手工改过配置 / 早期版本写的大写
            else entry.substring(0, i).trim() to entry.substring(i + 1).trim().lowercase()
        }.filter { it.first.isNotEmpty() }.toMap()

    /** 配置里明确写着的值；没写过就是 null。 */
    fun overrideOf(config: Config, tool: String): String? = overrides(config)[tool]

    /** 真正生效的权限：配置 → 出厂默认 → 跟随全局。 */
    fun effectiveOverride(config: Config, tool: String): String =
        overrideOf(config, tool) ?: FACTORY_DEFAULTS[tool] ?: FOLLOW

    /** 有多少个工具被单独设了权限（跟随不算）。 */
    fun explicitCount(config: Config): Int {
        val names = overrides(config).keys + FACTORY_DEFAULTS.keys
        return names.count { isExplicit(effectiveOverride(config, it)) }
    }

    /** 传 null 表示彻底抹掉记录（回到出厂默认）。 */
    fun setOverride(config: Config, tool: String, value: String?) {
        val map = overrides(config).toMutableMap()
        if (value == null) map.remove(tool) else map[tool] = value
        config.toolOverrides = map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}

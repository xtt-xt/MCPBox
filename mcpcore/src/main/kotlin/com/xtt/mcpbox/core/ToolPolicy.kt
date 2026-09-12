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
 * 权限语义：
 *  - [ALLOW] 该工具的所有操作直接放行，不弹审批
 *  - [ASK]   跟随全局权限矩阵（默认）
 *  - [DENY]  无论全局怎么设，这个工具一律拒绝
 */
object ToolPolicy {

    const val ALLOW = "allow"
    const val ASK = "ask"
    const val DENY = "deny"

    /** 权限三态的中文标签。 */
    fun label(value: String?): String = when (value) {
        ALLOW -> "允许"
        DENY -> "禁止"
        else -> "询问"
    }

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
            else entry.substring(0, i).trim() to entry.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }.toMap()

    fun overrideOf(config: Config, tool: String): String? = overrides(config)[tool]

    fun setOverride(config: Config, tool: String, value: String?) {
        val map = overrides(config).toMutableMap()
        if (value == null || value == ASK) map.remove(tool) else map[tool] = value
        config.toolOverrides = map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}

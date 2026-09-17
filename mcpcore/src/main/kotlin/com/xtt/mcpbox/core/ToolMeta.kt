// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** 一个内置工具的文案覆盖。空字符串 = 用回内置原文。 */
@Serializable
data class ToolMeta(
    val name: String = "",
    val title: String = "",
    val description: String = ""
)

/**
 * 内置工具的标题 / 说明可以被用户改写。
 *
 * 存进配置的 JSON 列表；`McpServer.tools` 每次取工具时套用一遍，
 * 所以改完立刻在 AI 的 `tools/list` 里生效（[ToolSpec.annotations] 也会跟着变）。
 *
 * 只影响文案，不影响 [ToolSpec.perm] / schema / 处理逻辑。
 */
class ToolMetaStore(private val src: SettingsSource) {

    @Volatile var metas: List<ToolMeta> = emptyList()
        private set

    init { load() }

    fun load() {
        val raw = src.getString(Config.Keys.TOOL_META, null)
        if (raw.isNullOrBlank()) {
            metas = emptyList()
            return
        }
        metas = runCatching {
            val el = J.parseToJsonElement(raw)
            J.decodeFromJsonElement(ListSerializer(ToolMeta.serializer()), el)
        }.getOrElse { emptyList() }
    }

    fun persist() {
        val el = J.encodeToJsonElement(ListSerializer(ToolMeta.serializer()), metas)
        src.putString(Config.Keys.TOOL_META, el.toString())
    }

    fun of(name: String): ToolMeta? = metas.firstOrNull { it.name == name }

    /** 写文案；两个字段都空时直接删掉这条记录，回到内置原文。 */
    fun set(name: String, title: String, description: String) {
        val cleaned = ToolMeta(
            name = name,
            title = title.trim().take(120),
            description = description.trim().take(4000)
        )
        metas = if (cleaned.title.isBlank() && cleaned.description.isBlank()) {
            metas.filterNot { it.name == name }
        } else {
            metas.filterNot { it.name == name } + cleaned
        }
        persist()
    }

    fun reset(name: String) {
        metas = metas.filterNot { it.name == name }
        persist()
    }

    fun resetAll() {
        metas = emptyList()
        persist()
    }

    /** 给一个工具套上覆盖后的文案。 */
    fun apply(spec: ToolSpec): ToolSpec {
        val meta = of(spec.name) ?: return spec
        val title = meta.title.ifBlank { spec.title }
        val desc = meta.description.ifBlank { spec.description }
        if (title == spec.title && desc == spec.description) return spec
        return spec.withMeta(title = title, description = desc)
    }

    /** 是否被改过。 */
    fun isCustomized(name: String): Boolean = of(name)?.let {
        it.title.isNotBlank() || it.description.isNotBlank()
    } ?: false
}

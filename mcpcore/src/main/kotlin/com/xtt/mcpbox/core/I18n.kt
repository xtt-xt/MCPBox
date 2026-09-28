// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * mcpcore 侧的多语言钩子。
 *
 * 核心库不认识 Android，也读不到 App 的语言包，所以这里只留一个**可注入**的翻译函数：
 * App 启动时把它接到自己的 i18n（[com.xtt.mcpbox.i18n.Lang.t]）；JVM 端（harness）不接，
 * 于是所有文案保持中文原文，测试断言完全不受影响。
 *
 * 键仍然是中文原文，查不到就原样返回 —— 跟 App 侧一个规矩。
 */
object CoreI18n {

    @Volatile
    private var translator: ((String) -> String)? = null

    /** 由 Android 层注入（每次调用都取当前语言，所以切语言后立刻生效）。 */
    fun install(fn: (String) -> String) {
        translator = fn
    }

    fun clear() {
        translator = null
    }

    /** 是否装了翻译器（没装就说明是 JVM 测试环境）。 */
    val installed: Boolean get() = translator != null

    fun t(zh: String): String = translator?.invoke(zh)?.takeIf { it.isNotBlank() } ?: zh

    /**
     * 深度翻译 JSON 里所有 `description` 字段。
     *
     * 工具参数说明是塞在 inputSchema 里的普通字符串，没法一个个包 L()，
     * 只能在出口统一过一遍 —— 只认 `description` 这一个键，别的原样不动
     * （`enum` / `default` 这些是程序语义，绝不能翻译）。
     */
    fun translateSchema(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(
            node.mapValues { (key, value) ->
                if (key == "description" && value is JsonPrimitive && value.isString) {
                    JsonPrimitive(t(value.content))
                } else {
                    translateSchema(value)
                }
            }
        )

        is JsonArray -> JsonArray(node.map { translateSchema(it) })
        else -> node
    }
}

/** 和 App 侧同名的短写法，用起来顺手。 */
fun L(zh: String): String = CoreI18n.t(zh)

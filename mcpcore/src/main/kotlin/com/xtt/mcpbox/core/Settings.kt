// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * Tiny key/value store abstraction so that the whole server core stays free of
 * Android imports and can be unit-tested on a plain JVM.
 */
interface SettingsSource {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)

    /**
     * 全部键值（给备份用）。没实现的话返回空表 —— 备份功能会显示「没有可备份的设置」，
     * 而不是直接把整个 App 搞崩。
     */
    fun all(): Map<String, Any?> = emptyMap()

    /** 清空全部键值（恢复备份的「覆盖」模式用）。 */
    fun clearAll() {}

    /**
     * 按值本身的类型写回一个键 —— 恢复备份时用，因为键值是从 JSON 里来的，
     * 类型要现看现判（Boolean / Int / Long / Double / String）。
     */
    fun putRaw(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Double -> putLong(key, value.toLong())
            is String -> putString(key, value)
            else -> putString(key, value.toString())
        }
    }
}

/** In-memory implementation: used by tests and as a safe fallback. */
class MemorySettings : SettingsSource {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
    override fun getInt(key: String, def: Int): Int = (map[key] as? Number)?.toInt() ?: def
    override fun getLong(key: String, def: Long): Long = (map[key] as? Number)?.toLong() ?: def
    override fun getBoolean(key: String, def: Boolean): Boolean = map[key] as? Boolean ?: def
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun all(): Map<String, Any?> = LinkedHashMap(map)
    override fun clearAll() = map.clear()
}

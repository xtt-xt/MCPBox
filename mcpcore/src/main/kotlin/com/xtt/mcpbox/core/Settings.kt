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
}

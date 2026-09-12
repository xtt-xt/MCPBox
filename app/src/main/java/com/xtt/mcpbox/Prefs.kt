// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.content.Context
import com.xtt.mcpbox.core.SettingsSource

/** SharedPreferences backed [SettingsSource] used by the server core. */
class Prefs(context: Context) : SettingsSource {

    private val sp = context.getSharedPreferences("mcpbox_settings", Context.MODE_PRIVATE)

    companion object {
        /** true = 用户希望服务器保持运行（用来做开机自启/进程被杀后重启）。 */
        const val KEY_SHOULD_RUN = "service_should_run"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_WAKE_SCREEN = "wake_screen_on_approval"
        const val KEY_NOTIFY_SOUND = "approval_sound"
        const val KEY_AUTO_START = "auto_start_boot"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_THEME = "theme_id"
        const val KEY_FIRST_RUN = "first_run_done"
    }

    override fun getString(key: String, def: String?): String? = sp.getString(key, def)
    override fun getInt(key: String, def: Int): Int = sp.getInt(key, def)
    override fun getLong(key: String, def: Long): Long = sp.getLong(key, def)
    override fun getBoolean(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    override fun putString(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = sp.edit().remove(key).apply()

    var shouldRun: Boolean
        get() = getBoolean(KEY_SHOULD_RUN, false)
        set(value) = putBoolean(KEY_SHOULD_RUN, value)

    var keepAwake: Boolean
        get() = getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = putBoolean(KEY_KEEP_AWAKE, value)

    var wakeScreenOnApproval: Boolean
        get() = getBoolean(KEY_WAKE_SCREEN, true)
        set(value) = putBoolean(KEY_WAKE_SCREEN, value)

    var approvalSound: Boolean
        get() = getBoolean(KEY_NOTIFY_SOUND, true)
        set(value) = putBoolean(KEY_NOTIFY_SOUND, value)

    var autoStartBoot: Boolean
        get() = getBoolean(KEY_AUTO_START, true)
        set(value) = putBoolean(KEY_AUTO_START, value)

    /** 颜色跟随壁纸（Material You 动态取色）。 */
    var dynamicColor: Boolean
        get() = getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = putBoolean(KEY_DYNAMIC_COLOR, value)

    /**
     * 主题色：dynamic / blue / green / purple / orange / rose / cyan。
     * 老版本只存了 dynamicColor 这个布尔值，这里做个兼容。
     */
    var themeId: String
        get() = getString(KEY_THEME, null)
            ?: if (getBoolean(KEY_DYNAMIC_COLOR, true)) "dynamic" else "blue"
        set(value) = putString(KEY_THEME, value)

    var firstRunDone: Boolean
        get() = getBoolean(KEY_FIRST_RUN, false)
        set(value) = putBoolean(KEY_FIRST_RUN, value)

    /** 重置所有设置（服务器核心的配置也在里面）。 */
    fun clearAll() = sp.edit().clear().apply()
}

/** 目录建议：给「添加允许访问的目录」对话框用。 */
object DefaultRootsHolder {
    fun suggestions(): List<String> = runCatching {
        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
        listOf(
            base,
            "$base/Download",
            "$base/Documents",
            "$base/DCIM",
            "$base/Pictures",
            "$base/Music",
            "$base/Android/media",
            "$base/Android/data"
        )
    }.getOrElse { listOf("/sdcard") }
}

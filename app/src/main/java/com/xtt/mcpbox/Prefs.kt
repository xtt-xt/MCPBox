// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import com.xtt.mcpbox.ui.DEFAULT_SEED
import com.xtt.mcpbox.ui.DarkMode
import com.xtt.mcpbox.ui.PaletteStyle

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
        const val KEY_SEED = "seed_color"
        const val KEY_PALETTE_STYLE = "palette_style"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_UPDATE_DAILY = "update_check_daily"
        const val KEY_UPDATE_LAST = "update_check_last"
        const val KEY_APP_LANG = "app_lang"
        const val KEY_CAT = "cat_unlocked"
        /** 连点关于页图标解锁的「开发者模式」入口。 */
        const val KEY_DEV_MODE = "dev_mode_unlocked"
        /** 开发者模式里的「语言菜单」开关（打开后语言列表里才有猫娘语）。 */
        const val KEY_LANG_MENU = "language_menu"
        /** 下一次启动无条件检查一次更新（开发者模式用）。 */
        const val KEY_FORCE_UPDATE = "force_update_check_next"
        const val KEY_FIRST_RUN = "first_run_done"
        /** 初始引导走完没有（走完 / 跳过都算走过）。 */
        const val KEY_ONBOARD_DONE = "onboard_done"
        /** 权限页「工具包」正在查看哪个会话（纯界面状态，跟 AI 实际用的会话无关）。 */
        const val KEY_PACK_PROFILE = "pack_profile_view"
        /** 「不限时」开关打开前用的秒数（关掉开关时恢复回去）。 */
        const val KEY_APPROVAL_TIMEOUT_LAST = "approval_timeout_last_sec"
        const val KEY_SHELL_TIMEOUT_LAST = "shell_timeout_last_sec"
        /** 有 Root / Shizuku 时，进入 App 自动补齐缺失的系统权限。 */
        const val KEY_AUTO_GRANT_PERMS = "auto_grant_perms"
        /** 预见式返回动画（Android 13+ 的 predictive back，默认开）。 */
        const val KEY_PREDICTIVE_BACK = "predictive_back"
        /** 审批请求怎么呈现：overlay（悬浮窗，默认）/ notify（通知栏）。 */
        const val KEY_APPROVAL_PRESENTATION = "approval_presentation"
    }

    override fun getString(key: String, def: String?): String? = sp.getString(key, def)

    /*
     * SharedPreferences 是强类型的：键里存的是 Integer 时 `getLong` 会直接抛
     * ClassCastException（恢复备份写错类型就踩过这个坑，App 直接闪退）。
     *
     * 所以这几个读取都包一层：正常路径零额外开销（不抛异常就不会进 fallback），
     * 真读到不匹配的类型才转一下，**并顺手写回正确类型**，免得每次都纠一次。
     */

    override fun getInt(key: String, def: Int): Int =
        runCatching { sp.getInt(key, def) }.getOrElse {
            val v = numberOrNull(key)?.toInt() ?: return@getOrElse def
            sp.edit().putInt(key, v).apply()
            v
        }

    override fun getLong(key: String, def: Long): Long =
        runCatching { sp.getLong(key, def) }.getOrElse {
            val v = numberOrNull(key) ?: return@getOrElse def
            sp.edit().putLong(key, v).apply()
            v
        }

    override fun getBoolean(key: String, def: Boolean): Boolean =
        runCatching { sp.getBoolean(key, def) }.getOrElse {
            val raw = sp.all[key]
            val v = when (raw) {
                is Boolean -> raw
                is String -> raw.equals("true", true)
                is Number -> raw.toInt() != 0
                else -> return@getOrElse def
            }
            sp.edit().putBoolean(key, v).apply()
            v
        }

    /** 把键里的原始值当数字读（只在类型不匹配的 fallback 路径里用）。 */
    private fun numberOrNull(key: String): Long? = when (val raw = sp.all[key]) {
        is Long -> raw
        is Int -> raw.toLong()
        is Float -> raw.toLong()
        is Double -> raw.toLong()
        is String -> raw.toLongOrNull()
        is Boolean -> if (raw) 1L else 0L
        else -> null
    }

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

    /** 种子颜色（ARGB）：关掉动态取色时，整套配色由它派生。 */
    var seedColor: Int
        get() = getInt(KEY_SEED, DEFAULT_SEED)
        set(value) = putInt(KEY_SEED, value)

    /** 调色板样式：tonal / vibrant / expressive / fidelity / neutral / mono。 */
    var paletteStyle: String
        get() = getString(KEY_PALETTE_STYLE, PaletteStyle.TONAL_SPOT.id) ?: PaletteStyle.TONAL_SPOT.id
        set(value) = putString(KEY_PALETTE_STYLE, value)

    /** 深色模式：system / light / dark。 */
    var darkMode: String
        get() = getString(KEY_DARK_MODE, DarkMode.SYSTEM.id) ?: DarkMode.SYSTEM.id
        set(value) = putString(KEY_DARK_MODE, value)

    /** 每天第一次打开 App 时自动检查更新。 */
    var updateCheckDaily: Boolean
        get() = getBoolean(KEY_UPDATE_DAILY, true)
        set(value) = putBoolean(KEY_UPDATE_DAILY, value)

    /** 彩蛋：连点关于页图标 7 次解锁的猫娘语（= 语言菜单已开启）。 */
    var catUnlocked: Boolean
        get() = getBoolean(KEY_CAT, false)
        set(value) = putBoolean(KEY_CAT, value)

    /**
     * 彩蛋：连点关于页图标 7 次解锁「开发者模式」入口。
     * 默认值取 [catUnlocked] —— 老版本解锁过猫娘语的人，升级后直接算已解锁。
     */
    var devModeUnlocked: Boolean
        get() = getBoolean(KEY_DEV_MODE, catUnlocked)
        set(value) = putBoolean(KEY_DEV_MODE, value)

    /**
     * 「语言菜单」开关：打开后语言列表里才会出现彩蛋语言（猫娘语）。
     * 默认值同样取 [catUnlocked]，兼容老版本「解锁即出现」的行为；
     * 写入时两个键一起写，[catUnlocked] 保持同步。
     */
    var languageMenu: Boolean
        get() = getBoolean(KEY_LANG_MENU, catUnlocked)
        set(value) {
            putBoolean(KEY_LANG_MENU, value)
            putBoolean(KEY_CAT, value)
        }

    /** 下一次启动 App 时无条件检查一次更新（哪怕「每天自动检查」是关的）。 */
    var forceUpdateCheckNext: Boolean
        get() = getBoolean(KEY_FORCE_UPDATE, false)
        set(value) = putBoolean(KEY_FORCE_UPDATE, value)

    /** 界面语言：system / zh / en / cat / 以及导入语言包的 id。 */
    var appLang: String
        get() = getString(KEY_APP_LANG, "system") ?: "system"
        set(value) = putString(KEY_APP_LANG, value)

    /** 上次检查更新的日期（yyyy-MM-dd），用来做「每天一次」。 */
    var lastUpdateCheck: String
        get() = getString(KEY_UPDATE_LAST, "") ?: ""
        set(value) = putString(KEY_UPDATE_LAST, value)

    var firstRunDone: Boolean
        get() = getBoolean(KEY_FIRST_RUN, false)
        set(value) = putBoolean(KEY_FIRST_RUN, value)

    /** 初始引导走完没有。开发者模式里的「强制进入初始引导」会把它置回 false。 */
    var onboardDone: Boolean
        get() = getBoolean(KEY_ONBOARD_DONE, false)
        set(value) = putBoolean(KEY_ONBOARD_DONE, value)

    /** 这个键到底有没有写过（用来区分「从没写过」和「写成了 false」）。 */
    fun contains(key: String): Boolean = sp.contains(key)

    /**
     * 整份配置是不是空的。
     *
     * 空 = 装完第一次打开（这时候还没人往 SharedPreferences 里写过任何东西）；
     * 老用户升级上来时里面早就有值了 —— 靠这个把「全新安装」和「升级」分开。
     */
    fun isBlank(): Boolean = sp.all.isEmpty()

    /**
     * 权限页里「工具包」正在查看哪个会话。
     * 这只是**界面状态**（方便你切走再回来还在原处），
     * AI 实际用哪个会话由它请求的地址 `/mcp/p/<名字>` 决定。
     */
    var packProfileView: String
        get() = getString(KEY_PACK_PROFILE, "default") ?: "default"
        set(value) = putString(KEY_PACK_PROFILE, value)

    /** 最近一次有限超时的秒数（用来在关掉「不限时」时恢复）。 */
    var approvalTimeoutLastSec: Long
        get() = getLong(KEY_APPROVAL_TIMEOUT_LAST, 300L)
        set(value) = putLong(KEY_APPROVAL_TIMEOUT_LAST, value)

    var shellTimeoutLastSec: Long
        get() = getLong(KEY_SHELL_TIMEOUT_LAST, 60L)
        set(value) = putLong(KEY_SHELL_TIMEOUT_LAST, value)

    /**
     * 进入 App 时自动补齐缺失的系统权限（默认开）。
     *
     * 只在已经拿到 Root / Shizuku 时才动手，而且只做不会重启进程的那几项
     * （文件访问 / 悬浮窗 / 忽略电池优化）；没有特权后端时这一项等于不存在。
     */
    var autoGrantPermissions: Boolean
        get() = getBoolean(KEY_AUTO_GRANT_PERMS, true)
        set(value) = putBoolean(KEY_AUTO_GRANT_PERMS, value)

    /**
     * 预见式返回动画（Android 13+ 的 predictive back，默认开）。
     *
     * 手指从边缘往右拖时子页面跟着手指横向走（下面露出上一页），松手才决定返回还是弹回。
     * 只影响「子页跟手」这一层：清单里的 `enableOnBackInvokedCallback` 是编译期写死的，
     * 根页返回桌面时那个系统动画跟这个开关无关。
     */
    var predictiveBack: Boolean
        get() = getBoolean(KEY_PREDICTIVE_BACK, true)
        set(value) = putBoolean(KEY_PREDICTIVE_BACK, value)

    /**
     * 审批请求的呈现方式：`overlay`（悬浮窗，默认）/ `notify`（通知栏）。
     *
     * 存字符串（不是布尔）：以后要再加「两处都发」之类的模式，不用改数据结构。
     * 实际行为在 [com.xtt.mcpbox.core.ApprovalPresentation.useOverlay]。
     */
    var approvalPresentation: String
        get() = getString(KEY_APPROVAL_PRESENTATION, com.xtt.mcpbox.core.ApprovalPresentation.OVERLAY.id)
            ?: com.xtt.mcpbox.core.ApprovalPresentation.OVERLAY.id
        set(value) = putString(KEY_APPROVAL_PRESENTATION, value)

    /** 重置所有设置（服务器核心的配置也在里面）。 */
    override fun clearAll() = sp.edit().clear().apply()

    /** 全部键值：备份用。SharedPreferences 的 getAll() 本来就是只读快照。 */
    override fun all(): Map<String, Any?> = LinkedHashMap(sp.all)
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

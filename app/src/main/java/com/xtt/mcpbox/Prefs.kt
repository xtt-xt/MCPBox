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
        /** 同意过的条款版本（v1 / v2…）。条款升版时比对它，要求重新确认。 */
        const val KEY_LEGAL_AGREED = "legal_agreed_version"
        /** 同意条款的时间戳（毫秒）。 */
        const val KEY_LEGAL_AGREED_AT = "legal_agreed_at"
        /** 开发者模式：下次启动时预览一次条款提示（不影响同意记录）。 */
        const val KEY_LEGAL_PREVIEW_NEXT = "legal_preview_next"
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

        // ---- 自定义背景（背景图 / 背景视频 + 一层可调透明度的遮罩）----
        const val KEY_BACKDROP_ENABLED = "backdrop_enabled"
        /** 背景类型：image / video。 */
        const val KEY_BACKDROP_KIND = "backdrop_kind"
        /** 背景文件名（落在 App 私有目录 files/backdrop/ 下；空 = 还没选）。 */
        const val KEY_BACKDROP_FILE = "backdrop_file"
        /** 遮罩不透明度 0~100（用的就是主题背景色，100 = 完全盖住背景）。 */
        const val KEY_BACKDROP_TINT = "backdrop_tint"
        /** 背景模糊半径（dp）0~40，0 = 不模糊（默认）。 */
        const val KEY_BACKDROP_BLUR = "backdrop_blur"
        /** 填充方式：crop（裁剪铺满）/ fit（完整显示）。 */
        const val KEY_BACKDROP_SCALE = "backdrop_scale"
        /** 子页面（工具管理 / 记忆库 / 设置子页…）要不要也透出背景。 */
        const val KEY_BACKDROP_SUBPAGES = "backdrop_subpages"
        /** 卡片不透明度 0~100（100 = 原来的不透明卡片，0 = 全透明）。 */
        const val KEY_BACKDROP_CARD_ALPHA = "backdrop_card_alpha"
        /** 底栏不透明度 0~100（100 = 原来的不透明底栏）。 */
        const val KEY_BACKDROP_BAR_ALPHA = "backdrop_bar_alpha"
        /** 按钮 / 下拉 / 弹窗要不要跟着卡片一起变透明。 */
        const val KEY_BACKDROP_CHROME_TINT = "backdrop_chrome_tint"

        /** 桌面图标用哪一个（见 ui/AppIcon.kt 的 id）。 */
        const val KEY_APP_ICON = "app_icon"

        /**
         * 底栏（底部导航栏 + 全选工具条）的不透明度 0~100。
         *
         * 和自定义背景无关：底栏是浮在内容上面的一层，半透明时透出的是从它下面划过的卡片。
         */
        const val KEY_BAR_ALPHA = "bar_alpha"
        /** 底栏的模糊半径（dp）0~40。 */
        const val KEY_BAR_BLUR = "bar_blur"

        /** 顶栏（滚上去之后那条矮栏）的不透明度 0~100。和底栏各自一套。 */
        const val KEY_TOP_BAR_ALPHA = "top_bar_alpha"
        /** 顶栏的模糊半径（dp）0~40。 */
        const val KEY_TOP_BAR_BLUR = "top_bar_blur"

        /** 动态取色的种子从哪来：`wallpaper`（系统壁纸）/ `backdrop`（自定义背景）。 */
        const val KEY_SEED_SOURCE = "dynamic_seed_source"
        /** 从自定义背景里取出来的种子色（ARGB，0 = 还没取）。 */
        const val KEY_BACKDROP_SEED = "backdrop_seed_color"
        /** 上面那个种子是从哪个文件取的 —— 换背景后靠它判断要不要重算。 */
        const val KEY_BACKDROP_SEED_FILE = "backdrop_seed_file"
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

    /**
     * 同意过哪个版本的《用户协议》与《隐私政策》。
     *
     * 空串 = 从没同意过。将来条款改版只要把 [com.xtt.mcpbox.ui.Legal.VERSION] 提升，
     * 老用户这里还是旧版本号，就能名正言顺地要求他重新读一遍。
     */
    var legalAgreedVersion: String
        get() = getString(KEY_LEGAL_AGREED, "") ?: ""
        set(value) = putString(KEY_LEGAL_AGREED, value)

    /**
     * 开发者模式里的「下次进入时预览条款提示」。
     *
     * 只让下一次启动显示一次，用完自己清掉；**不碰** [legalAgreedVersion] ——
     * 否则预览一次就等于真的确认过一次，反而看不出真实状态。
     */
    var legalPreviewNext: Boolean
        get() = getBoolean(KEY_LEGAL_PREVIEW_NEXT, false)
        set(value) = putBoolean(KEY_LEGAL_PREVIEW_NEXT, value)

    /** 同意条款的时间戳（毫秒），0 = 没记过。 */
    var legalAgreedAt: Long
        get() = getLong(KEY_LEGAL_AGREED_AT, 0L)
        set(value) = putLong(KEY_LEGAL_AGREED_AT, value)

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
     * 审批请求的呈现方式：`overlay`（悬浮窗，默认）/ `notify`（通知栏）。
     *
     * 存字符串（不是布尔）：以后要再加「两处都发」之类的模式，不用改数据结构。
     * 实际行为在 [com.xtt.mcpbox.core.ApprovalPresentation.useOverlay]。
     */
    var approvalPresentation: String
        get() = getString(KEY_APPROVAL_PRESENTATION, com.xtt.mcpbox.core.ApprovalPresentation.OVERLAY.id)
            ?: com.xtt.mcpbox.core.ApprovalPresentation.OVERLAY.id
        set(value) = putString(KEY_APPROVAL_PRESENTATION, value)

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

    /* ---------------------------------------------------------- 自定义背景 */

    /** 要不要显示自定义背景（图 / 视频）。默认关。 */
    var backdropEnabled: Boolean
        get() = getBoolean(KEY_BACKDROP_ENABLED, false)
        set(value) = putBoolean(KEY_BACKDROP_ENABLED, value)

    /** 背景类型：`image` / `video`。 */
    var backdropKind: String
        get() = getString(KEY_BACKDROP_KIND, "image") ?: "image"
        set(value) = putString(KEY_BACKDROP_KIND, value)

    /**
     * 背景文件名（`files/backdrop/<名字>`）。
     *
     * 选中的文件会被**复制**进 App 私有目录，所以原文件删了 / 授权失效都不影响；
     * 换一张时旧文件会被删掉（见 `BackdropFiles.import`）。
     */
    var backdropFile: String
        get() = getString(KEY_BACKDROP_FILE, "") ?: ""
        set(value) = putString(KEY_BACKDROP_FILE, value)

    /** 遮罩不透明度 0~100（主题背景色的 alpha，100 = 完全盖住背景）。 */
    var backdropTint: Int
        get() = getInt(KEY_BACKDROP_TINT, 88).coerceIn(0, 100)
        set(value) = putInt(KEY_BACKDROP_TINT, value.coerceIn(0, 100))

    /** 背景模糊半径（dp）0~40，0 = 不模糊。需要 Android 12 及以上。 */
    var backdropBlur: Int
        get() = getInt(KEY_BACKDROP_BLUR, 0).coerceIn(0, 40)
        set(value) = putInt(KEY_BACKDROP_BLUR, value.coerceIn(0, 40))

    /** 填充方式：`crop`（裁剪铺满，默认）/ `fit`（完整显示）。 */
    var backdropScale: String
        get() = getString(KEY_BACKDROP_SCALE, "crop") ?: "crop"
        set(value) = putString(KEY_BACKDROP_SCALE, value)

    /** 子页面要不要也透出背景（关掉 = 只有 5 个 tab 有背景，子页保持纯色）。默认开。 */
    var backdropSubpages: Boolean
        get() = getBoolean(KEY_BACKDROP_SUBPAGES, true)
        set(value) = putBoolean(KEY_BACKDROP_SUBPAGES, value)

    /** 卡片不透明度 0~100（0 = 完全透明，100 = 原来的不透明卡片）。 */
    var backdropCardAlpha: Int
        get() = getInt(KEY_BACKDROP_CARD_ALPHA, 100).coerceIn(0, 100)
        set(value) = putInt(KEY_BACKDROP_CARD_ALPHA, value.coerceIn(0, 100))

    /** 底栏不透明度 0~100（含底栏、选择栏）。 */
    var backdropBarAlpha: Int
        get() = getInt(KEY_BACKDROP_BAR_ALPHA, 100).coerceIn(0, 100)
        set(value) = putInt(KEY_BACKDROP_BAR_ALPHA, value.coerceIn(0, 100))

    /** 按钮 / 下拉 / 弹窗会不会跟着卡片一起变透明（默认关：只动卡片和底栏）。 */
    var backdropChromeTint: Boolean
        get() = getBoolean(KEY_BACKDROP_CHROME_TINT, false)
        set(value) = putBoolean(KEY_BACKDROP_CHROME_TINT, value)

    /* ---------------------------------------------------------------- 底栏 */

    /** 底栏不透明度 0~100（100 = 原来的不透明底栏，0 = 完全透明）。 */
    var barAlpha: Int
        get() = getInt(KEY_BAR_ALPHA, 100).coerceIn(0, 100)
        set(value) = putInt(KEY_BAR_ALPHA, value.coerceIn(0, 100))

    /** 底栏模糊半径（dp）0~40，0 = 不模糊。 */
    var barBlur: Int
        get() = getInt(KEY_BAR_BLUR, 0).coerceIn(0, 40)
        set(value) = putInt(KEY_BAR_BLUR, value.coerceIn(0, 40))

    /** 顶栏不透明度 0~100（默认 100 = 不透明）。 */
    var topBarAlpha: Int
        get() = getInt(KEY_TOP_BAR_ALPHA, 100).coerceIn(0, 100)
        set(value) = putInt(KEY_TOP_BAR_ALPHA, value.coerceIn(0, 100))

    /** 顶栏模糊半径（dp）0~40（默认 0 = 不模糊）。 */
    var topBarBlur: Int
        get() = getInt(KEY_TOP_BAR_BLUR, 0).coerceIn(0, 40)
        set(value) = putInt(KEY_TOP_BAR_BLUR, value.coerceIn(0, 40))

    /** 桌面图标用哪一个（ui/AppIcon 的 id，默认 `line`）。 */
    var appIcon: String
        get() = getString(KEY_APP_ICON, "line") ?: "line"
        set(value) = putString(KEY_APP_ICON, value)

    /**
     * 动态取色的种子从哪来：`wallpaper` / `backdrop`。
     *
     * 只在「动态取色」开着、并且「自定义背景」也开着时才有意义；
     * 背景一关，界面就把这一项藏起来，取色回落到壁纸。
     */
    var dynamicSeedSource: String
        get() = getString(KEY_SEED_SOURCE, com.xtt.mcpbox.ui.DynamicSeedSource.WALLPAPER.id)
            ?: com.xtt.mcpbox.ui.DynamicSeedSource.WALLPAPER.id
        set(value) = putString(KEY_SEED_SOURCE, value)

    /** 从自定义背景里取出来的种子色（0 = 还没取）。 */
    var backdropSeedColor: Int
        get() = getInt(KEY_BACKDROP_SEED, 0)
        set(value) = putInt(KEY_BACKDROP_SEED, value)

    /** 那个种子是从哪个背景文件取的（换背景 / 换类型后要重算）。 */
    var backdropSeedFile: String
        get() = getString(KEY_BACKDROP_SEED_FILE, "") ?: ""
        set(value) = putString(KEY_BACKDROP_SEED_FILE, value)

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

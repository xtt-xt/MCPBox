// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Application
import android.os.Build
import android.os.Environment
import com.xtt.mcpbox.core.ApprovalCenter
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.CustomToolStore
import com.xtt.mcpbox.core.DefaultRoots
import com.xtt.mcpbox.core.EventLog
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.core.MemoryStore
import com.xtt.mcpbox.core.PackStore
import com.xtt.mcpbox.core.PermissionStore
import com.xtt.mcpbox.core.ProfileStore
import com.xtt.mcpbox.core.ToolMetaStore
import com.xtt.mcpbox.core.PosixShLauncher
import com.xtt.mcpbox.core.ServerMeta
import com.xtt.mcpbox.core.ShellBackends
import com.xtt.mcpbox.core.ShellEnv
import com.xtt.mcpbox.core.SuLauncher
import com.xtt.mcpbox.ui.OverlayApproval

/**
 * 进程内单例：把 mcpcore 里的服务器核心和 Android 的各种能力接起来。
 * Service 和 Activity 共享同一个进程，所以这里直接持有实例即可。
 */
object AppCore {

    lateinit var prefs: Prefs
        private set
    lateinit var config: Config
        private set
    lateinit var log: EventLog
        private set
    lateinit var permissions: PermissionStore
        private set
    lateinit var approval: ApprovalCenter
        private set
    lateinit var host: AndroidHost
        private set
    lateinit var customTools: CustomToolStore
        private set
    lateinit var toolMeta: ToolMetaStore
        private set
    lateinit var memory: MemoryStore
        private set
    lateinit var packs: PackStore
        private set
    lateinit var profiles: ProfileStore
        private set
    lateinit var terminal: TerminalController
        private set

    /** Compose 主题变化时由 MainActivity 写进来，供悬浮窗使用。 */
    @Volatile var overlayPalette: OverlayPalette = OverlayPalette.Fallback
    lateinit var server: McpServer
        private set
    lateinit var app: Application
        private set

    @Volatile private var initialized = false

    /** 悬浮窗审批：只有服务在跑的时候才需要挂上去。 */
    var overlay: OverlayApproval? = null
        private set

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = application
            // 让核心层上报真实版本号
            val pkgInfo = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0)
            }.getOrNull()
            ServerMeta.appVersion = pkgInfo?.versionName
            ServerMeta.appVersionCode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo!!.longVersionCode.toInt()
                else @Suppress("DEPRECATION") pkgInfo!!.versionCode
            }.getOrDefault(0)
            DefaultRoots.provider = {
                runCatching { Environment.getExternalStorageDirectory().absolutePath }
                    .getOrElse { application.filesDir.absolutePath }
            }
            ServerMeta.deviceLabel = deviceLabel()
            prefs = Prefs(application)
            // 初始引导只在「装完第一次打开」时自动出现：这时候 SharedPreferences
            // 完全是空的。老用户升级上来里面已经有值了，直接视作早就走过一遍，
            // 免得升级后被平白挡一层引导（想再看一次走开发者模式里的强制入口）。
            if (!prefs.contains(Prefs.KEY_ONBOARD_DONE)) {
                prefs.onboardDone = !prefs.isBlank()
            }
            com.xtt.mcpbox.ui.OnboardingState.visible = !prefs.onboardDone
            config = Config(prefs)
            log = EventLog().also { it.enabled = config.logEnabled }
            permissions = PermissionStore(config, prefs)
            approval = ApprovalCenter(config, permissions, log)
            host = AndroidHost(application, config)
            customTools = CustomToolStore(config, prefs)
            toolMeta = ToolMetaStore(prefs)
            packs = PackStore(prefs)
            profiles = ProfileStore(java.io.File(application.filesDir, "profiles"), config)
            // 记忆库独立成文件，不塞进 SharedPreferences
            memory = MemoryStore(java.io.File(application.filesDir, "memory/graph.json"))

            // 语言：跟随系统时，系统语言不是中文就按英文走
            // 彩蛋语言（猫娘语）是否出现在语言列表里，由开发者模式里的「语言菜单」开关决定
            com.xtt.mcpbox.i18n.Lang.AndroidCatFlag.unlocked = prefs.languageMenu
            val sysLang = java.util.Locale.getDefault().language
            com.xtt.mcpbox.i18n.Lang.init(
                ctx = application,
                langId = prefs.appLang,
                englishOnly = sysLang != "zh"
            )
            // 把 mcpcore 的文案也接到同一份词表上（工具标题 / 说明 / 参数说明 / 网页控制台）
            com.xtt.mcpbox.core.CoreI18n.install { zh -> com.xtt.mcpbox.i18n.Lang.t(zh) }

            // Shell 环境 + 三个执行后端
            ShellEnv.home = runCatching { Environment.getExternalStorageDirectory().absolutePath }
                .getOrElse { application.filesDir.absolutePath }
            ShellEnv.tmp = application.cacheDir.absolutePath
            ShellEnv.extraPath = application.applicationInfo.nativeLibraryDir
            ShellBackends.register(PosixShLauncher())
            ShellBackends.register(SuLauncher())
            ShellBackends.register(ShizukuLauncher(application))
            ShizukuHelper.install(application)

            terminal = TerminalController(application)
            // AI 执行的命令也显示在 App 的「终端」页里
            com.xtt.mcpbox.core.ShellMirror.attach { text -> terminal.append(text) }
            server = McpServer(
                config = config,
                permissions = permissions,
                customTools = customTools,
                approval = approval,
                log = log,
                host = host,
                memory = memory,
                toolMeta = toolMeta,
                packs = packs,
                profiles = profiles
            )
            initialized = true
        }
    }

    fun deviceLabel(): String {
        val brand = Build.BRAND?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: ""
        val name = when {
            model.startsWith(brand, ignoreCase = true) -> model
            brand.isBlank() -> model
            else -> "$brand $model"
        }
        return "$name (Android ${Build.VERSION.RELEASE})"
    }

    /** 把配置写回磁盘并广播给需要感知变化的地方。 */
    fun saveConfig() {
        config.save()
        log.enabled = config.logEnabled
    }

    fun attachOverlay(): OverlayApproval {
        val existing = overlay
        if (existing != null) return existing
        val created = OverlayApproval(app)
        overlay = created
        approval.presenter = created
        return created
    }

    /** 恢复出厂设置（不动服务器以外的数据）。 */
    fun resetAll() {
        runCatching { com.xtt.mcpbox.server.McpService.stop(app) }
        prefs.clearAll()
        config.reload()
        if (config.token.isBlank()) {
            config.newToken()
            config.save()
        }
        permissions.load()
        customTools.load()
        log.clear()
    }

    fun detachOverlay() {
        overlay?.releaseAll()
        overlay = null
        if (approval.presenter != null) approval.presenter = null
    }
}

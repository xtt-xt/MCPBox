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
import com.xtt.mcpbox.core.PermissionStore
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
            config = Config(prefs)
            log = EventLog().also { it.enabled = config.logEnabled }
            permissions = PermissionStore(config, prefs)
            approval = ApprovalCenter(config, permissions, log)
            host = AndroidHost(application, config)
            customTools = CustomToolStore(config, prefs)

            // 语言：跟随系统时，系统语言不是中文就按英文走
            com.xtt.mcpbox.i18n.Lang.AndroidCatFlag.unlocked = prefs.catUnlocked
            val sysLang = java.util.Locale.getDefault().language
            com.xtt.mcpbox.i18n.Lang.init(
                ctx = application,
                langId = prefs.appLang,
                englishOnly = sysLang != "zh"
            )

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
            server = McpServer(
                config = config,
                permissions = permissions,
                customTools = customTools,
                approval = approval,
                log = log,
                host = host
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

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.MCPBoxApp
import com.xtt.mcpbox.NotificationHelper
import com.xtt.mcpbox.Prefs
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.i18n.L

/**
 * 常驻前台服务：App 退到后台、锁屏、被系统回收后都会把服务拉起来，
 * 只有用户主动点「停止服务」才会真正结束。
 *
 * 里面跑**两个互相独立**的服务：
 *  - MCP（HTTP），开关是 [Prefs.shouldRun]（首页那个总开关）
 *  - SSH / SFTP，开关是 `config.sshEnabled`（设置 → SSH 服务）
 * 两个都关掉时这个前台服务就没有存在意义了，会自己收掉；
 * 只开其中一个时它就只为那一个活着 —— 关掉 MCP 不会连带关掉 SSH。
 */
class McpService : Service() {

    companion object {
        const val ACTION_START = "com.xtt.mcpbox.action.START"
        const val ACTION_STOP = "com.xtt.mcpbox.action.STOP"

        /**
         * 只是「按当前配置对齐一次」：不动两个开关的值。
         * 打开 SSH、或者只关掉 MCP 时用 —— 服务要不要继续活着由配置决定。
         */
        const val ACTION_SYNC = "com.xtt.mcpbox.action.SYNC"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 当前实例（同进程里直接调 realign，不必走一次 startService）。 */
        @Volatile
        private var instance: McpService? = null

        /** 打开 MCP 服务（首页开关「开」）。 */
        fun start(context: Context) {
            AppCore.init(context.applicationContext as android.app.Application)
            AppCore.prefs.shouldRun = true
            val intent = Intent(context, McpService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 彻底停：MCP 与 SSH 一起关掉（通知栏「停止服务」、重置设置走这里）。 */
        fun stop(context: Context) {
            AppCore.prefs.shouldRun = false
            AppCore.config.sshEnabled = false
            AppCore.saveConfig()
            runCatching { context.stopService(Intent(context, McpService::class.java)) }
        }

        /** 打开 / 关掉 MCP 服务本身（首页那个开关）。SSH 不受影响。 */
        fun setMcpEnabled(context: Context, on: Boolean) {
            AppCore.init(context.applicationContext as android.app.Application)
            if (on) {
                start(context)
            } else {
                AppCore.prefs.shouldRun = false
                sync(context)
            }
        }

        /** 打开 / 关掉 SSH 服务（设置里的开关）。MCP 不受影响。 */
        fun setSshEnabled(context: Context, on: Boolean) {
            AppCore.init(context.applicationContext as android.app.Application)
            AppCore.config.sshEnabled = on
            AppCore.saveConfig()
            sync(context)
        }

        /**
         * 让服务进程按当前配置活一次：两个都关着就收掉，否则保证前台服务在跑、
         * 该起的起、该停的停。**不修改任何开关**。
         */
        fun sync(context: Context) {
            val app = context.applicationContext as android.app.Application
            AppCore.init(app)
            if (!AppCore.prefs.shouldRun && !AppCore.config.sshEnabled) {
                runCatching { context.stopService(Intent(context, McpService::class.java)) }
                return
            }
            val live = instance
            if (live != null && isRunning) {
                // 已经在跑：同进程直接对齐，省一次 start 和一次通知刷新
                live.realign()
                live.refreshNotification()
                return
            }
            val intent = Intent(context, McpService::class.java).setAction(ACTION_SYNC)
            runCatching { ContextCompat.startForegroundService(app, intent) }
        }

        fun restart(context: Context) {
            stop(context)
            Handler(Looper.getMainLooper()).postDelayed({ start(context) }, 600)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            refreshNotification()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppCore.init(applicationContext as MCPBoxApp)
        prefs = AppCore.prefs
        instance = this
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            prefs.shouldRun = false
            AppCore.config.sshEnabled = false
            AppCore.saveConfig()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_START) prefs.shouldRun = true
        if (!prefs.shouldRun && !AppCore.config.sshEnabled) {
            // 两个功能用户都主动关掉了，别偷偷复活
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        AppCore.attachOverlay().wakeScreenOnApproval = prefs.wakeScreenOnApproval
        realign()
        isRunning = true
        acquireWakeLock()
        startTicker()
        refreshNotification()
        return START_STICKY
    }

    /** 按两个开关把各自的服务器起停一次。 */
    private fun realign() {
        // MCP（HTTP）
        if (prefs.shouldRun) {
            if (!AppCore.server.isRunning) {
                val ok = AppCore.server.start()
                if (!ok) {
                    AppCore.log.add(
                        LogKind.ERROR, ok = false,
                        message = AppCore.server.lastError ?: L("服务器启动失败（端口可能被占用）")
                    )
                }
            }
        } else if (AppCore.server.isRunning) {
            AppCore.server.stop()
        }

        // SSH / SFTP：独立开关，和 MCP 互不牵连
        AppCore.syncSsh()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务里划掉 App：**进程会被系统回收**，这个服务也跟着结束。
        // 这里不做任何保活（那需要另外的手段）；只是把「实际发生了什么」写进日志，
        // 别再打印「服务器继续运行」那种与事实相反的提示。
        super.onTaskRemoved(rootIntent)
        if (prefs.shouldRun || AppCore.config.sshEnabled) {
            AppCore.log.add(LogKind.SYSTEM, message = L("App 被划掉，服务随之结束"))
        }
    }

    override fun onDestroy() {
        stopTicker()
        releaseWakeLock()
        AppCore.overlay?.releaseAll()
        // 服务停了：把浏览器的窗口和 WebView 一起收掉（不然会留下没人管的悬浮球）
        runCatching { AppCore.browser.release() }
        if (AppCore.server.isRunning) AppCore.server.stop()
        runCatching { AppCore.ssh.stop() }
        isRunning = false
        if (instance === this) instance = null
        NotificationHelper.cancelService(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

    private fun startForegroundCompat() {
        val notification = NotificationHelper.serviceNotification(
            this, L("MCP 文件盒正在运行"), statusText(), AppCore.config.port, AppCore.config.token
        )
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NotificationHelper.ID_SERVICE, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NotificationHelper.ID_SERVICE, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> startForeground(NotificationHelper.ID_SERVICE, notification)
        }
    }

    private fun statusText(): String {
        val s = AppCore.server.status()
        val sshRunning = AppCore.ssh.isRunning
        val sshLine = if (sshRunning) L("SSH 端口 %s").format(AppCore.config.sshPort) else null
        // 只开了 SSH 的时候别显示成「启动失败」—— MCP 没开是用户自己的选择
        if (!s.running) {
            return if (sshLine != null) {
                "$sshLine\n" + L("MCP 服务未开启")
            } else {
                L("启动失败：%s").format(s.lastError ?: L("未知原因"))
            }
        }
        return buildString {
            append(L("端口 %s · 已运行 %s").format(s.port, s.uptimeText))
            append(L(" · 请求 %s").format(s.total))
            if (s.pending > 0) append(L(" · 待审批 %s").format(s.pending))
            if (sshLine != null) append(" · ").append(sshLine)
            append('\n').append(L("点开查看地址和权限设置"))
        }
    }

    private fun refreshNotification() {
        val s = AppCore.server.status()
        val title = if (s.running || AppCore.ssh.isRunning) L("MCP 文件盒正在运行")
        else L("MCP 文件盒启动失败")
        NotificationHelper.updateService(this, title, statusText(), AppCore.config.port, AppCore.config.token)
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, 3000)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    private fun acquireWakeLock() {
        if (!prefs.keepAwake) return
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcpbox:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }
}

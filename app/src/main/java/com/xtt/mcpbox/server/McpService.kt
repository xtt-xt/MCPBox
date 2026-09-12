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

/**
 * 常驻前台服务：App 退到后台、锁屏、被系统回收后都会把服务器拉起来，
 * 只有用户主动点「停止服务」才会真正结束。
 */
class McpService : Service() {

    companion object {
        const val ACTION_START = "com.xtt.mcpbox.action.START"
        const val ACTION_STOP = "com.xtt.mcpbox.action.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            AppCore.init(context.applicationContext as android.app.Application)
            AppCore.prefs.shouldRun = true
            val intent = Intent(context, McpService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            AppCore.prefs.shouldRun = false
            runCatching { context.stopService(Intent(context, McpService::class.java)) }
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
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            prefs.shouldRun = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.shouldRun && action != ACTION_START) {
            // 用户之前主动关掉了，别偷偷复活
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.shouldRun = true

        startForegroundCompat()
        AppCore.attachOverlay().wakeScreenOnApproval = prefs.wakeScreenOnApproval
        if (!AppCore.server.isRunning) {
            val ok = AppCore.server.start()
            if (!ok) {
                AppCore.log.add(
                    LogKind.ERROR, ok = false,
                    message = AppCore.server.lastError ?: "服务器启动失败（端口可能被占用）"
                )
            }
        }
        isRunning = true
        acquireWakeLock()
        startTicker()
        refreshNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务里划掉 App 也不停服务（用户要求：只有主动关闭才停）
        super.onTaskRemoved(rootIntent)
        if (prefs.shouldRun) {
            AppCore.log.add(LogKind.SYSTEM, message = "App 被划掉，服务器继续运行")
        }
    }

    override fun onDestroy() {
        stopTicker()
        releaseWakeLock()
        AppCore.overlay?.releaseAll()
        if (AppCore.server.isRunning) AppCore.server.stop()
        isRunning = false
        NotificationHelper.cancelService(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

    private fun startForegroundCompat() {
        val status = AppCore.server.status()
        val notification = NotificationHelper.serviceNotification(
            this, "MCP 文件盒正在运行", statusText(), AppCore.config.port, AppCore.config.token
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
        return if (!s.running) {
            "启动失败：${s.lastError ?: "未知原因"}"
        } else buildString {
            append("端口 ").append(s.port).append(" · 已运行 ").append(s.uptimeText)
            append(" · 请求 ").append(s.total)
            if (s.pending > 0) append(" · 待审批 ").append(s.pending)
            append("\n点开查看地址和权限设置")
        }
    }

    private fun refreshNotification() {
        val s = AppCore.server.status()
        NotificationHelper.updateService(
            this,
            if (s.running) "MCP 文件盒正在运行" else "MCP 文件盒启动失败",
            statusText(), AppCore.config.port, AppCore.config.token
        )
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

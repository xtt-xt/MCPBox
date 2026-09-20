// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.HostInfo
import java.io.File

/** 给工具用的设备信息 / 通知能力。 */
class AndroidHost(private val context: Context, private val config: Config) : HostInfo {

    override fun deviceInfo(): Map<String, Any?> {
        val root = File(config.primaryRoot())
        val stat = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val total = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val free = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        return mapOf(
            "型号" to AppCore.deviceLabel(),
            "品牌" to Build.BRAND,
            "Android 版本" to Build.VERSION.RELEASE,
            "SDK" to Build.VERSION.SDK_INT,
            "ABI" to Build.SUPPORTED_ABIS.firstOrNull(),
            "App 版本" to appVersion(),
            "主根目录" to config.primaryRoot(),
            "根目录可用" to human(free),
            "根目录总计" to human(total),
            "外部存储可用" to human(runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrDefault(0L)),
            "电量" to batteryPercent()
        )
    }

    override fun notify(title: String, message: String): Boolean {
        return try {
            NotificationHelper.aiMessage(context, title, message)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 写系统剪贴板。UI 自动化输入中文时用：`input text` 只认 ASCII，
     * 非 ASCII 走「写剪贴板 → input keyevent 279（粘贴）」。
     * 写剪贴板不受 Android 10+ 的后台读取限制，粘贴由前台应用执行。
     */
    override fun setClipboard(text: String): Boolean {
        var ok = false
        val job = {
            ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mcpbox-ui", text))
                true
            }.getOrDefault(false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) job() else {
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { job(); latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        return ok
    }

    fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun batteryPercent(): String {
        return runCatching {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "未知"
        }.getOrDefault("未知")
    }

    private fun human(bytes: Long): String = when {
        bytes <= 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }

    /** 通知权限是否已经拿到（Android 13+ 需要运行时申请）。 */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)

    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }
}

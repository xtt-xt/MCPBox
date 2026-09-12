// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/** Identity/uptime of the embedded server, shared by the tools and the UI. */
object ServerMeta {
    const val NAME = "mcp-file-box"
    const val TITLE = "MCP 手机文件盒"

    /** 兜底版本号；App 启动时会用 BuildConfig.VERSION_NAME 覆盖 [appVersion]。 */
    const val VERSION = "1.0.0"
    const val PROTOCOL = "2025-06-18"

    @Volatile var appVersion: String? = null

    /** 内部版本（versionCode），每次发版 +1；由 Android 层写入。 */
    @Volatile var appVersionCode: Int = 0

    /** 对外展示的版本（优先真实 APK 版本）。 */
    val version: String get() = appVersion ?: VERSION

    /**
     * 完整版本号，形如 `v1.9.2-21`：
     * 前面的 `1.9.2` 是发布版本（按需求递增），
     * 后面的 `21` 是核心版本（versionCode，每次发版必 +1）。
     */
    val fullVersion: String
        get() = if (appVersionCode > 0) "v$version-$appVersionCode" else "v$version"

    var startTime: Long = System.currentTimeMillis()

    /** e.g. "Xiaomi 15 (Android 15)" - filled in by the Android layer. */
    @Volatile var deviceLabel: String = "Android 设备"

    fun uptimeMs(): Long = System.currentTimeMillis() - startTime

    fun uptimeText(): String {
        val ms = uptimeMs()
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h} 小时 ${m} 分"
            m > 0 -> "${m} 分 ${s} 秒"
            else -> "${s} 秒"
        }
    }
}

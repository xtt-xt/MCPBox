// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * 「有 Root / Shizuku 时进入 App 自动补齐缺失权限」里**不依赖 Android** 的那半：
 * 把「缺哪几项」翻译成「跑哪几条命令」。
 *
 * 放在 mcpcore 是因为这层可以在普通 JVM harness 里直接测；
 * 「现在到底缺哪几项」要问系统，那部分在 App 层（AndroidHost）。
 *
 * 只规划**不会把正在运行的自己搞重启**的几项：
 *
 * | 项 | 命令 | 为什么安全 |
 * |---|---|---|
 * | 文件访问 | `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` | 改的是 appops，不重启进程 |
 * | 悬浮窗 | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` | 同上 |
 * | 忽略电池优化 | `dumpsys deviceidle whitelist +<pkg>` | 只是加白名单 |
 *
 * **通知（POST_NOTIFICATIONS）故意不做**：它是运行时权限，`pm grant` 会让系统把本进程
 * 杀掉重启 —— 启动阶段干这个，界面刚画出来就没了，得不偿失；留给用户在系统弹窗里点一下。
 */
object AutoGrant {

    const val STORAGE = "storage"
    const val OVERLAY = "overlay"
    const val BATTERY = "battery"
    const val NOTIFICATION = "notification"

    /** 认识的项（顺序 = 自动补齐的执行顺序）。 */
    val ALL = listOf(STORAGE, OVERLAY, BATTERY, NOTIFICATION)

    /** 可以静默补齐的项。 */
    val SILENT = listOf(STORAGE, OVERLAY, BATTERY)

    /** 这一项能不能静默开（通知不行）。 */
    fun isSilent(id: String): Boolean = id in SILENT

    /**
     * 一项对应的命令。未知 id、以及不能静默的项（通知）返回 null ——
     * 调用方看到 null 就该走「系统页面 / 弹窗」那条路。
     */
    fun commandFor(id: String, pkg: String): String? = when (id) {
        STORAGE -> "appops set $pkg MANAGE_EXTERNAL_STORAGE allow"
        OVERLAY -> "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        BATTERY -> "dumpsys deviceidle whitelist +$pkg"
        else -> null
    }

    /**
     * 把「缺的项」规划成有序的 (id, 命令) 列表：
     * 按 [SILENT] 的固定顺序、忽略不认识的 id、跳过不能静默的项。
     */
    fun plan(pkg: String, missing: Collection<String>): List<Pair<String, String>> =
        SILENT.filter { it in missing }.mapNotNull { id ->
            commandFor(id, pkg)?.let { id to it }
        }

    /** 这次值不值得去开 shell：包名非空，且至少有一项真的能静默补。 */
    fun shouldRun(pkg: String, missing: Collection<String>): Boolean =
        pkg.isNotBlank() && plan(pkg, missing).isNotEmpty()
}

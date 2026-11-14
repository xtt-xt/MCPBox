// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 「App 现在有没有可见界面」——定位功能要用它做判断。
 *
 * 为什么需要：Android 的「使用应用时允许」定位权限**只在 App 有可见界面时才投递位置**。
 * App 在后台（只剩前台服务）时，`requestLocationUpdates` 注册成功却一个点都收不到，
 * logcat 里只有 `LocationManagerService: noteOp denied`。所以取位置前先问一句：
 *  · 有界面 → 可以要一个实时位置；
 *  · 没界面 → 只能读系统缓存，并把它有多旧一起报出去。
 *
 * 用 [Application.ActivityLifecycleCallbacks] 自己数「活动中的 Activity」，
 * 比 `ActivityManager.getRunningAppProcesses()` 可靠（后者在 Android 5.1+ 只返回自己）。
 */
object LocationVisibility : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var started = 0

    fun install(app: Application) {
        this.app = app.applicationContext as Application
        app.registerActivityLifecycleCallbacks(this)
    }

    /** 用来在「回到前台」那一刻悄悄取一次位置（见 [LocationWarmer]）。 */
    @Volatile
    private var app: Application? = null

    fun hasVisibleUi(): Boolean = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) {
            // App 刚回到前台：这时候系统才肯投递位置，赶紧取一次存着，
            // 等 AI 在后台调 get_location 时就有新鲜坐标可读（不用等 GPS）
            app?.let { LocationWarmer.warmUp(it) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.server

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xtt.mcpbox.AppCore

/** 开机（或系统重启服务）后，如果用户没有主动关闭过，就自动把服务器拉起来。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        AppCore.init(context.applicationContext as Application)
        if (AppCore.prefs.shouldRun && AppCore.prefs.autoStartBoot) {
            runCatching { McpService.start(context) }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Application
import com.xtt.mcpbox.server.McpService

class MCPBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCore.init(this)
        NotificationHelper.createChannels(this)
        // 上一次是开机自启/被杀后重启的场景：如果用户没有主动关闭，就把服务拉起来
        if (AppCore.prefs.shouldRun && !McpService.isRunning) {
            runCatching { McpService.start(this) }
        }
    }
}

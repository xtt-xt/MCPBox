// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Application
import com.xtt.mcpbox.server.McpService

class MCPBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCore.init(this)
        // 定位要先知道「App 现在有没有可见界面」：没有界面时系统不投递实时位置，只能读缓存
        // （见 AndroidHost.locationFix 的说明）
        LocationVisibility.install(this)
        NotificationHelper.createChannels(this)
        // 上一次是开机自启/被杀后重启的场景：如果用户没有主动关闭，就把服务拉起来
        if ((AppCore.prefs.shouldRun || AppCore.config.sshEnabled) && !McpService.isRunning) {
            runCatching { McpService.sync(this) }
        }
    }
}

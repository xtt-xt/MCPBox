// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.core.ServerMeta

@Composable
fun SettingsScreen(
    ctx: Context,
    status: McpServer.ServerStatus,
    revision: Int,
    themeId: String,
    onThemeChange: (String) -> Unit,
    onOpenCustomTools: () -> Unit,
    onChanged: () -> Unit,
    onRestartService: () -> Unit
) {
    var showPort by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var shellTimeoutState by remember(revision) {
        mutableStateOf(AppCore.config.shellTimeoutMs / 1000)
    }
    var timeoutState by remember(revision) { mutableStateOf(AppCore.config.approvalTimeoutMs / 1000) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        PageHeader(title = "设置", subtitle = "v${ServerMeta.version} · ${AppCore.deviceLabel()}")

        // ---------------------------------------------------------- 外观
        GroupLabel("外观")
        CardGroup(
            listOf(
                RowSpec(
                    title = "颜色模式",
                    subtitle = "跟随壁纸取色，或从预设里挑一个",
                    icon = Icons.Filled.Star,
                    trailing = {
                        PillDropdown(
                            value = ThemeChoice.of(themeId).label,
                            options = ThemeChoice.entries.map { it.label }
                        ) { index ->
                            onThemeChange(ThemeChoice.entries[index].id)
                        }
                    }
                )
            )
        )

        // ---------------------------------------------------------- 网络
        GroupLabel("网络")
        CardGroup(
            listOf(
                RowSpec(
                    title = "监听端口",
                    subtitle = if (status.running) "正在监听 ${status.port}" else "当前设置 ${AppCore.config.port}",
                    icon = Icons.Filled.Share,
                    trailing = {
                        PillButton("修改", outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) {
                            showPort = true
                        }
                    }
                ),
                switchSpec(
                    title = "允许局域网访问",
                    subtitle = if (AppCore.config.bindAll) "同一 Wi-Fi 下的电脑/平板也能连"
                    else "只允许本机 127.0.0.1",
                    icon = Icons.Filled.Share,
                    checked = AppCore.config.bindAll
                ) {
                    AppCore.config.bindAll = it
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = "响应格式",
                    subtitle = "自动最省心，不兼容时再手动切",
                    icon = Icons.Filled.Refresh,
                    trailing = {
                        val labels = listOf("自动", "JSON", "SSE")
                        val values = listOf(Config.Modes.AUTO, Config.Modes.JSON, Config.Modes.SSE)
                        val current = values.indexOf(AppCore.config.responseMode).coerceAtLeast(0)
                        PillDropdown(labels[current], labels) { index ->
                            AppCore.config.responseMode = values[index]
                            AppCore.saveConfig()
                            onChanged()
                        }
                    }
                )
            )
        )

        // ------------------------------------------------------ 网页控制台
        GroupLabel("网页控制台")
        CardGroup(
            listOf(
                switchSpec(
                    title = "仅本机访问",
                    subtitle = if (AppCore.config.consoleLocalOnly) {
                        "只响应 localhost（127.0.0.1），局域网设备一律被拒"
                    } else {
                        "局域网里的设备也能打开网页（靠令牌保护）"
                    },
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.consoleLocalOnly
                ) { on ->
                    AppCore.config.consoleLocalOnly = on
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = "密码保护",
                    subtitle = if (AppCore.config.consoleAuthEnabled) {
                        "打开网页要先登录；程序用 token 调用不受影响"
                    } else {
                        "打开网页不需要密码"
                    },
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.consoleAuthEnabled
                ) { on ->
                    AppCore.config.consoleAuthEnabled = on
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = "访问密码",
                    subtitle = if (AppCore.config.consolePassword.isBlank()) {
                        "还没设置，默认拿访问令牌当密码"
                    } else {
                        "已设置（${AppCore.config.consolePassword.length} 位）"
                    },
                    icon = Icons.Filled.Create,
                    onClick = {
                        passwordText = AppCore.config.consolePassword
                        showPassword = true
                    }
                )
            )
        )

        // ---------------------------------------------------------- 安全
        GroupLabel("安全")
        CardGroup(
            listOf(
                switchSpec(
                    title = "启用访问令牌",
                    subtitle = if (AppCore.config.tokenEnabled) "客户端需要带 token 才能连接"
                    else "任何设备都能连（不推荐）",
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.tokenEnabled
                ) {
                    AppCore.config.tokenEnabled = it
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("访问令牌", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
                        Text(
                            if (showToken) AppCore.config.token else "•".repeat(14),
                            color = if (showToken) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    RowIconButton(
                        onClick = { showToken = !showToken },
                        icon = Icons.Filled.Star,
                        desc = if (showToken) "隐藏" else "显示"
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "复制", Modifier.weight(1f), outlined = true,
                        color = MaterialTheme.colorScheme.primary, compact = true
                    ) {
                        copyText(ctx, AppCore.config.token, "令牌已复制")
                    }
                    PillButton(
                        "重置", Modifier.weight(1f), outlined = true, color = Sem.warn, compact = true
                    ) {
                        AppCore.config.newToken()
                        AppCore.saveConfig()
                        onChanged()
                        toast(ctx, "已生成新令牌，旧配置需要更新")
                    }
                }
            }
            CardBox {
                Text("审批超时", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
                Text(
                    "${timeoutState} 秒 · 超时自动拒绝",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Slider(
                    value = timeoutState.toFloat(),
                    onValueChange = { timeoutState = it.toLong() },
                    onValueChangeFinished = {
                        AppCore.config.approvalTimeoutMs = timeoutState * 1000
                        AppCore.saveConfig()
                        onChanged()
                    },
                    valueRange = 15f..600f,
                    steps = 38
                )
            }
        }

        // ---------------------------------------------------------- 终端与命令
        GroupLabel("终端与命令")
        CardGroup(
            listOf(
                RowSpec(
                    title = "自定义工具",
                    subtitle = "${AppCore.customTools.tools.size} 个 · 导入导出、也可以让 AI 自己创建",
                    icon = Icons.Filled.Build,
                    onClick = onOpenCustomTools
                ),
                RowSpec(
                    title = "命令后端优先级",
                    subtitle = "auto 时依次尝试；Shizuku 要先去「终端」页授权",
                    icon = Icons.Filled.Share,
                    trailing = {
                        val prefs = listOf("shizuku,root,app", "root,shizuku,app", "app")
                        val labels = listOf("Shizuku→Root→应用", "Root→Shizuku→应用", "只用应用沙箱")
                        val current = prefs.indexOf(AppCore.config.shellPreference).coerceAtLeast(0)
                        PillDropdown(labels[current], labels) { index ->
                            AppCore.config.shellPreference = prefs[index]
                            AppCore.saveConfig()
                            onChanged()
                        }
                    }
                ),
                RowSpec(
                    title = "命令规则",
                    subtitle = "${AppCore.permissions.commandRules().size} 条 · 在「权限」页里管理",
                    icon = Icons.Filled.Lock
                )
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            CardBox {
                Text("命令默认超时", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
                Text(
                    "${shellTimeoutState} 秒 · AI 调用 run_shell 时的上限",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Slider(
                    value = shellTimeoutState.toFloat(),
                    onValueChange = { shellTimeoutState = it.toLong() },
                    onValueChangeFinished = {
                        AppCore.config.shellTimeoutMs = shellTimeoutState * 1000
                        AppCore.saveConfig()
                        onChanged()
                    },
                    valueRange = 10f..300f,
                    steps = 28
                )
            }
        }

        // ---------------------------------------------------------- 后台运行
        GroupLabel("后台运行")
        CardGroup(
            listOf(
                switchSpec(
                    title = "保持 CPU 唤醒",
                    subtitle = "长时间会话更稳定，会稍微费电",
                    icon = Icons.Filled.Warning,
                    checked = AppCore.prefs.keepAwake
                ) {
                    AppCore.prefs.keepAwake = it
                    onChanged()
                },
                switchSpec(
                    title = "开机自动启动",
                    subtitle = "重启手机后自动把服务器打开",
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.prefs.autoStartBoot
                ) {
                    AppCore.prefs.autoStartBoot = it
                    onChanged()
                },
                switchSpec(
                    title = "审批时点亮屏幕",
                    subtitle = "有请求时亮屏，方便马上看到弹窗",
                    icon = Icons.Filled.Notifications,
                    checked = AppCore.prefs.wakeScreenOnApproval
                ) {
                    AppCore.prefs.wakeScreenOnApproval = it
                    AppCore.overlay?.wakeScreenOnApproval = it
                    onChanged()
                },
                switchSpec(
                    title = "记录日志",
                    subtitle = "保留最近 800 条调用/审批记录",
                    icon = Icons.Filled.Info,
                    checked = AppCore.config.logEnabled
                ) {
                    AppCore.config.logEnabled = it
                    AppCore.log.enabled = it
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        // ---------------------------------------------------------- 关于
        GroupLabel("关于")
        CardColumn {
            CardBox {
                KeyValue("版本", "v${ServerMeta.version}")
                KeyValue("设备", status.device)
                KeyValue("工具数量", "${status.toolCount} 个（自定义 ${status.customToolCount}）")
                KeyValue("MCP 协议", ServerMeta.PROTOCOL)
                KeyValue("Shell 后端", com.xtt.mcpbox.core.ShellBackends.available().joinToString("、") { it.label }
                    .ifBlank { "仅文件操作" })
                KeyValue("服务状态", if (status.running) "运行中（端口 ${status.port}）" else "已停止")
                KeyValue("允许目录", status.roots.joinToString("、"))
            }
        }
        Spacer(Modifier.height(7.dp))
        CardGroup(
            listOf(
                RowSpec(
                    title = "重启服务器",
                    subtitle = "改完端口或想重新开始会话时用",
                    icon = Icons.Filled.Settings,
                    onClick = onRestartService
                )
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            PillButton("重置全部设置", Modifier.fillMaxWidth(), outlined = true, color = Sem.bad) {
                showReset = true
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPort) {
        var portText by remember { mutableStateOf(AppCore.config.port.toString()) }
        AlertDialog(
            onDismissRequest = { showPort = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("监听端口", fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                        label = { Text("1024 - 65535") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "改完会自动重启服务，客户端里的地址也要跟着改。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        toast(ctx, "端口需要 1024 - 65535")
                    } else {
                        AppCore.config.port = p
                        AppCore.saveConfig()
                        showPort = false
                        if (status.running) onRestartService()
                        toast(ctx, "端口已改为 $p")
                        onChanged()
                    }
                }) { Text("应用", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPort = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showPassword) {
        AlertDialog(
            onDismissRequest = { showPassword = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("网页访问密码", fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { v -> passwordText = v.take(64) },
                        label = { Text("新密码（留空 = 用访问令牌）") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "只影响浏览器打开的网页控制台；AI 客户端照旧用访问令牌连接。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.config.consolePassword = passwordText.trim()
                    AppCore.saveConfig()
                    showPassword = false
                    toast(ctx, if (passwordText.isBlank()) "已清空，网页密码改用访问令牌" else "网页密码已保存")
                    onChanged()
                }) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPassword = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("重置全部设置？", fontSize = 20.sp) },
            text = {
                Text(
                    "会把权限、端口、令牌、目录等全部恢复默认，并停止服务器。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    AppCore.resetAll()
                    onChanged()
                }) { Text("重置", color = Sem.bad) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun RowIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String) {
    RoundIconButton(icon, desc, size = 40, onClick = onClick)
}

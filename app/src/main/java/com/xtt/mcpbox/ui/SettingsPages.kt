// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.core.ServerMeta

/**
 * 设置页的各个子页。
 *
 * 顶层（[SettingsScreen]）只放入口行，具体的开关 / 滑块 / 下拉都在这里 ——
 * 这样设置首页永远一屏扫得完，加功能只是多一行，不会退化成长列表。
 */

/** 子页的统一骨架：自己的大标题 + 返回按钮 + 可滚动内容。 */
@Composable
internal fun SettingsPageShell(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = title,
            subtitle = subtitle,
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )
        content()
    }
}

/* ------------------------------------------------------------ 外观与语言 */

@Composable
internal fun AppearanceSettingsPage(
    ctx: Context,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onOpenSeed: () -> Unit,
    onOpenLangPack: () -> Unit,
    onBack: () -> Unit
) {
    val sdkOk = android.os.Build.VERSION.SDK_INT >= 31
    val langChoices = com.xtt.mcpbox.i18n.Lang.languageChoices()

    SettingsPageShell(L("外观与语言"), L("主题配色、颜色模式与界面语言"), onBack) {
        GroupLabel(L("主题"))
        CardGroup(
            listOfNotNull(
                switchSpec(
                    title = L("动态取色"),
                    subtitle = if (sdkOk) L("用系统壁纸的强调色当种子，Material You 原版配色")
                    else L("需要 Android 12 及以上，当前系统不支持"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    checked = AppCore.prefs.dynamicColor
                ) { on ->
                    if (sdkOk) {
                        AppCore.prefs.dynamicColor = on
                        onThemeChanged()
                    }
                },
                // 动态取色开着的时候，种子色不起作用，就不显示了
                if (!AppCore.prefs.dynamicColor) RowSpec(
                    title = L("种子颜色"),
                    subtitle = L("整套配色都由这个颜色派生"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Create,
                    onClick = onOpenSeed,
                    trailing = {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(AppCore.prefs.seedColor))
                        )
                    }
                ) else null,
                dropdownSpec(
                    title = L("调色板样式"),
                    subtitle = L("同一个种子色，算法不同味道不同"),
                    icon = Icons.Filled.Star,
                    options = PaletteStyle.entries.map { L(it.label) },
                    selectedIndex = PaletteStyle.entries.indexOf(PaletteStyle.of(AppCore.prefs.paletteStyle))
                ) { index ->
                    AppCore.prefs.paletteStyle = PaletteStyle.entries[index].id
                    onThemeChanged()
                },
                dropdownSpec(
                    title = L("颜色模式"),
                    subtitle = L("深色 / 浅色 / 跟随系统"),
                    icon = Icons.Filled.Star,
                    options = DarkMode.entries.map { L(it.label) },
                    selectedIndex = DarkMode.entries.indexOf(DarkMode.of(AppCore.prefs.darkMode))
                ) { index ->
                    AppCore.prefs.darkMode = DarkMode.entries[index].id
                    onThemeChanged()
                }
            )
        )

        GroupLabel(L("语言"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("语言"),
                    subtitle = L("中文 / English，也可以导入别人做的语言包"),
                    icon = Icons.Filled.Info,
                    options = langChoices.map { it.second },
                    selectedIndex = langChoices
                        .indexOfFirst { it.first == AppCore.prefs.appLang }
                        .coerceAtLeast(0)
                ) { index ->
                    AppCore.prefs.appLang = langChoices[index].first
                    com.xtt.mcpbox.i18n.Lang.init(
                        ctx, AppCore.prefs.appLang, com.xtt.mcpbox.i18n.Lang.systemIsEnglish
                    )
                    onLangChanged()
                },
                RowSpec(
                    title = L("语言包"),
                    subtitle = L("导出模板去翻译，或者导入别人填好的"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info,
                    onClick = onOpenLangPack
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 网络与访问 */

@Composable
internal fun NetworkSettingsPage(
    status: McpServer.ServerStatus,
    onChanged: () -> Unit,
    onOpenPort: () -> Unit,
    onOpenPassword: () -> Unit,
    onBack: () -> Unit
) {
    SettingsPageShell(L("网络与访问"), L("监听端口、局域网与网页控制台"), onBack) {
        GroupLabel(L("网络"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("监听端口"),
                    subtitle = if (status.running) L("正在监听 %s").format(status.port)
                    else L("当前设置 %s").format(AppCore.config.port),
                    icon = Icons.Filled.Share,
                    trailing = {
                        PillButton(L("修改"), outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) {
                            onOpenPort()
                        }
                    }
                ),
                switchSpec(
                    title = L("允许局域网访问"),
                    subtitle = if (AppCore.config.bindAll) L("同一 Wi-Fi 下的电脑/平板也能连")
                    else L("只允许本机 127.0.0.1"),
                    icon = Icons.Filled.Share,
                    checked = AppCore.config.bindAll
                ) {
                    AppCore.config.bindAll = it
                    AppCore.saveConfig()
                    onChanged()
                },
                dropdownSpec(
                    title = L("响应格式"),
                    subtitle = L("自动最省心，不兼容时再手动切"),
                    icon = Icons.Filled.Refresh,
                    options = listOf(L("自动"), "JSON", "SSE"),
                    selectedIndex = listOf(Config.Modes.AUTO, Config.Modes.JSON, Config.Modes.SSE)
                        .indexOf(AppCore.config.responseMode).coerceAtLeast(0)
                ) { index ->
                    AppCore.config.responseMode =
                        listOf(Config.Modes.AUTO, Config.Modes.JSON, Config.Modes.SSE)[index]
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        GroupLabel(L("网页控制台"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("仅本机访问"),
                    subtitle = if (AppCore.config.consoleLocalOnly) {
                        L("只响应 localhost（127.0.0.1），局域网设备一律被拒")
                    } else {
                        L("局域网里的设备也能打开网页（靠令牌保护）")
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
                    title = L("密码保护"),
                    subtitle = if (AppCore.config.consoleAuthEnabled) {
                        L("打开网页要先登录；程序用 token 调用不受影响")
                    } else {
                        L("打开网页不需要密码")
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
                    title = L("访问密码"),
                    subtitle = if (AppCore.config.consolePassword.isBlank()) {
                        L("还没设置，默认拿访问令牌当密码")
                    } else {
                        L("已设置（%s 位）").format(AppCore.config.consolePassword.length)
                    },
                    icon = Icons.Filled.Create,
                    onClick = onOpenPassword
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 安全与审批 */

@Composable
internal fun SecuritySettingsPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }
    val approvalUnlimited = AppCore.config.approvalTimeoutMs <= 0L
    // 「不限时」时 timeoutMs = 0，滑块要拿上次的有限值来显示，否则滑块会落到范围外面
    var timeoutState by remember(revision) {
        mutableStateOf(
            (AppCore.config.approvalTimeoutMs / 1000).takeIf { it > 0L }
                ?: AppCore.prefs.approvalTimeoutLastSec
        )
    }

    SettingsPageShell(L("安全与审批"), L("访问令牌与审批超时"), onBack) {
        GroupLabel(L("访问令牌"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("启用访问令牌"),
                    subtitle = if (AppCore.config.tokenEnabled) L("客户端需要带 token 才能连接")
                    else L("任何设备都能连（不推荐）"),
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
                        Text(L("访问令牌"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
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
                        desc = if (showToken) L("隐藏") else L("显示")
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        L("复制"), Modifier.weight(1f), outlined = true,
                        color = MaterialTheme.colorScheme.primary, compact = true
                    ) {
                        copyText(ctx, AppCore.config.token, L("令牌已复制"))
                    }
                    PillButton(
                        L("重置"), Modifier.weight(1f), outlined = true, color = Sem.warn, compact = true
                    ) {
                        AppCore.config.newToken()
                        AppCore.saveConfig()
                        onChanged()
                        toast(ctx, L("已生成新令牌，旧配置需要更新"))
                    }
                }
            }
        }

        GroupLabel(L("审批"))
        Spacer(Modifier.height(0.dp))
        // 滑块行 + 「不限时」开关同属一组：首尾圆角、中间直角，视觉上连成一体
        CardGroup(
            listOf(
                RowSpec(
                    title = L("审批超时"),
                    subtitle = if (approvalUnlimited) L("不限制 · 弹窗不会自动消失，AI 一直等你")
                    else L("%s 秒 · 超时自动拒绝").format(timeoutState),
                    subtitleColor = if (approvalUnlimited) Sem.warn else null,
                    content = {
                        Slider(
                            value = timeoutState.toFloat(),
                            onValueChange = { timeoutState = it.toLong() },
                            onValueChangeFinished = {
                                AppCore.config.approvalTimeoutMs = timeoutState * 1000
                                AppCore.prefs.approvalTimeoutLastSec = timeoutState
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 15f..600f,
                            steps = 38,
                            // 不限时时滑块变灰：值仍然显示着上次的秒数，但说了不算
                            enabled = !approvalUnlimited
                        )
                    }
                ),
                switchSpec(
                    title = L("审批不限时"),
                    subtitle = L("打开后审批弹窗不会自动消失，AI 会一直等你答复（上面的秒数失效）"),
                    icon = Icons.Filled.Warning,
                    checked = approvalUnlimited
                ) { on ->
                    if (on) {
                        if (AppCore.config.approvalTimeoutMs > 0L) {
                            AppCore.prefs.approvalTimeoutLastSec =
                                AppCore.config.approvalTimeoutMs / 1000
                        }
                        AppCore.config.approvalTimeoutMs = 0L
                    } else {
                        timeoutState = AppCore.prefs.approvalTimeoutLastSec.coerceIn(15L, 600L)
                        AppCore.config.approvalTimeoutMs = timeoutState * 1000
                    }
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )
    }
}

/* ------------------------------------------------------------ 终端与命令 */

@Composable
internal fun ShellSettingsPage(
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val shellUnlimited = AppCore.config.shellTimeoutMs <= 0L
    var shellTimeoutState by remember(revision) {
        mutableStateOf(
            (AppCore.config.shellTimeoutMs / 1000).takeIf { it > 0L }
                ?: AppCore.prefs.shellTimeoutLastSec
        )
    }

    SettingsPageShell(L("终端与命令"), L("命令后端、命令规则与默认超时"), onBack) {
        GroupLabel(L("命令执行"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("命令后端优先级"),
                    subtitle = L("auto 时依次尝试；Shizuku 要先去「终端」页授权"),
                    icon = Icons.Filled.Share,
                    options = listOf(L("Shizuku→Root→应用"), L("Root→Shizuku→应用"), L("只用应用沙箱")),
                    selectedIndex = listOf("shizuku,root,app", "root,shizuku,app", "app")
                        .indexOf(AppCore.config.shellPreference).coerceAtLeast(0)
                ) { index ->
                    AppCore.config.shellPreference =
                        listOf("shizuku,root,app", "root,shizuku,app", "app")[index]
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("命令规则"),
                    subtitle = L("%s 条 · 在「权限」页里管理").format(AppCore.permissions.commandRules().size),
                    icon = Icons.Filled.Lock
                )
            )
        )

        GroupLabel(L("超时"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("命令默认超时"),
                    subtitle = if (shellUnlimited) L("不限制 · 命令一直跑到自己结束")
                    else L("%s 秒 · AI 调用 run_shell 时的上限").format(shellTimeoutState),
                    subtitleColor = if (shellUnlimited) Sem.warn else null,
                    content = {
                        Slider(
                            value = shellTimeoutState.toFloat(),
                            onValueChange = { shellTimeoutState = it.toLong() },
                            onValueChangeFinished = {
                                AppCore.config.shellTimeoutMs = shellTimeoutState * 1000
                                AppCore.prefs.shellTimeoutLastSec = shellTimeoutState
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 10f..300f,
                            steps = 28,
                            enabled = !shellUnlimited
                        )
                    }
                ),
                switchSpec(
                    title = L("命令不限时"),
                    subtitle = L("打开后 AI 执行的命令会一直跑到自己结束，不会中途被掐断（上面的秒数失效）"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Warning,
                    checked = shellUnlimited
                ) { on ->
                    if (on) {
                        if (AppCore.config.shellTimeoutMs > 0L) {
                            AppCore.prefs.shellTimeoutLastSec = AppCore.config.shellTimeoutMs / 1000
                        }
                        AppCore.config.shellTimeoutMs = 0L
                    } else {
                        shellTimeoutState = AppCore.prefs.shellTimeoutLastSec.coerceIn(10L, 300L)
                        AppCore.config.shellTimeoutMs = shellTimeoutState * 1000
                    }
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )
    }
}

/* ------------------------------------------------------------ AI 与工具 */

@Composable
internal fun AiSettingsPage(
    status: McpServer.ServerStatus,
    revision: Int,
    onChanged: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenMemory: () -> Unit,
    onBack: () -> Unit
) {
    var ttlMinutesState by remember(revision) { mutableStateOf(AppCore.config.profileTtlMinutes) }

    SettingsPageShell(L("AI 与工具"), L("工具管理、记忆库与会话状态"), onBack) {
        GroupLabel(L("工具与记忆"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("工具管理"),
                    subtitle = L("共 %s 个 · 可单独启用/禁用、设权限（跟随 / 允许 / 询问 / 拒绝）；右上角 + 新建自定义工具").format(status.toolCount),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Build,
                    onClick = onOpenTools
                ),
                RowSpec(
                    title = L("记忆库"),
                    subtitle = L("给 AI 的长期记忆：%s 个实体 · %s 条关系")
                        .format(AppCore.memory.graph.entities.size, AppCore.memory.graph.relations.size),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    onClick = onOpenMemory
                )
            )
        )

        GroupLabel(L("会话"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("会话状态自动重置"),
                    subtitle = if (AppCore.config.profileTtlEnabled)
                        L("超过 %s 分钟没请求就回到默认工具包（新对话更干净）")
                            .format(AppCore.config.profileTtlMinutes)
                    else L("不自动重置，激活状态一直保持到手动改"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.config.profileTtlEnabled
                ) {
                    AppCore.config.profileTtlEnabled = it
                    AppCore.saveConfig()
                    onChanged()
                },
                if (AppCore.config.profileTtlEnabled) RowSpec(
                    title = L("重置间隔"),
                    subtitle = L("%s 分钟没动静就重置").format(ttlMinutesState),
                    icon = Icons.Filled.Refresh,
                    trailing = {
                        Slider(
                            value = ttlMinutesState.toFloat(),
                            onValueChange = { ttlMinutesState = it.toInt() },
                            onValueChangeFinished = {
                                AppCore.config.profileTtlMinutes = ttlMinutesState
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 5f..240f,
                            modifier = Modifier.width(160.dp)
                        )
                    }
                ) else null
            ).filterNotNull()
        )
    }
}

/* ------------------------------------------------------------ 后台与运行 */

@Composable
internal fun BackgroundSettingsPage(
    onChanged: () -> Unit,
    onRestartService: () -> Unit,
    onOpenReset: () -> Unit,
    onBack: () -> Unit
) {
    SettingsPageShell(L("后台与运行"), L("保活、日志与服务控制"), onBack) {
        GroupLabel(L("后台"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("保持 CPU 唤醒"),
                    subtitle = L("长时间会话更稳定，会稍微费电"),
                    icon = Icons.Filled.Warning,
                    checked = AppCore.prefs.keepAwake
                ) {
                    AppCore.prefs.keepAwake = it
                    onChanged()
                },
                switchSpec(
                    title = L("开机自动启动"),
                    subtitle = L("重启手机后自动把服务器打开"),
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.prefs.autoStartBoot
                ) {
                    AppCore.prefs.autoStartBoot = it
                    onChanged()
                },
                switchSpec(
                    title = L("审批时点亮屏幕"),
                    subtitle = L("有请求时亮屏，方便马上看到弹窗"),
                    icon = Icons.Filled.Notifications,
                    checked = AppCore.prefs.wakeScreenOnApproval
                ) {
                    AppCore.prefs.wakeScreenOnApproval = it
                    AppCore.overlay?.wakeScreenOnApproval = it
                    onChanged()
                },
                switchSpec(
                    title = L("记录日志"),
                    subtitle = L("保留最近 800 条调用/审批记录"),
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

        GroupLabel(L("服务"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("重启服务器"),
                    subtitle = L("改完端口或想重新开始会话时用"),
                    icon = Icons.Filled.Settings,
                    onClick = onRestartService
                )
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            PillButton(L("重置全部设置"), Modifier.fillMaxWidth(), outlined = true, color = Sem.bad) {
                onOpenReset()
            }
        }
    }
}

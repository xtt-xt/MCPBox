// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenAbout: () -> Unit,
    onChanged: () -> Unit,
    onRestartService: () -> Unit
) {
    var showPort by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var showSeed by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }
    var langInfo by remember { mutableStateOf("") }
    val importLang = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast(ctx, L("读不到文件内容"))
            } else {
                com.xtt.mcpbox.i18n.Lang.importPack(ctx, text).fold(
                    onSuccess = { (id, count) ->
                        AppCore.prefs.appLang = id
                        com.xtt.mcpbox.i18n.Lang.init(ctx, id, com.xtt.mcpbox.i18n.Lang.systemIsEnglish)
                        langInfo = "已导入语言包 $id（$count 条译文）"
                        onLangChanged()
                    },
                    onFailure = { langInfo = "导入失败：${it.message}" }
                )
            }
        }
    }
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
        PageHeader(title = L("设置"), subtitle = "v${ServerMeta.version} · ${AppCore.deviceLabel()}")

        // ---------------------------------------------------------- 外观
        GroupLabel(L("外观"))
        val sdkOk = android.os.Build.VERSION.SDK_INT >= 31
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
                    onClick = { showSeed = true },
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
                    title = L("语言"),
                    subtitle = L("中文 / English，也可以导入别人做的语言包"),
                    icon = Icons.Filled.Info,
                    options = listOf("跟随系统", "中文", "English"),
                    selectedIndex = when (AppCore.prefs.appLang) {
                        "zh" -> 1
                        "en" -> 2
                        else -> 0
                    }
                ) { index ->
                    AppCore.prefs.appLang = listOf("system", "zh", "en")[index]
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
                    onClick = { langInfo = ""; showLang = true }
                ),
                dropdownSpec(
                    title = L("调色板样式"),
                    subtitle = L("同一个种子色，算法不同味道不同"),
                    icon = Icons.Filled.Star,
                    options = PaletteStyle.entries.map { it.label },
                    selectedIndex = PaletteStyle.entries.indexOf(PaletteStyle.of(AppCore.prefs.paletteStyle))
                ) { index ->
                    AppCore.prefs.paletteStyle = PaletteStyle.entries[index].id
                    onThemeChanged()
                },
                dropdownSpec(
                    title = L("颜色模式"),
                    subtitle = L("深色 / 浅色 / 跟随系统"),
                    icon = Icons.Filled.Star,
                    options = DarkMode.entries.map { it.label },
                    selectedIndex = DarkMode.entries.indexOf(DarkMode.of(AppCore.prefs.darkMode))
                ) { index ->
                    AppCore.prefs.darkMode = DarkMode.entries[index].id
                    onThemeChanged()
                },
                RowSpec(
                    title = L("预设配色"),
                    subtitle = L("点一下直接换种子色"),
                    icon = Icons.Filled.Star,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            ThemeChoice.entries.take(4).forEach { c ->
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.seed))
                                        .clickable {
                                            AppCore.prefs.seedColor = c.seed.toInt()
                                            AppCore.prefs.dynamicColor = false
                                            onThemeChanged()
                                        }
                                )
                            }
                        }
                    }
                )
            )
        )

        // ---------------------------------------------------------- 网络
        GroupLabel(L("网络"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("监听端口"),
                    subtitle = if (status.running) "正在监听 ${status.port}" else "当前设置 ${AppCore.config.port}",
                    icon = Icons.Filled.Share,
                    trailing = {
                        PillButton(L("修改"), outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) {
                            showPort = true
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

        // ------------------------------------------------------ 网页控制台
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
        GroupLabel(L("安全"))
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            CardBox {
                Text(L("审批超时"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
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
        GroupLabel(L("终端与命令"))
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
                    subtitle = "${AppCore.permissions.commandRules().size} 条 · 在「权限」页里管理",
                    icon = Icons.Filled.Lock
                )
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            CardBox {
                Text(L("命令默认超时"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
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

        // ---------------------------------------------------------- AI 工具
        GroupLabel(L("AI 工具"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("工具管理"),
                    subtitle = "共 ${status.toolCount} 个 · 可单独启用/禁用、设权限；右上角 + 新建自定义工具",
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Build,
                    onClick = onOpenTools
                )
            )
        )
        Spacer(Modifier.height(7.dp))

        // ---------------------------------------------------------- 后台运行
        GroupLabel(L("后台运行"))
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
                showReset = true
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---------------------------------------------------------- 关于
        GroupLabel(L("关于"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("关于"),
                    subtitle = "v${ServerMeta.version} · 开发者 xtt · 检查更新与开源鸣谢",
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info,
                    onClick = onOpenAbout
                )
            )
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showPort) {
        var portText by remember { mutableStateOf(AppCore.config.port.toString()) }
        AlertDialog(
            onDismissRequest = { showPort = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("监听端口"), fontSize = 20.sp) },
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
                        L("改完会自动重启服务，客户端里的地址也要跟着改。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        toast(ctx, L("端口需要 1024 - 65535"))
                    } else {
                        AppCore.config.port = p
                        AppCore.saveConfig()
                        showPort = false
                        if (status.running) onRestartService()
                        toast(ctx, "端口已改为 $p")
                        onChanged()
                    }
                }) { Text(L("应用"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPort = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showLang) {
        val cur = com.xtt.mcpbox.i18n.Lang.current
        val builtinCount = com.xtt.mcpbox.i18n.Lang.entriesOf(com.xtt.mcpbox.i18n.Lang.EN).size
        fun writeOut(name: String, text: String) {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val file = java.io.File(dir, name)
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                file.writeText(text)
            }.fold(
                onSuccess = { langInfo = "已导出到 ${file.absolutePath}" },
                onFailure = { langInfo = "导出失败：${it.message}" }
            )
        }
        AlertDialog(
            onDismissRequest = { showLang = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("语言包"), fontSize = 20.sp) },
            text = {
                Column {
                    Text(
                        "当前语言：$cur\n内置英文词条：$builtinCount 条\n已导入语言包：" +
                            com.xtt.mcpbox.i18n.Lang.packLanguages().joinToString("、").ifBlank { "无" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    if (langInfo.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(langInfo, color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        L("导出模板"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        writeOut(
                            "mcpbox-lang-template.json",
                            com.xtt.mcpbox.i18n.Lang.exportTemplate(ctx, "en")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        L("导出当前语言包"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        writeOut("mcpbox-lang-$cur.json", com.xtt.mcpbox.i18n.Lang.exportPack(ctx, cur))
                    }
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        L("从文件导入"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) { importLang.launch(arrayOf("application/json", "*/*")) }
                    if (com.xtt.mcpbox.i18n.Lang.packLanguages().isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        PillButton(
                            L("删除导入的语言包"),
                            Modifier.fillMaxWidth(),
                            outlined = true,
                            color = MaterialTheme.colorScheme.error
                        ) {
                            com.xtt.mcpbox.i18n.Lang.packLanguages().forEach {
                                com.xtt.mcpbox.i18n.Lang.deletePack(ctx, it)
                            }
                            AppCore.prefs.appLang = "system"
                            com.xtt.mcpbox.i18n.Lang.init(ctx, "system", com.xtt.mcpbox.i18n.Lang.systemIsEnglish)
                            langInfo = L("已删除")
                            onLangChanged()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        L("语言包就是一个 JSON：\"中文原文\" 对应 \"译文\"。导出模板照着填即可。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLang = false }) {
                    Text(L("关闭"), color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    if (showSeed) {
        val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(AppCore.prefs.seedColor, it) } }
        var hue by remember { mutableStateOf(hsv[0]) }
        var sat by remember { mutableStateOf(hsv[1]) }
        var bri by remember { mutableStateOf(hsv[2]) }
        val cur = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
        AlertDialog(
            onDismissRequest = { showSeed = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("种子颜色"), fontSize = 20.sp) },
            text = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(cur)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            hexOf(cur),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SEED_PRESETS.chunked(6).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { c ->
                                val selected = cur == c.toInt()
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .then(
                                            if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        )
                                        .clickable {
                                            val f = FloatArray(3)
                                            android.graphics.Color.colorToHSV(c.toInt(), f)
                                            hue = f[0]; sat = f[1]; bri = f[2]
                                        }
                                )
                            }
                        }
                    }
                    SeedSlider(L("色相"), hue, 0f..360f) { hue = it }
                    SeedSlider(L("饱和度"), sat, 0f..1f) { sat = it }
                    SeedSlider(L("亮度"), bri, 0f..1f) { bri = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.prefs.seedColor = cur
                    AppCore.prefs.dynamicColor = false
                    showSeed = false
                    toast(ctx, L("种子颜色已更新"))
                    onThemeChanged()
                }) { Text(L("应用"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSeed = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text(L("网页访问密码"), fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { v -> passwordText = v.take(64) },
                        label = { Text(L("新密码（留空 = 用访问令牌）")) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        L("只影响浏览器打开的网页控制台；AI 客户端照旧用访问令牌连接。"),
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
                    toast(ctx, if (passwordText.isBlank()) L("已清空，网页密码改用访问令牌") else L("网页密码已保存"))
                    onChanged()
                }) { Text(L("保存"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPassword = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text(L("重置全部设置？"), fontSize = 20.sp) },
            text = {
                Text(
                    L("会把权限、端口、令牌、目录等全部恢复默认，并停止服务器。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    AppCore.resetAll()
                    onChanged()
                }) { Text(L("重置"), color = Sem.bad) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun RowIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String) {
    RoundIconButton(icon, desc, size = 40, onClick = onClick)
}

private fun hexOf(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

@Composable
private fun SeedSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(46.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

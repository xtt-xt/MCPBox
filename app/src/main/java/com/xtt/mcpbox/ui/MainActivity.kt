// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.ShizukuHelper
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.core.LogEntry
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.server.McpService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCore.init(application)
        setContent {
            var themeRev by remember { mutableStateOf(0) }
            // 切语言时整棵树重建，让所有 L(...) 重新取值
            var langRev by remember { mutableStateOf(0) }
            val seed = remember(themeRev) { AppCore.prefs.seedColor }
            val style = remember(themeRev) { AppCore.prefs.paletteStyle }
            val darkMode = remember(themeRev) { AppCore.prefs.darkMode }
            val dynColor = remember(themeRev) { AppCore.prefs.dynamicColor }
            MCPBoxTheme(
                seedArgb = seed,
                paletteStyleId = style,
                darkModeId = darkMode,
                dynamicColor = dynColor
            ) {
                // 把当前配色同步给悬浮窗（它在 Compose 外面，拿不到 MaterialTheme）
                val cs = MaterialTheme.colorScheme
                LaunchedEffect(seed, style, darkMode, dynColor) {
                    AppCore.overlayPalette = OverlayPalette(
                        background = cs.background.toArgb(),
                        card = cs.surfaceContainer.toArgb(),
                        cardHigh = cs.surfaceContainerHigh.toArgb(),
                        cardLow = cs.surfaceContainerLow.toArgb(),
                        text = cs.onSurface.toArgb(),
                        textDim = cs.onSurfaceVariant.toArgb(),
                        primary = cs.primary.toArgb(),
                        onPrimary = cs.onPrimary.toArgb(),
                        outline = cs.outline.toArgb()
                    )
                }
                AppRoot(
                    requestPermission = ::handlePermNeed,
                    onRequestShizuku = ::requestShizuku,
                    onThemeChanged = { themeRev++ },
                    onLangChanged = { langRev++ },
                    langRev = langRev
                )
            }
        }
    }

    private fun requestShizuku() {
        val message = ShizukuHelper.request()
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePermNeed(need: PermNeed) {
        when (need) {
            PermNeed.STORAGE -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(intent) }.onFailure {
                        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }
                } else {
                    requestPermissions(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ), 1001
                    )
                }
            }
            PermNeed.OVERLAY -> runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            PermNeed.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
                }
            }
            PermNeed.BATTERY -> runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}

@Composable
fun AppRoot(
    requestPermission: (PermNeed) -> Unit,
    onRequestShizuku: () -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    /** 语言版本号：变化时只重建界面内容，导航状态（tab / 子页面）留在外面。 */
    langRev: Int = 0
) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var subScreen by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf(AppCore.server.status()) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var pending by remember { mutableStateOf<List<ApprovalRequest>>(emptyList()) }

    // 让每个页面（含子页面）的滚动位置、输入内容在切换后保留 ——
    // 否则从「自定义工具」返回设置页时会跳回顶部
    val tabStateHolder = rememberSaveableStateHolder()
    val screenStateHolder = rememberSaveableStateHolder()

    // 每天第一次打开时检查一次更新（可以在「关于」里关掉）
    var updateInfo by remember { mutableStateOf<com.xtt.mcpbox.UpdateChecker.Info?>(null) }
    LaunchedEffect(Unit) {
        val prefs = AppCore.prefs
        if (prefs.updateCheckDaily && prefs.lastUpdateCheck != com.xtt.mcpbox.UpdateChecker.today()) {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.xtt.mcpbox.UpdateChecker.check()
            }
            prefs.lastUpdateCheck = com.xtt.mcpbox.UpdateChecker.today()
            if (r is com.xtt.mcpbox.UpdateChecker.Result.Newer) updateInfo = r.info
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = AppCore.server.status()
            val newPending = AppCore.approval.pendingRequests()
            if (newPending.map { it.id } != pending.map { it.id }) pending = newPending
            // 日志没变就别刷新 state —— 否则每 700ms 全界面重组一次，
            // 弹出来的选择框/菜单会被无谓地重建
            val newLogs = AppCore.log.list(200)
            if (newLogs.size != logs.size ||
                newLogs.firstOrNull()?.time != logs.firstOrNull()?.time
            ) {
                logs = newLogs
            }
            delay(700)
        }
    }

    updateInfo?.let { info ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { updateInfo = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { androidx.compose.material3.Text(L("发现新版本 %s").format(info.tag), fontSize = 20.sp) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        L("当前版本 v%s").format(com.xtt.mcpbox.core.ServerMeta.version),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp
                    )
                    if (info.notes.isNotBlank()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Text(
                            info.notes.take(600),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    openUrl(ctx, info.url)
                    updateInfo = null
                }) { androidx.compose.material3.Text(L("去下载"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { updateInfo = null }) {
                    androidx.compose.material3.Text(L("稍后"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 子页面（自定义工具）进/出都带滑动动画：进去时从右侧滑入，返回时滑回右侧
    androidx.compose.runtime.key(langRev) {   // 只重建内容，导航状态留在外面
    AnimatedContent(
        targetState = subScreen,
        transitionSpec = {
            val entering = targetState.isNotEmpty()
            val slide = if (entering) 1 else -1
            (
                slideInHorizontally(tween(300)) { w -> slide * w / 3 } + fadeIn(tween(220))
                ).togetherWith(
                slideOutHorizontally(tween(260)) { w -> -slide * w / 6 } + fadeOut(tween(180))
            )
        },
        label = "subScreen"
    ) { screen ->
    screenStateHolder.SaveableStateProvider(screen) {
    if (screen.isNotEmpty()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (screen) {
                    "about" -> AboutScreen(
                        ctx = ctx,
                        revision = revision,
                        onChanged = { revision++ },
                        onBack = { subScreen = "" }
                    )
                    // tools 以及任何意外值都兜到工具管理，避免白屏
                    else -> ToolsScreen(
                        ctx = ctx,
                        revision = revision,
                        onChanged = { revision++ },
                        onBack = { subScreen = "" }
                    )
                }
            }
        }
    } else {

    val navItems = listOf(
        NavItem(L("首页"), Icons.Filled.Home),
        NavItem(L("终端"), Icons.Filled.Build),
        NavItem(L("权限"), Icons.Filled.Lock),
        NavItem(L("日志"), Icons.Filled.List),
        NavItem(L("设置"), Icons.Filled.Settings)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomPillNav(navItems, tab) { tab = it } }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // 往右切就内容从右边滑进来，往左切反过来
                    val forward = targetState > initialState
                    val slide = if (forward) 1 else -1
                    (
                        slideInHorizontally(tween(230)) { width -> slide * width / 10 } +
                            fadeIn(tween(200))
                        ).togetherWith(
                        slideOutHorizontally(tween(200)) { width -> -slide * width / 10 } +
                            fadeOut(tween(150))
                    )
                },
                label = "tabContent"
            ) { current ->
            tabStateHolder.SaveableStateProvider(current) {
            when (current) {
                0 -> HomeScreen(
                    ctx = ctx,
                    status = status,
                    pending = pending,
                    host = AppCore.host,
                    onToggleService = { on ->
                        if (on) McpService.start(ctx) else McpService.stop(ctx)
                        revision++
                    },
                    onRestartService = { McpService.restart(ctx) },
                    onPermNeed = requestPermission,
                    onOpenPermissions = { tab = 1 }
                )
                1 -> TerminalScreen(
                    ctx = ctx,
                    revision = revision,
                    onRequestShizuku = onRequestShizuku,
                    onChanged = { revision++ }
                )
                2 -> PermissionScreen(ctx, revision) { revision++ }
                3 -> LogScreen(ctx, logs) { revision++ }
                else -> SettingsScreen(
                    // 这里的入口是「松手才触发」（Compose 的 clickable 语义：按下高亮、松手进入、滑出取消）
                    ctx = ctx,
                    status = status,
                    revision = revision,
                    onThemeChanged = onThemeChanged,
                    onLangChanged = onLangChanged,
                    onOpenTools = { subScreen = "tools" },
                    onOpenAbout = { subScreen = "about" },
                    onChanged = { revision++ },
                    onRestartService = { McpService.restart(ctx) }
                )
            }
            }
            }
        }
    }
    }
    }
    }
    }
}

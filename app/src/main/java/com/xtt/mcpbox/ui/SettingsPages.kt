// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.ApprovalPresentation
import com.xtt.mcpbox.core.BrowserEngines
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.core.ServerMeta
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页的各个子页。
 *
 * 顶层（[SettingsScreen]）只放入口行，具体的开关 / 滑块 / 下拉都在这里 ——
 * 这样设置首页永远一屏扫得完，加功能只是多一行，不会退化成长列表。
 */

/**
 * 子页的统一骨架：自己的大标题 + 返回按钮 + 可滚动内容。
 *
 * [scroll] 一般不用传（自己 remember 一份就行）。**例外**：预见式返回的预览层
 * （`PredictiveBackBox` 的 `behind`）是这一页的**第二个实例**，它得和真页面共用同一份滚动状态，
 * 否则拖动时露出来的永远是页面顶部。这种页面由外面把 ScrollState 提出来传进来
 * （见设置栈的 `appearanceScroll`）。
 */
@Composable
internal fun SettingsPageShell(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    // 统一走 PageScaffold：返回按钮在左，往下滚时大标题收成顶上那条矮栏
    PageScaffold(
        scroll = scroll,
        title = title,
        onBack = onBack,
        bottomPadding = 24.dp
    ) {
        content()
    }
}

/* ------------------------------------------------------------ 外观与语言 */

/**
 * 后台算一次「背景种子色」，算出来才回调（让主题重建）。
 *
 * 读盘 + 解码，别放主线程；算不出来（文件坏了 / 视频首帧取不到）就什么都不做，
 * 取色自然回落到壁纸。
 */
private fun ensureBackdropSeed(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    scope.launch {
        if (BackdropSeed.ensure(ctx) != 0) onDone()
    }
}

@Composable
internal fun AppearanceSettingsPage(
    ctx: Context,
    revision: Int,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onOpenSeed: () -> Unit,
    onOpenLangPack: () -> Unit,
    onOpenWallpaper: () -> Unit,
    /** 打开「应用图标」子页。 */
    onOpenIcon: () -> Unit = {},
    onBack: () -> Unit,
    /** 滚动位置由外面持有：预览层要和真页面共用同一份（见 [SettingsPageShell]）。 */
    scroll: ScrollState = rememberScrollState()
) {
    val sdkOk = android.os.Build.VERSION.SDK_INT >= 31
    // 读一下 revision：它是对外声明的「设置变了」，让本页在开关改动后真的重组
    // （不加这个参数的话，调用点的 lambda 全被 memoize 成相等，整页会被 Compose 跳过）
    val langChoices = remember(revision) { com.xtt.mcpbox.i18n.Lang.languageChoices() }
    // 「交互」里的开关（如预见式返回动画）改了要能立刻刷新：Prefs 不是可观察状态，
    // 靠这个本页计数器把自己重组一次
    var localRev by remember { mutableStateOf(0) }
    // 从背景取色是读盘 + 解码的活，丢到协程里做，算完再让主题重建一次
    val scope = rememberCoroutineScope()

    SettingsPageShell(L("外观与语言"), L("主题配色、颜色模式与界面语言"), onBack, scroll) {
        GroupLabel(L("主题"))
        CardGroup(
            listOf(
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
                        // 打开就顺手把背景种子算好（换过背景、还没算过的情况）
                        if (on) ensureBackdropSeed(ctx, scope) { onThemeChanged() }
                        onThemeChanged()
                    }
                },
                // 取色方式：只在「动态取色开着 + 自定义背景开着」时出现。
                // 背景一关，这一项整行带动画收起，取色回落到壁纸。
                dropdownSpec(
                    title = L("取色方式"),
                    subtitle = L("种子色从哪来：系统壁纸，还是你设的背景图 / 视频首帧"),
                    icon = Icons.Filled.Create,
                    options = DynamicSeedSource.entries.map { L(it.label) },
                    selectedIndex = DynamicSeedSource.entries
                        .indexOf(DynamicSeedSource.of(AppCore.prefs.dynamicSeedSource))
                ) { index ->
                    val source = DynamicSeedSource.entries[index.coerceIn(0, DynamicSeedSource.entries.lastIndex)]
                    AppCore.prefs.dynamicSeedSource = source.id
                    if (source == DynamicSeedSource.BACKDROP) ensureBackdropSeed(ctx, scope) { onThemeChanged() }
                    onThemeChanged()
                }.copy(
                    // 关掉自定义背景（或根本没选文件）时整行收起，取色自然回落到壁纸
                    visible = AppCore.prefs.dynamicColor &&
                        AppCore.prefs.backdropEnabled &&
                        BackdropFiles.current(ctx, BackdropKind.of(AppCore.prefs.backdropKind)) != null
                ),

                // 动态取色开着的时候，种子色不起作用，就带动画收起来
                RowSpec(
                    title = L("种子颜色"),
                    subtitle = L("整套配色都由这个颜色派生"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Create,
                    visible = !AppCore.prefs.dynamicColor,
                    onClick = onOpenSeed,
                    trailing = {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(AppCore.prefs.seedColor))
                        )
                    }
                ),
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
                },
                entrySpec(
                    L("背景"),
                    L("自定义背景图或视频，上面盖一层可调透明度的遮罩"),
                    Icons.Filled.PlayArrow,
                    onOpenWallpaper
                ),
                entrySpec(
                    L("应用图标"),
                    L("换一个桌面图标：线性极简 / 扁平 / 渐变 / 玻璃…"),
                    Icons.Filled.Star,
                    onOpenIcon
                )
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

        GroupLabel(L("交互"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("预见式返回动画"),
                    subtitle = L("从屏幕边缘拖动，页面跟手移动，松手返回。需要 Android 13。"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.ArrowBack,
                    checked = remember(revision, localRev) { AppCore.prefs.predictiveBack }
                ) { on ->
                    AppCore.prefs.predictiveBack = on
                    // 「视频背景」只在关掉预见式返回动画时可用（视频模式下页面必须透明，动画开着就不好看）。
                    // 打开动画时给出视频的，自动退回图片：视频文件留着，关掉动画还能切回来。
                    if (on && AppCore.prefs.backdropKind == BackdropKind.VIDEO.id) {
                        AppCore.prefs.backdropKind = BackdropKind.IMAGE.id
                        BackdropFiles.syncPref(ctx, BackdropKind.IMAGE)
                        toast(
                            ctx,
                            if (BackdropFiles.current(ctx, BackdropKind.IMAGE) != null)
                                L("视频背景已切回图片（视频文件留着，关掉预见式返回动画还能切回来）")
                            else L("视频背景已停用：先选一张图片，或者关掉预见式返回动画继续用视频")
                        )
                    }
                    // 渲染方式（逐页画图 ↔ 透明页面）也跟着这个开关变，得让最外层那份背景立刻重新读一遍
                    onThemeChanged()
                    localRev++
                }
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
        // 开关放上面：打开「不限时」后下面的秒数行会带动画收起来
        val presentation = remember(revision) {
            ApprovalPresentation.of(AppCore.prefs.approvalPresentation)
        }
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("审批方式"),
                    subtitle = when (presentation) {
                        ApprovalPresentation.OVERLAY -> L("AI 请求权限时弹悬浮窗；没权限时自动改用通知栏")
                        ApprovalPresentation.NOTIFY -> L("只发通知栏提醒（带允许 / 拒绝按钮），不弹悬浮窗")
                    },
                    icon = Icons.Filled.Notifications,
                    options = listOf(L("悬浮窗"), L("通知栏")),
                    selectedIndex = if (presentation == ApprovalPresentation.NOTIFY) 1 else 0
                ) { index ->
                    AppCore.prefs.approvalPresentation =
                        if (index == 1) ApprovalPresentation.NOTIFY.id else ApprovalPresentation.OVERLAY.id
                    AppCore.log.add(
                        LogKind.SYSTEM,
                        message = L("审批方式改为：%s").format(L(if (index == 1) "通知栏" else "悬浮窗"))
                    )
                    onChanged()
                },
                switchSpec(
                    title = L("审批不限时"),
                    subtitle = L("打开后审批弹窗不会自动消失，AI 会一直等你答复"),
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
                },
                RowSpec(
                    title = L("审批超时"),
                    subtitle = L("%s 秒 · 超时自动拒绝").format(timeoutState),
                    visible = !approvalUnlimited,
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
                            steps = 38
                        )
                    }
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 终端与命令 */

@Composable
internal fun ShellSettingsPage(
    revision: Int,
    onChanged: () -> Unit,
    onOpenRules: (String) -> Unit,
    onBack: () -> Unit,
    /** 滚动位置由外面持有：预览层要和真页面共用同一份（见 [SettingsPageShell]）。 */
    scroll: ScrollState = rememberScrollState()
) {
    val shellUnlimited = AppCore.config.shellTimeoutMs <= 0L
    var shellTimeoutState by remember(revision) {
        mutableStateOf(
            (AppCore.config.shellTimeoutMs / 1000).takeIf { it > 0L }
                ?: AppCore.prefs.shellTimeoutLastSec
        )
    }

    SettingsPageShell(L("终端与命令"), L("命令后端、规则与默认超时"), onBack, scroll) {
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
                    title = L("路径规则"),
                    subtitle = L("%s 条 · 给目录单独定允许 / 询问 / 拒绝")
                        .format(remember(revision) { AppCore.permissions.pathRules().size }),
                    icon = Icons.Filled.Place,
                    onClick = { onOpenRules("path") }
                ),
                RowSpec(
                    title = L("命令规则"),
                    subtitle = L("%s 条 · 给命令定允许 / 询问 / 拒绝")
                        .format(remember(revision) { AppCore.permissions.commandRules().size }),
                    icon = Icons.Filled.Lock,
                    onClick = { onOpenRules("command") }
                )
            )
        )

        GroupLabel(L("超时"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("命令不限时"),
                    subtitle = L("打开后 AI 执行的命令会一直跑到自己结束，不会中途被掐断"),
                    subtitleMaxLines = 2,
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
                },
                RowSpec(
                    title = L("命令默认超时"),
                    subtitle = L("%s 秒 · AI 调用 run_shell 时的上限").format(shellTimeoutState),
                    visible = !shellUnlimited,
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
                            steps = 28
                        )
                    }
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 浏览器 */

/**
 * 内置浏览器（工具包 browser）的设置。
 *
 * 页面本身不控制「开不开浏览器」—— 那是权限页里「浏览器控制」的事；
 * 这里管的是行为：能不能碰内网、最多几个页面、搜索引擎、身份与登录态。
 */
@Composable
internal fun BrowserSettingsPage(
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val config = AppCore.config
    val allowLan = remember(revision) { config.browserAllowLan }
    var maxPages by remember(revision) { mutableStateOf(config.browserMaxPages) }
    val engines = remember(revision) { BrowserEngines.BUILTIN + BrowserEngines.custom(config) }
    val custom = remember(revision) { BrowserEngines.custom(config) }
    val defaultEngine = remember(revision) { config.browserDefaultEngine }
    val ua = remember(revision) { config.browserUserAgent }
    val pageCount = remember(revision) { runCatching { AppCore.browser.pages().size }.getOrDefault(0) }
    val overlayOk = remember(revision) { runCatching { AppCore.browser.available() }.getOrDefault(false) }

    var notice by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var clearWhat by remember { mutableStateOf("") }

    SettingsPageShell(L("浏览器"), L("内置 WebView：内网限制、页面数、搜索引擎与登录态"), onBack) {
        if (!overlayOk) {
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("浏览器现在用不了"),
                        subtitle = L("页面靠悬浮窗承载，需要「悬浮窗」权限：首页 → 环境检查 → 悬浮窗 → 授权"),
                        subtitleMaxLines = 3,
                        icon = Icons.Filled.Warning
                    )
                )
            )
            Spacer(Modifier.height(10.dp))
        }

        GroupLabel(L("安全"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("允许访问内网地址"),
                    subtitle = L("默认禁止 localhost / 127.0.0.1 / 192.168.x 等地址。原因：这台手机上跑着 MCP 服务器，网页脚本能访问本机就等于绕过审批"),
                    subtitleMaxLines = 4,
                    icon = Icons.Filled.Warning,
                    checked = allowLan
                ) { on ->
                    config.browserAllowLan = on
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        GroupLabel(L("页面"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("最多同时打开的页面"),
                    subtitle = L("%s 个 · 每个页面一块 WebView，很吃内存").format(maxPages),
                    icon = Icons.Filled.List,
                    content = {
                        Slider(
                            value = maxPages.toFloat(),
                            onValueChange = { maxPages = it.toInt().coerceIn(1, 12) },
                            onValueChangeFinished = {
                                config.browserMaxPages = maxPages
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 1f..12f,
                            steps = 10
                        )
                    }
                ),
                RowSpec(
                    title = L("当前打开的页面"),
                    subtitle = if (pageCount == 0) L("没有页面（悬浮球不显示）")
                    else L("%s 个 · 点这里全部关掉").format(pageCount),
                    icon = Icons.Filled.Clear,
                    onClick = if (pageCount == 0) null else ({
                        runCatching { AppCore.browser.closeAll() }
                        notice = L("已关闭全部页面")
                        onChanged()
                    })
                )
            )
        )

        GroupLabel(L("搜索"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("默认搜索引擎"),
                    subtitle = L("AI 调 browser_search 不指定 engine 时用它"),
                    icon = Icons.Filled.Search,
                    options = engines.map { it.title },
                    selectedIndex = engines.indexOfFirst { it.id == defaultEngine }.coerceAtLeast(0)
                ) { index ->
                    config.browserDefaultEngine = engines.getOrNull(index)?.id ?: "bing"
                    AppCore.saveConfig()
                    onChanged()
                }
            ) + custom.map { e ->
                RowSpec(
                    title = e.title,
                    subtitle = e.url,
                    subtitleMaxLines = 1,
                    icon = Icons.Filled.Search,
                    trailing = {
                        TextButton(onClick = {
                            // 和 browser_engines 工具共用 core 里的那份数据与校验
                            val removed = BrowserEngines.remove(config, e.id)
                            notice = if (removed != null) L("已删除搜索引擎「%s」").format(removed.title)
                            else L("没找到这个搜索引擎")
                            onChanged()
                        }) { Text(L("删除"), color = MaterialTheme.colorScheme.error) }
                    }
                )
            } + listOf(
                RowSpec(
                    title = L("新增搜索引擎"),
                    subtitle = L("模板里用 %s 表示查询词的位置，例如 https://example.com/search?q=%s"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Add,
                    onClick = { newTitle = ""; newUrl = ""; showAdd = true }
                )
            )
        )

        GroupLabel(L("身份与登录态"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("User-Agent"),
                    subtitle = L("桌面版能拿到完整网页，手机版更接近真实手机浏览"),
                    icon = Icons.Filled.Share,
                    options = listOf(L("手机版"), L("桌面版"), L("WebView 默认")),
                    selectedIndex = when (ua.lowercase()) {
                        "desktop" -> 1
                        "default" -> 2
                        else -> 0
                    }
                ) { index ->
                    config.browserUserAgent = listOf("mobile", "desktop", "default")[index]
                    AppCore.saveConfig()
                    notice = L("改完对已打开的页面要先刷新才生效")
                    onChanged()
                },
                RowSpec(
                    title = L("清空 cookie"),
                    subtitle = L("所有站点的登录态都会没掉"),
                    icon = Icons.Filled.Delete,
                    onClick = { clearWhat = "cookies" }
                ),
                RowSpec(
                    title = L("清空缓存"),
                    subtitle = L("页面缓存、表单记录、本地存储"),
                    icon = Icons.Filled.Refresh,
                    onClick = { clearWhat = "cache" }
                )
            )
        )

        if (notice.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                notice,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 22.dp)
            )
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            title = { Text(L("新增搜索引擎"), fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it.take(20) },
                        label = { Text(L("名字")) },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it.take(300) },
                        label = { Text(L("搜索链接模板（查询词写 %s）")) },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = newUrl.trim()
                    if (newTitle.isBlank() || !u.startsWith("http") || !u.contains("%s")) {
                        notice = L("名字要有，模板要是网址并且带 %s（查询词插在哪）")
                    } else {
                        runCatching { BrowserEngines.add(config, newTitle, u) }
                            .onSuccess {
                                notice = L("已添加搜索引擎「%s」").format(it.title)
                                showAdd = false
                            }
                            .onFailure { notice = it.message ?: L("添加失败") }
                    }
                    onChanged()
                }) { Text(L("添加"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (clearWhat.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { clearWhat = "" },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            title = {
                Text(if (clearWhat == "cookies") L("清空 cookie") else L("清空缓存"), fontSize = 20.sp)
            },
            text = {
                Text(
                    if (clearWhat == "cookies") L("所有站点的登录态都会没掉，确定吗？")
                    else L("会清掉页面缓存、表单记录和本地存储，确定吗？"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearWhat == "cookies") {
                        runCatching { AppCore.browser.clearCookies() }
                        notice = L("已清空 cookie")
                    } else {
                        runCatching { AppCore.browser.clearCache() }
                        notice = L("已清空缓存")
                    }
                    clearWhat = ""
                    onChanged()
                }) { Text(L("确定"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearWhat = "" }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/* ------------------------------------------------------------ 后台与运行 */

@Composable
internal fun BackgroundSettingsPage(
    revision: Int,
    onChanged: () -> Unit,
    onRestartService: () -> Unit,
    onOpenReset: () -> Unit,
    onBack: () -> Unit
) {
    // revision 是本页唯一的「外部状态」依赖：带上它，开关改动后才会重组。
    // 不带的话调用点的 lambda（onBack / onOpenReset…）会被 Compose memoize 成相等，
    // 整个子页被判定为「参数没变」直接跳过 —— 表现就是点了开关不动。
    val keepAwake = remember(revision) { AppCore.prefs.keepAwake }
    val autoStart = remember(revision) { AppCore.prefs.autoStartBoot }
    val wakeScreen = remember(revision) { AppCore.prefs.wakeScreenOnApproval }
    val logEnabled = remember(revision) { AppCore.config.logEnabled }
    val autoGrantPerms = remember(revision) { AppCore.prefs.autoGrantPermissions }
    SettingsPageShell(L("后台与运行"), L("保活、日志与服务控制"), onBack) {
        GroupLabel(L("后台"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("保持 CPU 唤醒"),
                    subtitle = L("长时间会话更稳定，会稍微费电"),
                    icon = Icons.Filled.Warning,
                    checked = keepAwake
                ) {
                    AppCore.prefs.keepAwake = it
                    onChanged()
                },
                switchSpec(
                    title = L("开机自动启动"),
                    subtitle = L("重启手机后自动把服务器打开"),
                    icon = Icons.Filled.Refresh,
                    checked = autoStart
                ) {
                    AppCore.prefs.autoStartBoot = it
                    onChanged()
                },
                switchSpec(
                    title = L("审批时点亮屏幕"),
                    subtitle = L("有请求时亮屏，方便马上看到弹窗"),
                    icon = Icons.Filled.Notifications,
                    checked = wakeScreen
                ) {
                    AppCore.prefs.wakeScreenOnApproval = it
                    AppCore.overlay?.wakeScreenOnApproval = it
                    onChanged()
                },
                switchSpec(
                    title = L("记录日志"),
                    subtitle = L("保留最近 800 条调用/审批记录"),
                    icon = Icons.Filled.Info,
                    checked = logEnabled
                ) {
                    AppCore.config.logEnabled = it
                    AppCore.log.enabled = it
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        GroupLabel(L("权限"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("进入应用自动补齐权限"),
                    subtitle = L("有 Root / Shizuku 时，打开 App 直接把缺的「文件访问 / 悬浮窗 / 忽略电池优化」静默开掉；通知权限仍走系统弹窗"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Lock,
                    checked = autoGrantPerms
                ) {
                    AppCore.prefs.autoGrantPermissions = it
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

/* ------------------------------------------------------------ 语言包 */

/**
 * 「语言包」子页（原来是「外观与语言」里的一个弹窗）。
 *
 * 语言包就是一个 JSON：`"中文原文": "译文"`。这一页负责看当前状态、导入 / 导出、
 * 删除已导入的包 —— 做成子页的理由很实际：内容一多，弹窗里滚动起来很难受，
 * 而且导入完想看一眼「现在用的是哪个」还得重新点开。
 */
@Composable
internal fun LanguagePackPage(
    ctx: Context,
    revision: Int,
    onLangChanged: () -> Unit,
    onBack: () -> Unit
) {
    var localRev by remember { mutableStateOf(0) }
    val rev = revision + localRev
    var info by remember { mutableStateOf("") }

    val cur = remember(rev) { com.xtt.mcpbox.i18n.Lang.current }
    val builtinCount = remember(rev) { com.xtt.mcpbox.i18n.Lang.entriesOf(com.xtt.mcpbox.i18n.Lang.EN).size }
    val packs = remember(rev) { com.xtt.mcpbox.i18n.Lang.packLanguages() }

    fun writeOut(name: String, text: String) {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(dir, name)
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            file.writeText(text)
        }.fold(
            onSuccess = { info = L("已导出到 %s").format(file.absolutePath) },
            onFailure = { info = L("导出失败：%s").format(it.message) }
        )
    }

    val importLang = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            info = L("读不到文件内容")
        } else {
            com.xtt.mcpbox.i18n.Lang.importPack(ctx, text).fold(
                onSuccess = { (id, count) ->
                    AppCore.prefs.appLang = id
                    com.xtt.mcpbox.i18n.Lang.init(ctx, id, com.xtt.mcpbox.i18n.Lang.systemIsEnglish)
                    info = L("已导入语言包 %s（%s 条译文）").format(id, count)
                    localRev++
                    onLangChanged()
                },
                onFailure = { info = L("导入失败：%s").format(it.message) }
            )
        }
    }

    SettingsPageShell(L("语言包"), L("导入、导出与删除界面译文"), onBack) {
        GroupLabel(L("当前"))
        CardColumn {
            CardBox {
                KeyValue(L("界面语言"), com.xtt.mcpbox.i18n.Lang.packDisplayName(cur))
                KeyValue(L("内置英文词条"), L("%s 条").format(builtinCount))
                KeyValue(
                    L("已导入语言包"),
                    if (packs.isEmpty()) L("无") else packs.joinToString("、") {
                        com.xtt.mcpbox.i18n.Lang.packDisplayName(it)
                    }
                )
            }
        }
        if (info.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            CardColumn {
                CardBox {
                    Text(info, color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp)
                }
            }
        }

        GroupLabel(L("语言包"))
        CardGroup(
            rows = buildList {
                packs.forEach { id ->
                    add(
                        RowSpec(
                            title = com.xtt.mcpbox.i18n.Lang.packDisplayName(id),
                            subtitle = L("%s · %s 条译文").format(id, com.xtt.mcpbox.i18n.Lang.entryCount(id)),
                            icon = Icons.Filled.Info,
                            trailing = {
                                RoundIconButton(Icons.Filled.Delete, L("删除"), tint = Sem.bad, size = 40) {
                                    com.xtt.mcpbox.i18n.Lang.deletePack(ctx, id)
                                    if (AppCore.prefs.appLang == id) {
                                        AppCore.prefs.appLang = "system"
                                        com.xtt.mcpbox.i18n.Lang.init(
                                            ctx, "system", com.xtt.mcpbox.i18n.Lang.systemIsEnglish
                                        )
                                    }
                                    info = L("已删除")
                                    localRev++
                                    onLangChanged()
                                }
                            }
                        )
                    )
                }
                add(
                    RowSpec(
                        title = L("从文件导入"),
                        subtitle = L("选一个语言包 JSON，导入后立刻切成那个语言"),
                        icon = Icons.Filled.Add,
                        onClick = { importLang.launch(arrayOf("application/json", "*/*")) }
                    )
                )
                add(
                    RowSpec(
                        title = L("导出模板"),
                        subtitle = L("英文模板，照着把译文填进去就是新语言包"),
                        icon = Icons.Filled.Share,
                        onClick = { writeOut("mcpbox-lang-template.json", com.xtt.mcpbox.i18n.Lang.exportTemplate(ctx, "en")) }
                    )
                )
                add(
                    RowSpec(
                        title = L("导出当前语言包"),
                        subtitle = L("把现在生效的这套译文导出成 JSON"),
                        icon = Icons.Filled.Share,
                        onClick = { writeOut("mcpbox-lang-$cur.json", com.xtt.mcpbox.i18n.Lang.exportPack(ctx, cur)) }
                    )
                )
                if (packs.isNotEmpty()) {
                    add(
                        RowSpec(
                            title = L("删除所有导入的语言包"),
                            subtitle = L("删完回到中文；内置英文不受影响"),
                            icon = Icons.Filled.Delete,
                            subtitleColor = Sem.bad,
                            onClick = {
                                packs.forEach { com.xtt.mcpbox.i18n.Lang.deletePack(ctx, it) }
                                AppCore.prefs.appLang = "system"
                                com.xtt.mcpbox.i18n.Lang.init(
                                    ctx, "system", com.xtt.mcpbox.i18n.Lang.systemIsEnglish
                                )
                                info = L("已删除")
                                localRev++
                                onLangChanged()
                            }
                        )
                    )
                }
            }
        )

        GroupLabel(L("说明"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("语言包长什么样"),
                    subtitle = L("一个 JSON：\"中文原文\" 对应 \"译文\"。导出模板照着填，缺的条目会自动回退中文。"),
                    subtitleMaxLines = 3
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 应用图标 */

/**
 * 「应用图标」子页：换桌面上那个图标。
 *
 * 真正的实现是 activity-alias 的开关（见 [AppIcon]）：每个图标在清单里都有一个
 * 带 MAIN/LAUNCHER 的 alias，切换就是把目标那个 ENABLED、其余 DISABLED。
 * 所以这里只管「写偏好 + 调 [AppIcon.apply]」，不需要重建界面。
 */
@Composable
internal fun IconSettingsPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    // 本页自己的计数器：切完立刻刷新「哪一个是选中的」（Prefs 不是可观察状态）
    var localRev by remember { mutableStateOf(0) }
    val current = remember(revision, localRev) { AppIcon.current() }
    // 待确认的那一个：点一下先弹窗（换图标会重启 App），同意后再动手
    var pending by remember { mutableStateOf<AppIcon?>(null) }
    val scope = rememberCoroutineScope()

    SettingsPageShell(L("应用图标"), L("换一个桌面图标，切换时需要重启一次 App"), onBack) {
        GroupLabel(L("图标"))
        CardGroup(
            rows = AppIcon.entries.map { icon ->
                val selected = icon == current
                RowSpec(
                    title = L(icon.label),
                    subtitle = L(icon.note),
                    subtitleMaxLines = 1,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIconArt(icon.art, 44.dp, 12.dp)
                            if (selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Done,
                                    contentDescription = L("已选中"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = { if (!selected) pending = icon }
                )
            }
        )

        GroupLabel(L("说明"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("换图标要重启一次 App"),
                    subtitle = L("图标是系统级的东西，改完必须让系统重新读一遍组件：App 会重启，服务器断几秒后自己恢复。桌面上的图标会立刻换成新的。"),
                    subtitleMaxLines = 3
                ),
                RowSpec(
                    title = L("桌面图标没变？"),
                    subtitle = L("有的启动器要等几秒才刷新；也可以把旧图标从桌面删掉再重新拖一个。升级过 App 的话，旧图标指向的是旧组件，必须重新拖。"),
                    subtitleMaxLines = 3
                ),
                RowSpec(
                    title = L("想要别的图标？"),
                    subtitle = L("图标是社区一起攒的：可以到 GitHub 的 Discussions 发讨论，或者直接开 Issue 投稿（附上 1024×1024 的方形图就行）。"),
                    subtitleMaxLines = 3,
                    onClick = {
                        openUrl(ctx, "https://github.com/xtt-xt/MCPBox/discussions")
                    }
                )
            )
        )
    }

    pending?.let { icon ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("换成「%s」？").format(L(icon.label)), fontSize = 20.sp) },
            text = {
                Text(
                    L("换图标会让 App 重启一次：正在跑的 MCP 服务器会断开几秒，之后自动恢复；桌面上的图标会立刻变成新的。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    AppCore.prefs.appIcon = icon.id
                    localRev++
                    onChanged()
                    toast(ctx, L("正在切换图标，App 马上重启…"))
                    // 等一下再动组件开关：restart = true 会让系统杀掉进程，toast 得先显示出来
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        AppIcon.apply(ctx, icon, restart = true)
                    }
                }) {
                    Text(L("换图标并重启"), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/* ------------------------------------------------------------ 背景（图 / 视频） */

/**
 * 「背景」子页：选一张图片或一段视频垫在最底下，上面盖一层**主题背景色**的遮罩，
 * 遮罩的不透明度可调（100% = 回到原来那种纯色底）；卡片和底栏也能各自调不透明度。
 *
 * 三个口径别搞错（完整规则见 `ui/Backdrop.kt` 文件头）：
 *  · 遮罩全 App **只有一层**，铺在最外层画布上（[BackdropLayer]）：盒子层（Scaffold /
 *    [PredictiveBackBox]）在有背景时一律留空、页面层只在「图片 + 预见式返回动画开着」时才画。
 *  · 「视频」只在**关掉预见式返回动画**时可选：视频模式下页面必须是透明的。关掉动画时图片
 *    也走同一套（透明页面），两者观感一样；动画开着时图片才是每页各画一份。
 *  · 文件会被**复制**进 App 私有目录（`files/backdrop/`），原文件删了 / 授权过期都不影响。
 */
@Composable
internal fun WallpaperSettingsPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    // 本页自己的计数器：改完偏好顺手 +1，保证这一页一定重组（调用点的 lambda 会被 memoize）
    var localRev by remember { mutableStateOf(0) }
    val rev = revision + localRev
    val enabled = remember(rev) { AppCore.prefs.backdropEnabled }
    val kind = remember(rev) { BackdropKind.of(AppCore.prefs.backdropKind) }
    // 按**当前类型**找文件（不是「拿偏好里那个名字开文件」）。
    // 图片和视频各留一份在私有目录里，所以来回切类型不用重新选。
    val file = remember(rev) { BackdropFiles.current(ctx, kind) }
    val scale = remember(rev) { BackdropScale.of(AppCore.prefs.backdropScale) }
    val subPages = remember(rev) { AppCore.prefs.backdropSubpages }
    val sdkOk = android.os.Build.VERSION.SDK_INT >= 31
    // 「视频」只在**关掉预见式返回动画**时可选：视频模式下页面必须是透明的，于是没有「上一页预览」、
    // 转场也只能让旧页直接收掉。关掉动画时图片也走同一套（透明页），两者观感一样、都正常；
    // 动画开着的时候透明页面就很难看，所以干脆不给视频。
    // 已经设成视频的（从备份恢复来的）照样列出来，免得卡住没法切回去。
    val predictive = remember(rev) { AppCore.prefs.predictiveBack }
    val kinds = if (!predictive || kind == BackdropKind.VIDEO) BackdropKind.entries
    else listOf(BackdropKind.IMAGE)

    // 滑块拖的时候只动本页预览：每一帧都写盘 + 让整棵树重组会卡，松手（onValueChangeFinished）才落盘
    var tint by remember(rev) { mutableFloatStateOf(AppCore.prefs.backdropTint.toFloat()) }
    var blur by remember(rev) { mutableFloatStateOf(AppCore.prefs.backdropBlur.toFloat()) }
    var cardA by remember(rev) { mutableFloatStateOf(AppCore.prefs.backdropCardAlpha.toFloat()) }

    // 换背景后要重算「从背景取色」的种子（没选这个取色方式就什么都不做）
    val seedScope = rememberCoroutineScope()

    fun commit() {
        localRev++
        onChanged()
        // 背景文件或开关变了 → 种子色跟着重算（同一个文件时 ensure 直接返回缓存，不重复解码）
        if (AppCore.prefs.dynamicSeedSource == DynamicSeedSource.BACKDROP.id) {
            seedScope.launch {
                val seed = BackdropSeed.ensure(ctx)
                if (seed == 0) {
                    // 取不出来（文件坏了 / 视频首帧没解出来）：回落壁纸色，不留一个错色
                    AppCore.prefs.backdropSeedColor = 0
                    AppCore.prefs.backdropSeedFile = ""
                }
                onChanged()
            }
        }
    }

    val pickImage = rememberBackdropPicker(ctx, BackdropKind.IMAGE) { commit() }
    val pickVideo = rememberBackdropPicker(ctx, BackdropKind.VIDEO) { commit() }

    // 当前文件的信息（名字 / 大小 / 像素），视频还要时长
    val info by produceState<BackdropInfo?>(null, file?.path, rev) {
        value = file?.let { BackdropFiles.info(it, kind) }
    }
    // 预览用的一帧：图片就是它自己，视频截第一秒（不为预览再开一路解码）
    val poster by produceState<ImageBitmap?>(null, file?.path, kind) {
        value = file?.let { BackdropFiles.poster(it, kind) }
    }

    SettingsPageShell(L("背景"), L("自定义背景图或视频，上面盖一层可调透明度的遮罩"), onBack) {

        GroupLabel(L("预览"))
        CardColumn {
            CardBox {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    val p = poster
                    if (p != null) {
                        Image(
                            bitmap = p,
                            contentDescription = null,
                            contentScale = if (scale == BackdropScale.CROP) ContentScale.Crop
                            else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // 遮罩：和真实渲染同一层（模糊没在这儿模拟，它只是「糊一点」的提示）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.background.copy(alpha = tint / 100f)
                            )
                    )
                    if (p == null) {
                        Text(
                            if (file == null) L("还没选图片或视频") else L("读不出预览"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }

        GroupLabel(L("背景"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("启用背景"),
                    subtitle = L("关掉就回到原来的纯色底，选好的文件会留着"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    checked = enabled
                ) { on ->
                    AppCore.prefs.backdropEnabled = on
                    commit()
                },
                dropdownSpec(
                    title = L("背景类型"),
                    subtitle = if (kinds.size > 1) L("图片和视频各留一份，来回切不用重选")
                    else L("视频要关掉「预见式返回动画」才能选，见下方说明"),
                    icon = Icons.Filled.PlayArrow,
                    options = kinds.map { L(it.label) },
                    selectedIndex = kinds.indexOf(kind).coerceAtLeast(0)
                ) { index ->
                    val k = kinds[index.coerceIn(0, kinds.lastIndex)]
                    AppCore.prefs.backdropKind = k.id
                    // 切类型后把偏好里的文件名对齐到这个类型自己的那份（有就自动认领）
                    BackdropFiles.syncPref(ctx, k)
                    commit()
                }.copy(visible = enabled),
                RowSpec(
                    title = L("选择图片"),
                    subtitle = info?.takeIf { kind == BackdropKind.IMAGE }
                        ?.let { L("%s · %s · %s").format(it.name, it.sizeText, it.detail) }
                        ?: if (kind == BackdropKind.IMAGE) L("还没有选图片，从系统文件选择器里挑一张")
                        else L("从系统文件选择器里挑一张，会复制进应用私有目录"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Add,
                    visible = enabled && kind == BackdropKind.IMAGE,
                    onClick = pickImage
                ),
                RowSpec(
                    title = L("选择视频"),
                    subtitle = info?.takeIf { kind == BackdropKind.VIDEO }
                        ?.let { L("%s · %s · %s").format(it.name, it.sizeText, it.detail) }
                        ?: if (kind == BackdropKind.VIDEO) L("还没有选视频，从系统文件选择器里挑一段")
                        else L("从系统文件选择器里挑一段，会复制进应用私有目录"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Add,
                    visible = enabled && kind == BackdropKind.VIDEO,
                    onClick = pickVideo
                )
            )
        )

        GroupLabel(L("外观"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("遮罩不透明度"),
                    subtitle = L("%s%%（100%% = 完全盖住背景）").format(tint.roundToInt()),
                    visible = enabled,
                    content = {
                        Slider(
                            value = tint,
                            onValueChange = { tint = it },
                            valueRange = 0f..100f,
                            onValueChangeFinished = {
                                AppCore.prefs.backdropTint = tint.roundToInt()
                                commit()
                            }
                        )
                    }
                ),
                RowSpec(
                    title = L("模糊"),
                    subtitle = if (!sdkOk) L("需要 Android 12 及以上，当前系统不支持")
                    else L("%s（0 = 不模糊，糊一点文字更好读）").format(blur.roundToInt()),
                    subtitleMaxLines = 2,
                    visible = enabled,
                    content = {
                        Slider(
                            value = blur,
                            onValueChange = { blur = it },
                            valueRange = 0f..40f,
                            enabled = sdkOk,
                            onValueChangeFinished = {
                                AppCore.prefs.backdropBlur = blur.roundToInt()
                                commit()
                            }
                        )
                    }
                ),
                dropdownSpec(
                    title = L("填充方式"),
                    subtitle = L("裁剪铺满 = 填满整屏；完整显示 = 按原比例，留边的地方露出遮罩"),
                    options = BackdropScale.entries.map { L(it.label) },
                    selectedIndex = BackdropScale.entries.indexOf(scale)
                ) { index ->
                    AppCore.prefs.backdropScale = BackdropScale.entries[index].id
                    commit()
                }.copy(visible = enabled),
                switchSpec(
                    title = L("子页也显示背景"),
                    subtitle = L("关掉后只有 5 个 tab 透出背景，工具管理 / 记忆库 / 各种子页回到纯色底"),
                    subtitleMaxLines = 3,
                    checked = subPages
                ) { on ->
                    AppCore.prefs.backdropSubpages = on
                    commit()
                }.copy(visible = enabled),
                RowSpec(
                    title = L("卡片不透明度"),
                    subtitle = L("%s%%（100%% = 原来的不透明卡片）").format(cardA.roundToInt()),
                    visible = enabled,
                    content = {
                        Slider(
                            value = cardA,
                            onValueChange = { cardA = it },
                            valueRange = 0f..100f,
                            onValueChangeFinished = {
                                AppCore.prefs.backdropCardAlpha = cardA.roundToInt()
                                commit()
                            }
                        )
                    }
                ),
                switchSpec(
                    title = L("按钮与弹窗也跟着变透明"),
                    subtitle = L("关掉时只动卡片和底栏；打开后胶囊按钮 / 下拉菜单 / 弹窗也按「卡片不透明度」变透明"),
                    subtitleMaxLines = 3,
                    checked = AppCore.prefs.backdropChromeTint
                ) { on ->
                    AppCore.prefs.backdropChromeTint = on
                    commit()
                }.copy(visible = enabled)
            )
        )

        // 底栏：**不依赖自定义背景**。它是浮在内容上面的一层，
        // 半透明 / 毛玻璃时透出来的是从它下面划过去的卡片（所以放在背景开关之外）。
        GroupLabel(L("底栏"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("底栏不透明度"),
                    subtitle = L("%s%%（100%% = 不透明底栏）").format((BarLive.alpha * 100).roundToInt()),
                    content = {
                        Slider(
                            value = BarLive.alpha * 100f,
                            // 拖动时直接改内存里的 BarLive → 底栏实时跟着变；松手才落盘
                            onValueChange = { BarLive.preview(alpha = it / 100f) },
                            valueRange = 0f..100f,
                            onValueChangeFinished = {
                                BarLive.commit()
                                commit()
                            }
                        )
                    }
                ),
                RowSpec(
                    title = L("底栏模糊"),
                    subtitle = if (android.os.Build.VERSION.SDK_INT >= 31)
                        L("%s dp（0 = 不模糊；模糊的是底栏下面划过去的内容）").format(BarLive.blur.roundToInt())
                    else L("需要 Android 12 及以上，当前系统只做半透明"),
                    subtitleMaxLines = 2,
                    content = {
                        Slider(
                            value = BarLive.blur,
                            onValueChange = { BarLive.preview(blur = it) },
                            valueRange = 0f..40f,
                            enabled = android.os.Build.VERSION.SDK_INT >= 31,
                            onValueChangeFinished = {
                                BarLive.commit()
                                commit()
                            }
                        )
                    }
                )
            )
        )

        if (file != null) {
            Spacer(Modifier.height(7.dp))
            CardColumn {
                PillButton(L("清除背景"), Modifier.fillMaxWidth(), outlined = true, color = Sem.bad) {
                    BackdropFiles.clear(ctx)
                    AppCore.prefs.backdropFile = ""
                    AppCore.prefs.backdropEnabled = false
                    toast(ctx, L("背景已清除"))
                    commit()
                }
            }
        }

    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.ShizukuHelper
import com.xtt.mcpbox.autoGrantJoin
import com.xtt.mcpbox.autoGrantMissingPermissions
import com.xtt.mcpbox.autoGrantResultText
import com.xtt.mcpbox.grantSilently
import com.xtt.mcpbox.grantId
import com.xtt.mcpbox.permNeedLabel
import com.xtt.mcpbox.privilegedLauncher
import com.xtt.mcpbox.resetBlockedAttempts
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.core.LogEntry
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.server.McpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 上一次自动补齐的时间（elapsedRealtime）：防止切来切去时反复开 shell。 */
    private var lastAutoGrantAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCore.init(application)
        // 统计：打开一次 App 记一次（一个进程只记一次，转屏 / 切回任务不算新的）
        AppCore.stats.noteAppLaunch()
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
                // 首次引导盖在整棵界面之前：走完（或跳过走完）才会进 AppRoot
                if (OnboardingState.visible) {
                    OnboardingScreen(
                        onLangChanged = { langRev++ },
                        onThemeChanged = { themeRev++ },
                        requestPermission = ::handlePermNeed
                    )
                } else {
                    AppRoot(
                        requestPermission = ::handlePermNeed,
                        onAutoGrant = ::handleAutoGrant,
                        onRequestShizuku = ::requestShizuku,
                        onThemeChanged = { themeRev++ },
                        onLangChanged = { langRev++ },
                        langRev = langRev
                    )
                }
            }
        }
    }

    /**
     * 进入 App 时补一次权限（回到前台也会走这里，比如刚在系统设置页里给完权限回来）。
     *
     * 只在「设有 Root / Shizuku」而且真的缺东西时才动手；整个过程静默，
     * 结果只写日志 + 补到了才弹一条 Toast —— 没有特权后端时等于不存在。
     */
    override fun onStart() {
        super.onStart()
        autoGrantIfEnabled()
    }

    private fun autoGrantIfEnabled() {
        if (!AppCore.prefs.autoGrantPermissions) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoGrantAt < AUTO_GRANT_MIN_GAP_MS) return
        lastAutoGrantAt = now
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) { autoGrantMissingPermissions() }
            if (done.isNotEmpty()) {
                toastNow(L("已自动补齐 %s").format(autoGrantJoin(done.map { it.grantId() })))
            }
        }
    }

    /** 首页「一键补齐」：把缺的、能静默开的都开掉，然后把结果告诉用户（含失败原因）。 */
    private fun handleAutoGrant() {
        lifecycleScope.launch {
            val (done, launcher) = withContext(Dispatchers.IO) {
                // 手动点就是要「再试一次」：把自动那轮记下的失败清掉
                resetBlockedAttempts()
                val l = privilegedLauncher()
                autoGrantMissingPermissions() to l
            }
            toastNow(autoGrantResultText(done, launcher))
        }
    }

    private fun toastNow(text: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizuku() {
        val message = ShizukuHelper.request()
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 权限入口：**有 Shizuku / root 就先试着直接开**，开不了（或这项不能静默开）
     * 再走原来的系统页面 / 弹窗流程。
     *
     * 只对不会把自己进程搞重启的那几项做静默：`appops`（悬浮窗、全部文件访问）
     * 和电池白名单。通知是**运行时权限**——`pm grant` 会让系统把正在运行的自己杀掉重启，
     * 所以它永远走系统弹窗。
     */
    private fun handlePermNeed(need: PermNeed) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { grantSilently(need) }
            if (ok) {
                toastNow(L("已直接授权（%s）").format(permNeedLabel(need)))
                return@launch
            }
            openSystemPermission(need)
        }
    }

    private fun openSystemPermission(need: PermNeed) {
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

/** 底栏「再点一下当前 tab」的双击窗口。单击当前格没有动作，所以放宽一点没副作用。 */
private const val DOUBLE_TAP_MS = 600L

/** 两次自动补齐之间至少隔这么久：切来切去（onStart 频繁触发）时别反复开 shell。 */
private const val AUTO_GRANT_MIN_GAP_MS = 4_000L

@Composable
fun AppRoot(
    requestPermission: (PermNeed) -> Unit,
    /** 首页「一键补齐」：把缺的、能静默开的系统权限一次开掉。 */
    onAutoGrant: () -> Unit,
    onRequestShizuku: () -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    /** 语言版本号：变化时只重建界面内容，导航状态（tab / 子页面）留在外面。 */
    langRev: Int = 0
) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var subScreen by rememberSaveable { mutableStateOf("") }
    // 设置页停在哪个子页。**故意放在 key(langRev) 之外**：切语言/主题会重建整棵树，
    // 状态放在里面的话用户会被从子页甩回设置首页
    var settingsPage by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf(AppCore.server.status()) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var pending by remember { mutableStateOf<List<ApprovalRequest>>(emptyList()) }

    // 让每个页面（含子页面）的滚动位置、输入内容在切换后保留 ——
    // 否则从「自定义工具」返回设置页时会跳回顶部
    val tabStateHolder = rememberSaveableStateHolder()
    val screenStateHolder = rememberSaveableStateHolder()

    // 每天第一次打开时检查一次更新（可以在「关于」里关掉）；
    // 开发者模式里的「下次启动强制检查更新」会把它变成无条件检查一次
    var updateInfo by remember { mutableStateOf<com.xtt.mcpbox.UpdateChecker.Info?>(null) }
    // 开发者模式里「预览更新弹窗」拿到的信息（可能是造的示例），只用来渲染弹窗
    var previewUpdate by remember { mutableStateOf<com.xtt.mcpbox.UpdateChecker.Info?>(null) }
    LaunchedEffect(Unit) {
        val prefs = AppCore.prefs
        val forced = prefs.forceUpdateCheckNext
        if (forced || (prefs.updateCheckDaily && prefs.lastUpdateCheck != com.xtt.mcpbox.UpdateChecker.today())) {
            prefs.forceUpdateCheckNext = false
            // 强制检查时忽略版本比较（本地 versionName 只在发版时变，否则永远比不过最新 tag），
            // 这样「模拟第一次进入」才真的看得到弹窗
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val r = com.xtt.mcpbox.UpdateChecker.check(ignoreVersion = forced)
                (r as? com.xtt.mcpbox.UpdateChecker.Result.Newer)?.info
            }
            prefs.lastUpdateCheck = com.xtt.mcpbox.UpdateChecker.today()
            if (info != null) updateInfo = info
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

    val shownUpdate = updateInfo ?: previewUpdate
    shownUpdate?.let { info ->
        val previewing = updateInfo == null
        fun closeUpdate() { if (previewing) previewUpdate = null else updateInfo = null }
        UpdateAvailableDialog(
            info = info,
            previewing = previewing,
            onDownload = {
                openUrl(ctx, info.url)
                closeUpdate()
            },
            onDismiss = { closeUpdate() }
        )
    }

    // 整个 App 的容器：底层常驻主界面 + 上层全屏子页。
    // 两者必须是同一个 Box 的孩子 —— 在函数体里平铺两个并列的「兄弟」，
    // 布局、命中测试、转场都不好保证（曾经因此整页白屏）。
    Box(Modifier.fillMaxSize()) {

    // 子页内容。抽成 lambda：转场自己驱动时，需要按「退出动画期间该显示哪一页」来渲染。
    val renderSubPage: @Composable (String) -> Unit = { screen ->
        when (screen) {
            "about" -> AboutScreen(
                ctx = ctx,
                revision = revision,
                onChanged = { revision++ },
                onLangChanged = onLangChanged,
                onPreviewUpdate = { previewUpdate = it },
                // 关于页里手动「检查更新」查到新版 → 走同一个「发现新版本」弹窗
                onUpdateFound = { updateInfo = it },
                onBack = { subScreen = "" }
            )
            "memory" -> MemoryScreen(
                ctx = ctx,
                revision = revision,
                onChanged = { revision++ },
                onBack = { subScreen = "" }
            )
            "backup" -> BackupScreen(
                ctx = ctx,
                revision = revision,
                onChanged = { revision++ },
                onThemeChanged = onThemeChanged,
                onLangChanged = onLangChanged,
                onBack = { subScreen = "" }
            )
            "tools" -> ToolsScreen(
                ctx = ctx,
                revision = revision,
                onChanged = { revision++ },
                onBack = { subScreen = "" }
            )
            // 未知值也兜到工具管理，避免整页空白
            else -> ToolsScreen(
                ctx = ctx,
                revision = revision,
                onChanged = { revision++ },
                onBack = { subScreen = "" }
            )
        }
    }

    val navItems = listOf(
        NavItem(L("首页"), Icons.Filled.Home),
        NavItem(L("终端"), Icons.Filled.Build),
        NavItem(L("权限"), Icons.Filled.Lock),
        NavItem(L("日志"), Icons.Filled.List),
        NavItem(L("设置"), Icons.Filled.Settings)
    )

    // 设置页进到子页（外观 / 网络 / 安全 / 终端 / 后台…）时隐藏底栏 ——
    // 跟「工具管理 / 记忆库 / 关于」这些全屏子页保持一致，子页里只有一个返回按钮，
    // 不然底栏还亮着、点了又跳走，等于给用户两条互相矛盾的出口。
    val inSettingsSubPage = tab == 4 && settingsPage.isNotEmpty()

    // 「再点一下底栏当前 tab」的计数器：每次双击 +1。页面只在自己活着的时候
    // 看到它变化才滚动（NavReselectEffect），所以切页回来不会误回顶。
    var scrollTopTick by remember { mutableStateOf(0) }
    // 双击判定：只认「同一格、间隔 < DOUBLE_TAP_MS」的第二次点击。
    // 切 tab 仍然是按下就响应 —— 没跟着双击改成「等超时」，
    // 否则点任何一格都要慢半拍；单击当前格本来就什么都不做，等也不会变快。
    var lastTapTab by remember { mutableStateOf(-1) }
    var lastTapAt by remember { mutableStateOf(0L) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = !inSettingsSubPage,
                enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(120))
            ) {
                BottomPillNav(navItems, tab) { index ->
                    if (index != tab) {
                        // 切页面：切走就清掉双击计数（点 A → 点 B → 再点 A 不算双击）
                        tab = index
                        lastTapTab = -1
                    } else {
                        // 当前这一格：双击才回顶（终端是回底）
                        val now = SystemClock.uptimeMillis()
                        if (lastTapTab == index && now - lastTapAt <= DOUBLE_TAP_MS) {
                            scrollTopTick++
                            lastTapTab = -1        // 一次双击只回一次
                        } else {
                            lastTapTab = index
                            lastTapAt = now
                        }
                    }
                }
            }
        }
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
                    onAutoGrant = onAutoGrant,
                    onOpenPermissions = { tab = 1 },
                    scrollTopTick = scrollTopTick
                )
                1 -> TerminalScreen(
                    ctx = ctx,
                    revision = revision,
                    onRequestShizuku = onRequestShizuku,
                    onChanged = { revision++ },
                    scrollTopTick = scrollTopTick
                )
                2 -> PermissionScreen(ctx, revision, scrollTopTick) { revision++ }
                3 -> LogScreen(ctx, logs, scrollTopTick) { revision++ }
                else -> SettingsScreen(
                    // 这里的入口是「松手才触发」（Compose 的 clickable 语义：按下高亮、松手进入、滑出取消）
                    ctx = ctx,
                    status = status,
                    revision = revision,
                    page = settingsPage,
                    onPage = { settingsPage = it },
                    onThemeChanged = onThemeChanged,
                    onLangChanged = onLangChanged,
                    onOpenTools = { subScreen = "tools" },
                    onOpenMemory = { subScreen = "memory" },
                    onOpenBackup = { subScreen = "backup" },
                    onOpenAbout = { subScreen = "about" },
                    onChanged = { revision++ },
                    onRestartService = { McpService.restart(ctx) },
                    scrollTopTick = scrollTopTick
                )
            }
            }
            }
        }
    }


    // 上层：全屏子页（工具管理 / 记忆库 / 备份与恢复 / 关于）。
    //
    // 转场**自己驱动**，不交给 AnimatedContent —— 这几页一直存在「进出都是瞬间切换、没有动画」的问题
    // （拖动预览却正常，说明是这一层转场本身没演）。自己控位移/透明度最直接：
    //   · 进入：从右边滑入 1/3 屏宽 + 淡入
    //   · 返回：滑回右边 + 淡出，动画期间继续显示旧页（用它自己的内容）
    //   · 跟手提交：这一页已经被拖出屏幕了，直接收掉（再演一遍会看到「跳回屏幕中间再滑走」）
    androidx.compose.runtime.key(langRev) {
    PredictiveBackBox(
        onBack = { subScreen = "" },
        handleBack = subScreen.isNotEmpty(),
        follow = subScreen.isNotEmpty() && AppCore.prefs.predictiveBack,
        // 这一层盖在常驻的主界面之上：**不能自带底色**，否则没有子页时会把主界面盖黑
        opaque = false
    ) {
    val commitDrag = LocalPredictiveCommitDrag.current
    val slide = remember {
        Animatable(if (subScreen.isNotEmpty()) 0f else 1f)   // 1 = 整层在右边屏外
    }
    var shown by remember { mutableStateOf(subScreen) }      // 退出动画期间还要显示的那一页
    LaunchedEffect(subScreen) {
        if (subScreen.isNotEmpty()) {
            shown = subScreen
            if (slide.value != 0f) {                          // 恢复现场时不重播进入动画
                slide.snapTo(1f)
                slide.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
            }
        } else if (shown.isNotEmpty()) {
            if (commitDrag > 0) {
                shown = ""                                     // 已经拖出去了，直接收掉
            } else {
                slide.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                shown = ""
            }
        }
    }
    if (shown.isNotEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = slide.value * size.width / 3f
                    alpha = 1f - slide.value * 0.85f
                }
        ) {
            screenStateHolder.SaveableStateProvider(shown) { renderSubPage(shown) }
        }
    }
    }
    }
    }   // ← 关掉整个 App 的容器 Box
}

/* ------------------------------------------------- 权限：能静默开就直接开 */

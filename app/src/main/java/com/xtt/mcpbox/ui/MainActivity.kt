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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
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
        // 图标自愈：老版本切图标可能把进程杀在半路，留下「一个 LAUNCHER 组件都没开」的状态
        // （桌面图标变系统默认图、点了进不去）。每次启动按偏好对齐一遍，组件开关带 DONT_KILL_APP。
        AppIcon.ensureConsistent(this)
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
            // 动态取色的种子来源：壁纸 / 自定义背景（背景取色是在设置页算好存起来的，
            // 这里只读缓存，跟当前背景文件对不上就当没有）
            val seedSource = remember(themeRev) { AppCore.prefs.dynamicSeedSource }
            val backdropSeed = remember(themeRev) {
                if (com.xtt.mcpbox.ui.DynamicSeedSource.of(seedSource) ==
                    com.xtt.mcpbox.ui.DynamicSeedSource.BACKDROP
                ) BackdropSeed.cached(this@MainActivity) else 0
            }
            MCPBoxTheme(
                seedArgb = seed,
                paletteStyleId = style,
                darkModeId = darkMode,
                dynamicColor = dynColor,
                seedSourceId = seedSource,
                backdropSeed = backdropSeed
            ) {
                // 把当前配色同步给悬浮窗（它在 Compose 外面，拿不到 MaterialTheme）
                val cs = MaterialTheme.colorScheme
                LaunchedEffect(seed, style, darkMode, dynColor, seedSource, backdropSeed) {
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
                // 自定义背景（图片 / 视频）铺在最底下；页面自己的 PageSurface 负责往上叠遮罩。
                // 它现读 Prefs，所以主题版本号变了（也就是背景设置改了）要重新取一遍。
                val backdrop = rememberBackdropState(themeRev)
                Box(Modifier.fillMaxSize()) {
                    BackdropLayer(backdrop)
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
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
                        // 条款更新提示：盖在 App 之上（底栏也盖住），但让引导先走
                        if (!OnboardingState.visible) LegalNoticeHost()
                    }
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

/**
 * 条款提示的宿主：提示页 + 盖在上面的全文页。
 *
 * 两者都是设置子页的版式（大标题 + 返回箭头），不是弹窗：
 * 「前往查看」打开全文，返回回到提示页，再返回（或点「我已知晓」）记为已读。
 */
@Composable
private fun LegalNoticeHost() {
    val ctx = LocalContext.current
    // 启动时判断一次；判断完就不再重复评估（否则「我已知晓」写进 prefs 后
    // 这个 state 会被冲掉，页面自己消失，看不到收起动作）
    LaunchedEffect(Unit) { LegalNotice.visible = LegalNotice.shouldPrompt() }
    if (!LegalNotice.visible) return
    PageSurface(sub = false) {
        if (LegalNotice.viewing) {
            LegalScreen(
                ctx = ctx,
                doc = LegalNotice.doc,
                handleBack = true,
                onBack = { LegalNotice.viewing = false }
            )
        } else {
            LegalNoticePage(
                onAcknowledge = { LegalNotice.acknowledge() },
                onView = {
                    LegalNotice.doc = Legal.Doc.TERMS
                    LegalNotice.viewing = true
                }
            )
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
    // 两个「有子页」的 tab，各自一个页面栈。**故意放在 key(langRev) 之外**：
    // 切语言/主题会重建整棵树，状态放在里面的话用户会被从子页甩回首页。
    //   设置 tab：外观与语言 / 网络与访问 / 终端与命令 / 规则 / 工具管理 / 记忆库 / 备份与恢复 / 关于
    //   权限 tab：工具包编辑页
    var settingsPage by rememberSaveable { mutableStateOf("") }
    var permPage by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf(AppCore.server.status()) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var pending by remember { mutableStateOf<List<ApprovalRequest>>(emptyList()) }

    // 让每个页面（含子页面）的滚动位置、输入内容在切换后保留 ——
    // 否则从「自定义工具」返回设置页时会跳回顶部
    val tabStateHolder = rememberSaveableStateHolder()

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

    val navItems = listOf(
        NavItem(L("首页"), Icons.Filled.Home),
        NavItem(L("终端"), Icons.Filled.Build),
        NavItem(L("权限"), Icons.Filled.Lock),
        NavItem(L("日志"), Icons.Filled.List),
        NavItem(L("设置"), Icons.Filled.Settings)
    )

    // 进了子页就把底栏收起来（子页里只有一个返回按钮，底栏还亮着等于给用户两条互相矛盾的出口）。
    //
    // 【统一后的规则】所有子页都挂在**各自的 tab 内容里**，共用这一个 Scaffold 的 bottomBar，
    // 所以底栏是**滑动收起**的（AnimatedVisibility）；页面栈和返回键也由各自的宿主统一接。
    // 以前那套「另起一个全屏 Scaffold」的路子已经删掉了 —— 底栏只会瞬间消失，
    // 而且返回键得每个页面自己接（漏一个就没反应）。
    val inSubPage = (tab == 4 && settingsPage.isNotEmpty()) || (tab == 2 && permPage.isNotEmpty())

    // 输入法弹出来的时候底栏也得收起来。
    //
    // 踩过的坑：底栏是 Scaffold 的 bottomBar，内容区拿到的 bottom padding = 底栏高度；
    // 页面里又加了 `imePadding()`。窗口是 edge-to-edge、不随 IME 缩放，于是两段 padding
    // 相加 —— 输入框离键盘之间就多出一条「原本属于底栏」的空隙（终端页最明显）。
    // 现在 IME 一起来就把底栏收掉（动画和进子页一样），内容区 bottom padding 归零，
    // 只剩 IME 那一层，输入框正好贴着键盘。
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible

    // 视频背景时页面是透明的（视频在最底层）→ tab 切换也不能让两页重叠（见 pageSlide 的 noOverlap）。
    // 必须在组合作用域里读一次：transitionSpec 那个 lambda 不是 @Composable。
    val tabHardCut = rememberTransparentPages()

    // 「再点一下底栏当前 tab」的计数器：每次双击 +1。页面只在自己活着的时候
    // 看到它变化才滚动（NavReselectEffect），所以切页回来不会误回顶。
    var scrollTopTick by remember { mutableStateOf(0) }
    // 双击判定：只认「同一格、间隔 < DOUBLE_TAP_MS」的第二次点击。
    // 切 tab 仍然是按下就响应 —— 没跟着双击改成「等超时」，
    // 否则点任何一格都要慢半拍；单击当前格本来就什么都不做，等也不会变快。
    var lastTapTab by remember { mutableStateOf(-1) }
    var lastTapAt by remember { mutableStateOf(0L) }

    // 切语言 / 主题时只重建内容，导航状态（tab / 两个页面栈）留在外面
    androidx.compose.runtime.key(langRev) {
    Scaffold(
        containerColor = backdropBoxColor(LocalBackdrop.current),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = !inSubPage && !imeVisible,
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
                    val enter = slideInHorizontally(tween(230)) { width -> slide * width / 10 } +
                        fadeIn(tween(200))
                    // 视频背景时页面是透明的，两页重叠会互相透字 → 旧页直接收掉（和 pageSlide 一个口径）
                    val exit: androidx.compose.animation.ExitTransition =
                        if (tabHardCut) androidx.compose.animation.ExitTransition.None
                        else slideOutHorizontally(tween(200)) { width -> -slide * width / 10 } +
                            fadeOut(tween(150))
                    enter togetherWith exit
                },
                label = "tabContent"
            ) { current ->
            // 每个 tab 首页自己也铺一层底：转场时两页重叠，各自有底才不会互相透出文字。
            // 这里是 tab 根页 → sub = false（「子页也显示背景」关掉时这层照样透出背景）
            PageSurface {
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
                2 -> PermissionScreen(
                    ctx = ctx,
                    revision = revision,
                    scrollTopTick = scrollTopTick,
                    page = permPage,
                    onPage = { permPage = it }
                ) { revision++ }
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
                    onPreviewUpdate = { previewUpdate = it },
                    onUpdateFound = { updateInfo = it },
                    onChanged = { revision++ },
                    onRestartService = { McpService.restart(ctx) },
                    scrollTopTick = scrollTopTick
                )
            }
            }
                        }
}
        }
    }
    }
}

/* ------------------------------------------------- 权限：能静默开就直接开 */

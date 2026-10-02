// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.ShizukuHelper
import com.xtt.mcpbox.core.Backup
import com.xtt.mcpbox.core.CommandLauncher
import com.xtt.mcpbox.core.ShellBackends
import com.xtt.mcpbox.core.ShellRunner
import com.xtt.mcpbox.i18n.L
import com.xtt.mcpbox.i18n.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 初始引导的显隐。
 *
 * 放在全局而不是 AppRoot 里，是因为「开发者模式」里要能把它重新拉起来，
 * 而开发者页在 AppRoot 里面 —— 状态放外面才推得动上面那层（跟 [AppCore.overlayPalette] 一个路子）。
 */
object OnboardingState {
    var visible by mutableStateOf(false)

    /**
     * 当前走到第几步。
     *
     * **故意不放 remember / rememberSaveable**：进程被杀后重开时，
     * 系统恢复 saved instance state 会把「上次走到第几步」一起恢复出来 ——
     * 那时候再进引导就是从中间续上，看着像「自己往前跳了两页」。
     * 放这里则：转屏 / 主题重建（Activity 重建）不丢，进程重开就是新的一轮。
     */
    var step by mutableStateOf(0)
}

/** 引导一共几步。 */
private const val STEPS = 5

/** 连点保护：两次「下一步」至少隔这么久，手快点两下不会跳两步。 */
private const val ADVANCE_GAP_MS = 400L

/** 每一步顶部那颗大图标。 */
private val StepIcons: List<ImageVector> = listOf(
    Icons.Filled.Home,
    Icons.Filled.Star,
    Icons.Filled.Lock,
    Icons.Filled.Refresh,
    Icons.Filled.Done
)

/**
 * 初始引导：欢迎 → 语言 → 权限 → 恢复备份 → 开始使用。
 *
 * 版式统一是「上面图标 / 中间选项 / 下面按钮」：
 *  - 顶部：大图标 + 标题 + 一句说明；
 *  - 中间：这一步要做的事（自己滚动，切步带滑动动画）；
 *  - 底部：**一个**按钮，文案跟着这一步的状态走 ——
 *    没完成是「跳过」，完成了是「下一步」，恢复页选好文件是「恢复」，最后一步是「开始使用」。
 */
@Composable
fun OnboardingScreen(
    onLangChanged: () -> Unit = {},
    onThemeChanged: () -> Unit = {},
    requestPermission: (PermNeed) -> Unit = {},
    onFinish: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 只放内存里（放在 OnboardingState 上）：进程被杀后重开必定回到第一步，
    // 但转屏 / 换主题这种 Activity 重建不会把用户甩回开头
    val step = OnboardingState.step
    // 每一步都留一条日志（logcat key：MCPBoxOnboarding）：万一它还自己跳，
    // 看日志就知道是「谁」把它推到下一步的
    fun goTo(v: Int, why: String) {
        android.util.Log.i("MCPBoxOnboarding", "step $step -> $v（$why）")
        OnboardingState.step = v
    }
    // 语言一改就整块重建，否则界面上的 L(...) 还是旧语言
    var langRev by remember { mutableStateOf(0) }
    // 用来重读权限 / 设备状态（轮询 + 操作后手动 +1）
    var rev by remember { mutableStateOf(0) }
    // 第 4 步恢复过没有
    var restored by remember { mutableStateOf(false) }
    // 恢复设置可能把语言也一起换掉：真发生时等离开这一页再整块重建，
    // 否则恢复结果那几行字会当场被刷掉
    var langDirty by remember { mutableStateOf(false) }
    var lastAdvance by remember { mutableStateOf(SystemClock.uptimeMillis()) }

    // 恢复页的状态放在这一层：底栏那个「恢复」按钮要用到
    var parts by remember { mutableStateOf<List<RestorePart>>(emptyList()) }
    var merge by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step) {
        if (step != 3 && langDirty) {
            langDirty = false
            langRev++
        }
    }

    fun finish() {
        AppCore.prefs.onboardDone = true
        OnboardingState.step = 0
        OnboardingState.visible = false
        onFinish()
    }

    fun advance() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAdvance < ADVANCE_GAP_MS) return
        lastAdvance = now
        if (step < STEPS - 1) goTo(step + 1, "下一步按钮")
    }

    // 系统返回 = 回上一步；第一步没得退（免得一进来就退出整个引导）
    BackHandler { if (step > 0) goTo(step - 1, "系统返回") }

    // 第 3 步的权限状态靠系统设置改，回到 App 要能立刻看见，所以轻量轮询
    LaunchedEffect(Unit) {
        while (true) {
            rev++
            delay(1000)
        }
    }

    val permsAllOk = remember(rev) { allPermissionsReady() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadBackup(ctx, uri, scope) { loaded, r ->
        parts = loaded
        restoreResult = r
    } }

    fun doRestore() {
        if (parts.isEmpty() || restoring) return
        restoring = true
        scope.launch {
            restoreResult = runRestore(ctx, parts, merge)
            restoring = false
            restored = true
            AppCore.config.reload()
            AppCore.permissions.load()
            AppCore.customTools.load()
            AppCore.log.enabled = AppCore.config.logEnabled
            onThemeChanged()
            onLangChanged()      // 真换了语言也只是记一下，离开这一页才重建
            langDirty = true
            toast(ctx, L("恢复完成"))
        }
    }

    // 这一点算不算「全部完成」
    val stepDone = when (step) {
        2 -> permsAllOk
        3 -> restored
        else -> true
    }
    // 恢复页：选好文件、还没恢复 → 主按钮就是「恢复」
    val readyToRestore = step == 3 && parts.isNotEmpty() && !restored

    val buttonLabel = when {
        step == STEPS - 1 -> L("开始使用")
        readyToRestore -> L("恢复")
        stepDone -> L("下一步")
        else -> L("跳过")
    }
    val buttonOutlined = buttonLabel == L("跳过")

    key(langRev) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val slide = if (forward) 1 else -1
                    (
                        slideInHorizontally(tween(260)) { w -> slide * w / 4 } +
                            fadeIn(tween(200))
                        ).togetherWith(
                        slideOutHorizontally(tween(220)) { w -> -slide * w / 4 } +
                            fadeOut(tween(150))
                    )
                },
                label = "onboardingStep",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { current ->
                Column(Modifier.fillMaxSize()) {
                    StepHeader(current)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 10.dp)
                    ) {
                        when (current) {
                            0 -> WelcomeStep()
                            1 -> LanguageStep(ctx) {
                                langRev++
                                onLangChanged()
                            }
                            2 -> PermissionStep(ctx, rev, requestPermission) { rev++ }
                            3 -> RestoreStep(
                                parts = parts,
                                merge = merge,
                                restoring = restoring,
                                result = restoreResult,
                                onPick = {
                                    picker.launch(
                                        arrayOf(
                                            "application/zip", "application/json",
                                            "text/plain", "*/*"
                                        )
                                    )
                                },
                                onMerge = { merge = it }
                            )
                            else -> DoneStep(rev, restored, permsAllOk)
                        }
                    }
                }
            }

            BottomBar(
                label = buttonLabel,
                outlined = buttonOutlined,
                enabled = !restoring,
                onClick = {
                    when {
                        step == STEPS - 1 -> finish()
                        readyToRestore -> doRestore()
                        else -> advance()
                    }
                }
            )
        }
    }
}

/* ------------------------------------------------------------------ 从备份里读 */

/** 选完文件后解析：zip 直接读包，单个 json 靠嗅探认。 */
private fun loadBackup(
    ctx: Context,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    done: (List<RestorePart>, String?) -> Unit
) {
    scope.launch {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                when {
                    bytes == null || bytes.isEmpty() -> emptyList()
                    // zip：PK\x03\x04
                    bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
                        Backup.readZip(bytes).parts.map { (p, t) -> RestorePart(p, t) }
                    else -> {
                        val text = bytes.toString(Charsets.UTF_8)
                        val p = Backup.sniff(text)
                        if (p == null) emptyList() else listOf(RestorePart(p, text))
                    }
                }
            }.getOrElse { emptyList() }
        }
        if (loaded.isEmpty()) {
            toast(ctx, L("没有能识别的备份内容"))
        } else {
            done(loaded, null)
        }
    }
}

/** 真正写回去，返回一段结果文本。 */
private suspend fun runRestore(
    ctx: Context,
    parts: List<RestorePart>,
    merge: Boolean
): String = withContext(Dispatchers.IO) {
    parts.map { rp ->
        // 设置这一块没有「合并」的概念，一律整份覆盖（跟备份页一致）
        val mode = if (merge && rp.part != Backup.Part.SETTINGS) Backup.Mode.MERGE
        else Backup.Mode.REPLACE
        runCatching {
            Backup.apply(
                part = rp.part,
                text = rp.text,
                mode = mode,
                restoreToken = false,
                settings = AppCore.prefs,
                memory = AppCore.memory,
                customTools = AppCore.customTools
            )
        }.fold(
            onSuccess = { L("%s：%s").format(partName(rp.part), it) },
            onFailure = { L("%s：失败 —— %s").format(partName(rp.part), it.message ?: "") }
        )
    }.joinToString("\n")
}

/* ------------------------------------------------------------------ 通用零件 */

@Composable
private fun StepHeader(step: Int) {
    val title = when (step) {
        0 -> L("欢迎使用 MCP 文件盒")
        1 -> L("选择语言")
        2 -> L("授予权限")
        3 -> L("恢复备份")
        else -> L("准备就绪")
    }
    val subtitle = when (step) {
        0 -> L("把手机变成 AI 能读写的文件服务器")
        1 -> L("之后可以在「设置 → 外观与语言」里改")
        2 -> L("授权后 AI 才能正常读写文件")
        3 -> L("有备份的话可以现在恢复")
        else -> L("下面这些之后都能改")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                StepIcons[step.coerceIn(0, StepIcons.lastIndex)],
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 23.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BottomBar(
    label: String,
    outlined: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp)
    ) {
        PillButton(
            label,
            Modifier.fillMaxWidth(),
            outlined = outlined,
            enabled = enabled,
            onClick = onClick
        )
    }
}

/** 一排小字（图标 + 说明）。 */
@Composable
private fun BulletRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.5.sp,
            lineHeight = 20.sp
        )
    }
}

/* ------------------------------------------------------------------ 1 欢迎 */

@Composable
private fun WelcomeStep() {
    Column(Modifier.padding(horizontal = 14.dp)) {
        CardBox {
            BulletRow(L("AI 直接读写手机文件"))
            BulletRow(L("支持 MCP 协议，客户端填地址即可连接"))
            BulletRow(L("只在本机运行，数据不出手机"))
        }
    }
}

/* ------------------------------------------------------------------ 2 语言 */

@Composable
private fun LanguageStep(ctx: Context, onPicked: () -> Unit) {
    var selected by remember { mutableStateOf(AppCore.prefs.appLang) }
    var note by remember { mutableStateOf<String?>(null) }
    // 导入语言包之后要重新取一次列表
    var listRev by remember { mutableStateOf(0) }

    val importLang = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                toast(ctx, L("读不到这个文件"))
            } else {
                Lang.importPack(ctx, text).fold(
                    onSuccess = { (id, count) ->
                        note = L("已导入 %s（%s 条）").format(id, count)
                        listRev++
                    },
                    onFailure = { toast(ctx, L("导入失败：%s").format(it.message ?: "")) }
                )
            }
        }
    }

    fun pick(id: String) {
        AppCore.prefs.appLang = id
        Lang.init(ctx, id, Lang.systemIsEnglish)
        selected = id
        onPicked()   // 让外层整块重建，语言立刻生效
    }

    val choices = remember(listRev, selected) { Lang.languageChoices() }

    CardGroup(
        rows = choices.map { (id, name) ->
            RowSpec(
                title = name,
                onClick = { pick(id) },
                trailing = {
                    if (selected == id) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = L("使用中"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    )

    Spacer(Modifier.height(14.dp))
    Column(Modifier.padding(horizontal = 14.dp)) {
        PillButton(
            L("从文件导入语言包"),
            Modifier.fillMaxWidth(),
            outlined = true,
            color = MaterialTheme.colorScheme.primary
        ) { importLang.launch(arrayOf("application/json", "*/*")) }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------ 3 权限 */

/**
 * 拿到 root / Shizuku 之后顺手能授的那几项。
 *
 * 只做**静默能搞定**的：能授就授，授不了（设备没 root 也没 Shizuku）就什么都不做，
 * 让用户自己去点对应那一行。真正的授权动作在 [autoGrant]。
 */
@Composable
private fun PermissionStep(
    ctx: Context,
    rev: Int,
    requestPermission: (PermNeed) -> Unit,
    onRev: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val suLauncher = remember(rev) { ShellBackends.byId("root") }
    val rootAvailable = remember(rev) { suLauncher?.isAvailable() == true }
    val shizukuGranted = remember(rev) { ShizukuHelper.isGranted() }
    val privileged = remember(rev) { privilegedLauncher() }

    var note by remember { mutableStateOf<String?>(null) }
    // 已经为哪个后端（root / shizuku）跑过自动授权了 —— 同一个后端不重复跑
    var grantedFor by remember { mutableStateOf<String?>(null) }

    // 拿到了特权后端就把下面能静默开的权限打开
    LaunchedEffect(privileged?.id) {
        val id = privileged?.id ?: return@LaunchedEffect
        if (grantedFor == id) return@LaunchedEffect
        grantedFor = id
        val done = autoGrantAsync(ctx)
        if (done.isNotEmpty()) {
            note = L("已自动授权：%s").format(done.joinToString("、"))
            toast(ctx, note ?: "")
        }
        onRev()
    }

    val h = AppCore.host
    val storageOk = remember(rev) { h.hasAllFilesAccess() }
    val overlayOk = remember(rev) { h.canDrawOverlays() }
    val notifyOk = remember(rev) { h.hasNotificationPermission() }
    val batteryOk = remember(rev) { h.isIgnoringBatteryOptimizations() }

    fun askRoot() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val r = ShellRunner().run(suLauncher!!, "id -u", timeoutMs = 120_000)
                    r.stdout.trim() == "0"
                }.getOrDefault(false)
            }
            toast(ctx, if (ok) L("Root 已授权") else L("还没拿到 Root 权限"))
            onRev()
        }
    }

    GroupLabel(L("高级权限"))
    CardGroup(
        listOf(
            RowSpec(
                title = L("Shizuku"),
                subtitle = ShizukuHelper.statusText(ctx),
                icon = Icons.Filled.Star,
                onClick = if (shizukuGranted) null else ({
                    toast(ctx, ShizukuHelper.request())
                    onRev()
                }),
                trailing = {
                    if (shizukuGranted) {
                        OutlineTag(L("已就绪"), MaterialTheme.colorScheme.tertiary)
                    } else {
                        PillButton(L("申请"), outlined = true, compact = true) {
                            toast(ctx, ShizukuHelper.request())
                            onRev()
                        }
                    }
                }
            ),
            RowSpec(
                title = L("Root"),
                subtitle = if (rootAvailable) L("已检测到 su，点击触发授权")
                else L("未检测到 su，设备未 root 时跳过这一项"),
                subtitleMaxLines = 2,
                icon = Icons.Filled.Warning,
                onClick = if (rootAvailable) ({ askRoot() }) else null,
                trailing = {
                    PillButton(L("申请"), outlined = true, compact = true, enabled = rootAvailable) {
                        askRoot()
                    }
                }
            )
        )
    )

    note?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    GroupLabel(L("系统权限"))
    CardGroup(
        listOf(
            PermRow(
                title = L("文件访问权限"),
                desc = L("AI 才能读写手机文件"),
                icon = Icons.Filled.List,
                ok = storageOk,
                need = PermNeed.STORAGE,
                requestPermission = requestPermission
            ),
            PermRow(
                title = L("悬浮窗权限"),
                desc = L("审批弹窗显示在所有应用之上"),
                icon = Icons.Filled.Lock,
                ok = overlayOk,
                need = PermNeed.OVERLAY,
                requestPermission = requestPermission
            ),
            PermRow(
                title = L("通知权限"),
                desc = L("显示运行状态与审批提醒"),
                icon = Icons.Filled.Warning,
                ok = notifyOk,
                need = PermNeed.NOTIFICATION,
                requestPermission = requestPermission
            ),
            PermRow(
                title = L("忽略电池优化"),
                desc = L("防止后台被系统清掉"),
                icon = Icons.Filled.Warning,
                ok = batteryOk,
                need = PermNeed.BATTERY,
                requestPermission = requestPermission
            )
        )
    )
}

@Composable
private fun PermRow(
    title: String,
    desc: String,
    icon: ImageVector,
    ok: Boolean,
    need: PermNeed,
    requestPermission: (PermNeed) -> Unit
): RowSpec = RowSpec(
    title = title,
    subtitle = if (ok) desc else L("%s（未授权）").format(desc),
    icon = icon,
    onClick = if (ok) null else ({ requestPermission(need) }),
    trailing = {
        if (ok) {
            OutlineTag(L("已就绪"), MaterialTheme.colorScheme.tertiary)
        } else {
            PillButton(L("授权"), outlined = true, compact = true) { requestPermission(need) }
        }
    }
)

/** 四项系统权限是不是都齐了（「下一步」能不能点的依据）。 */
private fun allPermissionsReady(): Boolean {
    val h = AppCore.host
    return h.hasAllFilesAccess() && h.canDrawOverlays() &&
        h.hasNotificationPermission() && h.isIgnoringBatteryOptimizations()
}

/** 现在能用的特权后端：Shizuku 优先，其次 root；都没有就 null。 */
private fun privilegedLauncher(): CommandLauncher? {
    val avail = ShellBackends.available()
    // Shizuku 要「正在运行 + 已授权」才算可用，选中它不会弹任何窗口；
    // 只有它不可用时才去碰 su（这时 Magisk 会弹一次授权框，符合「申请提权」的预期）
    return avail.firstOrNull { it.id == "shizuku" } ?: avail.firstOrNull { it.id == "root" }
}

/**
 * 用特权后端把能静默授予的权限直接打开。
 *
 * 只有这几条命令是 shell 身份能改的：通知、悬浮窗、全部文件访问、电池白名单。
 * 返回真正成功的那些项（用来提示用户）。授不了的不报错，
 * 界面上那行还是「未授权」，用户自己点一下走系统流程即可。
 */
private fun autoGrant(ctx: Context): List<String> = run {
    val launcher = privilegedLauncher() ?: return emptyList()
    val pkg = ctx.packageName
    // 注意：这里**不能**用 `pm grant` 授运行时权限（通知就是运行时权限）——
    // 给正在运行的自己改运行时权限，系统会当场把进程杀掉重启，
    // 引导里看着就像「这一步自己往前跳了」。通知权限留给用户点那一行走系统弹窗。
    // 下面三条都只是 appops / 电池白名单，不会重启进程。
    val tasks = listOf(
        L("悬浮窗权限") to "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        L("文件访问权限") to "appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
        L("忽略电池优化") to "dumpsys deviceidle whitelist +$pkg"
    )
    val runner = ShellRunner()
    tasks.mapNotNull { (label, cmd) ->
        val ok = runCatching { runner.run(launcher, cmd, timeoutMs = 20_000).ok }.getOrDefault(false)
        if (ok) label else null
    }
}

/** [autoGrant] 的挂起版：跑命令会阻塞，不能占着主线程。 */
private suspend fun autoGrantAsync(ctx: Context): List<String> =
    withContext(Dispatchers.IO) { autoGrant(ctx) }

/* ------------------------------------------------------------------ 4 恢复备份 */

/** 从备份文件里读出来的一块内容。 */
internal class RestorePart(val part: Backup.Part, val text: String)

private fun partName(part: Backup.Part): String = when (part) {
    Backup.Part.MEMORY -> L("记忆库")
    Backup.Part.SETTINGS -> L("设置")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
}

private fun partDesc(part: Backup.Part, text: String): String = when (part) {
    Backup.Part.MEMORY -> L("实体与关系")
    Backup.Part.SETTINGS -> if (Backup.settingsHasToken(text)) L("含访问令牌") else L("不含访问令牌")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
}

@Composable
private fun RestoreStep(
    parts: List<RestorePart>,
    merge: Boolean,
    restoring: Boolean,
    result: String?,
    onPick: () -> Unit,
    onMerge: (Boolean) -> Unit
) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        CardBox {
            PillButton(
                L("选择备份文件"),
                Modifier.fillMaxWidth(),
                outlined = true
            ) { onPick() }
            Spacer(Modifier.height(10.dp))
            Text(
                L("支持 zip 备份包或单独的 json 文件"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        if (parts.isNotEmpty()) {
            GroupLabel(L("备份内容"))
            CardGroup(
                rows = parts.map { rp ->
                    RowSpec(
                        title = partName(rp.part),
                        subtitle = partDesc(rp.part, rp.text),
                        icon = Icons.Filled.Done
                    )
                }
            )

            GroupLabel(L("恢复方式"))
            CardGroup(
                rows = listOf(
                    switchSpec(
                        title = L("合并"),
                        subtitle = if (merge) L("保留现有内容，只写入备份里的")
                        else L("整份替换，现有内容会被清掉"),
                        subtitleMaxLines = 2,
                        icon = Icons.Filled.Warning,
                        checked = merge
                    ) { onMerge(it) }
                )
            )
        }

        if (restoring) {
            Spacer(Modifier.height(12.dp))
            Text(
                L("正在恢复…"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }

        result?.let {
            Spacer(Modifier.height(12.dp))
            CardBox {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 5 结束 */

@Composable
private fun DoneStep(rev: Int, restored: Boolean, permsAllOk: Boolean) {
    val langName = remember(rev) { Lang.packDisplayName(AppCore.prefs.appLang) }

    Column(Modifier.padding(horizontal = 14.dp)) {
        CardBox {
            InfoLine(L("界面语言"), langName)
            InfoLine(
                L("系统权限"),
                if (permsAllOk) L("全部就绪") else L("尚未全部授权，可稍后在首页授权")
            )
            InfoLine(
                L("备份"),
                if (restored) L("已恢复") else L("未恢复")
            )
        }
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            k,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            modifier = Modifier.width(86.dp)
        )
        Text(
            v,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

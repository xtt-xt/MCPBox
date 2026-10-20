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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
private const val STEPS = 6

/** 各步的下标。用常量而不是裸数字：插一步时不用去猜哪个 `2` 指的是什么。 */
private const val STEP_WELCOME = 0
private const val STEP_LANG = 1
private const val STEP_TERMS = 2
private const val STEP_PERM = 3
private const val STEP_RESTORE = 4
private const val STEP_DONE = 5

/** 条款页勾选行的高度：按两行文案（13.5sp / 19sp 行高）预留，避免变行时闪。 */
private val TERMS_AGREE_ROW_HEIGHT = 58.dp

/** 连点保护：两次「下一步」至少隔这么久，手快点两下不会跳两步。 */
private const val ADVANCE_GAP_MS = 400L

/** 每一步顶部那颗大图标。 */
private val StepIcons: List<ImageVector> = listOf(
    Icons.Filled.Home,
    Icons.Filled.Star,
    Icons.Filled.Info,       // 2 条款
    Icons.Filled.Lock,       // 3 权限
    Icons.Filled.Refresh,    // 4 恢复备份
    Icons.Filled.Done        // 5 就绪
)

/**
 * 初始引导：欢迎 → 语言 → 条款 → 权限 → 恢复备份 → 开始使用。
 *
 * 语言放在条款前面：条款正文按语言整份取（中文 / 英文），
 * 先让用户选好语言，读到的才是他看得懂的那一份。
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
    // 条款页：滚到底没有、勾选框勾了没有。
    // 放在 key(langRev) 外面 —— 换语言会整块重建，但已经读到的进度不该丢。
    val termsScroll = rememberScrollState()
    var termsAgreed by remember { mutableStateOf(false) }
    val termsAtEnd by remember {
        derivedStateOf { termsScroll.maxValue > 0 && termsScroll.value >= termsScroll.maxValue - 8 }
    }

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
        // 记「同意过哪个版本」，不是一个布尔值：条款升到 v2 时能据此要求重新确认
        if (termsAgreed && AppCore.prefs.legalAgreedVersion != Legal.VERSION) {
            AppCore.prefs.legalAgreedVersion = Legal.VERSION
            AppCore.prefs.legalAgreedAt = System.currentTimeMillis()
        }
        OnboardingState.step = 0
        OnboardingState.visible = false
        onFinish()
    }

    fun advance() {
        val now = SystemClock.uptimeMillis()
        // 连点保护：手快点两下不会跳两步
        if (now - lastAdvance < ADVANCE_GAP_MS) return
        lastAdvance = now
        if (step < STEPS - 1) goTo(step + 1, "下一步按钮")
    }

    // 系统返回 = 回上一步；第一步没得退（免得一进来就退出整个引导）
    BackHandler { if (step > 0) goTo(step - 1, "系统返回") }

    // 权限那一步的状态靠系统设置改，回到 App 要能立刻看见，所以轻量轮询
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
        STEP_PERM -> permsAllOk
        STEP_RESTORE -> restored
        else -> true
    }
    // 恢复页：选好文件、还没恢复 → 主按钮就是「恢复」
    val readyToRestore = step == STEP_RESTORE && parts.isNotEmpty() && !restored

    val buttonLabel = when {
        step == STEP_DONE -> L("开始使用")
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
                    if (current == STEP_TERMS) {
                        // 条款页自己管滚动：外层再套一层 verticalScroll 会变成嵌套滚动，
                        // 「滚到底没有」就测不准了
                        TermsStep(
                            ctx = ctx,
                            scroll = termsScroll,
                            atEnd = termsAtEnd,
                            agreed = termsAgreed,
                            onAgree = { termsAgreed = it }
                        )
                    } else Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 10.dp)
                    ) {
                        when (current) {
                            STEP_WELCOME -> WelcomeStep()
                            STEP_LANG -> LanguageStep(ctx) {
                                langRev++
                                onLangChanged()
                            }
                            STEP_PERM -> PermissionStep(ctx, rev, requestPermission) { rev++ }
                            STEP_RESTORE -> RestoreStep(
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

            // 屏幕上还挂着自己的审批浮层时，把底栏按钮禁掉：
            // 那一下很可能是从浮层「漏」下来的（有过「跳过按钮自己会动、还带波纹」的现象）
            val approvalShowing = remember(rev) { AppCore.approval.pendingRequests().isNotEmpty() }

            BottomBar(
                label = buttonLabel,
                outlined = buttonOutlined,
                enabled = !restoring && !approvalShowing &&
                    (step != STEP_TERMS || termsAgreed),
                onClick = {
                    when {
                        step == STEP_DONE -> finish()
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
        // 设置与背景都是整份还原，没有「合并」这一说
        val mode = if (merge && rp.part != Backup.Part.SETTINGS && rp.part != Backup.Part.BACKDROP) Backup.Mode.MERGE
        else Backup.Mode.REPLACE
        runCatching {
            Backup.apply(
                part = rp.part,
                text = rp.text,
                mode = mode,
                restoreToken = false,
                settings = AppCore.prefs,
                memory = AppCore.memory,
                customTools = AppCore.customTools,
                stats = AppCore.stats,
                backdropDir = java.io.File(AppCore.app.filesDir, "backdrop")
            )
        }.fold(
            onSuccess = { L("%s：%s").format(partName(rp.part), it) },
            onFailure = { L("%s：失败 —— %s").format(partName(rp.part), it.message ?: "") }
        )
    }.joinToString("\n")
}

/* ------------------------------------------------------------------ 3 条款 */

/**
 * 条款页：正文整块可滚动，滚到底才允许勾选，勾了底栏才会亮。
 *
 * 三件事都必要：
 *  - 滚到底才解锁 → 至少真的把内容划过去了一趟；
 *  - 单独勾选 → 不是一个「下一步」按钮顺手带过去的默认同意；
 *  - 记版本号 → 条款改版后能要求重新确认（见 [Legal.VERSION]）。
 */
@Composable
private fun ColumnScope.TermsStep(
    ctx: Context,
    scroll: ScrollState,
    atEnd: Boolean,
    agreed: Boolean,
    onAgree: (Boolean) -> Unit
) {
    LegalScrollCard(
        ctx = ctx,
        scroll = scroll,
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 20.dp)
    )
    // 【高度必须钉死】未读是一行字、读完后是两行字。
    // 文案一变行数就变 → 卡片高度变 → 可滚动区间 maxValue 变 →
    // 刚到底又变回「没到底」→ 文案来回切，界面就一直在闪。
    // 所以这里按两行预留高度，文案怎么变都不影响布局。
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TERMS_AGREE_ROW_HEIGHT)
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = atEnd) { onAgree(!agreed) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = agreed,
            onCheckedChange = if (atEnd) ({ onAgree(it) }) else null,
            enabled = atEnd
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (atEnd) L("我已阅读并同意《用户协议》与《免责声明》") else L("请滚动阅读到最后"),
            color = if (atEnd) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            maxLines = 2
        )
    }
}

/* ------------------------------------------------------------------ 通用零件 */

@Composable
private fun StepHeader(step: Int) {
    val title = when (step) {
        STEP_WELCOME -> L("欢迎使用 MCP 文件盒")
        STEP_LANG -> L("选择语言")
        STEP_TERMS -> L("阅读并同意条款")
        STEP_PERM -> L("授予权限")
        STEP_RESTORE -> L("恢复备份")
        else -> L("准备就绪")
    }
    val subtitle = when (step) {
        STEP_WELCOME -> L("把手机变成 AI 能读写的文件服务器")
        STEP_LANG -> L("之后可以在「设置 → 外观与语言」里改")
        STEP_TERMS -> L("以下条款是本应用的使用约定")
        STEP_PERM -> L("授权后 AI 才能正常读写文件")
        STEP_RESTORE -> L("有备份的话可以现在恢复")
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
 * 权限页：上面两行是提权（Shizuku / Root，点「申请」触发），下面四行是系统权限。
 *
 * 本页自己**不主动授权**：只显示状态 + 提供入口，每一项都由用户自己点。
 * （早先做过「拿到 root / Shizuku 就把下面几项开掉」，那时候用的是 `pm grant` 运行时权限，
 * 给运行中的自己改权限会让系统把进程重启，引导页面看着会自己动，得不偿失。）
 *
 * 现在「文件访问 / 悬浮窗 / 忽略电池优化」改用 appops + 电池白名单，不重启进程，
 * 所以它们由设置里的「进入应用自动补齐权限」在 App 启动时静默补上；
 * 本页只负责把**真实状态**显示出来，通知权限（运行时权限）照旧让用户自己点。
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

    // 这里**不做自动授权**：之前试过用 root / Shizuku 静默把下面几项直接开掉，
    // 但给运行中的自己改权限会把进程搞重启，页面看着会自己动。
    // 现在一律让用户自己点，App 只负责把状态显示准。

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

/* ------------------------------------------------------------------ 4 恢复备份 */

/** 从备份文件里读出来的一块内容。 */
internal class RestorePart(val part: Backup.Part, val text: String)

private fun partName(part: Backup.Part): String = when (part) {
    Backup.Part.MEMORY -> L("记忆库")
    Backup.Part.SETTINGS -> L("设置")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
    Backup.Part.STATS -> L("统计")
    Backup.Part.BACKDROP -> L("自定义背景")
}

private fun partDesc(part: Backup.Part, text: String): String = when (part) {
    Backup.Part.MEMORY -> L("实体与关系")
    Backup.Part.SETTINGS -> if (Backup.settingsHasToken(text)) L("含访问令牌") else L("不含访问令牌")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
    Backup.Part.STATS -> L("请求次数与运行时长")
    Backup.Part.BACKDROP -> L("背景选项与背景文件")
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

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.saveable.rememberSaveable
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
}

/** 引导一共几步。 */
private const val STEPS = 5

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
 *  - 中间：这一步要做的事（自己滚动）；
 *  - 底部：左「跳过」右「下一步」；这一步要求的东西全齐了「下一步」才点得动，
 *    没齐就只能跳过（跳过只跳这一步，不是整段引导退出）。最后一步只有「开始使用」。
 */
@Composable
fun OnboardingScreen(
    onLangChanged: () -> Unit = {},
    onThemeChanged: () -> Unit = {},
    requestPermission: (PermNeed) -> Unit = {},
    onFinish: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(0) }
    // 语言一改就整块重建，否则界面上的 L(...) 还是旧语言
    var langRev by remember { mutableStateOf(0) }
    // 用来重读权限 / 设备状态（轮询 + 操作后手动 +1）
    var rev by remember { mutableStateOf(0) }
    // 第 4 步恢复过没有。放 key(langRev) 外面 —— 恢复设置可能带着语言一起换，
    // 重建之后这一步仍然得算「已完成」
    var restored by remember { mutableStateOf(false) }
    // 恢复设置可能把语言也一起换掉：真发生时等离开这一页再整块重建，
    // 否则恢复结果那几行字会当场被刷掉
    var langDirty by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step != 3 && langDirty) {
            langDirty = false
            langRev++
        }
    }

    fun finish() {
        AppCore.prefs.onboardDone = true
        OnboardingState.visible = false
        onFinish()
    }

    // 系统返回 = 回上一步；第一步没得退（免得一进来就退出整个引导）
    BackHandler { if (step > 0) step-- }

    // 第 3 步的权限状态靠系统设置改，回到 App 要能立刻看见，所以轻量轮询
    LaunchedEffect(Unit) {
        while (true) {
            rev++
            delay(1000)
        }
    }

    val permsAllOk = remember(rev) { allPermissionsReady() }

    // 这一步算不算「全部完成」：完成才让点下一步
    val stepDone = when (step) {
        2 -> permsAllOk
        3 -> restored
        else -> true
    }

    key(langRev) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            StepHeader(step)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 10.dp)
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> LanguageStep(ctx) {
                        langRev++
                        onLangChanged()
                    }
                    2 -> PermissionStep(ctx, rev, requestPermission) { rev++ }
                    3 -> RestoreStep(
                        ctx = ctx,
                        onRestored = {
                            restored = true
                            rev++
                        },
                        onThemeChanged = onThemeChanged,
                        onLangChanged = {
                            langDirty = true
                            onLangChanged()
                        }
                    )
                    else -> DoneStep(rev, restored, permsAllOk)
                }
            }

            BottomBar(
                step = step,
                nextEnabled = stepDone,
                onSkip = { if (step < STEPS - 1) step++ },
                onNext = { if (step < STEPS - 1) step++ },
                onFinish = { finish() },
                onBack = { if (step > 0) step-- }
            )
        }
    }
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
        0 -> L("把手机变成一个 AI 能读写的文件服务器，全程本地运行")
        1 -> L("随时可以在「设置 → 外观与语言」里再改")
        2 -> L("有几项必须授权，AI 才真的能用起来")
        3 -> L("以前导出过备份的话，可以现在恢复回来")
        else -> L("下面这些随时还能再改")
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
    step: Int,
    nextEnabled: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step < STEPS - 1) {
                PillButton(L("跳过"), Modifier.weight(1f), outlined = true, onClick = onSkip)
                PillButton(L("下一步"), Modifier.weight(1f), enabled = nextEnabled, onClick = onNext)
            } else {
                PillButton(L("开始使用"), Modifier.weight(1f), onClick = onFinish)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(STEPS) { i ->
                val w by animateDpAsState(
                    targetValue = if (i == step) 18.dp else 6.dp,
                    animationSpec = tween(200),
                    label = "dot"
                )
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(
                            if (i == step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                )
            }
        }
        if (step < STEPS - 1) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (step == 0) L("第一步，没什么要弄的")
                else L("「跳过」只跳过这一步，后面还会继续"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
            BulletRow(L("AI 直接读写手机文件：列目录、看内容、写入、删除"))
            BulletRow(L("支持 MCP 协议：Cherry Studio、Claude 等客户端填个地址就能连"))
            BulletRow(L("服务器只跑在本机，令牌关掉就只有你自己能用"))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            L("这个引导一共 5 步，走到最后就可以开始用了。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/* ------------------------------------------------------------------ 2 语言 */

@Composable
private fun LanguageStep(ctx: Context, onPicked: () -> Unit) {
    var sel by remember { mutableStateOf(AppCore.prefs.appLang) }
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
                        note = L("已导入语言包 %s（%s 条译文）").format(id, count)
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
        sel = id
        onPicked()   // 让外层整块重建，语言立刻生效
    }

    val choices = remember(listRev, sel) { Lang.languageChoices() }

    CardGroup(
        rows = choices.map { (id, name) ->
            RowSpec(
                title = name,
                subtitle = if (id == "system") L("系统不是中文就用英文") else null,
                onClick = { pick(id) },
                trailing = {
                    if (sel == id) {
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
 * 只做**静默能搞定**的：能授就授，授不了（比如设备没 root 也没 Shizuku）就什么都不做，
 * 让用户自己去点对应的那一行。真正的授权动作在 [autoGrant]。
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

    // 拿到了特权后端就尝试自动打开下面那几项
    LaunchedEffect(privileged?.id) {
        val id = privileged?.id ?: return@LaunchedEffect
        if (grantedFor == id) return@LaunchedEffect
        grantedFor = id
        val done = autoGrantAsync(ctx)
        note = if (done.isEmpty()) {
            L("%s 已就绪，下面几项自己点一下就行").format(id)
        } else {
            L("已用 %s 自动打开：%s").format(id, done.joinToString("、"))
        }
        toast(ctx, note ?: "")
        onRev()
    }

    val h = AppCore.host
    val storageOk = remember(rev) { h.hasAllFilesAccess() }
    val overlayOk = remember(rev) { h.canDrawOverlays() }
    val notifyOk = remember(rev) { h.hasNotificationPermission() }
    val batteryOk = remember(rev) { h.isIgnoringBatteryOptimizations() }

    GroupLabel(L("提权"))
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
                subtitle = if (rootAvailable) L("已检测到 su，点一下触发授权（Magisk 会弹窗）")
                else L("没检测到 su：设备没 root 就跳过这一项"),
                subtitleMaxLines = 2,
                icon = Icons.Filled.Warning,
                onClick = if (rootAvailable) ({
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
                }) else null,
                trailing = {
                    PillButton(L("申请"), outlined = true, compact = true, enabled = rootAvailable) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    ShellRunner().run(suLauncher!!, "id -u", timeoutMs = 120_000)
                                }
                            }
                            onRev()
                        }
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

    GroupLabel(L("必需"))
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

    Spacer(Modifier.height(10.dp))
    Text(
        L("全是系统权限，App 自己拿不到。没 root / Shizuku 就只能一个个点过来。"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 24.dp)
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

/** 现在能用的特权后端：root 优先，其次 Shizuku；都没有就 null。 */
private fun privilegedLauncher(): CommandLauncher? =
    ShellBackends.available().firstOrNull { it.id == "root" || it.id == "shizuku" }

/**
 * 用特权后端把能静默授予的权限直接打开。
 *
 * 只有这几条命令是 shell 身份能改的：通知、悬浮窗、全部文件访问、电池白名单。
 * 返回真正成功的那些项（用来提示用户）。授不了的不报错，界面上那行还是「未授权」，
 * 用户自己点一下走系统流程即可。
 */
private fun autoGrant(ctx: Context): List<String> = run {
    val launcher = privilegedLauncher() ?: return emptyList()
    val pkg = ctx.packageName
    val tasks = listOf(
        L("通知权限") to "pm grant $pkg android.permission.POST_NOTIFICATIONS",
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
private class RestorePart(val part: Backup.Part, val text: String)

private fun partName(part: Backup.Part): String = when (part) {
    Backup.Part.MEMORY -> L("记忆库")
    Backup.Part.SETTINGS -> L("设置")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
}

private fun partDesc(part: Backup.Part, text: String): String = when (part) {
    Backup.Part.MEMORY -> L("AI 的长期记忆：实体与关系")
    Backup.Part.SETTINGS ->
        (if (Backup.settingsHasToken(text)) L("含访问令牌") else L("不含访问令牌")) +
            " · " + L("端口、权限、外观等设置")
    Backup.Part.CUSTOM_TOOLS -> L("自己造的那些工具")
}

@Composable
private fun RestoreStep(
    ctx: Context,
    onRestored: () -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<RestorePart>>(emptyList()) }
    var merge by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
                parts = loaded
                result = null
            }
        }
    }

    fun doRestore() {
        if (parts.isEmpty() || busy) return
        busy = true
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
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
                        onFailure = {
                            L("%s：失败 —— %s").format(partName(rp.part), it.message ?: "")
                        }
                    )
                }
            }
            AppCore.config.reload()
            AppCore.permissions.load()
            AppCore.customTools.load()
            AppCore.log.enabled = AppCore.config.logEnabled
            result = lines.joinToString("\n")
            busy = false
            onRestored()
            onThemeChanged()
            onLangChanged()
            toast(ctx, L("恢复完成"))
        }
    }

    Column(Modifier.padding(horizontal = 14.dp)) {
        CardBox {
            Text(
                L("选一个备份包（zip 或单独的 json 都认），看清楚了再恢复。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                L("选择备份文件"),
                Modifier.fillMaxWidth(),
                outlined = true
            ) { picker.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) }
        }

        if (parts.isNotEmpty()) {
            GroupLabel(L("这个备份里有"))
            CardGroup(
                rows = parts.map { rp ->
                    RowSpec(
                        title = partName(rp.part),
                        subtitle = partDesc(rp.part, rp.text),
                        icon = Icons.Filled.Done
                    )
                }
            )

            GroupLabel(L("怎么恢复"))
            CardGroup(
                rows = listOf(
                    switchSpec(
                        title = L("合并（不删现有的）"),
                        subtitle = if (merge) L("备份里没有的保持原样，有的就覆盖过去")
                        else L("按备份整份替换，现在的内容会被清掉"),
                        subtitleMaxLines = 2,
                        icon = Icons.Filled.Warning,
                        checked = merge
                    ) { merge = it }
                )
            )

            Spacer(Modifier.height(14.dp))
            PillButton(
                L("开始恢复"),
                Modifier.fillMaxWidth(),
                enabled = !busy
            ) { doRestore() }
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

        Spacer(Modifier.height(10.dp))
        Text(
            L("没备份过就不管这一步，直接跳过。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/* ------------------------------------------------------------------ 5 结束 */

@Composable
private fun DoneStep(rev: Int, restored: Boolean, permsAllOk: Boolean) {
    val langName = remember(rev) { Lang.packDisplayName(AppCore.prefs.appLang) }

    Column(Modifier.padding(horizontal = 14.dp)) {
        CardBox {
            Text(
                L("都弄好了，点下面「开始使用」进去。"),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(12.dp))
            InfoLine(L("界面语言"), langName)
            InfoLine(
                L("系统权限"),
                if (permsAllOk) L("4 项都就绪") else L("还没全授权，进去以后在首页可以继续点")
            )
            InfoLine(
                L("备份"),
                if (restored) L("已经恢复过一份") else L("这次没恢复")
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            L("首页那个开关一打开，AI 就能连上来了。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
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

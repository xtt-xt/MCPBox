// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.DefaultRootsHolder
import com.xtt.mcpbox.core.ApprovalPresentation
import com.xtt.mcpbox.core.PermAction
import com.xtt.mcpbox.core.PermKey
import com.xtt.mcpbox.core.PermPreset
import com.xtt.mcpbox.core.Rule

/**
 * 「权限」tab = **安全中心**，一个页面栈（首页 + 子页）。
 *
 * 为什么改成入口页：以前这张页面把权限开关、工具包、安全选项、目录、私有目录全铺在一起，
 * 而审批在「设置 → 安全与审批」、命令规则和路径规则在「设置 → 终端与命令」、SSH 又单开一页。
 * 想调「AI 能不能跑命令」要跨三个页面，加一个功能就必然更乱。
 * 现在按「问题」分组：能做什么 / 碰哪些文件 / 怎么跑命令 / 怎么问 / 谁连进来。
 *
 * 页面 key：
 * ```
 * ""                 首页（预设 + 5 个入口 + 全局限制）
 * perm               工具权限（9 个开关，按 文件 / 系统 / 网络 分组）
 * files              文件范围（目录 / 路径规则 / 私有目录）
 * files/rules        路径规则列表
 * shell              命令与执行（身份 / 规则 / 超时）
 * shell/rules        命令规则列表
 * shell/rules/<id>   命令规则编辑（new = 新建）
 * approval           审批（方式 / 时限）
 * access             连接与访问（SSH / 令牌 / 文件通道）
 * access/ssh         SSH 服务
 * pack / pack:<id>   工具包编辑
 * ```
 *
 * 子页走的是和「设置」**完全一样**的路子：页面挂在 tab 内容里（不另起 Scaffold），
 * 底栏是同一个 bottomBar 会滑动收起；返回键由这里统一接；滚动位置按页面 key 保留。
 */
@Composable
fun PermissionScreen(
    ctx: Context,
    revision: Int,
    scrollTopTick: Int = 0,
    /** 当前子页：空串 = 权限首页。 */
    page: String = "",
    onPage: (String) -> Unit = {},
    onChanged: () -> Unit
) {
    val back = { onPage(permParent(page)) }

    // 每页的滚动位置跟着页面 key 存下来，回来时不跳回顶部
    val pageStates = rememberSaveableStateHolder()

    // 首页和每个一级子页的滚动都提到这一层：预览层与真页面共用同一份，
    // 否则预见式返回拖动时露出来的那一页永远停在最顶部。
    val homeScroll = rememberScrollState()
    val permScroll = rememberScrollState()
    val filesScroll = rememberScrollState()
    val shellScroll = rememberScrollState()
    val approvalScroll = rememberScrollState()
    val accessScroll = rememberScrollState()

    // 子页里按系统返回 → 回上一级（首页不接，交给外面/系统）。
    PredictiveBackBox(
        onBack = back,
        handleBack = page.isNotEmpty(),
        follow = page.isNotEmpty() && AppCore.prefs.predictiveBack,
        behind = {
            // 拖动时下面露出来的必须是**真正的上一级**，而且停在它自己的滚动位置。
            // 预览层只负责显示，回调全给空 lambda。
            when (permParent(page)) {
                "perm" -> ToolPermPage(
                    ctx = ctx, revision = revision, onChanged = {}, onOpenPack = {},
                    scroll = permScroll, onBack = {}
                )
                "files" -> FileScopePage(
                    ctx = ctx, revision = revision, onChanged = {}, onOpenRules = {},
                    scroll = filesScroll, onBack = {}
                )
                "shell" -> ShellPolicyPage(
                    revision = revision, onChanged = {}, onOpenRules = {}, onJumpPerm = {},
                    scroll = shellScroll, onBack = {}
                )
                "approval" -> ApprovalPolicyPage(
                    revision = revision, onChanged = {}, scroll = approvalScroll, onBack = {}
                )
                "access" -> AccessPage(
                    ctx = ctx, revision = revision, onChanged = {}, onOpenSsh = {},
                    scroll = accessScroll, onBack = {}
                )
                // 规则编辑页底下露出来的是规则列表本身
                "shell/rules" -> RulesSettingsPage(
                    kind = RuleKind.COMMAND, revision = revision, onChanged = {}, onBack = {}
                )
                "files/rules" -> RulesSettingsPage(
                    kind = RuleKind.PATH, revision = revision, onChanged = {}, onBack = {}
                )
                "access/ssh" -> SshSettingsPage(
                    ctx = ctx, revision = revision, onChanged = {}, onBack = {}
                )
                else -> PermissionHomePage(
                    scroll = homeScroll,
                    ctx = ctx,
                    revision = revision,
                    scrollTopTick = scrollTopTick,
                    onPage = onPage,
                    onChanged = onChanged
                )
            }
        }
    ) {
        // 跟手提交时旧页已经偏了多少（普通返回是 0）—— 交给共用转场去用
        val commitDrag = LocalPredictiveCommitDrag.current
        // 视频背景时页面是透明的，两页重叠会互相透出文字 → 这种模式下走 noOverlap
        val noOverlap = rememberTransparentPages()
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                pageSlide(
                    // 方向看**层级**：首页 0 / 一级子页 1 / 规则页 2 / 规则编辑 3
                    entering = pageForward(targetState, initialState) { permDepth(it) },
                    commitDrag = commitDrag,
                    noOverlap = noOverlap
                )
            },
            label = "permPage"
        ) { current ->
            // 每页自带底色：转场时新旧两页重叠，各自有底才不会互相透出文字。
            PageSurface(sub = current.isNotEmpty()) {
                pageStates.SaveableStateProvider(current.ifEmpty { "root" }) {
                    // 「编辑某条命令规则」的 key 是 `shell/rules/<id>`：整类归一到同一个分支
                    val isRuleEdit = current.startsWith("shell/rules/")
                    when (if (isRuleEdit) "shell/rule-edit" else current) {
                        "" -> PermissionHomePage(
                            scroll = homeScroll,
                            ctx = ctx,
                            revision = revision,
                            scrollTopTick = scrollTopTick,
                            onPage = onPage,
                            onChanged = onChanged
                        )
                        "perm" -> ToolPermPage(
                            ctx = ctx,
                            revision = revision,
                            onChanged = onChanged,
                            onOpenPack = { id -> onPage(if (id == null) "pack" else "pack:$id") },
                            scroll = permScroll,
                            onBack = back
                        )
                        "files" -> FileScopePage(
                            ctx = ctx,
                            revision = revision,
                            onChanged = onChanged,
                            onOpenRules = { onPage("files/rules") },
                            scroll = filesScroll,
                            onBack = back
                        )
                        "files/rules" -> RulesSettingsPage(
                            kind = RuleKind.PATH,
                            revision = revision,
                            onChanged = onChanged,
                            onBack = back
                        )
                        "shell" -> ShellPolicyPage(
                            revision = revision,
                            onChanged = onChanged,
                            onOpenRules = { onPage("shell/rules") },
                            onJumpPerm = { onPage("perm") },
                            scroll = shellScroll,
                            onBack = back
                        )
                        "shell/rules" -> RulesSettingsPage(
                            kind = RuleKind.COMMAND,
                            revision = revision,
                            onChanged = onChanged,
                            onBack = back,
                            onOpenNew = { onPage("shell/rules/new") },
                            onOpenEdit = { id -> onPage("shell/rules/$id") }
                        )
                        "shell/rule-edit" -> CommandRuleEditPage(
                            ctx = ctx,
                            ruleId = current.removePrefix("shell/rules/").takeIf { it != "new" },
                            revision = revision,
                            onChanged = onChanged,
                            onBack = back
                        )
                        "approval" -> ApprovalPolicyPage(
                            revision = revision,
                            onChanged = onChanged,
                            scroll = approvalScroll,
                            onBack = back
                        )
                        "access" -> AccessPage(
                            ctx = ctx,
                            revision = revision,
                            onChanged = onChanged,
                            onOpenSsh = { onPage("access/ssh") },
                            scroll = accessScroll,
                            onBack = back
                        )
                        "access/ssh" -> SshSettingsPage(
                            ctx = ctx,
                            revision = revision,
                            onChanged = onChanged,
                            onBack = back
                        )
                        else -> PackEditPage(
                            ctx = ctx,
                            packId = if (current == "pack") null else current.removePrefix("pack:"),
                            revision = revision,
                            onChanged = onChanged,
                            onBack = back
                        )
                    }
                }
            }
        }
    }
}

/** 层级 = 非空段数：首页 0，一级子页 1，规则列表 2，规则编辑 3。 */
private fun permDepth(page: String): Int = page.split('/').count { it.isNotEmpty() }

/**
 * 上一级页面。
 *
 * 三级（`shell/rules/<id>`）退到 `shell/rules`，二级（`shell/rules`）退到 `shell`，
 * 一级退到首页。别写成 `substringBeforeLast('/')` 一刀切：`shell/rules` 的上一级是
 * `shell` 而不是不存在的 `shell/rules` 前半截。
 */
private fun permParent(page: String): String = when {
    permDepth(page) >= 3 -> page.substringBeforeLast('/')
    page.contains('/') -> page.substringBefore('/')
    else -> ""
}

/**
 * 权限首页：预设会话 + 五个入口 + 全局限制。
 *
 * 入口化是为了「一件事只在一个地方」——点进去那一页里的东西是完整的，不用再去别的 tab 找。
 * 预设留在首页：它是所有开关的总控，比逐个键去调快得多。
 */
@Composable
private fun PermissionHomePage(
    scroll: ScrollState,
    ctx: Context,
    revision: Int,
    scrollTopTick: Int,
    onPage: (String) -> Unit,
    onChanged: () -> Unit
) {
    // 预设那一行的下拉展开了没
    var presetOpen by remember { mutableStateOf(false) }

    // 双击底栏「权限」：回到顶部
    NavReselectEffect(scrollTopTick) { scroll.animateScrollTo(0) }
    PageScaffold(
        scroll = scroll,
        title = L("权限"),
        bottomPadding = 20.dp
    ) {
        val preset = remember(revision) { AppCore.permissions.preset() }
        val switches = remember(revision) { AppCore.permissions.snapshot() }

        // ---------------------------------------------------------- 预设会话
        // 选前三个会把下面的开关统一成同一个值并**锁住**，想单独调必须先切到「自定义」。
        CardGroup(
            listOf(
                RowSpec(
                    title = L("预设会话"),
                    subtitle = if (preset.locked)
                        L("下面的权限已按这个预设统一并锁定；选「自定义」会恢复你之前调好的那份。")
                    else L("选固定预设会记住你现在的设置，切回「自定义」时自动恢复。"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    // 点整行也能展开右边那个下拉（保持和其它胶囊一致）
                    onClick = { presetOpen = true },
                    trailing = {
                        PillDropdown(
                            value = L(preset.label),
                            options = PermPreset.entries.map { L(it.label) },
                            expanded = presetOpen,
                            onExpandedChange = { presetOpen = it }
                        ) { index ->
                            presetOpen = false
                            AppCore.permissions.setPreset(PermPreset.entries[index])
                            onChanged()
                        }
                    }
                )
            )
        )

        // ---------------------------------------------------------- 五个入口
        GroupLabel(L("按问题找"))
        CardGroup(
            listOf(
                entrySpec(
                    L("工具权限"),
                    L("9 个全局开关：%s").format(summaryOf(switches)),
                    Icons.Filled.Lock
                ) { onPage("perm") },
                entrySpec(
                    L("文件范围"),
                    L("%s 个目录 · 私有目录 %s · 路径规则 %s 条")
                        .format(
                            AppCore.config.roots.size,
                            privateLabel(AppCore.config.privateAccess),
                            remember(revision) { AppCore.permissions.pathRules().size }
                        ),
                    Icons.Filled.Place
                ) { onPage("files") },
                entrySpec(
                    L("命令与执行"),
                    L("%s · 命令规则 %s 条 · 超时 %s")
                        .format(
                            identityLabel(AppCore.config.shellPreference),
                            remember(revision) { AppCore.permissions.commandRules().size },
                            timeoutLabel(AppCore.config.shellTimeoutMs)
                        ),
                    Icons.Filled.Build
                ) { onPage("shell") },
                entrySpec(
                    L("审批"),
                    L("%s · %s")
                        .format(
                            methodLabel(AppCore.prefs.approvalPresentation),
                            timeoutLabel(AppCore.config.approvalTimeoutMs)
                        ),
                    Icons.Filled.Notifications
                ) { onPage("approval") },
                entrySpec(
                    L("连接与访问"),
                    L("SSH %s · 访问令牌 %s")
                        .format(
                            if (AppCore.config.sshEnabled) L("已开启") else L("已关闭"),
                            if (AppCore.config.tokenEnabled) L("已启用") else L("未启用")
                        ),
                    Icons.Filled.Person
                ) { onPage("access") }
            )
        )

        // ---------------------------------------------------------- 全局限制
        // 只有三个开关，而且都是「一刀切」的全局闸门，不值得再开一层子页。
        GroupLabel(L("全局限制"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("只读模式"),
                    subtitle = L("打开后所有写入/删除都会被直接拒绝，AI 和 SSH 都算"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.readOnly
                ) {
                    AppCore.config.readOnly = it
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = L("不限制目录"),
                    subtitle = L("允许访问整机（危险；系统目录仍受保护）"),
                    icon = Icons.Filled.Warning,
                    checked = AppCore.config.fullAccess
                ) {
                    AppCore.config.fullAccess = it
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = L("删除进回收站"),
                    subtitle = L("删除的文件先放到 .MCPBox/trash，随时能还原"),
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.config.trashEnabled
                ) {
                    AppCore.config.trashEnabled = it
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------ 摘要文案 */

/** 「写入 询问 · 删除 询问 · 命令 询问」里取最需要知道的那几个：一共有几个「询问」。 */
private fun summaryOf(switches: Map<String, PermAction>): String {
    val ask = PermKey.entries.count { (switches[it.id] ?: it.default) == PermAction.ASK }
    val deny = PermKey.entries.count { (switches[it.id] ?: it.default) == PermAction.DENY }
    return L("%s 个询问、%s 个拒绝").format(ask, deny)
}

private fun privateLabel(mode: String): String = when (mode) {
    "read" -> L("只读")
    "full" -> L("可读写")
    else -> L("已禁止")
}

private fun identityLabel(pref: String): String = L(identityText(pref))

private fun timeoutLabel(ms: Long): String =
    if (ms <= 0L) L("不限时") else L("%s 秒").format(ms / 1000)

private fun methodLabel(id: String?): String =
    if (ApprovalPresentation.of(id) == ApprovalPresentation.NOTIFY) L("通知栏") else L("悬浮窗")

/* ------------------------------------------------------------ 图标与颜色 */

internal fun permIcon(key: PermKey): ImageVector = when (key) {
    PermKey.READ -> Icons.Filled.List
    PermKey.WRITE -> Icons.Filled.Create
    PermKey.DELETE -> Icons.Filled.Delete
    PermKey.SHELL -> Icons.Filled.Build
    PermKey.UI -> Icons.Filled.PlayArrow
    PermKey.BROWSER -> Icons.Filled.Search
    PermKey.TOOLS -> Icons.Filled.Refresh
    PermKey.SYSTEM -> Icons.Filled.Info
    PermKey.MEMORY -> Icons.Filled.Star
}

fun actionColor(action: PermAction): Color = when (action) {
    PermAction.ALLOW -> Sem.ok
    PermAction.ASK -> Sem.warn
    PermAction.DENY -> Sem.bad
}

/**
 * 「添加目录」弹窗。
 *
 * 以前这个按钮打开的是「添加路径规则」弹窗 —— 改的是规则，不是目录，点完目录列表纹丝不动。
 * 现在它是真的往「允许访问的目录」里加一项（相对路径都基于第一项）。
 */
@Composable
internal fun AddRootDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val suggestions = remember { DefaultRootsHolder.suggestions().take(6) }
    var path by remember { mutableStateOf(suggestions.firstOrNull().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(L("添加目录"), fontSize = 20.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(L("目录路径（绝对路径）")) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(L("常用目录"), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    suggestions.forEach { s ->
                        Text(
                            s,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = MaterialTheme.colorScheme.primary)
                                ) { path = s }
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (path.isNotBlank()) onAdd(path.trim()) }) {
                Text(L("添加"), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
internal fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (String, String, PermAction) -> Unit) {
    var path by remember { mutableStateOf(AppCore.config.primaryRoot()) }
    var permId by remember { mutableStateOf(PermKey.WRITE.id) }
    var action by remember { mutableStateOf(PermAction.ALLOW) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(L("添加路径规则"), fontSize = 20.sp) },
        text = {
            Column {
                Text(L("权限类型"), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermKey.entries.forEach { key ->
                        ChoiceChip(
                            text = L(key.title),
                            active = permId == key.id,
                            color = MaterialTheme.colorScheme.primary
                        ) { permId = key.id }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(L("目录或文件（前缀匹配）")) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text(L("动作"), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermAction.entries.forEach { a ->
                        ChoiceChip(L(a.label), action == a, actionColor(a)) { action = a }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(L("常用目录"), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                DefaultRootsHolder.suggestions().take(5).forEach { s ->
                    Text(
                        s,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = MaterialTheme.colorScheme.primary)
                            ) { path = s }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(permId, path.trim(), action) }) {
                Text(L("添加"), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun ChoiceChip(text: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.5.sp,
        maxLines = 1,
        softWrap = false,
        color = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (active) color.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(50)
            )
            .border(
                1.dp,
                if (active) color.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(50)
            )
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

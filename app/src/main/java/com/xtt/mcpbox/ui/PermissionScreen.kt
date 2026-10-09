// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import com.xtt.mcpbox.core.PermAction
import com.xtt.mcpbox.core.PermKey
import com.xtt.mcpbox.core.PermPreset
import com.xtt.mcpbox.core.Rule

/**
 * 权限页 = **一个页面栈**（首页 + 子页）。
 *
 * 子页只有「工具包编辑页」一个，但它走的是和「设置」那套**完全一样**的路子：
 * 页面挂在 tab 内容里（不另起 Scaffold），所以底栏是同一个 bottomBar、会**滑动收起**；
 * 返回键由这里**统一接**（子页不用各自 BackHandler，也就不会漏）；
 * 滚动位置按页面 key 保留。
 *
 * 页面 key：空串 = 首页；`pack` / `pack:<id>` = 新建 / 编辑工具包。
 */
@Composable
fun PermissionScreen(
    ctx: Context,
    revision: Int,
    scrollTopTick: Int = 0,
    /** 当前子页：空串 = 权限首页；`pack` / `pack:<id>` = 工具包编辑页。 */
    page: String = "",
    onPage: (String) -> Unit = {},
    onChanged: () -> Unit
) {
    val back = { onPage("") }

    // 每页的滚动位置跟着页面 key 存下来，回来时不跳回顶部
    val pageStates = rememberSaveableStateHolder()

    // 子页里按系统返回 → 回权限首页（首页不接，交给外面/系统）。
    // 开了「预见式返回动画」时手指拖着走、松手才决定；拖动时下面露出来的就是权限首页。
    PredictiveBackBox(
        onBack = back,
        handleBack = page.isNotEmpty(),
        follow = page.isNotEmpty() && AppCore.prefs.predictiveBack,
        behind = {
            PermissionHomePage(
                ctx = ctx,
                revision = revision,
                scrollTopTick = scrollTopTick,
                onOpenPack = { id -> onPage(if (id == null) "pack" else "pack:$id") },
                onChanged = onChanged
            )
        }
    ) {
    // 跟手提交时旧页已经偏了多少（普通返回是 0）—— 交给共用转场去用。
    // 注意必须在**组合作用域**里读（transitionSpec 那个 lambda 不是 @Composable）
    val commitDrag = LocalPredictiveCommitDrag.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            pageSlide(
                // 方向看**层级**，不看「key 是不是空串」（首页 0 / 子页 1）
                entering = pageForward(targetState, initialState) { if (it.isEmpty()) 0 else 1 },
                commitDrag = commitDrag
            )
        },
        label = "permPage"
    ) { current ->
        pageStates.SaveableStateProvider(current.ifEmpty { "root" }) {
            if (current.isEmpty()) {
                PermissionHomePage(
                    ctx = ctx,
                    revision = revision,
                    scrollTopTick = scrollTopTick,
                    onOpenPack = { id -> onPage(if (id == null) "pack" else "pack:$id") },
                    onChanged = onChanged
                )
            } else {
                PackEditPage(
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

/**
 * 权限首页：预设会话、权限开关、工具包、安全选项、目录与文件通道。
 *
 * 这一页里**只管自己**：滚动位置、各种弹窗状态都在这里，切到子页再回来位置不丢。
 */
@Composable
private fun PermissionHomePage(
    ctx: Context,
    revision: Int,
    scrollTopTick: Int,
    onOpenPack: (String?) -> Unit,
    onChanged: () -> Unit
) {
    // 「添加目录」弹窗
    var showAddRoot by remember { mutableStateOf(false) }
    // 预设那一行的下拉展开了没
    var presetOpen by remember { mutableStateOf(false) }
    // 权限开关里哪一行的下拉展开了（点整行 = 点它右边那个胶囊）
    var openPerm by remember { mutableStateOf<PermKey?>(null) }

    val scroll = rememberScrollState()
    // 双击底栏「权限」：回到顶部
    NavReselectEffect(scrollTopTick) { scroll.animateScrollTo(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 20.dp)
    ) {
        val switches = remember(revision) { AppCore.permissions.snapshot() }

        PageHeader(
            title = L("权限"),
        )

        // ---------------------------------------------------------- 预设会话
        // 放在最上面：一键决定「AI 能自己动手到什么程度」，比逐个键去调快得多。
        // 选前三个会把下面的开关统一成同一个值并**锁住**，想单独调必须先切到「自定义」。
        // 注意这里改的是**全局**权限开关，对所有会话都生效。
        val preset = remember(revision) { AppCore.permissions.preset() }
        CardGroup(
            listOf(
                RowSpec(
                    title = L("预设会话"),
                    subtitle = if (preset.locked)
                        L("下面的权限已按这个预设统一并锁定；选「自定义」会恢复你之前调好的那份。")
                    else L("选固定预设会记住你现在的设置，切回「自定义」时自动恢复。"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    // 点整行也能展开右边那个下拉（保持和权限开关一致）
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

        // ---------------------------------------------------------- 权限开关
        // 单独给一组标签：预设会话是「总控」，这里是「逐个开关」，视觉上分开才看得出层次。
        GroupLabel(L("权限开关"))
        CardGroup(
            rows = PermKey.entries.map { key ->
                val current = switches[key.id] ?: key.default
                RowSpec(
                    title = L(key.title),
                    subtitle = L(key.desc),
                    subtitleMaxLines = 1,
                    icon = permIcon(key),
                    // 预设锁定时整行也不可点：点了会被随后的下拉值弄得前后不一致。
                    // 没锁时点整行 = 展开右侧那个胶囊下拉（不再弹居中的大窗）。
                    onClick = if (preset.locked) null else ({ openPerm = key }),
                    trailing = {
                        if (preset.locked) {
                            // 锁定态只报值、不给改（要改先去上面切「自定义」）
                            OutlineTag(L(current.label), actionColor(current))
                        } else {
                            PillDropdown(
                                value = L(current.label),
                                options = PermAction.entries.map { L(it.label) },
                                expanded = openPerm == key,
                                // 打开和关闭都要接：早先只处理了「关闭」，
                                // 胶囊自己那次点击被吞掉（setOpen(true) → 回调里啥也不做），
                                // 结果只有点整行才展开、点胶囊没反应。
                                onExpandedChange = { open -> openPerm = if (open) key else null }
                            ) { index ->
                                openPerm = null
                                AppCore.permissions.setSwitch(key, PermAction.entries[index])
                                onChanged()
                            }
                        }
                    }
                )
            }
        )

        // ---------------------------------------------------------- 工具包
        // 「会话状态自动重置」也在这块里（工具包设置的最下面）。
        PacksSection(ctx = ctx, revision = revision, onChanged = onChanged, onOpenPack = onOpenPack)

        // ---------------------------------------------------------- 安全选项
        GroupLabel(L("安全选项"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("只读模式"),
                    subtitle = L("打开后所有写入/删除都会被直接拒绝"),
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.readOnly
                ) {
                    AppCore.config.readOnly = it
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = L("删除进回收站"),
                    subtitle = L("AI 删除的文件先放到 .MCPBox/trash，随时能还原"),
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.config.trashEnabled
                ) {
                    AppCore.config.trashEnabled = it
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
                }
            )
        )

        // ---------------------------------------------------- 应用私有目录
        GroupLabel(L("应用私有目录"))
        val privateMode = AppCore.config.privateAccess
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("私有目录访问"),
                    subtitle = when (privateMode) {
                        "read" -> L("只能读 /data/data 里的内容")
                        "full" -> L("读、写、删都可以（危险）")
                        else -> L("已禁止，AI 看不到应用私有数据")
                    },
                    icon = Icons.Filled.Lock,
                    options = listOf(L("禁止"), L("只读"), L("可读写")),
                    selectedIndex = listOf("off", "read", "full").indexOf(privateMode).coerceAtLeast(0)
                ) { index ->
                    AppCore.config.privateAccess = listOf("off", "read", "full")[index]
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("怎么生效"),
                    subtitle = L("应用自己读不了别家私有目录，所以开启后会通过 ") +
                        AppCore.server.bridge.privilegedLabel +
                        L(" 转发；想只放开某一个应用，可以在下面加路径规则（例：/data/data/包名）。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )

        // ---------------------------------------------------------- 目录
        GroupLabel(L("允许访问的目录"))
        CardGroup(
            rows = buildList {
                AppCore.config.roots.forEachIndexed { index, root ->
                    add(
                        RowSpec(
                            title = root,
                            subtitle = if (index == 0) L("默认目录（相对路径基于它）") else null,
                            icon = Icons.Filled.List,
                            trailing = {
                                RoundIconButton(Icons.Filled.Delete, L("移除"), tint = Sem.bad, size = 40) {
                                    if (AppCore.config.roots.size <= 1) {
                                        toast(ctx, L("至少要保留一个目录"))
                                    } else {
                                        AppCore.config.roots = AppCore.config.roots.filterNot { it == root }
                                        AppCore.saveConfig()
                                        onChanged()
                                    }
                                }
                            }
                        )
                    )
                }
                add(
                    RowSpec(
                        title = L("添加目录"),
                        subtitle = L("让 AI 能访问这个目录（相对路径基于第一个目录）"),
                        subtitleMaxLines = 2,
                        icon = Icons.Filled.Add,
                        onClick = { showAddRoot = true }
                    )
                )
            }
        )

        GroupLabel(L("文件进出通道"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("网页上传 / 下载"),
                    subtitle = L("浏览器打开 http://127.0.0.1:%s/upload 就能往手机传文件；").format(AppCore.config.port) +
                        L("外部程序也能用 POST /upload、GET /download（要带 token）"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Share
                )
            )
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showAddRoot) {
        AddRootDialog(
            onDismiss = { showAddRoot = false },
            onAdd = { dir ->
                val list = AppCore.config.roots.toMutableList()
                if (list.none { it.trim() == dir }) list.add(dir)
                AppCore.config.roots = list
                AppCore.saveConfig()
                showAddRoot = false
                onChanged()
            }
        )
    }
}


private fun permIcon(key: PermKey): ImageVector = when (key) {
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
private fun AddRootDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val suggestions = remember { DefaultRootsHolder.suggestions().take(6) }
    var path by remember { mutableStateOf(suggestions.firstOrNull().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
internal fun AddCommandRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, PermAction) -> Unit
) {
    var command by remember { mutableStateOf("") }
    var match by remember { mutableStateOf(Rule.MATCH_PREFIX) }
    var action by remember { mutableStateOf(PermAction.ALLOW) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(L("添加命令规则"), fontSize = 20.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(L("命令，如 pm 或 ^dd if=")) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text(L("匹配方式"), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(L("前缀"), match == Rule.MATCH_PREFIX, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_PREFIX
                    }
                    ChoiceChip(L("完全匹配"), match == Rule.MATCH_EXACT, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_EXACT
                    }
                    ChoiceChip(L("正则"), match == Rule.MATCH_REGEX, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_REGEX
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(L("动作"), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermAction.entries.forEach { a ->
                        ChoiceChip(L(a.label), action == a, actionColor(a)) { action = a }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(L("例：填「pm」+ 前缀 + 允许 → AI 以后执行 pm 开头的命令就不再问你。"), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (command.isNotBlank()) onAdd(command.trim(), match, action) }) {
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

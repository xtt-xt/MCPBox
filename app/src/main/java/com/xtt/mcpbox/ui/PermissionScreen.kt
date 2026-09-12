// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.xtt.mcpbox.core.Rule

@Composable
fun PermissionScreen(ctx: Context, revision: Int, onChanged: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var showAddCommand by remember { mutableStateOf(false) }
    var pickPerm by remember { mutableStateOf<PermKey?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        val switches = remember(revision) { AppCore.permissions.snapshot() }
        val rules = remember(revision) { AppCore.permissions.pathRules() }
        val cmdRules = remember(revision) { AppCore.permissions.commandRules() }

        PageHeader(
            title = "权限",
            subtitle = "选「询问」时，AI 每次调用都会弹出悬浮窗让你决定"
        )

        // ---------------------------------------------------------- 权限总开关
        CardGroup(
            rows = PermKey.entries.map { key ->
                val current = switches[key.id] ?: key.default
                RowSpec(
                    title = key.title,
                    subtitle = key.desc,
                    subtitleMaxLines = 1,
                    icon = permIcon(key),
                    onClick = { pickPerm = key },
                    trailing = {
                        PillDropdown(
                            value = current.label,
                            options = PermAction.entries.map { it.label }
                        ) { index ->
                            AppCore.permissions.setSwitch(key, PermAction.entries[index])
                            onChanged()
                        }
                    }
                )
            }
        )

        // ---------------------------------------------------------- 快捷操作
        GroupLabel("快捷操作")
        CardGroup(
            listOf(
                RowSpec(
                    title = "全部允许",
                    subtitle = "AI 想做什么都不再询问",
                    icon = Icons.Filled.Check,
                    onClick = {
                        AppCore.permissions.setSwitchForAll(PermAction.ALLOW)
                        onChanged()
                    }
                ),
                RowSpec(
                    title = "全部询问",
                    subtitle = "每个写/删动作都弹窗确认（推荐）",
                    icon = Icons.Filled.Info,
                    onClick = {
                        AppCore.permissions.setSwitchForAll(PermAction.ASK)
                        onChanged()
                    }
                ),
                RowSpec(
                    title = "全部拒绝",
                    subtitle = "彻底锁死，AI 只能看服务器状态",
                    icon = Icons.Filled.Close,
                    onClick = {
                        AppCore.permissions.setSwitchForAll(PermAction.DENY)
                        onChanged()
                    }
                )
            )
        )

        // ---------------------------------------------------------- 路径规则
        GroupLabel("路径规则（${rules.size}）")
        CardGroup(
            rows = buildList {
                if (rules.isEmpty()) {
                    add(
                        RowSpec(
                            title = "给目录单独定规则",
                            subtitle = "例：Download 目录免审批；放密码/密钥的目录直接拒绝。最长匹配优先。",
                            subtitleMaxLines = 3,
                            icon = Icons.Filled.Info
                        )
                    )
                } else {
                    rules.forEach { rule ->
                        add(
                            RowSpec(
                                title = if (rule.perm == "*") "全部权限" else (PermKey.of(rule.perm)?.title ?: rule.perm),
                                subtitle = rule.target.ifBlank { "（未填写路径）" },
                                icon = Icons.Filled.Place,
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TagPill(rule.actionEnum.label, actionColor(rule.actionEnum))
                                        Spacer(Modifier.width(8.dp))
                                        RoundIconButton(Icons.Filled.Delete, "删除规则", tint = Sem.bad, size = 40) {
                                            AppCore.permissions.removeRule(rule.id)
                                            onChanged()
                                        }
                                    }
                                }
                            )
                        )
                    }
                }
                add(
                    RowSpec(
                        title = "添加路径规则",
                        subtitle = "给某个目录单独定允许 / 询问 / 拒绝",
                        icon = Icons.Filled.Add,
                        onClick = { showAdd = true }
                    )
                )
            }
        )

        // ---------------------------------------------------------- 命令规则
        GroupLabel("命令规则（${cmdRules.size}）")
        CardGroup(
            rows = buildList {
                if (cmdRules.isEmpty()) {
                    add(
                        RowSpec(
                            title = "所有命令都要你点头",
                            subtitle = "AI 执行命令时会弹窗；点「记住此命令」就会自动生成一条" +
                                "按命令名前缀匹配的规则，之后同类命令不再询问。",
                            subtitleMaxLines = 3,
                            icon = Icons.Filled.Info
                        )
                    )
                } else {
                    cmdRules.forEach { rule ->
                        add(
                            RowSpec(
                                title = rule.target,
                                subtitle = when (rule.match) {
                                    Rule.MATCH_EXACT -> "完全匹配"
                                    Rule.MATCH_REGEX -> "正则匹配"
                                    else -> "前缀匹配"
                                },
                                icon = Icons.Filled.Build,
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TagPill(rule.actionEnum.label, actionColor(rule.actionEnum))
                                        Spacer(Modifier.width(8.dp))
                                        RoundIconButton(Icons.Filled.Delete, "删除规则", tint = Sem.bad, size = 40) {
                                            AppCore.permissions.removeRule(rule.id)
                                            onChanged()
                                        }
                                    }
                                }
                            )
                        )
                    }
                }
                add(
                    RowSpec(
                        title = "添加命令规则",
                        subtitle = "手动给某条命令定允许 / 询问 / 拒绝",
                        icon = Icons.Filled.Add,
                        onClick = { showAddCommand = true }
                    )
                )
            }
        )

        // ---------------------------------------------------------- 安全选项
        GroupLabel("安全选项")
        CardGroup(
            listOf(
                switchSpec(
                    title = "只读模式",
                    subtitle = "打开后所有写入/删除都会被直接拒绝",
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.readOnly
                ) {
                    AppCore.config.readOnly = it
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = "删除进回收站",
                    subtitle = "AI 删除的文件先放到 .MCPBox/trash，随时能还原",
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.config.trashEnabled
                ) {
                    AppCore.config.trashEnabled = it
                    AppCore.saveConfig()
                    onChanged()
                },
                switchSpec(
                    title = "不限制目录",
                    subtitle = "允许访问整机（危险；系统目录仍受保护）",
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
        GroupLabel("应用私有目录")
        val privateMode = AppCore.config.privateAccess
        CardGroup(
            listOf(
                dropdownSpec(
                    title = "私有目录访问",
                    subtitle = when (privateMode) {
                        "read" -> "只能读 /data/data 里的内容"
                        "full" -> "读、写、删都可以（危险）"
                        else -> "已禁止，AI 看不到应用私有数据"
                    },
                    icon = Icons.Filled.Lock,
                    options = listOf("禁止", "只读", "可读写"),
                    selectedIndex = listOf("off", "read", "full").indexOf(privateMode).coerceAtLeast(0)
                ) { index ->
                    AppCore.config.privateAccess = listOf("off", "read", "full")[index]
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = "怎么生效",
                    subtitle = "应用自己读不了别家私有目录，所以开启后会通过 " +
                        AppCore.server.bridge.privilegedLabel +
                        " 转发；想只放开某一个应用，可以在下面加路径规则（例：/data/data/包名）。",
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )

        // ---------------------------------------------------------- 目录
        GroupLabel("允许访问的目录")
        CardGroup(
            rows = buildList {
                AppCore.config.roots.forEachIndexed { index, root ->
                    add(
                        RowSpec(
                            title = root,
                            subtitle = if (index == 0) "默认目录（相对路径基于它）" else null,
                            icon = Icons.Filled.List,
                            trailing = {
                                RoundIconButton(Icons.Filled.Delete, "移除", tint = Sem.bad, size = 40) {
                                    if (AppCore.config.roots.size <= 1) {
                                        toast(ctx, "至少要保留一个目录")
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
                        title = "添加目录",
                        subtitle = "点「添加路径规则」旁边的 +，也可以在路径规则里直接写",
                        icon = Icons.Filled.Add,
                        onClick = { showAdd = true }
                    )
                )
            }
        )

        GroupLabel("文件进出通道")
        CardGroup(
            listOf(
                RowSpec(
                    title = "网页上传 / 下载",
                    subtitle = "浏览器打开 http://127.0.0.1:${AppCore.config.port}/upload 就能往手机传文件；" +
                        "外部程序也能用 POST /upload、GET /download（要带 token）",
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Share
                )
            )
        )

        Spacer(Modifier.height(24.dp))
    }

    // 整行点开也能选模式
    pickPerm?.let { key ->
        val current = AppCore.permissions.switchOf(key)
        ChoiceDialog(
            title = "「${key.title}」怎么处理",
            options = PermAction.entries.map { it.label },
            selected = PermAction.entries.indexOf(current),
            onDismiss = { pickPerm = null },
            onSelect = { index ->
                AppCore.permissions.setSwitch(key, PermAction.entries[index])
                pickPerm = null
                onChanged()
            }
        )
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { perm, path, action ->
                AppCore.permissions.addRule(perm, path, action)
                showAdd = false
                onChanged()
            }
        )
    }

    if (showAddCommand) {
        AddCommandRuleDialog(
            onDismiss = { showAddCommand = false },
            onAdd = { pattern, match, action ->
                AppCore.permissions.addRule(
                    PermKey.SHELL.id, pattern, action,
                    type = Rule.TYPE_COMMAND, match = match
                )
                showAddCommand = false
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
    PermKey.TOOLS -> Icons.Filled.Refresh
    PermKey.SYSTEM -> Icons.Filled.Info
}

fun actionColor(action: PermAction): Color = when (action) {
    PermAction.ALLOW -> Sem.ok
    PermAction.ASK -> Sem.warn
    PermAction.DENY -> Sem.bad
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (String, String, PermAction) -> Unit) {
    var path by remember { mutableStateOf(AppCore.config.primaryRoot()) }
    var permId by remember { mutableStateOf(PermKey.WRITE.id) }
    var action by remember { mutableStateOf(PermAction.ALLOW) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("添加路径规则", fontSize = 20.sp) },
        text = {
            Column {
                Text("权限类型", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermKey.entries.forEach { key ->
                        ChoiceChip(
                            text = key.title,
                            active = permId == key.id,
                            color = MaterialTheme.colorScheme.primary
                        ) { permId = key.id }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("目录或文件（前缀匹配）") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("动作", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermAction.entries.forEach { a ->
                        ChoiceChip(a.label, action == a, actionColor(a)) { action = a }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("常用目录", fontSize = 13.sp)
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
                            .clickable { path = s }
                            .padding(vertical = 5.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(permId, path.trim(), action) }) {
                Text("添加", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun AddCommandRuleDialog(
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
        title = { Text("添加命令规则", fontSize = 20.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令，如 pm 或 ^dd if=") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("匹配方式", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("前缀", match == Rule.MATCH_PREFIX, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_PREFIX
                    }
                    ChoiceChip("完全匹配", match == Rule.MATCH_EXACT, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_EXACT
                    }
                    ChoiceChip("正则", match == Rule.MATCH_REGEX, MaterialTheme.colorScheme.primary) {
                        match = Rule.MATCH_REGEX
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("动作", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermAction.entries.forEach { a ->
                        ChoiceChip(a.label, action == a, actionColor(a)) { action = a }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("例：填「pm」+ 前缀 + 允许 → AI 以后执行 pm 开头的命令就不再问你。", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (command.isNotBlank()) onAdd(command.trim(), match, action) }) {
                Text("添加", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

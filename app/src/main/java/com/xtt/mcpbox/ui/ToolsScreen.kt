// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.ToolPolicy
import com.xtt.mcpbox.core.ToolSpec

/**
 * 工具管理：列出全部工具（内置 + 自定义），点进去可以
 * **单独启用/禁用**、**单独设权限**（允许 / 询问 / 禁止），自定义工具还能删掉。
 */
@Composable
fun ToolsScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    // list / editor / detail:<工具名>
    var page by rememberSaveable { mutableStateOf("list") }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val entering = targetState.isNotEmpty()
            val slide = if (entering) 1 else -1
            (
                slideInHorizontally(tween(280)) { w -> slide * w / 3 } + fadeIn(tween(200))
                ).togetherWith(
                slideOutHorizontally(tween(240)) { w -> -slide * w / 6 } + fadeOut(tween(160))
            )
        },
        label = "toolPage"
    ) { current ->
        when {
            current == "editor" -> CustomToolsScreen(
                ctx = ctx,
                revision = revision,
                onChanged = onChanged,
                onBack = { page = "list" }
            )
            current.startsWith("detail:") -> ToolDetailPage(
                ctx = ctx,
                name = current.removePrefix("detail:"),
                revision = revision,
                onChanged = onChanged,
                onBack = { page = "list" }
            )
            else -> ToolListPage(ctx, revision, onChanged, onBack, onCreate = { page = "editor" }) {
                page = "detail:$it"
            }
        }
    }
}

@Composable
private fun ToolListPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }

    val all = remember(revision) { AppCore.server.tools }
    val builtinNames = remember(revision) { AppCore.server.builtinTools.map { it.name }.toSet() }
    var query by remember { mutableStateOf("") }

    val filtered = remember(revision, query) {
        val q = query.trim()
        if (q.isEmpty()) all
        else all.filter {
            it.name.contains(q, true) || it.title.contains(q, true) ||
                it.description.contains(q, true)
        }
    }
    val custom = filtered.filter { it.name !in builtinNames }
    val builtin = filtered.filter { it.name in builtinNames }
    val disabledCount = ToolPolicy.disabled(AppCore.config).size
    val overrideCount = ToolPolicy.explicitCount(AppCore.config)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("工具管理"),
            subtitle = L("共 %s 个 · 禁用 %s · 单独设权限 %s").format(all.size, disabledCount, overrideCount),
            actions = {
                RoundIconButton(Icons.Filled.Add, L("新建自定义工具"), onClick = onCreate)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack)
            }
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(L("搜索工具名 / 说明"), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Search, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (custom.isNotEmpty()) {
            GroupLabel(L("自定义工具（%s）").format(custom.size))
            CardGroup(custom.map { spec -> toolRow(spec, builtinNames, onOpen) })
        }

        GroupLabel(L("内置工具（%s）").format(builtin.size))
        CardGroup(builtin.map { spec -> toolRow(spec, builtinNames, onOpen) })

        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("右上角的 + 可以新建自定义工具；禁用的工具不会出现在 AI 的 tools/list 里，") +
                    L("也调不动；「单独设权限」只针对某个工具，不影响别的工具。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun toolRow(spec: ToolSpec, builtinNames: Set<String>, onOpen: (String) -> Unit): RowSpec {
    val disabled = ToolPolicy.isDisabled(AppCore.config, spec.name)
    val override = ToolPolicy.effectiveOverride(AppCore.config, spec.name)
    val overrideColor = when (override) {
        ToolPolicy.DENY -> MaterialTheme.colorScheme.error
        ToolPolicy.ALLOW -> MaterialTheme.colorScheme.tertiary
        ToolPolicy.ASK -> Sem.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val subtitle = buildString {
        append(L(spec.title))
        val tags = mutableListOf<String>()
        if (disabled) tags.add(L("已禁用"))
        if (ToolPolicy.isExplicit(override)) tags.add(L(ToolPolicy.label(override)))
        if (spec.name !in builtinNames) tags.add(L("自定义"))
        if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" / "))
    }
    return RowSpec(
        title = spec.name,
        subtitle = subtitle,
        subtitleMaxLines = 1,
        icon = Icons.Filled.Build,
        iconTint = when {
            disabled -> MaterialTheme.colorScheme.outline
            else -> overrideColor
        },
        onClick = { onOpen(spec.name) },
        trailing = {
            if (disabled) OutlineTag(L("已禁用"), MaterialTheme.colorScheme.outline)
            else if (ToolPolicy.isExplicit(override)) OutlineTag(L(ToolPolicy.label(override)), overrideColor)
        }
    )
}

@Composable
private fun ToolDetailPage(
    ctx: Context,
    name: String,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }

    val spec = remember(revision, name) { AppCore.server.tools.firstOrNull { it.name == name } }
    if (spec == null) {
        // 工具可能在别处被删了
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            PageHeader(
                title = L("工具不存在"),
                subtitle = L("它可能已经被删掉了"),
                actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
            )
        }
        return
    }
    val builtinSpec = remember(revision, name) { AppCore.server.builtinTools.firstOrNull { it.name == name } }
    val builtin = builtinSpec != null
    val custom = AppCore.customTools.byName(spec.name)
    var confirmDelete by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    val disabled = ToolPolicy.isDisabled(AppCore.config, spec.name)
    val override = ToolPolicy.effectiveOverride(AppCore.config, spec.name)
    val overrideColor = when (override) {
        ToolPolicy.DENY -> MaterialTheme.colorScheme.error
        ToolPolicy.ALLOW -> MaterialTheme.colorScheme.tertiary
        ToolPolicy.ASK -> Sem.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val metaCustomized = AppCore.toolMeta.isCustomized(spec.name)

    if (showPermDialog) {
        val values = ToolPolicy.ALL_VALUES
        ChoiceDialog(
            title = L("「%s」的权限").format(spec.name),
            options = values.map { permLabel(it) },
            selected = values.indexOf(override).coerceAtLeast(0),
            onDismiss = { showPermDialog = false },
            onSelect = { idx ->
                ToolPolicy.setOverride(AppCore.config, spec.name, values[idx])
                AppCore.saveConfig()
                showPermDialog = false
                onChanged()
            }
        )
    }

    if (showEditDialog && builtinSpec != null) {
        ToolMetaDialog(
            name = spec.name,
            defaultTitle = builtinSpec.title,
            defaultDescription = builtinSpec.description,
            currentTitle = spec.title,
            currentDescription = spec.description,
            onDismiss = { showEditDialog = false },
            onSave = { t, d ->
                AppCore.toolMeta.set(spec.name, t, d)
                showEditDialog = false
                toast(ctx, L("说明已更新，AI 下次 tools/list 就会看到"))
                onChanged()
            },
            onReset = {
                AppCore.toolMeta.reset(spec.name)
                showEditDialog = false
                toast(ctx, L("已恢复内置说明"))
                onChanged()
            }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = spec.name,
            subtitle = spec.title,
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("状态"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("启用这个工具"),
                    subtitle = if (disabled) L("当前已禁用：AI 看不到它也调不动")
                    else L("AI 可以在 tools/list 里看到并调用它"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Build,
                    checked = !disabled
                ) { on ->
                    ToolPolicy.setDisabled(AppCore.config, spec.name, !on)
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )

        GroupLabel(L("单独权限"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("权限"),
                    subtitle = when (override) {
                        ToolPolicy.ALLOW -> L("这个工具的所有操作直接放行，不弹审批")
                        ToolPolicy.ASK -> L("每次都弹窗问你，不看全局权限矩阵")
                        ToolPolicy.DENY -> L("无论全局怎么设，这个工具一律拒绝")
                        else -> L("跟随全局权限矩阵（%s）").format(L(spec.perm.title))
                    },
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info,
                    onClick = { showPermDialog = true },
                    trailing = {
                        OutlineTag(L(ToolPolicy.label(override)), overrideColor)
                    }
                )
            )
        )

        GroupLabel(L("说明"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("显示名称"),
                    subtitle = spec.title,
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info,
                    onClick = if (builtin) ({ showEditDialog = true }) else null,
                    trailing = if (builtin) ({
                        OutlineTag(
                            if (metaCustomized) L("已改") else L("默认"),
                            if (metaCustomized) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }) else null
                ),
                RowSpec(
                    title = L("给 AI 看的说明"),
                    subtitle = spec.description,
                    subtitleMaxLines = 5,
                    icon = Icons.Filled.Info,
                    onClick = if (builtin) ({ showEditDialog = true }) else null
                ),
                if (!builtin) RowSpec(
                    title = L("怎么改"),
                    subtitle = L("自定义工具的说明在上面的「编辑自定义工具」里改"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info
                ) else null
            ).filterNotNull()
        )

        GroupLabel(L("详情"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("权限分类"),
                    subtitle = L(spec.perm.title),
                    icon = Icons.Filled.Info
                ),
                RowSpec(
                    title = L("来源"),
                    subtitle = if (builtin) L("内置工具") else L("自定义工具"),
                    icon = Icons.Filled.Info
                ),
                if (custom != null) RowSpec(
                    title = L("命令模板"),
                    subtitle = custom.command,
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Build
                ) else null
            ).filterNotNull()
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                spec.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }

        if (!builtin) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(horizontal = 14.dp)) {
                PillButton(
                    L("删除这个工具"),
                    Modifier.fillMaxWidth(),
                    outlined = true,
                    color = MaterialTheme.colorScheme.error
                ) { confirmDelete = true }
            }
        }

        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("参数：%s").format(paramNames(spec)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("删除工具"), fontSize = 20.sp) },
            text = {
                Text(
                    L("确定删除「%s」吗？删除后 AI 就调不到它了。").format(L(spec.title)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.customTools.remove(spec.name)
                    confirmDelete = false
                    toast(ctx, L("已删除 %s").format(spec.name))
                    onChanged()
                    onBack()
                }) { Text(L("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** 参数名列表，展示用。 */
private fun paramNames(spec: ToolSpec): String =
    spec.paramNames.joinToString(", ").ifBlank { L("无") }

/** 权限四态的中文标签。 */
private fun permLabel(value: String): String = L(ToolPolicy.label(value))

/**
 * 编辑内置工具的「显示名称」和「给 AI 看的说明」。
 * 只改文案，工具的行为、参数、权限都不受影响；留空 = 用回内置原文。
 */
@Composable
private fun ToolMetaDialog(
    name: String,
    defaultTitle: String,
    defaultDescription: String,
    currentTitle: String,
    currentDescription: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onReset: () -> Unit
) {
    var title by remember { mutableStateOf(if (currentTitle == defaultTitle) "" else currentTitle) }
    var desc by remember { mutableStateOf(if (currentDescription == defaultDescription) "" else currentDescription) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            MaxTvScrollView(fraction = 0.78f) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        L("编辑工具说明"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(L("显示名称"), fontSize = 12.sp) },
                        placeholder = { Text(defaultTitle, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text(L("给 AI 看的说明"), fontSize = 12.sp) },
                        placeholder = { Text(defaultDescription, fontSize = 12.sp) },
                        minLines = 5,
                        maxLines = 12,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        L("留空表示用回内置原文。说明会出现在 AI 的 tools/list 里，改完立刻生效，") +
                            L("不影响工具本身的行为和权限。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            L("恢复默认"), Modifier.weight(1f), outlined = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                        ) { onReset() }
                        PillButton(
                            L("取消"), Modifier.weight(1f), outlined = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                        ) { onDismiss() }
                        PillButton(
                            L("保存"), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                            compact = true
                        ) { onSave(title, desc) }
                    }
                }
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

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
    val overrideCount = ToolPolicy.overrides(AppCore.config).size

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = "工具管理",
            subtitle = "共 ${all.size} 个 · 禁用 $disabledCount · 单独设权限 $overrideCount",
            actions = {
                RoundIconButton(Icons.Filled.Add, "新建自定义工具", onClick = onCreate)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.ArrowBack, "返回", onClick = onBack)
            }
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索工具名 / 说明", fontSize = 13.sp) },
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
            GroupLabel("自定义工具（${custom.size}）")
            CardGroup(custom.map { spec -> toolRow(spec, builtinNames, onOpen) })
        }

        GroupLabel("内置工具（${builtin.size}）")
        CardGroup(builtin.map { spec -> toolRow(spec, builtinNames, onOpen) })

        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                "右上角的 + 可以新建自定义工具；禁用的工具不会出现在 AI 的 tools/list 里，" +
                    "也调不动；「单独设权限」只针对某个工具，不影响别的工具。",
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
    val override = ToolPolicy.overrideOf(AppCore.config, spec.name)
    val subtitle = buildString {
        append(spec.title)
        val tags = mutableListOf<String>()
        if (disabled) tags.add("已禁用")
        if (override != null) tags.add(ToolPolicy.label(override))
        if (spec.name !in builtinNames) tags.add("自定义")
        if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" / "))
    }
    return RowSpec(
        title = spec.name,
        subtitle = subtitle,
        subtitleMaxLines = 1,
        icon = Icons.Filled.Build,
        iconTint = when {
            disabled -> MaterialTheme.colorScheme.outline
            override == ToolPolicy.DENY -> MaterialTheme.colorScheme.error
            override == ToolPolicy.ALLOW -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        onClick = { onOpen(spec.name) },
        trailing = {
            if (disabled) OutlineTag("已禁用", MaterialTheme.colorScheme.outline)
            else if (override != null) OutlineTag(ToolPolicy.label(override), MaterialTheme.colorScheme.primary)
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
                title = "工具不存在",
                subtitle = "它可能已经被删掉了",
                actions = { RoundIconButton(Icons.Filled.ArrowBack, "返回", onClick = onBack) }
            )
        }
        return
    }
    val builtin = spec.name in AppCore.server.builtinTools.map { it.name }
    val custom = AppCore.customTools.byName(spec.name)
    var confirmDelete by remember { mutableStateOf(false) }

    val disabled = ToolPolicy.isDisabled(AppCore.config, spec.name)
    val override = ToolPolicy.overrideOf(AppCore.config, spec.name)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = spec.name,
            subtitle = spec.title,
            actions = { RoundIconButton(Icons.Filled.ArrowBack, "返回", onClick = onBack) }
        )

        GroupLabel("状态")
        CardGroup(
            listOf(
                switchSpec(
                    title = "启用这个工具",
                    subtitle = if (disabled) "当前已禁用：AI 看不到它也调不动"
                    else "AI 可以在 tools/list 里看到并调用它",
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

        GroupLabel("单独权限")
        CardGroup(
            listOf(
                RowSpec(
                    title = "权限",
                    subtitle = when (override) {
                        ToolPolicy.ALLOW -> "这个工具的所有操作直接放行，不弹审批"
                        ToolPolicy.DENY -> "无论全局怎么设，这个工具一律拒绝"
                        else -> "跟随全局权限矩阵（${spec.perm.title}）"
                    },
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Info,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                ToolPolicy.ALLOW to "允许",
                                ToolPolicy.ASK to "询问",
                                ToolPolicy.DENY to "禁止"
                            ).forEach { (value, text) ->
                                val active = (override ?: ToolPolicy.ASK) == value
                                PillButton(
                                    text,
                                    outlined = !active,
                                    compact = true,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    ToolPolicy.setOverride(AppCore.config, spec.name, value)
                                    AppCore.saveConfig()
                                    onChanged()
                                }
                            }
                        }
                    }
                )
            )
        )

        GroupLabel("详情")
        CardGroup(
            listOf(
                RowSpec(
                    title = "权限分类",
                    subtitle = spec.perm.title,
                    icon = Icons.Filled.Info
                ),
                RowSpec(
                    title = "来源",
                    subtitle = if (builtin) "内置工具" else "自定义工具",
                    icon = Icons.Filled.Info
                ),
                if (custom != null) RowSpec(
                    title = "命令模板",
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
                    "删除这个工具",
                    Modifier.fillMaxWidth(),
                    outlined = true,
                    color = MaterialTheme.colorScheme.error
                ) { confirmDelete = true }
            }
        }

        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                "参数：${paramNames(spec)}",
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
            title = { Text("删除工具", fontSize = 20.sp) },
            text = {
                Text(
                    "确定删除「${spec.title}」吗？删除后 AI 就调不到它了。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.customTools.remove(spec.name)
                    confirmDelete = false
                    toast(ctx, "已删除 ${spec.name}")
                    onChanged()
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** 参数名列表，展示用。 */
private fun paramNames(spec: ToolSpec): String =
    spec.paramNames.joinToString(", ").ifBlank { "无" }

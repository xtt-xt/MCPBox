// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.CustomTool
import com.xtt.mcpbox.core.CustomToolParam
import com.xtt.mcpbox.core.ToolFailure

@Composable
fun CustomToolsScreen(ctx: Context, revision: Int, onChanged: () -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<CustomTool?>(null) }
    var creating by remember { mutableStateOf(false) }

    // 系统返回键 = 退回设置页，而不是把整个 App 关掉
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }
    val tools = remember(revision) { AppCore.customTools.tools }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val count = AppCore.customTools.tools.size
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(AppCore.customTools.exportJson().toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
        toast(ctx, if (ok) "已导出 $count 个工具" else "导出失败")
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast(ctx, L("读不到文件内容"))
            return@rememberLauncherForActivityResult
        }
        runCatching { AppCore.customTools.importJson(text) }
            .onSuccess { toast(ctx, it.message) }
            .onFailure { e -> toast(ctx, "导入失败：${(e as? ToolFailure)?.message ?: e.message}") }
        onChanged()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        PageHeader(
            title = L("自定义工具"),
            subtitle = L("给 AI 造工具：命令模板里用 {{参数名}} 插入参数，创建后自动出现在 tools/list"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        CardGroup(
            listOf(
                RowSpec(
                    title = L("新建工具"),
                    subtitle = L("填名称、说明和命令模板即可"),
                    icon = Icons.Filled.Add,
                    onClick = { creating = true }
                ),
                RowSpec(
                    title = L("让 AI 自己造工具"),
                    subtitle = L("直接说「帮我建一个能查磁盘占用的工具」，它会调用 create_custom_tool（要你批准）"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    L("导出到文件"), Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.primary, compact = true
                ) {
                    exportLauncher.launch("mcp-tools.json")
                }
                PillButton(
                    L("从文件导入"), Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.primary, compact = true
                ) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
        }

        GroupLabel("已有工具（${tools.size}）")
        CardGroup(
            rows = buildList {
                if (tools.isEmpty()) {
                    add(
                        RowSpec(
                            title = L("还没有自定义工具"),
                            subtitle = L("例如：du -sh {{path}} → 一个「查目录占用」的工具"),
                            subtitleMaxLines = 2,
                            icon = Icons.Filled.Build
                        )
                    )
                }
                tools.forEach { tool ->
                    add(
                        RowSpec(
                            title = tool.title.ifBlank { tool.name },
                            subtitle = buildString {
                                append(tool.name)
                                if (tool.engineLabel().isNotBlank()) append(" · ").append(tool.engineLabel())
                                append(L(" · 运行 ")).append(tool.runCount).append(L(" 次"))
                            },
                            icon = Icons.Filled.Create,
                            onClick = { editing = tool },
                            trailing = {
                                RoundIconButton(Icons.Filled.Delete, L("删除"), tint = Sem.bad, size = 40) {
                                    AppCore.customTools.remove(tool.id)
                                    onChanged()
                                }
                            }
                        )
                    )
                }
            }
        )

        GroupLabel(L("说明"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("参数怎么写"),
                    subtitle = L("命令里写 {{path}}，AI 调用时会做 shell 转义；写 {{path:raw}} 则原样插入。") +
                        L("必填参数没给会直接报错，不会执行危险命令。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Send
                ),
                RowSpec(
                    title = L("审批规则"),
                    subtitle = L("自定义工具用「执行命令」权限；如果命令以某个词开头（如 du），") +
                        L("你在弹窗里点过「记住此命令」，之后同类命令就不再询问。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Share
                )
            )
        )
    }

    if (creating) {
        EditToolDialog(
            tool = CustomTool(),
            isNew = true,
            onDismiss = { creating = false },
            onSave = { t ->
                runCatching { AppCore.customTools.add(t) }
                    .onSuccess { toast(ctx, "已创建 ${it.name}") }
                    .onFailure { e -> toast(ctx, "保存失败：${(e as? ToolFailure)?.message ?: e.message}") }
                creating = false
                onChanged()
            }
        )
    }

    editing?.let { tool ->
        EditToolDialog(
            tool = tool,
            isNew = false,
            onDismiss = { editing = null },
            onSave = { t ->
                runCatching { AppCore.customTools.update(t) }
                    .onSuccess { toast(ctx, "已保存 ${it.name}") }
                    .onFailure { e -> toast(ctx, "保存失败：${(e as? ToolFailure)?.message ?: e.message}") }
                editing = null
                onChanged()
            }
        )
    }
}

private fun CustomTool.engineLabel(): String = when (backend) {
    "shizuku" -> "Shizuku"
    "root" -> "Root"
    "app" -> L("应用沙箱")
    else -> ""
}

@Composable
private fun EditToolDialog(
    tool: CustomTool,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomTool) -> Unit
) {
    var name by remember { mutableStateOf(tool.name) }
    var title by remember { mutableStateOf(tool.title) }
    var description by remember { mutableStateOf(tool.description) }
    var command by remember { mutableStateOf(tool.command) }
    var params by remember { mutableStateOf(tool.params) }
    var backend by remember { mutableStateOf(tool.backend) }
    var enabled by remember { mutableStateOf(tool.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(if (isNew) L("新建自定义工具") else "编辑 ${tool.name}", fontSize = 19.sp) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() },
                    label = { Text(L("工具名（英文，如 disk_usage）")) },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(L("中文标题")) },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text(L("给 AI 看的说明")) },
                    minLines = 2, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text(L("命令模板，如 du -sh {{path}}")) },
                    minLines = 2,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("参数（${params.size}）", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    PillButton(L("加一个"), outlined = true, compact = true, color = MaterialTheme.colorScheme.primary) {
                        params = params + CustomToolParam(name = "")
                    }
                }
                params.forEachIndexed { index, p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = p.name,
                            onValueChange = { v ->
                                params = params.toMutableList().also { it[index] = p.copy(name = v.trim()) }
                            },
                            placeholder = { Text(L("名字"), fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        PillDropdown(
                            value = when (p.type) {
                                "integer" -> L("整数")
                                "boolean" -> L("开关")
                                else -> L("文本")
                            },
                            options = listOf(L("文本"), L("整数"), L("开关"))
                        ) { idx ->
                            params = params.toMutableList().also {
                                it[index] = p.copy(type = listOf("string", "integer", "boolean")[idx])
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        PillButton(
                            if (p.required) L("必填") else L("可选"),
                            outlined = !p.required,
                            compact = true,
                            color = if (p.required) Sem.warn else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            params = params.toMutableList().also { it[index] = p.copy(required = !p.required) }
                        }
                        RoundIconButton(Icons.Filled.Delete, L("删除"), tint = Sem.bad, size = 36) {
                            params = params.toMutableList().also { it.removeAt(index) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(L("执行后端"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillDropdown(
                        value = when (backend) {
                            "shizuku" -> "Shizuku"
                            "root" -> "Root"
                            "app" -> L("应用沙箱")
                            else -> L("自动")
                        },
                        options = listOf(L("自动"), "Shizuku", "Root", L("应用沙箱"))
                    ) { idx ->
                        backend = listOf("auto", "shizuku", "root", "app")[idx]
                    }
                    PillButton(
                        if (enabled) L("已启用") else L("已停用"),
                        outlined = !enabled, compact = true,
                        color = if (enabled) Sem.ok else MaterialTheme.colorScheme.onSurfaceVariant
                    ) { enabled = !enabled }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    tool.copy(
                        name = name, title = title, description = description,
                        command = command, params = params.filter { it.name.isNotBlank() },
                        backend = backend, enabled = enabled
                    )
                )
            }) { Text(if (isNew) L("创建") else L("保存"), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

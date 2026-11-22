// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.InstalledPlugin
import com.xtt.mcpbox.core.PluginException
import com.xtt.mcpbox.core.PluginHttpDecl
import com.xtt.mcpbox.core.PluginSettingDecl
import com.xtt.mcpbox.i18n.L
import kotlinx.serialization.json.JsonPrimitive

/**
 * 插件详情：概览、开关、接口 Key、设置项、日志、卸载。
 *
 * 一页装下所有「这个插件现在是什么样」—— 但它只是入口，改设置项仍然走原生弹窗，
 * 不在这里内联一堆输入框。设置项按 manifest 自动生成，插件作者不写 UI。
 */
@Composable
fun PluginDetailScreen(
    ctx: Context,
    revision: Int,
    pluginId: String,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val plugin = remember(revision) { AppCore.plugins.byId(pluginId) }
    // 已经在别的页面被卸载了：直接退回去，不在这里停一张空页
    if (plugin == null) {
        LaunchedEffect(pluginId) { onBack() }
        return
    }

    val values = remember(revision) { AppCore.plugins.settingValues(pluginId) }
    var editing by remember { mutableStateOf<PluginSettingDecl?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var confirmServe by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }

    val title = plugin.manifest.name.ifBlank { plugin.id }
    val subtitle = when {
        plugin.broken -> L("读不出来")
        else -> buildString {
            append("v").append(plugin.manifest.version.ifBlank { "?" })
            append(" · ").append(plugin.id)
        }
    }

    PageScaffold(
        scroll = rememberScrollState(),
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        bottomPadding = 20.dp
    ) {
        if (plugin.broken) {
            GroupLabel(L("状态"))
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("这个插件读不出来"),
                        subtitle = plugin.error ?: L(" plugin.json 不合规"),
                        subtitleMaxLines = 4,
                        subtitleColor = Sem.bad,
                        icon = Icons.Filled.Info
                    )
                )
            )
            GroupLabel(L("操作"))
            CardGroup(listOf(uninstallRow { confirmUninstall = true }))
            Spacer(Modifier.height(20.dp))
            return@PageScaffold
        }

        GroupLabel(L("状态"))
        CardGroup(
            buildList {
                add(
                    RowSpec(
                        title = L("启用"),
                        subtitle = if (plugin.incompatible) {
                            plugin.requirementError ?: L("版本不兼容")
                        } else {
                            L("停用后它的工具不会出现在 AI 的工具列表里，接口也会关闭")
                        },
                        subtitleMaxLines = 3,
                        subtitleColor = if (plugin.incompatible) Sem.bad else null,
                        trailing = {
                            if (plugin.incompatible) LockedSwitch(plugin.enabled)
                            else AppSwitch(plugin.enabled) { on ->
                                AppCore.plugins.setEnabled(pluginId, on)
                                onChanged()
                            }
                        }
                    )
                )
                if (plugin.manifest.http != null) {
                    add(
                        RowSpec(
                            title = L("对外服务"),
                            subtitle = if (plugin.serving) L("正在服务 · %s").format(serveSummary(plugin))
                            else serveSummary(plugin),
                            subtitleMaxLines = 2,
                            trailing = {
                                AppSwitch(plugin.httpEnabled) { on ->
                                    if (on) confirmServe = true
                                    else {
                                        AppCore.plugins.setHttpEnabled(pluginId, false)
                                        onChanged()
                                    }
                                }
                            }
                        )
                    )
                    add(
                        RowSpec(
                            title = L("接口 Key"),
                            subtitle = maskKey(AppCore.plugins.keyOf(pluginId).orEmpty()),
                            indent = true,
                            onClick = { showKey = true }
                        )
                    )
                }
            }
        )

        GroupLabel(L("概览"))
        CardGroup(
            buildList {
                add(RowSpec(title = L("说明"), subtitle = plugin.manifest.description, subtitleMaxLines = 4))
                if (plugin.manifest.author.isNotBlank()) {
                    add(RowSpec(title = L("作者"), subtitle = plugin.manifest.author))
                }
                if (plugin.manifest.homepage.isNotBlank()) {
                    add(RowSpec(title = L("主页"), subtitle = plugin.manifest.homepage, subtitleMaxLines = 2))
                }
                val installedAt = plugin.config.installedAt
                add(
                    RowSpec(
                        title = L("安装来源"),
                        subtitle = buildString {
                            append(plugin.config.source.ifBlank { L("本地目录") })
                            if (installedAt > 0) {
                                append(" · ")
                                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(installedAt)))
                            }
                        },
                        subtitleMaxLines = 2
                    )
                )
            }
        )

        if (plugin.tools().isNotEmpty()) {
            GroupLabel(L("工具（%s）").format(plugin.tools().size))
            CardGroup(
                plugin.tools().map { tool ->
                    RowSpec(
                        title = tool.name,
                        subtitle = tool.description,
                        subtitleMaxLines = 3
                    )
                }
            )
        }

        val routes = plugin.manifest.http?.routes.orEmpty()
        if (routes.isNotEmpty()) {
            GroupLabel(L("接口（%s）").format(routes.size))
            CardGroup(
                routes.map { route ->
                    RowSpec(
                        title = route.methods.joinToString("/") + " " + route.path,
                        subtitle = buildString {
                            append(route.handler)
                            if (route.stream) append(" · ").append(L("流式"))
                            if (route.auth == "none") append(" · ").append(L("不要 Key"))
                        },
                        subtitleMaxLines = 2
                    )
                }
            )
        }

        if (plugin.manifest.settings.isNotEmpty()) {
            GroupLabel(L("设置"))
            CardGroup(
                plugin.manifest.settings.map { decl ->
                    settingRow(
                        decl = decl,
                        current = values[decl.key],
                        onBool = { on ->
                            AppCore.plugins.setSetting(pluginId, decl.key, JsonPrimitive(on))
                            onChanged()
                        },
                        onEnum = { option ->
                            AppCore.plugins.setSetting(pluginId, decl.key, JsonPrimitive(option))
                            onChanged()
                        },
                        onEdit = { editing = decl }
                    )
                }
            )
        }

        GroupLabel(L("日志"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("运行日志"),
                    subtitle = if (logOpen) L("点一下收起") else L("点开看最近 %s 行").format(LOG_LINES),
                    onClick = { logOpen = !logOpen },
                    content = if (logOpen) {
                        {
                            val text = remember(revision, logOpen) { readLogTail(pluginId) }
                            Text(
                                text,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                    } else null
                )
            )
        )

        GroupLabel(L("操作"))
        CardGroup(listOf(uninstallRow { confirmUninstall = true }))

        Spacer(Modifier.height(20.dp))
    }

    if (confirmServe) {
        val lan = plugin.manifest.http?.bind == PluginHttpDecl.BIND_LAN
        AlertDialog(
            onDismissRequest = { confirmServe = false },
            title = { Text(L("打开对外服务？")) },
            text = {
                Text(
                    if (lan) L("局域网里的设备凭这把 Key 就能访问，同网段的人都能用。")
                    else L("打开后，手机上的程序可以用这把 Key 调这个插件。")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.plugins.setHttpEnabled(pluginId, true)
                    confirmServe = false
                    onChanged()
                }) { Text(L("打开")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmServe = false }) { Text(L("取消")) }
            }
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(L("卸载「%s」？").format(title)) },
            text = { Text(L("插件的目录、设置、Key 和它自己的数据都会被删除，不能恢复。")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    runCatching { AppCore.plugins.uninstall(pluginId) }
                        .onSuccess {
                            toast(ctx, L("已卸载「%s」").format(title))
                            onChanged()
                            onBack()
                        }
                        .onFailure { e ->
                            toast(ctx, (e as? PluginException)?.message ?: L("卸载失败"))
                        }
                }) { Text(L("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(L("取消")) }
            }
        )
    }

    if (showKey) {
        KeyDialog(pluginId = pluginId, onDismiss = { showKey = false }, onChanged = onChanged)
    }

    editing?.let { decl ->
        val current = values[decl.key]?.let { (it as? JsonPrimitive)?.content }.orEmpty()
        SettingEditDialog(
            decl = decl,
            current = current,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                runCatching {
                    AppCore.plugins.setSetting(pluginId, decl.key, JsonPrimitive(text))
                    onChanged()
                }.onFailure { e ->
                    toast(ctx, (e as? PluginException)?.message ?: L("保存失败"))
                }
            }
        )
    }
}

/** 一次最多显示几行日志。 */
private const val LOG_LINES = 60

private fun uninstallRow(onClick: () -> Unit) = RowSpec(
    title = L("卸载"),
    subtitle = L("删掉整个插件目录，设置、Key 和它的数据一起消失"),
    subtitleMaxLines = 2,
    subtitleColor = Sem.bad,
    icon = Icons.Filled.Delete,
    iconTint = Sem.bad,
    onClick = onClick
)

/** 读日志最后 [LOG_LINES] 行；文件不在或读不动就给一句人话。 */
private fun readLogTail(id: String): String {
    val file = runCatching { AppCore.plugins.logFile(id) }.getOrNull()
        ?: return L("读不到日志文件")
    if (!file.isFile) return L("这个插件还没写过日志")
    val lines = runCatching { file.readLines() }.getOrElse { return L("读不到日志文件") }
    if (lines.isEmpty()) return L("这个插件还没写过日志")
    return lines.takeLast(LOG_LINES).joinToString("\n")
}

/** 接口 Key：默认打码，能显示 / 复制 / 换一把。 */
@Composable
private fun KeyDialog(pluginId: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(AppCore.plugins.keyOf(pluginId).orEmpty()) }
    val shown = if (revealed) key else maskKey(key)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("接口 Key")) },
        text = {
            Column {
                Text(
                    shown,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                )
                Text(
                    L("客户端把这个 Key 填进 Authorization: Bearer 或 X-API-Key。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(if (revealed) L("隐藏") else L("显示"))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { copyText(ctx, key, L("Key 已复制")) }) { Text(L("复制")) }
                TextButton(onClick = {
                    key = AppCore.plugins.rotateKey(pluginId)
                    revealed = true
                    onChanged()
                }) { Text(L("换一把")) }
                TextButton(onClick = onDismiss) { Text(L("关闭")) }
            }
        }
    )
}

/**
 * 一个设置项对应的那一行。
 *
 * 布尔 = 开关，枚举 = 胶囊菜单，其余（文本 / 密钥 / 数字）= 胶囊显示当前值、点开是原生弹窗。
 * 三套形态各管一种值，不混用。
 */
@Composable
private fun settingRow(
    decl: PluginSettingDecl,
    current: kotlinx.serialization.json.JsonElement?,
    onBool: (Boolean) -> Unit,
    onEnum: (String) -> Unit,
    onEdit: () -> Unit
): RowSpec {
    val raw = (current as? JsonPrimitive)?.content.orEmpty()
    val hint: String? = when {
        decl.description.isNotBlank() -> decl.description
        decl.default.isNotBlank() -> L("默认 %s").format(decl.default)
        else -> null
    }
    return when (decl.type) {
        PluginSettingDecl.TYPE_BOOLEAN -> RowSpec(
            title = decl.title,
            subtitle = hint,
            subtitleMaxLines = 2,
            trailing = {
                AppSwitch(raw.equals("true", ignoreCase = true), onCheckedChange = onBool)
            }
        )
        PluginSettingDecl.TYPE_ENUM -> RowSpec(
            title = decl.title,
            subtitle = hint,
            subtitleMaxLines = 2,
            trailing = {
                PillDropdown(
                    value = raw.ifBlank { decl.default }.ifBlank { decl.options.firstOrNull().orEmpty() },
                    options = decl.options,
                    onSelect = { index -> decl.options.getOrNull(index)?.let(onEnum) }
                )
            }
        )
        else -> RowSpec(
            title = decl.title,
            subtitle = hint,
            subtitleMaxLines = 2,
            onClick = onEdit,
            trailing = {
                PillButton(
                    text = when {
                        decl.type == PluginSettingDecl.TYPE_SECRET && raw.isNotBlank() -> "••••••"
                        raw.isNotBlank() -> raw
                        else -> L("未设置")
                    },
                    outlined = true,
                    compact = true,
                    onClick = onEdit
                )
            }
        )
    }
}

/** 文本 / 密钥 / 数字的编辑弹窗：只有输入框和按钮。 */
@Composable
private fun SettingEditDialog(
    decl: PluginSettingDecl,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(decl.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = when (decl.type) {
                            PluginSettingDecl.TYPE_INTEGER -> v.filter { it.isDigit() || it == '-' }
                            PluginSettingDecl.TYPE_NUMBER -> v.filter { it.isDigit() || it == '-' || it == '.' }
                            else -> v
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (decl.type == PluginSettingDecl.TYPE_SECRET) {
                        PasswordVisualTransformation()
                    } else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(L("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(L("取消")) }
        }
    )
}

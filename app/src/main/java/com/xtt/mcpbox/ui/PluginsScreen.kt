// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.InstalledPlugin
import com.xtt.mcpbox.core.PluginHttpDecl
import com.xtt.mcpbox.i18n.L
import android.content.Context

/**
 * 插件页（P2 的最小版）：看出装了哪些、开关「对外服务」、看一眼 Key。
 *
 * 导入 / 卸载 / 设置项 / 日志留给 P3 —— 这里只做「能跑起来」必需的几件事。
 */
@Composable
fun PluginsScreen(ctx: Context, revision: Int, onChanged: () -> Unit, onBack: () -> Unit) {
    val plugins = remember(revision) { AppCore.plugins.plugins }
    var keyTarget by remember { mutableStateOf<String?>(null) }
    var confirmLan by remember { mutableStateOf<String?>(null) }

    PageScaffold(
        scroll = rememberScrollState(),
        title = L("插件"),
        subtitle = L("用 JS 写的工具与服务；服务类插件可以自己对外提供接口"),
        onBack = onBack,
        bottomPadding = 20.dp
    ) {
        if (plugins.isEmpty()) {
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("还没有装插件"),
                        subtitle = L("把插件包放进 %s 下就会出现在这里").format(
                            AppCore.plugins.rootDir.absolutePath
                        ),
                        subtitleMaxLines = 3,
                        icon = Icons.Filled.Info
                    )
                )
            )
        } else {
            GroupLabel(L("已安装"))
            val rows = ArrayList<RowSpec>()
            plugins.forEach { plugin ->
                rows.add(
                    pluginRow(
                        plugin = plugin,
                        onChanged = { onChanged() },
                        onRequestEnable = { confirmLan = it }
                    )
                )
                if (plugin.manifest.http != null && plugin.usable) {
                    rows.add(
                        RowSpec(
                            title = L("接口 Key"),
                            subtitle = maskKey(AppCore.plugins.keyOf(plugin.id).orEmpty()),
                            indent = true,
                            onClick = { keyTarget = plugin.id }
                        )
                    )
                }
            }
            CardGroup(rows)

            val serving = plugins.count { it.serving }
            GroupLabel(L("状态"))
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("正在对外服务"),
                        subtitle = if (serving == 0) L("没有插件在对外提供服务")
                        else L("%s 个插件正在对外提供服务").format(serving),
                        icon = Icons.Filled.List,
                        subtitleMaxLines = 2
                    )
                )
            )
        }

        Spacer(Modifier.height(20.dp))
    }

    confirmLan?.let { id ->
        val plugin = AppCore.plugins.byId(id)
        val lan = plugin?.manifest?.http?.bind == PluginHttpDecl.BIND_LAN
        AlertDialog(
            onDismissRequest = { confirmLan = null },
            title = { Text(L("打开对外服务？")) },
            text = {
                Text(
                    if (lan) L("局域网里的设备凭这把 Key 就能访问，同网段的人都能用。")
                    else L("打开后，手机上的程序可以用这把 Key 调这个插件。")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.plugins.setHttpEnabled(id, true)
                    confirmLan = null
                    onChanged()
                }) { Text(L("打开")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLan = null }) { Text(L("取消")) }
            }
        )
    }

    keyTarget?.let { id ->
        KeyDialog(
            pluginId = id,
            onDismiss = { keyTarget = null },
            onChanged = onChanged
        )
    }
}

@Composable
private fun pluginRow(
    plugin: InstalledPlugin,
    onChanged: () -> Unit,
    onRequestEnable: (String) -> Unit
): RowSpec {
    val name = plugin.manifest.name.ifBlank { plugin.id }
    val canServe = plugin.manifest.http != null && plugin.usable
    val subtitle = buildString {
        if (plugin.broken) {
            append(plugin.error ?: L("配置读不出来"))
        } else {
            append("v").append(plugin.manifest.version.ifBlank { "?" })
            val tools = plugin.tools().size
            if (tools > 0) append(" · ").append(L("%s 个工具").format(tools))
            val routes = plugin.manifest.http?.routes?.size ?: 0
            if (routes > 0) append(" · ").append(L("%s 条接口").format(routes))
            if (plugin.manifest.author.isNotBlank()) append(" · ").append(plugin.manifest.author)
            if (plugin.incompatible) append(" · ").append(L("版本不兼容"))
        }
    }
    return RowSpec(
        title = name,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        subtitleColor = if (plugin.broken || plugin.incompatible) Sem.warn else null,
        icon = Icons.Filled.List,
        onClick = if (canServe) {
            {
                if (plugin.httpEnabled) {
                    AppCore.plugins.setHttpEnabled(plugin.id, false)
                    onChanged()
                } else {
                    // 打开时要确认：这是「同意这个插件对外提供服务」
                    onRequestEnable(plugin.id)
                }
            }
        } else null,
        trailing = if (canServe) {
            {
                AppSwitch(plugin.httpEnabled) { on ->
                    if (on) onRequestEnable(plugin.id)
                    else {
                        AppCore.plugins.setHttpEnabled(plugin.id, false)
                        onChanged()
                    }
                }
            }
        } else null
    )
}

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
                TextButton(onClick = {
                    copyText(ctx, key, L("Key 已复制"))
                }) { Text(L("复制")) }
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

/** `sk-abcdef...wxyz` → `sk-••••wxyz`。 */
private fun maskKey(key: String): String {
    if (key.isBlank()) return L("还没有生成")
    if (key.length <= 8) return "••••"
    return key.take(3) + "••••" + key.takeLast(4)
}

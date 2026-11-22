// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.InstalledPlugin
import com.xtt.mcpbox.core.PluginException
import com.xtt.mcpbox.core.PluginHttpDecl
import com.xtt.mcpbox.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 插件页：装了哪些、开没开、点进去看详情。
 *
 * 右上角「+」导入 `.zip` 包；导入后插件保持停用，等用户在详情页看过再打开。
 * 详情页是独立页面（`plugins/<id>`），卸载 / 设置项 / Key / 日志都在那边。
 */
@Composable
fun PluginsScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    val plugins = remember(revision) { AppCore.plugins.plugins }
    // 正在导入的文件名；非空时盖一层进度弹窗
    var importing by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val label = displayName(ctx, uri)
        importing = label
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(ctx.cacheDir, "plugin-import.zip")
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { ins ->
                            tmp.outputStream().use { ins.copyTo(it) }
                        } ?: throw PluginException(L("读不到这个文件"))
                        val p = AppCore.plugins.installZip(tmp, label)
                        L("已导入「%s」v%s，现在是停用状态")
                            .format(p.manifest.name, p.manifest.version)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            importing = null
            result.onSuccess { toast(ctx, it); onChanged() }
                .onFailure { e ->
                    importError = (e as? PluginException)?.message ?: e.message ?: L("导入失败")
                }
        }
    }

    PageScaffold(
        scroll = rememberScrollState(),
        title = L("插件"),
        onBack = onBack,
        bottomPadding = 20.dp,
        actions = {
            RoundIconButton(Icons.Filled.Add, L("导入插件")) {
                picker.launch(arrayOf("*/*"))
            }
        }
    ) {
        if (plugins.isEmpty()) {
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("还没有装插件"),
                        subtitle = L("点右上角导入一个插件包（.zip），里面是 plugin.json 和脚本目录"),
                        subtitleMaxLines = 3,
                        icon = Icons.Filled.Info
                    )
                )
            )
        } else {
            GroupLabel(L("已安装（%s）").format(plugins.size))
            CardGroup(
                rows = plugins.map { plugin ->
                    RowSpec(
                        title = plugin.manifest.name.ifBlank { plugin.id },
                        subtitle = pluginSubtitle(plugin),
                        subtitleMaxLines = 2,
                        subtitleColor = if (plugin.broken || plugin.incompatible) Sem.bad else null,
                        icon = Icons.Filled.List,
                        onClick = { onOpen("plugins/${plugin.id}") },
                        // 坏掉的插件没有「启用」这回事，只留卸载（在详情页里）；
                        // 版本不兼容的能看见状态，但开关点不动
                        trailing = when {
                            plugin.broken -> null
                            plugin.incompatible -> ({ LockedSwitch(plugin.enabled) })
                            else -> ({
                                AppSwitch(plugin.enabled) { on ->
                                    AppCore.plugins.setEnabled(plugin.id, on)
                                    onChanged()
                                }
                            })
                        }
                    )
                }
            )

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

    importing?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(L("正在导入")) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(name, fontSize = 13.5.sp, maxLines = 2)
                }
            },
            confirmButton = {}
        )
    }

    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(L("导入失败")) },
            text = { Text(msg, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(L("知道了")) }
            }
        )
    }
}

/** 列表页那一行的小字：版本 · 工具数 · 接口数 · 作者，或者读不出来的原因。 */
internal fun pluginSubtitle(plugin: InstalledPlugin): String {
    if (plugin.broken) return plugin.error ?: L("配置读不出来")
    return buildString {
        append("v").append(plugin.manifest.version.ifBlank { "?" })
        val tools = plugin.tools().size
        if (tools > 0) append(" · ").append(L("%s 个工具").format(tools))
        val routes = plugin.manifest.http?.routes?.size ?: 0
        if (routes > 0) append(" · ").append(L("%s 条接口").format(routes))
        if (plugin.manifest.author.isNotBlank()) append(" · ").append(plugin.manifest.author)
        if (plugin.incompatible) append(" · ").append(L("版本不兼容"))
        if (plugin.serving) append(" · ").append(L("正在对外服务"))
    }
}

/** 对外服务那一段说明：这个插件监听在哪、要不要 Key。 */
internal fun serveSummary(plugin: InstalledPlugin): String {
    val http = plugin.manifest.http ?: return ""
    val bind = if (http.bind == PluginHttpDecl.BIND_LAN) L("局域网") else L("仅本机")
    val needKey = http.routes.any { it.auth == "key" }
    return L("%s · %s 条接口 · %s").format(
        bind, http.routes.size, if (needKey) L("需要 Key") else L("不需要 Key")
    )
}

/** `sk-abcdef...wxyz` → `sk-••••wxyz`；空 = 还没生成。 */
internal fun maskKey(key: String): String {
    if (key.isBlank()) return L("还没有生成")
    if (key.length <= 8) return "••••"
    return key.take(3) + "••••" + key.takeLast(4)
}

/** 文件选择器给的显示名；取不到就退回路径最后一段。 */
private fun displayName(ctx: Context, uri: Uri): String {
    val queried = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    if (!queried.isNullOrBlank()) return queried
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "plugin.zip"
}

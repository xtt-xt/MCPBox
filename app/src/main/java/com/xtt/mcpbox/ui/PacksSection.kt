// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.ToolPack

/**
 * 权限页里的「工具包」分组。
 *
 * 工具包管的是「AI 在 tools/list 里能看见哪些工具」，用来省 token；
 * 上面那排权限键管的是「能不能执行」。两者正交，互不影响。
 */
@Composable
fun PacksSection(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit
) {
    // 正在查看哪个会话：存进 Prefs，切走再回来（甚至重启 App）都还在原处。
    // 注意这只是「界面在看哪个」，AI 实际用哪个由它请求的 /mcp/p/<名字> 决定。
    var profile by remember { mutableStateOf(AppCore.prefs.packProfileView) }
    var pickProfile by remember { mutableStateOf(false) }
    var creatingProfile by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ToolPack?>(null) }
    var confirmDelete by remember { mutableStateOf<ToolPack?>(null) }

    fun selectProfile(id: String) {
        profile = id
        AppCore.prefs.packProfileView = id
    }

    val customNames = remember(revision) { AppCore.customTools.tools.map { it.name } }
    val all = remember(revision, customNames) { AppCore.packs.all(customNames) }
    val active = remember(revision, profile) { AppCore.profiles.peek(profile).toSet() }
    val profileIds = remember(revision, profile) {
        (AppCore.profiles.ids() + listOf("default", profile)).distinct().sorted()
    }

    if (pickProfile) {
        val options = profileIds.map { id ->
            val t = AppCore.profiles.stateOf(id).updatedAt
            if (id == "default") id
            else id + "　" + L("（%s）").format(relativeTime(t))
        } + L("＋ 新建会话…")
        ChoiceDialog(
            title = L("当前会话"),
            options = options,
            selected = profileIds.indexOf(profile).coerceAtLeast(0),
            onDismiss = { pickProfile = false },
            onSelect = { idx ->
                pickProfile = false
                if (idx < profileIds.size) selectProfile(profileIds[idx])
                else creatingProfile = true
            }
        )
    }

    if (creatingProfile) {
        PackNameDialog(
            title = L("新建会话"),
            nameLabel = L("会话名"),
            nameHint = L("例如：编码 / 写作（可以中文）"),
            descLabel = null,
            onDismiss = { creatingProfile = false },
            onConfirm = { name, _ ->
                val id = name.trim()
                if (id.isNotEmpty()) {
                    AppCore.profiles.reset(id)
                    selectProfile(id)
                }
                creatingProfile = false
                onChanged()
            }
        )
    }

    GroupLabel(L("工具包（省 token）"))

    CardGroup(
        listOf(
            RowSpec(
                title = L("当前会话"),
                subtitle = L("只是「我正在看哪个会话」，跟 AI 用哪个无关；AI 用哪个由它请求的地址决定"),
                subtitleMaxLines = 2,
                icon = Icons.Filled.Build,
                onClick = { pickProfile = true },
                trailing = { OutlineTag(profile, MaterialTheme.colorScheme.primary) }
            )
        )
    )

    // 直接给出该填进客户端的地址，省得自己拼
    Spacer(Modifier.height(7.dp))
    val lanIp = remember { com.xtt.mcpbox.core.LocalNet.primary() ?: "127.0.0.1" }
    val port = AppCore.config.port
    val profilePath = if (profile == "default") "/mcp"
    else "/mcp/p/" + runCatching { java.net.URLEncoder.encode(profile, "UTF-8") }.getOrDefault(profile)
    val fullUrl = "http://$lanIp:$port$profilePath"
    CardColumn {
        CardBox {
            Text(
                L("会话「%s」的地址").format(profile),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.5.sp
            )
            Text(
                fullUrl,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                L("令牌别写在地址里，放到自定义请求头更干净：") + "\n" +
                    "Authorization: Bearer " + (if (AppCore.config.tokenEnabled) AppCore.config.token else "…"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    L("复制地址"), Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.primary, compact = true
                ) { copyText(ctx, fullUrl, L("地址已复制")) }
                PillButton(
                    L("复制请求头"), Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.primary, compact = true
                ) {
                    copyText(
                        ctx,
                        "Authorization: Bearer ${AppCore.config.token}",
                        L("请求头已复制")
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(7.dp))
    CardGroup(
        all.map { pack ->
            val isOn = pack.core || pack.id in active
            val count = pack.tools.size
            RowSpec(
                title = pack.title,
                subtitle = buildString {
                    append(L("%s 个工具").format(count))
                    if (pack.core) append(" · ").append(L("常驻"))
                    else if (isOn) append(" · ").append(L("已激活"))
                    if (!pack.description.isBlank()) append(" · ").append(pack.description)
                },
                subtitleMaxLines = 2,
                icon = Icons.Filled.Build,
                iconTint = if (isOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = if (pack.core) null else ({ togglePack(pack, isOn, profile, onChanged) }),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (pack.core) {
                            OutlineTag(L("常驻"), MaterialTheme.colorScheme.outline)
                        } else {
                            AppSwitch(isOn) { togglePack(pack, isOn, profile, onChanged) }
                        }
                        if (!pack.builtin) {
                            RoundIconButton(
                                Icons.Filled.Delete, L("删除"),
                                tint = MaterialTheme.colorScheme.error, size = 34
                            ) { confirmDelete = pack }
                        }
                    }
                }
            )
        } + listOf(
            RowSpec(
                title = L("＋ 新建工具包"),
                subtitle = L("把常用工具打包，按需激活"),
                icon = Icons.Filled.Add,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { editing = ToolPack() }
            )
        )
    )

    Spacer(Modifier.height(7.dp))
    CardGroup(
        listOf(
            switchSpec(
                title = L("让 AI 自己开关工具包"),
                subtitle = if (AppCore.config.aiPackControl)
                    L("已打开：AI 能看到并能调用包管理工具。注意大多数客户端只在连接时拉一次工具列表，新激活的包要重连后才能用")
                else L("已关闭（推荐）：包纯粹是你自己的设置，AI 看到什么就用什么，不会白跑几轮去激活"),
                subtitleMaxLines = 3,
                icon = Icons.Filled.Build,
                checked = AppCore.config.aiPackControl
            ) { on ->
                AppCore.config.aiPackControl = on
                AppCore.saveConfig()
                onChanged()
            }
        )
    )

    Spacer(Modifier.height(10.dp))
    Column(Modifier.padding(horizontal = 14.dp)) {
        Text(
            L("工具包决定「AI 的工具列表里出现哪些工具」，用来省 token；也不会绕过上面的权限。") +
                L("默认只加载基础 + 文件读取 + 记忆库，能省约三分之一 token。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            L("重要：大多数 MCP 客户端只在连接时读取一次工具列表，所以改完工具包后，") +
                L("要让 AI 看到变化，需要重新连接（或重启 App）。"),
            color = Sem.warn,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (AppCore.config.profileTtlEnabled)
                L("超过 %s 分钟没请求会自动回到默认（可在设置里关掉）")
                    .format(AppCore.config.profileTtlMinutes)
            else L("会话状态不会自动重置，完全手动控制"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                L("重置这个会话"), Modifier.weight(1f), outlined = true,
                color = Sem.warn, compact = true
            ) {
                AppCore.profiles.reset(profile)
                toast(ctx, L("已重置「%s」").format(profile))
                onChanged()
            }
            if (profile != "default") {
                PillButton(
                    L("删除会话"), Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.error, compact = true
                ) {
                    AppCore.profiles.remove(profile)
                    selectProfile("default")
                    toast(ctx, L("已删除会话"))
                    onChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------- 编辑工具包

    val target = editing
    if (target != null) {
        PackEditorDialog(
            pack = target,
            isNew = target.id.isBlank(),
            allToolNames = remember(revision) { AppCore.server.tools.map { it.name } },
            onDismiss = { editing = null },
            onSave = { saved ->
                val r = runCatching {
                    if (saved.id.isBlank()) AppCore.packs.add(saved) else AppCore.packs.update(saved)
                }
                r.onSuccess {
                    toast(ctx, L("已保存工具包「%s」").format(it.title))
                    editing = null
                    onChanged()
                }.onFailure {
                    toast(ctx, it.message ?: L("保存失败"))
                }
            }
        )
    }

    confirmDelete?.let { pack ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(L("删除工具包"), fontSize = 20.sp) },
            text = {
                Text(
                    L("确定删除「%s」吗？包里的工具本身不会被删掉。").format(pack.title),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    runCatching { AppCore.packs.remove(pack.id) }
                        .onSuccess { toast(ctx, L("已删除「%s」").format(pack.title)); onChanged() }
                        .onFailure { toast(ctx, it.message ?: L("删除失败")) }
                    confirmDelete = null
                }) { Text(L("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

private fun togglePack(pack: ToolPack, currentlyOn: Boolean, profile: String, onChanged: () -> Unit) {
    if (currentlyOn) AppCore.profiles.deactivate(profile, pack.id)
    else AppCore.profiles.activate(profile, pack.id)
    onChanged()
}

/* ------------------------------------------------------------------------ 弹窗 */

@Composable
private fun PackNameDialog(
    title: String,
    nameLabel: String,
    nameHint: String,
    descLabel: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            MaxTvScrollView(fraction = 0.7f) {
                Column(Modifier.padding(22.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(nameLabel, fontSize = 12.sp) },
                        placeholder = { Text(nameHint, fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (descLabel != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = desc,
                            onValueChange = { desc = it },
                            label = { Text(descLabel, fontSize = 12.sp) },
                            minLines = 2,
                            maxLines = 5,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            L("取消"), Modifier.weight(1f), outlined = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                        ) { onDismiss() }
                        PillButton(
                            L("保存"), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary, compact = true
                        ) { onConfirm(name, desc) }
                    }
                }
            }
        }
    }
}

/**
 * 工具包编辑器。新建时给个自动生成的 id；已有包保持 id 不变。
 */
@Composable
private fun PackEditorDialog(
    pack: ToolPack,
    isNew: Boolean,
    allToolNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (ToolPack) -> Unit
) {
    var title by remember { mutableStateOf(pack.title) }
    var desc by remember { mutableStateOf(pack.description) }
    var toolsText by remember { mutableStateOf(pack.tools.joinToString("\n")) }
    var id by remember { mutableStateOf(if (isNew) "" else pack.id) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            MaxTvScrollView(fraction = 0.82f) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        if (isNew) L("新建工具包") else L("编辑工具包"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(L("包名"), fontSize = 12.sp) },
                        placeholder = { Text(L("例如：图片处理"), fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text(L("给 AI 看的说明"), fontSize = 12.sp) },
                        placeholder = { Text(L("写清什么时候该激活这个包"), fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = toolsText,
                        onValueChange = { toolsText = it },
                        label = { Text(L("工具名（每行一个）"), fontSize = 12.sp) },
                        minLines = 4,
                        maxLines = 12,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        L("可用工具：%s").format(allToolNames.joinToString("、")),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            L("取消"), Modifier.weight(1f), outlined = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                        ) { onDismiss() }
                        PillButton(
                            L("保存"), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary, compact = true
                        ) {
                            val names = toolsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            onSave(
                                ToolPack(
                                    id = if (isNew) autoId(title) else id,
                                    title = title.trim(),
                                    description = desc.trim(),
                                    tools = names,
                                    builtin = false
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 给新包生成一个安全的 id（用户不用关心它）。 */
private fun autoId(title: String): String {
    val base = title.trim().take(24).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
    return base.ifBlank { "pack" } + "_" + System.currentTimeMillis().toString(36).takeLast(4)
}

/** 「3 分钟前」这种相对时间，给会话列表用。 */
private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return L("还没用过")
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000L -> L("刚刚")
        d < 3_600_000L -> L("%s 分钟前").format(d / 60_000L)
        d < 86_400_000L -> L("%s 小时前").format(d / 3_600_000L)
        else -> L("%s 天前").format(d / 86_400_000L)
    }
}

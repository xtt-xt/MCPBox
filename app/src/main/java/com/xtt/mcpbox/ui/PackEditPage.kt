// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.ToolPack
import com.xtt.mcpbox.i18n.L

/**
 * 工具包编辑器（整页，不是弹窗）。
 *
 * 以前是「包名 + 说明 + 一个手输工具名的多行框 + 一大坨『可用工具：a、b、c…』的静态文字」，
 * 工具名得自己照着抄。现在改成：**搜索框 + 勾选列表** —— 勾上就进包，不用记名字。
 *
 * 页面形态跟设置里的子页一致（PageHeader + 右上返回，没有底栏），
 * 保存按钮固定在最下面，内容区自己滚，不受列表长短影响。
 */
@Composable
internal fun PackEditPage(
    ctx: Context,
    packId: String?,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val isNew = packId == null
    val existing = remember(revision, packId) {
        if (packId == null) null else AppCore.packs.all(AppCore.customTools.tools.map { it.name })
            .firstOrNull { it.id == packId }
    }
    val allTools = remember(revision) { AppCore.server.tools }
    val allNames = remember(allTools) { allTools.map { it.name } }

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var desc by remember { mutableStateOf(existing?.description.orEmpty()) }
    var query by remember { mutableStateOf("") }
    // 勾了哪些（存名字，不存下标 —— 搜索过滤后下标会变）
    var checked by remember { mutableStateOf((existing?.tools ?: emptyList()).toSet()) }

    val filtered = remember(allTools, query) {
        val q = query.trim()
        if (q.isEmpty()) allTools
        else allTools.filter {
            it.name.contains(q, true) || it.title.contains(q, true) || it.description.contains(q, true)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        PageHeader(
            title = L(if (isNew) "新建工具包" else "编辑工具包"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.padding(horizontal = 14.dp)) {
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
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(L("搜索工具名 / 说明"), fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            GroupLabel(L("工具（已选 %s / 共 %s）").format(checked.size, allTools.size))
            CardGroup(
                rows = filtered.map { spec ->
                    RowSpec(
                        title = spec.name,
                        subtitle = spec.title.takeIf { it.isNotBlank() && it != spec.name },
                        subtitleMaxLines = 1,
                        icon = null,
                        onClick = {
                            checked = if (spec.name in checked) checked - spec.name
                            else checked + spec.name
                        },
                        trailing = {
                            Checkbox(
                                checked = spec.name in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + spec.name else checked - spec.name
                                }
                            )
                        }
                    )
                }
            )
            if (filtered.isEmpty()) {
                EmptyHint(L("没有匹配的工具"))
            }
            Spacer(Modifier.height(16.dp))
        }

        // 保存：固定在底部，列表再长也不会把它顶出屏幕
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            PillButton(
                L("保存"),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary
            ) {
                if (title.isBlank()) {
                    toast(ctx, L("请先填写包名"))
                } else {
                    // 勾选的按工具列表顺序排；列表里已经没有的名字（比如自定义工具被删了）
                    // 也一并留着 —— 别因为界面上看不见就悄悄丢掉。
                    val ordered = allNames.filter { it in checked } +
                        checked.filter { it !in allNames }.sorted()
                    val saved = ToolPack(
                        id = if (isNew) autoId(title) else existing?.id.orEmpty(),
                        title = title.trim(),
                        description = desc.trim(),
                        tools = ordered,
                        builtin = false
                    )
                    runCatching {
                        if (isNew) AppCore.packs.add(saved) else AppCore.packs.update(saved)
                    }.onSuccess { pack ->
                        toast(ctx, L("已保存工具包「%s」").format(pack.title))
                        onChanged()
                        onBack()
                    }.onFailure {
                        toast(ctx, it.message ?: L("保存失败"))
                    }
                }
            }
        }
    }
}

/** 给新包生成一个安全的 id（用户不用关心它）。 */
internal fun autoId(title: String): String {
    val base = title.trim().take(24).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
    return base.ifBlank { "pack" } + "_" + System.currentTimeMillis().toString(36).takeLast(4)
}

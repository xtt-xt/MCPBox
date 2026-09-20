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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.MemoryEntity
import com.xtt.mcpbox.core.MemoryRelation

/**
 * 记忆库：给 AI 用的长期记忆（知识图谱）。
 *
 * 列表页负责浏览 / 搜索 / 按分区过滤；点进实体能改类型、分区、观察和关系。
 * AI 那边通过 create_entities / create_relations / search_nodes 等工具读写同一份数据。
 */
@Composable
fun MemoryScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf("list") }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val entering = targetState.isNotEmpty()
            val slide = if (entering) 1 else -1
            (
                slideInHorizontally(tween(300)) { w -> slide * w / 3 } + fadeIn(tween(220))
                ).togetherWith(
                slideOutHorizontally(tween(260)) { w -> -slide * w / 6 } + fadeOut(tween(180))
            )
        },
        label = "memoryPage"
    ) { current ->
        if (current.startsWith("detail:")) {
            MemoryDetailPage(
                ctx = ctx,
                name = current.removePrefix("detail:"),
                revision = revision,
                onChanged = onChanged,
                onOpen = { page = "detail:$it" },
                onBack = { page = "list" }
            )
        } else {
            MemoryListPage(
                ctx = ctx,
                revision = revision,
                onChanged = onChanged,
                onBack = onBack,
                onOpen = { page = "detail:$it" }
            )
        }
    }
}

/* ------------------------------------------------------------------ 列表页 */

@Composable
private fun MemoryListPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    BackHandler(enabled = true) { onBack() }

    val graph = remember(revision) { AppCore.memory.graph }
    val folders = remember(revision) { AppCore.memory.folders() }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }

    val list = remember(revision, query, folder) {
        graph.entities.filter { e ->
            (folder.isBlank() || e.folder == folder) &&
                (
                    query.isBlank() ||
                        e.name.contains(query, true) ||
                        e.type.contains(query, true) ||
                        e.observations.any { o -> o.contains(query, true) }
                    )
        }.sortedBy { it.name.lowercase() }
    }
    val scroll = rememberScrollState()
    // 实体卡比日志行高，一次全渲染同样会卡首帧：先渲染 60 张，快滑到底之前自动补
    val shownCount = rememberPagedCount(list.size, scroll, resetKey = query to folder)
    // 关系按实体名索引，避免每张卡都把整张关系表扫一遍
    val relOf = remember(graph) {
        val map = mutableMapOf<String, MutableList<MemoryRelation>>()
        graph.relations.forEach { r ->
            map.getOrPut(r.from) { mutableListOf() }.add(r)
            map.getOrPut(r.to) { mutableListOf() }.add(r)
        }
        map
    }

    if (showNew) {
        TextInputDialog(
            title = L("新建记忆"),
            fields = listOf(
                InputField("name", L("名称"), ""),
                InputField("type", L("类型"), L("例如：项目事实 / 用户偏好 / 事件")),
                InputField("folder", L("分区"), L("例如：projects / xtt"))
            ),
            onDismiss = { showNew = false },
            onConfirm = { values ->
                val name = values["name"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    AppCore.memory.createEntities(
                        listOf(Triple(name, values["type"]?.trim().orEmpty(), values["folder"]?.trim().orEmpty()))
                    )
                    toast(ctx, L("已新建「%s」").format(name))
                    onChanged()
                }
                showNew = false
            }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("记忆库"),
            subtitle = L("实体 %s · 关系 %s · 分区 %s")
                .format(graph.entities.size, graph.relations.size, folders.size),
            actions = {
                RoundIconButton(Icons.Filled.Add, L("新建记忆"), onClick = { showNew = true })
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack)
            }
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(L("搜索名称 / 类型 / 观察内容"), fontSize = 13.sp) },
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

        if (folders.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillButton(
                    L("全部"), outlined = folder.isNotBlank(), compact = true,
                    color = if (folder.isBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ) { folder = "" }
                folders.forEach { (name, count) ->
                    PillButton(
                        "$name $count", outlined = folder != name, compact = true,
                        color = if (folder == name) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) { folder = if (folder == name) "" else name }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (list.isEmpty()) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                EmptyHint(
                    if (graph.entities.isEmpty()) L("记忆库还是空的。AI 用 create_entities 就能往里写，也可以点右上角 + 手动加。")
                    else L("没有匹配的记忆。")
                )
            }
        } else {
            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                list.take(shownCount).forEach { e ->
                    EntityCard(e, relOf[e.name].orEmpty()) { onOpen(e.name) }
                }
            }
            PagedHint(shownCount, list.size)
        }

        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("记忆按「实体 + 观察 + 关系」组织。实体是一张卡（项目、工具、事件、偏好…），") +
                    L("观察是卡上的一条条事实，关系把两张卡连起来。同一份数据 AI 也能读写。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun EntityCard(e: MemoryEntity, relations: List<MemoryRelation>, onClick: () -> Unit) {
    // 按下底色 160ms 渐变 + primary ripple，两者都裁在 24dp 圆角里
    PressCardBox(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (e.type.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                OutlineTag(e.type, MaterialTheme.colorScheme.primary)
            }
        }

        val meta = buildString {
            append(e.folder.ifBlank { MemoryEntity.DEFAULT_FOLDER })
            append(" · ").append(L("%s 条观察").format(e.observations.size))
            if (relations.isNotEmpty()) append(" · ").append(L("%s 条关系").format(relations.size))
        }
        Text(
            meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp)
        )

        e.observations.take(3).forEach { o ->
            Text(
                "· $o",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        if (e.observations.size > 3) {
            Text(
                L("… 还有 %s 条").format(e.observations.size - 3),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (relations.isNotEmpty()) {
            Text(
                relations.take(2).joinToString("　") { r ->
                    if (r.from == e.name) "${r.type} ▸ ${r.to}" else "◂ ${r.type} ${r.from}"
                },
                color = Sem.info,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}


/* ------------------------------------------------------------------ 详情页 */

@Composable
private fun MemoryDetailPage(
    ctx: Context,
    name: String,
    revision: Int,
    onChanged: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }

    val entity = remember(revision, name) { AppCore.memory.entity(name) }
    if (entity == null) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            PageHeader(
                title = L("记忆不存在"),
                subtitle = L("它可能已经被删掉了"),
                actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
            )
        }
        return
    }
    val relations = remember(revision, name) { AppCore.memory.relationsOf(name) }
    val allNames = remember(revision) { AppCore.memory.graph.entities.map { it.name }.filter { it != name } }
    val scroll = rememberScrollState()
    // 观察可能有上百条，和日志一样分批渲染
    val obsShown = rememberPagedCount(entity.observations.size, scroll, resetKey = name)

    var editMeta by remember { mutableStateOf<String?>(null) }        // "name"/"type"/"folder"
    var addObs by remember { mutableStateOf(false) }
    var addRel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteObs by remember { mutableStateOf<String?>(null) }
    var deleteRel by remember { mutableStateOf<MemoryRelation?>(null) }

    val metaLabels = mapOf(
        "name" to L("名称"),
        "type" to L("类型"),
        "folder" to L("分区")
    )
    val metaValue = when (editMeta) {
        "name" -> entity.name
        "type" -> entity.type
        "folder" -> entity.folder
        else -> null
    }

    if (editMeta != null && metaValue != null) {
        TextInputDialog(
            title = L("修改%s").format(metaLabels[editMeta] ?: ""),
            fields = listOf(InputField("v", metaLabels[editMeta] ?: "", "")),
            initial = mapOf("v" to metaValue),
            onDismiss = { editMeta = null },
            onConfirm = { values ->
                val v = values["v"]?.trim().orEmpty()
                when (editMeta) {
                    "name" -> if (v.isNotEmpty()) AppCore.memory.updateEntity(name, newName = v)
                    "type" -> AppCore.memory.updateEntity(name, type = v)
                    "folder" -> AppCore.memory.updateEntity(name, folder = v)
                }
                val newName = if (editMeta == "name" && v.isNotEmpty()) v else name
                editMeta = null
                onChanged()
                if (newName != name) onOpen(newName)
            }
        )
    }

    if (addObs) {
        TextInputDialog(
            title = L("添加观察"),
            fields = listOf(InputField("v", L("一条事实"), L("一句话说清")),),
            single = false,
            onDismiss = { addObs = false },
            onConfirm = { values ->
                val v = values["v"]?.trim().orEmpty()
                if (v.isNotEmpty()) {
                    AppCore.memory.addObservations(name, v.lines().filter { it.isNotBlank() })
                    onChanged()
                }
                addObs = false
            }
        )
    }

    if (addRel) {
        TextInputDialog(
            title = L("新建关系"),
            fields = listOf(
                InputField("to", L("目标实体"), allNames.firstOrNull() ?: L("另一个实体的名字")),
                InputField("type", L("关系类型"), "PART_OF / INVOLVES / HAPPENS_AT …")
            ),
            single = false,
            onDismiss = { addRel = false },
            onConfirm = { values ->
                val to = values["to"]?.trim().orEmpty()
                val type = values["type"]?.trim().orEmpty().ifBlank { "RELATED_TO" }
                if (to.isNotEmpty()) {
                    AppCore.memory.createRelations(listOf(Triple(name, to, type)))
                    onChanged()
                }
                addRel = false
            }
        )
    }

    if (confirmDelete) {
        AppAlertDialog(
            title = L("删除实体"),
            text = L("确定删除「%s」吗？它的观察和 %s 条关系都会一起删掉。")
                .format(entity.name, relations.size),
            confirmText = L("删除"),
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                AppCore.memory.deleteEntities(listOf(name))
                toast(ctx, L("已删除「%s」").format(name))
                onChanged()
                onBack()
            }
        )
    }

    deleteObs?.let { obs ->
        AppAlertDialog(
            title = L("删除这条观察"),
            text = obs,
            confirmText = L("删除"),
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { deleteObs = null },
            onConfirm = { AppCore.memory.deleteObservations(name, listOf(obs)) ; onChanged() }
        )
    }

    deleteRel?.let { rel ->
        AppAlertDialog(
            title = L("删除这条关系"),
            text = "${rel.from} ──${rel.type}──▸ ${rel.to}",
            confirmText = L("删除"),
            confirmColor = MaterialTheme.colorScheme.error,
            mono = true,
            onDismiss = { deleteRel = null },
            onConfirm = { AppCore.memory.deleteRelations(listOf(Triple(rel.from, rel.to, rel.type))) ; onChanged() }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = entity.name,
            subtitle = buildString {
                append(entity.type.ifBlank { L("未分类") })
                append(" · ").append(entity.folder.ifBlank { MemoryEntity.DEFAULT_FOLDER })
            },
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("基本信息"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("名称"), subtitle = entity.name, subtitleMaxLines = 2,
                    icon = Icons.Filled.Edit, onClick = { editMeta = "name" }
                ),
                RowSpec(
                    title = L("类型"), subtitle = entity.type.ifBlank { L("未设置") }, subtitleMaxLines = 2,
                    icon = Icons.Filled.Edit, onClick = { editMeta = "type" }
                ),
                RowSpec(
                    title = L("分区"), subtitle = entity.folder.ifBlank { MemoryEntity.DEFAULT_FOLDER }, subtitleMaxLines = 2,
                    icon = Icons.Filled.Edit, onClick = { editMeta = "folder" }
                )
            )
        )

        GroupLabel(L("观察（%s）").format(entity.observations.size))
        if (entity.observations.isEmpty()) {
            Column(Modifier.padding(horizontal = 18.dp)) { EmptyHint(L("还没有观察。点下面的按钮加一条。")) }
        } else {
            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entity.observations.take(obsShown).forEach { o ->
                    val shape = RoundedCornerShape(20.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            o,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                        RoundIconButton(
                            Icons.Filled.Delete, L("删除"),
                            tint = MaterialTheme.colorScheme.error,
                            size = 34
                        ) { deleteObs = o }
                    }
                }
            }
            PagedHint(obsShown, entity.observations.size)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            PillButton(
                L("添加观察"), Modifier.fillMaxWidth(), outlined = true,
                color = MaterialTheme.colorScheme.primary, compact = true
            ) { addObs = true }
        }

        GroupLabel(L("关系（%s）").format(relations.size))
        if (relations.isEmpty()) {
            Column(Modifier.padding(horizontal = 18.dp)) { EmptyHint(L("还没有关系。用它把两条记忆连起来。")) }
        } else {
            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                relations.forEach { r ->
                    val outgoing = r.from == entity.name
                    val other = if (outgoing) r.to else r.from
                    val linked = AppCore.memory.hasEntity(other)
                    // 按下底色 160ms + primary ripple；对端不存在就点不动（也就没有反馈）
                    PressCardBox(
                        onClick = { onOpen(other) },
                        shape = RoundedCornerShape(20.dp),
                        enabled = linked,
                        contentPadding = PaddingValues(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (outgoing) "▸ " else "◂ ") + other,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    r.type,
                                    color = Sem.info,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            RoundIconButton(
                                Icons.Filled.Delete, L("删除"),
                                tint = MaterialTheme.colorScheme.error,
                                size = 34
                            ) { deleteRel = r }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            PillButton(
                L("新建关系"), Modifier.fillMaxWidth(), outlined = true,
                color = MaterialTheme.colorScheme.primary, compact = true
            ) { addRel = true }
        }

        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            PillButton(
                L("删除这个实体"),
                Modifier.fillMaxWidth(),
                outlined = true,
                color = MaterialTheme.colorScheme.error
            ) { confirmDelete = true }
        }
    }
}

/* -------------------------------------------------------------------- 弹窗 */

private data class InputField(val key: String, val label: String, val hint: String)

/**
 * 通用文本输入弹窗。多字段时纵向排列。
 * 正文区单独限高（屏高 62%）+ 可滚动，**按钮固定在限高区外** —— 字段再多也顶不掉按钮。
 * 进出场走 AppDialog：进入 300ms 减速 / 退出 150ms 加速。
 */
@Composable
private fun TextInputDialog(
    title: String,
    fields: List<InputField>,
    initial: Map<String, String> = emptyMap(),
    single: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit
) {
    val values = remember {
        mutableStateOf(fields.associate { it.key to (initial[it.key] ?: "") })
    }

    AppDialog(onDismiss = onDismiss) { close ->
        Column {
            MaxTvScrollView(fraction = 0.62f) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(14.dp))
                    fields.forEachIndexed { i, f ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = values.value[f.key] ?: "",
                            onValueChange = { v -> values.value = values.value + (f.key to v) },
                            label = { Text(f.label, fontSize = 12.sp) },
                            placeholder = { Text(f.hint, fontSize = 12.sp) },
                            singleLine = single || f.key != "v",
                            minLines = if (single) 1 else 3,
                            maxLines = if (single) 1 else 8,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        L("取消"), Modifier.weight(1f), outlined = true,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                    ) { close() }
                    PillButton(
                        L("保存"), Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary, compact = true
                    ) {
                        close()
                        onConfirm(values.value)
                    }
                }
            }
        }
    }
}

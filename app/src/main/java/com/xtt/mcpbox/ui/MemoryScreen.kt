// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.Backup
import com.xtt.mcpbox.core.MemoryLink
import com.xtt.mcpbox.core.MemoryNote
import kotlinx.coroutines.launch

/** 记忆列表一次渲染多少张卡。 */
private const val MEMORY_PAGE = 20

/**
 * 记忆库：每条记忆就是一个 Markdown 文件。
 *
 * 列表页负责浏览 / 搜索 / 按分区筛选；点进去整篇编辑（名字、分区、简介、类型、正文、关联）。
 * AI 那边通过 memory_list / memory_read / memory_write 等工具读写同一批文件。
 *
 * 根目录下还有旧版的 graph.json 时，整页只显示一条迁移横幅 —— 迁移完成前记忆功能不可用。
 */
@Composable
fun MemoryScreen(
    ctx: Context,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    // AI 从外面写过记忆时，这一页要跟着刷新 —— 光靠设置栈的 revision 不够
    var liveRev by remember { mutableStateOf(AppCore.memory.revision) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l = { liveRev = AppCore.memory.revision }
        AppCore.memory.addListener(l)
        onDispose { AppCore.memory.removeListener(l) }
    }
    val revision = liveRev.toInt()

    var page by rememberSaveable { mutableStateOf("list") }
    var showMenu by remember { mutableStateOf(false) }
    var showMigrate by remember { mutableStateOf(false) }
    // 待导入的内容（先读文件，再选合并 / 覆盖）
    var pendingImport by remember { mutableStateOf<ImportPayload?>(null) }

    val legacy = remember(revision) { AppCore.memory.hasLegacy }
    val backups = remember(revision, legacy) { AppCore.memory.legacyBackups() }

    // ---------------------------------------------------------------- 导入导出

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                Backup.zip(
                    parts = mapOf(Backup.Part.MEMORY to AppCore.memory.exportManifest()),
                    includeToken = false,
                    memoryFiles = AppCore.memory.filesSnapshot()
                )
            }.getOrNull()
            val ok = bytes != null && runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            toast(
                ctx,
                if (ok) L("已导出 %s 条记忆").format(AppCore.memory.notes.size) else L("导出失败")
            )
        }
    }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                toast(ctx, L("读不到文件内容"))
            } else {
                pendingImport = runCatching { parseImport(bytes) }.getOrNull()
                if (pendingImport == null) toast(ctx, L("不是记忆库备份"))
            }
        }
    }

    val legacyExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val latest = AppCore.memory.legacyBackups().firstOrNull()
            val text = latest?.let { runCatching { it.readText() }.getOrNull() }
            val ok = text != null && runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            toast(ctx, if (ok) L("已导出最后旧版数据库") else L("导出失败"))
        }
    }

    // ---------------------------------------------------------------- 弹窗

    if (showMenu) {
        val options = buildList {
            add(L("导出全部记忆"))
            add(L("从文件导入"))
            if (backups.isNotEmpty()) add(L("导出最后旧版数据库"))
        }
        ChoiceDialog(
            title = L("记忆库"),
            options = options,
            selected = -1,
            onDismiss = { showMenu = false },
            onSelect = { index ->
                showMenu = false
                when {
                    index == 0 -> exportLauncher.launch("mcpbox-memory-${memoryStamp()}.zip")
                    index == 1 -> importLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                    index == 2 -> legacyExportLauncher.launch("mcpbox-memory-legacy-${memoryStamp()}.json")
                }
            }
        )
    }

    pendingImport?.let { payload ->
        ChoiceDialog(
            title = L("导入方式"),
            options = listOf(L("合并：保留现有内容"), L("覆盖：清空后整份替换")),
            selected = 0,
            onDismiss = { pendingImport = null },
            onSelect = { index ->
                pendingImport = null
                runCatching {
                    val r = if (payload.files.isNotEmpty()) {
                        AppCore.memory.importFiles(payload.files, replace = index == 1)
                    } else {
                        AppCore.memory.importLegacyJson(payload.legacyJson.orEmpty(), replace = index == 1)
                    }
                    toast(ctx, r.describe())
                    onChanged()
                }.onFailure { toast(ctx, L("导入失败：%s").format(it.message ?: "")) }
            }
        )
    }

    // 旧版数据：页内横幅点开时复用同一个迁移窗（App 启动时也会自动弹一次）
    MemoryMigrateWindow(
        visible = showMigrate && legacy,
        onDismiss = { showMigrate = false },
        onMigrated = { showMigrate = false; onChanged() }
    )

    // ---------------------------------------------------------------- 页栈

    val listScroll = rememberScrollState()
    var listQuery by rememberSaveable { mutableStateOf("") }
    var listFolder by rememberSaveable { mutableStateOf("") }

    // 编辑页返回 = 回预览（新建则回列表）
    fun backFromPage() {
        page = if (page.startsWith("edit:") && page.removePrefix("edit:").isNotEmpty()) {
            "detail:" + page.removePrefix("edit:")
        } else "list"
    }

    // 从编辑页 / 新建页按返回时，背后露出来的应该是预览页
    val behindEdit = page.startsWith("edit:") && page.removePrefix("edit:").isNotEmpty()

    PredictiveBackBox(
        onBack = { backFromPage() },
        handleBack = page != "list",
        follow = page != "list" && AppCore.prefs.predictiveBack,
        behind = {
            if (behindEdit) MemoryPreviewPage(
                ctx = ctx,
                name = page.removePrefix("edit:"),
                revision = revision,
                onChanged = onChanged,
                onEdit = {},
                onOpen = { page = "detail:$it" },
                onBack = { page = "list" }
            ) else MemoryListPage(
                scroll = listScroll,
                query = listQuery,
                onQuery = { listQuery = it },
                folder = listFolder,
                onFolder = { listFolder = it },
                ctx = ctx,
                revision = revision,
                onChanged = onChanged,
                onBack = onBack,
                onOpen = { page = "detail:$it" },
                onNew = { page = "new" },
                onMore = { showMenu = true },
                legacy = legacy,
                onMigrate = { showMigrate = true }
            )
            }
    ) {
        val commitDrag = LocalPredictiveCommitDrag.current
        val noOverlap = rememberTransparentPages()
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                pageSlide(
                    entering = pageForward(targetState, initialState) {
                        // 列表 0、预览 1、编辑 2 —— 编辑页是预览的子级，
                        // 不分开的话「编辑完返回」会因为两边同为 1 被判成前进，又播一次进入动画
                        when {
                            it == "list" -> 0
                            it.startsWith("edit:") || it == "new" -> 2
                            else -> 1
                        }
                    },
                    commitDrag = commitDrag,
                    noOverlap = noOverlap
                )
            },
            label = "memoryPage"
        ) { current ->
            PageSurface(sub = true) {
                when {
                    current == "new" -> MemoryEditPage(
                        ctx = ctx,
                        name = null,
                        revision = revision,
                        onChanged = onChanged,
                        onOpen = { page = "detail:$it" },
                        onBack = { page = "list" },
                        onDone = { page = "detail:$it" }
                    )

                    current.startsWith("detail:") -> MemoryPreviewPage(
                        ctx = ctx,
                        name = current.removePrefix("detail:"),
                        revision = revision,
                        onChanged = onChanged,
                        onEdit = { page = "edit:" + current.removePrefix("detail:") },
                        onOpen = { page = "detail:$it" },
                        onBack = { page = "list" }
                    )

                    current.startsWith("edit:") -> MemoryEditPage(
                        ctx = ctx,
                        name = current.removePrefix("edit:"),
                        revision = revision,
                        onChanged = onChanged,
                        onOpen = { page = "detail:$it" },
                        onBack = { page = "detail:" + current.removePrefix("edit:") },
                        onDone = { page = "detail:$it" }
                    )

                    else -> MemoryListPage(
                        scroll = listScroll,
                        query = listQuery,
                        onQuery = { listQuery = it },
                        folder = listFolder,
                        onFolder = { listFolder = it },
                        ctx = ctx,
                        revision = revision,
                        onChanged = onChanged,
                        onBack = onBack,
                        onOpen = { page = "detail:$it" },
                        onNew = { page = "new" },
                        onMore = { showMenu = true },
                        legacy = legacy,
                        onMigrate = { showMigrate = true }
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 列表页 */

@Composable
private fun MemoryListPage(
    scroll: ScrollState,
    query: String,
    onQuery: (String) -> Unit,
    folder: String,
    onFolder: (String) -> Unit,
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onMore: () -> Unit,
    legacy: Boolean,
    onMigrate: () -> Unit
) {
    val all = remember(revision) { AppCore.memory.notes }
    val folders = remember(revision) { AppCore.memory.folders() }

    val list = remember(revision, query, folder) {
        all.filter { n ->
            (folder.isBlank() || n.folderLabel == folder) &&
                (
                    query.isBlank() ||
                        n.name.contains(query, true) ||
                        n.description.contains(query, true) ||
                        n.type.contains(query, true) ||
                        n.body.contains(query, true)
                    )
        }
    }
    val shownCount = rememberPagedCount(
        list.size, scroll,
        resetKey = query to folder,
        initial = MEMORY_PAGE,
        step = MEMORY_PAGE,
        preloadScreens = 0.5f
    )

    PageScaffold(
        scroll = scroll,
        title = L("记忆库"),
        onBack = onBack,
        bottomPadding = 24.dp,
        actions = {
            RoundIconButton(Icons.Filled.Add, L("新建记忆"), onClick = onNew)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Filled.MoreVert, L("导入导出"), onClick = onMore)
        }
    ) {
        Spacer(Modifier.height(10.dp))

        if (legacy) {
            // 旧版数据：整页只剩这一条横幅，迁移之前内容一律不显示
            Column(Modifier.padding(horizontal = 14.dp)) {
                LegacyBanner(onClick = onMigrate)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 18.dp)) {
                EmptyHint(L("记忆库还在旧版格式，迁移完成后这里才会显示内容。"))
            }
            return@PageScaffold
        }

        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(L("搜索名字 / 简介 / 正文"), fontSize = 13.sp) },
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
            val names = listOf("") + folders.map { it.first }
            val labels = listOf(L("全部") + " ${all.size}") + folders.map { "${it.first} ${it.second}" }
            val selected = names.indexOfFirst { it == folder }.coerceAtLeast(0)
            CategoryPillRow(
                items = labels,
                selected = selected,
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) { index ->
                onFolder(if (index == selected && index > 0) "" else names[index])
            }
        }

        Spacer(Modifier.height(14.dp))

        if (list.isEmpty()) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                EmptyHint(
                    if (all.isEmpty()) L("记忆库还是空的。AI 用 memory_write 就能往里写，也可以点右上角 + 手动新建。")
                    else L("没有匹配的记忆。")
                )
            }
        } else {
            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                list.take(shownCount).forEach { n ->
                    NoteCard(n, revision) { onOpen(n.name) }
                }
            }
            PagedHint(shownCount, list.size)
        }

        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("每条记忆就是一个 Markdown 文件，放在记忆目录下的分区文件夹里。AI 读写的是同一批文件，改了哪篇就是改了哪个文件。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun LegacyBanner(onClick: () -> Unit) {
    PressCardBox(onClick = onClick) {
        Text(
            L("更新新版记忆数据库"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            L("检测到旧版记忆数据。点这里完成迁移，迁移后才能继续读写记忆。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun NoteCard(n: MemoryNote, revision: Int, onClick: () -> Unit) {
    val edgeCount = remember(revision, n.name) { AppCore.memory.edgesOf(n.name).size }
    PressCardBox(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (n.type.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                OutlineTag(n.type, MaterialTheme.colorScheme.primary)
            }
        }
        if (n.description.isNotBlank()) {
            Text(
                n.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        val meta = buildString {
            append(n.folderLabel)
            if (edgeCount > 0) append(" · ").append(L("%s 条关联").format(edgeCount))
        }
        Text(
            meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/* ------------------------------------------------------------------ 预览页 */

/**
 * 记忆详情：默认只读预览。
 *
 * 正文按 Markdown 渲染，关联列在下方可以点进去；要改内容点右上角的笔。
 */
@Composable
private fun MemoryPreviewPage(
    ctx: Context,
    name: String,
    revision: Int,
    onChanged: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    val note = remember(revision, name) { AppCore.memory.note(name) }
    if (note == null) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            PageHeader(title = L("记忆不存在"), onBack = onBack)
        }
        return
    }
    val edges = remember(revision, name) { AppCore.memory.edgesOf(name) }
    val scroll = rememberScrollState()

    PageScaffold(
        scroll = scroll,
        title = note.name,
        subtitle = "/" + (if (note.folder.isBlank()) "" else note.folder + "/") + note.name + ".md",
        onBack = onBack,
        bottomPadding = 28.dp,
        actions = {
            RoundIconButton(Icons.Filled.Edit, L("编辑"), onClick = onEdit)
        }
    ) {
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分区 · 类型
            if (note.type.isNotBlank() || note.folder.isNotBlank()) {
                MarkdownInfoCard(
                    label = L("分区"),
                    value = listOf(note.folderLabel, note.type).filter { it.isNotBlank() }.joinToString(" · ")
                )
            }
            if (note.description.isNotBlank()) {
                MarkdownInfoCard(label = L("简介"), value = note.description)
            }

            if (note.body.isBlank()) {
                Surface(
                    color = chromeColor(MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        L("正文为空。点右上角的笔写点什么。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    )
                }
            } else {
                MarkdownCard(note.body)
            }

            // 关联
            if (edges.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    L("关联 %s 条").format(edges.size),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                edges.forEach { e ->
                    val peer = if (e.outgoing) e.to else e.from
                    PressCardBox(onClick = { if (e.peerExists) onOpen(peer) else onEdit() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (e.outgoing) e.type else "← " + e.type,
                                color = if (e.outgoing) Sem.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                peer,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (e.peerDescription.isNotBlank()) {
                            Text(
                                e.peerDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        } else if (!e.peerExists) {
                            Text(
                                L("对方还没有记忆"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 编辑页 */

@Composable
private fun MemoryEditPage(
    ctx: Context,
    name: String?,
    revision: Int,
    onChanged: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onDone: (String) -> Unit
) {
    val note = remember(revision, name) { name?.let { AppCore.memory.note(it) } }
    if (name != null && note == null) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            PageHeader(title = L("记忆不存在"), onBack = onBack)
        }
        return
    }

    val folders = remember(revision) { AppCore.memory.folders().map { it.first } }
    val allNames = remember(revision, name) {
        AppCore.memory.notes.map { it.name }.filter { it != name }
    }
    val edges = remember(revision, name) { name?.let { AppCore.memory.edgesOf(it) }.orEmpty() }

    var nameText by remember(name) { mutableStateOf(note?.name.orEmpty()) }
    var folderText by remember(name) { mutableStateOf(note?.folder.orEmpty()) }
    var descText by remember(name) { mutableStateOf(note?.description.orEmpty()) }
    var typeText by remember(name) { mutableStateOf(note?.type.orEmpty()) }
    var bodyText by remember(name) { mutableStateOf(note?.body.orEmpty()) }
    var links by remember(name) { mutableStateOf(note?.links.orEmpty()) }

    var addLink by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun save() {
        val finalName = nameText.trim()
        if (finalName.isEmpty()) {
            toast(ctx, L("名字不能为空"))
            return
        }
        val r = runCatching {
            AppCore.memory.write(
                name = finalName,
                folder = folderText.trim(),
                description = descText.trim(),
                type = typeText.trim(),
                body = bodyText,
                links = links,
                oldName = name
            )
        }.fold(
            onSuccess = { it },
            onFailure = {
                toast(ctx, L("保存失败：%s").format(it.message ?: ""))
                return
            }
        )
        onChanged()
        toast(ctx, if (r.created) L("已新建「%s」").format(r.name) else L("已保存「%s」").format(r.name))
        onDone(r.name)
    }

    if (addLink) {
        TextInputDialog(
            title = L("添加关联"),
            fields = listOf(
                InputField("to", L("对方记忆"), allNames.firstOrNull() ?: L("对方的名字")),
                InputField("type", L("关系类型"), L("例如：属于 / 依赖 / 相关"))
            ),
            onDismiss = { addLink = false },
            onConfirm = { values ->
                val to = values["to"]?.trim().orEmpty()
                val type = values["type"]?.trim().orEmpty().ifBlank { L("相关") }
                if (to.isNotEmpty() && to != nameText.trim()) {
                    links = links.filterNot { it.to == to } + MemoryLink(to, type)
                }
                addLink = false
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("删除这条记忆？"), fontSize = 20.sp) },
            text = {
                Text(
                    L("「%s」会被删除，指向它的关联也会从别的记忆里去掉。这一步不能撤销。").format(nameText),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    AppCore.memory.delete(listOf(nameText))
                    onChanged()
                    toast(ctx, L("已删除「%s」").format(nameText))
                    onBack()
                }) { Text(L("删除"), color = Sem.bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    PageScaffold(
        scroll = scroll,
        title = if (name == null) L("新建记忆") else L("编辑记忆"),
        onBack = onBack,
        bottomPadding = 28.dp,
        actions = {
            if (name != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, L("删除"), tint = Sem.bad, modifier = Modifier.size(20.dp))
                }
            }
        }
    ) {
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text(L("名字"), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = folderText,
                onValueChange = { folderText = it },
                label = { Text(L("分区"), fontSize = 12.sp) },
                placeholder = { Text(folders.firstOrNull().orEmpty(), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (folders.isNotEmpty()) {
                CategoryPillRow(
                    items = listOf(L("未分类")) + folders,
                    selected = if (folderText.isBlank()) 0 else folders.indexOf(folderText) + 1,
                    contentPadding = PaddingValues(0.dp)
                ) { index -> folderText = if (index == 0) "" else folders[index - 1] }
            }
            OutlinedTextField(
                value = descText,
                onValueChange = { descText = it },
                label = { Text(L("简介"), fontSize = 12.sp) },
                placeholder = { Text(L("一句话说清这条记忆是什么"), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = typeText,
                onValueChange = { typeText = it },
                label = { Text(L("类型"), fontSize = 12.sp) },
                placeholder = { Text(L("例如：项目 / 人物 / 偏好"), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bodyText,
                onValueChange = { bodyText = it },
                label = { Text(L("正文"), fontSize = 12.sp) },
                placeholder = { Text(L("正文，支持 Markdown"), fontSize = 12.sp) },
                minLines = 8,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ------------------------------------------------------------ 关联
        Spacer(Modifier.height(18.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("关联"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { addLink = true }) {
                    Icon(Icons.Filled.Add, L("添加关联"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            if (edges.isEmpty()) {
                Text(
                    L("还没有关联。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                edges.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (e.outgoing) "${e.type} → ${e.to}" else "← ${e.type} ${e.from}",
                                color = if (e.outgoing) Sem.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                            if (e.peerDescription.isNotBlank()) {
                                Text(
                                    e.peerDescription,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (e.outgoing) {
                            IconButton(onClick = {
                                links = links.filterNot { it.to == e.to && it.type == e.type }
                            }) {
                                Icon(
                                    Icons.Filled.Close, L("移除关联"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 保存
        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            PillButton(
                L("保存"),
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            ) { save() }
            Spacer(Modifier.height(8.dp))
            Text(
                L("保存后写回对应的 Markdown 文件。改名会同时把文件挪过去，指向旧名字的关联也会自动跟上。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/* ------------------------------------------------------------------ 辅助 */

private data class ImportPayload(
    /** zip 里 memory/files/ 下的记忆文件（相对路径 → 文本）。 */
    val files: Map<String, String> = emptyMap(),
    /** 旧版 graph.json 的原文。 */
    val legacyJson: String? = null
)

private data class InputField(val key: String, val label: String, val hint: String)

/** 从上传的文件里解析出导入内容：优先当 zip 备份，其次当旧版 JSON。 */
private fun parseImport(bytes: ByteArray): ImportPayload? {
    // zip 以 "PK\u0003\u0004" 开头
    if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
        val bundle = runCatching { Backup.readZip(bytes) }.getOrNull() ?: return null
        val partText = bundle.parts[Backup.Part.MEMORY]
        if (bundle.memoryFiles.isNotEmpty()) return ImportPayload(files = bundle.memoryFiles)
        if (partText != null) return ImportPayload(legacyJson = partText)
        return null
    }
    val text = bytes.toString(Charsets.UTF_8)
    if (!text.contains("\"entities\"") && !text.contains("\"relations\"")) return null
    return ImportPayload(legacyJson = text)
}

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

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            MaxTvScrollView(fraction = 0.78f) {
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
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            L("取消"), Modifier.weight(1f), outlined = true,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                        ) { onDismiss() }
                        PillButton(
                            L("保存"), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary, compact = true
                        ) { onConfirm(values.value) }
                    }
                }
            }
        }
    }
}

/** 导出文件名里的时间戳，如 20260928-2019。 */
private fun memoryStamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.Backup

/**
 * 备份与恢复。
 *
 * 两个子页：
 *  - 首页两张卡：备份 / 恢复备份
 *  - 备份子页：勾选要备份的部分（记忆 / 设置 / 自定义工具），导出 zip 或单独导出
 *
 * 恢复流程分两步（弹窗都在首页上）：
 *  ① 选要恢复哪些部分 —— zip 与多文件走这一步，单文件直接跳 ②
 *  ② 每部分各自选模式（合并 / 覆盖）
 */
@Composable
fun BackupScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onBack: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(HOME) }

    BackHandler(enabled = true) {
        if (page == CREATE) page = HOME else onBack()
    }

    val pages = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val entering = targetState == CREATE
            val slide = if (entering) 1 else -1
            (
                slideInHorizontally(tween(300)) { w -> slide * w / 3 } + fadeIn(tween(220))
                ).togetherWith(
                slideOutHorizontally(tween(260)) { w -> -slide * w / 6 } + fadeOut(tween(180))
            )
        },
        label = "backupPage"
    ) { current ->
        pages.SaveableStateProvider(current) {
            if (current == CREATE) {
                BackupCreatePage(
                    revision = revision,
                    ctx = ctx,
                    onBack = { page = HOME }
                )
            } else {
                BackupHomePage(
                    ctx = ctx,
                    revision = revision,
                    onChanged = onChanged,
                    onThemeChanged = onThemeChanged,
                    onLangChanged = onLangChanged,
                    onOpenCreate = { page = CREATE },
                    onBack = onBack
                )
            }
        }
    }
}

private const val HOME = "home"
private const val CREATE = "create"

/* ------------------------------------------------------------------ 首页 */

/** 待恢复的一项：一块内容 + 用户选好的模式。 */
private data class RestoreItem(
    val part: Backup.Part,
    val text: String,
    val selected: Boolean = true,
    val merge: Boolean = true,
    /** 设置专有：要不要连访问令牌一起恢复。 */
    val restoreToken: Boolean = false
)

@Composable
private fun BackupHomePage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onOpenCreate: () -> Unit,
    onBack: () -> Unit
) {
    var showPick by remember { mutableStateOf(false) }
    // 0 = 不在恢复流程里；1 = 选部分；2 = 选模式
    var stage by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<RestoreItem>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }

    val memoryCount = remember(revision) { AppCore.memory.graph.entities.size }
    val relationCount = remember(revision) { AppCore.memory.graph.relations.size }
    val toolCount = remember(revision) { AppCore.customTools.tools.size }
    val settingCount = remember(revision) { AppCore.prefs.all().size }

    fun startFlow(loaded: List<RestoreItem>, single: Boolean) {
        if (loaded.isEmpty()) {
            toast(ctx, L("没有能识别的备份内容"))
            return
        }
        items = loaded
        stage = if (single && loaded.size == 1) 2 else 1
    }

    // 选 zip
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                toast(ctx, L("读不到文件内容"))
            } else {
                runCatching { Backup.readZip(bytes) }.fold(
                    onSuccess = { bundle ->
                        startFlow(
                            bundle.parts.map { (part, text) ->
                                RestoreItem(part, text, restoreToken = Backup.settingsHasToken(text))
                            },
                            single = false
                        )
                    },
                    onFailure = { toast(ctx, L("不是合法的备份包：%s").format(it.message ?: "")) }
                )
            }
        }
    }

    // 选文件（可多选）
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val loaded = uris.mapNotNull { u ->
                val text = runCatching {
                    ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val part = text?.let { Backup.sniff(it) }
                if (text == null || part == null) null
                else RestoreItem(part, text, restoreToken = Backup.settingsHasToken(text))
            }
            startFlow(loaded, single = uris.size == 1)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("备份与恢复"),
            subtitle = L("把记忆、设置和自定义工具打包带走，或者从备份里挑着恢复"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("操作"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("备份"),
                    subtitle = L("选要备份的内容，导出成一个 zip 或单独的文件"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Share,
                    onClick = onOpenCreate,
                    trailing = { Chevron1() }
                ),
                RowSpec(
                    title = L("恢复备份"),
                    subtitle = L("从 zip 备份或单独的文件恢复，可挑部分、可逐项选合并还是覆盖"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    onClick = { showPick = true }
                )
            )
        )

        GroupLabel(L("当前内容"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("记忆库"),
                    subtitle = L("%s 个实体 · %s 条关系").format(memoryCount, relationCount),
                    icon = Icons.Filled.Star,
                    trailing = { OutlineTag(L("%s 个实体").format(memoryCount), MaterialTheme.colorScheme.primary) }
                ),
                RowSpec(
                    title = L("设置"),
                    subtitle = L("端口、权限、外观、超时等全部配置项"),
                    icon = Icons.Filled.Lock,
                    trailing = { OutlineTag(L("%s 项").format(settingCount), MaterialTheme.colorScheme.primary) }
                ),
                RowSpec(
                    title = L("自定义工具"),
                    subtitle = if (toolCount == 0) L("还没有自定义工具") else L("你在 App 里造的工具"),
                    icon = Icons.Filled.Build,
                    trailing = { OutlineTag(L("%s 个").format(toolCount), MaterialTheme.colorScheme.primary) }
                )
            )
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("zip 里是一个 manifest.json 加各个部分的 json；单独导出的文件也自带类型标记，恢复时能认出来。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                L("合并：只动备份里提到的内容，现有的一律保留。覆盖：先把这一部分清空再写入，不能撤销。"),
                color = Sem.warn,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        // 恢复结果直接留在页面上，不用 toast 一闪而过
        result?.let { text ->
            Spacer(Modifier.height(10.dp))
            CardColumn {
                CardBox {
                    Text(L("上次恢复结果"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, lineHeight = 19.sp)
                }
            }
        }
    }

    // ------------------------------------------------- 选「从 zip 还是从文件」
    if (showPick) {
        AlertDialog(
            onDismissRequest = { showPick = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("从哪里恢复？"), fontSize = 20.sp) },
            text = {
                Text(
                    L("zip 备份里可能装着多部分内容，导入后可以先挑要恢复哪些；单独的文件可以一次选多个。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPick = false
                    result = null
                    zipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text(L("选 zip 备份"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPick = false
                    result = null
                    fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) { Text(L("选单独文件"), color = MaterialTheme.colorScheme.primary) }
            }
        )
    }

    // -------------------------------------------------------------- 第一步
    if (stage == 1) {
        PartPickerDialog(
            items = items,
            onToggle = { index ->
                items = items.mapIndexed { i, it -> if (i == index) it.copy(selected = !it.selected) else it }
            },
            onDismiss = { stage = 0; items = emptyList() },
            onNext = {
                val picked = items.filter { it.selected }
                if (picked.isEmpty()) {
                    toast(ctx, L("至少要选一部分"))
                } else {
                    items = picked
                    stage = 2
                }
            }
        )
    }

    // -------------------------------------------------------------- 第二步
    if (stage == 2) {
        ModePickerDialog(
            items = items,
            onToggleMode = { index ->
                items = items.mapIndexed { i, it -> if (i == index) it.copy(merge = !it.merge) else it }
            },
            onToggleToken = { index ->
                items = items.mapIndexed { i, it ->
                    if (i == index) it.copy(restoreToken = !it.restoreToken) else it
                }
            },
            onDismiss = { stage = 0; items = emptyList() },
            onRun = {
                val lines = items.map { item ->
                    runCatching {
                        Backup.apply(
                            part = item.part,
                            text = item.text,
                            mode = if (item.merge) Backup.Mode.MERGE else Backup.Mode.REPLACE,
                            restoreToken = item.restoreToken,
                            settings = AppCore.prefs,
                            memory = AppCore.memory,
                            customTools = AppCore.customTools
                        )
                    }.fold(
                        onSuccess = { L("%s：%s").format(partLabel(item.part), it) },
                        onFailure = {
                            L("%s：失败 —— %s").format(
                                partLabel(item.part),
                                it.message ?: L("不是合法的备份内容")
                            )
                        }
                    )
                }
                val touchedSettings = items.any { it.part == Backup.Part.SETTINGS }
                result = lines.joinToString("\n") +
                    if (touchedSettings)
                        "\n" + L("外观 / 语言的改动已经生效；端口或监听目录变了的话，记得去「后台与运行」重启一次服务器。")
                    else ""
                stage = 0
                items = emptyList()
                // 设置可能被整份换掉：核心配置、权限、自定义工具都得重读
                AppCore.config.reload()
                AppCore.permissions.load()
                AppCore.customTools.load()
                AppCore.log.enabled = AppCore.config.logEnabled
                // 主题 / 语言 / 彩蛋语言开关都可能在备份里，重建整棵树才对得上
                onThemeChanged()
                onLangChanged()
                onChanged()
            }
        )
    }
}

/* ------------------------------------------------------------ 第一步弹窗 */

@Composable
private fun PartPickerDialog(
    items: List<RestoreItem>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
    onNext: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(L("要恢复哪些部分？"), fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    L("取消勾选的部分不会被写入。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChoiceChip(
                            text = if (item.selected) "✓" else "",
                            active = item.selected,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = { onToggle(index) }
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                partLabel(item.part),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.5.sp
                            )
                            Text(
                                partSummary(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNext) {
                Text(L("下一步"), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/* ------------------------------------------------------------ 第二步弹窗 */

@Composable
private fun ModePickerDialog(
    items: List<RestoreItem>,
    onToggleMode: (Int) -> Unit,
    onToggleToken: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(L("每一部分怎么恢复？"), fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    L("点右侧的胶囊切换合并 / 覆盖。覆盖会先清空这一部分，不能撤销。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                items.forEachIndexed { index, item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    partLabel(item.part),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.5.sp
                                )
                                Text(
                                    partSummary(item),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            ChoiceChip(
                                text = L(if (item.merge) "合并" else "覆盖"),
                                active = !item.merge,
                                color = if (item.merge) MaterialTheme.colorScheme.primary else Sem.warn,
                                onClick = { onToggleMode(index) }
                            )
                        }
                        // 设置部分带着令牌时多给一个开关：默认不恢复，免得把客户端全踢下线
                        if (item.part == Backup.Part.SETTINGS && Backup.settingsHasToken(item.text)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 2.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppSwitch(item.restoreToken) { onToggleToken(index) }
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    L(
                                        if (item.restoreToken)
                                            "连访问令牌一起恢复（客户端要用备份里那个）"
                                        else "保留当前访问令牌（推荐，客户端不用动）"
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRun) {
                Text(L("开始恢复"), color = Sem.warn)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/** 里头就翻译好：调用处直接拼字符串，不用再包一层 L(...)。 */
private fun partLabel(part: Backup.Part): String = when (part) {
    Backup.Part.MEMORY -> L("记忆库")
    Backup.Part.SETTINGS -> L("设置")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
}

/** 一行摘要：设置报含不含令牌，其它报个大概。 */
private fun partSummary(item: RestoreItem): String = when (item.part) {
    Backup.Part.MEMORY -> L("实体 / 观察 / 关系的完整内容")
    Backup.Part.SETTINGS ->
        if (Backup.settingsHasToken(item.text)) L("含访问令牌") else L("不含访问令牌")
    Backup.Part.CUSTOM_TOOLS -> L("命令模板 + 参数定义")
}

/* ---------------------------------------------------------------- 备份子页 */

@Composable
private fun BackupCreatePage(
    revision: Int,
    ctx: Context,
    onBack: () -> Unit
) {
    var pickMemory by rememberSaveable { mutableStateOf(true) }
    var pickSettings by rememberSaveable { mutableStateOf(true) }
    var pickTools by rememberSaveable { mutableStateOf(true) }
    var includeToken by rememberSaveable { mutableStateOf(true) }
    // 单独导出：先记住导哪一部分，等系统文件选择器回来再写
    var singlePart by remember { mutableStateOf<Backup.Part?>(null) }

    val memoryCount = remember(revision) { AppCore.memory.graph.entities.size }
    val relationCount = remember(revision) { AppCore.memory.graph.relations.size }
    val toolCount = remember(revision) { AppCore.customTools.tools.size }

    val chosen = buildList {
        if (pickMemory) add(Backup.Part.MEMORY)
        if (pickSettings) add(Backup.Part.SETTINGS)
        if (pickTools) add(Backup.Part.CUSTOM_TOOLS)
    }

    fun textOf(part: Backup.Part): String = when (part) {
        Backup.Part.MEMORY -> AppCore.memory.exportJson()
        Backup.Part.SETTINGS -> Backup.exportSettings(AppCore.prefs, includeToken)
        Backup.Part.CUSTOM_TOOLS -> AppCore.customTools.exportJson()
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val bytes = Backup.zip(
                    parts = chosen.associateWith { textOf(it) },
                    includeToken = includeToken && pickSettings
                )
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            toast(ctx, if (ok) L("已导出 %s 个部分").format(chosen.size) else L("导出失败"))
        }
    }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val part = singlePart
        singlePart = null
        if (uri != null && part != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(textOf(part).toByteArray()) }
            }.isSuccess
            toast(ctx, if (ok) L("已导出%s").format(partLabel(part)) else L("导出失败"))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("备份"),
            subtitle = L("勾选要备份的内容，再选导出成一个 zip 还是分开导"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("要备份的内容"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("记忆库"),
                    subtitle = L("%s 个实体 · %s 条关系").format(memoryCount, relationCount),
                    icon = Icons.Filled.Star,
                    onClick = { pickMemory = !pickMemory },
                    trailing = { CheckMark(pickMemory) }
                ),
                RowSpec(
                    title = L("设置"),
                    subtitle = L("端口、权限、外观、超时等全部配置项"),
                    icon = Icons.Filled.Lock,
                    onClick = { pickSettings = !pickSettings },
                    trailing = { CheckMark(pickSettings) }
                ),
                // 令牌单独可控：备份文件有可能被分享出去
                RowSpec(
                    title = L("包含访问令牌"),
                    subtitle = if (includeToken)
                        L("备份里带着令牌，恢复后客户端不用改地址；文件别随便分享")
                    else L("备份里不带令牌，恢复时会保留当前令牌"),
                    subtitleMaxLines = 2,
                    subtitleColor = if (includeToken) Sem.warn else null,
                    icon = Icons.Filled.Lock,
                    visible = pickSettings,
                    onClick = { includeToken = !includeToken },
                    trailing = { CheckMark(includeToken) }
                ),
                RowSpec(
                    title = L("自定义工具"),
                    subtitle = if (toolCount == 0) L("还没有自定义工具") else L("%s 个工具").format(toolCount),
                    icon = Icons.Filled.Build,
                    onClick = { pickTools = !pickTools },
                    trailing = { CheckMark(pickTools) }
                )
            )
        )

        Spacer(Modifier.height(10.dp))
        CardColumn {
            PillButton(
                if (chosen.isEmpty()) L("先勾选要备份的内容")
                else L("导出 zip（%s 个部分）").format(chosen.size),
                Modifier.fillMaxWidth(),
                outlined = false,
                color = if (chosen.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                compact = true
            ) {
                if (chosen.isNotEmpty()) zipLauncher.launch("mcpbox-backup-${stamp()}.zip")
            }
        }

        GroupLabel(L("单独导出"))
        CardGroup(
            listOf(
                singleExportRow(L("记忆库"), pickMemory, Icons.Filled.Star) {
                    singlePart = Backup.Part.MEMORY
                    singleLauncher.launch("mcpbox-memory-${stamp()}.json")
                },
                singleExportRow(L("设置"), pickSettings, Icons.Filled.Lock) {
                    singlePart = Backup.Part.SETTINGS
                    singleLauncher.launch("mcpbox-settings-${stamp()}.json")
                },
                singleExportRow(L("自定义工具"), pickTools, Icons.Filled.Build) {
                    singlePart = Backup.Part.CUSTOM_TOOLS
                    singleLauncher.launch("mcpbox-custom-tools-${stamp()}.json")
                }
            )
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                L("每个文件都自带类型标记，恢复时会被自动认出来 —— 单独导出的文件也能单独恢复。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun singleExportRow(
    title: String,
    enabled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
): RowSpec = RowSpec(
    title = title,
    subtitle = if (enabled) L("导出一个单独的 json 文件") else L("先在上面勾上它"),
    icon = icon,
    iconTint = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant,
    onClick = if (enabled) onClick else null,
    trailing = { Chevron1() }
)

/* ------------------------------------------------------------------ 小件 */

/**
 * 勾选状态：勾上打勾，没勾就什么都不显示。
 * （早先这里放了个「不备份」灰标签，两个状态都在抢注意力，太吵；
 * 整行本身就能点，没勾就是没勾。）
 */
@Composable
private fun CheckMark(checked: Boolean) {
    if (checked) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Chevron1() {
    Icon(
        Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

/** 文件名里的时间戳，如 20260929-213045。 */
private fun stamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

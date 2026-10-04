// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复。
 *
 * 三个页面：
 *  - 首页：两张卡（备份 / 恢复备份）+ 当前内容 + 说明
 *  - 备份页：用**开关**挑要备份的部分，底栏自带 全选 / 全不选 / 导出；
 *            点导出先选方式（文件导出 = 每个部分一个 json，zip 导出 = 打包成一个 zip），
 *            选完方式再选一个文件夹，文件直接落进去（SAF，不用手打文件名）
 *  - 恢复页：选完备份文件后的整页界面 —— 详情卡 + 统计 + 每部分一个开关，底栏同样是
 *            全选 / 全不选 / 恢复所选项
 *
 * 恢复时的模式询问：设置永远整份覆盖（不问）；记忆库 / 自定义工具只有在
 * 「备份里有、而且当前不是空的」才会问合并还是覆盖 —— 往空里写怎么写都一样，不用烦人。
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

    // 备份页挑了哪些、导出方式 —— 底栏在页面外面，得靠这些算文案和动作，所以状态放这里
    var pickMemory by rememberSaveable { mutableStateOf(true) }
    var pickSettings by rememberSaveable { mutableStateOf(true) }
    var pickTools by rememberSaveable { mutableStateOf(true) }
    var pickStats by rememberSaveable { mutableStateOf(true) }
    var includeToken by rememberSaveable { mutableStateOf(true) }
    var showMode by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf("") }

    // 恢复页：读出来的部分 + 备份元信息
    var restoreItems by remember { mutableStateOf<List<RestoreItem>>(emptyList()) }
    var restoreMeta by remember { mutableStateOf<RestoreMeta?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var askingMode by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val resolver = ctx.contentResolver

    val chosen = buildList {
        if (pickMemory) add(Backup.Part.MEMORY)
        if (pickSettings) add(Backup.Part.SETTINGS)
        if (pickTools) add(Backup.Part.CUSTOM_TOOLS)
        if (pickStats) add(Backup.Part.STATS)
    }
    val picked = restoreItems.filter { it.selected }

    fun textOf(part: Backup.Part): String = when (part) {
        Backup.Part.MEMORY -> AppCore.memory.exportJson()
        Backup.Part.SETTINGS -> Backup.exportSettings(AppCore.prefs, includeToken)
        Backup.Part.CUSTOM_TOOLS -> AppCore.customTools.exportJson()
        Backup.Part.STATS -> AppCore.stats.exportJson()
    }

    /** 已经在首页了就往外退，否则回首页。底栏和页面切换同时进行，不用等。 */
    fun backHome() {
        if (page == HOME) onBack() else page = HOME
    }

    fun loadZip(bytes: ByteArray) {
        runCatching { Backup.readZip(bytes) }.fold(
            onSuccess = { bundle ->
                restoreItems = bundle.parts.map { (part, text) -> RestoreItem.ready(part, text) }
                restoreMeta = RestoreMeta(bundle.exportedAt, bundle.app, bundle.tokenIncluded)
                result = null
                page = RESTORE
            },
            onFailure = { toast(ctx, L("不是合法的备份包：%s").format(it.message ?: "")) }
        )
    }

    fun loadFiles(uris: List<Uri>) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    val text = runCatching {
                        ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    val part = text?.let { Backup.sniff(it) }
                    if (text == null || part == null) null else RestoreItem.ready(part, text)
                }
            }
            if (loaded.isEmpty()) {
                toast(ctx, L("没有能识别的备份内容"))
            } else {
                restoreItems = loaded
                restoreMeta = null
                result = null
                page = RESTORE
            }
        }
    }

    /** 真正把选中的部分写回去。 */
    fun runRestore(list: List<RestoreItem>) {
        val lines = list.map { item ->
            runCatching {
                Backup.apply(
                    part = item.part,
                    text = item.text,
                    mode = if (item.merge) Backup.Mode.MERGE else Backup.Mode.REPLACE,
                    restoreToken = item.restoreToken,
                    settings = AppCore.prefs,
                    memory = AppCore.memory,
                    customTools = AppCore.customTools,
                    stats = AppCore.stats
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
        val touchedSettings = list.any { it.part == Backup.Part.SETTINGS }
        result = lines.joinToString("\n") +
            if (touchedSettings)
                "\n" + L("外观 / 语言的改动已经生效；端口或监听目录变了的话，记得去「后台与运行」重启一次服务器。")
            else ""
        // 设置可能被整份换掉：核心配置、权限、自定义工具都得重读
        AppCore.config.reload()
        AppCore.permissions.load()
        AppCore.customTools.load()
        AppCore.log.enabled = AppCore.config.logEnabled
        // 主题 / 语言 / 彩蛋语言开关都可能在备份里，重建整棵树才对得上
        onThemeChanged()
        onLangChanged()
        onChanged()
        toast(ctx, L("恢复完成，结果在最下面"))
    }

    /** 点「恢复所选项」：需要挑模式的才弹窗，不需要就直接开跑。 */
    fun startRestore() {
        when {
            picked.isEmpty() -> toast(ctx, L("至少要选一部分"))
            picked.none { it.part != Backup.Part.SETTINGS && targetNotEmpty(it.part) } ->
                runRestore(picked)

            else -> askingMode = true
        }
    }

    // 选 zip
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes == null) toast(ctx, L("读不到文件内容")) else loadZip(bytes)
            }
        }
    }

    // 选文件（可多选）
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) loadFiles(uris)
    }

    // 选文件夹 → 直接把文件写进去（不再让用户手打文件名）
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val mode = pendingMode
        pendingMode = ""
        if (uri == null) return@rememberLauncherForActivityResult

        val parts = chosen.associateWith { textOf(it) }
        val withToken = includeToken && pickSettings
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    if (mode == "zip") {
                        val bytes = Backup.zip(parts = parts, includeToken = withToken)
                        writeIntoFolder(
                            resolver, uri, "application/zip",
                            "mcpbox-backup-${stamp()}.zip", bytes
                        )
                    } else {
                        parts.forEach { (part, text) ->
                            writeIntoFolder(
                                resolver, uri, "application/json",
                                "${baseOf(part.file)}-${stamp()}.json", text.toByteArray()
                            )
                        }
                    }
                }
            }
            val ok = done.isSuccess
            toast(
                ctx,
                if (ok) {
                    if (mode == "zip") L("已导出 %s 个部分").format(parts.size)
                    else L("已导出 %s 个文件").format(parts.size)
                } else {
                    L("导出失败")
                }
            )
            // 导出成功就直接退回上一页，不用自己再点返回
            if (ok && page == CREATE) page = HOME
        }
    }

    // 进程被回收再回来时，恢复页的数据已经没了 —— 直接回首页，别显示一个空页面
    LaunchedEffect(page, restoreItems) {
        if (page == RESTORE && restoreItems.isEmpty()) page = HOME
    }

    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            // 跟手返回：手指从边缘往右拖时页面跟着走（回首页那一步）
            PredictiveBackBox(
                onBack = { backHome() },
                follow = page != HOME && AppCore.prefs.predictiveBack
            ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val entering = targetState != HOME
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
                    when (current) {
                        CREATE -> BackupCreatePage(
                            revision = revision,
                            pickMemory = pickMemory,
                            onPickMemory = { pickMemory = it },
                            pickSettings = pickSettings,
                            onPickSettings = { pickSettings = it },
                            pickTools = pickTools,
                            onPickTools = { pickTools = it },
                            pickStats = pickStats,
                            onPickStats = { pickStats = it },
                            includeToken = includeToken,
                            onIncludeToken = { includeToken = it },
                            onBack = { backHome() }
                        )

                        RESTORE -> RestorePage(
                            items = restoreItems,
                            meta = restoreMeta,
                            result = result,
                            onItemsChange = { restoreItems = it },
                            onBack = { backHome() }
                        )

                        else -> BackupHomePage(
                            ctx = ctx,
                            revision = revision,
                            result = result,
                            onOpenCreate = { page = CREATE },
                            onPickZip = {
                                result = null
                                zipLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            },
                            onPickFiles = {
                                result = null
                                fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            onBack = onBack
                        )
                    }
                }
            }
            }
        }

        // 底栏固定在页面外面：进子页滑入一次，子页之间切来切去都不再动它
        AnimatedVisibility(
            visible = page != HOME,
            enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(120))
        ) {
            if (page == CREATE) {
                SelectionBar(
                    actionText = if (chosen.isEmpty()) L("导出")
                    else L("导出（%s 个部分）").format(chosen.size),
                    actionEnabled = chosen.isNotEmpty(),
                    onSelectAll = {
                        pickMemory = true
                        pickSettings = true
                        pickTools = true
                        pickStats = true
                        includeToken = true
                    },
                    onSelectNone = {
                        pickMemory = false
                        pickSettings = false
                        pickTools = false
                        pickStats = false
                        includeToken = false
                    },
                    onAction = { showMode = true }
                )
            } else {
                SelectionBar(
                    actionText = L("恢复所选项"),
                    actionEnabled = picked.isNotEmpty(),
                    onSelectAll = { restoreItems = restoreItems.map { it.copy(selected = true) } },
                    onSelectNone = { restoreItems = restoreItems.map { it.copy(selected = false) } },
                    onAction = { startRestore() }
                )
            }
        }
    }

    if (showMode) {
        ExportModeDialog(
            count = chosen.size,
            onDismiss = { showMode = false },
            onPick = { mode ->
                showMode = false
                pendingMode = mode
                dirLauncher.launch(null)
            }
        )
    }

    if (askingMode) {
        ModePickerDialog(
            items = picked,
            onToggleMode = { part ->
                restoreItems = restoreItems.map { if (it.part == part) it.copy(merge = !it.merge) else it }
            },
            onDismiss = { askingMode = false },
            onRun = {
                askingMode = false
                runRestore(restoreItems.filter { it.selected })
            }
        )
    }
}

private const val HOME = "home"
private const val CREATE = "create"
private const val RESTORE = "restore"

/** 待恢复的一项：一块内容 + 用户选好的处理方式。 */
private data class RestoreItem(
    val part: Backup.Part,
    val text: String,
    val selected: Boolean = true,
    /** 合并 / 覆盖；设置这一部分永远走覆盖，不参与询问。 */
    val merge: Boolean = true,
    /** 设置专有：要不要连访问令牌一起恢复（默认不要，免得把客户端全踢下线）。 */
    val restoreToken: Boolean = false
) {
    companion object {
        /**
         * 从备份里读出来的一项。
         *
         * 设置的默认模式是**覆盖**（跟「把备份整份还原回去」的直觉一致），
         * 记忆库和自定义工具默认合并，至于要不要问用户，交给恢复页判断。
         */
        fun ready(part: Backup.Part, text: String): RestoreItem = RestoreItem(
            part = part,
            text = text,
            merge = part != Backup.Part.SETTINGS
        )
    }
}

/** 备份文件的元信息（zip 的 manifest / 没有就留空）。 */
private data class RestoreMeta(
    val exportedAt: Long,
    val app: String?,
    val tokenIncluded: Boolean
)

/* ------------------------------------------------------------------ 首页 */

@Composable
private fun BackupHomePage(
    ctx: Context,
    revision: Int,
    result: String?,
    onOpenCreate: () -> Unit,
    onPickZip: () -> Unit,
    onPickFiles: () -> Unit,
    onBack: () -> Unit
) {
    var showPick by remember { mutableStateOf(false) }

    val memoryCount = remember(revision) { AppCore.memory.graph.entities.size }
    val relationCount = remember(revision) { AppCore.memory.graph.relations.size }
    val toolCount = remember(revision) { AppCore.customTools.tools.size }
    val settingCount = remember(revision) { AppCore.prefs.all().size }
    val statsCount = remember(revision) { AppCore.stats.snapshot().requestsTotal }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("备份与恢复"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("操作"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("备份"),
                    subtitle = L("开关挑要备份的内容，再导出到文件夹里"),
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
                ),
                RowSpec(
                    title = L("统计"),
                    subtitle = L("请求热力图、运行时长与启动次数"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.DateRange,
                    trailing = {
                        OutlineTag(
                            L("%s 次请求").format(statsCount),
                            MaterialTheme.colorScheme.primary
                        )
                    }
                )
            )
        )

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
                    onPickZip()
                }) { Text(L("选 zip 备份"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPick = false
                    onPickFiles()
                }) { Text(L("选单独文件"), color = MaterialTheme.colorScheme.primary) }
            }
        )
    }
}

/* ---------------------------------------------------------------- 备份页 */

@Composable
private fun BackupCreatePage(
    revision: Int,
    pickMemory: Boolean,
    onPickMemory: (Boolean) -> Unit,
    pickSettings: Boolean,
    onPickSettings: (Boolean) -> Unit,
    pickTools: Boolean,
    onPickTools: (Boolean) -> Unit,
    pickStats: Boolean,
    onPickStats: (Boolean) -> Unit,
    includeToken: Boolean,
    onIncludeToken: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val memoryCount = remember(revision) { AppCore.memory.graph.entities.size }
    val relationCount = remember(revision) { AppCore.memory.graph.relations.size }
    val toolCount = remember(revision) { AppCore.customTools.tools.size }
    val stats = remember(revision) { AppCore.stats.snapshot() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        PageHeader(
            title = L("备份"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("要备份的内容"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("记忆库"),
                    subtitle = L("%s 个实体 · %s 条关系").format(memoryCount, relationCount),
                    icon = Icons.Filled.Star,
                    onClick = { onPickMemory(!pickMemory) },
                    trailing = { AppSwitch(pickMemory, onPickMemory) }
                ),
                RowSpec(
                    title = L("设置"),
                    subtitle = L("端口、权限、外观、超时等全部配置项"),
                    icon = Icons.Filled.Lock,
                    onClick = { onPickSettings(!pickSettings) },
                    trailing = { AppSwitch(pickSettings, onPickSettings) }
                ),
                // 令牌是「设置」的附属项：跟着设置一起开关，关掉设置时整行收起来
                RowSpec(
                    title = L("包含访问令牌"),
                    subtitle = if (includeToken)
                        L("备份里带着令牌，恢复后客户端不用改地址；文件别随便分享")
                    else L("备份里不带令牌，恢复时会保留当前令牌"),
                    subtitleMaxLines = 2,
                    visible = pickSettings,
                    indent = true,
                    onClick = { onIncludeToken(!includeToken) },
                    trailing = { AppSwitch(includeToken, onIncludeToken) }
                ),
                RowSpec(
                    title = L("自定义工具"),
                    subtitle = if (toolCount == 0) L("还没有自定义工具") else L("%s 个工具").format(toolCount),
                    icon = Icons.Filled.Build,
                    onClick = { onPickTools(!pickTools) },
                    trailing = { AppSwitch(pickTools, onPickTools) }
                ),
                RowSpec(
                    title = L("统计"),
                    subtitle = L("%s 天记录 · %s 次请求 · %s 次启动")
                        .format(stats.requestsByDay.size, stats.requestsTotal, stats.appLaunches),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.DateRange,
                    onClick = { onPickStats(!pickStats) },
                    trailing = { AppSwitch(pickStats, onPickStats) }
                )
            )
        )
    }
}

/** 导出方式：分开的 json 还是打包成 zip。 */
@Composable
private fun ExportModeDialog(
    count: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    L("怎么导出？"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    L("%s 个部分准备好了，选一种放法，下一步挑个文件夹就行。").format(count),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp)
                )
                Spacer(Modifier.height(14.dp))

                ModeChoiceRow(
                    title = L("文件导出"),
                    desc = L("每个部分一个 json，直接放进你选的文件夹"),
                    onClick = { onPick("files") }
                )
                Spacer(Modifier.height(4.dp))
                ModeChoiceRow(
                    title = L("zip 导出"),
                    desc = L("打包成一个 zip，里面是 manifest.json 加各个部分"),
                    onClick = { onPick("zip") }
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChoiceRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/* ---------------------------------------------------------------- 恢复页 */

@Composable
private fun RestorePage(
    items: List<RestoreItem>,
    meta: RestoreMeta?,
    result: String?,
    onItemsChange: (List<RestoreItem>) -> Unit,
    onBack: () -> Unit
) {
    val picked = items.filter { it.selected }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        PageHeader(
            title = L("恢复备份"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("备份详情"))
        CardColumn {
            CardBox {
                KeyValue(L("创建时间"), exportTimeLabel(meta))
                KeyValue(L("应用版本"), meta?.app?.takeIf { it.isNotBlank() } ?: L("未知"))
                KeyValue(
                    L("访问令牌"),
                    if (meta?.tokenIncluded == true) L("备份里带着") else L("备份里没有")
                )
                KeyValue(L("包含部分"), L("%s 个").format(items.size))
                // 选了几个放在这张卡里，不单独占一行
                KeyValue(
                    L("已选"),
                    L("%s 个，共 %s 个").format(picked.size, items.size),
                    if (picked.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
        }

        GroupLabel(L("要恢复的内容"))
        CardGroup(
            buildList {
                items.forEach { item ->
                    add(
                        RowSpec(
                            title = partLabel(item.part),
                            subtitle = restoreSummary(item),
                            subtitleMaxLines = 2,
                            icon = partIcon(item.part),
                            onClick = {
                                onItemsChange(items.map { if (it === item) it.copy(selected = !it.selected) else it })
                            },
                            trailing = {
                                AppSwitch(item.selected) { on ->
                                    onItemsChange(items.map { if (it === item) it.copy(selected = on) else it })
                                }
                            }
                        )
                    )
                    // 设置是整份覆盖，恢复不恢复令牌在这里定（默认保留当前的）
                    if (item.part == Backup.Part.SETTINGS && item.selected) {
                        add(
                            RowSpec(
                                title = L("同时恢复访问令牌"),
                                subtitle = if (item.restoreToken)
                                    L("恢复成备份里那个令牌，客户端也要跟着换")
                                else L("保留当前令牌（推荐）：客户端不用动"),
                                subtitleMaxLines = 2,
                                indent = true,
                                onClick = {
                                    onItemsChange(items.map { if (it === item) it.copy(restoreToken = !it.restoreToken) else it })
                                },
                                trailing = {
                                    AppSwitch(item.restoreToken) { on ->
                                        onItemsChange(items.map { if (it === item) it.copy(restoreToken = on) else it })
                                    }
                                }
                            )
                        )
                    }
                }
            }
        )

        result?.let { text ->
            Spacer(Modifier.height(10.dp))
            CardColumn {
                CardBox {
                    Text(
                        L("恢复结果"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------- 模式选择弹窗 */

/**
 * 合并还是覆盖。
 *
 * 只列「真的需要选」的那些部分（备份里有、当前又不是空的）——
 * 其它部分怎么写都一样，列出来只会让人犹豫。
 */
@Composable
private fun ModePickerDialog(
    items: List<RestoreItem>,
    onToggleMode: (Backup.Part) -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(L("怎么恢复这几部分？"), fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    L("点右侧的胶囊切换合并 / 覆盖。覆盖会先清空这一部分，不能撤销。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                items.forEach { item ->
                    if (item.part == Backup.Part.SETTINGS) return@forEach
                    if (!targetNotEmpty(item.part)) return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                partLabel(item.part),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.5.sp
                            )
                            Text(
                                restoreSummary(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        ChoiceChip(
                            text = L(if (item.merge) "合并" else "覆盖"),
                            active = !item.merge,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = { onToggleMode(item.part) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRun) {
                Text(L("开始恢复"), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/* ------------------------------------------------------------------ 小件 */

/** 里头就翻译好：调用处直接拼字符串，不用再包一层 L(...)。 */
private fun partLabel(part: Backup.Part): String = when (part) {
    Backup.Part.MEMORY -> L("记忆库")
    Backup.Part.SETTINGS -> L("设置")
    Backup.Part.CUSTOM_TOOLS -> L("自定义工具")
    Backup.Part.STATS -> L("统计")
}

private fun partIcon(part: Backup.Part) = when (part) {
    Backup.Part.MEMORY -> Icons.Filled.Star
    Backup.Part.SETTINGS -> Icons.Filled.Lock
    Backup.Part.CUSTOM_TOOLS -> Icons.Filled.Build
    Backup.Part.STATS -> Icons.Filled.DateRange
}

/**
 * 一行的说明：这一块里有什么。
 *
 * 只留数据量（有多少实体 / 多少工具），「将替换当前数据」那类后缀就省了 ——
 * 需要选合并还是覆盖的时候，弹窗里会说清楚。
 */
private fun restoreSummary(item: RestoreItem): String = when (item.part) {
    Backup.Part.MEMORY -> L("%s 个实体 · %s 条关系").format(
        AppCore.memory.graph.entities.size,
        AppCore.memory.graph.relations.size
    )

    // 设置是整份覆盖、不问模式，所以这里得留一句短的
    Backup.Part.SETTINGS -> (if (Backup.settingsHasToken(item.text)) L("含访问令牌")
    else L("不含访问令牌")) + " · " + L("整份覆盖")

    Backup.Part.CUSTOM_TOOLS -> {
        val n = AppCore.customTools.tools.size
        if (n == 0) L("还没有自定义工具") else L("%s 个工具").format(n)
    }

    // 统计：显示备份文件里的规模（不是当前 App 的），方便判断要不要恢复
    Backup.Part.STATS -> statsSummary(item.text)
}

/** 备份里的统计有多大：多少天记录、总请求多少次。 */
private fun statsSummary(text: String): String = runCatching {
    val o = org.json.JSONObject(text)
    L("%s 天记录 · 总请求 %s 次").format(
        o.optJSONObject("requests")?.length() ?: 0,
        o.optLong("requestsTotal")
    )
}.getOrElse { L("请求热力图、运行时长与启动次数") }

/** 这一部分现在有没有内容（空的就不用问合并还是覆盖）。 */
private fun targetNotEmpty(part: Backup.Part): Boolean = when (part) {
    Backup.Part.MEMORY ->
        AppCore.memory.graph.entities.isNotEmpty() || AppCore.memory.graph.relations.isNotEmpty()

    Backup.Part.CUSTOM_TOOLS -> AppCore.customTools.tools.isNotEmpty()
    Backup.Part.SETTINGS -> true
    Backup.Part.STATS -> AppCore.stats.snapshot().let {
        it.requestsTotal > 0L || it.appLaunches > 0L || it.serverMillis > 0L
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

/** 备份包里记的导出时间。 */
private fun exportTimeLabel(meta: RestoreMeta?): String {
    val ts = meta?.exportedAt ?: 0L
    if (ts <= 0L) return L("没记（单独导出的文件）")
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts))
}

/**
 * 往用户选的文件夹里写一个文件。
 *
 * 走 SAF 的 DocumentsContract，不依赖 DocumentFile（少一个依赖）；
 * 同名文件交给系统自己去重命名（一般会加「 (1)」），我们不覆盖别人的东西。
 */
private fun writeIntoFolder(
    resolver: ContentResolver,
    tree: Uri,
    mime: String,
    name: String,
    bytes: ByteArray
): Uri {
    val dir = DocumentsContract.buildDocumentUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree)
    )
    val file = DocumentsContract.createDocument(resolver, dir, mime, name)
        ?: error("createDocument returned null")
    resolver.openOutputStream(file)?.use { it.write(bytes) } ?: error("openOutputStream failed")
    return file
}

/** 文件名里的时间戳，如 20260929-213045。 */
private fun stamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

/** memory.json → memory。 */
private fun baseOf(file: String): String = file.substringBeforeLast('.')

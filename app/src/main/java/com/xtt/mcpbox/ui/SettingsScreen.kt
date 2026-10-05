// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.McpServer
import com.xtt.mcpbox.core.ServerMeta

/**
 * 设置页：**顶层只放入口，具体开关在各自的子页**（见 SettingsPages.kt）。
 *
 * 四件必修都在这里做齐：
 *  ① 子页进出场动画（进入滑 1/3 屏宽，返回反向）
 *  ② 子页接 [PredictiveBackBox]（内部是 BackHandler / 预见式返回动画），顶层不接（顶层再按返回就该退出应用）
 *  ③ 标题属于各自的页面：顶层用「设置」，子页用各自的 `PageHeader`
 *  ④ 每页滚动位置用 [rememberSaveableStateHolder] 按 key 保留
 *
 * 当前停在哪一页由外部传入（[page] / [onPage]）—— 这样切语言、切主题触发整棵树重建时，
 * 不会把用户从子页里甩回设置首页。
 */
@Composable
fun SettingsScreen(
    ctx: Context,
    status: McpServer.ServerStatus,
    revision: Int,
    page: String,
    onPage: (String) -> Unit,
    onThemeChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onChanged: () -> Unit,
    onRestartService: () -> Unit,
    scrollTopTick: Int = 0
) {
    var showPort by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var showSeed by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }
    var langInfo by remember { mutableStateOf("") }
    val importLang = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast(ctx, L("读不到文件内容"))
            } else {
                com.xtt.mcpbox.i18n.Lang.importPack(ctx, text).fold(
                    onSuccess = { (id, count) ->
                        AppCore.prefs.appLang = id
                        com.xtt.mcpbox.i18n.Lang.init(ctx, id, com.xtt.mcpbox.i18n.Lang.systemIsEnglish)
                        langInfo = L("已导入语言包 %s（%s 条译文）").format(id, count)
                        onLangChanged()
                    },
                    onFailure = { langInfo = L("导入失败：%s").format(it.message) }
                )
            }
        }
    }

    val back = { onPage("") }

    // 每页的滚动位置跟着页面 key 存下来，回来时不跳回顶部
    val pageStates = rememberSaveableStateHolder()

    // 设置首页的滚动状态放在这里（而不是页面内部）：预见式返回的预览层要和真页面共用同一个，
    // 否则拖出来的预览永远是最顶部、松手后真页面跳回当前滚动位置
    val homeScroll = rememberScrollState()

    // 子页里按系统返回 → 回设置首页，而不是退出整个应用（顶层不接）。
    // 开了「预见式返回动画」时手指拖着走，松手才决定回不回；拖动时下面露出来的就是设置首页。
    PredictiveBackBox(
        onBack = back,
        handleBack = page.isNotEmpty(),
        follow = page.isNotEmpty() && AppCore.prefs.predictiveBack,
        behind = {
            SettingsHomePage(
                scroll = homeScroll,
                scrollTopTick = scrollTopTick,
                toolCount = status.toolCount,
                entityCount = AppCore.memory.graph.entities.size,
                relationCount = AppCore.memory.graph.relations.size,
                onPage = onPage,
                onOpenTools = onOpenTools,
                onOpenMemory = onOpenMemory,
                onOpenBackup = onOpenBackup,
                onOpenAbout = onOpenAbout
            )
        }
    ) {
    // 跟手提交时旧页已经偏了多少（普通返回是 0）—— 交给共用转场去用
    val commitDrag = LocalPredictiveCommitDrag.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            pageSlide(
                entering = pageForward(targetState, initialState) { if (it.isEmpty()) 0 else 1 },
                commitDrag = commitDrag
            )
        },
        label = "settingsPage"
    ) { current ->
        // 顶层是空串，给它一个固定名字当 key
        pageStates.SaveableStateProvider(current.ifEmpty { "root" }) {
            when (current) {
                "appearance" -> AppearanceSettingsPage(
                    ctx = ctx,
                    revision = revision,
                    onChanged = onChanged,
                    onThemeChanged = onThemeChanged,
                    onLangChanged = onLangChanged,
                    onOpenSeed = { showSeed = true },
                    onOpenLangPack = { langInfo = ""; showLang = true },
                    onBack = back
                )
                "network" -> NetworkSettingsPage(
                    status = status,
                    onChanged = onChanged,
                    onOpenPort = { showPort = true },
                    onOpenPassword = {
                        passwordText = AppCore.config.consolePassword
                        showPassword = true
                    },
                    onBack = back
                )
                "security" -> SecuritySettingsPage(
                    ctx = ctx,
                    revision = revision,
                    onChanged = onChanged,
                    onBack = back
                )
                "shell" -> ShellSettingsPage(
                    revision = revision,
                    onChanged = onChanged,
                    onBack = back
                )
                "browser" -> BrowserSettingsPage(
                    revision = revision,
                    onChanged = onChanged,
                    onBack = back
                )
                "background" -> BackgroundSettingsPage(
                    revision = revision,
                    onChanged = onChanged,
                    onRestartService = onRestartService,
                    onOpenReset = { showReset = true },
                    onBack = back
                )
                "stats" -> StatsSettingsPage(onBack = back)
                else -> SettingsHomePage(
                        scroll = homeScroll,
                        scrollTopTick = scrollTopTick,
                    toolCount = status.toolCount,
                    entityCount = AppCore.memory.graph.entities.size,
                    relationCount = AppCore.memory.graph.relations.size,
                    onPage = onPage,
                    onOpenTools = onOpenTools,
                    onOpenMemory = onOpenMemory,
                    onOpenBackup = onOpenBackup,
                    onOpenAbout = onOpenAbout
                )
            }
        }
    }
    }

    if (showPort) {
        var portText by remember { mutableStateOf(AppCore.config.port.toString()) }
        AlertDialog(
            onDismissRequest = { showPort = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("监听端口"), fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                        label = { Text("1024 - 65535") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        L("改完会自动重启服务，客户端里的地址也要跟着改。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        toast(ctx, L("端口需要 1024 - 65535"))
                    } else {
                        AppCore.config.port = p
                        AppCore.saveConfig()
                        showPort = false
                        if (status.running) onRestartService()
                        toast(ctx, L("端口已改为 %s").format(p))
                        onChanged()
                    }
                }) { Text(L("应用"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPort = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showLang) {
        val cur = com.xtt.mcpbox.i18n.Lang.current
        val builtinCount = com.xtt.mcpbox.i18n.Lang.entriesOf(com.xtt.mcpbox.i18n.Lang.EN).size
        fun writeOut(name: String, text: String) {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val file = java.io.File(dir, name)
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                file.writeText(text)
            }.fold(
                onSuccess = { langInfo = L("已导出到 %s").format(file.absolutePath) },
                onFailure = { langInfo = L("导出失败：%s").format(it.message) }
            )
        }
        AlertDialog(
            onDismissRequest = { showLang = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("语言包"), fontSize = 20.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        L("当前语言：%s\n内置英文词条：%s 条\n已导入语言包：").format(
                            com.xtt.mcpbox.i18n.Lang.packDisplayName(cur),
                            builtinCount
                        ) + com.xtt.mcpbox.i18n.Lang.packLanguages().joinToString("、").ifBlank { L("无") },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    if (langInfo.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(langInfo, color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp)
                    }
                    // 已导入的语言包：可以逐个删掉
                    val packs = com.xtt.mcpbox.i18n.Lang.packLanguages()
                    if (packs.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            L("已导入的语言包"),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                        packs.forEach { id ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        com.xtt.mcpbox.i18n.Lang.packDisplayName(id),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        L("%s · %s 条译文").format(
                                            id, com.xtt.mcpbox.i18n.Lang.entryCount(id)
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = {
                                    com.xtt.mcpbox.i18n.Lang.deletePack(ctx, id)
                                    if (AppCore.prefs.appLang == id) {
                                        AppCore.prefs.appLang = "system"
                                        com.xtt.mcpbox.i18n.Lang.init(
                                            ctx, "system", com.xtt.mcpbox.i18n.Lang.systemIsEnglish
                                        )
                                    }
                                    langInfo = L("已删除")
                                    onLangChanged()
                                }) { Text(L("删除"), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        L("导出模板"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        writeOut(
                            "mcpbox-lang-template.json",
                            com.xtt.mcpbox.i18n.Lang.exportTemplate(ctx, "en")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        L("导出当前语言包"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        writeOut("mcpbox-lang-$cur.json", com.xtt.mcpbox.i18n.Lang.exportPack(ctx, cur))
                    }
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        L("从文件导入"),
                        Modifier.fillMaxWidth(),
                        outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) { importLang.launch(arrayOf("application/json", "*/*")) }
                    if (com.xtt.mcpbox.i18n.Lang.packLanguages().isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        PillButton(
                            L("删除导入的语言包"),
                            Modifier.fillMaxWidth(),
                            outlined = true,
                            color = MaterialTheme.colorScheme.error
                        ) {
                            com.xtt.mcpbox.i18n.Lang.packLanguages().forEach {
                                com.xtt.mcpbox.i18n.Lang.deletePack(ctx, it)
                            }
                            AppCore.prefs.appLang = "system"
                            com.xtt.mcpbox.i18n.Lang.init(ctx, "system", com.xtt.mcpbox.i18n.Lang.systemIsEnglish)
                            langInfo = L("已删除")
                            onLangChanged()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        L("语言包就是一个 JSON：\"中文原文\" 对应 \"译文\"。导出模板照着填即可。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLang = false }) {
                    Text(L("关闭"), color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    if (showSeed) {
        val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(AppCore.prefs.seedColor, it) } }
        var hue by remember { mutableStateOf(hsv[0]) }
        var sat by remember { mutableStateOf(hsv[1]) }
        var bri by remember { mutableStateOf(hsv[2]) }
        val cur = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
        AlertDialog(
            onDismissRequest = { showSeed = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("种子颜色"), fontSize = 20.sp) },
            text = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(cur)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            hexOf(cur),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SEED_PRESETS.chunked(6).forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { c ->
                                val selected = cur == c.toInt()
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .then(
                                            if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(color = MaterialTheme.colorScheme.primary)
                                        ) {
                                            val f = FloatArray(3)
                                            android.graphics.Color.colorToHSV(c.toInt(), f)
                                            hue = f[0]; sat = f[1]; bri = f[2]
                                        }
                                )
                            }
                        }
                    }
                    SeedSlider(L("色相"), hue, 0f..360f) { hue = it }
                    SeedSlider(L("饱和度"), sat, 0f..1f) { sat = it }
                    SeedSlider(L("亮度"), bri, 0f..1f) { bri = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.prefs.seedColor = cur
                    AppCore.prefs.dynamicColor = false
                    showSeed = false
                    toast(ctx, L("种子颜色已更新"))
                    onThemeChanged()
                }) { Text(L("应用"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSeed = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showPassword) {
        AlertDialog(
            onDismissRequest = { showPassword = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("网页访问密码"), fontSize = 20.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { v -> passwordText = v.take(64) },
                        label = { Text(L("新密码（留空 = 用访问令牌）")) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        L("只影响浏览器打开的网页控制台；AI 客户端照旧用访问令牌连接。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppCore.config.consolePassword = passwordText.trim()
                    AppCore.saveConfig()
                    showPassword = false
                    toast(ctx, if (passwordText.isBlank()) L("已清空，网页密码改用访问令牌") else L("网页密码已保存"))
                    onChanged()
                }) { Text(L("保存"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPassword = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(L("重置全部设置？"), fontSize = 20.sp) },
            text = {
                Column {
                    Text(
                        L("会重置除记忆库以外的所有设置。"),
                        color = Sem.bad,
                        fontSize = 13.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L("权限开关与规则、工具覆盖、端口、访问令牌、监听目录、外观与语言、后台选项都会回到默认；服务器会停止，用旧令牌的客户端要重新填地址。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L("记忆库（实体 · 观察 · 关系）和统计不受影响。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    AppCore.resetAll()
                    onChanged()
                }) { Text(L("确认重置"), color = Sem.bad) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/* ------------------------------------------------------------------ 设置首页 */

/**
 * 顶层只放入口行 —— 一眼扫得完，加功能只是多一行。
 * 高频的「看状态」留在首页里（版本号写在标题下方）。
 */
@Composable
private fun SettingsHomePage(
    scroll: ScrollState,
    scrollTopTick: Int = 0,
    toolCount: Int,
    entityCount: Int,
    relationCount: Int,
    onPage: (String) -> Unit,
    onOpenTools: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit
) {
    // 双击底栏「设置」：回到顶部
    NavReselectEffect(scrollTopTick) { scroll.animateScrollTo(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("设置"),
        )

        GroupLabel(L("通用"))
        CardGroup(
            listOf(
                entrySpec(
                    L("外观与语言"),
                    L("颜色模式、动态取色、调色板、界面语言与语言包"),
                    Icons.Filled.Star
                ) { onPage("appearance") },
                entrySpec(
                    L("网络与访问"),
                    L("监听端口、局域网访问、响应格式与网页控制台"),
                    Icons.Filled.Share
                ) { onPage("network") },
                entrySpec(
                    L("安全与审批"),
                    L("访问令牌、审批超时"),
                    Icons.Filled.Lock
                ) { onPage("security") },
                entrySpec(
                    L("终端与命令"),
                    L("命令后端优先级、命令规则、默认超时"),
                    Icons.Filled.Build
                ) { onPage("shell") },
                entrySpec(
                    L("浏览器"),
                    L("内置 WebView：内网限制、页面数、搜索引擎、cookie 与 User-Agent"),
                    Icons.Filled.Search
                ) { onPage("browser") }
            )
        )

        GroupLabel(L("AI"))
        CardGroup(
            listOf(
                entrySpec(
                    L("工具管理"),
                    L("共 %s 个 · 可单独启用/禁用、设权限（跟随 / 允许 / 询问 / 拒绝）；右上角 + 新建自定义工具")
                        .format(toolCount),
                    Icons.Filled.Build
                ) { onOpenTools() },
                entrySpec(
                    L("记忆库"),
                    L("给 AI 的长期记忆：%s 个实体 · %s 条关系").format(entityCount, relationCount),
                    Icons.Filled.Star
                ) { onOpenMemory() }
            )
        )

        GroupLabel(L("应用"))
        CardGroup(
            listOf(
                entrySpec(
                    L("后台与运行"),
                    L("CPU 唤醒、开机自启、日志与服务控制"),
                    Icons.Filled.Refresh
                ) { onPage("background") },
                entrySpec(
                    L("统计"),
                    L("每日请求热力图、累计运行时长与启动次数"),
                    Icons.Filled.DateRange
                ) { onPage("stats") },
                entrySpec(
                    L("备份与恢复"),
                    L("把记忆、设置、自定义工具和统计打包导出，或从备份里挑着恢复"),
                    Icons.Filled.Share
                ) { onOpenBackup() },
                entrySpec(
                    L("关于"),
                    L("%s · 开发者 xtt · 检查更新与开源鸣谢").format(ServerMeta.fullVersion),
                    Icons.Filled.Info
                ) { onOpenAbout() }
            )
        )

        Spacer(Modifier.height(20.dp))
    }
}

/** 顶层入口行：图标 + 标题 + 一句「里面有什么」 + 右侧箭头。 */
private fun entrySpec(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) = RowSpec(
    title = title,
    subtitle = subtitle,
    subtitleMaxLines = 2,
    icon = icon,
    onClick = onClick,
    trailing = {
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
)

/* ------------------------------------------------------------------ 小组件 */

@Composable
internal fun RowIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    RoundIconButton(icon, desc, size = 40, onClick = onClick)
}

private fun hexOf(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

@Composable
private fun SeedSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(46.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

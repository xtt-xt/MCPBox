// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.UpdateChecker
import com.xtt.mcpbox.core.ServerMeta
import com.xtt.mcpbox.core.ShellBackends
import com.xtt.mcpbox.i18n.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 开源依赖清单（鸣谢）。 */
private data class Credit(val name: String, val license: String, val url: String)

private val CREDITS = listOf(
    Credit("AndroidX / Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Credit("Kotlin & kotlinx.coroutines", "Apache-2.0", "https://kotlinlang.org"),
    Credit(L("Material Components (HCT 取色算法)"), "Apache-2.0", "https://github.com/material-components/material-components-android"),
    Credit("Material Color Utilities", "Apache-2.0", "https://github.com/material-foundation/material-color-utilities"),
    Credit("Shizuku", "Apache-2.0", "https://github.com/RikkaApps/Shizuku-API")
)

private const val ABOUT_PAGE = "about"
private const val DEV_PAGE = "dev"

/**
 * 关于页。
 *
 * 连点顶部图标 7 次 → 解锁「开发者模式」（关于页里多一张入口卡片）。
 * 彩蛋语言（猫娘语）不再随解锁直接出现，而是要在开发者模式里打开「语言菜单」开关。
 */
@Composable
fun AboutScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onPreviewUpdate: (UpdateChecker.Info) -> Unit,
    onUpdateFound: (UpdateChecker.Info) -> Unit,
    onBack: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(ABOUT_PAGE) }
    // 关于首页的滚动：预览层与真页面共用（否则拖出来的是最顶部）
    val homeScroll = rememberScrollState()

    // 系统返回：在开发者模式里先回关于页，在关于页才回设置。
    // 开了「预见式返回动画」时，在开发者模式里手指拖着走（下面露出关于页）。
    PredictiveBackBox(
        onBack = { if (page == DEV_PAGE) page = ABOUT_PAGE else onBack() },
        follow = page == DEV_PAGE && AppCore.prefs.predictiveBack,
        behind = {
            AboutHomePage(
                scroll = homeScroll,
                ctx = ctx,
                revision = revision,
                onChanged = onChanged,
                onUpdateFound = onUpdateFound,
                onOpenDev = { page = DEV_PAGE },
                onBack = onBack
            )
        }
    ) {
    val pages = rememberSaveableStateHolder()
    // 跟手提交时旧页已经偏了多少（普通返回是 0）—— 交给共用转场去用
    val commitDrag = LocalPredictiveCommitDrag.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            pageSlide(
                entering = pageForward(targetState, initialState) { if (it == DEV_PAGE) 1 else 0 },
                commitDrag = commitDrag
            )
        },
        label = "aboutPage"
    ) { current ->
        pages.SaveableStateProvider(current) {
            if (current == DEV_PAGE) {
                DevModePage(
                    revision = revision,
                    ctx = ctx,
                    onChanged = onChanged,
                    onLangChanged = onLangChanged,
                    onPreviewUpdate = onPreviewUpdate,
                    onCloseDev = {
                        // 关掉开发者模式：入口卡片消失，语言菜单也一起收起来。
                        // 如果正用着彩蛋语言，顺手退回跟随系统，免得下拉里找不到当前项。
                        AppCore.prefs.devModeUnlocked = false
                        if (AppCore.prefs.languageMenu) {
                            AppCore.prefs.languageMenu = false
                            Lang.AndroidCatFlag.unlocked = false
                            if (AppCore.prefs.appLang == Lang.CAT) {
                                AppCore.prefs.appLang = "system"
                                Lang.init(ctx, "system", Lang.systemIsEnglish)
                                onLangChanged()
                            }
                        }
                        page = ABOUT_PAGE
                        toast(ctx, L("开发者模式已关闭"))
                        onChanged()
                    },
                    onBack = { page = ABOUT_PAGE }
                )
            } else {
                AboutHomePage(
                    scroll = homeScroll,
                    ctx = ctx,
                    revision = revision,
                    onChanged = onChanged,
                    onUpdateFound = onUpdateFound,
                    onOpenDev = { page = DEV_PAGE },
                    onBack = onBack
                )
            }
        }
    }
    }
}

/* ------------------------------------------------------------------ 关于首页 */

@Composable
private fun AboutHomePage(
    scroll: ScrollState,
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onUpdateFound: (UpdateChecker.Info) -> Unit,
    onOpenDev: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var catTaps by remember { mutableStateOf(0) }
    var catTarget by remember { mutableStateOf(1f) }
    val catScale by animateFloatAsState(catTarget, tween(150), label = "catScale")
    var checking by remember { mutableStateOf(false) }
    val full = ServerMeta.fullVersion

    // 手动检查：有新版就弹更新弹窗（和每天自动检查用同一个弹窗），
    // 已是最新 / 检查失败都只用 toast，不再往列表里塞多余的行
    fun runCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check() }
            checking = false
            AppCore.prefs.lastUpdateCheck = UpdateChecker.today()
            when (r) {
                is UpdateChecker.Result.Newer -> onUpdateFound(r.info)
                UpdateChecker.Result.UpToDate -> toast(ctx, L("已经是最新版啦"))
                is UpdateChecker.Result.Failed -> toast(ctx, L("检查更新失败：%s").format(r.reason))
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("关于"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        // ------------------------------------------------ 应用信息
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // 彩蛋：连点 7 次解锁「开发者模式」（每次点击都有缩放 + 喵声反馈）
                    .graphicsLayer {
                        scaleX = catScale
                        scaleY = catScale
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null        // 彩蛋：反馈是缩放 + 喵声，不再叠波纹
                    ) {
                        catTaps++
                        catTarget = 0.86f
                        scope.launch {
                            delay(100)
                            catTarget = 1f
                        }
                        if (catTaps >= 7) {
                            catTaps = 0
                            if (AppCore.prefs.devModeUnlocked) {
                                toast(ctx, L("喵～ 开发者模式早就在「开发者」那组里了"))
                            } else {
                                AppCore.prefs.devModeUnlocked = true
                                toast(ctx, L("喵～ 开发者模式已解锁"))
                                onChanged()
                            }
                        } else {
                            // 第 1 次「喵」，第 2 次「喵喵」…这样点着就有反馈
                            toast(ctx, L("喵").repeat(catTaps))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        ctx.resources.getIdentifier(
                            "ic_launcher_foreground", "drawable", ctx.packageName
                        )
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                L("MCP 文件盒"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${full}（${ServerMeta.PROTOCOL}）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(3.dp))
            Text(
                L("把手机变成一台 MCP 文件服务器"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp
            )
        }

        // ------------------------------------------------ 开发者
        GroupLabel(L("开发者"))
        CardGroup(
            listOfNotNull(
                RowSpec(
                    title = "xtt",
                    subtitle = L("个人项目 · 使用 GPL-3.0 许可"),
                    icon = Icons.Filled.Star
                ),
                // 连点图标 7 次解锁后才出现
                if (AppCore.prefs.devModeUnlocked) RowSpec(
                    title = L("开发者模式"),
                    subtitle = L("调试入口：语言菜单、更新预览、初始引导"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Build,
                    onClick = onOpenDev,
                    trailing = {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                ) else null
            )
        )

        GroupLabel(L("更新"))
        CardGroup(
            listOf(
                RowSpec(
                    title = if (checking) L("正在检查…") else L("检查更新"),
                    subtitle = L("当前版本 %s").format(full),
                    icon = Icons.Filled.Refresh,
                    onClick = { runCheck() }
                ),
                switchSpec(
                    title = L("每天自动检查一次"),
                    subtitle = L("打开 App 时（当天还没查过）自动看一眼有没有新版本"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.prefs.updateCheckDaily
                ) { on ->
                    AppCore.prefs.updateCheckDaily = on
                    onChanged()
                }
            )
        )

        // ------------------------------------------------ 开源
        GroupLabel(L("开源"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("GitHub 仓库"),
                    subtitle = UpdateChecker.REPO_URL,
                    subtitleMaxLines = 1,
                    icon = Icons.Filled.Share,
                    onClick = { openUrl(ctx, UpdateChecker.REPO_URL) }
                ),
                RowSpec(
                    title = L("许可证"),
                    subtitle = "GNU General Public License v3.0",
                    icon = Icons.Filled.Info,
                    onClick = { openUrl(ctx, "https://www.gnu.org/licenses/gpl-3.0.html") }
                )
            )
        )

        GroupLabel(L("开源鸣谢"))
        CardGroup(
            CREDITS.map { credit ->
                RowSpec(
                    title = credit.name,
                    subtitle = credit.license,
                    subtitleMaxLines = 1,
                    icon = Icons.Filled.Info,
                    onClick = { openUrl(ctx, credit.url) }
                )
            }
        )

        // ------------------------------------------------ 运行环境
        GroupLabel(L("运行环境"))
        val status = AppCore.server.status()
        CardColumn {
            CardBox {
                KeyValue(L("版本"), full)
                KeyValue(L("设备"), status.device)
                KeyValue(L("工具数量"), L("%s 个（自定义 %s）").format(status.toolCount, status.customToolCount))
                KeyValue(L("MCP 协议"), ServerMeta.PROTOCOL)
                KeyValue(
                    L("Shell 后端"),
                    ShellBackends.available().joinToString(L("、")) { L(it.label) }.ifBlank { L("仅文件操作") }
                )
                KeyValue(L("服务状态"), if (status.running) L("运行中（端口 %s）").format(status.port) else L("已停止"))
                KeyValue(L("允许目录"), status.roots.joinToString("、"))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/* -------------------------------------------------------------- 开发者模式 */

/**
 * 开发者模式：连点关于页图标 7 次解锁的调试入口。
 *
 * 现在只有彩蛋语言的「语言菜单」开关和两个更新相关的调试项 ——
 * 以后要加调试功能，往这里塞一组卡片即可（标题写在页面里面，动画/返回键都跟着走）。
 */
@Composable
private fun DevModePage(
    revision: Int,
    ctx: Context,
    onChanged: () -> Unit,
    onLangChanged: () -> Unit,
    onPreviewUpdate: (UpdateChecker.Info) -> Unit,
    onCloseDev: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // 带上 revision：不然开关点了界面不重组，看着像没生效
    val langMenu = remember(revision) { AppCore.prefs.languageMenu }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = L("开发者模式"),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )

        GroupLabel(L("语言"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("语言菜单"),
                    subtitle = if (langMenu)
                        L("语言列表里已经出现「猫娘语」：设置 → 外观与语言 → 语言")
                    else L("打开后语言列表里才会出现彩蛋语言「猫娘语」"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Star,
                    checked = langMenu
                ) { on ->
                    AppCore.prefs.languageMenu = on
                    Lang.AndroidCatFlag.unlocked = on
                    // 关掉菜单时如果正用着彩蛋语言，就退回跟随系统，免得下拉找不到当前项
                    if (!on && AppCore.prefs.appLang == Lang.CAT) {
                        AppCore.prefs.appLang = "system"
                        Lang.init(ctx, "system", Lang.systemIsEnglish)
                        onLangChanged()
                    }
                    toast(ctx, if (on) L("语言菜单已打开") else L("语言菜单已关闭"))
                    onChanged()
                }
            )
        )

        GroupLabel(L("引导"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("强制进入初始引导"),
                    subtitle = L("立刻重走一遍五步引导，并清掉「已经走过」的记录（下次打开也会进）"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        AppCore.prefs.onboardDone = false
                        OnboardingState.step = 0
                        OnboardingState.visible = true
                    }
                )
            )
        )

        GroupLabel(L("更新"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("预览更新弹窗"),
                    subtitle = if (busy) L("正在检查…取到就用最新 Release 的真实内容")
                    else L("不管当前是什么版本，直接弹一次更新提示，显示 GitHub 上最新的 Release"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                // 忽略版本比较：版本名只在发版时变，否则永远比不过最新 tag
                                val r = withContext(Dispatchers.IO) {
                                    UpdateChecker.check(ignoreVersion = true)
                                }
                                val info = (r as? UpdateChecker.Result.Newer)?.info
                                onPreviewUpdate(info ?: UpdateChecker.sampleInfo())
                                toast(
                                    ctx,
                                    when {
                                        info != null -> L("用最新 Release %s 预览").format(info.tag)
                                        r is UpdateChecker.Result.Failed -> L("没连上 GitHub：%s").format(r.reason)
                                        else -> L("没连上 GitHub，用示例内容预览")
                                    }
                                )
                                busy = false
                            }
                        }
                    }
                ),
                RowSpec(
                    title = L("下次启动强制检查更新"),
                    subtitle = L("清掉「今天已经检查过」的记录，下次打开 App 必定检查一次；查到最新 Release 就弹窗（模拟第一次进入）"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        AppCore.prefs.forceUpdateCheckNext = true
                        AppCore.prefs.lastUpdateCheck = ""
                        toast(ctx, L("下次打开 App 会强制检查一次更新"))
                    }
                )
            )
        )

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                L("这些开关只影响本机，不会改变 AI 能用的工具。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        GroupLabel(L("开发者模式"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("关闭开发者模式"),
                    subtitle = L("入口卡片会消失，连点图标 7 次可再解锁"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Warning,
                    onClick = onCloseDev
                )
            )
        )

        Spacer(Modifier.height(20.dp))
    }
}

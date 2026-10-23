// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.DpOffset
import com.xtt.mcpbox.i18n.L
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.RectangleShape

/* ------------------------------------------------------------------ 小工具 */

/**
 * 运行时长文案（可翻译）。
 *
 * 不用 `ServerMeta.uptimeText()` —— 那个在 core 里拼死了中文单位（"2 分 17 秒"），
 * 英文界面下会变成 "Up 2 分 17 秒" 这种中英混排。
 */
fun uptimeLabel(ms: Long): String {
    val sec = ms / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> L("%s 小时 %s 分").format(h, m)
        m > 0 -> L("%s 分 %s 秒").format(m, s)
        else -> L("%s 秒").format(s)
    }
}

fun copyText(context: Context, text: String, label: String = L("已复制")) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mcpbox", text))
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    }
}

fun toast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}

/** 首尾卡片的大圆角。 */
private val CardCorner = 24.dp

/** 同一组里中间卡片的小圆角 + 卡片之间那道很细的缝。 */
private val InnerCorner = 7.dp
private val GroupGap = 2.dp

private val Tween = tween<androidx.compose.ui.unit.Dp>(durationMillis = 210)

/** 列表分批渲染：首屏先渲染这么多，快滚到底之前自动补下一批。 */
const val PageFirst = 60
const val PageStep = 60

/**
 * 分批渲染的「已渲染条数」。
 *
 * 长列表一次全渲染会卡在首帧，而且滚动时掉帧。这里只渲染前 [initial] 条，
 * 当滚动位置离底部不足约 1.2 屏时（或者内容还没撑满一屏时），下一帧再补
 * [step] 条 —— 每帧只做一批，不会和滚动抢帧，也不给每一项加入场动画
 * （那会变成瀑布式闪烁）。
 *
 * [resetKey] 变化时（换筛选 / 换搜索词）回到首批。
 */
@Composable
fun rememberPagedCount(
    total: Int,
    scroll: ScrollState,
    resetKey: Any? = null,
    initial: Int = PageFirst,
    step: Int = PageStep,
    /** 离底部还有几屏时补下一批。越小越晚补，首帧越轻。 */
    preloadScreens: Float = 1.2f
): Int {
    var shown by remember(resetKey) { mutableStateOf(initial.coerceAtMost(total)) }
    // 数据变少（清空日志 / 换筛选）时夹回范围内
    if (shown > total) shown = total

    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp
    val preload = with(density) { (screenH * preloadScreens).dp.roundToPx() }

    LaunchedEffect(total, resetKey, preload) {
        snapshotFlow { scroll.value to scroll.maxValue }
            .collect { (value, max) ->
                if (shown < total && max - value <= preload) {
                    withFrameNanos { }            // 等这一帧画完再补，别和滚动抢帧
                    shown = (shown + step).coerceAtMost(total)
                }
            }
    }
    return shown
}

/** 分批渲染时列表末尾的一行低调提示，让用户知道下面还有。 */
@Composable
fun PagedHint(shownCount: Int, total: Int) {
    if (shownCount >= total) return
    Text(
        L("已显示 %s / %s 条 · 继续下滑自动加载").format(shownCount, total),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.5.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    )
}

/* ------------------------------------------------------------------ 页面结构 */

/**
 * 页面大标题。
 *
 * 布局和参考的那种一致：**返回按钮在最左**，标题在它右边（大字号）；
 * [actions] 仍然挂在最右（重启服务 / 清屏这类操作）。
 *
 * 滚动收缩由外面的 [PageScaffold] 负责 —— 大标题本身跟着内容一起滚走，
 * 滚到位置后顶上会淡入一条矮栏。
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                RoundIconButton(
                    Icons.Filled.ArrowBack, L("返回"),
                    tint = MaterialTheme.colorScheme.onSurface,
                    size = 44
                ) { onBack() }
                Spacer(Modifier.width(14.dp))
            }
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        if (subtitle != null) {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp, start = if (onBack != null) 58.dp else 0.dp)
            )
        }
    }
}

/**
 * 页面骨架：大标题 + 滚动内容 + 「滚上去之后顶上淡入一条矮栏」。
 *
 * 矮栏只有一条：左边返回按钮（有的话），旁边是标题（小字号），右边是原来的操作按钮。
 * 出现与否由 [scroll] 的位置驱动 —— 往下滚过大标题的一半高度就开始淡入，
 * 所以手指往上推的时候能直接看到标题「收」到顶上。
 *
 * 矮栏背景跟底栏同一套：半透明主题色，开了模糊就用 haze 抓下面的内容；
 * 没开模糊时只是一层半透明底，不额外多一次离屏绘制。
 */
@Composable
fun PageScaffold(
    scroll: ScrollState,
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** 内容本身的底部留白（一般是 20/24dp）。底栏高度由这里自动加。 */
    bottomPadding: Dp = 24.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    // 矮栏的出现时机：大标题滑走**三成**就出来（约二十几 dp 的滚动距离）。
    // 以前要等标题几乎整块滑走（headerPx - 28dp），滚半天才冒出来。
    var headerPx by remember { mutableIntStateOf(0) }
    val trigger = remember(headerPx) { (headerPx * 0.3f).toInt() }
    // 只在跨过阈值那一刻变一次：滚动过程中不重组整页
    val shown = remember(scroll, trigger) {
        derivedStateOf { trigger > 0 && scroll.value > trigger }
    }
    // 出现 / 退场动画：淡入 + 从上方轻轻滑进来
    val appear by animateFloatAsState(
        targetValue = if (shown.value) 1f else 0f,
        animationSpec = tween(durationMillis = if (shown.value) 190 else 140),
        label = "topBarAppear"
    )

    val barStyle = LocalTopBarStyle.current
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    // 模糊的「源」必须是**这一页自己的滚动内容**。
    // 之前顶栏借用的是 MainActivity 那一个 HazeState —— 它在内容层的**祖先**上，
    // 同一个绘制树里自己抓自己是抓不到的，haze 直接画成透明：
    // 于是「不透明度开到 100 还是透明的」。这里在页面内自建一份，源和矮栏是兄弟节点，正常。
    val pageHaze = remember { dev.chrisbanes.haze.HazeState() }
    val useHaze = barStyle.usesHaze
    // 预见式返回的拖动进度（0..1）。取的是 lambda，在 graphicsLayer 里读 ——
    // 每帧只是重绘，不会让整页重组。
    val backProgress = LocalPredictiveDrag.current
    var barHeightPx by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxWidth()) {
        // 底栏可见时它自己盖住了小白条区域，内容只需多留出它的高度往它下面滑；
        // 底栏收起（子页 / 输入法弹出）时才需要自己避开小白条。
        val barInset = LocalBottomBarInset.current
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .then(if (useHaze) Modifier.hazeSource(pageHaze) else Modifier)
                .then(if (barInset <= 0f) Modifier.navigationBarsPadding() else Modifier)
                .padding(bottom = bottomPadding + barInset.dp)
        ) {
            PageHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                actions = actions,
                modifier = Modifier.onSizeChanged { headerPx = it.height }
            )
            content()
        }

        // 顶上那条矮栏：滚过大标题之后出现。
        // 写法就是最普通的那种 —— 一整条铺满（铺到屏幕最顶）、内部再避开状态栏，
        // 末尾一条发丝线。不透明时是实色；半透明 / 有模糊时才走 haze。
        // 作为「露出来的上一页」渲染时不画矮栏：它自己的滚动位置可能早就过了阈值，
        // 画出来就会在拖动过程中凭空多出一条 —— 用户要的是「直接没有」。
        if (!LocalPredictiveBehind.current && appear > 0.001f) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { barHeightPx = it.height }
                    .graphicsLayer {
                        // 出现时从上方轻轻压下来；**预见式返回拖动时整条往上收走**，
                        // 松手返回成功后由新页面按自己的滚动位置决定要不要再出现。
                        translationY = -(1f - appear) * 10.dp.toPx() -
                            backProgress() * barHeightPx
                    }
                    .then(
                        if (useHaze) {
                            Modifier.hazeEffect(pageHaze) {
                                blurRadius = barStyle.blur.dp
                                blurEnabled = barStyle.blur > 0.5f
                                backgroundColor = base
                                // tint 的不透明度跟着动画走：整条是「渐显」而不是「啪」地跳出来
                                alpha = barStyle.alpha * appear
                                noiseFactor = 0.06f
                            }
                        } else {
                            Modifier.background(
                                base.copy(alpha = base.alpha * barStyle.alpha * appear)
                            )
                        }
                    )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 内容避开状态栏；上面的底色已经铺到屏幕最顶
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(46.dp)
                        .padding(start = 14.dp, end = 14.dp)
                        .alpha(appear),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        RoundIconButton(
                            Icons.Filled.ArrowBack, L("返回"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 38
                        ) { onBack() }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    actions()
                }
                Hairline(0.6f * (if (useHaze) barStyle.alpha else 1f) * appear)
            }
        }
    }
}

/**
 * 浮在内容上的一条栏 —— 底栏与全选工具条共用同一份实现。
 *
 * 底色固定用 surfaceContainerLow（和顶栏同色，两条栏看起来是一条体系）；
 * 半透明 / 模糊由 [style] 决定（各自一份参数）：
 *  · 不透明度 1 + 模糊 0 → 纯色底，不额外离屏绘制；
 *  · 其余情况 → hazeEffect 抓下面划过去的内容做毛玻璃，tint 的 alpha 就是不透明度。
 *
 * 注意：调用方**不要**在这层外面再套 `graphicsLayer { alpha = … }` 做淡入 ——
 * alpha 图层会让 hazeEffect 画不出来，表现就是「调多实都是透的」。
 */
@Composable
fun FloatingBar(
    style: BarStyle,
    modifier: Modifier = Modifier,
    hairlineTop: Boolean = false,
    hairlineBottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val hazeState = LocalHazeState.current
    val hazeOn = style.usesHaze && hazeState != null
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (hazeOn) {
                    Modifier.hazeEffect(hazeState!!) {
                        blurRadius = style.blur.dp
                        blurEnabled = style.blur > 0.5f
                        backgroundColor = base
                        alpha = style.alpha
                        // 深色主题里 blur 需要一点噪声，否则糊成一片
                        noiseFactor = 0.06f
                    }
                } else Modifier
            )
            // 不走 haze 时底色乘上不透明度（100% 就是纯色，和以前完全一样）
            .background(if (hazeOn) Color.Transparent else base.copy(alpha = base.alpha * style.alpha))
    ) {
        val lineAlpha = 0.6f * (if (hazeOn) style.alpha else 1f)
        if (hairlineTop) Hairline(lineAlpha)
        content()
        if (hairlineBottom) Hairline(lineAlpha)
    }
}

/** 一条 1dp 发丝线：和内容分开，深浅两套主题下都看得见。 */
@Composable
private fun Hairline(alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
    )
}


@Composable
fun GroupLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp)
    )
}

/* ------------------------------------------------------------ 分组卡片的核心 */

/** 一行卡片的内容描述。 */
data class RowSpec(
    val title: String,
    val subtitle: String? = null,
    val subtitleMaxLines: Int = 2,
    /** 副标题颜色；null = 用默认的 onSurfaceVariant（警示态可以传 Sem.warn）。 */
    val subtitleColor: Color? = null,
    val icon: ImageVector? = null,
    val iconTint: Color? = null,
    val onClick: (() -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    /**
     * 附加在标题/副标题**下面**的自定义内容（例如滑块）。
     * 有了它，带滑块的行也能塞进同一个 [CardGroup]，和后面的开关连成一体 ——
     * 而不用拆成两张独立卡片（那会破坏「一组卡片首尾圆角、中间直角」的规范）。
     */
    val content: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * 这一行现在要不要显示。
     *
     * 和「直接把它从列表里删掉」的区别：传 false 是**带动画收起**（展开同理），
     * 用来做「开关一打开，下面冒出一行」的效果；首尾圆角按剩下的可见行重新算，
     * 所以收起最后一行时上面那行的底部圆角会跟着变回圆的。
     */
    val visible: Boolean = true,
    /**
     * 作为上一行的附属项缩进显示（字号也小一号）。
     * 用来表示「这一项从属于上面那一项」，比如备份里的「包含访问令牌」挂在「设置」下面。
     */
    val indent: Boolean = false
)

/**
 * 一组连续的卡片：
 * 只有最上面一行的顶部圆角、最下面一行的底部圆角是圆的，中间是直角；
 * 按下去的时候直角会带动画地变成圆角，分隔线同时淡出。
 *
 * 行之间的显隐走 [RowSpec.visible]，自带展开 / 收起动画。
 */
@Composable
fun CardGroup(
    rows: List<RowSpec>,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 14,
    /**
     * 给每一行套一层额外修饰符（按 [rows] 的下标）。
     *
     * 目前只有命令规则的拖动排序用它：需要给被拖的那行加位移和 zIndex，同时借
     * `onGloballyPositioned` 量出每行高度。不传就是原来的行为。
     */
    rowModifier: (@Composable (Int) -> Modifier)? = null
) {
    // 首尾圆角只认「可见行」：某行收起后，它上面的行就要接管底部圆角
    val visibleCount = rows.count { it.visible }
    var seen = 0
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding.dp)
    ) {
        rows.forEachIndexed { index, spec ->
            val slot = seen
            if (spec.visible) seen++
            AnimatedVisibility(
                visible = spec.visible,
                // 额外修饰符套在这一层（行的兄弟节点级），拖动时的 zIndex 才压得住邻居
                modifier = rowModifier?.invoke(index) ?: Modifier,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 220),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 150)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 180),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 120)),
                label = "rowVisibility"
            ) {
                GroupRowItem(
                    spec = spec,
                    isFirst = slot == 0,
                    isLast = slot == visibleCount - 1
                )
            }
        }
    }
}

@Composable
private fun GroupRowItem(
    spec: RowSpec,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = spec.onClick != null

    val topRadius by animateDpAsState(
        targetValue = if (pressEd(pressed, clickable) || isFirst) CardCorner else InnerCorner,
        animationSpec = Tween, label = "topRadius"
    )
    val bottomRadius by animateDpAsState(
        targetValue = if (pressEd(pressed, clickable) || isLast) CardCorner else InnerCorner,
        animationSpec = Tween, label = "bottomRadius"
    )
    val shape = RoundedCornerShape(
        topStart = topRadius, topEnd = topRadius,
        bottomStart = bottomRadius, bottomEnd = bottomRadius
    )
    val bg by animateColorAsState(
        targetValue = cardColor(
            if (pressEd(pressed, clickable)) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        animationSpec = tween(160), label = "rowBg"
    )
    Column(modifier.fillMaxWidth()) {
        // 组内就靠这 2dp 的缝隙分开（不再画分隔线）
        if (!isFirst) Spacer(Modifier.height(GroupGap))
        Surface(
            color = bg,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)                      // ripple 与按下底色都跟着圆角走
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(color = MaterialTheme.colorScheme.primary),
                    enabled = clickable
                ) { spec.onClick?.invoke() }
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(
                        start = if (spec.indent) 40.dp else 18.dp,
                        end = 18.dp,
                        top = 14.dp,
                        bottom = 14.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tint = spec.iconTint ?: MaterialTheme.colorScheme.primary
                    if (spec.icon != null) {
                        Icon(spec.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            spec.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = if (spec.indent) 15.sp else 15.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        spec.subtitle?.let {
                            Text(
                                it,
                                color = spec.subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = if (spec.indent) 12.sp else 12.5.sp,
                                lineHeight = 17.sp,
                                maxLines = spec.subtitleMaxLines,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                    spec.trailing?.let {
                        Spacer(Modifier.width(10.dp))
                        it()
                    }
                }
                // 附加内容（例如滑块）：跟在标题下面，和本行同属一张卡片
                spec.content?.let { block ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
                    ) { block() }
                }
            }
        }
    }
}

private fun pressEd(pressed: Boolean, clickable: Boolean) = pressed && clickable
private fun presEd(pressed: Boolean, clickable: Boolean) = pressed && clickable

/** 单张独立卡片（不参与分组圆角）。 */
/* ------------------------------------------------------------ 分类胶囊 */

/**
 * 一排横向滑动的分类胶囊（日志筛选、记忆库文件夹这类「单选用」）。
 *
 * 三种状态：
 *  · 选中 —— 主色实心、全圆；
 *  · 未选中 —— surfaceContainer 底；
 *  · 按下 —— 圆角收成 10dp（选中 / 未选中都收），未选中的底色再亮一档。
 *
 * 放不下就横向滑，右边露半个，暗示还能滑。
 */
@Composable
fun CategoryPillRow(
    items: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    onSelect: (Int) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { index, label ->
            CategoryPill(label, selected == index) { onSelect(index) }
        }
    }
}

/** 单个分类胶囊。选中态见 [CategoryPillRow]。 */
@Composable
fun CategoryPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 圆角四档：
    //  · 选中常态   12dp
    //  · 选中按下   10dp（比常态再收一点）
    //  · 未选中按下 12dp
    //  · 未选中常态 全圆胶囊
    val radius by animateDpAsState(
        targetValue = when {
            selected && pressed -> 10.dp
            selected -> 12.dp
            pressed -> 12.dp
            else -> 26.dp
        },
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        label = "pillCorner"
    )
    val shape = RoundedCornerShape(radius)
    val bg = when {
        // 选中按下：主色提亮一点，不要变成另一种颜色
        selected && pressed -> lerp(scheme.primary, Color.White, 0.12f)
        selected -> scheme.primary
        pressed -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainer
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                // 水波和胶囊按钮一致：主色、裁进当前形状里（形状在按下时会收，跟着一起裁）
                indication = ripple(color = scheme.primary),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(
            text,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CardRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    card: Boolean = true,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    if (card) {
        GroupRowItem(
            spec = RowSpec(
                title = title, subtitle = subtitle, subtitleMaxLines = subtitleMaxLines,
                icon = icon, iconTint = iconTint, onClick = onClick, trailing = trailing
            ),
            isFirst = true, isLast = true
        )
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = MaterialTheme.colorScheme.primary),
                            onClick = onClick
                        )
                    } else Modifier
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = iconTint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp, maxLines = titleMaxLines)
                subtitle?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                        maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.let { Spacer(Modifier.width(10.dp)); it() }
        }
    }
}

/** 好看一点的开关：关闭态不再是一团灰。 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            uncheckedTrackColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHighest),
            uncheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    )
}

/** 生成一个带开关的行（放进 CardGroup 里用）。 */
fun switchSpec(
    title: String,
    subtitle: String? = null,
    subtitleMaxLines: Int = 2,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): RowSpec = RowSpec(
    title = title,
    subtitle = subtitle,
    subtitleMaxLines = subtitleMaxLines,
    icon = icon,
    onClick = { onCheckedChange(!checked) },
    trailing = { AppSwitch(checked, onCheckedChange) }
)

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CardRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        trailing = { AppSwitch(checked, onCheckedChange) }
    )
}

/* ------------------------------------------------------------------ 选择器 */

/**
 * 胶囊下拉：贴着胶囊弹出的锚点小菜单（RikkaHub 那种），
 * 选项 15sp、选中项用主题色并打勾。
 */
/**
 * 「整行可点 + 下拉选择」的规格。
 *
 * 以前只有右侧那个胶囊能点开菜单，现在点整行任意位置都能展开 ——
 * 手指不用去戳那一小块。
 */
@Composable
fun dropdownSpec(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit
): RowSpec {
    var open by remember { mutableStateOf(false) }
    return RowSpec(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { open = true },
        trailing = {
            PillDropdown(
                value = value,
                options = options,
                onSelect = onSelect,
                expanded = open,
                onExpandedChange = { open = it }
            )
        }
    )
}

/** 同上，但直接给下标（省得调用处再算一遍）。 */
@Composable
fun dropdownSpec(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
): RowSpec {
    val value = options.getOrElse(selectedIndex.coerceIn(0, options.lastIndex)) { options.first() }
    return dropdownSpec(title, subtitle, icon, value, options, onSelect)
}

@Composable
fun PillDropdown(
    value: String,
    options: List<String>,
    /** 传了就用外部状态控制展开（配合「点整行也能展开」）；不传则自己管。 */
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelect: (Int) -> Unit
) {
    var innerOpen by remember { mutableStateOf(false) }
    val open = expanded ?: innerOpen
    fun setOpen(v: Boolean) {
        if (expanded == null) innerOpen = v else onExpandedChange?.invoke(v)
    }
    val selected = options.indexOf(value).coerceAtLeast(0)
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f, animationSpec = tween(190), label = "chevron"
    )
    Box {
        Surface(
            color = chromeColor(MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(50),
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { setOpen(true) }
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevron }
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { setOpen(false) },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(20.dp),
            offset = DpOffset(0.dp, 4.dp)
        ) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (index == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = {
                        if (index == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onClick = {
                        setOpen(false)
                        onSelect(index)
                    }
                )
            }
        }
    }
}

/** 居中的单选框（圆角大卡 + 勾）。 */
@Composable
/**
 * 单选列表弹窗（原生 Dialog）。
 *
 * @param selected 当前选中项的下标。
 * @param deletable 哪些行允许删除（默认都不允许）。给了 [onDelete] 才会出现垃圾桶。
 * @param onDelete 点垃圾桶时回调（下标）。传 null 表示这个弹窗不支持删除。
 */
fun ChoiceDialog(
    title: String?,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    deletable: (Int) -> Boolean = { false },
    deleteLabel: String = L("删除"),
    onDelete: ((Int) -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                if (title != null) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
                    )
                }
                options.forEachIndexed { index, label ->
                    val active = index == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(
                                start = 22.dp,
                                end = if (onDelete != null) 14.dp else 22.dp,
                                top = 13.dp,
                                bottom = 13.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (onDelete != null && deletable(index)) {
                            Spacer(Modifier.width(10.dp))
                            RoundIconButton(
                                Icons.Filled.Delete,
                                deleteLabel,
                                tint = MaterialTheme.colorScheme.error,
                                size = 34
                            ) { onDelete(index) }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 小零件 */

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Int = 40,
    onClick: () -> Unit
) {
    Surface(
        color = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = CircleShape,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size((size / 2.3).dp))
        }
    }
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    outlined: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // 禁用态：实心胶囊退成灰底 + 灰字，描边胶囊只是整体变灰
    val tint = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant
    val fill = if (enabled) color else chromeColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    Surface(
        color = if (outlined || !enabled) Color.Transparent else fill,
        shape = RoundedCornerShape(50),
        border = if (outlined) BorderStroke(1.dp, tint.copy(alpha = 0.7f)) else null,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Box(
            Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (outlined || !enabled) tint else contentColor,
                fontSize = if (compact) 12.5.sp else 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TagPill(text: String, color: Color, filled: Boolean = true) {
    Surface(
        color = if (filled) color.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(50),
        border = if (filled) null else BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cardColor(color),
        shape = RoundedCornerShape(CardCorner)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), content = content)
    }
}

@Composable
fun KeyValue(k: String, v: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
            modifier = Modifier.width(92.dp)
        )
        Text(
            v, color = valueColor, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
    }
}

@Composable
fun InnerDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    )
}

data class NavItem(val label: String, val icon: ImageVector)

/**
 * 底栏。
 *
 * 造型参考主流 App（B 站那种）：
 *  - 选中态是**图标外面一圈胶囊底色**，文字在下面、不参与选中底色；
 *  - 切 tab 时不做缩放，只换颜色和底色；
 *  - 整条浮在页面之上（顶部分割线 + 低一档的底色），内容避开系统小白条、底色铺到屏幕最底。
 */
@Composable
fun BottomPillNav(
    items: List<NavItem>,
    selected: Int,
    /** 底栏样式（不透明度 / 模糊），默认全不透明 = 原来的样子。 */
    style: BarStyle = BarStyle.Opaque,
    /** 实测高度（dp）：覆盖式布局靠它算内容末尾的留白，硬编码会因字体大小 / 手势条高度对不上。 */
    onHeight: (Float) -> Unit = {},
    onSelect: (Int) -> Unit
) {
    // 底栏和顶栏共用同一个浮层实现（[FloatingBar]）：同样的底色、同样的透明度 / 模糊算法，
    // 只是两边各传一份 [BarStyle]。这里只管内容和「量高度」。
    val density = LocalDensity.current
    FloatingBar(
        style = style,
        hairlineTop = true,
        modifier = Modifier.onSizeChanged { onHeight(with(density) { it.height.toDp().value }) }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 内容避开小白条，底色仍然铺到屏幕最底
                .windowInsetsPadding(WindowInsets.navigationBars)
                // 整条高度对齐改前 / 改后的中间值（≈93dp，含小白条）：top 8 + 内容 54 + bottom 6
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BottomNavItem(items, selected, onSelect)
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    items.forEachIndexed { index, item ->
        val active = index == selected
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        // 只有图标外面那一圈胶囊跟着状态变；整格**不画任何底色**，
        // 否则就会出现「一个框包着图标、另一个框包着图标+文字」的两层嵌套
        val iconBg by animateColorAsState(
            targetValue = when {
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                pressed -> barColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                else -> Color.Transparent
            },
            animationSpec = tween(150), label = "navIconBg"
        )
        Box(Modifier.weight(1f)) {
            Column(
        Modifier
            .fillMaxWidth()
            // indication = null：整格不画涟漪（免得出现「一个框套另一个框」），
            // 波纹改挂在下面那个图标胶囊上，仍是跟着整格的按压走
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onSelect(index) }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
            ) {
        // 选中底色只包图标这一块；涟漪也裁在这一层，超不出胶囊圆角
        Box(
            Modifier
                .size(width = 58.dp, height = 32.dp)
                .clip(RoundedCornerShape(50))
                .background(iconBg)
                .indication(
                    interactionSource = interaction,
                    indication = ripple(color = MaterialTheme.colorScheme.primary)
                ),
            contentAlignment = Alignment.Center
        ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 「再点一下底栏当前 tab」触发的滚动动作（首页 / 权限 / 日志 / 设置回顶，终端回底）。
 *
 * 关键在于**首帧不响应**：`remember` 记下进入组合那一刻的 tick，只有页面活着的时候
 * tick 真的变了才执行。之前是各页写 `if (scrollTopTick > 0)`，
 * 结果是点过一次之后那一格永远 > 0，切到任何页面（重新组合）都会被甩到顶上。
 */
@Composable
fun NavReselectEffect(tick: Int, action: suspend () -> Unit) {
    var lastHandled by remember { mutableStateOf(tick) }
    LaunchedEffect(tick) {
        if (tick != lastHandled) {
            lastHandled = tick
            action()
        }
    }
}

@Composable
fun CardColumn(
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 14,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content
    )
}

@Composable
fun VSpace(dp: Int) {
    Spacer(Modifier.height(dp.dp))
}

@Composable
fun CodeBlock(text: String) {
    Surface(
        color = cardColor(MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
fun OutlineTag(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * 弹窗用的滚动容器：内容少时贴合内容高度，内容多时最多占屏幕的 [fraction]（默认 78%）。
 * 重写 onMeasure 而不是用 post{} 读高度，避免"时好时坏"的测量时机问题。
 */
@Composable
fun MaxTvScrollView(
    fraction: Float = 0.78f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val screenH = LocalConfiguration.current.screenHeightDp
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = (screenH * fraction).dp)
            .verticalScroll(rememberScrollState())
    ) { content() }
}

/* -------------------------------------------------------------- 可按的卡 */

/**
 * 可点击的卡片：按下时底色 160ms 渐变到 `surfaceContainerHighest`，ripple 用 primary。
 *
 * 规范里的两条硬要求都在这里落实：可点击必须**有反馈**；ripple 必须**先 clip 到形状里** ——
 * 直接给 `Surface(shape = …)` 的 modifier 挂 clickable，按压高亮会溢出成方块。
 */
@Composable
fun PressCardBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CardCorner),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = cardColor(
            if (pressed && enabled) MaterialTheme.colorScheme.surfaceContainerHighest else color
        ),
        animationSpec = tween(160), label = "cardBg"
    )
    Surface(
        color = bg,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 选择型页面（备份 / 恢复）的底栏：
 * 左边「全选 / 全不选」两个圆形按钮，右边一个主操作胶囊。
 *
 * 和 [BottomPillNav] 同款：底色比页面低一档 + 顶部一条细分割线，
 * 让底栏看起来是「浮在内容上面的一层」，而不是页面里的一块。
 */
@Composable
fun SelectionBar(
    actionText: String,
    actionColor: Color = MaterialTheme.colorScheme.primary,
    actionEnabled: Boolean = true,
    selectAllDesc: String = L("全选"),
    selectNoneDesc: String = L("全不选"),
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onAction: () -> Unit
) {
    // 和底部导航栏共用同一套样式：它是浮在列表上的工具条，透出来的是列表内容
    val style = LocalBarStyle.current
    val hazeState = LocalHazeState.current
    val hazeOn = style.usesHaze && hazeState != null
    val barBase = MaterialTheme.colorScheme.surfaceContainerLow
    val hazeMod = if (hazeOn) {
        Modifier.hazeEffect(hazeState!!) {
            blurRadius = style.blur.dp
            blurEnabled = style.blur > 0.5f
            backgroundColor = barBase
            alpha = style.alpha
            noiseFactor = 0.06f
        }
    } else Modifier
    val plainColor = if (hazeOn) Color.Transparent else barColor(barBase)
    Column(
        Modifier
            .fillMaxWidth()
            .then(hazeMod)
            .background(plainColor)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        )
        Row(
            Modifier
                .fillMaxWidth()
                // 内容避开小白条，底色铺到屏幕最底
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoundIconButton(
                Icons.Filled.Done, selectAllDesc,
                tint = MaterialTheme.colorScheme.primary, size = 44
            ) { onSelectAll() }
            RoundIconButton(
                Icons.Filled.Clear, selectNoneDesc,
                tint = MaterialTheme.colorScheme.primary, size = 44
            ) { onSelectNone() }
            Spacer(Modifier.weight(1f))
            PillButton(
                actionText,
                color = actionColor,
                compact = true,
                enabled = actionEnabled
            ) { onAction() }
        }
    }
}

/* ------------------------------------------------------- 预见式返回动画 */

/**
 * 给**页面自己**铺一层底。
 *
 * 为什么不能只靠外面那层（Scaffold / PredictiveBackBox）兜背景：
 *  · [androidx.compose.animation.AnimatedContent] 转场时**新旧两页同时存在**，两页都贴在
 *    同一个底上、各自又没有底色 —— 中间重叠的那块就会**互相透出文字**（看着像两层字叠在一起）
 *  · 退回时旧页还要淡出（fadeOut），半透明的时候更是直接把下面那页的字透出来
 *
 * 所以：**每个页面分支自己带底**（这个 + [pageSlide] 里去掉淡出，两件一起才是干净的）。
 *
 * 自定义背景（[LocalBackdrop]）开着时这里分三种情况：
 *  · **图片 + 预见式返回动画开着**：铺「图 + 遮罩」。图不透明 → 转场时两页不会互相透字；
 *    遮罩是幂等的（先画的会被后画的不透明图盖掉），嵌套的页面栈也不会叠深。
 *  · **其它情况**（视频 / 关掉预见式返回动画的图片）：什么都不铺 —— 图 / 视频和遮罩都在最外层的
 *    [BackdropLayer] 上，页面上再铺一层遮罩就会和它叠起来（那正是「视频的遮罩看着很奇怪」的原因）。
 *  · **这一页不该显示背景**（子页 + 「子页也显示背景」关掉）：铺不透明底色盖住。
 *
 * @param sub 这一页是不是**子页**（工具管理 / 记忆库 / 设置子页…），受「子页也显示背景」控制。
 */
@Composable
fun PageSurface(sub: Boolean = false, content: @Composable () -> Unit) {
    val bd = LocalBackdrop.current
    Box(Modifier.fillMaxSize()) {
        when {
            !bd.active -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )

            bd.showsOn(sub) -> BackdropPageSurface(bd)

            else -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
        content()
    }
}

/**
 * 页面栈的方向：层级变大 = 往里进（新页从右侧滑入），变小 = 往回退。
 *
 * **别拿「页面 key 是不是空串」当方向判断**：设置页 / 关于页的根页 key 恰好是空串，
 * 那么写能跑对；工具页和记忆库页的根页 key 是 `"list"`（不是空串），
 * 于是永远被判成「往里进」—— 关详情时页面又从右边滑进来，看着和别的页面相反。
 *
 * [depth] 由各页面自己给：单层的给 `if (it == ROOT) 0 else 1`，多层（设置页）按路径算。
 */
fun pageForward(target: String, initial: String, depth: (String) -> Int): Boolean =
    depth(target) >= depth(initial)

/** 手指拖动时页面位移相对系统进度的比例。 */
const val PREDICTIVE_DRAG_SCALE = 0.34f

/**
 * 跟手提交时，正在退出的那一页已经偏移了多少（0..1 屏宽）；普通返回是 0。
 *
 * 作用：提交后位移由**退出的那一页自己**接着带走（从当前位置一路滑出屏幕），
 * 这样新页面就是**原地出现**的，而不是被整块内容平移着推出来 —— 否则会看到「返回完成后左右动一下」。
 */
val LocalPredictiveCommitDrag = compositionLocalOf { 0f }

/**
 * 预见式返回的拖动进度 0..1（0 = 没拖，1 = 拖满）。
 *
 * 给**顶栏**用：手指往右拖的时候顶栏跟着往上收走，不要跟着内容一起横移出去。
 * 故意给的是 lambda 而不是 Float —— 调用方都在 `graphicsLayer { }` 里读，
 * 拖动时每帧只重绘那一条，不会让整页重组。
 */
val LocalPredictiveDrag = compositionLocalOf<() -> Float> { { 0f } }

/**
 * 这一页是不是正在充当「预见式返回时**露出来的上一页**」。
 *
 * 拖动时下面那页是第二个实例（见 [PredictiveBackBox] 的 `behind`），它有自己的滚动位置，
 * 可能已经超过顶栏出现的阈值 —— 那就会凭空多出一条矮栏。这种页面**不画顶部矮栏**。
 */
val LocalPredictiveBehind = compositionLocalOf { false }

/**
 * 页面栈的通用转场（各页的 `transitionSpec` 都调它，别各写一份）。
 *
 *  · 往里进：新页从右侧滑入 1/3 屏宽 + 淡入，旧页往左滑 1/6 + 淡出
 *  · 往回退：新页从左侧滑入 1/3 + 淡入，旧页往右滑 1/6 + 淡出
 *  · **跟手提交**（[commitDrag] > 0）：退出的那一页在切换前**已经由 [PredictiveBackBox] 滑出屏幕**了，
 *    所以这里两边都不演动画（新页原地出现、旧页直接收掉）——
 *    否则旧页会「跳回屏幕中间再淡出」，新页还会被平移着推出来（用户看到的就是「返回完成后左右动一下」）。
 */
fun pageSlide(entering: Boolean, commitDrag: Float = 0f, noOverlap: Boolean = false): ContentTransform {
    if (!entering && commitDrag > 0f) return EnterTransition.None togetherWith ExitTransition.None
    val slide = if (entering) 1 else -1
    val enter: EnterTransition =
        slideInHorizontally(tween(300)) { w -> slide * w / 3 } + fadeIn(tween(220))
    // 视频背景：页面自己是**透明**的（视频在最底下一层，见 ui/Backdrop.kt），
    // 两页重叠就一定会互相透出文字（以前每页自带不透明底色，所以盖得住）。
    // 视频没法每页复制一份 → 那就**不让两页重叠**：退出的那一页直接收掉，不演滑出 / 淡出。
    if (noOverlap) return enter togetherWith ExitTransition.None
    val exit: ExitTransition =
        slideOutHorizontally(tween(260)) { w -> -slide * w / 6 } + fadeOut(tween(180))
    return enter togetherWith exit
}

/**
 * 页面是不是**透明**的 —— 转场 / 拖动都要走不重叠的那套（见 [pageSlide] / [PredictiveBackBox]）。
 *
 * 两种情况：视频（一页一个解码器太重，只能全局一份），以及**关掉预见式返回动画**时的图片
 * （和视频完全同一套渲染）。只有「图片 + 预见式返回动画开着」时页面才自己画图（见 [BackdropState.perPageImage]）。
 */
@Composable
fun rememberTransparentPages(): Boolean {
    val bd = LocalBackdrop.current
    return bd.active && !bd.perPageImage
}

/**
 * 预见式返回动画（Android 13+ 的 predictive back）。
 *
 * 手指从屏幕边缘往右拖时，[content] 跟着手指往右平移（下面露出 [behind]），松手按系统判定：
 * 提交就把这一页滑出去并调 [onBack]；取消就弹回原位。
 *
 * 参数：
 *  · [handleBack] —— 这一层要不要**接管返回**。自己确实有上一级时才传 true；
 *    已经在根页了就传 false，把返回**交还给外面那一层**（最外层没人接时系统就退出应用）
 *  · [follow] —— 拖动时**跟手**（设置开关 + 这一层确实有上一页可回）；关掉退回普通 [BackHandler]
 *  · [behind] —— 拖动/滑出时**露出来的那一页**（一般是这个栈的根页）
 *
 * **三个坑（都踩过）**：
 *  ① 收尾（滑出 + onBack）必须跑在**这个 composable 自己的**协程里（下面的 [LaunchedEffect]）。
 *     手势回调那个协程在页面被替换 / [follow] 一变就会被取消，动画到不了终点 →
 *     页面永久偏在屏幕外，表现就是「按返回整页变黑」。
 *  ② 被拖动的这一层要**自己铺底色**，否则会透出下面的 [behind]，看着像两层叠在一起。
 *  ③ 提交后**别让整块内容平移回 0**：先把这一页顺着方向滑出屏幕（位移归 1），
 *     再切状态 —— 这样新页面是**原地出现**的（见 [pageSlide] 的 `commitDrag` 分支）。
 *
 * 跟手的前提是清单里的 `android:enableOnBackInvokedCallback="true"`（编译期写死、App 里改不了）。
 */
@Composable
fun PredictiveBackBox(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    handleBack: Boolean = true,
    follow: Boolean = true,
    behind: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val drag = remember { Animatable(0f) }            // 0..1：内容整体右移多少屏宽
    var commitDrag by remember { mutableFloatStateOf(0f) }
    var commitTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 底色：有自定义背景时留空（页面自己那一层铺遮罩，只铺一次 —— 盒子是嵌套的，
    // 这里再铺一遍会和页面那层叠起来，滑块调出来的透明度就不准了）
    val bg = backdropBoxColor(LocalBackdrop.current)
    // 页面透明时（视频 / 关掉预见式返回动画），铺预览层会在被拖动的那一页**下面**透出上一页的文字
    // （用户看到的「切换页面时下面闪一下的文字」）→ 这种模式下干脆不铺预览，
    // 拖出来的那片区域显示最底下的背景。
    val showBehind = !rememberTransparentPages()

    // 兜底：不再跟手（回到根页 / 开关关掉）时位移一定归零
    LaunchedEffect(follow) {
        if (!follow && drag.value != 0f) drag.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
    }

    if (handleBack) {
        if (follow) {
            PredictiveBackHandler(enabled = true) { events ->
                try {
                    events.collect { e ->
                        drag.snapTo(e.progress.coerceIn(0f, 1f) * PREDICTIVE_DRAG_SCALE)
                    }
                    // 松手提交：只记一个号，收尾交给下面的 LaunchedEffect（这个协程随时会被取消）
                    commitTick++
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    scope.launch { drag.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
                    throw cancel
                }
            }
        } else {
            BackHandler(enabled = true) { onBack() }
        }
    }

    LaunchedEffect(commitTick) {
        if (commitTick == 0) return@LaunchedEffect
        // 先让这一页顺着方向滑出去（这一段只有内容在动，behind 是静止的，所以看着就是「上一页露出来」）
        drag.animateTo(1f, tween(170, easing = FastOutLinearInEasing))
        commitDrag = 1f
        // 状态切到上一页：位移交给退出的那一页自己带走，所以这里立刻归零（新页原地出现）
        onBack()
        drag.snapTo(0f)
        delay(400)                        // 等转场演完，再把 commitDrag 复位
        commitDrag = 0f
    }

    CompositionLocalProvider(
        LocalPredictiveCommitDrag provides commitDrag,
        LocalPredictiveDrag provides {
            (drag.value / PREDICTIVE_DRAG_SCALE).coerceIn(0f, 1f)
        }
    ) {
        Box(modifier.fillMaxSize().background(bg)) {
            // 拖动时下面露出上一页（它自己铺底色，免得和上层叠在一起）。
            // **提交后也要继续垫着**（commitDrag > 0）：这段时间真页面正在重新组合，
            // 撤早了中间会空一下 —— 看起来就是「返回的时候底下没有背景」。
            if (behind != null && showBehind && (drag.value > 0f || commitDrag > 0f)) {
                Box(Modifier.fillMaxSize().background(bg)) {
                    // 预览层的页面不画顶部矮栏（见 [LocalPredictiveBehind]）
                    CompositionLocalProvider(LocalPredictiveBehind provides true) { behind() }
                }
            }
            // 被拖动 / 退出的这一层：**必须自己铺底色**（页面自己的 [PageSurface] 也算），
            // 页面自己的根 Column 大多没有背景（设置子页、工具列表都是），不铺的话
            // 它一透明就把下面预览层的内容透出来 —— 真机表现就是「透过下面的文字」，两层叠字。
            // 自定义背景开着时这里留空（透明），遮罩交给页面自己那一层，只铺一次。
            //
            // ⚠️ 顺序不能反：修饰符是「左边的包住右边的」，`graphicsLayer` 必须写在 `background`
            // **前面**（背景画在图层里面 → 跟着平移）。写成 .background().graphicsLayer{} 的话，
            // 底色画在图层外面：内容走了、底色不动，而且那层不动的底色会把下面的预览页整个盖住
            // （真机表现：拖动时左边一片空、看不到上一页）。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = drag.value * size.width }
                    .background(bg)
            ) { content() }
        }
    }
}

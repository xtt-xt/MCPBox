// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/* ------------------------------------------------------------------ 小工具 */

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
    step: Int = PageStep
): Int {
    var shown by remember(resetKey) { mutableStateOf(initial.coerceAtMost(total)) }
    // 数据变少（清空日志 / 换筛选）时夹回范围内
    if (shown > total) shown = total

    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp
    val preload = with(density) { (screenH * 1.2f).dp.roundToPx() }

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

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
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
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
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
    val icon: ImageVector? = null,
    val iconTint: Color? = null,
    val onClick: (() -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null
)

/**
 * 一组连续的卡片：
 * 只有最上面一行的顶部圆角、最下面一行的底部圆角是圆的，中间是直角；
 * 按下去的时候直角会带动画地变成圆角，分隔线同时淡出。
 */
@Composable
fun CardGroup(
    rows: List<RowSpec>,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 14
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding.dp)
    ) {
        rows.forEachIndexed { index, spec ->
            GroupRowItem(
                spec = spec,
                isFirst = index == 0,
                isLast = index == rows.lastIndex
            )
        }
    }
}

@Composable
private fun GroupRowItem(spec: RowSpec, isFirst: Boolean, isLast: Boolean) {
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
        targetValue = if (pressEd(pressed, clickable)) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(160), label = "rowBg"
    )
    Column(Modifier.fillMaxWidth()) {
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
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
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
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    spec.subtitle?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
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
        }
    }
}

private fun pressEd(pressed: Boolean, clickable: Boolean) = pressed && clickable
private fun presEd(pressed: Boolean, clickable: Boolean) = pressed && clickable

/** 单张独立卡片（不参与分组圆角）。 */
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
            spec = RowSpec(title, subtitle, subtitleMaxLines, icon, iconTint, onClick, trailing),
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
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
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
    var anchorWidth by remember { mutableStateOf(0) }

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .onGloballyPositioned { anchorWidth = it.size.width }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = MaterialTheme.colorScheme.primary)
                ) { setOpen(true) }
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

        M3DropdownMenu(
            expanded = open,
            onDismiss = { setOpen(false) },
            minWidthPx = anchorWidth
        ) {
            options.forEachIndexed { index, label ->
                MenuRow(label, index == selected) {
                    setOpen(false)
                    onSelect(index)
                }
            }
        }
    }
}

/** 居中的单选框（圆角大卡 + 勾）。 */
@Composable
fun ChoiceDialog(
    title: String?,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val rowBg by animateColorAsState(
                        targetValue = if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest
                        else Color.Transparent,
                        animationSpec = tween(160), label = "menuItemBg"
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .clickable(
                                interactionSource = interaction,
                                indication = ripple(color = MaterialTheme.colorScheme.primary),
                                onClick = { onSelect(index) }
                            )
                            .padding(horizontal = 22.dp, vertical = 13.dp),
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
    onClick: () -> Unit
) {
    Surface(
        color = if (outlined) Color.Transparent else color,
        shape = RoundedCornerShape(50),
        border = if (outlined) BorderStroke(1.dp, color.copy(alpha = 0.7f)) else null,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
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
                color = if (outlined) color else contentColor,
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
        color = color,
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

/** 底栏：只有选中的那格有胶囊底色，其余透明（底部一条细分割线，整体抬高一点）。 */
@Composable
fun BottomPillNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
        val bg by animateColorAsState(
            targetValue = when {
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                pressed -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> Color.Transparent
            },
            animationSpec = tween(150), label = "navBg"
        )
        // 选中项轻微放大、胶囊底色淡入，切换时整排都有个舒服的过渡
        val scale by animateFloatAsState(
            targetValue = if (active) 1f else 0.90f,
            animationSpec = tween(190), label = "navScale"
        )
        Box(Modifier.weight(1f)) {
            Surface(
                color = bg,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))     // 按压/选中底色都限制在胶囊内
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = MaterialTheme.colorScheme.primary)
                    ) { onSelect(index) }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
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

/* ------------------------------------------------------------ 弹窗 / 浮层 */

/**
 * 统一弹窗容器：28dp 圆角 + 屏高 78% 限高，
 * 进入 300ms 减速（alpha + scale 0.90→1 + 下移 12dp），退出 150ms 加速。
 *
 * 退出是「先播动画再卸载」，所以不会闪；content 拿到的是 `close()`，
 * 确认 / 取消 / 点外部都走它，两条路径的观感一致。
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    horizontalPadding: Int = 12,
    content: @Composable (close: () -> Unit) -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) { shown = true }

    Dialog(onDismissRequest = { shown = false }) {
        LaunchedEffect(shown) {
            if (!shown) {
                delay(150)                 // 等退出动画播完再让外面卸载
                onDismiss()
            }
        }
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(300)) +
                scaleIn(tween(300), initialScale = 0.90f) +
                slideInVertically(tween(300)) { with(density) { 12.dp.roundToPx() } },
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.98f)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding.dp)
            ) {
                content { shown = false }
            }
        }
    }
}

/**
 * 下拉菜单：200ms 减速淡入 + 下移 4dp，退出 150ms。
 *
 * 为什么要自己写：M3 的 `DropdownMenu` 时长不可配，而规范里下拉菜单是明确的一档
 * （进入 200 / 退出 150、位移 4dp）。定位逻辑照抄系统菜单：优先贴锚点下方，
 * 放不下就翻到上方，左右夹在屏幕内。
 */
@Composable
fun M3DropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    minWidthPx: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    var mounted by remember { mutableStateOf(expanded) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            withFrameNanos { }             // 先挂上去，下一帧再播进入动画
            shown = true
        } else if (mounted) {
            shown = false
            delay(150)
            mounted = false
        }
    }
    if (!mounted) return

    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val minWidth = with(density) { minWidthPx.toDp() }

    Popup(
        popupPositionProvider = remember(gap) { MenuPositionProvider(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) +
                slideInVertically(tween(200)) { with(density) { 4.dp.roundToPx() } },
            exit = fadeOut(tween(150))
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = modifier
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .padding(vertical = 6.dp)
                ) {
                    // 撑一下最小宽度，让菜单不比它挂着的胶囊还窄
                    Spacer(Modifier.width(minWidth))
                    content()
                }
            }
        }
    }
}

/** 菜单项：高 44，左右 padding 16 / 12，选中项用 primary 色（不画勾，宽度才齐）。 */
@Composable
fun MenuRow(text: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
            .padding(horizontal = 16.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 15.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            softWrap = false
        )
    }
}

private class MenuPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val x = anchorBounds.left.coerceIn(gap, maxX)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - popupContentSize.height - gap
        val maxY = (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
        val y = when {
            below <= maxY -> below
            above >= gap -> above
            else -> maxY
        }
        return IntOffset(x, y)
    }
}

/**
 * 和系统 `AlertDialog` 长得一样，但进出场按规范来：进入 300ms 减速、退出 150ms 加速。
 * 内容多的时候正文区可以滚（限高 78%），按钮永远在限高区外 —— 不会被顶出屏幕。
 */
@Composable
fun AppAlertDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
    dismissText: String = L("取消"),
    mono: Boolean = false
) {
    AppDialog(onDismiss = onDismiss) { close ->
        MaxTvScrollView(fraction = 0.78f) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    dismissText, Modifier.weight(1f), outlined = true,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, compact = true
                ) { close() }
                PillButton(
                    confirmText, Modifier.weight(1f),
                    color = confirmColor, compact = true
                ) {
                    close()
                    onConfirm()
                }
            }
        }
    }
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
        targetValue = if (pressed && enabled) MaterialTheme.colorScheme.surfaceContainerHighest else color,
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

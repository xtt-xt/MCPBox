// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.HeatDay
import com.xtt.mcpbox.core.HeatScale
import com.xtt.mcpbox.core.StatsSnapshot
import com.xtt.mcpbox.core.heatWeeks
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

/**
 * 设置 → 统计。
 *
 * 内容就三样（都是**只增不减**的长期记录）：
 *  - 请求热力图：每天有多少条 MCP 消息（GitHub 那种格子图，一年一屏，可以左右滑）
 *  - 累计运行时长 / 服务器启动次数
 *  - 打开应用次数
 *
 * 数据由 AppCore.stats 管，存在 files/stats/stats.json —— 不在 SharedPreferences 里，
 * 所以「重置全部设置」不会把它抹掉；备份里的「统计」能把它整个带走。
 */
@Composable
fun StatsSettingsPage(onBack: () -> Unit) {
    // 服务器在跑的时候「累计运行时长」是活的：每秒刷新一次，看着它涨
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val snap = remember(now / 1000L) { AppCore.stats.snapshot() }

    // 自检：按天相加应当等于总数。不等说明聚合漏了/去重了 —— 只在真的不等时记一条，
    // 记在「日志」页的系统分类里，平时不会有人看到。
    LaunchedEffect(snap.requestsTotal) {
        if (snap.requestsTotal != snap.dailySum) {
            AppCore.log.add(
                com.xtt.mcpbox.core.LogKind.ERROR,
                tool = "stats",
                ok = false,
                message = "统计按天相加 %s 与总数 %s 不一致".format(snap.dailySum, snap.requestsTotal)
            )
        }
    }

    SettingsPageShell(L("统计"), L("请求次数、运行时长与启动次数（只增不减）"), onBack) {
        GroupLabel(L("请求"))
        HeatCard(snap)

        GroupLabel(L("累计"))
        NumberGrid(snap)

        // 这一页只放数据：底部那段「服务器正在运行 / 统计只记在本机…」的说明已去掉，
        // 免得每次进来都得先划过去
        Spacer(Modifier.height(16.dp))
    }
}

/* ------------------------------------------------------------------ 热力图 */

/**
 * 请求热力图。
 *
 * 一年 = 53 列 × 7 行，一屏放不下 —— 所以默认滚到最右边（今天），往左滑看以前。
 * 记录还不多（不到一年）的时候格子会自动放大铺满整宽，不会出现一小撮挤在右边。
 */
@Composable
private fun HeatCard(snap: StatsSnapshot) {
    val today = LocalDate.now()
    // 至少画一年；老数据比一年还早（一直在用）就往前延
    val yearAgo = today.minusDays(364)
    val earliest = snap.requestsByDay.keys.minOrNull()
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val from = if (earliest != null && earliest.isBefore(yearAgo)) earliest else yearAgo
    val weeks = heatWeeks(snap.requestsByDay, today, from)
    // 色阶按「画出来的这一段」里的每一天算：不把一年前还在用的老数据掺进来
    val scale = HeatScale.of(weeks.flatten().filterNotNull().map { it.count })

    CardColumn {
        CardBox {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("请求热力图"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    L("共 %s 次").format(snap.requestsTotal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            HeatGrid(weeks, scale)
            Spacer(Modifier.height(10.dp))
            HeatLegend()
        }
    }
}

@Composable
private fun HeatGrid(weeks: List<List<HeatDay?>>, scale: HeatScale) {
    val gap = 3.dp
    val minCell = 13.dp
    val monthRow = 18.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 能铺满就铺满，铺不下（一年）就按最小尺寸排、横向滚动
        val fit = (maxWidth - gap * (weeks.size - 1).coerceAtLeast(0)) / weeks.size.coerceAtLeast(1)
        val scrollable = fit < minCell
        val cell: Dp = if (scrollable) minCell else fit

        val state = rememberScrollState()
        LaunchedEffect(weeks.size, scrollable) {
            if (!scrollable) return@LaunchedEffect
            // 等这一帧量完（maxValue 有值）再滚到最右 —— 打开就是今天
            var tries = 0
            while (state.maxValue == 0 && tries < 30) {
                withFrameNanos { }
                tries++
            }
            state.scrollTo(state.maxValue)
        }

        Row {
            // 左边星期标签：不跟着横滑。字号给够 + 行高放开，别压成一横
            Column(Modifier.width(18.dp).padding(top = monthRow)) {
                repeat(7) { i ->
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (i % 2 == 0) shortDay(L(DAY_KEYS[i])) else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 1
                        )
                    }
                    if (i < 6) Spacer(Modifier.height(gap))
                }
            }
            Spacer(Modifier.width(6.dp))

            Box(Modifier.weight(1f)) {
                Column(Modifier.horizontalScroll(state)) {
                    // 月份标签：只在该月第一列写一次
                    Row(Modifier.height(monthRow)) {
                        var lastMonth = -1
                        weeks.forEach { week ->
                            val d = week.firstOrNull { it != null }?.date
                            Box(Modifier.width(cell + gap)) {
                                if (d != null && d.monthValue != lastMonth) {
                                    lastMonth = d.monthValue
                                    Text(
                                        L("%s月").format(d.monthValue),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        lineHeight = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.width(44.dp)
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        weeks.forEach { week ->
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                week.forEach { day ->
                                    HeatCell(cell, day, scale)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(cell: Dp, day: HeatDay?, scale: HeatScale) {
    val level = if (day == null) -1 else scale.level(day.count)
    Box(
        Modifier
            .size(cell)
            .clip(RoundedCornerShape(3.dp))
            .background(heatColor(level))
    )
}

/** 0（没有）→ 最浅；1..4 由浅到深。范围外（[level] = -1）什么都不画。 */
@Composable
private fun heatColor(level: Int): Color {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    return when (level) {
        -1 -> Color.Transparent
        0 -> empty
        // 起点给到 0.45：不然第 1 档和「没有请求」几乎分不出来。
        // 最高档给满 primary（5 级 → 0.45 + 0.55 * (level-1)/4）
        else -> lerp(empty, primary, 0.45f + 0.55f * (level - 1) / (HeatScale.LEVELS - 1).toFloat())
    }
}

/**
 * 图例：少 → 多 五个色块。
 *
 * 不在界面上写口径说明（用户明确不要那种注释）；「多少」是相对分档，
 * 具体算法见 [heatLevel]，需要时在对话里解释。
 */
@Composable
private fun HeatLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(L("少"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        (0..HeatScale.LEVELS).forEach { level ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(heatColor(level))
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(L("多"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

/* ------------------------------------------------------------------ 数字卡 */

@Composable
private fun NumberGrid(snap: StatsSnapshot) {
    val row = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(row, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                icon = Icons.Filled.List,
                value = bigNumber(snap.requestsTotal),
                label = L("总请求次数"),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.PlayArrow,
                value = bigNumber(snap.appLaunches),
                label = L("打开应用次数"),
                modifier = Modifier.weight(1f)
            )
        }
        Row(row, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                icon = Icons.Filled.DateRange,
                value = shortDuration(snap.serverMillis),
                label = L("累计运行时长"),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.Refresh,
                value = bigNumber(snap.serverStarts),
                label = L("服务器启动次数"),
                modifier = Modifier.weight(1f)
            )
        }
        Row(row, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                icon = Icons.Filled.Star,
                value = bigNumber(snap.activeDays.toLong()),
                label = L("有请求的天数"),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = cardColor(MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 25.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

/* ------------------------------------------------------------------ 小工具 */

private val DAY_KEYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 一 / 二 / …（英文下是 Mon / Tue …，所以只有非 ASCII 才截最后一个字）。 */
private fun shortDay(label: String): String =
    if (label.any { it.code > 127 }) label.takeLast(1) else label

/** 大数字：1234 / 3.9K / 1.23M。 */
private fun bigNumber(n: Long): String = when {
    n < 10_000L -> n.toString()
    n < 1_000_000L -> String.format(Locale.US, "%.1fK", n / 1000.0)
    else -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
}

/**
 * 时长文案（卡面用，越短越好）：
 * 秒 → 「42 秒」，不到一小时 → 「37 分」，不到一天 → 「7.6 小时」，再往上 → 「12.3 天」。
 */
private fun shortDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> L("%s 秒").format(sec)
        sec < 3600 -> L("%s 分").format(sec / 60)
        sec < 86_400 -> L("%s 小时").format(String.format(Locale.US, "%.1f", sec / 3600.0))
        else -> L("%s 天").format(String.format(Locale.US, "%.1f", sec / 86_400.0))
    }
}

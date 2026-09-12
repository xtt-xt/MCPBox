// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.LogEntry
import com.xtt.mcpbox.core.LogKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(ctx: Context, logs: List<LogEntry>, onChanged: () -> Unit) {
    var filter by remember { mutableStateOf<LogKind?>(null) }
    val shown = remember(logs, filter) {
        if (filter == null) logs else logs.filter { it.kind == filter }
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        PageHeader(
            title = L("日志"),
            subtitle = L("最近 %s 条调用与审批记录").format(logs.size)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChoiceChip(L("全部"), filter == null, MaterialTheme.colorScheme.primary) { filter = null }
            ChoiceChip(L("调用"), filter == LogKind.REQUEST, Sem.info) { filter = LogKind.REQUEST }
            ChoiceChip(L("审批"), filter == LogKind.APPROVAL, Sem.warn) { filter = LogKind.APPROVAL }
            ChoiceChip(L("连接"), filter == LogKind.CONNECT, Sem.ok) { filter = LogKind.CONNECT }
            ChoiceChip(L("错误"), filter == LogKind.ERROR, Sem.bad) { filter = LogKind.ERROR }
        }

        Spacer(Modifier.height(12.dp))

        CardColumn {
            CardBox {
                if (shown.isEmpty()) {
                    EmptyHint("还没有记录。\nAI 连上之后，每一次调用和审批都会留在这里。")
                } else {
                    shown.forEachIndexed { index, entry ->
                        if (index > 0) InnerDivider()
                        LogRow(entry, fmt)
                    }
                }
            }
            if (shown.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        L("复制全部"), Modifier.weight(1f), outlined = true,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        val text = shown.joinToString("\n") {
                            "${fmt.format(Date(it.time))} [${L(it.kind.label)}] ${it.tool ?: ""} " +
                                "${it.path ?: ""} ${it.message}"
                        }
                        copyText(ctx, text, L("日志已复制"))
                    }
                    PillButton(L("清空日志"), Modifier.weight(1f), outlined = true, color = Sem.bad) {
                        AppCore.log.clear()
                        onChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, fmt: SimpleDateFormat) {
    val color = when {
        entry.kind == LogKind.APPROVAL -> if (entry.ok) Sem.warn else Sem.bad
        entry.kind == LogKind.ERROR || !entry.ok -> Sem.bad
        entry.kind == LogKind.CONNECT -> Sem.ok
        else -> Sem.info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Spacer(Modifier.padding(top = 6.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .padding(top = 7.dp)
                .width(8.dp)
        ) { StatusDot(color, 8) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt.format(Date(entry.time)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                TagPill(L(entry.kind.label), color)
                entry.tool?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (entry.durationMs > 0) {
                    Text(
                        "${entry.durationMs}ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                entry.message,
                color = if (entry.ok) MaterialTheme.colorScheme.onSurface else Sem.bad,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            entry.path?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

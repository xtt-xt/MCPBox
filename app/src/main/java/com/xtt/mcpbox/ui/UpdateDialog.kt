// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.UpdateChecker
import com.xtt.mcpbox.core.ServerMeta

/** 匹配 <details> / <summary> 这类 HTML 标签（GitHub Release 正文里会有）。 */
private val HTML_TAG = Regex("<[^>]*>")

/** 行内标记：**加粗** / `行内代码` / [链接](url)。 */
private val INLINE = Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)")

/** 表格分隔行：|---|---|。 */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    val cells = t.trim('|').split('|').map { it.trim() }
    return cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.all { ch -> ch == '-' || ch == ':' } }
}

/**
 * 「发现新版本」弹窗。
 *
 * 说明正文是 GitHub Release 的 Markdown（标题、列表、表格、加粗、行内代码、<details> 标签…），
 * 这里做**轻量渲染**：
 *  - 标题按层级给字号与字重，列表加「·」，引用加左侧竖线；
 *  - 表格逐行拆开渲染（**说明** + 正文），分隔行直接丢掉；
 *  - 行内 `**加粗**` / `` `代码` `` / 链接只保留文字，HTML 标签整段剥掉。
 *
 * 正文区**限高 + 可滚动**，按钮固定在下面 —— 否则长说明会把按钮顶出屏幕（Release 说明动辄上千字）。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.Info,
    previewing: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    // 正文最多占屏高的一部分：标题 + 正文 + 按钮加起来仍然在一屏里
    val maxNotesHeight = LocalConfiguration.current.screenHeightDp.dp * 0.58f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                if (previewing) L("发现新版本 %s（预览）").format(info.tag)
                else L("发现新版本 %s").format(info.tag),
                fontSize = 20.sp
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (previewing) {
                    Text(
                        L("开发者模式预览：忽略版本比较，直接显示 GitHub 上最新的 Release 说明（发布于 %s）。")
                            .format(info.publishedAt.ifBlank { L("未知") }),
                        color = Sem.warn,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    L("当前版本 %s").format(ServerMeta.fullVersion),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    NotesBlock(info.notes, maxNotesHeight)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(L("去下载"), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L("稍后"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/* ------------------------------------------------------------------ 正文渲染 */

@Composable
private fun NotesBlock(markdown: String, maxHeight: Dp) {
    val source = remember(markdown) { markdown.replace("\r\n", "\n").lines() }
    val codeColor = MaterialTheme.colorScheme.primary

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        source.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val next = source.getOrNull(index + 1)?.trim()?.trimEnd().orEmpty()

            when {
                line.isBlank() -> Spacer(Modifier.height(9.dp))

                // 表格分隔行：只用来判断上一行是表头，本身不渲染
                isTableSeparator(line) -> Unit

                line.trim().startsWith("|") -> NoteTableRow(line, header = isTableSeparator(next), codeColor = codeColor)

                line.startsWith("### ") -> NoteHeading(line.removePrefix("### "), 13.sp, codeColor)
                line.startsWith("## ") -> NoteHeading(line.removePrefix("## "), 14.sp, codeColor)
                line.startsWith("# ") -> NoteHeading(line.removePrefix("# "), 15.sp, codeColor)

                else -> {
                    val trimmed = line.trimStart()
                    val indent = (line.length - trimmed.length).coerceAtMost(6)
                    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                            NoteLine("· " + trimmed.drop(2), indent, codeColor, bodyColor)

                        trimmed.startsWith("> ") ->
                            NoteLine("▏" + trimmed.drop(2), indent, codeColor, bodyColor)

                        else -> NoteLine(trimmed, indent, codeColor, bodyColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteHeading(text: String, size: TextUnit, codeColor: Color) {
    Spacer(Modifier.height(10.dp))
    Text(
        inlineStyled(text, codeColor),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        lineHeight = size * 1.4f,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun NoteLine(text: String, indent: Int, codeColor: Color, bodyColor: Color) {
    Text(
        inlineStyled(text, codeColor),
        color = bodyColor,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 6).dp, bottom = 2.dp)
    )
}

/** 表格的一行：第一格当小标题，其余格子拼成正文跟一行。 */
@Composable
private fun NoteTableRow(line: String, header: Boolean, codeColor: Color) {
    val cells = line.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
    if (cells.isEmpty()) return
    val head = cells.first()
    val rest = cells.drop(1).joinToString(" ").trim()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (header) 2.dp else 6.dp, top = if (header) 8.dp else 0.dp)
    ) {
        Text(
            inlineStyled(head, codeColor),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = if (header) 12.sp else 12.5.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Medium,
            lineHeight = 18.sp,
            modifier = Modifier.width(88.dp)
        )
        Text(
            inlineStyled(rest, codeColor),
            color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (header) 12.sp else 12.5.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ------------------------------------------------------------------ 行内样式 */

/** 剥掉 HTML 标签，把 `**粗体**` / `` `代码` `` / 链接渲染成带样式的一行。 */
@Composable
private fun inlineStyled(text: String, codeColor: Color): AnnotatedString {
    val clean = remember(text) { HTML_TAG.replace(text, "") }
    return remember(clean, codeColor) {
        buildAnnotatedString {
            var cursor = 0
            INLINE.findAll(clean).forEach { m ->
                if (m.range.first > cursor) append(clean.substring(cursor, m.range.first))
                when {
                    m.groupValues[1].isNotEmpty() ->
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(m.groupValues[1])
                        }
                    m.groupValues[2].isNotEmpty() ->
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                            append(m.groupValues[2])
                        }
                    // [文字](url)：只显示文字
                    else -> append(m.groupValues[3])
                }
                cursor = m.range.last + 1
            }
            if (cursor < clean.length) append(clean.substring(cursor))
        }
    }
}

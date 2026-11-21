// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.xtt.mcpbox.i18n.L

/**
 * 记忆正文的 Markdown 渲染：外面套一张圆角卡片。
 *
 * 渲染交给开源库（multiplatform-markdown-renderer，底层是 JetBrains 的 markdown 解析器），
 * 这里只负责配色对齐到 App 主题，以及外面那层卡片框。
 */
@Composable
fun MarkdownCard(
    content: String,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        // 卡片和页面底色差一档：用 surfaceContainer，和设置页的卡片一致
        color = chromeColor(scheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            SelectionContainer {
                Markdown(
                    content = content,
                    colors = markdownColor(
                        text = scheme.onSurface,
                        codeText = scheme.onSurface,
                        linkText = scheme.primary,
                        codeBackground = scheme.surfaceContainerHighest,
                        inlineCodeBackground = scheme.surfaceContainerHighest,
                        dividerColor = scheme.outlineVariant
                    ),
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        h2 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        h3 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        h4 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        h5 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        text = MaterialTheme.typography.bodyMedium,
                        paragraph = MaterialTheme.typography.bodyMedium,
                        ordered = MaterialTheme.typography.bodyMedium,
                        bullet = MaterialTheme.typography.bodyMedium,
                        list = MaterialTheme.typography.bodyMedium,
                        code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        inlineCode = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        quote = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        link = MaterialTheme.typography.bodyMedium.copy(
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 简介 / 分区这类「小信息」用的一张更轻的卡片。 */
@Composable
fun MarkdownInfoCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = chromeColor(scheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                label,
                color = scheme.onSurfaceVariant,
                fontSize = 11.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color = scheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

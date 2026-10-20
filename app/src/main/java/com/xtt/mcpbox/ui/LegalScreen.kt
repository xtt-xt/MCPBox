// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.R
import com.xtt.mcpbox.i18n.L
import com.xtt.mcpbox.i18n.Lang

/**
 * 法律文本：用户协议 + 免责声明。
 *
 * 正文放在 `res/raw/legal_*_{zh,en}.txt` 里，而不是塞进 [com.xtt.mcpbox.i18n.LangEn]
 * 那张「中文原文 → 译文」的大字典 —— 法律文本整段放进去会把词表撑爆，
 * 而且协议按语种整份提供（中文为准），不需要逐句翻译。
 *
 * 版本号见 [VERSION]：将来条款升到 v2，比对 `Prefs.legalAgreedVersion`
 * 就能要求用户重新确认，而不是只记一个「同意过了」的布尔值。
 */
/** 卡片圆角，跟 Common.kt 里卡片组的 24dp 对齐（那边是 private，这里自己写一份）。 */
private val LegalCardCorner = 24.dp

object Legal {

    /** 当前条款版本。改了正文就要改它。 */
    const val VERSION = "v1"

    /** 展示用的发布日期。 */
    const val DATE = "2026-10-20"

    enum class Doc(val titleZh: String, val titleEn: String) {
        TERMS("用户协议", "Terms of Use"),
        DISCLAIMER("免责声明", "Disclaimer")
    }

    fun title(doc: Doc): String =
        if (Lang.current == Lang.EN) doc.titleEn else doc.titleZh

    /** 读正文。语言包 / 彩蛋语言都按中文走，协议不做二次演绎。 */
    fun text(ctx: Context, doc: Doc): String {
        val en = Lang.current == Lang.EN
        val res = when (doc) {
            Doc.TERMS -> if (en) R.raw.legal_terms_en else R.raw.legal_terms_zh
            Doc.DISCLAIMER -> if (en) R.raw.legal_disclaimer_en else R.raw.legal_disclaimer_zh
        }
        return runCatching {
            ctx.resources.openRawResource(res).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
}

/**
 * 全文页：关于页里点进来读。
 *
 * 这里不复用设置子页的转场，因为它挂在关于页内部的一层 page 上，
 * 由关于页自己的 AnimatedContent 负责进出场。
 */
@Composable
fun LegalScreen(
    ctx: Context,
    doc: Legal.Doc,
    onBack: () -> Unit
) {
    val body = remember(doc, Lang.current) { Legal.text(ctx, doc) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 28.dp)
    ) {
        PageHeader(
            title = Legal.title(doc),
            actions = { RoundIconButton(Icons.Filled.ArrowBack, L("返回"), onClick = onBack) }
        )
        LegalBody(body, Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
    }
}

/**
 * 正文渲染：只认两种行 —— `# ` 一级标题、`## ` 二级标题，其余按段落排。
 *
 * 刻意不上 Markdown 解析库：这段文本是我们自己写的，格式约束得住。
 */
@Composable
fun LegalBody(text: String, modifier: Modifier = Modifier) {
    val lines = remember(text) { text.lines() }
    Column(modifier.fillMaxWidth()) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        line.removePrefix("## "),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                }
                line.startsWith("# ") -> {
                    Text(
                        line.removePrefix("# "),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("— ") -> {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
    }
}

/**
 * 引导里的条款正文块：装在一个可滚动的卡片里，滚到底才算读完。
 *
 * 高度由调用方给（引导页里是 weight(1f)），滚动状态也由调用方持有，
 * 这样「有没有到底」的判断留在页面那一层。
 */
@Composable
fun LegalScrollCard(
    ctx: Context,
    scroll: ScrollState,
    modifier: Modifier = Modifier
) {
    val terms = remember(Lang.current) { Legal.text(ctx, Legal.Doc.TERMS) }
    val disclaimer = remember(Lang.current) { Legal.text(ctx, Legal.Doc.DISCLAIMER) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LegalCardCorner))
            .background(cardColor(MaterialTheme.colorScheme.surfaceContainer))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            LegalBody(terms)
            Spacer(Modifier.height(16.dp))
            Text(
                "· · ·",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            LegalBody(disclaimer)
        }
    }
}

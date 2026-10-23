// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
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

    /**
     * 每个版本的变更说明。弹窗里逐条列给用户看。
     *
     * 改协议正文时：升 [VERSION]，并在这里补一条同名条目 ——
     * 老用户下次启动就会被拦一次，看到「这次改了什么」。
     */
    val CHANGELOG: Map<String, List<String>> = mapOf(
        "v1" to listOf("本应用新增用户协议与免责声明")
    )

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
 * 条款更新提示的显隐。
 *
 * 放在全局（和 [OnboardingState] 一个路子）而不是塞进 AppRoot：
 * 「前往查看」要盖住整个 App，弹窗和全屏页谁在上层得有同一份状态。
 */
object LegalNotice {
    /** 提示页开着没有。 */
    var visible by mutableStateOf(false)

    /** 正在看全文（全屏页盖住了提示页，返回后提示页还在）。 */
    var viewing by mutableStateOf(false)

    /** 全屏页打开的是哪一份。 */
    var doc by mutableStateOf(Legal.Doc.TERMS)

    /**
     * 启动时该不该显示。
     *
     * 判断就一句：记下的版本号是不是当前版本。从没同意过（空串）也走这条路，
     * 于是「老版本升级上来」和「协议改版」共用一套提示，不用分两处写。
     *
     * 再加一个「下次进入时预览」（开发者模式）：只影响这一次启动，不碰同意记录。
     */
    fun shouldPrompt(): Boolean {
        if (!AppCore.prefs.onboardDone) return false
        if (AppCore.prefs.legalPreviewNext) {
            AppCore.prefs.legalPreviewNext = false
            return true
        }
        return AppCore.prefs.legalAgreedVersion != Legal.VERSION
    }

    /** 记成「已经知道了」：写版本号 + 时间。 */
    fun acknowledge() {
        AppCore.prefs.legalAgreedVersion = Legal.VERSION
        AppCore.prefs.legalAgreedAt = System.currentTimeMillis()
        visible = false
        viewing = false
    }
}

/**
 * 条款提示页：版式和设置子页一致（大标题 + 返回箭头），正文列出本版改了什么。
 *
 * 两个出口：
 *  · 「我已知晓」→ 记为已读（写版本号），提示不再出现；
 *  · 「前往查看」→ 打开全文页，返回后仍停在这一页。
 *
 * 返回箭头等同于「我已知晓」：提示只在版本变化后的第一次出现，
 * 关掉就记账，不回锅（否则每次启动都要拦一次）。
 */
@Composable
fun LegalNoticePage(
    /** 返回箭头 / 系统返回：记为已读并关掉。 */
    onAcknowledge: () -> Unit,
    onView: () -> Unit
) {
    val previous = AppCore.prefs.legalAgreedVersion
    val changes = Legal.CHANGELOG[Legal.VERSION].orEmpty()
    BackHandler { onAcknowledge() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            PageHeader(
                title = if (previous.isBlank()) L("请阅读用户协议与免责声明") else L("用户协议已更新"),
                onBack = onAcknowledge
            )
            Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                Text(
                    L("当前版本 %s").format(Legal.VERSION),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (!previous.isBlank()) {
                    Text(
                        L("你上次同意的是 %s").format(previous),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            GroupLabel(L("本版变更"))
            CardBox(Modifier.padding(horizontal = 18.dp)) {
                changes.forEach { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "·",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            line,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
                if (changes.isEmpty()) {
                    Text(
                        L("这一版只是同步了条款版本号"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp
                    )
                }
            }
            GroupLabel(L("全文"))
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("用户协议"),
                        subtitle = L("版本 %s").format(Legal.VERSION),
                        subtitleMaxLines = 1,
                        icon = Icons.Filled.Info,
                        onClick = onView
                    )
                )
            )
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp)
        ) {
            PillButton(
                L("前往查看"),
                Modifier.fillMaxWidth(),
                outlined = true,
                onClick = onView
            )
            Spacer(Modifier.height(8.dp))
            PillButton(
                L("我已知晓"),
                Modifier.fillMaxWidth(),
                onClick = onAcknowledge
            )
        }
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
    /** 盖在 App 上时接管系统返回键（关于页内部的用法由外层栈接，不需要）。 */
    handleBack: Boolean = false,
    onBack: () -> Unit
) {
    val body = remember(doc, Lang.current) { Legal.text(ctx, doc) }
    if (handleBack) BackHandler { onBack() }
    PageScaffold(
        scroll = rememberScrollState(),
        title = Legal.title(doc),
        onBack = onBack,
        bottomPadding = 28.dp
    ) {
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

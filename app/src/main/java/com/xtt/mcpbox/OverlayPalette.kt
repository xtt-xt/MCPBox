// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.xtt.mcpbox.ui.BackdropSeed
import com.xtt.mcpbox.ui.DarkMode
import com.xtt.mcpbox.ui.DynamicSeedSource
import com.xtt.mcpbox.ui.PaletteStyle
import com.xtt.mcpbox.ui.buildScheme

/**
 * 悬浮窗（审批弹窗）用的配色快照。
 *
 * 悬浮窗是纯 View、跑在 Compose 外面，拿不到 MaterialTheme，
 * 所以由 MainActivity 在主题变化时把当前配色写进 [AppCore.overlayPalette]，
 * 这样弹窗也能跟着主题/壁纸走。
 */
data class OverlayPalette(
    val background: Int,
    val card: Int,
    val cardHigh: Int,
    val cardLow: Int,
    val text: Int,
    val textDim: Int,
    val primary: Int,
    val onPrimary: Int,
    val outline: Int,
    /** 当前是不是深色主题（悬浮窗画文字 / 图标时按它决定用黑还是用白）。 */
    val dark: Boolean = true
) {
    companion object {
        /**
         * 按当前主题设置现算一份（**不依赖 Compose、不依赖 Activity**）。
         *
         * 悬浮窗和悬浮球都是服务里的纯 View —— 只要服务在跑就得有正确的配色，
         * 不能等 MainActivity 起来才写：以前那份只在 Compose 里写，App 没在前台时
         * 一直是 [Fallback] 那套默认蓝，看起来就是「没跟随主题」。
         *
         * 算法与 `MCPBoxTheme` 保持一致（动态取色 → 背景种子 → 种子色 + 调色板样式）。
         */
        fun compute(ctx: Context): OverlayPalette = runCatching {
            val prefs = AppCore.prefs
            val dark = when (DarkMode.of(prefs.darkMode)) {
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
                DarkMode.SYSTEM -> (ctx.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val style = PaletteStyle.of(prefs.paletteStyle)
            val fromBackdrop = prefs.dynamicColor &&
                DynamicSeedSource.of(prefs.dynamicSeedSource) == DynamicSeedSource.BACKDROP &&
                BackdropSeed.cached(ctx) != 0
            val cs = when {
                fromBackdrop -> buildScheme(BackdropSeed.cached(ctx), dark, PaletteStyle.TONAL_SPOT)
                prefs.dynamicColor && Build.VERSION.SDK_INT >= 31 -> runCatching {
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                }.getOrElse { buildScheme(prefs.seedColor, dark, style) }
                else -> buildScheme(prefs.seedColor, dark, style)
            }
            OverlayPalette(
                background = cs.background.toArgb(),
                card = cs.surfaceContainer.toArgb(),
                cardHigh = cs.surfaceContainerHigh.toArgb(),
                cardLow = cs.surfaceContainerLow.toArgb(),
                text = cs.onSurface.toArgb(),
                textDim = cs.onSurfaceVariant.toArgb(),
                primary = cs.primary.toArgb(),
                onPrimary = cs.onPrimary.toArgb(),
                outline = cs.outline.toArgb(),
                dark = dark
            )
        }.getOrDefault(Fallback)

        val Fallback = OverlayPalette(
            background = 0xFF0B0B0D.toInt(),
            card = 0xFF1C1C20.toInt(),
            cardHigh = 0xFF26262B.toInt(),
            cardLow = 0xFF141416.toInt(),
            text = 0xFFE8E6EA.toInt(),
            textDim = 0xFF9E9EA7.toInt(),
            primary = 0xFFA8C7FA.toInt(),
            onPrimary = 0xFF00305F.toInt(),
            outline = 0xFF4A4A52.toInt(),
            dark = true
        )
    }
}

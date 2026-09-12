// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.google.android.material.color.utilities.TonalPalette

/**
 * Material 3 动态取色主题（原版风格）。
 *
 * - 开启「动态取色」：直接用系统壁纸派生的配色（Android 12+），原汁原味；
 * - 关闭时：用你选的**种子颜色** + **调色板样式**，按 Material 官方 HCT 算法
 *   和官方色调映射表（tone 80/40/30/90…）算出一整套配色。
 * 两种情况都会给出卡片色（surfaceContainer 系列），所以换色时整屏一起变。
 */
object M3 {
    val Background = Color(0xFF141218)
    val TextMain = Color(0xFFE6E1E9)
    val TextDim = Color(0xFF938F99)
}

/** 语义色：和状态绑定，不跟随主题变。 */
object Sem {
    val ok = Color(0xFF7CD98F)
    val warn = Color(0xFFF0C24B)
    val bad = Color(0xFFFFB4AB)
    val info = Color(0xFFA8C7FA)
}

/** 调色板样式：同一个种子色，算法不同，出来的味道就不一样。 */
enum class PaletteStyle(val id: String, val label: String) {
    TONAL_SPOT("tonal", "色调点"),
    VIBRANT("vibrant", "鲜活"),
    EXPRESSIVE("expressive", "表现力"),
    FIDELITY("fidelity", "保真"),
    NEUTRAL("neutral", "中性"),
    MONOCHROME("mono", "单色");

    companion object {
        fun of(id: String?): PaletteStyle = entries.firstOrNull { it.id == id } ?: TONAL_SPOT
    }
}

/** 深色 / 浅色 / 跟随系统。 */
enum class DarkMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun of(id: String?): DarkMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** 预设种子色（懒得调的时候点一个）。 */
enum class ThemeChoice(val id: String, val label: String, val seed: Long) {
    BLUE("blue", "经典蓝", 0xFF4C8DF6),
    GREEN("green", "森林绿", 0xFF3FA860),
    PURPLE("purple", "紫罗兰", 0xFF8B6CEF),
    ORANGE("orange", "暖橙", 0xFFE8843C),
    ROSE("rose", "玫瑰粉", 0xFFE86A9B),
    CYAN("cyan", "青碧", 0xFF2FA8B8),
    YELLOW("yellow", "琥珀金", 0xFFD9A521);

    companion object {
        fun of(id: String?): ThemeChoice? = entries.firstOrNull { it.id == id }
    }
}

/** 种子色选择器里的常用色板。 */
val SEED_PRESETS: List<Long> = listOf(
    0xFF4C8DF6, 0xFF2FA8B8, 0xFF3FA860, 0xFF7CB342, 0xFFD9A521, 0xFFE8843C,
    0xFFE05B4B, 0xFFE86A9B, 0xFF8B6CEF, 0xFF5C6BC0, 0xFF3B4A63, 0xFF6D7B8D
)

const val DEFAULT_SEED: Int = 0xFF4C8DF6.toInt()

/** 用官方算法把种子色展开成色调板。 */
private fun dynamicScheme(seedArgb: Int, dark: Boolean, style: PaletteStyle): DynamicScheme {
    val hct = Hct.fromInt(seedArgb)
    return when (style) {
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0)
        PaletteStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
        PaletteStyle.FIDELITY -> SchemeFidelity(hct, dark, 0.0)
        PaletteStyle.NEUTRAL -> SchemeNeutral(hct, dark, 0.0)
        PaletteStyle.MONOCHROME -> SchemeMonochrome(hct, dark, 0.0)
    }
}

/** 从色调板取某个色调（tone 0~100）。 */
private fun TonalPalette.t(tone: Int): Color = Color(tone(tone))

/** Material 官方深色色调映射。 */
private fun darkFrom(s: DynamicScheme) = darkColorScheme(
    primary = s.primaryPalette.t(80),
    onPrimary = s.primaryPalette.t(20),
    primaryContainer = s.primaryPalette.t(30),
    onPrimaryContainer = s.primaryPalette.t(90),
    inversePrimary = s.primaryPalette.t(40),
    secondary = s.secondaryPalette.t(80),
    onSecondary = s.secondaryPalette.t(20),
    secondaryContainer = s.secondaryPalette.t(30),
    onSecondaryContainer = s.secondaryPalette.t(90),
    tertiary = s.tertiaryPalette.t(80),
    onTertiary = s.tertiaryPalette.t(20),
    tertiaryContainer = s.tertiaryPalette.t(30),
    onTertiaryContainer = s.tertiaryPalette.t(90),
    error = s.errorPalette.t(80),
    onError = s.errorPalette.t(20),
    errorContainer = s.errorPalette.t(30),
    onErrorContainer = s.errorPalette.t(90),
    background = s.neutralPalette.t(6),
    onBackground = s.neutralPalette.t(90),
    surface = s.neutralPalette.t(6),
    onSurface = s.neutralPalette.t(90),
    surfaceVariant = s.neutralVariantPalette.t(30),
    onSurfaceVariant = s.neutralVariantPalette.t(80),
    surfaceTint = s.primaryPalette.t(80),
    surfaceContainerLowest = s.neutralPalette.t(4),
    surfaceContainerLow = s.neutralPalette.t(10),
    surfaceContainer = s.neutralPalette.t(12),
    surfaceContainerHigh = s.neutralPalette.t(17),
    surfaceContainerHighest = s.neutralPalette.t(22),
    surfaceDim = s.neutralPalette.t(6),
    surfaceBright = s.neutralPalette.t(24),
    outline = s.neutralVariantPalette.t(60),
    outlineVariant = s.neutralVariantPalette.t(30),
    inverseSurface = s.neutralPalette.t(90),
    inverseOnSurface = s.neutralPalette.t(20),
    scrim = s.neutralPalette.t(0)
)

/** Material 官方浅色色调映射。 */
private fun lightFrom(s: DynamicScheme) = lightColorScheme(
    primary = s.primaryPalette.t(40),
    onPrimary = s.primaryPalette.t(100),
    primaryContainer = s.primaryPalette.t(90),
    onPrimaryContainer = s.primaryPalette.t(10),
    inversePrimary = s.primaryPalette.t(80),
    secondary = s.secondaryPalette.t(40),
    onSecondary = s.secondaryPalette.t(100),
    secondaryContainer = s.secondaryPalette.t(90),
    onSecondaryContainer = s.secondaryPalette.t(10),
    tertiary = s.tertiaryPalette.t(40),
    onTertiary = s.tertiaryPalette.t(100),
    tertiaryContainer = s.tertiaryPalette.t(90),
    onTertiaryContainer = s.tertiaryPalette.t(10),
    error = s.errorPalette.t(40),
    onError = s.errorPalette.t(100),
    errorContainer = s.errorPalette.t(90),
    onErrorContainer = s.errorPalette.t(10),
    background = s.neutralPalette.t(98),
    onBackground = s.neutralPalette.t(10),
    surface = s.neutralPalette.t(98),
    onSurface = s.neutralPalette.t(10),
    surfaceVariant = s.neutralVariantPalette.t(90),
    onSurfaceVariant = s.neutralVariantPalette.t(30),
    surfaceTint = s.primaryPalette.t(40),
    surfaceContainerLowest = s.neutralPalette.t(100),
    surfaceContainerLow = s.neutralPalette.t(96),
    surfaceContainer = s.neutralPalette.t(94),
    surfaceContainerHigh = s.neutralPalette.t(92),
    surfaceContainerHighest = s.neutralPalette.t(90),
    surfaceDim = s.neutralPalette.t(87),
    surfaceBright = s.neutralPalette.t(98),
    outline = s.neutralVariantPalette.t(50),
    outlineVariant = s.neutralVariantPalette.t(80),
    inverseSurface = s.neutralPalette.t(20),
    inverseOnSurface = s.neutralPalette.t(95),
    scrim = s.neutralPalette.t(0)
)

/** 用当前参数算一套配色（主题入口和设置页预览共用）。 */
fun buildScheme(seedArgb: Int, dark: Boolean, style: PaletteStyle): ColorScheme =
    runCatching {
        if (dark) darkFrom(dynamicScheme(seedArgb, true, style))
        else lightFrom(dynamicScheme(seedArgb, false, style))
    }.getOrElse {
        if (dark) darkFrom(dynamicScheme(DEFAULT_SEED, true, PaletteStyle.TONAL_SPOT))
        else lightFrom(dynamicScheme(DEFAULT_SEED, false, PaletteStyle.TONAL_SPOT))
    }

/**
 * 主题入口。
 *
 * @param seedArgb       种子色（ARGB）
 * @param paletteStyleId 调色板样式
 * @param darkModeId     深色 / 浅色 / 跟随系统
 * @param dynamicColor   用系统壁纸取色（Android 12+），优先级高于种子色
 */
@Composable
fun MCPBoxTheme(
    seedArgb: Int = DEFAULT_SEED,
    paletteStyleId: String = PaletteStyle.TONAL_SPOT.id,
    darkModeId: String = DarkMode.SYSTEM.id,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val dark = when (DarkMode.of(darkModeId)) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        DarkMode.SYSTEM -> isSystemInDarkTheme()
    }
    val style = PaletteStyle.of(paletteStyleId)
    val scheme = remember(dark, dynamicColor, seedArgb, style) {
        if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
            runCatching {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }.getOrElse { buildScheme(seedArgb, dark, style) }
        } else {
            buildScheme(seedArgb, dark, style)
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

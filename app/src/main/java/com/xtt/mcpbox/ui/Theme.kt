// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive（RikkaHub / Material You 风格）
 *
 * 关键点：**卡片的颜色也跟着主题走** ——
 * 底色压到接近纯黑，但 surfaceContainer 系列会染上主题色（跟随壁纸时就是壁纸的颜色），
 * 所以换主题/换壁纸时，卡片会整体偏暖、偏冷、偏紫，像 RikkaHub 那样。
 */
object M3 {
    /** 底座：接近纯黑，最终会被主题色轻微染色 */
    val Background = Color(0xFF0B0B0D)
    val TextMain = Color(0xFFE8E6EA)
    val TextDim = Color(0xFF9E9EA7)
}

/** 语义色：和状态绑定，不跟随主题变。 */
object Sem {
    val ok = Color(0xFF7CD98F)
    val warn = Color(0xFFF0C24B)
    val bad = Color(0xFFFFB4AB)
    val info = Color(0xFFA8C7FA)
}

/** 可选的颜色模式。 */
enum class ThemeChoice(val id: String, val label: String, val seed: Long?) {
    DYNAMIC("dynamic", "跟随壁纸", null),
    BLUE("blue", "经典蓝", 0xFFA8C7FA),
    GREEN("green", "森林绿", 0xFF86D98F),
    PURPLE("purple", "紫罗兰", 0xFFD0BCFF),
    ORANGE("orange", "暖橙", 0xFFFFB77C),
    ROSE("rose", "玫瑰粉", 0xFFF6A6C1),
    CYAN("cyan", "青碧", 0xFF7ED4DE);

    companion object {
        fun of(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: DYNAMIC
    }
}

private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

private fun lighten(c: Color, t: Float) = blend(c, Color.White, t)
private fun darken(c: Color, t: Float) = blend(c, Color.Black, t)

private val BASE_BG = Color(0xFF0B0B0D)

/** 以某个主色为种子，铺出一整套深色配色（含被染色的卡片色）。 */
private fun palette(seed: Color) = darkColorScheme(
    primary = seed,
    onPrimary = darken(seed, 0.84f),
    primaryContainer = blend(seed, M3.Background, 0.80f),
    onPrimaryContainer = lighten(seed, 0.30f),
    secondary = blend(seed, Color(0xFFB9BDC9), 0.55f),
    onSecondary = darken(seed, 0.80f),
    secondaryContainer = blend(seed, M3.Background, 0.82f),
    onSecondaryContainer = lighten(seed, 0.28f),
    tertiary = blend(seed, Color(0xFFEBB8DD), 0.62f),
    onTertiary = Color(0xFF30202C),
    tertiaryContainer = blend(seed, M3.Background, 0.80f),
    onTertiaryContainer = lighten(seed, 0.30f),

    background = blend(M3.Background, seed, 0.05f),
    onBackground = M3.TextMain,
    surface = blend(M3.Background, seed, 0.05f),
    onSurface = M3.TextMain,
    surfaceVariant = blend(M3.Background, seed, 0.18f),
    onSurfaceVariant = blend(M3.TextDim, seed, 0.20f),

    // 卡片：越靠上层越亮，同时都带上主色
    surfaceContainerLowest = blend(M3.Background, seed, 0.05f),
    surfaceContainerLow = blend(M3.Background, seed, 0.09f),
    surfaceContainer = blend(M3.Background, seed, 0.14f),
    surfaceContainerHigh = blend(M3.Background, seed, 0.20f),
    surfaceContainerHighest = blend(M3.Background, seed, 0.27f),

    outline = blend(M3.Background, seed, 0.42f),
    outlineVariant = blend(M3.Background, seed, 0.20f),
    error = Sem.bad,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = M3.TextMain,
    inverseOnSurface = Color(0xFF303034),
    scrim = Color(0x99000000)
)

private val Fallback = palette(Color(ThemeChoice.BLUE.seed!!))

@Composable
fun MCPBoxTheme(themeId: String = ThemeChoice.DYNAMIC.id, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val choice = ThemeChoice.of(themeId)
    val scheme = when {
        choice == ThemeChoice.DYNAMIC && Build.VERSION.SDK_INT >= 31 -> runCatching {
            // 跟随壁纸：主色和卡片色都取系统的（带壁纸色调），
            // 只把「底」压暗成近黑，免得整屏发灰
            val d = dynamicDarkColorScheme(ctx)
            val deep = blend(d.surfaceContainerLowest, M3.Background, 0.62f)
            d.copy(
                background = deep,
                surface = deep,
                onBackground = M3.TextMain,
                onSurface = M3.TextMain,
                onSurfaceVariant = blend(d.onSurfaceVariant, M3.TextDim, 0.35f),
                surfaceContainerLowest = deep,
                surfaceContainerLow = blend(d.surfaceContainerLow, M3.Background, 0.48f),
                surfaceContainer = blend(d.surfaceContainer, M3.Background, 0.38f),
                surfaceContainerHigh = blend(d.surfaceContainerHigh, M3.Background, 0.32f),
                surfaceContainerHighest = blend(d.surfaceContainerHighest, M3.Background, 0.28f),
                outline = blend(d.outline, M3.Background, 0.30f),
                outlineVariant = blend(d.outlineVariant, M3.Background, 0.42f)
            )
        }.getOrElse { Fallback }

        choice.seed != null -> palette(Color(choice.seed))
        else -> Fallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

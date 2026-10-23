// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.xtt.mcpbox.AppCore

/**
 * 底栏（底部导航栏 + 全选工具条）的样式：不透明度与模糊。
 *
 * 和自定义背景**完全无关**：背景关着也能用 —— 底栏是浮在内容上面的一层，
 * 半透明 / 毛玻璃时透出来的是从它下面划过去的卡片，而不是背景图。
 */
data class BarStyle(
    /** 不透明度 0f~1f（1 = 原来的不透明底栏）。 */
    val alpha: Float = 1f,
    /** 模糊半径（dp），0 = 不模糊。 */
    val blur: Float = 0f
) {
    /** 要不要走毛玻璃这一层（全不透明又不模糊时直接用原来的纯色底，省一次离屏绘制）。 */
    val usesHaze: Boolean get() = alpha < 0.999f || blur > 0.5f

    companion object {
        val Opaque = BarStyle()
    }
}

/**
 * 底栏样式的**实时**状态。
 *
 * 为什么不直接读 Prefs：滑块拖动时要把变化立刻反映到整棵树（底栏在 Compose 树的最外层，
 * 设置页拿不到它的状态）。这里放一份 Compose 状态给两边共用 ——
 * 拖动时只改这里（实时预览），松手才写盘（见设置页的 onValueChangeFinished）。
 */
object BarLive {
    var alpha by mutableFloatStateOf(1f)
        private set
    var blur by mutableFloatStateOf(0f)
        private set

    /** 从 Prefs 读一遍（App 启动 / 恢复备份后调）。 */
    fun reload() {
        alpha = AppCore.prefs.barAlpha / 100f
        blur = AppCore.prefs.barBlur.toFloat()
    }

    /** 实时预览用：只改内存，不落盘。 */
    fun preview(alpha: Float? = null, blur: Float? = null) {
        alpha?.let { this.alpha = it.coerceIn(0f, 1f) }
        blur?.let { this.blur = it.coerceIn(0f, 40f) }
    }

    /** 落盘并同步内存。 */
    fun commit() {
        AppCore.prefs.barAlpha = (alpha * 100).toInt()
        AppCore.prefs.barBlur = blur.toInt()
        reload()
    }

    fun reset() {
        alpha = 1f
        blur = 0f
    }
}

/** 当前底栏样式（由 MainActivity 在最外层提供）。 */
val LocalBarStyle = compositionLocalOf { BarStyle.Opaque }

/**
 * 毛玻璃要抓的「内容层」。
 *
 * 由 MainActivity 创建并挂在内容那一层（hazeSource），底栏 / 全选工具条拿它做 hazeEffect。
 * 为 null = 没开毛玻璃（或者不在主界面里），这时底栏用纯色底。
 */
val LocalHazeState = compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * 底栏占多高 —— 给滚动内容末尾留白用。
 *
 * 底栏是**覆盖式**的（不参与布局），所以内容会从它下面划过去；
 * 不在这里垫一块空白，滚到底时最后一张卡片就永远被底栏挡住。
 * 子页 / 输入法弹出时底栏收起，这个值就是 0。
 */
val LocalBottomBarInset = compositionLocalOf { 0f }

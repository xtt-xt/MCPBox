package com.xtt.mcpbox

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
    val outline: Int
) {
    companion object {
        val Fallback = OverlayPalette(
            background = 0xFF0B0B0D.toInt(),
            card = 0xFF1C1C20.toInt(),
            cardHigh = 0xFF26262B.toInt(),
            cardLow = 0xFF141416.toInt(),
            text = 0xFFE8E6EA.toInt(),
            textDim = 0xFF9E9EA7.toInt(),
            primary = 0xFFA8C7FA.toInt(),
            onPrimary = 0xFF00305F.toInt(),
            outline = 0xFF4A4A52.toInt()
        )
    }
}

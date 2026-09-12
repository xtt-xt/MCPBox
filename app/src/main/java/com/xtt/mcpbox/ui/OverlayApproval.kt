// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.NotificationHelper
import com.xtt.mcpbox.core.ApprovalDecision
import com.xtt.mcpbox.core.ApprovalPresenter
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.core.LogKind

/**
 * 审批悬浮窗（Material 3 Expressive 风格）：
 * 近黑底 + 大圆角深灰卡片 + 胶囊按钮，任何应用之上弹出，
 * 用户点完立刻把结果回给等待中的服务器线程。
 */
class OverlayApproval(private val context: Context) : ApprovalPresenter {

    /** 跟随 App 主题的配色：MainActivity 在主题变化时写进 AppCore。 */
    private val p: OverlayPalette get() = AppCore.overlayPalette


    private companion object {
    }

    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val cards = LinkedHashMap<String, Card>()

    var wakeScreenOnApproval: Boolean = true

    private var root: LinearLayout? = null

    private class Card(val view: View, val ticker: Runnable)

    override fun show(request: ApprovalRequest) {
        main.post {
            if (!canDrawOverlays()) {
                AppCore.log.add(
                    LogKind.APPROVAL, ok = true,
                    message = L("没有悬浮窗权限，已改用通知栏审批：%s").format(request.summary)
                )
                NotificationHelper.postApproval(context, request)
                return@post
            }
            addCard(request)
        }
    }

    override fun dismiss(id: String) {
        main.post { removeCard(id) }
    }

    fun releaseAll() {
        main.post {
            cards.values.forEach { main.removeCallbacks(it.ticker) }
            cards.clear()
            detachRoot()
        }
    }

    private fun canDrawOverlays(): Boolean = try {
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ 窗口

    private fun ensureRoot(): LinearLayout? {
        root?.let { return it }
        val wm = wm ?: return null
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(4)
        }
        return try {
            wm.addView(container, params)
            root = container
            container
        } catch (e: Exception) {
            AppCore.log.add(LogKind.ERROR, ok = false, message = L("悬浮窗创建失败：%s").format(e.message))
            null
        }
    }

    private fun detachRoot() {
        val container = root ?: return
        runCatching { wm?.removeView(container) }
        root = null
    }

    private fun addCard(request: ApprovalRequest) {
        val container = ensureRoot()
        if (container == null) {
            NotificationHelper.postApproval(context, request)
            return
        }
        if (wakeScreenOnApproval) wakeScreen()
        val (card, ticker) = buildCard(request)
        cards[request.id] = Card(card, ticker)
        container.addView(card)
        animateIn(card)
        main.post(ticker)
    }

    private fun removeCard(id: String) {
        val card = cards.remove(id) ?: return
        main.removeCallbacks(card.ticker)
        animateOut(card.view) {
            runCatching { root?.removeView(card.view) }
            if (cards.isEmpty()) detachRoot()
        }
        NotificationHelper.cancelApproval(context)
    }

    /** 弹出：从下方滑上来 + 淡入 + 轻微放大（带一点点回弹）。 */
    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = dp(36).toFloat()
        view.scaleX = 0.90f
        view.scaleY = 0.90f
        view.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .start()
    }

    /** 收起：淡出 + 略微下沉。 */
    private fun animateOut(view: View, end: () -> Unit) {
        view.animate()
            .alpha(0f).translationY(dp(12).toFloat()).scaleX(0.98f).scaleY(0.98f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction(end)
            .start()
    }

    private fun wakeScreen() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "mcpbox:approval"
            )
            lock.acquire(8000)
        }
    }

    // ------------------------------------------------------------------ 卡片

    private fun buildCard(request: ApprovalRequest): Pair<View, Runnable> {
        val accent = permColor(request.perm.id)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(blend(p.card, p.background, 0.30f), 28f)
        }

        // 顶部：权限胶囊 + 倒计时
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(context).apply {
            background = rounded(accent, 50f)
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(9); gravity = Gravity.CENTER_VERTICAL })
        header.addView(pill(L(request.perm.title), p.primary))
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val countdown = TextView(context).apply {
            textSize = 12f
            setTextColor(p.textDim)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        header.addView(countdown)
        card.addView(header)

        card.addView(
            textView(request.summary, 16f, p.text, bold = true)
                .apply { setPadding(0, dp(14), 0, 0) }
        )

        request.path?.let {
            card.addView(
                textView(it, 12f, p.textDim).apply {
                    setPadding(0, dp(6), 0, 0)
                    typeface = Typeface.MONOSPACE
                }
            )
        }
        // 命令类审批：把要执行的命令单独显示出来（等宽字体 + 浅色块）
        // 中间内容统一放进一个可滚动的 body：
        // 卡片超高时只有它滚动，下面的按钮始终留在屏幕上
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        request.command?.let { cmd ->
            val shown = if (cmd.length > 400) cmd.take(400) + " …" else cmd
            body.addView(
                TextView(context).apply {
                    text = shown
                    textSize = 12.5f
                    setTextColor(p.text)
                    typeface = Typeface.MONOSPACE
                    background = rounded(p.cardLow, 12f)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }

        request.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            val lines = detail.lines().filter { it.isNotBlank() }.take(2)
            lines.forEach { line ->
                val shown = if (line.length > 68) line.take(68) + "…" else line
                body.addView(textView(shown, 12f, p.textDim).apply { setPadding(0, dp(6), 0, 0) })
            }
        }

        val meta = buildString {
            append(request.tool)
            request.backend?.let { b -> append(" · ").append(b) }
            request.client?.let { c -> append(" · ").append(c) }
            append(" · ").append(request.timeoutMs / 1000).append(L(" 秒未处理自动拒绝"))
        }
        body.addView(textView(meta, 11f, p.textDim).apply { setPadding(0, dp(10), 0, 0) })

        val bodyScroll = ScrollView(context).apply {
            isFillViewport = false
            addView(body)
        }
        // weight=1：卡片被限高时，只有中间这块被压缩并滚动
        card.addView(
            bodyScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        )

        // 按钮
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val isCommand = request.command != null
        row1.addView(button(L("允许一次"), p.primary, p.onPrimary) { resolve(request, ApprovalDecision.ALLOW_ONCE) }, weight())
        row1.addView(
            button(if (isCommand) L("记住此命令") else L("始终允许"), blend(p.primary, p.card, 0.16f), p.primary, stroke = null) {
                resolve(request, ApprovalDecision.ALLOW_ALWAYS)
            }, weight(true)
        )
        card.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row2.addView(
            button(L("拒绝"), p.cardHigh, p.text, stroke = null) {
                resolve(request, ApprovalDecision.DENY_ONCE)
            }, weight()
        )
        row2.addView(
            button(if (isCommand) L("永久拒绝") else L("始终拒绝"), Color.TRANSPARENT, p.textDim, stroke = p.outline) {
                resolve(request, ApprovalDecision.DENY_ALWAYS)
            }, weight(true)
        )
        card.addView(row2)

        // 整张卡片最多占屏幕 78%：命令再长也压不掉按钮
        card.post {
            val maxH = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
            if (card.height > maxH) {
                (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.height = maxH
                    card.layoutParams = lp
                    card.requestLayout()
                }
            }
        }

        val endAt = request.createdAt + request.timeoutMs
        val ticker = object : Runnable {
            override fun run() {
                val left = (endAt - System.currentTimeMillis()) / 1000
                if (left <= 0) {
                    countdown.text = L("已超时")
                    return
                }
                countdown.text = L("剩余 %s 秒").format(left)
                main.postDelayed(this, 1000)
            }
        }
        return card to ticker
    }

    private fun resolve(request: ApprovalRequest, decision: ApprovalDecision) {
        AppCore.approval.resolve(request.id, decision)
    }

    private fun permColor(permId: String): Int = p.primary

    // ------------------------------------------------------------------ 风格件

    private fun textView(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun pill(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(blend(color, p.card, 0.18f), 50f)
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    private fun button(
        label: String,
        fill: Int,
        textColor: Int,
        stroke: Int? = null,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(13), dp(8), dp(13))
        background = rounded(fill, 50f, stroke)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun weight(second: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (second) leftMargin = dp(8)
        }

    private fun blend(color: Int, bg: Int, alpha: Float): Int {
        val r = (Color.red(color) * alpha + Color.red(bg) * (1 - alpha)).toInt()
        val g = (Color.green(color) * alpha + Color.green(bg) * (1 - alpha)).toInt()
        val b = (Color.blue(color) * alpha + Color.blue(bg) * (1 - alpha)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1.2f).coerceAtLeast(1), stroke)
        }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun dp(value: Int): Int = dp(value.toFloat())
}

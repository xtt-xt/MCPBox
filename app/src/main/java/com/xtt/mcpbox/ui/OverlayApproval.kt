// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.NotificationHelper
import com.xtt.mcpbox.Prefs
import com.xtt.mcpbox.core.ApprovalDecision
import com.xtt.mcpbox.core.ApprovalPresentation
import com.xtt.mcpbox.core.ApprovalPresenter
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.core.useOverlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 审批卡片的弹出位置（设置 → 权限 → 审批 → 弹窗位置）。
 *
 * 存在 Prefs 里的是 [id]，默认 [CENTER]。
 */
enum class ApprovalPosition(val id: String) {
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom");

    companion object {
        fun of(id: String?): ApprovalPosition = entries.firstOrNull { it.id == id } ?: CENTER
    }
}

/**
 * 审批的呈现入口（Material 3 Expressive 风格的悬浮窗 + 通知栏两条路）：
 * 近黑底 + 大圆角卡片 + 2×2 按钮宫格，任何应用之上弹出，
 * 用户点完立刻把结果回给等待中的服务器线程。
 *
 * 走哪条路由设置决定（设置 → 安全与审批 → 审批方式，默认「悬浮窗」）：
 *  · 悬浮窗 —— 浮在所有应用之上；没有悬浮窗权限时自动退回通知栏（否则没人看得到请求）
 *  · 通知栏 —— 只发带按钮的高优先级通知，不弹悬浮窗
 * 顺带一提：模式是每次 [show] 现读的，所以改设置不用重启服务器。
 *
 * 卡片形态（2026-11-21 重做）：
 *  · 头部（图标 + 权限名 + 「工具 · 1 / 3」+ 缩小按钮）固定不动
 *  · 中间的详情区是唯一能滚的地方，整卡限高、内容少时自然收短
 *  · 底部是倒计时一行 + 四个按钮（允许一次 / 始终允许 / 拒绝 / 始终拒绝）
 *  · 点「缩小」把整张卡片收成一个可拖动的球，球上写着还剩几条待审批；
 *    点球恢复卡片，球的位置记在 Prefs 里，下次缩小还回到那
 *  · 同时有多条请求时，卡片一次只显示一条（先到先处理），处理完自动切下一条
 */
class OverlayApproval(private val context: Context) : ApprovalPresenter {

    /** 跟随 App 主题的配色：MainActivity 在主题变化时写进 AppCore。 */
    private val p: OverlayPalette get() = AppCore.overlayPalette

    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())

    var wakeScreenOnApproval: Boolean = true

    /** 还没拍板的请求，按到达顺序排队。 */
    private val pending = ArrayList<ApprovalRequest>()

    /** 卡片上正显示着哪一条；null = 没有卡片。 */
    private var shownId: String? = null

    /** 用户点了「缩小」：只留球。 */
    private var collapsed = false

    private var cardHost: FrameLayout? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var ticker: Runnable? = null
    private var counter: TextView? = null


    /** 量到过的 insets 缓存：同一块屏幕上高度不会变，缓存后定位就不会跳。 */
    private var insetsTop: Int? = null
    private var insetsBottom: Int? = null

    /**
     * 审批在共用悬浮球上的那一个「源」：
     * 只有「缩成球」并且还有请求排队时才在球上出现，球上是待审批条数。
     */
    private val ballSource = object : BallSource {
        override val id = "approval"
        override fun available(): Boolean = collapsed && pending.isNotEmpty()
        override fun badge(): Int = pending.size
        override fun label(): String = L("审批")
        override fun open() = expand()
        override fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
            // 有请求时球上永远是数字，这个图标只在极端情况下（badge 变成 0）才用得上
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.22f
            canvas.drawCircle(cx, cy, radius * 0.72f, paint)
            val d = radius * 0.30f
            canvas.drawLine(cx - d, cy, cx + d, cy, paint)
            paint.style = Paint.Style.FILL
        }
    }

    init {
        AppCore.ball.register(ballSource)
    }

    /** 审批这边状态变了：让共用球重新算一遍该不该显示、显示什么。 */
    private fun refreshBall() {
        AppCore.ball.refresh()
    }

    // ------------------------------------------------------------------ 对外

    override fun show(request: ApprovalRequest) {
        val mode = ApprovalPresentation.of(AppCore.prefs.approvalPresentation)
        if (mode == ApprovalPresentation.NOTIFY) {
            AppCore.log.add(
                LogKind.APPROVAL, ok = true,
                message = L("审批方式为「通知栏」，已发通知：%s").format(request.summary)
            )
            NotificationHelper.postApproval(context, request)
            return
        }
        main.post {
            if (!mode.useOverlay(canDrawOverlays())) {
                AppCore.log.add(
                    LogKind.APPROVAL, ok = true,
                    message = L("没有悬浮窗权限，已改用通知栏审批：%s").format(request.summary)
                )
                NotificationHelper.postApproval(context, request)
                return@post
            }
            pending.removeAll { it.id == request.id }
            pending.add(request)
            if (collapsed) {
                refreshBall()
                syncCounts()
            } else if (shownId == null) {
                showNext()
            } else {
                syncCounts()
            }
        }
    }

    override fun dismiss(id: String) {
        // 通知栏那条也要收掉：通知栏模式下根本没有卡片，只靠这里取消
        NotificationHelper.cancelApproval(context, id)
        main.post {
            if (!pending.removeAll { it.id == id }) return@post
            if (collapsed) {
                if (pending.isEmpty()) {
                    collapsed = false
                    refreshBall()
                } else {
                    syncCounts()
                }
            } else if (shownId == id) {
                shownId = null
                if (pending.isEmpty()) closeCard() else showNext()
            } else {
                syncCounts()
            }
        }
    }

    /** 还有没有排在队里等用户拍板的请求（设置页判断球该不该出现用）。 */
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** 主题配色变了（换深色 / 换壁纸 / 改种子色）：把正显示的卡片按新配色重画一遍。 */
    fun refreshPalette() {
        main.post {
            val request = pending.firstOrNull { it.id == shownId } ?: return@post
            val host = cardHost ?: return@post
            val card = buildCard(request)
            host.removeAllViews()
            host.addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            card.post { positionCard() }
        }
    }

    fun releaseAll() {
        main.post {
            stopTicker()
            pending.clear()
            shownId = null
            collapsed = false
            detachCardWindow()
            refreshBall()
            insetsTop = null
            insetsBottom = null
        }
    }

    private fun canDrawOverlays(): Boolean = try {
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ 卡片窗口

    private fun ensureCardWindow(): FrameLayout? {
        cardHost?.let { return it }
        val wmv = wm ?: return null
        val host = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            cardWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 统一用 TOP 重力 + 自己算 y：上 / 中 / 下三种位置都只差一个数字，
            // 不用去猜 BOTTOM 重力下 y 的正负方向。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = topInset() + dp(12)
        }
        // 卡片换内容时高度会变，落地后重新按位置算 y
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionCard() }
        if (Build.VERSION.SDK_INT >= 30) {
            host.setOnApplyWindowInsetsListener { _, insets ->
                val t = insets.getInsets(WindowInsets.Type.statusBars()).top
                val b = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                if (t > 0) insetsTop = t
                if (b >= 0) insetsBottom = b
                positionCard()
                insets
            }
        }
        return try {
            wmv.addView(host, params)
            cardHost = host
            cardParams = params
            host
        } catch (e: Exception) {
            AppCore.log.add(LogKind.ERROR, ok = false, message = L("悬浮窗创建失败：%s").format(e.message))
            null
        }
    }

    private fun detachCardWindow() {
        val host = cardHost ?: return
        runCatching { wm?.removeView(host) }
        cardHost = null
        cardParams = null
        counter = null
    }

    /** 按设置（上 / 中 / 下）把卡片摆到该在的地方。高度变了会再算一次。 */
    private fun positionCard() {
        val host = cardHost ?: return
        val params = cardParams ?: return
        val h = host.height
        if (h <= 0) return
        val top = topInset() + dp(12)
        val bottom = screenH() - bottomInset() - h - dp(12)
        val y = when (ApprovalPosition.of(AppCore.prefs.approvalPosition)) {
            ApprovalPosition.TOP -> top
            ApprovalPosition.BOTTOM -> max(top, bottom)
            ApprovalPosition.CENTER -> max(top, (screenH() - h) / 2)
        }
        if (params.y == y) return
        params.y = y
        runCatching { wm?.updateViewLayout(host, params) }
    }

    private fun showNext() {
        val request = pending.firstOrNull()
        if (request == null) {
            closeCard()
            return
        }
        val host = ensureCardWindow()
        if (host == null) {
            NotificationHelper.postApproval(context, request)
            return
        }
        if (wakeScreenOnApproval) wakeScreen()
        shownId = request.id
        val card = buildCard(request)
        host.removeAllViews()
        // 先藏起来挂上去：量完高度、摆好位置再淡入，避免先在错的位置露一帧
        card.alpha = 0f
        host.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.post {
            positionCard()
            animateIn(card)
        }
    }

    private fun closeCard() {
        stopTicker()
        val host = cardHost ?: return
        val child = host.getChildAt(0) ?: run { detachCardWindow(); return }
        child.animate()
            .alpha(0f).translationY(dp(10).toFloat()).scaleX(0.98f).scaleY(0.98f)
            .setDuration(140)
            .setInterpolator(AccelerateInterpolator())
            // 动画期间可能又来了新请求、卡片已经被换掉：这时不能把窗口拆了
            .withEndAction { if (host.getChildAt(0) === child) detachCardWindow() }
            .start()
    }

    /** 弹出：淡入 + 从下往上一点点 + 轻微放大。 */
    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = dp(16).toFloat()
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2.2f))
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

    // ------------------------------------------------------------------ 缩小 / 展开

    /** 缩小：卡片收成一个球，请求继续排队等着。 */
    private fun collapse() {
        if (pending.isEmpty()) return
        collapsed = true
        stopTicker()
        refreshBall()
        val host = cardHost ?: return
        val child = host.getChildAt(0) ?: run { detachCardWindow(); return }
        child.animate()
            .alpha(0f).scaleX(0.80f).scaleY(0.80f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (host.getChildAt(0) === child) detachCardWindow() }
            .start()
    }

    /** 点球：恢复卡片，接着处理排队里的第一条。 */
    private fun expand() {
        collapsed = false
        refreshBall()
        if (pending.isEmpty()) {
            detachCardWindow()
            return
        }
        shownId = null
        showNext()
    }

    // ------------------------------------------------------------------ 卡片内容

    private fun buildCard(request: ApprovalRequest): View {
        val accent = permColor(request.perm.id)
        val bg = blend(p.card, p.background, 0.86f)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(bg, 28f)
        }

        // ---- 头部：图标 + 权限名 / 工具 · 1 / 3 + 缩小（固定，不滚）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            IconBadge(context, accent),
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
        )
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            textView(L(request.perm.title), 16.5f, p.text, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        val sub = textView(headerSubtitle(request), 12f, p.textDim).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(sub)
        counter = sub
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            MinimizeButton(context),
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) }
        )
        card.addView(header)

        // ---- 中间：唯一能滚的地方
        val scroll = MaxHeightScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        // 命令类审批的摘要就是「执行命令：<整条命令>」，跟下面的命令块完全重复：
        // 标题已经在头部（就是权限名），这里只在摘要里还有别的话时才显示一行。
        val headline = headline(request)
        headline?.let { body.addView(textView(it, 15f, p.text, bold = true)) }

        request.path?.takeIf { it.isNotBlank() }?.let { path ->
            body.addView(
                textView(path, 12f, p.textDim).apply {
                    setPadding(0, dp(6), 0, 0)
                    typeface = Typeface.MONOSPACE
                }
            )
        }
        // 命令类审批：把要执行的命令单独显示出来（等宽字体 + 更暗的一块底）
        request.command?.let { cmd ->
            val shown = if (cmd.length > 2000) cmd.take(2000) + " …" else cmd
            body.addView(
                TextView(context).apply {
                    text = shown
                    textSize = 12.5f
                    setTextColor(p.text)
                    typeface = Typeface.MONOSPACE
                    background = rounded(blend(p.card, p.background, 0.45f), 14f)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (headline == null) 0 else dp(10) }
            )
        }
        request.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            detail.lines().filter { it.isNotBlank() }.take(3).forEach { line ->
                body.addView(textView(L(line), 12f, p.textDim).apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        scroll.addView(
            body,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 底部：倒计时 + 2×2 按钮宫格（固定，不滚）
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val countdown = textView(countdownText(request), 11.5f, p.textDim).apply {
            setPadding(0, 0, 0, dp(10))
        }
        footer.addView(countdown)

        val isCommand = request.command != null
        val buttons = ArrayList<TextView>(4)
        val allowOnce = actionButton(L("允许一次"), p.primary, p.onPrimary, null, buttons, request, ApprovalDecision.ALLOW_ONCE)
        val allowAlways = actionButton(
            if (isCommand) L("记住此命令") else L("始终允许"),
            blend(p.primary, bg, 0.20f), p.primary, null, buttons, request, ApprovalDecision.ALLOW_ALWAYS
        )
        val denyOnce = actionButton(L("拒绝"), p.cardHigh, p.text, null, buttons, request, ApprovalDecision.DENY_ONCE)
        val denyAlways = actionButton(
            if (isCommand) L("永久拒绝") else L("始终拒绝"),
            Color.TRANSPARENT, p.textDim, p.outline, buttons, request, ApprovalDecision.DENY_ALWAYS
        )
        buttons += listOf(allowOnce, allowAlways, denyOnce, denyAlways)

        footer.addView(buttonRow(allowOnce, allowAlways, 0))
        footer.addView(buttonRow(denyOnce, denyAlways, 8))
        card.addView(footer)

        // 整卡限高：中间那块最多吃到「屏幕 72% - 头尾」
        card.post {
            val limit = (screenH() * 0.72f).toInt()
            val fixed = header.measuredHeight + footer.measuredHeight +
                card.paddingTop + card.paddingBottom
            scroll.maxHeightPx = (limit - fixed).coerceAtLeast(dp(96))
            scroll.requestLayout()
        }

        startTicker(request, countdown)
        return card
    }

    /**
     * 头部标题下面那句话要不要再写一遍。
     *
     * 命令类审批的摘要 = 「执行命令：<整条命令>」，命令块会原样显示，所以把那半截摘掉；
     * 剩下的部分跟头部标题一样（比如「执行命令」）就整句不要，是自定义工具名才留下。
     */
    private fun headline(request: ApprovalRequest): String? {
        val cmd = request.command ?: return request.summary
        val idx = request.summary.indexOf(cmd)
        val head = if (idx > 0) request.summary.substring(0, idx) else request.summary
        val trimmed = head.trimEnd('：', ':', ' ').trim()
        if (trimmed.isBlank()) return null
        return trimmed.takeIf { it != L(request.perm.title) }
    }

    /** 两枚按钮并排的一行。 */
    private fun buttonRow(first: View, second: View, topMarginDp: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(topMarginDp), 0, 0)
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                second,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            )
        }

    private fun actionButton(
        label: String,
        fill: Int,
        textColor: Int,
        stroke: Int?,
        all: MutableList<TextView>,
        request: ApprovalRequest,
        decision: ApprovalDecision
    ): TextView = button(label, fill, textColor, stroke).apply {
        setOnClickListener {
            // 点完立刻变灰，防止连点把结果提交两次
            all.forEach { b ->
                b.isEnabled = false
                b.alpha = 0.45f
            }
            resolve(request, decision)
        }
    }

    private fun resolve(request: ApprovalRequest, decision: ApprovalDecision) {
        AppCore.approval.resolve(request.id, decision)
    }

    private fun headerSubtitle(request: ApprovalRequest): String {
        val total = pending.size
        val index = pending.indexOfFirst { it.id == request.id }.coerceAtLeast(0) + 1
        return buildString {
            append(request.tool)
            request.client?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append(" · ").append(index).append(" / ").append(total.coerceAtLeast(index))
        }
    }

    /** 同步「1 / 3」和球上的数字（排队里的条目变了但卡片不用重建时）。 */
    private fun syncCounts() {
        val request = pending.firstOrNull { it.id == shownId }
        if (request != null) counter?.text = headerSubtitle(request)
        refreshBall()
    }

    /** 倒计时那一行：算的是「还剩多少」，不是总时长。 */
    private fun countdownText(request: ApprovalRequest): String {
        if (request.timeoutMs <= 0L) return L("不会自动超时")
        val left = ((request.createdAt + request.timeoutMs - System.currentTimeMillis()) / 1000)
        return if (left <= 0) L("已超时") else L("剩余 %s 秒 · 超时自动拒绝").format(left)
    }

    private fun startTicker(request: ApprovalRequest, view: TextView) {
        stopTicker()
        if (request.timeoutMs <= 0L) return
        val endAt = request.createdAt + request.timeoutMs
        val r = object : Runnable {
            override fun run() {
                view.text = countdownText(request)
                if (endAt > System.currentTimeMillis()) main.postDelayed(this, 1000)
            }
        }
        ticker = r
        main.post(r)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
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

    private fun button(
        label: String,
        fill: Int,
        textColor: Int,
        stroke: Int? = null
    ): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(13), dp(8), dp(13))
        val fillDrawable = rounded(fill, 20f, stroke)
        // 水波裁进圆角里：mask 用同一套圆角，点下去不会溢出成方块
        background = RippleDrawable(
            ColorStateList.valueOf(blend(textColor, fill, 0.18f)),
            fillDrawable,
            rounded(Color.WHITE, 20f)
        )
        isClickable = true
        isFocusable = false
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
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density

    /** 窗口所在显示区域的真实大小（displayMetrics 有时不含状态栏 / 导航栏）。 */
    private fun windowBounds(): android.graphics.Rect? =
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        } else {
            null
        }

    /** 整块屏幕的尺寸：显示区域和 displayMetrics 取大的那个。 */
    private fun screenW(): Int = max(
        context.resources.displayMetrics.widthPixels,
        windowBounds()?.width() ?: 0
    )

    private fun screenH(): Int = max(
        context.resources.displayMetrics.heightPixels,
        windowBounds()?.height() ?: 0
    )

    private fun cardWidth(): Int = (screenW() - dp(28)).coerceAtLeast(dp(200))

    private fun systemDimen(name: String, fallbackDp: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(fallbackDp)
    }

    /**
     * 状态栏 / 导航栏的高度。
     *
     * 关键点：**必须走 WindowManager.currentWindowMetrics**，不能用 View.rootWindowInsets。
     * 后者要等第一帧布局之后才有值，窗口刚建出来时是 null，只能退回
     * `status_bar_height` 这个系统 dimen —— 而它在刘海屏上偏小，
     * 于是卡片先画在偏上的位置、一帧后再被 insets 纠正，看起来就是「闪一下」。
     * currentWindowMetrics 在窗口创建前就能查到，一次就定位准。
     */
    private fun windowInsetsNow(): WindowInsets? =
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { wm?.currentWindowMetrics?.windowInsets }.getOrNull()
        } else {
            cardHost?.rootWindowInsets
        }

    private fun topInset(): Int {
        insetsTop?.let { return it }
        val v = windowInsetsNow()?.let {
            if (Build.VERSION.SDK_INT >= 30) it.getInsets(WindowInsets.Type.statusBars()).top
            else it.systemWindowInsetTop
        } ?: 0
        if (v > 0) insetsTop = v
        // 真拿不到时给一个不至于压住状态栏的兜底值
        return if (v > 0) v else max(systemDimen("status_bar_height", 28), dp(28))
    }

    private fun bottomInset(): Int {
        insetsBottom?.let { return it }
        val v = windowInsetsNow()?.let {
            if (Build.VERSION.SDK_INT >= 30) it.getInsets(WindowInsets.Type.navigationBars()).bottom
            else it.systemWindowInsetBottom
        } ?: 0
        if (v > 0) insetsBottom = v
        return if (v > 0) v else systemDimen("navigation_bar_height", 48)
    }

    /** 自适应 + 上限的滚动容器：内容少时取内容高度，内容多时不超过 maxHeightPx 并可滚动。 */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx.coerceAtLeast(0), MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    /** 头部左侧的圆角方块图标：底色是权限色的淡版，里面一个盾牌。 */
    private inner class IconBadge(ctx: Context, private val accent: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = blend(accent, p.card, 0.22f)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), dpF(14f), dpF(14f), paint)

            val left = w * 0.27f
            val right = w * 0.73f
            val top = h * 0.20f
            val bottom = h * 0.84f
            val cx = w / 2f
            path.reset()
            path.moveTo(cx, top)
            path.lineTo(right, top + h * 0.12f)
            path.lineTo(right, h * 0.50f)
            path.cubicTo(right, h * 0.68f, cx + w * 0.11f, bottom - h * 0.08f, cx, bottom)
            path.cubicTo(cx - w * 0.11f, bottom - h * 0.08f, left, h * 0.68f, left, h * 0.50f)
            path.lineTo(left, top + h * 0.12f)
            path.close()
            paint.color = accent
            canvas.drawPath(path, paint)
        }
    }

    /** 头部右侧的「缩小」按钮：一条横线 + 圆形水波。 */
    private inner class MinimizeButton(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dpF(2.2f)
        }

        init {
            background = RippleDrawable(
                ColorStateList.valueOf(blend(p.text, p.card, 0.14f)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )
            isClickable = true
            isFocusable = false
            contentDescription = L("缩小")
            setOnClickListener { collapse() }
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = p.text
            val cy = height / 2f
            canvas.drawLine(width * 0.30f, cy, width * 0.70f, cy, paint)
        }
    }

}

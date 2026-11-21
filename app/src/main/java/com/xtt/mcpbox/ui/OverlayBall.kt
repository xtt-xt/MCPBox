// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.Prefs
import com.xtt.mcpbox.i18n.L
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 小球松手后贴在屏幕哪一边（设置 → 外观与语言 → 悬浮球 → 小球吸附）。
 *
 * 默认 [AUTO]：哪边近贴哪边；[NONE] 就停在手指松开的位置。
 */
enum class BallEdge(val id: String) {
    NONE("none"),
    LEFT("left"),
    RIGHT("right"),
    AUTO("auto");

    companion object {
        fun of(id: String?): BallEdge = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 悬浮球上的一个「源」：审批、浏览器各注册一个，共用一个球。
 *
 * 谁有东西可看谁就出现；两个都有时球上优先显示 [badge] 数字的那个。
 */
interface BallSource {
    /** 稳定的标识（「approval」/「browser」），重新注册时按它替换。 */
    val id: String

    /** 现在这个源有没有东西要在球上体现。false 就当它不存在。 */
    fun available(): Boolean

    /** 球上显示的数字；0 = 不显示数字，画这个源自己的图标。 */
    fun badge(): Int

    /** 环形菜单里的文字。 */
    fun label(): String

    /** 轻点球（它是主项时）/ 在菜单里选中它时做什么。 */
    fun open()

    /** 没有角标时球上画的图标。 */
    fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint)
}

/**
 * 屏幕上唯一的那颗悬浮球。
 *
 * 审批和浏览器各自注册一个 [BallSource]，球上显示什么、点了干什么都由源的优先级决定：
 *  · 只有审批 → 球上是待审批条数，轻点展开审批卡片
 *  · 只有浏览器 → 球上是地球图标，轻点展开浏览器面板
 *  · 两个都有 → 球上仍是待审批条数（审批更急），轻点进审批；
 *    **长按**震一下、球内侧弹出环形菜单，手指滑到哪一项、松手就打开哪一项
 * 只有一个源可用时长按不弹菜单，只做震动 + 回弹。
 *
 * 位置、吸附、拖出屏幕后的回弹动画都由这里管，两个功能共用一份。
 */
class OverlayBall(private val context: Context) {

    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val p: OverlayPalette get() = AppCore.overlayPalette

    private val sources = ArrayList<BallSource>()

    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anim: android.animation.ValueAnimator? = null

    private var menu: RingMenu? = null

    /** 球心在屏幕上的坐标（菜单定位要用）。 */
    private var centerX = 0f
    private var centerY = 0f

    // ------------------------------------------------------------------ 对外

    /** 注册一个源（同一个 id 再注册就是更新）。调用后自动重新算该不该显示。 */
    fun register(source: BallSource) {
        main.post {
            sources.removeAll { it.id == source.id }
            sources.add(source)
            refresh()
        }
    }

    /** 重新算一遍：一个源都没有就收球，有就把球画成当前该有的样子。 */
    fun refresh() {
        val alive = sources.filter { runCatching { it.available() }.getOrDefault(false) }
        if (alive.isEmpty()) {
            removeMenu()
            removeBall()
            return
        }
        ensureBall()
        ball?.invalidate()
    }

    /** 服务停了 / App 关了：把球和菜单一起收掉（注册的源留着，服务再起来时不用重新注册）。 */
    fun release() {
        main.post {
            anim?.cancel()
            anim = null
            removeMenu()
            removeBall()
        }
    }

    // ------------------------------------------------------------------ 球

    /** 窗口比球本身大一圈：缩放动画（按住 0.9、长按回弹到 1.08）才不会顶到窗口边被裁。 */
    private fun windowSize(): Int = dp(74)
    private fun ballDiameter(size: Float): Float = size - dpF(18f)

    private fun aliveSources(): List<BallSource> =
        sources.filter { runCatching { it.available() }.getOrDefault(false) }

    /** 球上代表谁：优先有数字的那个，都没数字就用第一个。 */
    private fun primaryOf(list: List<BallSource>): BallSource? =
        list.firstOrNull { runCatching { it.badge() }.getOrDefault(0) > 0 } ?: list.firstOrNull()

    private fun ensureBall() {
        if (ball != null) return
        val wmv = wm ?: return
        val view = BallView(context)
        val size = windowSize()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = savedX()
            val sy = savedY()
            x = if (sx < 0) snapTargetX(BallEdge.AUTO, screenW() - size, size)
            else sx.coerceIn(0, max(0, screenW() - size))
            y = if (sy < 0) (screenH() * 0.34f).toInt() else sy.coerceIn(0, max(0, screenH() - size))
        }
        view.setOnTouchListener(touch(view, params))
        runCatching { wmv.addView(view, params) }.onFailure { return }
        // 出现：从 0.6 弹到 1
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.alpha = 0f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator(2.4f))
            .start()
        ball = view
        this.params = params
        syncCenter(params)
    }

    private fun removeBall() {
        anim?.cancel()
        anim = null
        val v = ball ?: return
        ball = null
        params = null
        runCatching { wm?.removeView(v) }
    }

    private fun syncCenter(params: WindowManager.LayoutParams) {
        val size = windowSize()
        centerX = params.x + size / 2f
        centerY = params.y + size / 2f
    }

    /**
     * 拖动 = 挪位置（松手按「小球吸附」决定停哪）；轻点 = 打开主项；
     * 长按 = 震一下 + 回弹；有两个源时再弹出环形菜单，滑到哪项松手就是哪项。
     */
    private fun touch(view: View, params: WindowManager.LayoutParams) =
        object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            private var longPressed = false

            private val longPressDelay = 380L
            private val longPress = Runnable {
                if (!moved && !longPressed) {
                    longPressed = true
                    buzz()
                    bounce(view)
                    syncCenter(params)
                    if (aliveSources().size >= 2) {
                        showMenu(params)
                    }
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 上一段回弹动画还没跑完就又按住了：立刻停在当前位置
                        anim?.cancel()
                        anim = null
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        longPressed = false
                        press(view)
                        main.postDelayed(longPress, longPressDelay)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (longPressed) {
                            // 长按已经触发：这一手势变成「选菜单」，不再拖球
                            menu?.highlightAt(e.rawX, e.rawY)
                            return true
                        }
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                            moved = true
                            main.removeCallbacks(longPress)
                            // 刚开始拖：把按下去时缩的那一点放回来
                            releaseScale(view)
                        }
                        if (moved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            runCatching { wm?.updateViewLayout(view, params) }
                            syncCenter(params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        if (longPressed) {
                            val picked = menu?.pick(e.rawX, e.rawY)
                            removeMenu()
                            releaseScale(view)
                            if (e.actionMasked == MotionEvent.ACTION_UP) picked?.let { runCatching { it.open() } }
                            return true
                        }
                        if (!moved) {
                            releaseScale(view)
                            // 长按过的这次不算轻点，避免「按住看动画」顺手把卡片又展开了
                            if (!longPressed) primaryOf(aliveSources())?.let { runCatching { it.open() } }
                        } else {
                            val size = windowSize()
                            val edge = BallEdge.of(AppCore.prefs.overlayBallEdge)
                            val tx = snapTargetX(edge, params.x, size)
                            val ty = params.y.coerceIn(0, max(0, screenH() - size))
                            if (tx == params.x && ty == params.y) {
                                savePos(tx, ty)
                            } else {
                                springBack(view, params, tx, ty)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        }

    /** 松手后该停在哪一列：不吸附就留在原地，靠左 / 靠右 / 就近。 */
    private fun snapTargetX(edge: BallEdge, currentX: Int, size: Int): Int {
        val maxX = max(0, screenW() - size)
        return when (edge) {
            BallEdge.NONE -> currentX.coerceIn(0, maxX)
            BallEdge.LEFT -> 0
            BallEdge.RIGHT -> maxX
            BallEdge.AUTO -> if (currentX + size / 2 < screenW() / 2) 0 else maxX
        }
    }

    /** 按下：缩到 0.9。 */
    private fun press(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.9f).scaleY(0.9f)
            .setDuration(110)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 长按：从 0.9 胀到 1.08 再落回 1，像被掐了一下。 */
    private fun bounce(view: View) {
        view.animate().cancel()
        view.animate().scaleX(1.08f).scaleY(1.08f)
            .setDuration(110)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .start()
            }
            .start()
    }

    /** 松手 / 开始拖动：恢复原大小。 */
    private fun releaseScale(view: View) {
        if (view.scaleX == 1f && view.scaleY == 1f) return
        view.animate().cancel()
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 长按反馈的震动：很短的一下，不要 30ms 以上的长震。 */
    private fun buzz() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(18, 140))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (v == null || !v.hasVibrator()) return
                v.vibrate(VibrationEffect.createOneShot(18, 140))
            }
        }
    }

    /** 甩出屏幕后弹回屏幕里：按距离给时长，减速曲线，落点写进 Prefs。 */
    private fun springBack(
        view: View,
        params: WindowManager.LayoutParams,
        tx: Int,
        ty: Int
    ) {
        val fromX = params.x
        val fromY = params.y
        val distance = hypot((tx - fromX).toDouble(), (ty - fromY).toDouble()).toFloat()
        val duration = (140 + distance * 0.35f).toInt().coerceIn(160, 360)
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration.toLong()
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.x = (fromX + (tx - fromX) * t).toInt()
                params.y = (fromY + (ty - fromY) * t).toInt()
                runCatching { wm?.updateViewLayout(view, params) }
                syncCenter(params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    params.x = tx
                    params.y = ty
                    runCatching { wm?.updateViewLayout(view, params) }
                    syncCenter(params)
                    savePos(tx, ty)
                    anim = null
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ 环形菜单

    /**
     * 长按弹出的一圈菜单：两个扇区，永远铺在**没被屏幕边挡住**的那一侧 ——
     * 位置是按球心到上 / 下 / 左 / 右四条边的距离现算的：
     *
     * ```
     * 左上角 → 往右下开      右上角 → 往左下开
     * 左下角 → 往右上开      右下角 → 往左上开
     * 贴左边 → 往右开        贴右边 → 往左开
     * 贴顶边 → 往下开        贴底边 → 往上开
     * 四边都远（不吸附停在中间）→ 左右各一个
     * 每个角上两项各占 45°，正好铺满那个象限；贴单边时两项各占 90°，正好铺满那半圈。
     * ```
     */
    private fun showMenu(params: WindowManager.LayoutParams) {
        val wmv = wm ?: return
        val items = aliveSources().take(2)
        if (items.isEmpty()) return
        removeMenu(animate = false)
        val inner = dpF(34f)
        val outer = dpF(96f)
        val layout = ringLayout(outer)
        val view = RingMenu(context).apply {
            this.inner = inner
            this.outer = outer
            this.spread = layout.spread
            this.items = items.mapIndexed { i, src ->
                RingEntry(src, layout.angles.getOrElse(i) { 0f })
            }
        }
        val size = dp(240)
        val wp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 菜单以球心为中心；FLAG_LAYOUT_NO_LIMITS 允许它一部分画出屏幕，
            // 这样扇区位置永远和球的中心对得上（命中判定也用的球心）
            x = (centerX - size / 2f).toInt()
            y = (centerY - size / 2f).toInt()
        }
        runCatching { wmv.addView(view, wp) }.onFailure { return }
        menu = view
        // 弹出：从球心「胀」出来（先小后大、带一点过冲），透明度更快跟上 ——
        // 看着像从球里长出来，而不是凭空淡入
        view.scaleX = 0.58f
        view.scaleY = 0.58f
        view.alpha = 0f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(190)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
    }

    /** 环形菜单的摆法：两项各自的角度 + 每项扇区的半角。 */
    private class RingLayout(val angles: FloatArray, val spread: Float)

    /**
     * 球心到四边的距离 → 菜单往哪边开。
     *
     * 「会不会被挡住」的判据是「扇区最远伸到 [outer] 那么远」：
     * 离某条边不到 outer + 一圈余量就算贴边，那一侧不放东西。
     * 屏幕坐标：0° 指向右，90° 朝下。
     */
    private fun ringLayout(outer: Float): RingLayout {
        val reach = outer + dpF(12f)
        val nearLeft = centerX < reach
        val nearRight = screenW() - centerX < reach
        val nearTop = centerY < reach
        val nearBottom = screenH() - centerY < reach
        return when {
            // 贴角：两项各 45°，正好铺满对角那个象限
            nearLeft && nearTop -> RingLayout(floatArrayOf(22.5f, 67.5f), 22.5f)       // 右下
            nearRight && nearTop -> RingLayout(floatArrayOf(112.5f, 157.5f), 22.5f)     // 左下
            nearLeft && nearBottom -> RingLayout(floatArrayOf(-67.5f, -22.5f), 22.5f)   // 右上
            nearRight && nearBottom -> RingLayout(floatArrayOf(-157.5f, -112.5f), 22.5f) // 左上
            // 只贴一条边：两项各 90°，正好铺满对面那半圈
            nearLeft -> RingLayout(floatArrayOf(-45f, 45f), 45f)      // 右半
            nearRight -> RingLayout(floatArrayOf(135f, -135f), 45f)   // 左半
            nearTop -> RingLayout(floatArrayOf(45f, 135f), 45f)       // 下半
            nearBottom -> RingLayout(floatArrayOf(-45f, -135f), 45f)  // 上半
            // 四边都远（不吸附、停在屏幕中间）：左右各一个
            else -> RingLayout(floatArrayOf(0f, 180f), 45f)
        }
    }

    /**
     * 收起菜单：默认往球心缩回去再摘窗口（和弹出对称），[animate] = false 时立刻摘掉
     * （换位置重建 / 服务停止这种场合不该演动画）。
     */
    private fun removeMenu(animate: Boolean = true) {
        val v = menu ?: return
        menu = null
        if (!animate) {
            runCatching { wm?.removeView(v) }
            return
        }
        v.animate().cancel()
        v.animate()
            .alpha(0f).scaleX(0.72f).scaleY(0.72f)
            .setDuration(130)
            .setInterpolator(AccelerateInterpolator(2f))
            .withEndAction { runCatching { wm?.removeView(v) } }
            .start()
    }

    /**
     * 环形菜单：两片扇区，吃不到触摸（手势还留在球那个窗口里）。
     *
     * 画法（2026-11-21 第二版）：
     *  · 扇区底下铺一层**接近不透明的卡片色** —— 隔着别的应用也得看得清图标和文字；
     *  · 上面压一层主色**扫描渐变**：每片在自己中心最浓、越靠近射线越淡，
     *    这就是「射线两边逐渐变浅的那层」；
     *  · 一圈很淡的整圆轮廓打底，让整体看着还是个圆；
     *  · 射线本身画成「亮线 + 一层宽而淡的辉光」，两片的分界一眼能看出来。
     * 选中状态是渐变的（[mixes] 会平滑插值），不是啪一下换色。
     */
    private inner class RingMenu(ctx: Context) : View(ctx) {
        var inner: Float = dpF(34f)
        var outer: Float = dpF(96f)
        var spread: Float = 45f
        var items: List<RingEntry> = emptyList()
        var highlighted: Int = -1

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = dpF(11.5f)
        }

        private val path = Path()
        private val rect = RectF()
        private var sweep: Shader? = null
        private var sweepDirty = true

        /** 每项的选中程度 0~1，切换时平滑过渡。 */
        private val mixes = FloatArray(2)
        private var mixAnim: android.animation.ValueAnimator? = null

        /** 手指在哪：滑回球心附近 = 不选；否则按角度找最近的扇区。 */
        fun highlightAt(rawX: Float, rawY: Float) {
            val idx = indexAt(rawX, rawY)
            if (idx == highlighted) return
            highlighted = idx
            animateMixes()
        }

        fun pick(rawX: Float, rawY: Float): BallSource? =
            indexAt(rawX, rawY).takeIf { it >= 0 }?.let { items[it].source }

        /** 选中项淡入、其它项淡出；每帧重算一次扫描渐变。 */
        private fun animateMixes() {
            mixAnim?.cancel()
            val from = mixes.copyOf()
            val to = FloatArray(mixes.size) { if (it == highlighted) 1f else 0f }
            mixAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 130
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    for (i in mixes.indices) mixes[i] = from.getOrElse(i) { 0f } + (to[i] - from.getOrElse(i) { 0f }) * t
                    sweepDirty = true
                    invalidate()
                }
                start()
            }
        }

        private fun indexAt(rawX: Float, rawY: Float): Int {
            val dx = rawX - centerX
            val dy = rawY - centerY
            val dist = hypot(dx, dy)
            if (dist < dpF(22f)) return -1
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            var best = -1
            var bestDiff = 360f
            items.forEachIndexed { i, entry ->
                var diff = abs(normalize(deg - entry.angle))
                if (diff > 180f) diff = 360f - diff
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = i
                }
            }
            // 指尖有点偏也算选中（留 22° 的宽容），只在明显没对着任何一项时才取消
            return if (bestDiff <= spread + 22f) best else -1
        }

        /** 把角度收进 -180..180，好算差值。 */
        private fun normalize(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** 某个角度上的浓度 0~1：扇区中心最浓，靠近分界一路淡到 0。 */
        private fun strengthAt(deg: Float): Float {
            var best = 0f
            items.forEachIndexed { i, entry ->
                var diff = abs(normalize(deg - entry.angle))
                if (diff > 180f) diff = 360f - diff
                if (diff >= spread) return@forEachIndexed
                val t = diff / spread
                // 中心 30% 保持浓，之后用 smoothstep 一路淡到分界处为 0
                val fade = 1f - smoothstep(0.30f, 1f, t)
                val mix = mixes.getOrElse(i) { 0f }
                val weighted = fade * (0.42f + 0.58f * mix)
                if (weighted > best) best = weighted
            }
            return best
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        private fun buildSweep(cx: Float, cy: Float) {
            val steps = 240
            val colors = IntArray(steps)
            val tint = blend(p.primary, p.card, 0.55f)
            for (i in 0 until steps) {
                val deg = i * (360f / steps)
                colors[i] = withAlpha(tint, (255 * strengthAt(deg)).toInt())
            }
            sweep = SweepGradient(cx, cy, colors, null)
            sweepDirty = false
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (items.isEmpty() || outer <= 0f) return
            if (sweepDirty) buildSweep(cx, cy)

            // 磨边用 DST_IN，必须关在自己的图层里画 —— 直接作用在窗口画布上
            // 会把下面别的应用一起抠掉
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

            // ---- 1. 一圈很淡的整圆打底：扇区只占一部分角度，但整体要看着是个圆
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addCircle(cx, cy, outer, Path.Direction.CW)
            path.addCircle(cx, cy, inner, Path.Direction.CW)
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = withAlpha(blend(p.card, p.background, 0.96f), 46)
            canvas.drawPath(path, paint)

            // ---- 2. 扇区：保持透明，只垫一层极淡的底色把范围勾出来
            items.forEach { entry ->
                paint.color = withAlpha(blend(p.card, p.background, 0.98f), 30)
                canvas.drawPath(sectorPath(cx, cy, entry.angle), paint)
            }

            // ---- 3. 主色浓度：扫描渐变（中心浓、射线处透明）
            paint.shader = sweep
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, outer, paint)
            paint.shader = null

            // ---- 4. 边缘磨软：内圈和外圈都 fade 掉，看着像一团光而不是硬扇形
            maskPaint.shader = RadialGradient(
                cx, cy, outer,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.WHITE,
                    Color.WHITE,
                    withAlpha(Color.WHITE, 210),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    (inner * 1.02f / outer).coerceIn(0f, 0.9f),
                    0.55f,
                    0.78f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            maskPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, outer, maskPaint)
            maskPaint.xfermode = null
            canvas.restoreToCount(layer)

            // ---- 5. 整圆轮廓：一圈极淡的描边，把「圆」这个形状点出来
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(1f)
            paint.color = withAlpha(blend(p.outline, p.card, 0.55f), 90)
            canvas.drawCircle(cx, cy, outer - dpF(0.5f), paint)

            // ---- 6. 射线：两片的分界。先画一层宽而淡的辉光，再画实线
            val rayColor = blend(p.outline, p.card, 0.85f)
            items.forEach { entry ->
                listOf(entry.angle - spread, entry.angle + spread).forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val c = cos(rad).toFloat()
                    val s2 = sin(rad).toFloat()
                    val x0 = cx + inner * 0.96f * c
                    val y0 = cy + inner * 0.96f * s2
                    val x1 = cx + outer * 0.94f * c
                    val y1 = cy + outer * 0.94f * s2
                    rayPaint.style = Paint.Style.STROKE
                    rayPaint.strokeCap = Paint.Cap.ROUND
                    // 辉光：宽 5dp、透明度一路淡到 0
                    rayPaint.strokeWidth = dpF(5f)
                    rayPaint.shader = LinearGradient(
                        x0, y0, x1, y1,
                        withAlpha(rayColor, 54), withAlpha(rayColor, 0),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawLine(x0, y0, x1, y1, rayPaint)
                    // 实线：1.2dp
                    rayPaint.strokeWidth = dpF(1.2f)
                    rayPaint.shader = LinearGradient(
                        x0, y0, x1, y1,
                        withAlpha(rayColor, 235), withAlpha(rayColor, 40),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawLine(x0, y0, x1, y1, rayPaint)
                }
            }
            rayPaint.shader = null

            // ---- 7. 图标 + 文字：颜色跟明暗主题走（深色主题白字、浅色主题黑字），
            //        选中项更实一些，没选中的淡下去
            val mid = (inner + outer) / 2f
            val base = if (p.dark) Color.WHITE else Color.rgb(0x12, 0x12, 0x14)
            val dim = withAlpha(base, 190)
            items.forEachIndexed { i, entry ->
                val mix = mixes.getOrElse(i) { 0f }
                val rad = Math.toRadians(entry.angle.toDouble())
                val ix = cx + (mid * cos(rad)).toFloat()
                val iy = cy + (mid * sin(rad)).toFloat()
                val tint = lerpColor(dim, base, mix)

                paint.style = Paint.Style.FILL
                paint.color = tint
                canvas.save()
                canvas.translate(ix, iy - dpF(10f))
                entry.source.drawIcon(canvas, 0f, 0f, dpF(11.5f), paint)
                canvas.restore()

                text.color = tint
                canvas.drawText(entry.source.label(), ix, iy + dpF(20f), text)
            }
        }

        /** 一个扇区（内半径到外半径、±spread 度）；选中的那一项圆角更大一点。 */
        private fun sectorPath(cx: Float, cy: Float, angle: Float): Path {
            path.reset()
            rect.set(cx - outer, cy - outer, cx + outer, cy + outer)
            path.arcTo(rect, angle - spread, spread * 2f, true)
            rect.set(cx - inner, cy - inner, cx + inner, cy + inner)
            path.arcTo(rect, angle + spread, -spread * 2f, false)
            path.close()
            return path
        }

        private fun lerpColor(from: Int, to: Int, t: Float): Int {
            val a = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(from) * (1 - a) + Color.alpha(to) * a).toInt(),
                (Color.red(from) * (1 - a) + Color.red(to) * a).toInt(),
                (Color.green(from) * (1 - a) + Color.green(to) * a).toInt(),
                (Color.blue(from) * (1 - a) + Color.blue(to) * a).toInt()
            )
        }
    }

    private class RingEntry(val source: BallSource, val angle: Float)

    // ------------------------------------------------------------------ 球的样子

    /** 球：深色圆 + 边框 + 数字或图标。 */
    private inner class BallView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val d = ballDiameter(min(width, height).toFloat())
            val cx = width / 2f
            val cy = height / 2f
            val r = d / 2f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(56, 0, 0, 0)
            canvas.drawCircle(cx, cy + dpF(2f), r, paint)
            paint.color = blend(p.card, p.background, 0.97f)
            canvas.drawCircle(cx, cy, r, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(1.4f)
            paint.color = blend(p.primary, p.card, 0.55f)
            canvas.drawCircle(cx, cy, r, paint)

            paint.style = Paint.Style.FILL
            paint.color = blend(p.primary, p.card, 0.18f)
            canvas.drawCircle(cx, cy, r - dpF(4.5f), paint)

            val list = aliveSources()
            val primary = primaryOf(list)
            val badge = primary?.let { runCatching { it.badge() }.getOrDefault(0) } ?: 0
            if (badge > 0) {
                text.color = p.primary
                text.textSize = dpF(17f)
                val label = if (badge > 9) "9+" else badge.toString()
                val fm = text.fontMetrics
                canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text)
            } else if (primary != null) {
                paint.color = p.primary
                primary.drawIcon(canvas, cx, cy, r * 0.52f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    // ------------------------------------------------------------------ 偏好

    /**
     * 球的位置：先用合并后的新键，没有就回落到旧的审批球位置
     * （升级上来的人不会突然发现球跑到了默认位置）。
     */
    private fun savedX(): Int {
        val v = AppCore.prefs.getInt(Prefs.KEY_OVERLAY_BALL_X, -1)
        return if (v >= 0) v else AppCore.prefs.getInt(Prefs.KEY_APPROVAL_BALL_X, -1)
    }

    private fun savedY(): Int {
        val v = AppCore.prefs.getInt(Prefs.KEY_OVERLAY_BALL_Y, -1)
        return if (v >= 0) v else AppCore.prefs.getInt(Prefs.KEY_APPROVAL_BALL_Y, -1)
    }

    private fun savePos(x: Int, y: Int) {
        AppCore.prefs.putInt(Prefs.KEY_OVERLAY_BALL_X, x)
        AppCore.prefs.putInt(Prefs.KEY_OVERLAY_BALL_Y, y)
    }

    // ------------------------------------------------------------------ 尺寸

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )

    private fun screenW(): Int = context.resources.displayMetrics.widthPixels
    private fun screenH(): Int = context.resources.displayMetrics.heightPixels

    /** 把 [color] 按 [alpha] 混到 [bg] 上（悬浮窗不能用 Material 的语义色）。 */
    private fun blend(color: Int, bg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val r = (Color.red(color) * a + Color.red(bg) * (1 - a)).toInt()
        val g = (Color.green(color) * a + Color.green(bg) * (1 - a)).toInt()
        val b = (Color.blue(color) * a + Color.blue(bg) * (1 - a)).toInt()
        return Color.rgb(r, g, b)
    }
}

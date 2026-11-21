// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.OverlayPalette
import com.xtt.mcpbox.core.BrowserBridge
import com.xtt.mcpbox.core.BrowserPageInfo
import com.xtt.mcpbox.core.EventLog
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.i18n.L
import com.xtt.mcpbox.ui.BallSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * 内置浏览器的 Android 实现：[BrowserBridge] 的落地。
 *
 * 三个窗口（都是 `TYPE_APPLICATION_OVERLAY`，和审批悬浮窗同一套路，所以只要悬浮窗权限）：
 *  · **悬浮球** —— 有页面就出现，可拖动、贴边半收，球上显示「AI 正在做什么」；
 *  · **面板窗** —— 显示当前页面，可拖动 / 缩放，里面有页面标签、暂停 / 关闭按钮；
 *  · 面板折叠时把它缩成 1×1 像素，[PageHost] 仍然按整屏给 WebView 排版，
 *    于是页面在后台也是「正常尺寸」的（懒加载、媒体查询、坐标点击都不会错位），
 *    截图靠 `View.draw()`，不需要窗口真的可见。
 *
 * 线程模型：WebView 只能在主线程碰，所以 core 调进来的每个方法都会切到主线程；
 * `evaluateJavascript` 的结果是异步回调，**不能在主线程上等它**（会死锁），
 * 所以 [eval] 是「主线程发指令 + 调用线程等结果」。
 */
class BrowserController(private val context: Context) : BrowserBridge {

    private companion object {
        const val KEY_PANEL_W = "browser_panel_w"
        const val KEY_PANEL_H = "browser_panel_h"
        const val KEY_PANEL_X = "browser_panel_x"
        const val KEY_PANEL_Y = "browser_panel_y"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"
    }

    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val p: OverlayPalette get() = AppCore.overlayPalette

    /** WebView 需要一个带主题的 Context（下拉 / 弹窗才会好看）。 */
    private val themed: Context = if (context is android.app.Activity) context
    else android.view.ContextThemeWrapper(context, com.xtt.mcpbox.R.style.Theme_MCPBox)

    private class Page(val id: String) {
        /**
         * 这一页的 WebView。休眠时（[sleeping]）它是 null —— 彻底销毁，
         * 只留地址、标题和滚动位置，下次用到时 [wake] 重新建一个。
         */
        var web: WebView? = null
        var title: String = ""
        var url: String = ""
        var loading: Boolean = true
        var error: String? = null
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false

        /** 这一页自己的身份（mobile / desktop / 自定义串）；null = 跟随全局设置。 */
        var uaOverride: String? = null

        /** 最后一次被用到的时刻（开机以来的毫秒）：AI 操作、用户切页、触摸都算。休眠计时看它。 */
        var lastUsed: Long = SystemClock.elapsedRealtime()

        /** 已经休眠：WebView 已销毁，只剩地址。 */
        var sleeping: Boolean = false

        /** 休眠前记下的滚动位置，唤醒并加载完再滚回去。 */
        var sleepScrollY: Int = 0

        fun info(): BrowserPageInfo = BrowserPageInfo(
            id = id, url = url, title = title, loading = loading,
            canGoBack = canGoBack, canGoForward = canGoForward, error = error,
            ua = uaOverride, sleeping = sleeping
        )
    }

    private val pages = LinkedHashMap<String, Page>()
    private var seq = 0

    @Volatile private var currentId: String? = null
    @Volatile private var statusText: String = ""
    @Volatile private var pausedFlag: Boolean = false

    /**
     * 浏览器在共用悬浮球上的那一个「源」：
     * 只要还有页面就在球上出现，球上是地球图标（有审批时让位给待审批条数）。
     */
    private val ballSource = object : BallSource {
        override val id = "browser"
        override fun available(): Boolean = pages.isNotEmpty()
        override fun badge(): Int = 0
        override fun label(): String = L("浏览器")
        override fun open() = call { togglePanel() }
        override fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
            // 地球：一个圆 + 一条赤道 + 一条经线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.18f
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawOval(
                cx - radius * 0.46f, cy - radius,
                cx + radius * 0.46f, cy + radius, paint
            )
            paint.style = Paint.Style.FILL
        }
    }

    init {
        AppCore.ball.register(ballSource)
    }

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pageHost: PageHost? = null
    private var tabRow: LinearLayout? = null
    private var titleView: TextView? = null
    private var hintView: TextView? = null
    private var pauseBtn: TextView? = null
    private var expanded = false

    /** WebView 出厂默认 UA（第一次创建时记下来，好还原）。 */
    private var defaultUa: String? = null

    // =================================================================== 线程

    /** 在主线程同步执行（已经站在主线程就直接跑）。 */
    private fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: Any? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                value = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(30, TimeUnit.SECONDS)) throw IllegalStateException(L("浏览器主线程没有响应（超时）"))
        error?.let { throw IllegalStateException(it.message ?: it.javaClass.simpleName) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { runCatching { block() } }
    }

    private fun log(message: String, ok: Boolean = true) {
        runCatching { AppCore.log.add(LogKind.SYSTEM, tool = "browser", ok = ok, message = message) }
    }

    // ================================================================ 引擎状态

    override fun available(): Boolean = wm != null && canDrawOverlays()

    override fun unavailableReason(): String = when {
        wm == null -> L("拿不到 WindowManager")
        !canDrawOverlays() -> L("没有悬浮窗权限（浏览器用悬浮窗承载页面，去 首页 → 环境检查 → 悬浮窗 授权）")
        else -> ""
    }

    private fun canDrawOverlays(): Boolean = try {
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        false
    }

    // ==================================================================== 页面

    override fun open(url: String, ua: String?): String = call {
        val web = newWebView()
        val id = "p${++seq}"
        val page = Page(id)
        page.web = web
        // 身份要在 loadUrl 之前定下来 —— 否则第一个请求已经用旧 UA 出去了
        page.uaOverride = ua
        applyUa(page)
        pages[id] = page
        web.loadUrl(url)
        pageHost?.addView(web, frameParamsOf())
        currentId = id
        refreshVisibility()
        rebuildTabs()
        AppCore.ball.refresh()
        ensurePanel()
        refreshPanel()
        scheduleSleepTick()
        log(L("浏览器：新开页面 %s").format(url))
        id
    }

    override fun close(id: String) = call {
        val page = pages.remove(id) ?: return@call
        page.web?.let { pageHost?.removeView(it) }
        destroy(page)
        if (currentId == id) currentId = pages.keys.lastOrNull()
        afterPagesChanged()
    }

    override fun closeAll() = call {
        pages.values.toList().forEach { page ->
            page.web?.let { pageHost?.removeView(it) }
            destroy(page)
        }
        pages.clear()
        currentId = null
        pausedFlag = false
        afterPagesChanged()
    }

    private fun destroy(page: Page) {
        val web = page.web ?: return
        page.web = null
        destroy(web)
    }

    private fun destroy(web: WebView) {
        runCatching {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.removeAllViews()
            web.destroy()
        }
    }

    // -------------------------------------------------------------- 休眠 / 唤醒

    /** 页面 id → 唤醒后要滚回去的位置（加载完再滚，否则会被页面自己重置）。 */
    private val restoreScroll = HashMap<String, Int>()

    /** 每 20 秒看一遍有没有页面该休眠了。 */
    private val sleepTick = object : Runnable {
        override fun run() = sweepSleep()
    }

    private fun scheduleSleepTick() {
        main.removeCallbacks(sleepTick)
        if (pages.isEmpty()) return
        if (AppCore.config.browserSleepMinutes <= 0) return
        main.postDelayed(sleepTick, 20_000L)
    }

    /**
     * 到点的页面休眠：销毁 WebView，只留地址与滚动位置。
     *
     * 「看得见」的那一页不冻 —— 面板展开且它是当前页时，用户正看着它。
     * 面板收起以后没有任何页面是看得见的，所以当前页也按时间休眠。
     * 加载中的页面跳过，免得把正在加载的东西毁掉。
     */
    private fun sweepSleep() {
        val minutes = AppCore.config.browserSleepMinutes
        if (minutes <= 0) return
        val limit = minutes * 60_000L
        val now = SystemClock.elapsedRealtime()
        pages.values.toList().forEach { page ->
            if (page.sleeping || page.loading) return@forEach
            if (expanded && page.id == currentId) return@forEach
            if (now - page.lastUsed >= limit) sleep(page)
        }
        scheduleSleepTick()
    }

    private fun sleep(page: Page) {
        val web = page.web ?: return
        page.sleeping = true
        page.web = null
        page.loading = false
        page.error = null
        page.canGoBack = false
        page.canGoForward = false
        var finished = false
        // 滚动位置要先问页面（回调在主线程，不能在这里等）
        val finish = {
            if (!finished) {
                finished = true
                pageHost?.removeView(web)
                destroy(web)
                rebuildTabs()
                refreshPanel()
                log(L("浏览器：页面已休眠 %s").format(page.url.ifBlank { page.id }))
            }
        }
        main.postDelayed({ finish() }, 800)
        runCatching {
            web.evaluateJavascript("String(Math.round(window.scrollY||0))") { result ->
                page.sleepScrollY = result?.trim('"')?.toIntOrNull() ?: 0
                main.post { finish() }
            }
        }.onFailure { finish() }
    }

    /** 唤醒休眠页（主线程）：重建 WebView 并加载原地址；[urlOverride] 给了就直接开新地址。 */
    private fun wake(page: Page, urlOverride: String? = null): WebView? {
        page.web?.let { return it }
        val web = newWebView()
        page.web = web
        page.sleeping = false
        page.loading = true
        page.error = null
        applyUa(page)
        pageHost?.addView(web, frameParamsOf())
        web.visibility = if (page.id == currentId) View.VISIBLE else View.INVISIBLE
        val url = urlOverride ?: page.url.ifBlank { "about:blank" }
        if (urlOverride == null && page.sleepScrollY > 0) restoreScroll[page.id] = page.sleepScrollY
        page.sleepScrollY = 0
        web.loadUrl(url)
        page.lastUsed = SystemClock.elapsedRealtime()
        scheduleSleepTick()
        log(L("浏览器：唤醒页面 %s").format(url))
        return web
    }

    private fun ensureAwake(page: Page): WebView? = page.web ?: wake(page)

    /**
     * core 线程用：休眠页先唤醒，并等它加载完（最多 [timeoutMs]）。
     * 内部会 sleep 轮询，必须在**非主线程**调用。
     */
    private fun ensureAwakeWaiting(page: Page, timeoutMs: Long = 20_000) {
        if (call { page.sleeping }) call { wake(page) }
        if (!call { page.loading }) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!call { page.loading }) break
            runCatching { Thread.sleep(100) }
        }
        // 刚加载完再给一帧，截图 / 读内容才有东西
        runCatching { Thread.sleep(260) }
    }

    private fun touch(page: Page?) {
        if (page != null) page.lastUsed = SystemClock.elapsedRealtime()
    }

    private fun afterPagesChanged() {
        refreshVisibility()
        rebuildTabs()
        refreshPanel()
        if (pages.isEmpty()) {
            main.removeCallbacks(sleepTick)
            collapse()
            AppCore.ball.refresh()
        } else {
            // 面板开着的时候换了个当前页（比如关掉了刚才那一页）：新当前页要是休眠的就叫醒
            if (expanded) pages[currentId]?.let { ensureAwake(it) }
            scheduleSleepTick()
        }
    }

    override fun navigate(id: String, url: String, ua: String?) {
        call {
            val page = pages[id] ?: return@call
            if (ua != null) {
                page.uaOverride = ua
                applyUa(page)
            }
            page.loading = true
            page.error = null
            page.lastUsed = SystemClock.elapsedRealtime()
            // 休眠页不用先把旧地址加载一遍，直接开到新地址
            if (page.sleeping) wake(page, url) else page.web?.loadUrl(url)
        }
    }

    override fun history(id: String, action: String) = call {
        val page = pages[id] ?: return@call
        val web = ensureAwake(page) ?: return@call
        page.lastUsed = SystemClock.elapsedRealtime()
        when (action) {
            "back" -> if (web.canGoBack()) web.goBack()
            "forward" -> if (web.canGoForward()) web.goForward()
            else -> web.reload()
        }
    }

    override fun pages(): List<BrowserPageInfo> = call { pages.values.map { it.info() } }

    override fun currentId(): String? = currentId

    override fun switchTo(id: String) = call {
        val page = pages[id] ?: return@call
        currentId = id
        page.lastUsed = SystemClock.elapsedRealtime()
        ensureAwake(page)
        refreshVisibility()
        rebuildTabs()
        refreshPanel()
        scheduleSleepTick()
    }

    override fun eval(id: String, js: String): String? {
        val page = call { pages[id] } ?: return null
        // 休眠页先唤醒并等它加载完，否则读到的是白页
        ensureAwakeWaiting(page)
        // 结果回调在主线程 → 这里只能在**调用线程**上等，不能占着主线程
        var value: String? = null
        val latch = CountDownLatch(1)
        main.post {
            val web = page.web
            if (web == null) {
                latch.countDown()
                return@post
            }
            page.lastUsed = SystemClock.elapsedRealtime()
            runCatching {
                web.evaluateJavascript(js) { result ->
                    value = result
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        return if (latch.await(25, TimeUnit.SECONDS)) value else null
    }

    override fun screenshot(id: String): ByteArray? {
        val page = call { pages[id] } ?: return null
        // 休眠页要重新加载出来才画得出东西
        ensureAwakeWaiting(page)
        return call {
            val web = page.web ?: return@call null
            page.lastUsed = SystemClock.elapsedRealtime()
        val w = if (web.width > 0) web.width else screenW()
        val h = if (web.height > 0) web.height else screenH()
        if (w <= 0 || h <= 0) return@call null
        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val prev = web.layerType
            // WebView 走硬件加速时 draw() 可能是空图，强制软件渲染一次
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web.draw(canvas)
            web.setLayerType(prev, null)
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            log(L("网页截图失败：%s").format(t.message ?: t.javaClass.simpleName), ok = false)
            null
        } finally {
            runCatching { bmp?.recycle() }
        }
        }
    }

    // ============================================================== 状态 / 暂停

    override fun setStatus(text: String) {
        statusText = text
        post { hintView?.text = text }
    }

    override fun paused(): Boolean = pausedFlag

    override fun setPaused(value: Boolean) {
        pausedFlag = value
        post {
            refreshPanel()
            if (value) setStatus(L("已暂停，等你继续"))
        }
    }

    // ================================================================ cookie 等

    override fun cookies(url: String?): String = call {
        val cm = CookieManager.getInstance()
        if (!url.isNullOrBlank()) cm.getCookie(url).orEmpty()
        else pages.values.joinToString("\n") { pg ->
            val c = runCatching { cm.getCookie(pg.url) }.getOrNull().orEmpty()
            if (c.isBlank()) "" else "# ${pg.url}\n$c"
        }.trim()
    }

    override fun setCookie(url: String, cookie: String): Boolean = call {
        runCatching {
            CookieManager.getInstance().setCookie(url, cookie)
            CookieManager.getInstance().flush()
            true
        }.getOrDefault(false)
    }

    /**
     * 只清掉**一个站点**的 cookie（别的站点不动）。
     *
     * WebView 的 CookieManager 没有「按域名删」的接口，所以做法是：先读出这个网址能看到的 cookie，
     * 再逐条用「同名 + 立刻过期」覆盖掉（host-only / domain、各层 path 都试一遍），最后重读一遍算清掉几条。
     * **HttpOnly 的 cookie 读不到**（Chromium 不把它交给 Java 层）→ 删不掉，这是硬限制。
     */
    override fun clearSiteCookies(url: String): Int = call {
        val cm = CookieManager.getInstance()
        val before = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
        val names = before.split(';').map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (names.isEmpty()) return@call 0
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val path = uri?.path.orEmpty()
        val paths = listOf("/", path, path.substringBeforeLast('/', "/"))
            .filter { it.isNotEmpty() }.distinct()
        val domains = listOf("", host, if (host.isEmpty()) "" else ".$host")
            .filter { it.isNotEmpty() }.distinct() + ""
        val expired = "Thu, 01 Jan 1970 00:00:00 GMT"
        names.forEach { name ->
            paths.forEach { p ->
                domains.forEach { d ->
                    val tail = buildString {
                        if (d.isNotEmpty()) append("; Domain=").append(d)
                        append("; Path=").append(p)
                        append("; Expires=").append(expired)
                        append("; Max-Age=0")
                    }
                    runCatching { cm.setCookie(url, "$name=;$tail") }
                }
            }
        }
        runCatching { cm.flush() }
        val after = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
        val left = after.split(';').map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }.toSet()
        names.count { it !in left }
    }

    override fun clearCookies() = call {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        Unit
    }

    override fun clearCache() = call {
        runCatching {
            CookieManager.getInstance().flush()
            pages.values.forEach { it.web?.clearCache(true) }
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearFormData()
        }
        Unit
    }

    override fun userAgent(id: String?): String = call {
        val page = (if (id != null) pages[id] else null) ?: pages.values.firstOrNull()
        page?.web?.settings?.userAgentString
            ?: runCatching { resolveUa(page?.uaOverride ?: AppCore.config.browserUserAgent) }.getOrNull()
            ?: ""
    }

    override fun setPageUserAgent(id: String, ua: String?) = call {
        pages[id]?.let {
            it.uaOverride = ua
            applyUa(it)
        }
        Unit
    }

    override fun setUserAgent(ua: String?) = call {
        AppCore.config.browserUserAgent = ua ?: "default"
        AppCore.saveConfig()
        // 自己设过身份的页面不动（「两个都要」：全局默认 + 单页覆盖）
        pages.values.filter { it.uaOverride == null }.forEach { applyUa(it) }
        Unit
    }

    /** 服务停了 / App 关了：把窗口和 WebView 全部收掉。 */
    override fun release() {
        main.post {
            runCatching {
                main.removeCallbacks(sleepTick)
                pages.values.toList().forEach { pg ->
                    pg.web?.let { pageHost?.removeView(it) }
                    destroy(pg)
                }
                pages.clear()
                currentId = null
                pausedFlag = false
                statusText = ""
                AppCore.ball.refresh()
                removePanel()
            }
        }
    }

    // ================================================================== WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        ensurePanel()
        val web = WebView(themed)
        if (defaultUa == null) defaultUa = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // UA 不在这里设：页面自己的身份要等 Page 建出来才知道（open() / onCreateWindow 里 applyUa(page)）
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        // 用户在这一页上触摸（滚动、点击）也算「在用」，休眠计时从这里重新开始
        web.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) touch(pageOf(web))
            false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url?.toString().orEmpty()
                return !u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("about:")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageOf(view)?.let {
                    it.url = url.orEmpty()
                    it.loading = true
                    it.canGoBack = view.canGoBack()
                    it.canGoForward = view.canGoForward()
                }
                refreshPanel()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val pg = pageOf(view) ?: return
                pg.url = url.orEmpty().ifBlank { pg.url }
                pg.title = view.title.orEmpty().ifBlank { pg.title }
                pg.loading = false
                pg.canGoBack = view.canGoBack()
                pg.canGoForward = view.canGoForward()
                CookieManager.getInstance().flush()
                // 休眠唤醒的页面：加载完滚回原来的位置
                restoreScroll.remove(pg.id)?.let { y ->
                    if (y > 0) view.evaluateJavascript("window.scrollTo(0,$y)", null)
                }
                rebuildTabs()
                refreshPanel()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val desc = if (Build.VERSION.SDK_INT >= 23) error.description?.toString().orEmpty() else ""
                pageOf(view)?.let {
                    it.loading = false
                    it.error = desc.ifBlank { L("加载失败") }
                }
                refreshPanel()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                pageOf(view)?.let {
                    it.loading = false
                    it.error = "HTTP ${errorResponse.statusCode}"
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                val pg = pageOf(view)
                if (pg != null) {
                    pg.error = L("页面进程被系统回收了")
                    pg.loading = false
                    log(L("网页进程被系统回收（%s）").format(pg.url), ok = false)
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                pageOf(view)?.let { it.title = title.orEmpty() }
                rebuildTabs()
                refreshPanel()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageOf(view)?.let { it.loading = newProgress < 100 }
            }

            /** target=_blank：在 App 里新开一个页面（也要受页面数上限约束）。 */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                return runCatching {
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    val child = newWebView()
                    val id = "p${++seq}"
                    val childPage = Page(id)
                    childPage.web = child
                    applyUa(childPage)
                    pages[id] = childPage
                    child.webViewClient = web.webViewClient
                    child.webChromeClient = web.webChromeClient
                    pageHost?.addView(child, frameParamsOf())
                    currentId = id
                    transport.webView = child
                    resultMsg.sendToTarget()
                    refreshVisibility()
                    rebuildTabs()
                    refreshPanel()
                    AppCore.ball.refresh()
                    scheduleSleepTick()
                    true
                }.getOrDefault(false)
            }

            override fun onCloseWindow(window: WebView) {
                call {
                    val id = pages.entries.firstOrNull { it.value.web === window }?.key
                    if (id != null) close(id)
                }
            }
        }
        return web
    }

    /**
     * 「规格」→ 真正要用的 UA 串：
     * mobile/android/phone → 内置安卓 Chrome；desktop/windows/win/pc → 内置 Windows Chrome；
     * default/webview/空 → WebView 出厂值；其它 → 原样当自定义 UA 串。
     */
    private fun resolveUa(spec: String?): String? = when (spec?.trim()?.lowercase()) {
        null, "", "default", "webview", "auto", "reset", "global" -> defaultUa
        "mobile", "android", "phone" -> MOBILE_UA
        "desktop", "windows", "win", "pc" -> DESKTOP_UA
        else -> spec.trim()
    }

    /** 页面自己的身份优先，没有就用全局默认。 */
    private fun applyUa(page: Page) {
        val spec = page.uaOverride ?: AppCore.config.browserUserAgent
        runCatching { page.web?.settings?.userAgentString = resolveUa(spec) ?: defaultUa }
    }

    private fun pageOf(web: WebView): Page? = pages.values.firstOrNull { it.web === web }

    // ================================================================== 面板窗

    private fun ensurePanel() {
        if (panel != null) return
        val wmv = wm ?: return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(blend(p.card, p.background, 0.94f), 22f, blend(p.outline, p.card, 0.55f))
            clipToOutline = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // 顶栏：标题 + 状态
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(7))
        }
        titleView = TextView(context).apply {
            setTextColor(p.text)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        hintView = TextView(context).apply {
            setTextColor(p.textDim)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            hintView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.END
            }
        )
        header.setOnTouchListener(panelDrag(root))
        header.isClickable = true
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 顶栏下的细分隔线：把工具条和页面分开，看着更清爽
        root.addView(
            View(context).apply { background = rounded(blend(p.outline, p.card, 0.35f), 1f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(6) }
        )

        // 页面标签（横向滚动）
        tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        // 按钮行
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(4))
        }
        pauseBtn = panelButton(L("暂停 AI")) {
            setPaused(!pausedFlag)
        }
        val reloadBtn = panelButton(L("刷新页面")) {
            reloadPage(currentId ?: pages.keys.lastOrNull())
        }
        val closeOne = panelButton(L("关闭本页")) {
            val id = currentId ?: return@panelButton
            call { close(id) }
        }
        val closeAll = panelButton(L("全部关闭")) {
            setPaused(false)
            call { closeAll() }
        }
        val collapse = panelButton(L("收起")) { collapse() }
        listOf(pauseBtn!!, reloadBtn, closeOne, closeAll, collapse).forEachIndexed { i, v ->
            buttons.addView(
                v,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(6)
                }
            )
        }
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 页面宿主 + 右下角缩放柄
        pageHost = PageHost(context).apply {
            fallbackWidth = screenW()
            fallbackHeight = screenH()
        }
        val holder = FrameLayout(context)
        holder.addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        val handle = View(context).apply {
            background = rounded(blend(p.textDim, p.card, 0.35f), 6f)
        }
        holder.addView(
            handle,
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(6); bottomMargin = dp(6)
            }
        )
        handle.setOnTouchListener(resizeHandle(root))
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            TextView(context).apply {
                text = L("可以在这里手动操作页面（长按标签关掉它）")
                setTextColor(p.textDim)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val params = WindowManager.LayoutParams(
            dp(1), dp(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = AppCore.prefs.getInt(KEY_PANEL_X, -1).takeIf { it >= 0 } ?: dp(12)
            y = AppCore.prefs.getInt(KEY_PANEL_Y, -1).takeIf { it >= 0 } ?: (screenH() * 0.18f).toInt()
        }
        runCatching { wmv.addView(root, params) }.onFailure {
            log(L("浏览器悬浮窗创建失败：%s").format(it.message), ok = false)
        }
        panel = root
        panelParams = params
    }

    private fun removePanel() {
        val v = panel ?: return
        runCatching { wm?.removeView(v) }
        panel = null
        panelParams = null
        pageHost = null
        tabRow = null
        titleView = null
        hintView = null
        pauseBtn = null
        expanded = false
    }

    /** 折叠 = 面板缩成 1×1 且不吃触摸；展开 = 恢复上次的尺寸并抢焦点（才能打字）。 */
    private fun collapse() {
        val params = panelParams ?: return
        val v = panel ?: return
        if (!expanded) return
        expanded = false
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        params.width = dp(1)
        params.height = dp(1)
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(v, params) }
        refreshPanel()
    }

    private fun expand() {
        ensurePanel()
        val params = panelParams ?: return
        val v = panel ?: return
        val sw = screenW()
        val sh = screenH()
        val w = AppCore.prefs.getInt(KEY_PANEL_W, -1).takeIf { it > dp(120) } ?: (sw * 0.7f).toInt()
        val h = AppCore.prefs.getInt(KEY_PANEL_H, -1).takeIf { it > dp(120) } ?: (sh * 0.7f).toInt()
        expanded = true
        // 面板重新打开 = 用户要看了：休眠的当前页先唤醒
        pages[currentId]?.let {
            touch(it)
            ensureAwake(it)
        }
        pageHost?.apply {
            fallbackWidth = sw
            fallbackHeight = sh
        }
        params.width = w.coerceIn(dp(160), sw)
        params.height = h.coerceIn(dp(160), sh)
        params.flags = params.flags and
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        params.x = (params.x).coerceIn(0, max(0, sw - params.width))
        params.y = (params.y).coerceIn(0, max(0, sh - params.height))
        runCatching { wm?.updateViewLayout(v, params) }
        refreshVisibility()
        rebuildTabs()
        refreshPanel()
    }

    private fun togglePanel() {
        if (pages.isEmpty()) return
        if (expanded) collapse() else expand()
    }

    private fun panelDrag(root: View) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = panelParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).toInt()
                    params.y = (startY + (e.rawY - downY)).toInt()
                    runCatching { wm?.updateViewLayout(root, params) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    AppCore.prefs.putInt(KEY_PANEL_X, params.x)
                    AppCore.prefs.putInt(KEY_PANEL_Y, params.y)
                    return true
                }
            }
            return false
        }
    }

    private fun resizeHandle(root: View) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = panelParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startW = params.width; startH = params.height
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - downX)).toInt()
                        .coerceIn(dp(180), screenW())
                    params.height = (startH + (e.rawY - downY)).toInt()
                        .coerceIn(dp(180), screenH())
                    runCatching { wm?.updateViewLayout(root, params) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    AppCore.prefs.putInt(KEY_PANEL_W, params.width)
                    AppCore.prefs.putInt(KEY_PANEL_H, params.height)
                    return true
                }
            }
            return false
        }
    }

    /** 面板里的一个小胶囊按钮。 */
    private fun panelButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(p.text)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = rounded(
                blend(p.cardHigh, p.background, 0.92f), 50f, blend(p.outline, p.card, 0.5f)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 面板窗口里的每个 WebView 都铺满。 */
    private fun frameParamsOf(): ViewGroup.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun refreshVisibility() {
        val cur = currentId
        pages.forEach { (id, page) ->
            page.web?.visibility = if (id == cur) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun rebuildTabs() {
        val row = tabRow ?: return
        row.removeAllViews()
        pages.values.forEachIndexed { index, page ->
            val active = page.id == currentId
            val base = (index + 1).toString() + ". " + page.title.ifBlank { page.url }.ifBlank { page.id }
            val label = if (page.sleeping) base + L(" · 已休眠") else base
            val chip = TextView(context).apply {
                text = TextUtils.ellipsize(
                    label.take(48), TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11).toFloat() },
                    dp(120).toFloat(), TextUtils.TruncateAt.END
                )
                textSize = 11f
                setTextColor(
                    when {
                        active -> p.onPrimary
                        page.sleeping -> blend(p.textDim, p.background, 0.55f)
                        else -> p.textDim
                    }
                )
                background = if (active) rounded(p.primary, 50f)
                else rounded(blend(p.cardHigh, p.background, 0.9f), 50f, blend(p.outline, p.card, 0.45f))
                setPadding(dp(11), dp(6), dp(11), dp(6))
                isClickable = true
                setOnClickListener { call { switchTo(page.id) } }
                setOnLongClickListener {
                    call { close(page.id) }
                    true
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
    }

    /**
     * 悬浮窗里点「刷新」：让这一页重新加载。
     *
     * 和 `history(action=reload)` 的区别是**不看暂停状态** —— 暂停管的是 AI 的动作，
     * 用户在悬浮窗里自己点刷新属于手动接管，不该被自己设的暂停拦住。
     */
    private fun reloadPage(id: String?) {
        val page = (if (id != null) pages[id] else null) ?: pages.values.lastOrNull() ?: return
        call {
            page.loading = true
            page.error = null
            page.lastUsed = SystemClock.elapsedRealtime()
            ensureAwake(page)?.reload()
            refreshPanel()
            log(L("浏览器：手动刷新 %s").format(page.url.ifBlank { page.id }))
        }
    }

    private fun refreshPanel() {
        val page = pages[currentId] ?: pages.values.lastOrNull()
        titleView?.text = if (page == null) L("没有页面") else page.title.ifBlank { page.url }.ifBlank { page.id }
        hintView?.text = when {
            page == null -> ""
            page.error != null -> page.error
            page.loading -> L("加载中…")
            else -> page.url
        }
        pauseBtn?.apply {
            text = if (pausedFlag) L("继续 AI") else L("暂停 AI")
            setTextColor(if (pausedFlag) p.onPrimary else p.text)
            background = if (pausedFlag) rounded(p.primary, 50f)
            else rounded(blend(p.cardHigh, p.background, 0.92f), 50f, blend(p.outline, p.card, 0.5f))
        }
    }

    // ------------------------------------------------------------------ 风格件

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private fun screenW(): Int = context.resources.displayMetrics.widthPixels
    private fun screenH(): Int = context.resources.displayMetrics.heightPixels

    private fun blend(color: Int, bg: Int, alpha: Float): Int {
        val r = (Color.red(color) * alpha + Color.red(bg) * (1 - alpha)).toInt()
        val g = (Color.green(color) * alpha + Color.green(bg) * (1 - alpha)).toInt()
        val b = (Color.blue(color) * alpha + Color.blue(bg) * (1 - alpha)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun rounded(fill: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    /** 带一道细描边的圆角底（悬浮窗浮在任意背景上，有描边才看得出边界）。 */
    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        rounded(fill, radius).apply { setStroke(dpF(1f).coerceAtLeast(1f).toInt(), stroke) }

    /**
     * WebView 的宿主布局。
     *
     * 面板折叠时窗口只有 1×1 像素，但**子 View（WebView）仍然按整屏尺寸测量** ——
     * 这样页面在后台也是正常宽度（懒加载、媒体查询、元素坐标都不会错），
     * 而且 `View.draw()` 能整屏画出来。
     */
    private class PageHost(context: Context) : FrameLayout(context) {
        var fallbackWidth = 0
        var fallbackHeight = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val cw = if (measuredWidth >= 200) measuredWidth else fallbackWidth
            val ch = if (measuredHeight >= 200) measuredHeight else fallbackHeight
            if (cw <= 0 || ch <= 0 || childCount == 0) return
            val ws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
            val hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(ws, hs)
            }
        }
    }
}

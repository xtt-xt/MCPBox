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
        const val KEY_BALL_X = "browser_ball_x"
        const val KEY_BALL_Y = "browser_ball_y"
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

    private class Page(val id: String, val web: WebView) {
        var title: String = ""
        var url: String = ""
        var loading: Boolean = true
        var error: String? = null
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false

        /** 这一页自己的身份（mobile / desktop / 自定义串）；null = 跟随全局设置。 */
        var uaOverride: String? = null

        fun info(): BrowserPageInfo = BrowserPageInfo(
            id = id, url = url, title = title, loading = loading,
            canGoBack = canGoBack, canGoForward = canGoForward, error = error,
            ua = uaOverride
        )
    }

    private val pages = LinkedHashMap<String, Page>()
    private var seq = 0

    @Volatile private var currentId: String? = null
    @Volatile private var statusText: String = ""
    @Volatile private var pausedFlag: Boolean = false

    private var ball: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
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
        val page = Page(id, web)
        // 身份要在 loadUrl 之前定下来 —— 否则第一个请求已经用旧 UA 出去了
        page.uaOverride = ua
        applyUa(page)
        pages[id] = page
        web.loadUrl(url)
        pageHost?.addView(web, frameParamsOf())
        currentId = id
        refreshVisibility()
        rebuildTabs()
        ensureBall()
        ensurePanel()
        refreshPanel()
        log(L("浏览器：新开页面 %s").format(url))
        id
    }

    override fun close(id: String) = call {
        val page = pages.remove(id) ?: return@call
        pageHost?.removeView(page.web)
        destroy(page)
        if (currentId == id) currentId = pages.keys.lastOrNull()
        afterPagesChanged()
    }

    override fun closeAll() = call {
        pages.values.toList().forEach { page ->
            pageHost?.removeView(page.web)
            destroy(page)
        }
        pages.clear()
        currentId = null
        pausedFlag = false
        afterPagesChanged()
    }

    private fun destroy(page: Page) {
        runCatching {
            page.web.stopLoading()
            page.web.loadUrl("about:blank")
            page.web.removeAllViews()
            page.web.destroy()
        }
    }

    private fun afterPagesChanged() {
        refreshVisibility()
        rebuildTabs()
        refreshPanel()
        if (pages.isEmpty()) {
            collapse(keepBall = false)
            removeBall()
        }
    }

    override fun navigate(id: String, url: String, ua: String?) {
        call {
            val page = pages[id]
            if (page != null) {
                if (ua != null) {
                    page.uaOverride = ua
                    applyUa(page)
                }
                page.loading = true
                page.error = null
                page.web.loadUrl(url)
            }
        }
    }

    override fun history(id: String, action: String) = call {
        val web = pages[id]?.web ?: return@call
        when (action) {
            "back" -> if (web.canGoBack()) web.goBack()
            "forward" -> if (web.canGoForward()) web.goForward()
            else -> web.reload()
        }
    }

    override fun pages(): List<BrowserPageInfo> = call { pages.values.map { it.info() } }

    override fun currentId(): String? = currentId

    override fun switchTo(id: String) = call {
        if (!pages.containsKey(id)) return@call
        currentId = id
        refreshVisibility()
        rebuildTabs()
        refreshPanel()
    }

    override fun eval(id: String, js: String): String? {
        // 结果回调在主线程 → 这里只能在**调用线程**上等，不能占着主线程
        var value: String? = null
        val latch = CountDownLatch(1)
        main.post {
            val web = pages[id]?.web
            if (web == null) {
                latch.countDown()
                return@post
            }
            runCatching {
                web.evaluateJavascript(js) { result ->
                    value = result
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        return if (latch.await(25, TimeUnit.SECONDS)) value else null
    }

    override fun screenshot(id: String): ByteArray? = call {
        val web = pages[id]?.web ?: return@call null
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

    // ============================================================== 状态 / 暂停

    override fun setStatus(text: String) {
        statusText = text
        post {
            ball?.status = text
            ball?.invalidate()
            hintView?.text = text
        }
    }

    override fun paused(): Boolean = pausedFlag

    override fun setPaused(value: Boolean) {
        pausedFlag = value
        post {
            ball?.paused = value
            ball?.invalidate()
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
            pages.values.forEach { it.web.clearCache(true) }
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearFormData()
        }
        Unit
    }

    override fun userAgent(id: String?): String = call {
        val page = (if (id != null) pages[id] else null) ?: pages.values.firstOrNull()
        page?.web?.settings?.userAgentString.orEmpty()
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
                pages.values.toList().forEach { pg ->
                    pageHost?.removeView(pg.web)
                    destroy(pg)
                }
                pages.clear()
                currentId = null
                pausedFlag = false
                statusText = ""
                removeBall()
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
                    val childPage = Page(id, child)
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
                    ensureBall()
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
        runCatching { page.web.settings.userAgentString = resolveUa(spec) ?: defaultUa }
    }

    private fun pageOf(web: WebView): Page? = pages.values.firstOrNull { it.web === web }

    // ================================================================== 悬浮球

    private fun ensureBall() {
        if (ball != null) return
        val wmv = wm ?: return
        val view = BallView(context)
        val params = WindowManager.LayoutParams(
            dp(112), dp(80),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = AppCore.prefs.getInt(KEY_BALL_X, -1)
            val sy = AppCore.prefs.getInt(KEY_BALL_Y, -1)
            x = if (sx < 0) screenW() - dp(112) - dp(4) else sx.coerceIn(0, max(0, screenW() - dp(112)))
            y = if (sy < 0) (screenH() * 0.28f).toInt() else sy.coerceIn(0, max(0, screenH() - dp(80)))
        }
        view.status = statusText
        view.paused = pausedFlag
        view.setOnTouchListener(ballTouch(view, params))
        runCatching { wmv.addView(view, params) }.onFailure {
            log(L("悬浮球创建失败：%s").format(it.message), ok = false)
        }
        ball = view
        ballParams = params
    }

    private fun removeBall() {
        val v = ball ?: return
        runCatching { wm?.removeView(v) }
        ball = null
        ballParams = null
    }

    /** 拖动 = 挪位置；轻点 = 展开 / 收起面板；松手贴边半收。 */
    private fun ballTouch(view: View, params: WindowManager.LayoutParams) =
        object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            runCatching { wm?.updateViewLayout(view, params) }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            togglePanel()
                        } else {
                            // 贴边吸一下，但**整个球都留在屏幕里**（不做半收，免得看着像被裁掉）
                            val w = dp(112)
                            val h = dp(80)
                            val half = dp(26)
                            params.x = if (params.x + half < screenW() / 2) dp(4) else screenW() - w - dp(4)
                            params.y = params.y.coerceIn(dp(4), max(dp(4), screenH() - h - dp(4)))
                            runCatching { wm?.updateViewLayout(view, params) }
                            AppCore.prefs.putInt(KEY_BALL_X, params.x)
                            AppCore.prefs.putInt(KEY_BALL_Y, params.y)
                        }
                        return true
                    }
                }
                return false
            }
        }

    /** 悬浮球：一个圆 + 上面一条状态胶囊。 */
    private inner class BallView(ctx: Context) : View(ctx) {
        var status: String = ""
        var paused: Boolean = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(10).toFloat()
            color = p.text
        }

        override fun onDraw(canvas: Canvas) {
            val d = dp(50)
            val cx = width / 2f
            val cy = height - d / 2f
            val r = d / 2f
            val chipTop = cy - r - dp(23)

            // 状态胶囊（球上方）：不透明一点，保证在任意壁纸 / 应用上都读得清
            if (status.isNotBlank()) {
                val label = TextUtils.ellipsize(status, text, dp(100).toFloat(), TextUtils.TruncateAt.END)
                val tw = text.measureText(label.toString())
                val pad = dp(8).toFloat()
                val rect = RectF(
                    cx - tw / 2 - pad,
                    chipTop,
                    cx + tw / 2 + pad,
                    cy - r - dp(5)
                )
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(60, 0, 0, 0)
                canvas.drawRoundRect(
                    RectF(rect.left, rect.top + dpF(1.5f), rect.right, rect.bottom + dpF(1.5f)),
                    dp(10).toFloat(), dp(10).toFloat(), paint
                )
                paint.color = blend(p.card, p.background, 0.97f)
                canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpF(1f).coerceAtLeast(1f)
                paint.color = if (paused) blend(p.outline, p.card, 0.7f) else blend(p.primary, p.card, 0.45f)
                canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)
                paint.style = Paint.Style.FILL
                canvas.drawText(label.toString(), cx - tw / 2, rect.bottom - dp(6), text)
            }

            // 球体投影（两层，代替 elevation —— 悬浮窗里的 View 没有系统阴影）
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb(30, 0, 0, 0)
            canvas.drawCircle(cx, cy + dpF(3f), r + dpF(1f), paint)
            paint.color = Color.argb(18, 0, 0, 0)
            canvas.drawCircle(cx, cy + dpF(6f), r + dpF(2f), paint)

            // 球体：主色渐变（上浅下深），暂停时换成中性灰
            val top = if (paused) blend(p.cardHigh, p.text, 0.08f) else blend(p.primary, p.background, 0.94f)
            val bottom = if (paused) blend(p.card, p.background, 0.85f) else blend(p.primary, p.card, 0.70f)
            paint.shader = android.graphics.LinearGradient(
                0f, cy - r, 0f, cy + r, top, bottom, android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null

            // 描边：一道细细的主色环，边界更干净
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(1.2f).coerceAtLeast(1f)
            paint.color = if (paused) blend(p.outline, p.card, 0.6f) else blend(p.primary, p.background, 0.40f)
            canvas.drawCircle(cx, cy, r - dpF(0.6f), paint)

            // 地球（一根纬线 + 一根经线椭圆）
            val gr = r * 0.52f
            paint.color = if (paused) p.textDim else p.onPrimary
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(1.9f)
            canvas.drawCircle(cx, cy, gr, paint)
            canvas.drawOval(RectF(cx - gr * 0.50f, cy - gr, cx + gr * 0.50f, cy + gr), paint)
            canvas.drawLine(cx - gr, cy, cx + gr, cy, paint)

            // AI 正在操作时，右上角点一颗小灯
            if (!paused && status.isNotBlank()) {
                paint.style = Paint.Style.FILL
                paint.color = p.onPrimary
                canvas.drawCircle(cx + gr + dpF(1f), cy - gr - dpF(1f), dpF(2.6f), paint)
            }
        }
    }

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
        val collapse = panelButton(L("收起")) { collapse(keepBall = true) }
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
    private fun collapse(keepBall: Boolean) {
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
        if (!keepBall && pages.isEmpty()) removeBall()
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
        if (pages.isEmpty()) {
            removeBall()
            return
        }
        if (expanded) collapse(keepBall = true) else expand()
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
            page.web.visibility = if (id == cur) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun rebuildTabs() {
        val row = tabRow ?: return
        row.removeAllViews()
        pages.values.forEachIndexed { index, page ->
            val active = page.id == currentId
            val label = (index + 1).toString() + ". " + page.title.ifBlank { page.url }.ifBlank { page.id }
            val chip = TextView(context).apply {
                text = TextUtils.ellipsize(
                    label.take(40), TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11).toFloat() },
                    dp(120).toFloat(), TextUtils.TruncateAt.END
                )
                textSize = 11f
                setTextColor(if (active) p.onPrimary else p.textDim)
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
            page.web.reload()
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

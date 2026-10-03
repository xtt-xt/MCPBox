// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 浏览器工具包（`browser`）：App 内置 WebView 当接口用。
 *
 * 和 `ToolsUi`（截屏 + 点屏幕）的区别：
 *  · UI 自动化操作的是**整台手机**，慢、脆、要 Root / Shizuku；
 *  · 这里操作的是 App 自己那个**无头 WebView**，能读 DOM、能精确点元素、能截网页图、
 *    能开多个页面，不需要任何系统权限（只要悬浮窗权限来显示悬浮球）。
 *
 * 所有动作都过 `PermKey.BROWSER`（默认「询问」）—— 也就是说**每次都弹审批**。
 * 嫌烦可以在权限页把「浏览器控制」改成允许，或审批弹窗上点「始终允许」。
 */
object ToolsBrowser {

    fun specs(): List<ToolSpec> = listOf(
        open(),
        navigate(),
        history(),
        pages(),
        switchTo(),
        close(),
        content(),
        click(),
        input(),
        scroll(),
        waitFor(),
        eval(),
        screenshot(),
        search(),
        save(),
        storage(),
        engines(),
        download()
    )

    // ------------------------------------------------------------------ 基础

    private fun hub(ctx: CallContext): BrowserHub =
        ctx.browser ?: ctx.fail(L("浏览器没有接到服务器上（这台设备不支持内置 WebView）"))

    /**
     * 统一的开工检查：引擎在不在 → 用户有没有暂停 → 过审批。
     * 顺序很重要：引擎没起来的时候不该弹审批（用户批了也没用）。
     */
    private fun start(ctx: CallContext, summary: String, url: String? = null, detail: String? = null): BrowserHub {
        val hub = hub(ctx)
        hub.engine()
        hub.checkPaused()
        ctx.guardTarget(PermKey.BROWSER, url, summary, detail)
        hub.status(L("AI：%s").format(summary))
        return hub
    }

    private fun done(hub: BrowserHub) {
        hub.status("")
    }

    private fun modeOf(ctx: CallContext, def: String, vararg allowed: String): String {
        val v = ctx.str("mode")?.trim()?.lowercase().orEmpty().ifEmpty { def }
        if (v !in allowed) {
            ctx.fail(L("不认识的 mode：%s（可用：%s）").format(v, allowed.joinToString(" / ")))
        }
        return v
    }

    // -------------------------------------------------------------- browser_open

    private fun open() = ToolSpec(
        name = "browser_open",
        title = "打开网页",
        description = "在 App 的内置浏览器里新开一个页面并加载网址。打开后手机上会出现悬浮球，" +
            "用户可以点开悬浮窗看页面、自己接管操作。网址要写完整（example.com 会自动补 https://）。" +
            "想搜东西用 browser_search，别把搜索词当网址填。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "url" to Schema.str("网址，例如 https://example.com"),
                "wait_ms" to Schema.int("加载完成后最多再等多久（毫秒），默认 800", 800, 0, 30_000)
            ),
            listOf("url")
        )
    ) { ctx ->
        val hub = start(ctx, L("打开网页 %s").format(ctx.args.str("url").orEmpty().take(120)), ctx.args.str("url"))
        try {
            val page = hub.open(ctx.args.str("url").orEmpty())
            val wait = ctx.args.intOr("wait_ms", 800).coerceIn(0, 30_000)
            if (wait > 0) Thread.sleep(wait.toLong())
            val meta = readMeta(hub, page.id)
            ToolResult(
                L("已打开：[%s] %s\n").format(page.id, page.label()) +
                    (meta ?: "") +
                    "\n" + L("提示：用 browser_content 读内容、browser_click 点元素。用户可以在悬浮窗里手动接管。")
            )
        } finally {
            done(hub)
        }
    }

    // ---------------------------------------------------------- browser_navigate

    private fun navigate() = ToolSpec(
        name = "browser_navigate",
        title = "跳转网址",
        description = "让**已有的**页面跳到一个新网址（不新开页面）。一个页面里连续走几个链接时用它，" +
            "比反复 browser_open 省内存。当前没有页面时会自动新开一个。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "url" to Schema.str("网址"),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "wait_ms" to Schema.int("加载后最多再等多久（毫秒）", 800, 0, 30_000)
            ),
            listOf("url")
        )
    ) { ctx ->
        val hub = start(ctx, L("跳转到 %s").format(ctx.args.str("url").orEmpty().take(120)), ctx.args.str("url"))
        try {
            val pageArg = ctx.args.str("page")
            val url = ctx.args.str("url").orEmpty()
            val page: BrowserPageInfo = if (pageArg.isNullOrBlank()) {
                hub.navigateOrOpen(url).first
            } else {
                val target = hub.resolvePage(pageArg)
                hub.engine().navigate(target.id, hub.normalizeUrl(url))
                target
            }
            val wait = ctx.args.intOr("wait_ms", 800).coerceIn(0, 30_000)
            if (wait > 0) Thread.sleep(wait.toLong())
            ToolResult(
                L("已跳转：%s\n").format(hub.pageLine(hub.resolvePage(page.id))) +
                    (readMeta(hub, page.id) ?: "")
            )
        } finally {
            done(hub)
        }
    }

    // ----------------------------------------------------------- browser_history

    private fun history() = ToolSpec(
        name = "browser_history",
        title = "前进 / 后退 / 刷新",
        description = "页面的历史栈操作：action=back / forward / reload。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "action" to Schema.str("要做什么", "back", listOf("back", "forward", "reload")),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面")
            ),
            listOf("action")
        )
    ) { ctx ->
        val action = ctx.str("action")?.lowercase().orEmpty()
        val hub0 = hub(ctx)
        val label = when (action) {
            "back" -> L("后退")
            "forward" -> L("前进")
            "reload" -> L("刷新")
            else -> ctx.fail(L("不认识的 action：%s（可用 back / forward / reload）").format(action))
        }
        val page = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(ctx, L("页面%s").format(label), page?.url, page?.let { hub0.pageLine(it) })
        try {
            val p = if (page == null) hub.resolvePage(null) else hub.resolvePage(page.id)
            hub.engine().history(p.id, action)
            Thread.sleep(600)
            ToolResult(
                L("已%s：%s\n").format(label, hub.pageLine(hub.resolvePage(p.id))) +
                    (readMeta(hub, p.id) ?: "")
            )
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------- browser_pages

    private fun pages() = ToolSpec(
        name = "browser_pages",
        title = "页面列表",
        description = "列出浏览器里所有打开的页面（序号、id、标题、域名、是否加载中），" +
            "并标出当前页面（悬浮窗正在显示的那个）。切换用 browser_switch。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        val hub = start(ctx, L("查看页面列表"))
        try {
            val b = hub.engine()
            val list = b.pages()
            if (list.isEmpty()) {
                return@ToolSpec ToolResult(L("现在没有打开的页面（悬浮球不显示）。用 browser_open 打开一个。"))
            }
            val cur = hub.current()
            ToolResult(
                L("共 %s 个页面（带 * 的是当前页面）：\n").format(list.size) +
                    hub.formatPages(list, b.currentId()) +
                    "\n\n" + L("用户手动暂停了 AI 操作") + "：" + (if (b.paused()) L("是") else L("否"))
            )
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------ browser_switch

    private fun switchTo() = ToolSpec(
        name = "browser_switch",
        title = "切换当前页面",
        description = "把某个页面切成「当前页面」：之后省略 page 参数的动作都作用于它，" +
            "手机上的悬浮窗也会显示它。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf("page" to Schema.str("页面 id 或序号（见 browser_pages）")),
            listOf("page")
        )
    ) { ctx ->
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page"), defaultToCurrent = false) }.getOrNull()
        val hub = start(ctx, L("切换页面到 %s").format(target?.label()?.take(40) ?: ""), target?.url)
        try {
            val p = if (target == null) hub.resolvePage(ctx.args.str("page"), false) else target
            hub.engine().switchTo(p.id)
            ToolResult(L("已切换到：%s").format(hub.pageLine(hub.resolvePage(p.id))))
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------- browser_close

    private fun close() = ToolSpec(
        name = "browser_close",
        title = "关闭页面",
        description = "关掉一个页面；page 写 all 就全部关掉（页面全关后悬浮球会消失）。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf("page" to Schema.str("页面 id / 序号 / all；省略 = 全部关闭"))
        )
    ) { ctx ->
        val arg = ctx.args.str("page")
        val hub0 = hub(ctx)
        val all = arg.isNullOrBlank() || arg.equals("all", true)
        val target = if (all) null else runCatching { hub0.resolvePage(arg, defaultToCurrent = false) }.getOrNull()
        val hub = start(
            ctx,
            if (all) L("关闭全部页面") else L("关闭页面 %s").format(target?.label()?.take(40) ?: arg.orEmpty()),
            target?.url
        )
        try {
            val toClose = if (all || target == null) null else hub.resolvePage(target.id, false).id
            ToolResult(hub.close(toClose))
        } finally {
            done(hub)
        }
    }

    // ----------------------------------------------------------- browser_content

    private fun content() = ToolSpec(
        name = "browser_content",
        title = "读网页内容",
        description = "读当前（或指定）页面的内容。mode：" +
            "text=正文纯文本（推荐先用它）；markdown=正文转 Markdown（标题/列表/代码/链接）；" +
            "elements=可交互元素列表（带序号、文字、类型、坐标 —— 看这个再决定点什么，省 token）；" +
            "links=页面所有链接；html=原始 HTML（很大，慎用）；meta=标题/地址/加载状态/滚动位置。" +
            "默认会**先自动滚到底**（把「滚了才加载」的长列表 / 图片喂出来，auto_scroll=false 关掉）。" +
            "长内容用 offset / maxChars 分段读。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "mode" to Schema.str("读什么", "text", listOf("text", "markdown", "elements", "links", "html", "meta")),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "filter" to Schema.str("keywords：只看文字/链接里含这个词的（elements / links 用）"),
                "onlyInteractive" to Schema.bool("elements 模式只列可点的（默认 true）", true),
                "maxItems" to Schema.int("elements / links 最多列几条", 80, 1, 500),
                "maxChars" to Schema.int("文本最多返回多少字符", 6000, 200, 200_000),
                "offset" to Schema.int("从第几个字符开始（分段读长文），默认 0", 0, 0, 5_000_000),
                "auto_scroll" to Schema.bool("读之前先自动滚到底（把「滚了才加载」的内容喂出来），默认 true", true),
                "scroll_rounds" to Schema.int("自动最多往下滚几屏", 6, 1, 20)
            )
        )
    ) { ctx ->
        val mode = modeOf(ctx, "text", "text", "markdown", "elements", "links", "html", "meta")
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(
            ctx,
            L("读取网页内容（%s）").format(mode),
            target?.url,
            target?.let { hub0.pageLine(it) }
        )
        try {
            val b = hub.engine()
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            if (mode != "meta" && ctx.args.boolOr("auto_scroll", true)) {
                autoScroll(hub, page.id, ctx.args.intOr("scroll_rounds", 6))
            }
            val maxChars = ctx.args.intOr("maxChars", 6000).coerceIn(200, 200_000)
            val offset = ctx.args.intOr("offset", 0).coerceAtLeast(0)
            val maxItems = ctx.args.intOr("maxItems", 80).coerceIn(1, 500)
            val filter = ctx.args.str("filter")

            when (mode) {
                "meta" -> {
                    val meta = readMeta(hub, page.id) ?: L("（拿不到页面信息，可能还没加载完）")
                    ToolResult(hub.pageLine(page) + "\n" + meta)
                }

                "elements" -> {
                    val only = ctx.args.boolOr("onlyInteractive", true)
                    val js = BrowserJs.elements(only, filter, maxItems)
                    val json = hub.evalJson(page.id, js)
                    if (json == null) ctx.fail(L("读不到页面元素：页面可能还没加载完（用 browser_wait 等一等）。"))
                    val items = asObject(ctx, json)["items"]?.jsonArray ?: ctx.fail(L("页面元素解析失败"))
                    if (items.isEmpty()) {
                        return@ToolSpec ToolResult(
                            L("页面上没有匹配的可交互元素。") +
                                (filter?.let { L("\n（filter=%s；去掉它再试一次）").format(it) } ?: "") +
                                "\n" + L("可以先 browser_content(mode=text) 看看页面有没有内容。")
                        )
                    }
                    val lines = items.mapIndexed { i, el ->
                        val o = el.jsonObject
                        buildString {
                            append('[').append(i).append("] ")
                            append(o.str("tag").orEmpty())
                            o.str("type")?.takeIf { it.isNotBlank() }?.let { append('/').append(it) }
                            val text = o.str("text").orEmpty()
                            if (text.isNotBlank()) append(" \"").append(text.replace('\n', ' ').take(60)).append('"')
                            o.str("href")?.takeIf { it.isNotBlank() && it.length < 100 }?.let { append(" -> ").append(it) }
                            o.str("name")?.takeIf { it.isNotBlank() }?.let { append(" name=").append(it) }
                            o.str("placeholder")?.takeIf { it.isNotBlank() }?.let { append(" 提示=").append(it) }
                            o.str("value")?.takeIf { it.isNotBlank() }?.let { append(" 值=").append(it.take(20)) }
                            append(" (").append(o.str("x")).append(',').append(o.str("y")).append(')')
                            if (o.boolOr("checked", false)) append(L(" 已勾选"))
                            if (o.boolOr("disabled", false)) append(L(" 已禁用"))
                        }
                    }.joinToString("\n")
                    ToolResult(
                        L("可交互元素 %s 个（用 browser_click(by=\"index\", value=\"序号\") 点）：\n").format(items.size) +
                            lines +
                            "\n\n" + hub.pageLine(page)
                    )
                }

                "links" -> {
                    val js = BrowserJs.links(filter, maxItems)
                    val json = hub.evalJson(page.id, js)
                    val arr = (json as? JsonObject)?.get("links")?.jsonArray ?: ctx.fail(L("读不到页面链接"))
                    if (arr.isEmpty()) return@ToolSpec ToolResult(L("页面里没有链接。"))
                    val lines = arr.mapIndexed { i, el ->
                        val o = el.jsonObject
                        "%d. %s\n   %s".format(
                            i + 1,
                            o.str("text").orEmpty().ifBlank { L("（无文字）") }.take(80),
                            o.str("href").orEmpty().take(200)
                        )
                    }.joinToString("\n")
                    ToolResult(L("链接 %s 条：\n").format(arr.size) + lines)
                }

                "html" -> {
                    val html = hub.evalText(page.id, BrowserJs.HTML)
                    if (html.isBlank()) ctx.fail(L("页面还没有内容。"))
                    val cut = slice(html, offset, maxChars)
                    ToolResult(
                        L("HTML 共 %s 字符").format(html.length) + cut.note + "：\n----\n" + cut.text
                    )
                }

                "markdown" -> {
                    val md = hub.evalText(page.id, BrowserJs.MARKDOWN)
                    if (md.isBlank()) ctx.fail(L("页面正文是空的（可能是还没加载完，或者内容全靠脚本二次加载）。"))
                    val cut = slice(md, offset, maxChars)
                    ToolResult(
                        hub.pageLine(page) + L("\n正文（Markdown，共 %s 字符）").format(md.length) +
                            cut.note + "：\n----\n" + cut.text
                    )
                }

                else -> {
                    val text = hub.evalText(page.id, BrowserJs.TEXT)
                    if (text.isBlank()) ctx.fail(
                        L("页面正文是空的。可能还在加载（用 browser_wait 等元素出现），" +
                            "或者内容要靠滚动 / 点击才出来。")
                    )
                    val cut = slice(text, offset, maxChars)
                    ToolResult(
                        hub.pageLine(page) + L("\n正文（共 %s 字符）").format(text.length) +
                            cut.note + "：\n----\n" + cut.text
                    )
                }
            }
        } finally {
            done(hub)
        }
    }

    /** evalJson 的结果不一定是个对象（页面抽风 / 还没加载完），统一在这里给出人话错误。 */
    private fun asObject(ctx: CallContext, el: JsonElement?): JsonObject =
        (el as? JsonObject) ?: ctx.fail(L("页面返回的数据看不懂（多半是还没加载完，用 browser_wait 等一等）。"))

    /**
     * 自动滚到底：把「滚了才加载」的内容（长列表、图片、评论区）喂出来。
     *
     * 一屏一屏往下走，连续两轮位置没变就认为到底了；**滚完就停在底部**，
     * 不回顶部 —— 有些站点（比如必应的搜索结果）是客户端流式渲染的，
     * 滚走之后会把已经渲染好的内容撤掉，回顶反而读不到东西。
     */
    private fun autoScroll(hub: BrowserHub, pageId: String, rounds: Int) {
        val max = rounds.coerceIn(1, 20)
        var last = -1.0
        var stuck = 0
        var i = 0
        while (i < max) {
            i++
            val json = hub.evalJson(pageId, BrowserJs.scroll("page", "1"))
            val o = json as? JsonObject ?: break
            if (!o.boolOr("ok", false)) break
            val pos = o.doubleOrNull("pos") ?: 0.0
            Thread.sleep(260)
            if (pos <= last + 1.0) {
                stuck++
                if (stuck >= 2) break
            } else {
                stuck = 0
            }
            last = pos
        }
    }

    /** 读一次页面链接（还原跳转壳 + 算好「站外」的那批）。 */
    private fun readLinks(hub: BrowserHub, pageId: String, engineHost: String, maxLinks: Int):
        Pair<List<Triple<String, String, String>>, List<Triple<String, String, String>>> {
        val json = hub.evalJson(pageId, BrowserJs.links(null, maxLinks))
        val all = (json as? JsonObject)?.get("links")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val parsed = all.mapNotNull { el ->
            val o = el.jsonObject
            val raw = o.str("href").orEmpty()
            if (raw.isBlank() || !raw.startsWith("http")) return@mapNotNull null
            val real = BrowserRedirects.unwrap(raw)
            val host = runCatching { java.net.URI(real).host.orEmpty() }.getOrDefault("")
            Triple(o.str("text").orEmpty(), real, host)
        }
        return parsed to parsed.filterNot { sameSiteHost(it.third, engineHost) }
    }

    /**
     * 等结果链接出现。
     *
     * 搜索结果页是**流式渲染**的：打开一两秒后 DOM 里常常只有顶栏（首页 / 图片 / 视频…），
     * 真正的结果要再等一会儿。只读一次的话，AI 会拿到一堆导航链接还以为搜到了。
     */
    private fun waitForLinks(
        hub: BrowserHub,
        pageId: String,
        engineHost: String,
        maxLinks: Int,
        want: Int,
        timeoutMs: Long
    ): Pair<List<Triple<String, String, String>>, List<Triple<String, String, String>>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = readLinks(hub, pageId, engineHost, maxLinks)
        var stale = 0
        while (result.second.size < want && System.currentTimeMillis() < deadline && stale < 4) {
            Thread.sleep(500)
            val next = readLinks(hub, pageId, engineHost, maxLinks)
            if (next.second.size > result.second.size) {
                // 只在「变多了」的时候替换，免得某次抽风读少了把好的覆盖掉
                result = next
                stale = 0
            } else if (result.second.isNotEmpty()) {
                // 已经有一些站外链接、而且连着两秒没再变多 → 大概就这么多了，别白等
                stale++
            }
        }
        return result
    }

    private class Sliced(val text: String, val note: String)

    private fun slice(text: String, offset: Int, maxChars: Int): Sliced {
        val start = offset.coerceIn(0, text.length)
        val end = (start + maxChars).coerceAtMost(text.length)
        val note = when {
            start == 0 && end >= text.length -> ""
            else -> L("（%s-%s 字符）").format(start, end) +
                if (end < text.length) L("，后面还有 %s 字符，用 offset=%s 继续读").format(text.length - end, end)
                else ""
        }
        return Sliced(text.substring(start, end), note)
    }

    // ------------------------------------------------------------- browser_click

    private fun click() = ToolSpec(
        name = "browser_click",
        title = "点击网页元素",
        description = "点页面上的东西。by=index：点 browser_content(mode=elements) 里的第 N 项（推荐，最稳）；" +
            "by=text：点文字里含这个词的第一个元素；by=selector：CSS 选择器；by=point：页面坐标 \"x,y\"。" +
            "点击后页面可能要加载，接着用 browser_wait 或再读一次内容。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "by" to Schema.str("定位方式", "index", listOf("index", "text", "selector", "point")),
                "value" to Schema.str("index=序号；text=文字；selector=CSS；point=x,y"),
                "index" to Schema.int("by=text 时，匹配到多个用第几个（从 0 开始）", 0, 0, 200),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "wait_ms" to Schema.int("点完最多等多久再读状态（毫秒）", 900, 0, 30_000)
            ),
            listOf("by", "value")
        )
    ) { ctx ->
        val by = ctx.str("by")?.lowercase().orEmpty().ifEmpty { "index" }
        val value = ctx.args.str("value").orEmpty()
        if (value.isBlank()) ctx.fail(L("要给 value（index 序号 / 文字 / 选择器 / 坐标）"))
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(
            ctx,
            L("点击网页元素（%s=%s）").format(by, value.take(60)),
            target?.url,
            target?.let { hub0.pageLine(it) }
        )
        try {
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            val before = readMeta(hub, page.id)
            val js = BrowserJs.click(by, value, ctx.args.intOr("index", 0))
            val json = hub.evalJson(page.id, js) ?: ctx.fail(L("点击失败：页面没有响应（可能还没加载完）"))
            val o = asObject(ctx, json)
            if (!o.boolOr("ok", false)) {
                ctx.fail(
                    L("没找到要点的元素（%s=%s）。").format(by, value.take(60)) +
                        L("\n先 browser_content(mode=elements) 看一遍页面上的元素，再按序号点最稳。")
                )
            }
            val wait = ctx.args.intOr("wait_ms", 900).coerceIn(0, 30_000)
            if (wait > 0) Thread.sleep(wait.toLong())
            val after = readMeta(hub, page.id)
            val changed = before != null && after != null && before != after
            ToolResult(
                L("已点击：%s\n").format(o["target"]?.toString().orEmpty().take(200)) +
                    L("页面状态%s\n").format(if (changed) L("（有变化）") else L("（看不出变化，可能需要再等一下）")) +
                    (after ?: "")
            )
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------- browser_input

    private fun input() = ToolSpec(
        name = "browser_input",
        title = "填写网页表单",
        description = "往输入框 / 文本域 / 下拉框 / 勾选框里填值（会自动派发 input + change 事件，" +
            "React / Vue 那种受控组件也认）。submit=true 会顺手提交（点它的提交按钮或按回车）。" +
            "by=index 指 browser_content(mode=elements) 里的序号。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "by" to Schema.str("定位方式", "index", listOf("index", "selector")),
                "value" to Schema.str("index=序号；selector=CSS 选择器"),
                "text" to Schema.str("要填的值。下拉框可以填选项文字或 value；勾选框填 true/false"),
                "index" to Schema.int("by=text 时用第几个匹配", 0, 0, 200),
                "clear_first" to Schema.bool("先清空原来的内容（默认 true；false = 追加）", true),
                "submit" to Schema.bool("填完顺手提交", false),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "wait_ms" to Schema.int("提交后等多久（毫秒）", 1200, 0, 30_000)
            ),
            listOf("by", "value", "text")
        )
    ) { ctx ->
        val by = ctx.str("by")?.lowercase().orEmpty().ifEmpty { "index" }
        val value = ctx.args.str("value").orEmpty()
        val text = ctx.args.str("text").orEmpty()
        if (value.isBlank()) ctx.fail(L("要给 value（输入框的序号或选择器）"))
        val submit = ctx.args.boolOr("submit", false)
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(
            ctx,
            L("填写表单（%s=%s）%s").format(by, value.take(40), if (submit) L("并提交") else ""),
            target?.url,
            target?.let { hub0.pageLine(it) }
        )
        try {
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            val js = BrowserJs.input(
                by, value, text,
                ctx.args.intOr("index", 0),
                ctx.args.boolOr("clear_first", true),
                submit
            )
            val json = hub.evalJson(page.id, js) ?: ctx.fail(L("填表失败：页面没有响应"))
            val o = asObject(ctx, json)
            if (!o.boolOr("ok", false)) {
                val err = o.str("err").orEmpty()
                if (err == "no-such-option") {
                    val opts = o["options"]?.jsonArray?.joinToString("、") { it.toString().trim('"') }.orEmpty()
                    ctx.fail(L("下拉框里没有「%s」。可选：%s").format(text, opts))
                }
                ctx.fail(
                    L("没找到要填的元素（%s=%s）。").format(by, value.take(60)) +
                        L("\n先 browser_content(mode=elements) 看一眼输入框的序号。")
                )
            }
            val wait = ctx.args.intOr("wait_ms", 1200).coerceIn(0, 30_000)
            if (submit && wait > 0) Thread.sleep(wait.toLong())
            ToolResult(
                L("已填写：%s\n").format(o["target"]?.toString().orEmpty().take(160)) +
                    L("现在的值：%s\n").format(o.str("value").orEmpty().take(80)) +
                    (if (submit) L("提交方式：%s\n").format(o.str("how").orEmpty()) else "") +
                    (readMeta(hub, page.id) ?: "")
            )
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------ browser_scroll

    private fun scroll() = ToolSpec(
        name = "browser_scroll",
        title = "滚动页面",
        description = "滚动页面好让下面的内容渲染出来（很多网站要滚了才加载）。" +
            "to=bottom 到底 / top 回顶 / y 绝对位置 / page 往下翻 val 屏（默认 1）/ element 滚到某个元素（value 给序号或选择器）。" +
            "滚完建议再 browser_content 读一次。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "to" to Schema.str("滚到哪", "page", listOf("bottom", "top", "y", "page", "element")),
                "value" to Schema.str("y=像素位置；page=翻几屏；element=序号或选择器"),
                "times" to Schema.int("连续滚几次（加载长列表用）", 1, 1, 20),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面")
            ),
            listOf("to")
        )
    ) { ctx ->
        val to = ctx.str("to")?.lowercase().orEmpty().ifEmpty { "page" }
        val value = ctx.args.str("value")
        val times = ctx.args.intOr("times", 1).coerceIn(1, 20)
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(
            ctx,
            L("滚动页面（%s%s）").format(to, value?.let { "=$it" } ?: ""),
            target?.url
        )
        try {
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            var last: String = ""
            repeat(times) {
                val json = hub.evalJson(page.id, BrowserJs.scroll(to, value))
                if (json == null) ctx.fail(L("滚动失败：页面没有响应"))
                val o = asObject(ctx, json)
                if (!o.boolOr("ok", false)) {
                    ctx.fail(
                        L("滚动失败（%s）。").format(o.str("err").orEmpty()) +
                            L("\nto=element 时 value 要给元素序号或 CSS 选择器。")
                    )
                }
                last = o["y"]?.toString().orEmpty()
                Thread.sleep(350)
            }
            val meta = readMeta(hub, page.id)
            ToolResult(
                L("已滚动到 y=%s\n").format(last) +
                    L("（注意：懒加载的图 / 列表需要等一会儿，必要时再 browser_content 读一次）\n") +
                    (meta ?: "")
            )
        } finally {
            done(hub)
        }
    }

    // -------------------------------------------------------------- browser_wait

    private fun waitFor() = ToolSpec(
        name = "browser_wait",
        title = "等页面元素",
        description = "轮询页面，等某个 CSS 选择器 / 某段文字出现（或消失）。点完链接等加载时用它，" +
            "比固定等几秒稳。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "selector" to Schema.str("等这个 CSS 选择器出现"),
                "text" to Schema.str("等含这段文字的元素出现"),
                "disappear" to Schema.bool("反过来：等它消失（比如等加载圈消失）", false),
                "timeoutMs" to Schema.int("最多等多久（毫秒）", 15_000, 500, 120_000),
                "intervalMs" to Schema.int("每次检查间隔（毫秒）", 500, 200, 5_000),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面")
            )
        )
    ) { ctx ->
        val selector = ctx.args.str("selector")
        val text = ctx.args.str("text")
        if (selector.isNullOrBlank() && text.isNullOrBlank()) ctx.fail(L("至少要给 selector 或 text"))
        val disappear = ctx.args.boolOr("disappear", false)
        val timeout = ctx.args.longOr("timeoutMs", 15_000).coerceIn(500, 120_000)
        val interval = ctx.args.longOr("intervalMs", 500).coerceIn(200, 5_000)
        val what = selector ?: text.orEmpty()
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(ctx, L("等待页面元素「%s」").format(what.take(60)), target?.url)
        try {
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            val started = System.currentTimeMillis()
            var rounds = 0
            while (System.currentTimeMillis() - started < timeout) {
                if (hub.engine().paused()) {
                    ctx.fail(L("用户暂停了浏览器，等待中断。"))
                }
                rounds++
                val json = hub.evalJson(page.id, BrowserJs.find(selector, text))
                val present = (json as? JsonObject)?.boolOr("ok", false) == true
                if (present != disappear) {
                    return@ToolSpec ToolResult(
                        L("「%s」已%s（等了 %s 毫秒，检查了 %s 次）\n")
                            .format(what.take(60), if (disappear) L("消失") else L("出现"), System.currentTimeMillis() - started, rounds) +
                            (readMeta(hub, page.id) ?: "")
                    )
                }
                Thread.sleep(interval)
            }
            ctx.fail(
                L("等了 %s 毫秒，「%s」还是%s。\n").format(timeout, what.take(60), if (disappear) L("在") else L("没出现")) +
                    L("可以 browser_content(mode=text) 看看页面到底加载出了什么（也可能被反爬拦了）。")
            )
        } finally {
            done(hub)
        }
    }

    // -------------------------------------------------------------- browser_eval

    private fun eval() = ToolSpec(
        name = "browser_eval",
        title = "执行 JS",
        description = "在这个页面的上下文里执行一段 JavaScript，把最后表达式的值返回给你（JSON 编码）。" +
            "页面里能读到的（document、location、fetch…）都能用，是兜底手段。" +
            "注意：fetch 走的是页面自己的登录态，拿到的数据可能包含用户的隐私内容。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "js" to Schema.str("要执行的 JavaScript，最后写一个表达式作为返回值"),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "maxChars" to Schema.int("返回值最多展示多少字符", 4000, 100, 100_000)
            ),
            listOf("js")
        )
    ) { ctx ->
        val code = ctx.args.str("js").orEmpty()
        if (code.isBlank()) ctx.fail(L("js 不能为空"))
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(
            ctx,
            L("执行 JS（%s）").format(code.replace('\n', ' ').take(60)),
            target?.url
        )
        try {
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            val max = ctx.args.intOr("maxChars", 4000).coerceIn(100, 100_000)
            val json = hub.evalJson(page.id, BrowserJs.user(code))
            val o = json as? JsonObject
            if (o == null || !o.boolOr("ok", false)) {
                val err = o?.str("err").orEmpty()
                val csp = err.contains("Content Security Policy", true) ||
                    err.contains("unsafe-eval", true) || err.contains("EvalError", true)
                if (!csp) ctx.fail(L("JS 报错：%s").format(err))
                // 有些站点（必应）的 CSP 禁 eval：换一种注入方式再来一次
                val raw = hub.engine().eval(page.id, BrowserJs.userDirect(code))
                val text = hub.unquoteJs(raw ?: "")
                if (text.isBlank()) ctx.fail(L("JS 执行失败：页面没有返回结果"))
                ToolResult(
                    L("返回值：\n") +
                        (if (text.length > max) text.take(max) + L("\n…（共 %s 字符，已截断）").format(text.length) else text)
                )
            } else {
                val value = o["v"]?.let { hub.unquoteJs(it.toString()) }.orEmpty()
                ToolResult(
                    L("返回值：\n") +
                        (if (value.length > max) value.take(max) + L("\n…（共 %s 字符，已截断）").format(value.length) else value)
                )
            }
        } finally {
            done(hub)
        }
    }

    // -------------------------------------------------------- browser_screenshot

    private fun screenshot() = ToolSpec(
        name = "browser_screenshot",
        title = "网页截图",
        description = "把页面渲染成图片给模型看（不是屏幕截图，页面藏在后台也能拍）。" +
            "看不出布局、怀疑点错了元素时用它。可选保存到手机文件。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "path" to Schema.str("顺便保存到哪个文件（可选）"),
                "maxBytes" to Schema.int("图片大小上限（字节）", 8_000_000, 10_000, 32_000_000)
            )
        )
    ) { ctx ->
        val hub0 = hub(ctx)
        val target = runCatching { hub0.resolvePage(ctx.args.str("page")) }.getOrNull()
        val hub = start(ctx, L("网页截图"), target?.url, target?.let { hub0.pageLine(it) })
        try {
            val b = hub.engine()
            val page = if (target == null) hub.resolvePage(null) else hub.resolvePage(target.id)
            val bytes = b.screenshot(page.id) ?: ctx.fail(
                L("截图失败（页面还在加载？或者这个页面不允许被绘制）。")
            )
            if (bytes.isEmpty()) ctx.fail(L("截出来是空图。"))
            if (bytes.size > ctx.args.intOr("maxBytes", 8_000_000).let { if (it <= 0) 8_000_000 else it }) {
                ctx.fail(L("截图太大（%s 字节）。").format(bytes.size))
            }
            var saved = ""
            val pathArg = ctx.args.str("path")
            if (!pathArg.isNullOrBlank()) {
                val f = ctx.path("path")
                ctx.guard(PermKey.WRITE, f, L("保存网页截图 → %s").format(f.name), f.path)
                if (!ctx.bridge.writeBytes(f, bytes)) ctx.fail(L("写文件失败：%s").format(f.path))
                saved = L("\n已保存：%s").format(f.path)
            }
            ToolResult(
                text = hub.pageLine(page) + L("\n网页截图（PNG，%s 字节）").format(bytes.size) + saved,
                extraContent = listOf(
                    jo("type" to "image", "data" to java.util.Base64.getEncoder().encodeToString(bytes), "mimeType" to "image/png")
                )
            )
        } finally {
            done(hub)
        }
    }

    // ------------------------------------------------------------ browser_search

    private fun search() = ToolSpec(
        name = "browser_search",
        title = "网页搜索",
        description = "用搜索引擎查东西：把查询词拼成搜索链接打开，然后把结果页的标题 / 链接 / 摘要给你。" +
            "engine 可以选：必应(bing) / 百度(baidu) / Google(google) / DuckDuckGo(duckduckgo) / " +
            "知乎(zhihu) / 微博(weibo) / 哔哩哔哩(bilibili) / GitHub(github) / 维基百科(wikipedia)，" +
            "也可以填自己加的（见 browser_engines）。要看某条结果，再 browser_navigate / browser_open 打开它。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "query" to Schema.str("要搜的词"),
                "engine" to Schema.str("搜索引擎 id（默认用设置里的默认引擎）"),
                "limit" to Schema.int("最多给几条链接", 20, 1, 60),
                "read_text" to Schema.bool("顺便把结果页的正文也读一段给你（默认 true）", true),
                "new_page" to Schema.bool("新开页面（false = 用当前页面导航）", true),
                "auto_scroll" to Schema.bool("读结果前先自动滚到底（默认 true）", true),
                "wait_ms" to Schema.int("等结果加载多久（毫秒）", 1800, 0, 30_000)
            ),
            listOf("query")
        )
    ) { ctx ->
        val query = ctx.args.str("query").orEmpty()
        val limit = ctx.args.intOr("limit", 20).coerceIn(1, 60)
        val newPage = ctx.args.boolOr("new_page", true)
        val wait = ctx.args.intOr("wait_ms", 1800).coerceIn(0, 30_000)
        val hub0 = hub(ctx)
        val (engine, url) = runCatching { hub0.searchUrl(ctx.args.str("engine"), query) }
            .getOrElse { ctx.fail(it.message ?: L("搜索引擎用不了")) }
        val hub = start(ctx, L("搜索「%s」（%s）").format(query.take(40), engine.title), url)
        try {
            val page: BrowserPageInfo = if (newPage) {
                hub.open(url)
            } else {
                hub.navigateOrOpen(url).first
            }
            if (wait > 0) Thread.sleep(wait.toLong())
            val engineHost = runCatching { java.net.URI(engine.url).host.orEmpty() }.getOrDefault("")
            // 多抓一些：结果页前面全是导航 / 广告链接，抓少了会被它们占满
            val maxLinks = (limit * 8 + 40).coerceAtMost(400)
            if (ctx.args.boolOr("auto_scroll", true)) autoScroll(hub, page.id, 6)
            // 结果页是流式渲染的：等「站外」链接真的出现（最多 8 秒）再读，不然只会拿到顶栏
            val (parsed, offsite) = waitForLinks(
                hub, page.id, engineHost, maxLinks,
                want = limit.coerceAtMost(5),
                timeoutMs = 8_000
            )
            // 站外链接够多就用站外的（引擎自己的顶栏 / 翻页 / 广告都在它自己域名下）；不够就全都给
            val useful = (if (offsite.size >= 3) offsite else parsed)
                .distinctBy { it.second }
                .take(limit)
                .map { it.first to it.second }
            val head = L("搜索「%s」（%s）→ [%s] %s\n").format(query, engine.title, page.id, page.label())
            val body = buildString {
                append(head)
                if (useful.isEmpty()) {
                    append(L("没解析出结果链接（可能是需要登录 / 被反爬拦了，或者结果还没加载出来）。\n"))
                } else {
                    append(L("结果链接 %s 条：\n").format(useful.size))
                    useful.forEachIndexed { i, (text, href) ->
                        append("%d. %s\n   %s\n".format(i + 1, text.ifBlank { L("（无文字）") }.take(80), href.take(180)))
                    }
                }
                if (ctx.args.boolOr("read_text", true)) {
                    val text = hub.evalText(page.id, BrowserJs.TEXT)
                    if (text.isNotBlank()) {
                        val cut = slice(text, 0, 2500)
                        append("\n").append(L("结果页正文（前 %s 字符）：").format(cut.text.length)).append("\n----\n")
                        append(cut.text)
                    }
                }
            }
            ToolResult(body)
        } finally {
            done(hub)
        }
    }

    // -------------------------------------------------------------- browser_save

    private fun save() = ToolSpec(
        name = "browser_save",
        title = "网页另存为文件",
        description = "把页面内容存成手机上的文件（markdown / text / html）。" +
            "存下来的东西之后可以用 read_file 再看，也可以直接给用户。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("存到哪（完整路径或目录，目录会自动用页面标题当文件名）"),
                "format" to Schema.str("格式", "markdown", listOf("markdown", "text", "html")),
                "page" to Schema.str("页面 id 或序号；省略 = 当前页面"),
                "auto_scroll" to Schema.bool("存之前先自动滚到底（默认 true）", true)
            ),
            listOf("path")
        )
    ) { ctx ->
        val hub = hub(ctx)
        hub.engine()
        hub.checkPaused()
        val format = modeOf(ctx, "markdown", "markdown", "text", "html")
        val page = hub.resolvePage(ctx.args.str("page"))
        if (ctx.args.boolOr("auto_scroll", true)) autoScroll(hub, page.id, 6)
        val js = when (format) {
            "html" -> BrowserJs.HTML
            "text" -> BrowserJs.TEXT
            else -> BrowserJs.MARKDOWN
        }
        val text = if (format == "html") {
            hub.evalText(page.id, js)
        } else {
            val v = hub.evalText(page.id, js)
            if (v.isNotBlank() && format == "markdown") "# " + page.label() + "\n\n" + v else v
        }
        if (text.isBlank()) ctx.fail(L("页面没内容可存（还没加载完？先用 browser_wait 等一等）。"))
        var f = ctx.path("path")
        if (f.isDirectory || ctx.args.str("path")!!.endsWith("/")) {
            val name = page.label(50)
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .ifBlank { "page-${page.id}" }
            val ext = when (format) {
                "html" -> "html"
                "text" -> "txt"
                else -> "md"
            }
            f = java.io.File(f, "$name.$ext")
        }
        ctx.guard(
            PermKey.WRITE, f,
            L("保存网页 → %s").format(f.name),
            L("%s · %s 字节").format(hub.pageLine(page), text.toByteArray(Charsets.UTF_8).size)
        )
        if (!ctx.bridge.writeBytes(f, text.toByteArray(Charsets.UTF_8))) {
            ctx.fail(L("写文件失败：%s").format(f.path))
        }
        ToolResult(
            L("已保存 %s（%s，%s 字符）\n%s").format(
                format, f.path, text.length,
                L("之后可以用 read_file 直接读它。")
            )
        )
    }

    // ----------------------------------------------------------- browser_storage

    private fun storage() = ToolSpec(
        name = "browser_storage",
        title = "Cookie / 缓存 / UA",
        description = "管浏览器的登录态与身份：get_cookies 拿 cookie（用户在悬浮窗里登录过的站点，这里能拿到，" +
            "说明 AI 也能看到登录后的内容 —— 注意隐私）；set_cookie 手工塞一条 cookie；" +
            "clear_cookies / clear_cache 清掉；get_ua / set_ua 换身份（mobile=手机版 / desktop=桌面版 / default=WebView 默认 / 其它值=原样）。" +
            "需要登录才能看的页面，可以让用户先在悬浮窗里手动登录，然后 AI 就能接着用。",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "action" to Schema.str(
                    "做什么", "get_cookies",
                    listOf(
                        "get_cookies", "set_cookie", "clear_cookies", "clear_cache",
                        "get_ua", "set_ua"
                    )
                ),
                "url" to Schema.str("针对哪个站点（get_cookies / set_cookie 用）。省略 = 当前页面"),
                "cookie" to Schema.str("action=set_cookie 时要写的 cookie，如 a=1; b=2"),
                "value" to Schema.str("action=set_ua 时的值：mobile / desktop / default / 自定义 UA")
            ),
            listOf("action")
        )
    ) { ctx ->
        val action = ctx.str("action")?.lowercase().orEmpty().ifEmpty { "get_cookies" }
        val hub = hub(ctx)
        val b = hub.engine()
        val urlArg = ctx.args.str("url")?.takeIf { it.isNotBlank() }
            ?: runCatching { hub.current()?.url }.getOrNull()
        val label = when (action) {
            "get_cookies" -> L("读取 cookie")
            "set_cookie" -> L("写入 cookie")
            "clear_cookies" -> L("清空 cookie")
            "clear_cache" -> L("清空缓存")
            "get_ua" -> L("读取 User-Agent")
            "set_ua" -> L("设置 User-Agent")
            else -> ctx.fail(L("不认识的 action：%s").format(action))
        }
        hub.checkPaused()
        val detail = when (action) {
            "set_cookie" -> L("%s → %s").format(urlArg ?: L("（未指定站点）"), ctx.args.str("cookie").orEmpty().take(120))
            "set_ua" -> ctx.args.str("value").orEmpty()
            else -> urlArg
        }
        ctx.guardTarget(PermKey.BROWSER, urlArg, label, detail)
        hub.status(L("AI：%s").format(label))
        try {
            when (action) {
                "get_cookies" -> {
                    val c = b.cookies(urlArg)
                    ToolResult(
                        if (c.isBlank()) L("「%s」现在没有 cookie（没登录过，或者已经过期）。").format(urlArg ?: L("全部"))
                        else L("「%s」的 cookie：\n").format(urlArg ?: L("全部")) + c.take(4000)
                    )
                }

                "set_cookie" -> {
                    val target = urlArg ?: ctx.fail(L("set_cookie 需要 url（例如 https://example.com）"))
                    val cookie = ctx.args.str("cookie").orEmpty()
                    if (cookie.isBlank()) ctx.fail(L("set_cookie 需要 cookie 内容，如 a=1; b=2"))
                    val ok = b.setCookie(hub.normalizeUrl(target), cookie)
                    ToolResult(
                        if (ok) L("已写入 cookie：「%s」\n%s").format(target, cookie.take(200))
                        else L("cookie 写入失败（地址不对？）")
                    )
                }

                "clear_cookies" -> {
                    b.clearCookies()
                    ToolResult(L("已清空浏览器 cookie —— 所有站点的登录态都没了。"))
                }

                "clear_cache" -> {
                    b.clearCache()
                    ToolResult(L("已清空浏览器缓存。"))
                }

                "get_ua" -> {
                    val ua = b.userAgent()
                    ToolResult(if (ua.isBlank()) L("当前用 WebView 默认 User-Agent。") else L("当前 User-Agent：\n%s").format(ua))
                }

                else -> {
                    val v = ctx.args.str("value").orEmpty()
                    if (v.isBlank()) ctx.fail(L("set_ua 需要 value（mobile / desktop / default / 自定义 UA）"))
                    val ua = when (v.lowercase()) {
                        "default", "auto", "reset", "webview" -> null
                        else -> v
                    }
                    b.setUserAgent(ua)
                    ToolResult(
                        L("User-Agent 已设为：%s\n").format(ua ?: L("WebView 默认")) +
                            L("（已经打开的页面要 browser_history(reload) 之后才生效）")
                    )
                }
            }
        } finally {
            done(hub)
        }
    }

    // ----------------------------------------------------------- browser_engines

    private fun engines() = ToolSpec(
        name = "browser_engines",
        title = "搜索引擎管理",
        description = "看 / 加 / 删搜索引擎。内置的不能删；自己加的写进设置里，之后 browser_search 就能用。" +
            "模板里用 %s 表示查询词的位置，例如 https://example.com/search?q=%s",
        perm = PermKey.BROWSER,
        schema = Schema.obj(
            mapOf(
                "action" to Schema.str("做什么", "list", listOf("list", "add", "remove")),
                "title" to Schema.str("action=add：搜索引擎的名字，例如 淘宝"),
                "url" to Schema.str("action=add：搜索链接模板，查询词写 %s"),
                "id" to Schema.str("action=remove：要删的 id 或名字（自己加的才能删）")
            ),
            listOf("action")
        )
    ) { ctx ->
        val action = ctx.str("action")?.lowercase().orEmpty().ifEmpty { "list" }
        val hub = hub(ctx)
        hub.engine()
        hub.checkPaused()
        val label = when (action) {
            "add" -> L("新增搜索引擎")
            "remove" -> L("删除搜索引擎")
            else -> L("查看搜索引擎")
        }
        ctx.guardTarget(PermKey.BROWSER, null, label, ctx.args.str("title") ?: ctx.args.str("id"))
        hub.status(L("AI：%s").format(label))
        try {
            when (action) {
                "list" -> {
                    val list = hub.engines()
                    ToolResult(
                        L("搜索引擎 %s 个（%s 是当前默认）：\n").format(list.size, ctx.config.browserDefaultEngine) +
                            list.joinToString("\n") { e ->
                                "- %s（%s）%s\n  %s".format(
                                    e.title, e.id, if (e.builtin) L(" 内置") else L(" 自定义"), e.url
                                )
                            }
                    )
                }

                "add" -> {
                    val e = hub.addEngine(
                        ctx.args.str("title").orEmpty(),
                        ctx.args.str("url").orEmpty()
                    )
                    ToolResult(L("已添加搜索引擎：%s（%s）\n%s").format(e.title, e.id, e.url))
                }

                "remove" -> ToolResult(hub.removeEngine(ctx.args.str("id").orEmpty()))
                else -> ctx.fail(L("不认识的 action：%s（可用 list / add / remove）").format(action))
            }
        } finally {
            done(hub)
        }
    }

    // ---------------------------------------------------------- browser_download

    private fun download() = ToolSpec(
        name = "browser_download",
        title = "下载链接到手机",
        description = "把一个 http(s) 链接直接下载到手机（图片 / PDF / zip / 文本都行），" +
            "不走浏览器，所以不需要页面、也不吃 cookie。要下需要登录的文件，先用 browser_storage 拿 cookie 再想别的办法。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "url" to Schema.str("要下载的链接"),
                "path" to Schema.str("保存到哪：目录或完整文件路径"),
                "filename" to Schema.str("文件名（path 是目录时用；不给就从链接猜）"),
                "max_mb" to Schema.int("最大多少 MB（超过就放弃）", 200, 1, 4096)
            ),
            listOf("url", "path")
        )
    ) { ctx ->
        val hub = hub(ctx)
        val raw = ctx.args.str("url").orEmpty()
        val url = hub.normalizeUrl(raw)
        hub.checkPaused()
        var dir = ctx.path("path")
        val given = ctx.args.str("path").orEmpty()
        if (dir.isDirectory || given.endsWith("/")) {
            val name = ctx.args.str("filename")?.takeIf { it.isNotBlank() } ?: guessName(url)
            dir = java.io.File(dir, name)
        }
        val target = dir
        val maxMb = ctx.args.intOr("max_mb", 200).coerceIn(1, 4096)
        ctx.guard(
            PermKey.WRITE, target,
            L("下载 %s → %s").format(runCatching { java.net.URI(url).host }.getOrNull() ?: url, target.name),
            url
        )
        hub.status(L("AI：下载文件"))
        try {
            ToolResult(httpGet(url, target, maxMb, ctx))
        } finally {
            hub.status("")
        }
    }

    private fun guessName(url: String): String {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val last = path.substringAfterLast('/').substringBefore('?')
        val clean = last.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        return if (clean.isBlank() || !clean.contains('.')) "download-${System.currentTimeMillis() % 100000}.bin" else clean
    }

    /** 最简单的下载：HttpURLConnection，跟重定向，边读边写。 */
    private fun httpGet(url: String, target: java.io.File, maxMb: Int, ctx: CallContext): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", DEFAULT_UA)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return L("下载失败：HTTP %s\n%s").format(code, conn.responseMessage ?: "")
            }
            val total = conn.contentLengthLong
            if (total > maxMb.toLong() * 1024 * 1024) {
                return L("文件太大（%s MB，上限 %s MB）。").format(total / 1024 / 1024, maxMb)
            }
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                var read: Int
                while (true) {
                    read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    if (out.size() > maxMb.toLong() * 1024 * 1024) {
                        return L("文件超过 %s MB，已中止下载。").format(maxMb)
                    }
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return L("下载回来是空文件。")
            if (!ctx.bridge.writeBytes(target, bytes)) {
                return L("写文件失败：%s（没有权限？）").format(target.path)
            }
            L("已下载：%s（%s，%s）\n源：%s").format(
                target.path, ctx.sizeFormat(bytes.size.toLong()),
                conn.contentType ?: L("未知类型"), url
            )
        } catch (e: Exception) {
            L("下载失败：%s").format(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    // ------------------------------------------------------------------ 小工具

    /** 页面的 meta 一行（标题 / 地址 / 滚动位置 / 元素个数）。 */
    private fun readMeta(hub: BrowserHub, pageId: String): String? {
        val json = hub.evalJson(pageId, BrowserJs.META) ?: return null
        val o = json as? JsonObject ?: return null
        return L("标题：%s\n地址：%s\n状态：%s，元素 %s 个，正文 %s 字符，滚动 %s / %s\n").format(
            o.str("title").orEmpty().ifBlank { L("（无）") },
            o.str("url").orEmpty(),
            o.str("ready").orEmpty(),
            o.str("elements").orEmpty(),
            o.str("textLength").orEmpty(),
            o.str("scrollY").orEmpty(),
            o.str("scrollHeight").orEmpty()
        )
    }
}

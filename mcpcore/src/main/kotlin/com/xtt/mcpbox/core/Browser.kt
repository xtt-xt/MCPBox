// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * 内置浏览器（工具包 `browser`）。
 *
 * 分层：
 *  · **本文件（core）** —— 页面模型、URL 规整与内网拦截、搜索引擎表、注入页面的 JS、
 *    结果排版。纯 JVM，能在 harness 里全量测。
 *  · **app 层** —— `BrowserController` 实现 [BrowserBridge]：WebView + 悬浮球 + 悬浮窗。
 *
 * core 不 import android，所以 WebView 只能通过 [BrowserBridge] 这根线连进来
 * （和 `FileBridge` / `CommandLauncher` 一个套路）。
 */

/** 一个页面（标签页）的状态快照。 */
data class BrowserPageInfo(
    val id: String,
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** 加载失败时的原因（HTTP 错误 / 网络错误）。 */
    val error: String? = null
) {
    /** 域名（给 AI 看的一行用）。 */
    val host: String
        get() = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    /** 短标签：标题优先，其次域名，最后 URL。 */
    fun label(max: Int = 60): String {
        val t = title.trim().ifBlank { url.trim().ifBlank { id } }
        return t.replace('\n', ' ').take(max)
    }
}

/** 一个搜索引擎：`%s` 是查询词（已 URL 编码）要插的位置。 */
@Serializable
data class SearchEngine(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val builtin: Boolean = false
) {
    /** 把查询词拼进模板。 */
    fun build(query: String): String =
        url.replace("%s", java.net.URLEncoder.encode(query.trim(), "UTF-8"))
}

/**
 * app 层（WebView）要实现的能力。所有方法都必须在**主线程**上被调用
 * （实现方自己负责 post，core 这边的调用是阻塞的）。
 */
interface BrowserBridge {

    /** 引擎是否可用（WebView 能不能用、悬浮窗权限有没有）。 */
    fun available(): Boolean = true

    /** 不可用时的原因（直接展示给 AI / 用户）。 */
    fun unavailableReason(): String = L("浏览器引擎不可用")

    /** 新开一个页面，返回页面 id。 */
    fun open(url: String): String

    fun close(id: String)

    fun closeAll()

    fun navigate(id: String, url: String)

    /** action = back / forward / reload。 */
    fun history(id: String, action: String)

    /** 全部页面（顺序 = 创建顺序）。 */
    fun pages(): List<BrowserPageInfo>

    /** 当前页面（悬浮窗正在显示的那个）。 */
    fun currentId(): String?

    /** 切换当前页面（悬浮窗跟着切）。 */
    fun switchTo(id: String)

    /** 执行 JS，返回它的返回值（已经过 JSON 编码的字符串，可能为 null）。 */
    fun eval(id: String, js: String): String?

    /** 截整页图片（PNG 字节），失败返回 null。 */
    fun screenshot(id: String): ByteArray?

    /** 悬浮球上的状态提示（""=清除）。 */
    fun setStatus(text: String)

    /** 用户手动暂停 AI 操作。 */
    fun paused(): Boolean = false

    fun setPaused(value: Boolean) {}

    /** 取 cookie（url 为空 = 全部）。 */
    fun cookies(url: String?): String = ""

    /** 写一条自定义 cookie（url 例：https://example.com）。 */
    fun setCookie(url: String, cookie: String): Boolean = false

    fun clearCookies() {}

    fun clearCache() {}

    /** 当前 User-Agent（""=WebView 默认）。 */
    fun userAgent(): String = ""

    fun setUserAgent(ua: String?) {}

    /** 关掉引擎（服务停止时用）。 */
    fun release() {}
}

/**
 * 浏览器门面：core 侧唯一的入口。
 *
 * 页面状态归 app 层（[BrowserBridge]）所有，这里只做「规则 + 排版」：
 * URL 规整、内网拦截、搜索引擎表、页面数量上限、JS 注入、结果给 AI 的格式。
 */
class BrowserHub(private val config: Config, private val bridge: BrowserBridge?) {

    // ------------------------------------------------------------------ 基础

    /** 拿到可用的引擎，不行就直接给 AI 一个能照着做的说法。 */
    fun engine(): BrowserBridge {
        val b = bridge ?: throw ToolFailure(
            L("这台设备上没有接浏览器引擎（只有 App 里才有 WebView）。")
        )
        if (!b.available()) {
            throw ToolFailure(
                L("浏览器引擎不可用：%s").format(b.unavailableReason())
            )
        }
        return b
    }

    fun available(): Boolean = bridge?.available() == true

    /** 悬浮球状态提示。 */
    fun status(text: String) {
        runCatching { bridge?.setStatus(text) }
    }

    /** 用户暂停时，AI 的动作一律先被拦下来。 */
    fun checkPaused() {
        val b = bridge ?: return
        if (b.paused()) {
            throw ToolFailure(
                L("用户已暂停浏览器（暂停期间 AI 不能操作页面）。") +
                    currentHint() +
                    L("\n等用户在悬浮窗里点「继续」之后再试。")
            )
        }
    }

    /** 「当前页面是啥」的一句话，附在出错信息后面。 */
    fun currentHint(): String {
        val cur = runCatching { current() }.getOrNull() ?: return ""
        return L("\n当前页面：[%s] %s").format(cur.id, cur.label())
    }

    // ------------------------------------------------------------------ 页面

    fun pages(): List<BrowserPageInfo> = engine().pages()

    fun current(): BrowserPageInfo? {
        val b = engine()
        val id = b.currentId() ?: return b.pages().lastOrNull()
        return b.pages().firstOrNull { it.id == id } ?: b.pages().lastOrNull()
    }

    /**
     * AI 给的 `page` 参数 → 真实页面。
     *
     * 认三种写法，怎么顺手怎么来：
     *  · 省略 / "" → 当前页
     *  · 页面 id（或它的前缀，如 `p1`）
     *  · 序号（从 1 开始，= `browser_pages` 里显示的行号）
     */
    fun resolvePage(given: String?, defaultToCurrent: Boolean = true): BrowserPageInfo {
        val b = engine()
        val list = b.pages()
        if (list.isEmpty()) {
            throw ToolFailure(L("现在一个浏览器页面都没有。先用 browser_open 打开一个网页。"))
        }
        val key = given?.trim().orEmpty()
        if (key.isEmpty()) {
            if (defaultToCurrent) current()?.let { return it }
            return list.last()
        }
        key.toIntOrNull()?.let { n ->
            list.getOrNull(n - 1)?.let { return it }
            throw ToolFailure(
                L("没有第 %s 个页面（现在一共 %s 个），用 browser_pages 看一眼列表。")
                    .format(n, list.size)
            )
        }
        list.firstOrNull { it.id.equals(key, ignoreCase = true) }?.let { return it }
        val prefixed = list.filter { it.id.startsWith(key, ignoreCase = true) }
        return when (prefixed.size) {
            1 -> prefixed.first()
            0 -> throw ToolFailure(
                L("找不到页面「%s」。现有页面：\n").format(key) + formatPages(list, b.currentId())
            )
            else -> throw ToolFailure(
                L("「%s」匹配到多个页面，用完整 id 指定：\n").format(key) +
                    formatPages(prefixed, b.currentId())
            )
        }
    }

    /** 新开页面（受「最多几个页面」限制）。 */
    fun open(url: String): BrowserPageInfo {
        val b = engine()
        checkPaused()
        val clean = normalizeUrl(url)
        val max = config.browserMaxPages.coerceIn(1, 12)
        val list = b.pages()
        if (list.size >= max) {
            throw ToolFailure(
                L("已经开了 %s 个页面（上限 %s，可在 设置 → 浏览器 里调）。").format(list.size, max) +
                    L("\n先 browser_close 关掉不用的，或者用 browser_navigate 复用当前页面。") +
                    "\n" + formatPages(list, b.currentId())
            )
        }
        val id = b.open(clean)
        val page = b.pages().firstOrNull { it.id == id }
        // 等一小会儿让标题出来（新页面刚开始多半是空的）
        var tries = 0
        var best = page
        while (tries < 25 && (best?.title.isNullOrBlank() || best?.loading == true)) {
            Thread.sleep(120)
            best = b.pages().firstOrNull { it.id == id } ?: best
            tries++
        }
        return best ?: BrowserPageInfo(id = id, url = clean)
    }

    /** 复用当前页（没有页面就新开一个）。 */
    fun navigateOrOpen(url: String): Pair<BrowserPageInfo, Boolean> {
        val b = engine()
        checkPaused()
        val clean = normalizeUrl(url)
        val cur = runCatching { current() }.getOrNull()
        if (cur == null) return open(clean) to true
        b.navigate(cur.id, clean)
        return (b.pages().firstOrNull { it.id == cur.id } ?: cur) to false
    }

    fun close(given: String?): String {
        val b = engine()
        if (given == null || given.isBlank() || given.equals("all", true)) {
            val n = b.pages().size
            b.closeAll()
            return L("已关闭全部 %s 个页面。").format(n)
        }
        val page = resolvePage(given, defaultToCurrent = false)
        b.close(page.id)
        val left = runCatching { b.pages() }.getOrDefault(emptyList())
        return L("已关闭页面 [%s] %s\n").format(page.id, page.label()) +
            if (left.isEmpty()) L("现在没有打开的页面了（悬浮球会消失）。")
            else L("还剩 %s 个页面：\n").format(left.size) + formatPages(left, b.currentId())
    }

    // ---------------------------------------------------------------- URL

    /**
     * 规整 AI 给的 URL，并拦掉内网地址。
     *
     * 为什么要拦内网：AI 打开的网页里跑的是**别人写的脚本**，而这个手机上跑着
     * MCP 服务器（`/mcp` 端点本身不校验 token）。默认禁掉 127.0.0.1 / 局域网，
     * 免得一个恶意页面直接指挥手机上的工具。要放开见 设置 → 浏览器。
     */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim().trim('"').trim('\'').replace(" ", "%20")
        if (url.isEmpty()) throw ToolFailure(L("url 不能为空"))
        if (url.startsWith("//")) url = "https:$url"
        val lower = url.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("blob:") || lower.startsWith("file:") ||
            lower.startsWith("content:") || lower.startsWith("intent:")
        ) {
            throw ToolFailure(
                L("不支持这种地址：%s（只能打开 http / https 网页；本地文件用 read_file）。").format(url.take(60))
            )
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (!url.contains('.') || url.contains(' ')) {
                throw ToolFailure(
                    L("「%s」不像网址。").format(url.take(60)) +
                        L("\n要搜索的话用 browser_search（查询词会自动拼成搜索链接）。")
                )
            }
            url = "https://$url"
        }
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: throw ToolFailure(L("网址解析不了：%s").format(url.take(80)))
        checkHost(host)
        return url
    }

    /** 内网 / 本机地址拦截（allowLan 打开时全放行）。 */
    fun checkHost(host: String) {
        if (config.browserAllowLan) return
        val h = host.trim().trim('[', ']').lowercase().removeSuffix(".")
        val blocked = when {
            h.isEmpty() -> false
            h == "localhost" || h.endsWith(".localhost") -> true
            h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd") -> true
            h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") ||
                h.endsWith(".home.arpa") -> true
            h.contains(':') -> false
            // 纯主机名（没有点）= 公司 / 家庭内网里那台机器
            !h.contains('.') -> h.any { it.isDigit() }
            isPrivateIpv4(h) -> true
            else -> false
        }
        if (blocked) {
            throw ToolFailure(
                L("已阻止访问内网地址 %s。").format(host) +
                    L("\n原因：这个手机上跑着 MCP 服务器，网页脚本能访问本机 / 内网就等于绕过了审批。") +
                    L("\n确实要开：设置 → 浏览器 → 允许访问内网地址（默认关）。")
            )
        }
    }

    private fun isPrivateIpv4(h: String): Boolean {
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return when {
            nums[0] == 127 -> true      // 回环
            nums[0] == 10 -> true       // 10.0.0.0/8
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            nums[0] == 169 && nums[1] == 254 -> true  // link-local
            nums[0] == 0 -> true
            else -> false
        }
    }

    // ------------------------------------------------------------ 搜索引擎

    fun engines(): List<SearchEngine> =
        BrowserEngines.BUILTIN + BrowserEngines.custom(config)

    fun engineById(id: String?): SearchEngine {
        val key = id?.trim().orEmpty().ifEmpty { config.browserDefaultEngine }
        return engines().firstOrNull { it.id.equals(key, ignoreCase = true) }
            ?: engines().firstOrNull { it.title.contains(key, ignoreCase = true) }
            ?: throw ToolFailure(
                L("没有叫「%s」的搜索引擎。现在有：").format(key) +
                    engines().joinToString("、") { it.id } +
                    L("\n可以先用 browser_engines 加一个（action=add）。")
            )
    }

    fun searchUrl(engineId: String?, query: String): Pair<SearchEngine, String> {
        val q = query.trim()
        if (q.isEmpty()) throw ToolFailure(L("查询词不能为空"))
        val e = engineById(engineId)
        return e to e.build(q)
    }

    fun addEngine(title: String, url: String): SearchEngine = BrowserEngines.add(config, title, url)

    fun removeEngine(idOrTitle: String): String {
        val removed = BrowserEngines.remove(config, idOrTitle)
        if (removed == null) {
            throw ToolFailure(
                L("自定义搜索引擎里没有「%s」（内置的不能删）。").format(idOrTitle.trim()) +
                    "\n" + L("自定义的：") + (BrowserEngines.custom(config)
                    .joinToString("、") { it.title }.ifBlank { L("（还没有）") })
            )
        }
        return L("已删除搜索引擎「%s」。").format(removed.title)
    }

    private fun saveCustom(list: List<SearchEngine>) = BrowserEngines.save(config, list)

    // ------------------------------------------------------------ JS / 结果

    /** 跑一段 JS 并把它 JSON 解析出来（失败返回 null）。 */
    fun evalJson(pageId: String, js: String): JsonElement? {
        val raw = engine().eval(pageId, js) ?: return null
        return parseJsResult(raw)
    }

    /** 跑一段 JS，拿纯文本（字符串结果会去掉外层的 JSON 引号）。 */
    fun evalText(pageId: String, js: String): String {
        val raw = engine().eval(pageId, js) ?: return ""
        if (raw == "null" || raw.isBlank()) return ""
        return unquoteJs(raw)
    }

    /** evaluateJavascript 的回调值是 JSON 编码过的字符串，这里拆一层。 */
    fun parseJsResult(raw: String): JsonElement? {
        val text = unquoteJs(raw).trim()
        if (text.isEmpty() || text == "null") return null
        return runCatching { J.parseToJsonElement(text) }.getOrNull()
    }

    fun unquoteJs(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return runCatching {
                J.parseToJsonElement(s).let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: s
                }
            }.getOrDefault(s)
        }
        return s
    }

    // ------------------------------------------------------------------ 排版

    /** 页面列表（`browser_pages` 的输出）。 */
    fun formatPages(list: List<BrowserPageInfo>, currentId: String?): String {
        if (list.isEmpty()) return L("（没有打开的页面）")
        return list.mapIndexed { i, p ->
            val mark = if (p.id == currentId) "*" else " "
            val flags = buildString {
                if (p.loading) append(L(" 加载中"))
                if (p.error != null) append(L(" 出错"))
                if (p.canGoBack) append(L(" 可后退"))
            }
            "%s %d. [%s] %s%s%s".format(
                mark, i + 1, p.id, p.label(),
                if (p.host.isNotBlank()) "  · ${p.host}" else "", flags
            )
        }.joinToString("\n")
    }

    /** 一个页面的一行摘要（动作类工具的返回里用）。 */
    fun pageLine(p: BrowserPageInfo?): String =
        if (p == null) L("（没有页面）")
        else L("当前页面：[%s] %s%s%s").format(
            p.id, p.label(),
            if (p.host.isNotBlank()) "  · ${p.host}" else "",
            if (p.loading) L("（还在加载）") else ""
        )
}

/**
 * 跳转壳还原。
 *
 * 搜索引擎早就不直接给结果链接了：必应把它塞进 `bing.com/ck/a?...&u=a1<base64>`，
 * Google 用 `/url?q=`，DuckDuckGo 用 `/l/?uddg=`，知乎用 `link.zhihu.com/?target=`。
 * 不还原的话，AI 看到的全是「同一个域名下的怪链接」，既没法判断来源，
 * 也会被「去掉搜索引擎自己的域名」那一步全部滤掉。
 *
 * 认不出来就原样返回（比如百度的 `link?url=` 要联网才能解，不强求）。
 */
object BrowserRedirects {

    fun unwrap(href: String): String {
        val uri = runCatching { java.net.URI(href.trim()) }.getOrNull() ?: return href
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return href
        val query = parseQuery(uri.rawQuery)
        val target: String? = when {
            host.endsWith("bing.com") && uri.path.orEmpty().contains("/ck/a") -> {
                query["u"]?.let { decodeBing(it) }
            }

            (host.endsWith("google.com") || host.endsWith("google.com.hk")) &&
                uri.path.orEmpty().startsWith("/url") ->
                (query["q"] ?: query["url"])?.let { urlDecode(it) }

            host.endsWith("duckduckgo.com") && uri.path.orEmpty().startsWith("/l/") ->
                query["uddg"]?.let { urlDecode(it) }

            host == "link.zhihu.com" || host.endsWith(".zhihu.com") && uri.path == "/link" ->
                query["target"]?.let { urlDecode(it) }

            host.endsWith("sogou.com") && uri.path.orEmpty().startsWith("/link") ->
                query["url"]?.let { urlDecode(it) }

            else -> null
        }
        val clean = target?.trim().orEmpty()
        return if (clean.startsWith("http://") || clean.startsWith("https://")) clean else href
    }

    /** 必应用 `u=a1<base64url(真实地址)>`（没有 a1 前缀的也可能是纯 base64）。 */
    private fun decodeBing(value: String): String? {
        val raw = value.removePrefix("a1")
        val padded = raw.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching {
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val i = part.indexOf('=')
            if (i <= 0) return@forEach
            out.putIfAbsent(part.substring(0, i), part.substring(i + 1))
        }
        return out
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * 两个域名算不算「同一个站」。
 *
 * 为什么需要它：搜索引擎的导航、翻页、广告链接都在引擎自己的域名下，
 * 而引擎常常换域名（`www.bing.com` → `cn.bing.com`），直接 `endsWith` 会判错 ——
 * 结果就是满屏「打开必应主页 / 图片 / 视频 / 24 小时内」，真正的结果一个都看不到。
 *
 * 取最后两段来比（`cn.bing.com` 与 `www.bing.com` 都是 `bing.com`）。
 * `co.uk` 这类二级后缀会偏宽，但这里只用来剔除引擎自家链接，偏宽可以接受。
 */
fun sameSiteHost(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    fun tail(host: String): String {
        val parts = host.lowercase().trim('.').split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }
    return tail(a) == tail(b)
}

/** 内置搜索引擎。查询词统一放在 `%s`。 */object BrowserEngines {

    val BUILTIN: List<SearchEngine> = listOf(
        SearchEngine("bing", "必应", "https://www.bing.com/search?q=%s", true),
        SearchEngine("baidu", "百度", "https://www.baidu.com/s?wd=%s", true),
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s", true),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", true),
        SearchEngine("zhihu", "知乎", "https://www.zhihu.com/search?type=content&q=%s", true),
        SearchEngine("weibo", "微博", "https://s.weibo.com/weibo?q=%s", true),
        SearchEngine("bilibili", "哔哩哔哩", "https://search.bilibili.com/all?keyword=%s", true),
        SearchEngine("github", "GitHub", "https://github.com/search?q=%s", true),
        SearchEngine("wikipedia", "维基百科", "https://zh.wikipedia.org/w/index.php?search=%s", true)
    )

    val BUILTIN_IDS: List<String> = BUILTIN.map { it.id }

    /** 用户自建的搜索引擎（存在 Config 里的 JSON）。 */
    fun custom(config: Config): List<SearchEngine> {
        val raw = config.browserEngines
        if (raw.isBlank()) return emptyList()
        return runCatching {
            J.decodeFromJsonElement(ListSerializer(SearchEngine.serializer()), J.parseToJsonElement(raw))
        }.getOrDefault(emptyList()).map { it.copy(builtin = false) }
    }

    /** 写回自定义列表（App 的设置页和 `browser_engines` 工具共用这一份数据）。 */
    fun save(config: Config, list: List<SearchEngine>) {
        config.browserEngines = J.encodeToJsonElement(
            ListSerializer(SearchEngine.serializer()), list
        ).toString()
        config.save()
    }

    /** 校验并新增一个自定义搜索引擎（校验不过会抛 [ToolFailure]，文案能直接给用户看）。 */
    fun add(config: Config, title: String, url: String): SearchEngine {
        if (title.isBlank()) throw ToolFailure(L("搜索引擎得有个名字"))
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw ToolFailure(L("模板要是个网址，查询词的位置写 %s，例如 https://example.com/search?q=%s"))
        }
        if (!u.contains("%s")) {
            throw ToolFailure(L("模板里得有 %s（查询词插在哪里），例如 https://example.com/search?q=%s"))
        }
        val all = BUILTIN + custom(config)
        val id = title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "").take(16)
            .ifBlank { "e${System.currentTimeMillis() % 100000}" }
        if (all.any { it.title.equals(title.trim(), ignoreCase = true) }) {
            throw ToolFailure(L("已经有一个叫「%s」的搜索引擎了（id=%s）").format(title, id))
        }
        if (all.any { it.id == id }) {
            throw ToolFailure(L("已经有一个叫「%s」的搜索引擎了（id=%s）").format(title, id))
        }
        val created = SearchEngine(id = id, title = title.trim(), url = u, builtin = false)
        save(config, custom(config) + created)
        return created
    }

    /** 删掉一个自定义搜索引擎（内置的不给删）。 */
    fun remove(config: Config, idOrTitle: String): SearchEngine? {
        val key = idOrTitle.trim()
        val target = custom(config).firstOrNull {
            it.id.equals(key, true) || it.title.equals(key, true)
        } ?: return null
        save(config, custom(config).filterNot { it.id == target.id })
        return target
    }
}

/**
 * 注入页面的 JS 片段。
 *
 * 都用 `(function(){ … })()` 包起来 —— `evaluateJavascript` 认的是**整个脚本的完成值**，
 * 用 IIFE 就不用担心外面还有别的东西。
 *
 * 注意：这里是 Kotlin 原生字符串（不是模板串），JS 里的 `$` 不参与 Kotlin 插值；
 * 因此 JS 代码里不要写 `${…}` 模板字面量，一律用 `+` 拼。
 */
object BrowserJs {

    /** 公共工具函数：`MCP.*`，被下面每段脚本前置。 */
    private const val PRELUDE = """
var MCP = {
  vis: function (e) {
    if (!e || !e.getBoundingClientRect) return false;
    var r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var s = null;
    try { s = getComputedStyle(e); } catch (x) {}
    if (s && (s.visibility === 'hidden' || s.display === 'none' || s.opacity === '0')) return false;
    return true;
  },
  txt: function (e) {
    var t = e.innerText || e.textContent || e.value || e.getAttribute('aria-label') ||
            e.getAttribute('placeholder') || e.getAttribute('title') || '';
    return String(t).replace(/\s+/g, ' ').trim().slice(0, 140);
  },
  SEL: 'a,button,input,select,textarea,summary,label,[role],[onclick],[tabindex],[contenteditable=true]',
  interactive: function (e) {
    var t = (e.tagName || '').toLowerCase();
    if (t === 'a' || t === 'button' || t === 'select' || t === 'textarea' || t === 'summary' || t === 'label') return true;
    if (t === 'input') return String(e.type || 'text').toLowerCase() !== 'hidden';
    var r = (e.getAttribute('role') || '').toLowerCase();
    if (r === 'button' || r === 'link' || r === 'tab' || r === 'menuitem' || r === 'checkbox' ||
        r === 'radio' || r === 'combobox' || r === 'option' || r === 'switch') return true;
    if (e.hasAttribute('onclick') || e.isContentEditable) return true;
    var ti = e.getAttribute('tabindex');
    if (ti !== null && ti !== '-1') return true;
    return false;
  },
  list: function (onlyInteractive, filter, maxItems) {
    var els = document.querySelectorAll(MCP.SEL), out = [];
    var f = filter ? String(filter).toLowerCase() : '';
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (String(e.type || '').toLowerCase() === 'hidden') continue;
      if (!MCP.vis(e)) continue;
      if (onlyInteractive && !MCP.interactive(e)) continue;
      var t = MCP.txt(e);
      var hay = (t + ' ' + (e.id || '') + ' ' + (e.name || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.href || '')).toLowerCase();
      if (f && hay.indexOf(f) < 0) continue;
      if (out.length >= maxItems) break;
      e.setAttribute('data-mcp-idx', String(out.length));
      var r = e.getBoundingClientRect();
      var v = '';
      try { v = String(e.value === undefined ? '' : e.value).slice(0, 80); } catch (x) { v = ''; }
      if (String(e.type || '').toLowerCase() === 'password') v = '';
      out.push({
        i: out.length, tag: String(e.tagName || '').toLowerCase(), type: e.type || '',
        role: e.getAttribute('role') || '', text: t, href: e.href || '',
        id: e.id || '', name: e.name || '', placeholder: e.getAttribute('placeholder') || '',
        value: v, checked: !!e.checked, disabled: !!e.disabled,
        x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
        w: Math.round(r.width), h: Math.round(r.height)
      });
    }
    return out;
  },
  byIndex: function (n) {
    var e = document.querySelector('[data-mcp-idx="' + n + '"]');
    if (e) return e;
    MCP.list(true, null, 600);
    return document.querySelector('[data-mcp-idx="' + n + '"]');
  },
  byText: function (val, idx) {
    var v = String(val).toLowerCase(), els = document.querySelectorAll(MCP.SEL), hits = [];
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (!MCP.vis(e)) continue;
      if (MCP.txt(e).toLowerCase().indexOf(v) >= 0) hits.push(e);
    }
    var exact = [];
    for (var j = 0; j < hits.length; j++) { if (MCP.txt(hits[j]).toLowerCase() === v) exact.push(hits[j]); }
    var list = exact.length ? exact : hits;
    return list[idx || 0] || null;
  },
  findEl: function (by, val, idx) {
    if (by === 'index') return MCP.byIndex(Math.max(0, parseInt(val, 10) || 0));
    if (by === 'selector') { try { return document.querySelector(val); } catch (x) { return null; } }
    if (by === 'text') return MCP.byText(val, idx);
    return null;
  },
  describe: function (e) {
    if (!e) return 'null';
    var r = e.getBoundingClientRect();
    return JSON.stringify({
      tag: String(e.tagName || '').toLowerCase(), text: MCP.txt(e).slice(0, 80),
      href: e.href || '', id: e.id || '', name: e.name || '',
      x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2)
    });
  },
  click: function (e) {
    if (!e) return 'null';
    try { e.scrollIntoView({ block: 'center', inline: 'center' }); } catch (x) {}
    var r = e.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, view: window };
    var kinds = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
    for (var i = 0; i < kinds.length; i++) {
      try { e.dispatchEvent(new MouseEvent(kinds[i], opts)); } catch (x) {}
    }
    try {
      if (typeof e.click === 'function') e.click();
      else e.dispatchEvent(new MouseEvent('click', opts));
    } catch (x) {}
    return 'ok';
  },
  setValue: function (e, v, clearFirst) {
    if (!e) return 'not-found';
    var tag = String(e.tagName || '').toLowerCase();
    var tp = String(e.type || '').toLowerCase();
    if (tag === 'select') {
      var opts = e.options || [], ok = false;
      for (var i = 0; i < opts.length; i++) {
        var o = opts[i];
        if (String(o.value) === String(v) || String(o.text || '').trim() === String(v) || String(i) === String(v)) {
          e.selectedIndex = i; ok = true; break;
        }
      }
      if (!ok) return 'no-such-option';
    } else if (tp === 'checkbox' || tp === 'radio') {
      var want = (v === 'true' || v === '1' || v === 'on' || v === 'yes' || v === 'checked');
      if (!!e.checked !== want) { try { e.click(); } catch (x) { e.checked = want; } }
    } else if (e.isContentEditable) {
      try { e.focus(); } catch (x) {}
      e.textContent = (clearFirst === false ? String(e.textContent || '') + v : v);
    } else {
      try { e.focus(); } catch (x) {}
      var next = (clearFirst === false ? String(e.value || '') + v : v);
      var proto = (tag === 'textarea') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var d = null;
      try { d = Object.getOwnPropertyDescriptor(proto, 'value'); } catch (x) {}
      if (d && d.set) d.set.call(e, next); else e.value = next;
    }
    try {
      e.dispatchEvent(new Event('input', { bubbles: true }));
      e.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (x) {}
    return 'ok';
  },
  submit: function (e) {
    if (!e) return 'no-element';
    var f = e.form || (e.closest ? e.closest('form') : null);
    if (f) {
      try { if (f.requestSubmit) { f.requestSubmit(); return 'form'; } } catch (x) {}
      var b = f.querySelector('button[type=submit],input[type=submit],button:not([type])');
      if (b && b !== e) { MCP.click(b); return 'submit-button'; }
      try { f.submit(); return 'form-submit'; } catch (x) {}
    }
    try {
      e.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      e.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      e.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    } catch (x) {}
    return 'enter';
  },
  scroller: function () {
    var all = document.querySelectorAll('div,main,section,article,ul,ol'), best = null, bh = 0;
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.scrollHeight > e.clientHeight + 60 && e.clientHeight > 200 && e.scrollHeight > bh) {
        bh = e.scrollHeight; best = e;
      }
    }
    return best;
  },
  contentRoot: function () {
    var best = null, bs = 0;
    var cands = document.querySelectorAll('article,main,[role=main],#content,.content,.article,.post,.markdown-body');
    for (var i = 0; i < cands.length; i++) {
      var n = (cands[i].innerText || '').length;
      if (n > bs) { bs = n; best = cands[i]; }
    }
    return best || document.body;
  }
};
"""

    /** 页面概要：标题 / 地址 / 加载状态 / 滚动位置。 */
    val META = prelude() + """
JSON.stringify({
  title: document.title || '', url: location.href,
  ready: document.readyState,
  loading: document.readyState !== 'complete',
  scrollY: Math.round(window.scrollY || 0),
  innerHeight: window.innerHeight || 0,
  scrollHeight: Math.max(document.documentElement.scrollHeight || 0, (document.body ? document.body.scrollHeight : 0)),
  elements: document.querySelectorAll(MCP.SEL).length,
  links: document.links ? document.links.length : 0,
  textLength: (MCP.contentRoot().innerText || '').length
})
"""

    /** 可交互元素列表（含坐标 / 类型 / 值）。 */
    fun elements(onlyInteractive: Boolean, filter: String?, maxItems: Int): String = prelude() + """
JSON.stringify({
  title: document.title || '', url: location.href,
  items: MCP.list($onlyInteractive, ${jsStr(filter)}, $maxItems)
})
"""

    /** 全部链接（去重，绝对地址）。 */
    fun links(filter: String?, maxItems: Int): String = prelude() + """
(function () {
  var as = document.querySelectorAll('a[href]'), out = [], seen = {};
  var raw = ${jsStr(filter)};
  var f = raw ? String(raw).toLowerCase() : '';
  for (var i = 0; i < as.length; i++) {
    var a = as[i];
    var href = a.href || '';
    if (!href || href.indexOf('javascript:') === 0) continue;
    var t = MCP.txt(a);
    if (f && (t + ' ' + href).toLowerCase().indexOf(f) < 0) continue;
    var key = t + '|' + href;
    if (seen[key]) continue;
    seen[key] = 1;
    if (out.length >= $maxItems) break;
    out.push({ text: t, href: href });
  }
  return JSON.stringify({ url: location.href, links: out });
})()
"""

    /** 正文纯文本（去掉脚本 / 导航 / 表单之类的噪音）。 */
    val TEXT = prelude() + """
(function () {
  var root = MCP.contentRoot();
  if (!root) return '';
  var clone = root.cloneNode(true);
  var junk = clone.querySelectorAll('script,style,noscript,svg,iframe,nav,header,footer,aside,form,button,select,textarea,[aria-hidden=true]');
  for (var i = 0; i < junk.length; i++) {
    try { junk[i].parentNode.removeChild(junk[i]); } catch (x) {}
  }
  var t = clone.innerText || clone.textContent || '';
  return JSON.stringify(t.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim());
})()
"""

    /** 正文转 Markdown（标题 / 段落 / 列表 / 代码 / 图片 / 链接）。 */
    val MARKDOWN = prelude() + """
(function () {
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u || ''; } }
  var root = MCP.contentRoot();
  if (!root) return '';
  var out = [], seen = {};
  function push(s) {
    s = String(s || '').replace(/[ \t]+/g, ' ').trim();
    if (s && !seen[s]) { seen[s] = 1; out.push(s); }
  }
  function walk(el) {
    if (!el || !el.tagName) return;
    var t = el.tagName.toLowerCase();
    if (t === 'script' || t === 'style' || t === 'noscript' || t === 'svg' || t === 'iframe' ||
        t === 'nav' || t === 'footer' || t === 'aside' || t === 'form' || t === 'select') return;
    var s = null;
    try { s = getComputedStyle(el); } catch (x) {}
    if (s && (s.display === 'none' || s.visibility === 'hidden')) return;
    if (/^h[1-6]$/.test(t)) { push(new Array(parseInt(t.charAt(1), 10) + 1).join('#') + ' ' + (el.innerText || '')); return; }
    if (t === 'p') { push(el.innerText); return; }
    if (t === 'li') { push('- ' + (el.innerText || '')); return; }
    if (t === 'pre') { push('```\n' + String(el.innerText || '').replace(/\n+\s*$/, '') + '\n```'); return; }
    if (t === 'blockquote') { push('> ' + (el.innerText || '')); return; }
    if (t === 'img') {
      var src = el.currentSrc || el.src || '';
      if (src) push('![' + (el.getAttribute('alt') || '') + '](' + abs(src) + ')');
      return;
    }
    if (t === 'table') { push(String(el.innerText || '').replace(/\t/g, ' | ')); return; }
    if (t === 'a') {
      var href = el.href || '', tx = (el.innerText || '').trim();
      if (tx && href) push('[' + tx + '](' + abs(href) + ')');
      return;
    }
    var kids = el.children || [];
    for (var i = 0; i < kids.length; i++) walk(kids[i]);
  }
  walk(root);
  return JSON.stringify(out.join('\n\n'));
})()
"""

    /** 原始 HTML。 */
    val HTML = prelude() + "document.documentElement.outerHTML"

    /** 元素是否存在（browser_wait 轮询用）。 */
    fun find(selector: String?, text: String?): String = prelude() + """
(function () {
  var sel = ${jsStr(selector)}, txt = ${jsStr(text)};
  if (sel) {
    var e = null;
    try { e = document.querySelector(sel); } catch (x) { return JSON.stringify({ ok: false, err: 'selector 写法有问题' }); }
    return JSON.stringify({ ok: !!e, what: sel });
  }
  if (txt) {
    var els = document.querySelectorAll(MCP.SEL), v = txt.toLowerCase();
    for (var i = 0; i < els.length; i++) {
      if (MCP.vis(els[i]) && MCP.txt(els[i]).toLowerCase().indexOf(v) >= 0) {
        return JSON.stringify({ ok: true, what: txt, text: MCP.txt(els[i]).slice(0, 60) });
      }
    }
    return JSON.stringify({ ok: false, what: txt });
  }
  return JSON.stringify({ ok: false, err: '没有给 selector / text' });
})()
"""

    /** 按条件点击：by = index / text / selector / point。 */
    fun click(by: String, value: String?, index: Int): String = prelude() + """
(function () {
  var by = ${jsStr(by)}, val = ${jsStr(value)};
  var e = null;
  if (by === 'point') {
    var parts = String(val).split(',');
    e = document.elementFromPoint(parseFloat(parts[0]) || 0, parseFloat(parts[1]) || 0);
  } else {
    e = MCP.findEl(by, val, $index);
  }
  if (!e) return JSON.stringify({ ok: false, err: 'not-found' });
  var before = location.href;
  var r = MCP.click(e);
  return JSON.stringify({ ok: true, target: JSON.parse(MCP.describe(e)), urlBefore: before });
})()
"""

    /** 填一个控件（input / textarea / select / checkbox / contenteditable）。 */
    fun input(by: String, value: String?, text: String, index: Int, clearFirst: Boolean, submit: Boolean): String =
        prelude() + """
(function () {
  var by = ${jsStr(by)}, val = ${jsStr(value)};
  var e = MCP.findEl(by, val, $index);
  if (!e) return JSON.stringify({ ok: false, err: 'not-found' });
  var tag = String(e.tagName || '').toLowerCase();
  if (tag === 'select' || String(e.type || '').toLowerCase() === 'checkbox' || String(e.type || '').toLowerCase() === 'radio') {
    var s = MCP.setValue(e, ${jsStr(text)}, $clearFirst);
    if (s === 'no-such-option') {
      var opts = [];
      for (var i = 0; i < (e.options || []).length; i++) opts.push(String(e.options[i].text || '').trim());
      return JSON.stringify({ ok: false, err: 'no-such-option', options: opts.slice(0, 30) });
    }
  } else {
    MCP.setValue(e, ${jsStr(text)}, $clearFirst);
  }
  var how = '';
  if ($submit) how = MCP.submit(e);
  return JSON.stringify({ ok: true, how: how, target: JSON.parse(MCP.describe(e)), value: (function () { try { return String(e.value === undefined ? '' : e.value).slice(0, 80); } catch (x) { return ''; } })() });
})()
"""

    /** 滚动：to = top / bottom / element / selector / y / page。 */
    fun scroll(to: String, value: String?): String = prelude() + """
(function () {
  var to = ${jsStr(to)}, val = ${jsStr(value)};
  var sc = MCP.scroller();
  function bottom() {
    var h = Math.max(document.documentElement.scrollHeight || 0, document.body ? document.body.scrollHeight : 0);
    window.scrollTo(0, h);
    if (sc) sc.scrollTop = sc.scrollHeight;
  }
  if (to === 'bottom') { bottom(); }
  else if (to === 'top') { window.scrollTo(0, 0); if (sc) sc.scrollTop = 0; }
  else if (to === 'y') { window.scrollTo(0, parseFloat(val) || 0); }
  else if (to === 'page') {
    var step = (window.innerHeight || 800) * 0.9 * (parseFloat(val) || 1);
    window.scrollBy(0, step);
    if (sc && Math.abs(window.scrollY) < 1) sc.scrollTop = sc.scrollTop + step;
  } else if (to === 'element') {
    var e = MCP.byIndex(parseInt(val, 10) || 0) || MCP.findEl('selector', val, 0) || MCP.byText(val, 0);
    if (!e) return JSON.stringify({ ok: false, err: 'not-found' });
    try { e.scrollIntoView({ block: 'center' }); } catch (x) {}
  } else {
    return JSON.stringify({ ok: false, err: 'bad-mode' });
  }
  // pos：真正在滚的那个容器的位置（有的站点页面本身不滚，滚的是里面那个 div）
  var pos = (sc && Math.abs(window.scrollY) < 1) ? sc.scrollTop : window.scrollY;
  return JSON.stringify({
    ok: true, y: Math.round(window.scrollY || 0), pos: Math.round(pos || 0), scroller: !!sc
  });
})()
"""

    /** 用户自己写的 JS：包一层，拿完成值 + 错误信息。 */
    fun user(js: String): String = """
(function () {
  var code = ${jsStr(js)};
  try {
    var v = eval(code);
    if (v === undefined) v = null;
    return JSON.stringify({ ok: true, v: v });
  } catch (e) {
    return JSON.stringify({ ok: false, err: String((e && e.message) || e) });
  }
})()
"""

    /**
     * CSP 拒绝 eval 时的退路（必应这类站点 `script-src` 里没有 `unsafe-eval`）。
     *
     * 把用户代码**直接当脚本**发过去：`evaluateJavascript` 本身不受页面 CSP 限制，
     * 而 `try { … }` 的完成值就是花括号里最后那个表达式的值，语义正好一样。
     * 代价是拿不到结构化的错误信息，出错时以 `[js error] …` 这样的字符串回来。
     */
    fun userDirect(js: String): String =
        "try {\n" + js + "\n} catch (e) {\n  (\"[js error] \" + String((e && e.message) || e));\n}\n"

    /** 组装：先把公共函数拼在前面。 */
    private fun prelude(): String = "$PRELUDE\n"

    /** Kotlin 字符串 → JS 字符串字面量（安全转义）。 */
    fun jsStr(value: String?): String {
        val v: JsonElement = anyToJson(value)
        return v.toString()
    }
}

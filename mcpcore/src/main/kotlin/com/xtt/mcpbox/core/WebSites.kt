// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 静态站点托管：把手机里的网页目录直接当网站跑，浏览器打开就能看。
 *
 *   GET /web/<相对主根目录的路径>     只读的静态文件服务（目录会给一张索引页）
 *
 * 设计取舍（都是刻意这么定的）：
 * - **不收令牌**：这是给浏览器用的入口，页面里的 CSS/JS 子资源没法带上 `?token=`，
 *   要鉴权就得引入 cookie 会话，太重。代价是「谁能连上 8720 谁就能看这些文件」——
 *   不想暴露就给服务打开设置里的「仅本机访问」，或者别把私密的东西放在主根目录下。
 * - **路径走同一个 PathSandbox**：`/web/xtt/web/flat-ui/index.html` 就是手机上的
 *   `<主根目录>/xtt/web/flat-ui/index.html`；越权路径（`../../` 之类）直接 403。
 * - **只读**：写文件仍然只能走 `/upload`（那条路要过审批）。
 * - 权限口径：**只有 `fs.read` 的「拒绝」能拦住它**，不看「询问」。一个页面会带几十个
 *   子资源，每个都弹一次审批根本没法用；而这个路由本来就是免令牌的公开只读通道，
 *   拿权限键的「拒绝」当总开关就够了。
 * - 单文件上限 [MAX_FILE_BYTES]：FileBridge 没有分片读取，只能整个读进内存再发。
 */
class WebSites(
    private val config: Config,
    private val sandbox: PathSandbox,
    private val permissions: PermissionStore,
    private val log: EventLog,
    private val bridge: FileBridge
) {

    companion object {
        const val PREFIX = "/web"

        /** App 自己的数据目录名（回收站、内部文件都在这里）。 */
        const val APP_DIR = ".MCPBox"

        /** 单个文件上限（32MB）：超过这个大小请用 /download 取出去。 */
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        /** 这个请求路径归不归静态托管管。 */
        fun owns(path: String): Boolean = path == PREFIX || path.startsWith("$PREFIX/")

        private val HM = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "map" to "application/json; charset=utf-8",
            "webmanifest" to "application/manifest+json; charset=utf-8",
            "xml" to "application/xml; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "wasm" to "application/wasm",
            "txt" to "text/plain; charset=utf-8",
            "md" to "text/plain; charset=utf-8",
            "csv" to "text/plain; charset=utf-8",
            "log" to "text/plain; charset=utf-8",
            "pdf" to "application/pdf",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "apk" to "application/vnd.android.package-archive",
            "zip" to "application/zip"
        )

        fun mimeOf(name: String): String =
            MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }

        private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** 把「相对主根目录的路径」拼成 /web 链接（目录带结尾斜杠）。 */
        fun linkOf(rel: String, dir: Boolean): String {
            val body = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { seg(it) }
            return "$PREFIX/" + body + if (dir && body.isNotEmpty()) "/" else ""
        }
    }

    // ------------------------------------------------------------------ 入口

    fun handle(req: HttpRequest): FileGateway.Result {
        if (req.method != "GET" && req.method != "HEAD") {
            return page(
                405, L("静态站点只支持 GET"),
                L("这里是只读入口。要往手机里写文件请用 /upload（那条路要过审批）。")
            )
        }
        val rel = req.path.removePrefix(PREFIX).trim('/')
        val target = try {
            sandbox.resolve(rel.ifEmpty { "." })
        } catch (e: Exception) {
            return page(403, L("这个路径不在允许访问的范围内"), e.message ?: rel)
        }
        if (!bridge.exists(target)) {
            return page(
                404, L("找不到：%s").format(rel.ifEmpty { "/" }),
                L("检查一下路径和文件名（区分大小写）。")
            )
        }
        val stat = bridge.stat(target) ?: return page(500, L("读不出这个路径的信息"), target.path)
        // App 自己的数据目录（回收站等）不挂在这条免令牌的路由后面
        if (isAppInternal(target)) {
            return page(
                403, L("这个目录不给看"),
                L("它是 App 自己的数据目录（里面是回收站这类东西）。")
            )
        }
        return if (stat.dir) indexPage(rel, target) else serveFile(req, rel, target, stat)
    }

    /** `<主根目录>/.MCPBox` 是 App 的内部目录：删掉的文件（回收站）也在里面。 */
    private fun isAppInternal(file: File): Boolean {
        val app = File(sandbox.primaryRoot(), APP_DIR)
        val path = file.path
        return path == app.path || path.startsWith(app.path + File.separator)
    }

    // ------------------------------------------------------------------ 文件

    private fun serveFile(req: HttpRequest, rel: String, file: File, stat: FsStat): FileGateway.Result {
        if (stat.size > MAX_FILE_BYTES) {
            return page(
                413, L("这个文件太大了，预览只支持 32MB 以内"),
                L("%s 有 %s，请用 /download 取出去看。").format(file.name, humanSize(stat.size))
            )
        }
        // 权限口径：静态托管只看「拒绝」，不看「询问」（理由见类注释）
        val decision = permissions.decide(PermKey.READ, file.path, null)
        if (decision.action == PermAction.DENY) {
            return page(403, L("读取权限被拒绝了"), decision.source)
        }
        val bytes = bridge.readBytes(file, MAX_FILE_BYTES)
            ?: return page(500, L("读不出来：应用没权限，且 root / Shizuku 不可用"), file.path)

        val mime = mimeOf(file.name)
        val etag = "\"${stat.size}-${stat.modified}\""
        val headers = linkedMapOf(
            "Cache-Control" to "no-cache",
            "ETag" to etag,
            "Accept-Ranges" to "bytes"
        )

        // 浏览器第二次来看同一份文件：让缓存直接用本地的
        if (req.header("if-none-match") == etag) {
            return FileGateway.Result(304, mime, ByteArray(0), headers)
        }

        // 视频 / 音频 / PDF 会带 Range 来要一段，浏览器才能拖动进度条
        val range = parseRange(req.header("range"), bytes.size.toLong())
        if (range != null) {
            val (from, to) = range
            val slice = bytes.copyOfRange(from.toInt(), (to + 1).toInt())
            headers["Content-Range"] = "bytes $from-$to/${bytes.size}"
            log.add(
                LogKind.REQUEST, tool = "http_web", path = file.path, client = req.remote, ok = true,
                message = L("网页预览 %s（分段 %s-%s）").format(file.name, from, to)
            )
            return FileGateway.Result(206, mime, slice, headers)
        }

        log.add(
            LogKind.REQUEST, tool = "http_web", path = file.path, client = req.remote, ok = true,
            message = L("网页预览 %s（%s）").format(file.name, humanSize(bytes.size.toLong()))
        )
        return FileGateway.Result(200, mime, bytes, headers)
    }

    /** 只认单段 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`，多段一律当整文件。 */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        if (header == null || !header.startsWith("bytes=") || size <= 0) return null
        val spec = header.removePrefix("bytes=").substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        val start = if (startRaw.isEmpty()) {
            size - (endRaw.toLongOrNull() ?: return null)
        } else {
            startRaw.toLongOrNull() ?: return null
        }
        val end = if (endRaw.isEmpty()) size - 1 else endRaw.toLongOrNull() ?: return null
        if (start < 0 || start >= size || end < start) return null
        return start to end.coerceAtMost(size - 1)
    }

    // ------------------------------------------------------------------ 目录索引

    private fun indexPage(rel: String, dir: File): FileGateway.Result {
        val entries = bridge.listDir(dir)
            ?: return page(500, L("列不出这个目录"), dir.path)
        // 隐藏项不进列表（点对点 URL 还能用，但列表干净一点：主根目录里一堆 .xxx 是噪音）
        val visible = entries.filter { !it.name.startsWith(".") }
        val dirs = visible.filter { it.dir }.sortedBy { it.name.lowercase() }
        val files = visible.filter { !it.dir }.sortedBy { it.name.lowercase() }
        val hasIndex = files.any { it.name.equals("index.html", ignoreCase = true) }

        val crumbs = StringBuilder()
        crumbs.append("<a href=\"").append(PREFIX).append("/\">").append(L("主根目录")).append("</a>")
        val parts = rel.split('/').filter { it.isNotEmpty() }
        var acc = ""
        parts.forEachIndexed { i, p ->
            acc = if (acc.isEmpty()) p else "$acc/$p"
            crumbs.append("<span class=\"sep\">/</span>")
            if (i == parts.size - 1) {
                crumbs.append("<b>").append(htmlEscape(p)).append("</b>")
            } else {
                crumbs.append("<a href=\"").append(linkOf(acc, true)).append("\">")
                    .append(htmlEscape(p)).append("</a>")
            }
        }

        val rows = StringBuilder()
        if (parts.isNotEmpty()) {
            val up = parts.dropLast(1).joinToString("/")
            rows.append(row(linkOf(up, true), "../", "dir", L("上一层目录")))
        }
        if (hasIndex) {
            val idx = if (rel.isEmpty()) "index.html" else "$rel/index.html"
            rows.append(row(linkOf(idx, false), "index.html", "file", L("这个目录的入口页")))
        }
        dirs.forEach { e ->
            val child = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            rows.append(row(linkOf(child, true), e.name + "/", "dir", "", HM.format(Date(e.modified))))
        }
        files.forEach { e ->
            val child = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            rows.append(row(linkOf(child, false), e.name, "file", "", humanSize(e.size), HM.format(Date(e.modified))))
        }
        if (dirs.isEmpty() && files.isEmpty()) {
            rows.append("<div class=\"empty\">").append(L("这个目录是空的")).append("</div>")
        }

        val here = if (rel.isEmpty()) L("主根目录") else dir.path
        val body = """
<header>
  <h1>${L("静态站点")}</h1>
  <div class="muted">${L("MCP 文件盒 · 本地网页预览")}</div>
</header>
<main>
  <div class="card">
    <div class="crumb">$crumbs</div>
    <div class="muted" style="margin-top:6px">${L("允许目录：")}${htmlEscape(config.roots.joinToString(" / "))}</div>
  </div>
  <div class="card">
    $rows
  </div>
  <div class="hint">
    ${L("路径相对「主根目录」，当前目录：")}<code>${htmlEscape(here)}</code><br>
    ${L("点目录进去、点文件直接打开。")}<br>
    ${L("这个入口不收令牌（页面里的 CSS/JS 子资源带不上 token），能连上本机服务的人都能看这些文件。不想暴露就在设置里打开「仅本机访问」。")}
  </div>
</main>
"""
        return FileGateway.Result(200, "text/html; charset=utf-8", shell(body).toByteArray(Charsets.UTF_8))
    }

    /** 一行：目录/文件名 + 可选备注 + 可选大小 + 可选时间。除了 href 全部转义。 */
    private fun row(
        href: String, name: String, cls: String, note: String = "",
        size: String = "", time: String = ""
    ): String = buildString {
        append("<div class=\"row\"><a class=\"").append(cls).append("\" href=\"")
            .append(href).append("\">").append(htmlEscape(name)).append("</a>")
        if (note.isNotEmpty()) append("<span class=\"note\">").append(htmlEscape(note)).append("</span>")
        if (size.isNotEmpty()) append("<span class=\"num\">").append(htmlEscape(size)).append("</span>")
        if (time.isNotEmpty()) append("<span class=\"num\">").append(htmlEscape(time)).append("</span>")
        append("</div>")
    }

    // ------------------------------------------------------------------ 小件

    private fun page(status: Int, title: String, detail: String): FileGateway.Result {
        val body = """
<header><h1>${htmlEscape(title)}</h1><div class="muted">/web</div></header>
<main>
  <div class="card"><div class="muted">${htmlEscape(detail)}</div></div>
  <div class="hint"><a href="$PREFIX/">${L("回到主根目录")}</a></div>
</main>
"""
        return FileGateway.Result(status, "text/html; charset=utf-8", shell(body).toByteArray(Charsets.UTF_8))
    }

    private fun shell(body: String): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${L("静态站点")} · ${ServerMeta.NAME}</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; background:#0b0b0d; color:#e8e6ea;
         font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",system-ui,sans-serif; font-size:14px; }
  header { padding:16px; position:sticky; top:0; background:#0d1117; z-index:5; }
  h1 { font-size:16px; margin:0 0 6px; font-weight:600; }
  .muted { color:#8b949e; font-size:12px; }
  main { padding:16px; max-width:900px; margin:0 auto; }
  .card { background:#1c1c20; border-radius:26px; padding:16px 20px; margin-bottom:10px; }
  .crumb { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:12px; word-break:break-all; line-height:1.9; }
  .crumb a { color:#a8c7fa; text-decoration:none; }
  .crumb .sep { color:#4a4a55; margin:0 5px; }
  .row { display:flex; align-items:center; gap:10px; padding:11px 0; border-bottom:1px solid #232329; }
  .row:last-child { border-bottom:none; }
  .row a { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;
           color:#e8e6ea; text-decoration:none; }
  .row a.dir { color:#a8c7fa; font-weight:500; }
  .row a:hover { text-decoration:underline; }
  .num { font-size:12px; color:#8b949e; font-family:ui-monospace,Menlo,Consolas,monospace; white-space:nowrap; }
  .note { font-size:11px; color:#7cd98f; white-space:nowrap; }
  .empty { color:#8b949e; font-size:13px; padding:10px 0; }
  .hint { color:#8b949e; font-size:12px; line-height:1.8; padding:4px 6px 20px; }
  .hint a { color:#a8c7fa; text-decoration:none; }
  code { font-family:ui-monospace,Menlo,Consolas,monospace; color:#a8c7fa; word-break:break-all; }
</style>
</head>
<body>
$body
</body></html>
"""

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

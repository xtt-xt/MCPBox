// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** Read-only tools: browsing, reading, searching, hashing. */
object ToolsRead {

    private val IMAGE_EXT = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "avif" to "image/avif"
    )

    private fun isHidden(name: String) = name.startsWith(".")

    fun specs(): List<ToolSpec> = listOf(
        listDir(), tree(), info(), read(), readImage(), search(), hash(),
        storageInfo(), deviceInfo(), serverInfo()
    )

    // ---------------------------------------------------------- device_info
    private fun deviceInfo() = ToolSpec(
        name = "get_device_info",
        title = "设备信息",
        description = "查看手机型号、系统版本、电量、存储占用等设备信息。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "查看设备信息")
        val info = ctx.host?.deviceInfo()
            ?: ctx.fail("当前运行环境拿不到设备信息（桌面端测试模式）")
        val sb = StringBuilder()
        sb.append("设备信息\n")
        info.forEach { (k, v) -> sb.append("  ").append(k).append("：").append(v).append('\n') }
        ToolResult(sb.toString().trimEnd())
    }

    // ---------------------------------------------------------------- list_dir
    private fun listDir() = ToolSpec(
        name = "list_dir",
        title = "列出目录",
        description = "列出目录下的文件和子目录（名称、类型、大小、修改时间）。path 留空表示默认根目录。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("目录路径，可省略（默认根目录）；支持 ~ 开头表示根目录"),
                "showHidden" to Schema.bool("是否显示以点开头的隐藏文件，默认 false", false),
                "sort" to Schema.str("排序方式", "name", listOf("name", "size", "time")),
                "limit" to Schema.int("最多返回条目数", 500, 1, 5000)
            )
        )
    ) { ctx ->
        val dir = ctx.path(mustExist = false)
        val dirStat = ctx.bridge.stat(dir)
        if (dirStat != null && !dirStat.dir) ctx.fail("不是目录：${dir.path}")
        if (dirStat == null) ctx.fail("目录不存在，或者读不到：${dir.path}")
        val showHidden = ctx.args.boolOr("showHidden", false)
        val limit = ctx.args.intOr("limit", 500).coerceIn(1, 5000)
        val sort = ctx.args.strOr("sort", "name")
        ctx.guard(PermKey.READ, dir, "列出目录 ${dir.path}")
        val children = ctx.bridge.listDir(dir).orEmpty()
            .filter { showHidden || !isHidden(it.name) }
            .let {
                when (sort) {
                    "size" -> it.sortedByDescending { f -> f.size }
                    "time" -> it.sortedByDescending { f -> f.modified }
                    else -> it.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
                }
            }
        val shown = children.take(limit)
        val sb = StringBuilder()
        sb.append("目录：").append(dir.path)
        if (ctx.sandbox.isPrivatePath(dir)) {
            sb.append("（应用私有目录，经 ").append(ctx.bridge.privilegedLabel).append(" 读取）")
        }
        sb.append('\n')
        sb.append("共 ").append(children.size).append(" 项（目录 ")
            .append(children.count { it.dir }).append("，文件 ")
            .append(children.count { !it.dir }).append("）")
        if (children.size > shown.size) sb.append("，仅显示前 ").append(shown.size).append(" 项")
        sb.append('\n')
        if (children.isEmpty()) sb.append("（空目录，或者应用没有权限读到内容）\n")
        shown.forEach { f ->
            val type = if (f.dir) "DIR " else "FILE"
            val size = if (f.dir) "     -   " else String.format("%10s", ctx.sandbox.humanSize(f.size))
            sb.append('[').append(type).append("] ").append(size).append("  ")
                .append(ctx.sandbox.timeText(f.modified)).append("  ")
            sb.append(f.name)
            if (f.dir) sb.append('/')
            sb.append('\n')
        }
        ToolResult(sb.toString().trimEnd())
    }

    // ------------------------------------------------------------------- tree
    private fun tree() = ToolSpec(
        name = "directory_tree",
        title = "目录树",
        description = "以缩进树的形式展示目录结构，用于快速了解一个文件夹里有什么。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("起始目录，可省略"),
                "depth" to Schema.int("最大深度", 3, 1, 12),
                "maxEntries" to Schema.int("最多条目数", 400, 1, 5000),
                "showHidden" to Schema.bool("是否显示隐藏文件", false)
            )
        )
    ) { ctx ->
        val root = ctx.path(mustExist = true)
        if (!root.isDirectory) ctx.fail("不是目录：${root.path}")
        ctx.guard(PermKey.READ, root, "查看目录树 ${root.path}")
        val depth = ctx.args.intOr("depth", 3).coerceIn(1, 12)
        val maxEntries = ctx.args.intOr("maxEntries", 400).coerceIn(1, 5000)
        val showHidden = ctx.args.boolOr("showHidden", false)
        val sb = StringBuilder(root.path).append('\n')
        var count = 0
        var truncated = false
        fun walk(dir: File, level: Int) {
            if (level > depth || truncated) return
            val kids = (dir.listFiles() ?: emptyArray())
                .filter { showHidden || !isHidden(it.name) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (f in kids) {
                if (count >= maxEntries) { truncated = true; return }
                count++
                sb.append("  ".repeat(level))
                sb.append(if (f.isDirectory) "|- " else "|  ").append(f.name)
                if (f.isDirectory) sb.append('/') else sb.append("  (").append(ctx.sandbox.humanSize(f.length())).append(')')
                sb.append('\n')
                if (f.isDirectory && level < depth) walk(f, level + 1)
            }
        }
        walk(root, 1)
        if (truncated) sb.append("... 条目过多，已截断（可用 maxEntries 调整）\n")
        sb.append("共 ").append(count).append(" 项")
        ToolResult(sb.toString().trimEnd())
    }

    // ------------------------------------------------------------- file_info
    private fun info() = ToolSpec(
        name = "file_info",
        title = "文件信息",
        description = "查看文件或目录的详细信息：大小、修改时间、读写权限、子项数量。",
        perm = PermKey.READ,
        schema = Schema.obj(mapOf("path" to Schema.str("文件或目录路径")), listOf("path"))
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val st = ctx.bridge.stat(f)
            ?: ctx.fail("路径不存在，或者读不到：${f.path}")
        ctx.guard(PermKey.READ, f, "查看信息 ${f.path}")
        val sb = StringBuilder()
        sb.append("路径：").append(f.path).append('\n')
        sb.append("名称：").append(f.name).append('\n')
        sb.append("类型：").append(if (st.dir) "目录" else "文件").append('\n')
        sb.append("大小：").append(ctx.sandbox.humanSize(st.size)).append(" (").append(st.size).append(" 字节)\n")
        sb.append("修改时间：").append(ctx.sandbox.timeText(st.modified)).append('\n')
        f.parentFile?.let { sb.append("所在目录：").append(it.path).append('\n') }
        if (st.bridged) {
            sb.append("读取方式：经 ").append(ctx.bridge.privilegedLabel).append(" 转发（应用自己没权限）\n")
        }
        sb.append("可读：").append(if (st.bridged) "是（通过 shell）" else f.canRead())
            .append("  可写：").append(if (st.bridged) "是（通过 shell）" else f.canWrite()).append('\n')
        if (st.dir) {
            val kids = ctx.bridge.listDir(f).orEmpty()
            sb.append("子项：").append(kids.size).append(" 个（目录 ").append(kids.count { it.dir })
                .append("，文件 ").append(kids.count { !it.dir }).append("）\n")
        } else {
            sb.append("扩展名：").append(f.extension.ifBlank { "（无）" }).append('\n')
            val mime = IMAGE_EXT[f.extension.lowercase()]
            if (mime != null) sb.append("图片类型：").append(mime).append("（可用 read_image 直接查看）\n")
        }
        runCatching { sb.append("可用空间：").append(ctx.sandbox.humanSize(f.absoluteFile.usableSpace)) }
        ToolResult(sb.toString())
    }

    // -------------------------------------------------------------- read_file
    private fun read() = ToolSpec(
        name = "read_file",
        title = "读取文件",
        description = "读取文本文件内容，带行号输出。可用 startLine / lineCount 分段读取大文件。" +
            "二进制文件会报错，图片请用 read_image。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("文件路径"),
                "startLine" to Schema.int("从第几行开始（从 1 计数）", 1, 0, 10_000_000),
                "lineCount" to Schema.int("读取多少行", 300, 1, 5000),
                "maxBytes" to Schema.int("最多读取字节数（防止超大文件）", 2_000_000, 1024, 16_000_000),
                "encoding" to Schema.str("文本编码，默认 UTF-8，可填 GBK 等", "UTF-8"),
                "numbered" to Schema.bool("是否显示行号前缀", true)
            ),
            listOf("path")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val fStat = ctx.bridge.stat(f)
            ?: ctx.fail("文件不存在，或者读不到：${f.path}\n（私有目录要在 设置 → 权限 → 应用私有目录 里开放，并且需要 root 或 Shizuku）")
        if (fStat.dir) ctx.fail("这是目录，请用 list_dir：${f.path}")
        ctx.guard(
            PermKey.READ, f, "读取文件 ${f.name}",
            "大小 " + ctx.sandbox.humanSize(fStat.size), fStat.size
        )
        val maxBytes = ctx.args.intOr("maxBytes", 2_000_000).coerceIn(1024, 16_000_000)
        val bytes = ctx.bridge.readBytes(f, maxBytes.toLong())
            ?: ctx.fail("读不出来：应用自己没权限，而且没有可用的 root / Shizuku")
        if (looksBinary(bytes)) {
            ctx.fail("这是二进制文件（含空字节），无法按文本读取：${f.name}\n如果是图片请用 read_image；其他二进制可用 file_info 查看大小。")
        }
        val charset = runCatching { charset(ctx.args.strOr("encoding", "UTF-8")) }.getOrElse { Charsets.UTF_8 }
        val text = String(bytes, charset)
        val lines = text.split('\n')
        val startLine = ctx.args.intOr("startLine", 1).coerceIn(1, maxOf(lines.size, 1))
        val lineCount = ctx.args.intOr("lineCount", 300).coerceIn(1, 5000)
        val numbered = ctx.args.boolOr("numbered", true)
        val end = minOf(startLine - 1 + lineCount, lines.size)
        val sb = StringBuilder()
        sb.append("文件：").append(f.path).append('\n')
        sb.append("总行数：").append(lines.size)
        if (fStat.size > bytes.size) sb.append("（文件较大，本次只读取了前 ").append(ctx.sandbox.humanSize(bytes.size.toLong())).append("）")
        sb.append("，显示第 ").append(startLine).append(" ~ ").append(end).append(" 行\n")
        sb.append("----\n")
        for (i in (startLine - 1) until end) {
            if (numbered) sb.append(String.format("%5d| ", i + 1))
            sb.append(lines[i])
            sb.append('\n')
        }
        if (end < lines.size) sb.append("... 还有 ").append(lines.size - end).append(" 行（可加大 lineCount 或调整 startLine）\n")
        ToolResult(sb.toString().trimEnd())
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private fun charset(name: String): java.nio.charset.Charset = when (name.uppercase()) {
        "UTF-8", "UTF8" -> Charsets.UTF_8
        "GBK", "GB2312", "GB18030" -> java.nio.charset.Charset.forName("GBK")
        else -> java.nio.charset.Charset.forName(name)
    }

    // ------------------------------------------------------------- read_image
    private fun readImage() = ToolSpec(
        name = "read_image",
        title = "查看图片",
        description = "把手机上的图片读出来给模型看（返回图片内容，支持 png/jpg/gif/webp/bmp）。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("图片文件路径"),
                "maxBytes" to Schema.int("最大允许的图片大小", 8_000_000, 1024, 32_000_000)
            ),
            listOf("path")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val st = ctx.bridge.stat(f) ?: ctx.fail("图片不存在，或者读不到：${f.path}")
        if (st.dir) ctx.fail("这是目录：${f.path}")
        val ext = f.extension.lowercase()
        val mime = IMAGE_EXT[ext] ?: ctx.fail("不支持的图片格式：.${f.extension}（支持 png/jpg/jpeg/gif/webp/bmp）")
        val maxBytes = ctx.args.intOr("maxBytes", 8_000_000)
        if (st.size > maxBytes) ctx.fail("图片过大：${ctx.sandbox.humanSize(st.size)}，超过上限")
        ctx.guard(PermKey.READ, f, "查看图片 ${f.name}", ctx.sandbox.humanSize(st.size), st.size, mime)
        val raw = ctx.bridge.readBytes(f, maxBytes.toLong())
            ?: ctx.fail("读不出来：应用自己没权限，而且没有可用的 root / Shizuku")
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        ToolResult(
            text = "图片：${f.path}\n大小：${ctx.sandbox.humanSize(st.size)}（$mime）",
            extraContent = listOf(jo("type" to "image", "data" to b64, "mimeType" to mime))
        )
    }

    // ----------------------------------------------------------- search_files
    private fun search() = ToolSpec(
        name = "search_files",
        title = "搜索文件",
        description = "按文件名（通配符或正则，可写 re: 前缀）或文件内容（正则）搜索。适合找「某个文件在哪」" +
            "或「哪个文件里有这段文字」。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("搜索起始目录，默认根目录"),
                "name" to Schema.str("文件名匹配，如 *.txt、报告*、re:^IMG_\\d+\\.jpg$"),
                "content" to Schema.str("文件内容正则匹配，如 TODO、import .*kotlin"),
                "caseSensitive" to Schema.bool("区分大小写", false),
                "maxDepth" to Schema.int("最大搜索深度", 8, 1, 30),
                "maxResults" to Schema.int("最多返回条数", 100, 1, 1000),
                "maxFileSize" to Schema.int("内容搜索时单个文件最大字节", 2_000_000, 1024, 64_000_000),
                "includeDirs" to Schema.bool("是否把匹配的目录也列出来", false),
                "showHidden" to Schema.bool("是否搜索隐藏文件", false)
            )
        )
    ) { ctx ->
        val root = ctx.path(mustExist = true)
        if (!root.isDirectory) ctx.fail("搜索起点必须是目录：${root.path}")
        ctx.guard(PermKey.READ, root, "搜索目录 ${root.path}")
        val namePattern = ctx.args.str("name")
        val contentPattern = ctx.args.str("content")
        if (namePattern == null && contentPattern == null) ctx.fail("至少要提供 name 或 content 之一")
        val caseSensitive = ctx.args.boolOr("caseSensitive", false)
        val nameRegex = namePattern?.let { globToRegex(it, caseSensitive) }
        val contentRegex = contentPattern?.let {
            runCatching { Regex(it, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }
                .getOrElse { e -> ctx.fail("内容正则不合法：${e.message}") }
        }
        val maxDepth = ctx.args.intOr("maxDepth", 8).coerceIn(1, 30)
        val maxResults = ctx.args.intOr("maxResults", 100).coerceIn(1, 1000)
        val maxFileSize = ctx.args.intOr("maxFileSize", 2_000_000)
        val includeDirs = ctx.args.boolOr("includeDirs", false)
        val showHidden = ctx.args.boolOr("showHidden", false)
        val trashPath = ctx.sandbox.trashDir().path

        val hits = ArrayList<String>()
        var scanned = 0
        var truncated = false

        fun matchesName(f: File) = nameRegex?.containsMatchIn(f.name) ?: true

        fun walk(dir: File, depth: Int) {
            if (truncated || depth > maxDepth) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (truncated) return
                if (!showHidden && isHidden(f.name)) continue
                if (f.path == trashPath || f.path.startsWith("$trashPath/")) continue
                scanned++
                if (f.isDirectory) {
                    if (includeDirs && nameRegex != null && matchesName(f) && contentRegex == null) {
                        hits.add("[DIR ] ${f.path}")
                    }
                    walk(f, depth + 1)
                } else {
                    val nameOk = nameRegex == null || matchesName(f)
                    if (contentRegex == null) {
                        if (nameOk) hits.add("${f.path}  (${ctx.sandbox.humanSize(f.length())})")
                    } else if (nameOk && f.length() <= maxFileSize) {
                        val text = runCatching {
                            val bytes = f.inputStream().use { it.readBytes() }
                            if (looksBinary(bytes)) null else String(bytes, Charsets.UTF_8)
                        }.getOrNull()
                        if (text != null) {
                            text.split('\n').forEachIndexed { idx, line ->
                                if (contentRegex.containsMatchIn(line)) {
                                    hits.add("${f.path}:${idx + 1}: ${line.trim().take(240)}")
                                }
                            }
                        }
                    }
                }
                if (hits.size >= maxResults) { truncated = true; return }
            }
        }
        walk(root, 1)
        val sb = StringBuilder()
        sb.append("搜索目录：").append(root.path).append('\n')
        if (namePattern != null) sb.append("文件名条件：").append(namePattern).append('\n')
        if (contentPattern != null) sb.append("内容条件：").append(contentPattern).append('\n')
        sb.append("扫描条目：").append(scanned).append("，命中：").append(hits.size)
        if (truncated) sb.append("（已达上限，结果可能不完整）")
        sb.append("\n----\n")
        if (hits.isEmpty()) sb.append("没有找到匹配项") else sb.append(hits.joinToString("\n"))
        ToolResult(sb.toString())
    }

    private fun globToRegex(pattern: String, caseSensitive: Boolean): Regex {
        if (pattern.startsWith("re:")) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(pattern.removePrefix("re:"), opts) }.getOrElse { Regex(Regex.escape(pattern)) }
        }
        val sb = StringBuilder()
        pattern.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(sb.toString(), opts)
    }

    // ------------------------------------------------------------- file_hash
    private fun hash() = ToolSpec(
        name = "file_hash",
        title = "文件校验值",
        description = "计算文件的 md5 / sha1 / sha256，用于比较两个文件是否相同。",
        perm = PermKey.READ,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("文件路径"),
                "algorithm" to Schema.str("算法", "sha256", listOf("md5", "sha1", "sha256"))
            ),
            listOf("path")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = true)
        if (f.isDirectory) ctx.fail("目录不支持计算校验值：${f.path}")
        ctx.guard(PermKey.READ, f, "计算校验值 ${f.name}")
        val algo = ctx.args.strOr("algorithm", "sha256")
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        ToolResult("文件：${f.path}\n算法：${algo.uppercase()}\n校验值：$hex")
    }

    // ---------------------------------------------------------- storage_info
    private fun storageInfo() = ToolSpec(
        name = "storage_info",
        title = "存储空间",
        description = "查看当前允许访问的根目录、以及各分区的总空间/剩余空间。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "查看存储空间")
        val sb = StringBuilder()
        sb.append("允许访问的根目录（").append(ctx.config.roots.size).append(" 个）：\n")
        ctx.config.roots.forEachIndexed { i, r ->
            val f = File(r)
            sb.append("  ${i + 1}. ").append(r)
                .append(if (f.exists()) "" else "（不存在）")
                .append('\n')
        }
        sb.append("不限制目录：").append(if (ctx.config.fullAccess) "是" else "否").append('\n')
        val roots = (ctx.config.roots.map { File(it) } + File("/")).distinctBy { runCatching { it.absolutePath }.getOrNull() }
        sb.append("\n存储分区：\n")
        roots.forEach { f ->
            runCatching {
                sb.append("  ").append(f.absolutePath).append("：总 ")
                    .append(ctx.sandbox.humanSize(f.totalSpace))
                    .append("，已用 ").append(ctx.sandbox.humanSize(f.totalSpace - f.freeSpace))
                    .append("，可用 ").append(ctx.sandbox.humanSize(f.usableSpace)).append('\n')
            }
        }
        ToolResult(sb.toString())
    }

    // ----------------------------------------------------------- server_info
    private fun serverInfo() = ToolSpec(
        name = "server_info",
        title = "服务器信息",
        description = "查看这台手机上 MCP 文件服务器的状态：版本、运行时间、根目录、权限设置、可用的操作。" +
            "AI 在开始操作前可以先调用它了解环境。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "查看服务器信息")
        val sb = StringBuilder()
        sb.append("MCP 手机文件服务器\n")
        sb.append("版本：").append(ServerMeta.version).append('\n')
        sb.append("设备：").append(ServerMeta.deviceLabel).append('\n')
        sb.append("端口：").append(ctx.config.port).append('\n')
        sb.append("运行时间：").append(ServerMeta.uptimeText()).append('\n')
        sb.append("累计请求：").append(ctx.log.stats().total)
            .append("（允许 ").append(ctx.log.stats().ok)
            .append(" / 失败 ").append(ctx.log.stats().failed)
            .append(" / 审批 ").append(ctx.log.stats().approvals)
            .append(" / 拒绝 ").append(ctx.log.stats().denied).append("）\n")
        sb.append("\n根目录：\n")
        ctx.config.roots.forEach { sb.append("  ").append(it).append('\n') }
        sb.append("不限制目录：").append(if (ctx.config.fullAccess) "是" else "否").append('\n')
        sb.append("只读模式：").append(if (ctx.config.readOnly) "已开启（所有写/删会被拒绝）" else "关闭").append('\n')
        sb.append("回收站：").append(if (ctx.config.trashEnabled) "开启（删除会先进回收站）" else "关闭（删除即彻底删除）").append('\n')
        sb.append("\n权限开关：\n")
        PermKey.entries.forEach { k ->
            val action = ctx.permissions.decide(k, null).action
            sb.append("  ").append(k.title).append("（").append(k.id).append("）：").append(action.label)
                .append(when (action) {
                    PermAction.ASK -> "（每次操作会弹出审批窗口）"
                    PermAction.DENY -> "（相关操作会被直接拒绝）"
                    PermAction.ALLOW -> ""
                }).append('\n')
        }
        if (ctx.permissions.pathRules().isNotEmpty()) {
            sb.append("\n路径规则：\n")
            ctx.permissions.pathRules().forEach {
                sb.append("  ").append(if (it.perm == "*") "全部权限" else it.perm)
                    .append(" @ ").append(it.path.ifBlank { "（未填）" })
                    .append(" → ").append(it.actionEnum.label).append('\n')
            }
        }
        sb.append("\n提示：涉及写入/删除的操作会实时弹窗询问手机主人，被拒绝时请勿反复重试。")
        ToolResult(sb.toString())
    }
}

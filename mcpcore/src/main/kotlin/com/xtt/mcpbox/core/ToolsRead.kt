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
        ctx.guard(PermKey.SYSTEM, null, L("查看设备信息"))
        val info = ctx.host?.deviceInfo()
            ?: ctx.fail(L("当前运行环境拿不到设备信息（桌面端测试模式）"))
        val sb = StringBuilder()
        sb.append(L("设备信息\n"))
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
        if (dirStat != null && !dirStat.dir) ctx.fail(L("不是目录：%s").format(dir.path))
        if (dirStat == null) ctx.fail(L("目录不存在，或者读不到：%s").format(dir.path))
        val showHidden = ctx.args.boolOr("showHidden", false)
        val limit = ctx.args.intOr("limit", 500).coerceIn(1, 5000)
        val sort = ctx.args.strOr("sort", "name")
        ctx.guard(PermKey.READ, dir, L("列出目录 %s").format(dir.path))
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
        sb.append(L("目录：")).append(dir.path)
        if (ctx.sandbox.isPrivatePath(dir)) {
            sb.append(L("（应用私有目录，经 %s 读取）").format(ctx.bridge.privilegedLabel))
        }
        sb.append('\n')
        sb.append(
            L("共 %s 项（目录 %s，文件 %s）").format(
                children.size,
                children.count { it.dir },
                children.count { !it.dir }
            )
        )
        if (children.size > shown.size) sb.append(L("，仅显示前 %s 项").format(shown.size))
        sb.append('\n')
        if (children.isEmpty()) sb.append(L("（空目录，或者应用没有权限读到内容）\n"))
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
        if (!root.isDirectory) ctx.fail(L("不是目录：%s").format(root.path))
        ctx.guard(PermKey.READ, root, L("查看目录树 %s").format(root.path))
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
        if (truncated) sb.append(L("... 条目过多，已截断（可用 maxEntries 调整）\n"))
        sb.append(L("共 %s 项").format(count))
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
            ?: ctx.fail(L("路径不存在，或者读不到：%s").format(f.path))
        ctx.guard(PermKey.READ, f, L("查看信息 %s").format(f.path))
        val sb = StringBuilder()
        sb.append(L("路径：")).append(f.path).append('\n')
        sb.append(L("名称：")).append(f.name).append('\n')
        sb.append(L("类型：")).append(if (st.dir) L("目录") else L("文件")).append('\n')
        sb.append(L("大小：")).append(ctx.sandbox.humanSize(st.size))
            .append(L(" (%s 字节)\n").format(st.size))
        sb.append(L("修改时间：")).append(ctx.sandbox.timeText(st.modified)).append('\n')
        f.parentFile?.let { sb.append(L("所在目录：")).append(it.path).append('\n') }
        if (st.bridged) {
            sb.append(L("读取方式：经 %s 转发（应用自己没权限）\n").format(ctx.bridge.privilegedLabel))
        }
        val yes = L("是（通过 shell）")
        sb.append(L("可读：")).append(if (st.bridged) yes else f.canRead())
            .append(L("  可写：")).append(if (st.bridged) yes else f.canWrite()).append('\n')
        if (st.dir) {
            val kids = ctx.bridge.listDir(f).orEmpty()
            sb.append(
                L("子项：%s 个（目录 %s，文件 %s）\n").format(
                    kids.size, kids.count { it.dir }, kids.count { !it.dir }
                )
            )
        } else {
            sb.append(L("扩展名：")).append(f.extension.ifBlank { L("（无）") }).append('\n')
            val mime = IMAGE_EXT[f.extension.lowercase()]
            if (mime != null) sb.append(L("图片类型：%s（可用 read_image 直接查看）\n").format(mime))
        }
        runCatching { sb.append(L("可用空间：")).append(ctx.sandbox.humanSize(f.absoluteFile.usableSpace)) }
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
            ?: ctx.fail(
                L("文件不存在，或者读不到：%s\n(私有目录要在 设置 → 权限 → 应用私有目录 里开放，并且需要 root 或 Shizuku)").format(f.path)
            )
        if (fStat.dir) ctx.fail(L("这是目录，请用 list_dir：%s").format(f.path))
        ctx.guard(
            PermKey.READ, f, L("读取文件 %s").format(f.name),
            L("大小 %s").format(ctx.sandbox.humanSize(fStat.size)), fStat.size
        )
        val maxBytes = ctx.args.intOr("maxBytes", 2_000_000).coerceIn(1024, 16_000_000)
        val bytes = ctx.bridge.readBytes(f, maxBytes.toLong())
            ?: ctx.fail(L("读不出来：应用自己没权限，而且没有可用的 root / Shizuku"))
        if (looksBinary(bytes)) {
            ctx.fail(
                L("这是二进制文件（含空字节），无法按文本读取：%s\n如果是图片请用 read_image；其他二进制可用 file_info 查看大小。")
                    .format(f.name)
            )
        }
        val charset = runCatching { charset(ctx.args.strOr("encoding", "UTF-8")) }.getOrElse { Charsets.UTF_8 }
        val text = String(bytes, charset)
        val lines = text.split('\n')
        val startLine = ctx.args.intOr("startLine", 1).coerceIn(1, maxOf(lines.size, 1))
        val lineCount = ctx.args.intOr("lineCount", 300).coerceIn(1, 5000)
        val numbered = ctx.args.boolOr("numbered", true)
        val end = minOf(startLine - 1 + lineCount, lines.size)
        val sb = StringBuilder()
        sb.append(L("文件：")).append(f.path).append('\n')
        sb.append(L("总行数：")).append(lines.size)
        if (fStat.size > bytes.size) {
            sb.append(L("（文件较大，本次只读取了前 %s）").format(ctx.sandbox.humanSize(bytes.size.toLong())))
        }
        sb.append(L("，显示第 %s ~ %s 行\n").format(startLine, end))
        sb.append("----\n")
        for (i in (startLine - 1) until end) {
            if (numbered) sb.append(String.format("%5d| ", i + 1))
            sb.append(lines[i])
            sb.append('\n')
        }
        if (end < lines.size) {
            sb.append(L("... 还有 %s 行（可加大 lineCount 或调整 startLine）\n").format(lines.size - end))
        }
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
        val st = ctx.bridge.stat(f) ?: ctx.fail(L("图片不存在，或者读不到：%s").format(f.path))
        if (st.dir) ctx.fail(L("这是目录：%s").format(f.path))
        val ext = f.extension.lowercase()
        val mime = IMAGE_EXT[ext]
            ?: ctx.fail(L("不支持的图片格式：.%s（支持 png/jpg/jpeg/gif/webp/bmp）").format(f.extension))
        val maxBytes = ctx.args.intOr("maxBytes", 8_000_000)
        if (st.size > maxBytes) {
            ctx.fail(L("图片过大：%s，超过上限").format(ctx.sandbox.humanSize(st.size)))
        }
        ctx.guard(PermKey.READ, f, L("查看图片 %s").format(f.name), ctx.sandbox.humanSize(st.size), st.size, mime)
        val raw = ctx.bridge.readBytes(f, maxBytes.toLong())
            ?: ctx.fail(L("读不出来：应用自己没权限，而且没有可用的 root / Shizuku"))
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        ToolResult(
            text = L("图片：%s\n大小：%s（%s）")
                .format(f.path, ctx.sandbox.humanSize(st.size), mime),
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
        if (!root.isDirectory) ctx.fail(L("搜索起点必须是目录：%s").format(root.path))
        ctx.guard(PermKey.READ, root, L("搜索目录 %s").format(root.path))
        val namePattern = ctx.args.str("name")
        val contentPattern = ctx.args.str("content")
        if (namePattern == null && contentPattern == null) ctx.fail(L("至少要提供 name 或 content 之一"))
        val caseSensitive = ctx.args.boolOr("caseSensitive", false)
        val nameRegex = namePattern?.let { globToRegex(it, caseSensitive) }
        val contentRegex = contentPattern?.let {
            runCatching { Regex(it, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }
                .getOrElse { e -> ctx.fail(L("内容正则不合法：%s").format(e.message)) }
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
        sb.append(L("搜索目录：")).append(root.path).append('\n')
        if (namePattern != null) sb.append(L("文件名条件：")).append(namePattern).append('\n')
        if (contentPattern != null) sb.append(L("内容条件：")).append(contentPattern).append('\n')
        sb.append(L("扫描条目：%s，命中：%s").format(scanned, hits.size))
        if (truncated) sb.append(L("（已达上限，结果可能不完整）"))
        sb.append("\n----\n")
        if (hits.isEmpty()) sb.append(L("没有找到匹配项")) else sb.append(hits.joinToString("\n"))
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
        if (f.isDirectory) ctx.fail(L("目录不支持计算校验值：%s").format(f.path))
        ctx.guard(PermKey.READ, f, L("计算校验值 %s").format(f.name))
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
        ToolResult(L("文件：%s\n算法：%s\n校验值：%s").format(f.path, algo.uppercase(), hex))
    }

    // ---------------------------------------------------------- storage_info
    private fun storageInfo() = ToolSpec(
        name = "storage_info",
        title = "存储空间",
        description = "查看当前允许访问的根目录、以及各分区的总空间/剩余空间。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, L("查看存储空间"))
        val sb = StringBuilder()
        sb.append(L("允许访问的根目录（%s 个）：\n").format(ctx.config.roots.size))
        ctx.config.roots.forEachIndexed { i, r ->
            val f = File(r)
            sb.append("  ${i + 1}. ").append(r)
                .append(if (f.exists()) "" else L("（不存在）"))
                .append('\n')
        }
        sb.append(L("不限制目录：")).append(if (ctx.config.fullAccess) L("是") else L("否")).append('\n')
        val roots = (ctx.config.roots.map { File(it) } + File("/")).distinctBy { runCatching { it.absolutePath }.getOrNull() }
        sb.append(L("\n存储分区：\n"))
        roots.forEach { f ->
            runCatching {
                sb.append("  ").append(f.absolutePath)
                    .append(L("：总 %s，已用 %s，可用 %s\n").format(
                        ctx.sandbox.humanSize(f.totalSpace),
                        ctx.sandbox.humanSize(f.totalSpace - f.freeSpace),
                        ctx.sandbox.humanSize(f.usableSpace)
                    ))
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
        ctx.guard(PermKey.SYSTEM, null, L("查看服务器信息"))
        val sb = StringBuilder()
        sb.append(L("MCP 手机文件服务器\n"))
        sb.append(L("版本：")).append(ServerMeta.fullVersion).append('（').append(ServerMeta.NAME).append('）').append('\n')
        sb.append(L("设备：")).append(ServerMeta.deviceLabel).append('\n')
        sb.append(L("端口：")).append(ctx.config.port).append('\n')
        sb.append(L("运行时间：")).append(ServerMeta.uptimeText()).append('\n')
        val st = ctx.log.stats()
        sb.append(L("累计请求：")).append(st.total)
            .append(L("（允许 %s / 失败 %s / 审批 %s / 拒绝 %s）\n")
                .format(st.ok, st.failed, st.approvals, st.denied))
        sb.append(L("\n根目录：\n"))
        ctx.config.roots.forEach { sb.append("  ").append(it).append('\n') }
        sb.append(L("不限制目录：")).append(if (ctx.config.fullAccess) L("是") else L("否")).append('\n')
        sb.append(L("只读模式："))
            .append(if (ctx.config.readOnly) L("已开启（所有写/删会被拒绝）") else L("关闭")).append('\n')
        sb.append(L("回收站："))
            .append(
                if (ctx.config.trashEnabled) L("开启（删除会先进回收站）")
                else L("关闭（删除即彻底删除）")
            ).append('\n')
        sb.append(L("\n权限开关：\n"))
        PermKey.entries.forEach { k ->
            val action = ctx.permissions.decide(k, null).action
            sb.append("  ").append(L(k.title)).append("（").append(k.id).append("）：")
                .append(L(action.label))
                .append(when (action) {
                    PermAction.ASK -> L("（每次操作会弹出审批窗口）")
                    PermAction.DENY -> L("（相关操作会被直接拒绝）")
                    PermAction.ALLOW -> ""
                }).append('\n')
        }
        if (ctx.permissions.pathRules().isNotEmpty()) {
            sb.append(L("\n路径规则：\n"))
            ctx.permissions.pathRules().forEach {
                sb.append("  ").append(if (it.perm == "*") L("全部权限") else it.perm)
                    .append(" @ ").append(it.path.ifBlank { L("（未填）") })
                    .append(" → ").append(L(it.actionEnum.label)).append('\n')
            }
        }
        sb.append(L("\n提示：涉及写入/删除的操作会实时弹窗询问手机主人，被拒绝时请勿反复重试。"))
        ToolResult(sb.toString())
    }
}

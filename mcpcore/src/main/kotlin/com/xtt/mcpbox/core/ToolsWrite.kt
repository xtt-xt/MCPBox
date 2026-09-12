// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.File

/** Writing tools: create, edit, copy, move, delete, recycle bin. */
object ToolsWrite {

    fun specs(): List<ToolSpec> = listOf(
        writeFile(), editFile(), makeDir(), copyPath(), movePath(),
        deletePath(), listTrash(), restoreTrash(), emptyTrash(), notifyUser()
    )

    // ------------------------------------------------------------- write_file
    private fun writeFile() = ToolSpec(
        name = "write_file",
        title = "写入文件",
        description = "创建或写入文件（会自动创建上级目录）。mode=overwrite 覆盖，append 追加，create_new 只在文件不存在时创建。" +
            "content 也可以用 base64 编码来写二进制文件。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("目标文件路径"),
                "content" to Schema.str("要写入的内容"),
                "mode" to Schema.str("写入方式", "overwrite", listOf("overwrite", "append", "create_new")),
                "encoding" to Schema.str("内容编码", "utf8", listOf("utf8", "base64")),
                "createDirs" to Schema.bool("自动创建上级目录", true)
            ),
            listOf("path", "content")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val existed = ctx.bridge.stat(f)
        if (existed?.dir == true) ctx.fail("目标是一个目录：${f.path}")
        val content = ctx.args.str("content") ?: ""
        val mode = ctx.args.strOr("mode", "overwrite")
        val encoding = ctx.args.strOr("encoding", "utf8").lowercase()
        val bytes = if (encoding == "base64") {
            runCatching { java.util.Base64.getDecoder().decode(content) }
                .getOrElse { ctx.fail("base64 内容不合法") }
        } else content.toByteArray(Charsets.UTF_8)
        val exists = existed != null
        if (mode == "create_new" && exists) ctx.fail("文件已存在：${f.path}")
        val verb = when {
            exists && mode == "append" -> "追加"
            exists -> "覆盖"
            else -> "新建"
        }
        ctx.guard(
            PermKey.WRITE, f,
            "$verb 文件 ${f.name}（${ctx.sandbox.humanSize(bytes.size.toLong())}）",
            "目标：${f.path}\n内容预览：${preview(content)}",
            bytes.size.toLong()
        )
        try {
            ctx.sandbox.assertWritable(f)
        } catch (e: Exception) {
            ctx.fail(e.message ?: "当前模式下不允许写入")
        }
        val parent = f.parentFile
        if (ctx.args.boolOr("createDirs", true)) {
            parent?.let { ctx.bridge.mkdirs(it) }
        } else if (parent != null && !parent.exists()) {
            ctx.fail("上级目录不存在：${parent.path}（可设置 createDirs=true）")
        }
        val payload = if (mode == "append" && exists) {
            (ctx.bridge.readBytes(f) ?: ByteArray(0)) + bytes
        } else {
            bytes
        }
        if (!ctx.bridge.writeBytes(f, payload)) {
            ctx.fail("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku\n目标：${f.path}")
        }
        val total = ctx.bridge.stat(f)?.size ?: payload.size.toLong()
        ToolResult("${verb}成功：${f.path}\n本次写入 ${ctx.sandbox.humanSize(bytes.size.toLong())}，文件当前 ${ctx.sandbox.humanSize(total)}")
    }

    private fun preview(content: String): String {
        val one = content.replace("\n", "\\n")
        return if (one.length > 120) one.take(120) + "..." else one
    }

    // -------------------------------------------------------------- edit_file
    private fun editFile() = ToolSpec(
        name = "edit_file",
        title = "修改文件内容",
        description = "在文本文件里查找并替换内容（类似编辑器的「全部替换」），比整文件重写更安全。" +
            "regex=true 时 oldText 按正则处理。默认要求恰好匹配 1 处，可用 count 控制；replaceAll=true 替换全部。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("文件路径"),
                "oldText" to Schema.str("要被替换的原文（或正则）"),
                "newText" to Schema.str("替换成的内容，可为空字符串"),
                "replaceAll" to Schema.bool("替换所有匹配（默认只替换第一处）", false),
                "regex" to Schema.bool("oldText 按正则表达式处理", false),
                "count" to Schema.int("最多替换多少处", 1, 1, 100000)
            ),
            listOf("path", "oldText")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val st = ctx.bridge.stat(f) ?: ctx.fail("文件不存在，或者读不到：${f.path}")
        if (st.dir) ctx.fail("这是目录：${f.path}")
        val oldText = ctx.args.str("oldText") ?: ctx.fail("缺少 oldText")
        val newText = ctx.args.str("newText") ?: ""
        val useRegex = ctx.args.boolOr("regex", false)
        val replaceAll = ctx.args.boolOr("replaceAll", false)
        val maxCount = ctx.args.intOr("count", 1).coerceIn(1, 100000)
        val text = ctx.bridge.readText(f)
            ?: ctx.fail("读取失败：应用自己没有权限，而且没有可用的 root / Shizuku")
        val occurrences: Int
        val result: String
        if (useRegex) {
            val re = runCatching { Regex(oldText) }.getOrElse { ctx.fail("正则不合法：${it.message}") }
            occurrences = re.findAll(text).count()
            result = if (replaceAll) re.replace(text, newText) else re.replaceFirst(text, newText)
        } else {
            occurrences = countOccurrences(text, oldText)
            result = if (replaceAll) text.replace(oldText, newText)
            else text.replaceFirst(oldText, newText)
        }
        if (occurrences == 0) ctx.fail("没有找到要替换的内容（oldText 在文件中不存在）")
        val willReplace = if (replaceAll) occurrences else 1
        ctx.guard(
            PermKey.WRITE, f,
            "修改文件 ${f.name}（替换 $willReplace 处）",
            "文件：${f.path}\n匹配到 $occurrences 处，将替换 $willReplace 处",
            result.length.toLong()
        )
        try {
            ctx.sandbox.assertWritable(f)
        } catch (e: Exception) {
            ctx.fail(e.message ?: "当前模式下不允许写入")
        }
        if (!ctx.bridge.writeBytes(f, result.toByteArray(Charsets.UTF_8))) {
            ctx.fail("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku")
        }
        val newSize = ctx.bridge.stat(f)?.size ?: result.length.toLong()
        ToolResult("修改成功：${f.path}\n匹配 $occurrences 处，已替换 $willReplace 处\n文件大小：${ctx.sandbox.humanSize(newSize)}")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    // -------------------------------------------------------------- make_dir
    private fun makeDir() = ToolSpec(
        name = "make_dir",
        title = "新建目录",
        description = "创建目录（默认连同上级目录一起创建）。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("要创建的目录路径"),
                "parents" to Schema.bool("同时创建上级目录", true)
            ),
            listOf("path")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        ctx.bridge.stat(f)?.let {
            return@ToolSpec if (it.dir) ToolResult("目录已存在：${f.path}")
            else ctx.fail("同名文件已存在：${f.path}")
        }
        ctx.guard(PermKey.WRITE, f, "新建目录 ${f.path}")
        try {
            ctx.sandbox.assertWritable(f)
        } catch (e: Exception) {
            ctx.fail(e.message ?: "当前模式下不允许写入")
        }
        val parents = ctx.args.boolOr("parents", true)
        val ok = if (parents) {
            ctx.bridge.mkdirs(f)
        } else {
            val parent = f.parentFile
            parent != null && (parent.isDirectory || ctx.bridge.stat(parent)?.dir == true) &&
                ctx.bridge.mkdirs(f)
        }
        if (!ok) ctx.fail("创建目录失败（可能是权限不足）：${f.path}")
        ToolResult("已创建目录：${f.path}")
    }

    // ------------------------------------------------------------- copy_path
    private fun copyPath() = ToolSpec(
        name = "copy_path",
        title = "复制文件/目录",
        description = "复制文件或整个目录到目标位置。destination 可以是完整的新路径，也可以是已存在的目录（自动放到里面）。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "source" to Schema.str("源路径"),
                "destination" to Schema.str("目标路径"),
                "overwrite" to Schema.bool("目标已存在时覆盖", false)
            ),
            listOf("source", "destination")
        )
    ) { ctx ->
        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail("缺少 source"), true)
        val srcStat = ctx.bridge.stat(src) ?: ctx.fail("源不存在或读不到：${src.path}")
        val dstRaw = ctx.args.str("destination") ?: ctx.fail("缺少 destination")
        var dst = ctx.sandbox.resolve(dstRaw)
        val dstIsDir = dst.isDirectory || ctx.bridge.stat(dst)?.dir == true
        if (dstIsDir && dst.path != src.path) dst = File(dst, src.name)
        if (dst.path == src.path) ctx.fail("源和目标相同：${src.path}")
        if (isInside(src, dst)) ctx.fail("不能把目录复制到它自己的子目录里：${dst.path}")
        val dstExists = ctx.bridge.stat(dst) != null
        if (dstExists && !ctx.args.boolOr("overwrite", false)) {
            ctx.fail("目标已存在（可设置 overwrite=true 覆盖）：${dst.path}")
        }
        val size = srcStat.size
        ctx.guard(
            PermKey.WRITE, dst,
            "复制 ${src.name} → ${dst.path}",
            "源：${src.path}\n目标：${dst.path}\n大小：${ctx.sandbox.humanSize(size)}",
            size
        )
        try {
            ctx.sandbox.assertWritable(dst)
        } catch (e: Exception) {
            ctx.fail(e.message ?: "当前模式下不允许写入")
        }
        if (dstExists) ctx.bridge.delete(dst, recursive = true)
        val (ok, how) = ctx.bridge.copy(src, dst)
        if (!ok) ctx.fail("复制失败：$how")
        val newSize = ctx.bridge.stat(dst)?.size ?: size
        ToolResult(
            "复制完成\n源：${src.path}\n目标：${dst.path}\n" +
                "大小：${ctx.sandbox.humanSize(newSize)}" + if (how != "本地") "（经 $how 转发）" else ""
        )
    }

    // ------------------------------------------------------------- move_path
    private fun movePath() = ToolSpec(
        name = "move_path",
        title = "移动/重命名",
        description = "移动文件或目录，也可以用来重命名（源和目标在同一目录、只改名字）。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "source" to Schema.str("源路径"),
                "destination" to Schema.str("目标路径（新名字或新位置）"),
                "overwrite" to Schema.bool("目标已存在时覆盖", false)
            ),
            listOf("source", "destination")
        )
    ) { ctx ->
        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail("缺少 source"), true)
        val srcStat = ctx.bridge.stat(src) ?: ctx.fail("源不存在或读不到：${src.path}")
        var dst = ctx.sandbox.resolve(ctx.args.str("destination") ?: ctx.fail("缺少 destination"))
        val dstIsDir = dst.isDirectory || ctx.bridge.stat(dst)?.dir == true
        if (dstIsDir && dst.path != src.path && dst.parentFile?.path != src.parentFile?.path) {
            dst = File(dst, src.name)
        }
        if (dst.path == src.path) ctx.fail("源和目标相同：${src.path}")
        if (isInside(src, dst)) ctx.fail("不能把目录移动到它自己的子目录里：${dst.path}")
        val dstExists = ctx.bridge.stat(dst) != null
        if (dstExists && !ctx.args.boolOr("overwrite", false)) {
            ctx.fail("目标已存在（可设置 overwrite=true 覆盖）：${dst.path}")
        }
        ctx.guard(
            PermKey.WRITE, dst,
            "移动 ${src.name} → ${dst.path}",
            "源：${src.path}\n目标：${dst.path}",
            srcStat.size
        )
        try {
            ctx.sandbox.assertWritable(dst)
            ctx.sandbox.assertWritable(src)
        } catch (e: Exception) {
            ctx.fail(e.message ?: "当前模式下不允许写入")
        }
        if (dstExists) ctx.bridge.delete(dst, recursive = true)
        val (ok, how) = ctx.bridge.move(src, dst)
        if (!ok) ctx.fail("移动失败：${src.path} → ${dst.path}（$how）")
        ToolResult(
            "移动完成\n源：${src.path}\n目标：${dst.path}" +
                if (how != "本地") "\n方式：经 $how 转发" else ""
        )
    }

    // ----------------------------------------------------------- delete_path
    private fun deletePath() = ToolSpec(
        name = "delete_path",
        title = "删除文件/目录",
        description = "删除文件或目录。删除目录必须显式设置 recursive=true。默认会移动到回收站（可在 App 里关闭，" +
            "或用 permanent=true 直接彻底删除）。",
        perm = PermKey.DELETE,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("要删除的路径"),
                "recursive" to Schema.bool("删除非空目录时必须为 true", false),
                "permanent" to Schema.bool("true=彻底删除，false/省略=进回收站（若已开启）", false)
            ),
            listOf("path")
        )
    ) { ctx ->
        val f = ctx.path(mustExist = false)
        val st = ctx.bridge.stat(f) ?: ctx.fail("路径不存在，或者读不到：${f.path}")
        val recursive = ctx.args.boolOr("recursive", false)
        val isPrivate = ctx.sandbox.isPrivatePath(f)
        // 私有目录跨分区搬不进回收站，只能直接删
        val permanent = ctx.args.boolOr("permanent", false) || !ctx.config.trashEnabled || isPrivate
        if (st.dir) {
            val kids = ctx.bridge.listDir(f).orEmpty().size
            if (kids > 0 && !recursive) {
                ctx.fail("目录不是空的（$kids 项），确认要删除请设置 recursive=true：${f.path}")
            }
        }
        val size = st.size
        ctx.guard(
            PermKey.DELETE, f,
            "删除 ${if (st.dir) "目录" else "文件"} ${f.name}",
            "路径：${f.path}\n大小：${ctx.sandbox.humanSize(size)}\n方式：" +
                if (permanent) {
                    "彻底删除" + if (isPrivate) "（私有目录不进回收站）" else ""
                } else {
                    "移动到回收站"
                },
            size
        )
        if (permanent) {
            if (!ctx.bridge.delete(f, recursive)) ctx.fail("删除失败：${f.path}（应用没权限，且 root / Shizuku 不可用）")
            ToolResult("已彻底删除：${f.path}\n释放空间：${ctx.sandbox.humanSize(size)}（不可恢复）")
        } else {
            val entry = ctx.trash.move(f)
            ToolResult(
                "已移入回收站：${f.path}\n回收站 ID：${entry.id}（可用 list_trash 查看、restore_trash 还原）"
            )
        }
    }

    // ----------------------------------------------------------- list_trash
    private fun listTrash() = ToolSpec(
        name = "list_trash",
        title = "查看回收站",
        description = "列出回收站里的文件（AI 误删的文件可以在这里还原）。",
        perm = PermKey.READ,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        val entries = ctx.trash.entries()
        val total = entries.sumOf { it.size }
        val sb = StringBuilder()
        sb.append("回收站：").append(ctx.sandbox.trashDir().path).append('\n')
        sb.append("共 ").append(entries.size).append(" 项，占用 ").append(ctx.sandbox.humanSize(total)).append("\n----\n")
        if (entries.isEmpty()) sb.append("（空）")
        entries.sortedByDescending { it.deletedAt }.forEach {
            sb.append("[").append(it.id).append("] ").append(if (it.isDir) "目录 " else "文件 ")
                .append(it.name).append("  ").append(ctx.sandbox.humanSize(it.size)).append('\n')
            sb.append("      原位置：").append(it.originalPath).append('\n')
            sb.append("      删除时间：").append(ctx.sandbox.timeText(it.deletedAt)).append('\n')
        }
        ToolResult(sb.toString().trimEnd())
    }

    // -------------------------------------------------------- restore_trash
    private fun restoreTrash() = ToolSpec(
        name = "restore_trash",
        title = "还原回收站文件",
        description = "把回收站里的文件还原回原位置，也可以指定新的位置。",
        perm = PermKey.WRITE,
        schema = Schema.obj(
            mapOf(
                "id" to Schema.str("回收站条目的 ID（见 list_trash；也可以用 name 匹配文件名）"),
                "name" to Schema.str("用文件名匹配（ID 不方便时使用）"),
                "destination" to Schema.str("还原到指定路径，省略则回原位置"),
                "overwrite" to Schema.bool("目标已存在时覆盖", false)
            )
        )
    ) { ctx ->
        val all = ctx.trash.entries()
        val id = ctx.args.str("id")
        val name = ctx.args.str("name")
        val entry = when {
            id != null && name != null -> ctx.fail("id 和 name 只能给一个")
            id != null -> all.firstOrNull { it.id == id } ?: ctx.fail("回收站里没有 ID=$id 的条目")
            name != null -> all.firstOrNull { it.name == name }
                ?: all.firstOrNull { it.name.contains(name) }
                ?: ctx.fail("回收站里没有文件名含 $name 的条目")
            else -> {
                if (all.size == 1) all.first() else ctx.fail("请提供 id 或 name（回收站有 ${all.size} 项，可用 list_trash 查看）")
            }
        }
        val dst = ctx.optionalPath("destination")
        ctx.guard(
            PermKey.WRITE, dst ?: File(entry.originalPath),
            "还原回收站条目 ${entry.name}",
            "原位置：${entry.originalPath}\n还原到：${dst?.path ?: entry.originalPath}",
            entry.size
        )
        val restored = ctx.trash.restore(entry, dst, ctx.args.boolOr("overwrite", false))
        ToolResult("已还原：${entry.name} → ${restored.path}")
    }

    // ---------------------------------------------------------- empty_trash
    private fun emptyTrash() = ToolSpec(
        name = "empty_trash",
        title = "清空回收站",
        description = "彻底删除回收站里的所有文件（不可恢复）。",
        perm = PermKey.DELETE,
        schema = Schema.obj(mapOf("confirm" to Schema.bool("必须为 true 才会执行", true)), listOf("confirm"))
    ) { ctx ->
        if (!ctx.args.boolOr("confirm", false)) ctx.fail("请设置 confirm=true 确认清空回收站")
        val entries = ctx.trash.entries()
        if (entries.isEmpty()) return@ToolSpec ToolResult("回收站已经是空的")
        val size = entries.sumOf { it.size }
        ctx.guard(
            PermKey.DELETE, ctx.sandbox.trashDir(),
            "清空回收站（${entries.size} 项，${ctx.sandbox.humanSize(size)}）",
            "将彻底删除，无法恢复",
            size
        )
        val (n, bytes) = ctx.trash.emptyAll()
        ToolResult("回收站已清空：删除 $n 项，释放 ${ctx.sandbox.humanSize(bytes)}")
    }

    // --------------------------------------------------------- notify_user
    private fun notifyUser() = ToolSpec(
        name = "notify_user",
        title = "通知手机主人",
        description = "在手机上弹一条通知，用来告诉用户任务完成或需要他做什么。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(
            mapOf(
                "title" to Schema.str("通知标题", "MCP 文件盒"),
                "message" to Schema.str("通知内容")
            ),
            listOf("message")
        )
    ) { ctx ->
        val message = ctx.args.str("message") ?: ctx.fail("缺少 message")
        val title = ctx.args.str("title") ?: "MCP 文件盒"
        ctx.guard(PermKey.SYSTEM, null, "发送通知给用户")
        val ok = ctx.host?.notify(title, message) ?: false
        ToolResult(if (ok) "已发送通知：$title - $message" else "通知发送失败（可能缺少通知权限）")
    }

    private fun isInside(parent: File, child: File): Boolean {
        val p = parent.path.trimEnd('/')
        return child.path != p && child.path.startsWith("$p/")
    }
}

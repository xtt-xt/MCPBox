// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject

/**
 * 记忆库工具：每条记忆是一个 Markdown 文件，AI 直接整篇读写。
 *
 * 和旧版的区别：不再有「实体 + 观察列表 + 关系边」那套拆分，
 * 写一篇就是写一个文件，改一处就是改一篇。
 */
object ToolsMemory {

    fun specs(store: MemoryStore): List<ToolSpec> = listOf(
        memoryList(store),
        memoryRead(store),
        memoryWrite(store),
        memoryAppend(store),
        memorySearch(store),
        memoryDelete(store),
        memoryStats(store)
    )

    /** 处于旧版未迁移状态时，所有记忆工具都直接报错。 */
    private fun CallContext.requireReady(store: MemoryStore) {
        if (store.hasLegacy) {
            fail(
                L("记忆库还是旧版数据，暂时不能读写。请先在 App 的「设置 → AI 与工具 → 记忆库」里完成迁移。")
            )
        }
    }

    // -------------------------------------------------------------- memory_list

    private fun memoryList(store: MemoryStore) = ToolSpec(
        name = "memory_list",
        title = "记忆：列出",
        description = "列出记忆库里的所有记忆（分区 + 名字 + 一句简介）。" +
            "想先看看记过什么、或者找某篇的名字时用它。folder 可以只看某个分区。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "folder" to Schema.str("只看这个分区（省略 = 全部）")
            )
        )
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, L("查看记忆列表"))
        ctx.requireReady(store)
        val folder = ctx.args.str("folder")
        val list = store.list(folder)
        if (list.isEmpty()) {
            return@ToolSpec ToolResult(
                if (folder.isNullOrBlank()) L("记忆库是空的。")
                else L("分区「%s」下没有记忆。").format(folder)
            )
        }
        val sb = StringBuilder()
        sb.append(L("记忆 %s 条").format(list.size))
        if (!folder.isNullOrBlank()) sb.append(L("（分区：%s）").format(folder))
        sb.append('\n')
        var lastFolder = "\u0000"
        list.forEach { n ->
            if (n.folderLabel != lastFolder) {
                lastFolder = n.folderLabel
                sb.append("\n【").append(lastFolder).append("】\n")
            }
            sb.append("▸ ").append(n.name)
            if (n.type.isNotBlank()) sb.append("  <").append(n.type).append('>')
            if (n.description.isNotBlank()) sb.append(" — ").append(n.description)
            sb.append('\n')
        }
        ToolResult(sb.toString().trimEnd())
    }

    // -------------------------------------------------------------- memory_read

    private fun memoryRead(store: MemoryStore) = ToolSpec(
        name = "memory_read",
        title = "记忆：读取",
        description = "按名字读一篇记忆的全文：简介、类型、正文，以及它的全部关联（关联项会带上对方的简介）。" +
            "关联分「它指向的」和「指向它的」两种。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "name" to Schema.str("记忆名（就是文件名，不带 .md）")
            ),
            required = listOf("name")
        )
    ) { ctx ->
        val name = ctx.str("name")?.trim().orEmpty()
        if (name.isEmpty()) ctx.fail(L("name 不能为空"))
        ctx.guard(PermKey.MEMORY, null, L("读取记忆「%s」").format(name))
        ctx.requireReady(store)
        val note = store.note(name) ?: ctx.fail(L("没有叫「%s」的记忆。").format(name))
        ToolResult(renderNote(store, note))
    }

    // ------------------------------------------------------------- memory_write

    private fun memoryWrite(store: MemoryStore) = ToolSpec(
        name = "memory_write",
        title = "记忆：写入",
        description = "整篇写入一篇记忆（不存在就新建，存在就按给出的字段覆盖）。" +
            "不传的字段保持原样 —— 只想改正文就别传 description / type / links。" +
            "links 是这篇记忆指向别人的关联，每项 {to, type}，type 比如「属于」「依赖」「相关」。" +
            "改名用 oldName 指定旧名字，文件会跟着挪，别人指向它的关联也自动跟上。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "name" to Schema.str("记忆名（就是文件名，不带 .md）"),
                "folder" to Schema.str("分区目录名，省略或留空 = 未分类"),
                "description" to Schema.str("一句话简介，列表里显示的就是它"),
                "type" to Schema.str("类型，自由文本，比如「项目」「人物」「偏好」"),
                "content" to Schema.str("正文，Markdown"),
                "links" to arr(
                    "这篇记忆指向别人的关联",
                    Schema.obj(
                        mapOf(
                            "to" to Schema.str("对方记忆的名字"),
                            "type" to Schema.str("关系类型，比如「属于」「依赖」「相关」")
                        ),
                        listOf("to")
                    )
                ),
                "oldName" to Schema.str("改名时填旧名字（可选）")
            ),
            required = listOf("name")
        )
    ) { ctx ->
        val name = ctx.str("name")?.trim().orEmpty()
        if (name.isEmpty()) ctx.fail(L("name 不能为空"))
        val oldName = ctx.str("oldName")?.trim()
        ctx.guard(
            PermKey.MEMORY, null,
            if (oldName.isNullOrBlank() || oldName == name) L("写入记忆「%s」").format(name)
            else L("重命名记忆「%s」→「%s」").format(oldName, name)
        )
        ctx.requireReady(store)
        val links = ctx.args.arr("links")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val to = o.str("to")?.trim().orEmpty()
            if (to.isEmpty()) null else MemoryLink(to, o.str("type")?.trim().orEmpty())
        }
        val r = runCatching {
            store.write(
                name = name,
                folder = ctx.args.str("folder"),
                description = ctx.args.str("description"),
                type = ctx.args.str("type"),
                body = ctx.args.str("content"),
                links = links,
                oldName = oldName
            )
        }.getOrElse { e -> ctx.fail(e.message ?: L("写入失败")) }
        val note = store.note(r.name)
        ToolResult(
            buildString {
                when {
                    r.renamed -> append(L("已把「%s」改名为「%s」").format(oldName, r.name))
                    r.created -> append(L("已新建记忆「%s」").format(r.name))
                    else -> append(L("已更新记忆「%s」").format(r.name))
                }
                if (note != null) {
                    append('\n').append(L("分区：")).append(note.folderLabel)
                    if (note.description.isNotBlank()) append(" · ").append(note.description)
                    append('\n').append(L("正文 ")).append(note.body.length).append(L(" 字 · 关联 "))
                        .append(note.links.size).append(L(" 条"))
                }
                append('\n').append(L("当前记忆库共 %s 条。").format(store.notes.size))
            }
        )
    }

    // ------------------------------------------------------------ memory_append

    private fun memoryAppend(store: MemoryStore) = ToolSpec(
        name = "memory_append",
        title = "记忆：追加",
        description = "往一篇记忆的正文末尾追加一段（换行隔开）。这篇记忆不存在时会新建。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "name" to Schema.str("记忆名"),
                "text" to Schema.str("要追加的内容，Markdown")
            ),
            required = listOf("name", "text")
        )
    ) { ctx ->
        val name = ctx.str("name")?.trim().orEmpty()
        val text = ctx.str("text")
        if (name.isEmpty()) ctx.fail(L("name 不能为空"))
        if (text.isNullOrBlank()) ctx.fail(L("text 不能为空"))
        ctx.guard(PermKey.MEMORY, null, L("给记忆「%s」追加内容").format(name))
        ctx.requireReady(store)
        val existed = store.has(name)
        val note = store.append(name, text) ?: ctx.fail(L("追加失败"))
        ToolResult(
            if (existed) L("已追加到「%s」，现在正文 %s 字。").format(note.name, note.body.length)
            else L("没有叫「%s」的记忆，已新建，正文 %s 字。").format(note.name, note.body.length)
        )
    }

    // ------------------------------------------------------------ memory_search

    private fun memorySearch(store: MemoryStore) = ToolSpec(
        name = "memory_search",
        title = "记忆：搜索",
        description = "按关键词搜索记忆：匹配名字 / 简介 / 类型 / 分区 / 正文 / 关联。" +
            "命中后会顺带把它们的直接邻居也带出来，方便顺着线索往下读。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "query" to Schema.str("关键词"),
                "limit" to Schema.int("最多返回多少条（默认 20）", 20, 1, 200)
            ),
            required = listOf("query")
        )
    ) { ctx ->
        val q = ctx.str("query")?.trim().orEmpty()
        if (q.isEmpty()) ctx.fail(L("query 不能为空"))
        ctx.guard(PermKey.MEMORY, null, L("搜索记忆：「%s」").format(q))
        ctx.requireReady(store)
        val limit = ctx.args.intOr("limit", 20)
        val hits = store.search(q, limit)
        ToolResult(renderList(hits, L("搜索「%s」：命中 %s 条").format(q, hits.size)))
    }

    // ------------------------------------------------------------ memory_delete

    private fun memoryDelete(store: MemoryStore) = ToolSpec(
        name = "memory_delete",
        title = "记忆：删除",
        description = "删除若干篇记忆（按名字）。指向它们的关联会一并从别的记忆里去掉。删之前建议先确认一下名字。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "names" to arr("要删除的记忆名", Schema.str("记忆名"))
            ),
            required = listOf("names")
        )
    ) { ctx ->
        val names = ctx.args.arr("names")?.mapNotNull { el ->
            el.strValue()?.takeIf { it.isNotBlank() }
        }.orEmpty()
        if (names.isEmpty()) ctx.fail(L("names 不能为空"))
        ctx.guard(PermKey.MEMORY, null, L("删除记忆：%s").format(names.joinToString(L("、"))))
        ctx.requireReady(store)
        val removed = store.delete(names)
        val gone = names.filterNot { store.has(it) }
        ToolResult(
            buildString {
                append(L("已删除 %s 条记忆").format(removed))
                if (gone.isNotEmpty()) append(L("（不存在：%s）").format(gone.joinToString(L("、"))))
                append(L("。当前共 %s 条。").format(store.notes.size))
            }
        )
    }

    // ------------------------------------------------------------- memory_stats

    private fun memoryStats(store: MemoryStore) = ToolSpec(
        name = "memory_stats",
        title = "记忆库统计",
        description = "查看记忆库的规模：记忆条数、关联条数、分区 / 类型 / 关联类型的分布。写完记忆后可以用它自查结构乱不乱。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, L("查看记忆库统计"))
        ctx.requireReady(store)
        ToolResult(store.statsText())
    }

    // -------------------------------------------------------------------- 渲染

    /** 一篇记忆的完整文本：元信息 + 正文 + 关联（带对方简介）。 */
    private fun renderNote(store: MemoryStore, note: MemoryNote): String = buildString {
        append("# ").append(note.name).append('\n')
        append(L("分区：")).append(note.folderLabel)
        if (note.type.isNotBlank()) append(" · ").append(L("类型：")).append(note.type)
        append('\n')
        if (note.description.isNotBlank()) {
            append(L("简介：")).append(note.description).append('\n')
        }
        append('\n').append(note.body.ifBlank { L("（正文为空）") }).append('\n')

        val edges = store.edgesOf(note.name)
        if (edges.isNotEmpty()) {
            append('\n').append(L("关联 %s 条：").format(edges.size)).append('\n')
            edges.forEach { e ->
                if (e.outgoing) {
                    append("  → ").append(e.type).append(" → ").append(e.to)
                } else {
                    append("  ← ").append(e.type).append(" ← ").append(e.from)
                }
                if (!e.peerExists) append(L("（对方还没有记忆）"))
                else if (e.peerDescription.isNotBlank()) append("：").append(e.peerDescription)
                append('\n')
            }
        }
    }

    /** 一串记忆的列表文本。 */
    private fun renderList(list: List<MemoryNote>, title: String): String {
        if (list.isEmpty()) return title + L("。没有结果。")
        val sb = StringBuilder(title).append('\n')
        var lastFolder = "\u0000"
        list.forEach { n ->
            if (n.folderLabel != lastFolder) {
                lastFolder = n.folderLabel
                sb.append("\n【").append(lastFolder).append("】\n")
            }
            sb.append("▸ ").append(n.name)
            if (n.type.isNotBlank()) sb.append("  <").append(n.type).append('>')
            if (n.description.isNotBlank()) sb.append(" — ").append(n.description)
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    // -------------------------------------------------------------------- 辅助

    private fun arr(desc: String, items: JsonObject): JsonObject = jo(
        "type" to "array",
        "description" to desc,
        "items" to items
    )

    /** `["a","b"]` 里的元素转字符串。 */
    private fun kotlinx.serialization.json.JsonElement.strValue(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString || it.content.isNotBlank() }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }
}

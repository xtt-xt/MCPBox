// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject

/**
 * 记忆库工具：让 AI 自己维护一份跨对话的长期记忆。
 *
 * 设计对齐官方 knowledge-graph memory server：
 * 建实体、建关系、加观察、删、读全图、搜索、按名取。
 * 所有文本（实体名 / 类型 / 分区 / 关系谓词）都是自由文本，不强制枚举。
 */
object ToolsMemory {

    fun specs(store: MemoryStore): List<ToolSpec> = listOf(
        createEntities(store),
        createRelations(store),
        addObservations(store),
        readGraph(store),
        searchNodes(store),
        openNodes(store),
        memoryStats(store),
        deleteEntities(store),
        deleteRelations(store),
        deleteObservations(store)
    )

    // ------------------------------------------------------------ create_entities

    private fun createEntities(store: MemoryStore) = ToolSpec(
        name = "create_entities",
        title = "记忆：新建实体",
        description = "在记忆库里创建实体（节点）。实体可以是项目、工具、事件、用户偏好等任何东西。" +
            "name 是唯一标识，已存在的实体不会被覆盖，只会补上空着的类型/分区。" +
            "type 和 folder 都是自由文本：type 建议用「项目事实」「用户偏好」「事件」「工具」「人物」这类词，" +
            "folder 用来分区（如 dev / projects / xtt / skills），不填则进「未分类」。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "entities" to arr(
                    "要创建的实体列表",
                    Schema.obj(
                        mapOf(
                            "name" to Schema.str("实体名，唯一标识，例如「MCPBox 构建环境」"),
                            "entityType" to Schema.str("实体类型，例如「项目事实」「用户偏好」「事件」（可用 type 代替）"),
                            "type" to Schema.str("同 entityType（二选一即可）"),
                            "folder" to Schema.str("分区/文件夹，例如 projects、xtt（可选）"),
                            "observations" to arr("顺便带上的观察短句（可选）", Schema.str("一条事实，一句话说清"))
                        ),
                        listOf("name")
                    )
                )
            ),
            required = listOf("entities")
        )
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, "记忆：新建实体")
        val items = ctx.args.arr("entities")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            Triple(
                name,
                o.str("entityType") ?: o.str("type") ?: "",
                o.str("folder") ?: ""
            )
        }.orEmpty()
        if (items.isEmpty()) ctx.fail("entities 不能为空（每项至少要有 name）")

        val r = store.createEntities(items)
        // 顺带把 observations 塞进去
        var obsAdded = 0
        ctx.args.arr("entities")?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.str("name")?.trim().orEmpty()
            val texts = o.arr("observations")?.mapNotNull { it.toString().trim('"').takeIf { s -> s.isNotBlank() } }.orEmpty()
            if (name.isNotEmpty() && texts.isNotEmpty()) obsAdded += store.addObservations(name, texts)
        }
        val names = items.map { it.first }
        ToolResult(
            buildString {
                append("已创建 ").append(r.created).append(" 个实体，已存在 ").append(r.skipped).append(" 个")
                if (obsAdded > 0) append("，附带 ") .append(obsAdded).append(" 条观察")
                append("。\n")
                names.forEach { append("  · ").append(it).append('\n') }
                append("\n当前记忆库共 ").append(store.graph.entities.size).append(" 个实体、")
                    .append(store.graph.relations.size).append(" 条关系。")
            }
        )
    }

    // ----------------------------------------------------------- create_relations

    private fun createRelations(store: MemoryStore) = ToolSpec(
        name = "create_relations",
        title = "记忆：建立关系",
        description = "在两个实体之间建立有向关系（边）：from -关系类型-> to。" +
            "关系类型是自由文本，建议用大写下划线，例如 HAPPENS_AT（发生于）、PART_OF（属于）、" +
            "INVOLVES（涉及）、CORRECTS（纠正）、UPDATES（更新）、RELATES_TO（相关）、FOLLOWS（先后）。" +
            "两端的实体建议先用 create_entities 建好。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "relations" to arr(
                    "关系列表",
                    Schema.obj(
                        mapOf(
                            "from" to Schema.str("起点实体名"),
                            "to" to Schema.str("终点实体名"),
                            "relationType" to Schema.str("关系类型，例如 PART_OF（可用 type 代替）"),
                            "type" to Schema.str("同 relationType（二选一即可）")
                        ),
                        listOf("from", "to")
                    )
                )
            ),
            required = listOf("relations")
        )
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, "记忆：建立关系")
        val items = ctx.args.arr("relations")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = o.str("from")?.trim().orEmpty()
            val to = o.str("to")?.trim().orEmpty()
            if (from.isEmpty() || to.isEmpty()) return@mapNotNull null
            Triple(from, to, o.str("relationType") ?: o.str("type") ?: "RELATED_TO")
        }.orEmpty()
        if (items.isEmpty()) ctx.fail("relations 不能为空（每项要有 from 和 to）")

        val r = store.createRelations(items)
        val missing = items.flatMap { listOf(it.first, it.second) }
            .filterNot { store.hasEntity(it) }.distinct()
        ToolResult(
            buildString {
                append("已建立 ").append(r.created).append(" 条关系，已存在 ").append(r.skipped).append(" 条。\n")
                items.forEach { append("  ").append(it.first).append(" ──").append(it.third).append("──▸ ").append(it.second).append('\n') }
                if (missing.isNotEmpty()) {
                    append("\n注意：这些实体还不存在，建议补建：")
                    append(missing.joinToString("、")).append('\n')
                }
                append("\n当前记忆库共 ").append(store.graph.entities.size).append(" 个实体、")
                    .append(store.graph.relations.size).append(" 条关系。")
            }
        )
    }

    // ---------------------------------------------------------- add_observations

    private fun addObservations(store: MemoryStore) = ToolSpec(
        name = "add_observations",
        title = "记忆：追加观察",
        description = "给已有实体追加「观察」——一条条独立的事实短句。重复的内容会自动跳过。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "observations" to arr(
                    "要追加的观察",
                    Schema.obj(
                        mapOf(
                            "entityName" to Schema.str("实体名"),
                            "contents" to arr("这个实体的若干条事实", Schema.str("一句话说清一条事实"))
                        ),
                        listOf("entityName", "contents")
                    )
                )
            ),
            required = listOf("observations")
        )
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, "记忆：追加观察")
        val items = ctx.args.arr("observations")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("entityName")?.trim().orEmpty()
            val texts = o.arr("contents")?.mapNotNull { it.strValue() }.orEmpty()
            if (name.isEmpty() || texts.isEmpty()) return@mapNotNull null
            name to texts
        }.orEmpty()
        if (items.isEmpty()) ctx.fail("observations 不能为空（每项要有 entityName 和 contents）")

        val sb = StringBuilder()
        var total = 0
        items.forEach { (name, texts) ->
            if (!store.hasEntity(name)) {
                sb.append("  ! 跳过「").append(name).append("」：实体不存在\n")
                return@forEach
            }
            val n = store.addObservations(name, texts)
            total += n
            sb.append("  · ").append(name).append("：新增 ").append(n).append(" 条")
                .append(if (n < texts.size) "（跳过 ${texts.size - n} 条重复）" else "").append('\n')
        }
        ToolResult("已追加 $total 条观察。\n$sb".trimEnd())
    }

    // ---------------------------------------------------------------- read_graph

    private fun readGraph(store: MemoryStore) = ToolSpec(
        name = "read_graph",
        title = "记忆：读取记忆库",
        description = "读取记忆库的全部内容（实体 + 关系）。可以用 folder / type 过滤、用 limit 限制条数。" +
            "内容多的时候建议先用 search_nodes 或 memory_stats 定位。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "folder" to Schema.str("只看某个分区（可选）"),
                "type" to Schema.str("只看某个实体类型（可选）"),
                "limit" to Schema.int("最多返回多少个实体，默认 50", 50, 1, 500)
            )
        )
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, "记忆：读取记忆库")
        val g = store.snapshot(
            folder = ctx.args.str("folder"),
            type = ctx.args.str("type"),
            limit = ctx.args.intOr("limit", 50)
        )
        if (g.entities.isEmpty()) {
            ToolResult("记忆库还是空的（或该过滤条件下没有内容）。用 create_entities 开始记录吧。")
        } else {
            ToolResult(renderGraph(store, g, "记忆库"))
        }
    }

    // -------------------------------------------------------------- search_nodes

    private fun searchNodes(store: MemoryStore) = ToolSpec(
        name = "search_nodes",
        title = "记忆：搜索",
        description = "按关键词搜索记忆库，匹配实体名 / 类型 / 分区 / 观察内容。" +
            "结果会连带返回命中实体之间的关系和它们的直接邻居，方便顺着线索读下去。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "query" to Schema.str("关键词，例如「构建」「用户偏好」「token」"),
                "limit" to Schema.int("最多返回多少个命中实体，默认 20", 20, 1, 200)
            ),
            required = listOf("query")
        )
    ) { ctx ->
        val query = ctx.args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) ctx.fail("query 不能为空")
        ctx.guard(PermKey.MEMORY, null, "记忆：搜索「$query」")
        val g = store.search(query, ctx.args.intOr("limit", 20))
        if (g.entities.isEmpty()) {
            ToolResult("没有找到和「$query」相关的记忆。")
        } else {
            ToolResult(renderGraph(store, g, "搜索「$query」"))
        }
    }

    // ---------------------------------------------------------------- open_nodes

    private fun openNodes(store: MemoryStore) = ToolSpec(
        name = "open_nodes",
        title = "记忆：按名读取",
        description = "按名字精确读取若干实体的完整内容（含它们的观察与关系）。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "names" to arr("实体名列表", Schema.str("实体名"))
            ),
            required = listOf("names")
        )
    ) { ctx ->
        val names = ctx.args.arr("names")?.mapNotNull { it.strValue() }.orEmpty()
        if (names.isEmpty()) ctx.fail("names 不能为空")
        ctx.guard(PermKey.MEMORY, null, "记忆：读取 ${names.size} 个实体")
        val found = names.mapNotNull { store.entity(it) }
        val missing = names.filterNot { store.hasEntity(it) }
        if (found.isEmpty()) {
            ToolResult("这些实体都不存在：" + missing.joinToString("、"))
        } else {
            val sb = StringBuilder(renderGraph(store, MemoryGraph(found, store.graph.relations, store.graph.revision), "记忆"))
            if (missing.isNotEmpty()) sb.append("\n\n以下实体不存在：").append(missing.joinToString("、"))
            ToolResult(sb.toString())
        }
    }

    // --------------------------------------------------------------- memory_stats

    private fun memoryStats(store: MemoryStore) = ToolSpec(
        name = "memory_stats",
        title = "记忆库统计",
        description = "查看记忆库的规模：实体数、关系数、分区 / 类型 / 关系谓词的分布。写完记忆后可以用它自查结构乱不乱。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.MEMORY, null, "查看记忆库统计")
        ToolResult(store.statsText())
    }

    // ------------------------------------------------------------ delete_entities

    private fun deleteEntities(store: MemoryStore) = ToolSpec(
        name = "delete_entities",
        title = "记忆：删除实体",
        description = "删除若干实体。**与它们相连的关系会一并删除**。删之前建议先 search_nodes 确认一下。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf("names" to arr("要删除的实体名", Schema.str("实体名"))),
            required = listOf("names")
        )
    ) { ctx ->
        val names = ctx.args.arr("names")?.mapNotNull { it.strValue() }.orEmpty()
        if (names.isEmpty()) ctx.fail("names 不能为空")
        ctx.guard(PermKey.MEMORY, null, "记忆：删除实体 " + names.joinToString("、"))
        val n = store.deleteEntities(names)
        ToolResult("已删除 $n 个实体（连带的关系也一起删了）。\n剩余：${store.graph.entities.size} 个实体、${store.graph.relations.size} 条关系。")
    }

    // ----------------------------------------------------------- delete_relations

    private fun deleteRelations(store: MemoryStore) = ToolSpec(
        name = "delete_relations",
        title = "记忆：删除关系",
        description = "删除指定的关系。只给 from / to、不给 relationType 时，会删掉这两点之间的所有关系。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "relations" to arr(
                    "要删除的关系",
                    Schema.obj(
                        mapOf(
                            "from" to Schema.str("起点实体名"),
                            "to" to Schema.str("终点实体名"),
                            "relationType" to Schema.str("关系类型（可选，不填删全部）")
                        ),
                        listOf("from", "to")
                    )
                )
            ),
            required = listOf("relations")
        )
    ) { ctx ->
        val items = ctx.args.arr("relations")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = o.str("from")?.trim().orEmpty()
            val to = o.str("to")?.trim().orEmpty()
            if (from.isEmpty() || to.isEmpty()) return@mapNotNull null
            Triple(from, to, o.str("relationType") ?: o.str("type"))
        }.orEmpty()
        if (items.isEmpty()) ctx.fail("relations 不能为空")
        ctx.guard(PermKey.MEMORY, null, "记忆：删除 ${items.size} 条关系")
        val n = store.deleteRelations(items)
        ToolResult("已删除 $n 条关系。剩余 ${store.graph.relations.size} 条。")
    }

    // -------------------------------------------------------- delete_observations

    private fun deleteObservations(store: MemoryStore) = ToolSpec(
        name = "delete_observations",
        title = "记忆：删除观察",
        description = "删掉某个实体上的若干条观察（需要一字不差地给出原文）。",
        perm = PermKey.MEMORY,
        schema = Schema.obj(
            props = mapOf(
                "deletions" to arr(
                    "要删除的观察",
                    Schema.obj(
                        mapOf(
                            "entityName" to Schema.str("实体名"),
                            "contents" to arr("要删掉的观察原文", Schema.str("观察原文"))
                        ),
                        listOf("entityName", "contents")
                    )
                )
            ),
            required = listOf("deletions")
        )
    ) { ctx ->
        val items = ctx.args.arr("deletions")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("entityName")?.trim().orEmpty()
            val texts = o.arr("contents")?.mapNotNull { it.strValue() }.orEmpty()
            if (name.isEmpty() || texts.isEmpty()) return@mapNotNull null
            name to texts
        }.orEmpty()
        if (items.isEmpty()) ctx.fail("deletions 不能为空")
        ctx.guard(PermKey.MEMORY, null, "记忆：删除观察")
        var total = 0
        val sb = StringBuilder()
        items.forEach { (name, texts) ->
            if (!store.hasEntity(name)) {
                sb.append("  ! 跳过「").append(name).append("」：实体不存在\n")
                return@forEach
            }
            val n = store.deleteObservations(name, texts)
            total += n
            sb.append("  · ").append(name).append("：删掉 ").append(n).append(" 条\n")
        }
        ToolResult("共删除 $total 条观察。\n$sb".trimEnd())
    }

    // -------------------------------------------------------------------- 辅助

    /** `["a","b"]` 里的元素转字符串。 */
    private fun kotlinx.serialization.json.JsonElement.strValue(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun arr(desc: String, items: JsonObject): JsonObject = jo(
        "type" to "array",
        "description" to desc,
        "items" to items
    )

    /** 把一张（子）图渲染成给 AI 看的纯文本。 */
    private fun renderGraph(store: MemoryStore, g: MemoryGraph, title: String): String {
        val sb = StringBuilder()
        sb.append(title).append("\n")
        sb.append("实体 ").append(g.entities.size).append(" 个 · 关系 ").append(g.relations.size).append(" 条\n")

        val byFolder = g.entities.groupBy { it.folder.ifBlank { MemoryEntity.DEFAULT_FOLDER } }
        byFolder.forEach { (folder, list) ->
            sb.append("\n【").append(folder).append("】\n")
            list.forEach { e ->
                sb.append("▸ ").append(e.name)
                if (e.type.isNotBlank()) sb.append("  <").append(e.type).append('>')
                sb.append('\n')
                e.observations.forEach { o -> sb.append("    · ").append(o).append('\n') }
                g.relations.filter { it.from == e.name }.forEach { r ->
                    sb.append("    → ").append(r.type).append(" → ").append(r.to).append('\n')
                }
                g.relations.filter { it.to == e.name }.forEach { r ->
                    sb.append("    ← ").append(r.type).append(" ← ").append(r.from).append('\n')
                }
            }
        }
        // 两端都被引用但没显示出来的实体名（比如邻居被 limit 截掉）
        val shown = g.entities.map { it.name }.toSet()
        val dangling = g.relations.flatMap { listOf(it.from, it.to) }.filterNot { it in shown }.distinct()
        if (dangling.isNotEmpty()) {
            sb.append("\n（还有未展开的关联实体：").append(dangling.joinToString("、")).append("）\n")
        }
        return sb.toString().trimEnd()
    }
}

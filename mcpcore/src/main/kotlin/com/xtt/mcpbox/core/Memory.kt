// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 记忆图谱：给 AI 用的长期记忆。
 *
 * 结构就是经典的知识图谱三元组：
 *  - [MemoryEntity]  一个实体/节点（人、项目、工具、事件…）
 *  - [MemoryRelation] 实体之间的一条有向边（from -type-> to）
 *  - observations    实体自己的一串"观察"短句，用来放事实细节
 *
 * `type` / `folder` / 关系谓词都是**自由文本**，不强制枚举：
 * AI 自己会自然长出「项目事实」「用户偏好」「事件」这类结构。
 */
@Serializable
data class MemoryEntity(
    val name: String = "",
    val type: String = "",
    val folder: String = DEFAULT_FOLDER,
    val observations: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    companion object {
        const val DEFAULT_FOLDER = "未分类"
    }
}

@Serializable
data class MemoryRelation(
    val from: String = "",
    val to: String = "",
    val type: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class MemoryGraph(
    val entities: List<MemoryEntity> = emptyList(),
    val relations: List<MemoryRelation> = emptyList(),
    val revision: Int = 0
)

/**
 * 记忆库的存储与读写。
 *
 * [file] 为 null 时纯内存运行（单元测试用）；否则原子写入（先写 .tmp 再改名），
 * 中途断电最多丢最后一次写入，不会把文件写坏。
 */
class MemoryStore(private val file: File? = null) {

    @Volatile var graph: MemoryGraph = MemoryGraph()
        private set

    private val lock = Any()

    /** 版本号：每次落盘 +1，UI 用它判断要不要重读。 */
    @Volatile var revision: Long = 0
        private set

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    init { load() }

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyChanged() {
        revision++
        listeners.forEach { runCatching { it() } }
    }

    // ------------------------------------------------------------------ 持久化

    fun load() {
        val f = file ?: return
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
        if (text.isNullOrBlank()) {
            graph = MemoryGraph()
            return
        }
        graph = runCatching { JSON.decodeFromString(MemoryGraph.serializer(), text) }
            .getOrElse { MemoryGraph() }
    }

    /** 只清空内存，不动文件。 */
    fun clear() {
        synchronized(lock) {
            graph = MemoryGraph()
            persistLocked()
        }
        notifyChanged()
    }

    private fun persistLocked() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSON.encodeToString(MemoryGraph.serializer(), graph))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun commit() {
        persistLocked()
        notifyChanged()
    }

    // -------------------------------------------------------------------- 查询

    fun entity(name: String): MemoryEntity? = graph.entities.firstOrNull { it.name == name }

    fun hasEntity(name: String): Boolean = entity(name) != null

    fun relationsOf(name: String): List<MemoryRelation> =
        graph.relations.filter { it.from == name || it.to == name }

    /** 直接邻居（顺着关系走出去一步，不含自己）。 */
    fun neighbours(name: String): List<String> = relationsOf(name)
        .map { if (it.from == name) it.to else it.from }
        .filter { it != name }
        .distinct()

    /** 全图，可按分区 / 类型过滤。 */
    fun snapshot(folder: String? = null, type: String? = null, limit: Int = 0): MemoryGraph {
        val ents = graph.entities.filter {
            (folder.isNullOrBlank() || it.folder == folder) &&
                (type.isNullOrBlank() || it.type == type)
        }.let { if (limit > 0) it.take(limit) else it }
        val names = ents.map { it.name }.toSet()
        // 只保留两端都在结果集里的关系
        val rels = graph.relations.filter { it.from in names && it.to in names }
        return MemoryGraph(ents, rels, graph.revision)
    }

    /** 关键词搜索：命中实体 + 顺带把它们之间的关系和直接邻居一起带出来。 */
    fun search(query: String, limit: Int = 20): MemoryGraph {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return snapshot(limit = limit)
        val hit = graph.entities.filter { e ->
            e.name.lowercase().contains(q) ||
                e.type.lowercase().contains(q) ||
                e.folder.lowercase().contains(q) ||
                e.observations.any { it.lowercase().contains(q) }
        }.take(if (limit > 0) limit else Int.MAX_VALUE)

        val names = hit.map { it.name }.toMutableSet()
        // 邻居实体也带上，方便 AI 顺着关系读
        hit.forEach { e -> neighbours(e.name).forEach { names.add(it) } }

        val ents = graph.entities.filter { it.name in names }
        val rels = graph.relations.filter { it.from in names && it.to in names }
        return MemoryGraph(ents, rels, graph.revision)
    }

    // -------------------------------------------------------------------- 写入

    data class Created(val created: Int, val skipped: Int)

    fun createEntities(
        items: List<Triple<String, String, String>> // name / type / folder
    ): Created {
        var created = 0
        var skipped = 0
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var list = graph.entities
            items.forEach { (rawName, type, folder) ->
                val name = rawName.trim()
                if (name.isEmpty()) return@forEach
                if (list.any { it.name == name }) {
                    // 已存在：不覆盖旧观察，只在原来空着的时候补上 type / folder
                    list = list.map {
                        if (it.name == name) it.copy(
                            type = it.type.ifBlank { type.trim() },
                            folder = it.folder.ifBlank { folder.trim().ifBlank { MemoryEntity.DEFAULT_FOLDER } },
                            updatedAt = now
                        ) else it
                    }
                    skipped++
                } else {
                    list = list + MemoryEntity(
                        name = name,
                        type = type.trim(),
                        folder = folder.trim().ifBlank { MemoryEntity.DEFAULT_FOLDER },
                        observations = emptyList(),
                        createdAt = now,
                        updatedAt = now
                    )
                    created++
                }
            }
            graph = graph.copy(entities = list, revision = graph.revision + 1)
        }
        commit()
        return Created(created, skipped)
    }

    fun createRelations(
        items: List<Triple<String, String, String>> // from / to / type
    ): Created {
        var created = 0
        var skipped = 0
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var list = graph.relations
            items.forEach { (rawFrom, rawTo, rawType) ->
                val from = rawFrom.trim()
                val to = rawTo.trim()
                val type = rawType.trim().ifBlank { "RELATED_TO" }
                if (from.isEmpty() || to.isEmpty()) return@forEach
                if (list.any { it.from == from && it.to == to && it.type == type }) {
                    skipped++
                } else {
                    list = list + MemoryRelation(from, to, type, now)
                    created++
                }
            }
            graph = graph.copy(relations = list, revision = graph.revision + 1)
        }
        commit()
        return Created(created, skipped)
    }

    /** 追加观察；已有一模一样的就跳过。返回真正新增的条数。 */
    fun addObservations(name: String, texts: List<String>): Int {
        var added = 0
        synchronized(lock) {
            val now = System.currentTimeMillis()
            graph = graph.copy(
                entities = graph.entities.map { e ->
                    if (e.name != name) e
                    else {
                        // 先去空、去重（同一批里传了重复的也算一次），再排掉已经存在的
                        val fresh = texts.map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .filter { it !in e.observations }
                        added = fresh.size
                        if (fresh.isEmpty()) e.copy(updatedAt = now)
                        else e.copy(observations = e.observations + fresh, updatedAt = now)
                    }
                },
                revision = graph.revision + 1
            )
        }
        commit()
        return added
    }

    /** 覆盖式编辑（UI 用）：改名字 / 类型 / 分区 / 整份观察列表。 */
    fun updateEntity(
        name: String,
        newName: String = name,
        type: String? = null,
        folder: String? = null,
        observations: List<String>? = null
    ): Boolean {
        val target = entity(name) ?: return false
        val finalName = newName.trim().ifBlank { name }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            graph = graph.copy(
                entities = graph.entities.map {
                    if (it.name != name) it
                    else it.copy(
                        name = finalName,
                        type = type?.trim() ?: it.type,
                        folder = folder?.trim()?.ifBlank { MemoryEntity.DEFAULT_FOLDER } ?: it.folder,
                        observations = observations?.map { o -> o.trim() }?.filter { o -> o.isNotEmpty() }
                            ?: it.observations,
                        updatedAt = now
                    )
                },
                relations = if (finalName == name) graph.relations else graph.relations.map {
                    it.copy(
                        from = if (it.from == name) finalName else it.from,
                        to = if (it.to == name) finalName else it.to
                    )
                },
                revision = graph.revision + 1
            )
        }
        commit()
        return true
    }

    /** 删除实体，连带删掉所有跟它相连的关系。返回删掉的实体数。 */
    fun deleteEntities(names: List<String>): Int {
        val set = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (set.isEmpty()) return 0
        var removed = 0
        synchronized(lock) {
            val kept = graph.entities.filterNot { it.name in set }
            removed = graph.entities.size - kept.size
            graph = graph.copy(
                entities = kept,
                relations = graph.relations.filterNot { it.from in set || it.to in set },
                revision = graph.revision + 1
            )
        }
        commit()
        return removed
    }

    fun deleteRelations(items: List<Triple<String, String, String?>>): Int {
        var removed = 0
        synchronized(lock) {
            val kept = graph.relations.filterNot { r ->
                val hit = items.any { (f, t, ty) ->
                    r.from == f.trim() && r.to == t.trim() && (ty.isNullOrBlank() || r.type == ty.trim())
                }
                if (hit) removed++
                hit
            }
            graph = graph.copy(relations = kept, revision = graph.revision + 1)
        }
        commit()
        return removed
    }

    fun deleteObservations(name: String, texts: List<String>): Int {
        val set = texts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        var removed = 0
        synchronized(lock) {
            graph = graph.copy(
                entities = graph.entities.map { e ->
                    if (e.name != name) e
                    else {
                        val kept = e.observations.filterNot { it in set }
                        removed = e.observations.size - kept.size
                        e.copy(observations = kept, updatedAt = System.currentTimeMillis())
                    }
                },
                revision = graph.revision + 1
            )
        }
        commit()
        return removed
    }

    // --------------------------------------------------------------- 导入 / 导出

    /** 导出成 JSON（实体 + 关系，含观察）。格式就是 [MemoryGraph] 本身。 */
    fun exportJson(): String = JSON.encodeToString(MemoryGraph.serializer(), graph)

    /**
     * 导入结果。[replaced] = true 表示整库替换（不是合并）。
     */
    data class ImportResult(
        val entitiesAdded: Int = 0,
        val entitiesMerged: Int = 0,
        val observationsAdded: Int = 0,
        val relationsAdded: Int = 0,
        val relationsSkipped: Int = 0,
        val replaced: Boolean = false
    ) {
        /** 给工具 / 界面用的一句可读总结。 */
        fun describe(): String = if (replaced) {
            L("已覆盖导入：清空原库后写入 %s 个实体、%s 条关系。").format(entitiesAdded, relationsAdded)
        } else {
            L("合并导入完成：新增实体 %s · 补全既有实体 %s · 新增观察 %s · 新增关系 %s（跳过重复关系 %s）。")
                .format(entitiesAdded, entitiesMerged, observationsAdded, relationsAdded, relationsSkipped)
        }
    }

    /**
     * 合并导入时的分区取舍：原来只是「未分类」（等于没填）就听导入文件的，
     * 用户显式选过的分区一律不动。
     */
    private fun mergeFolder(old: String, incoming: String): String {
        val o = old.trim()
        if (o.isNotBlank() && o != MemoryEntity.DEFAULT_FOLDER) return o
        return incoming.trim().ifBlank { MemoryEntity.DEFAULT_FOLDER }
    }

    /**
     * 从 JSON 导入。
     *
     * - `merge = true`：合并模式 —— 不删任何东西。同名实体只补空着的 type / folder，
     *   观察去重后追加；关系三元组重复的跳过。
     * - `merge = false`：覆盖模式 —— 先清空整库，再用文件里的内容替换。
     *
     * 解析失败（不是合法 JSON / 结构不对）会抛异常，由调用方兜住。
     */
    fun importJson(text: String, merge: Boolean = true): ImportResult {
        val incoming = JSON.decodeFromString(MemoryGraph.serializer(), text.trim())
        val result: ImportResult
        synchronized(lock) {
            if (!merge) {
                val ents = incoming.entities
                    .filter { it.name.isNotBlank() }
                    .map { it.copy(folder = it.folder.trim().ifBlank { MemoryEntity.DEFAULT_FOLDER }) }
                val rels = incoming.relations.filter { it.from.isNotBlank() && it.to.isNotBlank() }
                graph = MemoryGraph(ents, rels, graph.revision + 1)
                result = ImportResult(ents.size, 0, 0, rels.size, 0, replaced = true)
            } else {
                val now = System.currentTimeMillis()
                var added = 0
                var merged = 0
                var obsAdded = 0
                // 用 LinkedHashMap 保证顺序：老实体原样在前，新增的按文件顺序追加
                val byName = LinkedHashMap<String, MemoryEntity>()
                graph.entities.forEach { byName[it.name] = it }
                incoming.entities.forEach { inc ->
                    val name = inc.name.trim()
                    if (name.isEmpty()) return@forEach
                    val old = byName[name]
                    if (old == null) {
                        byName[name] = inc.copy(
                            name = name,
                            type = inc.type.trim(),
                            folder = inc.folder.trim().ifBlank { MemoryEntity.DEFAULT_FOLDER },
                            observations = inc.observations.map { o -> o.trim() }.filter { o -> o.isNotEmpty() }.distinct(),
                            createdAt = if (inc.createdAt > 0L) inc.createdAt else now,
                            updatedAt = now
                        )
                        added++
                    } else {
                        // 已存在：只在原来空着的时候补 type / folder，观察去重后追加，绝不删旧的
                        val fresh = inc.observations.map { o -> o.trim() }
                            .filter { o -> o.isNotEmpty() }
                            .distinct()
                            .filter { o -> o !in old.observations }
                        obsAdded += fresh.size
                        byName[name] = old.copy(
                            type = old.type.ifBlank { inc.type.trim() },
                            folder = mergeFolder(old.folder, inc.folder),
                            observations = old.observations + fresh,
                            updatedAt = now
                        )
                        merged++
                    }
                }
                var rels = graph.relations
                var relAdded = 0
                var relSkipped = 0
                incoming.relations.forEach { r ->
                    val from = r.from.trim()
                    val to = r.to.trim()
                    if (from.isEmpty() || to.isEmpty()) return@forEach
                    if (rels.any { it.from == from && it.to == to && it.type == r.type }) {
                        relSkipped++
                    } else {
                        rels = rels + r.copy(from = from, to = to)
                        relAdded++
                    }
                }
                graph = graph.copy(
                    entities = byName.values.toList(),
                    relations = rels,
                    revision = graph.revision + 1
                )
                result = ImportResult(added, merged, obsAdded, relAdded, relSkipped, replaced = false)
            }
        }
        commit()
        return result
    }

    // -------------------------------------------------------------------- 统计

    fun folders(): List<Pair<String, Int>> = graph.entities
        .groupingBy { it.folder.ifBlank { MemoryEntity.DEFAULT_FOLDER } }
        .eachCount()
        .entries
        .map { e -> e.key to e.value }
        .sortedByDescending { e -> e.second }

    fun types(): List<Pair<String, Int>> = graph.entities
        .filter { it.type.isNotBlank() }
        .groupingBy { it.type }
        .eachCount()
        .entries
        .map { e -> e.key to e.value }
        .sortedByDescending { e -> e.second }

    fun relationTypes(): List<Pair<String, Int>> = graph.relations
        .groupingBy { it.type }
        .eachCount()
        .entries
        .map { e -> e.key to e.value }
        .sortedByDescending { e -> e.second }

    fun statsText(): String = buildString {
        append(L("记忆库")).append('\n')
        append(L("  实体：")).append(graph.entities.size).append('\n')
        append(L("  关系：")).append(graph.relations.size).append('\n')
        val obs = graph.entities.sumOf { it.observations.size }
        append(L("  观察：")).append(obs).append(L(" 条\n"))
        val f = folders()
        if (f.isNotEmpty()) {
            append(L("\n分区：\n"))
            f.forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
        val t = types()
        if (t.isNotEmpty()) {
            append(L("\n实体类型：\n"))
            t.take(20).forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
        val r = relationTypes()
        if (r.isNotEmpty()) {
            append(L("\n关系类型：\n"))
            r.take(20).forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
    }.trimEnd()

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }
    }
}

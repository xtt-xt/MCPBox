// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.File

/**
 * 一条记忆：**一个 Markdown 文件**。
 *
 * 文件本身既是数据也是可读的文档，front-matter 里放元信息，后面全是正文：
 *
 * ```
 * ---
 * title: MCPBox
 * description: 手机上的 MCP 文件服务器
 * type: 项目
 * created: 1763700000000
 * updated: 1763700000000
 * links:
 *   - to: 星天
 *     type: 属于
 * ---
 * 正文随便写。
 * ```
 *
 * 名字全局唯一，文件放在 `记忆根目录/<分区>/<名字>.md`；分区为空时直接放在根目录，
 * 界面上显示成「未分类」。
 */
data class MemoryLink(
    val to: String = "",
    val type: String = ""
)

data class MemoryNote(
    val name: String = "",
    val folder: String = "",
    val description: String = "",
    val type: String = "",
    val body: String = "",
    val links: List<MemoryLink> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    /** 界面上展示的分区名（空分区 = 未分类）。 */
    val folderLabel: String get() = folder.ifBlank { UNFILED }

    companion object {
        const val UNFILED = "未分类"
    }
}

/** 一条关联，带对方的简介 —— 读 / 搜的时候用来顺着线索往下走。 */
data class MemoryEdge(
    val from: String,
    val to: String,
    val type: String,
    /** 出边还是入边（站在谁的角度看）。 */
    val outgoing: Boolean,
    /** 对方的简介（对方不存在时为空）。 */
    val peerDescription: String,
    /** 对方是否真的存在。 */
    val peerExists: Boolean
)

/** 旧版图谱（graph.json）里的数据结构 —— 只用于导入与迁移，不再参与日常读写。 */
internal object LegacyMemory {
    @kotlinx.serialization.Serializable
    data class Entity(
        val name: String = "",
        val type: String = "",
        val folder: String = "未分类",
        val observations: List<String> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L
    )

    @kotlinx.serialization.Serializable
    data class Relation(
        val from: String = "",
        val to: String = "",
        val type: String = "",
        val createdAt: Long = 0L
    )

    @kotlinx.serialization.Serializable
    data class Graph(
        val entities: List<Entity> = emptyList(),
        val relations: List<Relation> = emptyList(),
        val revision: Int = 0
    )

    val JSON = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    fun parse(text: String): Graph = JSON.decodeFromString(Graph.serializer(), text.trim())

    fun encode(g: Graph): String = JSON.encodeToString(Graph.serializer(), g)
}

/**
 * 记忆库：一个装着 Markdown 文件的目录树。
 *
 * - `root` 为 null 时纯内存运行（单元测试 / harness 用）。
 * - 每次写入都直接落盘（先写 `.tmp` 再改名），断电最多丢最后一次写入。
 * - 根目录下还有旧版的 `graph.json` 时，整库处于 [State.LEGACY]：不读不写，
 *   等用户在 App 里迁移。迁移完成后 `graph.json` 会被移进 `_backup/`。
 */
class MemoryStore(private val root: File? = null) {

    enum class State {
        /** 一条记忆都没有。 */
        EMPTY,

        /** 正常可用。 */
        READY,

        /** 还躺着旧版的 graph.json，没迁移之前整个记忆功能不可用。 */
        LEGACY
    }

    /** 当前所有记忆，按「分区 → 名字」排好序。 */
    @Volatile
    var notes: List<MemoryNote> = emptyList()
        private set

    /** 旧版图谱的原文（非 null 即处于 [State.LEGACY]）。 */
    @Volatile
    var legacyJson: String? = null
        private set

    /** 版本号：每次落盘 +1，UI 用它判断要不要重读。 */
    @Volatile
    var revision: Long = 0
        private set

    private val lock = Any()
    private val mem = LinkedHashMap<String, MemoryNote>()
    private var listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    val state: State
        get() = when {
            legacyJson != null -> State.LEGACY
            notes.isEmpty() -> State.EMPTY
            else -> State.READY
        }

    init { reload() }

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        revision++
        listeners.forEach { runCatching { it() } }
    }

    // ------------------------------------------------------------------ 目录

    private val notesDir: File? get() = root
    private val legacyFile: File? get() = root?.let { File(it, "graph.json") }
    private val backupDir: File? get() = root?.let { File(it, "_backup") }

    // ------------------------------------------------------------------ 读取

    /** 重新扫描目录。 */
    fun reload() {
        val dir = notesDir
        if (dir == null) {
            synchronized(lock) { notes = sorted(mem.values) }
            return
        }
        val legacy = legacyFile?.takeIf { it.isFile }?.let { f ->
            runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (legacy != null) {
            synchronized(lock) {
                legacyJson = legacy
                notes = emptyList()
                mem.clear()
            }
            return
        }
        val found = LinkedHashMap<String, MemoryNote>()
        if (dir.isDirectory) {
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith("_") }
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .sortedBy { it.absolutePath }
                .forEach { f ->
                    val parsed = runCatching { parseFile(f, dir) }.getOrNull() ?: return@forEach
                    // 重名时保留先扫到的（按路径排序，稳定）
                    if (parsed.name.isNotBlank() && !found.containsKey(parsed.name)) {
                        found[parsed.name] = parsed
                    }
                }
        }
        synchronized(lock) {
            legacyJson = null
            mem.clear()
            mem.putAll(found)
            notes = sorted(mem.values)
        }
    }

    private fun sorted(list: Collection<MemoryNote>): List<MemoryNote> =
        list.sortedWith(compareBy({ it.folderLabel }, { it.name }))

    fun note(name: String): MemoryNote? {
        val n = name.trim()
        if (n.isEmpty()) return null
        synchronized(lock) { return mem[n] }
    }

    fun has(name: String): Boolean = note(name) != null

    /** 按分区过滤；[folder] 为空 = 全部。 */
    fun list(folder: String? = null): List<MemoryNote> {
        val f = folder?.trim().orEmpty()
        val all = synchronized(lock) { notes }
        if (f.isEmpty()) return all
        val want = if (f == MemoryNote.UNFILED) "" else f
        return all.filter { it.folder == want }
    }

    /** 所有分区（含空的「未分类」），按数量降序。 */
    fun folders(): List<Pair<String, Int>> = synchronized(lock) { notes }
        .groupingBy { it.folderLabel }
        .eachCount()
        .entries
        .map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    fun types(): List<Pair<String, Int>> = synchronized(lock) { notes }
        .filter { it.type.isNotBlank() }
        .groupingBy { it.type }
        .eachCount()
        .entries
        .map { it.key to it.value }
        .sortedByDescending { it.second }

    /**
     * 一条记忆的全部关联：它自己写出去的和别人指向它的都算。
     */
    fun edgesOf(name: String): List<MemoryEdge> {
        val n = name.trim()
        val all = synchronized(lock) { notes }
        val byName = all.associateBy { it.name }
        val out = (byName[n]?.links ?: emptyList())
            .filter { it.to.isNotBlank() }
            .map { l ->
                val peer = byName[l.to]
                MemoryEdge(n, l.to, l.type.ifBlank { DEFAULT_LINK }, true, peer?.description.orEmpty(), peer != null)
            }
        val inc = all.asSequence()
            .filter { it.name != n }
            .flatMap { src -> src.links.asSequence().filter { it.to == n }.map { src to it } }
            .map { (src, l) ->
                val peer = byName[src.name]
                MemoryEdge(src.name, n, l.type.ifBlank { DEFAULT_LINK }, false, peer?.description.orEmpty(), true)
            }
            .toList()
        return out + inc
    }

    /** 直接邻居（出边 + 入边，去重）。 */
    fun neighbours(name: String): List<String> = edgesOf(name)
        .map { if (it.outgoing) it.to else it.from }
        .filter { it != name }
        .distinct()

    /**
     * 关键词搜索：名字 / 简介 / 类型 / 分区 / 正文 / 关联目标。
     * 命中后顺带把它们的直接邻居也带出来。
     */
    fun search(query: String, limit: Int = 20): List<MemoryNote> {
        val q = query.trim().lowercase()
        val all = synchronized(lock) { notes }
        if (q.isEmpty()) return if (limit > 0) all.take(limit) else all
        val hit = all.filter { n ->
            n.name.lowercase().contains(q) ||
                n.description.lowercase().contains(q) ||
                n.type.lowercase().contains(q) ||
                n.folderLabel.lowercase().contains(q) ||
                n.body.lowercase().contains(q) ||
                n.links.any { it.to.lowercase().contains(q) || it.type.lowercase().contains(q) }
        }.take(if (limit > 0) limit else Int.MAX_VALUE)
        val names = LinkedHashSet(hit.map { it.name })
        hit.forEach { n -> neighbours(n.name).forEach { names.add(it) } }
        val byName = all.associateBy { it.name }
        return names.mapNotNull { byName[it] }
    }

    // ------------------------------------------------------------------ 写入

    /** 写入结果：新建 / 更新 / 改名。 */
    data class WriteResult(val created: Boolean, val renamed: Boolean, val name: String)

    /**
     * 整篇写入。不传的字段保持原样（新建时为空）。
     *
     * [oldName] 非空且与 [name] 不同时表示改名（旧文件会被删掉，指向它的关联不用动 ——
     * 关联只认名字，所以要把所有指向旧名的 links 一起改写）。
     */
    fun write(
        name: String,
        folder: String? = null,
        description: String? = null,
        type: String? = null,
        body: String? = null,
        links: List<MemoryLink>? = null,
        oldName: String? = null
    ): WriteResult {
        val finalName = sanitizeName(name)
        require(finalName.isNotBlank()) { L("记忆名不能为空") }
        val from = oldName?.trim()?.takeIf { it.isNotBlank() }
        val result: WriteResult
        synchronized(lock) {
            val existing: MemoryNote? = if (from != null) mem[from] else mem[finalName]
            val now = System.currentTimeMillis()
            val note = MemoryNote(
                name = finalName,
                folder = folder?.let { cleanFolder(it) } ?: existing?.folder.orEmpty(),
                description = description?.trim() ?: existing?.description.orEmpty(),
                type = type?.trim() ?: existing?.type.orEmpty(),
                body = body?.replace("\r\n", "\n") ?: existing?.body.orEmpty(),
                links = links?.map { MemoryLink(sanitizeName(it.to), it.type.trim()) }
                    ?.filter { it.to.isNotBlank() }
                    ?.distinctBy { it.to to it.type }
                    ?: existing?.links.orEmpty(),
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now
            )
            if (from != null && from != finalName) {
                deleteFileLocked(from)
                mem.remove(from)
                // 别人指向旧名字的关联一起改写
                mem.replaceAll { _, n ->
                    if (n.links.any { it.to == from }) {
                        n.copy(links = n.links.map { l -> if (l.to == from) l.copy(to = finalName) else l })
                    } else n
                }
                mem.values.forEach { persistLocked(it) }
            }
            mem[finalName] = note
            persistLocked(note)
            notes = sorted(mem.values)
            result = WriteResult(created = existing == null, renamed = from != null && from != finalName, name = finalName)
        }
        notifyChanged()
        return result
    }

    /** 往正文末尾追加一段。不存在就新建。 */
    fun append(name: String, text: String): MemoryNote? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val add = text.trim()
        if (add.isEmpty()) return note(n)
        val result: MemoryNote
        synchronized(lock) {
            val old = mem[n]
            val note = if (old == null) {
                MemoryNote(name = n, body = add, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            } else {
                // 用空行分隔：正文若以引用 / 列表结尾，只加一个换行会被 Markdown 的
                // 懒续行规则并进上一块（多见于 `> 引用` 后面接普通段落）。
                val sep = when {
                    old.body.isBlank() -> ""
                    old.body.endsWith("\n\n") -> ""
                    old.body.endsWith("\n") -> "\n"
                    else -> "\n\n"
                }
                old.copy(body = old.body + sep + add, updatedAt = System.currentTimeMillis())
            }
            mem[n] = note
            persistLocked(note)
            notes = sorted(mem.values)
            result = note
        }
        notifyChanged()
        return result
    }

    /** 只改某一项元信息。 */
    fun updateMeta(
        name: String,
        description: String? = null,
        type: String? = null,
        folder: String? = null
    ): Boolean {
        val n = name.trim()
        synchronized(lock) {
            val old = mem[n] ?: return false
            val note = old.copy(
                description = description?.trim() ?: old.description,
                type = type?.trim() ?: old.type,
                folder = folder?.let { cleanFolder(it) } ?: old.folder,
                updatedAt = System.currentTimeMillis()
            )
            mem[n] = note
            persistLocked(note)
            notes = sorted(mem.values)
        }
        notifyChanged()
        return true
    }

    /** 删除若干条记忆。返回真正删掉的条数。 */
    fun delete(names: List<String>): Int {
        val set = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (set.isEmpty()) return 0
        var removed = 0
        synchronized(lock) {
            set.forEach { n ->
                if (mem.remove(n) != null) {
                    deleteFileLocked(n)
                    removed++
                }
            }
            // 指向它们的关联也一并清掉
            if (removed > 0) {
                mem.replaceAll { _, note ->
                    val kept = note.links.filterNot { it.to in set }
                    if (kept.size != note.links.size) note.copy(links = kept) else note
                }
                mem.values.forEach { persistLocked(it) }
            }
            notes = sorted(mem.values)
        }
        notifyChanged()
        return removed
    }

    // ------------------------------------------------------------------ 落盘

    private fun persistLocked(note: MemoryNote) {
        val dir = notesDir ?: return
        runCatching {
            val target = fileFor(note)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(render(note))
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun deleteFileLocked(name: String) {
        val dir = notesDir ?: return
        runCatching { fileForName(dir, name)?.delete() }
    }

    private fun fileFor(note: MemoryNote): File =
        File(root!!, if (note.folder.isBlank()) "${note.name}.md" else "${note.folder}/${note.name}.md")

    /** 按名字找现有文件（可能在任何分区目录下）。 */
    private fun fileForName(dir: File, name: String): File? = dir.walkTopDown()
        .onEnter { it == dir || !it.name.startsWith("_") }
        .firstOrNull { it.isFile && it.nameWithoutExtension == name && it.extension.equals("md", true) }

    // ------------------------------------------------------------------ 导入 / 导出

    /** 记忆库的全部文件：相对路径（`分区/名字.md`，用 `/`）→ 文本。 */
    fun filesSnapshot(): Map<String, String> = synchronized(lock) {
        val out = LinkedHashMap<String, String>()
        notes.forEach { n ->
            out[if (n.folder.isBlank()) "${n.name}.md" else "${n.folder}/${n.name}.md"] = render(n)
        }
        out
    }

    /**
     * 备份清单（写进 zip 的 `memory/memory.json`）。真实内容在 `memory/files/` 下。
     */
    fun exportManifest(exportedAt: Long = System.currentTimeMillis()): String {
        val files = filesSnapshot()
        val obj = jo(
            "_type" to Backup.TYPE_MEMORY,
            "version" to 2,
            "app" to ServerMeta.fullVersion,
            "exportedAt" to exportedAt,
            "count" to files.size,
            "files" to jarr(files.map { (path, text) ->
                jo("path" to path, "size" to text.toByteArray().size)
            })
        )
        return LegacyMemory.JSON.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), obj
        )
    }

    data class ImportResult(val added: Int = 0, val updated: Int = 0, val removed: Int = 0) {
        fun describe(): String =
            L("记忆导入完成：新增 %s 条 · 更新 %s 条%s。")
                .format(added, updated, if (removed > 0) L(" · 清掉旧内容 %s 条").format(removed) else "")
    }

    /**
     * 从一批 md 文件导入。
     *
     * @param files 相对路径（`分区/名字.md`）→ 文本
     * @param replace true = 先清空整个记忆库再写入
     */
    fun importFiles(files: Map<String, String>, replace: Boolean = false): ImportResult {
        var added = 0
        var updated = 0
        var removed = 0
        synchronized(lock) {
            if (replace) {
                removed = mem.size
                mem.clear()
                val dir = notesDir
                if (dir != null && dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.extension.equals("md", true)) f.delete()
                        else if (f.isDirectory && !f.name.startsWith("_")) f.deleteRecursively()
                    }
                }
            }
            files.forEach { (path, text) ->
                val rel = path.replace('\\', '/').trimStart('/')
                if (!rel.endsWith(".md", true)) return@forEach
                val parts = rel.split('/').filter { it.isNotBlank() }
                val fileName = parts.last().removeSuffix(".md").removeSuffix(".MD")
                val folder = if (parts.size > 1) parts.dropLast(1).joinToString("/") else ""
                val note = runCatching { parse(text, fileName, folder) }.getOrNull() ?: return@forEach
                val name = sanitizeName(note.name.ifBlank { fileName })
                if (name.isBlank()) return@forEach
                val existed = mem.containsKey(name)
                val finalNote = note.copy(name = name, folder = cleanFolder(note.folder.ifBlank { folder }))
                mem[name] = finalNote
                persistLocked(finalNote)
                if (existed) updated++ else added++
            }
            notes = sorted(mem.values)
            legacyJson = null
        }
        notifyChanged()
        return ImportResult(added, updated, removed)
    }

    /** 老备份里的记忆是 graph.json 结构，导入时转成 md。 */
    fun importLegacyJson(text: String, replace: Boolean = false): ImportResult {
        val graph = runCatching { LegacyMemory.parse(text) }.getOrElse {
            throw ToolFailure(L("不是合法的旧版记忆文件：%s").format(it.message ?: ""))
        }
        var added = 0
        var updated = 0
        var removed = 0
        synchronized(lock) {
            if (replace) {
                removed = mem.size
                mem.clear()
                val dir = notesDir
                if (dir != null && dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.extension.equals("md", true)) f.delete()
                        else if (f.isDirectory && !f.name.startsWith("_")) f.deleteRecursively()
                    }
                }
            }
            val now = System.currentTimeMillis()
            graph.entities.filter { it.name.isNotBlank() }.forEach { e ->
                val name = sanitizeName(e.name)
                if (name.isBlank()) return@forEach
                val existed = mem.containsKey(name)
                val old = mem[name]
                val body = e.observations.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                val merged = if (old != null && !replace) {
                    old.copy(
                        body = listOf(old.body, body).filter { it.isNotBlank() }.joinToString("\n"),
                        type = old.type.ifBlank { e.type.trim() },
                        updatedAt = now
                    )
                } else {
                    MemoryNote(
                        name = name,
                        folder = cleanFolder(e.folder),
                        description = deriveDescription(body),
                        type = e.type.trim(),
                        body = body,
                        createdAt = e.createdAt,
                        updatedAt = now
                    )
                }
                mem[name] = merged
                persistLocked(merged)
                if (existed) updated++ else added++
            }
            graph.relations.forEach { r ->
                val from = mem[sanitizeName(r.from)] ?: return@forEach
                val to = sanitizeName(r.to)
                if (to.isBlank()) return@forEach
                if (from.links.any { it.to == to && it.type == r.type }) return@forEach
                val next = from.copy(links = from.links + MemoryLink(to, r.type.trim().ifBlank { DEFAULT_LINK }))
                mem[from.name] = next
                persistLocked(next)
            }
            notes = sorted(mem.values)
            legacyJson = null
        }
        notifyChanged()
        return ImportResult(added, updated, removed)
    }

    // ------------------------------------------------------------------ 统计

    fun statsText(): String = buildString {
        append(L("记忆库")).append('\n')
        append(L("  记忆：")).append(notes.size).append(L(" 条\n"))
        val links = notes.sumOf { it.links.size }
        append(L("  关联：")).append(links).append(L(" 条\n"))
        val f = folders()
        if (f.isNotEmpty()) {
            append(L("\n分区：\n"))
            f.forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
        val t = types()
        if (t.isNotEmpty()) {
            append(L("\n类型：\n"))
            t.take(20).forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
        val rt = notes.flatMap { it.links }.filter { it.type.isNotBlank() }
            .groupingBy { it.type }.eachCount().entries.map { it.key to it.value }
            .sortedByDescending { it.second }
        if (rt.isNotEmpty()) {
            append(L("\n关联类型：\n"))
            rt.take(20).forEach { (k, v) -> append("  ").append(k).append("：").append(v).append('\n') }
        }
    }.trimEnd()

    // ------------------------------------------------------------------ 旧版迁移

    /** 是否存在旧版数据（没迁移完）。 */
    val hasLegacy: Boolean get() = legacyJson != null

    /** `_backup/` 下所有旧版数据库备份，最新的在前。 */
    fun legacyBackups(): List<File> {
        val dir = backupDir ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("graph-") && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
    }

    /**
     * 把当前的 `graph.json` 复制一份进 `_backup/`（带时间戳）。
     *
     * 重复调用是安全的：已经备份过就不再生成第二份。
     */
    fun ensureLegacyBackup(): File? {
        val dir = backupDir ?: return null
        val src = legacyFile?.takeIf { it.isFile } ?: return null
        val text = runCatching { src.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        runCatching {
            dir.mkdirs()
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val marker = File(dir, ".legacy-$digest")
            if (marker.isFile) {
                val prev = File(dir, marker.readText().trim())
                if (prev.isFile) return prev
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val dst = File(dir, "graph-$stamp.json")
            dst.writeText(text)
            marker.writeText(dst.name)
            return dst
        }
        return null
    }

    /** 迁移结果。 */
    data class MigrateResult(
        val notes: Int = 0,
        val links: Int = 0,
        val backup: File? = null
    )

    /**
     * 把旧版 `graph.json` 拆成一批 Markdown。
     *
     * 规则：
     *  - 每个实体 → `<分区>/<名字>.md`，观察每句一行拼成正文；
     *  - 关系 → 起点那篇的 `links`；
     *  - `graph.json` 不删，改成 `_backup/` 里那份（原文件删掉，备份就是它）。
     */
    fun migrateLegacy(): MigrateResult {
        val text = legacyJson ?: return MigrateResult()
        val graph = runCatching { LegacyMemory.parse(text) }.getOrElse {
            throw ToolFailure(L("旧版记忆数据解析失败：%s").format(it.message ?: ""))
        }
        val backup = ensureLegacyBackup()
        var count = 0
        var links = 0
        synchronized(lock) {
            mem.clear()
            graph.entities.filter { it.name.isNotBlank() }.forEach { e ->
                val name = sanitizeName(e.name)
                if (name.isBlank()) return@forEach
                val body = e.observations.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                mem[name] = MemoryNote(
                    name = name,
                    folder = cleanFolder(e.folder),
                    description = deriveDescription(body),
                    type = e.type.trim(),
                    body = body,
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt
                )
                count++
            }
            // 关系挂到起点那篇的 links 上
            graph.relations.forEach { r ->
                val from = mem[sanitizeName(r.from)] ?: return@forEach
                val to = sanitizeName(r.to)
                if (to.isBlank()) return@forEach
                if (from.links.any { it.to == to && it.type == r.type }) return@forEach
                mem[from.name] = from.copy(
                    links = from.links + MemoryLink(to, r.type.trim().ifBlank { DEFAULT_LINK })
                )
                links++
            }
            // 旧文件里的名字可能被 sanitize 改过，整体重写一遍映射
            mem.values.forEach { persistLocked(it) }
            notes = sorted(mem.values)
            legacyJson = null
        }
        legacyFile?.delete()
        notifyChanged()
        return MigrateResult(count, links, backup)
    }

    /** 旧库没有简介：拿正文第一行顶上，方便在列表里认出来。 */
    private fun deriveDescription(body: String): String {
        val first = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val clean = first.trimStart('-', '*', '+', ' ', '#', '>').trim()
        return if (clean.length <= 60) clean else clean.take(60) + "…"
    }

    /** 旧版数据的原文（给「保存旧版记忆」用）。 */
    fun legacyText(): String? = legacyJson ?: legacyFile?.takeIf { it.isFile }?.let {
        runCatching { it.readText() }.getOrNull()
    }

    companion object {
        const val DEFAULT_LINK = "相关"

        /** 名字 / 分区里不能出现路径分隔符之类的东西。 */
        fun sanitizeName(raw: String): String = raw.trim()
            .replace('/', '／')
            .replace('\\', '＼')
            .replace('\u0000', ' ')
            .trim('.')

        fun cleanFolder(raw: String): String {
            val f = sanitizeName(raw)
            return if (f == MemoryNote.UNFILED) "" else f
        }

        // ---------------------------------------------------------- Markdown

        /** 把一条记忆渲染成 md 全文。 */
        fun render(note: MemoryNote): String = buildString {
            append("---\n")
            append("title: ").append(quote(note.name)).append('\n')
            if (note.description.isNotBlank()) append("description: ").append(quote(note.description)).append('\n')
            if (note.type.isNotBlank()) append("type: ").append(quote(note.type)).append('\n')
            append("created: ").append(note.createdAt).append('\n')
            append("updated: ").append(note.updatedAt).append('\n')
            if (note.links.isNotEmpty()) {
                append("links:\n")
                note.links.forEach { l ->
                    append("  - to: ").append(quote(l.to)).append('\n')
                    append("    type: ").append(quote(l.type.ifBlank { DEFAULT_LINK })).append('\n')
                }
            }
            append("---\n")
            if (note.body.isNotBlank()) {
                append('\n')
                append(note.body.trim('\n'))
                append('\n')
            }
        }

        /** 解析一个 md 文件。 */
        fun parseFile(file: File, root: File): MemoryNote {
            val text = file.readText()
            val folder = file.parentFile.relativeTo(root).invariantSeparatorsPath
                .let { if (it == "." || it.isEmpty()) "" else it }
            return parse(text, file.nameWithoutExtension, folder)
        }

        fun parse(text: String, fallbackName: String, folder: String): MemoryNote {
            val norm = text.replace("\r\n", "\n")
            val lines = norm.split('\n')
            if (lines.isEmpty() || lines[0].trim() != "---") {
                return MemoryNote(
                    name = fallbackName,
                    folder = cleanFolder(folder),
                    body = norm.trim('\n')
                )
            }
            var end = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") { end = i; break }
            }
            if (end < 0) {
                return MemoryNote(name = fallbackName, folder = cleanFolder(folder), body = norm.trim('\n'))
            }
            var name = fallbackName
            var description = ""
            var type = ""
            var createdAt = 0L
            var updatedAt = 0L
            val links = mutableListOf<MemoryLink>()
            var i = 1
            while (i < end) {
                val raw = lines[i]
                val line = raw.trimEnd()
                if (line.isBlank()) { i++; continue }
                // links 列表
                if (line.trimStart().startsWith("- ")) {
                    val bodyLine = line.trimStart().removePrefix("- ").trim()
                    val (k, v) = splitKv(bodyLine)
                    if (k == "to") {
                        val to = v
                        // 下一条可能是 type
                        var lt = ""
                        if (i + 1 < end) {
                            val next = lines[i + 1]
                            if (next.startsWith("    ") || next.startsWith("\t")) {
                                val (nk, nv) = splitKv(next.trim())
                                if (nk == "type") { lt = nv; i++ }
                            }
                        }
                        if (to.isNotBlank()) links.add(MemoryLink(to, lt.ifBlank { DEFAULT_LINK }))
                    }
                    i++
                    continue
                }
                val (k, v) = splitKv(line)
                when (k) {
                    "title" -> name = v.ifBlank { fallbackName }
                    "description" -> description = v
                    "type" -> type = v
                    "created" -> createdAt = v.toLongOrNull() ?: 0L
                    "updated" -> updatedAt = v.toLongOrNull() ?: 0L
                }
                i++
            }
            val body = lines.drop(end + 1).joinToString("\n").trim('\n')
            return MemoryNote(
                name = name.ifBlank { fallbackName },
                folder = cleanFolder(folder),
                description = description,
                type = type,
                body = body,
                links = links,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }

        private fun splitKv(line: String): Pair<String, String> {
            val idx = line.indexOf(':')
            if (idx < 0) return "" to ""
            return line.substring(0, idx).trim() to unquote(line.substring(idx + 1).trim())
        }

        private fun unquote(s: String): String {
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                val inner = s.substring(1, s.length - 1)
                val sb = StringBuilder()
                var i = 0
                while (i < inner.length) {
                    val c = inner[i]
                    if (c == '\\' && i + 1 < inner.length) {
                        when (inner[i + 1]) {
                            'n' -> { sb.append('\n'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            '"' -> { sb.append('"'); i += 2 }
                            '\\' -> { sb.append('\\'); i += 2 }
                            else -> { sb.append(c); i++ }
                        }
                    } else {
                        sb.append(c); i++
                    }
                }
                return sb.toString()
            }
            return s
        }

        /** 值需要引号时加引号，并转义。 */
        private fun quote(s: String): String {
            val needs = s.isEmpty() ||
                s != s.trim() ||
                s.startsWith("-") ||
                s.startsWith("#") ||
                s.any { it == ':' || it == '"' || it == '\n' || it == '\t' }
            if (!needs) return s
            val sb = StringBuilder("\"")
            s.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class TrashEntry(
    val id: String,
    val name: String,
    val originalPath: String,
    val storedPath: String,
    val isDir: Boolean,
    val size: Long,
    val deletedAt: Long
)

/**
 * Files deleted while "回收站" is enabled are moved here instead of being erased,
 * so a wrong AI call stays recoverable.
 */
class TrashManager(private val sandbox: PathSandbox) {

    private val indexFile: File get() = File(sandbox.trashDir(), "index.json")
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun entries(): List<TrashEntry> {
        val f = indexFile
        if (!f.exists()) return emptyList()
        return runCatching {
            val el = J.parseToJsonElement(f.readText())
            J.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(TrashEntry.serializer()), el)
        }.getOrElse { emptyList() }
    }

    private fun save(list: List<TrashEntry>) {
        val dir = sandbox.trashDir()
        if (!dir.exists()) dir.mkdirs()
        val el = J.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TrashEntry.serializer()), list
        )
        indexFile.writeText(el.toString())
    }

    fun move(file: File): TrashEntry {
        val dir = sandbox.trashDir()
        if (!dir.exists()) dir.mkdirs()
        val id = Tokens.newId().take(8)
        val safeName = file.name.ifBlank { "item" }
        var target = File(dir, "${stamp.format(Date())}-$id-$safeName")
        if (target.exists()) target = File(dir, "${stamp.format(Date())}-$id-$safeName-1")
        val ok = file.renameTo(target) || runCatching {
            copyRecursive(file, target); deleteRecursive(file); true
        }.getOrDefault(false)
        if (!ok) throw SandboxException("移动到回收站失败：${file.path}")
        val entry = TrashEntry(
            id = id,
            name = safeName,
            originalPath = file.path,
            storedPath = target.path,
            isDir = target.isDirectory,
            size = sizeOf(target),
            deletedAt = System.currentTimeMillis()
        )
        save(entries() + entry)
        return entry
    }

    fun restore(entry: TrashEntry, destination: File? = null, overwrite: Boolean = false): File {
        val src = File(entry.storedPath)
        if (!src.exists()) throw SandboxException("回收站里的文件已不存在：${entry.storedPath}")
        val dst = destination ?: File(entry.originalPath)
        if (dst.exists()) {
            if (!overwrite) throw SandboxException("目标已存在，无法还原：${dst.path}")
            deleteRecursive(dst)
        }
        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst) || runCatching {
            copyRecursive(src, dst); deleteRecursive(src); true
        }.getOrDefault(false)
        if (!ok) throw SandboxException("还原失败：${entry.originalPath}")
        save(entries().filterNot { it.id == entry.id })
        return dst
    }

    fun purge(entry: TrashEntry) {
        deleteRecursive(File(entry.storedPath))
        save(entries().filterNot { it.id == entry.id })
    }

    fun emptyAll(): Pair<Int, Long> {
        val list = entries()
        var n = 0
        var bytes = 0L
        list.forEach {
            bytes += it.size
            deleteRecursive(File(it.storedPath))
            n++
        }
        runCatching { indexFile.delete() }
        return n to bytes
    }

    fun sizeOf(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteRecursive(f: File) {
        if (!f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        if (!f.delete()) throw SandboxException("删除失败：${f.path}")
    }

    fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw SandboxException("无法创建目录：${dst.path}")
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dst.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            }
            dst.setLastModified(src.lastModified())
        }
    }
}

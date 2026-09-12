package com.xtt.mcpbox.core

import java.io.File

/** 一次目录项（本地和走 shell 拿到的统一成这个）。 */
data class FsEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long
)

/** 文件属性。 */
data class FsStat(
    val path: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long,
    val readable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
    val bridged: Boolean
)

/**
 * 文件访问桥：先试应用自己读写，读不到（别家私有目录 /data/data/xxx 这种）
 * 且 root / Shizuku 可用时，自动改走 shell 转发。
 *
 * 命令都用单引号包住路径，避免空格、$ 之类的字符出问题。
 */
class FileBridge(
    private val config: Config,
    /** 懒取一个「非应用自身」的后端；应用层注册顺序是 app / root / shizuku。 */
    private val privilegedLauncher: () -> CommandLauncher?
) {

    private val runner = ShellRunner()

    val privilegedAvailable: Boolean
        get() = privilegedLauncher() != null

    val privilegedLabel: String
        get() = privilegedLauncher()?.label ?: "不可用"

    private fun launcher(): CommandLauncher? =
        privilegedLauncher()?.takeIf { runCatching { it.isAvailable() }.getOrDefault(false) }

    // ------------------------------------------------------------------ 读

    fun readBytes(file: File, maxBytes: Long = 32L * 1024 * 1024): ByteArray? {
        runCatching {
            if (file.isFile && file.canRead()) {
                val len = file.length()
                if (len in 0..maxBytes) {
                    val bytes = file.readBytes()
                    if (bytes.isNotEmpty() || len == 0L) return bytes
                }
            }
        }
        val l = launcher() ?: return null
        val bytes = runner.runBytes(
            l, "cat ${shellQuote(file.path)} 2>/dev/null", null, 30_000, maxBytes
        )
        return bytes?.takeIf { it.isNotEmpty() || existsViaShell(file) }
    }

    fun readText(file: File, maxBytes: Long = 8L * 1024 * 1024): String? =
        readBytes(file, maxBytes)?.toString(Charsets.UTF_8)

    // ------------------------------------------------------------------ 写

    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            if (file.isFile && file.length() == bytes.size.toLong()) return true
        }
        val l = launcher() ?: return false
        val dir = file.parentFile?.path
        if (dir != null) {
            runner.run(l, "mkdir -p ${shellQuote(dir)}", null, 20_000)
        }
        val res = runner.runWithStdin(l, "cat > ${shellQuote(file.path)}", bytes, null, 180_000)
        return res.exitCode == 0
    }

    fun mkdirs(dir: File): Boolean {
        runCatching {
            if (dir.isDirectory) return true
            if (dir.mkdirs() || dir.isDirectory) return true
        }
        val l = launcher() ?: return false
        return runner.run(l, "mkdir -p ${shellQuote(dir.path)}", null, 30_000).exitCode == 0
    }

    // ------------------------------------------------------------ 目录 / 属性

    fun listDir(dir: File): List<FsEntry>? {
        val local = runCatching { dir.listFiles() }.getOrNull()
        if (local != null && local.isNotEmpty()) {
            return local.map { entry(it) }.sortedWith(
                compareByDescending<FsEntry> { it.dir }.thenBy { it.name.lowercase() }
            )
        }
        // 空目录 or 没权限（listFiles 返回 null / 空都可能）→ 用 shell 再确认一次
        val l = launcher() ?: run {
            if (local != null) {
                return emptyList()
            }
            return null
        }
        val out = runner.run(l, "ls -la ${shellQuote(dir.path)}", null, 30_000).stdout
        val parsed = parseLs(out, dir.path)
        return if (parsed.isNotEmpty()) parsed.sortedWith(
            compareByDescending<FsEntry> { it.dir }.thenBy { it.name.lowercase() }
        ) else if (local != null) emptyList() else parsed
    }

    fun exists(file: File): Boolean {
        runCatching { if (file.exists()) return true }
        return existsViaShell(file)
    }

    private fun existsViaShell(file: File): Boolean {
        val l = launcher() ?: return false
        val res = runner.run(l, "[ -e ${shellQuote(file.path)} ] && echo YES || echo NO", null, 15_000)
        return res.stdout.contains("YES")
    }

    fun stat(file: File): FsStat? {
        runCatching {
            if (file.exists()) {
                return FsStat(
                    path = file.path,
                    dir = file.isDirectory,
                    size = if (file.isDirectory) 0 else file.length(),
                    modified = file.lastModified(),
                    readable = file.canRead(),
                    writable = file.canWrite(),
                    executable = file.canExecute(),
                    bridged = false
                )
            }
        }
        val l = launcher() ?: return null
        val out = runner.run(l, "ls -la -d ${shellQuote(file.path)}", null, 20_000).stdout
        val entry = parseLs(out, file.parent ?: "/").firstOrNull() ?: return null
        return FsStat(
            path = file.path,
            dir = entry.dir,
            size = entry.size,
            modified = entry.modified,
            readable = true, writable = true, executable = false,
            bridged = true
        )
    }

    // -------------------------------------------------------------- 复制/移动/删除

    fun copy(src: File, dst: File): Pair<Boolean, String> {
        val local = runCatching {
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
            } else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                dst
            }
            true
        }.getOrDefault(false)
        if (local) return true to "本地"
        val l = launcher() ?: return false to "应用自己没有权限，而且没有可用的 root / Shizuku"
        val res = runner.run(
            l,
            "mkdir -p ${shellQuote(parentOf(dst))} && cp -r ${shellQuote(src.path)} ${shellQuote(dst.path)}",
            null, 180_000
        )
        return (res.exitCode == 0) to if (res.exitCode == 0) (l.label) else res.toText()
    }

    fun move(src: File, dst: File): Pair<Boolean, String> {
        val local = runCatching {
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) true else {
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
                src.deleteRecursively()
                true
            }
        }.getOrDefault(false)
        if (local) return true to "本地"
        val l = launcher() ?: return false to "应用自己没有权限，而且没有可用的 root / Shizuku"
        val res = runner.run(
            l,
            "mkdir -p ${shellQuote(parentOf(dst))} && mv ${shellQuote(src.path)} ${shellQuote(dst.path)}",
            null, 180_000
        )
        return (res.exitCode == 0) to if (res.exitCode == 0) l.label else res.toText()
    }

    fun delete(file: File, recursive: Boolean): Boolean {
        val local = runCatching {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }.getOrDefault(false)
        if (local && !exists(file)) return true
        val l = launcher() ?: return false
        val flag = if (recursive) "-rf" else "-f"
        val res = runner.run(l, "rm $flag ${shellQuote(file.path)}", null, 120_000)
        return res.exitCode == 0
    }

    // ------------------------------------------------------------------ 工具

    private fun entry(f: File): FsEntry = FsEntry(
        name = f.name,
        path = f.path,
        dir = f.isDirectory,
        size = if (f.isDirectory) 0 else liveLength(f),
        modified = f.lastModified()
    )

    /** 应用读不到 /data/data 里的文件时 length() 会是 0，这里补一次 shell 查询。 */
    private fun liveLength(f: File): Long {
        val len = runCatching { f.length() }.getOrDefault(0L)
        if (len > 0) return len
        val l = launcher() ?: return len
        val out = runner.run(l, "wc -c < ${shellQuote(f.path)} 2>/dev/null", null, 15_000).stdout
        return out.trim().lines().lastOrNull()?.trim()?.toLongOrNull() ?: len
    }

    companion object {
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        private fun parentOf(f: File): String = f.parent ?: "/"

        private val LS_LINE = Regex("""^([dlbcps\-][rwxsStT\-]{9})[.+@]?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(.+)$""")
        private val DATE_LONG = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** 解析 `ls -la` 输出（toybox / busybox 都能吃）。 */
        fun parseLs(out: String, dirPath: String): List<FsEntry> {
            val result = ArrayList<FsEntry>()
            out.lineSequence().forEach { line ->
                val m = LS_LINE.find(line.trim()) ?: return@forEach
                val perm = m.groupValues[1]
                val size = m.groupValues[2].toLongOrNull() ?: 0L
                val rest = m.groupValues[3].trim()
                if (rest.isEmpty()) return@forEach
                val fields = rest.split(Regex("\\s+"))
                // 时间有 "2024-01-01 12:00"（2 段）和 "Jan 1 12:00"（3 段）两种
                val timeFields = if (fields.size >= 2 && DATE_LONG.matches(fields[0])) 2 else 3
                if (fields.size <= timeFields) return@forEach
                val name = fields.drop(timeFields).joinToString(" ")
                if (name == "." || name == "..") return@forEach
                val dateText = fields.take(timeFields).joinToString(" ")
                result.add(
                    FsEntry(
                        name = name,
                        path = if (dirPath.endsWith("/")) dirPath + name else "$dirPath/$name",
                        dir = perm.startsWith("d"),
                        size = size,
                        modified = parseDate(dateText)
                    )
                )
            }
            return result
        }

        private fun parseDate(text: String): Long = runCatching {
            if (DATE_LONG.matches(text.substringBefore(' '))) {
                val t = if (text.length <= 16) "$text:00" else text
                java.time.LocalDateTime.parse(t.replace(' ', 'T'))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                // "Jan 1 12:00" 这种英文月份格式（补上当前年份）
                val fmt = java.time.format.DateTimeFormatter.ofPattern(
                    "yyyy MMM d HH:mm", java.util.Locale.ENGLISH
                )
                java.time.LocalDateTime.parse("${java.time.Year.now().value} $text", fmt)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrDefault(0L)
    }
}

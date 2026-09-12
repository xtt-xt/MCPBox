// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.File

class SandboxException(message: String) : Exception(message)

/**
 * Turns whatever path an AI client sends into a real [File], while keeping every
 * access inside the user configured roots.
 */
class PathSandbox(private val config: Config) {

    private val aliases = listOf(
        "/sdcard" to "/storage/emulated/0",
        "/mnt/sdcard" to "/storage/emulated/0",
        "/storage/self/primary" to "/storage/emulated/0"
    )

    private val protectedPaths = listOf("/proc", "/sys", "/dev", "/acct")

    fun roots(): List<File> = config.roots.map { canonical(File(it)) }

    fun primaryRoot(): File = canonical(File(config.primaryRoot()))

    fun trashDir(): File = File(primaryRoot(), ".MCPBox/trash")

    fun canonical(f: File): File = try {
        f.canonicalFile
    } catch (e: Exception) {
        f.absoluteFile
    }

    /** Resolve a client supplied path. Throws [SandboxException] when out of bounds. */
    fun resolve(input: String?, mustExist: Boolean = false): File {
        val raw = input?.trim().orEmpty()
        val base: File = when {
            raw.isEmpty() || raw == "~" || raw == "." -> primaryRoot()
            raw == "/" -> File("/")
            raw.startsWith("~/") || raw.startsWith("~\\") ->
                File(primaryRoot(), raw.substring(2))
            raw.startsWith("/") || raw.matches(Regex("^[A-Za-z]:[\\\\/].*")) -> File(applyAliases(raw))
            else -> File(primaryRoot(), raw)
        }
        val resolved = canonical(base)
        checkBounds(resolved, raw)
        if (mustExist && !resolved.exists()) {
            throw SandboxException("路径不存在：${resolved.path}")
        }
        return resolved
    }

    private fun applyAliases(path: String): String {
        aliases.forEach { (alias, real) ->
            if (path == alias) return real
            if (path.startsWith("$alias/")) return real + path.substring(alias.length)
        }
        return path
    }

    /** 应用私有目录：只有开了「私有目录访问」才放行，而且可以单独设成只读。 */
    private val privateRoots = listOf(
        "/data/data",
        "/data/user/0",
        "/data/user_de/0",
        "/data/local/tmp",
        "/data/app",
        "/data/misc",
        "/data/system",
        "/data/adb"
    )

    fun isPrivatePath(path: File): Boolean {
        val p = path.path
        return privateRoots.any { p == it || p.startsWith("$it/") }
    }

    fun privateAccess(): String = config.privateAccess

    /** 私有目录处于「只读」模式时，写/删直接拒绝。 */
    fun assertWritable(file: File) {
        if (config.privateAccess == Config.PRIVATE_READ && isPrivatePath(file)) {
            throw SandboxException(
                "应用私有目录当前是「只读」模式，不能修改：${file.path}\n" +
                    "（要写入请到 设置 → 权限 → 应用私有目录 改成「可读写」）"
            )
        }
    }

    private fun checkBounds(path: File, raw: String) {
        if (isProtected(path)) throw SandboxException("系统目录受保护，禁止访问：${path.path}")
        if (config.fullAccess) return
        if (isPrivatePath(path)) {
            if (config.privateAccess == Config.PRIVATE_OFF) {
                throw SandboxException(
                    "应用私有目录没有开放：${path.path}\n" +
                        "（到 设置 → 权限 → 应用私有目录 里选「只读」或「可读写」；需要 root 或 Shizuku）"
                )
            }
            return
        }
        val roots = roots()
        val inside = roots.any { root -> isInside(root, path) }
        if (!inside) {
            val allowed = roots.joinToString("、") { it.path }
            throw SandboxException(
                "路径超出允许范围：${path.path}\n当前允许的根目录只有：$allowed\n" +
                    "（需要在 App 的「设置 → 允许访问的目录」里添加，或把「不限制目录」打开）"
            )
        }
    }

    private fun isProtected(path: File): Boolean {
        if (config.fullAccess) return false
        val p = path.path
        return protectedPaths.any { p == it || p.startsWith("$it/") }
    }

    private fun isInside(root: File, path: File): Boolean {
        if (root.path == "/" || root.path.isEmpty()) return true
        if (path.path == root.path) return true
        return path.path.startsWith(root.path.trimEnd('/') + "/")
    }

    /** Path relative to the primary root when possible, for compact display. */
    fun display(file: File): String = file.path

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024; i++
        }
        return String.format("%.2f %s", v, units[i])
    }

    fun timeText(ms: Long): String = try {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } catch (e: Exception) {
        ms.toString()
    }
}

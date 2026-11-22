// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 插件包（`.zip`）的解包。
 *
 * 插件的目录结构原样放进去：
 *
 * ```
 * myplugin/
 *   plugin.json
 *   tools/  (工具脚本)
 *   api/    (路由脚本)
 * ```
 *
 * 打成 zip 时多套一层父目录也能用（`zip -r myplugin.zip myplugin/` 是最常见的打包方式），
 * [extract] 会自己剥掉「只有一个顶层目录并且里面才是 plugin.json」的那一层。
 *
 * **安全边界**：包是用户从外面拿进来的，条目名可以随便写 —— `../../x`、绝对路径、
 * Windows 的 `..\..\x`、盘符，都得在落盘之前挡住，否则一个包就能往 App 私有目录外面写文件。
 * 所以每个条目都要过 [safeName]，并且解出来的路径还要再和根目录比一次。
 * 只解普通文件；符号链接一律跳过（zip 里的软链在解包后只是一个写着目标路径的普通文件，
 * 不会真的变成本机软链，这里显式跳掉更省事）。
 */
object PluginPackage {

    /** 一个包里最多几个条目（防止解压炸弹式的海量小文件）。 */
    const val MAX_ENTRIES = 2000

    /** 单个文件解压后最大多少字节。 */
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024

    /** 整个包解压后最多多少字节。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    const val MANIFEST_NAME = PluginStore.MANIFEST_NAME
    const val CONFIG_NAME = PluginStore.CONFIG_NAME

    /** 打包工具自带的垃圾目录，找 plugin.json 时要跳过。 */
    private val JUNK_DIRS = setOf("__MACOSX", ".DS_Store", ".git")

    /**
     * 条目名归一化。
     *
     * @return 相对目标目录的安全路径；不安全返回 null。
     */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.length > 1024) return null
        if (raw.contains('\u0000')) return null
        // Windows 风格的分隔符按 / 处理，这样 `..\..\x` 也会被下面的 .. 检查抓住
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        // 盘符（C:/x）
        if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) return null
        val parts = ArrayList<String>()
        for (p in normalized.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> return null
                else -> parts.add(p)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /**
     * 把一个 zip 解到 [into]，返回**真正放着 plugin.json 的那个目录**。
     *
     * @param zip 包内容；调用方负责关闭。
     * @throws PluginException 包坏了、条目不安全、超限、或者里面根本没有 plugin.json。
     */
    fun extract(zip: InputStream, into: File): File {
        into.deleteRecursively()
        if (!into.mkdirs()) throw PluginException(L("创建临时目录失败"))
        val root = into.canonicalFile
        var entries = 0
        var total = 0L

        try {
            ZipInputStream(BufferedInputStream(zip)).use { zin ->
                val buf = ByteArray(8192)
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) {
                        throw PluginException(L("插件包里的文件太多（超过 %s 个）").format(MAX_ENTRIES))
                    }
                    val name = safeName(entry.name)
                        ?: throw PluginException(L("插件包里有不安全的路径：%s").format(entry.name))
                    val target = File(root, name)
                    // 双保险：归一化之后仍然确认一次真的落在 root 底下
                    if (target.canonicalPath != root.path &&
                        !target.canonicalPath.startsWith(root.path + File.separator)
                    ) {
                        throw PluginException(L("插件包里有不安全的路径：%s").format(entry.name))
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    // 符号链接在 ZipInputStream 里和普通文件一样，解出来只是一个写着目标
                    // 路径的文本文件，不会真的变成本机软链，所以不用特殊处理。
                    target.parentFile?.mkdirs()
                    var written = 0L
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > MAX_ENTRY_BYTES) {
                                throw PluginException(
                                    L("插件包里的「%s」太大（单文件最多 %s MB）")
                                        .format(name, MAX_ENTRY_BYTES / 1024 / 1024)
                                )
                            }
                            if (total > MAX_TOTAL_BYTES) {
                                throw PluginException(
                                    L("插件包解压后太大（最多 %s MB）")
                                        .format(MAX_TOTAL_BYTES / 1024 / 1024)
                                )
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: PluginException) {
            into.deleteRecursively()
            throw e
        } catch (e: Exception) {
            into.deleteRecursively()
            throw PluginException(L("插件包解不开：%s").format(e.message ?: e.javaClass.simpleName))
        }

        if (entries == 0) {
            into.deleteRecursively()
            throw PluginException(L("插件包是空的"))
        }

        val home = locateRoot(root)
        if (home == null) {
            into.deleteRecursively()
            throw PluginException(L("插件包里没有 %s").format(MANIFEST_NAME))
        }
        // 包里带来的 config.json 一律丢掉：那是**别人**的设置和 Key，
        // 装进来只会让插件拿着别人的 Key 跑。覆盖安装时 PluginStore.install 会把
        // 本地旧 config.json 搬过来，所以用户的设置不会丢。
        File(home, CONFIG_NAME).delete()
        return home
    }

    /**
     * 找一个目录，它自己直接放着 plugin.json。
     *
     * 先看根；根没有就往下找一层（跳过 `__MACOSX` 之类），正好只有一个候选才认 ——
     * 两个以上说明包里塞了多个插件，让用户自己选一个重新打包，别猜。
     */
    private fun locateRoot(root: File): File? {
        if (File(root, MANIFEST_NAME).isFile) return root
        val candidates = root.listFiles()
            ?.filter { it.isDirectory && it.name !in JUNK_DIRS }
            ?.filter { File(it, MANIFEST_NAME).isFile }
            .orEmpty()
        return when (candidates.size) {
            0 -> null
            1 -> candidates[0]
            else -> throw PluginException(
                L("插件包里装了好几个插件（%s），一次只能导入一个")
                    .format(candidates.joinToString("、") { it.name })
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 一次插件操作的审批闸门。
 *
 * 工具调用由 [CallContext.guard] 实现（带工具名、客户端地址），
 * 路由请求没有 CallContext —— 由 [PluginRouter] 直接接到 [ApprovalCenter] 上。
 * 两边共用下面这份 host 实现，免得文件 / 命令 / 网络这些逻辑写两遍。
 */
internal interface PluginGate {
    fun guard(
        perm: PermKey,
        path: File?,
        summary: String,
        detail: String? = null,
        bytes: Long? = null,
        mediaType: String? = null,
        command: String? = null,
        backend: String? = null
    )
}

/**
 * 把 host API 接到现有能力上：
 *  - 相对路径 → 插件自己的 data/，不审批，且出不去
 *  - 绝对路径 → [PathSandbox] + 权限矩阵
 *  - 命令 → 现有三种后端
 *  - 网络 → [PluginNetwork]（域名规则 + 审批）
 *  - 设置 → 插件的 config.json
 */
internal open class PluginHostImpl(
    private val plugin: InstalledPlugin,
    private val store: PluginStore,
    override val args: JsonObject,
    private val toolName: String,
    private val sandbox: PathSandbox,
    private val bridge: FileBridge,
    private val config: Config,
    private val gate: PluginGate,
    private val network: PluginNetwork?,
    private val toolsCall: ((String, JsonObject) -> ToolResult?)?
) : PluginHost {

    override val pluginId: String = plugin.id
    override val pluginVersion: String = plugin.manifest.version
    override val dataDirPath: String = store.dataDir(plugin.id).absolutePath

    private val dataRoot: File = File(dataDirPath)
    private val label: String get() = L("插件「%s」").format(plugin.manifest.name)

    private val runner = ShellRunner()

    // ------------------------------------------------------------ 设置

    override fun settingsGet(key: String): JsonElement? =
        store.settingValues(plugin.id)[key]

    override fun settingsSet(key: String, value: JsonElement) {
        store.setSetting(plugin.id, key, value)
    }

    // ------------------------------------------------------------ 文件

    override fun fsRead(path: String): String {
        if (!File(path).isAbsolute) {
            val f = insideData(path)
            if (!f.isFile) throw PluginRuntimeException(L("文件不存在：%s").format(path))
            return runCatching { f.readText() }.getOrElse {
                throw PluginRuntimeException(L("读不出文件：%s").format(path))
            }
        }
        val f = sandbox.resolve(path, mustExist = true)
        gate.guard(PermKey.READ, f, L("%s 读取文件").format(label) + "：" + f.path)
        return bridge.readText(f)
            ?: throw PluginRuntimeException(L("读不到文件：%s").format(path))
    }

    override fun fsWrite(path: String, text: String) {
        if (!File(path).isAbsolute) {
            val f = insideData(path)
            f.parentFile?.mkdirs()
            if (!runCatching { f.writeText(text) }.isSuccess) {
                throw PluginRuntimeException(L("写不进文件：%s").format(path))
            }
            return
        }
        val f = sandbox.resolve(path)
        gate.guard(
            PermKey.WRITE, f, L("%s 写入文件").format(label) + "：" + f.path,
            bytes = text.toByteArray().size.toLong(), mediaType = "text/plain"
        )
        if (!bridge.writeBytes(f, text.toByteArray())) {
            throw PluginRuntimeException(L("写不进文件：%s").format(path))
        }
    }

    override fun fsList(path: String): JsonElement {
        val dir = if (!File(path).isAbsolute) {
            insideData(path)
        } else {
            val f = sandbox.resolve(path, mustExist = true)
            gate.guard(PermKey.READ, f, L("%s 列目录").format(label) + "：" + f.path)
            f
        }
        val entries = bridge.listDir(dir)
            ?: throw PluginRuntimeException(L("列不出目录：%s").format(path))
        return jarr(entries.map { e ->
            jo(
                "name" to e.name, "path" to e.path, "dir" to e.dir,
                "size" to e.size, "modified" to e.modified
            )
        })
    }

    override fun fsRemove(path: String) {
        if (!File(path).isAbsolute) {
            val f = insideData(path)
            if (!f.exists()) return
            if (!f.deleteRecursively()) {
                throw PluginRuntimeException(L("删不掉：%s").format(path))
            }
            return
        }
        val f = sandbox.resolve(path, mustExist = true)
        gate.guard(PermKey.DELETE, f, L("%s 删除").format(label) + "：" + f.path)
        if (!bridge.delete(f, recursive = true)) {
            throw PluginRuntimeException(L("删不掉：%s").format(path))
        }
    }

    // ------------------------------------------------------------ 命令

    override fun shellRun(command: String, cwd: String?, timeoutMs: Long): ShellResult {
        if (command.isBlank()) throw PluginRuntimeException(L("命令不能为空"))
        val launcher = ShellBackends.pick("auto", config)
            ?: throw PluginRuntimeException(L("没有可用的 Shell 后端"))
        val workdir = cwd?.takeIf { it.isNotBlank() }?.let {
            if (!File(it).isAbsolute) insideData(it)
            else sandbox.resolve(it)
        }
        gate.guard(
            PermKey.SHELL,
            workdir,
            L("%s 执行命令").format(label) + "：" + command,
            detail = buildString {
                append(L("后端：%s").format(launcher.label))
                if (workdir != null) append("\n").append(L("工作目录：%s").format(workdir.path))
                append("\n").append(L("超时：%s 秒").format(timeoutMs / 1000))
            },
            command = command,
            backend = launcher.id
        )
        ShellMirror.emit("\n[插件 ${plugin.id}] \$ $command\n")
        val result = runner.run(
            launcher, command, workdir?.path, timeoutMs.coerceIn(1_000L, 300_000L)
        )
        ShellMirror.emit(buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) append(result.stderr)
        })
        return result
    }

    // ------------------------------------------------------------ 网络

    private fun requireNetwork(): PluginNetwork =
        network ?: throw PluginRuntimeException(L("联网功能没有接上"))

    override fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): PluginHttpResponse = requireNetwork().request(
        tool = toolName,
        label = label,
        method = method,
        url = url,
        headers = headers,
        body = body
    )

    override fun httpStream(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long
    ): PluginHttpStream = requireNetwork().open(
        tool = toolName,
        label = label,
        method = method,
        url = url,
        headers = headers,
        body = body,
        timeoutMs = timeoutMs
    )

    // ------------------------------------------------------------ 其它工具

    override fun callTool(name: String, args: JsonObject): ToolResult? =
        toolsCall?.invoke(name, args)

    // ------------------------------------------------------------ 日志

    private val logLock = Any()

    override fun log(level: String, message: String) {
        val file = store.logFile(plugin.id)
        val line = buildString {
            append(System.currentTimeMillis())
            append(" [")
            append(level.uppercase().take(8))
            append("] ")
            append(message.take(4000))
        }
        synchronized(logLock) {
            runCatching {
                val lines = if (file.isFile) file.readLines() else emptyList()
                val next = (lines + line).takeLast(ToolsPlugin.MAX_LOG_LINES)
                file.writeText(next.joinToString("\n"))
            }
        }
    }

    // ------------------------------------------------------------ 路径

    /** 相对路径只能落在插件自己的 data/ 里。 */
    private fun insideData(path: String): File {
        val base = dataRoot.canonicalFile
        val f = File(base, path).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) {
            throw PluginRuntimeException(
                L("只能访问插件自己的数据目录（data/），不能访问 %s").format(path)
            )
        }
        return f
    }
}

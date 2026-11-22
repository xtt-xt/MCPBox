// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * 插件盘上的一份数据：`files/plugins/<id>/config.json`。
 *
 * 插件设置项和内部状态都放这一个文件里，`__` 开头的键是宿主用的，不参与设置页渲染：
 *
 * ```json
 * { "api_base": "https://example.com", "__key": "sk-xxxx", "__enabled": true,
 *   "__installedAt": 1790000000000 }
 * ```
 *
 * 一个插件一个文件，读写都直接落盘 —— 插件数量是个位数，没必要攒批。
 */
class PluginConfig(val file: File) {

    object Keys {
        const val KEY = "__key"
        const val ENABLED = "__enabled"
        const val INSTALLED_AT = "__installedAt"
        const val SOURCE = "__source"
    }

    @Volatile
    private var values: JsonObject = JsonObject(emptyMap())

    init { reload() }

    fun reload() {
        values = if (!file.isFile) JsonObject(emptyMap())
        else runCatching {
            J.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
    }

    fun all(): JsonObject = values

    /** 用户能看到、能改的设置（去掉 `__` 内部字段）。 */
    fun userValues(): Map<String, JsonElement> =
        values.filterKeys { !it.startsWith("__") }

    fun get(key: String): JsonElement? = values[key]

    fun getString(key: String): String? =
        (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun getBoolean(key: String, def: Boolean = false): Boolean =
        (values[key] as? JsonPrimitive)?.booleanOrNull ?: def

    fun getInt(key: String, def: Int = 0): Int =
        (values[key] as? JsonPrimitive)?.intOrNull ?: def

    fun set(key: String, value: JsonElement?) {
        val next = LinkedHashMap<String, JsonElement>(values)
        if (value == null) next.remove(key) else next[key] = value
        values = JsonObject(next)
        save()
    }

    fun remove(key: String) = set(key, null)

    val enabled: Boolean get() = getBoolean(Keys.ENABLED, true)

    fun setEnabled(on: Boolean) = set(Keys.ENABLED, JsonPrimitive(on))

    val key: String get() = getString(Keys.KEY).orEmpty()

    fun setKey(value: String) = set(Keys.KEY, JsonPrimitive(value))

    val installedAt: Long
        get() = (values[Keys.INSTALLED_AT] as? JsonPrimitive)?.let {
            it.content.toLongOrNull()
        } ?: 0L

    fun setInstalledAt(ms: Long) = set(Keys.INSTALLED_AT, JsonPrimitive(ms))

    /** 从哪儿装的（目录名 / 文件名），只用来在列表里显示来源。 */
    val source: String get() = getString(Keys.SOURCE).orEmpty()

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(values.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * 目录里一个已安装（或装坏了）的插件。
 *
 * [error] 非空表示 plugin.json 读不出来 —— 这种插件仍然留在列表里让用户看见并删掉，
 * 而不是静默消失。 [requirementError] 非空表示版本要求不满足：插件被禁用、路由关闭，
 * 但数据一个字节都不删。
 */
data class InstalledPlugin(
    val dir: File,
    val manifest: PluginManifest = PluginManifest(),
    val config: PluginConfig = PluginConfig(File(dir, PluginStore.CONFIG_NAME)),
    val error: String? = null,
    val requirementError: String? = null
) {
    val id: String get() = manifest.id.ifBlank { dir.name }

    val broken: Boolean get() = error != null

    val enabled: Boolean get() = config.enabled

    /** manifest 合法且版本满足。 */
    val usable: Boolean get() = error == null && requirementError == null

    /** 当前真的会被加载（启用 + 可用）。 */
    val active: Boolean get() = enabled && usable

    /** 「不兼容」：能读出来，但版本要求不满足。 */
    val incompatible: Boolean get() = error == null && requirementError != null

    fun tools(): List<PluginToolDecl> = if (usable) manifest.tools else emptyList()

    /**
     * 对外服务的路由。
     *
     * 三个条件缺一不可：插件本身生效、用户打开了「对外服务」、并且**授权过**。
     * 授权位（`__httpGranted`）是每个插件独立的，不能在审批弹窗里逐次点 ——
     * 装完默认不授权，用户第一次打开开关时才记下来。
     */
    fun routes(): List<PluginRouteDecl> =
        if (active && httpEnabled && httpGranted) manifest.http?.routes.orEmpty() else emptyList()

    /** 服务类开关：默认值来自 manifest，用户可以在插件页改。 */
    val httpEnabled: Boolean
        get() = manifest.http != null &&
            config.getBoolean(PluginStore.HTTP_ENABLED_KEY, manifest.http.enabledByDefault)

    /** 用户是否确认过「这个插件可以对外提供服务」。 */
    val httpGranted: Boolean
        get() = manifest.http != null &&
            config.getBoolean(PluginStore.HTTP_GRANTED_KEY, false)

    /** 现在真的在对外服务。 */
    val serving: Boolean get() = active && httpEnabled && httpGranted
}

/**
 * 插件目录（`files/plugins`）的扫描、校验、安装与卸载。
 *
 * 只碰文件，不碰运行时 —— 脚本怎么执行由 [PluginRuntime] 决定，App 注入 QuickJS，
 * harness 注入假实现。这样 mcpcore 依然不认识 Android。
 */
class PluginStore(
    private val root: File,
    /**
     * App 的 versionName（`1.5.0` 这种），用来判 `requires.mcpbox`。
     * 空字符串 = 不做版本检查（harness / 早期启动阶段）。
     */
    @Volatile var appVersion: String = ""
) {

    companion object {
        const val MANIFEST_NAME = "plugin.json"
        const val CONFIG_NAME = "config.json"
        const val TOOLS_DIR = "tools"
        const val API_DIR = "api"
        const val WEB_DIR = "web"
        const val DATA_DIR = "data"
        const val LOGS_DIR = "logs"

        /** 用户在插件页改「对外服务」开关时用的键。 */
        const val HTTP_ENABLED_KEY = "__httpEnabled"

        /** 用户确认过「可以对外提供服务」。装完默认没有，第一次开开关才写。 */
        const val HTTP_GRANTED_KEY = "__httpGranted"

        /** 一个插件最多几个工具 / 几条路由（防止 manifest 写飞）。 */
        const val MAX_TOOLS = 40
        const val MAX_ROUTES = 40
    }

    @Volatile
    var plugins: List<InstalledPlugin> = emptyList()
        private set

    /**
     * 每次重新扫描完目录后回调。
     *
     * 路由层用它来断开「已经不再对外服务」的插件的流式连接 ——
     * 停用、卸载、关掉对外服务都走这里，不需要 PluginStore 认识路由。
     */
    @Volatile
    var onReload: (() -> Unit)? = null

    init {
        root.mkdirs()
        load()
    }

    // ------------------------------------------------------------------ 扫描

    fun load() {
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()

        plugins = dirs.map { dir -> readPlugin(dir) }
        onReload?.invoke()
    }

    /** 读一个插件目录；读不出来也返回对象（带 error），不抛。 */
    private fun readPlugin(dir: File): InstalledPlugin {
        val manifestFile = File(dir, MANIFEST_NAME)
        val config = PluginConfig(File(dir, CONFIG_NAME))
        if (!manifestFile.isFile) {
            return InstalledPlugin(
                dir = dir, config = config,
                error = L("缺少 %s").format(MANIFEST_NAME)
            )
        }
        val manifest = try {
            PluginManifestParser.parse(manifestFile.readText())
        } catch (e: PluginException) {
            return InstalledPlugin(dir = dir, config = config, error = e.message)
        } catch (e: Exception) {
            return InstalledPlugin(
                dir = dir, config = config,
                error = L("读 %s 失败：%s").format(MANIFEST_NAME, e.message ?: "")
            )
        }
        // 目录名必须和 id 一致，否则卸载 / 升级会指错地方
        if (dir.name != manifest.id) {
            return InstalledPlugin(
                dir = dir, manifest = manifest, config = config,
                error = L("目录名「%s」和插件 id「%s」不一致").format(dir.name, manifest.id)
            )
        }
        val reqError = if (appVersion.isBlank()) null
        else PluginManifestParser.checkRequirement(manifest, appVersion)
        return InstalledPlugin(
            dir = dir, manifest = manifest, config = config, requirementError = reqError
        )
    }

    fun byId(id: String): InstalledPlugin? = plugins.firstOrNull { it.id == id }

    /** 现在真正生效的插件（启用 + 可用）。 */
    fun active(): List<InstalledPlugin> = plugins.filter { it.active }

    // ------------------------------------------------------------------ 安装 / 卸载

    /**
     * 从 [source] 目录安装（或升级）一个插件。
     *
     * @param replace 已存在同 id 时是否覆盖。覆盖保留原 config.json（设置和 Key 不丢），
     *                并把旧目录整个换掉。
     * @return 装好的插件。校验失败抛 [PluginException]。
     */
    fun install(source: File, replace: Boolean = true, sourceLabel: String = ""): InstalledPlugin {
        if (!source.isDirectory) throw PluginException(L("插件来源必须是一个目录"))
        val manifestFile = File(source, MANIFEST_NAME)
        if (!manifestFile.isFile) throw PluginException(L("目录里没有 %s").format(MANIFEST_NAME))

        val manifest = PluginManifestParser.parse(manifestFile.readText())
        PluginManifestParser.checkRequirement(manifest, appVersion)?.let {
            throw PluginException(it)
        }
        if (manifest.tools.size > MAX_TOOLS) {
            throw PluginException(L("工具太多了（最多 %s 个）").format(MAX_TOOLS))
        }
        if ((manifest.http?.routes?.size ?: 0) > MAX_ROUTES) {
            throw PluginException(L("路由太多了（最多 %s 条）").format(MAX_ROUTES))
        }
        // 脚本文件必须真的存在，别等到调用时才莫名其妙地失败
        manifest.tools.forEach { t ->
            if (!File(source, t.file).isFile) {
                throw PluginException(L("工具「%s」的脚本不存在：%s").format(t.name, t.file))
            }
        }
        manifest.http?.routes?.forEach { r ->
            if (!File(source, r.handler).isFile) {
                throw PluginException(L("路由「%s」的处理脚本不存在：%s").format(r.path, r.handler))
            }
        }

        // 跨插件冲突：工具名带 id 前缀，只要 id 不撞就没事；路由是全局的，必须查
        val routes = manifest.http?.routes.orEmpty()
        if (routes.isNotEmpty()) {
            plugins.filter { it.id != manifest.id && it.usable }.forEach { other ->
                other.manifest.http?.routes?.forEach { taken ->
                    val hit = routes.firstOrNull { ours ->
                        ours.path == taken.path ||
                            ours.path.startsWith(taken.path.trimEnd('/') + "/") ||
                            taken.path.startsWith(ours.path.trimEnd('/') + "/")
                    }
                    if (hit != null) {
                        throw PluginException(
                            L("路由「%s」和插件「%s」的「%s」冲突")
                                .format(hit.path, other.manifest.name, taken.path)
                        )
                    }
                }
            }
        }

        val target = File(root, manifest.id)
        if (target.exists() && !replace) {
            throw PluginException(L("已经装了「%s」，要覆盖请先卸载").format(manifest.name))
        }

        // 先完整复制到临时目录，成功了再换上去 —— 中途失败不会留下半个插件
        val staging = File(root, ".staging-${manifest.id}-${System.currentTimeMillis()}")
        try {
            staging.deleteRecursively()
            if (!source.copyRecursively(staging, overwrite = true)) {
                throw PluginException(L("复制插件文件失败"))
            }
            // 旧设置搬过来
            if (target.exists()) {
                val oldConfig = File(target, CONFIG_NAME)
                if (oldConfig.isFile) oldConfig.copyTo(File(staging, CONFIG_NAME), overwrite = true)
                target.deleteRecursively()
            }
            if (!staging.renameTo(target)) {
                throw PluginException(L("插件目录改名失败"))
            }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }

        val config = PluginConfig(File(target, CONFIG_NAME))
        if (config.installedAt == 0L) config.setInstalledAt(System.currentTimeMillis())
        if (sourceLabel.isNotBlank()) config.set(PluginConfig.Keys.SOURCE, JsonPrimitive(sourceLabel))
        ensureDirs(target)

        load()
        val plugin = byId(manifest.id) ?: throw PluginException(L("安装后读不到插件"))
        // 服务类插件装好就有 Key（客户端要拿它填进配置）
        if (plugin.manifest.http != null) ensureKey(plugin)
        return plugin
    }

    /**
     * 从 `.zip` 包安装。
     *
     * 解包到插件根目录下的临时目录（和最终目录同一个文件系统，`renameTo` 才快），
     * 装完就删 —— 失败不会留下半个插件，也不会把临时文件留在用户能看到的地方。
     *
     * 新装的插件**先保持停用**：用户该先看一眼它要什么权限、有几个工具，再自己打开。
     * 升级已经装过的插件则保留原来的开关状态。
     */
    fun installZip(zipFile: File, sourceLabel: String = ""): InstalledPlugin {
        if (!zipFile.isFile) throw PluginException(L("找不到插件包"))
        val staging = File(root, ".import-${System.currentTimeMillis()}")
        try {
            val src = zipFile.inputStream().use { PluginPackage.extract(it, staging) }
            val id = PluginManifestParser.parse(File(src, MANIFEST_NAME).readText()).id
            val existed = byId(id) != null
            val plugin = install(
                source = src,
                replace = true,
                sourceLabel = sourceLabel.ifBlank { zipFile.name }
            )
            if (!existed) {
                plugin.config.setEnabled(false)
                load()
            }
            return byId(plugin.id) ?: plugin
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** 卸掉一个插件：整目录删除（含它自己的 data/）。 */
    fun uninstall(id: String): Boolean {
        val plugin = byId(id) ?: return false
        if (!plugin.dir.deleteRecursively()) {
            throw PluginException(L("删除插件目录失败，请检查文件是否被占用"))
        }
        load()
        return true
    }

    fun setEnabled(id: String, on: Boolean): Boolean {
        val plugin = byId(id) ?: return false
        plugin.config.setEnabled(on)
        if (on) ensureKey(plugin)
        load()
        return true
    }

    /**
     * 插件的对外服务开关。
     *
     * 打开时同时记下授权位 —— 也就是「用户点过这个开关」本身就是同意，
     * 不需要再单独弹一次确认。
     */
    fun setHttpEnabled(id: String, on: Boolean): Boolean {
        val plugin = byId(id) ?: return false
        plugin.config.set(HTTP_ENABLED_KEY, JsonPrimitive(on))
        if (on) plugin.config.set(HTTP_GRANTED_KEY, JsonPrimitive(true))
        load()
        return true
    }

    /** 只改授权位，不动开关（导入 / 备份恢复时用）。 */
    fun setHttpGranted(id: String, granted: Boolean): Boolean {
        val plugin = byId(id) ?: return false
        plugin.config.set(HTTP_GRANTED_KEY, JsonPrimitive(granted))
        load()
        return true
    }

    // ------------------------------------------------------------------ 设置读写

    fun setSetting(id: String, key: String, value: JsonElement?) {
        val plugin = byId(id) ?: throw PluginException(L("插件不存在：%s").format(id))
        val decl = plugin.manifest.settings.firstOrNull { it.key == key }
            ?: throw PluginException(L("插件「%s」没有叫「%s」的设置项").format(plugin.manifest.name, key))
        val cleaned = coerceSetting(decl, value)
        plugin.config.set(key, cleaned)
    }

    /** 按声明类型把值转换一次，顺便挡住明显不合法的写入。 */
    private fun coerceSetting(decl: PluginSettingDecl, value: JsonElement?): JsonElement? {
        if (value == null) return null
        val p = value as? JsonPrimitive ?: return value
        return when (decl.type) {
            PluginSettingDecl.TYPE_INTEGER -> JsonPrimitive(
                p.content.toIntOrNull()
                    ?: throw PluginException(L("设置项「%s」要整数").format(decl.title))
            )
            PluginSettingDecl.TYPE_NUMBER -> JsonPrimitive(
                p.content.toDoubleOrNull()
                    ?: throw PluginException(L("设置项「%s」要数字").format(decl.title))
            )
            PluginSettingDecl.TYPE_BOOLEAN -> JsonPrimitive(
                (p.booleanOrNull ?: p.content.toBooleanStrictOrNull())
                    ?: throw PluginException(L("设置项「%s」要 true 或 false").format(decl.title))
            )
            PluginSettingDecl.TYPE_ENUM -> {
                if (p.content !in decl.options) {
                    throw PluginException(
                        L("设置项「%s」只能是：%s").format(decl.title, decl.options.joinToString("/"))
                    )
                }
                JsonPrimitive(p.content)
            }
            else -> JsonPrimitive(p.content)
        }
    }

    /** 某个插件的设置项当前值（含默认值），给 UI 渲染和 ctx.settings 用。 */
    fun settingValues(id: String): Map<String, JsonElement> {
        val plugin = byId(id) ?: return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        plugin.manifest.settings.forEach { decl ->
            plugin.config.get(decl.key)?.let { out[decl.key] = it }
                ?: decl.defaultJson()?.let { out[decl.key] = it }
        }
        return out
    }

    // ------------------------------------------------------------------ 插件 Key

    /** 取已有 Key；没有就生成一个（启用插件时自动调）。 */
    fun ensureKey(id: String): String {
        val plugin = byId(id) ?: throw PluginException(L("插件不存在：%s").format(id))
        return ensureKey(plugin)
    }

    private fun ensureKey(plugin: InstalledPlugin): String {
        val existing = plugin.config.key
        if (existing.isNotBlank()) return existing
        val prefix = plugin.manifest.http?.key?.prefix.orEmpty()
        val key = prefix + Tokens.generateToken(32)
        plugin.config.setKey(key)
        return key
    }

    fun keyOf(id: String): String? =
        byId(id)?.config?.key?.takeIf { it.isNotBlank() }

    /** 换一把：旧 Key 立刻失效。 */
    fun rotateKey(id: String): String {
        val plugin = byId(id) ?: throw PluginException(L("插件不存在：%s").format(id))
        val prefix = plugin.manifest.http?.key?.prefix.orEmpty()
        val key = prefix + Tokens.generateToken(32)
        plugin.config.setKey(key)
        return key
    }

    fun clearKey(id: String) {
        byId(id)?.config?.setKey("")
    }

    // ------------------------------------------------------------------ 目录

    /** 插件自己的数据目录 / 日志目录，首次使用时创建。 */
    fun dataDir(id: String): File = ensureDirs(File(root, id))

    fun logFile(id: String): File = File(File(root, id).also { ensureDirs(it) }, LOGS_DIR + "/plugin.log")

    private fun ensureDirs(dir: File): File {
        dir.mkdirs()
        File(dir, DATA_DIR).mkdirs()
        File(dir, LOGS_DIR).mkdirs()
        return dir
    }

    /** 给 App 看的插件根目录（导入导出插件包时用）。 */
    val rootDir: File get() = root
}

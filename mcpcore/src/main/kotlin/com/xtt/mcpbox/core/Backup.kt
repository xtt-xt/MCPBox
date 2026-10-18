// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复。
 *
 * 一份备份由三「部分」组成，各自是一段独立的 JSON：
 *  - [Part.MEMORY]        记忆库（实体 / 观察 / 关系）
 *  - [Part.SETTINGS]      全部设置键值（可选带访问令牌）
 *  - [Part.CUSTOM_TOOLS]  自定义工具
 *  - [Part.STATS]         使用统计（请求次数 / 运行时长 / 启动次数）
 *
 * 打包成 zip 时多放一个 `manifest.json`，里面写清楚有哪些部分、含不含令牌；
 * 单独导出时就是一段裸 JSON（靠 [_type] 字段自报家门，导入时用来嗅探类型）。
 */
object Backup {

    const val TYPE_SETTINGS = "mcpbox.settings"
    const val TYPE_MANIFEST = "mcpbox.backup"
    const val TYPE_TOOLS = "mcpbox.custom-tools"
    /** 统计（请求热力图 / 运行时长 / 启动次数）。类型常量的正主在 [StatsStore]。 */
    const val TYPE_STATS = StatsStore.TYPE
    /** 自定义背景：外观与语言 → 背景 里的全部选项 +（可选）背景图片 / 视频的副本。 */
    const val TYPE_BACKDROP = "mcpbox.backdrop"

    /** 备份/恢复的粒度。 */
    enum class Part(val id: String, val file: String) {
        MEMORY("memory", "memory.json"),
        SETTINGS("settings", "settings.json"),
        CUSTOM_TOOLS("custom_tools", "custom-tools.json"),
        STATS("stats", "stats.json"),

        /**
         * 自定义背景。
         *
         * 它是「设置」里的一小块，但**单独成一部分**：因为要连带背景文件（图片 / 视频）的
         * 二进制副本一起打包 —— 设置那一段是纯 JSON，塞不下（也不可能 base64 一个几百 MB 的视频）。
         * zip 里这些文件固定放在 `backdrop/` 子文件夹下。
         */
        BACKDROP("backdrop", "backdrop.json");

        companion object {
            fun of(id: String): Part? = entries.firstOrNull { it.id == id }
        }
    }

    /** 恢复模式：合并 = 只动备份里提到的；覆盖 = 先清空再写入。 */
    enum class Mode(val id: String) {
        MERGE("merge"),
        REPLACE("replace");

        companion object {
            fun of(id: String): Mode = entries.firstOrNull { it.id == id } ?: MERGE
        }
    }

    private val J = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val JCompact = Json { ignoreUnknownKeys = true }

    /** 访问令牌的键 —— 备份时可选包含、恢复时可选跳过。 */
    val TOKEN_KEY = Config.Keys.TOKEN

    // ---------------------------------------------------------------- 嗅探

    /**
     * 一段 JSON 是哪一部分？
     *
     * 先看显式的 `_type`（新格式都带），没有就按字段猜 ——
     * 这样 1.1.0-55 那种老记忆文件（裸 `{entities, relations}`）也认。
     */
    fun sniff(text: String): Part? {
        val obj = runCatching { JCompact.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = obj["_type"]?.jsonPrimitive?.contentOrNull
        when (type) {
            TYPE_SETTINGS -> return Part.SETTINGS
            TYPE_TOOLS -> return Part.CUSTOM_TOOLS
            TYPE_STATS -> return Part.STATS
            TYPE_BACKDROP -> return Part.BACKDROP
            else -> {}
        }
        return when {
            obj.containsKey("entities") || obj.containsKey("relations") -> Part.MEMORY
            obj["values"] is JsonObject -> Part.SETTINGS
            obj["tools"] is JsonArray -> Part.CUSTOM_TOOLS
            // 统计：按「有 requests 表 + 有 appLaunches」认（老文件没有 _type 也认得出来）
            obj["requests"] is JsonObject && obj.containsKey("appLaunches") -> Part.STATS
            // 背景：靠 backdrop_* 键认（老文件没有 _type 也认得出来）
            obj["values"] is JsonObject &&
                (obj["values"] as JsonObject).keys.any { it.startsWith("backdrop_") } -> Part.BACKDROP
            else -> null
        }
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 设置部分：值按类型原样存，恢复时再按类型写回。
     *
     * **必须额外记一份 `types`**：JSON 的数字不区分 int / long，
     * `approval_timeout = 300000` 导出再读回来会变成 Int，
     * 而 SharedPreferences 是强类型的 —— `getLong()` 读到 Integer 会直接
     * ClassCastException 崩掉。所以这里把每个键的原始类型标出来。
     */
    fun exportSettings(settings: SettingsSource, includeToken: Boolean): String {
        val entries = settings.all().filterKeys { includeToken || it != TOKEN_KEY }
        val values = buildJsonObject { entries.forEach { (k, v) -> putValue(this, k, v) } }
        val types = buildJsonObject { entries.forEach { (k, v) -> put(k, typeName(v)) } }
        return J.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_type", TYPE_SETTINGS)
                put("version", 1)
                put("app", ServerMeta.fullVersion)
                put("exportedAt", System.currentTimeMillis())
                put("tokenIncluded", includeToken)
                put("types", types)
                put("values", values)
            }
        )
    }

    /** 一个值的类型名，恢复时靠它把数字写回正确的 Java 类型。 */
    private fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float, is Double -> "double"
        else -> "string"
    }

    private fun putValue(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: Any?) {
        when (value) {
            null -> builder.put(key, JsonNull)
            is Boolean -> builder.put(key, value)
            is Int -> builder.put(key, value)
            is Long -> builder.put(key, value)
            is Float -> builder.put(key, value.toDouble())
            is Double -> builder.put(key, value)
            is Number -> builder.put(key, value.toDouble())
            else -> builder.put(key, value.toString())
        }
    }

    // ---------------------------------------------------------------- 背景

    /** 「外观与语言 → 背景」里的全部偏好键（导出 / 恢复都只碰这些）。 */
    val BACKDROP_KEYS = listOf(
        "backdrop_enabled",
        "backdrop_kind",
        "backdrop_file",
        "backdrop_tint",
        "backdrop_blur",
        "backdrop_scale",
        "backdrop_subpages",
        "backdrop_card_alpha",
        "backdrop_bar_alpha",
        "backdrop_chrome_tint"
    )

    /**
     * 背景部分：选项 +（可选）文件清单。
     *
     * 文件本体不进 JSON（视频可能几百 MB），而是作为 zip 里的 `backdrop/<文件名>` 条目单独放；
     * 这里只记名字和大小，UI 用来显示「带了哪些文件」。
     *
     * @param files 要一起打包的背景文件（空 = 只备份选项，恢复后需要重新选图 / 视频）
     */
    fun exportBackdrop(settings: SettingsSource, files: List<Pair<String, Long>>): String {
        val all = settings.all()
        val values = buildJsonObject {
            BACKDROP_KEYS.forEach { k -> if (all.containsKey(k)) putValue(this, k, all[k]) }
        }
        val types = buildJsonObject { BACKDROP_KEYS.forEach { k -> put(k, typeName(all[k])) } }
        return J.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_type", TYPE_BACKDROP)
                put("version", 1)
                put("app", ServerMeta.fullVersion)
                put("exportedAt", System.currentTimeMillis())
                put("values", values)
                put("types", types)
                put("files", JsonArray(files.map { (name, size) ->
                    buildJsonObject {
                        put("name", name)
                        put("size", size)
                    }
                }))
            }
        )
    }

    /** 这一段设置里有没有带令牌。 */
    fun settingsHasToken(text: String): Boolean {
        val obj = runCatching { JCompact.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        if (obj["tokenIncluded"]?.jsonPrimitive?.booleanOrNull == true) return true
        return (obj["values"] as? JsonObject)?.containsKey(TOKEN_KEY) == true
    }

    /**
     * 打包成 zip：manifest + 各个部分的文件 +（可选）背景文件的二进制副本。
     *
     * **两种结构（[nested]）**：
     *  · `nested = true`（导出**整体** zip）：每个部分一个**子文件夹** ——
     *    `memory/memory.json`、`settings/settings.json`、`backdrop/backdrop.json` …
     *  · `nested = false`（导出**单部分**）：manifest 加这一部分的文件，平铺在根目录 ——
     *    单部分本来就只有一个文件，再套一层目录反而要多点一次
     * 两种结构都是压缩包（以前单部分是裸 `.json`，装不下背景的图片 / 视频）。
     *
     * 背景的二进制副本固定放 `backdrop/files/<名字>`（`image_…` / `video_…`）——
     * 和其它部分是同一个思路：一个部分一个目录，附件在它自己的 `files/` 里。
     *
     * @param blobs 附加文件，键 = 背景文件在 `files/backdrop/` 里的名字
     */
    fun zip(
        parts: Map<Part, String>,
        includeToken: Boolean,
        exportedAt: Long = System.currentTimeMillis(),
        blobs: Map<String, ByteArray> = emptyMap(),
        nested: Boolean = true
    ): ByteArray {
        val manifest = J.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_type", TYPE_MANIFEST)
                put("version", 2)
                put("app", ServerMeta.fullVersion)
                put("exportedAt", exportedAt)
                put("tokenIncluded", includeToken)
                put("nested", nested)
                put("parts", JsonArray(parts.keys.map { JsonPrimitive(it.id) }))
            }
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun putEntry(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
            putEntry("manifest.json", manifest.toByteArray())
            parts.forEach { (part, text) ->
                val path = if (nested) "${part.id}/${part.file}" else part.file
                putEntry(path, text.toByteArray())
            }
            // 背景附件：一个部分一个目录（backdrop/files/…）
            blobs.forEach { (name, bytes) -> putEntry("backdrop/files/$name", bytes) }
        }
        return out.toByteArray()
    }

    /** 一个解出来的备份包。 */
    data class Bundle(
        val parts: Map<Part, String>,
        val tokenIncluded: Boolean,
        val exportedAt: Long,
        val app: String?,
        /** zip 里 `backdrop/` 下的文件（键 = 文件名）。 */
        val blobs: Map<String, ByteArray> = emptyMap()
    )

    /**
     * 读 zip。不是合法备份包就抛异常（让 UI 提示用户）。
     *
     * 三种结构都能读（判断只看「文件名」和「在不在 backdrop/ 下」，不看目录层级）：
     *  · 新·整体：`<part.id>/<file>` + `backdrop/files/<附件>`
     *  · 新·单部分：`<file>` + `backdrop/files/<附件>`
     *  · 老版本：`<file>`（全平铺）+ `backdrop/<附件>`
     */
    fun readZip(bytes: ByteArray): Bundle {
        val files = LinkedHashMap<String, String>()
        val blobs = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val data = zis.readBytes()
                // 背景附件是二进制（图片 / 视频），别当文本读。
                // `.json` 一律当文本 —— `backdrop/backdrop.json` 是背景那一部分的正文，
                // 不能和附件混在一起（以前按 startsWith("backdrop/") 判断，会把它也当附件吞掉）。
                val isBlob = entry.name.startsWith("backdrop/") && !name.endsWith(".json")
                if (isBlob) blobs[name] = data else files[name] = data.toString(Charsets.UTF_8)
            }
        }
        if (files.isEmpty()) throw ToolFailure(L("这个 zip 里没有文件"))

        // zip 里没有 manifest 也认：按文件名 / 内容嗅探
        val manifest = files["manifest.json"]?.let {
            runCatching { JCompact.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val parts = LinkedHashMap<Part, String>()
        Part.entries.forEach { part ->
            val text = files[part.file] ?: files.entries.firstOrNull { (n, _) ->
                n.endsWith(".json") && sniff(files[n] ?: "") == part
            }?.value
            if (text != null) parts[part] = text
        }
        if (parts.isEmpty()) throw ToolFailure(L("这个 zip 里没有认得出来的备份内容"))

        val tokenIncluded = manifest?.get("tokenIncluded")?.jsonPrimitive?.booleanOrNull
            ?: parts[Part.SETTINGS]?.let { settingsHasToken(it) }
            ?: false
        val exportedAt = manifest?.get("exportedAt")?.jsonPrimitive?.longOrNull ?: 0L
        val app = manifest?.get("app")?.jsonPrimitive?.contentOrNull
        return Bundle(parts, tokenIncluded, exportedAt, app, blobs)
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 把一段备份写回去。
     *
     * 设置部分的「覆盖」模式会先清空所有设置再写入 —— 但**令牌例外**：
     * 没勾「同时恢复访问令牌」时，无论合并还是覆盖，令牌都保持原样，
     * 免得恢复个备份把客户端全踢下线。
     */
    fun apply(
        part: Part,
        text: String,
        mode: Mode,
        restoreToken: Boolean,
        settings: SettingsSource,
        memory: MemoryStore,
        customTools: CustomToolStore,
        stats: StatsStore = StatsStore(),
        /** 背景文件要落到的目录（`files/backdrop/`）；恢复背景时必填。 */
        backdropDir: java.io.File? = null,
        /** zip 里 `backdrop/` 下的文件（键 = 文件名）。 */
        blobs: Map<String, ByteArray> = emptyMap()
    ): String = when (part) {
        Part.MEMORY -> {
            val r = memory.importJson(text, merge = mode == Mode.MERGE)
            r.describe()
        }

        Part.CUSTOM_TOOLS -> {
            val r = customTools.importJson(text, replace = mode == Mode.REPLACE)
            r.message
        }

        Part.STATS -> stats.importJson(text, merge = mode == Mode.MERGE)

        Part.SETTINGS -> restoreSettings(text, mode, restoreToken, settings)

        Part.BACKDROP -> restoreBackdrop(text, settings, backdropDir, blobs)
    }

    /**
     * 恢复自定义背景：先把选项写回偏好，再（有文件的话）把副本落进 `files/backdrop/`。
     *
     * 口径：
     *  · 选项**只覆盖备份里有的那几个键**（不动别的设置）；
     *  · 有文件副本时先清空 `files/backdrop/` 再写入 —— 和「恢复成备份那一刻的样子」一致；
     *    没带文件时**保留**现有背景文件（只是把选项对齐，图 / 视频还在）；
     *  · 最后按 `backdrop_kind` 把 `backdrop_file` 指到对应类型的那份，避免「切了类型什么都不显示」。
     */
    private fun restoreBackdrop(
        text: String,
        settings: SettingsSource,
        dir: java.io.File?,
        blobs: Map<String, ByteArray>
    ): String {
        val obj = runCatching { JCompact.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ToolFailure(L("不是合法的背景备份：%s").format(it.message))
        }
        val values = obj["values"] as? JsonObject
            ?: throw ToolFailure(L("背景备份里没有 values 字段"))
        val types = obj["types"] as? JsonObject
        val before = settings.all()
        values.forEach { (k, v) -> settings.putRaw(k, coerce(k, v, types, before[k])) }

        var written = 0
        if (blobs.isNotEmpty() && dir != null) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            blobs.forEach { (name, bytes) ->
                runCatching {
                    java.io.File(dir, name).writeBytes(bytes)
                    written++
                }
            }
        }

        // 把 backdrop_file 对准当前类型那份（文件名带 image_/video_ 前缀）
        val kind = settings.getString("backdrop_kind", "image") ?: "image"
        val pick = dir?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("${kind}_") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
        if (pick != null) settings.putString("backdrop_file", pick.name)

        return if (written > 0) {
            L("已恢复背景（含 %s 个文件）").format(written)
        } else {
            L("已恢复背景设置（没带文件，原来的图片 / 视频还在）")
        }
    }

    private fun restoreSettings(
        text: String,
        mode: Mode,
        restoreToken: Boolean,
        settings: SettingsSource
    ): String {
        val obj = runCatching { JCompact.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ToolFailure(L("不是合法的设置备份：%s").format(it.message))
        }
        val values = (obj["values"] as? JsonObject)
            ?: throw ToolFailure(L("设置备份里没有 values 字段"))
        val keepToken = settings.getString(TOKEN_KEY, null) ?: ""

        val types = obj["types"] as? JsonObject
        val before = settings.all()
        val incoming = LinkedHashMap<String, Any?>()
        values.forEach { (k, v) ->
            incoming[k] = coerce(k, v, types, before[k])
        }

        if (!restoreToken) incoming.remove(TOKEN_KEY)

        if (mode == Mode.REPLACE) {
            // 整份替换：先清空，再把令牌放回来（没勾恢复令牌时）
            settings.clearAll()
            if (!restoreToken && keepToken.isNotEmpty()) settings.putString(TOKEN_KEY, keepToken)
        }
        incoming.forEach { (k, v) -> settings.putRaw(k, v) }

        val count = incoming.size
        return L("已恢复 %s 项设置（%s）").format(count, L(if (mode == Mode.MERGE) "合并" else "覆盖"))
    }

    /**
     * 把 JSON 里的值转成该键「原本的 Java 类型」。
     *
     * 优先看备份自带的 `types`；老备份没有这一栏时，就**跟随当前这个键的类型**
     * （比如当前 approval_timeout 是 Long，备份里是 300000，那就写回 Long）——
     * 实在没有参照才按数字范围猜。这一步是防 ClassCastException 的关键。
     */
    private fun coerce(
        key: String,
        el: kotlinx.serialization.json.JsonElement,
        types: JsonObject?,
        existing: Any?
    ): Any? {
        val p = el as? JsonPrimitive ?: return el.toString()
        val declared = types?.get(key)?.jsonPrimitive?.contentOrNull
        // 声明了类型就严格照做
        when (declared) {
            "boolean" -> return p.booleanOrNull ?: p.content.equals("true", true)
            "int" -> return p.intOrNull ?: p.doubleOrNull?.toInt() ?: 0
            "long" -> return p.longOrNull ?: p.doubleOrNull?.toLong() ?: 0L
            "double" -> return p.doubleOrNull ?: 0.0
            "string" -> return p.content
            "null" -> return null
        }
        if (p.isString) return p.content
        p.booleanOrNull?.let { return it }
        if (existing is Long) return p.longOrNull ?: p.doubleOrNull?.toLong() ?: return p.content
        if (existing is Int) return p.intOrNull ?: p.doubleOrNull?.toInt() ?: return p.content
        if (existing is Boolean) return p.booleanOrNull ?: return p.content
        // 没有参照：能塞进 Int 就 Int，否则 Long，带小数点的当 Double
        p.intOrNull?.let { return it }
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { return it }
        return p.content
    }
}

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
 *
 * 打包成 zip 时多放一个 `manifest.json`，里面写清楚有哪些部分、含不含令牌；
 * 单独导出时就是一段裸 JSON（靠 [_type] 字段自报家门，导入时用来嗅探类型）。
 */
object Backup {

    const val TYPE_SETTINGS = "mcpbox.settings"
    const val TYPE_MANIFEST = "mcpbox.backup"
    const val TYPE_TOOLS = "mcpbox.custom-tools"

    /** 备份/恢复的粒度。 */
    enum class Part(val id: String, val file: String) {
        MEMORY("memory", "memory.json"),
        SETTINGS("settings", "settings.json"),
        CUSTOM_TOOLS("custom_tools", "custom-tools.json");

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
            else -> {}
        }
        return when {
            obj.containsKey("entities") || obj.containsKey("relations") -> Part.MEMORY
            obj["values"] is JsonObject -> Part.SETTINGS
            obj["tools"] is JsonArray -> Part.CUSTOM_TOOLS
            else -> null
        }
    }

    // ---------------------------------------------------------------- 导出

    /** 设置部分：值按类型原样存，恢复时再按类型写回。 */
    fun exportSettings(settings: SettingsSource, includeToken: Boolean): String {
        val values = buildJsonObject {
            settings.all()
                .filterKeys { includeToken || it != TOKEN_KEY }
                .forEach { (k, v) -> putValue(this, k, v) }
        }
        return J.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_type", TYPE_SETTINGS)
                put("version", 1)
                put("app", ServerMeta.fullVersion)
                put("exportedAt", System.currentTimeMillis())
                put("tokenIncluded", includeToken)
                put("values", values)
            }
        )
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

    /** 这一段设置里有没有带令牌。 */
    fun settingsHasToken(text: String): Boolean {
        val obj = runCatching { JCompact.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        if (obj["tokenIncluded"]?.jsonPrimitive?.booleanOrNull == true) return true
        return (obj["values"] as? JsonObject)?.containsKey(TOKEN_KEY) == true
    }

    /** 打包成 zip：manifest + 各个部分的文件。 */
    fun zip(
        parts: Map<Part, String>,
        includeToken: Boolean,
        exportedAt: Long = System.currentTimeMillis()
    ): ByteArray {
        val manifest = J.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_type", TYPE_MANIFEST)
                put("version", 1)
                put("app", ServerMeta.fullVersion)
                put("exportedAt", exportedAt)
                put("tokenIncluded", includeToken)
                put("parts", JsonArray(parts.keys.map { JsonPrimitive(it.id) }))
            }
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
            parts.forEach { (part, text) ->
                zos.putNextEntry(ZipEntry(part.file))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 一个解出来的备份包。 */
    data class Bundle(
        val parts: Map<Part, String>,
        val tokenIncluded: Boolean,
        val exportedAt: Long,
        val app: String?
    )

    /** 读 zip。不是合法备份包就抛异常（让 UI 提示用户）。 */
    fun readZip(bytes: ByteArray): Bundle {
        val files = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                files[name] = zis.readBytes().toString(Charsets.UTF_8)
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
        return Bundle(parts, tokenIncluded, exportedAt, app)
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
        customTools: CustomToolStore
    ): String = when (part) {
        Part.MEMORY -> {
            val r = memory.importJson(text, merge = mode == Mode.MERGE)
            r.describe()
        }

        Part.CUSTOM_TOOLS -> {
            val r = customTools.importJson(text, replace = mode == Mode.REPLACE)
            r.message
        }

        Part.SETTINGS -> restoreSettings(text, mode, restoreToken, settings)
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

        val incoming = LinkedHashMap<String, Any?>()
        values.forEach { (k, v) -> incoming[k] = jsonToValue(v) }

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

    private fun jsonToValue(el: kotlinx.serialization.json.JsonElement): Any? {
        val p = el as? JsonPrimitive ?: return el.toString()
        if (p.isString) return p.content
        p.booleanOrNull?.let { return it }
        p.intOrNull?.let { return it }
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { return it }
        return p.content
    }
}

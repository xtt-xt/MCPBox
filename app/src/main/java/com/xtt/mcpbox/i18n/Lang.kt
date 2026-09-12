// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.i18n

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 轻量多语言。
 *
 * 设计上直接**用中文原文当键**：`L("设置")` → 英文下返回 "Settings"。
 * 好处是不用维护一堆 key 常量，界面上看着也还是可读的中文；
 * 查不到翻译时**原样回退中文**，永远不会露出奇怪的 key。
 *
 * 语言包就是一个「中文 → 译文」的 JSON 映射，可以导出模板、翻译后导入。
 */
object Lang {

    const val ZH = "zh"
    const val EN = "en"

    /** 彩蛋语言：猫娘语。 */
    const val CAT = "cat"

    /** 当前生效语言：zh / en / 其它（导入的语言包 id）。 */
    @Volatile var current: String = ZH
        private set

    /** 跟随系统时，是否用英文（系统语言不是中文就当作英文）。 */
    var systemIsEnglish: Boolean = false

    /** 用户导入的语言包：语言 id → (中文 → 译文)。 */
    private val packs = mutableMapOf<String, Map<String, String>>()

    /** 内置英文（随包发布）。 */
    private var builtin: Map<String, String> = emptyMap()

    /** 由 AppCore 在启动时调用。 */
    fun init(ctx: Context, langId: String, englishOnly: Boolean) {
        builtin = LangEn.ENTRIES
        systemIsEnglish = englishOnly
        loadPacks(ctx)
        current = resolve(langId)
    }

    /** `system` → 按系统语言落到 zh 或 en。 */
    fun resolve(langId: String): String = when (langId) {
        "system" -> if (systemIsEnglish) EN else ZH
        else -> langId
    }

    /** 翻译。查不到就返回原文。 */
    fun t(zh: String): String {
        if (current == ZH) return zh
        if (current == CAT) return LangCat.say(zh)
        packs[current]?.get(zh)?.let { return it }
        if (current == EN) builtin[zh]?.let { return it }
        return zh
    }

    /** 这个语言有没有可用的词条。 */
    fun hasPack(langId: String): Boolean = langId == EN || packs.containsKey(langId)

    fun packLanguages(): List<String> = packs.keys.sorted()

    /** 猫娘语是内置的彩蛋语言，解锁后可用。 */
    fun catAvailable(ctx: android.content.Context): Boolean =
        AndroidCatFlag.unlocked

    /** 由 AppCore 在启动时写入（避免 i18n 层依赖 Prefs）。 */
    object AndroidCatFlag {
        @Volatile var unlocked: Boolean = false
    }

    fun entriesOf(langId: String): Map<String, String> = when (langId) {
        EN -> builtin
        else -> packs[langId] ?: emptyMap()
    }

    // ------------------------------------------------------------ 语言包文件

    private fun packFile(ctx: Context, langId: String) = File(ctx.filesDir, "lang_$langId.json")

    private fun loadPacks(ctx: Context) {
        packs.clear()
        runCatching {
            ctx.filesDir.listFiles { f -> f.name.startsWith("lang_") && f.name.endsWith(".json") }
                ?.forEach { file ->
                    val id = file.name.removePrefix("lang_").removeSuffix(".json")
                    parseEntries(file.readText())?.let { packs[id] = it }
                }
        }
    }

    private fun parseEntries(json: String): Map<String, String>? = runCatching {
        val root = JSONObject(json)
        val obj = root.optJSONObject("entries") ?: root
        buildMap {
            obj.keys().forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } }
        }
    }.getOrNull()

    /**
     * 导出模板：带上所有已知中文原文（内置英文的词条 + 界面上出现过的），
     * 译文留空，方便照着填。
     */
    fun exportTemplate(ctx: Context, langId: String): String {
        val obj = JSONObject()
        obj.put("lang", langId)
        obj.put("name", langId)
        obj.put("version", 1)
        val entries = JSONObject()
        val existing = entriesOf(langId)
        // 内置英文的键就是当前界面上用到的全部中文
        builtin.keys.sorted().forEach { zh -> entries.put(zh, existing[zh] ?: "") }
        obj.put("entries", entries)
        return obj.toString(2)
    }

    /** 导入语言包，返回导入的词条数。 */
    fun importPack(ctx: Context, json: String, langIdHint: String? = null): Result<Pair<String, Int>> {
        return runCatching {
            val root = JSONObject(json)
            val id = root.optString("lang").ifBlank { langIdHint ?: "custom" }
            val map = parseEntries(json) ?: throw IllegalArgumentException("没有读到 entries")
            if (map.isEmpty()) throw IllegalArgumentException("语言包里一条译文都没有")
            packFile(ctx, id).writeText(json)
            packs[id] = map
            id to map.size
        }
    }

    /** 导出当前语言包（用于分享给别人）。 */
    fun exportPack(ctx: Context, langId: String): String {
        val obj = JSONObject()
        obj.put("lang", langId)
        obj.put("name", langId)
        obj.put("version", 1)
        val entries = JSONObject()
        entriesOf(langId).forEach { (k, v) -> entries.put(k, v) }
        obj.put("entries", entries)
        return obj.toString(2)
    }

    fun deletePack(ctx: Context, langId: String) {
        packFile(ctx, langId).delete()
        packs.remove(langId)
    }
}

/** 界面里到处都要用，短一点。 */
fun L(zh: String): String = Lang.t(zh)

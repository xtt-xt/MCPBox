// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 一份「会话状态」：某个 profile 下激活了哪些工具包。
 */
@Serializable
data class ProfileState(
    val id: String = ProfileStore.DEFAULT_ID,
    /** 当前激活的包 id。 */
    val active: List<String> = emptyList(),
    /** 最后一次活动时间（用来做 TTL 判定）。 */
    val updatedAt: Long = 0L
)

/**
 * 会话状态的存储。
 *
 * ## 为什么用「URL profile」而不是「MCP session」
 *
 * MCP 协议层面**无法感知「AI 开了新对话」**：客户端启动时 `initialize` 一次，
 * 之后所有对话共用同一条连接、同一个 `Mcp-Session-Id`，服务端收不到新对话的信号。
 *
 * 所以这里用请求路径来区分：客户端把地址配成 `/mcp/p/<名字>`，
 * 不同地址 = 不同 profile = 不同激活状态。好处是完全可控、可读、可持久化。
 *
 * ## TTL 兜底
 *
 * 开了 TTL 之后，某个 profile 超过 N 分钟没有请求，就自动回到默认包集。
 * 这样「隔一阵开新对话」也能拿到干净状态，不用手动重置。
 * 一直在用则不会过期（每次请求都会刷新时间戳）。想完全手动控制就把 TTL 关掉。
 */
class ProfileStore(
    private val dir: File?,
    private val config: Config
) {

    @Volatile private var states: Map<String, ProfileState> = emptyMap()

    /** 上次落盘时间，避免每次请求都写磁盘。 */
    @Volatile private var lastFlush: Long = 0L

    private val lock = Any()

    init { load() }

    // ------------------------------------------------------------------ 持久化

    private fun fileOf(id: String): File? {
        val d = dir ?: return null
        return File(d, "${sanitize(id)}.json")
    }

    fun load() {
        val d = dir ?: return
        val map = LinkedHashMap<String, ProfileState>()
        runCatching {
            d.mkdirs()
            d.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                val id = f.name.removeSuffix(".json")
                val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                runCatching {
                    J.decodeFromJsonElement(ProfileState.serializer(), J.parseToJsonElement(text))
                }.getOrNull()?.let { map[id] = it.copy(id = id) }
            }
        }
        synchronized(lock) { states = map }
    }

    private fun flush(force: Boolean = false) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < FLUSH_INTERVAL_MS) return
        lastFlush = now
        runCatching {
            d.mkdirs()
            synchronized(lock) { states }.forEach { (id, st) ->
                val f = File(d, "${sanitize(id)}.json")
                val tmp = File(d, "${sanitize(id)}.json.tmp")
                tmp.writeText(J.encodeToString(ProfileState.serializer(), st))
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    // -------------------------------------------------------------------- 查询

    /** 所有用过的 profile 名。 */
    fun ids(): List<String> = states.keys.sorted()

    fun stateOf(profileId: String): ProfileState {
        val id = sanitize(profileId)
        return states[id] ?: ProfileState(id = id, active = defaults(), updatedAt = 0L)
    }

    private fun defaults(): List<String> = BuiltinPacks.defaults().toList()

    /**
     * 取某个 profile 当前激活的包，顺带处理 TTL 与时间戳刷新。
     * 这是热路径（每次 MCP 请求都会走）。
     */
    fun active(profileId: String): Set<String> {
        val id = sanitize(profileId)
        val now = System.currentTimeMillis()
        var expired = false

        val result = synchronized(lock) {
            val cur = states[id] ?: ProfileState(id = id, active = defaults(), updatedAt = now)
            val tooOld = config.profileTtlEnabled &&
                cur.updatedAt > 0 &&
                now - cur.updatedAt > config.profileTtlMinutes * 60_000L
            val next = if (tooOld) {
                expired = true
                cur.copy(active = defaults())
            } else cur
            val touched = next.copy(updatedAt = now)
            states = states + (id to touched)
            touched.active.toSet()
        }

        if (expired) {
            // 过期重置是个有意义的状态变化，立刻落盘
            flush(force = true)
        } else {
            flush(force = false)
        }
        return result
    }

    /** 测试用：把一个 profile 的时间戳往前拨，模拟「很久没动过」。 */
    fun expireForTest(profileId: String, minutesAgo: Int) {
        val id = sanitize(profileId)
        synchronized(lock) {
            val cur = states[id] ?: return
            states = states + (id to cur.copy(updatedAt = System.currentTimeMillis() - minutesAgo * 60_000L))
        }
    }

    /**
     * 只读地看激活状态，**不刷新时间戳**。
     * UI 用它展示，免得「打开权限页看一眼」就把 TTL 给续上了。
     */
    fun peek(profileId: String): List<String> {
        val id = sanitize(profileId)
        return states[id]?.active ?: defaults()
    }

    /** TTL 有没有把这个 profile 重置过（UI 上给个提示用）。 */
    fun ttlExpired(profileId: String, now: Long = System.currentTimeMillis()): Boolean {
        val st = states[sanitize(profileId)] ?: return false
        if (!config.profileTtlEnabled) return false
        return st.updatedAt > 0 && now - st.updatedAt > config.profileTtlMinutes * 60_000L
    }

    // -------------------------------------------------------------------- 修改

    fun activate(profileId: String, packId: String): Boolean {
        val id = sanitize(profileId)
        var changed = false
        synchronized(lock) {
            val cur = states[id] ?: ProfileState(id = id, active = defaults(), updatedAt = System.currentTimeMillis())
            if (packId !in cur.active) {
                states = states + (id to cur.copy(
                    active = cur.active + packId,
                    updatedAt = System.currentTimeMillis()
                ))
                changed = true
            }
        }
        if (changed) flush(force = true)
        return changed
    }

    fun deactivate(profileId: String, packId: String): Boolean {
        val id = sanitize(profileId)
        var changed = false
        synchronized(lock) {
            val cur = states[id] ?: return@synchronized
            if (packId in cur.active) {
                states = states + (id to cur.copy(
                    active = cur.active - packId,
                    updatedAt = System.currentTimeMillis()
                ))
                changed = true
            }
        }
        if (changed) flush(force = true)
        return changed
    }

    /** 整套替换激活列表（UI 用）。 */
    fun setActive(profileId: String, packIds: List<String>) {
        val id = sanitize(profileId)
        synchronized(lock) {
            val cur = states[id] ?: ProfileState(id = id)
            states = states + (id to cur.copy(active = packIds, updatedAt = System.currentTimeMillis()))
        }
        flush(force = true)
    }

    /** 重置回出厂默认。 */
    fun reset(profileId: String) {
        val id = sanitize(profileId)
        synchronized(lock) {
            states = states + (id to ProfileState(id = id, active = defaults(), updatedAt = System.currentTimeMillis()))
        }
        flush(force = true)
    }

    fun remove(profileId: String) {
        val id = sanitize(profileId)
        if (id == DEFAULT_ID) return
        synchronized(lock) { states = states - id }
        fileOf(id)?.delete()
    }

    // -------------------------------------------------------------------- 工具

    companion object {
        const val DEFAULT_ID = "default"

        private const val FLUSH_INTERVAL_MS = 60_000L

        /**
         * 把任意的 profile 名收敛成一个安全的 id。
         * 允许中文（URL 会被 decode 成中文），但挡掉路径分隔符和 `..`，
         * 免得有人拿 `/mcp/p/../../etc/passwd` 这种路径来试探。
         */
        fun sanitize(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return DEFAULT_ID
            if (t.contains('/') || t.contains('\\') || t.contains("..")) return DEFAULT_ID
            if (!t.matches(Regex("^[\\p{L}\\p{N}._\\- ]+$"))) return DEFAULT_ID
            return t.take(32).ifBlank { DEFAULT_ID }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class LogKind(val id: String, val label: String) {
    REQUEST("request", "调用"),
    APPROVAL("approval", "审批"),
    CONNECT("connect", "连接"),
    SYSTEM("system", "系统"),
    ERROR("error", "错误");

    companion object {
        fun of(id: String?): LogKind = entries.firstOrNull { it.id == id } ?: REQUEST
    }
}

data class LogEntry(
    val id: Long,
    val time: Long,
    val kind: LogKind,
    val tool: String?,
    val path: String?,
    val client: String?,
    val ok: Boolean,
    val message: String,
    val durationMs: Long
)

/** Ring buffer of server activity. Thread safe, never throws. */
class EventLog(private val capacity: Int = 800) {

    private val items = ArrayDeque<LogEntry>()
    private val seq = AtomicLong(0)
    private val lock = Any()

    private val total = AtomicLong(0)
    private val okCount = AtomicLong(0)
    private val failCount = AtomicLong(0)
    private val denyCount = AtomicLong(0)
    private val approvalCount = AtomicLong(0)

    val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    @Volatile var enabled: Boolean = true

    fun add(
        kind: LogKind,
        tool: String? = null,
        path: String? = null,
        client: String? = null,
        ok: Boolean = true,
        message: String = "",
        durationMs: Long = 0
    ): LogEntry {
        if (!enabled) return LogEntry(0, System.currentTimeMillis(), kind, tool, path, client, ok, message, durationMs)
        val entry = LogEntry(
            id = seq.incrementAndGet(),
            time = System.currentTimeMillis(),
            kind = kind,
            tool = tool,
            path = path,
            client = client,
            ok = ok,
            message = message,
            durationMs = durationMs
        )
        synchronized(lock) {
            items.addLast(entry)
            while (items.size > capacity) items.removeFirst()
        }
        total.incrementAndGet()
        if (kind == LogKind.REQUEST) {
            if (ok) okCount.incrementAndGet() else failCount.incrementAndGet()
        }
        if (kind == LogKind.APPROVAL) approvalCount.incrementAndGet()
        if (!ok && kind == LogKind.APPROVAL) denyCount.incrementAndGet()
        listeners.forEach { runCatching { it(entry) } }
        return entry
    }

    fun list(limit: Int = 400): List<LogEntry> = synchronized(lock) {
        items.toList().takeLast(limit).reversed()
    }

    fun clear() = synchronized(lock) { items.clear() }

    data class Stats(
        val total: Long,
        val ok: Long,
        val failed: Long,
        val approvals: Long,
        val denied: Long
    )

    fun stats(): Stats = Stats(total.get(), okCount.get(), failCount.get(), approvalCount.get(), denyCount.get())
}

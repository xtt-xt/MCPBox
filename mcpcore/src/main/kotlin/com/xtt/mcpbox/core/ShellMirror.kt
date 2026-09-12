// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * AI 执行命令时的镜像出口。
 *
 * 核心层不方便直接依赖 App 的终端，所以留个函数指针：
 * App 启动时接到 [com.xtt.mcpbox.TerminalController] 上，
 * 这样 AI 通过 run_shell / 自定义工具干的事，也能在 App 的「终端」页里看到。
 */
object ShellMirror {

    @Volatile private var sink: ((String) -> Unit)? = null

    /** 是否已经接上（没接上时不做任何事，纯 JVM 测试也不会炸）。 */
    val isAttached: Boolean get() = sink != null

    fun attach(s: (String) -> Unit) {
        sink = s
    }

    fun detach() {
        sink = null
    }

    fun emit(text: String) {
        if (text.isEmpty()) return
        val s = sink ?: return
        runCatching { s(text) }
    }
}

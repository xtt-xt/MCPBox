// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.harness

import com.xtt.mcpbox.core.PluginHost
import com.xtt.mcpbox.core.PluginRuntime
import com.xtt.mcpbox.core.PluginRuntimeException
import com.xtt.mcpbox.core.PluginTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * harness 用的假引擎：不跑 JS，只记录「脚本被调了几次、参数是什么」，
 * 按脚本名返回预设结果。
 *
 * 测试的关注点是**流程**（安装 → 注册 → 调用 → 审批 → 卸载），不是引擎本身；
 * QuickJS 的坑在真机上踩。
 */
class FakePluginRuntime : PluginRuntime {

    override val name: String = "fake"

    /** 脚本名 → 返回值（默认 `fake:<脚本名>`）。 */
    val results = ConcurrentHashMap<String, String>()

    /** 脚本名 → 要抛的异常。 */
    val failures = ConcurrentHashMap<String, Exception>()

    /** 每次调用记一笔，顺序保留。 */
    val calls: MutableList<Call> = CopyOnWriteArrayList()

    var available = true

    /**
     * 想测 host API 的话挂这个钩子：返回值非 null 就用它当脚本结果，
     * 返回 null 继续走 [results] / 默认值。参数是 [PluginHost]，可以直接调它的方法。
     */
    var onRun: ((scriptName: String, host: PluginHost) -> String?)? = null

    data class Call(
        val scriptName: String,
        val pluginId: String,
        val args: String,
        val timeoutMs: Long
    )

    override fun available(): Boolean = available

    override fun runTool(
        script: String,
        scriptName: String,
        host: PluginHost,
        timeoutMs: Long
    ): String {
        calls.add(Call(scriptName, host.pluginId, host.args.toString(), timeoutMs))
        failures[scriptName]?.let { throw it }
        onRun?.invoke(scriptName, host)?.let { return it }
        return results[scriptName] ?: "fake:$scriptName"
    }

    /** 便捷：让某个脚本超时。 */
    fun timeout(scriptName: String) {
        failures[scriptName] = PluginTimeoutException("脚本超时")
    }

    /** 便捷：让某个脚本报错。 */
    fun fail(scriptName: String, message: String = "脚本抛异常") {
        failures[scriptName] = PluginRuntimeException(message)
    }

    fun reset() {
        results.clear()
        failures.clear()
        calls.clear()
        onRun = null
        available = true
    }
}

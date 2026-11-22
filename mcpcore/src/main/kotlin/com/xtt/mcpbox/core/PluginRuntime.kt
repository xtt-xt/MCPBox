// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 脚本自己报的错（`throw new Error(...)`、语法错误、调用不存在的 host API……）。 */
class PluginRuntimeException(message: String) : Exception(message)

/** 脚本跑太久，被引擎中断。 */
class PluginTimeoutException(message: String) : Exception(message)

/** 一次 HTTP 请求的结果（`ctx.http.request` 用）。 */
data class PluginHttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    /** 最终地址（跟过重定向之后），脚本记日志用。 */
    val url: String = "",
    /** 正文超过上限被截断。 */
    val truncated: Boolean = false
)

/**
 * 宿主暴露给插件的上下文。
 *
 * **脚本看到的是 `ctx`，Kotlin 侧看到的是这个接口。** 引擎实现负责把方法注册成
 * JS / Python 里的对象，mcpcore 只负责实现这些方法本身（路径沙箱、权限审批、
 * 后端选择都在这里；引擎不认识它们）。
 *
 * 所有方法都是**同步**的。引擎在自己的线程里跑脚本，需要异步时在引擎侧包一层
 * Promise / coroutine —— 这样 mcpcore 不用关心并发模型。
 */
interface PluginHost {

    /** 已填好默认值的参数对象（脚本直接读 `ctx.args.city`）。 */
    val args: JsonObject

    val pluginId: String
    val pluginVersion: String

    /** 插件自己的数据目录（`files/plugins/<id>/data`），脚本可以随便读写。 */
    val dataDirPath: String

    // ---------------------------------------------------------------- 设置

    /** 读插件设置项；没设过就返回声明的默认值。 */
    fun settingsGet(key: String): JsonElement?

    /** 写插件设置项。不审批 —— 改自己的设置不算危险动作。 */
    fun settingsSet(key: String, value: JsonElement)

    // ---------------------------------------------------------------- 文件

    /**
     * 读文件。
     *
     * 相对路径按插件 data/ 解析，不审批；绝对路径走 [PathSandbox] 与 `fs.read` 权限。
     */
    fun fsRead(path: String): String

    fun fsWrite(path: String, text: String)

    /** 列目录，返回 `[{name, path, dir, size, modified}]`。 */
    fun fsList(path: String): JsonElement

    fun fsRemove(path: String)

    // ---------------------------------------------------------------- 命令

    /** 跑一条命令；`shell.exec` 权限会弹审批。 */
    fun shellRun(command: String, cwd: String?, timeoutMs: Long): ShellResult

    // ---------------------------------------------------------------- 网络

    /** 发一次 HTTP 请求；`network` 权限会弹审批，按域名规则记住。 */
    fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): PluginHttpResponse

    /**
     * 发一次请求但先不读正文，返回一个可以逐行读的流（SSE 转发用）。
     *
     * 同样过域名规则与审批。用完必须 [PluginHttpStream.close]。
     */
    fun httpStream(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long
    ): PluginHttpStream

    // ---------------------------------------------------------------- 其它工具

    /** 调一个已存在的工具（内置或别的插件的）；不存在返回 null。 */
    fun callTool(name: String, args: JsonObject): ToolResult?

    // ---------------------------------------------------------------- 日志

    /** 写插件的日志（`files/plugins/<id>/logs/plugin.log`，环形 500 条）。 */
    fun log(level: String, message: String)
}

/**
 * 脚本引擎。
 *
 * 唯一实现是 App 层的 `JsPluginRuntime`（QuickJS）。harness 用
 * `FakePluginRuntime` 把「安装 → 注册 → 调用 → 审批 → 卸载」整条链路串起来，
 * 不测引擎本身 —— 引擎的坑在真机上踩。
 */
interface PluginRuntime {

    /** 引擎名字，写日志用（如 "quickjs"）。 */
    val name: String

    /** 引擎能不能用（初始化失败时返回 false，插件页会提示）。 */
    fun available(): Boolean = true

    /**
     * 执行工具脚本。
     *
     * 脚本契约见 docs/12 第 6.2 节：默认导出一个带 `run(ctx)` 的对象。
     * 引擎负责取返回值并转成文本：
     *  - 字符串 → 原样
     *  - null / undefined → 空字符串
     *  - 其它 → JSON 文本
     *
     * @throws PluginTimeoutException 超时（引擎必须中断脚本，不能只是不管）
     * @throws PluginRuntimeException 脚本抛异常 / 语法错误 / 契约不对
     */
    fun runTool(script: String, scriptName: String, host: PluginHost, timeoutMs: Long): String

    /**
     * 执行一条 HTTP 路由脚本。
     *
     * 脚本用 `ctx.res.respond(...)` 或 `ctx.sse.write(...)` 把结果写回去，
     * 返回值忽略。脚本什么都不写时由路由层回 500。
     *
     * 超时只约束脚本自己的计算；流式阶段阻塞在 host 调用上不算 JS 时间。
     *
     * @throws PluginTimeoutException 脚本跑太久
     * @throws PluginRuntimeException 脚本抛异常 / 语法错误
     */
    fun runRoute(script: String, scriptName: String, host: PluginRouteHost, timeoutMs: Long)
}

// ------------------------------------------------------------------ 服务类：路由

/** 脚本看到的 `ctx.req`。 */
data class PluginRouteRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val remote: String
)

/** 脚本给的响应（[PluginRouteHost.respond] 收下的东西）。 */
data class PluginRouteResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json; charset=utf-8"
)

/** 脚本写 SSE 的出口（`ctx.sse`）。 */
interface PluginSse {
    val closed: Boolean

    /** 打开流（第一次 write 会自动开）。 */
    fun open(extraHeaders: Map<String, String> = emptyMap()): Boolean

    /** 写一帧 `data:`。返回 false 表示客户端已经断开。 */
    fun write(data: String): Boolean

    /** 写一条注释帧（保活）。 */
    fun comment(text: String): Boolean

    fun close()
}

/**
 * 路由脚本的 host。
 *
 * 和工具用的 [PluginHost] 相比多了 `req` / `respond` / `sse`，
 * 其余（设置、文件、命令、网络、日志）完全一样。
 */
interface PluginRouteHost : PluginHost {
    val req: PluginRouteRequest

    /** `stream: true` 的路由才有；其它路由为 null。 */
    val sse: PluginSse?

    /** 一次性响应。重复调用以第一次为准。 */
    fun respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8"
    )

    /** 脚本有没有给出响应（[respond] 或 [sse] 写成功过）。 */
    val responded: Boolean

    /** 脚本给的响应；没响应为 null。 */
    val response: PluginRouteResponse?
}

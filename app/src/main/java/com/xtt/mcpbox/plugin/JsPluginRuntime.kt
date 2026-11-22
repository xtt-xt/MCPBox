// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.function
import com.xtt.mcpbox.core.PluginHost
import com.xtt.mcpbox.core.PluginRuntime
import com.xtt.mcpbox.core.PluginRuntimeException
import com.xtt.mcpbox.core.PluginTimeoutException
import com.xtt.mcpbox.core.anyToJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * QuickJS 引擎：把插件的 JS 跑起来，宿主能力通过 `__mcpbox_call` 转回 Kotlin。
 *
 * 每次工具调用新建一个 QuickJs 实例（几十毫秒），跑完就关 —— 插件之间不共享
 * 全局状态，一个插件把运行时玩坏了也影响不到下一个。工具调用频率低，这点开销
 * 换来的是没有任何跨插件的状态泄漏。
 *
 * 脚本契约见 docs/12 第 6.2 节：默认导出一个带 `run(ctx)` 的对象。
 */
class JsPluginRuntime : PluginRuntime {

    override val name: String = "quickjs"

    /** 首次探测后缓存：QuickJS 的 .so 加载失败是环境问题，不会自己变好。 */
    private val engineAvailable: Boolean by lazy {
        runCatching {
            QuickJs.create(Dispatchers.Default).close()
        }.isSuccess
    }

    override fun available(): Boolean = engineAvailable

    override fun runTool(
        script: String,
        scriptName: String,
        host: PluginHost,
        timeoutMs: Long
    ): String {
        return runBlocking {
            val qjs = try {
                QuickJs.create(Dispatchers.Default)
            } catch (e: Exception) {
                throw PluginRuntimeException(
                    "JS 引擎初始化失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
            try {
                qjs.evaluationTimeoutMillis = timeoutMs
                qjs.memoryLimit = MEMORY_LIMIT_BYTES
                qjs.function("__mcpbox_call") { args ->
                    handleHostCall(host, args.firstOrNull())
                }
                // 外层再兜一层：evaluationTimeoutMillis 只算 JS 执行时间，
                // 不算 await 里跑着的 host 调用（比如一条慢命令）。
                val text = withTimeout(timeoutMs + HOST_GRACE_MS) {
                    qjs.evaluate<String>(wrap(script, scriptName, host), filename = scriptName)
                }
                text
            } catch (e: QuickJsInterruptedException) {
                throw PluginTimeoutException(
                    "脚本执行超过 ${timeoutMs / 1000} 秒，已中断"
                )
            } catch (e: QuickJsException) {
                throw PluginRuntimeException(describe(e))
            } finally {
                runCatching { qjs.close() }
            }
        }
    }

    /** 把 QuickJS 的异常变成「脚本哪一行出了什么」。 */
    private fun describe(e: QuickJsException): String {
        val where = listOfNotNull(
            e.fileName,
            e.lineNumber?.let { "第 $it 行" }
        ).joinToString(" ")
        val msg = e.message ?: "脚本出错"
        return if (where.isBlank()) msg else "$msg（$where）"
    }

    // ------------------------------------------------------------------ host 调用

    /**
     * JS 侧 `__mcpbox_call({ method, params })` → Kotlin。
     *
     * 返回值统一转成 JS 认识的基本类型（Map / List / String / Number / Boolean / null）；
     * 抛异常会自然变成 JS 异常，脚本里可以 catch。
     */
    private fun handleHostCall(host: PluginHost, raw: Any?): Any? {
        val req = raw as? JsObject
            ?: throw IllegalArgumentException("host 调用的参数必须是一个对象")
        val method = req["method"] as? String
            ?: throw IllegalArgumentException("host 调用缺少 method")
        val p = (req["params"] as? JsObject).orEmpty()

        fun str(key: String): String =
            p[key] as? String ?: throw IllegalArgumentException("host 调用 $method 缺少参数 $key")

        fun optStr(key: String): String? = p[key] as? String

        fun num(key: String): Long? = (p[key] as? Number)?.toLong()

        return when (method) {
            "settings.get" -> host.settingsGet(str("key"))?.plain()
            "settings.set" -> {
                host.settingsSet(str("key"), anyToJson(p["value"]))
                null
            }

            "fs.read" -> host.fsRead(str("path"))
            "fs.write" -> {
                host.fsWrite(str("path"), p["text"]?.toString() ?: "")
                null
            }
            "fs.list" -> host.fsList(optStr("path") ?: ".").plain()
            "fs.remove" -> {
                host.fsRemove(str("path"))
                null
            }

            "shell.run" -> {
                val r = host.shellRun(
                    command = str("command"),
                    cwd = optStr("cwd"),
                    timeoutMs = num("timeoutMs") ?: 60_000L
                )
                // 必须是 JsObject：普通 Map 会被映射成 JS 的 Map，
                // 脚本里 `r.stdout` 就成了 undefined（要 r.get("stdout")）
                JsObject(
                    mapOf(
                        "exitCode" to r.exitCode,
                        "stdout" to r.stdout,
                        "stderr" to r.stderr,
                        "timedOut" to r.timedOut,
                        "ok" to r.ok,
                        "durationMs" to r.durationMs,
                        "backend" to r.backend
                    )
                )
            }

            "http.request" -> {
                val r = host.httpRequest(
                    method = optStr("method") ?: "GET",
                    url = str("url"),
                    headers = (p["headers"] as? JsObject)
                        ?.mapValues { it.value?.toString() ?: "" }.orEmpty(),
                    body = optStr("body")
                )
                JsObject(
                    mapOf(
                        "status" to r.status,
                        "headers" to JsObject(r.headers),
                        "body" to r.body
                    )
                )
            }

            "tools.call" -> {
                val name = str("name")
                val args = (p["args"] as? JsObject)?.let { JsonObject(it.entries.associate { e -> e.key to anyToJson(e.value) }) }
                    ?: JsonObject(emptyMap())
                host.callTool(name, args)?.let {
                    JsObject(mapOf("ok" to it.ok, "text" to it.text))
                }
            }

            "log" -> {
                host.log(optStr("level") ?: "info", p["message"]?.toString() ?: "")
                null
            }

            else -> throw IllegalArgumentException("不认识的 host 调用：$method")
        }
    }

    /** JSON → JS 认识的原生类型。 */
    private fun JsonElement.plain(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            content.toLongOrNull() != null -> content.toLong()
            else -> doubleOrNull ?: content
        }
        is JsonObject -> JsObject(entries.associate { it.key to it.value.plain() })
        is JsonArray -> map { it.plain() }
    }

    // ------------------------------------------------------------------ 脚本包装

    private fun wrap(script: String, scriptName: String, host: PluginHost): String {
        // 只认 `export default`：P1 不支持跨文件 import / 具名导出（文档 6.2）
        val body = script.replace(Regex("""export\s+default\b"""), "module.exports =")
        // 不用 IIFE 包：QuickJS 支持顶层 await，而 IIFE 会把返回值变成 Promise 对象
        return buildString {
            append("\"use strict\";\n")
            append("var module = { exports: {} };\nvar exports = module.exports;\n")
            append(prelude(host))
            append('\n')
            append(body)
            append('\n')
            append(EPILOGUE)
        }
    }

    private fun prelude(host: PluginHost): String = """
var __call = function (method, params) {
  return __mcpbox_call({ method: method, params: params === undefined ? null : params });
};
var ctx = {
  args: ${host.args.toString()},
  plugin: {
    id: ${JsonPrimitive(host.pluginId)},
    version: ${JsonPrimitive(host.pluginVersion)},
    dataDir: ${JsonPrimitive(host.dataDirPath)}
  },
  settings: {
    get: function (key) { return __call("settings.get", { key: String(key) }); },
    set: function (key, value) { __call("settings.set", { key: String(key), value: value }); }
  },
  fs: {
    read: function (path) { return __call("fs.read", { path: String(path) }); },
    write: function (path, text) { __call("fs.write", { path: String(path), text: String(text) }); },
    list: function (path) { return __call("fs.list", { path: path === undefined ? "." : String(path) }); },
    remove: function (path) { __call("fs.remove", { path: String(path) }); }
  },
  shell: {
    run: function (command, opts) {
      return __call("shell.run", {
        command: String(command),
        cwd: opts && opts.cwd ? String(opts.cwd) : null,
        timeoutMs: opts && opts.timeoutMs ? Number(opts.timeoutMs) : null
      });
    }
  },
  http: {
    fetch: function (url, opts) {
      opts = opts || {};
      return __call("http.request", {
        method: opts.method ? String(opts.method) : "GET",
        url: String(url),
        headers: opts.headers || null,
        body: opts.body === undefined || opts.body === null ? null : String(opts.body)
      });
    },
    get: function (url, opts) {
      return ctx.http.fetch(url, Object.assign({}, opts || {}, { method: "GET" }));
    },
    post: function (url, opts) {
      return ctx.http.fetch(url, Object.assign({}, opts || {}, { method: "POST" }));
    }
  },
  tools: {
    call: function (name, args) {
      return __call("tools.call", { name: String(name), args: args || {} });
    }
  },
  log: function (level, message) {
    __call("log", { level: String(level), message: String(message) });
  }
};
""".trimIndent()

    private companion object {
        const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        const val HOST_GRACE_MS = 5_000L

        val EPILOGUE = """
var __entry = module.exports;
if (__entry && typeof __entry === "object" && Object.prototype.hasOwnProperty.call(__entry, "default")) {
  __entry = __entry.default;
}
if (typeof __entry === "function") __entry = { run: __entry };
if (!__entry || typeof __entry.run !== "function") {
  throw new Error("插件脚本要导出 run(ctx)：export default { async run(ctx) { ... } }");
}
var __ret = await __entry.run(ctx);
(typeof __ret === "string") ? __ret
  : (__ret === undefined || __ret === null) ? ""
  : JSON.stringify(__ret)
""".trimIndent()
    }
}

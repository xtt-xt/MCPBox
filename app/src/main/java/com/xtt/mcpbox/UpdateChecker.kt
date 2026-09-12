// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import com.xtt.mcpbox.core.ServerMeta
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 检查更新：读 GitHub Releases 的最新一条。
 *
 * 注意：仓库是私有的，匿名请求会拿到 404 —— 那种情况提示用户即可；
 * 仓库转成公开后就能正常检查了。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/xtt-xt/MCPBox/releases/latest"
    const val REPO_URL = "https://github.com/xtt-xt/MCPBox"
    const val RELEASES_URL = "$REPO_URL/releases"

    data class Info(
        val tag: String,
        val version: String,
        val url: String,
        val notes: String,
        val publishedAt: String
    )

    sealed class Result {
        /** 有新版可用。 */
        data class Newer(val info: Info) : Result()

        /** 已经是最新。 */
        data object UpToDate : Result()

        /** 检查失败（网络、私有仓库、还没发过 Release…）。 */
        data class Failed(val reason: String) : Result()
    }

    /** 阻塞式检查，调用方放到 IO 线程里跑。 */
    fun check(): Result {
        val text = runCatching { fetch() }.getOrElse { e ->
            return Result.Failed(e.message ?: "网络请求失败")
        }
        val json = runCatching { JSONObject(text) }.getOrElse {
            return Result.Failed("返回内容看不懂")
        }
        if (json.has("message") && !json.has("tag_name")) {
            val msg = json.optString("message")
            return Result.Failed(
                when {
                    msg.contains("Not Found") -> "仓库还是私有的，暂时检查不到更新"
                    msg.contains("rate limit") -> "请求太频繁，过一会儿再试"
                    else -> msg
                }
            )
        }
        val tag = json.optString("tag_name").ifBlank { return Result.Failed("没有读到版本号") }
        val info = Info(
            tag = tag,
            version = tag.removePrefix("v").trim(),
            url = json.optString("html_url").ifBlank { RELEASES_URL },
            notes = json.optString("body").take(1200),
            publishedAt = json.optString("published_at").take(10)
        )
        return if (compare(info.version, ServerMeta.version) > 0) Result.Newer(info) else Result.UpToDate
    }

    private fun fetch(): String {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MCPBox/${ServerMeta.version}")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) body
            else if (body.contains("Not Found")) "{\"message\":\"Not Found\"}"
            else throw IllegalStateException("服务器返回 $code")
        } finally {
            conn.disconnect()
        }
    }

    /** 版本号比较：1.10.0 > 1.9.0 ✓（按数字段比，不会踩字符串比较的坑）。 */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val y = b.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val vx = x.getOrElse(i) { 0 }
            val vy = y.getOrElse(i) { 0 }
            if (vx != vy) return vx - vy
        }
        return 0
    }

    /** 今天是哪天（用来判断「每天第一次」）。 */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

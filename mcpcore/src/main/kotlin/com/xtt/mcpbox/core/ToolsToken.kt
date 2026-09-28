// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * 让 AI 自己领访问令牌。
 *
 * 关键点：**调用这个工具本身不需要令牌**（MCP 工具调用走协议层，
 * 令牌只拦 HTTP 侧的 /upload、/download、/api 系列接口），所以它能用来
 * 解开"要先有令牌才能传文件、但 AI 又拿不到令牌"的死循环。
 */
object ToolsToken {

    fun specs(): List<ToolSpec> = listOf(getToken())

    private fun getToken() = ToolSpec(
        name = "get_token",
        title = "获取访问令牌",
        description = "获取本机 MCP 服务的访问令牌（token），以及端口和可用地址。" +
            "调用这个工具本身不需要令牌。拿到的令牌用于调用 HTTP 接口：" +
            "上传文件 POST /upload?path=目标路径&token=xxx、下载文件 GET /download?path=路径&token=xxx、" +
            "以及 /api/status、/api/tools、/api/log 等。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, L("获取访问令牌"))

        val port = ctx.config.port
        val token = ctx.config.token
        val enabled = ctx.config.tokenEnabled && token.isNotBlank()
        val lan = LocalNet.primary()
        val urls = LocalNet.urls(port)

        ToolResult(
            buildString {
                append(L("访问令牌\n"))
                if (!enabled) {
                    append(L("  未启用令牌校验：接口不需要 token 也能访问（设置 → 安全 里可以打开）。\n"))
                } else {
                    append("  ").append(token).append('\n')
                    append(L("  长度 %s 位 · 已在「设置 → 安全」里开启校验\n").format(token.length))
                }

                append(L("\n服务地址\n"))
                append(L("  端口：")).append(port).append('\n')
                if (lan != null) {
                    append("  ").append(L("局域网：%s").format("http://$lan:$port"))
                        .append(L("（手机连同一个 Wi-Fi 的设备可用）\n"))
                }
                append(L("  本机：")).append("http://127.0.0.1:").append(port)
                append(L("（手机本机 / 工作区容器内都能直连）\n"))
                if (urls.size > 2) {
                    append(L("  其它：")).append(urls.drop(2).joinToString(L("、"))).append('\n')
                }

                append(L("\n用法示例\n"))
                val t = if (enabled) token else "<token>"
                append(L("  上传文件到手机：\n"))
                append(L("    curl -X POST --data-binary @本地文件 \\\n"))
                append("      \"http://127.0.0.1:").append(port).append("/upload?path=/storage/emulated/0/x.txt&token=").append(t).append("\"\n")
                append(L("  从手机取文件：\n"))
                append("    curl -H \"Authorization: Bearer ").append(t).append("\" \\\n")
                append("      \"http://127.0.0.1:").append(port).append("/download?path=/storage/emulated/0/x.txt\"\n")
                append(L("  也可以把令牌放进请求头 X-MCP-Token，或用 Authorization: Bearer。\n"))
                append(L("\n提示\n"))
                append(L("  · 路径里的中文和空格要 URL 编码（例如 %E6%96%87%E4%BB%B6）。\n"))
                append(L("  · 令牌泄露等于把文件存取权限交出去；用户重置令牌后旧值立刻失效。\n"))
                append(L("  · 文件相关的操作优先用本工具集里的 list_dir / read_file / write_file，不必走 HTTP。"))
            }
        )
    }
}

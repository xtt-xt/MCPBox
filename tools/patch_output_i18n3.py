#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
第 5 批 i18n：get_token 输出、连接日志、自定义工具 / 工具包校验错误、HTTP 服务器错误。
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ ToolsToken.kt
    (f"{CORE}/ToolsToken.kt",
     '                append("访问令牌\\n")\n'
     '                if (!enabled) {\n'
     '                    append("  未启用令牌校验：接口不需要 token 也能访问（设置 → 安全 里可以打开）。\\n")\n'
     '                } else {\n'
     '                    append("  ").append(token).append(\'\\n\')\n'
     '                    append("  长度 ").append(token.length).append(" 位 · 已在「设置 → 安全」里开启校验\\n")\n'
     '                }\n'
     '\n'
     '                append("\\n服务地址\\n")\n'
     '                append("  端口：").append(port).append(\'\\n\')\n'
     '                if (lan != null) {\n'
     '                    append("  局域网：http://").append(lan).append(\':\').append(port).append("（手机连同一个 Wi-Fi 的设备可用）\\n")\n'
     '                }\n'
     '                append("  本机：http://127.0.0.1:").append(port)\n'
     '                append("（手机本机 / 工作区容器内都能直连）\\n")\n'
     '                if (urls.size > 2) {\n'
     '                    append("  其它：").append(urls.drop(2).joinToString("、")).append(\'\\n\')\n'
     '                }\n'
     '\n'
     '                append("\\n用法示例\\n")\n'
     '                val t = if (enabled) token else "<token>"\n'
     '                append("  上传文件到手机：\\n")\n'
     '                append("    curl -X POST --data-binary @本地文件 \\\\\\n")',
     '                append(L("访问令牌\\n"))\n'
     '                if (!enabled) {\n'
     '                    append(L("  未启用令牌校验：接口不需要 token 也能访问（设置 → 安全 里可以打开）。\\n"))\n'
     '                } else {\n'
     '                    append("  ").append(token).append(\'\\n\')\n'
     '                    append(L("  长度 %s 位 · 已在「设置 → 安全」里开启校验\\n").format(token.length))\n'
     '                }\n'
     '\n'
     '                append(L("\\n服务地址\\n"))\n'
     '                append(L("  端口：")).append(port).append(\'\\n\')\n'
     '                if (lan != null) {\n'
     '                    append("  ").append(L("局域网：%s").format("http://$lan:$port"))\n'
     '                        .append(L("（手机连同一个 Wi-Fi 的设备可用）\\n"))\n'
     '                }\n'
     '                append(L("  本机：")).append("http://127.0.0.1:").append(port)\n'
     '                append(L("（手机本机 / 工作区容器内都能直连）\\n"))\n'
     '                if (urls.size > 2) {\n'
     '                    append(L("  其它：")).append(urls.drop(2).joinToString(L("、"))).append(\'\\n\')\n'
     '                }\n'
     '\n'
     '                append(L("\\n用法示例\\n"))\n'
     '                val t = if (enabled) token else "<token>"\n'
     '                append(L("  上传文件到手机：\\n"))\n'
     '                append(L("    curl -X POST --data-binary @本地文件 \\\\\\n"))'),
    (f"{CORE}/ToolsToken.kt",
     '                append("  从手机取文件：\\n")',
     '                append(L("  从手机取文件：\\n"))'),
    (f"{CORE}/ToolsToken.kt",
     '                append("  也可以把令牌放进请求头 X-MCP-Token，或用 Authorization: Bearer。\\n")\n'
     '                append("\\n提示\\n")\n'
     '                append("  · 路径里的中文和空格要 URL 编码（例如 %E6%96%87%E4%BB%B6）。\\n")\n'
     '                append("  · 令牌泄露等于把文件存取权限交出去；用户重置令牌后旧值立刻失效。\\n")\n'
     '                append("  · 文件相关的操作优先用本工具集里的 list_dir / read_file / write_file，不必走 HTTP。")',
     '                append(L("  也可以把令牌放进请求头 X-MCP-Token，或用 Authorization: Bearer。\\n"))\n'
     '                append(L("\\n提示\\n"))\n'
     '                append(L("  · 路径里的中文和空格要 URL 编码（例如 %E6%96%87%E4%BB%B6）。\\n"))\n'
     '                append(L("  · 令牌泄露等于把文件存取权限交出去；用户重置令牌后旧值立刻失效。\\n"))\n'
     '                append(L("  · 文件相关的操作优先用本工具集里的 list_dir / read_file / write_file，不必走 HTTP。"))'),

    # ================================================================ McpServer.kt
    (f"{CORE}/McpServer.kt",
     'log.add(LogKind.SYSTEM, message = "服务已启动，端口 ${config.port}")',
     'log.add(LogKind.SYSTEM, message = L("服务已启动，端口 %s").format(config.port))'),
    (f"{CORE}/McpServer.kt",
     '            lastError = "启动失败：${e.message}"\n'
     '            log.add(LogKind.ERROR, ok = false, message = lastError ?: "启动失败")',
     '            lastError = L("启动失败：%s").format(e.message)\n'
     '            log.add(LogKind.ERROR, ok = false, message = lastError ?: L("启动失败"))'),
    (f"{CORE}/McpServer.kt",
     'message = "网页控制台登录成功")',
     'message = L("网页控制台登录成功"))'),
    (f"{CORE}/McpServer.kt",
     'log.add(LogKind.REQUEST, tool = "web_login", client = req.remote, ok = false, message = "网页控制台密码错误")',
     'log.add(\n                LogKind.REQUEST, tool = "web_login", client = req.remote, ok = false,\n                message = L("网页控制台密码错误")\n            )'),
    (f"{CORE}/McpServer.kt",
     'log.add(LogKind.CONNECT, message = "客户端断开：${session.label()}")',
     'log.add(LogKind.CONNECT, message = L("客户端断开：%s").format(session.label()))'),
    (f"{CORE}/McpServer.kt",
     'log.add(LogKind.CONNECT, message = "客户端连接（SSE）：${session.label()} @${req.remote}")',
     'log.add(\n            LogKind.CONNECT,\n            message = L("客户端连接（SSE）：%s @%s").format(session.label(), req.remote)\n        )'),
    (f"{CORE}/McpServer.kt",
     'if (!ok) log.add(LogKind.ERROR, ok = false, message = "SSE 通道已断开，响应未能送达")',
     'if (!ok) log.add(LogKind.ERROR, ok = false, message = L("SSE 通道已断开，响应未能送达"))'),
    (f"{CORE}/McpServer.kt",
     '                    message = "客户端已连接：${newSession.label()} @$remote（协议 $negotiated）"',
     '                    message = L("客户端已连接：%s @%s（协议 %s）")\n'
     '                        .format(newSession.label(), remote, negotiated)'),
    (f"{CORE}/McpServer.kt",
     '                    message = "列出工具（会话 $profile）"',
     '                    message = L("列出工具（会话 %s）").format(profile)'),
    (f"{CORE}/McpServer.kt",
     '                message = e.message ?: "被拒绝", durationMs = System.currentTimeMillis() - started',
     '                message = e.message ?: L("被拒绝"), durationMs = System.currentTimeMillis() - started'),
    (f"{CORE}/McpServer.kt",
     'toolErrorResult("操作被拒绝：${e.message}\\n（用户可在 App 的权限页调整该权限）")',
     'toolErrorResult(L("操作被拒绝：%s\\n（用户可在 App 的权限页调整该权限）").format(e.message ?: ""))'),
    (f"{CORE}/McpServer.kt",
     '                message = e.message ?: "失败", durationMs = System.currentTimeMillis() - started',
     '                message = e.message ?: L("失败"), durationMs = System.currentTimeMillis() - started'),
    (f"{CORE}/McpServer.kt",
     'toolErrorResult(e.message ?: "操作失败")',
     'toolErrorResult(e.message ?: L("操作失败"))'),
    (f"{CORE}/McpServer.kt",
     '                message = e.message ?: "路径不合法", durationMs = System.currentTimeMillis() - started\n'
     '            )\n'
     '            toolErrorResult(e.message ?: "路径不合法")',
     '                message = e.message ?: L("路径不合法"), durationMs = System.currentTimeMillis() - started\n'
     '            )\n'
     '            toolErrorResult(e.message ?: L("路径不合法"))'),

    # ================================================================ CustomTools.kt
    (f"{CORE}/CustomTools.kt",
     'throw ToolFailure("缺少必填参数：" + missing.joinToString("、") { it.name })',
     'throw ToolFailure(L("缺少必填参数：") + missing.joinToString(L("、")) { it.name })'),
    (f"{CORE}/CustomTools.kt",
     '?: throw ToolFailure("模板里用到了参数 {{$key}}，但它没有定义")',
     '?: throw ToolFailure(L("模板里用到了参数 {{%s}}，但它没有定义").format(key))'),
    (f"{CORE}/CustomTools.kt",
     'return "工具名要小写字母开头，只能包含小写字母/数字/下划线，长度 2-41"',
     'return L("工具名要小写字母开头，只能包含小写字母/数字/下划线，长度 2-41")'),
    (f"{CORE}/CustomTools.kt",
     'return "已存在同名工具：${tool.name}"',
     'return L("已存在同名工具：%s").format(tool.name)'),
    (f"{CORE}/CustomTools.kt",
     'if (tool.command.isBlank()) return "命令模板不能为空"',
     'if (tool.command.isBlank()) return L("命令模板不能为空")'),
    (f"{CORE}/CustomTools.kt",
     'return "命令里用到了未定义的参数：" + undefined.joinToString("、")',
     'return L("命令里用到了未定义的参数：") + undefined.joinToString(L("、"))'),
    (f"{CORE}/CustomTools.kt",
     'if (!p.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return "参数名不合法：${p.name}"',
     'if (!p.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {\n'
     '                return L("参数名不合法：%s").format(p.name)\n'
     '            }'),
    (f"{CORE}/CustomTools.kt",
     '?: throw ToolFailure("找不到工具：${tool.name}")',
     '?: throw ToolFailure(L("找不到工具：%s").format(tool.name))'),
    (f"{CORE}/CustomTools.kt",
     '"note" to "MCP 文件盒 · 自定义工具导出",',
     '"note" to L("MCP 文件盒 · 自定义工具导出"),'),
    (f"{CORE}/CustomTools.kt",
     'throw ToolFailure("不是合法的 JSON：${it.message}")',
     'throw ToolFailure(L("不是合法的 JSON：%s").format(it.message))'),
    (f"{CORE}/CustomTools.kt",
     '?: throw ToolFailure("JSON 里 tools 字段不是数组")',
     '?: throw ToolFailure(L("JSON 里 tools 字段不是数组"))'),
    (f"{CORE}/CustomTools.kt",
     'else -> throw ToolFailure("JSON 结构不对，需要数组或 {\\"tools\\":[...]}")',
     'else -> throw ToolFailure(L("JSON 结构不对，需要数组或 {\\"tools\\":[...]}"))'),
    (f"{CORE}/CustomTools.kt",
     'if (incoming.isEmpty()) throw ToolFailure("没有解析出任何工具")',
     'if (incoming.isEmpty()) throw ToolFailure(L("没有解析出任何工具"))'),
    (f"{CORE}/CustomTools.kt",
     '            message = "导入完成：新增 $added，更新 $updated" + if (skipped > 0) "，跳过 $skipped" else ""',
     '            message = L("导入完成：新增 %s，更新 %s").format(added, updated) +\n'
     '                if (skipped > 0) L("，跳过 %s").format(skipped) else ""'),

    # ================================================================ ToolPack.kt
    (f"{CORE}/ToolPack.kt",
     'if (isBuiltin(created.id)) throw ToolFailure("「${created.id}」是内置包的名字，换一个吧")\n'
     '        if (custom.any { it.id == created.id }) throw ToolFailure("已经有一个叫「${created.id}」的包了")',
     'if (isBuiltin(created.id)) {\n'
     '            throw ToolFailure(L("「%s」是内置包的名字，换一个吧").format(created.id))\n'
     '        }\n'
     '        if (custom.any { it.id == created.id }) {\n'
     '            throw ToolFailure(L("已经有一个叫「%s」的包了").format(created.id))\n'
     '        }'),
    (f"{CORE}/ToolPack.kt",
     '                if (isBuiltin(pack.id)) "内置包不能改，但可以用 manage_pack 新建一个自己的包"\n'
     '                else "找不到包：${pack.id}"',
     '                if (isBuiltin(pack.id)) L("内置包不能改，但可以用 manage_pack 新建一个自己的包")\n'
     '                else L("找不到包：%s").format(pack.id)'),
    (f"{CORE}/ToolPack.kt",
     'if (isBuiltin(id)) throw ToolFailure("「$id」是内置包，不能删")',
     'if (isBuiltin(id)) throw ToolFailure(L("「%s」是内置包，不能删").format(id))'),
    (f"{CORE}/ToolPack.kt",
     '        if (pack.title.isBlank()) throw ToolFailure("包名不能为空")\n'
     '        if (pack.title.length > 60) throw ToolFailure("包名太长（最多 60 字）")\n'
     '        if (pack.description.length > 1200) throw ToolFailure("包说明太长（最多 1200 字）")\n'
     '        if (pack.id.length > 48) throw ToolFailure("包 id 太长（最多 48 字）")\n'
     '        if (!pack.id.matches(Regex("^[a-zA-Z0-9._\\\\-]*$"))) {\n'
     '            throw ToolFailure("包 id 只能用字母、数字、点、横线和下划线")\n'
     '        }',
     '        if (pack.title.isBlank()) throw ToolFailure(L("包名不能为空"))\n'
     '        if (pack.title.length > 60) throw ToolFailure(L("包名太长（最多 60 字）"))\n'
     '        if (pack.description.length > 1200) throw ToolFailure(L("包说明太长（最多 1200 字）"))\n'
     '        if (pack.id.length > 48) throw ToolFailure(L("包 id 太长（最多 48 字）"))\n'
     '        if (!pack.id.matches(Regex("^[a-zA-Z0-9._\\\\-]*$"))) {\n'
     '            throw ToolFailure(L("包 id 只能用字母、数字、点、横线和下划线"))\n'
     '        }'),

    # ================================================================ HttpServer.kt
    (f"{CORE}/HttpServer.kt",
     'throw IOException("请求头过长")',
     'throw IOException(L("请求头过长"))'),
    (f"{CORE}/HttpServer.kt",
     'throw IOException("请求体不完整")',
     'throw IOException(L("请求体不完整"))'),
    (f"{CORE}/HttpServer.kt",
     'throw IOException("请求体过大")',
     'throw IOException(L("请求体过大"))'),

    # ================================================================ Shell.kt
    (f"{CORE}/Shell.kt",
     'return if (text.length > maxChars) text.take(maxChars) + "\\n...（已截断）" else text',
     'return if (text.length > maxChars) text.take(maxChars) + L("\\n...（已截断）") else text'),
]


def main():
    dry = "--dry" in sys.argv
    failed = []
    cache = {}
    for item in PATCHES:
        rel, old, new = item[0], item[1], item[2]
        want = item[3] if len(item) > 3 else 1
        path = os.path.join(ROOT, rel)
        src = cache.get(path)
        if src is None:
            src = open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != want:
            failed.append((rel, old.split("\n")[0][:70], n, want))
            continue
        cache[path] = src.replace(old, new)

    if failed:
        print(f"❌ {len(failed)} 处没匹配上：")
        for rel, head, n, want in failed:
            print(f"   [实际 {n} / 期望 {want}] {rel}\n        {head}")
        print("\n没有写入任何文件。")
        return 1

    print(f"✅ {len(PATCHES)} 处替换全部命中，涉及 {len(cache)} 个文件")
    if dry:
        return 0
    for path, src in cache.items():
        open(path, "w", encoding="utf-8").write(src)
    print("已写入：" + "、".join(sorted(os.path.basename(p) for p in cache)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

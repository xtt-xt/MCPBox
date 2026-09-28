#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 8 批 i18n：真正收尾 —— HTTP / JSON-RPC 报错、终端标记、Shell 日志、设备信息标签、两个网页。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ McpServer：HTTP / RPC 报错
    (f"{CORE}/McpServer.kt",
     '(if (clientName.isBlank()) "未知客户端" else clientName) +',
     '(if (clientName.isBlank()) L("未知客户端") else clientName) +'),
    (f"{CORE}/McpServer.kt",
     '                "已开启「仅本机访问」：这个服务只接受来自 localhost 的请求。\\n" +\n'
     '                    "想让局域网里的设备（电脑、其它手机）访问，请在 App 的「设置 → 网页控制台」里关掉它。",',
     '                L("已开启「仅本机访问」：这个服务只接受来自 localhost 的请求。\\n") +\n'
     '                    L("想让局域网里的设备（电脑、其它手机）访问，请在 App 的「设置 → 网页控制台」里关掉它。"),'),
    (f"{CORE}/McpServer.kt",
     'jo("error" to "需要 id 和 decision").toString(), cors',
     'jo("error" to L("需要 id 和 decision")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     '404, "application/json; charset=utf-8", jo("error" to "未知路径: $path").toString(), cors',
     '404, "application/json; charset=utf-8",\n'
     '                jo("error" to L("未知路径: %s").format(path)).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'jo("error" to "缺少或错误的访问令牌（token）").toString(),\n'
     '                cors + mapOf("WWW-Authenticate" to "Bearer")',
     'jo("error" to L("缺少或错误的访问令牌（token）")).toString(),\n'
     '                cors + mapOf("WWW-Authenticate" to "Bearer")'),
    (f"{CORE}/McpServer.kt",
     'rpcError(null, -32001, "缺少或错误的访问令牌（token）").toString(),',
     'rpcError(null, -32001, L("缺少或错误的访问令牌（token）")).toString(),'),
    (f"{CORE}/McpServer.kt",
     'rpcError(null, -32700, "空请求体").toString(), cors',
     'rpcError(null, -32700, L("空请求体")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'rpcError(null, -32700, "JSON 解析失败").toString(), cors',
     'rpcError(null, -32700, L("JSON 解析失败")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'rpcError(null, -32000, "缺少 Mcp-Session-Id，请先 POST initialize").toString(), cors',
     'rpcError(null, -32000, L("缺少 Mcp-Session-Id，请先 POST initialize")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'rpcError(null, -32600, "不支持的方法 ${req.method}").toString(), cors',
     'rpcError(null, -32600, L("不支持的方法 %s").format(req.method)).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'ex.respondText(401, "text/plain; charset=utf-8", "未授权：缺少或错误的 token", cors)',
     'ex.respondText(401, "text/plain; charset=utf-8", L("未授权：缺少或错误的 token"), cors)'),
    (f"{CORE}/McpServer.kt",
     'jo("error" to "未授权").toString()',
     'jo("error" to L("未授权")).toString()'),
    (f"{CORE}/McpServer.kt",
     '404, "application/json; charset=utf-8", jo("error" to "会话不存在").toString(), cors',
     '404, "application/json; charset=utf-8", jo("error" to L("会话不存在")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     '400, "application/json; charset=utf-8", jo("error" to "JSON 解析失败").toString(), cors',
     '400, "application/json; charset=utf-8", jo("error" to L("JSON 解析失败")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     'RpcOut(rpcError(id, -32600, "缺少 method"))',
     'RpcOut(rpcError(id, -32600, L("缺少 method")))'),
    (f"{CORE}/McpServer.kt",
     'RpcOut(rpcError(id, -32601, "不支持的方法：$method"))',
     'RpcOut(rpcError(id, -32601, L("不支持的方法：%s").format(method)))'),
    (f"{CORE}/McpServer.kt",
     '?: return toolErrorResult("tools/call 缺少参数 name")',
     '?: return toolErrorResult(L("tools/call 缺少参数 name"))'),
    (f"{CORE}/McpServer.kt",
     '?: return toolErrorResult("未知工具：$name（可用 tools/list 查看全部工具）")',
     '?: return toolErrorResult(L("未知工具：%s（可用 tools/list 查看全部工具）").format(name))'),
    (f"{CORE}/McpServer.kt",
     'return toolErrorResult("工具「$name」已在 App 里被禁用（可在「设置 → 工具管理」里启用）")',
     'return toolErrorResult(\n'
     '                L("工具「%s」已在 App 里被禁用（可在「设置 → 工具管理」里启用）").format(name)\n'
     '            )'),
    (f"{CORE}/McpServer.kt",
     '                "工具包现在由用户在 App 里手动管理，「$name」没有开放给 AI。\\n" +\n'
     '                    "需要的话请让用户去「权限 → 工具包」勾选好要用的包，" +\n'
     '                    "并打开「让 AI 自己开关工具包」，然后重新连接 MCP 服务。"',
     '                L("工具包现在由用户在 App 里手动管理，「%s」没有开放给 AI。\\n").format(name) +\n'
     '                    L("需要的话请让用户去「权限 → 工具包」勾选好要用的包，") +\n'
     '                    L("并打开「让 AI 自己开关工具包」，然后重新连接 MCP 服务。")'),
    (f"{CORE}/McpServer.kt",
     'toolErrorResult("工具执行异常：${e.javaClass.simpleName}: ${e.message}")',
     'toolErrorResult(\n'
     '                L("工具执行异常：%s: %s").format(e.javaClass.simpleName, e.message ?: "")\n'
     '            )'),
    (f"{CORE}/McpServer.kt",
     'jo("error" to "请求体不是 JSON 对象").toString(), cors',
     'jo("error" to L("请求体不是 JSON 对象")).toString(), cors'),
    (f"{CORE}/McpServer.kt",
     '400, "application/json; charset=utf-8", jo("error" to "缺少 tool").toString(), cors',
     '400, "application/json; charset=utf-8", jo("error" to L("缺少 tool")).toString(), cors'),

    # ================================================================ Shell：终端标记与日志
    # 注：PosixShLauncher.hint 是构造参数，不能改成 getter；它本来就在 shell_info 里被 L(l.hint) 包着。
    (f"{CORE}/Shell.kt",
     '                exitCode = -1, stdout = "", stderr = "启动失败：${e.message}",',
     '                exitCode = -1, stdout = "", stderr = L("启动失败：%s").format(e.message),'),
    (f"{CORE}/Shell.kt",
     '            return ShellResult(-1, "", "启动失败：${e.message}", false, 0, launcher.id, command)',
     '            return ShellResult(\n'
     '                -1, "", L("启动失败：%s").format(e.message), false, 0, launcher.id, command\n'
     '            )'),
    (f"{CORE}/Shell.kt",
     '}.onFailure { onOutput("\\n[读取输出失败：${it.message}]\\n") }',
     '}.onFailure { onOutput("\\n" + L("[读取输出失败：%s]").format(it.message) + "\\n") }'),
    (f"{CORE}/Shell.kt",
     '            onOutput("\\n[写入失败：${e.message}]\\n")',
     '            onOutput("\\n" + L("[写入失败：%s]").format(e.message) + "\\n")'),
    (f"{CORE}/Shell.kt",
     'send("echo \\"[MCP 文件盒] uid=$(id -u 2>/dev/null) @ $(pwd)\\"")',
     'send("echo \\"[${L("MCP 文件盒")}] uid=$(id -u 2>/dev/null) @ $(pwd)\\"")'),

    # ================================================================ ToolsShell：镜像到终端的标记
    (f"{CORE}/ToolsShell.kt",
     '            if (result.timedOut) append("\\n[超时，已中断]\\n")\n'
     '            if (result.truncated) append("\\n[输出过长已截断]\\n")',
     '            if (result.timedOut) append("\\n" + L("[超时，已中断]") + "\\n")\n'
     '            if (result.truncated) append("\\n" + L("[输出过长已截断]") + "\\n")'),

    # ================================================================ ToolsUi：镜像标记
    (f"{CORE}/ToolsUi.kt",
     '                if (result.timedOut) append("\\n[超时，已中断]\\n")',
     '                if (result.timedOut) append("\\n" + L("[超时，已中断]") + "\\n")'),
    (f"{CORE}/ToolsUi.kt",
     'ShellMirror.emit("\\n[UI] \\$ $command\\n")',
     'ShellMirror.emit("\\n[UI] \\$ $command\\n")'),
    (f"{CORE}/ToolsUi.kt",
     'ShellMirror.emit("\\n[UI] 换用「${l.label}」后端读到了界面结构\\n")',
     'ShellMirror.emit("\\n" + L("[UI] 换用「%s」后端读到了界面结构").format(L(l.label)) + "\\n")'),

    # ================================================================ FileBridge
    (f"{CORE}/FileBridge.kt",
     'get() = privilegedLauncher()?.label ?: "不可用"',
     'get() = privilegedLauncher()?.label ?: L("不可用")'),

    # ================================================================ FileGateway：网页上传页 + 报错
    (f"{CORE}/FileGateway.kt",
     'json(403, false, e.message ?: "没有获得写入许可")',
     'json(403, false, e.message ?: L("没有获得写入许可"))'),
]


def main():
    dry = "--dry" in sys.argv
    cache = {}
    failed = []
    for rel, old, new in PATCHES:
        path = os.path.join(ROOT, rel)
        src = cache.get(path) or open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != 1:
            failed.append((os.path.basename(rel), old.split("\n")[0][:70], n))
            continue
        cache[path] = src.replace(old, new)
    if failed:
        print(f"❌ {len(failed)} 处没匹配上：")
        for f, head, n in failed:
            print(f"   [{n} 次] {f}\n        {head}")
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

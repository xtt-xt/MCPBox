#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""i18n 第七批英文词条：HTTP / JSON-RPC 报错、终端标记、Shell 提示。"""

NET_AND_RPC = {
    "未知客户端": "Unknown client",
    "已开启「仅本机访问」：这个服务只接受来自 localhost 的请求。\n":
        'Local-only access is on: this service only accepts requests from localhost.\n',
    "想让局域网里的设备（电脑、其它手机）访问，请在 App 的「设置 → 网页控制台」里关掉它。":
        "To let LAN devices (a computer, another phone) reach it, turn this off in the app under Settings → Web console.",
    "需要 id 和 decision": "id and decision are required",
    "未知路径: %s": "Unknown path: %s",
    "缺少或错误的访问令牌（token）": "Missing or wrong access token",
    "空请求体": "Empty request body",
    "JSON 解析失败": "Failed to parse JSON",
    "缺少 Mcp-Session-Id，请先 POST initialize": "Missing Mcp-Session-Id — POST initialize first",
    "不支持的方法 %s": "Unsupported method %s",
    "不支持的方法：%s": "Unsupported method: %s",
    "未授权：缺少或错误的 token": "Unauthorized: missing or wrong token",
    "未授权": "Unauthorized",
    "会话不存在": "Session does not exist",
    "缺少 method": "Missing method",
    "请求体不是 JSON 对象": "The request body is not a JSON object",
    "缺少 tool": "Missing tool",
    "tools/call 缺少参数 name": "tools/call is missing the name argument",
    "未知工具：%s（可用 tools/list 查看全部工具）":
        "Unknown tool: %s (use tools/list to see them all)",
    "工具「%s」已在 App 里被禁用（可在「设置 → 工具管理」里启用）":
        'Tool "%s" is disabled in the app (re-enable it under Settings → Tool management)',
    "工具包现在由用户在 App 里手动管理，「%s」没有开放给 AI。\n":
        'Tool packs are managed manually by the user in the app; "%s" is not exposed to the AI.\n',
    "需要的话请让用户去「权限 → 工具包」勾选好要用的包，":
        "If you need it, ask the user to tick the packs they need under Permissions → Tool packs, ",
    "并打开「让 AI 自己开关工具包」，然后重新连接 MCP 服务。":
        'turn on "Let the AI manage tool packs", and then reconnect the MCP service.',
    "工具执行异常：%s: %s": "Tool execution error: %s: %s",
}

TERMINAL = {
    "不需要额外权限，只能操作应用有权访问的文件":
        "No extra permission needed; can only touch files the app can access",
    "启动失败：%s": "Failed to start: %s",
    "[读取输出失败：%s]": "[failed to read output: %s]",
    "[写入失败：%s]": "[failed to write: %s]",
    "[超时，已中断]": "[timed out, interrupted]",
    "[输出过长已截断]": "[output too long, truncated]",
    "[UI] 换用「%s」后端读到了界面结构": "[UI] switched to the %s backend and read the UI tree",
    "不可用": "unavailable",
    "没有获得写入许可": "No write permission granted",
}

GROUPS = [
    ("i18n 第七批：HTTP / RPC 报错", NET_AND_RPC),
    ("i18n 第七批：终端标记与 Shell 提示", TERMINAL),
]

STALE_KEYS = []

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
第九批英文词条：设置页改成「顶层入口 + 子页」后的新增文案，以及记忆库导入导出。

用 tools/merge_lang_en.py 合并进 LangEn.kt（已存在的键不覆盖）。
"""

GROUPS = [
    ("i18n 第九批：设置页（顶层入口与子页标题）", {
        "通用": "General",
        "应用": "App",
        "外观与语言": "Appearance & language",
        "颜色模式、动态取色、调色板、界面语言与语言包": "Color mode, dynamic color, palette, app language and language packs",
        "主题配色、颜色模式与界面语言": "Theme colors, color mode and UI language",
        "网络与访问": "Network & access",
        "监听端口、局域网访问、响应格式与网页控制台": "Port, LAN access, response format and web console",
        "监听端口、局域网与网页控制台": "Port, LAN and the web console",
        "安全与审批": "Security & approval",
        "访问令牌、审批超时": "Access token and approval timeout",
        "访问令牌与审批超时": "Access token and approval timeout",
        "终端与命令": "Terminal & commands",
        "命令后端优先级、命令规则、默认超时": "Shell backend priority, command rules and default timeout",
        "命令后端、命令规则与默认超时": "Shell backends, command rules and default timeout",
        "AI 与工具": "AI & tools",
        "工具管理、记忆库、会话状态自动重置": "Tool manager, memory store and session auto-reset",
        "工具管理、记忆库与会话状态": "Tool manager, memory store and session state",
        "后台与运行": "Background & runtime",
        "CPU 唤醒、开机自启、日志与服务控制": "CPU wake lock, boot start, logs and service control",
        "保活、日志与服务控制": "Keep-alive, logs and service control",
        "主题": "Theme",
        "审批": "Approval",
        "命令执行": "Command execution",
        "超时": "Timeout",
        "工具与记忆": "Tools & memory",
        "会话": "Session",
        "后台": "Background",
        "服务": "Service",
    }),

    ("i18n 第九批：记忆库导入导出", {
        "导入导出": "Import / export",
        "导出到文件": "Export to file",
        "从文件导入": "Import from file",
        "导入方式": "Import mode",
        "合并（推荐）：保留现有内容": "Merge (recommended): keep what is already there",
        "覆盖：清空后整份替换": "Replace: wipe everything, then write the file as-is",
        "覆盖整个记忆库？": "Replace the whole memory store?",
        "当前 %s 个实体 · %s 条关系会被全部清空，然后写入文件里的内容。这一步不能撤销。":
            "The current %s entities and %s relations will be deleted, then the file's contents are written in. This cannot be undone.",
        "已导出 %s 个实体 · %s 条关系": "Exported %s entities and %s relations",
        "不是合法的记忆文件": "not a valid memory file",
        "记忆：导出全库": "Memory: export all",
        "把整个记忆库导出成一个 JSON 文件（实体 + 关系 + 观察），用来备份或搬到别的设备。" +
        "path 省略时自动命名到 根目录/xtt/memory/memory-<时间>.json。":
            "Export the whole memory store to a JSON file (entities + relations + observations), for backup or moving to another device. " +
            "If path is omitted the file is auto-named under <root>/xtt/memory/memory-<time>.json.",
        "导出到的文件路径，省略则写到 根目录/xtt/memory/": "Path to export to; defaults to <root>/xtt/memory/",
        "记忆：导入全库": "Memory: import all",
        "从 JSON 文件导入记忆库（memory_export 导出的格式，也可以是只带 entities / relations 的 JSON）。" +
        "mode=merge（默认、安全）合并：同名实体只补空着的类型 / 分区，观察去重后追加，绝不覆盖已有观察，关系重复的跳过；" +
        "mode=replace 覆盖：先清空整个记忆库，再写入文件里的内容。":
            "Import the memory store from a JSON file (the format memory_export writes; a JSON with just entities / relations works too). " +
            "mode=merge (default, safe): for entities with the same name only blank types/folders are filled in, observations are de-duplicated and appended, " +
            "existing observations are never overwritten, duplicate relations are skipped. " +
            "mode=replace: wipe the whole store first, then write the file's contents in.",
        "merge = 合并（默认，安全）；replace = 清空后整份覆盖":
            "merge = combine (default, safe); replace = wipe the store and write the file in",
        "导出记忆库（%s 个实体 · %s 条关系）到 %s": "Export memory store (%s entities, %s relations) to %s",
        "已导出记忆库：%s 个实体 · %s 条关系 · %s 条观察\n文件：%s\n大小：%s":
            "Exported the memory store: %s entities, %s relations, %s observations\nFile: %s\nSize: %s",
        "用 %s 覆盖整个记忆库": "Replace the whole memory store with %s",
        "从 %s 合并导入记忆库": "Merge %s into the memory store",
        "解析失败：%s": "Parse failed: %s",
        "已覆盖导入：清空原库后写入 %s 个实体、%s 条关系。":
            "Replaced the store: wrote %s entities and %s relations.",
        "合并导入完成：新增实体 %s · 补全既有实体 %s · 新增观察 %s · 新增关系 %s（跳过重复关系 %s）。":
            "Merge import done: %s new entities, %s existing entities completed, %s new observations, %s new relations (%s duplicate relations skipped).",
        "当前：%s 个实体 · %s 条关系": "Now: %s entities · %s relations",
        "\n当前：%s 个实体 · %s 条关系": "\nNow: %s entities, %s relations",
    }),
]

STALE_KEYS = []

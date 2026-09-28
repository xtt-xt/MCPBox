#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""i18n 第八批英文词条：设备信息标签、网页控制台文案。"""

DEVICE_LABELS = {
    "型号": "Model",
    "品牌": "Brand",
    "Android 版本": "Android version",
    "App 版本": "App version",
    "主根目录": "Primary root",
    "根目录可用": "Root free",
    "根目录总计": "Root total",
    "外部存储可用": "External storage free",
    "电量": "Battery",
    "：": ": ",
}

WEB_CONSOLE = {
    "MCP 文件盒 · 控制台": "MCP Box · Console",
    "连接中…": "Connecting…",
    "连接信息": "Connection info",
    "MCP 地址（HTTP）：": "MCP address (HTTP): ",
    "允许目录：": "Allowed directories: ",
    "访问令牌：": "Access token: ",
    "（客户端需带 Authorization: Bearer 或 ?token=）":
        " (clients must send Authorization: Bearer or ?token=)",
    "待审批请求": "Pending approvals",
    "暂无": "None",
    "工具测试": "Tool test",
    "填充参数": "Fill example",
    "执行": "Run",
    "（结果会显示在这里）": "(the result shows up here)",
    "最近日志": "Recent logs",
    "加载中…": "Loading…",
    "工具": "Tool",
    "权限": "Permission",
    "来自": "from",
    "允许一次": "Allow once",
    "始终允许": "Always allow",
    "拒绝": "Deny",
    "暂无日志": "No logs yet",
    "MCP 文件盒 · 上传": "MCP Box · Upload",
    "上传到手机": "Upload to the phone",
    "选一个文件 + 填目标路径，直接写进手机存储（会按权限设置弹审批）":
        "Pick a file, enter a target path, and write it straight to the phone (an approval prompt may appear)",
    "目标路径（可以只写到目录，会自动带上原文件名）":
        "Target path (a directory is fine — the original file name is appended)",
    "文件": "File",
    "开始上传": "Start upload",
    "等待中…": "Waiting…",
    "先选一个文件": "Pick a file first",
    "先填目标路径": "Enter a target path first",
    "上传中…": "Uploading…",
    "失败：网络错误": "Failed: network error",
}

GROUPS = [
    ("i18n 第八批：设备信息标签", DEVICE_LABELS),
    ("i18n 第八批：网页控制台 / 上传页", WEB_CONSOLE),
]

STALE_KEYS = []

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
i18n 第三批英文词条：**审批弹窗**这条链路。

弹窗里显示的标题（summary）和明细（detail）都是工具执行时在 mcpcore 里拼出来的，
第二批只翻了权限名和按钮，于是出现「Control screen」配「读取界面结构」这种半截英文。
这批把拼句改成 `%s` 模板后，补齐对应的英文。
"""

POPUP = {
    # ---------------- 悬浮窗 / 通知栏里的标题
    "查看设备信息": "View device info",
    "列出目录 %s": "List directory %s",
    "查看目录树 %s": "View directory tree %s",
    "查看信息 %s": "View info %s",
    "读取文件 %s": "Read file %s",
    "查看图片 %s": "View image %s",
    "搜索目录 %s": "Search directory %s",
    "计算校验值 %s": "Hash %s",
    "查看存储空间": "View storage info",
    "查看服务器信息": "View server info",
    "%s 文件 %s（%s）": "%s file %s (%s)",
    "修改文件 %s（替换 %s 处）": "Edit file %s (%s replacements)",
    "创建自定义工具 %s": "Create custom tool %s",
    "新建目录 %s": "Create directory %s",
    "复制 %s → %s": "Copy %s → %s",
    "移动 %s → %s": "Move %s → %s",
    "删除 %s %s": "Delete %s %s",
    "还原回收站条目 %s": "Restore trash entry %s",
    "清空回收站（%s 项，%s）": "Empty trash (%s items, %s)",
    "发送通知给用户": "Send a notification to the user",
    "执行命令：%s": "Run command: %s",
    "查看 Shell 环境": "View shell environment",
    "修改自定义工具 %s": "Update custom tool %s",
    "删除自定义工具 %s": "Delete custom tool %s",
    "查看自定义工具": "View custom tools",
    "导出自定义工具（%s 个）到 %s": "Export %s custom tools to %s",
    "导入自定义工具（%s）": "Import custom tools (%s)",
    "记忆：搜索「%s」": 'Memory: search "%s"',
    "记忆：读取 %s 个实体": "Memory: open %s entities",
    "记忆：删除实体 %s": "Memory: delete entities %s",
    "记忆：删除 %s 条关系": "Memory: delete %s relations",
    "查看记忆库统计": "View memory stats",
    "列出工具包": "List tool packs",
    "激活工具包 %s": "Activate tool pack %s",
    "停用工具包 %s": "Deactivate tool pack %s",
    "管理工具包：%s %s": "Manage tool packs: %s %s",
    "获取访问令牌": "Get the access token",
    "读取界面结构": "Read UI tree",
    "查看前台应用": "Check the foreground app",
    "列出第三方应用": "List third-party apps",
    "等待「%s」%s": 'Wait for "%s" to %s',
    "出现": "appear",
    "消失": "disappear",
    "网页 / HTTP 上传：%s（%s）": "Web / HTTP upload: %s (%s)",
    "网页 / HTTP 下载：%s": "Web / HTTP download: %s",

    # ---------------- 弹窗明细（detail）
    "来源：%s": "Source: %s",
    "后端：%s": "Backend: %s",
    "后端：%s（%s）": "Backend: %s (%s)",
    "\n工作目录：%s": "\nWorking dir: %s",
    "\n超时：%s 秒": "\nTimeout: %s s",
    "目标：%s\n内容预览：%s": "Target: %s\nContent preview: %s",
    "文件：%s\n匹配到 %s 处，将替换 %s 处": "File: %s\n%s matches found, %s will be replaced",
    "源：%s\n目标：%s\n大小：%s": "From: %s\nTo: %s\nSize: %s",
    "源：%s\n目标：%s": "From: %s\nTo: %s",
    "原位置：%s\n还原到：%s": "Original: %s\nRestore to: %s",
    "文件：%s\n大小：%s": "File: %s\nSize: %s",
    "大小 %s": "Size %s",
    "路径：%s\n大小：%s\n方式：%s": "Path: %s\nSize: %s\nMethod: %s",
    "将彻底删除，无法恢复": "Deleted permanently, cannot be undone",
    "彻底删除": "Delete permanently",
    "移动到回收站": "Move to trash",
    "目录": "folder",
    "文件": "file",
    "（私有目录不进回收站）": " (app-private files skip the trash)",
    "写入位置：%s": "Write to: %s",
    "\n（追加模式）": "\n(append mode)",
    "\n（应用私有目录，经 %s 写入）": "\n(app-private directory, written via %s)",
    "\n选「始终允许 / 始终拒绝」会把「%s」这个工具本身设为允许 / 拒绝。":
        "\n\"Always allow / always deny\" sets the tool \"%s\" itself to allow / deny.",
    "\n选「始终允许 / 始终拒绝」会记住这条规则：以 `%s` 开头的命令":
        "\n\"Always allow / always deny\" remembers this rule: commands starting with `%s`",
    "等待用户审批：%s": "Waiting for user approval: %s",

    # ---------------- 拒绝时的报错
    "工具「%s」已被单独设为禁止": 'Tool "%s" is set to Deny',
    "工具「%s」已被单独设为「禁止」（可在 App 的「工具管理」里改回来）":
        'Tool "%s" is set to Deny (change it back in the app under Tool management)',
    "工具「%s」被单独设为「询问」": 'Tool "%s" is set to Ask',
    "已拒绝（%s）": "Denied (%s)",
    "权限「%s」被拒绝（%s）": 'Permission "%s" denied (%s)',
}

GROUPS = [
    ("i18n 第三批：审批弹窗（标题 / 明细 / 拒绝报错）", POPUP),
]

STALE_KEYS = []

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
i18n 第四批英文词条：**工具输出 / 日志 / 错误消息**。

这些是工具真正返回给 AI 的正文（命令结果、文件列表、回收站操作结果…），
以及写进日志、在 App 的 Logs 页直接看得见的消息。

注意几处「拼装片段」的翻法：
  * " 个"  → ""        —— 英文数字不需要量词（"Custom tools: 3"）
  * " 条\n" → "\n"      —— 同上（"  Observations: 3\n"）
  * "　后端：" 是全角空格，英文改成两个普通空格更自然
"""

# ---------------------------------------------------------------- 日志 / 错误
LOG_AND_ERROR = {
    "服务已在运行": "The service is already running",
    "accept 失败：%s": "accept failed: %s",
    "连接处理异常：%s": "Connection handling error: %s",
    "来自审批弹窗": "from the approval pop-up",
    "用户允许一次": "User allowed once",
    "用户拒绝（一次）": "User denied (once)",
    "审批超时（%s 秒无响应），已自动拒绝": "Approval timed out (%s s with no response), auto-denied",
    "服务已停止，审批取消": "Service stopped, approval cancelled",
    "用户未批准「%s」（%s）": 'User did not approve "%s" (%s)',
    "没有可用的审批界面，「%s」被自动拒绝": 'No approval UI available, "%s" was auto-denied',
    "用户选择「始终允许」→ 工具「%s」已单独设为允许": 'User chose "always allow" → tool "%s" is now set to Allow',
    "用户选择「始终允许」→ 已添加命令规则：%s 开头的命令":
        'User chose "always allow" → added a command rule: commands starting with %s',
    "用户选择「始终允许」→ 权限「%s」已设为允许": 'User chose "always allow" → permission "%s" is now set to Allow',
    "用户选择「始终拒绝」→ 工具「%s」已单独设为拒绝": 'User chose "always deny" → tool "%s" is now set to Deny',
    "用户选择「始终拒绝」→ 已添加命令规则：%s 开头的命令被拒绝":
        'User chose "always deny" → added a command rule: commands starting with %s are denied',
    "用户选择「始终拒绝」→ 权限「%s」已设为拒绝": 'User chose "always deny" → permission "%s" is now set to Deny',
}

# ---------------------------------------------------------------- 沙箱 / 文件系统错误
SANDBOX_ERROR = {
    "路径不存在：%s": "Path does not exist: %s",
    "路径不合法": "Invalid path",
    "路径超出允许范围：%s\n当前允许的根目录只有：%s\n":
        "Path is outside the allowed range: %s\nThe only allowed roots are: %s\n",
    "（需要在 App 的「设置 → 允许访问的目录」里添加，或把「不限制目录」打开）":
        "(Add it in the app under Settings → Allowed directories, or turn on \"Unrestricted directories\".)",
    "系统目录受保护，禁止访问：%s": "System directory is protected, access denied: %s",
    "应用私有目录没有开放：%s\n": "App-private directory access is off: %s\n",
    "（到 设置 → 权限 → 应用私有目录 里选「只读」或「可读写」；需要 root 或 Shizuku）":
        "(Choose Read-only or Read-write in Settings → Permissions → App-private directories; needs root or Shizuku.)",
    "应用私有目录当前是「只读」模式，不能修改：%s\n":
        "The app-private directory is currently Read-only, cannot modify: %s\n",
    "（要写入请到 设置 → 权限 → 应用私有目录 改成「可读写」）":
        "(To write, switch it to Read-write in Settings → Permissions → App-private directories.)",
    "本地": "Local",
    "应用自己没有权限，而且没有可用的 root / Shizuku":
        "The app lacks permission and no root / Shizuku is available",
    "移动到回收站失败：%s": "Failed to move to trash: %s",
    "回收站里的文件已不存在：%s": "The file is no longer in the trash: %s",
    "目标已存在，无法还原：%s": "Destination already exists, cannot restore: %s",
    "还原失败：%s": "Restore failed: %s",
    "删除失败：%s": "Delete failed: %s",
    "无法创建目录：%s": "Cannot create directory: %s",
}

# ---------------------------------------------------------------- 命令执行结果
SHELL_OUTPUT = {
    "退出码：": "Exit code: ",
    "　耗时：": "  Time: ",
    "　后端：": "  Backend: ",
    "命令：": "Command: ",
    "（超时，已强制结束）": "(timed out, killed)",
    "（无输出）\n": "(no output)\n",
    "（输出过长，已截断）\n": "(output too long, truncated)\n",
    "Shell 后端\n": "Shell backends\n",
    "[可用] ": "[available] ",
    "[不可用] ": "[unavailable] ",
    "当前 auto 会选中：": "auto currently picks: ",
    "无（只能做文件操作）": "none (file operations only)",
    "优先级设置：": "Preference order: ",
    "\n环境\n": "\nEnvironment\n",
    "  默认超时=": "  default timeout=",
    "不限制（命令一直跑到自己结束）": "no limit (runs until it finishes on its own)",
    "\n命令规则（按顺序匹配，第一条命中生效）\n":
        "\nCommand rules (matched in order, first hit wins)\n",
    "  （无，所有命令都走「执行命令」权限开关：":
        "  (none — every command goes through the Run commands switch: ",
    "）\n": ")\n",
    "\n自定义工具：": "\nCustom tools: ",
    " 个": "",
}

# ---------------------------------------------------------------- 自定义工具
CUSTOM_TOOLS_OUTPUT = {
    "已创建自定义工具：%s\n": "Created custom tool: %s\n",
    "标题：%s\n参数：%s 个\n命令模板：%s\n":
        "Title: %s\nParameters: %s\nCommand template: %s\n",
    "现在开始，客户端 tools/list 里就会出现它。":
        "It will appear in the client's tools/list from now on.",
    "缺少 name": "Missing name",
    "找不到工具：%s": "Tool not found: %s",
    "已更新 %s\n命令模板：%s": "Updated %s\nCommand template: %s",
    "已删除自定义工具：%s": "Deleted custom tool: %s",
    "还没有自定义工具。可以用 create_custom_tool 造一个。":
        "No custom tools yet. Use create_custom_tool to build one.",
    "自定义工具（%s 个）\n": "Custom tools (%s)\n",
    "（已停用）": " (disabled)",
    "  标题：": "  Title: ",
    "  说明：": "  Description: ",
    "  命令：": "  Command: ",
    "  参数：": "  Parameters: ",
    "(必填)": "(required)",
    "  工作目录：": "  Working dir: ",
    "  后端：": "  Backend: ",
    "　超时：%s 秒": "  Timeout: %s s",
    "　运行次数：": "  Runs: ",
    "目标是一个目录：%s": "The destination is a directory: %s",
    "已导出 %s 个自定义工具\n文件：%s\n": "Exported %s custom tools\nFile: %s\n",
    "大小：%s": "Size: %s",
    "这是一个目录：%s": "This is a directory: %s",
    "文件太大（%s）": "File too large (%s)",
    "\n当前共 %s 个自定义工具": "\n%s custom tools in total",
    "自定义工具": "Custom tool",
    "\n（这是用户自定义的工具，实际执行命令：":
        "\n(user-defined tool; it actually runs this command: ",
    "自定义工具「%s」": 'custom tool "%s"',
}

# ---------------------------------------------------------------- 网页上传 / 下载
FILE_GATEWAY = {
    "只支持 POST / PUT（GET 会给你一个上传网页）":
        "Only POST / PUT is supported (GET gives you an upload page)",
    "缺少 path 参数：要写到手机上的哪个位置":
        "Missing the path parameter: where on the phone should it be written?",
    "当前模式下不允许写入": "Writing is not allowed in the current mode",
    "请求体是空的（把文件内容放在 body 里发过来）":
        "The request body is empty (send the file content in the body)",
    "文件太大：%s，上限 %s MB": "File too large: %s, limit is %s MB",
    "写入失败：应用和 root / Shizuku 都没能写入 %s":
        "Write failed: neither the app nor root / Shizuku could write %s",
    "上传 %s 字节 → %s": "Uploaded %s bytes → %s",
    "已写入 %s": "Written to %s",
    "只支持 GET": "Only GET is supported",
    "缺少 path 参数": "Missing the path parameter",
    "文件不存在：%s": "File does not exist: %s",
    "这是一个目录，不能直接下载：%s": "This is a directory, cannot download it directly: %s",
    "读不出来：应用没权限，且 root / Shizuku 不可用":
        "Cannot read: the app lacks permission and root / Shizuku is unavailable",
    "下载 %s 字节 ← %s": "Downloaded %s bytes ← %s",
    "没有获得读取许可": "No read permission granted",
}

# ---------------------------------------------------------------- 记忆库统计正文
MEMORY_OUTPUT = {
    "\n分区：\n": "\nFolders:\n",
    "\n实体类型：\n": "\nEntity types:\n",
    "\n关系类型：\n": "\nRelation types:\n",
    "  实体：": "  Entities: ",
    "  关系：": "  Relations: ",
    "  观察：": "  Observations: ",
    " 条\n": "\n",
}

GROUPS = [
    ("i18n 第四批：工具输出与日志 —— 审批 / 日志", LOG_AND_ERROR),
    ("i18n 第四批：工具输出与日志 —— 沙箱与文件系统错误", SANDBOX_ERROR),
    ("i18n 第四批：工具输出与日志 —— 命令执行结果", SHELL_OUTPUT),
    ("i18n 第四批：工具输出与日志 —— 自定义工具", CUSTOM_TOOLS_OUTPUT),
    ("i18n 第四批：工具输出与日志 —— 网页上传下载", FILE_GATEWAY),
    ("i18n 第四批：工具输出与日志 —— 记忆库统计", MEMORY_OUTPUT),
]

STALE_KEYS = []

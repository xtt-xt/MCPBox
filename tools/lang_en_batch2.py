#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
i18n 第二批英文词条。

键 = 中文原文的**运行时值**（跟源码里 L("...") 传给 Lang.t 的完全一致），
值 = 英文。由 merge_lang_en.py 转义成 Kotlin 字面量后写进 LangEn.kt。

分组只是为了好维护，跟 LangEn.kt 里的分组注释一一对应。
"""

APP_UI = {
    # ---------------- 通知栏
    "停止服务": "Stop service",
    "打开控制台": "Open console",
    "AI 请求「%s」": 'AI requests "%s"',
    "\n（%s 秒内未处理将自动拒绝）": "\n(auto-denied if not handled within %s s)",

    # ---------------- 前台服务通知
    "MCP 文件盒正在运行": "MCP Box is running",
    "MCP 文件盒启动失败": "MCP Box failed to start",
    "服务器启动失败（端口可能被占用）": "Server failed to start (the port may be in use)",
    "App 被划掉，服务器继续运行": "App swiped away, server keeps running",
    "端口 %s · 已运行 %s": "Port %s · up %s",
    " · 请求 %s": " · %s requests",
    " · 待审批 %s": " · %s pending",
    "点开查看地址和权限设置": "Tap to see the addresses and permission settings",
    "启动失败：%s": "Failed to start: %s",
    "未知原因": "unknown reason",

    # ---------------- Shizuku
    "需要先安装并启动 Shizuku，然后在 App 的「终端」页点「申请 Shizuku 授权」":
        'Install and start Shizuku first, then tap "Request Shizuku permission" on the Terminal page',
    "Shizuku 服务不可用": "Shizuku service unavailable",
    "Shizuku 拒绝创建进程": "Shizuku refused to create the process",
    "拿不到 ShizukuRemoteProcess 构造函数": "Couldn't get the ShizukuRemoteProcess constructor",
    "Shizuku 服务已连接": "Shizuku service connected",
    "Shizuku 服务已断开": "Shizuku service disconnected",
    "已获得 Shizuku 授权": "Shizuku authorization granted",
    "Shizuku 授权结果：%s": "Shizuku authorization result: %s",
    "已允许": "Allowed",
    "被拒绝": "Denied",
    "注册 Shizuku 监听失败：%s": "Failed to register Shizuku listeners: %s",
    "旧版本": "old version",
    "Shizuku 版本太旧，请升级到 11 以上": "Shizuku is too old — please upgrade to 11 or newer",
    "Shizuku 没有运行，请先打开 Shizuku 应用并启动服务":
        "Shizuku isn't running — open the Shizuku app and start the service first",
    "已经授权过了": "Already authorized",
    "你之前拒绝过，需要到 Shizuku 应用 → 授权管理里手动允许「MCP 文件盒」":
        'You denied it before — allow "MCP Box" manually in the Shizuku app under Authorized apps',
    "已发送授权请求": "Authorization request sent",
    "申请失败：%s": "Request failed: %s",
    "未安装 Shizuku": "Shizuku not installed",
    "Shizuku 未运行": "Shizuku isn't running",
    "已授权（%s）": "Authorized (%s)",
    "未授权": "Not authorized",
    "未知": "Unknown",

    # ---------------- 终端
    "没有可用的 Shell 后端": "No shell backend available",
    "终端已启动（%s）": "Terminal started (%s)",
    "终端启动失败：%s": "Terminal failed to start: %s",
    "已连接：%s": "Connected: %s",
    "[无法启动 %s：%s]": "[failed to start %s: %s]",
    "[会话已结束，退出码 %s]": "[session ended, exit code %s]",
    "[会话不在运行，正在重新启动]": "[session not running, restarting]",
    "[^C 已中断，会话重启]": "[^C interrupted, session restarted]",
    "...（输出过多，已截断旧内容）": "...(too much output, older lines trimmed)",

    # ---------------- 更新检查
    "网络请求失败": "Network request failed",
    "返回内容看不懂": "Couldn't make sense of the response",
    "仓库还是私有的，暂时检查不到更新": "The repo is still private, so updates can't be checked yet",
    "请求太频繁，过一会儿再试": "Too many requests — try again in a bit",
    "没有读到版本号": "No version number found",
    "服务器返回 %s": "Server returned %s",

    # ---------------- 通知栏审批回执
    "通过通知栏处理了审批请求（%s）": "Approval handled from the notification (%s)",
    "该审批请求已失效": "That approval request has expired",
    "用户从通知栏停止了服务": "User stopped the service from the notification",

    # ---------------- 网络与语言
    "有线/热点": "Ethernet/hotspot",
    "热点": "Hotspot",
    "局域网": "LAN",
    "本机": "This phone",
    "跟随系统": "Follow system",
    "已导入语言包 %s（%s 条译文）": "Imported language pack %s (%s entries)",
    "没有读到 entries": 'No "entries" found',
    "语言包里一条译文都没有": "The language pack contains no translations at all",

    # ---------------- 关于页
    "Material Components (HCT 取色算法)": "Material Components (HCT color extraction)",
    "喵": "Meow",
    "喵喵喵": "Meow meow meow",
    "喵～ 猫娘语已解锁": "Meow~ cat-girl language unlocked",
    "猫娘语已解锁：设置 → 外观 → 语言": "Cat-girl language unlocked: Settings → Appearance → Language",

    # ---------------- 权限页那条漏翻的拼接片段（词表里原来的键少了前导空格）
    " 转发；想只放开某一个应用，可以在下面加路径规则（例：/data/data/包名）。":
        " forwarding. To open up a single app, add a path rule below (e.g. /data/data/<package>).",
}

CORE_LABELS = {
    # ---------------- 日志分类 / 审批
    "系统": "System",
    "超时拒绝": "Timed out — auto-denied",
    "已取消": "Cancelled",
}

SERVER_INSTRUCTIONS = {
    # ---------------- MCP instructions（只在「让 AI 自己开关工具包」打开时才出现的那几段）
    "这是运行在手机上的文件管理服务器（MCP 文件盒），可以直接读写手机本地文件。":
        "This is a file-management server running on a phone (MCP Box); it can read and write the phone's local files directly.",
    "根目录：": "Roots: ",
    "路径规则：可写绝对路径；相对路径和 ~ 表示第一个根目录。":
        "Paths: absolute paths are fine; relative paths and ~ refer to the first root.",
    "安全机制：涉及写入/删除的操作会实时在手机上弹出审批窗口，被拒绝时不要反复重试。":
        "Safety: write/delete operations pop up an approval prompt on the phone in real time. If denied, don't retry over and over.",
    "删除默认进入回收站，可用 list_trash / restore_trash 找回。":
        "Deleting sends files to the trash by default; use list_trash / restore_trash to get them back.",
    "\n【工具包】你看到的工具是分包的，当前会话（%s）只加载了一部分。":
        "\n[Tool packs] The tools you see are grouped into packs; session (%s) has only some of them loaded.",
    "还没激活的包：": "Packs not activated yet: ",
    "要用的工具不在列表里时，先 list_packs 看有哪些包，再用 activate_pack 打开。":
        "If a tool you need isn't listed, run list_packs to see the available packs, then open it with activate_pack.",
    "注意：激活之后**需要客户端重新连接才能生效**，这一轮里不一定能用上；":
        "Note: after activating, **the client must reconnect for it to take effect** — it may not be usable this turn; ",
    "实在调不到就请用户去 App 的「权限 → 工具包」里勾选，并重连客户端。":
        "If you still can't call it, ask the user to tick it in the app under Permissions → Tool packs and reconnect the client.",
    "\n终端：run_shell 可以执行 Shell 命令（后端 %s），危险命令同样会弹窗审批，用户可以「始终允许」某条命令。":
        "\nTerminal: run_shell executes shell commands (backend: %s). Dangerous commands are approved through a pop-up too, and the user can \"always allow\" a given command.",
    "自定义工具：": "Custom tools: ",
    "（用户自己定义的操作，可直接调用）。": " (user-defined operations, callable directly).",
    "建议流程：server_info 了解环境 → list_dir / search_files 定位 → read_file 查看 → ":
        "Suggested flow: server_info to learn the environment → list_dir / search_files to locate → read_file to inspect → ",
    "write_file / edit_file 修改 → notify_user 通知用户。":
        "write_file / edit_file to modify → notify_user to tell the user.",
    "当前权限：": "Current permissions: ",
}

TOOLS_PACKS = {
    # ---------------- 工具包管理工具
    "列出工具包": "List tool packs",
    "列出所有工具包：每个包的用途说明、里面有哪些工具、当前是否已激活。**你现在只看到基础包 + 已激活包里的工具**；要用别的工具，先在这里找到对应的包，再用 activate_pack 打开。常驻的基础包不用激活。":
        "List every tool pack: what it's for, which tools it holds, and whether it's currently active. **You can only see the tools of the base pack plus the active packs**; to use others, find the matching pack here and open it with activate_pack. The base pack is always on and needs no activation.",
    "激活工具包": "Activate a tool pack",
    "激活一个工具包，包里的工具立刻出现在你的工具列表里（客户端可能需要刷新一次 tools/list）。用 list_packs 查看所有可用的包。激活纯属「让工具看得见」，不改变任何权限。":
        "Activate a tool pack — its tools show up in your tool list right away (the client may need to refresh tools/list once). Use list_packs to see all available packs. Activating only makes tools visible; it changes no permissions.",
    "停用工具包": "Deactivate a tool pack",
    "停用一个工具包，把它的工具从工具列表里收起来（省 token）。基础包不能停用。注意：停用后这些工具不再出现在 tools/list 里。":
        "Deactivate a tool pack and put its tools away (saves tokens). The base pack can't be deactivated. Note: those tools will no longer appear in tools/list.",
    "重置工具包": "Reset tool packs",
    "把当前会话的工具包激活状态重置回默认（基础包 + 文件读取 + 记忆库）。找不到该用哪个包、或者上一轮开太多包把 token 撑爆了，就用它清一下。":
        "Reset the current session's pack activation back to the default (base + file reading + memory). Use it when you can't tell which pack you need, or when too many packs were opened last turn and tokens blew up.",
    "管理工具包": "Manage tool packs",
    "新建 / 修改 / 删除**自定义**工具包（内置包不能改，但可以照它新建一个自己的）。包就是一组工具的名字，用来把工具分门别类、按需激活。字段：id（只允许字母数字点横线下划线，留空自动生成）、title、description、tools（工具名数组）。不确定有哪些工具名，可以先 list_packs 看内置包。":
        "Create / modify / delete **custom** tool packs (built-in packs can't be changed, but you can copy one into your own). A pack is just a list of tool names, used to group tools and activate them on demand. Fields: id (letters, digits, dot, dash and underscore only; auto-generated if blank), title, description, tools (array of tool names). If you're unsure which tool names exist, run list_packs to look at the built-in packs first.",
    "是否列出每个包里的工具名（默认 true）": "Whether to list the tool names inside each pack (default true)",
    "要激活的包 id，例如 file.write、shell、memory": "ID of the pack to activate, e.g. file.write, shell, memory",
    "要停用的包 id": "ID of the pack to deactivate",
    "要做什么": "What to do",
    "包的 id（update / delete 时必填；create 留空自动生成）":
        "Pack id (required for update / delete; auto-generated if blank on create)",
    "包名（中文也行）": "Pack name (non-English is fine)",
    "给 AI 看的说明：写清什么时候该激活这个包":
        "Description shown to the AI: make clear when this pack should be activated",
    "工具名，例如 read_file": "Tool name, e.g. read_file",
}

TOOLS_READ = {
    # ---------------- 只读工具
    "设备信息": "Device info",
    "查看手机型号、系统版本、电量、存储占用等设备信息。":
        "Show device info: phone model, system version, battery, storage usage, etc.",
    "列出目录": "List directory",
    "列出目录下的文件和子目录（名称、类型、大小、修改时间）。path 留空表示默认根目录。":
        "List the files and subdirectories of a directory (name, type, size, modified time). Leave path empty for the default root.",
    "目录树": "Directory tree",
    "以缩进树的形式展示目录结构，用于快速了解一个文件夹里有什么。":
        "Show the directory structure as an indented tree, to quickly see what's inside a folder.",
    "文件信息": "File info",
    "查看文件或目录的详细信息：大小、修改时间、读写权限、子项数量。":
        "Show details about a file or directory: size, modified time, read/write permissions, number of children.",
    "读取文件": "Read file",
    "读取文本文件内容，带行号输出。可用 startLine / lineCount 分段读取大文件。二进制文件会报错，图片请用 read_image。":
        "Read a text file with line numbers. Use startLine / lineCount to read large files in chunks. Binary files raise an error — use read_image for pictures.",
    "查看图片": "View image",
    "把手机上的图片读出来给模型看（返回图片内容，支持 png/jpg/gif/webp/bmp）。":
        "Read an image from the phone and show it to the model (returns the image itself; supports png/jpg/gif/webp/bmp).",
    "搜索文件": "Search files",
    "按文件名（通配符或正则，可写 re: 前缀）或文件内容（正则）搜索。适合找「某个文件在哪」或「哪个文件里有这段文字」。":
        "Search by file name (wildcard or regex with the re: prefix) or by file contents (regex). Good for \"where is this file\" or \"which file contains this text\".",
    "文件校验值": "File hash",
    "计算文件的 md5 / sha1 / sha256，用于比较两个文件是否相同。":
        "Compute a file's md5 / sha1 / sha256, to compare whether two files are identical.",
    "存储空间": "Storage info",
    "查看当前允许访问的根目录、以及各分区的总空间/剩余空间。":
        "Show the roots you're allowed to access, plus total/free space of each storage volume.",
    "服务器信息": "Server info",
    "查看这台手机上 MCP 文件服务器的状态：版本、运行时间、根目录、权限设置、可用的操作。AI 在开始操作前可以先调用它了解环境。":
        "Show the state of the MCP file server on this phone: version, uptime, roots, permission settings, available operations. Call it before starting work to understand the environment.",
    "目录路径，可省略（默认根目录）；支持 ~ 开头表示根目录": "Directory path; can be omitted (defaults to the root). A leading ~ means the root.",
    "是否显示以点开头的隐藏文件，默认 false": "Whether to show hidden files (starting with a dot). Default false.",
    "排序方式": "Sort order",
    "最多返回条目数": "Maximum number of entries to return",
    "起始目录，可省略": "Starting directory; can be omitted",
    "最大深度": "Maximum depth",
    "最多条目数": "Maximum number of entries",
    "是否显示隐藏文件": "Whether to show hidden files",
    "文件或目录路径": "File or directory path",
    "文件路径": "File path",
    "从第几行开始（从 1 计数）": "Line to start from (1-based)",
    "读取多少行": "How many lines to read",
    "最多读取字节数（防止超大文件）": "Maximum bytes to read (guards against huge files)",
    "文本编码，默认 UTF-8，可填 GBK 等": "Text encoding; UTF-8 by default, GBK etc. also work",
    "是否显示行号前缀": "Whether to prefix line numbers",
    "图片文件路径": "Image file path",
    "最大允许的图片大小": "Maximum allowed image size",
    "搜索起始目录，默认根目录": "Directory to start searching from; defaults to the root",
    "文件名匹配，如 *.txt、报告*、re:^IMG_\\d+\\.jpg$":
        "File name pattern, e.g. *.txt, report*, re:^IMG_\\d+\\.jpg$",
    "文件内容正则匹配，如 TODO、import .*kotlin": "Regex to match in file contents, e.g. TODO, import .*kotlin",
    "区分大小写": "Case sensitive",
    "最大搜索深度": "Maximum search depth",
    "最多返回条数": "Maximum number of results",
    "内容搜索时单个文件最大字节": "Maximum bytes per file when searching contents",
    "是否把匹配的目录也列出来": "Whether to list matching directories too",
    "是否搜索隐藏文件": "Whether to search hidden files",
    "算法": "Algorithm",
}

TOOLS_WRITE = {
    # ---------------- 写入 / 删除工具
    "写入文件": "Write file",
    "创建或写入文件（会自动创建上级目录）。mode=overwrite 覆盖，append 追加，create_new 只在文件不存在时创建。content 也可以用 base64 编码来写二进制文件。":
        "Create or write a file (parent directories are created automatically). mode=overwrite replaces, append adds to the end, create_new only creates when missing. content can be base64-encoded to write binary files.",
    "修改文件内容": "Edit file",
    "在文本文件里查找并替换内容（类似编辑器的「全部替换」），比整文件重写更安全。regex=true 时 oldText 按正则处理。默认要求恰好匹配 1 处，可用 count 控制；replaceAll=true 替换全部。":
        "Find and replace text inside a file (like the editor's \"replace all\"), safer than rewriting the whole file. With regex=true, oldText is treated as a regular expression. Exactly one match is required by default; use count to change that, or replaceAll=true to replace every match.",
    "新建目录": "Create directory",
    "创建目录（默认连同上级目录一起创建）。": "Create a directory (parent directories are created by default).",
    "复制文件/目录": "Copy file/folder",
    "复制文件或整个目录到目标位置。destination 可以是完整的新路径，也可以是已存在的目录（自动放到里面）。":
        "Copy a file or an entire directory to a destination. destination can be a full new path, or an existing directory (the item goes inside it).",
    "移动/重命名": "Move/rename",
    "移动文件或目录，也可以用来重命名（源和目标在同一目录、只改名字）。":
        "Move a file or directory; also works as rename (same directory for source and destination, only the name changes).",
    "删除文件/目录": "Delete file/folder",
    "删除文件或目录。删除目录必须显式设置 recursive=true。默认会移动到回收站（可在 App 里关闭，或用 permanent=true 直接彻底删除）。":
        "Delete a file or directory. Deleting a directory requires recursive=true explicitly. By default items go to the trash (can be turned off in the app, or bypass it with permanent=true).",
    "查看回收站": "View trash",
    "列出回收站里的文件（AI 误删的文件可以在这里还原）。":
        "List the files in the trash (files the AI deleted by mistake can be restored here).",
    "还原回收站文件": "Restore from trash",
    "把回收站里的文件还原回原位置，也可以指定新的位置。":
        "Restore a file from the trash to its original location, or to a new one.",
    "清空回收站": "Empty trash",
    "彻底删除回收站里的所有文件（不可恢复）。":
        "Permanently delete every file in the trash (unrecoverable).",
    "通知手机主人": "Notify the phone's owner",
    "在手机上弹一条通知，用来告诉用户任务完成或需要他做什么。":
        "Post a notification on the phone to tell the user a task is done or that they need to do something.",
    "目标文件路径": "Target file path",
    "要写入的内容": "Content to write",
    "写入方式": "Write mode",
    "内容编码": "Content encoding",
    "自动创建上级目录": "Create parent directories automatically",
    "要被替换的原文（或正则）": "Text to replace (or a regex)",
    "替换成的内容，可为空字符串": "Replacement text; an empty string is allowed",
    "替换所有匹配（默认只替换第一处）": "Replace every match (by default only the first one)",
    "oldText 按正则表达式处理": "Treat oldText as a regular expression",
    "最多替换多少处": "Maximum number of replacements",
    "要创建的目录路径": "Directory path to create",
    "同时创建上级目录": "Create parent directories as well",
    "源路径": "Source path",
    "目标路径": "Destination path",
    "目标路径（新名字或新位置）": "Destination path (new name or new location)",
    "目标已存在时覆盖": "Overwrite if the destination already exists",
    "要删除的路径": "Path to delete",
    "删除非空目录时必须为 true": "Must be true to delete a non-empty directory",
    "true=彻底删除，false/省略=进回收站（若已开启）":
        "true = delete permanently; false/omitted = move to the trash (if enabled)",
    "回收站条目的 ID（见 list_trash；也可以用 name 匹配文件名）":
        "ID of the trash entry (see list_trash; name can also match the file name)",
    "用文件名匹配（ID 不方便时使用）": "Match by file name (for when the ID isn't handy)",
    "还原到指定路径，省略则回原位置": "Restore to this path; omitted means back to the original location",
    "必须为 true 才会执行": "Must be true for this to run",
    "通知标题": "Notification title",
    "通知内容": "Notification text",
}

TOOLS_SHELL = {
    # ---------------- 命令与自定义工具
    "执行终端命令": "Run a terminal command",
    "在手机上执行一条 Shell 命令并返回输出（就像在 Termux 里敲命令）。可以带上 cwd 指定工作目录。涉及危险操作（删除、改系统设置、安装应用等）时会在手机上弹窗审批；用户可以选择「始终允许某条命令」以免每次都问。":
        "Run a shell command on the phone and return its output (like typing it in Termux). Pass cwd to set the working directory. Dangerous operations (deleting, changing system settings, installing apps…) trigger an approval prompt on the phone; the user can choose \"always allow\" for a given command so they aren't asked every time.",
    "Shell 环境": "Shell environment",
    "查看可用的命令执行后端（应用沙箱 / Root / Shizuku）、当前身份、环境变量和超时设置。":
        "Show the available command backends (app sandbox / Root / Shizuku), the current identity, environment variables and timeout settings.",
    "创建自定义工具": "Create a custom tool",
    "给自己造一个新的 MCP 工具：填好名称、说明、参数和命令模板，之后它就会出现在 tools/list 里。命令模板里用 {{参数名}} 插入参数（会做 shell 转义），{{参数名:raw}} 表示原样插入。例：name=disk_usage, command=\"du -sh {{path}}\", params=[{name:path,type:string,required:true}]":
        "Build yourself a new MCP tool: fill in the name, description, parameters and a command template, and it appears in tools/list from then on. In the template, {{param}} inserts a parameter (shell-escaped) and {{param:raw}} inserts it verbatim. Example: name=disk_usage, command=\"du -sh {{path}}\", params=[{name:path,type:string,required:true}]",
    "修改自定义工具": "Update a custom tool",
    "修改已有自定义工具（按 name 定位，没传的字段保持原样）。":
        "Update an existing custom tool (matched by name; fields you don't pass stay as they are).",
    "删除自定义工具": "Delete a custom tool",
    "删除一个自定义工具。": "Delete one custom tool.",
    "自定义工具列表": "List custom tools",
    "列出所有自定义工具及其定义（可以据此修改或导出）。":
        "List every custom tool with its definition (use it to edit or export them).",
    "导出自定义工具": "Export custom tools",
    "把所有自定义工具导出成一个 JSON 文件（可以分享给别的设备，或用 import_custom_tools 再导回来）。path 省略时导出到 根目录/xtt/mcp-tools.json。":
        "Export all custom tools to a JSON file (share it with another device, or import it back with import_custom_tools). If path is omitted it goes to <root>/xtt/mcp-tools.json.",
    "导入自定义工具": "Import custom tools",
    "从 JSON 文件导入自定义工具（export_custom_tools 导出的格式，或直接给一个工具数组）。同名工具会被更新。replace=true 表示先清空现有的再导入。":
        "Import custom tools from a JSON file (the format export_custom_tools produces, or just an array of tools). Tools with the same name get updated. replace=true clears the existing ones first.",
    "要执行的命令，例如 ls -la /sdcard/Download 或 pm list packages":
        "Command to run, e.g. ls -la /sdcard/Download or pm list packages",
    "工作目录（可选），必须在你允许的目录里": "Working directory (optional); must be inside a directory you're allowed to use",
    "工作目录（可选）": "Working directory (optional)",
    "工作目录": "Working directory",
    "超时毫秒数，默认用设置里的值；0 表示不限制（命令会一直跑）":
        "Timeout in milliseconds; defaults to the value in settings, 0 means no limit (the command runs until it finishes)",
    "超时毫秒": "Timeout in milliseconds",
    "工具名（小写字母开头，可含数字和下划线），例：disk_usage":
        "Tool name (lowercase letter first; digits and underscores allowed), e.g. disk_usage",
    "简短中文标题": "Short title",
    "给 AI 看的说明：这个工具做什么、参数怎么填":
        "Description for the AI: what this tool does and how to fill in its parameters",
    "命令模板，例：du -sh {{path}}": "Command template, e.g. du -sh {{path}}",
    "备注（可选）": "Note (optional)",
    "要修改的工具名": "Name of the tool to update",
    "新的标题": "New title",
    "新的说明": "New description",
    "新的命令模板": "New command template",
    "是否启用": "Whether it's enabled",
    "工具名": "Tool name",
    "导出到的文件路径，省略则用 根目录/xtt/mcp-tools.json":
        "Path to export to; defaults to <root>/xtt/mcp-tools.json",
    "JSON 文件路径": "JSON file path",
    "是否先清空现有工具": "Whether to clear the existing tools first",
}

TOOLS_MEMORY = {
    # ---------------- 记忆库工具
    "记忆：新建实体": "Memory: create entities",
    "在记忆库里创建实体（节点）。实体可以是项目、工具、事件、用户偏好等任何东西。name 是唯一标识，已存在的实体不会被覆盖，只会补上空着的类型/分区。type 和 folder 都是自由文本：type 建议用「项目事实」「用户偏好」「事件」「工具」「人物」这类词，folder 用来分区（如 dev / projects / xtt / skills），不填则进「未分类」。":
        "Create entities (nodes) in the memory graph. An entity can be a project, a tool, an event, a user preference — anything. name is the unique key; existing entities are never overwritten, only their empty type/folder fields get filled in. type and folder are free text: for type, words like \"project fact\", \"user preference\", \"event\", \"tool\", \"person\" work well; folder groups them (e.g. dev / projects / xtt / skills). Leave folder empty to put it under \"Unsorted\".",
    "记忆：建立关系": "Memory: create relations",
    "在两个实体之间建立有向关系（边）：from -关系类型-> to。关系类型是自由文本，建议用大写下划线，例如 HAPPENS_AT（发生于）、PART_OF（属于）、INVOLVES（涉及）、CORRECTS（纠正）、UPDATES（更新）、RELATES_TO（相关）、FOLLOWS（先后）。两端的实体建议先用 create_entities 建好。":
        "Create a directed relation (edge) between two entities: from -RELATION-> to. The relation type is free text; UPPER_SNAKE_CASE is recommended, e.g. HAPPENS_AT, PART_OF, INVOLVES, CORRECTS, UPDATES, RELATES_TO, FOLLOWS. It's best to create both entities first with create_entities.",
    "记忆：追加观察": "Memory: add observations",
    "给已有实体追加「观察」——一条条独立的事实短句。重复的内容会自动跳过。":
        "Append \"observations\" to existing entities — short, self-contained factual statements. Duplicates are skipped automatically.",
    "记忆：读取记忆库": "Memory: read the graph",
    "读取记忆库的全部内容（实体 + 关系）。可以用 folder / type 过滤、用 limit 限制条数。内容多的时候建议先用 search_nodes 或 memory_stats 定位。":
        "Read the whole memory graph (entities + relations). Filter with folder / type and cap the number with limit. When there's a lot of content, use search_nodes or memory_stats to narrow things down first.",
    "记忆：搜索": "Memory: search",
    "按关键词搜索记忆库，匹配实体名 / 类型 / 分区 / 观察内容。结果会连带返回命中实体之间的关系和它们的直接邻居，方便顺着线索读下去。":
        "Search the memory graph by keyword, matching entity name / type / folder / observation text. Results also include the relations of each hit and its direct neighbours, so you can follow the thread.",
    "记忆：按名读取": "Memory: open entities",
    "按名字精确读取若干实体的完整内容（含它们的观察与关系）。":
        "Read several entities in full by exact name (including their observations and relations).",
    "记忆库统计": "Memory stats",
    "查看记忆库的规模：实体数、关系数、分区 / 类型 / 关系谓词的分布。写完记忆后可以用它自查结构乱不乱。":
        "Check the size of the memory graph: number of entities and relations, plus the breakdown by folder / type / relation predicate. Handy for a sanity check after writing memories.",
    "记忆：删除实体": "Memory: delete entities",
    "删除若干实体。**与它们相连的关系会一并删除**。删之前建议先 search_nodes 确认一下。":
        "Delete several entities. **Relations attached to them are deleted as well.** It's worth running search_nodes first to confirm.",
    "记忆：删除关系": "Memory: delete relations",
    "删除指定的关系。只给 from / to、不给 relationType 时，会删掉这两点之间的所有关系。":
        "Delete the given relations. If you pass only from / to without relationType, every relation between those two entities is removed.",
    "记忆：删除观察": "Memory: delete observations",
    "删掉某个实体上的若干条观察（需要一字不差地给出原文）。":
        "Delete some observations from an entity (the text must match exactly).",
    "实体名，唯一标识，例如「MCPBox 构建环境」": "Entity name, the unique key, e.g. \"MCPBox build environment\"",
    "实体类型，例如「项目事实」「用户偏好」「事件」（可用 type 代替）":
        "Entity type, e.g. \"project fact\", \"user preference\", \"event\" (type works as an alias)",
    "同 entityType（二选一即可）": "Same as entityType (pick one of the two)",
    "分区/文件夹，例如 projects、xtt（可选）": "Folder, e.g. projects, xtt (optional)",
    "一条事实，一句话说清": "One fact, stated in a single sentence",
    "起点实体名": "Source entity name",
    "终点实体名": "Target entity name",
    "关系类型，例如 PART_OF（可用 type 代替）": "Relation type, e.g. PART_OF (type works as an alias)",
    "同 relationType（二选一即可）": "Same as relationType (pick one of the two)",
    "实体名": "Entity name",
    "一句话说清一条事实": "One fact, stated in a single sentence",
    "只看某个分区（可选）": "Only look at this folder (optional)",
    "只看某个实体类型（可选）": "Only look at this entity type (optional)",
    "最多返回多少个实体，默认 50": "Maximum number of entities to return; 50 by default",
    "关键词，例如「构建」「用户偏好」「token」": "Keyword, e.g. \"build\", \"user preference\", \"token\"",
    "最多返回多少个命中实体，默认 20": "Maximum number of matching entities; 20 by default",
    "关系类型（可选，不填删全部）": "Relation type (optional; omit to delete all)",
    "观察原文": "Observation text, exactly as written",
}

TOOLS_UI = {
    # ---------------- UI 自动化工具
    "截屏": "Screenshot",
    "截一张当前屏幕的图，直接把图片给模型看（同时说明屏幕坐标空间，方便配合 ui_tap 点击）。需要 Root 或 Shizuku。看界面长什么样、确认上一步操作的结果时用它。":
        "Take a screenshot of the current screen and hand the image to the model (along with a note about the coordinate space, so ui_tap can use it). Needs Root or Shizuku. Use it to see what the UI looks like or to confirm the result of the previous action.",
    "读取界面结构": "Read UI tree",
    "读出当前屏幕上的控件树（哪些元素能点、在哪里、什么文字），返回每个元素的中心坐标，可以直接拿去 ui_tap / ui_swipe。比截图更适合让 AI 决定「点哪里」，也省 token。需要 Root 或 Shizuku。":
        "Read the widget tree of the current screen (which elements are tappable, where they are, what text they hold) and return the centre coordinates of each element, ready to feed into ui_tap / ui_swipe. Better than a screenshot for deciding where to tap, and cheaper in tokens. Needs Root or Shizuku.",
    "点击屏幕": "Tap the screen",
    "点一下屏幕上的某个位置。可以给坐标（x / y），也可以给文字 / 描述 / id 让它自己去找元素（会先读一次界面结构），匹配到多个时返回候选列表，再用 index 指定。需要 Root 或 Shizuku。":
        "Tap a spot on the screen. You can pass coordinates (x / y), or text / desc / id and let it find the element itself (it reads the UI tree first); when several elements match it returns the candidates, and you pick one with index. Needs Root or Shizuku.",
    "滑动 / 滚动": "Swipe / scroll",
    "滑动屏幕：可以给方向（up / down / left / right，表示「内容往哪边滚」），也可以给起止坐标。翻页、滚动列表、下拉刷新都用它。需要 Root 或 Shizuku。":
        "Swipe the screen: pass a direction (up / down / left / right, meaning which way the content scrolls) or explicit start/end coordinates. Use it to turn pages, scroll lists and pull to refresh. Needs Root or Shizuku.",
    "从 ($sx,$sy) 滑到 ($ex,$ey)": "Swipe from ($sx,$sy) to ($ex,$ey)",
    "内容上滚 $dist px": "Scroll content up $dist px",
    "内容下滚 $dist px": "Scroll content down $dist px",
    "向左滑 $dist px": "Swipe left $dist px",
    "向右滑 $dist px": "Swipe right $dist px",
    "输入文字": "Type text",
    "往当前焦点输入框里打字。英文数字直接键入；**中文等非 ASCII 字符**会自动改成「写剪贴板 + 模拟粘贴」的方式（所以要先点一下输入框让它获得焦点，用 ui_tap）。需要 Root 或 Shizuku。":
        "Type into the currently focused input field. ASCII text is typed directly; **non-ASCII text (e.g. Chinese)** falls back to \"write the clipboard + simulate paste\" (so tap the field first with ui_tap to focus it). Needs Root or Shizuku.",
    "按键": "Press a key",
    "按系统按键：back（返回）、home、enter、recent（最近任务）、delete、tab、escape、上下左右、音量、power、wakeup（唤醒）等。也可以直接给数字键值（如 4）。需要 Root 或 Shizuku。":
        "Press a system key: back, home, enter, recent, delete, tab, escape, arrows, volume, power, wakeup, etc. Raw numeric key codes (e.g. 4) work too. Needs Root or Shizuku.",
    "启动应用 / 看前台": "Launch an app / check the foreground",
    "action=app 启动指定包名的应用；action=home 回桌面；action=current 看当前前台是哪个应用；action=list 列出已安装的第三方应用（找包名用）。需要 Root 或 Shizuku。":
        "action=app launches the app with the given package name; action=home goes to the home screen; action=current tells you which app is in the foreground; action=list lists installed third-party apps (to find package names). Needs Root or Shizuku.",
    "等元素出现": "Wait for an element",
    "轮询界面结构，等某个文字 / 描述 / id 出现（或消失）再继续 —— 点击之后界面要加载时用它，比固定 sleep 稳。需要 Root 或 Shizuku。":
        "Poll the UI tree and wait for some text / desc / id to appear (or disappear) before continuing — use it when the UI needs to load after a tap; more reliable than a fixed sleep. Needs Root or Shizuku.",
    "保存到哪个文件（可选）。默认存到 主根目录/.MCPBox/ui/screen.png":
        "File to save to (optional). Defaults to <root>/.MCPBox/ui/screen.png",
    "图片大小上限（字节）": "Maximum image size (bytes)",
    "输出格式": "Output format",
    "只列可交互元素（可点 / 可输入 / 可滚动），默认 true":
        "Only list interactive elements (tappable / editable / scrollable); default true",
    "只显示文字 / 描述 / id 里包含这个词的元素": "Only show elements whose text / desc / id contains this word",
    "最多返回多少个元素": "Maximum number of elements to return",
    "返回 uiautomator 的原始 XML（调试用，会被截断）":
        "Return uiautomator's raw XML (for debugging; it gets truncated)",
    "横坐标（和 y 一起用时按坐标点）": "X coordinate (with y, taps by coordinate)",
    "纵坐标": "Y coordinate",
    "按元素文字定位，如：发送、确定": "Locate by element text, e.g. Send, OK",
    "按元素的 content-desc（无障碍描述）定位": "Locate by the element's content-desc (accessibility label)",
    "按 resource-id 定位，如：com.x:id/btn": "Locate by resource-id, e.g. com.x:id/btn",
    "匹配到多个时选第几个（从 0 开始）": "Which match to pick when several are found (0-based)",
    "长按（约 800ms）": "Long press (about 800 ms)",
    "方向：up=内容上滚（手指从下往上滑，看后面的内容）, down=回滚, left / right=左右滑":
        "Direction: up = scroll content up (finger moves up, revealing what's below), down = scroll back, left / right = swipe sideways",
    "滑动距离（像素），默认屏幕的三分之一": "Swipe distance in pixels; a third of the screen by default",
    "起点 x（默认屏幕中心）": "Start x (screen centre by default)",
    "起点 y（默认屏幕中心）": "Start y (screen centre by default)",
    "起点 x（给了就用坐标模式）": "Start x (switches to coordinate mode when given)",
    "起点 y": "Start y",
    "终点 x": "End x",
    "终点 y": "End y",
    "滑动耗时（毫秒），越大越慢": "Swipe duration in milliseconds; larger is slower",
    "重复几次（连续滚动时有用）": "How many times to repeat (handy for continuous scrolling)",
    "要输入的文字（支持中文）": "Text to type (Chinese is supported)",
    "先清空输入框（先按 80 次退格，够清掉一般长度的内容）":
        "Clear the input field first (presses backspace 80 times, enough for typical content)",
    "强制走剪贴板粘贴（默认只对非 ASCII 自动用）":
        "Force clipboard paste (by default only used automatically for non-ASCII)",
    "输入完再按一次回车": "Press enter once after typing",
    "按键名或键值。常用：": "Key name or key code. Common ones: ",
    "按几次": "How many times to press",
    "要做什么": "What to do",
    "应用包名（action=app 时必填），如 com.tencent.mm":
        "App package name (required for action=app), e.g. com.tencent.mm",
    "等这个文字出现": "Wait for this text to appear",
    "等这个描述出现": "Wait for this description to appear",
    "等这个 resource-id 出现": "Wait for this resource-id to appear",
    "反过来：等它消失": "Invert it: wait for it to disappear",
    "最多等多久（毫秒）": "How long to wait at most (milliseconds)",
    "每次检查间隔（毫秒）": "Interval between checks (milliseconds)",
}

TOOLS_TOKEN = {
    "获取访问令牌": "Get the access token",
    "获取本机 MCP 服务的访问令牌（token），以及端口和可用地址。调用这个工具本身不需要令牌。拿到的令牌用于调用 HTTP 接口：上传文件 POST /upload?path=目标路径&token=xxx、下载文件 GET /download?path=路径&token=xxx、以及 /api/status、/api/tools、/api/log 等。":
        "Get the access token of the MCP server on this phone, plus the port and available addresses. Calling this tool itself needs no token. The token is for the HTTP endpoints: upload POST /upload?path=<target>&token=xxx, download GET /download?path=<path>&token=xxx, and /api/status, /api/tools, /api/log, etc.",
}

TOOLS_UI_MISC = {
    # 界面上用的零散标签
    "无": "none",
}

GROUPS = [
    ("i18n 第二批：通知栏 / 前台服务 / Shizuku / 终端 / 更新检查 / 网络", APP_UI),
    ("i18n 第二批：日志与审批标签", CORE_LABELS),
    ("i18n 第二批：MCP instructions", SERVER_INSTRUCTIONS),
    ("i18n 第二批：内置工具 —— 工具包管理", TOOLS_PACKS),
    ("i18n 第二批：内置工具 —— 只读", TOOLS_READ),
    ("i18n 第二批：内置工具 —— 写入 / 删除", TOOLS_WRITE),
    ("i18n 第二批：内置工具 —— 命令与自定义工具", TOOLS_SHELL),
    ("i18n 第二批：内置工具 —— 记忆库", TOOLS_MEMORY),
    ("i18n 第二批：内置工具 —— UI 自动化", TOOLS_UI),
    ("i18n 第二批：内置工具 —— 令牌", TOOLS_TOKEN),
]

# 「无」这条词表里其实已经有了，加进 GROUPS 只是为了对账；合并时已存在的键会被跳过。
GROUPS.append(("i18n 第二批：零散标签", TOOLS_UI_MISC))

STALE_KEYS = [
    # 键少了前导空格，永远匹配不上（源码里是 " 转发；…"）
    "转发；想只放开某一个应用，可以在下面加路径规则（例：/data/data/包名）。",
]

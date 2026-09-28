#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
i18n 第五批英文词条：**44 个工具返回给 AI 的正文**（文件读写 / UI 自动化 / 记忆库 / 工具包）。

注意几处刻意保留空译文的拼接片段：
  * ''  → ''      （英文里不需要量词或标点，直接给空串）
"""

# ---------------------------------------------------------------- 只读工具（目录/文件/搜索/存储/服务器）
TOOLS_READ = {
    "不是目录：%s": "Not a directory: %s",
    "目录不存在，或者读不到：%s": "Directory does not exist, or cannot be read: %s",
    "目录：": "Directory: ",
    "（应用私有目录，经 %s 读取）": " (app-private directory, read via %s)",
    "共 %s 项（目录 %s，文件 %s）": "%s items (%s folders, %s files)",
    "，仅显示前 %s 项": ", showing only the first %s",
    "（空目录，或者应用没有权限读到内容）\n": "(empty directory, or the app has no permission to read it)\n",
    "... 条目过多，已截断（可用 maxEntries 调整）\n":
        "... too many entries, truncated (raise maxEntries to see more)\n",
    "共 %s 项": "%s items",
    "路径：": "Path: ",
    "名称：": "Name: ",
    "类型：": "Type: ",
    "大小：": "Size: ",
    " (%s 字节)\n": " (%s bytes)\n",
    "修改时间：": "Modified: ",
    "所在目录：": "Directory: ",
    "读取方式：经 %s 转发（应用自己没权限）\n": "Read via: forwarded through %s (the app lacks permission)\n",
    "可读：": "Readable: ",
    "是（通过 shell）": "yes (via shell)",
    "  可写：": "  Writable: ",
    "子项：%s 个（目录 %s，文件 %s）\n": "Children: %s (%s folders, %s files)\n",
    "扩展名：": "Extension: ",
    "（无）": "(none)",
    "图片类型：%s（可用 read_image 直接查看）\n": "Image type: %s (view it directly with read_image)\n",
    "可用空间：": "Free space: ",
    "文件不存在，或者读不到：%s": "File does not exist, or cannot be read: %s",
    "文件不存在，或者读不到：%s\n(私有目录要在 设置 → 权限 → 应用私有目录 里开放，并且需要 root 或 Shizuku)":
        "File does not exist, or cannot be read: %s\n(app-private directories must be opened in Settings → Permissions → App-private directories, and need root or Shizuku)",
    "这是目录，请用 list_dir：%s": "This is a directory, use list_dir: %s",
    "读不出来：应用自己没权限，而且没有可用的 root / Shizuku":
        "Cannot read: the app lacks permission and no root / Shizuku is available",
    "这是二进制文件（含空字节），无法按文本读取：%s\n如果是图片请用 read_image；其他二进制可用 file_info 查看大小。":
        "This is a binary file (contains null bytes) and cannot be read as text: %s\nUse read_image for pictures; for other binaries use file_info to see the size.",
    "文件：": "File: ",
    "总行数：": "Total lines: ",
    "（文件较大，本次只读取了前 %s）": " (large file, only the first %s was read)",
    "，显示第 %s ~ %s 行\n": ", showing lines %s ~ %s\n",
    "... 还有 %s 行（可加大 lineCount 或调整 startLine）\n":
        "... %s more lines (increase lineCount or adjust startLine)\n",
    "图片不存在，或者读不到：%s": "Image does not exist, or cannot be read: %s",
    "这是目录：%s": "This is a directory: %s",
    "不支持的图片格式：.%s（支持 png/jpg/jpeg/gif/webp/bmp）":
        "Unsupported image format: .%s (supports png/jpg/jpeg/gif/webp/bmp)",
    "图片过大：%s，超过上限": "Image too large: %s, over the limit",
    "图片：%s\n大小：%s（%s）": "Image: %s\nSize: %s (%s)",
    "搜索起点必须是目录：%s": "The search start point must be a directory: %s",
    "至少要提供 name 或 content 之一": "Provide at least one of name or content",
    "内容正则不合法：%s": "Invalid content regex: %s",
    "搜索目录：": "Search directory: ",
    "文件名条件：": "Name condition: ",
    "内容条件：": "Content condition: ",
    "扫描条目：%s，命中：%s": "Scanned: %s, matched: %s",
    "（已达上限，结果可能不完整）": " (hit the limit, results may be incomplete)",
    "没有找到匹配项": "No matches found",
    "目录不支持计算校验值：%s": "Hashing is not supported for directories: %s",
    "文件：%s\n算法：%s\n校验值：%s": "File: %s\nAlgorithm: %s\nHash: %s",
    "允许访问的根目录（%s 个）：\n": "Allowed roots (%s):\n",
    "（不存在）": " (missing)",
    "不限制目录：": "Unrestricted directories: ",
    "是": "Yes",
    "否": "No",
    "\n存储分区：\n": "\nStorage volumes:\n",
    "：总 %s，已用 %s，可用 %s\n": ": total %s, used %s, free %s\n",
    "MCP 手机文件服务器\n": "MCP phone file server\n",
    "版本：": "Version: ",
    "设备：": "Device: ",
    "端口：": "Port: ",
    "运行时间：": "Uptime: ",
    "累计请求：": "Total requests: ",
    "（允许 %s / 失败 %s / 审批 %s / 拒绝 %s）\n": " (allowed %s / failed %s / approvals %s / denied %s)\n",
    "\n根目录：\n": "\nRoots:\n",
    "只读模式：": "Read-only mode: ",
    "已开启（所有写/删会被拒绝）": "on (all writes/deletes are denied)",
    "关闭（删除即彻底删除）": "off (deleting removes files permanently)",
    "回收站：": "Trash: ",
    "开启（删除会先进回收站）": "on (deleting sends files to the trash first)",
    "\n权限开关：\n": "\nPermission switches:\n",
    "（每次操作会弹出审批窗口）": " (an approval prompt appears for every operation)",
    "（相关操作会被直接拒绝）": " (related operations are denied outright)",
    "\n路径规则：\n": "\nPath rules:\n",
    "全部权限": "All permissions",
    "（未填）": "(empty)",
    "\n提示：涉及写入/删除的操作会实时弹窗询问手机主人，被拒绝时请勿反复重试。":
        "\nNote: write/delete operations pop up a prompt on the phone in real time; if denied, do not retry repeatedly.",
    "设备信息\n": "Device info\n",
    "当前运行环境拿不到设备信息（桌面端测试模式）":
        "Device info is unavailable in this environment (desktop test mode)",
}

# ---------------------------------------------------------------- 写入与回收站
TOOLS_WRITE = {
    "目标是一个目录：%s": "The destination is a directory: %s",
    "base64 内容不合法": "Invalid base64 content",
    "文件已存在：%s": "File already exists: %s",
    "追加": "append",
    "覆盖": "overwrite",
    "新建": "create",
    "上级目录不存在：%s（可设置 createDirs=true）":
        "Parent directory does not exist: %s (set createDirs=true to create it)",
    "%s成功：%s\n本次写入 %s，文件当前 %s": "%s: %s\nwrote %s this time, file is now %s",
    "缺少 oldText": "Missing oldText",
    "读取失败：应用自己没有权限，而且没有可用的 root / Shizuku":
        "Read failed: the app lacks permission and no root / Shizuku is available",
    "正则不合法：%s": "Invalid regex: %s",
    "没有找到要替换的内容（oldText 在文件中不存在）":
        "Nothing to replace (oldText does not occur in the file)",
    "修改成功：%s\n匹配 %s 处，已替换 %s 处\n文件大小：%s":
        "Edit succeeded: %s\n%s matches found, %s replaced\nFile size: %s",
    "写入失败：应用自己没有权限，而且没有可用的 root / Shizuku":
        "Write failed: the app lacks permission and no root / Shizuku is available",
    "写入失败：应用自己没有权限，而且没有可用的 root / Shizuku\n目标：%s":
        "Write failed: the app lacks permission and no root / Shizuku is available\nTarget: %s",
    "目录已存在：%s": "Directory already exists: %s",
    "同名文件已存在：%s": "A file with the same name already exists: %s",
    "创建目录失败（可能是权限不足）：%s":
        "Failed to create directory (possibly insufficient permission): %s",
    "已创建目录：%s": "Directory created: %s",
    "缺少 source": "Missing source",
    "源不存在或读不到：%s": "Source does not exist or cannot be read: %s",
    "缺少 destination": "Missing destination",
    "源和目标相同：%s": "Source and destination are the same: %s",
    "不能把目录复制到它自己的子目录里：%s": "Cannot copy a directory into its own subdirectory: %s",
    "不能把目录移动到它自己的子目录里：%s": "Cannot move a directory into its own subdirectory: %s",
    "目标已存在（可设置 overwrite=true 覆盖）：%s":
        "Destination already exists (set overwrite=true to replace it): %s",
    "复制失败：%s": "Copy failed: %s",
    "复制完成\n源：%s\n目标：%s\n大小：%s": "Copy complete\nFrom: %s\nTo: %s\nSize: %s",
    "（经 %s 转发）": " (forwarded via %s)",
    "移动失败：%s → %s（%s）": "Move failed: %s → %s (%s)",
    "移动完成\n源：%s\n目标：%s": "Move complete\nFrom: %s\nTo: %s",
    "\n方式：经 %s 转发": "\nMethod: forwarded via %s",
    "路径不存在，或者读不到：%s": "Path does not exist, or cannot be read: %s",
    "目录不是空的（%s 项），确认要删除请设置 recursive=true：%s":
        "Directory is not empty (%s items); set recursive=true to delete it: %s",
    "删除失败：%s（应用没权限，且 root / Shizuku 不可用）":
        "Delete failed: %s (the app lacks permission and root / Shizuku is unavailable)",
    "已彻底删除：%s\n释放空间：%s（不可恢复）": "Permanently deleted: %s\nFreed: %s (unrecoverable)",
    "已移入回收站：%s\n回收站 ID：%s（可用 list_trash 查看、restore_trash 还原）":
        "Moved to trash: %s\nTrash ID: %s (list with list_trash, restore with restore_trash)",
    "共 %s 项，占用 %s\n----\n": "%s items, %s\n----\n",
    "（空）": "(empty)",
    "目录 ": "folder ",
    "文件 ": "file ",
    "      原位置：": "      Original: ",
    "      删除时间：": "      Deleted: ",
    "id 和 name 只能给一个": "Give either id or name, not both",
    "回收站里没有 ID=%s 的条目": "No trash entry with ID=%s",
    "回收站里没有文件名含 %s 的条目": "No trash entry whose file name contains %s",
    "请提供 id 或 name（回收站有 %s 项，可用 list_trash 查看）":
        "Provide id or name (the trash has %s items; view them with list_trash)",
    "已还原：%s → %s": "Restored: %s → %s",
    "请设置 confirm=true 确认清空回收站": "Set confirm=true to confirm emptying the trash",
    "回收站已经是空的": "The trash is already empty",
    "回收站已清空：删除 %s 项，释放 %s": "Trash emptied: %s items deleted, %s freed",
    "缺少 message": "Missing message",
}

# ---------------------------------------------------------------- UI 自动化
TOOLS_UI = {
    " 范围[": " span[",
    " 已勾选": " checked",
    " 已禁用": " disabled",
    "UI 自动化需要 Root 或 Shizuku 身份：截屏、读取界面结构、模拟点击都要系统权限，\n":
        "UI automation needs Root or Shizuku: screenshots, UI-tree reads and simulated taps all require system privileges,\n",
    "应用自身 UID 做不到。请先打开 Shizuku 授权（App 的「终端」页），或者用已 root 的设备。":
        "the app's own UID cannot do it. Grant Shizuku permission first (app's Terminal page) or use a rooted device.",
    "UI 动作成功": "UI action succeeded",
    "UI 动作失败（退出码 %s）": "UI action failed (exit code %s)",
    "UI 后端回退：%s 跑不动 uiautomator，改用 %s":
        "UI backend fallback: %s could not run uiautomator, switched to %s",
    "没有给出定位条件": "No locator was given",
    "没找到「%s」。当前屏幕上能看到的文字：\n": 'Could not find "%s". Text currently visible on screen:\n',
    "  （没有可读文字，可能是自绘界面或还没加载完）":
        "  (no readable text — could be a custom-drawn UI or not loaded yet)",
    "\n\n可以先用 ui_dump 看看结构，或者直接用 ui_tap 传 x / y 坐标。":
        "\n\nTry ui_dump to inspect the structure, or pass x / y coordinates straight to ui_tap.",
    "「%s」匹配到 %s 个元素，请用 index 指定要哪一个：\n%s":
        '"%s" matched %s elements — use index to pick one:\n%s',
    "截屏失败（退出码 %s）：\n": "Screenshot failed (exit code %s):\n",
    "截屏命令执行了，但读不到图片：%s\n（应用自己没有权限读它，而且没有可用的 root / Shizuku）":
        "The screenshot command ran but the image cannot be read: %s\n(the app has no permission to read it, and no root / Shizuku is available)",
    "截出来的图片是空的：%s": "The screenshot came out empty: %s",
    "截屏：%s\n": "Screenshot: %s\n",
    "图片尺寸：%sx%s　屏幕坐标空间：%sx%s\n": "Image size: %sx%s  Screen coordinate space: %sx%s\n",
    "注意：图片被缩放过了，点坐标要按屏幕空间算（乘 %s），ui_dump 给的坐标已经是屏幕空间。\n":
        "Note: the image was scaled, so tap coordinates must use the screen space (multiply by %s); ui_dump already returns screen-space coordinates.\n",
    "前端：": "Foreground: ",
    "原始 XML（%s 字符，已截断到 20000）\n----\n":
        "Raw XML (%s characters, truncated to 20000)\n----\n",
    "屏幕 %sx%s（rotation=%s）": "Screen %sx%s (rotation=%s)",
    " · 前台 %s · 结构里共 %s 个节点，下面是 %s 个\n":
        " · foreground %s · %s nodes in the tree, %s listed below\n",
    "（坐标是屏幕空间，可直接用于 ui_tap）\n":
        "(coordinates are screen space, ready for ui_tap)\n",
    "（没有匹配的可交互元素）\n": "(no matching interactive elements)\n",
    "可能是：界面还在加载 / 是自绘界面（游戏、视频）。可以用 ui_screenshot 看一眼，":
        "It may be that the UI is still loading, or it is custom-drawn (games, video). Try ui_screenshot, ",
    "或者用 onlyInteractive=false 看看全部节点。": "or use onlyInteractive=false to see all nodes.",
    "格式：[序号] 类型 \"文字\" id (中心x,中心y) 范围[左,上][右,下] 类名\n":
        'Format: [index] type "text" id (centreX,centreY) span[left,top][right,bottom] class\n',
    "用法：ui_tap 传 x/y 点坐标，或者传 text / desc / id 让我自动找；":
        "Usage: ui_tap takes x/y coordinates, or text / desc / id and finds it for you; ",
    "滚动用 ui_swipe 的 direction。\n----\n": "scroll with ui_swipe's direction.\n----\n",
    "定位到「%s」→ 点 (%s, %s)": 'Located "%s" → tap (%s, %s)',
    "长按 ": "Long press ",
    "点击 ": "Tap ",
    "点击命令失败（退出码 %s）：\n": "Tap command failed (exit code %s):\n",
    "%s\n（已通过 %s 执行：%s）": "%s\n(via %s: %s)",
    "从 (%s,%s) 滑到 (%s,%s)": "Swipe from (%s,%s) to (%s,%s)",
    "内容上滚 %s px": "Scroll content up %s px",
    "内容下滚 %s px": "Scroll content down %s px",
    "向左滑 %s px": "Swipe left %s px",
    "向右滑 %s px": "Swipe right %s px",
    "请给 direction（up / down / left / right），或者给全 x1 y1 x2 y2 四个坐标":
        "Give direction (up / down / left / right), or all four coordinates x1 y1 x2 y2",
    "滑动命令失败（退出码 %s）：\n": "Swipe command failed (exit code %s):\n",
    "滑动：%s": "Swipe: %s",
    "%s（重复 %s 次，每个 %sms）": "%s (×%s, %sms each)",
    "缺少 text": "Missing text",
    "text 不能为空": "text must not be empty",
    "这段文字含非 ASCII 字符（中文等），安卓的 `input text` 输不进去，需要走剪贴板粘贴，":
        "This text contains non-ASCII characters (Chinese etc.); Android's `input text` cannot type it and the clipboard is needed, ",
    "但当前拿不到剪贴板能力。\n可以用 ui_input 只输英文数字，":
        "but the clipboard is not reachable right now.\nYou can use ui_input with ASCII only, ",
    "或者用 run_shell 配合别的输入法方案。": "or use run_shell with another input method.",
    "剪贴板 + 粘贴（KEYCODE_PASTE）": "clipboard + paste (KEYCODE_PASTE)",
    "直接键入（input text）": "typed directly (input text)",
    "输入文字：%s%s（%s）": "Type text: %s%s (%s)",
    "输入失败（退出码 %s）：\n": "Typing failed (exit code %s):\n",
    "已输入 %s 个字符（%s）\n": "Typed %s characters (%s)\n",
    "提示：粘贴前输入框必须有焦点，且文字确实进了剪贴板；如果没生效，先 ui_tap 点一下输入框再试。\n":
        "Tip: before pasting, the field must be focused and the text must actually be in the clipboard; if it does not work, ui_tap the field first.\n",
    "已按回车提交。\n": "Pressed enter to submit.\n",
    "缺少 key": "Missing key",
    "不认识的按键：%s\n可用：": "Unknown key: %s\nAvailable: ",
    "，或者直接给数字键值（如 4 = 返回）": ", or give a numeric key code directly (e.g. 4 = back)",
    "按键：%s（%s）%s": "Key: %s (%s)%s",
    " %s 次": " ×%s",
    "按键失败（退出码 %s）：\n": "Key press failed (exit code %s):\n",
    "已按 %s（keycode %s）%s\n当前前台：%s": "Pressed %s (keycode %s)%s\nForeground: %s",
    "回桌面失败：\n": "Failed to go home:\n",
    "已回到桌面": "Back to the home screen",
    "当前前台：%s\n": "Foreground: %s\n",
    "（拿不到 activity 信息）": "(no activity info available)",
    "第三方应用 %s 个：\n": "%s third-party apps:\n",
    "（没有查到）": "(nothing found)",
    "\n… 还有 %s 个": "\n… %s more",
    "action=app 需要给 package（包名）。不知道包名可以先用 action=list 查。":
        "action=app needs package (the app package name). Use action=list to find it.",
    "包名格式不对：%s（应该是 com.xxx.yyy 这种）":
        "Bad package name: %s (should look like com.xxx.yyy)",
    "启动应用 %s": "Launch app %s",
    "启动 %s 失败：\n": "Failed to launch %s:\n",
    "\n\n可能这个包名不存在，或者它没有可启动的界面（是纯后台服务）。":
        "\n\nThe package may not exist, or it has no launchable UI (a background-only service).",
    "已启动 %s\n当前前台：%s\n\n提示：启动后稍等一下再 ui_dump，界面可能还在加载。":
        "Launched %s\nForeground: %s\n\nTip: wait a moment before ui_dump — the UI may still be loading.",
    "不认识的 action：%s（可用 app / home / current / list）":
        "Unknown action: %s (available: app / home / current / list)",
    "至少要给 text / desc / id 之一": "Provide at least one of text / desc / id",
    "消失": "disappear",
    "出现": "appear",
    "「%s」已%s（等了 %sms，查了 %s 次）\n":
        '"%s" has %s (waited %sms, checked %s times)\n',
    "位置：中心(%s,%s) 范围[%s,%s][%s,%s]\n":
        "Position: centre (%s,%s) span [%s,%s][%s,%s]\n",
    "等了 %sms，「%s」还是%s。\n": 'Waited %sms and "%s" %s.\n',
    "在": "is still there",
    "没出现": "never appeared",
    "可以用 ui_dump 看看当前屏幕上到底有什么（或者 ui_screenshot 看画面）。":
        "Use ui_dump to see what is actually on screen (or ui_screenshot for the picture).",
}

# ---------------------------------------------------------------- 记忆库
TOOLS_MEMORY = {
    "entities 不能为空（每项至少要有 name）": "entities must not be empty (each item needs at least a name)",
    "已创建 %s 个实体，已存在 %s 个": "Created %s entities, %s already existed",
    "，附带 %s 条观察": ", plus %s observations",
    "\n当前记忆库共 %s 个实体、%s 条关系。": "\nThe memory graph now has %s entities and %s relations.",
    "relations 不能为空（每项要有 from 和 to）":
        "relations must not be empty (each item needs from and to)",
    "已建立 %s 条关系，已存在 %s 条。\n": "Created %s relations, %s already existed.\n",
    "\n注意：这些实体还不存在，建议补建：": "\nNote: these entities do not exist yet — consider creating them: ",
    "observations 不能为空（每项要有 entityName 和 contents）":
        "observations must not be empty (each item needs entityName and contents)",
    "  ! 跳过「%s」：实体不存在\n": '  ! skipped "%s": entity does not exist\n',
    "  · %s：新增 %s 条": "  · %s: %s new",
    "（跳过 %s 条重复）": " (skipped %s duplicates)",
    "已追加 %s 条观察。\n%s": "Added %s observations.\n%s",
    "记忆库还是空的（或该过滤条件下没有内容）。用 create_entities 开始记录吧。":
        "The memory graph is still empty (or nothing matches these filters). Start with create_entities.",
    "记忆库": "Memory",
    "query 不能为空": "query must not be empty",
    "没有找到和「%s」相关的记忆。": 'No memories related to "%s" were found.',
    "搜索「%s」": 'Search "%s"',
    "names 不能为空": "names must not be empty",
    "这些实体都不存在：": "None of these entities exist: ",
    "记忆": "Memory",
    "\n\n以下实体不存在：": "\n\nThese entities do not exist: ",
    "已删除 %s 个实体（连带的关系也一起删了）。\n剩余：%s 个实体、%s 条关系。":
        "Deleted %s entities (their relations went too).\nRemaining: %s entities, %s relations.",
    "relations 不能为空": "relations must not be empty",
    "已删除 %s 条关系。剩余 %s 条。": "Deleted %s relations. %s remaining.",
    "deletions 不能为空": "deletions must not be empty",
    "  · %s：删掉 %s 条\n": "  · %s: %s deleted\n",
    "共删除 %s 条观察。\n%s": "Deleted %s observations in total.\n%s",
    "实体 %s 个 · 关系 %s 条\n": "%s entities · %s relations\n",
    "\n（还有未展开的关联实体：": "\n(related entities not expanded: ",
    "）\n": ")\n",
}

# ---------------------------------------------------------------- 工具包
TOOLS_PACKS = {
    "pack 不能为空": "pack must not be empty",
    "没有叫「%s」的包。用 list_packs 看看有哪些。": 'No pack named "%s". Use list_packs to see what exists.',
    "「%s」是空的，没有工具可以激活。": '"%s" is empty, there are no tools to activate.',
    "已激活": "Activated ",
    "本来就已激活": "Already active ",
    "「%s」（%s）。\n": '"%s" (%s).\n',
    "新增可见的工具 %s 个：\n": "%s newly visible tools:\n",
    "\n注意：大多数 MCP 客户端**只在连接时拉一次工具列表**，之后不会再拉，":
        "\nNote: most MCP clients **fetch the tool list only once per connection** and never again, ",
    "服务端也无法通知它刷新。所以这些工具通常**要等客户端重新连接（或重启 App）后才能调用**。\n":
        "nor can the server tell it to refresh. So these tools usually **become callable only after the client reconnects (or the app restarts)**.\n",
    "如果你现在就要用，可以直接试着按名字调用 —— 服务端允许，但客户端可能拦下来；":
        "If you need it right now, just try calling it by name — the server allows it, but the client may block it; ",
    "被拦了就告诉用户「请重新连接 MCP 服务」，不要反复重试。\n":
        'if it is blocked, tell the user "please reconnect the MCP service" and do not retry repeatedly.\n',
    "当前会话：": "Current session: ",
    "基础包不能停用（工具包管理本身就在它里面）":
        "The base pack cannot be deactivated (pack management itself lives in it)",
    "已停用「%s」。它的 %s 个工具不再出现在工具列表里。":
        'Deactivated "%s". Its %s tools no longer appear in the tool list.',
    "「%s」本来就没激活。": '"%s" was not active anyway.',
    "已重置回默认": "Reset to default",
    "title 不能为空": "title must not be empty",
    "tools 不能为空，至少放一个工具名": "tools must not be empty — put at least one tool name",
    "update 需要给 id": "update needs an id",
    "已新建": "Created ",
    "已更新": "Updated ",
    "工具包「%s」\n": 'Tool pack "%s"\n',
    "  工具 %s 个：": "  %s tools: ",
    "\n注意：这些名字当前不在工具表里（可能拼错了，或者被禁用了）：":
        "\nNote: these names are not in the tool list right now (possibly misspelled, or disabled): ",
    "\n用 activate_pack pack=\"%s\" 就能激活它。":
        '\nUse activate_pack pack="%s" to activate it.',
    "delete 需要给 id": "delete needs an id",
    "「%s」是内置包，不能删": '"%s" is a built-in pack and cannot be deleted',
    "已删除工具包「%s」。": 'Deleted tool pack "%s".',
    "没有找到自定义包「%s」。": 'No custom pack named "%s" was found.',
    "action 只能是 create / update / delete": "action must be create / update / delete",
    "工具包 —— 当前会话「%s」\n": 'Tool packs — current session "%s"\n',
    "现在能看到 %s 个工具\n": "%s tools are visible right now\n",
    "\n● 已激活（%s）\n": "\n● Active (%s)\n",
    "\n○ 未激活（%s）—— 需要时用 activate_pack 打开\n":
        "\n○ Inactive (%s) — open with activate_pack when needed\n",
    "\n提示：包决定「工具列表里能看见什么」。停用只是从列表里收起来；":
        "\nNote: packs decide what shows up in the tool list. Deactivating merely hides them; ",
    "要在客户端生效，需要让它重新连接（或重启 App）。":
        "for it to take effect in the client, the client must reconnect (or the app restart).",
    "  · ": "  · ",
    "  [常驻]": "  [always on]",
    "（没有说明）": "(no description)",
    "      工具：": "      Tools: ",
}

# ---------------------------------------------------------------- 其它（令牌 / 自定义工具 / 服务端）
MISC = {
    "访问令牌\n": "Access token\n",
    "  未启用令牌校验：接口不需要 token 也能访问（设置 → 安全 里可以打开）。\n":
        "  Token check is off: the endpoints can be used without a token (enable it in Settings → Security).\n",
    "  长度 %s 位 · 已在「设置 → 安全」里开启校验\n":
        "  %s characters · verification is on (Settings → Security)\n",
    "\n服务地址\n": "\nService addresses\n",
    "  端口：": "  Port: ",
    "局域网：%s": "LAN: %s",
    "（手机连同一个 Wi-Fi 的设备可用）\n": " (usable by devices on the same Wi-Fi)\n",
    "  本机：": "  This phone: ",
    "（手机本机 / 工作区容器内都能直连）\n":
        " (reachable from this phone / the workspace container)\n",
    "  其它：": "  Others: ",
    "\n用法示例\n": "\nUsage examples\n",
    "  上传文件到手机：\n": "  Upload a file to the phone:\n",
    "    curl -X POST --data-binary @本地文件 \\\n":
        "    curl -X POST --data-binary @local-file \\\n",
    "  从手机取文件：\n": "  Fetch a file from the phone:\n",
    "  也可以把令牌放进请求头 X-MCP-Token，或用 Authorization: Bearer。\n":
        "  You can also put the token in the X-MCP-Token header, or use Authorization: Bearer.\n",
    "\n提示\n": "\nNotes\n",
    "  · 路径里的中文和空格要 URL 编码（例如 %E6%96%87%E4%BB%B6）。\n":
        "  · Non-ASCII characters and spaces in paths must be URL-encoded (e.g. %E6%96%87%E4%BB%B6).\n",
    "  · 令牌泄露等于把文件存取权限交出去；用户重置令牌后旧值立刻失效。\n":
        "  · Leaking the token hands over file access; once the user resets it the old value stops working immediately.\n",
    "  · 文件相关的操作优先用本工具集里的 list_dir / read_file / write_file，不必走 HTTP。":
        "  · For file work prefer this toolset's list_dir / read_file / write_file — no need to go over HTTP.",
    "缺少必填参数：": "Missing required parameters: ",
    "模板里用到了参数 {{%s}}，但它没有定义":
        "The template uses the parameter {{%s}} but it is not defined",
    "工具名要小写字母开头，只能包含小写字母/数字/下划线，长度 2-41":
        "The tool name must start with a lowercase letter and contain only lowercase letters/digits/underscores, length 2-41",
    "已存在同名工具：%s": "A tool with that name already exists: %s",
    "命令模板不能为空": "Command template must not be empty",
    "命令里用到了未定义的参数：": "The command uses undefined parameters: ",
    "参数名不合法：%s": "Invalid parameter name: %s",
    "MCP 文件盒 · 自定义工具导出": "MCP Box · custom tools export",
    "不是合法的 JSON：%s": "Not valid JSON: %s",
    "JSON 里 tools 字段不是数组": 'The "tools" field is not an array',
    "JSON 结构不对，需要数组或 {\"tools\":[...]}":
        'Bad JSON structure: expected an array or {"tools":[...]}',
    "没有解析出任何工具": "No tools could be parsed",
    "导入完成：新增 %s，更新 %s": "Import complete: %s added, %s updated",
    "，跳过 %s": ", skipped %s",
    "「%s」是内置包的名字，换一个吧": '"%s" is a built-in pack name — pick another one',
    "已经有一个叫「%s」的包了": 'A pack named "%s" already exists',
    "内置包不能改，但可以用 manage_pack 新建一个自己的包":
        "Built-in packs cannot be changed, but you can create your own with manage_pack",
    "找不到包：%s": "Pack not found: %s",
    "「%s」是内置包，不能删": '"%s" is a built-in pack and cannot be deleted',
    "包名不能为空": "Pack name must not be empty",
    "包名太长（最多 60 字）": "Pack name too long (max 60 characters)",
    "包说明太长（最多 1200 字）": "Pack description too long (max 1200 characters)",
    "包 id 太长（最多 48 字）": "Pack id too long (max 48 characters)",
    "包 id 只能用字母、数字、点、横线和下划线":
        "Pack id may only contain letters, digits, dots, dashes and underscores",
    "服务已启动，端口 %s": "Service started on port %s",
    "网页控制台登录成功": "Web console: signed in",
    "网页控制台密码错误": "Web console: wrong password",
    "客户端断开：%s": "Client disconnected: %s",
    "客户端连接（SSE）：%s @%s": "Client connected (SSE): %s @%s",
    "SSE 通道已断开，响应未能送达": "SSE channel closed, response could not be delivered",
    "客户端已连接：%s @%s（协议 %s）": "Client connected: %s @%s (protocol %s)",
    "列出工具（会话 %s）": "List tools (session %s)",
    "被拒绝": "Denied",
    "操作被拒绝：%s\n（用户可在 App 的权限页调整该权限）":
        "Operation denied: %s\n(the user can adjust this permission on the app's Permissions page)",
    "失败": "Failed",
    "操作失败": "Operation failed",
    "请求头过长": "Request headers too long",
    "请求体不完整": "Incomplete request body",
    "请求体过大": "Request body too large",
    "\n...（已截断）": "\n...(truncated)",
}

GROUPS = [
    ("i18n 第五批：工具输出 —— 只读（目录 / 文件 / 搜索 / 存储 / 服务器）", TOOLS_READ),
    ("i18n 第五批：工具输出 —— 写入与回收站", TOOLS_WRITE),
    ("i18n 第五批：工具输出 —— UI 自动化", TOOLS_UI),
    ("i18n 第五批：工具输出 —— 记忆库", TOOLS_MEMORY),
    ("i18n 第五批：工具输出 —— 工具包", TOOLS_PACKS),
    ("i18n 第五批：工具输出 —— 令牌 / 自定义工具 / 服务端", MISC),
]

STALE_KEYS = []

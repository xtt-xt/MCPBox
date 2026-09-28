#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""i18n 第六批英文词条：收尾（命令日志、notify_user 回执、网页登录页）。"""

TAIL = {
    "没有可用的 Shell 后端。\n": "No shell backend available.\n",
    "应用沙箱后端应该总是可用；如果用 Shizuku，请先在 App 的「终端」页申请授权。":
        "The app-sandbox backend should always be available; if you use Shizuku, grant it permission first on the app's Terminal page.",
    "工作目录不合法": "Invalid working directory",
    "命令执行成功": "Command succeeded",
    "命令失败（退出码 %s）": "Command failed (exit code %s)",
    "命令不能为空": "Command must not be empty",
    "params 格式不对，需要 [{name, type, description, required}]：%s":
        "Bad params format — expected [{name, type, description, required}]: %s",
    "已发送通知：%s - %s": "Notification sent: %s - %s",
    "通知发送失败（可能缺少通知权限）": "Failed to send the notification (probably missing notification permission)",
    "密码不对，再试一次": "Wrong password, try again",
    "提示：还没单独设置网页密码，这里填 App 里显示的访问令牌（token）就能进。":
        "Tip: no web password has been set, so enter the access token shown in the app to get in.",
    "密码可以在 App 的「设置 → 网页控制台 → 访问密码」里改。":
        "You can change the password in the app under Settings → Web console → Access password.",
}

GROUPS = [
    ("i18n 第六批：收尾（命令日志 / 通知回执 / 网页登录页）", TAIL),
]

STALE_KEYS = []

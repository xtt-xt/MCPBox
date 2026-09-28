#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 7 批 i18n：收尾 —— ToolsShell 剩余日志/报错、notify_user 回执、网页登录页。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ ToolsShell
    (f"{CORE}/ToolsShell.kt",
     '            ?: ctx.fail(\n'
     '                "没有可用的 Shell 后端。\\n" +\n'
     '                    "应用沙箱后端应该总是可用；如果用 Shizuku，请先在 App 的「终端」页申请授权。"\n'
     '            )',
     '            ?: ctx.fail(\n'
     '                L("没有可用的 Shell 后端。\\n") +\n'
     '                    L("应用沙箱后端应该总是可用；如果用 Shizuku，请先在 App 的「终端」页申请授权。")\n'
     '            )'),
    (f"{CORE}/ToolsShell.kt",
     '            runCatching { ctx.sandbox.resolve(it).path }.getOrElse { ctx.fail(it.message ?: "工作目录不合法") }',
     '            runCatching { ctx.sandbox.resolve(it).path }\n'
     '                .getOrElse { ctx.fail(it.message ?: L("工作目录不合法")) }'),
    (f"{CORE}/ToolsShell.kt",
     '            message = (if (result.ok) "命令执行成功" else "命令失败（退出码 ${result.exitCode}）") +\n'
     '                "：" + command.take(120),',
     '            message = (\n'
     '                if (result.ok) L("命令执行成功")\n'
     '                else L("命令失败（退出码 %s）").format(result.exitCode)\n'
     '            ) + "：" + command.take(120),'),
    (f"{CORE}/ToolsShell.kt",
     '        if (command.isBlank()) ctx.fail("命令不能为空")',
     '        if (command.isBlank()) ctx.fail(L("命令不能为空"))'),
    (f"{CORE}/ToolsShell.kt",
     '        }.getOrElse { ctx.fail("params 格式不对，需要 [{name, type, description, required}]：${it.message}") }',
     '        }.getOrElse {\n'
     '            ctx.fail(\n'
     '                L("params 格式不对，需要 [{name, type, description, required}]：%s")\n'
     '                    .format(it.message)\n'
     '            )\n'
     '        }'),
    (f"{CORE}/ToolsShell.kt",
     '        if (name.isBlank()) ctx.fail("缺少 name")',
     '        if (name.isBlank()) ctx.fail(L("缺少 name"))'),

    # ================================================================ ToolsWrite：notify_user
    (f"{CORE}/ToolsWrite.kt",
     '        val message = ctx.args.str("message") ?: ctx.fail("缺少 message")\n'
     '        val title = ctx.args.str("title") ?: "MCP 文件盒"',
     '        val message = ctx.args.str("message") ?: ctx.fail(L("缺少 message"))\n'
     '        val title = ctx.args.str("title") ?: L("MCP 文件盒")'),
    (f"{CORE}/ToolsWrite.kt",
     '        ToolResult(if (ok) "已发送通知：$title - $message" else "通知发送失败（可能缺少通知权限）")',
     '        ToolResult(\n'
     '            if (ok) L("已发送通知：%s - %s").format(title, message)\n'
     '            else L("通知发送失败（可能缺少通知权限）")\n'
     '        )'),
    (f"{CORE}/ToolsWrite.kt",
     '                "title" to Schema.str("通知标题", "MCP 文件盒"),',
     '                "title" to Schema.str("通知标题", L("MCP 文件盒")),'),

    # ================================================================ WebConsole：浏览器登录页
    (f"{CORE}/WebConsole.kt",
     'val errBox = if (error) """<div class="err">密码不对，再试一次</div>""" else ""\n'
     '        val hint = if (passwordIsToken) {\n'
     '            "提示：还没单独设置网页密码，这里填 App 里显示的访问令牌（token）就能进。"\n'
     '        } else {\n'
     '            "密码可以在 App 的「设置 → 网页控制台 → 访问密码」里改。"\n'
     '        }',
     'val errBox = if (error) """<div class="err">""" + L("密码不对，再试一次") + "</div>" else ""\n'
     '        val hint = if (passwordIsToken) {\n'
     '            L("提示：还没单独设置网页密码，这里填 App 里显示的访问令牌（token）就能进。")\n'
     '        } else {\n'
     '            L("密码可以在 App 的「设置 → 网页控制台 → 访问密码」里改。")\n'
     '        }'),
    (f"{CORE}/WebConsole.kt",
     '<title>MCP 文件盒 · 登录</title>',
     '<title>${L("MCP 文件盒 · 登录")}</title>'),
    (f"{CORE}/WebConsole.kt",
     '  <h1>MCP 文件盒</h1>\n'
     '  <p class="sub">网页控制台开了密码保护，输入密码才能进。</p>',
     '  <h1>${L("MCP 文件盒")}</h1>\n'
     '  <p class="sub">${L("网页控制台开了密码保护，输入密码才能进。")}</p>'),
    (f"{CORE}/WebConsole.kt",
     '<input type="password" name="password" placeholder="访问密码" autofocus autocomplete="current-password">\n'
     '  <button type="submit">进入控制台</button>',
     '<input type="password" name="password" placeholder="${L("访问密码")}" autofocus autocomplete="current-password">\n'
     '  <button type="submit">${L("进入控制台")}</button>'),
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

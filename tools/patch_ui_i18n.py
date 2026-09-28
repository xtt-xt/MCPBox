#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 6 批 i18n（收尾）：ToolsUi.kt（UI 自动化工具的返回正文）。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsUi.kt"

PATCHES = [
    # ---------------------------------------------------------------- 节点行 / 勾选状态
    ('            if (checked) append(" 已勾选")\n'
     '            if (!enabled) append(" 已禁用")',
     '            if (checked) append(L(" 已勾选"))\n'
     '            if (!enabled) append(L(" 已禁用"))'),
    ('            append(" 范围[").append(left).append(\',\').append(top).append("][")',
     '            append(L(" 范围[")).append(left).append(\',\').append(top).append("][")'),

    # ---------------------------------------------------------------- 后端选择
    ('            ctx.fail(\n'
     '                "UI 自动化需要 Root 或 Shizuku 身份：截屏、读取界面结构、模拟点击都要系统权限，\\n" +\n'
     '                    "应用自身 UID 做不到。请先打开 Shizuku 授权（App 的「终端」页），或者用已 root 的设备。"\n'
     '            )',
     '            ctx.fail(\n'
     '                L("UI 自动化需要 Root 或 Shizuku 身份：截屏、读取界面结构、模拟点击都要系统权限，\\n") +\n'
     '                    L("应用自身 UID 做不到。请先打开 Shizuku 授权（App 的「终端」页），或者用已 root 的设备。")\n'
     '            )'),

    # ---------------------------------------------------------------- exec 的日志
    ('            message = (if (result.ok) "UI 动作成功" else "UI 动作失败（退出码 ${result.exitCode}）") +\n'
     '                "：" + summary,',
     '            message = (\n'
     '                if (result.ok) L("UI 动作成功")\n'
     '                else L("UI 动作失败（退出码 %s）").format(result.exitCode)\n'
     '            ) + "：" + summary,'),
    ('                        ctx.log.add(\n'
     '                            LogKind.SYSTEM, tool = ctx.tool, client = ctx.client,\n'
     '                            message = "UI 后端回退：${launcher.label} 跑不动 uiautomator，改用 ${l.label}"\n'
     '                        )',
     '                        ctx.log.add(\n'
     '                            LogKind.SYSTEM, tool = ctx.tool, client = ctx.client,\n'
     '                            message = L("UI 后端回退：%s 跑不动 uiautomator，改用 %s")\n'
     '                                .format(L(launcher.label), L(l.label))\n'
     '                        )'),

    # ---------------------------------------------------------------- 元素定位失败
    ('        val key = text ?: desc ?: id ?: ctx.fail("没有给出定位条件")',
     '        val key = text ?: desc ?: id ?: ctx.fail(L("没有给出定位条件"))'),
    ('            ctx.fail(\n'
     '                "没找到「$target」。当前屏幕上能看到的文字：\\n" +\n'
     '                    (near.ifBlank { "  （没有可读文字，可能是自绘界面或还没加载完）" }) +\n'
     '                    "\\n\\n可以先用 ui_dump 看看结构，或者直接用 ui_tap 传 x / y 坐标。"\n'
     '            )',
     '            ctx.fail(\n'
     '                L("没找到「%s」。当前屏幕上能看到的文字：\\n").format(target) +\n'
     '                    near.ifBlank { L("  （没有可读文字，可能是自绘界面或还没加载完）") } +\n'
     '                    L("\\n\\n可以先用 ui_dump 看看结构，或者直接用 ui_tap 传 x / y 坐标。")\n'
     '            )'),
    ('            ctx.fail(\n'
     '                "「$target」匹配到 ${hits.size} 个元素，请用 index 指定要哪一个：\\n$lines"\n'
     '            )',
     '            ctx.fail(\n'
     '                L("「%s」匹配到 %s 个元素，请用 index 指定要哪一个：\\n%s")\n'
     '                    .format(target, hits.size, lines)\n'
     '            )'),

    # ---------------------------------------------------------------- ui_screenshot
    ('            ctx.fail("截屏失败（退出码 ${res.exitCode}）：\\n" + (res.stderr + res.stdout).trim().take(400))',
     '            ctx.fail(\n'
     '                L("截屏失败（退出码 %s）：\\n").format(res.exitCode) +\n'
     '                    (res.stderr + res.stdout).trim().take(400)\n'
     '            )'),
    ('            ?: ctx.fail("截屏命令执行了，但读不到图片：${target.path}\\n（应用自己没有权限读它，而且没有可用的 root / Shizuku）")\n'
     '        if (bytes.isEmpty()) ctx.fail("截出来的图片是空的：${target.path}")',
     '            ?: ctx.fail(\n'
     '                L("截屏命令执行了，但读不到图片：%s\\n（应用自己没有权限读它，而且没有可用的 root / Shizuku）")\n'
     '                    .format(target.path)\n'
     '            )\n'
     '        if (bytes.isEmpty()) ctx.fail(L("截出来的图片是空的：%s").format(target.path))'),
    ('            append("截屏：${target.path}\\n")\n'
     '            append("图片尺寸：${pw}x${ph}　屏幕坐标空间：${sw}x${sh}\\n")\n'
     '            if (scale != 1.0 && sw > 0) {\n'
     '                append("注意：图片被缩放过了，点坐标要按屏幕空间算（乘 %.3f），ui_dump 给的坐标已经是屏幕空间。\\n".format(scale))\n'
     '            }\n'
     '            append("前端：").append(foreground(launcher))',
     '            append(L("截屏：%s\\n").format(target.path))\n'
     '            append(L("图片尺寸：%sx%s　屏幕坐标空间：%sx%s\\n").format(pw, ph, sw, sh))\n'
     '            if (scale != 1.0 && sw > 0) {\n'
     '                append(\n'
     '                    L("注意：图片被缩放过了，点坐标要按屏幕空间算（乘 %s），ui_dump 给的坐标已经是屏幕空间。\\n")\n'
     '                        .format("%.3f".format(scale))\n'
     '                )\n'
     '            }\n'
     '            append(L("前端：")).append(foreground(launcher))'),

    # ---------------------------------------------------------------- ui_dump
    ('            return@ToolSpec ToolResult("原始 XML（${xml.length} 字符，已截断到 20000）\\n----\\n" + xml.take(20_000))',
     '            return@ToolSpec ToolResult(\n'
     '                L("原始 XML（%s 字符，已截断到 20000）\\n----\\n").format(xml.length) +\n'
     '                    xml.take(20_000)\n'
     '            )'),
    ('        val head = "屏幕 ${snap.screenWidth}x${snap.screenHeight}（rotation=${snap.rotation}）" +\n'
     '            " · 前台 $fg · 结构里共 ${snap.nodes.size} 个节点，下面是 ${nodes.size} 个\\n"',
     '        val head = buildString {\n'
     '            append(L("屏幕 %sx%s（rotation=%s）").format(snap.screenWidth, snap.screenHeight, snap.rotation))\n'
     '            append(L(" · 前台 %s · 结构里共 %s 个节点，下面是 %s 个\\n")\n'
     '                .format(fg, snap.nodes.size, nodes.size))\n'
     '        }'),
    ('                head + "（坐标是屏幕空间，可直接用于 ui_tap）\\n" +',
     '                head + L("（坐标是屏幕空间，可直接用于 ui_tap）\\n") +'),
    ('                head + "（没有匹配的可交互元素）\\n" +\n'
     '                    "可能是：界面还在加载 / 是自绘界面（游戏、视频）。可以用 ui_screenshot 看一眼，" +\n'
     '                    "或者用 onlyInteractive=false 看看全部节点。"',
     '                head + L("（没有匹配的可交互元素）\\n") +\n'
     '                    L("可能是：界面还在加载 / 是自绘界面（游戏、视频）。可以用 ui_screenshot 看一眼，") +\n'
     '                    L("或者用 onlyInteractive=false 看看全部节点。")'),
    ('            head + "格式：[序号] 类型 \\"文字\\" id (中心x,中心y) 范围[左,上][右,下] 类名\\n" +\n'
     '                "用法：ui_tap 传 x/y 点坐标，或者传 text / desc / id 让我自动找；" +\n'
     '                "滚动用 ui_swipe 的 direction。\\n----\\n" + body',
     '            head + L("格式：[序号] 类型 \\"文字\\" id (中心x,中心y) 范围[左,上][右,下] 类名\\n") +\n'
     '                L("用法：ui_tap 传 x/y 点坐标，或者传 text / desc / id 让我自动找；") +\n'
     '                L("滚动用 ui_swipe 的 direction。\\n----\\n") + body'),

    # ---------------------------------------------------------------- ui_tap
    ('            note = "定位到「${node.label.ifBlank { node.shortClass }}」→ 点 (${px}, ${py})"',
     '            note = L("定位到「%s」→ 点 (%s, %s)")\n'
     '                .format(node.label.ifBlank { node.shortClass }, px, py)'),
    ('        val res = exec(ctx, launcher, cmd, summary = (if (longPress) "长按 " else "点击 ") + note, timeoutMs = 20_000)\n'
     '        if (!res.ok) ctx.fail("点击命令失败（退出码 ${res.exitCode}）：\\n" + (res.stderr + res.stdout).trim().take(400))\n'
     '        ToolResult("$note\\n（已通过 ${launcher.label} 执行：$cmd）")',
     '        val res = exec(\n'
     '            ctx, launcher, cmd,\n'
     '            summary = (if (longPress) L("长按 ") else L("点击 ")) + note,\n'
     '            timeoutMs = 20_000\n'
     '        )\n'
     '        if (!res.ok) {\n'
     '            ctx.fail(\n'
     '                L("点击命令失败（退出码 %s）：\\n").format(res.exitCode) +\n'
     '                    (res.stderr + res.stdout).trim().take(400)\n'
     '            )\n'
     '        }\n'
     '        ToolResult(L("%s\\n（已通过 %s 执行：%s）").format(note, L(launcher.label), cmd))'),

    # ---------------------------------------------------------------- ui_swipe
    ('            label = "从 ($sx,$sy) 滑到 ($ex,$ey)"',
     '            label = L("从 (%s,%s) 滑到 (%s,%s)").format(sx, sy, ex, ey)'),
    ('                "up" -> { sx = fromX; sy = fromY; ex = fromX; ey = fromY - dist; label = "内容上滚 $dist px" }\n'
     '                "down" -> { sx = fromX; sy = fromY; ex = fromX; ey = fromY + dist; label = "内容下滚 $dist px" }\n'
     '                "left" -> { sx = fromX; sy = fromY; ex = fromX - dist; ey = fromY; label = "向左滑 $dist px" }\n'
     '                "right" -> { sx = fromX; sy = fromY; ex = fromX + dist; ey = fromY; label = "向右滑 $dist px" }\n'
     '                else -> ctx.fail("请给 direction（up / down / left / right），或者给全 x1 y1 x2 y2 四个坐标")',
     '                "up" -> {\n'
     '                    sx = fromX; sy = fromY; ex = fromX; ey = fromY - dist\n'
     '                    label = L("内容上滚 %s px").format(dist)\n'
     '                }\n'
     '                "down" -> {\n'
     '                    sx = fromX; sy = fromY; ex = fromX; ey = fromY + dist\n'
     '                    label = L("内容下滚 %s px").format(dist)\n'
     '                }\n'
     '                "left" -> {\n'
     '                    sx = fromX; sy = fromY; ex = fromX - dist; ey = fromY\n'
     '                    label = L("向左滑 %s px").format(dist)\n'
     '                }\n'
     '                "right" -> {\n'
     '                    sx = fromX; sy = fromY; ex = fromX + dist; ey = fromY\n'
     '                    label = L("向右滑 %s px").format(dist)\n'
     '                }\n'
     '                else -> ctx.fail(L("请给 direction（up / down / left / right），或者给全 x1 y1 x2 y2 四个坐标"))'),
    ('        val res = exec(ctx, launcher, cmd, summary = "滑动：$label", timeoutMs = 20_000 + duration.toLong() * repeat)\n'
     '        if (!res.ok) ctx.fail("滑动命令失败（退出码 ${res.exitCode}）：\\n" + (res.stderr + res.stdout).trim().take(400))\n'
     '        ToolResult("$label（重复 $repeat 次，每个 ${duration}ms）")',
     '        val res = exec(\n'
     '            ctx, launcher, cmd,\n'
     '            summary = L("滑动：%s").format(label),\n'
     '            timeoutMs = 20_000 + duration.toLong() * repeat\n'
     '        )\n'
     '        if (!res.ok) {\n'
     '            ctx.fail(\n'
     '                L("滑动命令失败（退出码 %s）：\\n").format(res.exitCode) +\n'
     '                    (res.stderr + res.stdout).trim().take(400)\n'
     '            )\n'
     '        }\n'
     '        ToolResult(L("%s（重复 %s 次，每个 %sms）").format(label, repeat, duration))'),

    # ---------------------------------------------------------------- ui_input
    ('        val text = ctx.args.str("text") ?: ctx.fail("缺少 text")\n'
     '        if (text.isEmpty()) ctx.fail("text 不能为空")',
     '        val text = ctx.args.str("text") ?: ctx.fail(L("缺少 text"))\n'
     '        if (text.isEmpty()) ctx.fail(L("text 不能为空"))'),
    ('                ctx.fail(\n'
     '                    "这段文字含非 ASCII 字符（中文等），安卓的 `input text` 输不进去，需要走剪贴板粘贴，" +\n'
     '                        "但当前拿不到剪贴板能力。\\n可以用 ui_input 只输英文数字，" +\n'
     '                        "或者用 run_shell 配合别的输入法方案。"\n'
     '                )',
     '                ctx.fail(\n'
     '                    L("这段文字含非 ASCII 字符（中文等），安卓的 `input text` 输不进去，需要走剪贴板粘贴，") +\n'
     '                        L("但当前拿不到剪贴板能力。\\n可以用 ui_input 只输英文数字，") +\n'
     '                        L("或者用 run_shell 配合别的输入法方案。")\n'
     '                )'),
    ('            how = "剪贴板 + 粘贴（KEYCODE_PASTE）"',
     '            how = L("剪贴板 + 粘贴（KEYCODE_PASTE）")'),
    ('            how = "直接键入（input text）"',
     '            how = L("直接键入（input text）")'),
    ('            summary = "输入文字：${text.take(40)}${if (text.length > 40) "…" else ""}（$how）",',
     '            summary = L("输入文字：%s%s（%s）")\n'
     '                .format(text.take(40), if (text.length > 40) "…" else "", how),'),
    ('        if (!res.ok) ctx.fail("输入失败（退出码 ${res.exitCode}）：\\n" + (res.stderr + res.stdout).trim().take(400))\n'
     '        ToolResult(\n'
     '            "已输入 ${text.length} 个字符（$how）\\n" +\n'
     '                (if (usePaste) "提示：粘贴前输入框必须有焦点，且文字确实进了剪贴板；如果没生效，先 ui_tap 点一下输入框再试。\\n" else "") +\n'
     '                (if (ctx.args.boolOr("submit", false)) "已按回车提交。\\n" else "")\n'
     '        )',
     '        if (!res.ok) {\n'
     '            ctx.fail(\n'
     '                L("输入失败（退出码 %s）：\\n").format(res.exitCode) +\n'
     '                    (res.stderr + res.stdout).trim().take(400)\n'
     '            )\n'
     '        }\n'
     '        ToolResult(\n'
     '            L("已输入 %s 个字符（%s）\\n").format(text.length, how) +\n'
     '                (if (usePaste) {\n'
     '                    L("提示：粘贴前输入框必须有焦点，且文字确实进了剪贴板；如果没生效，先 ui_tap 点一下输入框再试。\\n")\n'
     '                } else "") +\n'
     '                (if (ctx.args.boolOr("submit", false)) L("已按回车提交。\\n") else "")\n'
     '        )'),

    # ---------------------------------------------------------------- ui_key
    ('        if (raw.isEmpty()) ctx.fail("缺少 key")',
     '        if (raw.isEmpty()) ctx.fail(L("缺少 key"))'),
    ('            ?: ctx.fail(\n'
     '                "不认识的按键：$raw\\n可用：" + KEYS.keys.joinToString(" / ") + "，或者直接给数字键值（如 4 = 返回）"\n'
     '            )',
     '            ?: ctx.fail(\n'
     '                L("不认识的按键：%s\\n可用：").format(raw) +\n'
     '                    KEYS.keys.joinToString(" / ") +\n'
     '                    L("，或者直接给数字键值（如 4 = 返回）")\n'
     '            )'),
    ('        val res = exec(ctx, launcher, cmd, summary = "按键：$raw（$code）${if (repeat > 1) " ×$repeat" else ""}", timeoutMs = 20_000)\n'
     '        if (!res.ok) ctx.fail("按键失败（退出码 ${res.exitCode}）：\\n" + (res.stderr + res.stdout).trim().take(400))\n'
     '        val fg = runCatching { foreground(launcher) }.getOrDefault("")\n'
     '        ToolResult("已按 $raw（keycode $code）${if (repeat > 1) " $repeat 次" else ""}\\n当前前台：$fg")',
     '        val res = exec(\n'
     '            ctx, launcher, cmd,\n'
     '            summary = L("按键：%s（%s）%s")\n'
     '                .format(raw, code, if (repeat > 1) " ×$repeat" else ""),\n'
     '            timeoutMs = 20_000\n'
     '        )\n'
     '        if (!res.ok) {\n'
     '            ctx.fail(\n'
     '                L("按键失败（退出码 %s）：\\n").format(res.exitCode) +\n'
     '                    (res.stderr + res.stdout).trim().take(400)\n'
     '            )\n'
     '        }\n'
     '        val fg = runCatching { foreground(launcher) }.getOrDefault("")\n'
     '        ToolResult(\n'
     '            L("已按 %s（keycode %s）%s\\n当前前台：%s")\n'
     '                .format(raw, code, if (repeat > 1) L(" %s 次").format(repeat) else "", fg)\n'
     '        )'),

    # ---------------------------------------------------------------- ui_launch
    ('                if (!res.ok) ctx.fail("回桌面失败：\\n" + (res.stderr + res.stdout).trim().take(300))\n'
     '                ToolResult("已回到桌面")',
     '                if (!res.ok) {\n'
     '                    ctx.fail(L("回桌面失败：\\n") + (res.stderr + res.stdout).trim().take(300))\n'
     '                }\n'
     '                ToolResult(L("已回到桌面"))'),
    ('                ToolResult("当前前台：$fg\\n" + (if (act.isNotBlank()) act else "（拿不到 activity 信息）"))',
     '                ToolResult(\n'
     '                    L("当前前台：%s\\n").format(fg) +\n'
     '                        (if (act.isNotBlank()) act else L("（拿不到 activity 信息）"))\n'
     '                )'),
    ('                ToolResult(\n'
     '                    "第三方应用 ${pkgs.size} 个：\\n" +\n'
     '                        pkgs.take(200).joinToString("\\n").ifBlank { "（没有查到）" } +\n'
     '                        if (pkgs.size > 200) "\\n… 还有 ${pkgs.size - 200} 个" else ""\n'
     '                )',
     '                ToolResult(\n'
     '                    L("第三方应用 %s 个：\\n").format(pkgs.size) +\n'
     '                        pkgs.take(200).joinToString("\\n").ifBlank { L("（没有查到）") } +\n'
     '                        if (pkgs.size > 200) L("\\n… 还有 %s 个").format(pkgs.size - 200) else ""\n'
     '                )'),
    ('                if (pkg.isBlank()) ctx.fail("action=app 需要给 package（包名）。不知道包名可以先用 action=list 查。")\n'
     '                if (!PKG_NAME.matches(pkg)) ctx.fail("包名格式不对：$pkg（应该是 com.xxx.yyy 这种）")',
     '                if (pkg.isBlank()) {\n'
     '                    ctx.fail(L("action=app 需要给 package（包名）。不知道包名可以先用 action=list 查。"))\n'
     '                }\n'
     '                if (!PKG_NAME.matches(pkg)) {\n'
     '                    ctx.fail(L("包名格式不对：%s（应该是 com.xxx.yyy 这种）").format(pkg))\n'
     '                }'),
    ('                val res = exec(ctx, launcher, cmd, summary = "启动应用 $pkg", timeoutMs = 30_000)',
     '                val res = exec(\n'
     '                    ctx, launcher, cmd,\n'
     '                    summary = L("启动应用 %s").format(pkg),\n'
     '                    timeoutMs = 30_000\n'
     '                )'),
    ('                    ctx.fail(\n'
     '                        "启动 $pkg 失败：\\n" + out.take(400) +\n'
     '                            "\\n\\n可能这个包名不存在，或者它没有可启动的界面（是纯后台服务）。"\n'
     '                    )',
     '                    ctx.fail(\n'
     '                        L("启动 %s 失败：\\n").format(pkg) + out.take(400) +\n'
     '                            L("\\n\\n可能这个包名不存在，或者它没有可启动的界面（是纯后台服务）。")\n'
     '                    )'),
    ('                ToolResult("已启动 $pkg\\n当前前台：$fg\\n\\n提示：启动后稍等一下再 ui_dump，界面可能还在加载。")',
     '                ToolResult(\n'
     '                    L("已启动 %s\\n当前前台：%s\\n\\n提示：启动后稍等一下再 ui_dump，界面可能还在加载。")\n'
     '                        .format(pkg, fg)\n'
     '                )'),
    ('            else -> ctx.fail("不认识的 action：$action（可用 app / home / current / list）")',
     '            else -> ctx.fail(L("不认识的 action：%s（可用 app / home / current / list）").format(action))'),

    # ---------------------------------------------------------------- ui_wait
    ('        if (text == null && desc == null && id == null) ctx.fail("至少要给 text / desc / id 之一")',
     '        if (text == null && desc == null && id == null) {\n'
     '            ctx.fail(L("至少要给 text / desc / id 之一"))\n'
     '        }'),
    ('                return@ToolSpec ToolResult(\n'
     '                    "「$what」已${if (disappear) "消失" else "出现"}（等了 ${took}ms，查了 $rounds 次）\\n" +\n'
     '                        (last?.let { "位置：中心(${it.centerX},${it.centerY}) 范围[${it.left},${it.top}][${it.right},${it.bottom}]\\n" } ?: "")\n'
     '                )',
     '                return@ToolSpec ToolResult(\n'
     '                    L("「%s」已%s（等了 %sms，查了 %s 次）\\n").format(\n'
     '                        what,\n'
     '                        if (disappear) L("消失") else L("出现"),\n'
     '                        took,\n'
     '                        rounds\n'
     '                    ) +\n'
     '                        (\n'
     '                            last?.let {\n'
     '                                L("位置：中心(%s,%s) 范围[%s,%s][%s,%s]\\n").format(\n'
     '                                    it.centerX, it.centerY, it.left, it.top, it.right, it.bottom\n'
     '                                )\n'
     '                            } ?: ""\n'
     '                        )\n'
     '                )'),
    ('        ctx.fail(\n'
     '            "等了 ${timeout}ms，「$what」还是${if (disappear) "在" else "没出现"}。\\n" +\n'
     '                "可以用 ui_dump 看看当前屏幕上到底有什么（或者 ui_screenshot 看画面）。"\n'
     '        )',
     '        ctx.fail(\n'
     '            L("等了 %sms，「%s」还是%s。\\n")\n'
     '                .format(timeout, what, if (disappear) L("在") else L("没出现")) +\n'
     '                L("可以用 ui_dump 看看当前屏幕上到底有什么（或者 ui_screenshot 看画面）。")\n'
     '        )'),
]


def main():
    dry = "--dry" in sys.argv
    path = os.path.join(ROOT, F)
    src = open(path, encoding="utf-8").read()
    failed = []
    for old, new in PATCHES:
        n = src.count(old)
        if n != 1:
            failed.append((old.split("\n")[0][:70], n))
            continue
        src = src.replace(old, new)
    if failed:
        print(f"❌ {len(failed)} 处没匹配上：")
        for head, n in failed:
            print(f"   [{n} 次] {head}")
        return 1
    print(f"✅ {len(PATCHES)} 处替换全部命中")
    if dry:
        return 0
    open(path, "w", encoding="utf-8").write(src)
    print("已写入 ToolsUi.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

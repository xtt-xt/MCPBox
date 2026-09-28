#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 6 批 i18n（续）：ToolsPacks.kt（工具包管理与列表的返回正文）。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsPacks.kt"

PATCHES = [
    # ---------------------------------------------------------------- activate_pack
    ('        if (id.isEmpty()) ctx.fail("pack 不能为空")\n'
     '        ctx.guard(PermKey.SYSTEM, null, L("激活工具包 %s").format(id))\n'
     '\n'
     '        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })\n'
     '            ?: ctx.fail("没有叫「$id」的包。用 list_packs 看看有哪些。")\n'
     '        if (pack.tools.isEmpty()) {\n'
     '            ToolResult("「${pack.title}」是空的，没有工具可以激活。")',
     '        if (id.isEmpty()) ctx.fail(L("pack 不能为空"))\n'
     '        ctx.guard(PermKey.SYSTEM, null, L("激活工具包 %s").format(id))\n'
     '\n'
     '        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })\n'
     '            ?: ctx.fail(L("没有叫「%s」的包。用 list_packs 看看有哪些。").format(id))\n'
     '        if (pack.tools.isEmpty()) {\n'
     '            ToolResult(L("「%s」是空的，没有工具可以激活。").format(L(pack.title)))'),
    ('                    append(if (changed) "已激活" else "本来就已激活")\n'
     '                    append("「").append(pack.title).append("」（").append(id).append("）。\\n")\n'
     '                    append("新增可见的工具 ").append(pack.tools.size).append(" 个：\\n")\n'
     '                    pack.tools.forEach { append("  · ").append(it).append(\'\\n\') }\n'
     '                    append("\\n注意：大多数 MCP 客户端**只在连接时拉一次工具列表**，之后不会再拉，")\n'
     '                    append("服务端也无法通知它刷新。所以这些工具通常**要等客户端重新连接（或重启 App）后才能调用**。\\n")\n'
     '                    append("如果你现在就要用，可以直接试着按名字调用 —— 服务端允许，但客户端可能拦下来；")\n'
     '                    append("被拦了就告诉用户「请重新连接 MCP 服务」，不要反复重试。\\n")\n'
     '                    append("当前会话：").append(ctx.profile)',
     '                    append(if (changed) L("已激活") else L("本来就已激活"))\n'
     '                    append(L("「%s」（%s）。\\n").format(L(pack.title), id))\n'
     '                    append(L("新增可见的工具 %s 个：\\n").format(pack.tools.size))\n'
     '                    pack.tools.forEach { append("  · ").append(it).append(\'\\n\') }\n'
     '                    append(L("\\n注意：大多数 MCP 客户端**只在连接时拉一次工具列表**，之后不会再拉，"))\n'
     '                    append(L("服务端也无法通知它刷新。所以这些工具通常**要等客户端重新连接（或重启 App）后才能调用**。\\n"))\n'
     '                    append(L("如果你现在就要用，可以直接试着按名字调用 —— 服务端允许，但客户端可能拦下来；"))\n'
     '                    append(L("被拦了就告诉用户「请重新连接 MCP 服务」，不要反复重试。\\n"))\n'
     '                    append(L("当前会话：")).append(ctx.profile)'),

    # ---------------------------------------------------------------- deactivate_pack
    ('        if (id.isEmpty()) ctx.fail("pack 不能为空")\n'
     '        if (id == ToolPack.CORE_ID) ctx.fail("基础包不能停用（工具包管理本身就在它里面）")\n'
     '        ctx.guard(PermKey.SYSTEM, null, L("停用工具包 %s").format(id))\n'
     '\n'
     '        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })\n'
     '            ?: ctx.fail("没有叫「$id」的包。用 list_packs 看看有哪些。")\n'
     '        val changed = ctx.profiles.deactivate(ctx.profile, id)\n'
     '        ToolResult(\n'
     '            if (changed) "已停用「${pack.title}」。它的 ${pack.tools.size} 个工具不再出现在工具列表里。"\n'
     '            else "「${pack.title}」本来就没激活。"\n'
     '        )',
     '        if (id.isEmpty()) ctx.fail(L("pack 不能为空"))\n'
     '        if (id == ToolPack.CORE_ID) {\n'
     '            ctx.fail(L("基础包不能停用（工具包管理本身就在它里面）"))\n'
     '        }\n'
     '        ctx.guard(PermKey.SYSTEM, null, L("停用工具包 %s").format(id))\n'
     '\n'
     '        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })\n'
     '            ?: ctx.fail(L("没有叫「%s」的包。用 list_packs 看看有哪些。").format(id))\n'
     '        val changed = ctx.profiles.deactivate(ctx.profile, id)\n'
     '        ToolResult(\n'
     '            if (changed) {\n'
     '                L("已停用「%s」。它的 %s 个工具不再出现在工具列表里。")\n'
     '                    .format(L(pack.title), pack.tools.size)\n'
     '            } else {\n'
     '                L("「%s」本来就没激活。").format(L(pack.title))\n'
     '            }\n'
     '        )'),

    # ---------------------------------------------------------------- reset_packs
    ('        ToolResult(renderPacks(ctx, ctx.profiles, showTools = false, header = "已重置回默认"))',
     '        ToolResult(renderPacks(ctx, ctx.profiles, showTools = false, header = L("已重置回默认")))'),

    # ---------------------------------------------------------------- manage_pack
    ('                if (title.isBlank()) ctx.fail("title 不能为空")\n'
     '                if (tools.isEmpty()) ctx.fail("tools 不能为空，至少放一个工具名")\n'
     '                if (action == "update" && id.isEmpty()) ctx.fail("update 需要给 id")',
     '                if (title.isBlank()) ctx.fail(L("title 不能为空"))\n'
     '                if (tools.isEmpty()) ctx.fail(L("tools 不能为空，至少放一个工具名"))\n'
     '                if (action == "update" && id.isEmpty()) ctx.fail(L("update 需要给 id"))'),
    ('                        append(if (action == "create") "已新建" else "已更新")\n'
     '                        append("工具包「").append(saved.title).append("」\\n")\n'
     '                        append("  id：").append(saved.id).append(\'\\n\')\n'
     '                        append("  工具 ").append(saved.tools.size).append(" 个：")\n'
     '                        append(saved.tools.joinToString("、")).append(\'\\n\')\n'
     '                        if (unknown.isNotEmpty()) {\n'
     '                            append("\\n注意：这些名字当前不在工具表里（可能拼错了，或者被禁用了）：")\n'
     '                            append(unknown.joinToString("、")).append(\'\\n\')\n'
     '                        }\n'
     '                        append("\\n用 activate_pack pack=\\\"").append(saved.id).append("\\\" 就能激活它。")',
     '                        append(if (action == "create") L("已新建") else L("已更新"))\n'
     '                        append(L("工具包「%s」\\n").format(L(saved.title)))\n'
     '                        append(L("  id：")).append(saved.id).append(\'\\n\')\n'
     '                        append(L("  工具 %s 个：").format(saved.tools.size))\n'
     '                        append(saved.tools.joinToString(L("、"))).append(\'\\n\')\n'
     '                        if (unknown.isNotEmpty()) {\n'
     '                            append(L("\\n注意：这些名字当前不在工具表里（可能拼错了，或者被禁用了）："))\n'
     '                            append(unknown.joinToString(L("、"))).append(\'\\n\')\n'
     '                        }\n'
     '                        append(L("\\n用 activate_pack pack=\\\"%s\\\" 就能激活它。").format(saved.id))'),
    ('                if (id.isEmpty()) ctx.fail("delete 需要给 id")\n'
     '                if (packs.isBuiltin(id)) ctx.fail("「$id」是内置包，不能删")\n'
     '                val ok = packs.remove(id)\n'
     '                if (ok) profiles.deactivate(ctx.profile, id)\n'
     '                ToolResult(if (ok) "已删除工具包「$id」。" else "没有找到自定义包「$id」。")',
     '                if (id.isEmpty()) ctx.fail(L("delete 需要给 id"))\n'
     '                if (packs.isBuiltin(id)) ctx.fail(L("「%s」是内置包，不能删").format(id))\n'
     '                val ok = packs.remove(id)\n'
     '                if (ok) profiles.deactivate(ctx.profile, id)\n'
     '                ToolResult(\n'
     '                    if (ok) L("已删除工具包「%s」。").format(id)\n'
     '                    else L("没有找到自定义包「%s」。").format(id)\n'
     '                )'),
    ('            else -> ctx.fail("action 只能是 create / update / delete")',
     '            else -> ctx.fail(L("action 只能是 create / update / delete"))'),

    # ---------------------------------------------------------------- renderPacks / one
    ('            if (header != null) append(header).append(\'\\n\')\n'
     '            append("工具包 —— 当前会话「").append(ctx.profile).append("」\\n")\n'
     '            append("现在能看到 ").append(visible.size).append(" 个工具\\n")\n'
     '\n'
     '            val on = all.filter { it.core || it.id in active }\n'
     '            val off = all.filterNot { it.core || it.id in active }\n'
     '\n'
     '            append("\\n● 已激活（").append(on.size).append("）\\n")\n'
     '            on.forEach { append(one(it, showTools)) }\n'
     '            if (off.isNotEmpty()) {\n'
     '                append("\\n○ 未激活（").append(off.size).append("）—— 需要时用 activate_pack 打开\\n")\n'
     '                off.forEach { append(one(it, showTools = false)) }\n'
     '            }\n'
     '            append("\\n提示：包决定「工具列表里能看见什么」。停用只是从列表里收起来；")\n'
     '            append("要在客户端生效，需要让它重新连接（或重启 App）。")',
     '            if (header != null) append(header).append(\'\\n\')\n'
     '            append(L("工具包 —— 当前会话「%s」\\n").format(ctx.profile))\n'
     '            append(L("现在能看到 %s 个工具\\n").format(visible.size))\n'
     '\n'
     '            val on = all.filter { it.core || it.id in active }\n'
     '            val off = all.filterNot { it.core || it.id in active }\n'
     '\n'
     '            append(L("\\n● 已激活（%s）\\n").format(on.size))\n'
     '            on.forEach { append(one(it, showTools)) }\n'
     '            if (off.isNotEmpty()) {\n'
     '                append(L("\\n○ 未激活（%s）—— 需要时用 activate_pack 打开\\n").format(off.size))\n'
     '                off.forEach { append(one(it, showTools = false)) }\n'
     '            }\n'
     '            append(L("\\n提示：包决定「工具列表里能看见什么」。停用只是从列表里收起来；"))\n'
     '            append(L("要在客户端生效，需要让它重新连接（或重启 App）。"))'),
    ('        append("  · ").append(p.title).append("（").append(p.id).append("）")\n'
     '        if (p.core) append("  [常驻]")\n'
     '        append("\\n      ").append(p.description.ifBlank { "（没有说明）" }).append(\'\\n\')\n'
     '        if (showTools && p.tools.isNotEmpty()) {\n'
     '            append("      工具：").append(p.tools.joinToString("、")).append(\'\\n\')\n'
     '        }',
     '        append("  · ").append(L(p.title)).append("（").append(p.id).append("）")\n'
     '        if (p.core) append(L("  [常驻]"))\n'
     '        append("\\n      ").append(L(p.description).ifBlank { L("（没有说明）") }).append(\'\\n\')\n'
     '        if (showTools && p.tools.isNotEmpty()) {\n'
     '            append(L("      工具：")).append(p.tools.joinToString(L("、"))).append(\'\\n\')\n'
     '        }'),
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
    print("已写入 ToolsPacks.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 6 批 i18n（续）：ToolsWrite 尾部（还原/清空回收站）+ ToolsMemory 全部返回正文。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsWrite.kt"
M = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsMemory.kt"

PATCHES = [
    # ================================================================ ToolsWrite：restore / empty
    (W, '            id != null && name != null -> ctx.fail("id 和 name 只能给一个")\n'
        '            id != null -> all.firstOrNull { it.id == id } ?: ctx.fail("回收站里没有 ID=$id 的条目")\n'
        '            name != null -> all.firstOrNull { it.name == name }\n'
        '                ?: all.firstOrNull { it.name.contains(name) }\n'
        '                ?: ctx.fail("回收站里没有文件名含 $name 的条目")\n'
        '            else -> {\n'
        '                if (all.size == 1) all.first() else ctx.fail("请提供 id 或 name（回收站有 ${all.size} 项，可用 list_trash 查看）")\n'
        '            }',
        '            id != null && name != null -> ctx.fail(L("id 和 name 只能给一个"))\n'
        '            id != null -> all.firstOrNull { it.id == id }\n'
        '                ?: ctx.fail(L("回收站里没有 ID=%s 的条目").format(id))\n'
        '            name != null -> all.firstOrNull { it.name == name }\n'
        '                ?: all.firstOrNull { it.name.contains(name) }\n'
        '                ?: ctx.fail(L("回收站里没有文件名含 %s 的条目").format(name))\n'
        '            else -> {\n'
        '                if (all.size == 1) all.first()\n'
        '                else ctx.fail(L("请提供 id 或 name（回收站有 %s 项，可用 list_trash 查看）").format(all.size))\n'
        '            }'),
    (W, '        ToolResult("已还原：${entry.name} → ${restored.path}")',
        '        ToolResult(L("已还原：%s → %s").format(entry.name, restored.path))'),
    (W, '        if (!ctx.args.boolOr("confirm", false)) ctx.fail("请设置 confirm=true 确认清空回收站")\n'
        '        val entries = ctx.trash.entries()\n'
        '        if (entries.isEmpty()) return@ToolSpec ToolResult("回收站已经是空的")',
        '        if (!ctx.args.boolOr("confirm", false)) {\n'
        '            ctx.fail(L("请设置 confirm=true 确认清空回收站"))\n'
        '        }\n'
        '        val entries = ctx.trash.entries()\n'
        '        if (entries.isEmpty()) return@ToolSpec ToolResult(L("回收站已经是空的"))'),
    (W, '        ToolResult("回收站已清空：删除 $n 项，释放 ${ctx.sandbox.humanSize(bytes)}")',
        '        ToolResult(L("回收站已清空：删除 %s 项，释放 %s").format(n, ctx.sandbox.humanSize(bytes)))'),

    # ================================================================ ToolsMemory
    (M, '        if (items.isEmpty()) ctx.fail("entities 不能为空（每项至少要有 name）")',
        '        if (items.isEmpty()) ctx.fail(L("entities 不能为空（每项至少要有 name）"))'),
    (M, '                append("已创建 ").append(r.created).append(" 个实体，已存在 ").append(r.skipped).append(" 个")\n'
        '                if (obsAdded > 0) append("，附带 ") .append(obsAdded).append(" 条观察")\n'
        '                append("。\\n")\n'
        '                names.forEach { append("  · ").append(it).append(\'\\n\') }\n'
        '                append("\\n当前记忆库共 ").append(store.graph.entities.size).append(" 个实体、")\n'
        '                    .append(store.graph.relations.size).append(" 条关系。")',
        '                append(L("已创建 %s 个实体，已存在 %s 个").format(r.created, r.skipped))\n'
        '                if (obsAdded > 0) append(L("，附带 %s 条观察").format(obsAdded))\n'
        '                append(L("。\\n"))\n'
        '                names.forEach { append("  · ").append(it).append(\'\\n\') }\n'
        '                append(\n'
        '                    L("\\n当前记忆库共 %s 个实体、%s 条关系。")\n'
        '                        .format(store.graph.entities.size, store.graph.relations.size)\n'
        '                )'),
    (M, '        if (items.isEmpty()) ctx.fail("relations 不能为空（每项要有 from 和 to）")',
        '        if (items.isEmpty()) ctx.fail(L("relations 不能为空（每项要有 from 和 to）"))'),
    (M, '                append("已建立 ").append(r.created).append(" 条关系，已存在 ").append(r.skipped).append(" 条。\\n")',
        '                append(L("已建立 %s 条关系，已存在 %s 条。\\n").format(r.created, r.skipped))'),
    (M, '                    append("\\n注意：这些实体还不存在，建议补建：")\n'
        '                    append(missing.joinToString("、")).append(\'\\n\')\n'
        '                }\n'
        '                append("\\n当前记忆库共 ").append(store.graph.entities.size).append(" 个实体、")\n'
        '                    .append(store.graph.relations.size).append(" 条关系。")',
        '                    append(L("\\n注意：这些实体还不存在，建议补建："))\n'
        '                    append(missing.joinToString(L("、"))).append(\'\\n\')\n'
        '                }\n'
        '                append(\n'
        '                    L("\\n当前记忆库共 %s 个实体、%s 条关系。")\n'
        '                        .format(store.graph.entities.size, store.graph.relations.size)\n'
        '                )'),
    (M, '        if (items.isEmpty()) ctx.fail("observations 不能为空（每项要有 entityName 和 contents）")',
        '        if (items.isEmpty()) {\n'
        '            ctx.fail(L("observations 不能为空（每项要有 entityName 和 contents）"))\n'
        '        }'),
    (M, '                sb.append("  ! 跳过「").append(name).append("」：实体不存在\\n")\n'
        '                return@forEach\n'
        '            }\n'
        '            val n = store.addObservations(name, texts)\n'
        '            total += n\n'
        '            sb.append("  · ").append(name).append("：新增 ").append(n).append(" 条")\n'
        '                .append(if (n < texts.size) "（跳过 ${texts.size - n} 条重复）" else "").append(\'\\n\')\n'
        '        }\n'
        '        ToolResult("已追加 $total 条观察。\\n$sb".trimEnd())',
        '                sb.append(L("  ! 跳过「%s」：实体不存在\\n").format(name))\n'
        '                return@forEach\n'
        '            }\n'
        '            val n = store.addObservations(name, texts)\n'
        '            total += n\n'
        '            sb.append(L("  · %s：新增 %s 条").format(name, n))\n'
        '                .append(if (n < texts.size) L("（跳过 %s 条重复）").format(texts.size - n) else "")\n'
        '                .append(\'\\n\')\n'
        '        }\n'
        '        ToolResult(L("已追加 %s 条观察。\\n%s").format(total, sb.toString()).trimEnd())'),
    (M, '            ToolResult("记忆库还是空的（或该过滤条件下没有内容）。用 create_entities 开始记录吧。")\n'
        '        } else {\n'
        '            ToolResult(renderGraph(store, g, "记忆库"))',
        '            ToolResult(L("记忆库还是空的（或该过滤条件下没有内容）。用 create_entities 开始记录吧。"))\n'
        '        } else {\n'
        '            ToolResult(renderGraph(store, g, L("记忆库")))'),
    (M, '        if (query.isEmpty()) ctx.fail("query 不能为空")',
        '        if (query.isEmpty()) ctx.fail(L("query 不能为空"))'),
    (M, '            ToolResult("没有找到和「$query」相关的记忆。")\n'
        '        } else {\n'
        '            ToolResult(renderGraph(store, g, "搜索「$query」"))',
        '            ToolResult(L("没有找到和「%s」相关的记忆。").format(query))\n'
        '        } else {\n'
        '            ToolResult(renderGraph(store, g, L("搜索「%s」").format(query)))'),
    (M, '        if (names.isEmpty()) ctx.fail("names 不能为空")\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：读取 %s 个实体").format(names.size))\n'
        '        val found = names.mapNotNull { store.entity(it) }\n'
        '        val missing = names.filterNot { store.hasEntity(it) }\n'
        '        if (found.isEmpty()) {\n'
        '            ToolResult("这些实体都不存在：" + missing.joinToString("、"))\n'
        '        } else {\n'
        '            val sb = StringBuilder(renderGraph(store, MemoryGraph(found, store.graph.relations, store.graph.revision), "记忆"))\n'
        '            if (missing.isNotEmpty()) sb.append("\\n\\n以下实体不存在：").append(missing.joinToString("、"))',
        '        if (names.isEmpty()) ctx.fail(L("names 不能为空"))\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：读取 %s 个实体").format(names.size))\n'
        '        val found = names.mapNotNull { store.entity(it) }\n'
        '        val missing = names.filterNot { store.hasEntity(it) }\n'
        '        if (found.isEmpty()) {\n'
        '            ToolResult(L("这些实体都不存在：") + missing.joinToString(L("、")))\n'
        '        } else {\n'
        '            val sb = StringBuilder(\n'
        '                renderGraph(\n'
        '                    store,\n'
        '                    MemoryGraph(found, store.graph.relations, store.graph.revision),\n'
        '                    L("记忆")\n'
        '                )\n'
        '            )\n'
        '            if (missing.isNotEmpty()) {\n'
        '                sb.append(L("\\n\\n以下实体不存在：")).append(missing.joinToString(L("、")))\n'
        '            }'),
    (M, '        if (names.isEmpty()) ctx.fail("names 不能为空")\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：删除实体 %s").format(names.joinToString(L("、"))))\n'
        '        val n = store.deleteEntities(names)\n'
        '        ToolResult("已删除 $n 个实体（连带的关系也一起删了）。\\n剩余：${store.graph.entities.size} 个实体、${store.graph.relations.size} 条关系。")',
        '        if (names.isEmpty()) ctx.fail(L("names 不能为空"))\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：删除实体 %s").format(names.joinToString(L("、"))))\n'
        '        val n = store.deleteEntities(names)\n'
        '        ToolResult(\n'
        '            L("已删除 %s 个实体（连带的关系也一起删了）。\\n剩余：%s 个实体、%s 条关系。")\n'
        '                .format(n, store.graph.entities.size, store.graph.relations.size)\n'
        '        )'),
    (M, '        if (items.isEmpty()) ctx.fail("relations 不能为空")\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：删除 %s 条关系").format(items.size))\n'
        '        val n = store.deleteRelations(items)\n'
        '        ToolResult("已删除 $n 条关系。剩余 ${store.graph.relations.size} 条。")',
        '        if (items.isEmpty()) ctx.fail(L("relations 不能为空"))\n'
        '        ctx.guard(PermKey.MEMORY, null, L("记忆：删除 %s 条关系").format(items.size))\n'
        '        val n = store.deleteRelations(items)\n'
        '        ToolResult(L("已删除 %s 条关系。剩余 %s 条。").format(n, store.graph.relations.size))'),
    (M, '        if (items.isEmpty()) ctx.fail("deletions 不能为空")',
        '        if (items.isEmpty()) ctx.fail(L("deletions 不能为空"))'),
    (M, '                sb.append("  ! 跳过「").append(name).append("」：实体不存在\\n")\n'
        '                return@forEach\n'
        '            }\n'
        '            val n = store.deleteObservations(name, texts)\n'
        '            total += n\n'
        '            sb.append("  · ").append(name).append("：删掉 ").append(n).append(" 条\\n")\n'
        '        }\n'
        '        ToolResult("共删除 $total 条观察。\\n$sb".trimEnd())',
        '                sb.append(L("  ! 跳过「%s」：实体不存在\\n").format(name))\n'
        '                return@forEach\n'
        '            }\n'
        '            val n = store.deleteObservations(name, texts)\n'
        '            total += n\n'
        '            sb.append(L("  · %s：删掉 %s 条\\n").format(name, n))\n'
        '        }\n'
        '        ToolResult(L("共删除 %s 条观察。\\n%s").format(total, sb.toString()).trimEnd())'),
    (M, '        sb.append(title).append("\\n")\n'
        '        sb.append("实体 ").append(g.entities.size).append(" 个 · 关系 ").append(g.relations.size).append(" 条\\n")',
        '        sb.append(title).append("\\n")\n'
        '        sb.append(L("实体 %s 个 · 关系 %s 条\\n").format(g.entities.size, g.relations.size))'),
    (M, '            sb.append("\\n（还有未展开的关联实体：").append(dangling.joinToString("、")).append("）\\n")',
        '            sb.append(L("\\n（还有未展开的关联实体：")).append(dangling.joinToString(L("、")))\n'
        '                .append(L("）\\n"))'),
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

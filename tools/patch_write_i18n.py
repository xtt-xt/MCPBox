#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 6 批 i18n（续）：ToolsWrite.kt（写入 / 编辑 / 复制 / 移动 / 删除 / 回收站的返回正文）。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsWrite.kt"

PATCHES = [
    # ---------------------------------------------------------------- write_file
    ('        if (existed?.dir == true) ctx.fail("目标是一个目录：${f.path}")',
     '        if (existed?.dir == true) ctx.fail(L("目标是一个目录：%s").format(f.path))'),
    ('                .getOrElse { ctx.fail("base64 内容不合法") }',
     '                .getOrElse { ctx.fail(L("base64 内容不合法")) }'),
    ('        if (mode == "create_new" && exists) ctx.fail("文件已存在：${f.path}")\n'
     '        val verb = when {\n'
     '            exists && mode == "append" -> "追加"\n'
     '            exists -> "覆盖"\n'
     '            else -> "新建"\n'
     '        }',
     '        if (mode == "create_new" && exists) ctx.fail(L("文件已存在：%s").format(f.path))\n'
     '        val verb = when {\n'
     '            exists && mode == "append" -> L("追加")\n'
     '            exists -> L("覆盖")\n'
     '            else -> L("新建")\n'
     '        }'),
    ('            ctx.fail(e.message ?: "当前模式下不允许写入")\n'
     '        }\n'
     '        val parent = f.parentFile',
     '            ctx.fail(e.message ?: L("当前模式下不允许写入"))\n'
     '        }\n'
     '        val parent = f.parentFile'),
    ('            ctx.fail("上级目录不存在：${parent.path}（可设置 createDirs=true）")',
     '            ctx.fail(L("上级目录不存在：%s（可设置 createDirs=true）").format(parent.path))'),
    ('            ctx.fail("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku\\n目标：${f.path}")\n'
     '        }\n'
     '        val total = ctx.bridge.stat(f)?.size ?: payload.size.toLong()\n'
     '        ToolResult("${verb}成功：${f.path}\\n本次写入 ${ctx.sandbox.humanSize(bytes.size.toLong())}，文件当前 ${ctx.sandbox.humanSize(total)}")',
     '            ctx.fail(\n'
     '                L("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku\\n目标：%s").format(f.path)\n'
     '            )\n'
     '        }\n'
     '        val total = ctx.bridge.stat(f)?.size ?: payload.size.toLong()\n'
     '        ToolResult(\n'
     '            L("%s成功：%s\\n本次写入 %s，文件当前 %s").format(\n'
     '                verb, f.path, ctx.sandbox.humanSize(bytes.size.toLong()), ctx.sandbox.humanSize(total)\n'
     '            )\n'
     '        )'),

    # ---------------------------------------------------------------- edit_file
    ('        val st = ctx.bridge.stat(f) ?: ctx.fail("文件不存在，或者读不到：${f.path}")\n'
     '        if (st.dir) ctx.fail("这是目录：${f.path}")\n'
     '        val oldText = ctx.args.str("oldText") ?: ctx.fail("缺少 oldText")',
     '        val st = ctx.bridge.stat(f) ?: ctx.fail(L("文件不存在，或者读不到：%s").format(f.path))\n'
     '        if (st.dir) ctx.fail(L("这是目录：%s").format(f.path))\n'
     '        val oldText = ctx.args.str("oldText") ?: ctx.fail(L("缺少 oldText"))'),
    ('            ?: ctx.fail("读取失败：应用自己没有权限，而且没有可用的 root / Shizuku")',
     '            ?: ctx.fail(L("读取失败：应用自己没有权限，而且没有可用的 root / Shizuku"))'),
    ('            val re = runCatching { Regex(oldText) }.getOrElse { ctx.fail("正则不合法：${it.message}") }',
     '            val re = runCatching { Regex(oldText) }\n'
     '                .getOrElse { ctx.fail(L("正则不合法：%s").format(it.message)) }'),
    ('        if (occurrences == 0) ctx.fail("没有找到要替换的内容（oldText 在文件中不存在）")',
     '        if (occurrences == 0) ctx.fail(L("没有找到要替换的内容（oldText 在文件中不存在）"))'),
    ('            ctx.fail(e.message ?: "当前模式下不允许写入")\n'
     '        }\n'
     '        if (!ctx.bridge.writeBytes(f, result.toByteArray(Charsets.UTF_8))) {\n'
     '            ctx.fail("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku")\n'
     '        }\n'
     '        val newSize = ctx.bridge.stat(f)?.size ?: result.length.toLong()\n'
     '        ToolResult("修改成功：${f.path}\\n匹配 $occurrences 处，已替换 $willReplace 处\\n文件大小：${ctx.sandbox.humanSize(newSize)}")',
     '            ctx.fail(e.message ?: L("当前模式下不允许写入"))\n'
     '        }\n'
     '        if (!ctx.bridge.writeBytes(f, result.toByteArray(Charsets.UTF_8))) {\n'
     '            ctx.fail(L("写入失败：应用自己没有权限，而且没有可用的 root / Shizuku"))\n'
     '        }\n'
     '        val newSize = ctx.bridge.stat(f)?.size ?: result.length.toLong()\n'
     '        ToolResult(\n'
     '            L("修改成功：%s\\n匹配 %s 处，已替换 %s 处\\n文件大小：%s")\n'
     '                .format(f.path, occurrences, willReplace, ctx.sandbox.humanSize(newSize))\n'
     '        )'),

    # ---------------------------------------------------------------- make_dir
    ('            return@ToolSpec if (it.dir) ToolResult("目录已存在：${f.path}")\n'
     '            else ctx.fail("同名文件已存在：${f.path}")',
     '            return@ToolSpec if (it.dir) ToolResult(L("目录已存在：%s").format(f.path))\n'
     '            else ctx.fail(L("同名文件已存在：%s").format(f.path))'),
    ('            ctx.fail(e.message ?: "当前模式下不允许写入")\n'
     '        }\n'
     '        val parents = ctx.args.boolOr("parents", true)',
     '            ctx.fail(e.message ?: L("当前模式下不允许写入"))\n'
     '        }\n'
     '        val parents = ctx.args.boolOr("parents", true)'),
    ('        if (!ok) ctx.fail("创建目录失败（可能是权限不足）：${f.path}")\n'
     '        ToolResult("已创建目录：${f.path}")',
     '        if (!ok) ctx.fail(L("创建目录失败（可能是权限不足）：%s").format(f.path))\n'
     '        ToolResult(L("已创建目录：%s").format(f.path))'),

    # ---------------------------------------------------------------- copy_path
    ('        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail("缺少 source"), true)\n'
     '        val srcStat = ctx.bridge.stat(src) ?: ctx.fail("源不存在或读不到：${src.path}")\n'
     '        val dstRaw = ctx.args.str("destination") ?: ctx.fail("缺少 destination")',
     '        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail(L("缺少 source")), true)\n'
     '        val srcStat = ctx.bridge.stat(src) ?: ctx.fail(L("源不存在或读不到：%s").format(src.path))\n'
     '        val dstRaw = ctx.args.str("destination") ?: ctx.fail(L("缺少 destination"))'),
    ('        if (dst.path == src.path) ctx.fail("源和目标相同：${src.path}")\n'
     '        if (isInside(src, dst)) ctx.fail("不能把目录复制到它自己的子目录里：${dst.path}")\n'
     '        val dstExists = ctx.bridge.stat(dst) != null\n'
     '        if (dstExists && !ctx.args.boolOr("overwrite", false)) {\n'
     '            ctx.fail("目标已存在（可设置 overwrite=true 覆盖）：${dst.path}")\n'
     '        }',
     '        if (dst.path == src.path) ctx.fail(L("源和目标相同：%s").format(src.path))\n'
     '        if (isInside(src, dst)) ctx.fail(L("不能把目录复制到它自己的子目录里：%s").format(dst.path))\n'
     '        val dstExists = ctx.bridge.stat(dst) != null\n'
     '        if (dstExists && !ctx.args.boolOr("overwrite", false)) {\n'
     '            ctx.fail(L("目标已存在（可设置 overwrite=true 覆盖）：%s").format(dst.path))\n'
     '        }'),
    ('            ctx.fail(e.message ?: "当前模式下不允许写入")\n'
     '        }\n'
     '        if (dstExists) ctx.bridge.delete(dst, recursive = true)\n'
     '        val (ok, how) = ctx.bridge.copy(src, dst)\n'
     '        if (!ok) ctx.fail("复制失败：$how")\n'
     '        val newSize = ctx.bridge.stat(dst)?.size ?: size\n'
     '        ToolResult(\n'
     '            "复制完成\\n源：${src.path}\\n目标：${dst.path}\\n" +\n'
     '                "大小：${ctx.sandbox.humanSize(newSize)}" + if (how != "本地") "（经 $how 转发）" else ""\n'
     '        )',
     '            ctx.fail(e.message ?: L("当前模式下不允许写入"))\n'
     '        }\n'
     '        if (dstExists) ctx.bridge.delete(dst, recursive = true)\n'
     '        val (ok, how) = ctx.bridge.copy(src, dst)\n'
     '        if (!ok) ctx.fail(L("复制失败：%s").format(how))\n'
     '        val newSize = ctx.bridge.stat(dst)?.size ?: size\n'
     '        val local = L("本地")\n'
     '        ToolResult(\n'
     '            L("复制完成\\n源：%s\\n目标：%s\\n大小：%s")\n'
     '                .format(src.path, dst.path, ctx.sandbox.humanSize(newSize)) +\n'
     '                if (how != local) L("（经 %s 转发）").format(how) else ""\n'
     '        )'),

    # ---------------------------------------------------------------- move_path
    ('        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail("缺少 source"), true)\n'
     '        val srcStat = ctx.bridge.stat(src) ?: ctx.fail("源不存在或读不到：${src.path}")\n'
     '        var dst = ctx.sandbox.resolve(ctx.args.str("destination") ?: ctx.fail("缺少 destination"))',
     '        val src = ctx.sandbox.resolve(ctx.args.str("source") ?: ctx.fail(L("缺少 source")), true)\n'
     '        val srcStat = ctx.bridge.stat(src) ?: ctx.fail(L("源不存在或读不到：%s").format(src.path))\n'
     '        var dst = ctx.sandbox.resolve(\n'
     '            ctx.args.str("destination") ?: ctx.fail(L("缺少 destination"))\n'
     '        )'),
    ('        if (dst.path == src.path) ctx.fail("源和目标相同：${src.path}")\n'
     '        if (isInside(src, dst)) ctx.fail("不能把目录移动到它自己的子目录里：${dst.path}")\n'
     '        val dstExists = ctx.bridge.stat(dst) != null\n'
     '        if (dstExists && !ctx.args.boolOr("overwrite", false)) {\n'
     '            ctx.fail("目标已存在（可设置 overwrite=true 覆盖）：${dst.path}")\n'
     '        }',
     '        if (dst.path == src.path) ctx.fail(L("源和目标相同：%s").format(src.path))\n'
     '        if (isInside(src, dst)) ctx.fail(L("不能把目录移动到它自己的子目录里：%s").format(dst.path))\n'
     '        val dstExists = ctx.bridge.stat(dst) != null\n'
     '        if (dstExists && !ctx.args.boolOr("overwrite", false)) {\n'
     '            ctx.fail(L("目标已存在（可设置 overwrite=true 覆盖）：%s").format(dst.path))\n'
     '        }'),
    ('        try {\n'
     '            ctx.sandbox.assertWritable(dst)\n'
     '            ctx.sandbox.assertWritable(src)\n'
     '        } catch (e: Exception) {\n'
     '            ctx.fail(e.message ?: "当前模式下不允许写入")\n'
     '        }\n'
     '        if (dstExists) ctx.bridge.delete(dst, recursive = true)\n'
     '        val (ok, how) = ctx.bridge.move(src, dst)\n'
     '        if (!ok) ctx.fail("移动失败：${src.path} → ${dst.path}（$how）")\n'
     '        ToolResult(\n'
     '            "移动完成\\n源：${src.path}\\n目标：${dst.path}" +\n'
     '                if (how != "本地") "\\n方式：经 $how 转发" else ""\n'
     '        )',
     '        try {\n'
     '            ctx.sandbox.assertWritable(dst)\n'
     '            ctx.sandbox.assertWritable(src)\n'
     '        } catch (e: Exception) {\n'
     '            ctx.fail(e.message ?: L("当前模式下不允许写入"))\n'
     '        }\n'
     '        if (dstExists) ctx.bridge.delete(dst, recursive = true)\n'
     '        val (ok, how) = ctx.bridge.move(src, dst)\n'
     '        if (!ok) ctx.fail(L("移动失败：%s → %s（%s）").format(src.path, dst.path, how))\n'
     '        val local = L("本地")\n'
     '        ToolResult(\n'
     '            L("移动完成\\n源：%s\\n目标：%s").format(src.path, dst.path) +\n'
     '                if (how != local) L("\\n方式：经 %s 转发").format(how) else ""\n'
     '        )'),

    # ---------------------------------------------------------------- delete_path
    ('        val st = ctx.bridge.stat(f) ?: ctx.fail("路径不存在，或者读不到：${f.path}")\n'
     '        val recursive = ctx.args.boolOr("recursive", false)',
     '        val st = ctx.bridge.stat(f) ?: ctx.fail(L("路径不存在，或者读不到：%s").format(f.path))\n'
     '        val recursive = ctx.args.boolOr("recursive", false)'),
    ('                ctx.fail("目录不是空的（$kids 项），确认要删除请设置 recursive=true：${f.path}")',
     '                ctx.fail(\n'
     '                    L("目录不是空的（%s 项），确认要删除请设置 recursive=true：%s")\n'
     '                        .format(kids, f.path)\n'
     '                )'),
    ('            if (!ctx.bridge.delete(f, recursive)) ctx.fail("删除失败：${f.path}（应用没权限，且 root / Shizuku 不可用）")\n'
     '            ToolResult("已彻底删除：${f.path}\\n释放空间：${ctx.sandbox.humanSize(size)}（不可恢复）")\n'
     '        } else {\n'
     '            val entry = ctx.trash.move(f)\n'
     '            ToolResult(\n'
     '                "已移入回收站：${f.path}\\n回收站 ID：${entry.id}（可用 list_trash 查看、restore_trash 还原）"\n'
     '            )\n'
     '        }',
     '            if (!ctx.bridge.delete(f, recursive)) {\n'
     '                ctx.fail(L("删除失败：%s（应用没权限，且 root / Shizuku 不可用）").format(f.path))\n'
     '            }\n'
     '            ToolResult(\n'
     '                L("已彻底删除：%s\\n释放空间：%s（不可恢复）")\n'
     '                    .format(f.path, ctx.sandbox.humanSize(size))\n'
     '            )\n'
     '        } else {\n'
     '            val entry = ctx.trash.move(f)\n'
     '            ToolResult(\n'
     '                L("已移入回收站：%s\\n回收站 ID：%s（可用 list_trash 查看、restore_trash 还原）")\n'
     '                    .format(f.path, entry.id)\n'
     '            )\n'
     '        }'),

    # ---------------------------------------------------------------- list_trash
    ('        sb.append("回收站：").append(ctx.sandbox.trashDir().path).append(\'\\n\')\n'
     '        sb.append("共 ").append(entries.size).append(" 项，占用 ").append(ctx.sandbox.humanSize(total)).append("\\n----\\n")\n'
     '        if (entries.isEmpty()) sb.append("（空）")\n'
     '        entries.sortedByDescending { it.deletedAt }.forEach {\n'
     '            sb.append("[").append(it.id).append("] ").append(if (it.isDir) "目录 " else "文件 ")\n'
     '                .append(it.name).append("  ").append(ctx.sandbox.humanSize(it.size)).append(\'\\n\')\n'
     '            sb.append("      原位置：").append(it.originalPath).append(\'\\n\')\n'
     '            sb.append("      删除时间：").append(ctx.sandbox.timeText(it.deletedAt)).append(\'\\n\')\n'
     '        }',
     '        sb.append(L("回收站：")).append(ctx.sandbox.trashDir().path).append(\'\\n\')\n'
     '        sb.append(\n'
     '            L("共 %s 项，占用 %s\\n----\\n").format(entries.size, ctx.sandbox.humanSize(total))\n'
     '        )\n'
     '        if (entries.isEmpty()) sb.append(L("（空）"))\n'
     '        entries.sortedByDescending { it.deletedAt }.forEach {\n'
     '            sb.append("[").append(it.id).append("] ")\n'
     '                .append(if (it.isDir) L("目录 ") else L("文件 "))\n'
     '                .append(it.name).append("  ").append(ctx.sandbox.humanSize(it.size)).append(\'\\n\')\n'
     '            sb.append(L("      原位置：")).append(it.originalPath).append(\'\\n\')\n'
     '            sb.append(L("      删除时间：")).append(ctx.sandbox.timeText(it.deletedAt)).append(\'\\n\')\n'
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
    print("已写入 ToolsWrite.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

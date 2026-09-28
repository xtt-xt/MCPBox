#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 6 批 i18n：ToolsRead.kt（只读工具的返回正文）。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core/ToolsRead.kt"

PATCHES = [
    # ---------------------------------------------------------------- device_info / 通用报错
    ("ctx.fail(\"当前运行环境拿不到设备信息（桌面端测试模式）\")",
     "ctx.fail(L(\"当前运行环境拿不到设备信息（桌面端测试模式）\"))"),
    ("sb.append(\"设备信息\\n\")",
     "sb.append(L(\"设备信息\\n\"))"),

    # ---------------------------------------------------------------- list_dir
    ("if (dirStat != null && !dirStat.dir) ctx.fail(\"不是目录：${dir.path}\")\n"
     "        if (dirStat == null) ctx.fail(\"目录不存在，或者读不到：${dir.path}\")",
     "if (dirStat != null && !dirStat.dir) ctx.fail(L(\"不是目录：%s\").format(dir.path))\n"
     "        if (dirStat == null) ctx.fail(L(\"目录不存在，或者读不到：%s\").format(dir.path))"),
    ('        sb.append("目录：").append(dir.path)\n'
     '        if (ctx.sandbox.isPrivatePath(dir)) {\n'
     '            sb.append("（应用私有目录，经 ").append(ctx.bridge.privilegedLabel).append(" 读取）")\n'
     '        }\n'
     '        sb.append(\'\\n\')\n'
     '        sb.append("共 ").append(children.size).append(" 项（目录 ")\n'
     '            .append(children.count { it.dir }).append("，文件 ")\n'
     '            .append(children.count { !it.dir }).append("）")\n'
     '        if (children.size > shown.size) sb.append("，仅显示前 ").append(shown.size).append(" 项")\n'
     '        sb.append(\'\\n\')\n'
     '        if (children.isEmpty()) sb.append("（空目录，或者应用没有权限读到内容）\\n")',
     '        sb.append(L("目录：")).append(dir.path)\n'
     '        if (ctx.sandbox.isPrivatePath(dir)) {\n'
     '            sb.append(L("（应用私有目录，经 %s 读取）").format(ctx.bridge.privilegedLabel))\n'
     '        }\n'
     '        sb.append(\'\\n\')\n'
     '        sb.append(\n'
     '            L("共 %s 项（目录 %s，文件 %s）").format(\n'
     '                children.size,\n'
     '                children.count { it.dir },\n'
     '                children.count { !it.dir }\n'
     '            )\n'
     '        )\n'
     '        if (children.size > shown.size) sb.append(L("，仅显示前 %s 项").format(shown.size))\n'
     '        sb.append(\'\\n\')\n'
     '        if (children.isEmpty()) sb.append(L("（空目录，或者应用没有权限读到内容）\\n"))'),

    # ---------------------------------------------------------------- tree
    ("if (!root.isDirectory) ctx.fail(\"不是目录：${root.path}\")",
     "if (!root.isDirectory) ctx.fail(L(\"不是目录：%s\").format(root.path))"),
    ('        if (truncated) sb.append("... 条目过多，已截断（可用 maxEntries 调整）\\n")\n'
     '        sb.append("共 ").append(count).append(" 项")',
     '        if (truncated) sb.append(L("... 条目过多，已截断（可用 maxEntries 调整）\\n"))\n'
     '        sb.append(L("共 %s 项").format(count))'),

    # ---------------------------------------------------------------- file_info
    ("            ?: ctx.fail(\"路径不存在，或者读不到：${f.path}\")\n"
     "        ctx.guard(PermKey.READ, f, L(\"查看信息 %s\").format(f.path))",
     "            ?: ctx.fail(L(\"路径不存在，或者读不到：%s\").format(f.path))\n"
     "        ctx.guard(PermKey.READ, f, L(\"查看信息 %s\").format(f.path))"),
    ('        sb.append("路径：").append(f.path).append(\'\\n\')\n'
     '        sb.append("名称：").append(f.name).append(\'\\n\')\n'
     '        sb.append("类型：").append(if (st.dir) "目录" else "文件").append(\'\\n\')\n'
     '        sb.append("大小：").append(ctx.sandbox.humanSize(st.size)).append(" (").append(st.size).append(" 字节)\\n")\n'
     '        sb.append("修改时间：").append(ctx.sandbox.timeText(st.modified)).append(\'\\n\')\n'
     '        f.parentFile?.let { sb.append("所在目录：").append(it.path).append(\'\\n\') }\n'
     '        if (st.bridged) {\n'
     '            sb.append("读取方式：经 ").append(ctx.bridge.privilegedLabel).append(" 转发（应用自己没权限）\\n")\n'
     '        }\n'
     '        sb.append("可读：").append(if (st.bridged) "是（通过 shell）" else f.canRead())\n'
     '            .append("  可写：").append(if (st.bridged) "是（通过 shell）" else f.canWrite()).append(\'\\n\')\n'
     '        if (st.dir) {\n'
     '            val kids = ctx.bridge.listDir(f).orEmpty()\n'
     '            sb.append("子项：").append(kids.size).append(" 个（目录 ").append(kids.count { it.dir })\n'
     '                .append("，文件 ").append(kids.count { !it.dir }).append("）\\n")\n'
     '        } else {\n'
     '            sb.append("扩展名：").append(f.extension.ifBlank { "（无）" }).append(\'\\n\')\n'
     '            val mime = IMAGE_EXT[f.extension.lowercase()]\n'
     '            if (mime != null) sb.append("图片类型：").append(mime).append("（可用 read_image 直接查看）\\n")\n'
     '        }\n'
     '        runCatching { sb.append("可用空间：").append(ctx.sandbox.humanSize(f.absoluteFile.usableSpace)) }',
     '        sb.append(L("路径：")).append(f.path).append(\'\\n\')\n'
     '        sb.append(L("名称：")).append(f.name).append(\'\\n\')\n'
     '        sb.append(L("类型：")).append(if (st.dir) L("目录") else L("文件")).append(\'\\n\')\n'
     '        sb.append(L("大小：")).append(ctx.sandbox.humanSize(st.size))\n'
     '            .append(L(" (%s 字节)\\n").format(st.size))\n'
     '        sb.append(L("修改时间：")).append(ctx.sandbox.timeText(st.modified)).append(\'\\n\')\n'
     '        f.parentFile?.let { sb.append(L("所在目录：")).append(it.path).append(\'\\n\') }\n'
     '        if (st.bridged) {\n'
     '            sb.append(L("读取方式：经 %s 转发（应用自己没权限）\\n").format(ctx.bridge.privilegedLabel))\n'
     '        }\n'
     '        val yes = L("是（通过 shell）")\n'
     '        sb.append(L("可读：")).append(if (st.bridged) yes else f.canRead())\n'
     '            .append(L("  可写：")).append(if (st.bridged) yes else f.canWrite()).append(\'\\n\')\n'
     '        if (st.dir) {\n'
     '            val kids = ctx.bridge.listDir(f).orEmpty()\n'
     '            sb.append(\n'
     '                L("子项：%s 个（目录 %s，文件 %s）\\n").format(\n'
     '                    kids.size, kids.count { it.dir }, kids.count { !it.dir }\n'
     '                )\n'
     '            )\n'
     '        } else {\n'
     '            sb.append(L("扩展名：")).append(f.extension.ifBlank { L("（无）") }).append(\'\\n\')\n'
     '            val mime = IMAGE_EXT[f.extension.lowercase()]\n'
     '            if (mime != null) sb.append(L("图片类型：%s（可用 read_image 直接查看）\\n").format(mime))\n'
     '        }\n'
     '        runCatching { sb.append(L("可用空间：")).append(ctx.sandbox.humanSize(f.absoluteFile.usableSpace)) }'),

    # ---------------------------------------------------------------- read_file
    ('            ?: ctx.fail("文件不存在，或者读不到：${f.path}\\n（私有目录要在 设置 → 权限 → 应用私有目录 里开放，并且需要 root 或 Shizuku）")\n'
     '        if (fStat.dir) ctx.fail("这是目录，请用 list_dir：${f.path}")',
     '            ?: ctx.fail(\n'
     '                L("文件不存在，或者读不到：%s\\n(私有目录要在 设置 → 权限 → 应用私有目录 里开放，并且需要 root 或 Shizuku)").format(f.path)\n'
     '            )\n'
     '        if (fStat.dir) ctx.fail(L("这是目录，请用 list_dir：%s").format(f.path))'),
    ('            ?: ctx.fail("读不出来：应用自己没权限，而且没有可用的 root / Shizuku")\n'
     '        if (looksBinary(bytes)) {\n'
     '            ctx.fail("这是二进制文件（含空字节），无法按文本读取：${f.name}\\n如果是图片请用 read_image；其他二进制可用 file_info 查看大小。")\n'
     '        }',
     '            ?: ctx.fail(L("读不出来：应用自己没权限，而且没有可用的 root / Shizuku"))\n'
     '        if (looksBinary(bytes)) {\n'
     '            ctx.fail(\n'
     '                L("这是二进制文件（含空字节），无法按文本读取：%s\\n如果是图片请用 read_image；其他二进制可用 file_info 查看大小。")\n'
     '                    .format(f.name)\n'
     '            )\n'
     '        }'),
    ('        sb.append("文件：").append(f.path).append(\'\\n\')\n'
     '        sb.append("总行数：").append(lines.size)\n'
     '        if (fStat.size > bytes.size) sb.append("（文件较大，本次只读取了前 ").append(ctx.sandbox.humanSize(bytes.size.toLong())).append("）")\n'
     '        sb.append("，显示第 ").append(startLine).append(" ~ ").append(end).append(" 行\\n")\n'
     '        sb.append("----\\n")',
     '        sb.append(L("文件：")).append(f.path).append(\'\\n\')\n'
     '        sb.append(L("总行数：")).append(lines.size)\n'
     '        if (fStat.size > bytes.size) {\n'
     '            sb.append(L("（文件较大，本次只读取了前 %s）").format(ctx.sandbox.humanSize(bytes.size.toLong())))\n'
     '        }\n'
     '        sb.append(L("，显示第 %s ~ %s 行\\n").format(startLine, end))\n'
     '        sb.append("----\\n")'),
    ('        if (end < lines.size) sb.append("... 还有 ").append(lines.size - end).append(" 行（可加大 lineCount 或调整 startLine）\\n")',
     '        if (end < lines.size) {\n'
     '            sb.append(L("... 还有 %s 行（可加大 lineCount 或调整 startLine）\\n").format(lines.size - end))\n'
     '        }'),

    # ---------------------------------------------------------------- read_image
    ('        val st = ctx.bridge.stat(f) ?: ctx.fail("图片不存在，或者读不到：${f.path}")\n'
     '        if (st.dir) ctx.fail("这是目录：${f.path}")\n'
     '        val ext = f.extension.lowercase()\n'
     '        val mime = IMAGE_EXT[ext] ?: ctx.fail("不支持的图片格式：.${f.extension}（支持 png/jpg/jpeg/gif/webp/bmp）")\n'
     '        val maxBytes = ctx.args.intOr("maxBytes", 8_000_000)\n'
     '        if (st.size > maxBytes) ctx.fail("图片过大：${ctx.sandbox.humanSize(st.size)}，超过上限")',
     '        val st = ctx.bridge.stat(f) ?: ctx.fail(L("图片不存在，或者读不到：%s").format(f.path))\n'
     '        if (st.dir) ctx.fail(L("这是目录：%s").format(f.path))\n'
     '        val ext = f.extension.lowercase()\n'
     '        val mime = IMAGE_EXT[ext]\n'
     '            ?: ctx.fail(L("不支持的图片格式：.%s（支持 png/jpg/jpeg/gif/webp/bmp）").format(f.extension))\n'
     '        val maxBytes = ctx.args.intOr("maxBytes", 8_000_000)\n'
     '        if (st.size > maxBytes) {\n'
     '            ctx.fail(L("图片过大：%s，超过上限").format(ctx.sandbox.humanSize(st.size)))\n'
     '        }'),
    ('            ?: ctx.fail("读不出来：应用自己没权限，而且没有可用的 root / Shizuku")\n'
     '        val b64 = java.util.Base64.getEncoder().encodeToString(raw)\n'
     '        ToolResult(\n'
     '            text = "图片：${f.path}\\n大小：${ctx.sandbox.humanSize(st.size)}（$mime）",',
     '            ?: ctx.fail(L("读不出来：应用自己没权限，而且没有可用的 root / Shizuku"))\n'
     '        val b64 = java.util.Base64.getEncoder().encodeToString(raw)\n'
     '        ToolResult(\n'
     '            text = L("图片：%s\\n大小：%s（%s）")\n'
     '                .format(f.path, ctx.sandbox.humanSize(st.size), mime),'),

    # ---------------------------------------------------------------- search_files
    ('        if (!root.isDirectory) ctx.fail("搜索起点必须是目录：${root.path}")',
     '        if (!root.isDirectory) ctx.fail(L("搜索起点必须是目录：%s").format(root.path))'),
    ('        if (namePattern == null && contentPattern == null) ctx.fail("至少要提供 name 或 content 之一")',
     '        if (namePattern == null && contentPattern == null) ctx.fail(L("至少要提供 name 或 content 之一"))'),
    ('                .getOrElse { e -> ctx.fail("内容正则不合法：${e.message}") }',
     '                .getOrElse { e -> ctx.fail(L("内容正则不合法：%s").format(e.message)) }'),
    ('        sb.append("搜索目录：").append(root.path).append(\'\\n\')\n'
     '        if (namePattern != null) sb.append("文件名条件：").append(namePattern).append(\'\\n\')\n'
     '        if (contentPattern != null) sb.append("内容条件：").append(contentPattern).append(\'\\n\')\n'
     '        sb.append("扫描条目：").append(scanned).append("，命中：").append(hits.size)\n'
     '        if (truncated) sb.append("（已达上限，结果可能不完整）")\n'
     '        sb.append("\\n----\\n")\n'
     '        if (hits.isEmpty()) sb.append("没有找到匹配项") else sb.append(hits.joinToString("\\n"))',
     '        sb.append(L("搜索目录：")).append(root.path).append(\'\\n\')\n'
     '        if (namePattern != null) sb.append(L("文件名条件：")).append(namePattern).append(\'\\n\')\n'
     '        if (contentPattern != null) sb.append(L("内容条件：")).append(contentPattern).append(\'\\n\')\n'
     '        sb.append(L("扫描条目：%s，命中：%s").format(scanned, hits.size))\n'
     '        if (truncated) sb.append(L("（已达上限，结果可能不完整）"))\n'
     '        sb.append("\\n----\\n")\n'
     '        if (hits.isEmpty()) sb.append(L("没有找到匹配项")) else sb.append(hits.joinToString("\\n"))'),

    # ---------------------------------------------------------------- hash
    ('        if (f.isDirectory) ctx.fail("目录不支持计算校验值：${f.path}")',
     '        if (f.isDirectory) ctx.fail(L("目录不支持计算校验值：%s").format(f.path))'),
    ('        ToolResult("文件：${f.path}\\n算法：${algo.uppercase()}\\n校验值：$hex")',
     '        ToolResult(L("文件：%s\\n算法：%s\\n校验值：%s").format(f.path, algo.uppercase(), hex))'),

    # ---------------------------------------------------------------- storage_info
    ('        sb.append("允许访问的根目录（").append(ctx.config.roots.size).append(" 个）：\\n")',
     '        sb.append(L("允许访问的根目录（%s 个）：\\n").format(ctx.config.roots.size))'),
    ('                .append(if (f.exists()) "" else "（不存在）")\n'
     '                .append(\'\\n\')\n'
     '        }\n'
     '        sb.append("不限制目录：").append(if (ctx.config.fullAccess) "是" else "否").append(\'\\n\')\n'
     '        val roots = (ctx.config.roots.map { File(it) } + File("/")).distinctBy { runCatching { it.absolutePath }.getOrNull() }\n'
     '        sb.append("\\n存储分区：\\n")\n'
     '        roots.forEach { f ->\n'
     '            runCatching {\n'
     '                sb.append("  ").append(f.absolutePath).append("：总 ")\n'
     '                    .append(ctx.sandbox.humanSize(f.totalSpace))\n'
     '                    .append("，已用 ").append(ctx.sandbox.humanSize(f.totalSpace - f.freeSpace))\n'
     '                    .append("，可用 ").append(ctx.sandbox.humanSize(f.usableSpace)).append(\'\\n\')\n'
     '            }\n'
     '        }',
     '                .append(if (f.exists()) "" else L("（不存在）"))\n'
     '                .append(\'\\n\')\n'
     '        }\n'
     '        sb.append(L("不限制目录：")).append(if (ctx.config.fullAccess) L("是") else L("否")).append(\'\\n\')\n'
     '        val roots = (ctx.config.roots.map { File(it) } + File("/")).distinctBy { runCatching { it.absolutePath }.getOrNull() }\n'
     '        sb.append(L("\\n存储分区：\\n"))\n'
     '        roots.forEach { f ->\n'
     '            runCatching {\n'
     '                sb.append("  ").append(f.absolutePath)\n'
     '                    .append(L("：总 %s，已用 %s，可用 %s\\n").format(\n'
     '                        ctx.sandbox.humanSize(f.totalSpace),\n'
     '                        ctx.sandbox.humanSize(f.totalSpace - f.freeSpace),\n'
     '                        ctx.sandbox.humanSize(f.usableSpace)\n'
     '                    ))\n'
     '            }\n'
     '        }'),

    # ---------------------------------------------------------------- server_info
    ('        sb.append("MCP 手机文件服务器\\n")\n'
     '        sb.append("版本：").append(ServerMeta.fullVersion).append(\'（\').append(ServerMeta.NAME).append(\'）\').append(\'\\n\')\n'
     '        sb.append("设备：").append(ServerMeta.deviceLabel).append(\'\\n\')\n'
     '        sb.append("端口：").append(ctx.config.port).append(\'\\n\')\n'
     '        sb.append("运行时间：").append(ServerMeta.uptimeText()).append(\'\\n\')\n'
     '        sb.append("累计请求：").append(ctx.log.stats().total)\n'
     '            .append("（允许 ").append(ctx.log.stats().ok)\n'
     '            .append(" / 失败 ").append(ctx.log.stats().failed)\n'
     '            .append(" / 审批 ").append(ctx.log.stats().approvals)\n'
     '            .append(" / 拒绝 ").append(ctx.log.stats().denied).append("）\\n")\n'
     '        sb.append("\\n根目录：\\n")\n'
     '        ctx.config.roots.forEach { sb.append("  ").append(it).append(\'\\n\') }\n'
     '        sb.append("不限制目录：").append(if (ctx.config.fullAccess) "是" else "否").append(\'\\n\')\n'
     '        sb.append("只读模式：").append(if (ctx.config.readOnly) "已开启（所有写/删会被拒绝）" else "关闭").append(\'\\n\')\n'
     '        sb.append("回收站：").append(if (ctx.config.trashEnabled) "开启（删除会先进回收站）" else "关闭（删除即彻底删除）").append(\'\\n\')\n'
     '        sb.append("\\n权限开关：\\n")\n'
     '        PermKey.entries.forEach { k ->\n'
     '            val action = ctx.permissions.decide(k, null).action\n'
     '            sb.append("  ").append(k.title).append("（").append(k.id).append("）：").append(action.label)\n'
     '                .append(when (action) {\n'
     '                    PermAction.ASK -> "（每次操作会弹出审批窗口）"\n'
     '                    PermAction.DENY -> "（相关操作会被直接拒绝）"\n'
     '                    PermAction.ALLOW -> ""\n'
     '                }).append(\'\\n\')\n'
     '        }\n'
     '        if (ctx.permissions.pathRules().isNotEmpty()) {\n'
     '            sb.append("\\n路径规则：\\n")\n'
     '            ctx.permissions.pathRules().forEach {\n'
     '                sb.append("  ").append(if (it.perm == "*") "全部权限" else it.perm)\n'
     '                    .append(" @ ").append(it.path.ifBlank { "（未填）" })\n'
     '                    .append(" → ").append(it.actionEnum.label).append(\'\\n\')\n'
     '            }\n'
     '        }\n'
     '        sb.append("\\n提示：涉及写入/删除的操作会实时弹窗询问手机主人，被拒绝时请勿反复重试。")',
     '        sb.append(L("MCP 手机文件服务器\\n"))\n'
     '        sb.append(L("版本：")).append(ServerMeta.fullVersion).append(\'（\').append(ServerMeta.NAME).append(\'）\').append(\'\\n\')\n'
     '        sb.append(L("设备：")).append(ServerMeta.deviceLabel).append(\'\\n\')\n'
     '        sb.append(L("端口：")).append(ctx.config.port).append(\'\\n\')\n'
     '        sb.append(L("运行时间：")).append(ServerMeta.uptimeText()).append(\'\\n\')\n'
     '        val st = ctx.log.stats()\n'
     '        sb.append(L("累计请求：")).append(st.total)\n'
     '            .append(L("（允许 %s / 失败 %s / 审批 %s / 拒绝 %s）\\n")\n'
     '                .format(st.ok, st.failed, st.approvals, st.denied))\n'
     '        sb.append(L("\\n根目录：\\n"))\n'
     '        ctx.config.roots.forEach { sb.append("  ").append(it).append(\'\\n\') }\n'
     '        sb.append(L("不限制目录：")).append(if (ctx.config.fullAccess) L("是") else L("否")).append(\'\\n\')\n'
     '        sb.append(L("只读模式："))\n'
     '            .append(if (ctx.config.readOnly) L("已开启（所有写/删会被拒绝）") else L("关闭")).append(\'\\n\')\n'
     '        sb.append(L("回收站："))\n'
     '            .append(\n'
     '                if (ctx.config.trashEnabled) L("开启（删除会先进回收站）")\n'
     '                else L("关闭（删除即彻底删除）")\n'
     '            ).append(\'\\n\')\n'
     '        sb.append(L("\\n权限开关：\\n"))\n'
     '        PermKey.entries.forEach { k ->\n'
     '            val action = ctx.permissions.decide(k, null).action\n'
     '            sb.append("  ").append(L(k.title)).append("（").append(k.id).append("）：")\n'
     '                .append(L(action.label))\n'
     '                .append(when (action) {\n'
     '                    PermAction.ASK -> L("（每次操作会弹出审批窗口）")\n'
     '                    PermAction.DENY -> L("（相关操作会被直接拒绝）")\n'
     '                    PermAction.ALLOW -> ""\n'
     '                }).append(\'\\n\')\n'
     '        }\n'
     '        if (ctx.permissions.pathRules().isNotEmpty()) {\n'
     '            sb.append(L("\\n路径规则：\\n"))\n'
     '            ctx.permissions.pathRules().forEach {\n'
     '                sb.append("  ").append(if (it.perm == "*") L("全部权限") else it.perm)\n'
     '                    .append(" @ ").append(it.path.ifBlank { L("（未填）") })\n'
     '                    .append(" → ").append(L(it.actionEnum.label)).append(\'\\n\')\n'
     '            }\n'
     '        }\n'
     '        sb.append(L("\\n提示：涉及写入/删除的操作会实时弹窗询问手机主人，被拒绝时请勿反复重试。"))'),
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
    print("已写入 ToolsRead.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

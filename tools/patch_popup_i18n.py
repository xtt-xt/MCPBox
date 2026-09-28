#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
第 3 批 i18n：把「审批弹窗」这条链路的文案接上翻译。

弹窗里显示的 summary / detail 是在**工具执行时**拼出来的（mcpcore 侧），
之前只翻了权限名和按钮，所以会出现「Control screen」配着「读取界面结构」这种半截英文。

规则同前：拼句一律改成 `L("...%s...").format(...)`，纯短句直接包 `L()`。
每条替换都带存在性校验，对不上就报错退出，避免静默改错。
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"
UI = "app/src/main/java/com/xtt/mcpbox/ui"

PATCHES = [
    # ---------------------------------------------------------------- 悬浮窗渲染
    (f"{UI}/OverlayApproval.kt",
     "            textView(request.summary, 16f, p.text, bold = true)",
     "            textView(L(request.summary), 16f, p.text, bold = true)"),
    (f"{UI}/OverlayApproval.kt",
     '                card.addView(textView(shown, 12f, p.textDim).apply { setPadding(0, dp(6), 0, 0) })',
     '                card.addView(textView(L(shown), 12f, p.textDim).apply { setPadding(0, dp(6), 0, 0) })'),

    # ---------------------------------------------------------------- 审批中心：detail 拼句
    (f"{CORE}/Approval.kt",
     "            append(\"来源：\").append(source)",
     "            append(L(\"来源：%s\").format(source))"),
    (f"{CORE}/Approval.kt",
     "                append(\"\\n选「始终允许 / 始终拒绝」会把「\").append(tool).append(\"」这个工具本身设为允许 / 拒绝。\")",
     "                append(L(\"\\n选「始终允许 / 始终拒绝」会把「%s」这个工具本身设为允许 / 拒绝。\").format(tool))"),
    (f"{CORE}/Approval.kt",
     "                append(\"\\n选「始终允许 / 始终拒绝」会记住这条规则：以 \")\n                append('`').append(prefix).append(\"` 开头的命令\")",
     "                append(L(\"\\n选「始终允许 / 始终拒绝」会记住这条规则：以 `%s` 开头的命令\").format(prefix))"),
    (f"{CORE}/Approval.kt",
     '            message = "等待用户审批：$summary"',
     '            message = L("等待用户审批：%s").format(summary)'),
    (f"{CORE}/Approval.kt",
     "                    message = \"工具「$tool」已被单独设为禁止\"",
     "                    message = L(\"工具「%s」已被单独设为禁止\").format(tool)"),
    (f"{CORE}/Approval.kt",
     "                    \"工具「$tool」已被单独设为「禁止」（可在 App 的「工具管理」里改回来）\"",
     "                    L(\"工具「%s」已被单独设为「禁止」（可在 App 的「工具管理」里改回来）\").format(tool)"),
    (f"{CORE}/Approval.kt",
     "                    \"工具「$tool」被单独设为「询问」\", mediaType, byteSize, command, backend,",
     "                    L(\"工具「%s」被单独设为「询问」\").format(tool), mediaType, byteSize, command, backend,"),
    (f"{CORE}/Approval.kt",
     "                    message = \"已拒绝（${decision.source}）\" + (command?.let { \"：$it\" } ?: \"\")",
     "                    message = L(\"已拒绝（%s）\").format(decision.source) + (command?.let { \"：$it\" } ?: \"\")"),
    (f"{CORE}/Approval.kt",
     "                throw PermissionDeniedException(\"权限「${perm.title}」被拒绝（${decision.source}）\")",
     "                throw PermissionDeniedException(L(\"权限「%s」被拒绝（%s）\").format(L(perm.title), decision.source))"),

    # ---------------------------------------------------------------- ToolsRead
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.SYSTEM, null, "查看设备信息")',
     'ctx.guard(PermKey.SYSTEM, null, L("查看设备信息"))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, dir, "列出目录 ${dir.path}")',
     'ctx.guard(PermKey.READ, dir, L("列出目录 %s").format(dir.path))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, root, "查看目录树 ${root.path}")',
     'ctx.guard(PermKey.READ, root, L("查看目录树 %s").format(root.path))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, f, "查看信息 ${f.path}")',
     'ctx.guard(PermKey.READ, f, L("查看信息 %s").format(f.path))'),
    (f"{CORE}/ToolsRead.kt",
     '            PermKey.READ, f, "读取文件 ${f.name}",\n            "大小 " + ctx.sandbox.humanSize(fStat.size), fStat.size',
     '            PermKey.READ, f, L("读取文件 %s").format(f.name),\n            L("大小 %s").format(ctx.sandbox.humanSize(fStat.size)), fStat.size'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, f, "查看图片 ${f.name}", ctx.sandbox.humanSize(st.size), st.size, mime)',
     'ctx.guard(PermKey.READ, f, L("查看图片 %s").format(f.name), ctx.sandbox.humanSize(st.size), st.size, mime)'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, root, "搜索目录 ${root.path}")',
     'ctx.guard(PermKey.READ, root, L("搜索目录 %s").format(root.path))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.READ, f, "计算校验值 ${f.name}")',
     'ctx.guard(PermKey.READ, f, L("计算校验值 %s").format(f.name))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.SYSTEM, null, "查看存储空间")',
     'ctx.guard(PermKey.SYSTEM, null, L("查看存储空间"))'),
    (f"{CORE}/ToolsRead.kt",
     'ctx.guard(PermKey.SYSTEM, null, "查看服务器信息")',
     'ctx.guard(PermKey.SYSTEM, null, L("查看服务器信息"))'),

    # ---------------------------------------------------------------- ToolsWrite
    (f"{CORE}/ToolsWrite.kt",
     '            "$verb 文件 ${f.name}（${ctx.sandbox.humanSize(bytes.size.toLong())}）",\n            "目标：${f.path}\\n内容预览：${preview(content)}",',
     '            L("%s 文件 %s（%s）").format(verb, f.name, ctx.sandbox.humanSize(bytes.size.toLong())),\n            L("目标：%s\\n内容预览：%s").format(f.path, preview(content)),'),
    (f"{CORE}/ToolsWrite.kt",
     '            "修改文件 ${f.name}（替换 $willReplace 处）",\n            "文件：${f.path}\\n匹配到 $occurrences 处，将替换 $willReplace 处",',
     '            L("修改文件 %s（替换 %s 处）").format(f.name, willReplace),\n            L("文件：%s\\n匹配到 %s 处，将替换 %s 处").format(f.path, occurrences, willReplace),'),
    (f"{CORE}/ToolsWrite.kt",
     'ctx.guard(PermKey.WRITE, f, "新建目录 ${f.path}")',
     'ctx.guard(PermKey.WRITE, f, L("新建目录 %s").format(f.path))'),
    (f"{CORE}/ToolsWrite.kt",
     '            "复制 ${src.name} → ${dst.path}",\n            "源：${src.path}\\n目标：${dst.path}\\n大小：${ctx.sandbox.humanSize(size)}",',
     '            L("复制 %s → %s").format(src.name, dst.path),\n            L("源：%s\\n目标：%s\\n大小：%s").format(src.path, dst.path, ctx.sandbox.humanSize(size)),'),
    (f"{CORE}/ToolsWrite.kt",
     '            "移动 ${src.name} → ${dst.path}",\n            "源：${src.path}\\n目标：${dst.path}",',
     '            L("移动 %s → %s").format(src.name, dst.path),\n            L("源：%s\\n目标：%s").format(src.path, dst.path),'),
    (f"{CORE}/ToolsWrite.kt",
     '            "删除 ${if (st.dir) "目录" else "文件"} ${f.name}",\n            "路径：${f.path}\\n大小：${ctx.sandbox.humanSize(size)}\\n方式：" +\n                if (permanent) {\n                    "彻底删除" + if (isPrivate) "（私有目录不进回收站）" else ""\n                } else {\n                    "移动到回收站"\n                },',
     '            L("删除 %s %s").format(if (st.dir) L("目录") else L("文件"), f.name),\n            "路径：${f.path}\\n大小：${ctx.sandbox.humanSize(size)}\\n方式：" +\n                if (permanent) {\n                    L("彻底删除") + if (isPrivate) L("（私有目录不进回收站）") else ""\n                } else {\n                    L("移动到回收站")\n                },'),
    (f"{CORE}/ToolsWrite.kt",
     '            "还原回收站条目 ${entry.name}",\n            "原位置：${entry.originalPath}\\n还原到：${dst?.path ?: entry.originalPath}",',
     '            L("还原回收站条目 %s").format(entry.name),\n            L("原位置：%s\\n还原到：%s").format(entry.originalPath, dst?.path ?: entry.originalPath),'),
    (f"{CORE}/ToolsWrite.kt",
     '            "清空回收站（${entries.size} 项，${ctx.sandbox.humanSize(size)}）",\n            "将彻底删除，无法恢复",',
     '            L("清空回收站（%s 项，%s）").format(entries.size, ctx.sandbox.humanSize(size)),\n            L("将彻底删除，无法恢复"),'),
    (f"{CORE}/ToolsWrite.kt",
     'ctx.guard(PermKey.SYSTEM, null, "发送通知给用户")',
     'ctx.guard(PermKey.SYSTEM, null, L("发送通知给用户"))'),

    # ---------------------------------------------------------------- ToolsShell
    (f"{CORE}/ToolsShell.kt",
     '            summary = if (forCustom) "$toolLabel：$command" else "执行命令：$command",\n            detail = buildString {\n                append("后端：").append(launcher.label).append("（").append(launcher.uidLabel).append("）")\n                if (workdir != null) append("\\n工作目录：").append(workdir)\n                append("\\n超时：").append(timeoutMs / 1000).append(" 秒")\n            },',
     '            summary = if (forCustom) L("%s：%s").format(toolLabel, command) else L("执行命令：%s").format(command),\n            detail = buildString {\n                append(L("后端：%s（%s）").format(L(launcher.label), launcher.uidLabel))\n                if (workdir != null) append(L("\\n工作目录：%s").format(workdir))\n                append(L("\\n超时：%s 秒").format(timeoutMs / 1000))\n            },'),
    (f"{CORE}/ToolsShell.kt",
     'ctx.guard(PermKey.SYSTEM, null, "查看 Shell 环境")',
     'ctx.guard(PermKey.SYSTEM, null, L("查看 Shell 环境"))'),
    (f"{CORE}/ToolsShell.kt",
     'ctx.guard(PermKey.TOOLS, null, "创建自定义工具 ${ctx.args.str("name") ?: "?"}")',
     'ctx.guard(PermKey.TOOLS, null, L("创建自定义工具 %s").format(ctx.args.str("name") ?: "?"))'),
    (f"{CORE}/ToolsShell.kt",
     'ctx.guard(PermKey.TOOLS, null, "修改自定义工具 $name")',
     'ctx.guard(PermKey.TOOLS, null, L("修改自定义工具 %s").format(name))'),
    (f"{CORE}/ToolsShell.kt",
     'ctx.guard(PermKey.TOOLS, null, "删除自定义工具 $name")',
     'ctx.guard(PermKey.TOOLS, null, L("删除自定义工具 %s").format(name))'),
    (f"{CORE}/ToolsShell.kt",
     'ctx.guard(PermKey.SYSTEM, null, "查看自定义工具")',
     'ctx.guard(PermKey.SYSTEM, null, L("查看自定义工具"))'),
    (f"{CORE}/ToolsShell.kt",
     '            "导出自定义工具（${store.tools.size} 个）到 ${file.name}",',
     '            L("导出自定义工具（%s 个）到 %s").format(store.tools.size, file.name),'),
    (f"{CORE}/ToolsShell.kt",
     '            "导入自定义工具（${file.name}）",\n            "文件：${file.path}\\n大小：${ctx.sandbox.humanSize(file.length())}",',
     '            L("导入自定义工具（%s）").format(file.name),\n            L("文件：%s\\n大小：%s").format(file.path, ctx.sandbox.humanSize(file.length())),'),

    # ---------------------------------------------------------------- ToolsMemory
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：新建实体")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：新建实体"))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：建立关系")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：建立关系"))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：追加观察")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：追加观察"))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：读取记忆库")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：读取记忆库"))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：搜索「$query」")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：搜索「%s」").format(query))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：读取 ${names.size} 个实体")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：读取 %s 个实体").format(names.size))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "查看记忆库统计")',
     'ctx.guard(PermKey.MEMORY, null, L("查看记忆库统计"))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：删除实体 " + names.joinToString("、"))',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：删除实体 %s").format(names.joinToString(L("、"))))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：删除 ${items.size} 条关系")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：删除 %s 条关系").format(items.size))'),
    (f"{CORE}/ToolsMemory.kt",
     'ctx.guard(PermKey.MEMORY, null, "记忆：删除观察")',
     'ctx.guard(PermKey.MEMORY, null, L("记忆：删除观察"))'),

    # ---------------------------------------------------------------- ToolsPacks
    (f"{CORE}/ToolsPacks.kt",
     'ctx.guard(PermKey.SYSTEM, null, "列出工具包")',
     'ctx.guard(PermKey.SYSTEM, null, L("列出工具包"))'),
    (f"{CORE}/ToolsPacks.kt",
     'ctx.guard(PermKey.SYSTEM, null, "激活工具包 $id")',
     'ctx.guard(PermKey.SYSTEM, null, L("激活工具包 %s").format(id))'),
    (f"{CORE}/ToolsPacks.kt",
     'ctx.guard(PermKey.SYSTEM, null, "停用工具包 $id")',
     'ctx.guard(PermKey.SYSTEM, null, L("停用工具包 %s").format(id))'),
    (f"{CORE}/ToolsPacks.kt",
     'ctx.guard(PermKey.SYSTEM, null, "重置工具包")',
     'ctx.guard(PermKey.SYSTEM, null, L("重置工具包"))'),
    (f"{CORE}/ToolsPacks.kt",
     'ctx.guard(PermKey.TOOLS, null, ("管理工具包：$action $id").trim())',
     'ctx.guard(PermKey.TOOLS, null, L("管理工具包：%s %s").format(action, id).trim())'),

    # ---------------------------------------------------------------- ToolsUi
    (f"{CORE}/ToolsUi.kt",
     '                append("后端：").append(launcher.label).append("（").append(launcher.uidLabel).append("）")\n                if (!detail.isNullOrBlank()) append(\'\\n\').append(detail)',
     '                append(L("后端：%s（%s）").format(L(launcher.label), launcher.uidLabel))\n                if (!detail.isNullOrBlank()) append(\'\\n\').append(detail)'),
    (f"{CORE}/ToolsUi.kt",
     'ctx.guard(PermKey.UI, null, "读取界面结构", "后端：${launcher.label}")',
     'ctx.guard(PermKey.UI, null, L("读取界面结构"), L("后端：%s").format(L(launcher.label)))'),
    (f"{CORE}/ToolsUi.kt",
     'ctx.guard(PermKey.UI, null, "查看前台应用", "后端：${launcher.label}")',
     'ctx.guard(PermKey.UI, null, L("查看前台应用"), L("后端：%s").format(L(launcher.label)))'),
    (f"{CORE}/ToolsUi.kt",
     'ctx.guard(PermKey.UI, null, "列出第三方应用", "后端：${launcher.label}")',
     'ctx.guard(PermKey.UI, null, L("列出第三方应用"), L("后端：%s").format(L(launcher.label)))'),
    (f"{CORE}/ToolsUi.kt",
     'ctx.guard(PermKey.UI, null, "等待「$what」${if (disappear) "消失" else "出现"}", "后端：${launcher.label}")',
     'ctx.guard(\n            PermKey.UI, null,\n            L("等待「%s」%s").format(what, if (disappear) L("消失") else L("出现")),\n            L("后端：%s").format(L(launcher.label))\n        )'),

    # ---------------------------------------------------------------- ToolsToken
    (f"{CORE}/ToolsToken.kt",
     'ctx.guard(PermKey.SYSTEM, null, "获取访问令牌")',
     'ctx.guard(PermKey.SYSTEM, null, L("获取访问令牌"))'),

    # ---------------------------------------------------------------- FileGateway（网页上传 / 下载）
    (f"{CORE}/FileGateway.kt",
     '                summary = "网页 / HTTP 上传：${file.name}（${sandbox.humanSize(payload.size.toLong())}）",\n                detail = "写入位置：${file.path}" +\n                    if (append) "\\n（追加模式）" else "" +\n                    if (sandbox.isPrivatePath(file)) "\\n（应用私有目录，经 ${bridge.privilegedLabel} 写入）" else "",',
     '                summary = L("网页 / HTTP 上传：%s（%s）").format(file.name, sandbox.humanSize(payload.size.toLong())),\n                detail = L("写入位置：%s").format(file.path) +\n                    if (append) L("\\n（追加模式）") else "" +\n                    if (sandbox.isPrivatePath(file)) L("\\n（应用私有目录，经 %s 写入）").format(bridge.privilegedLabel) else "",'),
    (f"{CORE}/FileGateway.kt",
     '                summary = "网页 / HTTP 下载：${file.name}",',
     '                summary = L("网页 / HTTP 下载：%s").format(file.name),'),
]


def main():
    dry = "--dry" in sys.argv
    failed = []
    cache = {}  # 同一文件的多条替换要累积，不能每条都重读原文件
    for rel, old, new in PATCHES:
        path = os.path.join(ROOT, rel)
        src = cache.get(path)
        if src is None:
            src = open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != 1:
            failed.append((rel, old.split("\n")[0][:70], n))
            continue
        cache[path] = src.replace(old, new)

    if failed:
        print(f"❌ {len(failed)} 处没匹配上（预期各 1 次）：")
        for rel, head, n in failed:
            print(f"   [{n} 次] {rel}\n        {head}")
        print("\n没有写入任何文件。")
        return 1

    touched = [os.path.basename(p) for p in cache]
    print(f"✅ {len(PATCHES)} 处替换全部命中，涉及 {len(cache)} 个文件")
    if dry:
        return 0
    for path, src in cache.items():
        open(path, "w", encoding="utf-8").write(src)
    print("已写入：" + "、".join(sorted(set(touched))))
    return 0


if __name__ == "__main__":
    sys.exit(main())

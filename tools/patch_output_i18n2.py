#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
第 4 批 i18n（续）：Shell 执行结果、shell_info、自定义工具 CRUD、FileGateway（网页上传/下载）。
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ Shell.kt：命令结果正文
    (f"{CORE}/Shell.kt",
     '        sb.append("退出码：").append(exitCode)\n'
     '        if (timedOut) sb.append("（超时，已强制结束）")\n'
     '        sb.append("　耗时：").append(durationMs).append(" ms")\n'
     '        sb.append("　后端：").append(backend).append(\'\\n\')\n'
     '        sb.append("命令：").append(command).append("\\n----\\n")',
     '        sb.append(L("退出码：")).append(exitCode)\n'
     '        if (timedOut) sb.append(L("（超时，已强制结束）"))\n'
     '        sb.append(L("　耗时：")).append(durationMs).append(" ms")\n'
     '        sb.append(L("　后端：")).append(backend).append(\'\\n\')\n'
     '        sb.append(L("命令：")).append(command).append("\\n----\\n")'),
    (f"{CORE}/Shell.kt",
     'if (stdout.isBlank() && stderr.isBlank()) sb.append("（无输出）\\n")',
     'if (stdout.isBlank() && stderr.isBlank()) sb.append(L("（无输出）\\n"))'),
    (f"{CORE}/Shell.kt",
     'if (truncated) sb.append("（输出过长，已截断）\\n")',
     'if (truncated) sb.append(L("（输出过长，已截断）\\n"))'),

    # ================================================================ ToolsShell.kt：shell_info
    (f"{CORE}/ToolsShell.kt",
     '        sb.append("Shell 后端\\n")',
     '        sb.append(L("Shell 后端\\n"))'),
    (f"{CORE}/ToolsShell.kt",
     '            sb.append("  ").append(if (ok) "[可用] " else "[不可用] ")\n'
     '                .append(l.label).append("（").append(l.id).append("） · ").append(l.uidLabel).append(\'\\n\')\n'
     '            if (!ok) sb.append("         ").append(l.hint).append(\'\\n\')',
     '            sb.append("  ").append(if (ok) L("[可用] ") else L("[不可用] "))\n'
     '                .append(L(l.label)).append("（").append(l.id).append("） · ").append(l.uidLabel).append(\'\\n\')\n'
     '            if (!ok) sb.append("         ").append(L(l.hint)).append(\'\\n\')'),
    (f"{CORE}/ToolsShell.kt",
     '        sb.append("当前 auto 会选中：").append(picked?.label ?: "无（只能做文件操作）").append(\'\\n\')\n'
     '        sb.append("优先级设置：").append(ctx.config.shellPreference).append(\'\\n\')\n'
     '        sb.append("\\n环境\\n")',
     '        sb.append(L("当前 auto 会选中："))\n'
     '            .append(picked?.let { L(it.label) } ?: L("无（只能做文件操作）")).append(\'\\n\')\n'
     '        sb.append(L("优先级设置：")).append(ctx.config.shellPreference).append(\'\\n\')\n'
     '        sb.append(L("\\n环境\\n"))'),
    (f"{CORE}/ToolsShell.kt",
     '        sb.append("  默认超时=").append(\n'
     '            if (ctx.config.shellTimeoutMs <= 0L) "不限制（命令一直跑到自己结束）"\n'
     '            else "${ctx.config.shellTimeoutMs / 1000} 秒"\n'
     '        ).append(\'\\n\')\n'
     '        sb.append("\\n命令规则（按顺序匹配，第一条命中生效）\\n")',
     '        sb.append(L("  默认超时=")).append(\n'
     '            if (ctx.config.shellTimeoutMs <= 0L) L("不限制（命令一直跑到自己结束）")\n'
     '            else L("%s 秒").format(ctx.config.shellTimeoutMs / 1000)\n'
     '        ).append(\'\\n\')\n'
     '        sb.append(L("\\n命令规则（按顺序匹配，第一条命中生效）\\n"))'),
    (f"{CORE}/ToolsShell.kt",
     '            sb.append("  （无，所有命令都走「执行命令」权限开关：")\n'
     '                .append(ctx.permissions.switchOf(PermKey.SHELL).label).append("）\\n")',
     '            sb.append(L("  （无，所有命令都走「执行命令」权限开关："))\n'
     '                .append(L(ctx.permissions.switchOf(PermKey.SHELL).label)).append(L("）\\n"))'),
    (f"{CORE}/ToolsShell.kt",
     '                    .append("  [").append(it.match).append("] → ").append(it.actionEnum.label).append(\'\\n\')',
     '                    .append("  [").append(it.match).append("] → ").append(L(it.actionEnum.label)).append(\'\\n\')'),
    (f"{CORE}/ToolsShell.kt",
     '        sb.append("\\n自定义工具：").append(custom.size).append(" 个")\n'
     '        if (custom.isNotEmpty()) sb.append("（").append(custom.joinToString("、") { it.name }).append("）")',
     '        sb.append(L("\\n自定义工具：")).append(custom.size).append(L(" 个"))\n'
     '        if (custom.isNotEmpty()) {\n'
     '            sb.append("（").append(custom.joinToString(L("、")) { it.name }).append("）")\n'
     '        }'),

    # ================================================================ ToolsShell.kt：自定义工具 CRUD
    (f"{CORE}/ToolsShell.kt",
     '        ToolResult(\n'
     '            "已创建自定义工具：${created.name}\\n" +\n'
     '                "标题：${created.title}\\n参数：${created.params.size} 个\\n命令模板：${created.command}\\n" +\n'
     '                "现在开始，客户端 tools/list 里就会出现它。"\n'
     '        )',
     '        ToolResult(\n'
     '            L("已创建自定义工具：%s\\n").format(created.name) +\n'
     '                L("标题：%s\\n参数：%s 个\\n命令模板：%s\\n")\n'
     '                    .format(created.title, created.params.size, created.command) +\n'
     '                L("现在开始，客户端 tools/list 里就会出现它。")\n'
     '        )'),
    (f"{CORE}/ToolsShell.kt",
     '        val name = ctx.args.str("name") ?: ctx.fail("缺少 name")\n'
     '        val existing = store.byName(name) ?: ctx.fail("找不到工具：$name")',
     '        val name = ctx.args.str("name") ?: ctx.fail(L("缺少 name"))\n'
     '        val existing = store.byName(name) ?: ctx.fail(L("找不到工具：%s").format(name))'),
    (f"{CORE}/ToolsShell.kt",
     '        ToolResult("已更新 ${updated.name}\\n命令模板：${updated.command}")',
     '        ToolResult(L("已更新 %s\\n命令模板：%s").format(updated.name, updated.command))'),
    (f"{CORE}/ToolsShell.kt",
     '        val name = ctx.args.str("name") ?: ctx.fail("缺少 name")\n'
     '        ctx.guard(PermKey.TOOLS, null, L("删除自定义工具 %s").format(name))\n'
     '        if (!store.remove(name)) ctx.fail("找不到工具：$name")\n'
     '        ToolResult("已删除自定义工具：$name")',
     '        val name = ctx.args.str("name") ?: ctx.fail(L("缺少 name"))\n'
     '        ctx.guard(PermKey.TOOLS, null, L("删除自定义工具 %s").format(name))\n'
     '        if (!store.remove(name)) ctx.fail(L("找不到工具：%s").format(name))\n'
     '        ToolResult(L("已删除自定义工具：%s").format(name))'),
    (f"{CORE}/ToolsShell.kt",
     '            return@ToolSpec ToolResult("还没有自定义工具。可以用 create_custom_tool 造一个。")',
     '            return@ToolSpec ToolResult(L("还没有自定义工具。可以用 create_custom_tool 造一个。"))'),
    (f"{CORE}/ToolsShell.kt",
     '        val sb = StringBuilder("自定义工具（${list.size} 个）\\n")',
     '        val sb = StringBuilder(L("自定义工具（%s 个）\\n").format(list.size))'),
    (f"{CORE}/ToolsShell.kt",
     '            sb.append("\\n● ").append(t.name).append(if (t.enabled) "" else "（已停用）").append(\'\\n\')\n'
     '            sb.append("  标题：").append(t.title).append(\'\\n\')\n'
     '            if (t.description.isNotBlank()) sb.append("  说明：").append(t.description).append(\'\\n\')\n'
     '            sb.append("  命令：").append(t.command).append(\'\\n\')',
     '            sb.append("\\n● ").append(t.name).append(if (t.enabled) "" else L("（已停用）")).append(\'\\n\')\n'
     '            sb.append(L("  标题：")).append(t.title).append(\'\\n\')\n'
     '            if (t.description.isNotBlank()) sb.append(L("  说明：")).append(t.description).append(\'\\n\')\n'
     '            sb.append(L("  命令：")).append(t.command).append(\'\\n\')'),
    (f"{CORE}/ToolsShell.kt",
     '                sb.append("  参数：").append(\n'
     '                    t.params.joinToString("、") { p ->\n'
     '                        p.name + ":" + p.type + if (p.required) "(必填)" else ""\n'
     '                    }\n'
     '                ).append(\'\\n\')',
     '                sb.append(L("  参数：")).append(\n'
     '                    t.params.joinToString(L("、")) { p ->\n'
     '                        p.name + ":" + p.type + if (p.required) L("(必填)") else ""\n'
     '                    }\n'
     '                ).append(\'\\n\')'),
    (f"{CORE}/ToolsShell.kt",
     '            if (t.cwd.isNotBlank()) sb.append("  工作目录：").append(t.cwd).append(\'\\n\')\n'
     '            sb.append("  后端：").append(t.backend).append("　超时：").append(t.timeoutMs / 1000).append(" 秒")\n'
     '            sb.append("　运行次数：").append(t.runCount).append(\'\\n\')',
     '            if (t.cwd.isNotBlank()) sb.append(L("  工作目录：")).append(t.cwd).append(\'\\n\')\n'
     '            sb.append(L("  后端：")).append(t.backend)\n'
     '                .append(L("　超时：%s 秒").format(t.timeoutMs / 1000))\n'
     '            sb.append(L("　运行次数：")).append(t.runCount).append(\'\\n\')'),
    (f"{CORE}/ToolsShell.kt",
     '        if (file.isDirectory) ctx.fail("目标是一个目录：${file.path}")',
     '        if (file.isDirectory) ctx.fail(L("目标是一个目录：%s").format(file.path))'),
    (f"{CORE}/ToolsShell.kt",
     '        ToolResult(\n'
     '            "已导出 ${store.tools.size} 个自定义工具\\n文件：${file.path}\\n" +\n'
     '                "大小：${ctx.sandbox.humanSize(file.length())}"\n'
     '        )',
     '        ToolResult(\n'
     '            L("已导出 %s 个自定义工具\\n文件：%s\\n").format(store.tools.size, file.path) +\n'
     '                L("大小：%s").format(ctx.sandbox.humanSize(file.length()))\n'
     '        )'),
    (f"{CORE}/ToolsShell.kt",
     '        if (file.isDirectory) ctx.fail("这是一个目录：${file.path}")\n'
     '        if (file.length() > 8L * 1024 * 1024) ctx.fail("文件太大（${ctx.sandbox.humanSize(file.length())}）")',
     '        if (file.isDirectory) ctx.fail(L("这是一个目录：%s").format(file.path))\n'
     '        if (file.length() > 8L * 1024 * 1024) {\n'
     '            ctx.fail(L("文件太大（%s）").format(ctx.sandbox.humanSize(file.length())))\n'
     '        }'),
    (f"{CORE}/ToolsShell.kt",
     '        ToolResult(result.message + "\\n当前共 ${store.tools.size} 个自定义工具")',
     '        ToolResult(result.message + L("\\n当前共 %s 个自定义工具").format(store.tools.size))'),
    (f"{CORE}/ToolsShell.kt",
     '            append(tool.description.ifBlank { "自定义工具" })\n'
     '            append("\\n（这是用户自定义的工具，实际执行命令：")\n'
     '            append(tool.command)\n'
     '            append("）")',
     '            append(tool.description.ifBlank { L("自定义工具") })\n'
     '            append(L("\\n（这是用户自定义的工具，实际执行命令："))\n'
     '            append(tool.command)\n'
     '            append(L("）"))'),
    (f"{CORE}/ToolsShell.kt",
     '                toolLabel = "自定义工具「${tool.title.ifBlank { tool.name }}」",',
     '                toolLabel = L("自定义工具「%s」").format(tool.title.ifBlank { tool.name }),'),

    # ================================================================ FileGateway.kt：网页上传 / 下载
    (f"{CORE}/FileGateway.kt",
     'return json(405, false, "只支持 POST / PUT（GET 会给你一个上传网页）")',
     'return json(405, false, L("只支持 POST / PUT（GET 会给你一个上传网页）"))'),
    (f"{CORE}/FileGateway.kt",
     'return json(400, false, "缺少 path 参数：要写到手机上的哪个位置")',
     'return json(400, false, L("缺少 path 参数：要写到手机上的哪个位置"))'),
    (f"{CORE}/FileGateway.kt",
     'return json(400, false, e.message ?: "路径不合法")',
     'return json(400, false, e.message ?: L("路径不合法"))', 2),
    (f"{CORE}/FileGateway.kt",
     'return json(403, false, e.message ?: "当前模式下不允许写入")',
     'return json(403, false, e.message ?: L("当前模式下不允许写入"))'),
    (f"{CORE}/FileGateway.kt",
     'return json(400, false, "请求体是空的（把文件内容放在 body 里发过来）")',
     'return json(400, false, L("请求体是空的（把文件内容放在 body 里发过来）"))'),
    (f"{CORE}/FileGateway.kt",
     'return json(413, false, "文件太大：${sandbox.humanSize(bytes.size.toLong())}，上限 ${config.maxUploadMb} MB")',
     'return json(\n                413, false,\n                L("文件太大：%s，上限 %s MB").format(sandbox.humanSize(bytes.size.toLong()), config.maxUploadMb)\n            )'),
    (f"{CORE}/FileGateway.kt",
     '                json(500, false, "写入失败：应用和 root / Shizuku 都没能写入 ${file.path}")',
     '                json(500, false, L("写入失败：应用和 root / Shizuku 都没能写入 %s").format(file.path))'),
    (f"{CORE}/FileGateway.kt",
     '                    ok = true, message = "上传 ${payload.size} 字节 → ${file.path}"',
     '                    ok = true, message = L("上传 %s 字节 → %s").format(payload.size, file.path)'),
    (f"{CORE}/FileGateway.kt",
     '                    200, true, "已写入 ${file.path}",',
     '                    200, true, L("已写入 %s").format(file.path),'),
    (f"{CORE}/FileGateway.kt",
     'return json(405, false, "只支持 GET")',
     'return json(405, false, L("只支持 GET"))'),
    (f"{CORE}/FileGateway.kt",
     'if (raw.isBlank()) return json(400, false, "缺少 path 参数")',
     'if (raw.isBlank()) return json(400, false, L("缺少 path 参数"))'),
    (f"{CORE}/FileGateway.kt",
     'if (!bridge.exists(file)) return json(404, false, "文件不存在：${file.path}")',
     'if (!bridge.exists(file)) return json(404, false, L("文件不存在：%s").format(file.path))'),
    (f"{CORE}/FileGateway.kt",
     'return json(400, false, "这是一个目录，不能直接下载：${file.path}")',
     'return json(400, false, L("这是一个目录，不能直接下载：%s").format(file.path))'),
    (f"{CORE}/FileGateway.kt",
     '?: return json(500, false, "读不出来：应用没权限，且 root / Shizuku 不可用")',
     '?: return json(500, false, L("读不出来：应用没权限，且 root / Shizuku 不可用"))'),
    (f"{CORE}/FileGateway.kt",
     '                ok = true, message = "下载 ${bytes.size} 字节 ← ${file.path}"',
     '                ok = true, message = L("下载 %s 字节 ← %s").format(bytes.size, file.path)'),
    (f"{CORE}/FileGateway.kt",
     'json(403, false, e.message ?: "没有获得读取许可")',
     'json(403, false, e.message ?: L("没有获得读取许可"))'),
]


def main():
    dry = "--dry" in sys.argv
    failed = []
    cache = {}
    for item in PATCHES:
        rel, old, new = item[0], item[1], item[2]
        want = item[3] if len(item) > 3 else 1
        path = os.path.join(ROOT, rel)
        src = cache.get(path)
        if src is None:
            src = open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != want:
            failed.append((rel, old.split("\n")[0][:70], n, want))
            continue
        cache[path] = src.replace(old, new)

    if failed:
        print(f"❌ {len(failed)} 处没匹配上：")
        for rel, head, n, want in failed:
            print(f"   [实际 {n} / 期望 {want}] {rel}\n        {head}")
        print("\n没有写入任何文件。")
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

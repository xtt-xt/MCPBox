#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
第 4 批 i18n：**工具输出 / 日志 / 错误消息**接上翻译。

第 2、3 批解决的是「App 界面」和「审批弹窗」；这一批管工具真正返回给 AI 的正文、
以及写进日志（App 的 Logs 页直接看得见）的那些消息。

规则同上：拼句改成 `L("...%s...").format(...)`，纯短句直接包 `L()`。
枚举的中文 label（PermAction / LogKind / ApprovalDecision）**定义处不动**，在调用处包 L()。
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ Approval.kt
    (f"{CORE}/Approval.kt",
     'throw PermissionDeniedException("没有可用的审批界面，「${perm.title}」被自动拒绝")',
     'throw PermissionDeniedException(L("没有可用的审批界面，「%s」被自动拒绝").format(L(perm.title)))'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终允许」→ 工具「$tool」已单独设为允许"',
     '                        message = L("用户选择「始终允许」→ 工具「%s」已单独设为允许").format(tool)'),
    (f"{CORE}/Approval.kt",
     '                    permissions.addCommandRule(prefix, PermAction.ALLOW, note = "来自审批弹窗")',
     '                    permissions.addCommandRule(prefix, PermAction.ALLOW, note = L("来自审批弹窗"))'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终允许」→ 已添加命令规则：$prefix 开头的命令"',
     '                        message = L("用户选择「始终允许」→ 已添加命令规则：%s 开头的命令").format(prefix)'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终允许」→ 权限「${perm.title}」已设为允许"',
     '                        message = L("用户选择「始终允许」→ 权限「%s」已设为允许").format(L(perm.title))'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终拒绝」→ 工具「$tool」已单独设为拒绝"',
     '                        message = L("用户选择「始终拒绝」→ 工具「%s」已单独设为拒绝").format(tool)'),
    (f"{CORE}/Approval.kt",
     '                    permissions.addCommandRule(prefix, PermAction.DENY, note = "来自审批弹窗")',
     '                    permissions.addCommandRule(prefix, PermAction.DENY, note = L("来自审批弹窗"))'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终拒绝」→ 已添加命令规则：$prefix 开头的命令被拒绝"',
     '                        message = L("用户选择「始终拒绝」→ 已添加命令规则：%s 开头的命令被拒绝").format(prefix)'),
    (f"{CORE}/Approval.kt",
     '                        message = "用户选择「始终拒绝」→ 权限「${perm.title}」已设为拒绝"',
     '                        message = L("用户选择「始终拒绝」→ 权限「%s」已设为拒绝").format(L(perm.title))'),
    (f"{CORE}/Approval.kt",
     'log.add(LogKind.APPROVAL, tool, path, client, ok = true, message = "用户允许一次")',
     'log.add(LogKind.APPROVAL, tool, path, client, ok = true, message = L("用户允许一次"))'),
    (f"{CORE}/Approval.kt",
     'log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = "用户拒绝（一次）")',
     'log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = L("用户拒绝（一次）"))'),
    (f"{CORE}/Approval.kt",
     '                    message = "审批超时（${req.timeoutMs / 1000} 秒无响应），已自动拒绝"',
     '                    message = L("审批超时（%s 秒无响应），已自动拒绝").format(req.timeoutMs / 1000)'),
    (f"{CORE}/Approval.kt",
     'log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = "服务已停止，审批取消")',
     'log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = L("服务已停止，审批取消"))'),
    (f"{CORE}/Approval.kt",
     'throw PermissionDeniedException("用户未批准「${perm.title}」（${finalDecision.label}）")',
     'throw PermissionDeniedException(\n                L("用户未批准「%s」（%s）").format(L(perm.title), L(finalDecision.label))\n            )'),

    # ================================================================ Trash.kt
    (f"{CORE}/Trash.kt",
     'throw SandboxException("移动到回收站失败：${file.path}")',
     'throw SandboxException(L("移动到回收站失败：%s").format(file.path))'),
    (f"{CORE}/Trash.kt",
     'throw SandboxException("回收站里的文件已不存在：${entry.storedPath}")',
     'throw SandboxException(L("回收站里的文件已不存在：%s").format(entry.storedPath))'),
    (f"{CORE}/Trash.kt",
     'throw SandboxException("目标已存在，无法还原：${dst.path}")',
     'throw SandboxException(L("目标已存在，无法还原：%s").format(dst.path))'),
    (f"{CORE}/Trash.kt",
     'throw SandboxException("还原失败：${entry.originalPath}")',
     'throw SandboxException(L("还原失败：%s").format(entry.originalPath))'),
    (f"{CORE}/Trash.kt",
     'throw SandboxException("删除失败：${f.path}")',
     'throw SandboxException(L("删除失败：%s").format(f.path))'),
    (f"{CORE}/Trash.kt",
     'throw SandboxException("无法创建目录：${dst.path}")',
     'throw SandboxException(L("无法创建目录：%s").format(dst.path))'),

    # ================================================================ PathSandbox.kt
    (f"{CORE}/PathSandbox.kt",
     'throw SandboxException("路径不存在：${resolved.path}")',
     'throw SandboxException(L("路径不存在：%s").format(resolved.path))'),
    (f"{CORE}/PathSandbox.kt",
     '                "应用私有目录当前是「只读」模式，不能修改：${file.path}\\n" +\n                    "（要写入请到 设置 → 权限 → 应用私有目录 改成「可读写」）"',
     '                L("应用私有目录当前是「只读」模式，不能修改：%s\\n").format(file.path) +\n                    L("（要写入请到 设置 → 权限 → 应用私有目录 改成「可读写」）")'),
    (f"{CORE}/PathSandbox.kt",
     'throw SandboxException("系统目录受保护，禁止访问：${path.path}")',
     'throw SandboxException(L("系统目录受保护，禁止访问：%s").format(path.path))'),
    (f"{CORE}/PathSandbox.kt",
     '                    "应用私有目录没有开放：${path.path}\\n" +\n                        "（到 设置 → 权限 → 应用私有目录 里选「只读」或「可读写」；需要 root 或 Shizuku）"',
     '                    L("应用私有目录没有开放：%s\\n").format(path.path) +\n                        L("（到 设置 → 权限 → 应用私有目录 里选「只读」或「可读写」；需要 root 或 Shizuku）")'),
    (f"{CORE}/PathSandbox.kt",
     '                "路径超出允许范围：${path.path}\\n当前允许的根目录只有：$allowed\\n" +\n                    "（需要在 App 的「设置 → 允许访问的目录」里添加，或把「不限制目录」打开）"',
     '                L("路径超出允许范围：%s\\n当前允许的根目录只有：%s\\n").format(path.path, allowed) +\n                    L("（需要在 App 的「设置 → 允许访问的目录」里添加，或把「不限制目录」打开）")'),

    # ================================================================ FileBridge.kt（copy 与 move 各一处）
    (f"{CORE}/FileBridge.kt",
     'if (local) return true to "本地"',
     'if (local) return true to L("本地")', 2),
    (f"{CORE}/FileBridge.kt",
     'val l = launcher() ?: return false to "应用自己没有权限，而且没有可用的 root / Shizuku"',
     'val l = launcher() ?: return false to L("应用自己没有权限，而且没有可用的 root / Shizuku")', 2),

    # ================================================================ AboutScreen.kt（Shell 后端列表漏翻）
    ("app/src/main/java/com/xtt/mcpbox/ui/AboutScreen.kt",
     'ShellBackends.available().joinToString("、") { it.label }.ifBlank { L("仅文件操作") }',
     'ShellBackends.available().joinToString(L("、")) { L(it.label) }.ifBlank { L("仅文件操作") }'),

    # ================================================================ ServerMeta.kt
    (f"{CORE}/ServerMeta.kt",
     '            h > 0 -> "${h} 小时 ${m} 分"\n            m > 0 -> "${m} 分 ${s} 秒"\n            else -> "${s} 秒"',
     '            h > 0 -> L("%s 小时 %s 分").format(h, m)\n            m > 0 -> L("%s 分 %s 秒").format(m, s)\n            else -> L("%s 秒").format(s)'),

    # ================================================================ HttpServer.kt
    (f"{CORE}/HttpServer.kt",
     'throw IllegalStateException("服务已在运行")',
     'throw IllegalStateException(L("服务已在运行"))'),
    (f"{CORE}/HttpServer.kt",
     'onError?.invoke("accept 失败：${e.message}")',
     'onError?.invoke(L("accept 失败：%s").format(e.message))'),
    (f"{CORE}/HttpServer.kt",
     'onError?.invoke("连接处理异常：${e.message}")',
     'onError?.invoke(L("连接处理异常：%s").format(e.message))'),

    # ================================================================ Memory.kt（记忆库统计正文）
    (f"{CORE}/Memory.kt",
     '        append("记忆库\\n")\n        append("  实体：").append(graph.entities.size).append(\'\\n\')\n        append("  关系：").append(graph.relations.size).append(\'\\n\')\n        val obs = graph.entities.sumOf { it.observations.size }\n        append("  观察：").append(obs).append(" 条\\n")\n        val f = folders()\n        if (f.isNotEmpty()) {\n            append("\\n分区：\\n")',
     '        append(L("记忆库")).append(\'\\n\')\n        append(L("  实体：")).append(graph.entities.size).append(\'\\n\')\n        append(L("  关系：")).append(graph.relations.size).append(\'\\n\')\n        val obs = graph.entities.sumOf { it.observations.size }\n        append(L("  观察：")).append(obs).append(L(" 条\\n"))\n        val f = folders()\n        if (f.isNotEmpty()) {\n            append(L("\\n分区：\\n"))'),
    (f"{CORE}/Memory.kt",
     '            append("\\n实体类型：\\n")',
     '            append(L("\\n实体类型：\\n"))'),
    (f"{CORE}/Memory.kt",
     '            append("\\n关系类型：\\n")',
     '            append(L("\\n关系类型：\\n"))'),
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
            print(f"   [实际 {n} 次 / 期望 {want} 次] {rel}\n        {head}")
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

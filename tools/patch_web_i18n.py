#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""第 9 批 i18n：两个网页（浏览器控制台 / 上传页）的可见文案。"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = "mcpcore/src/main/kotlin/com/xtt/mcpbox/core"

PATCHES = [
    # ================================================================ WebConsole：控制台
    (f"{CORE}/WebConsole.kt",
     '<title>MCP 文件盒 · 控制台</title>',
     '<title>${L("MCP 文件盒 · 控制台")}</title>'),
    (f"{CORE}/WebConsole.kt",
     '  <h1>MCP 文件盒 · 控制台 <span class="muted">v__VERSION__</span></h1>\n'
     '  <div class="muted"><span class="dot" id="dot"></span> <span id="stat">连接中…</span></div>',
     '  <h1>${L("MCP 文件盒 · 控制台")} <span class="muted">v__VERSION__</span></h1>\n'
     '  <div class="muted"><span class="dot" id="dot"></span> <span id="stat">${L("连接中…")}</span></div>'),
    (f"{CORE}/WebConsole.kt",
     '    <h2>连接信息</h2>\n'
     '    <div class="muted">MCP 地址（HTTP）：<code id="ep"></code></div>\n'
     '    <div class="muted">允许目录：__ROOTS__</div>\n'
     '    <div class="muted">访问令牌：<code id="tk"></code>（客户端需带 <code>Authorization: Bearer</code> 或 <code>?token=</code>）</div>',
     '    <h2>${L("连接信息")}</h2>\n'
     '    <div class="muted">${L("MCP 地址（HTTP）：")}<code id="ep"></code></div>\n'
     '    <div class="muted">${L("允许目录：")}__ROOTS__</div>\n'
     '    <div class="muted">${L("访问令牌：")}<code id="tk"></code>${L("（客户端需带 Authorization: Bearer 或 ?token=）")}</div>'),
    (f"{CORE}/WebConsole.kt",
     '    <h2>待审批请求 <span class="tag" id="pendCount">0</span></h2>\n'
     '    <div id="pendList" class="muted">暂无</div>',
     '    <h2>${L("待审批请求")} <span class="tag" id="pendCount">0</span></h2>\n'
     '    <div id="pendList" class="muted">${L("暂无")}</div>'),
    (f"{CORE}/WebConsole.kt",
     '    <h2>工具测试</h2>\n'
     '    <div class="row" style="margin-bottom:10px">\n'
     '      <select id="tool" style="flex:2"></select>\n'
     '      <button class="sec" onclick="fillExample()">填充参数</button>\n'
     '      <button onclick="run()">执行</button>\n'
     '    </div>\n'
     '    <textarea id="args" spellcheck="false">{}</textarea>\n'
     '    <pre id="result" style="margin-top:10px">（结果会显示在这里）</pre>',
     '    <h2>${L("工具测试")}</h2>\n'
     '    <div class="row" style="margin-bottom:10px">\n'
     '      <select id="tool" style="flex:2"></select>\n'
     '      <button class="sec" onclick="fillExample()">${L("填充参数")}</button>\n'
     '      <button onclick="run()">${L("执行")}</button>\n'
     '    </div>\n'
     '    <textarea id="args" spellcheck="false">{}</textarea>\n'
     '    <pre id="result" style="margin-top:10px">${L("（结果会显示在这里）")}</pre>'),
    (f"{CORE}/WebConsole.kt",
     '    <h2>最近日志</h2>\n'
     '    <div id="log" class="muted">加载中…</div>',
     '    <h2>${L("最近日志")}</h2>\n'
     '    <div id="log" class="muted">${L("加载中…")}</div>'),
    (f"{CORE}/WebConsole.kt",
     """if (!list.length) { box.innerHTML = '<span class="muted">暂无</span>'; return; }""",
     """if (!list.length) { box.innerHTML = '<span class="muted">${L("暂无")}</span>'; return; }"""),
    (f"{CORE}/WebConsole.kt",
     """      '<div class="muted">工具 ' + x.tool + ' · 权限 ' + x.perm + ' · 来自 ' + (x.client||'?') + '</div>' +
      '<div class="row" style="margin-top:8px">' +
      '<button onclick="approve(\\'' + x.id + '\\',\\'allow_once\\')">允许一次</button>' +
      '<button class="sec" onclick="approve(\\'' + x.id + '\\',\\'allow_always\\')">始终允许</button>' +
      '<button class="warn" onclick="approve(\\'' + x.id + '\\',\\'deny_once\\')">拒绝</button>' +""",
     """      '<div class="muted">${L("工具")} ' + x.tool + ' · ${L("权限")} ' + x.perm + ' · ${L("来自")} ' + (x.client||'?') + '</div>' +
      '<div class="row" style="margin-top:8px">' +
      '<button onclick="approve(\\'' + x.id + '\\',\\'allow_once\\')">${L("允许一次")}</button>' +
      '<button class="sec" onclick="approve(\\'' + x.id + '\\',\\'allow_always\\')">${L("始终允许")}</button>' +
      '<button class="warn" onclick="approve(\\'' + x.id + '\\',\\'deny_once\\')">${L("拒绝")}</button>' +"""),
    (f"{CORE}/WebConsole.kt",
     """    }).join('') : '<span class="muted">暂无日志</span>';""",
     """    }).join('') : '<span class="muted">${L("暂无日志")}</span>';"""),

    # ================================================================ FileGateway：上传页
    (f"{CORE}/FileGateway.kt",
     '<title>MCP 文件盒 · 上传</title>',
     '<title>${L("MCP 文件盒 · 上传")}</title>'),
    (f"{CORE}/FileGateway.kt",
     '<h1>上传到手机</h1>\n'
     '<p class="sub">选一个文件 + 填目标路径，直接写进手机存储（会按权限设置弹审批）</p>',
     '<h1>${L("上传到手机")}</h1>\n'
     '<p class="sub">${L("选一个文件 + 填目标路径，直接写进手机存储（会按权限设置弹审批）")}</p>'),
    (f"{CORE}/FileGateway.kt",
     '  <label>目标路径（可以只写到目录，会自动带上原文件名）</label>\n'
     '  <input type="text" id="path" value="/storage/emulated/0/xtt/app/mcp/">\n'
     '  <label>文件</label>\n'
     '  <input type="file" id="file">\n'
     '  <button id="go" onclick="up()">开始上传</button>\n'
     '  <progress id="bar" value="0" max="100" style="display:none"></progress>\n'
     '  <pre id="out">等待中…</pre>',
     '  <label>${L("目标路径（可以只写到目录，会自动带上原文件名）")}</label>\n'
     '  <input type="text" id="path" value="/storage/emulated/0/xtt/app/mcp/">\n'
     '  <label>${L("文件")}</label>\n'
     '  <input type="file" id="file">\n'
     '  <button id="go" onclick="up()">${L("开始上传")}</button>\n'
     '  <progress id="bar" value="0" max="100" style="display:none"></progress>\n'
     '  <pre id="out">${L("等待中…")}</pre>'),
    (f"{CORE}/FileGateway.kt",
     """  if (!f) { out.textContent = '先选一个文件'; return; }
  if (!p) { out.textContent = '先填目标路径'; return; }""",
     """  if (!f) { out.textContent = '${L("先选一个文件")}'; return; }
  if (!p) { out.textContent = '${L("先填目标路径")}'; return; }"""),
    (f"{CORE}/FileGateway.kt",
     """  out.textContent = '上传中…';""",
     """  out.textContent = '${L("上传中…")}';"""),
    (f"{CORE}/FileGateway.kt",
     """  xhr.onerror = () => { out.textContent = '失败：网络错误'; };""",
     """  xhr.onerror = () => { out.textContent = '${L("失败：网络错误")}'; };"""),
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

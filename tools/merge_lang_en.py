#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
把 lang_en_batch2.py 里的词条合并进 LangEn.kt。

规则：
  * 已存在的键**不覆盖**（保留人工校对过的旧译文）；
  * STALE_KEYS 里那些「永远匹配不上」的旧键直接删掉；
  * 按分组追加到 mapOf 末尾，附分组注释，方便以后维护。

用法：python3 tools/merge_lang_en.py [--dry]
"""

import glob
import importlib
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 自动收集 tools/lang_en_batch*.py 里的词条，按文件名排序合并
BATCHES = sorted(
    os.path.basename(p)[:-3]
    for p in glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), "lang_en_batch*.py"))
)
GROUPS = []
STALE_KEYS = []
for name in BATCHES:
    mod = importlib.import_module(name)
    GROUPS.extend(getattr(mod, "GROUPS", []))
    STALE_KEYS.extend(getattr(mod, "STALE_KEYS", []))

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_EN = os.path.join(ROOT, "app/src/main/java/com/xtt/mcpbox/i18n/LangEn.kt")


def kotlin_lit(s):
    out = []
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "$":
            out.append("\\$")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def existing_keys(src):
    return set(m.group(1) for m in re.finditer(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"', src, re.M))


def main():
    dry = "--dry" in sys.argv
    src = open(LANG_EN, encoding="utf-8").read()

    # 1) 删掉匹配不上的旧键
    removed = 0
    for key in STALE_KEYS:
        pat = re.compile(r'^\s*"' + re.escape(key) + r'"\s+to\s+"(?:[^"\\]|\\.)*",\n', re.M)
        src, n = pat.subn("", src)
        removed += n
        if n == 0:
            print(f"  ! 没找到要删的旧键：{key[:40]}…")

    # 2) 把之前生成的批次块整块拆掉 —— 这样脚本**幂等**，重复跑不会留下重复词条
    lines = src.split("\n")
    keep = []
    i = 0
    dropped = 0
    while i < len(lines):
        if lines[i].lstrip().startswith("// ---------------- i18n 第"):
            i += 1
            while i < len(lines):
                s = lines[i].strip()
                if s == "" or s.startswith('"'):
                    i += 1
                else:
                    break
            dropped += 1
            continue
        keep.append(lines[i])
        i += 1
    # 顺手把尾部可能多出来的连续空行收一收
    while len(keep) >= 2 and keep[-1].strip() == "" and keep[-2].strip() == "":
        keep.pop()
    src = "\n".join(keep)

    # 3) 重新追加
    have = existing_keys(src)
    lines = []
    added = skipped = 0
    for title, mapping in GROUPS:
        block = []
        for zh, en in mapping.items():
            if zh in have:
                skipped += 1
                continue
            block.append(f"    {kotlin_lit(zh)} to {kotlin_lit(en)},")
            have.add(zh)
            added += 1
        if block:
            lines.append(f"\n    // ---------------- {title}")
            lines.extend(block)

    anchor = "\n    )\n}"
    idx = src.rindex(anchor)
    out = src[:idx] + "\n" + "\n".join(lines) + src[idx:]

    print(f"删除旧键 {removed} 条；拆掉旧批次块 {dropped} 个；新增 {added} 条；跳过（词表已有）{skipped} 条")
    if dry:
        print("（--dry：没有写文件）")
        return
    open(LANG_EN, "w", encoding="utf-8").write(out)
    print(f"已写入 {LANG_EN}")


if __name__ == "__main__":
    main()

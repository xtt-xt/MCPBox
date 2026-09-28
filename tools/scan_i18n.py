#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xtt
"""
扫描 Kotlin 源码里的「裸中文」—— 即会显示给用户、但没有被 L(...) 包裹的字符串。

用法：
  python3 tools/scan_i18n.py             # 列出所有裸中文（按文件分组）
  python3 tools/scan_i18n.py --summary   # 只列每个文件的数量
  python3 tools/scan_i18n.py --missing   # 标出哪些还没进 LangEn 词表
  python3 tools/scan_i18n.py --file X.kt # 只看某个文件

原理：
  1. 先做一遍词法扫描，把所有字符串字面量（普通 / 原始字符串）记下来，
     同时生成一份「注释和字符串内容都被抹成空格」的掩码源码。
  2. 在掩码源码上找 L( 调用 —— 因为没有字符串干扰，括号配对绝对可靠。
  3. 掩码上处于 L(...) 区间内、且原文含中文的字面量 = 已包裹；其余 = 裸中文。

跳过：LangEn.kt / LangCat.kt（词表本体）、harness（测试输出）、build 目录。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", "build", ".gradle", ".idea"}
SKIP_FILES = {"LangEn.kt", "LangCat.kt"}
CN = re.compile(r"[\u4e00-\u9fff]")

_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "'": "'", "$": "$", "0": "\0"}


def unescape(s):
    """把 Kotlin 字面量里的转义还原成运行时值（原始字符串不走这里）。"""
    out = []
    i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            c = s[i + 1]
            if c in _ESCAPES:
                out.append(_ESCAPES[c])
            else:
                out.append(s[i:i + 2])
            i += 2
            continue
        out.append(s[i])
        i += 1
    return "".join(out)


def lex(src):
    """返回 (literals, masked)。

    literals: [(start, end, raw_text, is_raw_string)]
    masked:   等长副本，注释与字符串内容都换成空格（换行保留）。
    """
    n = len(src)
    mask = list(src)
    lits = []
    i = 0
    while i < n:
        c = src[i]
        if c == "/" and src[i:i + 2] == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                mask[k] = " "
            i = j
            continue
        if c == "/" and src[i:i + 2] == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if mask[k] != "\n":
                    mask[k] = " "
            i = j
            continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            lits.append((i, j, src[i + 3:j - 3], True))
            mask[i] = mask[i + 1] = mask[i + 2] = '"'
            for k in range(i + 3, j - 3):
                if mask[k] != "\n":
                    mask[k] = " "
            for k in range(j - 3, j):
                mask[k] = '"'
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            j = min(j, n - 1)
            lits.append((i, j + 1, unescape(src[i + 1:j]), False))
            mask[i] = '"'
            for k in range(i + 1, j):
                if mask[k] != "\n":
                    mask[k] = " "
            mask[j] = '"'
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                if mask[k] != "\n":
                    mask[k] = " "
            i = j + 1
            continue
        i += 1
    return lits, "".join(mask)


def wrapped_ranges(masked):
    """掩码源码里所有 L( ... ) 调用的字符区间。"""
    ranges = []
    for m in re.finditer(r"(?<![A-Za-z0-9_.])L\s*\(", masked):
        depth = 0
        i = m.end() - 1
        n = len(masked)
        while i < n:
            if masked[i] == "(":
                depth += 1
            elif masked[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        ranges.append((m.start(), i))
    return ranges


def wrapped_literals(src):
    """所有被 L(...) 包裹的中文字面量（键）。"""
    lits, masked = lex(src)
    wr = wrapped_ranges(masked)
    out = set()
    for (s, e, text, raw) in lits:
        if not CN.search(text):
            continue
        if any(s >= a and s < b for (a, b) in wr):
            out.add(text)
    return out


def collect_all_files():
    """遍历 app + mcpcore 的所有 kt 文件（harness 也算，里面有工具名引用）。"""
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in sorted(files):
            if not f.endswith(".kt") or f in SKIP_FILES:
                continue
            yield os.path.join(base, f)


def untranslated():
    """列出所有被 L() 包裹、但词表里没有的中文键（含工具变量形式的源头，单独核）。"""
    entries = lang_en_entries()
    keys = set()
    for path in collect_all_files():
        keys |= wrapped_literals(open(path, encoding="utf-8").read())
    missing = sorted(keys - entries)
    return keys, entries, missing


def defined_keys():
    """扫描「定义位」的中文串 —— 这些是以变量形式传给 L() 的键。

    覆盖：
      * `title = "..."` / `description = "..."` / `label = "..."`（工具、工具包、枚举标签）
      * `Schema.str("...")` / `Schema.bool(...)` / `Schema.int(...)`（参数说明）
      * `PermKey` / `PermAction` / `LogKind` 这类 `NAME("id", "中文", ...)` 枚举
    跨行 `"a" + "b"` 会合并成一个键（Kotlin 编译期就是这么折叠的）。
    """
    lit = r'"(?:[^"\\]|\\.)*"'
    pat_assign = re.compile(r'\b(title|description|label)\s*=\s*((?:' + lit + r'\s*(?:\+\s*)?)+)')
    pat_schema = re.compile(r'Schema\.(?:str|int|bool|num)\s*\(\s*((?:' + lit + r'\s*(?:\+\s*)?)+)')
    pat_enum = re.compile(r'^\s*[A-Z][A-Z0-9_]*\(\s*' + lit + r',\s*((?:' + lit + r'\s*(?:\+\s*)?)+)', re.M)
    one = re.compile(lit)

    out = {}
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in sorted(files):
            if not f.endswith(".kt") or f in SKIP_FILES:
                continue
            path = os.path.join(base, f)
            rel = os.path.relpath(path, ROOT)
            src = open(path, encoding="utf-8").read()
            _, masked = lex(src)
            found = []
            for pat, kind in ((pat_assign, "定义"), (pat_schema, "参数说明"), (pat_enum, "枚举")):
                for m in pat.finditer(masked):
                    # 位置来自掩码（避开注释），内容要回原文取
                    s, e = m.span(m.lastindex)
                    group = src[s:e]
                    text = "".join(unescape(t[1:-1]) for t in one.findall(group))
                    if CN.search(text):
                        found.append((kind, text))
            if found:
                out[rel] = found
    return out


def check_lang_en():
    """词表质检：重复键、%s 数量对不上、译文里残留中文。"""
    path = os.path.join(ROOT, "app/src/main/java/com/xtt/mcpbox/i18n/LangEn.kt")
    src = open(path, encoding="utf-8").read()
    pairs = re.findall(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"', src, re.M)
    seen = {}
    dup = []
    bad_ph = []
    bad_cn = []
    for raw_k, raw_v in pairs:
        k, v = unescape(raw_k), unescape(raw_v)
        if k in seen:
            dup.append(k)
        seen[k] = v
        if k.count("%s") != v.count("%s"):
            bad_ph.append((k, v))
        if CN.search(v):
            bad_cn.append((k, v))

    print(f"词条 {len(pairs)} 条，唯一 {len(seen)} 条")
    ok = True
    if dup:
        ok = False
        print(f"\n重复键 {len(dup)} 组：")
        for k in dup:
            print(f"  {k}")
    if bad_ph:
        ok = False
        print(f"\n占位符对不上 {len(bad_ph)} 条：")
        for k, v in bad_ph:
            print(f"  键({k.count('%s')}个%s) {k}\n  值({v.count('%s')}个%s) {v}")
    if bad_cn:
        ok = False
        print(f"\n译文残留中文 {len(bad_cn)} 条：")
        for k, v in bad_cn[:20]:
            print(f"  {k}  →  {v}")
    print("\n" + ("✅ 词表质检通过" if ok else "❌ 词表有问题，见上"))
    return 0 if ok else 1


def scan_source(src):
    lits, masked = lex(src)
    wr = wrapped_ranges(masked)
    lines = src.split("\n")
    starts = []
    pos = 0
    for ln in lines:
        starts.append(pos)
        pos += len(ln) + 1

    def line_of(p):
        lo, hi = 0, len(starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if starts[mid] <= p:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1

    out = []
    for (s, e, text, raw) in lits:
        if not CN.search(text):
            continue
        if any(s >= a and s < b for (a, b) in wr):
            continue
        out.append({
            "line": line_of(s),
            "text": text,
            "raw": raw,
            "multiline": "\n" in text,
            "pos": s,
        })
    return out


def lang_en_entries():
    path = os.path.join(ROOT, "app/src/main/java/com/xtt/mcpbox/i18n/LangEn.kt")
    src = open(path, encoding="utf-8").read()
    keys = set()
    for m in re.finditer(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"', src, re.M):
        keys.add(unescape(m.group(1)))
    return keys


def collect(only_file=None):
    result = {}
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in sorted(files):
            if not f.endswith(".kt") or f in SKIP_FILES:
                continue
            if "/harness/" in base + "/":
                continue
            path = os.path.join(base, f)
            rel = os.path.relpath(path, ROOT)
            if only_file and only_file not in rel:
                continue
            hits = scan_source(open(path, encoding="utf-8").read())
            if hits:
                result[rel] = hits
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("--missing", action="store_true")
    ap.add_argument("--file")
    ap.add_argument("--untranslated", action="store_true", help="列出被 L() 包裹但词表里没有的键")
    ap.add_argument("--defines", action="store_true", help="列出定义位（title/description/参数说明）里的中文键缺口")
    ap.add_argument("--check", action="store_true", help="质检词表：重复键 / %s 对不上 / 译文残留中文")
    args = ap.parse_args()

    if args.check:
        return check_lang_en()

    if args.defines:
        entries = lang_en_entries()
        data = defined_keys()
        missing = []
        for rel in sorted(data):
            lack = [(k, t) for (k, t) in data[rel] if t not in entries]
            if lack:
                missing += lack
                print(f"\n== {rel}  缺 {len(lack)}")
                for (kind, t) in lack:
                    t1 = t.replace("\n", "⏎")
                    print(f"  [{kind}] {t1[:110]}")
        print(f"\n---- 定义位缺口合计 {len(missing)} 条")
        return

    if args.untranslated:
        keys, entries, missing = untranslated()
        print(f"L() 里出现的键：{len(keys)} 个；词表已有：{len(entries)} 个；**缺口 {len(missing)} 个**\n")
        for k in missing:
            print(f'    "{k}" to "",')
        return

    entries = lang_en_entries()
    by_file = collect(args.file)
    total = sum(len(v) for v in by_file.values())

    if args.summary:
        for rel in sorted(by_file, key=lambda r: -len(by_file[r])):
            print(f"{len(by_file[rel]):5d}  {rel}")
        print(f"---- 合计 {total} 条裸中文")
        return

    for rel in sorted(by_file, key=lambda r: -len(by_file[r])):
        print(f"\n== {rel}  ({len(by_file[rel])})")
        for h in by_file[rel]:
            t = h["text"].replace("\\n", "⏎").replace("\n", "⏎")
            if len(t) > 110:
                t = t[:110] + "…"
            tags = []
            if h["multiline"]:
                tags.append("多行")
            if args.missing and h["text"] not in entries:
                tags.append("词表缺")
            tag = ("   [" + ",".join(tags) + "]") if tags else ""
            print(f"  {h['line']:5d}  {t}{tag}")
    print(f"\n---- 合计 {total} 条裸中文")


if __name__ == "__main__":
    sys.exit(main())

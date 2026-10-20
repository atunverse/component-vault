#!/usr/bin/env python3
"""Kotlin 源文件的括号平衡自检（本机没有 JDK/Android SDK，编译只能靠 CI）。

只做一件事：去掉字符串与注释后，检查 () [] {} 是否严格配对，并给出首个失配位置。
不是编译器，但能把「少一个 }」这种最常见的编译错误拦在 push 之前。

用法：
    python tools/kotlin_brace_check.py app/src/main/java/.../Foo.kt [...]
    python tools/kotlin_brace_check.py --all      # 扫 app/src 下全部 .kt
"""

import sys
from pathlib import Path

PAIRS = {")": "(", "]": "[", "}": "{"}
OPEN = set("([{")


def strip_code(text: str) -> str:
    """把字符串字面量与注释替换成空格，保留换行以便定位行号。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
        elif text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 3
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    code = strip_code(text)
    line = 1
    stack: list[tuple[str, int]] = []
    problems = []
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in OPEN:
            stack.append((ch, line))
        elif ch in PAIRS:
            if not stack:
                problems.append(f"{path}:{line} 多余的 {ch}")
                return problems
            opener, oline = stack.pop()
            if opener != PAIRS[ch]:
                problems.append(f"{path}:{line} 期望配 {opener}（第 {oline} 行），实际是 {ch}")
                return problems
    for opener, oline in stack:
        problems.append(f"{path}:{oline} 未闭合的 {opener}")
    return problems


def main() -> int:
    args = sys.argv[1:]
    if args == ["--all"]:
        root = Path(__file__).resolve().parent.parent / "app" / "src"
        files = sorted(root.rglob("*.kt"))
    else:
        files = [Path(a) for a in args]
    bad = 0
    for f in files:
        for p in check(f):
            print(p)
            bad += 1
    print(f"检查 {len(files)} 个文件，问题 {bad} 处")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

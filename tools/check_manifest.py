#!/usr/bin/env python3
"""
构建前置检查：AndroidManifest.xml 里声明的组件类必须真的存在。

踩过的坑：manifest 写 `android:name=".VaultApp"`，实际类叫 `VaultApplication`。
这种错误**编译期不报错**（manifest 里的类名是纯字符串，AGP 不校验），
装到手机上必然 ClassNotFoundException 秒退，而且只有装上才暴露。

用法：python3 tools/check_manifest.py <项目根目录>
退出码 0 = 通过，1 = 有问题
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def find_source_dirs(root):
    base = os.path.join(root, "app", "src", "main")
    return [
        os.path.join(base, "java"),
        os.path.join(base, "kotlin"),
    ]


def collect_declared_classes(root):
    """
    收集源文件里声明的类名（顶层 class / object / interface）。
    只做"类名是否存在"的粗校验，不做包名分析 —— 够用且不依赖 JDK。
    """
    declared = set()
    for d in find_source_dirs(root):
        if not os.path.isdir(d):
            continue
        for dirpath, _, files in os.walk(d):
            for f in files:
                if not f.endswith((".kt", ".java")):
                    continue
                path = os.path.join(dirpath, f)
                try:
                    text = open(path, encoding="utf-8").read()
                except OSError:
                    continue
                for m in re.finditer(
                    r"^\s*(?:public\s+|internal\s+|private\s+|open\s+|abstract\s+|sealed\s+|data\s+)*"
                    r"(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)",
                    text,
                    re.M,
                ):
                    declared.add(m.group(1))
    return declared


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    manifest = os.path.join(root, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(manifest):
        print(f"[check_manifest] 找不到 {manifest}")
        return 1

    tree = ET.parse(manifest)
    declared = collect_declared_classes(root)

    problems = []
    checked = 0

    # application 本身：完整类名，取最后一段比对
    for app in tree.getroot().findall("application"):
        name = app.get(ANDROID_NS + "name")
        if name:
            checked += 1
            simple = name.rsplit(".", 1)[-1]
            if simple not in declared:
                problems.append(
                    f"<application android:name=\"{name}\"> -> 源码里没有类 {simple}"
                )
        # 四大组件
        for tag in ("activity", "service", "receiver", "provider"):
            for node in app.findall(tag):
                cname = node.get(ANDROID_NS + "name")
                if not cname:
                    continue
                checked += 1
                simple = cname.rsplit(".", 1)[-1]
                if simple not in declared:
                    problems.append(
                        f"<{tag} android:name=\"{cname}\"> -> 源码里没有类 {simple}"
                    )

    if problems:
        print(f"[check_manifest] 检查 {checked} 个组件，发现 {len(problems)} 个问题：")
        for p in problems:
            print("  FAIL " + p)
        return 1

    print(f"[check_manifest] OK：{checked} 个组件声明都能在源码里找到对应类")
    return 0


if __name__ == "__main__":
    sys.exit(main())

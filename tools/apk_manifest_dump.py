#!/usr/bin/env python3
"""
直接读取 APK 内的二进制 AndroidManifest.xml（AXML），打印组件实际生效的类名。

为什么需要它：APK 里的 Manifest 是编译过的二进制格式，`unzip -p` 出来是乱码，
用文本方式 grep 根本看不到 application 指向哪个类。而「类名写错」恰恰是
编译期完全不报错、只有装机才暴露的那类错（本项目就踩过：
manifest 写 .VaultApp，实际类叫 VaultApplication，装机必闪退）。

用法：
    python3 tools/apk_manifest_dump.py <apk路径>
退出码：0 = application 指向 VaultApplication；1 = 有问题
"""
import struct
import sys
import zipfile

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
UTF8_FLAG = 0x100

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def _decode_length8(data, pos):
    """UTF-8 池的变长长度：1 或 2 字节"""
    n = data[pos]
    if n & 0x80:
        return ((n & 0x7F) << 8) | data[pos + 1], pos + 2
    return n, pos + 1


def read_string_pool(data, start):
    """解析字符串池，返回字符串列表"""
    _ctype, _hsize, csize = struct.unpack_from("<HHI", data, start)
    string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", data, start + 8
    )
    utf8 = bool(flags & UTF8_FLAG)
    offsets = struct.unpack_from("<%dI" % string_count, data, start + 28)
    base = start + strings_start

    out = []
    for off in offsets:
        pos = base + off
        if utf8:
            # 跳过 utf16 长度，再取 utf8 字节长度
            _, pos = _decode_length8(data, pos)
            byte_len, pos = _decode_length8(data, pos)
            out.append(data[pos:pos + byte_len].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if n & 0x8000:
                n2 = struct.unpack_from("<H", data, pos)[0]
                n = ((n & 0x7FFF) << 16) | n2
                pos += 2
            out.append(data[pos:pos + n * 2].decode("utf-16-le", "replace"))
    return out


def parse_elements(data):
    """遍历 chunk，返回 [(元素名, {(命名空间, 属性名): 值})]"""
    ctype, hsize, total = struct.unpack_from("<HHI", data, 0)
    if ctype != RES_XML_TYPE:
        raise ValueError("不是 AXML")

    pos = hsize
    strings = []
    elements = []

    while pos < total:
        ctype, _chdr, csize = struct.unpack_from("<HHI", data, pos)
        if csize <= 0:
            break

        if ctype == RES_STRING_POOL_TYPE:
            strings = read_string_pool(data, pos)

        elif ctype == RES_XML_START_ELEMENT_TYPE:
            # ResXMLTree_node: header(8) + lineNumber(4) + comment(4) = 16
            # ResXMLTree_attrExt 紧随其后：ns(4) name(4) attributeStart(2)
            #   attributeSize(2) attributeCount(2) id/class/style(6)
            ns_idx, name_idx = struct.unpack_from("<iI", data, pos + 16)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)

            name = strings[name_idx] if name_idx < len(strings) else "?"

            attrs = {}
            # attributeStart 是相对 attrExt 起点的偏移（即 pos+16），不是相对 chunk 起点
            base = pos + 16 + attr_start
            for i in range(attr_count):
                p = base + i * attr_size
                a_ns, a_name, a_raw = struct.unpack_from("<iII", data, p)
                data_type = data[p + 15]
                raw_data = struct.unpack_from("<I", data, p + 16)[0]

                key = strings[a_name] if a_name < len(strings) else "?"
                if a_raw != 0xFFFFFFFF and a_raw < len(strings):
                    val = strings[a_raw]
                elif data_type == 0x03:  # TYPE_STRING
                    val = strings[raw_data] if raw_data < len(strings) else ""
                elif data_type == 0x12:  # TYPE_INT_BOOLEAN
                    val = "true" if raw_data else "false"
                elif data_type == 0x10:  # TYPE_INT_DEC
                    val = str(struct.unpack("<i", struct.pack("<I", raw_data))[0])
                else:
                    val = "0x%08x" % raw_data

                ns = strings[a_ns] if 0 <= a_ns < len(strings) else ""
                attrs[(ns, key)] = val

            elements.append((name, attrs))

        pos += csize

    return elements


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    apk = sys.argv[1]
    with zipfile.ZipFile(apk) as z:
        data = z.read("AndroidManifest.xml")

    elements = parse_elements(data)

    pkg = ""
    for name, attrs in elements:
        if name == "manifest":
            pkg = attrs.get(("", "package"), "")
            break

    print("包名:", pkg)
    print()

    components = ("application", "activity", "service", "receiver", "provider")
    app_class = ""
    for name, attrs in elements:
        cname = attrs.get((ANDROID_NS, "name"))
        if name in components and cname:
            full = pkg + cname if cname.startswith(".") else cname
            print(f"  {name:11s} -> {full}")
            if name == "application":
                app_class = full
                for (ns, k), v in attrs.items():
                    if ns == ANDROID_NS and k != "name":
                        print(f"  {'':11s}    {k} = {v}")

    print()
    if not app_class:
        print("FAIL: 没找到 application 的 android:name")
        return 1

    expect = (pkg + ".VaultApplication") if pkg else "VaultApplication"
    if app_class != expect:
        print(f"FAIL: application 指向 {app_class}，应为 {expect}")
        print("      这个类不存在的话，手机上一启动就会 ClassNotFoundException 秒退")
        return 1

    print(f"OK: application 指向 {app_class}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

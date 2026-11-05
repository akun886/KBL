#!/usr/bin/env python3
"""
电视截图助手。

这台 MiTV 的 shell 会在 stdout 前多打一行 "argc: N"，直接 exec-out 重定向会得到
一个带垃圾前缀的 PNG。这里按 PNG 魔数切片修掉，顺便绕开 Git Bash 对 /sdcard 的路径转换。

用法：python tools/shot.py build/shots/02-probe.png
"""

import subprocess
import sys
import os

ADB = r"C:\Users\Kun\android-sdk\platform-tools\adb.exe"
SERIAL = "192.168.2.185:5555"
SIG = b"\x89PNG\r\n\x1a\n"


def adb(*args, binary=False):
    cmd = [ADB, "-s", SERIAL, *args]
    return subprocess.run(cmd, capture_output=binary, text=not binary)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "build/shots/shot.png"
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    subprocess.run([ADB, "connect", SERIAL], capture_output=True)

    p = subprocess.run(
        [ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
        capture_output=True,
    )
    data = p.stdout
    i = data.find(SIG)
    if i < 0:
        raise SystemExit("没拿到 PNG：" + data[:120].decode("utf-8", "replace"))

    with open(out, "wb") as f:
        f.write(data[i:])
    print(f"{out}  {len(data) - i} bytes（裁掉前缀 {i} 字节）")


if __name__ == "__main__":
    main()

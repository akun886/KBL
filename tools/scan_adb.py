#!/usr/bin/env python3
"""
扫本地网段里开着 5555（ADB over network）的设备。
只连 5555 端口做 TCP 握手，不发任何数据，扫完即断。
用法：python tools/scan_adb.py 192.168.2 192.168.8
"""

import concurrent.futures
import socket
import sys


def probe(ip, port=5555, timeout=0.45):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        if s.connect_ex((ip, port)) == 0:
            # 试探一下是不是 ADB：连上后读一小段，ADB 会先发 CNXN 或等待握手
            try:
                s.settimeout(0.6)
                s.sendall(b"000c00000000000000000000")
                data = s.recv(4)
            except Exception:
                data = b""
            return ip, data
    except Exception:
        pass
    finally:
        s.close()
    return None


def main():
    prefixes = sys.argv[1:] or ["192.168.2", "192.168.8"]
    targets = [f"{p}.{i}" for p in prefixes for i in range(1, 255)]
    print(f"扫描 {len(targets)} 个地址的 5555 端口…")
    hits = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=160) as ex:
        for r in ex.map(probe, targets):
            if r:
                hits.append(r)
                print(f"  ✔ {r[0]}:5555  握手回应={r[1]!r}")
    if not hits:
        print("没找到。")
    return hits


if __name__ == "__main__":
    main()

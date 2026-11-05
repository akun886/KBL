#!/usr/bin/env python3
"""
KunPlayer 图标生成器 —— 零依赖（不需要 Pillow）。

K 的竖笔与上臂用白色，下臂用主题橙 #FF8A3D；底色 #0E1215。
橙色与界面焦点色是同一个值，否则屏幕上会出现两种橙。

用法： python tools/make_icons.py
输出： app/src/main/res/mipmap-*/ic_launcher.png 与 drawable-nodpi/banner.png
"""

import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BG = (14, 18, 21, 255)
WHITE = (242, 245, 247, 255)
ORANGE = (255, 138, 61, 255)

# 归一化坐标下的 K（0..1），三个多边形
STEM = [(0.255, 0.185), (0.395, 0.185), (0.395, 0.815), (0.255, 0.815)]
ARM_UP = [(0.425, 0.500), (0.665, 0.185), (0.815, 0.185), (0.575, 0.500)]
ARM_DOWN = [(0.425, 0.500), (0.575, 0.500), (0.815, 0.815), (0.665, 0.815)]


def inside(poly, x, y):
    """射线法判断点是否在多边形内"""
    result = False
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xt = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if x < xt:
                result = not result
    return result


def rounded(x, y, radius):
    """圆角矩形遮罩，坐标归一化"""
    if radius <= 0:
        return True
    dx = max(radius - x, 0) + max(x - (1 - radius), 0)
    dy = max(radius - y, 0) + max(y - (1 - radius), 0)
    return dx * dx + dy * dy <= radius * radius


def blend(dst, src, alpha):
    return tuple(int(d + (s - d) * alpha) for d, s in zip(dst[:3], src[:3])) + (255,)


def render(size, icon=True, ss=4):
    """超采样 ss 倍再降采样，边缘才不锯齿"""
    w = h = size
    big = size * ss
    rows = []
    for py in range(big):
        y = (py + 0.5) / big
        row = []
        for px in range(big):
            x = (px + 0.5) / big
            if icon and not rounded(x, y, 0.22):
                row.append((0, 0, 0, 0))
                continue
            c = BG
            if inside(STEM, x, y) or inside(ARM_UP, x, y):
                c = WHITE
            elif inside(ARM_DOWN, x, y):
                c = ORANGE
            row.append(c)
        rows.append(row)

    # 降采样（盒式平均）
    out = []
    for py in range(h):
        row = []
        for px in range(w):
            acc = [0, 0, 0, 0]
            for dy in range(ss):
                for dx in range(ss):
                    p = rows[py * ss + dy][px * ss + dx]
                    for i in range(4):
                        acc[i] += p[i]
            n = ss * ss
            row.append(tuple(v // n for v in acc))
        out.append(row)
    return out


def render_banner(w=320, h=180):
    """320x180 基图，系统在 4K 面板上自己缩放，不必维护两套"""
    ss = 3
    bigw, bigh = w * ss, h * ss
    # K 占左侧正方形区域，右侧留白（桌面会给标题留位置）
    box = bigh
    offx = int(w * 0.06) * ss
    rows = []
    for py in range(bigh):
        row = []
        for px in range(bigw):
            c = BG
            if offx <= px < offx + box and py < box:
                nx = (px - offx + 0.5) / box
                ny = (py + 0.5) / box
                if inside(STEM, nx, ny) or inside(ARM_UP, nx, ny):
                    c = WHITE
                elif inside(ARM_DOWN, nx, ny):
                    c = ORANGE
            row.append(c)
        rows.append(row)

    out = []
    for py in range(h):
        row = []
        for px in range(w):
            acc = [0, 0, 0, 0]
            for dy in range(ss):
                for dx in range(ss):
                    p = rows[py * ss + dy][px * ss + dx]
                    for i in range(4):
                        acc[i] += p[i]
            n = ss * ss
            row.append(tuple(v // n for v in acc))
        out.append(row)
    return out


def write_png(path, pixels):
    h = len(pixels)
    w = len(pixels[0])
    raw = b"".join(
        b"\x00" + bytes(v for px in row for v in px) for row in pixels
    )

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print(f"  {os.path.relpath(path, ROOT)}  {w}x{h}")


def main():
    print("生成图标：")
    for folder, size in [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]:
        write_png(os.path.join(RES, folder, "ic_launcher.png"), render(size))

    write_png(os.path.join(RES, "drawable-nodpi", "banner.png"), render_banner())
    print("完成。")


if __name__ == "__main__":
    main()

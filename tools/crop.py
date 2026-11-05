#!/usr/bin/env python3
"""裁剪并放大 PNG，用来看电视截图上的细节。

不依赖 Pillow：这台机器上没装，而为了看一张图去装个轮子不值当。
PNG 的解码无非是 zlib 解压 + 逐行反过滤，编码无非是把过滤类型 0 的行
再压回去，几十行就够。

用法：
    python tools/crop.py 输入.png 输出.png X Y W H [放大倍数]
"""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    pos = 8
    idat = b""
    w = h = bit = color = None
    palette = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bit, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "暂不支持隔行扫描"
        elif typ == b"PLTE":
            palette = body
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        pos += 12 + ln

    assert bit == 8, f"只支持 8 位，拿到 {bit}"
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)

    stride = w * channels
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    # 统一成 RGB
    rgb = bytearray(w * h * 3)
    for i in range(w * h):
        if color == 2:
            rgb[i * 3:i * 3 + 3] = out[i * 3:i * 3 + 3]
        elif color == 6:
            rgb[i * 3:i * 3 + 3] = out[i * 4:i * 4 + 3]
        elif color == 0:
            v = out[i]
            rgb[i * 3] = rgb[i * 3 + 1] = rgb[i * 3 + 2] = v
        elif color == 3:
            idx = out[i] * 3
            rgb[i * 3:i * 3 + 3] = palette[idx:idx + 3]
    return w, h, rgb


def write_png(path, w, h, rgb):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgb[y * w * 3:(y + 1) * w * 3]
    def chunk(typ, body):
        return (struct.pack(">I", len(body)) + typ + body +
                struct.pack(">I", zlib.crc32(typ + body) & 0xFFFFFFFF))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 6))
    png += chunk(b"IEND", b"")
    open(path, "wb").write(png)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    x, y, cw, ch = (int(v) for v in sys.argv[3:7])
    scale = int(sys.argv[7]) if len(sys.argv) > 7 else 2
    w, h, rgb = read_png(src)
    x = max(0, min(x, w - 1))
    y = max(0, min(y, h - 1))
    cw = min(cw, w - x)
    ch = min(ch, h - y)
    ow, oh = cw * scale, ch * scale
    out = bytearray(ow * oh * 3)
    for j in range(oh):
        sy = y + j // scale
        row = bytearray()
        for i in range(ow):
            sx = x + i // scale
            o = (sy * w + sx) * 3
            row += rgb[o:o + 3]
        out[j * ow * 3:(j + 1) * ow * 3] = row
    write_png(dst, ow, oh, out)
    print(f"{dst} {ow}x{oh} (源 {w}x{h})")


if __name__ == "__main__":
    main()

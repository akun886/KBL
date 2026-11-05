"""零依赖 PNG 读取：只够取指定坐标的颜色，用来核对 UI 的实际渲染色值。

本机 Python 3.13 装不上 Pillow（没有对应 wheel），所以直接按 PNG 规范解：
IHDR → 拼接 IDAT → zlib 解压 → 逐行反滤波。够用即可，不做调色板/隔行/16 位。
"""

import struct
import sys
import zlib


def load(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    pos = 8
    width = height = bitdepth = colortype = None
    idat = bytearray()
    while pos < len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bitdepth, colortype = struct.unpack(">IIBB", body[:10])
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"IEND":
            break

    assert bitdepth == 8, f"只支持 8 位色深，实际 {bitdepth}"
    channels = {2: 3, 6: 4, 0: 1}[colortype]
    raw = zlib.decompress(bytes(idat))
    stride = width * channels

    out = bytearray(stride * height)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        ftype = raw[src]
        src += 1
        line = bytearray(raw[src:src + stride])
        src += stride
        if ftype == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    return width, height, channels, out


def pixels(path, coords):
    width, height, channels, buf = load(path)
    for (x, y) in coords:
        off = (y * width + x) * channels
        r, g, b = buf[off], buf[off + 1], buf[off + 2]
        print(f"({x},{y}) = #{r:02X}{g:02X}{b:02X}")


if __name__ == "__main__":
    path = sys.argv[1]
    coords = [tuple(int(v) for v in a.split(",")) for a in sys.argv[2:]]
    print(path)
    pixels(path, coords)

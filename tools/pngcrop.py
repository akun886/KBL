"""零依赖 PNG 裁图。本机 Python 3.13 没有 Pillow，装它要编译，不值得。

用途：从 adb 截的大图里裁出一小块看细节（底栏 2dp 的线、行高、对齐）。

    python tools/pngcrop.py in.png out.png x y w h
"""
import sys
import zlib
from struct import unpack, pack


def read_png(path):
    d = open(path, 'rb').read()
    pos = 8
    w = h = bd = ct = None
    idat = b''
    while pos < len(d):
        ln = unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, bd, ct = unpack('>IIBB', data[:10])
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    bpp = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    stride = w * bpp
    out = bytearray(stride * h)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bpp, out


def write_png(path, w, h, bpp, pixels):
    ct = {1: 0, 3: 2, 4: 6}[bpp]
    raw = b''.join(b'\x00' + bytes(pixels[y * w * bpp:(y + 1) * w * bpp]) for y in range(h))

    def chunk(tag, data):
        c = tag + data
        return pack('>I', len(data)) + c + pack('>I', zlib.crc32(c) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', pack('>IIBBBBB', w, h, 8, ct, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 6))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def main():
    src, dst, x, y, w, h = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:7])
    W, H, bpp, px = read_png(src)
    x = max(0, x)
    y = max(0, y)
    w = min(w, W - x)
    h = min(h, H - y)
    out = bytearray()
    for row in range(h):
        start = ((y + row) * W + x) * bpp
        out += px[start:start + w * bpp]
    write_png(dst, w, h, bpp, out)
    print(f'{src} -> {dst} {w}x{h}')


main()

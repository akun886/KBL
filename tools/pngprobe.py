"""零依赖量像素：打印某一行上亮像素的连续区段，用来核对控件是否居中。

    python tools/pngprobe.py in.png y [min_bright]

只读 PNG（非隔行、8bit），不依赖 Pillow。用途是量「播放键圆心中不中」这种事——
肉眼估 1080 宽截图里的 10px 偏差不可靠。
"""
import sys
import zlib
from struct import unpack


def read(path):
    d = open(path, 'rb').read()
    pos, idat, w, h, ct = 8, b'', 0, 0, 6
    while pos < len(d):
        ln = unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, _bd, ct = unpack('>IIBB', data[:10])
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
                c = prev[i - bpp] if i >= bpp else 0
                b = prev[i]
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bpp, out


def main():
    path, y = sys.argv[1], int(sys.argv[2])
    floor = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    w, h, bpp, px = read(path)
    stride = w * bpp
    runs, start = [], None
    for x in range(w):
        i = y * stride + x * bpp
        bright = px[i] > floor and px[i + 1] > floor and px[i + 2] > floor
        if bright and start is None:
            start = x
        elif not bright and start is not None:
            runs.append((start, x - 1))
            start = None
    if start is not None:
        runs.append((start, w - 1))
    print(f"{w}x{h}  y={y}  阈值>{floor}")
    for a, b in runs:
        if b - a >= 2:
            print(f"  x {a}..{b}  宽 {b - a + 1}  中心 {(a + b) / 2:.1f}")


if __name__ == '__main__':
    main()

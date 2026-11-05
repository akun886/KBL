#!/usr/bin/env python3
"""探 B 站 CC 字幕：一条稿件到底有没有字幕、有几条、长什么样。

    python tools/probe_subtitle.py <bvid> <cid>

三步，和 biu 的取数链路一致：
  1. /x/web-interface/nav          取 wbi_img 的两个 key（未登录不给，必须带 SESSDATA）
  2. 重排取 32 位 mixin key，给 /x/player/wbi/v2 签名
  3. 有 subtitle_url 就直接 GET 那份 JSON —— 公开 CDN，不带 Cookie

零依赖，只用标准库。Cookie 从环境变量 BILI_COOKIE 读，形如
    BILI_COOKIE='SESSDATA=xxx; buvid3=yyy'
不写进任何文件。
"""
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)

# WBI 的混淆表。img_key + sub_key 拼成 64 位，按这张表取出前 32 位
MIXIN_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52,
]


def get(url, cookie="", referer="https://www.bilibili.com/"):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": referer,
        "Cookie": cookie,
        "Accept": "application/json, text/plain, */*",
    })
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"), strict=False)


def wbi_keys(cookie):
    nav = get("https://api.bilibili.com/x/web-interface/nav", cookie)
    img = (nav.get("data") or {}).get("wbi_img") or {}
    if not img.get("img_url"):
        raise SystemExit(f"nav 没给 wbi_img：code={nav.get('code')} {nav.get('message')}"
                         f"（未登录 / 风控都会这样）")
    key = lambda u: u.rsplit("/", 1)[-1].split(".")[0]  # noqa: E731
    return key(img["img_url"]) + key(img["sub_url"])


def sign(params, raw):
    mixin = "".join(raw[i] for i in MIXIN_TAB)[:32]
    params = dict(params, wts=int(time.time()))
    clean = {k: "".join(ch for ch in str(v) if ch not in "!'()*")
             for k, v in params.items()}
    query = urllib.parse.urlencode(sorted(clean.items()))
    return f"{query}&w_rid={hashlib.md5((query + mixin).encode()).hexdigest()}"


def probe(bvid, cid, cookie):
    raw = wbi_keys(cookie)
    q = sign({"bvid": bvid, "cid": cid}, raw)
    r = get(f"https://api.bilibili.com/x/player/wbi/v2?{q}", cookie)
    if r.get("code") != 0:
        print(f"player/wbi/v2 code={r.get('code')} {r.get('message')}")
        return
    d = r.get("data") or {}
    print(f"稿件 {d.get('bvid')} · cid {d.get('cid')} · {d.get('title')}")
    print(f"时长 {d.get('duration')}s · UP {((d.get('owner') or {}).get('name'))}")

    block = d.get("subtitle") or {}
    subs = block.get("subtitles") or []
    print(f"\nallow_submit={block.get('allow_submit')} · 字幕 {len(subs)} 条")
    for s in subs:
        print(f"  · {s.get('lan')} / {s.get('lan_doc')} · ai={s.get('ai_type')}"
              f" · {s.get('subtitle_url') or s.get('subtitle_url_v2')}")
    if not subs:
        print("  （空：UP 没传，或 cid 用错了）")
        return

    url = subs[0].get("subtitle_url") or subs[0].get("subtitle_url_v2")
    if url.startswith("//"):
        url = "https:" + url
    doc = get(url)
    body = doc.get("body") or []
    print(f"\n字幕文件 {len(body)} 行 · type={doc.get('type')} lang={doc.get('lang')}")
    for item in body[:6]:
        print(f"  [{item.get('from'):>8.2f}s → {item.get('to'):>8.2f}s] {item.get('content')}")
    if len(body) > 6:
        print(f"  …… 末行 [{body[-1].get('from'):.2f}s] {body[-1].get('content')}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    probe(sys.argv[1], sys.argv[2], os.environ.get("BILI_COOKIE", ""))

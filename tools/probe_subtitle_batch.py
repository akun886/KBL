#!/usr/bin/env python3
"""批量探字幕覆盖率：首页三栏里哪一栏的歌真的带字幕。

    python tools/probe_subtitle_batch.py rank   [ps]      流行（综合榜）
    python tools/probe_subtitle_batch.py region [pages]   音乐（分区推荐流）

回答一个问题：如果把 B 站 CC 字幕当歌词用，首页这一栏里到底有多少首能拿到？
拿得到的那部分，内容像不像歌词？

两个来源各有各的坑，和 FeedApi 里那两条注释是同一批：
  · 综合榜的 bvid 在 related_archive、cid 在顶层
  · 推荐流要 WBI 签名 + buvid3，缺 buvid 会谎报「没有更多内容」
"""
import os
import sys
import time

from probe_subtitle import get, sign, wbi_keys

RANK = "https://api.bilibili.com/x/centralization/interface/music/comprehensive/web/rank"
REGION = "https://api.bilibili.com/x/web-interface/region/feed/rcmd"


def rank_items(pn, ps):
    r = get(f"{RANK}?pn={pn}&ps={ps}&web_location=333.1351")
    out = []
    for it in (r.get("data") or {}).get("list") or []:
        arc = it.get("related_archive") or {}
        bvid = arc.get("bvid") or it.get("bvid")
        cid = it.get("cid")
        if not bvid or not cid:
            continue
        out.append({
            "bvid": bvid,
            "cid": int(cid),
            "title": (arc.get("title") or it.get("musicTitle") or "").strip(),
            "dur": arc.get("duration") or 0,
        })
    return out


def region_items(raw, cookie, pages):
    out = []
    for pn in range(1, pages + 1):
        q = sign({
            "display_id": pn, "request_cnt": 15, "from_region": 1003,
            "device": "web", "plat": 30, "web_location": "333.40138",
        }, raw)
        r = get(f"{REGION}?{q}", cookie)
        if r.get("code") != 0:
            print(f"  第 {pn} 页 code={r.get('code')} {r.get('message')}")
            continue
        for a in (r.get("data") or {}).get("archives") or []:
            if a.get("bvid") and a.get("cid"):
                out.append({
                    "bvid": a["bvid"],
                    "cid": int(a["cid"]),
                    "title": (a.get("title") or "").strip(),
                    "dur": a.get("duration") or 0,
                })
        time.sleep(0.2)
    return out


def first_line(url):
    if url.startswith("//"):
        url = "https:" + url
    body = (get(url).get("body") or [])
    for line in body:
        text = (line.get("content") or "").strip()
        if text:
            return text, len(body)
    return "", len(body)


def main():
    source = (sys.argv[1] if len(sys.argv) > 1 else "rank").lower()
    n = int(sys.argv[2]) if len(sys.argv) > 2 else (30 if source == "rank" else 2)
    cookie = os.environ.get("BILI_COOKIE", "")
    if not cookie:
        raise SystemExit("先设 BILI_COOKIE，字幕列表要登录态")

    raw = wbi_keys(cookie)
    items = rank_items(1, n) if source == "rank" else region_items(raw, cookie, n)
    print(f"{source}：{len(items)} 条\n")
    print(f"{'#':>2} {'时长':>6} {'字幕':>4} {'行数':>5}  首行 / 标题")
    print("-" * 78)

    hit = 0
    for i, it in enumerate(items, 1):
        dur = f"{it['dur'] // 60}:{it['dur'] % 60:02d}" if it["dur"] else "--"
        try:
            q = sign({"bvid": it["bvid"], "cid": it["cid"]}, raw)
            r = get(f"https://api.bilibili.com/x/player/wbi/v2?{q}", cookie)
            subs = ((r.get("data") or {}).get("subtitle") or {}).get("subtitles") or []
            if not subs:
                print(f"{i:>2} {dur:>6} {0:>4} {0:>5}  {it['title'][:34]}")
                continue
            url = subs[0].get("subtitle_url") or subs[0].get("subtitle_url_v2")
            text, lines = first_line(url)
            hit += 1
            print(f"{i:>2} {dur:>6} {len(subs):>4} {lines:>5}  {text[:22]}  | {it['title'][:20]}")
        except Exception as e:  # noqa: BLE001
            print(f"{i:>2} {dur:>6}  ERR  {type(e).__name__}: {e}")
        time.sleep(0.25)

    print("-" * 78)
    print(f"有字幕 {hit}/{len(items)}"
          f"（{hit * 100 // max(1, len(items))}%）")


if __name__ == "__main__":
    main()

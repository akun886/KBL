#!/usr/bin/env python3
"""探这次要用的四个新接口：关注列表、UP 主页、UP 投稿、收藏夹增删。

    BILI_COOKIE='SESSDATA=...; bili_jct=...' python tools/probe_follow.py

每个接口单独打一次，只看「能不能拿到」和「拿到什么形状」，
不真的删东西——删除类接口只打 OPTIONS/不存在的 media_id 看返回码。
"""
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

MIXIN_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52,
]

COOKIE = os.environ.get("BILI_COOKIE", "")


def get(url, referer="https://www.bilibili.com/"):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": referer,
        "Cookie": COOKIE,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"), strict=False)


def post_form(url, form):
    body = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(url, data=body, headers={
        "User-Agent": UA,
        "Referer": "https://www.bilibili.com/",
        "Cookie": COOKIE,
        "Content-Type": "application/x-www-form-urlencoded",
    })
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"), strict=False)


def wbi_raw():
    nav = get("https://api.bilibili.com/x/web-interface/nav")
    img = (nav.get("data") or {}).get("wbi_img") or {}
    if not img.get("img_url"):
        raise SystemExit(f"nav 没给 wbi_img：code={nav.get('code')}")
    key = lambda u: u.rsplit("/", 1)[-1].split(".")[0]  # noqa: E731
    return key(img["img_url"]) + key(img["sub_url"])


def sign(params, raw):
    mixin = "".join(raw[i] for i in MIXIN_TAB)[:32]
    params = dict(params, wts=int(time.time()))
    query = urllib.parse.urlencode(sorted(params.items()))
    import hashlib
    w_rid = hashlib.md5((query + mixin).encode()).hexdigest()
    return f"{query}&w_rid={w_rid}"


def show(title, obj, keys=None):
    print("=" * 72)
    print(title)
    print("-" * 72)
    if isinstance(obj, dict):
        print("  code=", obj.get("code"), "message=", obj.get("message"))
        data = obj.get("data")
        if isinstance(data, dict):
            if keys:
                for k in keys:
                    v = data.get(k)
                    if isinstance(v, list):
                        print(f"  data.{k}: list[{len(v)}]")
                        for item in v[:3]:
                            print("     ", json.dumps(item, ensure_ascii=False)[:230])
                    else:
                        print(f"  data.{k}:", json.dumps(v, ensure_ascii=False)[:400])
            else:
                print("  data keys:", list(data.keys()))
        else:
            print("  data:", json.dumps(data, ensure_ascii=False)[:400])
    else:
        print(json.dumps(obj, ensure_ascii=False)[:600])


def main():
    raw = wbi_raw()
    mid = os.environ.get("BILI_MID", "")
    if not mid:
        nav = get("https://api.bilibili.com/x/web-interface/nav")
        mid = str((nav.get("data") or {}).get("mid") or "")
        print("mid =", mid, "uname =", (nav.get("data") or {}).get("uname"))
        face = (nav.get("data") or {}).get("face")
        print("face =", face)

    # 1. 关注列表。注意路径是 /x/relation/followings，
    #    /x/web-interface/relations/followings 这个老路径已经 404
    lst = []
    try:
        r = get("https://api.bilibili.com/x/relation/followings"
                f"?vmid={mid}&pn=1&ps=10&order=desc&tid=0&platform=web")
        show("1. 关注列表 relation/followings", r, ["list", "total"])
        lst = ((r.get("data") or {}).get("list") or [])
        if lst:
            print("  第一条全部字段:", json.dumps(lst[0], ensure_ascii=False)[:600])
    except Exception as e:
        print("1. 关注列表失败:", e)

    # 2. UP 主页信息（需要 WBI）
    target = lst[0]["mid"] if lst else mid
    try:
        q = sign({"mid": str(target)}, raw)
        r = get(f"https://api.bilibili.com/x/space/wbi/acc/info?{q}")
        show("2. UP 主页 space/wbi/acc/info", r)
    except Exception as e:
        print("2. UP 主页失败:", e)

    # 3. UP 投稿列表
    try:
        q = sign({"mid": str(target), "pn": "1", "ps": "10", "order": "pubdate",
                  "platform": "web", "web_location": "1550101"}, raw)
        r = get(f"https://api.bilibili.com/x/space/wbi/arc/search?{q}")
        show("3. UP 投稿 space/wbi/arc/search", r)
        vlist = ((r.get("data") or {}).get("list") or {}).get("vlist") or []
        if vlist:
            print("  第一条:", json.dumps(vlist[0], ensure_ascii=False)[:500])
    except Exception as e:
        print("3. UP 投稿失败:", e)

    # 4. 收藏夹列表
    try:
        r = get(f"https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={mid}")
        show("4. 收藏夹列表", r, ["list", "count"])
    except Exception as e:
        print("4. 收藏夹列表失败:", e)

    # 5. 创建收藏夹 —— 先试一个明显不该建的名字？不行，会真的建出来。
    #    改成只打接口看返回：真要建就用临时名再删掉。
    csrf = ""
    for part in COOKIE.split(";"):
        k, _, v = part.strip().partition("=")
        if k == "bili_jct":
            csrf = urllib.parse.unquote(v)
    print("\nbili_jct 长度:", len(csrf))
    if csrf:
        tmp_name = f"kunplayer_tmp_{int(time.time())}"
        try:
            r = post_form("https://api.bilibili.com/x/v3/fav/folder/create",
                          {"title": tmp_name, "intro": "", "privacy": "0",
                           "cover": "", "type": "2", "csrf": csrf})
            show("5. 创建收藏夹 fav/folder/create", r)
            new_id = ((r.get("data") or {}) or {}).get("id")
            print("  新建 media_id =", new_id)
            if new_id:
                r2 = post_form("https://api.bilibili.com/x/v3/fav/folder/delete",
                               {"media_id": str(new_id), "csrf": csrf})
                show("6. 删除收藏夹 fav/folder/delete", r2)
        except Exception as e:
            print("5/6. 收藏夹增删失败:", e)

    # 7. 关注状态查询
    try:
        r = get("https://api.bilibili.com/x/relation/status"
                f"?fids={target}")
        show("7. 关注状态 relation/statu", r)
    except Exception as e:
        print("7. 关注状态失败:", e)

    # 8. 自己的关系列表（用于「我关注了谁」）
    try:
        r = get("https://api.bilibili.com/x/relation/relations"
                f"?vmid={mid}&fid={target}&pn=1&ps=10&relation=1&order=desc")
        show("8. relation/relations", r, ["list"])
    except Exception as e:
        print("8. relation/relations 失败:", e)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Collect plain Apple CMS (type 1) sites from every usable config and keep the ones that really answer a search.

Usage: python3 -I cms_sources.py out_dir

Reads out_dir/results.json (written by check.py), fetches each usable config (one level into multi-repo indexes),
gathers every type 1 site with an http(s) api, de-duplicates by address, and runs a real search against each.
Then downloads a real video segment from each (direct connection) and writes out_dir/cms.json as `{"sites": [...]}`
sorted by playback speed (`play`: segment download speed / play speed, 0 = would not play); the TV Player app
merges it with best.json and tries the faster sites first.
Needs no spider runtime, so the sites work in clients that cannot run jar / js / python crawlers.
"""
import concurrent.futures as cf
import importlib.util
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check", os.path.join(HERE, "check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)
# 播放测速借用 build_lives.py 的直连分片测速（绕过本机代理，电视盒子没有代理）
spec = importlib.util.spec_from_file_location("build_lives", os.path.join(HERE, "build_lives.py"))
lives = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lives)

KEYWORD = "爱"
TIMEOUT = 10
PLAY_SAMPLES = 2  # 每个站点测几部片子的播放速度，取最快的一部（一部片子的 CDN 偶尔慢不代表整个站点慢）
SMOOTH = 1.5      # 分片下载速度至少是播放速度的这么多倍才算流畅，和 build_lives.py、App 里的测速一样
# 成人站：搜索结果一半以上落在这些分类里就丢掉。主流站搜「爱」也会混进几部伦理片（实测最多 3/20），所以按比例判断
ADULT_TYPES = re.compile(r"伦理|倫理|成人|福利|情色|色情|无码|有码|自拍|偷拍|探花|麻豆|AV|里番|三级|乱伦|主播|黑料|写真")


def load_config(url):
    try:
        status, final, body, _ = check.fetch(url, timeout=15)
        if status != 200 or check.looks_like_html(body):
            return None
        return check.parse_json(body.decode("utf-8", errors="replace"))[0]
    except Exception:
        return None


def config_urls(results):
    urls = []
    for row in results["sources"]:
        if isinstance(row, dict) and row.get("ok"):
            urls.append((row["name"], row["url"], row.get("kind")))
    return urls


def collect(urls):
    """api -> (name, origin). Multi-repo indexes are expanded one level."""
    found = {}

    def add(config, origin):
        for site in (config or {}).get("sites", []) if isinstance(config, dict) else []:
            if not isinstance(site, dict) or str(site.get("type")) != "1":
                continue
            api = str(site.get("api", "")).strip()
            if not api.startswith(("http://", "https://")):
                continue
            key = api.rstrip("/").lower()
            name = re.sub(r"\[.*?]", "", str(site.get("name", "")).split("•")[-1]).strip() or site.get("key", "")
            found.setdefault(key, (name, api, origin))

    for name, url, kind in urls:
        config = load_config(url)
        if config is None:
            continue
        if kind == "multi" or "urls" in config:
            for entry in config.get("urls", [])[:30]:
                sub = entry.get("url") if isinstance(entry, dict) else None
                if sub:
                    add(load_config(urllib.parse.urljoin(url, sub)), name)
        add(config, name)
    return found


def brand_of(name):
    """站名里的品牌：「🐂┃红牛┃采集」「红牛资源」都是红牛，「❤量子资源2」是量子；只剩数字时保留（360）。"""
    b = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]|资源|影视|点播|采集", "", name)
    return re.sub(r"\d+$", "", b) or b or name


def probe(api):
    # These sites are flaky; one retry keeps a good site from being dropped by a single timeout.
    return probe_once(api) or probe_once(api)


def probe_once(api):
    """(ms, count, m3u8 地址, 前 5 个片名) when a real search answers; the addresses are the first episode of each result."""
    sep = "&" if "?" in api else "?"
    url = f"{api}{sep}ac=videolist&wd={urllib.parse.quote(KEYWORD)}&pg=1"
    try:
        status, _, body, ms = check.fetch(url, timeout=TIMEOUT)
        data = json.loads(body.decode("utf-8", errors="replace"))
        items = [x for x in data.get("list") or [] if isinstance(x, dict) and x.get("vod_name") and x.get("vod_id") not in (None, "")]
        if items and sum(bool(ADULT_TYPES.search(str(x.get("type_name") or ""))) for x in items) * 2 >= len(items):
            return None
        names = tuple(str(x.get("vod_name")) for x in items[:5])
        return (ms, len(items), [u for u in map(first_m3u8, items) if u], names) if status == 200 and items else None
    except Exception:
        return None


def first_m3u8(item):
    """第一集的 m3u8 地址：vod_play_url 是「第1集$地址#第2集$地址$$$另一组…」。"""
    for group in str(item.get("vod_play_url") or "").split("$$$"):
        for ep in group.split("#"):
            u = ep.split("$")[-1].strip()
            if u.startswith(("http://", "https://")) and ".m3u8" in u.lower():
                return u
    return None


def play_test(urls):
    """几部片子里最快的播放速度（分片下载速度 / 播放速度）；一部都放不出来是 0；没有能测的 m3u8 地址是 None（没测）。"""
    if not urls:
        return None
    best = 0.0
    for u in urls[:PLAY_SAMPLES]:
        r = lives.speed_test(u)
        if "speedup" in r and r.get("note") != "progressive":
            best = max(best, r["speedup"])
        elif r.get("note") == "segment too slow":
            # 列表拿到了，分片在来但没在它的播放时长内下完：能放但会卡，算 0.5（连不上、不回数据的还是 0）
            best = max(best, 0.5)
    return best


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "out"
    results = json.load(open(os.path.join(out_dir, "results.json"), encoding="utf-8"))
    found = collect(config_urls(results))
    extra = os.path.join(HERE, "cms-candidates.tsv")
    if os.path.exists(extra):
        for line in open(extra, encoding="utf-8"):
            if line.strip() and not line.startswith("#") and "\t" in line:
                name, api = (x.strip() for x in line.split("\t", 1))
                found.setdefault(api.rstrip("/").lower(), (name, api, "cms-candidates.tsv"))
    print(f"{len(found)} distinct type 1 sites in {len(config_urls(results))} usable configs; probing…")
    with cf.ThreadPoolExecutor(16) as pool:
        tested = list(pool.map(lambda kv: (kv, probe(kv[1][1])), found.items()))
    good = []
    for (key, (name, api, origin)), res in tested:
        if res:
            good.append({"key": key, "name": name, "type": 1, "api": api, "ms": res[0], "count": res[1], "from": origin, "urls": res[2], "sig": res[3]})
    good.sort(key=lambda s: s["ms"])
    # The same backend is often listed under several spellings (…/vod, …/vod/?ac=list); keep the fastest per host.
    seen, unique = set(), []
    for site in good:
        host = urllib.parse.urlsplit(site["api"]).netloc.lower()
        if host not in seen:
            seen.add(host)
            unique.append(site)
    good = unique
    # 真的下载视频分片测播放速度（直连）。并发低：同时下载会互相分带宽，测出来都偏慢
    print(f"play-testing {len(good)} sites (direct connection)…")
    with cf.ThreadPoolExecutor(lives.TEST_WORKERS) as pool:
        speeds = list(pool.map(lambda s: play_test(s.pop("urls")), good))
    for site, speed in zip(good, speeds):
        site["play"] = speed
    # App 按这个顺序先试哪个源（见 Store.playRanks）：能放的按播放速度从快到慢，没测的（null）其次，放不出来的垫底
    good.sort(key=lambda s: (0 if s["play"] is None or s["play"] > 0 else 1, s["play"] is None, -(s["play"] or 0), s["ms"]))
    # 同一品牌常有两三个镜像域名、内容相同，只留一个。放在测速之后，留下播放最快的那个镜像（测速前按延迟挑会留下慢镜像）
    # 站名是各家配置随手起的（不同站都可能叫「烂大街」），所以还要求搜索结果一致才算同一个站的镜像
    seen, unique = set(), []
    for site in good:
        brand = (brand_of(site["name"]), site.pop("sig"))
        if brand not in seen:
            seen.add(brand)
            unique.append(site)
    good = unique
    for s in good:
        p = s["play"]
        mark = "N/A " if p is None else "OK  " if p >= SMOOTH else "SLOW" if p > 0 else "DEAD"
        print(f"{mark} play {(p or 0):4.1f}x  {s['ms']:5d}ms  {s['count']:3d}  {s['name']:<14} {s['api'][:60]}  (from {s['from']})")
    smooth = sum((s["play"] or 0) >= SMOOTH for s in good)
    print(f"\n{len(good)} distinct sites answered a real search ({len(found)} candidates); {smooth} play smoothly here")
    with open(os.path.join(out_dir, "cms.json"), "w", encoding="utf-8") as f:
        json.dump({"sites": good}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

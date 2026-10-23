#!/usr/bin/env python3
"""Collect plain Apple CMS (type 1) sites from every usable config and keep the ones that really answer a search.

Usage: python3 -I cms_sources.py out_dir

Reads out_dir/results.json (written by check.py), fetches each usable config (one level into multi-repo indexes),
gathers every type 1 site with an http(s) api, de-duplicates by address, and runs a real search against each.
Writes out_dir/cms.json as `{"sites": [...]}` sorted by response time; the TV Player app merges it with best.json.
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

KEYWORD = "爱"
TIMEOUT = 10


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


def probe(api):
    # These sites are flaky; one retry keeps a good site from being dropped by a single timeout.
    return probe_once(api) or probe_once(api)


def probe_once(api):
    sep = "&" if "?" in api else "?"
    url = f"{api}{sep}ac=videolist&wd={urllib.parse.quote(KEYWORD)}&pg=1"
    try:
        status, _, body, ms = check.fetch(url, timeout=TIMEOUT)
        data = json.loads(body.decode("utf-8", errors="replace"))
        items = [x for x in data.get("list") or [] if isinstance(x, dict) and x.get("vod_name") and x.get("vod_id") not in (None, "")]
        return (ms, len(items)) if status == 200 and items else None
    except Exception:
        return None


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
            good.append({"key": key, "name": name, "type": 1, "api": api, "ms": res[0], "count": res[1], "from": origin})
    good.sort(key=lambda s: s["ms"])
    # The same backend is often listed under several spellings (…/vod, …/vod/?ac=list); keep the fastest per host.
    seen, unique = set(), []
    for site in good:
        host = urllib.parse.urlsplit(site["api"]).netloc.lower()
        if host not in seen:
            seen.add(host)
            unique.append(site)
    good = unique
    for s in good:
        print(f"OK   {s['ms']:5d}ms  {s['count']:3d}  {s['name']:<14} {s['api'][:60]}  (from {s['from']})")
    print(f"\n{len(good)} distinct sites answered a real search ({len(found)} candidates)")
    with open(os.path.join(out_dir, "cms.json"), "w", encoding="utf-8") as f:
        json.dump({"sites": good}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

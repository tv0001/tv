#!/usr/bin/env python3
"""Build a speed-tested CCTV / 卫视 live list for TVBox.

Usage: python3 -I build_lives.py live-sources.tsv out_dir

Collects channels from the lists in live-sources.tsv, then tests every
candidate stream over a DIRECT connection (bypassing any local proxy, since
the TV box at home has none): for HLS it downloads one real segment and
compares download time with the segment's play time. Only sources that
download comfortably faster than real time are kept, fastest first, as
out_dir/live.txt in TVBox txt format (several lines per channel = several
lines the viewer can switch between).

Run it on a machine on the same network as the TV box; results from a CI
runner abroad would say nothing about lag at home.
"""
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

UA = "okhttp/3.12.0"
LIST_TIMEOUT = 15
STREAM_TIMEOUT = 6
MAX_TESTED_PER_CHANNEL = 10  # about one try per source list
KEEP_PER_CHANNEL = 3
# Hosts whose streams were seen carrying inserted commercials (400-number health-product spots) across many
# channels, while other hosts showed the clean programme at the same moment. Used only when nothing else exists.
AD_HOSTS = ("38.75.136.137",)
# Streams that passed the speed test but showed the wrong picture when a frame was grabbed (another channel,
# a test card, a local station, audio only). Found by eye in a frame-by-frame check; add to it when you spot more.
BAD_STREAMS = ("event.pull.hebtv.com", "p2.bdstatic.com", "live.zohi.tv", "l.cztvcloud.com",
               "piccpndks.v.kcdnvip.com/audio", "218.84.12.186:8001")
MIN_SPEEDUP = 1.5        # segment must download 1.5x faster than it plays
MIN_PROGRESSIVE_KBPS = 1500  # for non-HLS streams we can only measure throughput
TEST_WORKERS = 6         # low on purpose: parallel downloads share the line and skew speed

DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))
DEFAULT = urllib.request.build_opener()  # honours proxy settings; used to fetch the lists

CCTV_RE = re.compile(r"CCTV[-\s_]*(\d{1,2})(\s*\+|\s*plus)?", re.I)
CCTV_SPECIAL = {"CCTV4K": re.compile(r"CCTV[-\s_]*4K", re.I),
                "CCTV8K": re.compile(r"CCTV[-\s_]*8K", re.I)}
WEISHI_RE = re.compile(r"([一-龥]{2,4}卫视)")
WEISHI_ORDER = ["湖南卫视", "浙江卫视", "东方卫视", "江苏卫视", "北京卫视", "广东卫视",
                "深圳卫视", "山东卫视", "天津卫视", "安徽卫视", "湖北卫视", "四川卫视",
                "重庆卫视", "东南卫视", "江西卫视", "河南卫视", "河北卫视", "辽宁卫视",
                "黑龙江卫视", "吉林卫视", "广西卫视", "云南卫视", "贵州卫视", "海南卫视"]


def get(opener, url, timeout, limit, deadline=None):
    """GET url; returns (final_url, body, seconds). Stops reading at limit bytes or
    when the deadline (seconds) passes, in which case it raises TimeoutError."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    start = time.monotonic()
    with opener.open(req, timeout=timeout) as resp:
        chunks, size = [], 0
        while size < limit:
            chunk = resp.read(min(65536, limit - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            if deadline and time.monotonic() - start > deadline:
                raise TimeoutError("too slow")
        return resp.geturl(), b"".join(chunks), time.monotonic() - start


def channel_key(name):
    """Canonical channel name, or None for channels this list doesn't cover."""
    for key, rx in CCTV_SPECIAL.items():
        if rx.search(name):
            return key
    m = CCTV_RE.search(name)
    if m:
        return f"CCTV{int(m.group(1))}" + ("+" if m.group(2) else "")
    m = WEISHI_RE.search(name)
    return m.group(1) if m else None


def parse_list(text):
    """(name, url) pairs from an m3u or TVBox txt list."""
    pairs, pending = [], None
    for ln in text.splitlines():
        ln = ln.strip()
        if not ln:
            continue
        if ln.startswith("#EXTINF"):
            m = re.search(r'tvg-name="([^"]+)"', ln)
            pending = m.group(1) if m else ln.rsplit(",", 1)[-1].strip()
            continue
        if ln.startswith("#"):
            continue
        if "://" in ln and "," in ln and not ln.split(",", 1)[0].count("://"):
            name, rest = ln.split(",", 1)
            for url in rest.split("#"):
                url = url.split("$")[0].strip()
                if url.startswith(("http://", "https://")):
                    pairs.append((name.strip(), url))
            continue
        if ln.startswith(("http://", "https://")) and pending:
            pairs.append((pending, ln))
        pending = None
    return pairs


def hls_segment(playlist_url, text):
    """(segment_url, duration) near the live edge, following a master playlist once."""
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    if any(ln.startswith("#EXT-X-STREAM-INF") for ln in lines):
        variant = next((ln for ln in lines if not ln.startswith("#")), None)
        if not variant:
            return None
        variant_url = urllib.parse.urljoin(playlist_url, variant)
        final, body, _ = get(DIRECT, variant_url, STREAM_TIMEOUT, 512 * 1024)
        return hls_segment(final, body.decode("utf-8", "replace"))
    segments, duration = [], None
    for ln in lines:
        if ln.startswith("#EXTINF"):
            try:
                duration = float(ln.split(":", 1)[1].split(",")[0])
            except ValueError:
                duration = None
        elif not ln.startswith("#") and duration:
            segments.append((urllib.parse.urljoin(playlist_url, ln), duration))
            duration = None
    if not segments:
        return None
    return segments[-2] if len(segments) > 1 else segments[0]


def speed_test(url):
    """{"ok", "speedup" or "kbps", "note"} for one stream over a direct connection."""
    try:
        final, body, secs = get(DIRECT, url, STREAM_TIMEOUT, 1024 * 1024, deadline=8)
    except Exception as e:
        return {"ok": False, "note": type(e).__name__}
    if not body or body[:1024].lstrip().lower().startswith(b"<"):
        return {"ok": False, "note": "empty or HTML"}
    head = body[:4096]
    if head.startswith(b"#EXTM3U") or b"#EXTINF" in head or b"#EXT-X-STREAM-INF" in head:
        try:
            seg = hls_segment(final, body.decode("utf-8", "replace"))
            if not seg:
                return {"ok": False, "note": "no segments"}
            seg_url, duration = seg
            # Abort once the download takes longer than the segment plays: that lags for sure.
            _, data, secs = get(DIRECT, seg_url, STREAM_TIMEOUT, 32 * 1024 * 1024, deadline=duration)
        except Exception as e:
            return {"ok": False, "note": f"segment {type(e).__name__}"}
        if len(data) < 1024:
            return {"ok": False, "note": "segment too small"}
        speedup = duration / max(secs, 0.01)
        return {"ok": speedup >= MIN_SPEEDUP, "speedup": round(speedup, 1),
                "kbps": int(len(data) * 8 / max(secs, 0.01) / 1000), "note": ""}
    kbps = int(len(body) * 8 / max(secs, 0.01) / 1000)
    return {"ok": kbps >= MIN_PROGRESSIVE_KBPS, "speedup": round(kbps / 2000, 1),
            "kbps": kbps, "note": "progressive"}


def read_sources(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if ln and not ln.startswith("#") and "\t" in ln:
                name, url = [p.strip() for p in ln.split("\t", 1)]
                rows.append((name, url))
    return rows


def fetch_list(row):
    name, url = row
    try:
        _, body, _ = get(DEFAULT, url, LIST_TIMEOUT, 8 * 1024 * 1024)
        pairs = parse_list(body.decode("utf-8-sig", "replace"))
        return name, pairs, ""
    except Exception as e:
        return name, [], type(e).__name__


def channel_sort_key(key):
    if key.startswith("CCTV"):
        rest = key[4:]
        num = int(re.sub(r"\D", "", rest) or 99)
        return (0, num, rest)
    return (1, WEISHI_ORDER.index(key) if key in WEISHI_ORDER else 99, key)


def main():
    src_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    with cf.ThreadPoolExecutor(max_workers=6) as pool:
        lists = list(pool.map(fetch_list, read_sources(src_path)))

    # Round-robin across lists so each channel's test budget covers several providers.
    per_list, origin = [], {}  # origin: url -> first list it came from, for per-list stats
    for name, pairs, note in lists:
        print(f"list {name}: {len(pairs)} entries {note}")
        bucket = {}
        for ch, url in pairs:
            key = channel_key(ch)
            if key:
                bucket.setdefault(key, []).append(url)
                origin.setdefault(url, name)
        per_list.append(bucket)
    candidates = {}
    for key in sorted({k for b in per_list for k in b}, key=channel_sort_key):
        seen, picked = set(), []
        queues = [list(b.get(key, [])) for b in per_list]
        while len(picked) < MAX_TESTED_PER_CHANNEL and any(queues):
            for q in queues:
                while q:
                    u = q.pop(0)
                    if u not in seen:
                        seen.add(u)
                        picked.append(u)
                        break
                if len(picked) >= MAX_TESTED_PER_CHANNEL:
                    break
        candidates[key] = picked

    jobs = [(key, url) for key, urls in candidates.items() for url in urls]
    print(f"testing {len(jobs)} streams across {len(candidates)} channels (direct connection)...")
    with cf.ThreadPoolExecutor(max_workers=TEST_WORKERS) as pool:
        tested = list(pool.map(lambda j: (j[0], j[1], speed_test(j[1])), jobs))

    good, stats = {}, {}
    for key, url, res in tested:
        st = stats.setdefault(origin[url], [0, 0])
        st[0] += 1
        if res["ok"]:
            st[1] += 1
            good.setdefault(key, []).append((res["speedup"], url))
    print("\npassed / tested per list:")
    for name, (n, ok) in sorted(stats.items(), key=lambda kv: -kv[1][1]):
        print(f"  {ok:>3}/{n:<3} {name}")
    lines, report = [], {"channels": {}, "missing": []}
    groups = [("央视", [k for k in candidates if k.startswith("CCTV")]),
              ("卫视", [k for k in candidates if not k.startswith("CCTV")])]
    for group, keys in groups:
        kept = [k for k in keys if k in good]
        if kept:
            lines.append(f"{group},#genre#")
        for key in keys:
            if key not in good:
                report["missing"].append(key)
                continue
            ranked = [x for x in sorted(good[key], reverse=True) if not any(b in x[1] for b in BAD_STREAMS)]
            if not ranked:
                report["missing"].append(key)
                continue
            clean = [x for x in ranked if not any(h in x[1] for h in AD_HOSTS)]
            best = (clean or ranked[:1])[:KEEP_PER_CHANNEL]
            report["channels"][key] = [{"url": u, "speedup": s} for s, u in best]
            lines.extend(f"{key},{u}" for _, u in best)

    with open(os.path.join(out_dir, "live.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(out_dir, "live-report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    ok_cctv = [k for k in report["channels"] if k.startswith("CCTV")]
    ok_ws = [k for k in report["channels"] if not k.startswith("CCTV")]
    print(f"\nCCTV ok ({len(ok_cctv)}): {' '.join(ok_cctv)}")
    print(f"卫视 ok ({len(ok_ws)}): {' '.join(ok_ws)}")
    print(f"missing ({len(report['missing'])}): {' '.join(report['missing'])}")
    return 0 if report["channels"] else 1


if __name__ == "__main__":
    sys.exit(main())

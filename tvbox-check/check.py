#!/usr/bin/env python3
"""Probe TVBox source URLs and report which ones respond with usable content.

Usage: python3 -I check.py candidates.tsv out_dir [lives.tsv]

Only issues GET requests and parses responses in memory. Nothing is saved
from the network except the summary files written to out_dir, and nothing
downloaded is executed.

A config only counts as working if its spider jar downloads too. The chosen
config's live lists are checked by sampling their stream URLs; dead ones are
dropped and working lists from lives.tsv are added.

Exits 1 without touching best.json / tvbox-multi.json when no config works,
so a bad run (runner outage, geo-blocking) never publishes an empty list.
"""
import base64
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT = 10
MAX_BYTES = 2 * 1024 * 1024
UA = "okhttp/3.12.0"  # TVBox-style clients send an okhttp UA; some hosts reject others
CONFIG_KEYS = ("sites", "lives", "urls", "spider", "wallpaper")
# Fields that may hold paths relative to the config file. Only "./" and "../"
# values are rewritten: "/..." inside site ext rules usually means a path on the
# scraped video site, not on the config host.
REL_KEYS = ("spider", "jar", "api", "ext", "url")
URL_RE = re.compile(r"^[a-z][a-z0-9+.-]*://\S+$", re.I)
STATE_FILE = "best-source.json"  # which source best.json came from, for sticky selection

STREAM_TIMEOUT = 6
LIVE_SAMPLE = 8      # streams sampled per live list
LIVE_MIN_ALIVE = 2   # a list passes when at least this many sampled streams respond
# Prefix for raw.githubusercontent.com URLs written into best.json, since that
# host is often unreachable from mainland China. Set GH_PROXY="" to disable.
GH_PROXY = os.environ.get("GH_PROXY", "https://gh-proxy.com/")


def normalize(url):
    """Punycode the host and percent-encode non-ASCII path/query so urllib accepts it."""
    p = urllib.parse.urlsplit(url)
    host = p.hostname.encode("idna").decode("ascii") if p.hostname else ""
    netloc = host + (f":{p.port}" if p.port else "")
    path = urllib.parse.quote(p.path, safe="/%:@!$&'()*+,;=-._~")
    query = urllib.parse.quote(p.query, safe="=&%:/?+-._~!$'()*,;@")
    return urllib.parse.urlunsplit((p.scheme, netloc, path, query, ""))


def fetch(url, timeout=TIMEOUT, limit=MAX_BYTES, headers=None):
    """GET url and return (status, final_url, body, ms); body is capped at limit+1 bytes.
    Raises urllib.error.HTTPError for 4xx/5xx and other exceptions for network errors."""
    req = urllib.request.Request(normalize(url), headers={"User-Agent": UA, **(headers or {})})
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read(limit + 1)
        return resp.status, resp.geturl(), body, int((time.monotonic() - start) * 1000)


def looks_like_html(data):
    head = data[:1024].lower()
    if isinstance(head, bytes):
        head = head.decode("utf-8", errors="replace")
    head = head.lstrip()
    return head.startswith("<") or "<html" in head or "<!doctype" in head


def absolutize(node, base):
    """Turn relative jar/js/ext paths into absolute ones so best.json works when hosted elsewhere."""
    if isinstance(node, dict):
        return {k: absolutize_value(v, base) if k in REL_KEYS else absolutize(v, base)
                for k, v in node.items()}
    if isinstance(node, list):
        return [absolutize(x, base) for x in node]
    return node


def absolutize_value(value, base):
    if isinstance(value, str) and value.startswith(("./", "../")):
        return urllib.parse.urljoin(base, value)
    return absolutize(value, base)


def parse_json(text):
    """Parse strictly, then retry without whole-line // comments and trailing commas,
    which TVBox clients tolerate. Raises json.JSONDecodeError if both fail."""
    try:
        return json.loads(text), ""
    except json.JSONDecodeError:
        pass
    cleaned = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("//"))
    cleaned = re.sub(r",\s*([}\]])", r"\1", cleaned)
    return json.loads(cleaned), "parsed after stripping // comments"


def count(value):
    return len(value) if isinstance(value, list) else 0


def stream_urls(text):
    """Stream URLs from a TVBox txt ("name,url") or m3u live list."""
    urls = []
    for ln in text.splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("#") or "#genre#" in ln:
            continue  # m3u directives / comments / txt group headers
        if "," in ln and not URL_RE.match(ln):
            ln = ln.split(",", 1)[1].strip()
        # txt lists allow several sources per channel ("u1#u2") and labels ("u$label")
        ln = ln.split("#")[0].split("$")[0].strip()
        if URL_RE.match(ln):
            urls.append(ln)
    return urls


def spider_ok(spider):
    """The spider jar must download, or every csp_ site in the config is dead."""
    url = spider.split(";")[0].strip()  # "url;md5;hash"
    if url.startswith("img+"):  # jar appended to an image
        url = url[4:]
    if not URL_RE.match(url):
        return True, ""  # bundled or unusual form we can't verify; don't reject on it
    try:
        status, _, body, _ = fetch(url, limit=4096, headers={"Range": "bytes=0-4095"})
    except urllib.error.HTTPError as e:
        return False, f"spider jar http {e.code}"
    except Exception as e:
        return False, f"spider jar {type(e).__name__}"
    if status not in (200, 206) or len(body) < 512 or looks_like_html(body):
        return False, "spider jar is not a jar"
    return True, ""


def probe(name, url):
    result = {"name": name, "url": url, "ok": False, "status": None,
              "ms": None, "kind": None, "note": ""}
    try:
        status, final_url, body, ms = fetch(url)
    except urllib.error.HTTPError as e:
        result["status"] = e.code
        result["note"] = "http error"
        return result
    except Exception as e:
        result["note"] = type(e).__name__
        return result
    result["status"], result["ms"] = status, ms
    if final_url != normalize(url):
        result["final_url"] = final_url
    if len(body) > MAX_BYTES:
        result["note"] = "too large, skipped"
        return result

    text = body.decode("utf-8-sig", errors="replace").strip()
    if not text:
        result["note"] = "empty body"
        return result

    if text[:1] in "{[":
        try:
            data, note = parse_json(text)
        except json.JSONDecodeError:
            result["kind"] = "json-invalid"
            result["note"] = "looks like JSON but does not parse"
            return result
        result["note"] = note
        if not (isinstance(data, dict) and any(k in data for k in CONFIG_KEYS)):
            result["kind"] = "json-other"
            result["note"] = "JSON without TVBox keys"
            return result
        # A multi-repo index is usable on its own, but is not merged into the
        # outputs: nesting one index inside tvbox-multi.json would loop.
        if count(data.get("urls")) and not count(data.get("sites")):
            result["kind"] = "multi"
            result["repos"] = count(data["urls"])
            result["ok"] = True
            return result
        result["kind"] = "config"
        result["sites"] = count(data.get("sites"))
        result["lives"] = count(data.get("lives"))
        if not (result["sites"] or result["lives"]):
            result["note"] = "config has no sites or lives"
            return result
        data = absolutize(data, final_url)
        if isinstance(data.get("spider"), str):
            jar_ok, jar_note = spider_ok(data["spider"])
            if not jar_ok:
                result["note"] = jar_note
                return result
        result["ok"] = True
        result["_config"] = data  # kept in memory only, for best.json
        return result

    if looks_like_html(text):
        result["kind"] = "html"
        result["note"] = "HTML page, not a config or live list"
        return result

    # Live playlist: usable if it has at least a few stream lines.
    result["kind"] = "live-list"
    result["lines"] = len(stream_urls(text))
    result["ok"] = result["lines"] >= 3
    if not result["ok"]:
        result["note"] = "no stream lines found"
    return result


def safe_probe(name, url):
    """One broken source must never abort the whole run."""
    try:
        return probe(name, url)
    except Exception as e:
        return {"name": name, "url": url, "ok": False, "status": None, "ms": None,
                "kind": None, "note": f"probe crashed: {type(e).__name__}: {e}"}


def stream_alive(url):
    try:
        status, _, body, _ = fetch(url, timeout=STREAM_TIMEOUT, limit=2048,
                                   headers={"Range": "bytes=0-2047"})
    except Exception:
        return False
    return status in (200, 206) and bool(body) and not looks_like_html(body)


def check_live(name, url):
    """Fetch a live list and sample its http(s) streams."""
    result = {"name": name, "url": url, "ok": False, "streams": 0, "sampled": 0,
              "alive": 0, "note": ""}
    try:
        _, _, body, _ = fetch(url)
    except urllib.error.HTTPError as e:
        result["note"] = f"http {e.code}"
        return result
    except Exception as e:
        result["note"] = type(e).__name__
        return result
    text = body.decode("utf-8-sig", errors="replace")
    if looks_like_html(text):
        result["note"] = "HTML page, not a live list"
        return result
    urls = [u for u in stream_urls(text) if u.lower().startswith(("http://", "https://"))]
    result["streams"] = len(urls)
    if not urls:
        result["note"] = "no http(s) streams to verify"
        return result
    sample = urls[::max(1, len(urls) // LIVE_SAMPLE)][:LIVE_SAMPLE]
    with cf.ThreadPoolExecutor(max_workers=LIVE_SAMPLE) as pool:
        alive = sum(pool.map(stream_alive, sample))
    result.update(sampled=len(sample), alive=alive,
                  ok=alive >= min(LIVE_MIN_ALIVE, len(sample)))
    if not result["ok"]:
        result["note"] = "sampled streams are dead"
    return result


def safe_check_live(name, url):
    try:
        return check_live(name, url)
    except Exception as e:
        return {"name": name, "url": url, "ok": False, "streams": 0, "sampled": 0,
                "alive": 0, "note": f"check crashed: {type(e).__name__}: {e}"}


def live_entry_url(entry):
    """The list URL behind a config "lives" entry: plain url, or a base64 proxy:// ext."""
    if not isinstance(entry, dict):
        return None
    if isinstance(entry.get("url"), str):
        return entry["url"]
    for ch in entry.get("channels") or []:
        for u in (ch.get("urls") or []) if isinstance(ch, dict) else []:
            if isinstance(u, str) and u.startswith("proxy://") and "ext=" in u:
                ext = urllib.parse.unquote(u.split("ext=", 1)[1].split("&")[0])
                try:
                    return base64.b64decode(ext + "=" * (-len(ext) % 4)).decode("utf-8")
                except Exception:
                    return None
    return None


def with_gh_proxy(node):
    """Prefix every raw.githubusercontent.com URL (spider, js, live lists...) with GH_PROXY."""
    if isinstance(node, dict):
        return {k: with_gh_proxy(v) for k, v in node.items()}
    if isinstance(node, list):
        return [with_gh_proxy(x) for x in node]
    if GH_PROXY and isinstance(node, str) and node.startswith("https://raw.githubusercontent.com/"):
        return GH_PROXY + node
    return node


def build_lives(config, live_rows):
    """Working candidate lists first, then the config's own lists that still work.
    Falls back to the config's original lives if nothing verifies."""
    own = [e for e in (config.get("lives") or []) if isinstance(e, dict)]
    own_rows = [(e.get("name") or e.get("group") or "config live", live_entry_url(e)) for e in own]
    jobs = list(live_rows) + [(n, u) for n, u in own_rows if u]
    with cf.ThreadPoolExecutor(max_workers=4) as pool:
        checked = list(pool.map(lambda r: safe_check_live(*r), jobs))
    by_url = {r["url"]: r for r in checked}

    lives, seen = [], set()
    for name, url in live_rows:
        if by_url[url]["ok"] and url not in seen:
            seen.add(url)
            lives.append({"name": name, "type": 0, "url": url})
    for entry, (_, url) in zip(own, own_rows):
        if url and by_url[url]["ok"] and url not in seen:
            seen.add(url)
            lives.append(entry)
    return (lives or config.get("lives") or []), checked, bool(lives)


def read_candidates(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, ln in enumerate(f, 1):
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            parts = [p.strip() for p in ln.split("\t")]
            if len(parts) != 2 or not all(parts):
                print(f"skip {path}:{n}: expected 'name<TAB>url'", file=sys.stderr)
                continue
            rows.append(parts)
    return rows


def read_state(out_dir):
    try:
        with open(os.path.join(out_dir, STATE_FILE), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def write_json(out_dir, name, data):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main():
    cand_path, out_dir = sys.argv[1], sys.argv[2]
    live_rows = read_candidates(sys.argv[3]) if len(sys.argv) > 3 else []
    os.makedirs(out_dir, exist_ok=True)
    rows = read_candidates(cand_path)

    with cf.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda r: safe_probe(*r), rows))

    for r in results:
        mark = "OK  " if r["ok"] else "FAIL"
        ms = f"{r['ms']}ms" if r["ms"] is not None else "-"
        if r["kind"] == "config":
            extra = f"sites={r['sites']} lives={r['lives']}"
        elif r["kind"] == "multi":
            extra = f"multi repos={r['repos']}"
        else:
            extra = r["kind"] or ""
        print(f"{mark} {str(r['status']):>4} {ms:>7}  {r['name']:<12} {extra} {r['note']}")
    print(f"\n{sum(r['ok'] for r in results)}/{len(results)} usable")

    # Candidate order, not latency order, so the file only changes when the set changes.
    configs = [r for r in results if r["ok"] and r["kind"] == "config"]
    if not configs:
        write_json(out_dir, "results.json",
                   {"sources": [{k: v for k, v in r.items() if k != "_config"} for r in results]})
        print("no working config; leaving best.json and tvbox-multi.json untouched", file=sys.stderr)
        return 1

    # Sticky: keep the previous source while it still works, so users' sources
    # don't change every run. Otherwise take the config with the most sites:
    # latency measured from a CI runner says little about the viewer's network.
    prev_url = read_state(out_dir).get("url")
    chosen = next((r for r in configs if r["url"] == prev_url), None)
    if chosen:
        print(f"best: {chosen['name']} (kept, still working)")
    else:
        chosen = max(configs, key=lambda r: r["sites"])  # first in candidate order on ties
        print(f"best: {chosen['name']} (most sites" + (", previous source failed)" if prev_url else ")"))

    best = chosen["_config"]
    best["lives"], live_results, verified = build_lives(best, live_rows)
    print("\nlives:")
    for r in live_results:
        mark = "OK  " if r["ok"] else "FAIL"
        print(f"{mark} alive={r['alive']}/{r['sampled']} streams={r['streams']:<5} {r['name']:<16} {r['note']}")
    if not verified:
        print("no live list verified; keeping the config's own lives", file=sys.stderr)

    write_json(out_dir, "results.json",
               {"sources": [{k: v for k, v in r.items() if k != "_config"} for r in results],
                "lives": live_results})
    write_json(out_dir, "best.json", with_gh_proxy(best))
    write_json(out_dir, STATE_FILE, {"name": chosen["name"], "url": chosen["url"]})
    # Multi-repo file in the "urls" format used by TVBox-style clients.
    # Confirm the format against your client before relying on it.
    write_json(out_dir, "tvbox-multi.json",
               {"urls": [{"name": r["name"], "url": r["url"]} for r in configs]})
    return 0


if __name__ == "__main__":
    sys.exit(main())

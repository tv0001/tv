#!/usr/bin/env python3
"""Probe TVBox source URLs and report which ones respond with usable content.

Usage: python3 -I check.py candidates.tsv out_dir

Only issues GET requests and parses responses in memory. Nothing is saved
from the network except the summary files written to out_dir, and nothing
downloaded is executed.

Exits 1 without touching best.json / tvbox-multi.json when no config works,
so a bad run (runner outage, geo-blocking) never publishes an empty list.
"""
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
# "name,scheme://..." (TVBox txt) or a bare "scheme://..." line (m3u).
STREAM_LINE = re.compile(r"^(?:[^,<>]*,\s*)?[a-z][a-z0-9+.-]*://\S+", re.I)
STATE_FILE = "best-source.json"  # which source best.json came from, for sticky selection


def normalize(url):
    """Punycode the host and percent-encode non-ASCII path/query so urllib accepts it."""
    p = urllib.parse.urlsplit(url)
    host = p.hostname.encode("idna").decode("ascii") if p.hostname else ""
    netloc = host + (f":{p.port}" if p.port else "")
    path = urllib.parse.quote(p.path, safe="/%:@!$&'()*+,;=-._~")
    query = urllib.parse.quote(p.query, safe="=&%:/?+-._~!$'()*,;@")
    return urllib.parse.urlunsplit((p.scheme, netloc, path, query, ""))


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


def probe(name, url):
    result = {"name": name, "url": url, "ok": False, "status": None,
              "ms": None, "kind": None, "note": ""}
    try:
        target = normalize(url)
    except Exception as e:  # malformed host
        result["note"] = f"bad url: {e}"
        return result
    req = urllib.request.Request(target, headers={"User-Agent": UA})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            result["status"] = resp.status
            final_url = resp.geturl()  # relative paths resolve against this after redirects
            body = resp.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as e:
        result["status"] = e.code
        result["note"] = "http error"
        return result
    except Exception as e:
        result["note"] = type(e).__name__
        return result
    result["ms"] = int((time.monotonic() - start) * 1000)
    if final_url != target:
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
        result["ok"] = result["sites"] > 0 or result["lives"] > 0
        if result["ok"]:
            result["_config"] = absolutize(data, final_url)  # kept in memory only, for best.json
        else:
            result["note"] = "config has no sites or lives"
        return result

    head = text[:1024].lower()
    if head.startswith("<") or "<html" in head or "<!doctype" in head:
        result["kind"] = "html"
        result["note"] = "HTML page, not a config or live list"
        return result

    # Live playlist: usable if it has at least a few real stream lines.
    lines = [ln for ln in text.splitlines() if STREAM_LINE.match(ln.strip())]
    result["kind"] = "live-list"
    result["lines"] = len(lines)
    result["ok"] = len(lines) >= 3
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
    os.makedirs(out_dir, exist_ok=True)
    rows = read_candidates(cand_path)

    with cf.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda r: safe_probe(*r), rows))

    write_json(out_dir, "results.json",
               [{k: v for k, v in r.items() if k != "_config"} for r in results])

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
        print("no working config; leaving best.json and tvbox-multi.json untouched", file=sys.stderr)
        return 1

    # Sticky: keep the previous source while it still works, so near-equal
    # latencies don't flip best.json (and users' sources) every run.
    prev_url = read_state(out_dir).get("url")
    chosen = next((r for r in configs if r["url"] == prev_url), None)
    if chosen:
        print(f"best: {chosen['name']} (kept, still working)")
    else:
        chosen = min(configs, key=lambda r: r["ms"])
        print(f"best: {chosen['name']} (fastest" + (", previous source failed)" if prev_url else ")"))

    write_json(out_dir, "best.json", chosen["_config"])
    write_json(out_dir, STATE_FILE, {"name": chosen["name"], "url": chosen["url"]})
    # Multi-repo file in the "urls" format used by TVBox-style clients.
    # Confirm the format against your client before relying on it.
    write_json(out_dir, "tvbox-multi.json",
               {"urls": [{"name": r["name"], "url": r["url"]} for r in configs]})
    return 0


if __name__ == "__main__":
    sys.exit(main())

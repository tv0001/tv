#!/usr/bin/env python3
"""Print the app-version.json for a release APK.

usage: make-update-manifest.py <app.apk> <download-url> ["release notes"] > app-version.json

versionCode / versionName come from the build output (app/build/outputs/apk/release/output-metadata.json), so
build the release with the same -PappVersionCode / -PappVersionName you want to publish. Upload app-version.json to
the subscription directory (next to best.json) and the APK to the URL you pass in.
"""
import hashlib, json, os, sys

if len(sys.argv) < 3:
    sys.exit(__doc__)
apk, url = sys.argv[1], sys.argv[2]
notes = sys.argv[3] if len(sys.argv) > 3 else ""
meta_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build", "outputs", "apk", "release", "output-metadata.json")
element = json.load(open(meta_path))["elements"][0]
data = open(apk, "rb").read()
print(json.dumps({
    "versionCode": element["versionCode"],
    "versionName": element["versionName"],
    "url": url,
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
    "notes": notes,
    "force": False,
}, ensure_ascii=False, indent=2))

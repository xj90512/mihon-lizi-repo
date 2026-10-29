#!/usr/bin/env python3
"""Build a Tachimanga JAR store from the actual compiled manifest and APK signer."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default="xj90512/mihon-lizi-repo")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("invalid GitHub repository")
    jar, = (ROOT / "build/outputs/jar").glob("*.jar")
    apk, = (ROOT / "build/outputs/apk/release").glob("*.apk")
    with zipfile.ZipFile(jar) as archive:
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
    version = manifest.attrib[ANDROID + "versionName"]
    code = int(manifest.attrib[ANDROID + "versionCode"])
    package = manifest.attrib["package"]
    sdk = Path(os.environ["ANDROID_HOME"])
    signer = sdk / "build-tools/36.0.0/apksigner"
    result = subprocess.run([str(signer), "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True)
    fingerprint = re.search(r"certificate SHA-256 digest: ([a-f0-9]+)", result.stdout).group(1)
    source_file = ROOT / "src/main/kotlin/eu/kanade/tachiyomi/extension/zh/lizi/LiziProtocol.kt"
    source = source_file.read_text()
    source_id = re.search(r"const val SOURCE_ID = (\d+)L", source).group(1)
    base = re.search(r'const val API_BASE = "([^"]+)"', source).group(1)
    stem = f"tachiyomi-zh.lizi-v{version}"
    for src, dest in [(jar, ROOT / f"apk/{stem}.jar"), (apk, ROOT / f"apk/{stem}.apk")]:
        dest.parent.mkdir(exist_ok=True)
        shutil.copyfile(src, dest)
    cdn = f"https://cdn.jsdelivr.net/gh/{args.repo}@main"
    jar_url = f"{cdn}/apk/{stem}.jar"
    entry = {
        "name": "栗子漫画", "pkg": package, "apk": stem + ".jar", "jar": stem + ".jar",
        "apkUrl": jar_url, "jarUrl": jar_url,
        "lang": "zh", "code": code, "version": version, "nsfw": 1,
        "sources": [{"name": "栗子漫画", "lang": "zh", "id": source_id, "versionId": 1, "baseUrl": base}],
    }
    (ROOT / "index.min.json").write_text(json.dumps([entry], ensure_ascii=False, indent=2) + "\n")
    (ROOT / "repo.json").write_text(json.dumps({"meta": {
        "name": "栗子漫画 · Tachimanga", "shortName": "栗子",
        "website": f"https://github.com/{args.repo}", "signingKeyFingerprint": fingerprint,
    }}, ensure_ascii=False, indent=2) + "\n")
    checksums = {f"apk/{stem}.{ext}": hashlib.sha256((ROOT / f"apk/{stem}.{ext}").read_bytes()).hexdigest() for ext in ["jar", "apk"]}
    (ROOT / "checksums.json").write_text(json.dumps(checksums, indent=2) + "\n")
    print(f"Prepared Tachimanga store {version}: JAR + APK; source ID {source_id}")


if __name__ == "__main__":
    main()

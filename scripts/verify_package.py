#!/usr/bin/env python3
"""Validate the installed store surface, not merely whether Gradle compiled."""
import argparse
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def verify_jar(jar):
    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None, "corrupt archive"
        names = archive.namelist()
        entry = "eu/kanade/tachiyomi/extension/zh/lizi/Lizi.class"
        assert entry in names, "missing source entry point"
        assert "classes.dex" not in names, "JAR must contain native JVM classes, not a renamed APK"
        assert not any(x.startswith(("kotlin/", "kotlinx/", "okhttp3/", "android/", "uy/")) for x in names), "bundled host runtime dependency"
        classes = [x for x in names if x.endswith(".class")]
        assert classes, "empty program"
        for name in classes:
            data = archive.read(name)
            assert data[:4] == b"\xca\xfe\xba\xbe", name
            assert struct.unpack(">H", data[6:8])[0] <= 52, "requires newer than Java 8: " + name
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        assert manifest.attrib["package"] == "eu.kanade.tachiyomi.extension.zh.lizi"
        assert manifest.find("uses-sdk").attrib[ANDROID + "minSdkVersion"] == "21"
        application = manifest.find("application")
        meta = {x.attrib[ANDROID + "name"]: x.attrib[ANDROID + "value"] for x in application.findall("meta-data")}
        assert meta["tachiyomi.extension.class"] == ".Lizi"
        assert any(x.startswith("res/") for x in names), "missing APK icon resources"
        print(f"PASS: native JVM JAR, {len(classes)} Java 8 classes, Android manifest and icon resources")
        return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-only", action="store_true")
    args = parser.parse_args()
    if args.build_only:
        jar, = (ROOT / "build/outputs/jar").glob("*.jar")
        verify_jar(jar)
        return
    entry, = json.loads((ROOT / "index.min.json").read_text())
    assert entry["apk"].endswith(".jar"), "Tachimanga subscription must point directly to JAR"
    jar = ROOT / "apk" / entry["apk"]
    manifest = verify_jar(jar)
    assert entry["version"] == manifest.attrib[ANDROID + "versionName"]
    assert entry["code"] == int(manifest.attrib[ANDROID + "versionCode"])
    assert entry["pkg"] == manifest.attrib["package"]
    source_id = int(entry["sources"][0]["id"])
    expected = int.from_bytes(hashlib.md5("栗子漫画/zh/1".encode()).digest()[:8], "big") & ((1 << 63) - 1)
    assert source_id == expected
    source = (ROOT / "src/main/kotlin/eu/kanade/tachiyomi/extension/zh/lizi/LiziProtocol.kt").read_text()
    assert int(re.search(r"const val SOURCE_ID = (\d+)L", source).group(1)) == source_id
    assert (ROOT / "icon" / (entry["pkg"] + ".png")).is_file()
    for relative, digest in json.loads((ROOT / "checksums.json").read_text()).items():
        assert hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == digest, relative
    print("PASS: subscription, source identity, version, icon and artifact checksums agree")


if __name__ == "__main__":
    main()

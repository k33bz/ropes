#!/usr/bin/env python3
"""Write shields.io "endpoint" badge files for the README, one set per release line.

The README can't read gradle.properties, so CI writes a tiny JSON per badge onto the orphan
`badges` branch (scripts/commit_badges.sh) and shields.io renders them. Source:
build/server-test/versions.json, written by scripts/server_test.py: the mod version, the
Minecraft release the real-server test booted, the Fabric loader, fabric-api tested vs.
compiled, and the server-test pass count (ropes bundles no sgui).
Files land in <out>/<branch>/. (Adapted from k33bz/postbox.) Standard library only.
"""
import argparse
import json
import os


def badge(path, label, message, color):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"schemaVersion": 1, "label": label, "message": message or "none", "color": color}, f)
        f.write("\n")


def from_build(versions, out, branch):
    with open(versions) as f:
        v = json.load(f)
    d = os.path.join(out, branch)
    badge(f"{d}/mod.json", f"ropes ({branch})", v.get("mod"), "blueviolet")
    badge(f"{d}/minecraft.json", f"minecraft ({branch})", v.get("minecraft_tested") or v.get("minecraft"),
          "brightgreen")
    badge(f"{d}/loader.json", f"fabric loader ({branch})", v.get("loader"), "informational")
    tested, compiled = v.get("fabric_api_tested"), v.get("fabric_api_compiled")
    same = tested and compiled and tested == compiled
    api = tested if same else f"{tested or '?'} (built {compiled or '?'})"
    badge(f"{d}/fabric-api.json", f"fabric-api ({branch})", api, "informational")
    passed, total = v.get("passed", 0), v.get("total", 0)
    badge(f"{d}/server-test.json", f"server test ({branch})", f"{passed}/{total} passed",
          "brightgreen" if total and passed == total else "red")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--versions", default="build/server-test/versions.json")
    ap.add_argument("--branch", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    from_build(a.versions, a.out, a.branch)


if __name__ == "__main__":
    main()

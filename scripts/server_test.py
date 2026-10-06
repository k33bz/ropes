#!/usr/bin/env python3
"""Boot a real Fabric server with the freshly built ropes jar and run scripted checks.

The unit tests cover span/climb math, the config sanitizer, the store file handling and the rule
helpers; this answers "does the jar actually load and work on a real server of this Minecraft
version". No bots and no client: every check goes in through the server console, the config/
files ropes reads and writes, and the server's own answers. Standard library only.
(Adapted from k33bz/postbox.)

Boot 1, fresh world, with a config/ropes.json written beforehand:
  boot      ropes logs its init line and the server is Done
  config    out-of-range values come back clamped (knotScale -1 -> 0.05, climbMaxRate 50 -> 2.3)
  store     config/ropes_store.json is materialized
  commands  /rope list answers; /rope give answers (players only, from the console)
  tie       /rope tie refuses non-fences; strings a rope between two fences: the store gets the
            segment, one endpoint bat, one knot and its knot caps appear
  protect   the endpoint bat takes no damage (/damage)
  reload    the rope's chunk is unloaded and loaded again: still exactly ONE endpoint bat and one
            knot (before 0.3.1 every reload of a rope's chunk spawned another bat)
  strays    a ropes-tagged bat and knot cap with no stored rope are removed when they load
  cut       /rope cut removes the segment and its bat
Boot 2, after garbage was written over config/ropes_store.json:
  store     the server still starts, the damaged store is kept as ropes_store.json.corrupt-* byte
            for byte, a fresh store is written, and the rope's bat is NOT removed as a stray
Boot 3, the store restored from boot 1 plus one malformed entry (no dimension, no posts):
  store     the malformed entry is dropped with a warning instead of crashing the tick, and the
            restored rope is verified as healthy (still one bat)

The right-click path, /rope tie reach + Rope cost, Ropes refusing to leash mobs, climbing and
shears need a player; that stays with the mineflayer harness. Which Minecraft: the newest stable
release of this line. Writes build/server-test/versions.json for the README badges and a Markdown
summary to $GITHUB_STEP_SUMMARY. Exit code 0 = every check passed.

Usage: python3 scripts/server_test.py [--jar build/libs/x.jar] [--workdir build/server-test]
"""
import argparse
import glob
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "k33bz/ropes server-test (github actions)"}

# Log lines that mean the mod (or the server) failed.
FATAL = re.compile(
    r"Mixin apply failed|InvalidInjectionException|InvalidMixinException|MixinApplyError"
    r"|Critical injection failure|Exception ticking world|Encountered an unexpected exception"
    r"|Could not execute entrypoint|Error executing task|Unbound values in registry"
    r"|Failed to load registries|Error generating chunk|Exception in server tick loop")

INIT = r"\[ropes\] v\S+ initialized"
GARBAGE = '{"segments": [{"dim": "minecraft:overworld", "fenceA": [1000, '   # a store cut off mid-write

# A vertical rope far from spawn, in a chunk the test loads and unloads with /forceload
FAR = (1000, 1000)
FAR_A = "1000 -60 1000"
FAR_B = "1000 -54 1000"
# Near spawn, for tie/cut
NEAR_A = "2 -60 2"
NEAR_B = "6 -60 2"


# ---------------------------------------------------------------- downloads

def http_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def download(url, dest):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, \
            open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def props():
    out = {}
    with open(os.path.join(ROOT, "gradle.properties")) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def modrinth_file(project, mc, want_version=None):
    """Primary file of a Modrinth project's Fabric build for `mc` (exact version if given)."""
    q = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(["fabric"])})
    versions = http_json(f"https://api.modrinth.com/v2/project/{project}/version?{q}")
    if not versions:
        return None
    pick = next((v for v in versions if v["version_number"] == want_version), None) if want_version else None
    pick = pick or versions[0]
    f = next((x for x in pick["files"] if x.get("primary")), pick["files"][0])
    return pick["version_number"], f["url"], f["filename"]


# ---------------------------------------------------------------- server process

class Server:
    def __init__(self, workdir):
        self.workdir = workdir
        self.lines = queue.Queue()
        self.log = []
        self.fatal = []
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(
            ["java", "-Xms1G", "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=self.workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            self.log.append(line)
            if FATAL.search(line):
                self.fatal.append(line)
            self.lines.put(line)
        self.lines.put(None)  # process ended

    def send(self, cmd):
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()

    def wait_for(self, pattern, timeout):
        """Next line matching `pattern` (regex) within `timeout` s, else None."""
        rx = re.compile(pattern)
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=max(0.05, end - time.time()))
            except queue.Empty:
                break
            if line is None:
                return None
            if rx.search(line):
                return line
        return None

    def drain(self):
        while True:
            try:
                self.lines.get_nowait()
            except queue.Empty:
                return

    def test(self, cmd):
        """Run an `execute if ...` command; True = passed, False = failed, None = no answer."""
        self.drain()
        self.send(cmd)
        line = self.wait_for(r"Test (passed|failed)", 10)
        if line is None:
            return None
        return "Test passed" in line

    def poll(self, cmd, want, timeout):
        """Repeat `cmd` until it answers `want` or `timeout` s pass. Returns the last answer."""
        end = time.time() + timeout
        got = None
        while time.time() < end:
            got = self.test(cmd)
            if got == want:
                return got
            time.sleep(1)
        return got

    def alive(self):
        self.drain()
        self.send("list")
        return self.wait_for(r"There are \d+ of a max", 15) is not None

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.send("stop")
                self.proc.wait(timeout=60)
            except Exception:
                self.proc.kill()



def test_minecraft(line):
    """Newest stable release of this Minecraft line: '26.1' -> '26.1.2' when that exists."""
    stable = [v["version"] for v in http_json("https://meta.fabricmc.net/v2/versions/game") if v["stable"]]
    same = [v for v in stable if v == line or v.startswith(line + ".")]
    return same[0] if same else line   # meta lists newest first


# What the server test actually booted with, for scripts/publish_badges.py (and humans).
TESTED = {}


def setup(workdir, jars, p, mc):
    """Fresh server dir with fabric loader, the newest fabric-api for `mc`, and `jars`."""
    shutil.rmtree(workdir, ignore_errors=True)
    os.makedirs(os.path.join(workdir, "mods"), exist_ok=True)
    installer = next(i["version"] for i in http_json("https://meta.fabricmc.net/v2/versions/installer") if i["stable"])
    download(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{p['loader_version']}/{installer}/server/jar",
             os.path.join(workdir, "fabric-server-launch.jar"))
    # The newest fabric-api for this Minecraft version, as a real server would run, not the pin.
    api = modrinth_file("fabric-api", mc)
    if api is None:
        raise SystemExit(f"no fabric-api build on Modrinth for {mc}")
    download(api[1], os.path.join(workdir, "mods", api[2]))
    TESTED["fabric_api_tested"] = api[0]
    for jar in jars:
        shutil.copy(jar, os.path.join(workdir, "mods", os.path.basename(jar)))
    with open(os.path.join(workdir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(workdir, "server.properties"), "w") as f:
        f.write("\n".join([
            "online-mode=false", "level-type=minecraft\\:flat", "spawn-protection=0",
            "view-distance=4", "simulation-distance=4",
            # Default 60: an empty server stops ticking, and nothing here ever joins.
            "pause-when-empty-seconds=-1",
            "enable-command-block=false", "sync-chunk-writes=false", "server-port=25597", ""]))
    return installer, api


def boot(workdir, results, label, init_pattern, timeout):
    """Start a server and wait for ropes' init line and Done. Returns (Server, ready).

    The Server comes back even when the boot failed, so its log (the crash) is kept."""
    s = Server(workdir)
    s.start()
    init = s.wait_for(init_pattern, timeout)
    results.append((f"{label}: ropes initialized", init is not None, init or _last_error(s)))
    done = s.wait_for(r"Done \(\d", timeout) if init else None
    results.append((f"{label}: server reached Done", done is not None, done or ("" if not init else _last_error(s))))
    return s, done is not None


def _last_error(s):
    """The most telling line of a failed boot, for the summary table."""
    for line in reversed(s.log):
        if re.search(r"ERROR|Exception|Incompatible|requires|Caused by", line):
            return line[-200:]
    return s.log[-1][-200:] if s.log else "server produced no output"


# ---------------------------------------------------------------- scenarios

# Only the server's own command errors count as a bad answer: ropes logs warnings on purpose
# (a corrupt store, a malformed segment) while the checks run.
def answer(s, cmd, ok_pattern, bad_pattern=r"Unknown or incomplete command|Incorrect argument|Unknown command", timeout=20):
    """Send a console command; (True|False|None, line): matched ok, matched bad, or no answer."""
    s.drain()
    s.send(cmd)
    rx_ok, rx_bad = re.compile(ok_pattern), re.compile(bad_pattern)
    end = time.time() + timeout
    while time.time() < end:
        line = s.wait_for(r".", max(0.1, end - time.time()))
        if line is None:
            break
        if rx_ok.search(line):
            return True, line
        if rx_bad.search(line):
            return False, line
    return None, ""


def read_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def wait_until(fn, timeout, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        got = fn()
        if got:
            return got
        time.sleep(step)
    return fn()


def checker(results):
    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}", flush=True)
    return check


def count(s, selector):
    """How many entities match `selector` (via a scoreboard, the console's only counter)."""
    s.send("scoreboard objectives add ci dummy")
    s.send(f"execute store result score #n ci if entity {selector}")
    ok, line = answer(s, "scoreboard players get #n ci", r"#n has (\d+)")
    m = re.search(r"#n has (\d+)", line or "")
    return int(m.group(1)) if m else None


def segments(workdir):
    st = read_json(os.path.join(workdir, "config", "ropes_store.json")) or {}
    return st.get("segments")


BAT = "@e[type=minecraft:bat,tag=ropes_endpoint]"
KNOT = "@e[type=minecraft:leash_knot]"
CAPS = "@e[type=minecraft:item_display,tag=ropes_knot]"


def run_first_boot(s, results, workdir):
    check = checker(results)
    cfg_dir = os.path.join(workdir, "config")
    cfg = read_json(os.path.join(cfg_dir, "ropes.json")) or {}
    check("config: out-of-range values come back clamped (knotScale -1 -> 0.05, climbMaxRate 50 -> 2.3)",
          cfg.get("knotScale") == 0.05 and cfg.get("climbMaxRate") == 2.3 and "tieReachBlocks" in cfg,
          json.dumps(cfg)[:140])
    st = read_json(os.path.join(cfg_dir, "ropes_store.json"))
    check("store: ropes_store.json materialized", isinstance(st, dict) and st.get("segments") == [], str(st)[:80])

    ok, line = answer(s, "rope list", r"0 rope segment\(s\) stored")
    check("commands: /rope list answers", ok, line[-80:])
    ok, line = answer(s, "rope give", r"Players only")
    check("commands: /rope give answers (op-only, players only)", ok, line[-80:])

    # Near rope: tie refuses non-fences, then strings a real one
    s.send("forceload add 0 0")
    time.sleep(2)
    ok, line = answer(s, f"rope tie {NEAR_A} {NEAR_B}", r"Both ends must be fence posts", r"Rope strung")
    check("tie: refuses blocks that are not fences", ok, line[-80:])
    s.send(f"setblock {NEAR_A} minecraft:oak_fence")
    s.send(f"setblock {NEAR_B} minecraft:oak_fence")
    time.sleep(1)
    ok, line = answer(s, f"rope tie {NEAR_A} {NEAR_B}", r"Rope strung", r"must be|Could not|Too far|Unknown")
    check("tie: a rope is strung between two fences", ok, line[-80:])
    check("tie: the store holds the segment", wait_until(lambda: len(segments(workdir) or []) == 1, 10),
          str(segments(workdir))[:120])
    check("tie: one endpoint bat, one knot, knot caps", count(s, BAT) == 1 and count(s, KNOT) == 1
          and (count(s, CAPS) or 0) >= 1, f"bats {count(s, BAT)}, knots {count(s, KNOT)}, caps {count(s, CAPS)}")

    # The endpoint takes no damage (replaces the Invulnerable flag, gone in 26.3)
    s.send(f"damage {BAT[:-1]},limit=1] 100")
    time.sleep(1)
    check("protect: the endpoint bat survives /damage", count(s, BAT) == 1, "")

    # Cut it from the console (no player: the owner check does not apply)
    ok, line = answer(s, f"rope cut {NEAR_A}", r"Rope cut", r"No rope here|isn't yours")
    gone = wait_until(lambda: segments(workdir) == [], 10)
    check("cut: /rope cut removes the segment and its bat", ok and gone and count(s, BAT) == 0,
          f"{line[-60:]} store: {segments(workdir)}")
    s.send("kill @e[type=minecraft:item]")

    # Far rope: tie it, unload its chunk, load it again. Exactly one bat and one knot must remain.
    s.send(f"forceload add {FAR[0]} {FAR[1]}")
    time.sleep(3)
    s.send(f"setblock {FAR_A} minecraft:oak_fence")
    s.send(f"setblock {FAR_B} minecraft:oak_fence")
    time.sleep(1)
    ok, line = answer(s, f"rope tie {FAR_A} {FAR_B}", r"Rope strung", r"must be|Could not|Too far|Unknown")
    check("reload: a rope is strung in a far chunk", ok, line[-80:])
    for i in range(2):
        s.send(f"forceload remove {FAR[0]} {FAR[1]}")
        unloaded = wait_until(lambda: count(s, BAT) == 0, 30, step=2)
        s.send(f"forceload add {FAR[0]} {FAR[1]}")
        time.sleep(8)   # entities load off-thread; the verify waits for them (checked every 20 ticks)
        bats, knots = count(s, BAT), count(s, KNOT)
        check(f"reload {i + 1}: the chunk unloads and comes back with exactly one bat and one knot",
              unloaded and bats == 1 and knots == 1, f"unloaded: {unloaded}, bats {bats}, knots {knots}")

    # Strays: a ropes-tagged bat and knot cap with no stored rope behind them are removed on load
    s.send('summon minecraft:bat 4 -58 4 {NoAI:1b,Tags:["ropes_endpoint","ci_stray"]}')
    s.send('summon minecraft:item_display 4 -58 4 {Tags:["ropes_knot","ropes_knot_deadbeef","ci_stray"]}')
    gone = s.poll("execute if entity @e[tag=ci_stray]", False, 10)
    check("strays: an untracked endpoint bat and knot cap are removed when they load", gone is False,
          "log: " + str(any("stray rope entit" in l for l in s.log)))
    check("strays: the real rope's bat is left alone", count(s, BAT) == 1, "")
    time.sleep(3)
    check("ticks run cleanly", s.alive() and not s.fatal, "; ".join(s.fatal[:2]))


def run_second_boot(s, results, workdir):
    check = checker(results)
    cfg_dir = os.path.join(workdir, "config")
    backups = glob.glob(os.path.join(cfg_dir, "ropes_store.json.corrupt-*"))
    kept = backups and open(backups[0]).read() == GARBAGE
    logged = any("could not read the rope store" in l for l in s.log)
    check("store: a corrupt store is kept as .corrupt-* byte for byte", kept and logged,
          f"backups: {[os.path.basename(b) for b in backups]}, logged: {logged}")
    fresh = read_json(os.path.join(cfg_dir, "ropes_store.json"))
    check("store: a fresh store is written next to it", isinstance(fresh, dict) and fresh.get("segments") == [],
          str(fresh)[:80])
    time.sleep(8)
    check("store: the rope's bat is not removed as a stray while the store is unreadable",
          count(s, BAT) == 1, f"bats {count(s, BAT)}")
    check("ticks run cleanly after the restart", s.alive() and not s.fatal, "; ".join(s.fatal[:2]))


def run_third_boot(s, results, workdir):
    check = checker(results)
    logged = any("dropped 1 malformed rope segment" in l for l in s.log)
    segs = segments(workdir) or []
    check("store: a malformed segment is dropped with a warning, the good one kept",
          logged and len(segs) == 1 and segs[0].get("dim") == "minecraft:overworld",
          f"logged: {logged}, store: {str(segs)[:100]}")
    time.sleep(8)
    bats, knots = count(s, BAT), count(s, KNOT)
    check("store: the restored rope is verified with one bat and one knot", bats == 1 and knots == 1,
          f"bats {bats}, knots {knots}")
    check("ticks run cleanly with the restored store", s.alive() and not s.fatal, "; ".join(s.fatal[:2]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar")
    ap.add_argument("--workdir", default=os.path.join(ROOT, "build", "server-test"))
    ap.add_argument("--boot-timeout", type=int, default=600)
    a = ap.parse_args()
    jar = a.jar or next((j for j in sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")))
                         if not j.endswith(("-sources.jar", "-dev.jar"))), None)
    if not jar:
        raise SystemExit("no jar: run ./gradlew build first or pass --jar")
    p = props()
    mc = test_minecraft(p["minecraft_version"])
    TESTED["minecraft_tested"] = mc
    results, logs, fatal = [], [], []

    installer, api = setup(a.workdir, [jar], p, mc)
    # Written before the first boot: out-of-range values to be clamped
    os.makedirs(os.path.join(a.workdir, "config"), exist_ok=True)
    with open(os.path.join(a.workdir, "config", "ropes.json"), "w") as f:
        json.dump({"knotScale": -1, "climbMaxRate": 50}, f)
    notes = [f"Minecraft {mc} (newest release of the {p['minecraft_version']} line), "
             f"Fabric loader {p['loader_version']} (installer {installer})",
             f"fabric-api {api[0]} (newest for {mc}; compiled against {p.get('fabric_api_version')})",
             f"under test: {os.path.basename(jar)}"]
    for n in notes:
        print("  " + n, flush=True)

    store_path = os.path.join(a.workdir, "config", "ropes_store.json")

    def corrupt():
        with open(store_path) as f:
            boot1_store = f.read()
        with open(store_path, "w") as f:
            f.write(GARBAGE)
        return boot1_store

    saved = {}
    for label, scenario in [("first boot", run_first_boot),
                            ("restart on a corrupt store", run_second_boot),
                            ("restart on a restored store with a malformed entry", run_third_boot)]:
        if label != "first boot":
            if not saved.get("ready"):
                break
            if scenario is run_second_boot:
                saved["store"] = corrupt()
            else:
                st = json.loads(saved["store"])
                st["segments"].append({"dim": None, "fenceA": None, "fenceB": [1, 2, 3], "endpointUuid": "x"})
                with open(store_path, "w") as f:
                    json.dump(st, f)
        s, ready = boot(a.workdir, results, label, INIT, a.boot_timeout)
        saved["ready"] = ready
        try:
            if ready:
                scenario(s, results, a.workdir)
        finally:
            s.stop()
            logs += [f"==== {label} ===="] + s.log
            fatal += s.fatal

    results.append(("no mixin / tick / entrypoint errors in the logs", len(fatal) == 0, "; ".join(fatal[:3])))
    with open(os.path.join(a.workdir, "console.log"), "w") as f:
        f.write("\n".join(logs))
    failed = [r for r in results if r[1] is False]
    ran = [r for r in results if r[1] is not None]
    # Machine-readable record of this run for README badges (scripts/publish_badges.py).
    with open(os.path.join(a.workdir, "versions.json"), "w") as f:
        json.dump({
            "mod": p.get("mod_version"), "minecraft": p.get("minecraft_version"),
            "minecraft_tested": TESTED.get("minecraft_tested"),
            "loader": p.get("loader_version"), "fabric_api_compiled": p.get("fabric_api_version"),
            "fabric_api_tested": TESTED.get("fabric_api_tested"),
            "passed": len(ran) - len(failed), "total": len(ran),
            "sha": os.environ.get("GITHUB_SHA"), "branch": os.environ.get("GITHUB_REF_NAME"),
        }, f, indent=2)
    if failed:
        print("---- last 80 console lines ----")
        print("\n".join(logs[-80:]))
        print("---- end ----", flush=True)
    md = [f"### Server test: Minecraft {TESTED.get('minecraft_tested')}, ropes {p.get('mod_version')}", ""]
    md += [f"- {n}" for n in notes] + ["", "| Check | Result |", "|---|---|"]
    md += [f"| {n} | {'✅' if ok else '❌ ' + d.replace('|', '/')[:200]} |" for n, ok, d in results]
    md += ["", f"**{len(ran) - len(failed)}/{len(ran)} passed**"]
    print("\n".join(md))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write("\n".join(md) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

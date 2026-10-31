#!/usr/bin/env python3
"""The Java bridge's regression run: every scene that once worked, played headless, each
checked by what the game reports (horizon-ctl), its log and its screenshots.

    python3 tools/java_bridge/regress.py --jars ~/hz-test/jars          # all cases
    python3 tools/java_bridge/regress.py --jars ~/hz-test/jars pot rei  # some
    python3 tools/java_bridge/regress.py --java-only ...                # after a Java-only change
    python3 tools/java_bridge/regress.py --list

--jars is one folder of Modrinth mod jars; each case takes the ones it needs by name and is
skipped when one is missing (the repo's fixture mods are built here). Screenshots and logs go
to --out (default /tmp/hz-regress/<case>/). Exit status 1 when a case fails.
"""
import argparse
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "java_bridge"

# Mod jars by name, as Modrinth names them (case-insensitive globs).
MODS = {
    "fd": ["farmers*delight*"],
    "toms": ["toms*storage*", "toms_storage*"],
    "jade": ["jade*"],
    "appleskin": ["appleskin*"],
    "rei": ["rei.jar", "roughlyenoughitems*", "rei-*"],
    "architectury": ["architectury*"],
    "cloth": ["cloth-config*", "cloth_config*"],
    "bop": ["biomes*o*plenty*", "biomesoplenty*"],
    "terrablender": ["terrablender*"],
    "glitchcore": ["glitchcore*"],
    "fabric-api": ["fabric-api*"],
}


def check_file(name, pattern, what):
    """The scratch file `name` (a step wrote it) matches `pattern`."""
    return ("file", name, pattern, what)


def check_log(pattern, what):
    """The game's log has a line matching `pattern`."""
    return ("log", None, pattern, what)


def check_json(name, path, expect, what):
    """JSON file `name`'s value at `path` (keys and indexes) equals `expect`."""
    return ("json", name, (path, expect), what)


def screen_has(slot, item):
    """The open container's slot holds `item` (horizon-ctl screen)."""
    return rf'"item":"{re.escape(item)}"[^}}]*"slot":{slot}\b|"slot":{slot}[^}}]*"item":"{re.escape(item)}"'


CASES = {
    "pot": dict(
        about="Farmer's Delight: the cooking pot's screen opens; beef moved into it",
        mods=["fd"], scene="pot",
        steps=['$C click-at 141 187', '$C click-at 163 62', '$C state > $S/state.json; $C screen > $S/screen.json'],
        checks=[check_json("state.json", ["screen"], "container", "the pot's screen is open"),
                check_json("state.json", ["looking_at", "block", "block"], "farmersdelight:cooking_pot", "looking at the pot"),
                check_file("screen.json", screen_has(300, "minecraft:beef"), "beef is in the pot's first slot")],
    ),
    "terminal": dict(
        about="Tom's Storage: the terminal lists the chest beside its connector",
        mods=["toms"], scene="terminal",
        steps=['sleep 2; $C state > $S/state.json'],
        checks=[check_json("state.json", ["screen"], "container", "the terminal's screen is open"),
                check_log(r"Java mods: commands", "the mods started")],
        look="the terminal shows 12 diamonds and 40 oak logs",
    ),
    "ber": dict(
        about="Farmer's Delight's block entity renderers: beef on the stove, a carrot on the board",
        mods=["fd"], scene="ber",
        steps=[],
        checks=[check_log(r"Java mods: commands", "the mods started")],
        look="beef on the stove's grill, a carrot on the cutting board",
    ),
    "hud": dict(
        about="Jade's tooltip and AppleSkin on Horizon's HUD",
        mods=["fd", "jade", "appleskin"], scene="none", env={"HORIZON_JAVA_DEBUG": "hud"},
        steps=['$C command "gamemode survival" >/dev/null; $C look 0 80 >/dev/null; sleep 4'],
        checks=[check_log(r"hud element jade:main: [1-9]", "Jade draws its tooltip"),
                check_log(r"hud: [1-9][0-9]* ops kept", "mods' HUD parts reach the game")],
        forbid=[r"HUD's \S+ failed"],
        look="Jade's box at the top: the block below, its mod in blue",
    ),
    "rei": dict(
        about="REI's recipe view: vanilla and Farmer's Delight recipes",
        mods=["rei", "architectury", "cloth", "fd"], scene="none",
        steps=['$C command "recipes minecraft:crafting_table" >/dev/null; sleep 8',
               '$C key escape tap >/dev/null; $C command "recipes farmersdelight:beef_stew" >/dev/null; sleep 5'],
        checks=[check_log(r"recipes of 1 minecraft:crafting_table: opened", "crafting table's recipe shows"),
                check_log(r"recipes of 1 farmersdelight:beef_stew: opened", "beef stew's recipe shows")],
        forbid=[r"recipe viewer failed"],
    ),
    "keys": dict(
        about="A mod's key binding (the keysmod fixture, G) reaches the mod",
        fixtures=["keysmod"], scene="none",
        steps=['$C key g tap >/dev/null; sleep 1; $C key g tap >/dev/null; sleep 1; $C messages > $S/messages.json'],
        checks=[check_file("messages.json", r"key G pressed by", "the mod saw G")],
    ),
    "entity": dict(
        about="A mod's mob (the crittermod fixture): summoned, ticked, drawn",
        fixtures=["crittermod"], scene="entity",
        steps=['$C state > $S/state.json; $C entities 16 > $S/entities.json'],
        checks=[check_log(r"Summoned new Critter", "the mod's mob is summoned"),
                check_file("entities.json", r'"kind":"java"[^}]*"type":"horizon_critters:critter"|"type":"horizon_critters:critter"[^}]*"kind":"java"',
                           "horizon-ctl sees the critters")],
        look="two critters ahead",
    ),
    "worldgen": dict(
        about="Biomes O' Plenty's biomes: /locate finds one",
        mods=["bop", "terrablender", "glitchcore"], scene="none",
        steps=['$C command "locate biome biomesoplenty:redwood_forest" >/dev/null; sleep 6; $C messages > $S/messages.json'],
        checks=[check_file("messages.json", r"redwood_forest[^\"]*is at|nearest[^\"]*redwood", "a redwood forest is found")],
        forbid=[r"features failed"],
    ),
    "prism": dict(
        about="Prism Launcher's wrapper: an instance's mods linked, Fabric API left out (no game)",
        mods=["fd"], optional=["fabric-api"], prism=True,
    ),
}


def find_jars(folder, names):
    """The jar of each name in `folder`; None when one is missing."""
    found = []
    for name in names:
        hit = None
        for jar in sorted(Path(folder).glob("*.jar")) if folder else []:
            if any(fnmatch.fnmatch(jar.name.lower(), g) for g in MODS[name]):
                hit = jar
                break
        if hit is None:
            return None, name
        found.append(hit)
    return found, None


def fixture(name, cache):
    jar = cache / f"{name}.jar"
    if not jar.exists():
        r = subprocess.run([str(TOOLS / "fixture_mod.sh"), name, str(jar)], capture_output=True, text=True)
        if r.returncode != 0 or not jar.exists():
            raise RuntimeError(f"fixture {name} did not build: {r.stderr[-400:]}")
    return jar


def build(java_only):
    env = os.environ.copy()
    if java_only:
        r = subprocess.run([str(TOOLS / "java.sh")], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"Java build failed:\n{r.stderr[-2000:]}")
        return {"HORIZON_JAVA_JARS": r.stdout.strip().splitlines()[-1]}
    r = subprocess.run(["cargo", "build", "-q", "--profile", "fast", "-p", "horizon-client",
                        "--features", "horizon-client/java-mods"], cwd=ROOT, env=env,
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"build failed:\n{r.stderr[-3000:]}")
    return {}


def run_prism(case, jars, out):
    inst = Path(tempfile.mkdtemp(prefix="hz-prism."))
    try:
        mods = inst / ".minecraft" / "mods"
        mods.mkdir(parents=True)
        for jar in jars:
            shutil.copy(jar, mods)
        env = dict(os.environ, INST_MC_DIR=str(inst / ".minecraft"), HORIZON_PRISM_DRY="1")
        r = subprocess.run([str(TOOLS / "prism.sh")], input="param --username\nparam Steve\nlaunch\n",
                           env=env, capture_output=True, text=True, timeout=60)
        (out / "prism.txt").write_text(r.stdout + r.stderr)
        linked = sorted(p.name for p in (inst / ".minecraft" / "horizon" / "mods-java").glob("*.jar"))
        results = [(r.returncode == 0 and "horizon-client" in r.stdout, "the game's command is made"),
                   (len(linked) == len([j for j in jars if not fnmatch.fnmatch(j.name.lower(), "fabric-api*")]),
                    f"the mods are linked ({len(linked)})"),
                   (not any(n.lower().startswith("fabric-api") for n in linked), "Fabric API is left out"),
                   ((inst / ".minecraft" / "horizon" / "config").is_symlink(), "config/ is the instance's")]
        return results, []
    finally:
        shutil.rmtree(inst, ignore_errors=True)


def run_game(case, name, jars, out, extra_env):
    mods = Path(tempfile.mkdtemp(prefix="hz-regress-mods."))
    try:
        for jar in jars:
            shutil.copy(jar, mods)
        env = dict(os.environ, MODS=str(mods), SCENE=case["scene"], OUT=str(out), NO_BUILD="1", **extra_env,
                   **case.get("env", {}))
        started = time.time()
        r = subprocess.run([str(TOOLS / "screen_test.sh"), *case["steps"]], env=env, capture_output=True,
                           text=True, timeout=600)
        (out / "screen_test.txt").write_text(r.stdout + r.stderr)
        scratch = Path(Path("/tmp/hz-bridge-scratch").read_text().strip())
        log_path = scratch / "home" / "logs" / "latest.log"
        log = log_path.read_text(errors="replace") if log_path.exists() else ""
        shutil.copy(log_path, out / "latest.log") if log_path.exists() else None
        results = []
        for kind, file, pattern, what in case["checks"]:
            if kind == "log":
                ok = re.search(pattern, log) is not None
            elif kind == "file":
                text = (scratch / file).read_text(errors="replace") if (scratch / file).exists() else ""
                (out / file).write_text(text)
                ok = re.search(pattern, text) is not None
            else:
                path, expect = pattern
                try:
                    value = json.loads((scratch / file).read_text())
                    for key in path:
                        value = value[key]
                    ok = value == expect
                except (OSError, ValueError, KeyError, IndexError, TypeError):
                    ok = False
            results.append((ok, what))
        for pattern in case.get("forbid", []):
            hits = re.findall(rf".*{pattern}.*", log)
            results.append((not hits, f"no '{pattern}' in the log" + (f" ({hits[0].strip()[:120]})" if hits else "")))
        # The log's failures, for a reader (not a check: known noise stays).
        errors = [l[:220] for l in log.splitlines() if re.search(r"failed|Exception", l) and "content:" not in l]
        results.append((log != "", f"the game ran ({time.time() - started:.0f} s)"))
        return results, errors
    finally:
        shutil.rmtree(mods, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cases", nargs="*", help="cases to run (default: all)")
    ap.add_argument("--jars", default=os.environ.get("HZ_JARS"), help="a folder of Modrinth mod jars")
    ap.add_argument("--out", default="/tmp/hz-regress")
    ap.add_argument("--java-only", action="store_true", help="rebuild only the Java side (java.sh)")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        for name, case in CASES.items():
            print(f"{name:10} {case['about']}")
        return 0
    names = a.cases or list(CASES)
    unknown = [n for n in names if n not in CASES]
    if unknown:
        sys.exit(f"unknown cases: {', '.join(unknown)} (--list)")
    out_root = Path(a.out)
    out_root.mkdir(parents=True, exist_ok=True)
    fixtures = out_root / "fixtures"
    fixtures.mkdir(exist_ok=True)
    extra_env = build(a.java_only)
    summary = []
    for name in names:
        case = CASES[name]
        out = out_root / name
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir()
        jars, missing = find_jars(a.jars, case.get("mods", []))
        if jars is None:
            print(f"SKIP {name}: no {missing} jar in {a.jars or '(no --jars)'}")
            summary.append((name, None))
            continue
        for opt in case.get("optional", []):
            more, _ = find_jars(a.jars, [opt])
            jars += more or []
        try:
            jars += [fixture(f, fixtures) for f in case.get("fixtures", [])]
            print(f"---- {name}: {case['about']}", flush=True)
            if case.get("prism"):
                results, errors = run_prism(case, jars, out)
            else:
                results, errors = run_game(case, name, jars, out, extra_env)
        except Exception as e:  # a case that cannot run fails, the others still run
            results, errors = [(False, f"the case could not run: {e}")], []
        for ok, what in results:
            print(f"  {'ok  ' if ok else 'FAIL'} {what}")
        if case.get("look"):
            print(f"  look {case['look']}: {out}/{case['scene']}-0.png")
        if errors:
            print(f"  ({len(errors)} failure lines in the log: {out}/latest.log)")
        summary.append((name, all(ok for ok, _ in results)))
    print()
    print("  ".join(f"{n}:{'pass' if r else 'FAIL' if r is False else 'skip'}" for n, r in summary))
    return 1 if any(r is False for _, r in summary) else 0


if __name__ == "__main__":
    sys.exit(main())

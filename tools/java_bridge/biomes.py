#!/usr/bin/env python3
"""The biomes of the loaded chunks around the player of the running game (launch.sh's), one
sample a chunk at the player's height, with a place of each: what the mods' worldgen made.
Usage: tools/java_bridge/biomes.py [RADIUS]  (chunks; default 8)"""
import collections, json, os, subprocess, sys
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ctl = os.path.join(root, "target/fast/horizon-ctl")
sock = os.path.join(open("/tmp/hz-bridge-scratch").read().strip(), "ctl.sock")
r = int(sys.argv[1]) if len(sys.argv) > 1 else 8
def call(*a):
    return json.loads(subprocess.run([ctl, "--socket", sock, *map(str, a)], capture_output=True, text=True).stdout)
p = call("state")["player"]["position"]
cx, cz, y = int(p[0]) >> 4, int(p[2]) >> 4, int(p[1])
seen = collections.Counter(); where = {}
for dx in range(-r, r + 1):
    for dz in range(-r, r + 1):
        b = call("block", (cx + dx) * 16 + 8, y, (cz + dz) * 16 + 8)
        if b.get("loaded") and "biome" in b:
            seen[b["biome"]] += 1
            where.setdefault(b["biome"], ((cx + dx) * 16 + 8, (cz + dz) * 16 + 8))
for k, v in seen.most_common():
    print(f"{v:4d} {k} at {where[k]}")

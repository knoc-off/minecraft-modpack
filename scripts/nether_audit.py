#!/usr/bin/env python3
"""What wiping world/DIM-1 would take with it, on a server dir (read-only).

Usage (inside `nix develop`): python3 scripts/nether_audit.py <datadir>

Reports players whose saved position is in the nether (they'd respawn in
regenerated terrain, possibly inside a wall, lava, or open air), and every
world/data/*.dat that names the nether: Create trains/stations, Sable
ships and other cross-dimension state that would point into chunks that no
longer exist.
"""
import gzip, json, os, sys

root = sys.argv[1]
world = os.path.join(root, "world")
names = {e["uuid"]: e["name"] for e in json.load(open(os.path.join(root, "usercache.json")))}

def read(p):
    with open(p, "rb") as f:
        b = f.read()
    try:
        return gzip.decompress(b)
    except OSError:
        return b

def nbt_string(b, key):
    # TAG_String payload directly after the key: u16 length + bytes.
    k = key.encode()
    i = b.find(k)
    if i < 0:
        return None
    j = i + len(k)
    n = int.from_bytes(b[j:j + 2], "big")
    return b[j + 2:j + 2 + n].decode(errors="replace")

print("# players saved in the nether")
pd = os.path.join(world, "playerdata")
for f in sorted(os.listdir(pd)):
    if f.endswith(".dat"):
        dim = nbt_string(read(os.path.join(pd, f)), "Dimension")
        if dim and "nether" in dim:
            print(" ", names.get(f[:-4], f[:-4] + " (not in usercache)"))

print("# world/data files naming the nether (count, file)")
dd = os.path.join(world, "data")
hits = []
for f in sorted(os.listdir(dd)):
    p = os.path.join(dd, f)
    if os.path.isfile(p):
        n = read(p).count(b"the_nether")
        if n:
            hits.append((n, f))
for n, f in sorted(hits, reverse=True):
    print(f"  {n:6d} {f}")

print("# nether dirs and other dimension dirs")
dm = os.path.join(world, "DIM-1")
print("  DIM-1:", sorted(os.listdir(dm)) if os.path.isdir(dm) else "absent")
for d in ("dimensions",):
    p = os.path.join(world, d)
    if os.path.isdir(p):
        for ns in sorted(os.listdir(p)):
            print(f"  dimensions/{ns}:", sorted(os.listdir(os.path.join(p, ns))))

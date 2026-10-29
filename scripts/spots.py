#!/usr/bin/env python3
"""Rank areas of a world by how long players have spent in them.

Every chunk stores InhabitedTime: ticks a player has been within range of it,
cumulative. Summed over a cell of chunks, that is a decent proxy for "where
the bases are" -- exactly the areas worth force-loading and profiling in
scripts/test-server.sh.

Prints spots.txt lines: `<dim> <block x> <block z> <radius chunks> <label>`.

Usage:
  spots.py <world dir> [top N, default 8] [cell size in chunks, default 8]
"""

import glob
import os
import re
import struct
import sys
import zlib

# TAG_Long (4), name length 13, "InhabitedTime". Top-level in 1.18+ chunks,
# so a byte search beats parsing the whole NBT tree of ~700k chunks.
NEEDLE = b"\x04\x00\x0dInhabitedTime"

DIMS = {
    "minecraft:overworld": "region",
    "minecraft:the_nether": "DIM-1/region",
    "minecraft:the_end": "DIM1/region",
}


def chunks(path):
    """Yield (chunk_x, chunk_z, inhabited_ticks) for one .mca file."""
    rx, rz = map(int, re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", os.path.basename(path)).groups())
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    for i in range(1024):
        off = int.from_bytes(data[i * 4 : i * 4 + 3], "big") * 4096
        if not off:
            continue
        length, comp = struct.unpack(">IB", data[off : off + 5])
        if comp != 2:  # zlib is the default; gzip/lz4/external are rare enough to skip
            continue
        try:
            raw = zlib.decompress(data[off + 5 : off + 4 + length])
        except zlib.error:
            continue
        at = raw.find(NEEDLE)
        if at < 0:
            continue
        (ticks,) = struct.unpack(">q", raw[at + len(NEEDLE) : at + len(NEEDLE) + 8])
        yield rx * 32 + i % 32, rz * 32 + i // 32, ticks


def main():
    world = sys.argv[1]
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    cell = int(sys.argv[3]) if len(sys.argv) > 3 else 8

    scores = {}
    for dim, sub in DIMS.items():
        for path in glob.glob(os.path.join(world, sub, "r.*.mca")):
            for cx, cz, ticks in chunks(path):
                key = (dim, cx // cell, cz // cell)
                scores[key] = scores.get(key, 0) + ticks

    # Neighbouring cells of one base would otherwise fill the whole list.
    picked = []
    for (dim, gx, gz), ticks in sorted(scores.items(), key=lambda kv: -kv[1]):
        if any(d == dim and abs(gx - x) <= 1 and abs(gz - z) <= 1 for d, x, z, _ in picked):
            continue
        picked.append((dim, gx, gz, ticks))
        if len(picked) == top:
            break

    radius = cell // 2
    for dim, gx, gz, ticks in picked:
        x = (gx * cell + radius) * 16
        z = (gz * cell + radius) * 16
        hours = ticks / 20 / 3600
        print(f"{dim} {x} {z} {radius} spot-{dim.split(':')[1]}-{x}-{z}  # {hours:.0f} player-chunk-hours")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Write pack/servers.dat -- the multiplayer server list Minecraft reads.

Uncompressed NBT (NbtIo.read, not readCompressed), so no gzip.

Usage:
  gen_servers_dat.py [output_path]     defaults to ./pack/servers.dat
"""

import struct
import sys

SERVERS = [
    {"name": "inkwell", "ip": "mc.niko.ink"},
]

TAG_END = 0
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def nbt_string(s: str) -> bytes:
    raw = s.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def named_string(key: str, value: str) -> bytes:
    return bytes([TAG_STRING]) + nbt_string(key) + nbt_string(value)


def build(servers: list[dict]) -> bytes:
    entries = b""
    for s in servers:
        # Inside a TAG_List the elements carry no tag id and no name.
        entries += named_string("name", s["name"])
        entries += named_string("ip", s["ip"])
        entries += bytes([TAG_END])

    out = bytes([TAG_COMPOUND]) + nbt_string("")
    out += bytes([TAG_LIST]) + nbt_string("servers")
    out += bytes([TAG_COMPOUND]) + struct.pack(">i", len(servers))
    out += entries
    out += bytes([TAG_END])
    return out


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "./pack/servers.dat"
    data = build(SERVERS)
    with open(output, "wb") as f:
        f.write(data)
    print(f"Wrote {len(data)} bytes to {output} ({len(SERVERS)} server(s))")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Rank mods by share of server-thread time in a spark .sparkprofile.

The spark viewer does this interactively; this is the headless version, so a
test run can say "create 31%, minecraft 40%, ..." per spot and two runs can be
compared by eye. stdlib only: the file is a plain protobuf SamplerData
(https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto),
and the handful of fields needed are decoded by hand.

Attribution: each sample's self time goes to the innermost frame on its stack
whose class belongs to a mod (spark's class_sources map). Frames spark knows no
source for -- vanilla, NeoForge, the JDK -- inherit the nearest mod above them,
or count as "minecraft" if there is none. So a Create block entity calling
into vanilla code is charged to Create, which is the question being asked:
whose content is costing tick time.

Usage: spark_top.py [--thread NAME] [--top N] <profile.sparkprofile>...
"""

import struct
import sys


def fields(buf):
    """Yield (field number, wire type, value) for one protobuf message."""
    i, n = 0, len(buf)
    while i < n:
        key, i = varint(buf, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            val, i = varint(buf, i)
        elif wt == 1:
            val, i = buf[i : i + 8], i + 8
        elif wt == 2:
            ln, i = varint(buf, i)
            val, i = buf[i : i + ln], i + ln
        elif wt == 5:
            val, i = buf[i : i + 4], i + 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        yield num, wt, val


def varint(buf, i):
    out = shift = 0
    while True:
        b = buf[i]
        i += 1
        out |= (b & 0x7F) << shift
        if b < 0x80:
            return out, i
        shift += 7


def packed_varints(buf):
    i, out = 0, []
    while i < len(buf):
        v, i = varint(buf, i)
        out.append(v)
    return out


def doubles(wt, val):
    if wt == 2:  # packed
        return list(struct.unpack(f"<{len(val) // 8}d", val))
    return [struct.unpack("<d", val)[0]]


def parse_node(buf):
    cls, times, refs = "", 0.0, []
    for num, wt, val in fields(buf):
        if num == 3:
            cls = val.decode()
        elif num == 8:
            times += sum(doubles(wt, val))
        elif num == 9:
            refs += packed_varints(val) if wt == 2 else [val]
    return cls, times, refs


def parse_thread(buf):
    name, nodes, roots = "", [], []
    for num, wt, val in fields(buf):
        if num == 1:
            name = val.decode()
        elif num == 3:
            nodes.append(parse_node(val))
        elif num == 5:
            roots += packed_varints(val) if wt == 2 else [val]
    return name, nodes, roots


def parse(path):
    data = open(path, "rb").read()
    threads, sources, comment = [], {}, ""
    for num, _, val in fields(data):
        if num == 1:
            for n, _, v in fields(val):
                if n == 6:
                    comment = v.decode()
        elif num == 2:
            threads.append(parse_thread(val))
        elif num == 3:
            kv = {n: v.decode() for n, _, v in fields(val)}
            sources[kv.get(1, "")] = kv.get(2, "")
    return comment, threads, sources


def attribute(nodes, roots, sources):
    """Self time per owning mod, and per tick phase (entities, block
    entities, chunks, ...) across all owners."""
    by_mod: dict[str, float] = {}
    by_phase: dict[str, float] = {}
    # Owner None means "no Java frame yet": async-profiler roots some stacks
    # in pure native frames, which say nothing about who caused them.
    #
    # NeoForge is platform, same as vanilla: Create inserting into a
    # NeoForge ItemStackHandler is Create's cost, so a platform frame never
    # takes ownership away from a mod above it.
    stack: list[tuple[int, str | None, str]] = [(r, None, "other") for r in roots]
    while stack:
        idx, owner, phase = stack.pop()
        cls, total, refs = nodes[idx]
        # The server thread waits for the next tick in LockSupport.park
        # (MinecraftServer.waitUntilNextTick). That is idle, not cost -- on a
        # healthy server it is most of the samples and would bury everything.
        if cls in IDLE:
            continue
        phase = PHASES.get(cls, phase)
        src = sources.get(cls)
        if src and src not in PLATFORM:
            owner = src
        elif owner is None and (src or ("." in cls and ".so" not in cls)):
            owner = "minecraft"
        self_time = max(total - sum(nodes[c][1] for c in refs), 0.0)
        if owner is not None:
            by_mod[owner] = by_mod.get(owner, 0.0) + self_time
            by_phase[phase] = by_phase.get(phase, 0.0) + self_time
        stack.extend((c, owner, phase) for c in refs)
    return by_mod, by_phase


PLATFORM = {"minecraft", "neoforge"}
IDLE = {"java.util.concurrent.locks.LockSupport"}

# Frames that mark which part of the tick a sample is in; the innermost one
# wins. Coarse on purpose -- this answers "entities or machines?", the
# viewer answers the rest.
PHASES = {
    "net.minecraft.world.level.entity.EntityTickList": "entities",
    "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity": "block-entities",
    "net.minecraft.server.level.ServerChunkCache": "chunks",
    "net.minecraft.server.level.ChunkMap": "chunks",
    "net.minecraft.world.ticks.LevelTicks": "scheduled-ticks",
    "net.minecraft.server.network.ServerConnectionListener": "network",
}


def main():
    args = sys.argv[1:]
    thread, top = "Server thread", 8
    while args and args[0].startswith("--"):
        flag, val, args = args[0], args[1], args[2:]
        if flag == "--thread":
            thread = val
        elif flag == "--top":
            top = int(val)
    for path in args:
        comment, threads, sources = parse(path)
        match = [t for t in threads if t[0] == thread]
        if not match:
            names = ", ".join(sorted({t[0] for t in threads}))
            print(f"{comment or path}: no thread {thread!r} (have: {names})")
            continue
        _, nodes, roots = match[0]
        by_mod, by_phase = attribute(nodes, roots, sources)
        total = sum(by_mod.values()) or 1.0
        fmt = lambda d: ", ".join(  # noqa: E731
            f"{k} {v / total:.0%}" for k, v in sorted(d.items(), key=lambda kv: -kv[1])[:top]
        )
        print(f"{comment or path}: {fmt(by_mod)}")
        print(f"  by phase: {fmt(by_phase)}")


if __name__ == "__main__":
    main()

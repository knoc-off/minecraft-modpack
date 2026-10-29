#!/usr/bin/env python3
"""Static dependency check over a mods dir, nested jars included.

test-server.sh proves the server side loads, but 40-odd client-only mods
(shaders, Connector-run Fabric mods, minimaps) never load headless. This
catches the cheap half of what a client launch would: a required mod that is
missing, or present at a version outside the declared range. It cannot catch
mixin or runtime failures -- that still needs one real client launch.

Reads META-INF/neoforge.mods.toml (NeoForge) and fabric.mod.json (Fabric,
for Connector) from every jar and from jars nested under META-INF/jarjar or
META-INF/jars, since libraries are routinely bundled that way.

Usage: check_deps.py <mods dir> [<packwiz pack dir>]   exit 1 if anything is missing
       (with a pack dir, also checks Modrinth's declared required deps)
"""

import io
import json
import os
import re
import sys
import tomllib
import zipfile

# Provided by the loader or by Connector's runtime, never by a jar in mods/.
BUILTIN = {"minecraft", "neoforge", "java", "fml", "javafml", "forge",
           "fabricloader", "fabric", "fabric-api-base", "mixinextras"}


def read_jar(data, name, provides, needs, depth=0):
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return
    names = set(z.namelist())
    if "META-INF/neoforge.mods.toml" in names:
        try:
            t = tomllib.loads(z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace"))
        except tomllib.TOMLDecodeError:
            t = {}
        for m in t.get("mods", []):
            mid, ver = m.get("modId", ""), m.get("version", "")
            # JarJar resolves duplicates to the highest version on offer.
            if mid not in provides or ver_key(ver) > ver_key(provides[mid]):
                provides[mid] = ver
        for owner, deps in t.get("dependencies", {}).items():
            for d in deps:
                required = d.get("type", "").lower() == "required" or d.get("mandatory") is True
                if required and depth == 0:
                    needs.append((name, owner, d.get("modId", ""), d.get("versionRange", "")))
    if "fabric.mod.json" in names:
        try:
            j = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
        except json.JSONDecodeError:
            j = {}
        provides[j.get("id", "")] = j.get("version", "")
        for alias in j.get("provides", []):
            provides[alias if isinstance(alias, str) else alias.get("id", "")] = j.get("version", "")
        if depth == 0:
            for dep in j.get("depends", {}):
                needs.append((name, j.get("id", ""), dep, ""))
    for n in names:
        if n.startswith(("META-INF/jarjar/", "META-INF/jars/")) and n.endswith(".jar"):
            read_jar(z.read(n), name, provides, needs, depth + 1)


def ver_key(v):
    return [int(p) if p.isdigit() else p for p in re.findall(r"\d+|[a-z]+", v.lower())]


def in_range(version, rng):
    """Maven range check, good enough for [a,b), [a,), (,b] and bare *.

    A bare version with no brackets is a Maven "soft" requirement: any
    version satisfies it, which is how NeoForge treats it too."""
    if not rng or rng.strip() in ("*", "") or not version or "$" in version:
        return True
    if not re.search(r"[\[(]", rng):
        return True
    for part in re.findall(r"[\[(][^\])]*[\])]", rng):
        lo_inc, hi_inc = part[0] == "[", part[-1] == "]"
        bounds = part[1:-1].split(",")
        lo, hi = bounds[0].strip(), (bounds[1].strip() if len(bounds) > 1 else bounds[0].strip())
        v = ver_key(version)
        try:
            ok_lo = not lo or (v >= ver_key(lo) if lo_inc else v > ver_key(lo))
            ok_hi = not hi or (v <= ver_key(hi) if hi_inc else v < ver_key(hi))
        except TypeError:  # mixed int/str segments: don't guess
            return True
        if ok_lo and ok_hi:
            return True
    return False


def modrinth_missing(pack):
    """Required deps that Modrinth declares but the jar's own metadata does not.

    GPU Booster 1.3.2 calls into ToadLib yet its neoforge.mods.toml lists only
    neoforge/minecraft; only the Modrinth version record says so, and packwiz
    update never adds new dependencies. One bulk API call covers every pin."""
    import urllib.parse
    import urllib.request
    # Declared on Modrinth but satisfied otherwise here: fabric-api comes from
    # Connector's bundled Forgified Fabric API; mezzconfig is a Fabric/Forge
    # dep of JEI that its NeoForge build does not load.
    ignore = {"P7dR8mSH", "7tEfOcA7"}
    pins = {}
    for f in sorted(os.listdir(os.path.join(pack, "mods"))):
        if f.endswith(".pw.toml"):
            with open(os.path.join(pack, "mods", f), "rb") as fh:
                m = tomllib.load(fh).get("update", {}).get("modrinth")
            if m:
                pins[m["version"]] = (f, m["mod-id"])
    have = {mid for _, mid in pins.values()} | ignore
    url = "https://api.modrinth.com/v2/versions?ids=" + urllib.parse.quote(json.dumps(list(pins)))
    req = urllib.request.Request(url, headers={"User-Agent": "inkwell-pack/check_deps"})
    with urllib.request.urlopen(req, timeout=30) as r:
        versions = json.load(r)
    out = []
    for v in versions:
        for d in v.get("dependencies", []):
            if d.get("dependency_type") == "required" and d.get("project_id") and d["project_id"] not in have:
                out.append(f"MISSING  {pins[v['id']][0]}: Modrinth requires project {d['project_id']}")
    return out


def main():
    mods = sys.argv[1]
    provides, needs = {}, []
    for f in sorted(os.listdir(mods)):
        if f.endswith(".jar"):
            with open(os.path.join(mods, f), "rb") as fh:
                read_jar(fh.read(), f, provides, needs)
    bad = 0
    if len(sys.argv) > 2:
        try:
            for line in modrinth_missing(sys.argv[2]):
                print(line)
                bad += 1
        except OSError as e:
            print(f"(modrinth check skipped: {e})")
    for jar, owner, dep, rng in needs:
        if dep in BUILTIN or dep.startswith("fabric-") or not dep:
            continue
        if dep not in provides:
            print(f"MISSING  {jar}: {owner} needs {dep} {rng}")
            bad += 1
        elif not in_range(provides[dep], rng):
            print(f"VERSION  {jar}: {owner} needs {dep} {rng}, have {provides[dep]}")
            bad += 1
    print(f"{len(needs)} required deps checked, {bad} problems")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

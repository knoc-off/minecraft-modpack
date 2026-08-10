#!/usr/bin/env python3
"""Generate nix/mods.nix from packwiz .pw.toml files.

Reads every .pw.toml under the pack's resource directories (mods/,
shaderpacks/, resourcepacks/) and produces a Nix expression with fetchurl
derivations for each. Each entry carries a `dest` naming the directory it
belongs in, so the flake can rebuild the same layout inside .minecraft/.

packwiz decides the directory when a project is added -- `packwiz mr add
bsl-shaders` lands in shaderpacks/ because Modrinth types it as a shader.
Scanning only mods/ would silently drop those.

Local mods (packages/*/gradle.properties) are deliberately excluded: their
jars are committed under local-mods/ and the flake sources them from there.
Emitting a fetchurl for them would reintroduce a network dependency on
whatever host serves the pack -- and since the served pack's URLs point back
at that same host, a build would then require the artifact it is building.

Usage:
  gen_nix_from_packwiz.py [pack_dir] [output_path]

  pack_dir    defaults to ./pack
  output_path defaults to ./nix/mods.nix
"""

import glob
import os
import re
import sys
import tomllib


def local_mod_ids(repo_root: str) -> set[str]:
    """mod_id of every packages/<mod>/ that has a build.gradle.

    Same discovery scripts/build-mods.sh uses, so the two can't disagree.
    """
    ids = set()
    for props in glob.glob(os.path.join(repo_root, "packages", "*", "gradle.properties")):
        if not os.path.isfile(os.path.join(os.path.dirname(props), "build.gradle")):
            continue
        with open(props) as f:
            for line in f:
                m = re.match(r"\s*mod_id\s*=\s*(\S+)", line)
                if m:
                    ids.add(m.group(1))
                    break
    return ids


RESOURCE_DIRS = ["mods", "shaderpacks", "resourcepacks"]


def parse_pw_toml(path: str, dest: str) -> dict:
    with open(path, "rb") as f:
        data = tomllib.load(f)
    dl = data.get("download", {})
    return {
        "name": data.get("name", ""),
        "filename": data.get("filename", ""),
        "slug": os.path.basename(path).removesuffix(".pw.toml"),
        "dest": dest,
        "url": dl.get("url", ""),
        "hash": dl.get("hash", ""),
        "hash_format": dl.get("hash-format", "sha512"),
        # packwiz default is "both" when unspecified
        "side": data.get("side", "both"),
    }


def nix_attr_name(slug: str) -> str:
    return slug.replace(".", "-").replace("_", "-")


def generate(pack_dir: str, skip_ids: set[str]) -> str:
    entries = []
    for dest in RESOURCE_DIRS:
        pattern = os.path.join(pack_dir, dest, "*.pw.toml")
        entries.extend(parse_pw_toml(p, dest) for p in glob.glob(pattern))
    entries.sort(key=lambda m: (m["dest"], m["slug"]))

    skipped = [m for m in entries if m["slug"] in skip_ids]
    entries = [m for m in entries if m["slug"] not in skip_ids]
    for m in skipped:
        print(f"  skipping local mod: {m['slug']}", file=sys.stderr)

    # The attrset is keyed by slug alone, so the same slug in two resource
    # directories would silently drop one of them.
    seen = {}
    for m in entries:
        attr = nix_attr_name(m["slug"])
        if attr in seen:
            raise SystemExit(
                f"attribute name collision: {seen[attr]}/{m['slug']} and {m['dest']}/{m['slug']}"
            )
        seen[attr] = m["dest"]

    counts = ", ".join(
        f"{sum(1 for m in entries if m['dest'] == d)} {d}"
        for d in RESOURCE_DIRS
        if any(m["dest"] == d for m in entries)
    )

    lines = [
        "# Auto-generated from packwiz -- do not edit manually.",
        f"# {counts}",
        "",
        "{ pkgs }:",
        "",
        "{",
    ]

    for mod in entries:
        attr = nix_attr_name(mod["slug"])
        lines.append(f'  # {mod["name"]}')
        lines.append(f'  "{attr}" = {{')
        lines.append(f'    side = "{mod["side"]}";')
        lines.append(f'    dest = "{mod["dest"]}";')
        lines.append(f'    jar = pkgs.fetchurl {{')
        lines.append(f'      url = "{mod["url"]}";')
        lines.append(f'      outputHashAlgo = "{mod["hash_format"]}";')
        lines.append(f'      outputHash = "{mod["hash"]}";')
        lines.append(f'      name = "{mod["filename"]}";')
        lines.append(f"    }};")
        lines.append(f"  }};")
        lines.append("")

    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main():
    pack_dir = sys.argv[1] if len(sys.argv) > 1 else "./pack"
    output = sys.argv[2] if len(sys.argv) > 2 else "./nix/mods.nix"

    if not os.path.isdir(os.path.join(pack_dir, "mods")):
        print(f"Error: {pack_dir}/mods/ not found", file=sys.stderr)
        sys.exit(1)

    repo_root = os.path.dirname(os.path.abspath(pack_dir.rstrip("/")))
    nix_src = generate(pack_dir, local_mod_ids(repo_root))

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w") as f:
        f.write(nix_src)

    count = nix_src.count("pkgs.fetchurl")
    print(f"Wrote {count} entries to {output}")


if __name__ == "__main__":
    main()

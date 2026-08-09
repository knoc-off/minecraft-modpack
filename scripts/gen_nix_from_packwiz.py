#!/usr/bin/env python3
"""Generate nix/mods.nix from packwiz .pw.toml files.

Reads all pack/mods/*.pw.toml and produces a Nix expression with
fetchurl derivations for each mod.

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


def parse_pw_toml(path: str) -> dict:
    with open(path, "rb") as f:
        data = tomllib.load(f)
    dl = data.get("download", {})
    return {
        "name": data.get("name", ""),
        "filename": data.get("filename", ""),
        "slug": os.path.basename(path).removesuffix(".pw.toml"),
        "url": dl.get("url", ""),
        "hash": dl.get("hash", ""),
        "hash_format": dl.get("hash-format", "sha512"),
        # packwiz default is "both" when unspecified
        "side": data.get("side", "both"),
    }


def nix_attr_name(slug: str) -> str:
    return slug.replace(".", "-").replace("_", "-")


def generate(pack_dir: str, skip_ids: set[str]) -> str:
    pattern = os.path.join(pack_dir, "mods", "*.pw.toml")
    mods = sorted(
        (parse_pw_toml(p) for p in glob.glob(pattern)),
        key=lambda m: m["slug"],
    )

    skipped = [m for m in mods if m["slug"] in skip_ids]
    mods = [m for m in mods if m["slug"] not in skip_ids]
    for m in skipped:
        print(f"  skipping local mod: {m['slug']}", file=sys.stderr)

    lines = [
        f"# Auto-generated from packwiz -- do not edit manually.",
        f"# {len(mods)} mods",
        "",
        "{ pkgs }:",
        "",
        "{",
    ]

    for mod in mods:
        attr = nix_attr_name(mod["slug"])
        lines.append(f'  # {mod["name"]}')
        lines.append(f'  "{attr}" = {{')
        lines.append(f'    side = "{mod["side"]}";')
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
    print(f"Wrote {count} mods to {output}")


if __name__ == "__main__":
    main()

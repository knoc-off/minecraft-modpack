#!/usr/bin/env python3
"""Generate a Packwiz pack from resolved mod data.

Reads the output of resolve_mods.py and produces a valid packwiz
directory structure with pack.toml, index.toml, and per-mod .pw.toml files.
"""

import json
import os
import sys
import hashlib

LOADER_KEYS = {
    "fabric": "fabric",
    "forge": "forge",
    "neoforge": "neoforge",
    "quilt": "quilt",
}

LOADER_VERSIONS = {
    "fabric": "0.16.14",
    "forge": "47.3.0",
    "neoforge": "21.1.172",
    "quilt": "0.27.1",
}


def sanitize_filename(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_." else "-" for c in name.lower())


def generate_pack_toml(pack_name: str, mc_version: str, loader: str, loader_version: str = None) -> str:
    lv = loader_version or LOADER_VERSIONS.get(loader, "latest")
    loader_key = LOADER_KEYS.get(loader, loader)

    return f"""[pack]
name = "{pack_name}"
author = ""
version = "1.0.0"
description = ""

[versions]
minecraft = "{mc_version}"
{loader_key} = "{lv}"

[pack.index]
file = "index.toml"
hash-format = "sha256"
"""


def generate_mod_toml(mod: dict) -> str:
    return f"""name = "{mod['title']}"
filename = "{mod['slug']}-{mod['version_number']}.jar"
side = "{classify_side(mod)}"

[update]
[update.modrinth]
mod-id = "{mod['project_id']}"
version = "{mod['version_id']}"

[download]
url = "{mod['file_url']}"
hash-format = "{mod['hash_format']}"
hash = "{mod['file_hash']}"
"""


def classify_side(mod: dict) -> str:
    client = mod.get("client_side", "unknown")
    server = mod.get("server_side", "unknown")
    if client == "required" and server in ("unsupported", "optional"):
        return "client"
    if server == "required" and client in ("unsupported", "optional"):
        return "server"
    return "both"


def compute_index_hash(content: str) -> str:
    return hashlib.sha256(content.encode()).hexdigest()


def generate_pack(resolved_data: dict, output_dir: str, pack_name: str = None):
    mc_version = resolved_data["minecraft_version"]
    loader = resolved_data["loader"]
    mods = resolved_data["resolved"]
    name = pack_name or f"modpack-{mc_version}-{loader}"

    os.makedirs(os.path.join(output_dir, "mods"), exist_ok=True)

    pack_toml = generate_pack_toml(name, mc_version, loader)
    with open(os.path.join(output_dir, "pack.toml"), "w") as f:
        f.write(pack_toml)

    index_entries = []
    for mod in mods:
        filename = f"{sanitize_filename(mod['slug'])}.pw.toml"
        mod_toml = generate_mod_toml(mod)
        mod_path = os.path.join(output_dir, "mods", filename)
        with open(mod_path, "w") as f:
            f.write(mod_toml)

        entry_hash = compute_index_hash(mod_toml)
        index_entries.append(f"""[[files]]
file = "mods/{filename}"
hash = "{entry_hash}"
hash-format = "sha256"
metafile = true
""")

    index_toml = "hash-format = \"sha256\"\n\n" + "\n".join(index_entries)
    with open(os.path.join(output_dir, "index.toml"), "w") as f:
        f.write(index_toml)

    print(f"Generated packwiz pack at {output_dir}/")
    print(f"  {len(mods)} mods, targeting {loader} on Minecraft {mc_version}")
    print(f"  Files: pack.toml, index.toml, mods/*.pw.toml")

    if resolved_data.get("conflicts"):
        print(f"\n  WARNING: {len(resolved_data['conflicts'])} unresolved conflicts:")
        for c in resolved_data["conflicts"]:
            print(f"    - {c['mod']}: {c['reason']}")


def main():
    if len(sys.argv) < 3:
        print("Usage: gen_packwiz.py <resolved.json> <output_dir> [pack_name]")
        sys.exit(1)

    with open(sys.argv[1]) as f:
        data = json.load(f)

    output_dir = sys.argv[2]
    pack_name = sys.argv[3] if len(sys.argv) > 3 else None
    generate_pack(data, output_dir, pack_name)


if __name__ == "__main__":
    main()

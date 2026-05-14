#!/usr/bin/env python3
"""Resolve Minecraft mod compatibility via the Modrinth API.

Takes a JSON manifest of desired mods and target platform,
queries Modrinth for compatible versions, resolves dependencies,
and reports conflicts.
"""

import json
import sys
import time
import urllib.request
import urllib.parse
import urllib.error
from dataclasses import dataclass, field, asdict
from typing import Optional

API_BASE = "https://api.modrinth.com/v2"
USER_AGENT = "minecraft-modpack-skill/1.0 (claude-skill)"
RATE_LIMIT_DELAY = 0.2


@dataclass
class ModRequest:
    name: str
    slug: Optional[str] = None
    required: bool = True


@dataclass
class ResolvedMod:
    slug: str
    title: str
    project_id: str
    version_id: str
    version_number: str
    file_url: str
    file_hash: str
    hash_format: str = "sha512"
    dependencies: list = field(default_factory=list)
    client_side: str = "unknown"
    server_side: str = "unknown"
    source_url: str = ""
    license: str = ""
    file_size: int = 0


@dataclass
class Conflict:
    mod: str
    reason: str
    suggestions: list = field(default_factory=list)


def api_get(path: str, params: dict = None) -> dict:
    url = f"{API_BASE}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params, doseq=True)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    time.sleep(RATE_LIMIT_DELAY)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def search_mod(query: str, loader: str, mc_version: str) -> Optional[dict]:
    facets = json.dumps([
        [f"categories:{loader}"],
        [f"versions:{mc_version}"],
        ["project_type:mod"],
    ])
    data = api_get("/search", {"query": query, "facets": facets, "limit": 5})
    if not data or not data.get("hits"):
        return None
    for hit in data["hits"]:
        if hit["slug"].lower() == query.lower() or hit["title"].lower() == query.lower():
            return hit
    return data["hits"][0]


def get_project(id_or_slug: str) -> Optional[dict]:
    return api_get(f"/project/{id_or_slug}")


def get_versions(project_id: str, loader: str, mc_version: str) -> list:
    return api_get(f"/project/{project_id}/version", {
        "loaders": json.dumps([loader]),
        "game_versions": json.dumps([mc_version]),
    }) or []


def resolve_single(mod: ModRequest, loader: str, mc_version: str) -> tuple:
    identifier = mod.slug or mod.name
    project = get_project(identifier) if mod.slug else None
    if not project:
        hit = search_mod(mod.name, loader, mc_version)
        if not hit:
            return None, Conflict(
                mod=mod.name,
                reason=f"Not found on Modrinth for {loader} {mc_version}",
                suggestions=["Check spelling", "Try CurseForge", "Try a different MC version"],
            )
        project = get_project(hit["project_id"])

    versions = get_versions(project["id"], loader, mc_version)
    if not versions:
        return None, Conflict(
            mod=project["title"],
            reason=f"No versions available for {loader} on {mc_version}",
            suggestions=[
                f"Check if {project['title']} supports a different loader",
                "Try a different MC version",
                f"Visit https://modrinth.com/mod/{project['slug']} for details",
            ],
        )

    best = versions[0]
    primary_file = next((f for f in best["files"] if f.get("primary", False)), best["files"][0])
    hashes = primary_file.get("hashes", {})
    file_hash = hashes.get("sha512", hashes.get("sha1", ""))
    hash_format = "sha512" if "sha512" in hashes else "sha1"

    deps = []
    for dep in best.get("dependencies", []):
        if dep.get("dependency_type") in ("required", "optional"):
            deps.append({
                "project_id": dep.get("project_id"),
                "version_id": dep.get("version_id"),
                "type": dep["dependency_type"],
            })

    license_info = project.get("license", {})
    license_id = license_info.get("id", "") if isinstance(license_info, dict) else str(license_info)

    resolved = ResolvedMod(
        slug=project["slug"],
        title=project["title"],
        project_id=project["id"],
        version_id=best["id"],
        version_number=best["version_number"],
        file_url=primary_file["url"],
        file_hash=file_hash,
        hash_format=hash_format,
        dependencies=deps,
        client_side=project.get("client_side", "unknown"),
        server_side=project.get("server_side", "unknown"),
        source_url=project.get("source_url", "") or "",
        license=license_id,
        file_size=primary_file.get("size", 0),
    )
    return resolved, None


def resolve_dependencies(resolved: dict, loader: str, mc_version: str, depth: int = 0):
    if depth > 5:
        return [], []
    new_resolved = []
    new_conflicts = []

    all_deps = []
    for mod in resolved.values():
        for dep in mod.dependencies:
            pid = dep.get("project_id")
            if pid and pid not in resolved:
                all_deps.append(pid)

    for pid in set(all_deps):
        project = get_project(pid)
        if not project:
            continue
        if project["slug"] in resolved:
            continue
        req = ModRequest(name=project["title"], slug=project["slug"])
        mod, conflict = resolve_single(req, loader, mc_version)
        if mod:
            resolved[mod.slug] = mod
            new_resolved.append(mod)
        elif conflict:
            new_conflicts.append(conflict)

    if new_resolved:
        deeper_resolved, deeper_conflicts = resolve_dependencies(resolved, loader, mc_version, depth + 1)
        new_resolved.extend(deeper_resolved)
        new_conflicts.extend(deeper_conflicts)

    return new_resolved, new_conflicts


def resolve_all(manifest: dict) -> dict:
    mc_version = manifest["minecraft_version"]
    loader = manifest["loader"]
    mods = [ModRequest(**m) for m in manifest["mods"]]

    resolved = {}
    conflicts = []

    print(f"Resolving {len(mods)} mods for {loader} on Minecraft {mc_version}...\n")

    for mod in mods:
        print(f"  Resolving {mod.name}...", end=" ", flush=True)
        result, conflict = resolve_single(mod, loader, mc_version)
        if result:
            resolved[result.slug] = result
            print(f"OK ({result.version_number})")
        else:
            conflicts.append(conflict)
            print(f"CONFLICT: {conflict.reason}")

    print(f"\nResolving dependencies...")
    dep_resolved, dep_conflicts = resolve_dependencies(resolved, loader, mc_version)
    if dep_resolved:
        print(f"  Added {len(dep_resolved)} dependencies: {', '.join(m.title for m in dep_resolved)}")
    conflicts.extend(dep_conflicts)

    output = {
        "minecraft_version": mc_version,
        "loader": loader,
        "resolved": [asdict(m) for m in resolved.values()],
        "conflicts": [asdict(c) for c in conflicts],
        "summary": {
            "requested": len(mods),
            "resolved": len(resolved),
            "conflicts": len(conflicts),
            "dependencies_added": len(dep_resolved),
        },
    }

    print(f"\nSummary: {len(resolved)} resolved, {len(conflicts)} conflicts, {len(dep_resolved)} deps added")
    return output


def main():
    if len(sys.argv) < 2:
        print("Usage: resolve_mods.py <manifest.json> [output.json]")
        print("\nManifest format:")
        print(json.dumps({
            "minecraft_version": "1.20.1",
            "loader": "fabric",
            "mods": [
                {"name": "sodium", "slug": "sodium"},
                {"name": "Create", "required": True},
            ],
        }, indent=2))
        sys.exit(1)

    with open(sys.argv[1]) as f:
        manifest = json.load(f)

    result = resolve_all(manifest)

    output_path = sys.argv[2] if len(sys.argv) > 2 else None
    if output_path:
        with open(output_path, "w") as f:
            json.dump(result, f, indent=2)
        print(f"\nResults written to {output_path}")
    else:
        print("\n" + json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

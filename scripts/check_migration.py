#!/usr/bin/env python3
"""Check modpack migration readiness to a new Minecraft version.

Takes an existing packwiz pack (or resolved.json) and a target MC version.
Produces a migration report with three layers of intelligence:
  L1: Modrinth changelogs (always)
  L2: GitHub activity for blocked mods (source_url required)
  L3: Detailed changelog/PR analysis for critical mods (on-demand)

Respects GITHUB_TOKEN env var for higher rate limits.
"""

import json
import os
import re
import sys
import time
import urllib.request
import urllib.parse
import urllib.error
from dataclasses import dataclass, field, asdict
from typing import Optional
from pathlib import Path

API_MODRINTH = "https://api.modrinth.com/v2"
API_GITHUB = "https://api.github.com"
USER_AGENT = "minecraft-modpack-skill/1.0 (claude-skill)"
RATE_LIMIT_DELAY = 0.2

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN")

SMALL_MOD_THRESHOLD_BYTES = 500_000  # 500KB
LARGE_MOD_THRESHOLD_BYTES = 5_000_000  # 5MB


@dataclass
class ModStatus:
    slug: str
    title: str
    project_id: str
    current_version: str
    status: str  # ready, updatable, blocked
    target_version: Optional[str] = None
    target_version_id: Optional[str] = None
    changelogs: list = field(default_factory=list)
    source_url: Optional[str] = None
    license: Optional[str] = None
    file_size: int = 0
    patchability: Optional[str] = None  # easy, moderate, hard, impractical
    github_activity: Optional[dict] = None
    migration_notes: list = field(default_factory=list)


def api_get(base: str, path: str, params: dict = None, headers: dict = None) -> Optional[dict]:
    url = f"{base}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params, doseq=True)
    hdrs = {"User-Agent": USER_AGENT}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, headers=hdrs)
    time.sleep(RATE_LIMIT_DELAY)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        if e.code in (404, 403, 422):
            return None
        raise


def modrinth_get(path: str, params: dict = None):
    return api_get(API_MODRINTH, path, params)


def github_get(path: str, params: dict = None):
    headers = {}
    if GITHUB_TOKEN:
        headers["Authorization"] = f"token {GITHUB_TOKEN}"
    return api_get(API_GITHUB, path, params, headers)


def parse_github_url(url: str) -> Optional[tuple]:
    """Extract (owner, repo) from a GitHub URL."""
    if not url:
        return None
    m = re.match(r"https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$", url)
    if m:
        return m.group(1), m.group(2)
    return None


def get_modrinth_changelogs(project_id: str, loader: str, current_mc: str, target_mc: str) -> list:
    """L1: Fetch changelogs for versions between current and target MC versions."""
    versions = modrinth_get(f"/project/{project_id}/version", {
        "loaders": json.dumps([loader]),
    })
    if not versions:
        return []

    changelogs = []
    for v in versions:
        game_versions = v.get("game_versions", [])
        changelog = v.get("changelog", "").strip()
        if not changelog:
            continue
        if target_mc in game_versions or current_mc in game_versions:
            changelogs.append({
                "version": v["version_number"],
                "game_versions": game_versions,
                "changelog": changelog[:2000],
                "date": v.get("date_published", ""),
            })

    return changelogs


def check_github_activity(owner: str, repo: str, target_mc: str) -> Optional[dict]:
    """L2: Check GitHub for port activity toward the target version."""
    activity = {
        "port_prs": [],
        "port_branches": [],
        "recent_commits": 0,
        "last_commit_date": None,
        "open_issues_mentioning_version": 0,
        "assessment": "unknown",
    }

    # Check for PRs mentioning the target version
    prs = github_get(f"/repos/{owner}/{repo}/pulls", {
        "state": "all",
        "per_page": 30,
        "sort": "updated",
        "direction": "desc",
    })
    if prs:
        version_patterns = [target_mc, target_mc.replace(".", "_"), target_mc.replace(".", "")]
        for pr in prs:
            title_lower = (pr.get("title", "") + " " + (pr.get("body", "") or "")).lower()
            if any(p in title_lower for p in version_patterns):
                activity["port_prs"].append({
                    "number": pr["number"],
                    "title": pr["title"],
                    "state": pr["state"],
                    "merged": pr.get("merged_at") is not None,
                    "updated": pr.get("updated_at", ""),
                    "url": pr["html_url"],
                })

    # Check for branches mentioning the target version
    branches = github_get(f"/repos/{owner}/{repo}/branches", {"per_page": 100})
    if branches:
        for branch in branches:
            name = branch["name"].lower()
            if any(p in name for p in [target_mc, target_mc.replace(".", "_"), target_mc.replace(".", "")]):
                activity["port_branches"].append(branch["name"])

    # Recent commit activity
    commits = github_get(f"/repos/{owner}/{repo}/commits", {"per_page": 5})
    if commits:
        activity["recent_commits"] = len(commits)
        if commits[0].get("commit", {}).get("committer", {}).get("date"):
            activity["last_commit_date"] = commits[0]["commit"]["committer"]["date"]

    # Search issues for version mentions
    search = github_get("/search/issues", {
        "q": f"repo:{owner}/{repo} is:issue {target_mc}",
        "per_page": 5,
    })
    if search:
        activity["open_issues_mentioning_version"] = search.get("total_count", 0)

    # Assess
    if any(pr.get("merged") for pr in activity["port_prs"]):
        activity["assessment"] = "port_merged"
    elif any(pr["state"] == "open" for pr in activity["port_prs"]):
        activity["assessment"] = "port_in_progress"
    elif activity["port_branches"]:
        activity["assessment"] = "branch_exists"
    elif activity["last_commit_date"] and activity["recent_commits"] > 0:
        activity["assessment"] = "active_development"
    elif activity["recent_commits"] == 0:
        activity["assessment"] = "possibly_abandoned"
    else:
        activity["assessment"] = "no_port_signals"

    return activity


def get_detailed_changes(owner: str, repo: str, from_tag: str, to_tag: str) -> Optional[dict]:
    """L3: Detailed changelog between two tags/releases."""
    details = {
        "release_notes": [],
        "merged_prs": [],
        "breaking_changes": [],
    }

    releases = github_get(f"/repos/{owner}/{repo}/releases", {"per_page": 50})
    if releases:
        collecting = False
        for rel in releases:
            tag = rel.get("tag_name", "")
            if tag == to_tag:
                collecting = True
            if collecting:
                body = rel.get("body", "") or ""
                details["release_notes"].append({
                    "tag": tag,
                    "name": rel.get("name", ""),
                    "body": body[:1500],
                    "date": rel.get("published_at", ""),
                })
                body_lower = body.lower()
                if any(kw in body_lower for kw in ["breaking", "removed", "deprecated", "migration", "renamed"]):
                    details["breaking_changes"].append({
                        "tag": tag,
                        "excerpt": body[:500],
                    })
            if tag == from_tag:
                break

    compare = github_get(f"/repos/{owner}/{repo}/compare/{from_tag}...{to_tag}")
    if compare:
        for commit in (compare.get("commits") or [])[:20]:
            msg = commit.get("commit", {}).get("message", "")
            if msg.startswith("Merge pull request"):
                details["merged_prs"].append(msg.split("\n")[0])

    return details


def assess_patchability(mod: dict, source_url: str, license_id: str) -> str:
    """Heuristic for how patchable a blocked mod is."""
    size = mod.get("file_size", 0)
    has_source = bool(parse_github_url(source_url))
    open_license = license_id in (
        "MIT", "Apache-2.0", "GPL-3.0-only", "GPL-3.0-or-later",
        "LGPL-3.0-only", "LGPL-3.0-or-later", "MPL-2.0", "ISC",
        "BSD-2-Clause", "BSD-3-Clause", "Unlicense", "CC0-1.0",
        "GPL-2.0-only", "GPL-2.0-or-later", "LGPL-2.1-only",
    )

    if not has_source:
        return "impractical"
    if not open_license:
        return "hard"
    if size > LARGE_MOD_THRESHOLD_BYTES:
        return "impractical"
    if size > SMALL_MOD_THRESHOLD_BYTES:
        return "hard"
    if size > 200_000:
        return "moderate"
    return "easy"


def load_existing_pack(pack_dir: str) -> list:
    """Load mod list from an existing packwiz pack directory."""
    mods = []
    mods_dir = Path(pack_dir) / "mods"
    if not mods_dir.exists():
        return mods

    if sys.version_info >= (3, 11):
        import tomllib
    else:
        tomllib = None

    for pw_file in mods_dir.glob("*.pw.toml"):
        with open(pw_file, "rb") as f:
            if tomllib:
                data = tomllib.load(f)
            else:
                # Fallback: basic TOML parsing for the fields we need
                f.seek(0)
                text = f.read().decode()
                data = _parse_simple_toml(text)

        modrinth = data.get("update", {}).get("modrinth", {})
        if modrinth:
            mods.append({
                "name": data.get("name", pw_file.stem),
                "slug": pw_file.stem,
                "project_id": modrinth.get("mod-id", ""),
                "version_id": modrinth.get("version", ""),
            })
    return mods


def _parse_simple_toml(text: str) -> dict:
    """Minimal TOML parser for packwiz .pw.toml files."""
    result = {}
    current_section = result
    section_path = []

    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue

        section_match = re.match(r"\[([^\]]+)\]", line)
        if section_match:
            section_path = section_match.group(1).split(".")
            current_section = result
            for key in section_path:
                current_section = current_section.setdefault(key, {})
            continue

        kv_match = re.match(r'(\S+)\s*=\s*"([^"]*)"', line)
        if kv_match:
            current_section[kv_match.group(1)] = kv_match.group(2)

    return result


def check_migration(
    resolved_or_pack: dict | str,
    target_mc: str,
    loader: str,
    critical_mods: list = None,
    layer: int = 2,
) -> dict:
    """Run migration check at the specified layer depth."""
    critical_mods = set(critical_mods or [])

    if isinstance(resolved_or_pack, str):
        mods = load_existing_pack(resolved_or_pack)
        current_mc = "unknown"
    else:
        mods = resolved_or_pack.get("resolved", [])
        current_mc = resolved_or_pack.get("minecraft_version", "unknown")

    results = []
    summary = {"ready": 0, "updatable": 0, "blocked": 0, "total": len(mods)}

    print(f"Checking migration: {current_mc} -> {target_mc} ({loader})")
    print(f"Mods to check: {len(mods)}, Layer: {layer}\n")

    for mod_data in mods:
        pid = mod_data.get("project_id", "")
        slug = mod_data.get("slug", mod_data.get("name", "unknown"))
        current_ver = mod_data.get("version_number", mod_data.get("version_id", ""))

        print(f"  Checking {slug}...", end=" ", flush=True)

        project = modrinth_get(f"/project/{pid}") if pid else None
        if not project:
            project = {}

        source_url = project.get("source_url", "")
        license_id = project.get("license", {}).get("id", "") if isinstance(project.get("license"), dict) else ""

        # Check for target version availability
        target_versions = modrinth_get(f"/project/{pid}/version", {
            "loaders": json.dumps([loader]),
            "game_versions": json.dumps([target_mc]),
        }) if pid else []
        target_versions = target_versions or []

        if target_versions:
            best = target_versions[0]
            primary_file = next((f for f in best["files"] if f.get("primary", False)), best["files"][0])
            file_size = primary_file.get("size", 0)

            if best["id"] == mod_data.get("version_id"):
                status_str = "ready"
            else:
                status_str = "updatable"

            mod_status = ModStatus(
                slug=slug,
                title=project.get("title", slug),
                project_id=pid,
                current_version=current_ver,
                status=status_str,
                target_version=best["version_number"],
                target_version_id=best["id"],
                source_url=source_url,
                license=license_id,
                file_size=file_size,
            )
            summary[status_str] += 1
            print(f"{status_str.upper()} ({best['version_number']})")
        else:
            file_size = 0
            if mod_data.get("file_url"):
                pass  # Could HEAD request for size, but skip for now

            mod_status = ModStatus(
                slug=slug,
                title=project.get("title", slug),
                project_id=pid,
                current_version=current_ver,
                status="blocked",
                source_url=source_url,
                license=license_id,
                file_size=file_size,
                patchability=assess_patchability(
                    {"file_size": file_size}, source_url, license_id
                ),
            )
            summary["blocked"] += 1
            print(f"BLOCKED (no {target_mc} version)")

        # L1: Modrinth changelogs (always)
        if pid:
            mod_status.changelogs = get_modrinth_changelogs(pid, loader, current_mc, target_mc)

        # L2: GitHub activity (for blocked mods, or if layer >= 2)
        gh_info = parse_github_url(source_url)
        if layer >= 2 and gh_info and mod_status.status == "blocked":
            owner, repo = gh_info
            print(f"    Checking GitHub activity for {owner}/{repo}...", flush=True)
            mod_status.github_activity = check_github_activity(owner, repo, target_mc)
            assessment = mod_status.github_activity.get("assessment", "unknown")
            print(f"    GitHub: {assessment}")

            if assessment == "port_merged":
                mod_status.migration_notes.append(
                    "Port PR was merged on GitHub but no Modrinth release yet. "
                    "Check for a pre-release or build from source."
                )
            elif assessment == "port_in_progress":
                prs = mod_status.github_activity.get("port_prs", [])
                open_prs = [p for p in prs if p["state"] == "open"]
                if open_prs:
                    mod_status.migration_notes.append(
                        f"Port PR open: {open_prs[0]['title']} ({open_prs[0]['url']}). "
                        "Consider waiting or building from the PR branch."
                    )
            elif assessment == "possibly_abandoned":
                mod_status.migration_notes.append(
                    "No recent activity. Consider finding an alternative or forking."
                )

        # L3: Detailed changes (for critical mods with version jumps)
        if layer >= 3 and gh_info and slug in critical_mods and mod_status.status == "updatable":
            owner, repo = gh_info
            print(f"    Fetching detailed changes for {owner}/{repo}...", flush=True)
            details = get_detailed_changes(owner, repo, current_ver, mod_status.target_version or "HEAD")
            if details and details.get("breaking_changes"):
                mod_status.migration_notes.append(
                    f"Breaking changes detected in {len(details['breaking_changes'])} release(s). "
                    "Review config migration requirements."
                )
            mod_status.github_activity = mod_status.github_activity or {}
            mod_status.github_activity["detailed_changes"] = details

        results.append(mod_status)

    output = {
        "migration": {
            "from": current_mc,
            "to": target_mc,
            "loader": loader,
            "layer": layer,
        },
        "summary": summary,
        "mods": [asdict(m) for m in results],
        "blocked_report": [
            {
                "mod": m.slug,
                "title": m.title,
                "patchability": m.patchability,
                "source_url": m.source_url,
                "license": m.license,
                "github_assessment": (m.github_activity or {}).get("assessment"),
                "notes": m.migration_notes,
            }
            for m in results if m.status == "blocked"
        ],
    }

    print(f"\nMigration Summary: {summary['ready']} ready, "
          f"{summary['updatable']} updatable, {summary['blocked']} blocked "
          f"out of {summary['total']}")

    return output


def main():
    if len(sys.argv) < 4:
        print("Usage: check_migration.py <resolved.json|pack_dir> <target_mc_version> <loader> [output.json] [--layer N] [--critical mod1,mod2]")
        print("\nLayers:")
        print("  1: Modrinth changelogs only")
        print("  2: + GitHub activity for blocked mods (default)")
        print("  3: + Detailed PR/release analysis for critical mods")
        print("\nSet GITHUB_TOKEN env var for higher GitHub API rate limits.")
        sys.exit(1)

    source = sys.argv[1]
    target_mc = sys.argv[2]
    loader = sys.argv[3]

    output_path = None
    layer_depth = 2
    critical = []

    i = 4
    while i < len(sys.argv):
        if sys.argv[i] == "--layer" and i + 1 < len(sys.argv):
            layer_depth = int(sys.argv[i + 1])
            i += 2
        elif sys.argv[i] == "--critical" and i + 1 < len(sys.argv):
            critical = sys.argv[i + 1].split(",")
            i += 2
        else:
            output_path = sys.argv[i]
            i += 1

    if source.endswith(".json"):
        with open(source) as f:
            data = json.load(f)
    else:
        data = source  # pack directory path

    result = check_migration(data, target_mc, loader, critical, layer_depth)

    if output_path:
        with open(output_path, "w") as f:
            json.dump(result, f, indent=2)
        print(f"\nReport written to {output_path}")
    else:
        print("\n" + json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Parse Minecraft logs for mod-related errors.

Reads a log file (or stdin) and extracts actionable issues:
mod loading failures, missing dependencies, mixin conflicts,
config errors, and crashes with responsible mod identification.
"""

import json
import re
import sys
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Optional


class Severity(str, Enum):
    FATAL = "fatal"
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"


@dataclass
class LogIssue:
    severity: str
    category: str
    mod: Optional[str]
    message: str
    details: str = ""
    suggestions: list = field(default_factory=list)
    line_number: int = 0


PATTERNS = [
    {
        "name": "missing_dependency",
        "pattern": re.compile(
            r"(?:Mod|mod)\s+['\"]?(\S+?)['\"]?\s+requires\s+(?:mod\s+)?['\"]?(\S+?)['\"]?\s+"
            r"(?:version\s+)?([^\s,]+)",
            re.IGNORECASE,
        ),
        "severity": Severity.ERROR,
        "category": "missing_dependency",
        "extract": lambda m: {
            "mod": m.group(1),
            "message": f"{m.group(1)} requires {m.group(2)} {m.group(3)}",
            "suggestions": [
                f"Add {m.group(2)} to the modpack",
                f"Check if {m.group(2)} is available for the target version",
                f"Remove {m.group(1)} if {m.group(2)} is unavailable",
            ],
        },
    },
    {
        "name": "version_mismatch",
        "pattern": re.compile(
            r"(?:Incompatible|incompatible)\s+mod\s+set.*?['\"](\S+?)['\"].*?version\s+(\S+).*?"
            r"(?:requires|needs|expected)\s+(\S+)",
            re.IGNORECASE | re.DOTALL,
        ),
        "severity": Severity.ERROR,
        "category": "version_mismatch",
        "extract": lambda m: {
            "mod": m.group(1),
            "message": f"Version mismatch: {m.group(1)} has {m.group(2)}, needs {m.group(3)}",
            "suggestions": [
                f"Update {m.group(1)} to a version providing {m.group(3)}",
                "Check for a compatibility patch",
            ],
        },
    },
    {
        "name": "mixin_conflict",
        "pattern": re.compile(
            r"Mixin apply.*?(\S+\.mixins?\.\S+).*?failed.*?in\s+(\S+)|"
            r"Mixin apply.*?failed.*?(\S+\.mixins?\.\S+).*?in\s+(\S+)",
            re.IGNORECASE,
        ),
        "severity": Severity.ERROR,
        "category": "mixin_conflict",
        "extract": lambda m: {
            "mod": m.group(2) or m.group(4),
            "message": f"Mixin conflict in {m.group(1) or m.group(3)} from {m.group(2) or m.group(4)}",
            "suggestions": [
                f"Check for known conflicts with {m.group(2) or m.group(4)}",
                "Try removing one of the conflicting mods",
                "Look for a compat mod or updated version",
            ],
        },
    },
    {
        "name": "mixin_error_alt",
        "pattern": re.compile(
            r"(org\.spongepowered\.asm\.mixin\..*?Exception).*?:(.+)",
            re.IGNORECASE,
        ),
        "severity": Severity.ERROR,
        "category": "mixin_conflict",
        "extract": lambda m: {
            "mod": None,
            "message": f"Mixin error: {m.group(1).split('.')[-1]}: {m.group(2).strip()[:200]}",
            "suggestions": [
                "Check the full stack trace to identify the responsible mod",
                "This is often caused by two mods modifying the same game class",
            ],
        },
    },
    {
        "name": "duplicate_mod",
        "pattern": re.compile(
            r"[Dd]uplicate\s+mod[:\s]+['\"]?(\S+)['\"]?",
        ),
        "severity": Severity.ERROR,
        "category": "duplicate_mod",
        "extract": lambda m: {
            "mod": m.group(1),
            "message": f"Duplicate mod detected: {m.group(1)}",
            "suggestions": [
                f"Remove one copy of {m.group(1)} from the mods folder",
                "Check if a dependency bundle already includes this mod",
            ],
        },
    },
    {
        "name": "config_error",
        "pattern": re.compile(
            r"(?:Error|Failed)\s+(?:loading|parsing|reading)\s+config.*?['\"]?([^\s'\"]+\.(?:toml|json|cfg|yml))['\"]?",
            re.IGNORECASE,
        ),
        "severity": Severity.WARNING,
        "category": "config_error",
        "extract": lambda m: {
            "mod": None,
            "message": f"Config parse error: {m.group(1)}",
            "suggestions": [
                f"Delete {m.group(1)} and let the mod regenerate defaults",
                "Check for syntax errors (mismatched brackets, invalid TOML)",
            ],
        },
    },
    {
        "name": "crash_mod_id",
        "pattern": re.compile(
            r"(?:Caused by|crash|exception).*?(?:mod|from)\s+['\"]?(\w[\w-]+)['\"]?",
            re.IGNORECASE,
        ),
        "severity": Severity.FATAL,
        "category": "crash",
        "extract": lambda m: {
            "mod": m.group(1),
            "message": f"Crash likely caused by {m.group(1)}",
            "suggestions": [
                f"Update {m.group(1)} to the latest version",
                f"Check {m.group(1)}'s issue tracker for known crashes",
                f"Try removing {m.group(1)} to confirm it's the cause",
            ],
        },
    },
    {
        "name": "fabric_loader_missing",
        "pattern": re.compile(
            r"net\.fabricmc\.loader\.impl\.FormattedException.*?:(.+)",
        ),
        "severity": Severity.FATAL,
        "category": "loader_error",
        "extract": lambda m: {
            "mod": None,
            "message": f"Fabric Loader error: {m.group(1).strip()[:200]}",
            "suggestions": ["Check the full error for missing mods or version conflicts"],
        },
    },
    {
        "name": "forge_missing_mod",
        "pattern": re.compile(
            r"Missing or unsupported mandatory dependencies.*?Mod ID:\s*['\"]?(\S+?)['\"]?,\s*Requested by:\s*['\"]?(\S+?)['\"]?",
            re.IGNORECASE | re.DOTALL,
        ),
        "severity": Severity.ERROR,
        "category": "missing_dependency",
        "extract": lambda m: {
            "mod": m.group(2),
            "message": f"{m.group(2)} requires missing mod {m.group(1)}",
            "suggestions": [f"Add {m.group(1)} to the modpack"],
        },
    },
]

SUCCESS_INDICATORS = [
    re.compile(r"Done \(\d+\.\d+s\)! For help, type", re.IGNORECASE),
    re.compile(r"Server started on port", re.IGNORECASE),
    re.compile(r"\[Server\] Running", re.IGNORECASE),
    re.compile(r"Loading complete", re.IGNORECASE),
]

MOD_COUNT_PATTERN = re.compile(r"Loading (\d+) mods?", re.IGNORECASE)


def parse_log(log_text: str) -> dict:
    issues = []
    seen = set()
    lines = log_text.split("\n")
    server_started = False
    mod_count = None

    for i, line in enumerate(lines):
        for indicator in SUCCESS_INDICATORS:
            if indicator.search(line):
                server_started = True

        m = MOD_COUNT_PATTERN.search(line)
        if m:
            mod_count = int(m.group(1))

        for pat in PATTERNS:
            m = pat["pattern"].search(line)
            if m:
                data = pat["extract"](m)
                dedup_key = (pat["name"], data.get("mod"), data["message"][:80])
                if dedup_key not in seen:
                    seen.add(dedup_key)
                    issues.append(LogIssue(
                        severity=pat["severity"].value,
                        category=data.get("category", pat["category"]),
                        mod=data.get("mod"),
                        message=data["message"],
                        suggestions=data.get("suggestions", []),
                        line_number=i + 1,
                    ))

    fatal = [i for i in issues if i.severity == "fatal"]
    errors = [i for i in issues if i.severity == "error"]
    warnings = [i for i in issues if i.severity == "warning"]

    return {
        "success": server_started and not fatal and not errors,
        "server_started": server_started,
        "mod_count": mod_count,
        "issues": [asdict(i) for i in issues],
        "summary": {
            "fatal": len(fatal),
            "errors": len(errors),
            "warnings": len(warnings),
            "total": len(issues),
        },
    }


def main():
    if len(sys.argv) > 1 and sys.argv[1] != "-":
        with open(sys.argv[1]) as f:
            log_text = f.read()
    else:
        log_text = sys.stdin.read()

    result = parse_log(log_text)

    if result["success"]:
        print("Server started successfully!")
        if result["mod_count"]:
            print(f"  {result['mod_count']} mods loaded")
        if result["summary"]["warnings"]:
            print(f"  {result['summary']['warnings']} warnings (non-fatal)")
    else:
        print("Issues detected:\n")
        for issue in result["issues"]:
            icon = {"fatal": "FATAL", "error": "ERROR", "warning": "WARN"}.get(issue["severity"], "INFO")
            print(f"  [{icon}] {issue['message']}")
            if issue["mod"]:
                print(f"         Mod: {issue['mod']}")
            for s in issue["suggestions"]:
                print(f"         -> {s}")
            print()

    output_path = sys.argv[2] if len(sys.argv) > 2 else None
    if output_path:
        with open(output_path, "w") as f:
            json.dump(result, f, indent=2)
        print(f"\nStructured output written to {output_path}")
    elif "--json" in sys.argv:
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Regression testing for Minecraft modpacks.

Captures a baseline snapshot from a running server (mod list, load status,
warnings) and compares against future runs. Optionally runs RCON smoke
commands to verify mod functionality.

Modes:
  baseline  - Boot server, capture baseline.json
  check     - Boot server, compare against baseline
  rcon-only - Run RCON checks against an already-running server

Requires: test-plan.toml alongside the packwiz pack (optional but recommended).
"""

import json
import os
import re
import socket
import struct
import sys
import subprocess
import time
import hashlib
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# RCON client
# ---------------------------------------------------------------------------

class RCONClient:
    """Minimal Minecraft RCON client."""

    SERVERDATA_AUTH = 3
    SERVERDATA_AUTH_RESPONSE = 2
    SERVERDATA_EXECCOMMAND = 2
    SERVERDATA_RESPONSE_VALUE = 0

    def __init__(self, host: str = "localhost", port: int = 25575, password: str = ""):
        self.host = host
        self.port = port
        self.password = password
        self.sock = None
        self.request_id = 0

    def connect(self, timeout: float = 10.0):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect((self.host, self.port))
        self._authenticate()

    def close(self):
        if self.sock:
            self.sock.close()
            self.sock = None

    def command(self, cmd: str) -> str:
        self.request_id += 1
        self._send(self.SERVERDATA_EXECCOMMAND, cmd)
        return self._receive()

    def _authenticate(self):
        self.request_id += 1
        self._send(self.SERVERDATA_AUTH, self.password)
        resp = self._receive_raw()
        if resp["id"] == -1:
            raise ConnectionRefusedError("RCON authentication failed")

    def _send(self, packet_type: int, payload: str):
        data = payload.encode("utf-8") + b"\x00\x00"
        packet = struct.pack("<iii", self.request_id, packet_type, 0)
        packet = struct.pack("<i", len(data) + 8) + struct.pack("<ii", self.request_id, packet_type) + data
        self.sock.sendall(packet)

    def _receive_raw(self) -> dict:
        length_data = self._recv_exact(4)
        length = struct.unpack("<i", length_data)[0]
        data = self._recv_exact(length)
        req_id = struct.unpack("<i", data[:4])[0]
        ptype = struct.unpack("<i", data[4:8])[0]
        payload = data[8:-2].decode("utf-8", errors="replace")
        return {"id": req_id, "type": ptype, "payload": payload}

    def _receive(self) -> str:
        resp = self._receive_raw()
        return resp["payload"]

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("Connection closed")
            buf += chunk
        return buf


# ---------------------------------------------------------------------------
# Log analysis (reuses parse_mc_logs patterns but returns structured data)
# ---------------------------------------------------------------------------

def extract_mod_list(log_text: str) -> list:
    """Extract loaded mod IDs from log."""
    mods = []
    # Fabric pattern
    for m in re.finditer(r"Loading\s+\d+\s+mods?:.*?(?=\n\S|\Z)", log_text, re.DOTALL):
        block = m.group()
        for mod_match in re.finditer(r"[\t ]+- (\S+)\s+([\d.]+\S*)", block):
            mods.append({"id": mod_match.group(1), "version": mod_match.group(2)})
    # Forge/NeoForge pattern
    for m in re.finditer(r"\[FML\].*?(\w+)\s*\(([^)]+)\)\s*-\s*([\d.]+\S*)", log_text):
        mods.append({"id": m.group(1), "version": m.group(3)})
    # Generic "Loaded X mods" with individual lines
    for m in re.finditer(r"(\w[\w-]+)\s+([\d]+\.[\d]+\S*)", log_text):
        candidate = {"id": m.group(1), "version": m.group(2)}
        if candidate not in mods and len(candidate["id"]) > 2:
            mods.append(candidate)
    return mods


def extract_warnings(log_text: str) -> list:
    warnings = []
    for line in log_text.split("\n"):
        if re.search(r"\bWARN\b", line, re.IGNORECASE):
            warnings.append(line.strip()[:300])
    return warnings


def extract_errors(log_text: str) -> list:
    errors = []
    for line in log_text.split("\n"):
        if re.search(r"\bERROR\b|\bFATAL\b", line, re.IGNORECASE):
            errors.append(line.strip()[:300])
    return errors


def server_started_ok(log_text: str) -> bool:
    patterns = [
        r"Done \(\d+\.\d+s\)! For help, type",
        r"Server started on port",
    ]
    return any(re.search(p, log_text, re.IGNORECASE) for p in patterns)


# ---------------------------------------------------------------------------
# Test plan
# ---------------------------------------------------------------------------

@dataclass
class RCONCheck:
    command: str
    expect_contains: Optional[str] = None
    expect_not_contains: Optional[str] = None
    description: str = ""


@dataclass
class TestPlan:
    expected_mod_count: Optional[int] = None
    critical_mods: list = field(default_factory=list)
    rcon_checks: list = field(default_factory=list)
    rcon_port: int = 25575
    rcon_password: str = ""
    boot_timeout_seconds: int = 300
    ingame_results_file: str = "test-results.json"


def load_test_plan(path: str) -> TestPlan:
    """Load test-plan.toml or test-plan.json."""
    p = Path(path)
    plan = TestPlan()

    if not p.exists():
        return plan

    if p.suffix == ".json":
        with open(p) as f:
            data = json.load(f)
    elif p.suffix == ".toml":
        if sys.version_info >= (3, 11):
            import tomllib
            with open(p, "rb") as f:
                data = tomllib.load(f)
        else:
            return plan  # Skip TOML on older Python
    else:
        return plan

    plan.expected_mod_count = data.get("expected_mod_count")
    plan.critical_mods = data.get("critical_mods", [])
    plan.rcon_port = data.get("rcon", {}).get("port", 25575)
    plan.rcon_password = data.get("rcon", {}).get("password", "")

    for check in data.get("rcon_checks", []):
        plan.rcon_checks.append(RCONCheck(
            command=check["command"],
            expect_contains=check.get("expect_contains"),
            expect_not_contains=check.get("expect_not_contains"),
            description=check.get("description", check["command"]),
        ))

    plan.ingame_results_file = data.get("ingame_results_file", "test-results.json")

    return plan


def load_ingame_results(server_dir: str, filename: str = "test-results.json") -> list:
    """Load test-results.json written by the in-game test harness mod."""
    path = Path(server_dir) / filename
    if not path.exists():
        return []
    try:
        with open(path) as f:
            data = json.load(f)
        return data.get("results", [])
    except Exception as e:
        return [{"name": "_load_error", "passed": False, "message": f"Failed to read {path}: {e}"}]


# ---------------------------------------------------------------------------
# Snapshot
# ---------------------------------------------------------------------------

@dataclass
class Snapshot:
    timestamp: str
    server_started: bool
    mod_list: list
    mod_count: int
    warnings: list
    errors: list
    warning_count: int
    error_count: int
    rcon_results: list = field(default_factory=list)
    ingame_results: list = field(default_factory=list)
    log_hash: str = ""


def capture_snapshot(log_text: str, rcon_results: list = None, ingame_results: list = None) -> Snapshot:
    mods = extract_mod_list(log_text)
    warnings = extract_warnings(log_text)
    errors = extract_errors(log_text)

    return Snapshot(
        timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        server_started=server_started_ok(log_text),
        mod_list=mods,
        mod_count=len(mods),
        warnings=warnings,
        errors=errors,
        warning_count=len(warnings),
        error_count=len(errors),
        rcon_results=rcon_results or [],
        ingame_results=ingame_results or [],
        log_hash=hashlib.sha256(log_text.encode()).hexdigest()[:16],
    )


# ---------------------------------------------------------------------------
# RCON smoke tests
# ---------------------------------------------------------------------------

def run_rcon_checks(plan: TestPlan) -> list:
    if not plan.rcon_checks:
        return []

    results = []
    try:
        client = RCONClient("localhost", plan.rcon_port, plan.rcon_password)
        client.connect()

        for check in plan.rcon_checks:
            try:
                response = client.command(check.command)
                passed = True
                reason = ""

                if check.expect_contains and check.expect_contains not in response:
                    passed = False
                    reason = f"Expected '{check.expect_contains}' not found in response"
                if check.expect_not_contains and check.expect_not_contains in response:
                    passed = False
                    reason = f"Unexpected '{check.expect_not_contains}' found in response"

                results.append({
                    "command": check.command,
                    "description": check.description,
                    "response": response[:500],
                    "passed": passed,
                    "reason": reason,
                })
            except Exception as e:
                results.append({
                    "command": check.command,
                    "description": check.description,
                    "response": "",
                    "passed": False,
                    "reason": str(e),
                })

        client.close()
    except Exception as e:
        results.append({
            "command": "_connect",
            "description": "RCON connection",
            "response": "",
            "passed": False,
            "reason": f"Could not connect to RCON: {e}",
        })

    return results


# ---------------------------------------------------------------------------
# Diff / regression check
# ---------------------------------------------------------------------------

def diff_snapshots(baseline: Snapshot, current: Snapshot, plan: TestPlan) -> dict:
    """Compare current snapshot against baseline, return regression report."""
    issues = []
    info = []

    # Server boot
    if baseline.server_started and not current.server_started:
        issues.append({
            "severity": "fatal",
            "message": "Server failed to start (was OK in baseline)",
        })

    # Mod count
    if baseline.mod_count != current.mod_count:
        diff = current.mod_count - baseline.mod_count
        msg = f"Mod count changed: {baseline.mod_count} -> {current.mod_count} ({'+' if diff > 0 else ''}{diff})"
        if diff < 0:
            issues.append({"severity": "warning", "message": msg})
        else:
            info.append(msg)

    # Missing mods
    baseline_ids = {m["id"] for m in baseline.mod_list}
    current_ids = {m["id"] for m in current.mod_list}
    missing = baseline_ids - current_ids
    added = current_ids - baseline_ids

    if missing:
        issues.append({
            "severity": "error",
            "message": f"Mods missing from baseline: {', '.join(sorted(missing))}",
        })
    if added:
        info.append(f"New mods added: {', '.join(sorted(added))}")

    # Version changes
    baseline_versions = {m["id"]: m["version"] for m in baseline.mod_list}
    current_versions = {m["id"]: m["version"] for m in current.mod_list}
    changed = []
    for mod_id in baseline_ids & current_ids:
        if baseline_versions.get(mod_id) != current_versions.get(mod_id):
            changed.append({
                "mod": mod_id,
                "from": baseline_versions[mod_id],
                "to": current_versions[mod_id],
            })
    if changed:
        info.append(f"{len(changed)} mod(s) changed version")

    # Critical mods
    for cmod in plan.critical_mods:
        if cmod not in current_ids:
            issues.append({
                "severity": "error",
                "message": f"Critical mod '{cmod}' not loaded",
            })

    # New errors
    baseline_errors = set(baseline.errors)
    new_errors = [e for e in current.errors if e not in baseline_errors]
    if new_errors:
        issues.append({
            "severity": "error",
            "message": f"{len(new_errors)} new error(s) in logs",
            "details": new_errors[:10],
        })

    # New warnings
    baseline_warnings = set(baseline.warnings)
    new_warnings = [w for w in current.warnings if w not in baseline_warnings]
    if new_warnings:
        info.append(f"{len(new_warnings)} new warning(s) in logs")

    # RCON check results
    rcon_failures = [r for r in current.rcon_results if not r["passed"]]
    if rcon_failures:
        for f in rcon_failures:
            issues.append({
                "severity": "error",
                "message": f"RCON check failed: {f['description']} - {f['reason']}",
            })

    # In-game test harness results
    ingame_failures = [r for r in current.ingame_results if not r.get("passed", True)]
    if ingame_failures:
        for f in ingame_failures:
            issues.append({
                "severity": "error",
                "message": f"In-game test failed: {f['name']} - {f.get('message', 'no details')}",
            })
    if current.ingame_results:
        ingame_passed = sum(1 for r in current.ingame_results if r.get("passed", False))
        info.append(f"In-game tests: {ingame_passed}/{len(current.ingame_results)} passed")

    # Compare in-game results against baseline (detect regressions)
    if baseline.ingame_results and current.ingame_results:
        baseline_passed_tests = {r["name"] for r in baseline.ingame_results if r.get("passed")}
        for r in current.ingame_results:
            if not r.get("passed") and r["name"] in baseline_passed_tests:
                issues.append({
                    "severity": "error",
                    "message": f"In-game test regression: {r['name']} was passing, now fails",
                })

    # Expected mod count from plan
    if plan.expected_mod_count and current.mod_count != plan.expected_mod_count:
        issues.append({
            "severity": "warning",
            "message": f"Expected {plan.expected_mod_count} mods, got {current.mod_count}",
        })

    passed = not any(i["severity"] in ("fatal", "error") for i in issues)

    return {
        "passed": passed,
        "issues": issues,
        "info": info,
        "version_changes": changed,
        "missing_mods": sorted(missing),
        "added_mods": sorted(added),
        "rcon_results": current.rcon_results,
        "ingame_results": current.ingame_results,
        "baseline_hash": baseline.log_hash,
        "current_hash": current.log_hash,
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) < 3:
        print("Usage:")
        print("  regression_test.py baseline <log_file> [output.json] [--plan test-plan.toml] [--server-dir path]")
        print("  regression_test.py check <log_file> <baseline.json> [output.json] [--plan test-plan.toml] [--server-dir path]")
        print("  regression_test.py rcon <baseline.json> [output.json] [--plan test-plan.toml]")
        print()
        print("--server-dir: path to server directory containing test-results.json")
        print("              from the in-game test harness mod")
        print()
        print("test-plan.toml format:")
        print("""
expected_mod_count = 42

critical_mods = ["sodium", "fabric-api", "create"]

ingame_results_file = "test-results.json"

[rcon]
port = 25575
password = "test"

[[rcon_checks]]
command = "/list"
expect_contains = "players online"
description = "Server responds to /list"

[[rcon_checks]]
command = "/forge mods"
description = "Forge mod list loads"
""")
        sys.exit(1)

    mode = sys.argv[1]
    plan_path = "test-plan.toml"
    server_dir = None

    # Parse flags
    args = sys.argv[2:]
    filtered_args = []
    i = 0
    while i < len(args):
        if args[i] == "--plan" and i + 1 < len(args):
            plan_path = args[i + 1]
            i += 2
        elif args[i] == "--server-dir" and i + 1 < len(args):
            server_dir = args[i + 1]
            i += 2
        else:
            filtered_args.append(args[i])
            i += 1

    plan = load_test_plan(plan_path)

    if mode == "baseline":
        log_file = filtered_args[0]
        output_path = filtered_args[1] if len(filtered_args) > 1 else "baseline.json"

        with open(log_file) as f:
            log_text = f.read()

        rcon_results = run_rcon_checks(plan) if plan.rcon_checks else []
        ingame_results = load_ingame_results(server_dir, plan.ingame_results_file) if server_dir else []
        snapshot = capture_snapshot(log_text, rcon_results, ingame_results)

        with open(output_path, "w") as f:
            json.dump(asdict(snapshot), f, indent=2)

        print(f"Baseline captured: {snapshot.mod_count} mods, "
              f"{snapshot.error_count} errors, {snapshot.warning_count} warnings")
        if rcon_results:
            passed = sum(1 for r in rcon_results if r["passed"])
            print(f"RCON checks: {passed}/{len(rcon_results)} passed")
        if ingame_results:
            passed = sum(1 for r in ingame_results if r.get("passed", False))
            print(f"In-game tests: {passed}/{len(ingame_results)} passed")
        print(f"Saved to {output_path}")

    elif mode == "check":
        log_file = filtered_args[0]
        baseline_path = filtered_args[1]
        output_path = filtered_args[2] if len(filtered_args) > 2 else "regression_report.json"

        with open(log_file) as f:
            log_text = f.read()
        with open(baseline_path) as f:
            baseline_data = json.load(f)

        baseline = Snapshot(**baseline_data)
        rcon_results = run_rcon_checks(plan) if plan.rcon_checks else []
        ingame_results = load_ingame_results(server_dir, plan.ingame_results_file) if server_dir else []
        current = capture_snapshot(log_text, rcon_results, ingame_results)
        report = diff_snapshots(baseline, current, plan)

        with open(output_path, "w") as f:
            json.dump(report, f, indent=2)

        if report["passed"]:
            print("PASSED - No regressions detected")
        else:
            print("FAILED - Regressions found:")
            for issue in report["issues"]:
                print(f"  [{issue['severity'].upper()}] {issue['message']}")

        if report["info"]:
            print("\nInfo:")
            for note in report["info"]:
                print(f"  {note}")

        print(f"\nReport saved to {output_path}")

    elif mode == "rcon":
        baseline_path = filtered_args[0]
        output_path = filtered_args[1] if len(filtered_args) > 1 else "rcon_report.json"

        with open(baseline_path) as f:
            baseline_data = json.load(f)

        results = run_rcon_checks(plan)

        passed = sum(1 for r in results if r["passed"])
        total = len(results)

        report = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "passed": passed == total,
            "results": results,
            "summary": f"{passed}/{total} checks passed",
        }

        with open(output_path, "w") as f:
            json.dump(report, f, indent=2)

        for r in results:
            icon = "PASS" if r["passed"] else "FAIL"
            print(f"  [{icon}] {r['description']}")
            if not r["passed"]:
                print(f"         {r['reason']}")

        print(f"\n{passed}/{total} passed. Report saved to {output_path}")

    else:
        print(f"Unknown mode: {mode}")
        sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Phase 5.3 — Auto-regenerate STATUS.md numeric scoreboard from repo artifacts.

Reads source-of-truth artifacts (test XMLs, p21/report.json, file globs) and
replaces values inside `<!--AUTO:name-->` markers in STATUS.md. Idempotent:
zero diff when nothing changed; minimal one-line diff when a single source
artifact changes.

stdlib only. No pip deps. No jinja2.

Usage:
    python scripts/auto-status-closeout.py             # update STATUS.md in place
    python scripts/auto-status-closeout.py --dry-run   # print would-be diff, no write
    python scripts/auto-status-closeout.py --check     # exit 1 if diff non-empty
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

os.umask(0o077)

REPO_ROOT = Path(__file__).resolve().parent.parent
STATUS_PATH = REPO_ROOT / "STATUS.md"

# Marker format: <!--AUTO:name-->VALUE<!--/AUTO-->
MARKER_RE = re.compile(r"<!--AUTO:([a-z0-9_]+)-->.*?<!--/AUTO-->", re.DOTALL)


def metric_probe_count() -> str:
    """Count Kotlin probe source files (excluding *Test*)."""
    probes_dir = REPO_ROOT / "agents" / "detection" / "src" / "probes"
    if not probes_dir.exists():
        return "?"
    n = sum(
        1
        for p in probes_dir.rglob("*.kt")
        if "Test" not in p.name
    )
    return str(n)


def metric_test_count() -> str:
    """Sum tests= across all gradle test-result XMLs."""
    results_dir = REPO_ROOT / "agents" / "detection" / "build" / "test-results"
    if not results_dir.exists():
        return "?"
    total = 0
    for xml in results_dir.rglob("TEST-*.xml"):
        try:
            root = ET.parse(xml).getroot()
            total += int(root.attrib.get("tests", "0"))
        except (ET.ParseError, ValueError):
            continue
    return f"{total:,}"


def metric_compose_count() -> str:
    compose_dir = REPO_ROOT / "agents" / "stability" / "stack" / "compose"
    if not compose_dir.exists():
        return "?"
    return str(len(list(compose_dir.glob("*.yml"))))


def metric_runbook_count() -> str:
    stack_dir = REPO_ROOT / "agents" / "stability" / "stack"
    if not stack_dir.exists():
        return "?"
    return str(len(list(stack_dir.glob("*RUNBOOK*.md"))))


def metric_ci_workflow_count() -> str:
    wf_dir = REPO_ROOT / ".github" / "workflows"
    if not wf_dir.exists():
        return "?"
    return str(len(list(wf_dir.glob("*.yml"))))


def metric_paperclip_routine_count() -> str:
    routine_dir = REPO_ROOT / "docs" / "super-action" / "clawpatch"
    if not routine_dir.exists():
        return "?"
    return str(len(list(routine_dir.glob("paperclip-routine-*.yml"))))


def metric_p21_verdict_pct() -> str:
    report = REPO_ROOT / "p21" / "report.json"
    if not report.exists():
        return "?"
    try:
        with report.open() as fh:
            data = json.load(fh)
        pct = data.get("summary", {}).get("matches_expected_pct")
        return f"{pct}%" if pct is not None else "?"
    except (json.JSONDecodeError, OSError):
        return "?"


def metric_p21_cells_total() -> str:
    report = REPO_ROOT / "p21" / "report.json"
    if not report.exists():
        return "?"
    try:
        with report.open() as fh:
            data = json.load(fh)
        return str(data.get("summary", {}).get("total_cells", "?"))
    except (json.JSONDecodeError, OSError):
        return "?"


def metric_cross_cutting_closed() -> str:
    """Count '✅ CLOSED' rows in Power-3-FINAL audit table (out of 8)."""
    audit = REPO_ROOT / "audit" / "Power-3-FINAL-2026-05-20.md"
    if not audit.exists():
        return "?"
    try:
        text = audit.read_text(encoding="utf-8")
    except OSError:
        return "?"
    return str(text.count("✅ CLOSED"))


def metric_e2e_loops_automated() -> str:
    """Wired CI + Paperclip routine loops."""
    ci = int(metric_ci_workflow_count() or "0")
    routines = int(metric_paperclip_routine_count() or "0")
    return str(ci + routines)


METRICS = {
    "probe_count": metric_probe_count,
    "test_count": metric_test_count,
    "compose_count": metric_compose_count,
    "runbook_count": metric_runbook_count,
    "ci_workflow_count": metric_ci_workflow_count,
    "paperclip_routine_count": metric_paperclip_routine_count,
    "p21_verdict_pct": metric_p21_verdict_pct,
    "p21_cells_total": metric_p21_cells_total,
    "cross_cutting_closed": metric_cross_cutting_closed,
    "e2e_loops_automated": metric_e2e_loops_automated,
}

# Metrics intentionally NOT marker-bound in STATUS.md:
# - test_count: gradle test-results dir is overwritten by partial-suite runs
#   (e.g., running a single test class wipes the prior 4,241 XMLs and leaves
#   only the new one). Until a stable build/test-summary.json is wired,
#   marker-binding test_count would surface drift that's a build-artifact
#   freshness signal, not a real regression. Function kept for one-off use
#   after a full `./gradlew :detection:test` run.


def substitute_markers(text: str) -> tuple[str, list[str]]:
    """Replace each <!--AUTO:name-->...<!--/AUTO--> with current metric value.

    Returns (new_text, list of changed metric names).
    """
    changes: list[str] = []

    def repl(match: re.Match[str]) -> str:
        name = match.group(1)
        if name not in METRICS:
            return match.group(0)  # unknown marker — leave untouched
        new_value = METRICS[name]()
        new_block = f"<!--AUTO:{name}-->{new_value}<!--/AUTO-->"
        if new_block != match.group(0):
            changes.append(name)
        return new_block

    new_text = MARKER_RE.sub(repl, text)
    return new_text, changes


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="Print diff without writing")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 if STATUS.md would change (CI gate mode)",
    )
    args = parser.parse_args(argv)

    if not STATUS_PATH.exists():
        print(f"ERROR: {STATUS_PATH} not found", file=sys.stderr)
        return 2

    original = STATUS_PATH.read_text(encoding="utf-8")
    updated, changes = substitute_markers(original)

    if updated == original:
        print("STATUS.md: up to date, no changes")
        return 0

    if args.dry_run or args.check:
        print(f"STATUS.md: {len(changes)} metric(s) would change: {', '.join(changes)}")
        if args.check:
            return 1
        return 0

    STATUS_PATH.write_text(updated, encoding="utf-8")
    print(f"STATUS.md: {len(changes)} metric(s) updated: {', '.join(changes)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

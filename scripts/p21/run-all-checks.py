#!/usr/bin/env python3
"""P21-C: Deterministic harness for cloud-phone integrity probes.

Runs 3 tests (T1 cold-boot, T2 warm-reboot, T3 prop-diff) across all
INSTALLED apps from p21/install-report.json. Records verdict/evidence per
cell into p21/report.json (99 cells: 21 testable + 78 not-tested).

stdlib only. subprocess uses list-form args (no shell=True).
"""

import argparse
import difflib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

os.umask(0o077)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
P21_DIR = REPO_ROOT / "p21"
SCREENSHOTS_DIR = P21_DIR / "screenshots"
UIA_DIR = P21_DIR / "uia"
PROPS_DIR = P21_DIR / "props"
REPORT_PATH = P21_DIR / "report.json"
INSTALL_REPORT_PATH = P21_DIR / "install-report.json"

ADB_SERIAL = os.environ.get("ADB_SERIAL", "172.17.0.2:5555")
HARNESS_VERSION = "p21-c-1.0"

# Per-probe expected verdict assignment (per task #66 description).
EXPECTED_VERDICT = {
    "rikka.safetynetchecker":              "FAIL-L0-HARDCEILING",   # SafetyNet API deprecated 2024-01
    "com.henrikherzig.playintegritychecker":"FAIL-L0-HARDCEILING",  # PlayIntegrity needs TEE+PlayServices
    "com.byxiaorun.detector":              "FAIL-L0-x86",           # Ruru emu-detect on x86
    "icu.nullptr.applistdetector":         "FAIL-L0-x86",           # ApplistDetector emu-detect class
    "io.github.vvb2060.keyattestation":    "FAIL-L0-HARDCEILING",   # no TEE on Redroid
    "tk.hack5.treblecheck":                "PASS",                  # benign device-info
    "com.mantle.verify":                   "PASS",                  # benign device-info
}

# Default expected verdict for not-tested apps (best-guess by source/notes).
DEFAULT_EXPECTED_NOT_TESTED = "FAIL-L0-x86"

PASS_KEYWORDS = [
    "device_integrity", "meets_device_integrity", "meets_strong_integrity",
    "not rooted", "not emulator", "basic integrity: true",
    "real device", "no root", "ctsprofilematch: true",
    "hardware-backed",
]
# FAIL keywords — observed in real Redroid runs (2026-05-21):
#   "abnormal environment"  → Ruru / ApplistDetector top-level verdict
#   "suspicious"            → Ruru / ApplistDetector category state
#   "bootloader is unlocked"→ KeyAttestation finding
#   "tampered with"         → KeyAttestation finding
#   "software attestation"  → KeyAttestation finding (no TEE → AOSP-software cert)
#   "does not support hardware-level" → KeyAttestation finding
# NOTE: bare "x86" appears in benign device-info strings on Treble Info
# (e.g., "system-x86_64-ab.img.xz" is info, not a fail). Use more specific
# phrases for x86 detection.
FAIL_KEYWORDS = [
    "rooted", "is rooted", "emulator detected", "virtual device",
    "violation", "basic integrity: false",
    "test-keys", "test keys", "redroid",
    "mock location", "mock provider", "ro.debuggable=1",
    "abnormal environment", "suspicious",
    "bootloader is unlocked", "tampered with", "software attestation",
    "does not support hardware-level",
    "service unavailable",
]
LAUNCHER_PKGS = {
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.android.launcher",
}
# System overlays that intercept focus but do NOT mean the app crashed.
SYSTEM_OVERLAY_PKGS = {
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.systemui",
}

T2_REBOOT_WAIT_SEC = 90
T2_SETTLE_AFTER_BOOT_SEC = 20
LAUNCH_SETTLE_SEC = 8


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def adb(*args: str, timeout: int = 30, check: bool = False) -> subprocess.CompletedProcess:
    """Run `adb -s <serial> <args>`. List-form, no shell=True."""
    cmd = ["adb", "-s", ADB_SERIAL, *args]
    return subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout, check=check
    )


def adb_shell(cmdline: str, timeout: int = 30) -> subprocess.CompletedProcess:
    """Run `adb shell <cmdline>` — cmdline is passed as a single argv element.

    Safe for our use because we only pass package names that come from
    install-report.json (a curated allowlist), never user input.
    """
    return adb("shell", cmdline, timeout=timeout)


def adb_exec_out(cmdline: str, out_path: Path, timeout: int = 30) -> int:
    """Run `adb exec-out <cmdline>` and write stdout (binary) to out_path."""
    cmd = ["adb", "-s", ADB_SERIAL, "exec-out", cmdline]
    with open(out_path, "wb") as f:
        proc = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=timeout)
    return proc.returncode


def resolve_launcher(pkg: str) -> str | None:
    """Return COMPONENT_NAME like `pkg/activity`, or None if no launcher."""
    res = adb_shell(f"cmd package resolve-activity --brief {shlex.quote(pkg)}")
    if res.returncode != 0:
        return None
    lines = [ln.strip() for ln in res.stdout.strip().splitlines() if ln.strip()]
    if not lines:
        return None
    # Last line is the component-name; first line is the package label.
    candidate = lines[-1]
    if "/" not in candidate or " " in candidate:
        return None
    return candidate


def wait_for_device(max_sec: int = T2_REBOOT_WAIT_SEC) -> bool:
    """Wait until adb sees device. Returns True on success."""
    start = time.monotonic()
    while time.monotonic() - start < max_sec:
        res = subprocess.run(
            ["adb", "-s", ADB_SERIAL, "get-state"],
            capture_output=True, text=True, timeout=5
        )
        if res.returncode == 0 and res.stdout.strip() == "device":
            return True
        time.sleep(2)
    return False


def get_focused_pkg() -> str | None:
    """Parse mCurrentFocus from `dumpsys window` to return focused package."""
    res = adb_shell("dumpsys window | grep mCurrentFocus | head -1", timeout=15)
    if res.returncode != 0 or not res.stdout.strip():
        return None
    # Typical: mCurrentFocus=Window{abc u0 com.byxiaorun.detector/.MainActivity}
    m = re.search(r"\b([\w\.]+)/[\w\.\$]+", res.stdout)
    if not m:
        return None
    return m.group(1)


def get_focused_activity() -> str | None:
    res = adb_shell("dumpsys window | grep mCurrentFocus | head -1", timeout=15)
    if res.returncode != 0 or not res.stdout.strip():
        return None
    m = re.search(r"\b([\w\.]+/[\w\.\$]+)", res.stdout)
    return m.group(1) if m else None


def collect_text_nodes(xml_path: Path) -> list[tuple[str, str]]:
    """Walk uiautomator XML and return [(attr, value)] for text & content-desc."""
    out: list[tuple[str, str]] = []
    try:
        tree = ET.parse(xml_path)
    except ET.ParseError:
        return out
    for node in tree.iter():
        for attr in ("text", "content-desc"):
            v = node.get(attr)
            if v:
                out.append((attr, v))
    return out


def extract_verdict(text_nodes: list[tuple[str, str]], focused_pkg: str | None,
                    target_pkg: str) -> tuple[str, list[dict]]:
    """Return (verdict, evidence_list)."""
    evidence: list[dict] = []

    # CRASH check first: focus not on target = no observable response
    if focused_pkg and focused_pkg in LAUNCHER_PKGS:
        evidence.append({
            "key": "focus.is_launcher",
            "value": focused_pkg,
            "source": "dumpsys.window",
        })
        return "CRASH", evidence

    if focused_pkg is None:
        evidence.append({
            "key": "focus.unavailable",
            "value": "no mCurrentFocus parsed",
            "source": "dumpsys.window",
        })
        return "UNKNOWN", evidence

    if focused_pkg in SYSTEM_OVERLAY_PKGS:
        # System overlay (permission dialog etc.) — app DID launch successfully
        # but we cannot observe its native UI past the overlay. UNKNOWN, not CRASH.
        evidence.append({
            "key": "focus.system_overlay",
            "value": focused_pkg,
            "source": "dumpsys.window",
        })
        return "UNKNOWN", evidence

    if focused_pkg != target_pkg:
        evidence.append({
            "key": "focus.wrong_pkg",
            "value": focused_pkg,
            "source": "dumpsys.window",
        })
        return "CRASH", evidence

    # Focus is on the target app; record that as ground-truth evidence
    evidence.append({
        "key": "focus.on_target",
        "value": focused_pkg,
        "source": "dumpsys.window",
    })

    # Keyword search (case-insensitive) — must match a whole substring.
    pass_hits: list[tuple[str, str]] = []
    fail_hits: list[tuple[str, str]] = []
    for attr, val in text_nodes:
        low = val.lower()
        for kw in PASS_KEYWORDS:
            if kw in low:
                pass_hits.append((kw, val))
        for kw in FAIL_KEYWORDS:
            if kw in low:
                fail_hits.append((kw, val))

    for kw, val in pass_hits:
        evidence.append({"key": f"xml.contains.{kw}", "value": val, "source": "uia"})
    for kw, val in fail_hits:
        evidence.append({"key": f"xml.contains.{kw}", "value": val, "source": "uia"})

    if fail_hits and not pass_hits:
        return "FAIL", evidence
    if pass_hits and not fail_hits:
        return "PASS", evidence
    return "UNKNOWN", evidence


def verdict_matches_expected(verdict: str, expected: str | None) -> bool | None:
    if expected is None:
        return None
    if verdict in ("UNKNOWN", "NOT-TESTED"):
        return False if verdict == "UNKNOWN" else None
    if expected == "PASS":
        return verdict == "PASS"
    if expected.startswith("FAIL"):
        return verdict in ("FAIL", "CRASH")
    return None


def atomic_write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", delete=False,
        dir=str(path.parent), prefix=".report-", suffix=".json.tmp",
    )
    try:
        json.dump(data, tmp, indent=2, ensure_ascii=False)
        tmp.flush()
        os.fsync(tmp.fileno())
        tmp.close()
        os.replace(tmp.name, path)
    except Exception:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
        raise


def load_install_report() -> tuple[list[dict], list[dict]]:
    """Return (installed_apps, not_installed_apps) excluding _meta entries."""
    data = json.loads(INSTALL_REPORT_PATH.read_text())
    apps = [e for e in data if "pkg" in e]
    installed = [a for a in apps if a.get("status") == "installed"]
    not_installed = [a for a in apps if a.get("status") != "installed"]
    return installed, not_installed


def not_tested_reason_for(status: str) -> str:
    if status == "aurora-required":
        return "aurora-required-no-play-login"
    if status == "skip":
        return "skip-manual"
    if status == "install-failed":
        return "install-failed-url-html-not-apk"
    return f"status-{status}"


def run_test_for_app(app: dict, t: int, component: str,
                     skip_t2: bool = False) -> dict:
    """Execute one T-cell. Returns the cell dict."""
    pkg = app["pkg"]
    name = app["name"]
    cell: dict = {
        "app": name,
        "pkg": pkg,
        "t": t,
        "verdict": "UNKNOWN",
        "evidence": [],
        "screenshot": None,
        "uia": None,
        "expected": EXPECTED_VERDICT.get(pkg),
        "matches_expected": None,
        "focused_activity": None,
        "not_tested_reason": None,
    }

    # Skip T2 if requested
    if t == 2 and skip_t2:
        cell["verdict"] = "NOT-TESTED"
        cell["not_tested_reason"] = "skipped-t2-flag"
        cell["matches_expected"] = None
        return cell

    # Force-stop the target
    adb_shell(f"am force-stop {shlex.quote(pkg)}", timeout=15)

    # T2 reboot + wait. On this redroid `adb reboot` blocks indefinitely
    # (container-style reboot keeps adb session alive). Treat TimeoutExpired
    # as expected and proceed to wait_for_device.
    if t == 2:
        try:
            adb("reboot", timeout=10)
        except subprocess.TimeoutExpired:
            pass
        time.sleep(5)
        if not wait_for_device(max_sec=T2_REBOOT_WAIT_SEC):
            cell["verdict"] = "NOT-TESTED"
            cell["not_tested_reason"] = "adb-lost-after-reboot"
            return cell
        time.sleep(T2_SETTLE_AFTER_BOOT_SEC)

    # T3 pre-launch prop dump
    pre_props_text: str | None = None
    if t == 3:
        res = adb_shell("getprop", timeout=30)
        if res.returncode == 0:
            pre_props_text = res.stdout

    # Launch
    launch_res = adb_shell(f"am start -n {shlex.quote(component)}", timeout=20)
    if launch_res.returncode != 0:
        cell["verdict"] = "CRASH"
        cell["evidence"].append({
            "key": "am.start.nonzero_rc",
            "value": f"rc={launch_res.returncode}; stderr={launch_res.stderr.strip()[:200]}",
            "source": "am.start",
        })
        cell["matches_expected"] = verdict_matches_expected(cell["verdict"], cell["expected"])
        return cell

    time.sleep(LAUNCH_SETTLE_SEC)

    # Focus check
    focused_pkg = get_focused_pkg()
    focused_act = get_focused_activity()
    cell["focused_activity"] = focused_act

    # UI dump (use /data/local/tmp/, not /sdcard/)
    remote_xml = f"/data/local/tmp/window-{pkg}-{t}.xml"
    dump_res = adb_shell(f"uiautomator dump {shlex.quote(remote_xml)}", timeout=30)
    local_xml = UIA_DIR / f"{pkg}-{t}.xml"
    if dump_res.returncode == 0:
        cat_res = adb_shell(f"cat {shlex.quote(remote_xml)}", timeout=15)
        if cat_res.returncode == 0 and cat_res.stdout.strip():
            local_xml.write_text(cat_res.stdout)
            cell["uia"] = f"p21/uia/{pkg}-{t}.xml"
        adb_shell(f"rm -f {shlex.quote(remote_xml)}", timeout=10)

    # Screenshot
    local_png = SCREENSHOTS_DIR / f"{pkg}-{t}.png"
    rc = adb_exec_out("screencap -p", local_png, timeout=30)
    if rc == 0 and local_png.exists() and local_png.stat().st_size > 0:
        cell["screenshot"] = f"p21/screenshots/{pkg}-{t}.png"

    # T3 post-launch prop dump + diff
    if t == 3 and pre_props_text is not None:
        res = adb_shell("getprop", timeout=30)
        post_props_text = res.stdout if res.returncode == 0 else ""
        diff_iter = difflib.unified_diff(
            pre_props_text.splitlines(keepends=True),
            post_props_text.splitlines(keepends=True),
            fromfile=f"{pkg}-T3-pre",
            tofile=f"{pkg}-T3-post",
            n=2,
        )
        diff_path = PROPS_DIR / f"{pkg}-T3-diff.txt"
        diff_path.write_text("".join(diff_iter))

    # Verdict extraction
    text_nodes: list[tuple[str, str]] = []
    if local_xml.exists():
        text_nodes = collect_text_nodes(local_xml)
    verdict, evidence = extract_verdict(text_nodes, focused_pkg, pkg)
    cell["verdict"] = verdict
    # Prepend our extracted evidence to whatever launch evidence might exist
    cell["evidence"] = evidence + cell["evidence"]
    cell["matches_expected"] = verdict_matches_expected(verdict, cell["expected"])

    return cell


def build_cells(args: argparse.Namespace) -> list[dict]:
    installed, not_installed = load_install_report()
    cells: list[dict] = []

    # Pre-resolve launcher components for installed apps
    components: dict[str, str] = {}
    for app in installed:
        pkg = app["pkg"]
        comp = resolve_launcher(pkg)
        if comp:
            components[pkg] = comp
        else:
            print(f"[WARN] no launcher resolved for {pkg}", file=sys.stderr)

    if args.dry_run:
        print(f"[DRY] Would test {len(installed)} installed apps × 3 = {len(installed)*3} testable cells")
        print(f"[DRY] Would emit {len(not_installed)} not-installed × 3 = {len(not_installed)*3} not-tested cells")
        print(f"[DRY] Total: {len(installed)*3 + len(not_installed)*3} cells")
        print("[DRY] Resolved launchers:")
        for pkg, comp in components.items():
            print(f"  {pkg} -> {comp}")
        return []

    # Testable cells: 3 T per app
    for app in installed:
        pkg = app["pkg"]
        comp = components.get(pkg)
        if not comp:
            for t in (1, 2, 3):
                cell = {
                    "app": app["name"],
                    "pkg": pkg,
                    "t": t,
                    "verdict": "NOT-TESTED",
                    "evidence": [],
                    "screenshot": None,
                    "uia": None,
                    "expected": EXPECTED_VERDICT.get(pkg),
                    "matches_expected": None,
                    "focused_activity": None,
                    "not_tested_reason": "no-launcher-activity",
                }
                cells.append(cell)
            continue

        for t in (1, 2, 3):
            print(f"[RUN] {pkg} T{t} (component={comp})", file=sys.stderr)
            try:
                cell = run_test_for_app(app, t, comp, skip_t2=args.skip_t2)
            except subprocess.TimeoutExpired as e:
                cell = {
                    "app": app["name"],
                    "pkg": pkg,
                    "t": t,
                    "verdict": "NOT-TESTED",
                    "evidence": [{"key": "subprocess.timeout", "value": str(e)[:200], "source": "harness"}],
                    "screenshot": None,
                    "uia": None,
                    "expected": EXPECTED_VERDICT.get(pkg),
                    "matches_expected": None,
                    "focused_activity": None,
                    "not_tested_reason": "subprocess-timeout",
                }
            cells.append(cell)
            print(f"  -> verdict={cell['verdict']} matches_expected={cell['matches_expected']}",
                  file=sys.stderr)

    # Not-tested cells: 3 T per not-installed app
    for app in not_installed:
        pkg = app["pkg"]
        status = app.get("status", "unknown")
        reason = not_tested_reason_for(status)
        for t in (1, 2, 3):
            cells.append({
                "app": app["name"],
                "pkg": pkg,
                "t": t,
                "verdict": "NOT-TESTED",
                "evidence": [],
                "screenshot": None,
                "uia": None,
                "expected": EXPECTED_VERDICT.get(pkg, DEFAULT_EXPECTED_NOT_TESTED),
                "matches_expected": None,
                "focused_activity": None,
                "not_tested_reason": reason,
            })

    return cells


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="List what would be tested; don't touch device")
    ap.add_argument("--skip-t2", action="store_true",
                    help="Skip T2 reboot tests (faster first-pass)")
    args = ap.parse_args()

    SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
    UIA_DIR.mkdir(parents=True, exist_ok=True)
    PROPS_DIR.mkdir(parents=True, exist_ok=True)

    if not args.dry_run:
        # Sanity-check device
        st = subprocess.run(["adb", "-s", ADB_SERIAL, "get-state"],
                            capture_output=True, text=True, timeout=10)
        if st.returncode != 0 or st.stdout.strip() != "device":
            print(f"[FATAL] adb device {ADB_SERIAL} not ready: {st.stdout.strip()}",
                  file=sys.stderr)
            return 2

    cells = build_cells(args)

    if args.dry_run:
        return 0

    # Aggregate counts
    verdict_counts: dict[str, int] = {}
    for c in cells:
        verdict_counts[c["verdict"]] = verdict_counts.get(c["verdict"], 0) + 1
    matched = sum(1 for c in cells if c["matches_expected"] is True)
    testable = sum(1 for c in cells if c["verdict"] != "NOT-TESTED")

    report = {
        "generated_at": now_iso(),
        "redroid_target": ADB_SERIAL,
        "harness_version": HARNESS_VERSION,
        "summary": {
            "total_cells": len(cells),
            "testable_cells": testable,
            "not_tested_cells": len(cells) - testable,
            "verdict_counts": verdict_counts,
            "matches_expected_count": matched,
            "matches_expected_pct": round(100.0 * matched / max(testable, 1), 1),
        },
        "notes": [
            "SafetyNet API (used by YASNAC) was deprecated by Google 2024-01;"
            " 'service unavailable' is API-EOL noise, not a clean emulator-detected verdict.",
            "Play Integrity (used by SPIC) requires TEE + Play Services — absent on Redroid;"
            " 'service unavailable' here is also API-class noise.",
            "CRASH verdict means focus was launcher (not target app) after launch — separate from FAIL.",
            "UNKNOWN verdict means neither PASS nor FAIL keywords matched while focus was on the target.",
        ],
        "cells": cells,
    }

    atomic_write_json(REPORT_PATH, report)
    print(f"[OK] wrote {len(cells)} cells to {REPORT_PATH}", file=sys.stderr)
    print(f"[OK] verdict counts: {verdict_counts}", file=sys.stderr)
    print(f"[OK] matches_expected: {matched}/{testable} = {report['summary']['matches_expected_pct']}%",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

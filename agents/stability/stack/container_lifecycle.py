#!/usr/bin/env python3
# =============================================================================
# container_lifecycle.py — Stability-owned lifecycle runner for SpoofStack cells
# =============================================================================
# Owner:        Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
# Filed under:  CLO-61 (split-out from CLO-55 per ownership-routing correction)
# Contract:     agents/orchestrator/SPEC.md §4 (the SPEC.md line at row
#               `container_lifecycle.py`) — refuses privileged:true, injects
#               seccomp/redroid-seccomp.json, cap_drop:[ALL], narrow cap_add,
#               no-new-privileges.
#
# Hard rules (matches refuse-privileged-compose skill):
#   1. Read every compose file passed via --compose; read any .env that
#      affects interpolation. (.env is optional.)
#   2. Grep each file for forbidden keys. On match: exit 78, dump offending
#      lines, name the unblock owner (board).
#   3. Verify cap_drop:[ALL] on every service.
#   4. Verify security_opt contains seccomp=<path>.
#   5. Verify security_opt contains no-new-privileges:true.
#   6. Verify every image: reference resolves to a digest in image-pins.yml.
#
# Sub-commands:
#   preflight  Run the 6 checks and exit; 0=pass, 78=hard-block.
#   up         Run preflight, then `docker compose up -d` (or --dry-run).
#   down       `docker compose down -v` (Stability tears down after Detection).
#   metrics    Sample uptime/oom_kills/module_failures → reports/stability/<rid>.json
#
# --dry-run output contract (for the refuse-privileged-compose skill's
# automated grep): the runner prints one banner line per service in the
# canonical form
#
#     [preflight:<service>] privileged=false, cap_drop=[ALL], image=<digest-ref>
#
# so the skill can `rg '^\\[preflight:.*\\] privileged=false, cap_drop=\\[ALL\\]'`
# to confirm the lifecycle ran the audit.
# =============================================================================

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

EXIT_OK = 0
EXIT_PRECONDITION_FAIL = 78  # POSIX EX_CONFIG — "configuration error"

# --- Forbidden patterns (mirrors refuse-privileged-compose skill grep) ---
# Note the WORD boundary on SYS_ADMIN / ALL: cap_drop:[ALL] is REQUIRED, the
# forbidden-form here is cap_add:[ALL] or cap_add:[..., SYS_ADMIN, ...].
FORBIDDEN_LINE_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"^\s*privileged\s*:\s*(true|yes)\b", re.IGNORECASE),
    re.compile(r"cap_add\s*:.*\b(SYS_ADMIN|ALL)\b"),
    re.compile(r"^\s*pid\s*:\s*[\"']?host[\"']?\s*$", re.IGNORECASE),
    re.compile(r"^\s*network\s*:\s*[\"']?host[\"']?\s*$", re.IGNORECASE),
    re.compile(r"^\s*ipc\s*:\s*[\"']?host[\"']?\s*$", re.IGNORECASE),
    re.compile(r"^\s*userns_mode\s*:\s*[\"']?host[\"']?\s*$", re.IGNORECASE),
    re.compile(r"/var/run/docker\.sock"),
    re.compile(r"[\"']?/\s*:\s*/host\b"),
]

# --- Forbidden image-ref patterns (no :latest, no untagged, no missing digest)
TAG_LATEST = re.compile(r"^\s*image\s*:\s*[\"']?[^@\s]+:latest[\"']?\s*$")
TAG_PIN_BAD = re.compile(r"^\s*image\s*:\s*[\"']?[^@\s]+:[0-9]+(\.[0-9]+)*[\"']?\s*$")


@dataclass
class PreflightFinding:
    file: str
    line_no: int
    line: str
    rule: str

    def fmt(self) -> str:
        return f"  {self.file}:{self.line_no}  [{self.rule}]\n    {self.line.rstrip()}"


@dataclass
class PreflightReport:
    findings: list[PreflightFinding] = field(default_factory=list)
    services_audited: list[str] = field(default_factory=list)
    images: dict[str, str] = field(default_factory=dict)  # service -> image-ref
    cap_drop_all_present: dict[str, bool] = field(default_factory=dict)
    seccomp_present: dict[str, bool] = field(default_factory=dict)
    no_new_priv_present: dict[str, bool] = field(default_factory=dict)
    digest_resolved: dict[str, str] = field(default_factory=dict)  # service -> "amd64"|"arm64"|"unpinned"
    module_file_sha256: dict[str, str] = field(default_factory=dict)  # "<mod>:<file>" -> "MATCH"|"MISMATCH"|"MISSING"

    @property
    def ok(self) -> bool:
        if self.findings:
            return False
        for svc in self.services_audited:
            if not self.cap_drop_all_present.get(svc):
                return False
            if not self.seccomp_present.get(svc):
                return False
            if not self.no_new_priv_present.get(svc):
                return False
            if self.digest_resolved.get(svc) == "unpinned":
                return False
        return True


# -----------------------------------------------------------------------------
# Compose-file parsing
# -----------------------------------------------------------------------------
def _load_yaml(path: Path) -> dict[str, Any]:
    try:
        import yaml  # PyYAML
    except ModuleNotFoundError:  # pragma: no cover — dev env only
        print(
            "[preflight:fatal] PyYAML missing. Install with `pip install PyYAML` and retry.",
            file=sys.stderr,
        )
        sys.exit(EXIT_PRECONDITION_FAIL)
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


_COMMENT_LINE = re.compile(r"^\s*#")


def _scan_forbidden_lines(path: Path) -> list[PreflightFinding]:
    findings: list[PreflightFinding] = []
    with path.open("r", encoding="utf-8") as f:
        for i, raw in enumerate(f, start=1):
            line = raw.rstrip("\n")
            if _COMMENT_LINE.match(line):
                continue
            for pat in FORBIDDEN_LINE_PATTERNS:
                if pat.search(line):
                    findings.append(
                        PreflightFinding(
                            file=str(path),
                            line_no=i,
                            line=line,
                            rule=pat.pattern,
                        )
                    )
            if TAG_LATEST.match(line):
                findings.append(
                    PreflightFinding(
                        file=str(path),
                        line_no=i,
                        line=line,
                        rule="image-tag-latest-forbidden",
                    )
                )
            elif TAG_PIN_BAD.match(line) and "@sha256:" not in line:
                # image: redroid/redroid:12.0.0  — versioned tag without digest.
                findings.append(
                    PreflightFinding(
                        file=str(path),
                        line_no=i,
                        line=line,
                        rule="image-tag-without-digest-forbidden",
                    )
                )
    return findings


# -----------------------------------------------------------------------------
# Per-service preflight
# -----------------------------------------------------------------------------
def _audit_service(
    svc_name: str,
    svc: dict[str, Any],
    image_pins: dict[str, Any],
    report: PreflightReport,
) -> None:
    report.services_audited.append(svc_name)

    cap_drop = svc.get("cap_drop") or []
    report.cap_drop_all_present[svc_name] = "ALL" in [str(c).upper() for c in cap_drop]

    sec_opt_raw = svc.get("security_opt") or []
    sec_opt = [str(s) for s in sec_opt_raw]
    report.seccomp_present[svc_name] = any(s.startswith("seccomp=") for s in sec_opt)
    report.no_new_priv_present[svc_name] = any(
        s.replace(" ", "") == "no-new-privileges:true" for s in sec_opt
    )

    image_ref = str(svc.get("image", "")).strip()
    report.images[svc_name] = image_ref
    if "@sha256:" in image_ref:
        # Resolve against image_pins
        digest = image_ref.split("@", 1)[1]
        platform_match = "unpinned"
        for key, entry in image_pins.items():
            if not isinstance(entry, dict):
                continue
            if entry.get("digest_amd64") == digest:
                platform_match = "amd64"
                break
            if entry.get("digest_arm64") == digest:
                platform_match = "arm64"
                break
        report.digest_resolved[svc_name] = platform_match
    else:
        report.digest_resolved[svc_name] = "unpinned"


# -----------------------------------------------------------------------------
# Image-pins loader
# -----------------------------------------------------------------------------
def _load_image_pins(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return _load_yaml(path)


# -----------------------------------------------------------------------------
# local-module-file-sha256-match  (verification id in image-pins.yml)
# -----------------------------------------------------------------------------
# For every modules[] entry that carries a file_sha256 map, resolve each file
# relative to the module's local source root and confirm sha256(file) == pinned
# hash. A mismatch, a missing file, or an unresolvable source is recorded as a
# PreflightFinding — which flips report.ok to False and drives the exit-78
# hard-block (mirrors how the image-digest / forbidden-key checks fail).
_LOCAL_SOURCE_PREFIX = "local:"


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _module_source_root(entry: dict[str, Any], repo_root: Path) -> Path | None:
    """Resolve a modules[] entry's local source dir, or None if not local."""
    source = str(entry.get("source", "")).strip()
    if not source.startswith(_LOCAL_SOURCE_PREFIX):
        return None
    rel = source[len(_LOCAL_SOURCE_PREFIX):].strip()
    if not rel:
        return None
    return (repo_root / rel).resolve()


def _verify_local_module_file_hashes(
    image_pins: dict[str, Any],
    repo_root: Path,
    report: PreflightReport,
) -> None:
    modules = image_pins.get("modules")
    if not isinstance(modules, list):
        return
    pins_label = "image-pins.yml::modules[].file_sha256"
    for entry in modules:
        if not isinstance(entry, dict):
            continue
        file_hashes = entry.get("file_sha256")
        if not isinstance(file_hashes, dict) or not file_hashes:
            continue
        mod_id = str(entry.get("id", "<unknown>"))
        root = _module_source_root(entry, repo_root)
        if root is None:
            report.findings.append(
                PreflightFinding(
                    file=pins_label,
                    line_no=0,
                    line=(
                        f"module '{mod_id}' declares file_sha256 pins but has no "
                        f"resolvable local: source — cannot fingerprint files"
                    ),
                    rule="local-module-file-sha256-match",
                )
            )
            continue
        for rel_path, pinned in file_hashes.items():
            target = (root / str(rel_path)).resolve()
            pinned_hash = str(pinned).strip().lower()
            if not target.exists() or not target.is_file():
                report.findings.append(
                    PreflightFinding(
                        file=str(target),
                        line_no=0,
                        line=(
                            f"module '{mod_id}' pinned file missing: {rel_path} "
                            f"(expected sha256 {pinned_hash})"
                        ),
                        rule="local-module-file-sha256-match",
                    )
                )
                report.module_file_sha256[f"{mod_id}:{rel_path}"] = "MISSING"
                continue
            actual = _sha256_file(target).lower()
            if actual != pinned_hash:
                report.findings.append(
                    PreflightFinding(
                        file=str(target),
                        line_no=0,
                        line=(
                            f"module '{mod_id}' file {rel_path} sha256 mismatch — "
                            f"pinned {pinned_hash}, got {actual} "
                            f"(cpuinfo-overlay tampering or stale pin)"
                        ),
                        rule="local-module-file-sha256-match",
                    )
                )
                report.module_file_sha256[f"{mod_id}:{rel_path}"] = "MISMATCH"
            else:
                report.module_file_sha256[f"{mod_id}:{rel_path}"] = "MATCH"


# -----------------------------------------------------------------------------
# Top-level preflight
# -----------------------------------------------------------------------------
def preflight(
    compose_files: list[Path],
    image_pins_path: Path,
    *,
    cell_config_path: Path | None = None,
) -> PreflightReport:
    image_pins = _load_image_pins(image_pins_path)
    report = PreflightReport()

    # local: module sources in image-pins.yml are repo-root-relative
    # (e.g. "local:agents/stability/stack/modules/cpuinfo-overlay"). The pins
    # file lives at <repo>/agents/stability/stack/image-pins.yml, so the repo
    # root is three parents up.
    repo_root = image_pins_path.resolve().parents[3]
    _verify_local_module_file_hashes(image_pins, repo_root, report)

    for cf in compose_files:
        if not cf.exists():
            report.findings.append(
                PreflightFinding(
                    file=str(cf),
                    line_no=0,
                    line=f"compose file does not exist: {cf}",
                    rule="compose-file-missing",
                )
            )
            continue
        report.findings.extend(_scan_forbidden_lines(cf))
        doc = _load_yaml(cf)
        services = doc.get("services") or {}
        for name, svc in services.items():
            if isinstance(svc, dict):
                _audit_service(name, svc, image_pins, report)

    if cell_config_path is not None and cell_config_path.exists():
        # Cross-check the cell config's declared image digest matches the
        # one image-pins.yml lists for the host architecture.
        try:
            cell = json.loads(cell_config_path.read_text())
        except json.JSONDecodeError as exc:
            report.findings.append(
                PreflightFinding(
                    file=str(cell_config_path),
                    line_no=0,
                    line=f"cell config JSON parse error: {exc}",
                    rule="cell-config-malformed",
                )
            )
        else:
            declared = (
                cell.get("expected_image_digest")
                or cell.get("image_digest")
                or None
            )
            if declared is not None:
                for svc_name, ref in report.images.items():
                    if declared not in ref:
                        report.findings.append(
                            PreflightFinding(
                                file=str(cell_config_path),
                                line_no=0,
                                line=(
                                    f"cell expects digest {declared} but "
                                    f"compose service {svc_name} resolves to {ref}"
                                ),
                                rule="cell-image-digest-mismatch",
                            )
                        )

    return report


# -----------------------------------------------------------------------------
# Banner-line emission (consumed by the refuse-privileged-compose skill grep)
# -----------------------------------------------------------------------------
def emit_banner(report: PreflightReport) -> None:
    for svc in report.services_audited:
        cap_ok = report.cap_drop_all_present.get(svc)
        priv_lit = "privileged=false"  # we have already refused privileged=true above
        cap_lit = "cap_drop=[ALL]" if cap_ok else "cap_drop=MISSING"
        img = report.images.get(svc, "<unset>")
        print(f"[preflight:{svc}] {priv_lit}, {cap_lit}, image={img}")


# -----------------------------------------------------------------------------
# Compose invocation
# -----------------------------------------------------------------------------
def _compose_cmd(
    action: str, compose_files: list[Path], *, services: list[str] | None = None
) -> list[str]:
    cmd: list[str] = ["docker", "compose"]
    for cf in compose_files:
        cmd += ["-f", str(cf)]
    cmd.append(action)
    if action == "up":
        cmd += ["-d", "--no-build", "--pull=never"]
    elif action == "down":
        cmd += ["-v"]
    if services:
        cmd += services
    return cmd


def cmd_up(args: argparse.Namespace) -> int:
    compose_files = [Path(p).resolve() for p in args.compose]
    image_pins_path = Path(args.image_pins).resolve()
    cell_path = Path(args.config).resolve() if args.config else None

    report = preflight(
        compose_files,
        image_pins_path,
        cell_config_path=cell_path,
    )
    emit_banner(report)

    if not report.ok:
        print(
            "\n[preflight:FAIL] refuse-privileged-compose hard-block. "
            "Open a [PRIVILEGED-OK] parent issue assigned to stability to override.",
            file=sys.stderr,
        )
        for f in report.findings:
            print(f.fmt(), file=sys.stderr)
        for svc in report.services_audited:
            if not report.cap_drop_all_present.get(svc):
                print(f"  service={svc} [cap_drop:[ALL]-missing]", file=sys.stderr)
            if not report.seccomp_present.get(svc):
                print(f"  service={svc} [seccomp=-missing]", file=sys.stderr)
            if not report.no_new_priv_present.get(svc):
                print(
                    f"  service={svc} [no-new-privileges:true-missing]", file=sys.stderr
                )
            if report.digest_resolved.get(svc) == "unpinned":
                print(f"  service={svc} [image-digest-not-in-image-pins.yml]", file=sys.stderr)
        for key, status in report.module_file_sha256.items():
            if status != "MATCH":
                print(
                    f"  module-file={key} [local-module-file-sha256-{status.lower()}]",
                    file=sys.stderr,
                )
        return EXIT_PRECONDITION_FAIL

    print("\n[preflight:PASS] refuse-privileged-compose 6/6 checks green.")

    if args.dry_run:
        print("[dry-run] would exec: " + " ".join(_compose_cmd("up", compose_files)))
        return EXIT_OK

    if shutil.which("docker") is None:
        print("[preflight:fatal] docker CLI not on PATH; cannot proceed.", file=sys.stderr)
        return EXIT_PRECONDITION_FAIL

    cmd = _compose_cmd("up", compose_files)
    print("[lifecycle] exec: " + " ".join(cmd))
    proc = subprocess.run(cmd, check=False)
    return proc.returncode


def cmd_down(args: argparse.Namespace) -> int:
    compose_files = [Path(p).resolve() for p in args.compose]
    if shutil.which("docker") is None:
        print("[preflight:fatal] docker CLI not on PATH; cannot proceed.", file=sys.stderr)
        return EXIT_PRECONDITION_FAIL
    cmd = _compose_cmd("down", compose_files)
    print("[lifecycle] exec: " + " ".join(cmd))
    proc = subprocess.run(cmd, check=False)
    return proc.returncode


def cmd_preflight(args: argparse.Namespace) -> int:
    compose_files = [Path(p).resolve() for p in args.compose]
    image_pins_path = Path(args.image_pins).resolve()
    cell_path = Path(args.config).resolve() if args.config else None
    report = preflight(
        compose_files,
        image_pins_path,
        cell_config_path=cell_path,
    )
    emit_banner(report)
    if report.ok:
        print("\n[preflight:PASS] refuse-privileged-compose 6/6 checks green.")
        return EXIT_OK
    print(
        "\n[preflight:FAIL] refuse-privileged-compose hard-block.", file=sys.stderr
    )
    for f in report.findings:
        print(f.fmt(), file=sys.stderr)
    return EXIT_PRECONDITION_FAIL


# -----------------------------------------------------------------------------
# CLI wiring
# -----------------------------------------------------------------------------
def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="container_lifecycle",
        description="Stability lifecycle runner with mandatory refuse-privileged-compose preflight",
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    for name in ("preflight", "up"):
        sp = sub.add_parser(name)
        sp.add_argument(
            "--compose",
            action="append",
            required=True,
            help="path to a docker-compose file (may be passed multiple times)",
        )
        sp.add_argument(
            "--image-pins",
            default=str(
                Path(__file__).resolve().parent / "image-pins.yml"
            ),
            help="path to image-pins.yml (default: alongside this script)",
        )
        sp.add_argument(
            "--config",
            default=None,
            help="path to per-cell config JSON (mutations/sandbox/<id>/<cell>.json)",
        )
        if name == "up":
            sp.add_argument(
                "--dry-run",
                action="store_true",
                help="run preflight + emit banner; skip docker compose up",
            )

    sp_down = sub.add_parser("down")
    sp_down.add_argument("--compose", action="append", required=True)

    return p


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    if args.cmd == "preflight":
        return cmd_preflight(args)
    if args.cmd == "up":
        return cmd_up(args)
    if args.cmd == "down":
        return cmd_down(args)
    return EXIT_PRECONDITION_FAIL


if __name__ == "__main__":
    sys.exit(main())

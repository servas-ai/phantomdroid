"""Unit and integration tests for container_lifecycle.py."""

from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
STACK_DIR = REPO_ROOT / "agents" / "stability" / "stack"
LIFECYCLE_SCRIPT = STACK_DIR / "container_lifecycle.py"
IMAGE_PINS = STACK_DIR / "image-pins.yml"
CPUINFO_MODULE_REL = "agents/stability/stack/modules/cpuinfo-overlay"


def _load_lifecycle_module():
    """Import container_lifecycle.py as a module for in-process unit tests."""
    spec = importlib.util.spec_from_file_location(
        "container_lifecycle_under_test", LIFECYCLE_SCRIPT
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod  # required so @dataclass can resolve the module
    spec.loader.exec_module(mod)
    return mod


def run_lifecycle(*args: str, cwd: Path = REPO_ROOT) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(REPO_ROOT)
    return subprocess.run(
        [sys.executable, str(LIFECYCLE_SCRIPT), *args],
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def test_preflight_all_layers() -> None:
    compose_dir = REPO_ROOT / "agents" / "stability" / "stack" / "compose"
    compose_files = sorted(list(compose_dir.glob("L*.y*ml")))
    assert len(compose_files) >= 8  # L0a, L0b, L1..L6

    for cf in compose_files:
        result = run_lifecycle("preflight", "--compose", str(cf))
        assert result.returncode == 0, f"preflight failed for {cf.name}: {result.stderr}"
        assert "[preflight:" in result.stdout
        assert "refuse-privileged-compose 6/6 checks green" in result.stdout


def test_up_dry_run() -> None:
    cf = REPO_ROOT / "agents" / "stability" / "stack" / "compose" / "L0a.yml"
    result = run_lifecycle("up", "--compose", str(cf), "--dry-run")
    assert result.returncode == 0, result.stderr
    assert "[dry-run] would exec: docker compose" in result.stdout
    assert "refuse-privileged-compose 6/6 checks green" in result.stdout


# -----------------------------------------------------------------------------
# local-module-file-sha256-match enforcement (resynced cpuinfo-overlay pins)
# -----------------------------------------------------------------------------
def _build_pins_with_module(tmp_path: Path, file_hashes: dict[str, str]) -> Path:
    """Write a minimal image-pins.yml whose cpuinfo_overlay module points its
    local: source at a copy under tmp_path, with the given file_sha256 map.

    The pins file is placed so that parents[3] (the repo-root the loader infers
    from the pins path) is tmp_path, and the module lives at the same repo-root-
    relative path the real pin uses.
    """
    import shutil

    import yaml

    # Recreate the <repo>/agents/stability/stack/ layout under tmp_path so the
    # loader's repo_root = image_pins_path.parents[3] resolves to tmp_path.
    stack_dir = tmp_path / "agents" / "stability" / "stack"
    stack_dir.mkdir(parents=True, exist_ok=True)
    module_dst = tmp_path / CPUINFO_MODULE_REL
    shutil.copytree(REPO_ROOT / CPUINFO_MODULE_REL, module_dst, dirs_exist_ok=True)

    pins = {
        "modules": [
            {
                "id": "cpuinfo_overlay",
                "source": "local:" + CPUINFO_MODULE_REL,
                "file_sha256": file_hashes,
            }
        ]
    }
    pins_path = stack_dir / "image-pins.yml"
    pins_path.write_text(yaml.safe_dump(pins), encoding="utf-8")
    return pins_path


def _authoritative_hashes(cl) -> dict[str, str]:
    root = REPO_ROOT / CPUINFO_MODULE_REL
    return {
        rel: cl._sha256_file(root / rel)
        for rel in (
            "module.prop",
            "service.sh",
            "system/etc/cpuinfo.spoofed",
            "META-INF/com/google/android/update-binary",
        )
    }


def test_module_file_sha256_passes_against_resynced_pins() -> None:
    """The in-tree image-pins.yml cpuinfo_overlay file_sha256 pins are resynced
    to the authoritative HEAD files (676d6c1 CLO-114 fix), so the enforcement
    check must report every file MATCH and produce no findings."""
    cl = _load_lifecycle_module()
    report = cl.PreflightReport()
    pins = cl._load_image_pins(IMAGE_PINS)
    cl._verify_local_module_file_hashes(pins, REPO_ROOT, report)

    assert report.module_file_sha256, "check did not run on any module file"
    assert all(
        status == "MATCH" for status in report.module_file_sha256.values()
    ), report.module_file_sha256
    assert not report.findings, [f.line for f in report.findings]
    # The two files that drifted in 676d6c1 must be present and MATCH.
    assert report.module_file_sha256["cpuinfo_overlay:service.sh"] == "MATCH"
    assert (
        report.module_file_sha256["cpuinfo_overlay:system/etc/cpuinfo.spoofed"]
        == "MATCH"
    )


def test_module_file_sha256_fails_on_tampered_file(tmp_path: Path) -> None:
    """A byte change to a pinned module file must be caught as a mismatch
    finding (drives the exit-78 preflight hard-block)."""
    cl = _load_lifecycle_module()
    hashes = _authoritative_hashes(cl)
    pins_path = _build_pins_with_module(tmp_path, hashes)

    # Tamper: append a byte to the copied cpuinfo.spoofed under tmp_path.
    tampered = tmp_path / CPUINFO_MODULE_REL / "system" / "etc" / "cpuinfo.spoofed"
    tampered.write_bytes(tampered.read_bytes() + b"\n# tamper\n")

    report = cl.preflight([], pins_path)
    assert not report.ok
    assert (
        report.module_file_sha256["cpuinfo_overlay:system/etc/cpuinfo.spoofed"]
        == "MISMATCH"
    )
    mismatch_findings = [
        f for f in report.findings if f.rule == "local-module-file-sha256-match"
    ]
    assert mismatch_findings, "tampering produced no sha256 finding"
    assert any("mismatch" in f.line.lower() for f in mismatch_findings)


def test_module_file_sha256_fails_on_missing_file(tmp_path: Path) -> None:
    """A pinned file that does not exist must FAIL (not silently pass)."""
    cl = _load_lifecycle_module()
    hashes = _authoritative_hashes(cl)
    pins_path = _build_pins_with_module(tmp_path, hashes)

    # Remove a pinned file from the copied module tree.
    missing = tmp_path / CPUINFO_MODULE_REL / "service.sh"
    missing.unlink()

    report = cl.preflight([], pins_path)
    assert not report.ok
    assert report.module_file_sha256["cpuinfo_overlay:service.sh"] == "MISSING"
    missing_findings = [
        f
        for f in report.findings
        if f.rule == "local-module-file-sha256-match" and "missing" in f.line.lower()
    ]
    assert missing_findings, "missing pinned file produced no finding"

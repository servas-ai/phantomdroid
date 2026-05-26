"""Unit and integration tests for container_lifecycle.py."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
LIFECYCLE_SCRIPT = REPO_ROOT / "agents" / "stability" / "stack" / "container_lifecycle.py"


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

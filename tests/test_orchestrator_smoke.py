"""Integration test for the orchestrator --matrix smoke flag (CLO-143 W20)."""

from __future__ import annotations

import os
import sqlite3
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]


def run_module(*args: str, cwd: Path = REPO_ROOT) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(REPO_ROOT)
    return subprocess.run(
        [sys.executable, "-m", "agents.orchestrator.src.runner", *args],
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def fetch_smoke_rows(journal_path: Path) -> list[dict]:
    conn = sqlite3.connect(journal_path)
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(
            "SELECT config_id, run_index, status FROM cells WHERE config_id = 'smoke'"
        ).fetchall()
    finally:
        conn.close()
    return [dict(row) for row in rows]


def test_help_advertises_matrix_smoke() -> None:
    result = run_module("--help")
    assert result.returncode == 0, result.stderr
    assert "--matrix smoke" in result.stdout


def test_matrix_smoke_writes_completed_journal_row(tmp_path: Path) -> None:
    journal_path = tmp_path / "results" / "journal.sqlite"

    result = run_module(
        "--matrix",
        "smoke",
        "--n",
        "1",
        "--journal-path",
        str(journal_path),
    )

    assert result.returncode == 0, result.stderr
    assert journal_path.exists()

    rows = fetch_smoke_rows(journal_path)
    assert len(rows) == 1
    assert rows[0]["config_id"] == "smoke"
    assert rows[0]["run_index"] == 0
    assert rows[0]["status"] == "COMPLETED"


def test_matrix_smoke_n_cycles_writes_n_rows(tmp_path: Path) -> None:
    journal_path = tmp_path / "journal.sqlite"

    result = run_module(
        "--matrix",
        "smoke",
        "--n",
        "3",
        "--journal-path",
        str(journal_path),
    )

    assert result.returncode == 0, result.stderr

    rows = fetch_smoke_rows(journal_path)
    assert {row["run_index"] for row in rows} == {0, 1, 2}
    assert all(row["status"] == "COMPLETED" for row in rows)


def test_matrix_smoke_rejects_missing_fixture(tmp_path: Path) -> None:
    journal_path = tmp_path / "journal.sqlite"

    result = run_module(
        "--matrix",
        "smoke",
        "--n",
        "1",
        "--journal-path",
        str(journal_path),
        "--fixture",
        str(tmp_path / "does-not-exist.json"),
    )

    assert result.returncode == 65
    assert "smoke fixture not found" in result.stderr

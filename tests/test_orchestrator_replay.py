"""Integration tests for the orchestrator --matrix replay mode.

Replay maps the two committed e2e reports across the device x os matrix,
seeding N journal rows per cell and writing a multi-cell heatmap cells.json
(more non-grey cells than the smoke path's single cell).
"""

from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

from agents.orchestrator.src.aggregator import DEVICES, OS_VERSIONS
from agents.orchestrator.src.runner import replay_matrix_plan

REPO_ROOT = Path(__file__).resolve().parents[1]


def run_module(*args: str) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(REPO_ROOT)
    return subprocess.run(
        [sys.executable, "-m", "agents.orchestrator.src.runner", *args],
        cwd=REPO_ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def fetch_replay_rows(journal_path: Path) -> list[dict]:
    conn = sqlite3.connect(journal_path)
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(
            "SELECT config_id, run_index, status FROM cells "
            "WHERE config_id LIKE 'replay-cell-%'"
        ).fetchall()
    finally:
        conn.close()
    return [dict(row) for row in rows]


def test_help_advertises_matrix_replay() -> None:
    result = run_module("--help")
    assert result.returncode == 0, result.stderr
    assert "--matrix replay" in result.stdout


def test_replay_plan_covers_full_matrix() -> None:
    plan = replay_matrix_plan(None)
    assert len(plan) == len(DEVICES) * len(OS_VERSIONS)
    coords = {(device, os_version) for device, os_version, _, _ in plan}
    assert coords == {(d, o) for d in DEVICES for o in OS_VERSIONS}


def test_replay_plan_alternates_reports() -> None:
    plan = replay_matrix_plan(4)
    report_names = [report.name for _, _, report, _ in plan]
    assert report_names[0] == "e2e-report-spoofed.json"
    assert report_names[1] == "e2e-report-unspoofed.json"
    assert report_names[2] == "e2e-report-spoofed.json"
    assert report_names[3] == "e2e-report-unspoofed.json"


def test_replay_writes_multi_cell_cells_json(tmp_path: Path) -> None:
    journal_path = tmp_path / "results" / "journal.sqlite"
    out_path = tmp_path / "heatmap" / "cells.json"

    result = run_module(
        "--matrix",
        "replay",
        "--n",
        "2",
        "--journal-path",
        str(journal_path),
        "--out",
        str(out_path),
    )

    assert result.returncode == 0, result.stderr
    summary = json.loads(result.stdout.strip().splitlines()[-1])
    assert summary["matrix"] == "replay"
    assert summary["non_grey_cells"] > 2
    assert summary["non_grey_cells"] == len(DEVICES) * len(OS_VERSIONS)

    cells = json.loads(out_path.read_text(encoding="utf-8"))
    # Far more non-grey cells than the smoke path's single cell.
    assert len(cells) == len(DEVICES) * len(OS_VERSIONS)
    for key, value in cells.items():
        device, _, os_version = key.partition("|")
        assert device in DEVICES
        assert os_version in OS_VERSIONS
        assert isinstance(value, float)
        assert 0.0 <= value <= 1.0

    # Mix of green (spoofed ~0.0) and amber (unspoofed ~0.35) verdicts.
    assert min(cells.values()) < 0.3
    assert max(cells.values()) > 0.3


def test_replay_seeds_n_journal_rows_per_cell(tmp_path: Path) -> None:
    journal_path = tmp_path / "journal.sqlite"
    out_path = tmp_path / "cells.json"

    result = run_module(
        "--matrix",
        "replay",
        "--n",
        "3",
        "--cells",
        "4",
        "--journal-path",
        str(journal_path),
        "--out",
        str(out_path),
    )

    assert result.returncode == 0, result.stderr
    rows = fetch_replay_rows(journal_path)
    # 4 cells x 3 cycles = 12 journal rows, all COMPLETED.
    assert len(rows) == 12
    assert all(row["status"] == "COMPLETED" for row in rows)
    assert {row["run_index"] for row in rows} == {0, 1, 2}

    cells = json.loads(out_path.read_text(encoding="utf-8"))
    assert len(cells) == 4


def test_replay_rejects_zero_cells(tmp_path: Path) -> None:
    result = run_module(
        "--matrix",
        "replay",
        "--cells",
        "0",
        "--journal-path",
        str(tmp_path / "journal.sqlite"),
        "--out",
        str(tmp_path / "cells.json"),
    )
    assert result.returncode == 64
    assert "--cells must be >= 1" in result.stderr

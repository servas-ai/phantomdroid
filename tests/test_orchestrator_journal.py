import json
import os
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER = REPO_ROOT / "runner"


def run_runner(*args: str, cwd: Path = REPO_ROOT) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(REPO_ROOT)
    return subprocess.run(
        [sys.executable, str(RUNNER), *args],
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def json_lines(output: str) -> list[dict]:
    return [json.loads(line) for line in output.splitlines() if line.strip()]


def test_journal_lists_pending_cells_with_limit(tmp_path: Path) -> None:
    journal_path = tmp_path / "results" / "journal.sqlite"

    for index in range(5):
        result = run_runner(
            "journal",
            "seed",
            "--journal-path",
            str(journal_path),
            "--config",
            "cfg-a",
            "--run-index",
            str(index),
            "--layer-set",
            "L0a,L1",
        )
        assert result.returncode == 0, result.stderr

    result = run_runner(
        "journal",
        "--journal-path",
        str(journal_path),
        "--status",
        "PENDING",
        "--limit",
        "4",
    )

    assert result.returncode == 0, result.stderr
    rows = json_lines(result.stdout)
    assert [row["run_index"] for row in rows] == [0, 1, 2, 3]
    assert all(row["config_id"] == "cfg-a" for row in rows)
    assert all(row["status"] == "PENDING" for row in rows)
    assert all(row["layer_set"] == ["L0a", "L1"] for row in rows)
    assert journal_path.exists()


def test_journal_claims_once_and_persists_terminal_status(tmp_path: Path) -> None:
    journal_path = tmp_path / "results" / "journal.sqlite"

    seed = run_runner(
        "journal",
        "seed",
        "--journal-path",
        str(journal_path),
        "--config",
        "cfg-b",
        "--run-index",
        "7",
        "--layer-set",
        "L0a,L2",
    )
    assert seed.returncode == 0, seed.stderr

    claim = run_runner(
        "journal",
        "claim",
        "--journal-path",
        str(journal_path),
        "--config",
        "cfg-b",
        "--run-index",
        "7",
        "--parent-issue-id",
        "cell-cfg-b-7",
    )
    assert claim.returncode == 0, claim.stderr
    claimed = json.loads(claim.stdout)
    assert claimed["status"] == "RUNNING"
    assert claimed["parent_issue_id"] == "cell-cfg-b-7"

    active = run_runner(
        "journal",
        "--journal-path",
        str(journal_path),
        "--status",
        "PENDING",
        "--status",
        "RUNNING",
    )
    assert active.returncode == 0, active.stderr
    active_rows = json_lines(active.stdout)
    assert len(active_rows) == 1
    assert active_rows[0]["status"] == "RUNNING"

    duplicate = run_runner(
        "journal",
        "claim",
        "--journal-path",
        str(journal_path),
        "--config",
        "cfg-b",
        "--run-index",
        "7",
    )
    assert duplicate.returncode == 75
    assert "not claimable" in duplicate.stderr

    complete = run_runner(
        "journal",
        "complete",
        "--journal-path",
        str(journal_path),
        "--config",
        "cfg-b",
        "--run-index",
        "7",
        "--status",
        "COMPLETED",
    )
    assert complete.returncode == 0, complete.stderr
    completed = json.loads(complete.stdout)
    assert completed["status"] == "COMPLETED"
    assert completed["finished_at"]

    listed = run_runner(
        "journal",
        "--journal-path",
        str(journal_path),
        "--status",
        "RUNNING",
        "--status",
        "PENDING",
    )
    assert listed.returncode == 0, listed.stderr
    assert json_lines(listed.stdout) == []


def test_journal_refuses_unavailable_database_cleanly(tmp_path: Path) -> None:
    blocking_file = tmp_path / "not-a-directory"
    blocking_file.write_text("blocks journal parent", encoding="utf-8")
    journal_path = blocking_file / "journal.sqlite"

    result = run_runner(
        "journal",
        "--journal-path",
        str(journal_path),
        "--status",
        "PENDING",
    )

    assert result.returncode == 70
    assert "journal unavailable:" in result.stderr

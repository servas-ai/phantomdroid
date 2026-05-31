# agents/orchestrator/src/persistence.py
#
# Atomic, schema-gated persistence (SPEC §4 persistence.py + §1 goal 3).
#
# Writes a validated report JSON into experiments/runs/{config_id}/{run_id}.json
# via tmp-write + fsync + atomic rename. Refuses to overwrite an existing run_id
# (a collision = bug, SPEC §7). Validation is delegated to report_validator.

from __future__ import annotations

import json
import os
from pathlib import Path

try:
    from .report_validator import validate_report, ReportValidationError  # type: ignore
except Exception:  # pragma: no cover - allow standalone import
    validate_report = None  # type: ignore
    ReportValidationError = ValueError  # type: ignore


class PersistenceCollision(RuntimeError):
    """Raised when a run_id file already exists (SPEC §7: hard-fail)."""


class ReportInvalid(RuntimeError):
    """Raised when the report fails schema validation before persistence."""


DEFAULT_RUNS_ROOT = Path("experiments/runs")


def persist_report(
    report: dict,
    config_id: str,
    run_id: str,
    runs_root: Path | str = DEFAULT_RUNS_ROOT,
    *,
    validate: bool = True,
) -> Path:
    """Atomically persist a report. Returns the written path. Refuses overwrite."""
    if validate and validate_report is not None:
        try:
            validate_report(report)
        except ReportValidationError as exc:
            raise ReportInvalid(f"report failed schema validation: {exc}") from exc

    root = Path(runs_root) / config_id
    root.mkdir(parents=True, exist_ok=True)
    target = root / f"{run_id}.json"
    if target.exists():
        raise PersistenceCollision(f"run_id file already exists (collision=bug): {target}")

    tmp = root / f".{run_id}.json.tmp"
    payload = json.dumps(report, indent=2, sort_keys=False, ensure_ascii=False)
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    try:
        os.write(fd, payload.encode("utf-8"))
        os.fsync(fd)
    finally:
        os.close(fd)
    os.replace(tmp, target)  # atomic on POSIX
    # fsync the directory so the rename is durable
    dfd = os.open(root, os.O_RDONLY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)
    return target

"""SQLite journal for idempotent Orchestrator matrix dispatch."""

from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Iterable


ACTIVE_STATUSES = {"PENDING", "RUNNING"}
TERMINAL_STATUSES = {
    "COMPLETED",
    "ABORTED",
    "ABORTED_DRIFT",
    "BOOT_FAIL",
    "INSTALL_FAIL",
    "PROBE_TIMEOUT",
    "SCHEMA_FAIL",
    "POLICY_REFUSED",
    "FAILED",
}
ALL_STATUSES = ACTIVE_STATUSES | TERMINAL_STATUSES


class JournalUnavailable(RuntimeError):
    """Raised when the SQLite journal cannot be opened or migrated."""


class CellNotFound(RuntimeError):
    """Raised when a requested journal cell does not exist."""


class CellNotClaimable(RuntimeError):
    """Raised when a cell cannot transition to RUNNING."""


class InvalidStatus(RuntimeError):
    """Raised when a caller supplies an unsupported journal status."""


@dataclass(frozen=True)
class JournalCell:
    config_id: str
    run_index: int
    layer_set: list[str]
    status: str
    parent_issue_id: str | None = None
    build_issue_id: str | None = None
    probe_issue_id: str | None = None
    error: str | None = None
    created_at: str | None = None
    updated_at: str | None = None
    started_at: str | None = None
    finished_at: str | None = None

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> "JournalCell":
        return cls(
            config_id=row["config_id"],
            run_index=row["run_index"],
            layer_set=json.loads(row["layer_set_json"]),
            status=row["status"],
            parent_issue_id=row["parent_issue_id"],
            build_issue_id=row["build_issue_id"],
            probe_issue_id=row["probe_issue_id"],
            error=row["error"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            started_at=row["started_at"],
            finished_at=row["finished_at"],
        )

    def to_dict(self) -> dict:
        return {
            "config_id": self.config_id,
            "run_index": self.run_index,
            "layer_set": self.layer_set,
            "status": self.status,
            "parent_issue_id": self.parent_issue_id,
            "build_issue_id": self.build_issue_id,
            "probe_issue_id": self.probe_issue_id,
            "error": self.error,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
        }


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def validate_statuses(statuses: Iterable[str]) -> list[str]:
    normalized = [status.upper() for status in statuses]
    invalid = sorted(set(normalized) - ALL_STATUSES)
    if invalid:
        raise InvalidStatus(f"unsupported journal status: {', '.join(invalid)}")
    return normalized


class JournalStore:
    def __init__(self, path: Path | str = Path("results/journal.sqlite")) -> None:
        self.path = Path(path)

    def _connect(self) -> sqlite3.Connection:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(self.path)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA foreign_keys=ON")
            self._migrate(conn)
            return conn
        except OSError as exc:
            raise JournalUnavailable(str(exc)) from exc
        except sqlite3.Error as exc:
            raise JournalUnavailable(str(exc)) from exc

    @staticmethod
    def _migrate(conn: sqlite3.Connection) -> None:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS cells (
              config_id TEXT NOT NULL,
              run_index INTEGER NOT NULL,
              layer_set_json TEXT NOT NULL,
              status TEXT NOT NULL CHECK (
                status IN (
                  'PENDING',
                  'RUNNING',
                  'COMPLETED',
                  'ABORTED',
                  'ABORTED_DRIFT',
                  'BOOT_FAIL',
                  'INSTALL_FAIL',
                  'PROBE_TIMEOUT',
                  'SCHEMA_FAIL',
                  'POLICY_REFUSED',
                  'FAILED'
                )
              ),
              parent_issue_id TEXT,
              build_issue_id TEXT,
              probe_issue_id TEXT,
              error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              started_at TEXT,
              finished_at TEXT,
              PRIMARY KEY (config_id, run_index)
            );
            CREATE INDEX IF NOT EXISTS idx_cells_status
              ON cells(status, config_id, run_index);
            """
        )
        conn.commit()

    def seed_cell(
        self,
        config_id: str,
        run_index: int,
        layer_set: list[str],
    ) -> JournalCell:
        now = utc_now()
        layer_set_json = json.dumps(layer_set, separators=(",", ":"))
        with self._connect() as conn:
            conn.execute(
                """
                INSERT OR IGNORE INTO cells (
                  config_id,
                  run_index,
                  layer_set_json,
                  status,
                  created_at,
                  updated_at
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                """,
                (config_id, run_index, layer_set_json, now, now),
            )
            return self.get_cell(conn, config_id, run_index)

    def list_cells(self, statuses: Iterable[str], limit: int | None = None) -> list[JournalCell]:
        normalized = validate_statuses(statuses)
        if limit is not None and limit < 1:
            raise ValueError("limit must be greater than 0")
        placeholders = ",".join("?" for _ in normalized)
        sql = (
            "SELECT * FROM cells "
            f"WHERE status IN ({placeholders}) "
            "ORDER BY config_id ASC, run_index ASC"
        )
        params: list[object] = list(normalized)
        if limit is not None:
            sql += " LIMIT ?"
            params.append(limit)
        with self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
            return [JournalCell.from_row(row) for row in rows]

    def claim_cell(
        self,
        config_id: str,
        run_index: int,
        parent_issue_id: str | None = None,
        build_issue_id: str | None = None,
        probe_issue_id: str | None = None,
    ) -> JournalCell:
        now = utc_now()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT * FROM cells WHERE config_id = ? AND run_index = ?",
                (config_id, run_index),
            ).fetchone()
            if row is None:
                raise CellNotFound(f"cell {config_id}/{run_index} does not exist")
            cell = JournalCell.from_row(row)
            if cell.status != "PENDING":
                raise CellNotClaimable(
                    f"cell {config_id}/{run_index} is {cell.status}, not claimable"
                )
            conn.execute(
                """
                UPDATE cells
                SET status = 'RUNNING',
                    parent_issue_id = COALESCE(?, parent_issue_id),
                    build_issue_id = COALESCE(?, build_issue_id),
                    probe_issue_id = COALESCE(?, probe_issue_id),
                    started_at = COALESCE(started_at, ?),
                    updated_at = ?
                WHERE config_id = ? AND run_index = ?
                """,
                (
                    parent_issue_id,
                    build_issue_id,
                    probe_issue_id,
                    now,
                    now,
                    config_id,
                    run_index,
                ),
            )
            return self.get_cell(conn, config_id, run_index)

    def complete_cell(
        self,
        config_id: str,
        run_index: int,
        status: str,
        error: str | None = None,
    ) -> JournalCell:
        normalized = status.upper()
        if normalized not in TERMINAL_STATUSES:
            raise InvalidStatus(f"terminal status required, got {status}")
        now = utc_now()
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM cells WHERE config_id = ? AND run_index = ?",
                (config_id, run_index),
            ).fetchone()
            if row is None:
                raise CellNotFound(f"cell {config_id}/{run_index} does not exist")
            conn.execute(
                """
                UPDATE cells
                SET status = ?,
                    error = ?,
                    finished_at = ?,
                    updated_at = ?
                WHERE config_id = ? AND run_index = ?
                """,
                (normalized, error, now, now, config_id, run_index),
            )
            return self.get_cell(conn, config_id, run_index)

    @staticmethod
    def get_cell(conn: sqlite3.Connection, config_id: str, run_index: int) -> JournalCell:
        row = conn.execute(
            "SELECT * FROM cells WHERE config_id = ? AND run_index = ?",
            (config_id, run_index),
        ).fetchone()
        if row is None:
            raise CellNotFound(f"cell {config_id}/{run_index} does not exist")
        return JournalCell.from_row(row)

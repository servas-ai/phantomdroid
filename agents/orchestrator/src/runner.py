"""Canonical Orchestrator runner CLI."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import re

from agents.orchestrator.src.aggregator import (
    DEVICES,
    OS_VERSIONS,
    AggregationError,
    ReportAssignment,
    aggregate_to_cells_json,
)
from agents.orchestrator.src.journal import (
    CellNotClaimable,
    CellNotFound,
    InvalidStatus,
    JournalStore,
    JournalUnavailable,
)


EXIT_INTERNAL = 70
EXIT_NOT_CLAIMABLE = 75


def emit_json(row: object) -> None:
    if hasattr(row, "to_dict"):
        row = row.to_dict()
    print(json.dumps(row, sort_keys=True, separators=(",", ":")))


def parse_layer_set(raw: str) -> list[str]:
    raw = raw.strip()
    if not raw:
        raise argparse.ArgumentTypeError("layer set cannot be empty")
    if raw.startswith("["):
        parsed = json.loads(raw)
        if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
            raise argparse.ArgumentTypeError("JSON layer set must be a list of strings")
        return parsed
    return [item.strip() for item in raw.split(",") if item.strip()]


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--journal-path",
        type=Path,
        default=Path("results/journal.sqlite"),
        help="Path to the SQLite journal.",
    )


def build_journal_parser(action: str) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog=f"runner journal {action}")
    add_common(parser)
    if action == "list":
        parser.add_argument(
            "--status",
            action="append",
            default=None,
            help="Status to show. Repeat for multiple statuses. Defaults to PENDING.",
        )
        parser.add_argument("--limit", type=int, default=None)
    elif action == "seed":
        parser.add_argument("--config", required=True, dest="config_id")
        parser.add_argument("--run-index", required=True, type=int)
        parser.add_argument("--layer-set", required=True, type=parse_layer_set)
    elif action == "claim":
        parser.add_argument("--config", required=True, dest="config_id")
        parser.add_argument("--run-index", required=True, type=int)
        parser.add_argument("--parent-issue-id")
        parser.add_argument("--build-issue-id")
        parser.add_argument("--probe-issue-id")
    elif action == "complete":
        parser.add_argument("--config", required=True, dest="config_id")
        parser.add_argument("--run-index", required=True, type=int)
        parser.add_argument("--status", required=True)
        parser.add_argument("--error")
    else:
        raise ValueError(f"unsupported journal action: {action}")
    return parser


def journal_main(argv: list[str]) -> int:
    action = "list"
    if argv and argv[0] in {"seed", "claim", "complete"}:
        action = argv[0]
        argv = argv[1:]
    parser = build_journal_parser(action)
    args = parser.parse_args(argv)
    store = JournalStore(args.journal_path)

    try:
        if action == "list":
            statuses = args.status if args.status else ["PENDING"]
            for cell in store.list_cells(statuses=statuses, limit=args.limit):
                emit_json(cell)
        elif action == "seed":
            emit_json(store.seed_cell(args.config_id, args.run_index, args.layer_set))
        elif action == "claim":
            emit_json(
                store.claim_cell(
                    args.config_id,
                    args.run_index,
                    parent_issue_id=args.parent_issue_id,
                    build_issue_id=args.build_issue_id,
                    probe_issue_id=args.probe_issue_id,
                )
            )
        elif action == "complete":
            emit_json(
                store.complete_cell(
                    args.config_id,
                    args.run_index,
                    args.status,
                    error=args.error,
                )
            )
    except JournalUnavailable as exc:
        print(f"journal unavailable: {exc}", file=sys.stderr)
        return EXIT_INTERNAL
    except CellNotClaimable as exc:
        print(str(exc), file=sys.stderr)
        return EXIT_NOT_CLAIMABLE
    except (CellNotFound, InvalidStatus, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 64
    return 0


SMOKE_CONFIG_ID = "smoke"
SMOKE_LAYER_SET = ["L0a"]
SMOKE_FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "apps"
    / "detector-lab"
    / "examples"
    / "probe-result.fixture.json"
)

REPLAY_SPOOFED_REPORT = (
    Path(__file__).resolve().parents[3] / "results" / "e2e-report-spoofed.json"
)
REPLAY_UNSPOOFED_REPORT = (
    Path(__file__).resolve().parents[3] / "results" / "e2e-report-unspoofed.json"
)
# Layer set carried on a journal row when its cell replays the spoofed report;
# the unspoofed (baseline) cell carries no SpoofStack layers.
REPLAY_SPOOFED_LAYERS = ["L0a", "L1", "L2"]
REPLAY_UNSPOOFED_LAYERS: list[str] = []


def build_matrix_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="runner --matrix")
    parser.add_argument(
        "--matrix",
        required=True,
        choices=["smoke", "replay"],
        help=(
            "Matrix mode. 'smoke' runs 1 cell against a fixed mock fixture "
            "(no docker, no adb). 'replay' maps the two committed e2e reports "
            "across the full device x os matrix, seeding N journal rows per "
            "cell and writing a multi-cell heatmap cells.json (no docker, no adb)."
        ),
    )
    parser.add_argument(
        "--n",
        type=int,
        default=1,
        help="Number of cycles per cell (default 1).",
    )
    parser.add_argument(
        "--journal-path",
        type=Path,
        default=Path("results/journal.sqlite"),
        help="Path to the SQLite journal.",
    )
    parser.add_argument(
        "--fixture",
        type=Path,
        default=SMOKE_FIXTURE,
        help="Mock probe-result fixture used for smoke mode.",
    )
    parser.add_argument(
        "--cells",
        type=int,
        default=None,
        help=(
            "replay mode only: how many device x os cells to fill "
            "(default: the full matrix). Cells alternate spoofed/unspoofed."
        ),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help=(
            "replay mode only: cells.json output path "
            "(default: latest docs/super-action/W*/heatmap/cells.json)."
        ),
    )
    return parser


def matrix_main(argv: list[str]) -> int:
    parser = build_matrix_parser()
    args = parser.parse_args(argv)
    if args.matrix == "replay":
        return replay_main(args)
    if args.n < 1:
        print("--n must be >= 1", file=sys.stderr)
        return 64
    if not args.fixture.exists():
        print(f"smoke fixture not found: {args.fixture}", file=sys.stderr)
        return 65

    store = JournalStore(args.journal_path)
    try:
        for run_index in range(args.n):
            store.seed_cell(SMOKE_CONFIG_ID, run_index, SMOKE_LAYER_SET)
            try:
                store.claim_cell(SMOKE_CONFIG_ID, run_index)
            except CellNotClaimable:
                continue
            with args.fixture.open(encoding="utf-8") as fh:
                json.load(fh)
            store.complete_cell(SMOKE_CONFIG_ID, run_index, "COMPLETED")
    except JournalUnavailable as exc:
        print(f"journal unavailable: {exc}", file=sys.stderr)
        return EXIT_INTERNAL
    except (CellNotFound, InvalidStatus, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 64

    print(
        json.dumps(
            {
                "matrix": "smoke",
                "config_id": SMOKE_CONFIG_ID,
                "cycles": args.n,
                "result": "pass",
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


def replay_matrix_plan(num_cells: int | None) -> list[tuple[str, str, Path, list[str]]]:
    """Return the ordered (device, os, report, layer_set) plan for replay mode.

    Walks the full device x os matrix row-major and alternates the spoofed and
    unspoofed e2e reports so the rendered heatmap shows a green/amber mix rather
    than a single class. ``num_cells`` truncates the plan (default: full matrix).
    """
    full = [(device, os_version) for device in DEVICES for os_version in OS_VERSIONS]
    if num_cells is None:
        num_cells = len(full)
    if num_cells < 1:
        raise ValueError("--cells must be >= 1")
    plan: list[tuple[str, str, Path, list[str]]] = []
    for index, (device, os_version) in enumerate(full[:num_cells]):
        if index % 2 == 0:
            plan.append((device, os_version, REPLAY_SPOOFED_REPORT, REPLAY_SPOOFED_LAYERS))
        else:
            plan.append(
                (device, os_version, REPLAY_UNSPOOFED_REPORT, REPLAY_UNSPOOFED_LAYERS)
            )
    return plan


def replay_main(args: argparse.Namespace) -> int:
    if args.n < 1:
        print("--n must be >= 1", file=sys.stderr)
        return 64
    for report in (REPLAY_SPOOFED_REPORT, REPLAY_UNSPOOFED_REPORT):
        if not report.exists():
            print(f"replay report not found: {report}", file=sys.stderr)
            return 65
    try:
        plan = replay_matrix_plan(args.cells)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 64

    store = JournalStore(args.journal_path)
    assignments: list[ReportAssignment] = []
    try:
        for cell_index, (device, os_version, report, layer_set) in enumerate(plan):
            config_id = f"replay-cell-{cell_index:02d}"
            for run_index in range(args.n):
                store.seed_cell(config_id, run_index, layer_set)
                try:
                    store.claim_cell(config_id, run_index)
                except CellNotClaimable:
                    continue
                store.complete_cell(config_id, run_index, "COMPLETED")
            assignments.append(
                ReportAssignment(
                    report_path=report, device=device, os_version=os_version
                )
            )
    except JournalUnavailable as exc:
        print(f"journal unavailable: {exc}", file=sys.stderr)
        return EXIT_INTERNAL
    except (CellNotFound, InvalidStatus, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 64

    try:
        out_path = args.out if args.out is not None else default_cells_path()
        cells = aggregate_to_cells_json(assignments, out_path)
    except AggregationError as exc:
        print(str(exc), file=sys.stderr)
        return 64

    non_grey = sum(1 for value in cells.values() if value is not None)
    print(
        json.dumps(
            {
                "matrix": "replay",
                "cells_filled": len(cells),
                "non_grey_cells": non_grey,
                "cycles_per_cell": args.n,
                "out": str(out_path),
                "result": "pass",
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


REPO_ROOT = Path(__file__).resolve().parents[3]


def _latest_week_dir(super_action: Path) -> Path:
    candidates = [
        (int(m.group(1)), d)
        for d in super_action.iterdir()
        if d.is_dir() and (m := re.match(r"^W(\d+)$", d.name))
    ]
    if not candidates:
        raise AggregationError(f"no W-numbered directories found in {super_action}")
    return max(candidates, key=lambda item: item[0])[1]


def default_cells_path() -> Path:
    super_action = REPO_ROOT / "docs" / "super-action"
    return _latest_week_dir(super_action) / "heatmap" / "cells.json"


def build_aggregate_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="runner aggregate")
    parser.add_argument(
        "--report",
        action="append",
        default=None,
        metavar="PATH:DEVICE:OS",
        help=(
            "Report-to-cell assignment as PATH:DEVICE:OS, e.g. "
            "'results/e2e-report-spoofed.json:Pixel 8:Android 14'. Repeatable. "
            "DEVICE/OS must match the heatmap matrix."
        ),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Output cells.json path. Defaults to the latest docs/super-action/W*/heatmap/cells.json.",
    )
    return parser


def _parse_assignment(raw: str) -> ReportAssignment:
    parts = raw.split(":")
    if len(parts) != 3:
        raise AggregationError(
            f"invalid --report {raw!r}; expected PATH:DEVICE:OS (exactly 2 colons)"
        )
    path, device, os_version = (part.strip() for part in parts)
    if not path:
        raise AggregationError(f"invalid --report {raw!r}; empty report path")
    return ReportAssignment(
        report_path=Path(path), device=device, os_version=os_version
    )


def aggregate_main(argv: list[str]) -> int:
    parser = build_aggregate_parser()
    args = parser.parse_args(argv)
    if not args.report:
        print("at least one --report PATH:DEVICE:OS is required", file=sys.stderr)
        return 64
    try:
        assignments = [_parse_assignment(raw) for raw in args.report]
        out_path = args.out if args.out is not None else default_cells_path()
        cells = aggregate_to_cells_json(assignments, out_path)
    except AggregationError as exc:
        print(str(exc), file=sys.stderr)
        return 64
    emit_json({"out": str(out_path), "cells": cells})
    return 0


USAGE = (
    "usage: runner <command> [options]\n"
    "\n"
    "commands:\n"
    "  journal list     [--status STATUS]... [--limit N] [--journal-path PATH]\n"
    "  journal seed     --config ID --run-index N --layer-set SETS [--journal-path PATH]\n"
    "  journal claim    --config ID --run-index N [--parent-issue-id ID] [--build-issue-id ID] [--probe-issue-id ID]\n"
    "  journal complete --config ID --run-index N --status STATUS [--error MSG]\n"
    "  aggregate        --report PATH:DEVICE:OS [--report ...] [--out cells.json]\n"
    "                   Reduce probe reports to per-cell weightedScore and write the\n"
    "                   heatmap cells.json that render-heatmap.py consumes (local, no deps).\n"
    "  --matrix smoke   --n N [--journal-path PATH] [--fixture PATH]\n"
    "                   Smoke-grade health check: runs N cycles of 1 cell against\n"
    "                   a fixed mock fixture (no docker, no adb). Writes a journal\n"
    "                   row per cycle with config='smoke' and status='COMPLETED'.\n"
    "  --matrix replay  --n N [--cells K] [--journal-path PATH] [--out cells.json]\n"
    "                   Replay the two committed e2e reports across the device x os\n"
    "                   matrix (no docker, no adb). Seeds N journal rows per cell,\n"
    "                   schema-validates each report, and writes a multi-cell\n"
    "                   heatmap cells.json for render-heatmap.py.\n"
    "\n"
    "options:\n"
    "  -h, --help       Show this help and exit\n"
)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in {"-h", "--help", "help"}:
        print(USAGE)
        return 0
    if argv[0] == "--matrix":
        return matrix_main(argv)
    command = argv.pop(0)
    if command == "journal":
        return journal_main(argv)
    if command == "aggregate":
        return aggregate_main(argv)
    print(f"unknown runner command: {command}", file=sys.stderr)
    print(USAGE, file=sys.stderr)
    return 64


if __name__ == "__main__":
    raise SystemExit(main())

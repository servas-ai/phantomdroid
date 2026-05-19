"""Canonical Orchestrator runner CLI."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

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


USAGE = (
    "usage: runner <command> [options]\n"
    "\n"
    "commands:\n"
    "  journal list     [--status STATUS]... [--limit N] [--journal-path PATH]\n"
    "  journal seed     --config ID --run-index N --layer-set SETS [--journal-path PATH]\n"
    "  journal claim    --config ID --run-index N [--parent-issue-id ID] [--build-issue-id ID] [--probe-issue-id ID]\n"
    "  journal complete --config ID --run-index N --status STATUS [--error MSG]\n"
    "\n"
    "options:\n"
    "  -h, --help       Show this help and exit\n"
)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in {"-h", "--help", "help"}:
        print(USAGE)
        return 0
    command = argv.pop(0)
    if command == "journal":
        return journal_main(argv)
    print(f"unknown runner command: {command}", file=sys.stderr)
    print(USAGE, file=sys.stderr)
    return 64


if __name__ == "__main__":
    raise SystemExit(main())

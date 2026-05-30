"""Unit tests for the orchestrator aggregator → heatmap cells.json seam.

Feeds the two committed e2e-report fixtures plus a seeded journal and asserts a
valid cells.json keyed in the renderer's ``"<device>|<os>"`` shape, with the
spoofed cell ~0.0 and the unspoofed cell ~0.35.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from agents.orchestrator.src.aggregator import (
    DEVICES,
    OS_VERSIONS,
    AggregationError,
    ReportAssignment,
    aggregate_cells,
    aggregate_to_cells_json,
    cell_key,
    extract_weighted_score,
    load_report,
)
from agents.orchestrator.src.journal import JournalStore

REPO_ROOT = Path(__file__).resolve().parents[1]
SPOOFED_REPORT = REPO_ROOT / "results" / "e2e-report-spoofed.json"
UNSPOOFED_REPORT = REPO_ROOT / "results" / "e2e-report-unspoofed.json"


def test_committed_report_fixtures_exist() -> None:
    assert SPOOFED_REPORT.exists(), SPOOFED_REPORT
    assert UNSPOOFED_REPORT.exists(), UNSPOOFED_REPORT


def test_extract_weighted_score_from_real_reports() -> None:
    spoofed = extract_weighted_score(load_report(SPOOFED_REPORT))
    unspoofed = extract_weighted_score(load_report(UNSPOOFED_REPORT))
    assert spoofed == pytest.approx(0.0, abs=1e-9)
    assert unspoofed == pytest.approx(0.35, abs=0.01)


def test_cell_key_matches_renderer_contract() -> None:
    assert cell_key("Pixel 8", "Android 14") == "Pixel 8|Android 14"
    assert cell_key("Pixel 9 Pro", "Android 16") == "Pixel 9 Pro|Android 16"


def test_cell_key_rejects_unknown_matrix_coordinates() -> None:
    with pytest.raises(AggregationError):
        cell_key("Galaxy S24", "Android 14")
    with pytest.raises(AggregationError):
        cell_key("Pixel 8", "Android 99")


def test_aggregate_cells_maps_two_reports_to_distinct_cells() -> None:
    cells = aggregate_cells(
        [
            ReportAssignment(SPOOFED_REPORT, "Pixel 8", "Android 14"),
            ReportAssignment(UNSPOOFED_REPORT, "Pixel 9", "Android 15"),
        ]
    )
    assert cells["Pixel 8|Android 14"] == pytest.approx(0.0, abs=1e-9)
    assert cells["Pixel 9|Android 15"] == pytest.approx(0.35, abs=0.01)
    # only cells with data are present; the renderer greys the rest
    assert len(cells) == 2


def test_aggregate_cells_averages_reports_in_same_cell() -> None:
    cells = aggregate_cells(
        [
            ReportAssignment(SPOOFED_REPORT, "Pixel 8", "Android 14"),
            ReportAssignment(UNSPOOFED_REPORT, "Pixel 8", "Android 14"),
        ]
    )
    # mean of 0.0 and ~0.346
    assert cells["Pixel 8|Android 14"] == pytest.approx(0.173, abs=0.01)


def test_aggregate_to_cells_json_writes_renderer_shape(tmp_path: Path) -> None:
    # Seed a journal alongside the aggregation (mirrors a real matrix run).
    journal = JournalStore(tmp_path / "results" / "journal.sqlite")
    journal.seed_cell("spoofed", 0, ["L0a", "L1", "L2"])
    journal.claim_cell("spoofed", 0)
    journal.complete_cell("spoofed", 0, "COMPLETED")
    journal.seed_cell("unspoofed", 0, [])
    journal.claim_cell("unspoofed", 0)
    journal.complete_cell("unspoofed", 0, "COMPLETED")

    out_path = tmp_path / "heatmap" / "cells.json"
    cells = aggregate_to_cells_json(
        [
            ReportAssignment(SPOOFED_REPORT, "Pixel 8", "Android 14"),
            ReportAssignment(UNSPOOFED_REPORT, "Pixel 9", "Android 15"),
        ],
        out_path,
    )

    assert out_path.exists()
    on_disk = json.loads(out_path.read_text(encoding="utf-8"))
    assert on_disk == cells

    # Renderer contract: flat dict, "<device>|<os>" keys, float values in [0,1].
    for key, value in on_disk.items():
        device, _, os_version = key.partition("|")
        assert device in DEVICES
        assert os_version in OS_VERSIONS
        assert isinstance(value, float)
        assert 0.0 <= value <= 1.0

    assert on_disk["Pixel 8|Android 14"] == pytest.approx(0.0, abs=1e-9)
    assert on_disk["Pixel 9|Android 15"] == pytest.approx(0.35, abs=0.01)


def test_extract_weighted_score_rejects_malformed_reports() -> None:
    with pytest.raises(AggregationError):
        extract_weighted_score({})
    with pytest.raises(AggregationError):
        extract_weighted_score({"aggregate": {}})
    with pytest.raises(AggregationError):
        extract_weighted_score({"aggregate": {"weightedScore": "0.5"}})
    # bool must not be accepted as numeric
    with pytest.raises(AggregationError):
        extract_weighted_score({"aggregate": {"weightedScore": True}})


def test_load_report_rejects_missing_file(tmp_path: Path) -> None:
    with pytest.raises(AggregationError):
        load_report(tmp_path / "nope.json")

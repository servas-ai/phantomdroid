"""Unit tests for the SPEC §4 report_validator schema gate.

Covers the happy path against the two committed e2e fixtures plus the malformed
cases the validator must reject before a report can reach a heatmap cell.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from agents.orchestrator.src.aggregator import (
    AggregationError,
    ReportAssignment,
    aggregate_cells,
    load_validated_report,
)
from agents.orchestrator.src.report_validator import (
    EXPECTED_SCHEMA_VERSION,
    ReportValidationError,
    validate_report,
)

REPO_ROOT = Path(__file__).resolve().parents[1]
SPOOFED_REPORT = REPO_ROOT / "results" / "e2e-report-spoofed.json"
UNSPOOFED_REPORT = REPO_ROOT / "results" / "e2e-report-unspoofed.json"


def _good_report() -> dict:
    return {
        "schemaVersion": EXPECTED_SCHEMA_VERSION,
        "deviceLabel": "redroid-test",
        "aggregate": {"weightedScore": 0.25, "category": "CLEAN"},
        "probes": [
            {"id": "buildprop.fingerprint", "score": 0.0},
            {"id": "root.su_detection", "score": 1.0},
        ],
    }


def test_validate_real_committed_reports() -> None:
    spoofed = validate_report(json.loads(SPOOFED_REPORT.read_text(encoding="utf-8")))
    unspoofed = validate_report(
        json.loads(UNSPOOFED_REPORT.read_text(encoding="utf-8"))
    )
    assert spoofed["schemaVersion"] == EXPECTED_SCHEMA_VERSION
    assert unspoofed["aggregate"]["category"] == "DETECTED"


def test_validate_good_synthetic_report_returns_dict() -> None:
    report = _good_report()
    result = validate_report(report)
    assert result == report
    assert isinstance(result, dict)


def test_rejects_non_object_root() -> None:
    with pytest.raises(ReportValidationError, match="report root"):
        validate_report([1, 2, 3])


def test_rejects_missing_schema_version() -> None:
    report = _good_report()
    del report["schemaVersion"]
    with pytest.raises(ReportValidationError, match="schemaVersion is missing"):
        validate_report(report)


def test_rejects_wrong_schema_version() -> None:
    report = _good_report()
    report["schemaVersion"] = "2.0"
    with pytest.raises(ReportValidationError, match="schemaVersion"):
        validate_report(report)


def test_rejects_empty_device_label() -> None:
    report = _good_report()
    report["deviceLabel"] = ""
    with pytest.raises(ReportValidationError, match="deviceLabel"):
        validate_report(report)


def test_rejects_missing_probes() -> None:
    report = _good_report()
    del report["probes"]
    with pytest.raises(ReportValidationError, match="probes is missing"):
        validate_report(report)


def test_rejects_empty_probes_list() -> None:
    report = _good_report()
    report["probes"] = []
    with pytest.raises(ReportValidationError, match="probes is empty"):
        validate_report(report)


def test_rejects_probe_without_id() -> None:
    report = _good_report()
    report["probes"][0] = {"score": 0.1}
    with pytest.raises(ReportValidationError, match=r"probes\[0\]\.id"):
        validate_report(report)


def test_rejects_probe_with_non_numeric_score() -> None:
    report = _good_report()
    report["probes"][1]["score"] = "high"
    with pytest.raises(ReportValidationError, match=r"probes\[1\]\.score"):
        validate_report(report)


def test_rejects_missing_aggregate() -> None:
    report = _good_report()
    del report["aggregate"]
    with pytest.raises(ReportValidationError, match="aggregate is missing"):
        validate_report(report)


def test_rejects_weighted_score_out_of_range() -> None:
    report = _good_report()
    report["aggregate"]["weightedScore"] = 1.5
    with pytest.raises(ReportValidationError, match="outside"):
        validate_report(report)


def test_rejects_boolean_weighted_score() -> None:
    report = _good_report()
    report["aggregate"]["weightedScore"] = True
    with pytest.raises(ReportValidationError, match="weightedScore is not numeric"):
        validate_report(report)


def test_rejects_missing_category() -> None:
    report = _good_report()
    del report["aggregate"]["category"]
    with pytest.raises(ReportValidationError, match="category is missing"):
        validate_report(report)


def test_load_validated_report_passes_real_fixture() -> None:
    report = load_validated_report(SPOOFED_REPORT)
    assert report["deviceLabel"]


def test_load_validated_report_rejects_malformed(tmp_path: Path) -> None:
    bad = copy.deepcopy(_good_report())
    del bad["aggregate"]
    bad_path = tmp_path / "bad-report.json"
    bad_path.write_text(json.dumps(bad), encoding="utf-8")
    with pytest.raises(AggregationError, match="invalid report"):
        load_validated_report(bad_path)


def test_aggregate_path_rejects_malformed_report(tmp_path: Path) -> None:
    bad = _good_report()
    bad["schemaVersion"] = "999"
    bad_path = tmp_path / "bad.json"
    bad_path.write_text(json.dumps(bad), encoding="utf-8")
    with pytest.raises(AggregationError):
        aggregate_cells([ReportAssignment(bad_path, "Pixel 8", "Android 14")])

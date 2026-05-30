"""Aggregate validated probe reports into the heatmap ``cells.json`` shape.

This closes the SPEC §4 ``aggregator.py`` seam (README module #8): it is the only
producer of ``docs/super-action/W{n}/heatmap/cells.json``, the file that
``scripts/render-heatmap.py`` already consumes.

Renderer contract (inspected from ``scripts/render-heatmap.py::load_cells`` +
``render_json``):

* ``cells.json`` is a flat JSON object.
* Each key is ``"<device>|<os>"`` (pipe-separated, e.g. ``"Pixel 8|Android 14"``).
* The recognised devices/OS versions are exactly the renderer's ``DEVICES`` and
  ``OS_VERSIONS`` constants; any other key is silently ignored by the renderer.
* Each value is the *detection score* — a ``float`` in ``[0.0, 1.0]`` (the
  renderer reads it as ``score`` and colours green ``<=0.3`` / amber ``<=0.65``
  / red otherwise). A missing key (or ``None``) renders grey ``no_data``.

The per-cell score is the probe report's ``aggregate.weightedScore`` (the
detection score the renderer thresholds were authored for). When more than one
report maps to the same cell, the cell score is the arithmetic mean of their
``weightedScore`` values.

Neither the e2e report (keyed by ``deviceLabel``) nor the journal (keyed by
``config_id``/``run_index``/``layer_set``) encodes the device×OS matrix
coordinate, so callers must supply the report→cell assignment explicitly. This
module performs no device-label parsing guesswork.

stdlib-only; no third-party dependencies.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping

from agents.orchestrator.src.report_validator import (
    ReportValidationError,
    validate_report,
)

# Mirror of scripts/render-heatmap.py constants. Kept as a local copy so this
# module stays import-free of the scripts/ tree; verified against the renderer.
DEVICES = ("Pixel 8", "Pixel 9", "Pixel 9 Pro")
OS_VERSIONS = ("Android 14", "Android 15", "Android 16")

CELL_KEY_SEP = "|"


class AggregationError(RuntimeError):
    """Raised when a report cannot be aggregated into a heatmap cell."""


def cell_key(device: str, os_version: str) -> str:
    """Return the renderer's ``"<device>|<os>"`` key for a matrix coordinate."""
    if device not in DEVICES:
        raise AggregationError(
            f"unknown device {device!r}; expected one of {DEVICES}"
        )
    if os_version not in OS_VERSIONS:
        raise AggregationError(
            f"unknown os {os_version!r}; expected one of {OS_VERSIONS}"
        )
    return f"{device}{CELL_KEY_SEP}{os_version}"


def extract_weighted_score(report: Mapping) -> float:
    """Pull ``aggregate.weightedScore`` from a probe report document.

    Mirrors the shape of ``results/e2e-report-*.json``: a top-level
    ``aggregate`` object carrying a numeric ``weightedScore``.
    """
    aggregate = report.get("aggregate")
    if not isinstance(aggregate, Mapping):
        raise AggregationError("report is missing the 'aggregate' object")
    score = aggregate.get("weightedScore")
    if not isinstance(score, (int, float)) or isinstance(score, bool):
        raise AggregationError(
            "report 'aggregate.weightedScore' is missing or not numeric"
        )
    return float(score)


def load_report(path: Path | str) -> dict:
    """Load and minimally validate a probe report JSON document."""
    path = Path(path)
    try:
        with path.open(encoding="utf-8") as fh:
            report = json.load(fh)
    except FileNotFoundError as exc:
        raise AggregationError(f"report not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise AggregationError(f"report is not valid JSON: {path}: {exc}") from exc
    if not isinstance(report, dict):
        raise AggregationError(f"report root is not an object: {path}")
    return report


def load_validated_report(path: Path | str) -> dict:
    """Load a report and schema-gate it before it can reach a heatmap cell.

    Wraps :func:`load_report` with the SPEC §4 ``report_validator``. A malformed
    report is rejected as an :class:`AggregationError` (carrying the validator's
    path-prefixed reason) so the aggregate path never writes a garbage cell.
    """
    report = load_report(path)
    try:
        return validate_report(report)
    except ReportValidationError as exc:
        raise AggregationError(f"invalid report {Path(path)}: {exc}") from exc


@dataclass(frozen=True)
class ReportAssignment:
    """Binds one probe report file to a heatmap matrix coordinate.

    ``device`` / ``os_version`` must be members of the renderer's known matrix.
    """

    report_path: Path
    device: str
    os_version: str

    def key(self) -> str:
        return cell_key(self.device, self.os_version)


def aggregate_cells(assignments: Iterable[ReportAssignment]) -> dict[str, float]:
    """Reduce report assignments to the renderer's ``{cell_key: score}`` map.

    Multiple assignments to the same cell are averaged (arithmetic mean of
    ``weightedScore``). The returned dict contains only cells with data; cells
    with no report are simply absent, which the renderer renders as grey
    ``no_data``.
    """
    accum: dict[str, list[float]] = {}
    for assignment in assignments:
        key = assignment.key()
        report = load_validated_report(assignment.report_path)
        accum.setdefault(key, []).append(extract_weighted_score(report))
    return {key: sum(scores) / len(scores) for key, scores in accum.items()}


def write_cells_json(cells: Mapping[str, float], out_path: Path | str) -> Path:
    """Write ``cells.json`` atomically (tmp + rename) in the renderer's shape."""
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(dict(cells), indent=2, sort_keys=True)
    tmp_path = out_path.with_suffix(out_path.suffix + ".tmp")
    tmp_path.write_text(payload + "\n", encoding="utf-8")
    tmp_path.replace(out_path)
    return out_path


def aggregate_to_cells_json(
    assignments: Iterable[ReportAssignment],
    out_path: Path | str,
) -> dict[str, float]:
    """Convenience: aggregate assignments and write ``cells.json``."""
    cells = aggregate_cells(assignments)
    write_cells_json(cells, out_path)
    return cells

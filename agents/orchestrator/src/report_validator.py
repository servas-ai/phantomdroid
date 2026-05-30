"""Schema-gate for e2e probe reports (SPEC §4 ``report_validator.py``).

The aggregator (``aggregator.py``) is the only producer of the heatmap
``cells.json``. SPEC §3/§9 require that *no* report contributes to persisted
state until it passes JSON-Schema-style validation. This module is that gate.

It is intentionally stdlib-only: the lab host does not carry ``jsonschema`` and
SPEC §14's dependency list is aspirational. We hand-roll the small, fixed
invariant set that the committed ``results/e2e-report-*.json`` fixtures and the
``scripts/render-heatmap.py`` thresholds were authored against:

* ``schemaVersion == "1.0"`` (SPEC §9.3 — F15 forward-compat is handled here
  when the schema is bumped, not silently).
* ``deviceLabel`` is a non-empty string.
* ``probes`` is a non-empty list; each probe is an object carrying at least an
  ``id`` (str) and a numeric ``score``.
* ``aggregate`` is an object carrying a numeric ``weightedScore`` in ``[0, 1]``
  and a non-empty ``category`` string.

Validation failures raise :class:`ReportValidationError` with a path-prefixed,
human-readable message so the aggregate path can reject a malformed report
loudly instead of writing a garbage cell (SPEC §7 ``SCHEMA_FAIL``).
"""

from __future__ import annotations

from numbers import Real
from typing import Mapping

EXPECTED_SCHEMA_VERSION = "1.0"


class ReportValidationError(ValueError):
    """Raised when an e2e report fails schema validation (SPEC §9 SCHEMA_FAIL)."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportValidationError(message)


def _is_number(value: object) -> bool:
    # bool is a subclass of int; a JSON boolean is never a valid score.
    return isinstance(value, Real) and not isinstance(value, bool)


def _validate_probe(probe: object, index: int) -> None:
    where = f"probes[{index}]"
    _require(isinstance(probe, Mapping), f"{where} is not an object")
    assert isinstance(probe, Mapping)  # narrow for type-checkers
    _require("id" in probe, f"{where}.id is missing")
    _require(
        isinstance(probe["id"], str) and bool(probe["id"]),
        f"{where}.id is not a non-empty string",
    )
    _require("score" in probe, f"{where}.score is missing")
    _require(_is_number(probe["score"]), f"{where}.score is not numeric")


def _validate_aggregate(aggregate: object) -> None:
    _require(isinstance(aggregate, Mapping), "aggregate is not an object")
    assert isinstance(aggregate, Mapping)
    _require("weightedScore" in aggregate, "aggregate.weightedScore is missing")
    score = aggregate["weightedScore"]
    _require(_is_number(score), "aggregate.weightedScore is not numeric")
    _require(
        0.0 <= float(score) <= 1.0,
        f"aggregate.weightedScore {score!r} is outside [0.0, 1.0]",
    )
    _require("category" in aggregate, "aggregate.category is missing")
    _require(
        isinstance(aggregate["category"], str) and bool(aggregate["category"]),
        "aggregate.category is not a non-empty string",
    )


def validate_report(report: object) -> dict:
    """Validate an e2e report document against the SPEC §4 invariant set.

    Returns the report (narrowed to ``dict``) on success so callers can chain
    ``extract_weighted_score(validate_report(load_report(path)))``. Raises
    :class:`ReportValidationError` on the first violation.
    """
    _require(isinstance(report, Mapping), "report root is not an object")
    assert isinstance(report, Mapping)

    _require("schemaVersion" in report, "schemaVersion is missing")
    _require(
        report["schemaVersion"] == EXPECTED_SCHEMA_VERSION,
        f"schemaVersion {report['schemaVersion']!r} != {EXPECTED_SCHEMA_VERSION!r}",
    )

    _require("deviceLabel" in report, "deviceLabel is missing")
    _require(
        isinstance(report["deviceLabel"], str) and bool(report["deviceLabel"]),
        "deviceLabel is not a non-empty string",
    )

    _require("probes" in report, "probes is missing")
    probes = report["probes"]
    _require(isinstance(probes, list), "probes is not a list")
    _require(len(probes) > 0, "probes is empty")
    for index, probe in enumerate(probes):
        _validate_probe(probe, index)

    _require("aggregate" in report, "aggregate is missing")
    _validate_aggregate(report["aggregate"])

    return dict(report)

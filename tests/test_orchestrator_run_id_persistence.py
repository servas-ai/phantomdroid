"""Unit tests for orchestrator run_id + persistence (SPEC §6, §4 persistence)."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src.run_id import (  # noqa: E402
    compute_run_id, canonical_json, config_id_from_layers,
)
from agents.orchestrator.src.persistence import (  # noqa: E402
    persist_report, PersistenceCollision, ReportInvalid,
)

_VALID_REPORT = {
    "schemaVersion": "1.0",
    "deviceLabel": "test-device",
    "aggregate": {"weightedScore": 0.15, "criticalFailures": 0, "category": "SUSPICIOUS"},
    "probes": [{"id": "buildprop.fingerprint", "score": 0.0}],
}


# ---- run_id ----

def test_run_id_is_deterministic():
    m = {"layers": ["L0a", "L1"], "label": "x"}
    a = compute_run_id(m, "abc123", 0)
    b = compute_run_id(m, "abc123", 0)
    assert a == b
    assert len(a) == 16 and a == a.lower()


def test_run_id_changes_with_inputs():
    m = {"layers": ["L0a"]}
    base = compute_run_id(m, "sha", 0)
    assert compute_run_id(m, "sha", 1) != base          # run_index
    assert compute_run_id(m, "other", 0) != base        # apk sha
    assert compute_run_id({"layers": ["L1"]}, "sha", 0) != base  # manifest


def test_run_id_rejects_negative_index():
    with pytest.raises(ValueError):
        compute_run_id({"a": 1}, "sha", -1)


def test_canonical_json_is_key_order_stable():
    assert canonical_json({"b": 1, "a": 2}) == canonical_json({"a": 2, "b": 1})


def test_config_id_from_layers():
    assert config_id_from_layers(["L0a", "L1", "L2"]) == "L0a-L1-L2"
    with pytest.raises(ValueError):
        config_id_from_layers([])


# ---- persistence ----

def test_persist_writes_atomically(tmp_path):
    p = persist_report(_VALID_REPORT, "L0a", "abcd1234", runs_root=tmp_path)
    assert p.exists()
    assert p == tmp_path / "L0a" / "abcd1234.json"
    assert json.loads(p.read_text())["aggregate"]["category"] == "SUSPICIOUS"
    # no leftover tmp files
    assert not list((tmp_path / "L0a").glob(".*tmp"))


def test_persist_refuses_overwrite(tmp_path):
    persist_report(_VALID_REPORT, "L0a", "dup", runs_root=tmp_path)
    with pytest.raises(PersistenceCollision):
        persist_report(_VALID_REPORT, "L0a", "dup", runs_root=tmp_path)


def test_persist_rejects_invalid_report(tmp_path):
    bad = {"schemaVersion": "1.0", "aggregate": {"category": "X"}}  # missing weightedScore
    with pytest.raises(ReportInvalid):
        persist_report(bad, "L0a", "bad", runs_root=tmp_path)

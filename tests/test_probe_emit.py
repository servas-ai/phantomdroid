"""Unit tests for apps/detector-lab/scripts/probe_emit.py — schema-v2 record builder (CLO-21 gate)."""
import argparse
import importlib.util
import json
from pathlib import Path

import pytest

_ROOT = Path(__file__).resolve().parents[1]
_PE_PATH = _ROOT / "apps/detector-lab/scripts/probe_emit.py"
_SCHEMA = json.loads((_ROOT / "shared/probe-schema.v2.json").read_text())

# import probe_emit.py as a module
_spec = importlib.util.spec_from_file_location("probe_emit", _PE_PATH)
probe_emit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe_emit)


def _args(**over):
    base = dict(probe_id="runtime.installed_apps", probe_name="InstalledAppsProbe",
                category="runtime", layer="L0a", score=0.0, runtime_ms=12,
                sample_count=1, seed_ms=0, confidence=0.95, evidence=["pkgs=96"],
                repro=None, notes=None)
    base.update(over)
    return argparse.Namespace(**base)


def _validate(record):
    import jsonschema
    jsonschema.validate(record, _SCHEMA)


def test_build_record_has_all_required_fields_and_schema_version():
    rec = probe_emit.build_record(_args(), {"out": "raw"})
    for k in _SCHEMA["required"]:
        assert k in rec, f"required field {k} missing"
    assert rec["schema_version"] == "2.0"


def test_build_record_passes_schema_validation():
    _validate(probe_emit.build_record(_args(), {"out": "raw"}))


def test_no_additional_properties_leak():
    rec = probe_emit.build_record(_args(), {"out": "raw"})
    allowed = set(_SCHEMA["properties"].keys())
    assert set(rec).issubset(allowed), f"unexpected keys: {set(rec) - allowed}"


def test_invalid_category_is_rejected():
    with pytest.raises(SystemExit):
        probe_emit.build_record(_args(category="not_a_category"), {"out": "raw"})


def test_invalid_layer_is_rejected():
    with pytest.raises(SystemExit):
        probe_emit.build_record(_args(layer="L99"), {"out": "raw"})


def test_reference_fixture_matches_schema():
    fixture = json.loads((_ROOT / "apps/detector-lab/examples/probe-result.fixture.json").read_text())
    _validate(fixture)
    assert fixture["schema_version"] == "2.0"

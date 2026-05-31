"""Unit tests for orchestrator config_loader (SPEC §5 manifest, §12 positive+negative)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src.config_loader import (  # noqa: E402
    validate_manifest, load_manifest, ManifestError,
)

_VALID = {
    "schema_version": "runner.v1",
    "config_id": "L0a-L1",
    "container_image_hash": "sha256:e6f799d56b9a",
    "compose_file": "agents/stability/stack/compose/L1.compose.yml",
    "seccomp_profile": "agents/stability/stack/seccomp/redroid-seccomp.json",
    "target_runs": 30,
}


def test_valid_manifest_passes():
    assert validate_manifest(dict(_VALID))["config_id"] == "L0a-L1"


def test_unknown_key_refused():
    bad = dict(_VALID, surprise="x")
    with pytest.raises(ManifestError, match="unknown manifest keys"):
        validate_manifest(bad)


def test_missing_required_key():
    bad = dict(_VALID)
    del bad["container_image_hash"]
    with pytest.raises(ManifestError, match="required key missing"):
        validate_manifest(bad)


def test_bad_schema_version():
    with pytest.raises(ManifestError, match="schema_version"):
        validate_manifest(dict(_VALID, schema_version="v2"))


def test_bad_image_hash_rejected():
    with pytest.raises(ManifestError, match="container_image_hash"):
        validate_manifest(dict(_VALID, container_image_hash="latest"))


def test_bad_config_id_rejected():
    with pytest.raises(ManifestError, match="config_id"):
        validate_manifest(dict(_VALID, config_id="bad id!"))


def test_target_runs_bounds():
    with pytest.raises(ManifestError, match="target_runs"):
        validate_manifest(dict(_VALID, target_runs=0))
    with pytest.raises(ManifestError, match="target_runs"):
        validate_manifest(dict(_VALID, target_runs=10001))


def test_load_real_example_manifest():
    p = Path(__file__).resolve().parents[1] / "experiments/manifests/L0a-L1.yml"
    if p.is_file():
        m = load_manifest(p)
        assert m["schema_version"] == "runner.v1"

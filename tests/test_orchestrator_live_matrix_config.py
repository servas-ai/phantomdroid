"""Unit tests for live_matrix --config (manifest-driven) CLI wiring + run_id derivation."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src import live_matrix  # noqa: E402
from agents.orchestrator.src.config_loader import load_manifest  # noqa: E402
from agents.orchestrator.src.run_id import compute_run_id  # noqa: E402

_MANIFEST = Path(__file__).resolve().parents[1] / "experiments/manifests/L0a-L1.yml"


def test_config_without_container_errors():
    # argparse p.error -> SystemExit(2)
    with pytest.raises(SystemExit):
        live_matrix.main(["--config", str(_MANIFEST)])


def test_no_work_errors():
    with pytest.raises(SystemExit):
        live_matrix.main([])


def test_manifest_run_id_is_canonical_and_deterministic():
    if not _MANIFEST.is_file():
        pytest.skip("example manifest absent")
    m = load_manifest(_MANIFEST)
    sha = m.get("detector_lab_apk_hash") or m.get("container_image_hash")
    a = compute_run_id(m, sha, 0)
    b = compute_run_id(m, sha, 0)
    assert a == b and len(a) == 16
    # run_index changes the id
    assert compute_run_id(m, sha, 1) != a

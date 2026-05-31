"""Unit test for live_matrix resume index (SPEC §7 OOM-resume: skip COMPLETED)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src.journal import JournalStore  # noqa: E402
from agents.orchestrator.src.live_matrix import _completed_index  # noqa: E402


def test_completed_index_only_lists_completed(tmp_path):
    j = JournalStore(path=tmp_path / "j.sqlite")
    # COMPLETED cell -> should appear
    j.seed_cell(config_id="L0a", run_index=0, layer_set=["L0a"])
    j.claim_cell(config_id="L0a", run_index=0)
    j.complete_cell(config_id="L0a", run_index=0, status="COMPLETED")
    # PENDING cell -> should NOT appear
    j.seed_cell(config_id="L0a-L1", run_index=0, layer_set=["L0a", "L1"])
    # FAILED cell -> should NOT appear (must be retried on resume)
    j.seed_cell(config_id="L2", run_index=0, layer_set=["L2"])
    j.claim_cell(config_id="L2", run_index=0)
    j.complete_cell(config_id="L2", run_index=0, status="FAILED", error="boom")

    done = _completed_index(j)
    assert ("L0a", 0) in done
    assert ("L0a-L1", 0) not in done
    assert ("L2", 0) not in done

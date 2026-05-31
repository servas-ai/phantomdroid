"""Unit tests for scripts/auto-status-closeout.py marker substitution (STATUS.md auto-scoreboard)."""
import importlib.util
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("auto_status", _ROOT / "scripts/auto-status-closeout.py")
asc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(asc)


def test_unknown_marker_left_untouched():
    text = "x<!--AUTO:does_not_exist-->OLD<!--/AUTO-->y"
    new, changes = asc.substitute_markers(text)
    assert new == text and changes == []


def test_known_marker_wires_to_metric_and_preserves_surroundings():
    # compose_count is deterministic (counts repo compose files)
    expected = asc.metric_compose_count()
    text = "before <!--AUTO:compose_count-->STALE<!--/AUTO--> after"
    new, changes = asc.substitute_markers(text)
    assert f"<!--AUTO:compose_count-->{expected}<!--/AUTO-->" in new
    assert new.startswith("before ") and new.endswith(" after")
    assert "compose_count" in changes  # STALE != expected


def test_idempotent_second_pass_no_change():
    text = "<!--AUTO:compose_count-->STALE<!--/AUTO-->"
    once, _ = asc.substitute_markers(text)
    twice, changes2 = asc.substitute_markers(once)
    assert twice == once and changes2 == []  # stable: re-running changes nothing


def test_multiple_markers_all_substituted():
    text = "<!--AUTO:compose_count-->a<!--/AUTO--> | <!--AUTO:runbook_count-->b<!--/AUTO-->"
    new, _ = asc.substitute_markers(text)
    assert f"<!--AUTO:compose_count-->{asc.metric_compose_count()}<!--/AUTO-->" in new
    assert f"<!--AUTO:runbook_count-->{asc.metric_runbook_count()}<!--/AUTO-->" in new

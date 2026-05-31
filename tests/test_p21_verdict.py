"""Unit tests for the P21 harness verdict logic (scripts/p21/run-all-checks.py).

extract_verdict + verdict_matches_expected are pure functions — the core of the real-app
verdict matrix (story-05 / p21/report.json). Tested here without any device/adb.
"""
import importlib.util
from pathlib import Path

import pytest

_ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("p21_checks", _ROOT / "scripts/p21/run-all-checks.py")
p21 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(p21)

TARGET = "com.byxiaorun.detector"


def _v(nodes, focused, target=TARGET):
    return p21.extract_verdict(nodes, focused, target)[0]


# ---- extract_verdict decision matrix ----

def test_launcher_focus_is_crash():
    assert _v([], next(iter(p21.LAUNCHER_PKGS))) == "CRASH"


def test_no_focus_is_unknown():
    assert _v([("text", "anything")], None) == "UNKNOWN"


def test_system_overlay_is_unknown():
    assert _v([], next(iter(p21.SYSTEM_OVERLAY_PKGS))) == "UNKNOWN"


def test_wrong_pkg_focus_is_crash():
    assert _v([], "com.some.other.app") == "CRASH"


def test_on_target_fail_keyword_is_fail():
    # "redroid" is a FAIL keyword — an emulator tell leaking in the UI
    assert _v([("text", "Model: redroid12_x86_64_only")], TARGET) == "FAIL"


def test_on_target_pass_keyword_is_pass():
    assert _v([("text", "Result: not rooted")], TARGET) == "PASS"


def test_on_target_both_keywords_is_unknown():
    assert _v([("a", "not rooted"), ("b", "redroid")], TARGET) == "UNKNOWN"


def test_on_target_no_keywords_is_unknown():
    assert _v([("text", "neutral device info")], TARGET) == "UNKNOWN"


# ---- verdict_matches_expected ----

def test_matches_expected_pass():
    assert p21.verdict_matches_expected("PASS", "PASS") is True
    assert p21.verdict_matches_expected("FAIL", "PASS") is False


def test_matches_expected_fail_accepts_crash():
    # an expected-FAIL cell is satisfied by FAIL or CRASH
    assert p21.verdict_matches_expected("FAIL", "FAIL") is True
    assert p21.verdict_matches_expected("CRASH", "FAIL-detected") is True


def test_matches_expected_none_and_unknown():
    assert p21.verdict_matches_expected("PASS", None) is None
    assert p21.verdict_matches_expected("UNKNOWN", "PASS") is False


# ---- substring-overlap regression: "rooted" ⊂ "not rooted" ----

def test_not_rooted_pass_not_neutralised_by_rooted_substring():
    # "not rooted" must score PASS, not UNKNOWN (the "rooted" FAIL substring is masked)
    assert _v([("text", "Device status: not rooted")], TARGET) == "PASS"

def test_is_rooted_alone_is_fail():
    assert _v([("text", "This device is rooted")], TARGET) == "FAIL"

def test_no_root_pass_not_neutralised():
    # "no root" is PASS; ensure it isn't undone by any fail substring
    assert _v([("text", "no root detected")], TARGET) == "PASS"

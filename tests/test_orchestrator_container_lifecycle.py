"""Unit tests for container_lifecycle hardening + privileged-refusal (SPEC §4/§7)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src.container_lifecycle import (  # noqa: E402
    preflight, harden_service, harden_compose, is_hardened,
    PrivilegedRefused, EXIT_PRIVILEGED_REFUSED, up, down, main,
)

_PRIV = {"services": {"redroid": {"image": "redroid/redroid", "privileged": True}}}
_PLAIN = {"services": {"redroid": {"image": "redroid/redroid"}}}


def test_preflight_refuses_privileged():
    with pytest.raises(PrivilegedRefused):
        preflight(_PRIV)


def test_preflight_accepts_non_privileged():
    preflight(_PLAIN)  # no raise


def test_exit_code_is_78():
    assert EXIT_PRIVILEGED_REFUSED == 78


def test_harden_service_injects_invariants():
    h = harden_service({"image": "x", "privileged": True})
    assert "privileged" not in h
    assert h["cap_drop"] == ["ALL"]
    assert "SYS_ADMIN" in h["cap_add"]
    assert "no-new-privileges:true" in h["security_opt"]
    assert any(o.startswith("seccomp=") for o in h["security_opt"])
    assert is_hardened(h)


def test_harden_compose_refuses_then_would_harden():
    # authored privileged is refused at preflight (SPEC §7) — fail closed
    with pytest.raises(PrivilegedRefused):
        harden_compose(_PRIV)
    # a plain compose hardens cleanly
    out = harden_compose(_PLAIN)
    assert is_hardened(out["services"]["redroid"])


def test_up_down_dry_run_returns_argv_without_executing():
    argv = up("compose.yml", "proj-1234", dry_run=True)
    assert argv[:3] == ["docker", "compose", "-p"] and "--privileged" not in argv
    assert "up" in argv
    assert down("proj-1234", dry_run=True)[-1] == "-v"


def test_main_preflight_real_L0a_compose_or_synthetic(tmp_path):
    # synthetic privileged compose -> exit 78
    f = tmp_path / "priv.yml"
    f.write_text("services:\n  redroid:\n    image: redroid/redroid\n    privileged: true\n")
    assert main(["--preflight", str(f)]) == EXIT_PRIVILEGED_REFUSED
    # synthetic hardened compose -> exit 0
    g = tmp_path / "ok.yml"
    g.write_text("services:\n  redroid:\n    image: redroid/redroid\n    cap_drop: [ALL]\n")
    assert main(["--preflight", str(g)]) == 0


def test_build_hardened_run_argv_is_never_privileged():
    from agents.orchestrator.src.container_lifecycle import (
        build_hardened_run_argv, HARDENED_SECCOMP, DEVICE_CGROUP_RULES,
    )
    argv = build_hardened_run_argv("redroid/redroid", "c1", 15599, "/tmp/d")
    assert "--privileged" not in argv
    assert "--cap-drop" in argv and "ALL" in argv
    assert any("seccomp=" in a and "l0b" in a for a in argv)
    assert "apparmor=unconfined" in argv and "no-new-privileges" in argv
    # device access via cgroup rule, not privileged
    assert argv.count("--device-cgroup-rule") == len(DEVICE_CGROUP_RULES)
    assert "127.0.0.1:15599:5555" in argv

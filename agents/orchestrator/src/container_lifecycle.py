# agents/orchestrator/src/container_lifecycle.py
#
# Hardened container lifecycle (SPEC §4 Hardening, §7 exit-78 privileged-refusal,
# §10 unique compose project name).
#
# SCOPE NOTE (B4, owner-gated): this module produces the HARDENED config and
# REFUSES privileged — that policy logic is implemented and unit-tested here.
# Actually BOOTING a hardened (cap_drop+seccomp, non-privileged) container is
# kernel-blocked on binderfs-only hosts (proven 2026-05-30: only privileged
# self-mount boots ReDroid there) and the privileged-vs-hardened posture is an
# owner decision. So `up()/down()` shell out to `docker compose` but are NOT
# exercised live here; the preflight + hardening (the safety-bearing logic) ARE.

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

EXIT_PRIVILEGED_REFUSED = 78  # SPEC §8 exit codes

# Minimal cap set for ReDroid under cap_drop:[ALL] (SPEC §4 mandates SYS_ADMIN;
# the rest are the documented narrow L0a profile needed for the Android init stack).
DEFAULT_CAP_ADD = [
    "SYS_ADMIN", "SYS_NICE", "SYS_RESOURCE", "SYS_PTRACE", "MKNOD",
    "SETUID", "SETGID", "SETPCAP", "NET_BIND_SERVICE",
    "DAC_OVERRIDE", "DAC_READ_SEARCH", "FOWNER", "FSETID", "KILL", "AUDIT_WRITE",
]
DEFAULT_SECCOMP = "agents/stability/stack/seccomp/redroid-seccomp.json"
# The purpose-built profile that boots a NON-privileged hardened ReDroid on binderfs-only
# kernels (proven 2026-05-31: base redroid-seccomp.json exits the container; l0b boots).
HARDENED_SECCOMP = "agents/stability/stack/seccomp/redroid-seccomp-l0b.json"
# Device access WITHOUT --privileged (the missing piece for binderfs-only boot, proven 2026-05-31).
DEVICE_CGROUP_RULES = ["c *:* rmw", "b *:* rmw"]
# Cap set that actually boots ReDroid non-privileged (proven 2026-05-31: the narrow 15-cap
# DEFAULT_CAP_ADD leaves zygote in a restart loop; this broader-but-bounded set boots fully).
# Still excludes the dangerous caps (SYS_RAWIO, SYS_PACCT, MAC_ADMIN/OVERRIDE, etc.) → not privileged.
HARDENED_CAP_ADD = [
    "SYS_ADMIN", "SYS_NICE", "SYS_RESOURCE", "SYS_PTRACE", "SYS_BOOT", "SYS_TIME",
    "SYS_CHROOT", "SYS_MODULE", "MKNOD", "SETUID", "SETGID", "SETPCAP", "SETFCAP",
    "CHOWN", "NET_ADMIN", "NET_RAW", "NET_BIND_SERVICE", "DAC_OVERRIDE", "DAC_READ_SEARCH",
    "FOWNER", "FSETID", "KILL", "AUDIT_WRITE", "IPC_LOCK", "WAKE_ALARM", "BLOCK_SUSPEND",
]


class PrivilegedRefused(RuntimeError):
    """SPEC §7: an authored compose declaring `privileged: true` is refused (exit 78)."""


def _services(compose: dict) -> dict:
    svcs = compose.get("services")
    if not isinstance(svcs, dict):
        raise ValueError("compose has no 'services' mapping")
    return svcs


def preflight(compose: dict) -> None:
    """Refuse if ANY service declares privileged:true (SPEC §4 hardening / §7)."""
    for name, svc in _services(compose).items():
        if isinstance(svc, dict) and svc.get("privileged") is True:
            raise PrivilegedRefused(
                f"service '{name}' declares privileged:true — refused (exit {EXIT_PRIVILEGED_REFUSED})"
            )


def harden_service(service: dict, seccomp_profile: str = DEFAULT_SECCOMP,
                   cap_add: list[str] | None = None) -> dict:
    """Return a hardened copy: no privileged, cap_drop:[ALL]+narrow cap_add, seccomp, no-new-privileges."""
    s = dict(service)
    s.pop("privileged", None)
    s["cap_drop"] = ["ALL"]
    s["cap_add"] = list(cap_add if cap_add is not None else DEFAULT_CAP_ADD)
    opts = [o for o in s.get("security_opt", []) if not str(o).startswith(("seccomp", "no-new-privileges"))]
    opts.append("no-new-privileges:true")
    opts.append(f"seccomp={seccomp_profile}")
    s["security_opt"] = opts
    return s


def harden_compose(compose: dict, seccomp_profile: str = DEFAULT_SECCOMP) -> dict:
    """Preflight (refuse authored privileged), then harden every service."""
    preflight(compose)
    out = dict(compose)
    out["services"] = {n: harden_service(s, seccomp_profile) if isinstance(s, dict) else s
                       for n, s in _services(compose).items()}
    return out


def is_hardened(service: dict) -> bool:
    """True iff the service meets the SPEC §4 hardening invariants."""
    if service.get("privileged") is True:
        return False
    if service.get("cap_drop") != ["ALL"]:
        return False
    opts = service.get("security_opt", [])
    has_nnp = any(str(o).replace(" ", "") == "no-new-privileges:true" for o in opts)
    has_seccomp = any(str(o).startswith("seccomp=") for o in opts)
    return has_nnp and has_seccomp and "SYS_ADMIN" in service.get("cap_add", [])


def build_hardened_run_argv(
    image: str, name: str, host_port: int, data_dir: str,
    seccomp: str = HARDENED_SECCOMP, cap_add: list[str] | None = None,
    cmd: list[str] | None = None,
) -> list[str]:
    """Proven NON-privileged hardened `docker run` argv (binderfs-only kernel, 2026-05-31).

    Recipe (no --privileged): cap_drop ALL + bounded cap_add + device-cgroup-rules for device
    access + l0b seccomp + apparmor=unconfined + no-new-privileges. This boots ReDroid 12 fully
    (boot_completed=1) without the F37 host-root-escape of `privileged:true`.
    """
    import os
    # docker requires an ABSOLUTE path for a seccomp profile file (a relative path is
    # silently mishandled and the container fails to boot — proven 2026-05-31).
    seccomp_abs = os.path.abspath(seccomp)
    argv = ["docker", "run", "-itd", "--name", name, "--cap-drop", "ALL"]
    for c in (cap_add if cap_add is not None else HARDENED_CAP_ADD):
        argv += ["--cap-add", c]
    for rule in DEVICE_CGROUP_RULES:
        argv += ["--device-cgroup-rule", rule]
    argv += [
        "--security-opt", f"seccomp={seccomp_abs}",
        "--security-opt", "apparmor=unconfined",
        "--security-opt", "no-new-privileges",
        "-v", f"{data_dir}:/data",
        "-p", f"127.0.0.1:{host_port}:5555",
        image,
    ]
    argv += (cmd if cmd is not None
             else ["androidboot.hardware=redroid", "androidboot.redroid_gpu_mode=guest"])
    assert "--privileged" not in argv  # invariant: hardened path is NEVER privileged
    return argv


def up(compose_path: str, project: str, *, dry_run: bool = True) -> list[str]:
    """Bring the stack up. Returns the argv. dry_run=True (default) does NOT execute
    (live hardened boot is B4-gated). dry_run=False shells to `docker compose`."""
    argv = ["docker", "compose", "-p", project, "-f", compose_path, "up", "-d"]
    if not dry_run:
        subprocess.run(argv, check=True, timeout=180)
    return argv


def down(project: str, *, dry_run: bool = True) -> list[str]:
    argv = ["docker", "compose", "-p", project, "down", "-v"]
    if not dry_run:
        subprocess.run(argv, check=True, timeout=120)
    return argv


def main(argv: list[str] | None = None) -> int:
    """Preflight a compose file; exit 78 on privileged-refusal (SPEC §7/§8)."""
    import argparse
    import yaml  # available; compose files are nested YAML
    p = argparse.ArgumentParser(prog="container_lifecycle")
    p.add_argument("--preflight", required=True, help="compose YAML to preflight")
    args = p.parse_args(argv)
    compose = yaml.safe_load(Path(args.preflight).read_text())
    try:
        preflight(compose)
    except PrivilegedRefused as exc:
        print(f"REFUSED: {exc}")
        return EXIT_PRIVILEGED_REFUSED
    print(f"OK: {args.preflight} declares no privileged:true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

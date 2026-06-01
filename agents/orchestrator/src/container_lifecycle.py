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

import hashlib
import re
import subprocess
from pathlib import Path
from typing import Any

EXIT_PRIVILEGED_REFUSED = 78  # SPEC §8 exit codes

# Repo root resolved from this module's location: src -> orchestrator -> agents -> <root>.
# Robust regardless of CWD (the relative DEFAULT_SECCOMP/HARDENED_SECCOMP paths are
# resolved against CWD by docker, but the *pin verification* must not depend on CWD).
_REPO_ROOT = Path(__file__).resolve().parents[3]
# Pin manifest that carries the production seccomp sha256 (image-pins-v1).
IMAGE_PINS = "agents/stability/stack/image-pins.yml"

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
# BROAD baseline: grants rwm to ALL char + ALL block devices. Proven to boot, but an adversarial
# validator flagged it as over-broad. Kept as the safe fallback default.
DEVICE_CGROUP_RULES = ["c *:* rmw", "b *:* rmw"]
# NARROWED set, empirically derived 2026-05-31 by enumerating EVERY device node the Magisk-rooted
# ReDroid 12 container actually creates under the broad rules (container `dcg-base`, port 5771):
#   maj 1   mem        -> /dev/null,zero,full,random,urandom,kmsg
#   maj 5   tty        -> /dev/tty,/dev/console(ptmx area),/dev/pts/ptmx
#   maj 10  misc       -> misc subsystem (fuse=10,229 on host; defensive include)
#   maj 136 pts        -> /dev/console (pseudo-terminal slave) + pts slaves
#   maj 239 binder     -> /dev/binderfs/{binder,binder-control,hwbinder,vndbinder}
# NO block-device nodes exist in the container (/data is a bind mount), so NO `b` rule is needed.
# CAVEAT: 239 is the kernel's DYNAMICALLY-allocated binder major. It is allocated by the host
# kernel at binderfs init and is stable per host-boot; the container (which self-mounts binderfs
# on this binderfs-only kernel) reuses the SAME major rather than getting a fresh one — proven by
# observing maj 239 both on host /dev/binderfs and inside the container. If the host kernel ever
# reallocates binder to a different major, this rule must be regenerated. Proven live 2026-05-31
# (container `dcg-narrow`, port 5773): boot_completed=1 AND `su -c id` -> uid=0 with this set.
MINIMAL_DEVICE_CGROUP_RULES = [
    "c 1:* rmw",    # mem: null, zero, full, random, urandom, kmsg
    "c 5:* rmw",    # tty, console, ptmx
    "c 10:* rmw",   # misc (fuse etc.)
    "c 136:* rmw",  # pts slaves
    "c 239:* rmw",  # binder (dynamically-allocated major, discovered live)
]
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


class SeccompPinDriftError(RuntimeError):
    """The production seccomp profile's sha256 != the pin in image-pins.yml.

    Mirrors the exit-78 / refuse-privileged posture: an unenforced pin cannot catch
    tampering or an unapproved BPF edit, so a drift here MUST hard-block the boot.
    """


def _read_pinned_seccomp_sha256(pins_path: Path) -> str:
    """Return seccomp_l0b_production.file_sha256 from image-pins.yml.

    Uses PyYAML when importable (the module already relies on yaml in main()).
    Falls back to a robust single-line parse if yaml is unavailable, so the
    safety-bearing pin check never silently degrades to "pass".
    """
    if not pins_path.is_file():
        raise SeccompPinDriftError(
            f"refuse to boot: pin manifest not found at {pins_path}; "
            "cannot verify production seccomp profile against its pin"
        )
    text = pins_path.read_text()
    try:
        import yaml  # available; main() already imports it
        pins = yaml.safe_load(text) or {}
        block = pins.get("seccomp_l0b_production")
        if not isinstance(block, dict) or "file_sha256" not in block:
            raise SeccompPinDriftError(
                "refuse to boot: 'seccomp_l0b_production.file_sha256' missing from "
                f"{pins_path}; the production seccomp profile is not pinned — "
                "file a pin-update mutation"
            )
        sha = str(block["file_sha256"]).strip()
    except ImportError:
        # Robust fallback: find the file_sha256 inside the seccomp_l0b_production block.
        block_re = re.search(
            r"^seccomp_l0b_production:\s*$.*?^(?=\S)",
            text, re.MULTILINE | re.DOTALL,
        )
        scope = block_re.group(0) if block_re else text
        m = re.search(r"^\s*file_sha256:\s*\"?([0-9a-fA-F]{64})\"?", scope, re.MULTILINE)
        if not m:
            raise SeccompPinDriftError(
                "refuse to boot: 'seccomp_l0b_production.file_sha256' missing from "
                f"{pins_path}; the production seccomp profile is not pinned — "
                "file a pin-update mutation"
            )
        sha = m.group(1)
    if not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
        raise SeccompPinDriftError(
            f"refuse to boot: pinned seccomp sha256 in {pins_path} is not a valid "
            f"64-hex digest: {sha!r}"
        )
    return sha.lower()


def verify_hardened_seccomp_pin(
    pins_path: str | None = None,
    seccomp_path: str = HARDENED_SECCOMP,
) -> None:
    """Verify the on-disk production seccomp profile matches its pinned sha256.

    The pin (`seccomp_l0b_production.file_sha256` in image-pins.yml) is the
    authoritative tamper-detector for the PINNED PRODUCTION BPF profile wired as
    HARDENED_SECCOMP. This makes the declarative `seccomp-l0b-sha256-match` check
    actually enforced in code: it is called at the boot chokepoint so a drifted
    profile refuses to boot (mirroring SPEC §7 exit-78 / refuse-privileged).

    Args:
        pins_path: image-pins.yml path. Default: resolved relative to the repo root.
        seccomp_path: seccomp profile to hash. Default: HARDENED_SECCOMP.

    Raises:
        SeccompPinDriftError: pin missing, profile missing, or sha256 mismatch.
    Returns:
        None on a clean match.
    """
    pins = Path(pins_path) if pins_path is not None else (_REPO_ROOT / IMAGE_PINS)
    if not pins.is_absolute():
        pins = (_REPO_ROOT / pins) if not pins.exists() else pins

    profile = Path(seccomp_path)
    if not profile.is_absolute() and not profile.exists():
        profile = _REPO_ROOT / seccomp_path
    if not profile.is_file():
        raise SeccompPinDriftError(
            f"refuse to boot: production seccomp profile not found at {profile}; "
            "cannot verify it against its pin — file a pin-update mutation"
        )

    pinned = _read_pinned_seccomp_sha256(pins)
    actual = hashlib.sha256(profile.read_bytes()).hexdigest()
    if actual != pinned:
        raise SeccompPinDriftError(
            "refuse to boot: production seccomp profile drifted from pin; "
            "file a pin-update mutation "
            f"(profile={profile}, expected sha256={pinned}, actual sha256={actual})"
        )
    return None


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
    device_cgroup_rules: list[str] | None = None,
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
    for rule in (device_cgroup_rules if device_cgroup_rules is not None
                 else MINIMAL_DEVICE_CGROUP_RULES):
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
        # Boot chokepoint: enforce the production seccomp pin before any real boot.
        # A drifted/tampered profile raises SeccompPinDriftError and aborts the boot.
        verify_hardened_seccomp_pin()
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
    p.add_argument("--preflight", help="compose YAML to preflight")
    p.add_argument(
        "--verify-seccomp-pin", action="store_true",
        help="verify the production seccomp profile matches its image-pins.yml sha256 "
             "(exit 78 on drift); intended to gate a real boot",
    )
    args = p.parse_args(argv)

    if args.verify_seccomp_pin:
        try:
            verify_hardened_seccomp_pin()
        except SeccompPinDriftError as exc:
            print(f"REFUSED: {exc}")
            return EXIT_PRIVILEGED_REFUSED
        print("OK: production seccomp profile matches image-pins.yml pin")

    if args.preflight:
        compose = yaml.safe_load(Path(args.preflight).read_text())
        try:
            preflight(compose)
        except PrivilegedRefused as exc:
            print(f"REFUSED: {exc}")
            return EXIT_PRIVILEGED_REFUSED
        print(f"OK: {args.preflight} declares no privileged:true")
    elif not args.verify_seccomp_pin:
        p.error("one of --preflight or --verify-seccomp-pin is required")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

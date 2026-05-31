# Plan item — container_lifecycle.py (SPEC §4 hardening + §7 privileged-refusal) — implemented + E2E

This is the orchestrator's LAST missing SPEC §4 module. Previously coarsely filed under B4 as
"fully owner-gated"; in fact only the LIVE hardened boot is gated — the safety-bearing policy logic
(refuse privileged, inject cap_drop+seccomp+no-new-privileges) is implementable + testable and is now done.

`agents/orchestrator/src/container_lifecycle.py`:
- `preflight(compose)` — refuses any service with `privileged: true` → `PrivilegedRefused`; `main --preflight` exits **78** (SPEC §8 policy-refused).
- `harden_service` / `harden_compose` — strip privileged; inject `cap_drop:[ALL]`, narrow `cap_add` (incl. SYS_ADMIN per SPEC §4), `security_opt: [no-new-privileges:true, seccomp=redroid-seccomp.json]`.
- `is_hardened` invariant check; `up/down` (dry_run default — live boot NOT exercised, B4).

E2E (`preflight-real-compose.txt`): the REAL committed compose files preflight clean —
`L0a.yml` exit 0, `L1.compose.yml` exit 0 (both already use hardened anchors, no `privileged`);
a synthetic `privileged:true` compose is REFUSED with exit **78**. 7 unit tests; orchestrator suite **65→72**.

## Still B4-gated (the LIVE part only)
Actually BOOTING the hardened (non-privileged) container is blocked: on binderfs-only kernels only the
privileged self-mount boots ReDroid (proven 2026-05-30). Resolving needs the owner posture decision
(accept privileged for the lab, OR a board-reviewed narrowed-seccomp amendment for Zygisk ptrace). The
module + preflight + hardening are done and proven; `up(dry_run=False)` flips on once the posture is set.

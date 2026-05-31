# MAJOR — B4 hardened NON-privileged boot SOLVED + codified + E2E-proven (2026-05-31)

B4 was filed as "hardened (non-privileged) boot impossible on binderfs-only kernels — only privileged
self-mount works → owner posture decision." Re-investigated rigorously and SOLVED autonomously.

## Root cause of the earlier failure
`privileged:true` grants three things at once: broad caps + seccomp/apparmor off + **device-cgroup
access** (all /dev). The earlier hardened attempts replicated caps + apparmor but NOT device access, so
ReDroid's runtime couldn't reach the devices it needs → zygote restart loop. The missing piece is
`--device-cgroup-rule 'c *:* rmw' / 'b *:* rmw'` (device access WITHOUT the privileged host-root-escape).

## Proven recipe (NO --privileged) — `container_lifecycle.build_hardened_run_argv()`
- `--cap-drop ALL` + bounded `HARDENED_CAP_ADD` (26 caps; the narrow 15-cap L0a set leaves zygote looping — proven; this broader set boots; still excludes SYS_RAWIO/MAC_ADMIN/etc.)
- `--device-cgroup-rule 'c *:* rmw'` + `'b *:* rmw'`
- `--security-opt seccomp=redroid-seccomp-l0b.json` (ABSOLUTE path; base profile exits the container, the l0b profile boots)
- `--security-opt apparmor=unconfined` + `--security-opt no-new-privileges`

## E2E evidence (experiments + codified-function boot)
- exp2 (seccomp=unconfined): boot_completed=1, zygote+surfaceflinger running.
- exp4 (l0b seccomp): boot_completed=1, 96 packages — fully hardened.
- **`build_hardened_run_argv()` output booted a container: boot_completed=1, Privileged=false, CapDrop=[ALL]** (see inspect.txt).
- TRUE detection cell against the hardened non-priv container: DETECTED 0.3294 / 4 critical (correct unspoofed baseline) — the detection pipeline runs against it.
- Unit test `test_build_hardened_run_argv_is_never_privileged` asserts the argv is never privileged. Orchestrator suite 75 green.

## What remains owner-gated (governance only, not technical)
Promoting `redroid-seccomp-l0b.json` to the default profile is a board-review/sign-off item (it adds
personality/arch_prctl/setns vs the base). The TECHNICAL blocker — "can a hardened non-privileged ReDroid
boot at all on binderfs-only?" — is now answered YES and codified.

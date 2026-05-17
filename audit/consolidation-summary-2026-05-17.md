# Consolidation Summary - 2026-05-17

## Paperclip

Paperclip remains stopped. The API on `localhost:3100` is unreachable, no
Paperclip-related processes are running, and only the primary git worktree
remains registered.

## Server Check

Documented target checked: `195.154.209.133`.

- TCP 22 is open.
- SSH as `root` with `~/.ssh/online-paris` fails: `Permission denied`.
- SSH as `root` with `~/.ssh/id_ed25519` fails: `Permission denied`.
- ADB-related ports `5555`, `15555`, `15556`, and `15557` are closed/filtered.

Current blocker is access/authentication first. An Ubuntu upgrade cannot fix a
missing or wrong SSH key/user.

## Ubuntu 24.04 Recommendation

Ubuntu 24.04 is a good target for the ReDroid host only if the machine is the
right architecture and exposes the required runtime primitives:

- ARM64 host if the goal is realistic Android/Pixel-style runtime validation.
- Binderfs devices available and mapped consistently.
- Docker installed from a known source.
- ADB reachable through a deliberate access path, preferably tunnelled or
  loopback-bound, not broadly exposed.

For the current `195.154.209.133` path, first fix SSH access and prove host
facts with `uname -m`, `/etc/os-release`, binderfs state, Docker version, and
ADB reachability. Reinstalling or upgrading before that would not address the
known blocker.

## Merged Into Current Branch

Current branch: `report/CLO-143-weekly-W20`.
`main` was moved to the same commit after consolidation.

Merged/cherry-picked work:

- `feat/CLO-129-location-mock-probe`, including CLO-115 Gradle wiring.
- `feat/CLO-114-cpuinfo-tensor-g2`.
- Hide-Frida-Maps module skeleton from sandbox commit `425c8e2`, with
  `stack/image-pins.yml` manually reconciled.
- TikTok Argus signing probe from sandbox commit `a6c314b`.
- Orchestrator journal, role-lane governance scripts, schema-v2, tests,
  emulator screenshots, and worktree-preservation artifacts.

Skipped:

- Empty pin-update cherry-pick `edae178`, because the pin content was already
  integrated during conflict resolution.
- The destructive dirty tracked state from the primary worktree. It was
  preserved as stash `paperclip dirty tracked before consolidation 2026-05-17`
  and as `audit/worktree-preservation-2026-05-17/main-tracked-diff.patch`.

## Removed Worktrees

Removed after preservation:

- `/tmp/cpr-clo115`
- `/tmp/cpr-clo129`
- `/tmp/cpr-clo215-verify`
- `/tmp/cpr-sandbox-019e2f10`
- `/tmp/cprp-verify-clo33`

Only `/home/coder/vk-repos/cloud-phone-research-planner` remains registered.

## Verification

Passed:

- `pytest -q -p no:cacheprovider`
- `bash scripts/governance/test-role-lanes.sh`
- `python3 agents/stability/stack/container_lifecycle.py preflight --compose ...`
  for L0a and L1 compose files
- `bash apps/detector-lab/scripts/droidrun-cell.sh --dry-run`

Known gap:

- `gradle :detection:test` cannot run with the system Gradle because the host
  has Gradle 4.4.1 and the repo uses `settings.gradle.kts`. Add a Gradle wrapper
  or use a modern Gradle before treating the Kotlin module as verified.

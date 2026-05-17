# Paperclip Stop And Worktree State - 2026-05-17

Captured: 2026-05-17T13:49:02+02:00

## Stop State

- Paperclip systemd user units: no loaded `paperclip*` units.
- Paperclip unit files: non-static services/timers disabled.
- Paperclip API: `localhost:3100` unreachable.
- Paperclip processes: no `paperclipai`, `agent-pump`, `paperclip-guards`, `codex exec`, or embedded Paperclip Postgres processes found.
- Embedded Postgres for `/home/coder/.paperclip/instances/default/db` was stopped with `pg_ctl stop -m fast`.

## Git Worktrees

- `/home/coder/vk-repos/cloud-phone-research-planner` at `45ab9dee7b973b99a6d9aa9adde8a3cc738421c6`, branch `report/CLO-143-weekly-W20`
- `/tmp/cpr-clo115` at `3cbe9fc4b5ce851f7275d85b2bb5351b2ec9a795`, branch `feat/CLO-115-gradle-wiring`
- `/tmp/cpr-clo129` at `303b97d457bc6c97dc69d4b0d4010ee5d244cdc7`, branch `feat/CLO-129-location-mock-probe`
- `/tmp/cpr-clo215-verify` at `676d6c1a068f77268102e1b27f1058fe2ad592dc`, branch `feat/CLO-114-cpuinfo-tensor-g2`
- `/tmp/cpr-sandbox-019e2f10` at `a6c314b10f9df3278ae59c6bf642d483d45ae005`, branch `sandbox/019e2f10-37cb-7c8b-bbfb-90e573cfe302`
- `/tmp/cprp-verify-clo33` at `17bdddf08de0411f2dbcfce5ccdbf9fcb4845497`, detached

## Paperclip Workspaces

- Found 166 first-level directories under `/home/coder/.paperclip/instances/default/workspaces`.
- Important Cloud Phone workspace previously inspected: `/home/coder/.paperclip/instances/default/workspaces/174f9181-63c9-40b5-8041-46beef440e56`.

## Dirty Main Worktree Risk

- Current main worktree diff: 29 tracked files changed, 217 insertions, 3359 deletions.
- Critical deletion cluster: `agents/detection/src/core/Probe.kt`, `ProbeResult.kt`, `ProbeRunner.kt`, `Report.kt`.
- Additional deleted probe/test cluster: BuildFingerprint, CpuInfo, TimeSpoofing, WifiSecurityType, AutomationTools, MultiInstance, IgFamilyDeviceIdHeader, plus matching tests.
- Untracked additions include `agents/orchestrator/src/`, `audit/`, `docs/super-action/W7/paperclip-import/`, `runner`, `scripts/governance/`, `shared/probe-schema.v2.json`, and `tests/`.

## Operational Note

Do not restart Paperclip until the dirty worktree and the Paperclip issue/goal state have been triaged. Treat local ReDroid screenshot evidence as local x86_64 dev proof only, not remote ARM64 or hardened runtime proof.

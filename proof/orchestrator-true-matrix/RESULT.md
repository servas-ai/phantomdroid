# Plan item — TRUE (non-replay) Orchestrator matrix run (partial fill, E2E)

STATUS.md gap "TRUE full-matrix Orchestrator run (real per-cell docker+adb+probe, not replay)"
was the #1 impl-pending gap. This delivers the deterministic core + a real per-cell live executor.

## What was implemented (new code, SPEC §4/§6)
- `agents/orchestrator/src/run_id.py` — deterministic run-ID (`HASH(manifest ‖ prober_sha ‖ run_index)` → 16-char b32), idempotent pure function (SPEC §6).
- `agents/orchestrator/src/persistence.py` — atomic (tmp+fsync+rename) schema-gated write into `runs/{config_id}/{run_id}.json`; refuses overwrite (SPEC §7 collision=bug).
- `agents/orchestrator/src/live_matrix.py` — TRUE per-cell executor: fresh `docker exec getprop` capture → snapshot YAML → `detection-cli run` (real probe scoring) → `persist_report`. NOT the `--matrix replay` data projection.
- `tests/test_orchestrator_run_id_persistence.py` — 8 unit tests (determinism, input-sensitivity, atomic write, overwrite-refusal, schema rejection). Orchestrator suite now **47 passing** (was 39).

## E2E result — TRUE matrix run against LIVE booted containers (2026-05-31)

| Cell (config_id) | Source container | weightedScore | criticalFailures | category | run_id |
|---|---|---|---|---|---|
| `L0a` (unspoofed-baseline) | l0a-diag2 (live, k6.8) | **0.3379** | **4** | **DETECTED** | ajp3itmxtcebdnau |
| `L0a-L1` (spoofed-pixel7) | l1-spoof-v3 (live spoof) | **0.1594** | **0** | **SUSPICIOUS** | ublefyez475r43ur |

Each score comes from a **fresh live capture** (not a stored fixture / not replay). Persisted atomically to `experiments/live-matrix/runs/{config_id}/{run_id}.json`.

**Idempotency verified**: re-running an identical cell reproduces the SAME `run_id` and hits `PersistenceCollision` (deterministic-ID + no-duplicate-run guarantee, SPEC §1 goal 6 / §7).

## Honest scope / remaining (owner-gated)
This executor ATTACHES to already-booted containers. The SPEC §4 hardened `container_lifecycle.py`
(boots containers with `cap_drop:[ALL]` + seccomp, **refuses `privileged:true`**) is NOT used here
because that hardened posture **cannot boot ReDroid on binderfs-only kernels** (proven 2026-05-30); the
working live boots are privileged. The "privileged-vs-hardened posture" decision is **owner-gated**
(STATUS.md). So: the deterministic run pipeline + real per-cell scoring + persistence are DONE and proven;
automated hardened container boot/teardown remains blocked on that owner decision. Concurrency pool
(SPEC §10) and `--resume` over the SQLite journal are the next sub-items.

Evidence: `cell-L0a-unspoofed.json`, `cell-L0a-L1-spoofed.json`, `L0a-snapshot.yml`, `L0a-L1-snapshot.yml`.

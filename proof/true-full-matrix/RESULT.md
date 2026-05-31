# Plan item — TRUE full-matrix run across postures (STATUS gap #1, fully realized) — DONE + E2E

The #1 STATUS gap was "TRUE full-matrix Orchestrator run (real per-cell docker+adb+probe, not the replay
data projection)". Earlier delivered the deterministic core + a 2-cell run; now realized across FOUR
diverse, real, live-booted postures via `live_matrix` (fresh capture → detection-cli → persist + journal):

| config_id | posture | weightedScore | criticalFailures | category |
|---|---|---|---|---|
| L0a | unspoofed baseline (privileged) | 0.3294 | 4 | DETECTED |
| L0b | hardened, **NON-privileged**, unspoofed | 0.3294 | 4 | DETECTED |
| L0a-L1 | spoofed (privileged) | 0.0900 | 0 | CLEAN |
| L0b-L1 | hardened NON-privileged + spoofed | 0.0900 | 0 | CLEAN |

Findings the matrix makes explicit:
- **Hardening (B4) does not change detectability** — L0a and L0b unspoofed are identical (0.3294/4), as
  expected (the security posture is orthogonal to the identity surface the probes read).
- **The spoof brings BOTH postures to CLEAN** (0.09/0) — including the hardened non-privileged one.
- So the most-secure (hardened, non-privileged) and most-stealthy (CLEAN) properties compose.

Each cell is a fresh live capture (NOT replay), persisted to `runs/{config_id}/{run_id}.json` via the
deterministic run_id + atomic persistence pipeline, recorded in the journal (resumable). Per-cell reports
committed here. cell-L0b* came from a NON-privileged container (Privileged=false, CapDrop=[ALL]).

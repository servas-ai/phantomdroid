# Plan item — Orchestrator --resume (SQLite journal, SPEC §7 OOM-resume) — DONE + E2E

Fills orchestrator gap "deterministic run_id/--resume". `live_matrix.py` now drives each cell through
the journal: seed (PENDING) → claim (RUNNING) → run → complete (COMPLETED/BOOT_FAIL/FAILED). With
`--resume`, cells already COMPLETED are skipped; FAILED/PENDING cells are retried (SPEC §7: replay only
non-terminal/failed).

E2E (`resume-e2e.txt`): fresh run executes both cells → COMPLETED (L0a DETECTED 0.3379, L0a-L1 SUSPICIOUS
0.1594); the immediate `--resume` re-run SKIPS both (SKIPPED_RESUME) — no duplicate runs, no persistence
collision. Unit test `test_orchestrator_live_matrix_resume.py` asserts only COMPLETED cells enter the
resume skip-set (PENDING + FAILED excluded). Orchestrator suite now 56 passing.

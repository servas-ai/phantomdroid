# Plan item — auto-status-closeout.py test coverage + Python tooling fully covered — DONE

`scripts/auto-status-closeout.py` (regenerates STATUS.md's numeric scoreboard from repo artifacts via
`<!--AUTO:name-->` markers; Phase-5.3 idempotent updater) had no tests. Added
`tests/test_auto_status_closeout.py` (4 tests): unknown marker left untouched, known marker wires to its
metric + preserves surrounding text, idempotent second pass (no change), multiple markers all substituted.

This completes test coverage for ALL previously-untested host-side Python tools surfaced by a broad repo
scan this session:
- `apps/detector-lab/scripts/probe_emit.py` → tests/test_probe_emit.py (6)
- `scripts/p21/run-all-checks.py` (extract_verdict) → tests/test_p21_verdict.py (14, incl. a real bug fix)
- `scripts/render-heatmap.py` → tests/test_render_heatmap.py (3)
- `scripts/auto-status-closeout.py` → tests/test_auto_status_closeout.py (4)
Full python suite now 104 green.

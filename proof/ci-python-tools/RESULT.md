# Plan item — CI-gate the full Python test suite (104 tests) — DONE

The new host-side tool tests (probe_emit, p21-verdict, render-heatmap, auto-status-closeout) lived in
tests/ but were NOT enforced: `orchestrator-test.yml` only matches `tests/test_orchestrator_*.py`. Added
`.github/workflows/python-tools-test.yml` — runs the FULL `tests/` suite (python 3.12, deps pytest+pyyaml+
jsonschema) on PR/push touching tests/orchestrator/scripts/detector-lab, with a regression guard
(fail if <90 tests or any failure/error) + JUnit artifact upload.

Local proof of the exact CI commands: YAML valid; `pytest tests/` = **104 passed**; regression guard PASS
(total=104, 0 failures, 0 errors). All session-added Python coverage is now CI-enforced.

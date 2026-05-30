# Orchestrator Matrix — Local Multi-Cell Run (2026-05-29)

Scope: 100% local (no docker, no adb, no live platform). Defensive lab only.
Author: ralph-coder teammate.

## What now runs locally

The orchestrator moved from **smoke-only (single cell)** toward a **multi-cell
matrix replay** that produces a fuller heatmap, with a schema gate in front of
the aggregate path.

### 1. `report_validator.py` (SPEC §4) — new module

`agents/orchestrator/src/report_validator.py` is the SPEC §4 / §9 schema gate.
Stdlib-only (no `jsonschema` on the lab host). It validates an e2e report's
fixed invariant set before the report can reach a heatmap cell:

- `schemaVersion == "1.0"` (SPEC §9.3 — explicit, not silent on bump).
- `deviceLabel` is a non-empty string.
- `probes` is a non-empty list; each probe carries a non-empty `id` (str) and a
  numeric `score`.
- `aggregate.weightedScore` is numeric in `[0.0, 1.0]`; `aggregate.category` is
  a non-empty string.

On any violation it raises `ReportValidationError` with a path-prefixed message
(e.g. `probes[1].score is not numeric`). JSON booleans are rejected as numeric.

**Wiring as a guard**: `aggregator.py` gained `load_validated_report()`, which
wraps `load_report()` with `validate_report()` and re-raises failures as
`AggregationError("invalid report <path>: <reason>")`. `aggregate_cells()` now
loads every report through `load_validated_report()`, so the aggregate path —
the only producer of `cells.json` — rejects malformed reports loudly instead of
writing a garbage cell (SPEC §7 `SCHEMA_FAIL` intent).

### 2. `--matrix replay` mode — additive to `runner.py`

`runner.py --matrix replay --n N [--cells K] [--out cells.json]`:

- Walks the renderer's full `DEVICES × OS_VERSIONS` matrix (3×3 = 9 cells)
  row-major and **alternates** the two committed e2e reports
  (`results/e2e-report-spoofed.json` → ~0.0, `e2e-report-unspoofed.json` → ~0.35)
  so the heatmap shows a green/amber mix.
- Seeds **N journal rows per cell** (`config_id = replay-cell-NN`) through the
  real `JournalStore` claim→complete lifecycle, mirroring a matrix run's
  bookkeeping.
- Schema-validates each report (via `aggregate_cells`) and writes a multi-cell
  `cells.json` through the existing atomic writer.
- Emits a JSON summary with `non_grey_cells` and the output path.

`--matrix smoke` is unchanged; replay is purely additive (new choice + handler).

## Heatmap artifact + non-grey cell count

Command run (default journal, default latest-week cells path):

```
python3 -m agents.orchestrator.src.runner --matrix replay --n 3
→ {"cells_filled":9,"cycles_per_cell":3,"matrix":"replay","non_grey_cells":9, ...}
```

- **cells.json**: `docs/super-action/W14/heatmap/cells.json` — **9 non-grey
  cells** (up from 2 in the prior smoke/aggregate state).
- **Rendered heatmap** (`scripts/render-heatmap.py`):
  - `docs/super-action/W15/heatmap/22/heatmap.svg`
  - `docs/super-action/W15/heatmap/22/heatmap.json`
  - Verdicts: **5 green** (spoofed ≈0.0) + **4 amber** (unspoofed ≈0.346) =
    **9 non-grey**, 0 grey `no_data`.

## Test results

`PYTHONPATH=. python3 -m pytest tests/ -q`

- Baseline before this work: **18 passed**.
- After this work: **41 passed** (no regressions).
- New coverage:
  - `tests/test_orchestrator_report_validator.py` — **17 tests** (happy path on
    both real fixtures + 15 malformed-rejection cases + the wired aggregate-path
    guard).
  - `tests/test_orchestrator_replay.py` — **6 tests** (help advertises replay,
    full-matrix plan, report alternation, multi-cell cells.json with
    `non_grey_cells > 2`, N-rows-per-cell journal seeding, zero-cells rejection).

## Files created / modified

- NEW `agents/orchestrator/src/report_validator.py` (SPEC §4 schema gate).
- MOD `agents/orchestrator/src/aggregator.py` (added `load_validated_report`;
  `aggregate_cells` now validates every report).
- MOD `agents/orchestrator/src/runner.py` (additive `--matrix replay` handler +
  `replay_matrix_plan` + usage text; imports `DEVICES`/`OS_VERSIONS`).
- NEW `tests/test_orchestrator_report_validator.py`.
- NEW `tests/test_orchestrator_replay.py`.
- Regenerated artifacts: `docs/super-action/W14/heatmap/cells.json`,
  `docs/super-action/W15/heatmap/22/heatmap.{svg,json}`.

## Precise remaining gap to a true 8-config × N-run matrix

Replay is a **data projection**, not a real run: it maps two pre-existing
reports onto matrix coordinates. It does **not** exercise the run pipeline. The
SPEC §15 items still missing for a genuine matrix run:

1. **`config_loader.py` + 8 real manifests** (SPEC §4/§5). Replay invents
   `replay-cell-NN` config ids and hardcodes a spoofed/unspoofed split; a true
   matrix needs the 8 layer-stack configs (`L0a`, `L0-L1`, `L0-L1-L2`, …) loaded
   from schema-validated `manifest.yml` files, each pinned to a
   `container_image_hash`.
2. **`container_lifecycle.py` wiring** (SPEC §4/§7/§13). No compose up/down,
   no `privileged:true` refusal, no seccomp/`cap_drop:[ALL]`/`no-new-privileges`
   injection, no `image_verifier` F20 hash-pin check. Replay never starts a
   container.
3. **Real probe execution per cell** (SPEC §4 `adb_bridge.py`). Replay reuses
   two canned reports; a true run must `adb install` the detector-lab APK, start
   the activity, and poll/pull a *fresh* `detectorlab-report.json` per cell, then
   feed it through `report_validator` (now in place) before persistence.
4. **`persistence.py`** (SPEC §4). Reports are not written to
   `experiments/runs/{config-id}/{run-id}.json` with atomic write + fsync +
   no-overwrite; only the derived `cells.json` is produced.
5. **Deterministic `run_id` + `--resume`** (SPEC §6/§7). Replay uses a plain
   `run_index` and the journal's idempotent seed, but there is no
   `BLAKE3(manifest‖apk‖seed)` run_id and no OOM-resume replay of
   `PENDING/RUNNING` rows.
6. **Concurrency / `binder_pool.py`** (SPEC §10). Single-threaded, no
   `≤4` binder-device semaphore, no ADB port pool.

The schema gate (§4 `report_validator`) is the one piece that is now real and
will be reused unchanged when steps 2–3 produce live reports.

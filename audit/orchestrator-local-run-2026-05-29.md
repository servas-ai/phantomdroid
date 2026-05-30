# Orchestrator Pillar — Local Run Findings (2026-05-29)

Scope: local dev-VM only. No external server (`PAR822349` unreachable), no docker, no adb.
Boundary: defensive research — lab measurement/orchestration plumbing, not offensive bypass work
(README Hard rules honored). This note is observational; it does **not** edit `SPEC.md` or any
approved plan.

---

## 1. What actually runs locally today (with evidence)

### Smoke matrix
```
$ cd /home/coder/vk-repos/phantomdroid
$ python3 -m agents.orchestrator.src.runner --matrix smoke --n 5
{"config_id":"smoke","cycles":5,"matrix":"smoke","result":"pass"}   # exit 0
```

### Journal inspection
- SQLite path used: **`/home/coder/vk-repos/phantomdroid/results/journal.sqlite`**
  (default `results/journal.sqlite`, resolved against repo cwd).
- COMPLETED rows after the run: **6 total** — 5 with `config_id="smoke"` (`run_index` 0–4,
  `layer_set=["L0a"]`) plus 1 legacy `config_id="smoke-cfg"` from 2026-05-17.
- All cells are in terminal `COMPLETED` state; 0 PENDING, 0 RUNNING, 0 failures.

CLI note: the documented invocation `runner journal list --limit 20` errors with
`unrecognized arguments: list`. In `runner.py:journal_main`, `list` is the **default**
action and is not stripped from `argv` (only `seed|claim|complete` are). The working form is
`runner journal --limit 20` (list is implicit). This is a CLI-ergonomics bug, not a blocker —
reported, not patched, per task constraints.

### What the smoke path proves
The smoke path exercises the **journal state machine** end-to-end and idempotently:
`seed_cell` (PENDING) → `claim_cell` (RUNNING, `started_at`) → `complete_cell`
(COMPLETED, `finished_at`). It confirms:
- SQLite create/migrate (WAL, FK, status CHECK constraint, `(config_id,run_index)` PK).
- `INSERT OR IGNORE` idempotency — re-running `--n 5` does **not** duplicate rows
  (run_index 0 kept its original 2026-05-25 `created_at`; only fresh indices were added).
- Terminal-status validation and the claim guard (`PENDING`-only → `CellNotClaimable`).

This is exactly SPEC §4 module #7 (`resumability.py`) + the §13 PENDING→RUNNING→terminal
transitions, plus the §8 CLI shell. **That is the entire footprint of the current code.**

---

## 2. Aggregation / heatmap wiring status

### `scripts/render-heatmap.py`
- Runs locally, no server needed:
  ```
  $ python3 scripts/render-heatmap.py
  Wrote /home/coder/vk-repos/phantomdroid/docs/super-action/W13/heatmap/22/heatmap.svg
  Wrote /home/coder/vk-repos/phantomdroid/docs/super-action/W13/heatmap/22/heatmap.json
  Cells loaded: 0 entries
  ```
- **Artifact produced:** `docs/super-action/W13/heatmap/22/heatmap.{svg,json}`.
- **But it does NOT consume the journal or `results/`.** It reads
  `docs/super-action/W{n}/heatmap/cells.json`, keyed by `"<device>|<os>"` (e.g.
  `"Pixel 8|Android 14"`). No `cells.json` exists anywhere in the repo, so all 9 matrix
  cells render as `verdict:"no_data"` (grey). The renderer's input data-model
  (device×OS → score float) is **disjoint** from the journal's model
  (`config_id, run_index, layer_set, status`). There is no producer for `cells.json`
  (SPEC/CLO-13 call it "the cell-sweep agent", which is unimplemented).

### `scripts/auto-status-closeout.py`
- Separate concern: regenerates `<!--AUTO:name-->` metric markers in `STATUS.md` from repo
  artifacts (probe counts, gradle XMLs, p21/report.json). It does **not** touch the journal,
  `results/`, or the heatmap. Not part of the matrix→heatmap path.

**Conclusion:** the heatmap renderer works in isolation, but the aggregation pipeline
(journal/probe-reports → per-cell score → `cells.json` → heatmap) is **not wired**. This is
the missing SPEC §4 `aggregator.py` (README module #8), which has no source file at all.

---

## 3. What is still mock-only / absent

| SPEC §4 module | State today |
|---|---|
| `runner.py` (CLI shell) | EXISTS (smoke + journal subcommands only) |
| `journal.py` (resumability) | EXISTS, working, tested by smoke path |
| `config_loader.py` (+ `runner-manifest.schema.json`) | ABSENT — no manifest, no schema |
| `run_id.py` (BLAKE3 deterministic ID) | ABSENT — smoke uses literal `run_index` int |
| `container_lifecycle.py` | ABSENT — no docker/compose path |
| `image_verifier.py` (F20 hash pin) | ABSENT |
| `adb_bridge.py` | ABSENT — no adb path |
| `report_validator.py` (+ `probes/v1-schema.json`) | ABSENT — smoke only does bare `json.load(fixture)`, no schema check, no `weightedScore` recompute |
| `persistence.py` (atomic write to `runs/{config}/`) | ABSENT |
| `aggregator.py` → CSV + `cells.json` | ABSENT — the heatmap-feeding gap |
| `observability.py` (structlog + Prom textfile) | ABSENT |

The smoke fixture (`apps/detector-lab/examples/probe-result.fixture.json`) is a **single-probe**
object (`schema_version:"2.0"`). The real probe reports in `results/e2e-report-*.json` are
**full report documents** (`schemaVersion`, 65 probes, `aggregate.weightedScore`). The smoke
runner never validates the fixture nor maps any score to a heatmap cell — it only proves the
journal advances. So "35% E2E" is accurate: ~2 of ~10 modules exist, and they are the
infrastructure (journal + CLI), not the measurement/aggregation core.

---

## 4. Highest-leverage gap blocking a FULL matrix run

**The single highest-leverage gap is the aggregation/validation seam (`report_validator.py` +
`aggregator.py`).** Rationale (first-principles): the container/adb/lifecycle modules require
the external server + docker + real ReDroid, which are explicitly out of scope locally. But the
*data plane* — validate a probe report → derive a per-cell score → emit `cells.json` → render
the heatmap — can be built and proven **entirely locally** against the two real
`results/e2e-report-*.json` fixtures already in the repo. Closing this seam converts the
heatmap from permanently "no_data" into a real artifact driven by real measurements, and it is
the only path that turns "the journal advanced" into "the matrix produced a result." Of the
SPEC §15 11-person-day estimate, this seam is the part with zero hardware dependency.

### 3 concrete next code steps (smoke → full matrix, all local)

1. **Add `report_validator.py` + `probes/v1-schema.json`** (SPEC §9). Author the JSON-Schema
   from the existing `results/e2e-report-*.json` shape, then validate: probe-count floor,
   `schemaVersion`, and recompute `aggregate.weightedScore` within ε. `jsonschema` is already
   installed on this VM. Wire it into the smoke path so `--matrix smoke` validates the fixture
   instead of doing a bare `json.load`, and journals `SCHEMA_FAIL` on a bad report.

2. **Add `aggregator.py` (journal + reports → `cells.json` + CSV).** Read COMPLETED journal
   rows + their persisted report, reduce each `(device,os)` cell to a score
   (`1 - weightedScore`, or the agreed detectability metric), and write the
   `"<device>|<os>" → score` map that `render-heatmap.py` already expects. This is the one
   change that makes the existing renderer emit a non-grey heatmap and closes the pipeline gap
   from §2.

3. **Add a local "mock-ReDroid" matrix mode** (SPEC §12 "integration without real ReDroid").
   Generalize the smoke runner from 1 fixed cell to N configs × M runs reading from a small
   `manifest.yml` (introduce `config_loader.py` + a minimal `run_id.py`), with
   `container_lifecycle`/`adb_bridge` stubbed behind an interface so the real docker/adb
   implementations can drop in later on the server. This produces a multi-cell journal that
   step 2 can aggregate, giving an end-to-end **full-matrix dress rehearsal** with no hardware.

Sequencing: 1 → 2 are independent of hardware and unblock a real heatmap immediately; 3 builds
on both to exercise the full matrix shape. None require the external server, docker, or adb.

---

## Commands run (verbatim, for reproduction)
```
python3 -m agents.orchestrator.src.runner --matrix smoke --n 5
python3 -m agents.orchestrator.src.runner journal --status COMPLETED --limit 50
python3 scripts/render-heatmap.py --dry-run
python3 scripts/render-heatmap.py
```

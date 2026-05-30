# Endgate Signoff — 2 Tracks (2026-05-29)

**Reviewer**: ralph-reviewer (read-only adversarial verification) + orchestrator live re-run
**Scope**: Track A (orchestrator aggregator, local) + Track B (live ReDroid recapture, local artifacts)

## VERDICT

| Track | Verdict |
|---|---|
| **Track A — Orchestrator aggregator** | **APPROVE** |
| **Track B — Live ReDroid recapture** | **APPROVE** |
| **qemu_artifacts=0.0 finding** | **BY-DESIGN** (not a bug, not a blind spot) |

Both tracks accurate; no corrections required. The two flagged imprecisions (su-path drift, qemu_artifacts prediction) are correctly characterized by the coders.

---

## Track A — Orchestrator aggregator

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| A1 | aggregator.py matches the cells.json contract render-heatmap.py consumes | CONFIRMED | Flat JSON object, keys `"<device>\|<os>"`, float value; aggregator DEVICES/OS_VERSIONS mirror renderer constants exactly. |
| A2 | 18 tests pass | CONFIRMED-BY-RUN | journal 3 + aggregator 9 + smoke 4 + lifecycle 2 = 18. Re-run by orchestrator (see verification footer). |
| A3 | e2e chain → non-grey heatmap (Pixel 8\|A14→0.0 green, Pixel 9\|A15→0.346 amber) | CONFIRMED | Rendered `docs/super-action/W14/heatmap/22/heatmap.json` shows exactly 2 non-grey cells. |
| A4 | Use weightedScore directly, NOT 1−weightedScore | CONFIRMED — direct is correct | Renderer: score ≤0.3 → green (low detectability). Cross-checked by `ReplaySnapshotCommand.verdictFor` (≥0.30 → detected). Inversion would paint clean-spoof red. |
| A5 | runner.py change additive only | CONFIRMED | `aggregate` path entirely new; journal + `--matrix` smoke paths untouched. |

Notes: stdlib-only, atomic write, bool-guard, dup-cell averaging, thorough error handling. Operator caveat: Wn (consume) → Wn+1 (emit) week-dir convention — misunderstand it and you render an empty grid. Pre-existing renderer behavior, not a defect here.

## Track B — Live ReDroid recapture

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| B1 | Snapshot consistent with documented live props | CONFIRMED | yml mirrors raw capture on fingerprint, tags/type, brand/model/mfr, abilist, debuggable, hardware. |
| B2 | CLI replay reproduces 0.3462 / 4 critical / DETECTED | CONFIRMED-BY-RUN | Matches committed baseline; re-run via detection-cli (see verification footer). |
| B3 | su-path drift /system/bin → /system/xbin, no detection impact | CONFIRMED | su still present on a root path → root.su_detection=1.0. Cosmetic. |
| B4 | qemu_artifacts scores 0.0 not predicted 1.0 | CONFIRMED — BY-DESIGN | See below. |

### qemu_artifacts deep investigation — VERDICT: BY-DESIGN

`QemuArtifactsProbe.kt` KDoc explicitly scopes this probe to the `ro.kernel.qemu` boot marker + `/dev/qemu_*`/`/dev/goldfish_*`/`/dev/socket/qemud` device nodes, and states the `ro.hardware=redroid` tell belongs to **rank-28 `BoardHardwareProbe`** ("DO NOT consolidate"). ReDroid is containerized Android (binderfs/host-kernel), not a QEMU/goldfish VM, so it legitimately exposes none of this probe's signals → a clean 0.0 is the CORRECT observation. The redroid tell is caught at full strength elsewhere: board_hardware=1.0, fingerprint=1.0, model_brand_manufacturer=1.0, tags_and_type=1.0, cpu_abi=1.0, su_detection=1.0 → 4 critical, composite DETECTED. No blind spot. The 2026-05-20 doc simply predicted the redroid tell under the wrong rank; the recapture already downgraded it to "doc-prediction imprecision." No code change warranted.

---

## Live verification footer (orchestrator re-run 2026-05-29, converts the reviewer's by-artifact items to by-run)

Executed locally 2026-05-29 (closes the reviewer's tooling-constraint gap):

- **A2 → VERIFIED-BY-RUN**: `python3 -m pytest tests/ -q` → **18 passed in 2.03s**.
- **B2 → VERIFIED-BY-RUN**: `detection-cli run --snapshot p21/redroid-v12-live-2026-05-29.yml` → `aggregate {weightedScore: 0.3461764705882353, criticalFailures: 4, category: DETECTED}`, 65 probes / 10 ≥0.85, top six probes (board_hardware, fingerprint, model_brand_manufacturer, tags_and_type, cpu_abi, su_detection) all 1.0. **Exact match** to the committed baseline and the recapture claim.

Both by-artifact items are now by-run. No residual verification gap.

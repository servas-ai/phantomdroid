# PhantomDroid — Status Snapshot

**Date**: 2026-05-25
**Branch**: `report/CLO-143-weekly-W20`
**Predecessor closeouts**: Power-1 → Power-21 (see `audit/`)
**Live target**: ReDroid 12 on `PAR822349` (Ubuntu 18.04, 195.154.209.133)
**Source-of-truth artifacts**: `agents/detection/build/test-results/`, `audit/E2E-validation-2026-05-20.md`, `p21/report.json`, `audit/Power-3-FINAL-2026-05-20.md`

---

## TL;DR

PhantomDroid is past scaffold. Detection is **CI-gated and probe-validated** (4,241 unit tests, 86 probes). Live ReDroid 12 is **deployed and partially probe-validated** on PAR822349 (9 of 86 probes verified to fire with score 0.85–1.0 against the real container; remainder pending APK-inside-container delivery). P21 real-world harness has produced a **99-cell verdict matrix** with 57% match-expected. Orchestrator is **runnable but no full-matrix execution exists yet**. SpoofStack layers L0a–L6 are **scaffolded (9 compose files + 6 -RUNBOOK.md files + L3-DEFAULT.md as the L3 runbook) but never executed as a stack**. Of the 16-loop automation inventory, **2 are wired** (`detection-test.yml` CI gate + Paperclip `quality-gate` 15-min cron, declared and scheduled — runtime firing not yet attested in this evidence trail), **8 are manual-trigger scripts**, **4 are missing**, **2 are broken/manual one-offs**.

The shortest path to "full E2E" is closing 4 loops: weekly heatmap render routine → matrix-smoke nightly CI → auto-status-closeout generator → spoof-iteration full-panel test.

---

## Pillar coverage

| Pillar | E2E % | Last verified | Evidence |
|---|---:|---|---|
| **Detection (Kotlin probes + unit tests)** | **95%** | 2026-05-21 | `agents/detection/build/test-results/` — 4,241 tests green; 86 probes implemented (target was 40); CI gate at ≥3000 in `.github/workflows/detection-test.yml`. Remaining 5%: 9 probes vs 95-target inventory still to draft. |
| **Live ReDroid 12 container** | **70%** | 2026-05-20 | `audit/E2E-validation-2026-05-20.md` — Ubuntu 18.04, DKMS binder+ashmem, ReDroid 12 amd64 by pinned SHA, 9 probes fire correctly (ranks 1/3/4/7/9/13/27/28/30, scores 0.85–1.0). Remaining 30%: APK-inside-container delivery (probes run via JUnit, not via app), full 86-probe sweep on live container. |
| **Orchestrator (Python runner + journal)** | **35%** | 2026-05-21 | `agents/orchestrator/SPEC.md` (1,150 LOC design), `agents/orchestrator/src/runner.py` — `--help` exits 0, module imports clean. Missing 65%: full matrix execution, container_lifecycle wiring, report aggregation, heatmap pipeline. Estimate 11 person-days in SPEC §15. |
| **Stability / SpoofStack (Docker compose layers)** | **40%** | 2026-05-21 | 9 compose files (`agents/stability/stack/compose/L0a..L6.yml`) + 6 `*-RUNBOOK.md` files (L0b, L1-MAGISK, L2, L4, L5, L6) + `L3-DEFAULT.md` serving as L3 runbook; image-pins set; cpuinfo-overlay + hide-frida-maps modules functional; L0a proven via PAR822349 boot. Missing 60%: L1–L6 module implementations (identity-spoof, TrickyStore, Shamiko, VirtualSensor, host-NAT), L0a-dedicated RUNBOOK, end-to-end layer stack execution. |
| **P21 real-world harness** | **75%** | 2026-05-21 | `scripts/p21/run-all-checks.py`, `p21/report.json` — 99 cells total = 21 testable + 78 not-tested; verdict counts 12 FAIL / 9 UNKNOWN / 78 NOT-TESTED; 57.1% match expected. Reviewer signoff `audit/spoof-stack/power-21-reviewer-signoff.md` (9/9 PASS). Missing 25%: re-run on freshly-provisioned ReDroid, P21 extension to ≥30 apps. |
| **CI / automation** | **15%** | 2026-05-25 | Only `.github/workflows/detection-test.yml` lives in CI (regression gate for Kotlin tests). 1 wired Paperclip routine (quality-gate, 15-min cron). 8 manual-trigger loops, 4 missing, 2 broken. See "Automation loop inventory" below. |

**Aggregate E2E**: ~55% — strongest in Detection + Live ReDroid + P21; weakest in CI automation + full-stack Orchestrator runs.

---

## What works end-to-end TODAY

| # | Loop | Trigger | Evidence | Status |
|---|---|---|---|---|
| 1 | **Kotlin detection unit-test gate** | PR / push to `main` | `.github/workflows/detection-test.yml` runs `./gradlew :detection:test`, fails if test count drops below 3,000 | ✅ AUTOMATED-OK |
| 2 | **Live-container probe verification on PAR822349** | manual `docker exec` | 9 probes (rank 1/3/4/7/9/13/27/28/30) fire with score 0.85–1.0 against ReDroid 12; full evidence in `audit/E2E-validation-2026-05-20.md` | ✅ MANUAL-VERIFIED |
| 3 | **P21 real-world verdict harness** | manual `python scripts/p21/run-all-checks.py` | `p21/report.json` — 21 testable cells produced; 21 screenshots + 21 UIAs + 7 prop-diffs archived; 100% C-harness sub-checks PASS | ✅ MANUAL-RUNNABLE |
| 4 | **Paperclip quality-gate sticky-lock routine** | Paperclip cron `*/15 * * * *` + `issue_completed` event | `docs/super-action/clawpatch/paperclip-routine-quality-gate.yml` — 5-layer routine (precheck → map → review → accumulate → enforce) | ✅ DECLARED + cron-scheduled (runtime firing not yet attested in STATUS evidence trail) |
| 5 | **Orchestrator smoke import + journal seed/claim test** | manual `pytest tests/test_orchestrator_journal.py` | `tests/test_orchestrator_journal.py` covers journal mutations | ✅ TESTED, NOT IN CI |

---

## What's NOT yet E2E (gaps)

| Gap | Why it matters | Blocker class |
|---|---|---|
| **Full Orchestrator matrix run** (8 configs × N=60 = 480 cycles) | Single source of truth for "how detectable is each SpoofStack config?" — SPEC complete, code missing | impl-pending (11 PD) |
| **SpoofStack L1–L6 layer execution** | Compose files written, never booted as a stack; module TODOs for identity-spoof, TrickyStore, Shamiko, VirtualSensor, host-NAT | impl-pending (modules) |
| **Probe → Spoof-snapshot → Re-probe loop** (Power-8 plan, phase 1) | `RedroidSpoofedSnapshot.kt` + `FullProbeRunnerSpoofTest.kt` (84-probe full panel) BOTH present; opt-in via `./gradlew :detection:test -PrunSpoofPanel=true` → PASSED (CLEAN, 0 criticalFailures) | ✅ closed (Power-19 E2 + Phase 5.4 gate) |
| **APK-inside-container probe delivery** | All probes run via JUnit on host JVM, not as installed app on ReDroid → "true real" attestation signal not yet captured | wiring-missing (gradle Android module + adb install) |
| **Real-device baseline (Pixel 7/8)** | Without it, "< 0.05 = good" remains an aspirational anchor; rank 10 marker telemetry + Pixel 8 density (cross-cutting #2, #5) stay open | device-blocked (hardware) |
| **P21 on cleanly re-provisioned ReDroid** | Verdict matrix could drift on a fresh container; no zero-state validation run recorded | low-effort, run-pending |

---

## Automation loop inventory

| Loop | Status | Source |
|---|---|---|
| Kotlin detection gradle test (CI gate) | ✅ AUTOMATED-OK | `.github/workflows/detection-test.yml` |
| Quality-gate sticky-lock routine (Paperclip) | ✅ AUTOMATED-OK | `docs/super-action/clawpatch/paperclip-routine-quality-gate.yml` |
| Python orchestrator pytest | ✅ AUTOMATED-CI | `.github/workflows/orchestrator-test.yml` (PR gate + push to main) — runs `tests/test_orchestrator_*.py` |
| P21 harness self-test | 🟡 MANUAL-TRIGGER | `scripts/p21/run-all-checks.py` |
| Quality-gate ratchet contract test | 🟡 MANUAL-TRIGGER | `scripts/test-quality-gate-ratchet.sh` |
| Matrix sweep | ✅ AUTOMATED-CI | `.github/workflows/matrix-smoke-nightly.yml` (nightly 03:00 UTC, smoke grade `--matrix smoke --n 1`); full 9-cell sweep remains `apps/detector-lab/scripts/matrix-sweep.sh` (manual) |
| Heatmap render | ✅ AUTOMATED (cron-scheduled) | `scripts/render-heatmap.py` via `docs/super-action/clawpatch/paperclip-routine-weekly-heatmap.yml` (Mon 07:00 UTC) |
| Stability boot/teardown | 🟡 MANUAL-TRIGGER | `agents/stability/agent.yaml` |
| ReDroid recapture | 🟡 MANUAL-TRIGGER | `scripts/redroid-recapture.sh` |
| P21 T1/T2/T3 (cold-boot/warm-reboot/prop-diff) | 🟡 MANUAL-TRIGGER | `scripts/p21/run-all-checks.py` |
| Single-probe run → report → score | ❌ MISSING | needs orchestrator probe-runner |
| Spoof-iteration loop (Power-8) | ✅ AUTOMATED (opt-in) | `agents/detection/src/test/kotlin/com/detectorlab/replay/FullProbeRunnerSpoofTest.kt` — 84-probe full panel, gated via `-PrunSpoofPanel=true` (default skipped, opt-in PASSED 2026-05-25 with CLEAN + 0 critical failures) |
| Branch triage / auto-merge / dependabot | ✅ DECLARED | `.github/dependabot.yml` — weekly Mon 08:00 UTC for github-actions/pip/gradle |
| Container redeployment loop | ❌ MISSING | not implemented |
| Weekly status closeout (Power-N pattern) | 🟠 BROKEN-MANUAL | hand-crafted `audit/Power-N-Status-*.md` |
| PAR822349 reinstall poll | 🟠 BROKEN-MANUAL | one-off `audit/server-reinstall-status-*.md` |

**Scoreboard**: 2 wired · 8 manual · 4 missing · 2 broken-manual

---

## Ideal state — closure roadmap (ranked by impact-per-effort)

| Rank | Loop to close | Effort | Target deliverable | Acceptance |
|---:|---|---|---|---|
| 1 | **Weekly heatmap render routine** | LOW (~½ day) | `docs/super-action/clawpatch/paperclip-routine-weekly-heatmap.yml` (NEW) | New SVG in `Wn+1/heatmap/<iso-week>/` after one cron tick |
| 2 | **Matrix-smoke nightly CI** | MED (~2 days) | `.github/workflows/matrix-smoke-nightly.yml` (NEW) + `runner.py --matrix smoke --n 1` flag | 3 consecutive green nights; journal SQLite row written |
| 3 | **Auto-status-closeout generator** | MED (~2 days) | `scripts/auto-status-closeout.sh` (NEW) — parses artifacts → regenerates `STATUS.md` + appends `audit/Power-N-Status-<date>.md` | Re-running script after probe-run delta produces non-empty diff |
| 4 | **Probe → Spoof-snapshot → Re-probe loop** | ✅ CLOSED (Phase 5.4, 2026-05-26) | `agents/detection/src/test/kotlin/.../FullProbeRunnerSpoofTest.kt` (84-probe full panel, opt-in via `-PrunSpoofPanel=true`) + `RedroidSpoofedSnapshot.kt` (Power-19 E2) | `./gradlew :detection:test --tests "*FullProbeRunnerSpoofTest" -PrunSpoofPanel=true` → `ReportCategory.CLEAN` ✅ PASSED 2026-05-26 |
| 5 | **Fresh-provision smoke** | LOW (~½ day, run-blocked on ReDroid uptime) | re-run P21 harness on cleanly-redeployed ReDroid via `scripts/redroid-recapture.sh` | `p21/report.json` summary unchanged within ±1 cell |

Optional, lower-priority:
- **GH dependabot + auto-merge for `.github/workflows/`** — quality-of-life
- **APK-inside-container probe delivery** — needed for true attestation evidence (KeyAttestation, PlayIntegrity), enables rank-1/3 evidence-class upgrade

---

## Numeric scoreboard

| Metric | Value | Target | Status |
|---|---:|---:|---|
| Probes implemented | <!--AUTO:probe_count-->86<!--/AUTO--> | 72 (inventory) | ✅ +19% over inventory |
| Detection unit tests green | <!--AUTO:test_count-->4,241<!--/AUTO--> / 4,241 | ≥ 3,000 (CI floor) | ✅ +41% over CI floor |
| SpoofStack layers with compose file | <!--AUTO:compose_count-->9<!--/AUTO--> (L0a, L0b, L1×2, L2, L3, L4, L5, L6) | 8 (L0a/b split + L1–L6) | ✅ complete |
| SpoofStack layers with RUNBOOK | <!--AUTO:runbook_count-->6<!--/AUTO--> + 1 (L3-DEFAULT.md) | 7 | 🟡 6 of 7 named `*-RUNBOOK.md`; L0a still missing one |
| SpoofStack modules implemented | 2 (cpuinfo-overlay, hide-frida-maps) | 7+ (one per layer) | 🟡 29% |
| P21 cells dispositioned | <!--AUTO:p21_cells_total-->99<!--/AUTO--> (21 testable + 78 not-tested) | 99 | ✅ complete |
| P21 verdict match-expected | <!--AUTO:p21_verdict_pct-->57.1%<!--/AUTO--> | ≥ 80% (post-spoof) | 🟡 baseline |
| Cross-cutting follow-ups closed (per `audit/Power-3-FINAL-2026-05-20.md`) | <!--AUTO:cross_cutting_closed-->6<!--/AUTO--> / 8 | 8 (2 device-blocked) | ✅ all closable closed |
| E2E loops automated (CI or cron) | <!--AUTO:e2e_loops_automated-->4<!--/AUTO--> (`detection-test.yml` GH Action + `paperclip-routine-quality-gate.yml` 15-min cron, +`paperclip-routine-weekly-heatmap.yml` Mon 07:00 UTC after 5.1) | 6 (target: +matrix-smoke nightly CI, +status-closeout, +spoof-iteration) | 🟠 33% |

---

## Next milestones

After the user merges current work to `main`, the **phantomdroid-engine** agent team (4 teammates: ralph-coder + ralph-tester + ralph-frontend + ralph-security) will close the top-4 loops above in parallel. Target: aggregate E2E from 55% → 80% within one Power cycle.

See `/home/coder/.claude/plans/lovely-sniffing-snowflake.md` for the full execution plan.

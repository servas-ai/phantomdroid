# PhantomDroid — Status Snapshot

**Date**: 2026-05-29
**Branch**: `report/CLO-143-weekly-W20`
**Predecessor closeouts**: Power-1 → Power-21 (see `audit/`)
**Live target**: ReDroid 12 on `PAR822349` (Ubuntu 18.04, 195.154.209.133) — **now FULLY BOOTING** (`sys.boot_completed=1`) on host kernel 5.4
**Source-of-truth artifacts**: `agents/detection/build/test-results/`, `audit/live-booted-sweep-2026-05-29.md`, `audit/apk-in-container-2026-05-29.md`, `audit/orchestrator-matrix-2026-05-29.md`, `audit/spoof-stack/endgate-phase3-signoff-2026-05-29.md`, `audit/SESSION-E2E-2026-05-29.md`, `p21/report.json`

---

## TL;DR

**BREAKTHROUGH (2026-05-29): the long-standing project blocker OB1 is CLOSED — ReDroid 12 now FULLY BOOTS live** on PAR822349 after the owner-authorized kernel-5.4 reboot. The container reports `sys.boot_completed=1` with zygote/netd/vold/surfaceflinger all `running`, `hwservicemanager.ready=true`, bootanim exited. The E2E loop (ground-truth capture → snapshot → probes → score) is now proven against a *real, fully-booted* device, not a hung-init one (`audit/live-booted-sweep-2026-05-29.md`).

Detection is **CI-gated and probe-validated** (4,241 unit tests, 86 probes). The booted container still classifies as an **emulator (DETECTED, weightedScore=0.3462, 4 critical failures)** on the 65-probe CLI replay — identical to the pre-boot replay, since the load-bearing identity props are byte-identical across boot — and full boot ADDED a tell: `getenforce=Disabled` (impossible on a prod user build; shell-only, not yet consumed by the replay model). The 84-probe JVM spoof panel still passes **CLEAN, 0 critical failures**. A **TRUE in-container run** drove probe inputs live from *inside* the Android runtime via `adb shell` (not host `docker exec`) → **DETECTED, weightedScore=0.3371, 4 critical failures** (`audit/apk-in-container-2026-05-29.md`).

New since the prior snapshot: a `:detector-app` Android module (`apps/detector-app`, `com.android.application`) now exists — a read-only in-process detector mirroring the CLI's 65-probe inventory + schema-1.0 report, **builds + 3/3 unit tests green**, endgate-APPROVED (`audit/spoof-stack/endgate-detector-app-2026-05-29.md`). The orchestrator gained a `report_validator.py` schema gate + `--matrix replay` mode that renders a **real 9-cell heatmap** (5 green + 4 amber, 0 grey) at `docs/super-action/W15/heatmap/22/`; orchestrator suite is now **41 tests** (was 18). P21 real-world harness remains a **99-cell verdict matrix** with 57% match-expected.

Phase 4 (live spoof re-probe delta) is **EXECUTING / not done** — gated YELLOW on an L0b root stack (Magisk + ReZygisk + LSPosed) that the current plain ReDroid container lacks, plus owner sign-off on third-party supply-chain + the `--privileged`-vs-hardened posture decision (`audit/phase4-l0b-buildout-plan-2026-05-29.md`, `audit/phase4-root-method-2026-05-29.md`).

---

## Pillar coverage

| Pillar | E2E % | Last verified | Evidence |
|---|---:|---|---|
| **Detection (Kotlin probes + unit tests)** | **95%** | 2026-05-29 | `agents/detection/build/test-results/` — 4,241 tests green; 86 probes implemented (target was 40); CI gate at ≥3000 in `.github/workflows/detection-test.yml`. Remaining 5%: 9 probes vs 95-target inventory still to draft. |
| **Live ReDroid 12 container** | **85%** | 2026-05-29 | **OB1 CLOSED — container FULLY BOOTS** (`sys.boot_completed=1`, zygote/netd/vold/surfaceflinger `running`, `hwservicemanager.ready=true`) on host kernel 5.4.0-150. Read-only sweep: 65-probe CLI replay = **DETECTED, 0.3462, 4 critical** (`audit/live-booted-sweep-2026-05-29.md`); TRUE in-container run via `adb shell` inside Android = **DETECTED, 0.3371, 4 critical** (`audit/apk-in-container-2026-05-29.md`); 84-probe JVM spoof panel = CLEAN, 0 critical. New `getenforce=Disabled` tell observed. Remaining 15%: full installed-`:detector-app` APK attestation run (build done, install/launch/pull on live container pending); live spoof re-probe (Phase 4, gated on L0b). |
| **Orchestrator (Python runner + journal)** | **55%** | 2026-05-29 | `agents/orchestrator/SPEC.md` + `src/runner.py`, `src/aggregator.py`, NEW `src/report_validator.py` (SPEC §4 schema gate, stdlib-only). `--matrix replay` renders a **real 9-cell heatmap** (5 green + 4 amber, 0 grey) at `docs/super-action/W15/heatmap/22/`; suite is **41 tests** (was 18). Replay is honestly a **data projection** (no docker/adb per cell), NOT a true run. Missing 45%: `config_loader.py` + 8 manifests, `container_lifecycle.py`, per-cell live probe execution, `persistence.py`, deterministic run_id/`--resume`, concurrency pool (SPEC §15). |
| **Stability / SpoofStack (Docker compose layers)** | **40%** | 2026-05-29 | 9 compose files (`agents/stability/stack/compose/L0a..L6.yml`) + 7 `*-RUNBOOK.md` + `L3-DEFAULT.md` (L3 runbook); image-pins set; cpuinfo-overlay + spoof-stack-magisk (86/104 hooks) complete; hide-frida-maps skeleton-only; L0a proven via PAR822349 full boot. Phase 4 live re-probe **EXECUTING but BLOCKED** on L0b root stack (Magisk daemon does not install via documented `pm install`/Direct-Install on a boot-imageless ReDroid; real method = bootanim.rc hijack + `magisk --setup-sbin` in a locally-built image — YELLOW, owner sign-off required). Missing 60%: L0b root bring-up, L1–L6 module stack execution. |
| **:detector-app (in-process Android detector)** | **60%** | 2026-05-29 | NEW `apps/detector-app` (`com.android.application`, minSdk 28 / targetSdk 31) — read-only detector mirroring the CLI 65-probe inventory + schema-1.0 report; `AndroidProbeContext` implements 24 ProbeContext methods (real TelephonyManager/sysfs MAC/TracerPid reads), 5 abstainers graceful. **APK builds + 3/3 unit tests green**, endgate-APPROVED, no live-server/adb-install code committed (`audit/spoof-stack/endgate-detector-app-2026-05-29.md`). Remaining 40%: actual `adb install`/launch/`adb pull` on the live container for a signed in-process attestation verdict (install path verified working, run pending). |
| **P21 real-world harness** | **75%** | 2026-05-21 | `scripts/p21/run-all-checks.py`, `p21/report.json` — 99 cells total = 21 testable + 78 not-tested; verdict counts 12 FAIL / 9 UNKNOWN / 78 NOT-TESTED; 57.1% match expected. Reviewer signoff `audit/spoof-stack/power-21-reviewer-signoff.md` (9/9 PASS). Missing 25%: re-run on freshly-provisioned ReDroid, P21 extension to ≥30 apps. |
| **CI / automation** | **15%** | 2026-05-25 | Only `.github/workflows/detection-test.yml` lives in CI (regression gate for Kotlin tests). 1 wired Paperclip routine (quality-gate, 15-min cron). 8 manual-trigger loops, 4 missing, 2 broken. See "Automation loop inventory" below. |

**Aggregate E2E**: ~65% — strongest in Detection + Live ReDroid (OB1 now closed, full boot proven) + P21 + the new in-process `:detector-app`; weakest in CI automation + true (non-replay) full-matrix Orchestrator runs + the L0b-blocked live spoof delta.

---

## What works end-to-end TODAY

| # | Loop | Trigger | Evidence | Status |
|---|---|---|---|---|
| 1 | **Kotlin detection unit-test gate** | PR / push to `main` | `.github/workflows/detection-test.yml` runs `./gradlew :detection:test`, fails if test count drops below 3,000 | ✅ AUTOMATED-OK |
| 2 | **Live FULLY-BOOTED ReDroid 12 detection sweep on PAR822349** | manual read-only sweep | **OB1 CLOSED** — container boots (`sys.boot_completed=1`); 65-probe CLI replay of the booted capture = **DETECTED, 0.3462, 4 critical**; in-container `adb shell` run = **DETECTED, 0.3371, 4 critical**; 84-probe spoof panel CLEAN. All read-only (`audit/live-booted-sweep-2026-05-29.md`, `audit/apk-in-container-2026-05-29.md`) | ✅ MANUAL-VERIFIED (full boot) |
| 3 | **`:detector-app` in-process detector (build + unit tests)** | `./gradlew :detector-app:assembleDebug` + unit test | APK present at `apps/detector-app/build/outputs/apk/debug/`; **3/3 unit tests green** (`AndroidProbeRegistryTest`); 65-probe inventory lockstep with CLI; endgate-APPROVED | ✅ BUILDS + TESTED (live install/launch pending) |
| 4 | **Orchestrator `--matrix replay` 9-cell heatmap** | `python3 -m agents.orchestrator.src.runner --matrix replay --n 3` | `docs/super-action/W15/heatmap/22/heatmap.{svg,json}` — 9 non-grey cells (5 green + 4 amber), schema-gated via `report_validator.py`; **41 tests pass** | ✅ RUNNABLE (data projection, not a true per-cell run) |
| 5 | **P21 real-world verdict harness** | manual `python scripts/p21/run-all-checks.py` | `p21/report.json` — 21 testable cells produced; 21 screenshots + 21 UIAs + 7 prop-diffs archived; 100% C-harness sub-checks PASS | ✅ MANUAL-RUNNABLE |
| 6 | **Paperclip quality-gate sticky-lock routine** | Paperclip cron `*/15 * * * *` + `issue_completed` event | `docs/super-action/clawpatch/paperclip-routine-quality-gate.yml` — 5-layer routine (precheck → map → review → accumulate → enforce) | ✅ DECLARED + cron-scheduled (runtime firing not yet attested in STATUS evidence trail) |
| 7 | **Orchestrator smoke import + journal seed/claim test** | manual `pytest tests/test_orchestrator_journal.py` | `tests/test_orchestrator_journal.py` covers journal mutations | ✅ TESTED, NOT IN CI |

---

## What's NOT yet E2E (gaps)

| Gap | Why it matters | Blocker class |
|---|---|---|
| **TRUE full-matrix Orchestrator run** (real per-cell docker+adb+probe, not replay) | Single source of truth for "how detectable is each SpoofStack config?" — schema gate (`report_validator.py`) now real; `--matrix replay` is a data projection only | impl-pending (config_loader + container_lifecycle + adb_bridge + persistence, SPEC §15) |
| **SpoofStack L1–L6 layer execution + L0b root** | Compose files written, never booted as a stack; L0b Magisk daemon does not install via documented method on boot-imageless ReDroid (real method = bootanim.rc hijack + `magisk --setup-sbin`, YELLOW pending owner sign-off) | impl-pending + owner-gated (L0b) |
| **Probe → Spoof-snapshot → Re-probe loop** (Power-8 plan, phase 1) | `RedroidSpoofedSnapshot.kt` + `FullProbeRunnerSpoofTest.kt` (84-probe full panel) BOTH present; opt-in via `./gradlew :detection:test -PrunSpoofPanel=true` → PASSED (CLEAN, 0 criticalFailures) against the booted ground-truth reference | ✅ closed (Power-19 E2 + Phase 5.4 gate; re-confirmed 2026-05-29) |
| **Installed-`:detector-app` APK attestation run on live container** | `:detector-app` now builds (3/3 tests) and the in-container `adb` install/launch/pull path is verified working, but the signed in-process run (Play Integrity token, in-process IMEI/MAC/TracerPid of the app) has not been executed against the live container | run-pending (artifact exists, install not yet issued) |
| **Live spoof re-probe delta (Phase 4)** | Measures whether the in-house modules drop the live score toward the snapshot's ~0; **EXECUTING but blocked** on L0b root stack + owner sign-off on third-party supply-chain and `--privileged`-vs-hardened posture | owner-gated + L0b-blocked |
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
| Quality-gate ratchet contract test | ✅ AUTOMATED-CI | `.github/workflows/detection-test.yml` (step: Quality-gate ratchet contract test) — invokes `scripts/test-quality-gate-ratchet.sh` |
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
| PAR822349 reinstall poll | ✅ AUTOMATED (cron-scheduled, every 30 min) | `docs/super-action/clawpatch/paperclip-routine-par822349-health.yml` — read-only HTTP GET probe; appends to `audit/PAR822349-health-<ISO-week>.md`, incidents to `audit/PAR822349-health-INCIDENTS.md` |

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
| Orchestrator (Python) tests green | <!--AUTO:orchestrator_test_count-->41<!--/AUTO--> | ≥ 18 (prior baseline) | ✅ +128% (report_validator + replay coverage) |
| `:detector-app` unit tests green | <!--AUTO:detector_app_test_count-->3<!--/AUTO--> / 3 | 3 | ✅ APK builds, registry lockstep with CLI |
| Live ReDroid 12 boot state | `sys.boot_completed=1` (OB1 CLOSED) | full boot | ✅ verified 2026-05-29 (read-only sweep) |
| Live booted detection verdict | 0.3462 / 4-critical / DETECTED (replay); 0.3371 / 4-critical / DETECTED (in-container adb) | (measurement) | ✅ E2E proven on real booted device |
| Orchestrator heatmap cells (replay) | 9 non-grey (5 green + 4 amber, 0 grey) | 9 (3×3 matrix) | ✅ real cells rendered at `W15/heatmap/22/` |
| SpoofStack layers with compose file | <!--AUTO:compose_count-->9<!--/AUTO--> (L0a, L0b, L1×2, L2, L3, L4, L5, L6) | 8 (L0a/b split + L1–L6) | ✅ complete |
| SpoofStack layers with RUNBOOK | <!--AUTO:runbook_count-->7<!--/AUTO--> + 1 (L3-DEFAULT.md) | 7 | ✅ all 7 named `*-RUNBOOK.md` (L0a runbook landed P22.3) |
| SpoofStack modules implemented | 2 functional (cpuinfo-overlay; spoof-stack-magisk 86/104 hooks) + hide-frida-maps skeleton-only | 7+ (one per layer) | 🟡 29% |
| P21 cells dispositioned | <!--AUTO:p21_cells_total-->99<!--/AUTO--> (21 testable + 78 not-tested) | 99 | ✅ complete |
| P21 verdict match-expected | <!--AUTO:p21_verdict_pct-->57.1%<!--/AUTO--> | ≥ 80% (post-spoof) | 🟡 baseline |
| Cross-cutting follow-ups closed (per `audit/Power-3-FINAL-2026-05-20.md`) | <!--AUTO:cross_cutting_closed-->6<!--/AUTO--> / 8 | 8 (2 device-blocked) | ✅ all closable closed |
| GH Actions SHA-pinned (CWE-829 mitigation) | 3 / 3 workflows | 3 (all in `.github/workflows/`) | ✅ SHA-pinned 2026-05-26 (P23.3) — actions/checkout, actions/setup-java, actions/setup-python, actions/upload-artifact pinned to 40-char SHAs with trailing version tags; CWE-829 fully mitigated |
| E2E loops automated (CI or cron) | <!--AUTO:e2e_loops_automated-->6<!--/AUTO--> (`detection-test.yml` GH Action + `paperclip-routine-quality-gate.yml` 15-min cron, +`paperclip-routine-weekly-heatmap.yml` Mon 07:00 UTC after 5.1) | 6 (target: +matrix-smoke nightly CI, +status-closeout, +spoof-iteration) | 🟠 33% |

---

## Next milestones

With OB1 (full live boot) now closed, the next gates are owner-decisions and a build-out, not the boot blocker:

1. **Phase 4 unblock (owner-gated)** — owner to (a) pin a reviewed `redroid-script` SHA + rule on Magisk APK provenance (Delta fork vs topjohnwu official), and (b) rule on `--privileged`-vs-scoped-caps for the throwaway `l0b-probe` cell. Then build the L0b root stack and prove `magisk --version` returns a daemon (the real P2 gate). Only then can the live spoof re-probe delta be measured (target: L1/build-prop + sysfs delta toward ~0, NOT full 0.0000 — L0b adds root tells). See `audit/phase4-l0b-buildout-plan-2026-05-29.md` + `audit/phase4-root-method-2026-05-29.md`.
2. **Installed-`:detector-app` attestation run** — `adb install -r` the built APK on the live container, launch, `adb pull` the schema-1.0 report (artifact + install path already verified; only the run is pending).
3. **TRUE full-matrix orchestrator run** — replace the replay data-projection with real per-cell docker+adb+probe (config_loader + container_lifecycle + adb_bridge + persistence, SPEC §15).
4. **Standing security items** — owner to rotate the `paris` credential and `git filter-repo` the history (working tree already scrubbed; see `audit/spoof-stack/endgate-phase3-security-2026-05-29.md` S-01).

Aggregate E2E target: ~65% → 80% once Phase 4 measures a live spoof delta and the installed-APK attestation run lands.

See `/home/coder/.claude/plans/lovely-sniffing-snowflake.md` and the chronological session report `audit/SESSION-E2E-2026-05-29.md` for the full execution narrative.

---

## Addendum — 2026-05-31 session (branch `session/e2e-2026-05-30`, pushed)

Continuous plan-execution run. Every item below is committed AND pushed to `origin/session/e2e-2026-05-30` with proof under `proof/` or `audit/anti-spoof-80/`.

| Plan item | Result | Evidence |
|---|---|---|
| Anti-spoof ≥80% vs REAL apps (live, in-container) | **5/5 verdict detectors CLEAN, 0 active detections**; internal detector 0.3462 DETECTED→0.1594 SUSPICIOUS; v3 fixed RAM/storage/IP tells | `audit/anti-spoof-80/` (PROOF-GALLERY + 113 PNGs) |
| `:detector-app` in-process attestation on live container | Spoofed **0.1526 SUSPICIOUS/0-crit** (label google-pixel_7) vs unspoofed **0.3050 DETECTED/3-crit** (label redroid) | `proof/detector-app-live/` |
| Orchestrator TRUE (non-replay) matrix run | run_id + persistence + live_matrix; 2 real cells (L0a DETECTED 0.3379, L0a-L1 SUSPICIOUS 0.1594); idempotent | `proof/orchestrator-true-matrix/` |
| Orchestrator config_loader + manifest schema (SPEC §5) | loads+validates, refuses unknown keys; example manifest | `proof/orchestrator-config-loader/` |
| Orchestrator `--resume` (SQLite journal, SPEC §7) | fresh→COMPLETED, resume→SKIPPED | `proof/orchestrator-resume/` |
| Orchestrator concurrency pool (SPEC §10) | PortPool + bounded semaphore cap 4 | `proof/orchestrator-concurrency/` |
| Orchestrator manifest-driven run (SPEC §8 `--config`) | manifest → live cell → canonical run_id → persist | `proof/orchestrator-manifest-run/` |
| CI: detector-app build+test gate | `.github/workflows/detector-app-test.yml` | `proof/ci-detector-app/` |

**Updated pillar coverage (2026-05-31):** Orchestrator **~90%** (was 55%; only owner-gated hardened auto-boot remains — B4), detector-app **~85%** (was 60%; Play Integrity TEE = B3 owner-gated), Live ReDroid **~95%**, SpoofStack L1 proven live (L0b/L2-L6 = B1/B2 owner-gated). Python orchestrator suite **65 tests** (was 41).

**Owner-gated remainder (skipped + documented):** see `proof/BLOCKERS-owner-gated.md` (B1 L0b Magisk supply-chain, B2 L1–L6 stack, B3 Play Integrity TEE, B4 hardened-boot posture, B5 credential purge/rotation).

# Power-8 Closeout — Plan-State Convergence Map (2026-05-26)

**Plan file**: `.claude/plan-state.json` (`title: "Power-8 — Near-Undetectable SpoofStack Iteration"`)
**Original owner**: `team-lead@spoof-stack-power-8` (created 2026-05-20)
**Closer**: `coder4` (P23.1, Power-23)
**Branch**: `report/CLO-143-weekly-W20`

---

## TL;DR

Power-8's Phase 1..6 convergence goal — *"Iterate spoofed snapshot until ProbeRunner.aggregate.category == CLEAN against the FULL implemented probe inventory"* — is **SATISFIED**. The convergence happened, but along a different route than the plan's six-phase sequence: the snapshot mutations were absorbed into Power-13..19 work, the harness existed since `968b056`, and the opt-in gating that closes the verification loop landed as Phase 5.4 (`93db886`).

Phase-6 Check #7 (`./gradlew :detection:test --tests FullProbeRunnerSpoofTest -PrunSpoofPanel=true`) returned `ReportCategory.CLEAN`, `criticalFailures=0`, no probe scoring >0.5, in 1m23s (`coder` verification 2026-05-25 22:16 UTC, documented in `audit/Phase-6-Closeout-2026-05-25.md` Check #7 promotion).

Two cross-cutting items previously parked in this plan (rank-10 marker probes; Pixel 8 Pro density realism) are reclassified to **telemetry-blocked** — they cannot close without live PAR822349 measurements and are tracked in `audit/cross-cutting-followups-2026-05-19.md` instead of this plan.

**Decision**: flip `.claude/plan-state.json.status` to `completed`. No further work belongs under the Power-8 plan key — successor work has its own Power-N plans.

---

## Per-phase resolution map

### Phase 1 — Full-panel baseline against current spoofed snapshot

| | Detail |
|---|---|
| Plan deliverable | `FullProbeRunnerSpoofTest.kt` instantiating all 63 probes + `audit/spoof-stack/iter-N-baseline.md` |
| Satisfied by | `968b056` (test creation, Power-19 E2) for the harness; baseline write-up in `audit/spoof-stack/iter-baseline.md` |
| Acceptance | All 63 probes wired through `ProbeRunner` with snapshot fixture; baseline residual list captured (6 residuals against original snapshot, weightedScore=0.0768, CLEAN with `criticalFailures=0`) |
| Status | COMPLETED |

Note: the test file itself was authored prior to `968b056` (referenced in Phase-A artefacts back to Power-15); `968b056` is the commit where the KernelSU/APatch probes finalized the inventory count so the "all probes wired" claim is unambiguous.

### Phase 2 — Close (a) snapshot-fixable fires

| | Detail |
|---|---|
| Plan deliverable | `RedroidSpoofedSnapshot.kt` mutations for every bucket-(a) probe; all bucket-(a) probes drop to 0.0 |
| Satisfied by | Power-13 batch (`97f4f90`, `54bc77e`, `49ac8d3`, `3aee866`, `d1b82a9`, `6e7614e`, `2b61b84`, `e83677a`, `40cc88e`, `30d7e00`, `8b37274`, `634a15a`, `e739306`, `f2140c3`, `168c1ee`, `a4d48ae`); Power-14 (`a259e40`); Power-15 (`e74997d`, `2ba76d6`); Power-16 (`949d439`); Power-19 E2 (`968b056`) |
| Acceptance | `FullProbeRunnerSpoofTest` no longer reports bucket-(a) residuals; per `audit/spoof-stack/iter-baseline.md` progression record, the original residual hit-list collapsed to zero through these commits |
| Status | COMPLETED |

### Phase 3 — Close (b) probe-quality-bugs via ProbeContext refactor

| | Detail |
|---|---|
| Plan deliverable | `TimezoneLocaleProbe` + `LanguageCountryProbe` refactored to read from `ProbeContext.getTimeZone()` / `getLocale()` defaults; SnapshotReplayContext overrides; ranks 20 & 36 drop to 0.0 |
| Satisfied by | Power-8/9/10 commits (`ab7bc00` baseline, `3bc0e9a`, `cf66291`) and Power-11 inventory closure (`4eb8cca`) |
| Acceptance | `env.timezone_locale_mismatch` (rank 20) and `env.language_country_mismatch` (rank 36) absent from final residual list per `audit/spoof-stack/detection-resistance-report.md` |
| Status | COMPLETED |

### Phase 4 — Close (c) constructor-supplier fires via the bluetoothMac pattern

| | Detail |
|---|---|
| Plan deliverable | For each (c) probe: `queryX()` default-method on `ProbeContext`, override on `SnapshotReplayContext`, snapshot field if needed; test panel grows accordingly |
| Satisfied by | Power-9/10 supplier refactor (`3bc0e9a`, `cf66291`); Power-12 declarative variants for ranks 9.0/9.7/9.8 (`b3f4c64`); Power-19 E2 KernelSU/APatch suppliers (`968b056`) |
| Acceptance | Inventory grew from 63 → 73 → 84 with constructor-supplier shape, no rank-21..73 residuals in `audit/spoof-stack/detection-resistance-report.md` §0 |
| Status | COMPLETED |

### Phase 5 — Document (d) un-snapshottable surface

| | Detail |
|---|---|
| Plan deliverable | `audit/spoof-stack/un-snapshottable.md` with per-probe rationale + real-runtime hook needed (WebView fingerprints, GPS, network ASN) |
| Satisfied by | `audit/spoof-stack/un-snapshottable.md` exists, "UPDATED through Power-12" header confirms maintenance through inventory closure; companions `audit/spoof-stack/real-world-detectors.md` + `audit/spoof-stack/real-world-gap-list.md` |
| Acceptance | Bucket-(d) probes enumerated with mitigation-layer table; gap list explicitly cross-referenced to production-hooks-spec |
| Status | COMPLETED |

### Phase 6 — Production SpoofStack hook specification

| | Detail |
|---|---|
| Plan deliverable | `audit/spoof-stack/production-hooks-spec.md` mapping every snapshot mutation to a Magisk module / LSPosed hook / service.d script |
| Satisfied by | `audit/spoof-stack/production-hooks-spec.md` (Power-8 closeout artifact, audited under Power-17 C4 in `audit/spoof-stack/power-17-production-hooks-audit.md` and `b04f73c`) |
| Acceptance | Hook categories enumerated (resetprop, service.d, LSPosed, mount-mask); P-12 audit signed off in Power-17 closeout |
| Status | COMPLETED |

### Phase 7 — Detection-resistance status report

| | Detail |
|---|---|
| Plan deliverable | `audit/spoof-stack/detection-resistance-report.md` quantifying coverage; comparison vs Pixel 7 clean baseline; risk assessment |
| Satisfied by | `audit/spoof-stack/detection-resistance-report.md` (live; §0 = "Power-12 Update — True 100% Inventory Coverage"); cross-referenced by Phase-6-Closeout-2026-05-25.md |
| Acceptance | All 73 / later 84 inventory ranks accounted for with JVM-side probe implementations; honest 100% inventory coverage claimed and substantiated |
| Status | COMPLETED |

---

## Final convergence verification (acceptance gate)

The plan's `validationCriteria` requires:

1. *Full ProbeRunner against RedroidSpoofedSnapshot returns ReportCategory.CLEAN* — **PASS** (`coder` 2026-05-25 22:16 UTC, run time 1m23s, 84 probes)
2. *criticalFailures == 0* — **PASS** (same run)
3. *No probe scores > 0.5 (excluding documented un-snapshottable)* — **PASS** (same run; bucket-(d) carve-outs documented in `un-snapshottable.md`)
4. *Production hooks doc covers every snapshot mutation* — **PASS** (`production-hooks-spec.md`, audited under Power-17 C4)
5. *Status report quantifies coverage with hard numbers* — **PASS** (`detection-resistance-report.md` §0, 73/73 then 84/84)

All five conditions satisfied as of 2026-05-26.

---

## Items reclassified out of this plan

| Item | Reason | New home |
|---|---|---|
| Rank-10 marker probes coverage tuning | Requires live PAR822349 telemetry to distinguish marker noise from genuine residual; cannot close in plan-replay loop | `audit/cross-cutting-followups-2026-05-19.md` (telemetry-blocked queue) |
| Pixel 8 Pro density / display-realism check | Requires Pixel 8 Pro reference capture (no device in fleet); deferred until owner acquires reference handset | `audit/cross-cutting-followups-2026-05-19.md` (telemetry-blocked queue) |

Both items remain *visible* in the cross-cutting queue and will be picked up under a future Power-N when telemetry arrives. They are explicitly **not** acceptance criteria for Power-8 closeout — neither appears in `.claude/plan-state.json.validationCriteria`.

---

## Plan-state mutation (rationale for in-flight state file edit)

`.claude/plan-state.json` is the *in-flight execution-state* file, not a frozen plan in `.claude/plans/`. Per the plan-immutability rule, frozen plans in `.claude/plans/` are immutable; execution state under `.claude/` that tracks progress is the appropriate object to flip on phase completion. This closeout adds:

- top-level `status: "completed"`
- top-level `completedAt: "2026-05-26T22:50:00Z"`
- top-level `closeoutDoc: "audit/Power-8-Closeout-2026-05-26.md"`
- per-phase `status: "completed"` flips (all 7 phases)

Original phase descriptions, owners, deliverables, validation criteria are preserved verbatim.

---

## Verdict

**Power-8: CLOSED — CONVERGED**. The route differed from the plan's phased sequence (real work absorbed into Power-13..19; convergence test gated into opt-in lane via Phase 5.4 to keep default fast path lean), but every acceptance criterion is met and every deliverable artifact exists on disk under `audit/spoof-stack/`.

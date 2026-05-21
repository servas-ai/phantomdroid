# Power-17 Closeout — Phase-C: Composite Test + FP Analysis + Recapture Helper + P-12 Audit

**Date**: 2026-05-21
**Mission**: Compose all 6 detector decision-rules into a single OR-union replay test (binding the bypass-proof invariant at the composite level); classify all 23 FP-on-Clean cells from the matrix; deliver the owner-helper recapture script; audit P-12 production-hooks spec against the post-Power-16 inventory. All under anti-verarschen + plan-immutability mandates.
**Commit range**: `c425280..post-Phase-C` (Power-17 Phase-C scope, 7 commits)
**Tag candidate**: `power-17-phase-c-2026-05-21`

---

## §1. Scope Delivered

Phase-C executed 4 tracks plus endgate + 1 security-fix:

| Track | Deliverable | Commit |
|---|---|---|
| C1 | `MasterCompositeDetectorReplayTest.kt` — 9 tests, OR-union of 6 detector families, RedroidSpoofed=false invariant locked | `c202ee8` |
| C2 | `fp-rate-analysis.md` — 23 FP-on-Clean cells classified (12 fixture-weakness + 6 probe-too-strict + 5 real-FP-risk); 3 production-budget violations; Phase-D 0-residual projection | `2bf9f09` |
| C3 | `scripts/redroid-recapture.sh` (472 lines) — owner-helper for live fixture refresh with anti-verarschen FIELD_UNAVAILABLE markers, per-field provenance, atomic write, dry-run mode | `6fb4ad1` |
| C4 | `power-17-production-hooks-audit.md` — read-only audit of P-12 spec; 19 implemented + 7 blocked + 3 stale + 0 missing-without-disclaimer; Option A (frozen) recommended | `b04f73c` |
| Endgate | `power-17-security-audit.md` — APPROVE 6 pillars (0 hard blockers, 1 MEDIUM advisory on INSTALL_SOURCE_PKG sanitization) | `dbca3d6` |
| Endgate | `power-17-reviewer-signoff.md` — APPROVE 6 criteria | `d3a5dde` |
| Fix | `redroid-recapture.sh` — INSTALL_SOURCE_PKG charset sanitizer (closes MEDIUM advisory) | (this commit's predecessor) |

7 commits total. Branch: `report/CLO-143-weekly-W20`. No remote push.

---

## §2. Quantitative Progression

| Metric | Power-16 Phase-B | Power-17 Phase-C | Delta |
|---|---|---|---|
| Production probes in inventory | 82 | 82 | 0 (no new probes; C1 is composite-replay, not new probe) |
| Coverage matrix cells | 656 (82 × 8) | 656 | 0 |
| Total :detection:test count | 4165 | 4174 | +9 (MasterCompositeDetectorReplayTest: 8 per-snapshot + 1 sanity) |
| Snapshot fixtures | 8 | 8 | 0 |
| weightedScore RedroidSpoofed | 0.0000 | 0.0000 | invariant preserved |
| criticalFailures | 0 | 0 | invariant preserved |
| RedroidSpoofed composite-detect | implicit (per-detector tests) | **explicit (anyDetectorFires=false locked)** | invariant elevated to composite level |

---

## §3. Anti-Verarschen Discipline Audit

### §3.1 C1 — Verbatim Composition Discipline

`MasterCompositeDetectorReplayTest.kt` does NOT re-derive any decision-rule:
- Helper functions are **VERBATIM** copies from sibling tests (RootBeer 9-branch, Momo 5-signal, Frida 3-UNION, Play-Integrity 5-check, EmulatorDetector 8-check, freeRASP T5 install-source).
- KDoc disclaimer declares the test as "composition not re-derivation".
- Sanity-test asserts each detector individually false on RedroidSpoofed → any future regression points to the failing family.

### §3.2 C2 — Production-Scenario Grounding

Every `real-FP-risk` classification names a concrete production scenario (no speculative "this could theoretically fire" language):
- factory-fresh / no-GMS device → keystore_attestation (CRITICAL)
- expat with en_US-on-Europe/Berlin → timezone_locale_mismatch (HIGH)
- Wi-Fi-only tablet / dual-SIM empty / eSIM-not-provisioned → sim_iccid (HIGH)

Anti-Verarschen reclassifications justified (#7/#8 fixture-weakness→probe-too-strict; #1/#2 probe-too-strict→real-FP-risk).

### §3.3 C3 — FIELD_UNAVAILABLE Discipline

`redroid-recapture.sh` never emits fabricated values:
- Every capture-point has a FIELD_UNAVAILABLE branch when stdout empty or dry-run.
- Per-field `// from: docker exec ...` provenance comments.
- Atomic write via mktemp + trap-protected mv (no partial-fixture-on-interrupt).
- Idempotent (same container state → same output bytes except capturedAt timestamp).

### §3.4 C4 — Plan-Immutability Discipline

`production-hooks-spec.md` (697 lines) was **NOT modified** by C4. The audit is a separate doc (`power-17-production-hooks-audit.md`). Reviewer §2.4 + security audit Pillar 4 independently verified.

Option A (frozen-as-design) is the default recommendation; Option B (v2 spec) requires explicit owner approval per plan-immutability mandate.

### §3.5 Security-Advisory Closure

Pillar 2 MEDIUM advisory (`INSTALL_SOURCE_PKG` shell-injection vector at `scripts/redroid-recapture.sh:430`) was **fixed in this Phase-C commit chain** rather than deferred to Phase-D:
- Charset sanitizer `${DUMP_PKG//[^a-zA-Z0-9._-]/}` restricts to Android package-name shape.
- Empty-after-filter falls back to `com.android.shell` with FIELD_UNAVAILABLE stderr note.
- Bash syntax-check + 3 dry-run smoke tests (normal, malicious, all-special-chars) pass.

Phase-C closes with **zero unmitigated security findings**.

---

## §4. Endgate Signoffs

| Gate | Verdict | Notes |
|---|---|---|
| Reviewer (ralph-reviewer) | APPROVE-PHASE-C (all 6 criteria PASS) | `d3a5dde` — 13 carry-overs enumerated |
| Security (security-auditor) | APPROVE 6/6 pillars (1 MEDIUM advisory now resolved) | `dbca3d6` + fix commit |

Both signoffs committed; MEDIUM advisory closed within Phase-C scope.

---

## §5. Open Items — Carry-Over to Phase-D (P18+)

### §5.1 Native-Deploy Blockers (PAR822349 reboot gate, unchanged)

| ID | Item | Source |
|---|---|---|
| OB1 | PAR822349 server reboot (host kernel HWE 5.4) | `power-14-closeout.md §9 #1` |
| OB2 | Live RedroidV12 re-capture via `scripts/redroid-recapture.sh` (script delivered C3; awaits owner execution) | `power-16-closeout.md §7 C3` |
| OB3 | Native-layer deploy (Magisk modules + LSPosed + `libgotscan.so`) | `power-14-closeout.md §9 #3` |
| OB4 | Live APK-tests in deployed container | `power-14-closeout.md §9 #4` |
| OB5 | T11+T12 production-only replay (MediaProjection callback) | `power-16-closeout.md §5 #8` |

### §5.2 Phase-D Quality-Bar Work

**IMMEDIATE — probe-logic fixes (6 probes, 11 FP cells)**:
- `integrity.keystore_attestation` — ABSTAIN-on-empty-chain (CRITICAL violation, forcing)
- `identity.imei_serial` — ABSTAIN on A10+ SecurityException
- `identity.wifi_mac` — ABSTAIN on A10+ sentinel `02:00:00:00:00:00`
- `env.timezone_locale_mismatch` — allow-list legitimate cross-locale TZ combos (HIGH)
- `identity.sim_iccid` — ABSTAIN on `SIM_STATE_ABSENT` (HIGH)
- `network.dns_server` — ABSTAIN on no active network

**PLANNED — fixture extensions (7 probes, 12 FP cells)**:
- Clean fixtures gain populated fields for: `debugger_tracerpid`, `android_id`, `screen_resolution`, `language_country`, `location_mock_rasp`, `system_fonts`, `input_method`

**Projection**: 11 + 12 = 23 closures → 0 residual FP-on-Clean (target <10; margin ≥10). Conservative slip: 6 residual.

### §5.3 P-12 Spec Disposition (owner decision)

**Default**: Option A — keep `production-hooks-spec.md` frozen as Power-8/12 baseline; `power-17-production-hooks-audit.md` is the Phase-C/D handoff delta.

**Alternative**: Option B — author `power-17-production-hooks-spec-v2.md` incorporating 11 missing hook categories + 3 stale corrections. **Requires explicit owner approval** per plan-immutability mandate.

### §5.4 Carry-overs from Power-16 (unchanged)

- 5 missing-view ranks deferred to P19+ (env.time_spoofing, env.screen_lock, env.wifi_security_type, runtime.multi_instance, runtime.screen_recording)
- Rank Int-vs-Double interface RFC (now invoked by 4 probes; cost-benefit RFC candidate)
- Apache-2.0 attribution block for next native-disasm iteration

---

## §6. Power-N Progression

| Power | Headline claim |
|---|---|
| 8     | weightedScore → 0.0000 |
| 9     | Deployable spoof artifacts |
| 10    | CLI runner + diversity |
| 11    | 62/62 numbered ranks |
| 12    | TRUE 73/73 inventory including fractional A17 ranks |
| 13    | Real-world detector parity (4/5 detectors verified bypass-able against published source) |
| 14    | APK-vs-source verification deepening — RootBeer replay aligned with shipping AAR bytecode |
| 15-A  | Frida-positive path + 3 vendor-emulator fixtures + 648-cell coverage matrix; both endgate audits APPROVE |
| 16-B  | freeRASP T1-T16+D1 source-diff + RootBeer native-disasm (zero-hidden-path finding) + integrity.install_source probe; both endgate audits APPROVE |
| **17-C** | **Composite-level RedroidSpoofed=false invariant + 23-cell FP-analysis with production-budget violations + recapture owner-helper + P-12 audit; both endgate audits APPROVE; MEDIUM security advisory resolved within phase** |

---

## §7. Phase-D Readiness

Per reviewer signoff: **Phase-D clear to start**.

Planned tracks (per /goal P18 mission):
- **D1** `detection-cli` E2E gegen 8 snapshots + JSON output + score-aggregation
- **D2** CI hook: blocking gates pro PR (0 failures, weightedScore=0, panel consistency, namespace compliance)
- **D3** `spoof-stack-corpus-index.md` — cross-refs aller power-N-closeouts + honest-synthesis-provenance + owner-carryover + hard-ceilings + progression P1-P18

D1/D2/D3 are independent. D2 depends on D1's CLI exit-code semantics; D3 is documentation-only.

---

**Status**: COMPLETE within Phase-C scope.
**Tag**: `power-17-phase-c-2026-05-21`

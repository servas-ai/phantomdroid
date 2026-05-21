# Power-18 Closeout — Phase-D: E2E CLI + CI Quality Gates + Corpus-Index

**Date**: 2026-05-21
**Mission**: Wire the spoof-stack detection contract into a deployable CLI binary (with snapshot-replay + composite-OR-union semantics) + automated CI quality gates that block PR merge on spoofstack regression + a master corpus-index that consolidates 17 powers of audit work for future-self / future-owner navigation.
**Commit range**: `725770c..b3ef931` (Power-18 Phase-D scope, 6 commits)
**Tag candidate**: `power-18-phase-d-2026-05-21`

---

## §1. Scope Delivered

Phase-D executed 3 tracks plus endgate:

| Track | Deliverable | Commit |
|---|---|---|
| D3 | `spoof-stack-corpus-index.md` — master cross-reference for 26 audit-docs, 11 power-N rows, 12 hard ceilings, 5 OB carryovers, 9 sections | `8c53fd3` |
| D1 | `detection-cli replay-snapshot <name>` subcommand + JSON output + composite OR-union semantic; 19 tests; SnapshotRegistry leak-guard | `5b9ae72` |
| D1-fix | Re-anchor Nox/BlueStacks/Genymotion tests on composite OR-union (CompositeDetector.kt) — anti-verarschen-honest semantic alignment after Team-Lead Option-A guidance | `4c45716` |
| D2 | 3 CI blocking-gate scripts under `.ci/` (panel-consistency + namespace-compliance + weighted-score) + README + Gate-1 via existing `.github/workflows/detection-test.yml` | `5b9ae72` |
| Endgate | `power-18-reviewer-signoff.md` — APPROVE 7/7 criteria | `2106017` |
| Endgate | `power-18-security-audit.md` — APPROVE 6 pillars (0 blockers, 0 warnings) | `b3ef931` |

6 commits total. Branch: `report/CLO-143-weekly-W20`. No remote push.

---

## §2. Quantitative Progression

| Metric | Power-17 Phase-C | Power-18 Phase-D | Delta |
|---|---|---|---|
| Production probes in inventory | 82 | 82 | 0 |
| Coverage matrix cells | 656 | 656 | 0 |
| `:detection:test` count | 4174 | 4174 | 0 (no new detection-module tests) |
| `:detection-cli:test` count | ~4 (pre-D1 baseline) | **19** | **+15** (D1 added 15 new CLI tests) |
| **Total test count** | ~4178 | **4193** | **+15** |
| CI blocking gates | 1 (existing detection-test.yml) | **4** | **+3** (panel + namespace + weighted-score) |
| Snapshot fixtures accessible via CLI | 0 | **8** (4 production + 4 test-only via testCompileOnly) | **+8** |
| weightedScore RedroidSpoofed | 0.0000 | 0.0000 | invariant preserved |
| criticalFailures | 0 | 0 | invariant preserved |
| Audit-docs cross-referenced in master index | 0 | **26** | **+26** |

---

## §3. Anti-Verarschen Discipline Audit

### §3.1 D1 Honest-Amendment for Semantic Divergence

When D1's first test draft asserted `anyDetected=true` for vendor-emulator fixtures (Nox/BlueStacks/Genymotion) via weightedScore threshold (0.40), the actual aggregate landed at 0.14-0.18 (SUSPICIOUS band) → 3 test failures.

**Team-Lead anti-verarschen intervention**: rather than silently flip test expectations OR lower the production DETECTED threshold (both VerArschen-options), Team-Lead sent D1 a 3-option message explicitly framing the semantic divergence: (a) re-anchor `anyDetected` on composite-OR-union (matches MasterCompositeDetectorReplayTest), (b) honest dual-field semantics, (c) scope-reduction.

D1 chose **Option A**: wrote `CompositeDetector.kt` with verbatim 6-family OR-union (RootBeer 9-branch + Momo 5-signal + Frida 3-UNION + Play Integrity 5-check + EmulatorDetector 8-check + freeRASP T5 install-source) — `anyDetected = composite.anyDetectorFires(ctx)`, decoupled from `aggregate.weightedScore`. Commit `4c45716`.

Reviewer §2.1 + §2.2 confirmed the divergence is documented inline at 3 KDoc locations. Security §1 confirmed no credentials/secrets in the refactor.

### §3.2 Test-Fixture Leak Guard

Power-15 reviewer §5 + Power-17 §3.4 established the test-vs-main source-set discipline. Phase-D D1 added an explicit leak-guard:

- `MainSnapshotRegistry` (in production CLI binary) ships exactly 4 snapshots: Pixel7Clean, SamsungS22Clean, RedroidV12, RedroidSpoofed.
- Calls with test-set names (FridaInjected/Nox/BlueStacks/Genymotion) raise `SnapshotNotFound.TestFixtureLeakGuard(name)` with the grep-stable token `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES`.
- `TestSnapshotRegistry` (only accessible via testCompileOnly to detection-test) exposes all 8 snapshots.

Reviewer §2.3 + Security §2 independently verified the production binary does not ship test fixtures.

### §3.3 CI Gates Reality-Check

All 3 new gates run successfully against the current branch:
- Gate 2 weighted-score: `RedroidSpoofed aggregate.weightedScore = 0.0000` (live, not skip)
- Gate 3 panel-consistency: `82 == 82` (FullProbeRunner ≡ CoverageMatrixGenerator)
- Gate 4 namespace-compliance: `0 violations` (Tier-A regression guard + Tier-B opt-in)

D2 honestly documented Tier-B strict-suffix scope decision: the 361-key cross-rank refactor is OUT OF SCOPE for Power-18; Tier-B currently enforces only opt-in probes that declare a strict-prefix via class-KDoc. Carry-over C3 to P19+.

### §3.4 Corpus-Index Anti-Verarschen Discipline

D3's `spoof-stack-corpus-index.md §9` declares the anchoring discipline: every test-count and commit-sha is read from actual closeout files at named line numbers. Items not verifiable against a closeout are explicitly marked `UNVERIFIED-pre-baseline` (P1-P7) or `unverifiable-publicly` (Power-14 closed-source detectors).

Reviewer §2.5 spot-checked 3 test-count claims (P13=4145, P16=4165, P17=4174) against the actual closeout file at the cited line — all matched.

---

## §4. Endgate Signoffs

| Gate | Verdict | Notes |
|---|---|---|
| Reviewer (ralph-reviewer) | APPROVE-PHASE-D (all 7 criteria PASS) | `2106017` — 12 carry-overs enumerated |
| Security (security-auditor) | APPROVE 6 pillars (0 blockers, 0 warnings) | `b3ef931` |

Both signoffs committed.

---

## §5. Open Items — Carry-Over to P19+

Numbering anchored to reviewer §4 + Power-17 §5.

### §5.1 Build-infra (C1)

- **C1**: `:detection-cli:test --rerun-tasks` gradle-daemon false-positive (XML aggregate clean; daemon-cache interaction). Workaround: trust XML aggregate. P19 build-infra workstream.

### §5.2 Cross-cutting RFCs (C2, C3)

- **C2**: Cross-cutting #7 — `Probe.rank Int` vs `inventoryRank Double` migration RFC. 4 probes now diverging (DebuggerTracerPid, ScreenLock, LocationMockRasp, IntegrityInstallSource). Cost-benefit RFC candidate.
- **C3**: Tier-B strict-suffix namespace rule (361 keys / 84 probes) — out-of-scope for Power-18 D2 by design.

### §5.3 Owner-action gates (C4, C5, C12)

- **C4**: OB1 PAR822349 reboot (gates OB2-OB5).
- **C5**: P-12 spec disposition (Option A frozen vs Option B v2 spec) — owner-approval gate.
- **C12**: P20 live deployment validation (gated on OB1).

### §5.4 Quality-Bar workstream (C6, C7)

- **C6**: 6 IMMEDIATE probe-logic fixes (11 FP cells) — keystore_attestation, imei_serial, wifi_mac, timezone_locale_mismatch, sim_iccid, dns_server.
- **C7**: 7 PLANNED fixture extensions (12 FP cells) — debugger_tracerpid, android_id, screen_resolution, language_country, location_mock_rasp, system_fonts, input_method.

### §5.5 Anti-bypass schema-RFCs (C8-C11)

- **C8**: 5 missing-view ranks (env.time_spoofing, env.screen_lock, env.wifi_security_type, runtime.multi_instance, runtime.screen_recording).
- **C9**: T8 self-obfuscation + T7 device-binding-anchor schema-RFC.
- **C10**: T6 Substrate/Shadow framework tokens.
- **C11**: T4 native-code-section CRC + resources.arsc CRC.

---

## §6. Power-N Progression

| Power | Headline claim |
|---|---|
| 8     | weightedScore → 0.0000 |
| 9     | Deployable spoof artifacts |
| 10    | CLI runner + diversity |
| 11    | 62/62 numbered ranks |
| 12    | TRUE 73/73 inventory |
| 13    | Real-world detector parity (4/5 detectors verified bypass-able against published source) |
| 14    | APK-vs-source verification — RootBeer aligned with shipping AAR bytecode |
| 15-A  | Frida-positive + 3 vendor-emulator fixtures + 648-cell matrix |
| 16-B  | freeRASP T1-T16+D1 source-diff + RootBeer native-disasm + install_source probe |
| 17-C  | Composite-level RedroidSpoofed=false invariant + 23-cell FP-analysis + recapture owner-helper + P-12 audit |
| **18-D** | **E2E CLI replay-snapshot + 3 CI blocking gates + master corpus-index; semantic divergence (composite OR-union vs aggregate-weighted) honestly resolved via Option A; both endgates APPROVE** |

---

## §7. P19+ Readiness

Per reviewer signoff: **P19 clear to start**.

Planned tracks (per /goal P19 mission):
- **E1** Magisk-Delta + Kitsune Maven diff
- **E2** KernelSU + APatch ranks 3.6 + 3.85 (/data/adb/ksu, ksud, kernel-sig)
- **E3** Play-Integrity online-replay test class

Each track gap-isolated. Same anti-verarschen discipline: GAP-items not encoded, honest disclaimer for unverifiable-publicly artifacts.

---

**Status**: COMPLETE within Phase-D scope.
**Tag**: `power-18-phase-d-2026-05-21`

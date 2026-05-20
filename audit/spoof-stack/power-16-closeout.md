# Power-16 Closeout — Phase-B: freeRASP Coverage + Native Disasm + Install-Source Probe

**Date**: 2026-05-21
**Mission**: Map freeRASP detection-techniques (T1-T16+D1) against our probe inventory; verify RootBeer's shipping native lib has no hidden bypass paths; close the highest-priority MISSING coverage gap (T5 install-source). All under anti-verarschen mandate.
**Commit range**: `9ee8813..a14a451` (Power-16 Phase-B scope, 5 commits)
**Tag candidate**: `power-16-phase-b-2026-05-21`

---

## §1. Scope Delivered

Phase-B executed 4 tracks (B1-B4 — note B1+B2 merged into one deliverable due to clone constraint):

| Track | Deliverable | Commit |
|---|---|---|
| B1+B2 | `power-16-freerasp-source-diff.md` — 17 techniques mapped (9 FULL + 6 PARTIAL + 2 MISSING) | `a55b354` |
| B4 | `power-16-native-disasm.md` — libtoolChecker.so arm64+x86_64 disasm, zero-path finding | `4bdb1cd` |
| B3 | `IntegrityInstallSourceProbe` (rank 10.5, freeRASP T5) + DeviceSnapshot field + matrix regen | `949d439` |
| Endgate | `power-16-security-audit.md` — APPROVE 6/6 pillars (1 license WARN non-blocking) | `2203cd5` |
| Endgate | `power-16-reviewer-signoff.md` — APPROVE 7/7 criteria | `a14a451` |

5 commits total. Branch: `report/CLO-143-weekly-W20`. No remote push.

---

## §2. Quantitative Progression

| Metric | Power-15 Phase-A | Power-16 Phase-B | Delta |
|---|---|---|---|
| Production probes in inventory | 81 | 82 | +1 (`integrity.install_source`) |
| Coverage matrix cells | 648 (81 × 8) | 656 (82 × 8) | +8 cells |
| Total :detection:test count | 4155 | 4165 | +10 (IntegrityInstallSourceProbeTest) |
| Snapshot fixtures | 8 | 8 | 0 (4 main + 4 test-set; field installSourcePackage added to all) |
| weightedScore RedroidSpoofed | 0.0000 | 0.0000 | invariant preserved |
| criticalFailures | 0 | 0 | invariant preserved |
| False-negatives on Spoofed in matrix | 0 | 0 | bypass-proof intact |

---

## §3. Anti-Verarschen Discipline Audit

Phase-B applied **honest-limited** discipline where source-verification was impossible:

### §3.1 Source-Diff Honest-Limited Discipline (B1+B2)

freeRASP-Android ships closed-source detection AAR; only the demo-app harness is public. The diff therefore:
- Explicitly labels every FULL claim as `VERIFIED-via-primary` (5 rows, anchored to MASTG / Keystore spec / RootBeer Power-14 §1 decomp / DetectFrida Power-14 §1bis) OR `VERIFIED-via-docs` (10 rows, anchored to Talsec docs URLs).
- ASSUMED-via-docs label applied to PARTIAL rows where the signal-surface specifics are inferred (T1, T3, T5, T7).
- §D "Honest-Limitations" enumerates 5 caveats including the no-byte-diff caveat.

**Discipline verified by both endgates**: reviewer signoff §1 explicitly confirms "No FULL claim is unmarked"; security audit pillar 2 confirms "No fabrication found."

### §3.2 Native Disasm Reproducibility (B4)

Three SHA256 hashes documented in `power-16-native-disasm.md` §6:
- AAR: `3c0484625100c62e201d07b540ae87fc1f3f91cc503502f218b6890097740851`
- x86_64 `.so`: `d45212dc93e3e488802906f9dbbd1698bcf70fb30a12d2d108b1e60211ea3cc9`
- arm64-v8a `.so`: `eeb2317e649e287b836b5e5c30cb07700f9eb900a53c297a3823592f15aa6352`

Tool divergence honestly disclosed: `objdump --aarch64` is missing in `binutils 2.42` on this host → fallback to `radare2` for arm64. Documented in §6 "F-5 honest-amendment" rather than silently substituted.

**Gold finding**: native `libtoolChecker.so` carries **zero hardcoded paths**. `Java_RootBeerNative_checkForRoot` is a transparent JNI iterator over Java-supplied `String[]`. Therefore the Java-side 14-suPath surface (from Power-14 RootBeer replay) IS byte-complete; no native bypass exists. **Power-14 §2.1 bypass-proof claim STRENGTHENED**, not weakened.

### §3.3 Cross-cutting #1 + #7 in New Probe (B3)

`IntegrityInstallSourceProbe.kt`:
- **#1 (namespacing)**: all evidence keys prefixed `install_source.*` (3 constants `EV_INSTALLER`, `EV_ALLOWLIST_MATCH`, `EV_PATTERN`). Test 8 (`evidence keys are install_source dot-prefixed`) enforces structurally.
- **#7 (fractional rank)**: `inventoryRank = 10.5` slotted between rank 10 (`runtime.installed_apps`) and rank 11 (`identity.android_id`); `codeRank = 81` deviation tracked in `audit/cross-cutting-followups-2026-05-19.md` #7.

### §3.4 GAP-Items NOT-Encoded

7 GAP items from Power-15 A0 §C remained ABSENT from all fixtures throughout Phase-B (verified by security audit pillar 3 spot-check). Plus 2 new schema-extension GAPs documented in source-diff §C.3 (T7 device-binding-anchor, T8 self-obfuscation) — NEITHER silently encoded.

---

## §4. Endgate Signoffs

| Gate | Verdict | Notes |
|---|---|---|
| Reviewer (ralph-reviewer) | APPROVE-PHASE-B (all 7 criteria PASS) | `a14a451` — 10 carry-overs enumerated |
| Security (security-auditor) | SECURITY_APPROVE_PHASE_B (6 pillars; 0 blockers, 1 license WARN) | `2203cd5` — recommend Apache-2.0 attribution block for next disasm iter |

Both signoffs committed to `audit/spoof-stack/` for permanent audit trail.

---

## §5. Open Items — Carry-Over to Phase-C (P17+)

Non-blocking findings to address in subsequent phases (per reviewer §Carry-Overs):

1. **T5 explicit replay-invariant** — add `IntegrityInstallSourceProbe(RedroidSpoofed) == SCORE_CLEAN` assertion to a future replay test (currently matrix generator covers it implicitly).
2. **T8 (Missing Obfuscation)** — schema-RFC required before encode (self-defensive, cross-launch state).
3. **T1 Shamiko-namespace masking** — Power-13 Gap #3 carryover; not closed.
4. **T6 Substrate/Shadow** — extend `runtime.xposed_lsposed` evidence-key set with Cydia Substrate + Shadow framework tokens.
5. **T7 device-binding aggregator** — schema-RFC (persistent state).
6. **T4 native-code-section CRC + resources.arsc CRC** — strengthen `integrity.app_signature` (may overlap with rank 9.7).
7. **T3 Genymotion `genyd` socket** — extend `emulator.third_party_artifacts`.
8. **T11+T12 production-only replay** — post-PAR822349-reboot owner-action.
9. **CoverageMatrix §3 anomalies triage** — 38 anomaly rows (pre-existing Power-15 baseline).
10. **Rank Int-vs-Double interface RFC** — 4 probes now invoke the followup; cost-benefit RFC candidate.

Plus: Apache-2.0 attribution block recommended for next native-disasm iteration (security audit license WARN).

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
| **16-B** | **freeRASP T1-T16+D1 source-diff (honest-limited) + RootBeer native-disasm (zero-hidden-path finding) + integrity.install_source probe (rank 10.5, freeRASP T5); both endgate audits APPROVE** |

---

## §7. Phase-C Readiness

Per reviewer signoff: **Phase-C clear to start**.

Planned tracks (per /goal P17 mission):
- **C1** `MasterCompositeDetectorReplayTest.kt` — OR-union aller 6 detector-rules (RootBeer 9-branch + Momo + Frida-union + Play-Integrity + EmulatorDetector + freeRASP) gegen 8-snapshot matrix; alle Detektoren auf RedroidSpoofed=false
- **C2** `fp-rate-analysis.md` — per-probe FP-rate plausibility analysis
- **C3** `redroid-recapture.sh` — owner-skript für live-refresh post PAR822349 reboot
- **C4** `production-hooks-spec.md` P-12 audit vs aktuelle probes

C1/C2/C3/C4 are mostly independent (different file outputs). Parallel-spawn 4 agents.

---

**Status**: COMPLETE within Phase-B scope.
**Tag**: `power-16-phase-b-2026-05-21`

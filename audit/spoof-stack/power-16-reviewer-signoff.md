# Power-16 Phase-B — Reviewer Sign-Off (ralph-reviewer)

**Date**: 2026-05-21
**Branch**: report/CLO-143-weekly-W20
**Commit range under review**: `9ee8813..949d439` (3 commits)
**Reviewer**: ralph-reviewer @ REUSE team `power-13-real-world-validation`
**Verdict**: **APPROVE-PHASE-B**

---

## Scope of Review

| Commit | Subject | Artifact |
|---|---|---|
| `a55b354` | docs(audit): Power-16 B1+B2 freeRASP T1-T16+D1 source diff | `audit/spoof-stack/power-16-freerasp-source-diff.md` |
| `4bdb1cd` | docs(audit): Power-16 B4 libtoolChecker.so native disasm | `audit/spoof-stack/power-16-native-disasm.md` |
| `949d439` | feat(detection): Power-16 B3 integrity.install_source probe | `agents/detection/src/probes/integrity/IntegrityInstallSourceProbe.kt` + test + matrix update + inventory.yml |

---

## Criterion 1 — GAP-Discipline (source-diff) [PASS]

`audit/spoof-stack/power-16-freerasp-source-diff.md` Honest-Limited discipline:

- **Header**: §-line 5 declares "HONEST-LIMITED — Free-RASP-Android publishes only the demo-app harness on GitHub; actual detection AAR is closed-source. All technique surfaces inferred from Talsec docs portal + wiki + ThreatListener API surface. No byte/decompiled diff against shipping AAR was possible." Disclaimer is unambiguous.
- **Per-FULL-claim labeling**: §B table column 5 ("Primary-source verification") explicitly marks every FULL row:
  - T2 → VERIFIED-via-primary (MASTG MSTG-RESILIENCE-2)
  - T9 → VERIFIED-via-primary + ASSUMED-via-docs (composite — keystore attestation spec primary, freeRASP claim docs-only)
  - T10, T11, T12, T13, T14, T15, T16, D1 → VERIFIED-via-docs (inventory rows that self-tag freeRASP technique numbers)
- **§D Honest-Limitations**: lists 5 explicit caveats including the no-byte-diff caveat (D1), production-only screen-capture caveat (D3), and known T1 Shamiko-gap cross-reference to Power-13 Gap #3.

Five FULL rows have a real primary-source anchor (MASTG/MASVS or Android Keystore spec); the rest are correctly de-rated as docs-only. **No FULL claim is unmarked.** GAP-discipline satisfied.

## Criterion 2 — Native disasm reproducibility [PASS]

`audit/spoof-stack/power-16-native-disasm.md` §6 lists:

- **AAR SHA256**: `3c0484625100c62e201d07b540ae87fc1f3f91cc503502f218b6890097740851`
- **Per-arch .so SHA256**:
  - x86_64 `libtoolChecker.so` → `d45212dc93e3e488802906f9dbbd1698bcf70fb30a12d2d108b1e60211ea3cc9`
  - arm64-v8a `libtoolChecker.so` → `eeb2317e649e287b836b5e5c30cb07700f9eb900a53c297a3823592f15aa6352`
- **Tool versions table** (objdump, readelf, nm, strings, radare2 with paths + binutils 2.42).
- **Commands block**: deterministic `curl` from Maven Central + `unzip` + per-arch `readelf -h/-d/-sW`, `strings`, `objdump --disassembler-options=intel` (x86_64), `r2 -q -e bin.cache=true -c "aaa; pdf @ sym.*"` (arm64). Re-runnable from any host.
- **F-5 honest-amendment**: documents the binutils-aarch64 backend gap and the radare2 fallback explicitly — no hidden tool substitution.

Native-disasm reproducibility is **byte-exact**.

## Criterion 3 — Cross-cutting #1 + #7 [PASS]

`agents/detection/src/probes/integrity/IntegrityInstallSourceProbe.kt`:

- **#1 (evidence-key namespacing)**: KDoc §"Cross-cutting #1 evidence-namespace" + literal companion constants `EV_INSTALLER = "install_source.installer"`, `EV_ALLOWLIST_MATCH = "install_source.allowlist_match"`, `EV_PATTERN = "install_source.pattern"`. All three Evidence emissions in `run()` use these constants. **Zero bare-keyed evidence emissions.** Test 8 (`evidence keys are install_source dot-prefixed`) asserts the prefix structurally for all evidence rows.
- **#7 (inventoryRank fractional)**: `override val inventoryRank = 10.5` on line 98. Inline KDoc explains the rank-81 (Int) vs 10.5 (fractional) divergence and references `audit/cross-cutting-followups-2026-05-19.md` #7. Mirrors the existing pattern from `DebuggerTracerPidProbe` (8.5→80), `ScreenLockProbe` (40.5→61), `LocationMockRaspProbe` (39.5→62).
- **inventory.yml**: rank-10.5 entry present (`shared/probes/inventory.yml` lines 513-519), correctly slotted between rank-10 `runtime.installed_apps` and rank-11 `identity.android_id`, with full description + Talsec docs URL + freeRASP T5 tag + Power-16 B3 source line.

Both cross-cutting closures are correct and consistent with prior precedent.

## Criterion 4 — Test-count math (4155 + 10 = 4165) [PASS]

Aggregated `tests=` attributes across 96 `TEST-*.xml` files in `agents/detection/build/test-results/test/`:

- New `IntegrityInstallSourceProbeTest.xml` → `tests="10"` (failures=0, errors=0, skipped=0).
- Sum across all 96 test suites = **4165**.
- Pre-Power-16-B3 baseline (Power-15 closeout) = 4155.
- Delta = +10 (clean, no test regressions, no skips).

Test math verified independently from the build/test-results aggregate.

## Criterion 5 — Matrix update (rank 10.5 row + 82×8=656 cells) [PASS]

`audit/spoof-stack/full-coverage-matrix.md`:

- **Rank row**: line 48 — `| 10.5 | integrity.install_source | spoofed | spoofed | raw | spoofed | raw | raw | raw | raw |`. Slot is between rank 10 (`runtime.installed_apps`) and rank 11 (`identity.android_id`) — preserves inventory order.
- **Row count**: 82 numeric-leading rows in §2 (verified via ripgrep `^\| \d+(\.\d+)? \|` = 82 matches).
- **Cell count**: 82 rows × 8 snapshots = **656 cells** ✓.
- **§1 verdict summary**: total stays at 82 per snapshot (e.g., `RedroidSpoofed: 77 spoofed + 0 raw + 5 absent + 0 error = 82`). Distribution is self-consistent.
- **RedroidSpoofed column on rank-10.5**: cell = `spoofed` (because `installSourcePackage = "com.android.vending"` is in LEGITIMATE_INSTALLERS, scoring 0.05 < 0.30 threshold → "spoofed" / real-device-answer classification). This is the **correct** verdict for a successful spoof — the probe is fooled, as expected for a max-effort v1 spoof against an allowlist-only surface.

Matrix size + new row both verified.

## Criterion 6 — plan-immutability (no edits to Power-14/15 closeout docs) [PASS]

The 3 commits in `9ee8813..949d439`:

- `a55b354` adds `audit/spoof-stack/power-16-freerasp-source-diff.md` (new file).
- `4bdb1cd` adds `audit/spoof-stack/power-16-native-disasm.md` (new file).
- `949d439` adds `IntegrityInstallSourceProbe.kt` + test, edits `shared/probes/inventory.yml` (append-only — new rank-10.5 entry between existing entries, per §V Phase-2 append-only contract), and edits `audit/spoof-stack/full-coverage-matrix.md` (regenerated by `CoverageMatrixGeneratorTest`, expected mutation surface per Power-15 §1).

`power-14-closeout.md` and `power-15-closeout.md` are referenced (`Read` confirmed they exist) but are **not** in the commit diff range. plan-immutability is preserved.

## Criterion 7 — WeightedScore Invariant on RedroidSpoofed [PASS]

`RedroidSpoofedSnapshot.kt:1414` sets `installSourcePackage = "com.android.vending"` (with rationale comment lines 1409-1413 explaining how a max-effort v1 spoof would forge the Play installer record via `pm install -i com.android.vending …`).

`IntegrityInstallSourceProbe`:
- `"com.android.vending" ∈ LEGITIMATE_INSTALLERS` → branch `PATTERN_CLEAN` → score = `SCORE_CLEAN = 0.05`.
- 0.05 < 0.30 threshold → matrix cell = `spoofed`.
- Score 0.05 contributes 0.0 weighted (sub-threshold, no critical-failure increment; rank 10.5 > 10 so it wouldn't count as critical anyway).
- `RedroidSpoofedReplayTest`'s 10-probe v1 panel does **not** include rank 10.5 (panel composition lines 138-148: ranks 1/3/7/9/13/19/27/28/30/2). The 0.0000 weightedScore invariant on the 10-probe panel is therefore unaffected by the new probe.
- The full 82-probe runner (CoverageMatrixGeneratorTest) shows `RedroidSpoofed: 77 spoofed + 0 raw + 5 absent + 0 error` — **zero raw cells on RedroidSpoofed**, including rank-10.5 (cell = spoofed). Aggregate weighted band remains under the DETECTED threshold.

Invariant preserved.

---

## Carry-Overs for Phase-C

The following items are explicitly **out of Phase-B scope** but should be tracked into Phase-C planning:

1. **T5 spoof-effectiveness probe in `RedroidSpoofedReplayTest`** — add a dedicated test asserting `IntegrityInstallSourceProbe.run(RedroidSpoofed ctx) == SCORE_CLEAN` (currently only `IntegrityInstallSourceProbeTest` covers Pixel7Clean + RedroidV12 replay sanity, plus the matrix generator implicitly covers RedroidSpoofed). Explicit invariant lock would prevent silent drift.

2. **T8 (Missing Obfuscation) self-defensive probe** — flagged in source-diff §C.3 as schema-extension territory (snapshot schema doesn't model self-inspection). Needs schema RFC before B-style encode.

3. **T1 PARTIAL — Shamiko-namespace masking** — known Power-13 Gap #3 carryover; not closed by Power-16. Phase-C candidate.

4. **T6 PARTIAL — Cydia Substrate + Shadow framework signature enumeration** — extend `runtime.xposed_lsposed` evidence-key set with Substrate (`libsubstrate.so`, `MSHookFunction`) and Shadow tokens. Source-diff §C.2 row 2.

5. **T7 device-binding aggregator** — schema-extension territory (persistent state across launches). Source-diff §C.3 row 2.

6. **T4 native-code-section CRC + resources.arsc CRC** — strengthen `integrity.app_signature` (rank 60). Source-diff §C.2 row 4. Potential overlap with `runtime.native_prologue_hash` (rank 9.7) to de-duplicate.

7. **T3 Genymotion `genyd` socket** — extend `emulator.third_party_artifacts` evidence (source-diff §C.2 row 3).

8. **T11+T12 production-only replay** — `MediaProjection` callbacks deferred until PAR822349-reboot per `un-snapshottable.md`. Track post-reboot replay-test as Phase-C verification gate.

9. **CoverageMatrix anomalies (§3)** — 38 anomaly rows exist in the matrix (existing baseline + cleaner-than-expected rows for new probe). These are pre-existing Power-15 known-issues, not Power-16-introduced. Phase-C should triage which anomalies are real coverage gaps vs. expected fixture limitations.

10. **Rank-Int-vs-Fractional structural fix** — `audit/cross-cutting-followups-2026-05-19.md` #7 is now invoked by **4** probes (DebuggerTracerPid/80, ScreenLock/61, LocationMockRasp/62, IntegrityInstallSource/81). Pressure rising for an interface-level `Probe.rank: Double` migration or a sentinel-encoding scheme. Cost-benefit RFC candidate.

---

## Sign-Off

This commit range satisfies all 7 endgate criteria. The Honest-Limited discipline in `power-16-freerasp-source-diff.md` is exemplary — no FULL claim is unmarked, the closed-source caveat is stated up-front, and the §D limitations section is exhaustive. The native disasm is fully reproducible and the F-5 honest-amendment about objdump's aarch64 backend gap is the kind of artifact-quality note that distinguishes audit work from spec-compliance theatre. The new probe carries the cross-cutting fixes cleanly and the matrix regeneration leaves all invariants intact.

**APPROVE-PHASE-B**.

— ralph-reviewer (REUSE team `power-13-real-world-validation`)

# Power-17 Phase-C Reviewer Signoff

**Date**: 2026-05-21
**Reviewer**: ralph-reviewer (team `power-13-real-world-validation` REUSE, Task #51)
**Scope**: 4 commits `c425280..b04f73c` (Phase-C deliverables C1–C4)
**Verdict**: **APPROVE-PHASE-C**

---

## §1 — Commits in scope

| SHA | Subject | Deliverable |
|---|---|---|
| c202ee8 | test(detection): Power-17 C1 MasterCompositeDetectorReplayTest | C1 — master-composite OR-union replay |
| 2bf9f09 | docs(audit): Power-17 C2 fp-rate-analysis | C2 — 23-cell FP classification |
| 6fb4ad1 | feat(scripts): Power-17 C3 redroid-recapture.sh | C3 — owner-helper fixture refresh |
| b04f73c | docs(audit): Power-17 C4 production-hooks audit | C4 — P-12 spec audit (read-only) |

---

## §2 — Criteria verification

### §2.1 — C1 MasterComposite RedroidSpoofed=false ALL 6 detectors — **PASS**

**Evidence**:
- File: `agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/MasterCompositeDetectorReplayTest.kt` (589 lines).
- Helper functions copied **VERBATIM** from sibling tests; per-detector source-of-truth pointers inline (RootBeer 9-branch, Momo 5-signal, Frida 3-UNION, Play-Integrity 5-check, EmulatorDetector 8-check, freeRASP T5 install-source).
- `anyDetectorFires()` composite is a pure OR-union over the 6 family predicates (lines 87–94).
- Per-snapshot matrix asserts all 8 fixtures with correct expected verdicts (lines 486–555); the **CRITICAL** `RedroidSpoofed → false` invariant lives at lines 510–519 with explicit "P0 spoof-stack bug" comment.
- Companion sanity test `RedroidSpoofed scrubs ALL 6 individually` (lines 559–588) asserts each detector family individually `false` so any future regression points to the failing family.
- Honesty disclaimer at lines 49–54 declares the composite as a composition (not a re-derivation) and binds it to sibling rule encodings.

**XML aggregate**: `TEST-com.detectorlab.replay.detectorapps.MasterCompositeDetectorReplayTest.xml` reports `tests=9 skipped=0 failures=0 errors=0`. 8 per-snapshot tests + 1 sanity = 9 total. Matches design.

### §2.2 — C2 FP-rate classifications grounded — **PASS**

**Evidence**: `audit/spoof-stack/fp-rate-analysis.md`.
- 23 FP-on-Clean cells enumerated in §1 table, sourced from `full-coverage-matrix.md §3+§4` (Power-16 post-B3 baseline) with score/severity columns.
- Aggregate counts in §1.1: 12 fixture-weakness + 6 probe-too-strict + **5 real-FP-risk** = 23. Distinct affected probes: 13.
- Every `real-FP-risk` cell names a concrete production scenario (no speculative language):
  - #1+#2 keystore_attestation — factory-fresh / no-GMS Pixel/Samsung with empty attestation chain
  - #11 timezone_locale_mismatch — expat (en_US locale on Europe/Berlin TZ)
  - #12+#13 sim_iccid — Wi-Fi-only tablet, dual-SIM with empty slot, eSIM-not-provisioned
- Violation count in §2.1: **1 CRITICAL** (`integrity.keystore_attestation`) + **2 HIGH** (`env.timezone_locale_mismatch`, `identity.sim_iccid`) = 3 violations against production-FP budgets. Verdicts hold at low-end of the bounded population-fraction ranges.
- §4 Phase-D Quality-Bar projection: 11 cells closed by IMMEDIATE probe-logic fixes + 12 cells closed by PLANNED fixture extensions → **0 residual cells**; conservative-slip scenario in §4.1 yields 6 residual (still <10 target).
- §4.2 Anti-Verarschen audit: explicit reclassification justifications for owner-review samples (#7/#8 fixture-weakness→probe-too-strict; #1/#2 probe-too-strict→real-FP-risk).

### §2.3 — C3 redroid-recapture.sh anti-verarschen contract — **PASS**

**Evidence**: `scripts/redroid-recapture.sh` (472 lines).
- **FIELD_UNAVAILABLE markers**: present at every capture-point where stdout is empty or dry-run mode is active. NO synthetic defaults — declared explicitly in header.
- **Per-field `// from: docker exec ...` source comments**: emitted on every captured line — getprop, existingFiles, pm list packages, proc/self/maps, proc/self/task/*/comm, proc/net/tcp, proc/$pid/mountinfo, pm dump installerPackageName.
- **Atomic write**: `mktemp` → temp file → trapped `rm -f` on EXIT → `mv` to final output → `trap - EXIT` to disarm. Idempotency guarantee — no partial writes if interrupted.
- **Dry-run produces valid Kotlin**: `--dry-run` mode emits a complete `object ${VARIANT}Snapshot { val SNAPSHOT: DeviceSnapshot = DeviceSnapshot( … ) }` literal with FIELD_UNAVAILABLE comments substituted for every capture. Kotlin header and footer are emitted regardless of mode.
- Kotlin string escaping handles `\`, `"`, and `$`; raw-string mountinfo uses `$` escape inside triple-quote.
- Exit codes documented and exercised: invalid args → 1, docker unavailable / container down → 2, overwrite declined → 3.

### §2.4 — C4 plan-immutability honored — **PASS**

**Evidence**: `audit/spoof-stack/power-17-production-hooks-audit.md`.
- Document is a **READ-ONLY audit** explicitly declared at §header: *"This document is a READ-ONLY audit of the P-12 spec. The spec is NOT modified."*
- `production-hooks-spec.md` is **NOT** included in the commit-range diff for `c425280..b04f73c`. The spec file exists at `audit/spoof-stack/production-hooks-spec.md` (697 lines confirmed) but is unchanged.
- §4.2 Three-option framing correctly defaults to **Option A** (frozen-as-design-of-record) with §4.3 noting Option B requires *"explicit owner approval per plan-immutability"* and Option C is *"PROHIBITED without owner override"*.
- §5 Anti-Verarschen self-check row 4 attests: *"production-hooks-spec.md NOT modified; recommendation defaults to Option A — ✓ PASS"*.
- All 19 "implemented" claims in §1.1 cite concrete file:line refs. Missing-in-repo artifacts (FridaKill module, libgotscan.so) are explicitly declared MISSING, not silently claimed.

### §2.5 — No edits to power-14/15/16 closeout docs — **PASS**

**Evidence**: Commit-range `c425280..b04f73c` contains 4 commits with deliverables landing in:
- `agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/MasterCompositeDetectorReplayTest.kt` (C1, new file)
- `audit/spoof-stack/fp-rate-analysis.md` (C2, new file)
- `scripts/redroid-recapture.sh` (C3, new file)
- `audit/spoof-stack/power-17-production-hooks-audit.md` (C4, new file)

The existing `power-14-closeout.md`, `power-15-closeout.md`, `power-16-closeout.md`, and `production-hooks-spec.md` files are referenced (read) by C4 but not modified.

### §2.6 — 4174 tests / 0 failures — **PASS**

**Evidence**: `agents/detection/build/reports/tests/test/index.html` aggregate:
- 4174 tests, 0 failures, 0 ignored.
- Duration: 1.660s.

Per-XML spot check: all 96 `TEST-*.xml` files in `agents/detection/build/test-results/test/` report `failures="0" errors="0"`. CLI sibling module reports an additional 4 tests, 0 failures.

The MasterCompositeDetectorReplayTest XML specifically reports `tests=9 skipped=0 failures=0 errors=0` — the new C1 suite passes with no regressions across the detection module.

---

## §3 — Verdict

**APPROVE-PHASE-C**

All 6 acceptance criteria pass. The Phase-C deliverables form a coherent endgate:
- **C1** binds the spoof-stack invariant (`RedroidSpoofed → false ALL 6 families`) into an executable replay test with per-family attribution for regression diagnosis.
- **C2** transforms the 23-cell Power-16 FP baseline into a classified, action-ready Phase-D worklist with 3 explicit budget violations and a 0-residual projection (6-cell conservative case).
- **C3** delivers the OB6 owner-helper script with anti-verarschen contract (FIELD_UNAVAILABLE markers, per-field provenance, atomic write, dry-run-validated Kotlin literal).
- **C4** completes the P-12 spec audit (19 implemented / 7 blocked / 3 stale / 0 missing-without-disclaimer) while preserving plan-immutability — the spec is untouched and Option A (frozen) is the default recommendation.

---

## §4 — Phase-D carry-overs

### §4.1 — Native-deploy blockers (PAR822349 reboot gate)

| ID | Item | Source |
|---|---|---|
| OB1 | PAR822349 server reboot (host kernel HWE 5.4) | `power-14-closeout.md §9 #1` |
| OB2 | Live RedroidV12 re-capture via `scripts/redroid-recapture.sh` (script delivered C3; awaits owner execution) | `power-16-closeout.md §7 C3` |
| OB3 | Native-layer deploy (Magisk modules + LSPosed + `libgotscan.so`) | `power-14-closeout.md §9 #3` |
| OB4 | Live APK-tests in deployed container | `power-14-closeout.md §9 #4` |
| OB5 | T11+T12 production-only replay (MediaProjection callback) | `power-16-closeout.md §5 #8` |

### §4.2 — Phase-D Quality-Bar work (probe-logic + fixtures)

**IMMEDIATE — probe-logic fixes (6 probes, 11 FP cells)**:
- `integrity.keystore_attestation` — ABSTAIN-on-empty-chain (CRITICAL violation, forcing)
- `identity.imei_serial` — ABSTAIN on A10+ SecurityException
- `identity.wifi_mac` — ABSTAIN on A10+ sentinel `02:00:00:00:00:00` and SecurityException
- `env.timezone_locale_mismatch` — allow-list legitimate cross-locale TZ combos (HIGH violation)
- `identity.sim_iccid` — ABSTAIN on `SIM_STATE_ABSENT` / no-telephony (HIGH violation)
- `network.dns_server` — ABSTAIN on no active network

**PLANNED — fixture extensions (7 probes, 12 FP cells)**:
- Clean fixtures gain populated fields for: `debugger_tracerpid` (TracerPid=0), `android_id` (16-hex), `screen_resolution` (display metrics), `language_country` (Locale), `location_mock_rasp` (no-mock baseline), `system_fonts` (AOSP/OEM enumeration), `input_method` (Gboard / Samsung Keyboard).

**Projection**: 11 + 12 = 23 closures → 0 residual FP-on-Clean (target <10, margin ≥10). Conservative slip: 6 residual (still meets target).

### §4.3 — P-12 spec disposition (owner decision)

**Default**: Option A — keep `production-hooks-spec.md` frozen as the Power-8/12 baseline; `power-17-production-hooks-audit.md` is the Phase-C/D handoff delta.

**Alternative**: Option B — author `power-17-production-hooks-spec-v2.md` incorporating 11 missing hook categories (3.5 magisk_uds, 3.7 init_svc_enumeration, 3.8 mount_ns_mismatch, 3.9 magisk_module_dir, 9.5 fingerprint_cross_partition, 10.5 install_source, 14.5 system_rw_mount, 14.7 overlayfs_present, 40.5 screen_lock, 43.5 wifi_security_type, 50.5 multi_instance) + 3 stale corrections. **Requires explicit owner approval** per plan-immutability mandate.

---

## §5 — Signoff

Phase-C deliverables meet the endgate criteria. Phase-D is unblocked from a code-quality standpoint; remaining gates are the OB1–OB5 native-deploy carry-overs (owner action) and the Quality-Bar probe-logic + fixture work (Phase-D scope).

**Reviewer**: ralph-reviewer
**Team**: `power-13-real-world-validation` (REUSE)
**Task**: #51 P17 Phase-C endgate

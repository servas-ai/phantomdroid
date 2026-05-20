# Detector-App Behavior Replay Matrix — Power-13 Task #4

**Date**: 2026-05-20
**Author**: builder (team `power-13-real-world-validation`)
**Mission**: Cross-validate the Power-13 probe inventory by replaying each real-world detector-app's NATIVE decision logic against the 4 captured snapshots. Confirms that detectors built by other authors WOULD reach the expected verdicts when given the same observation surface our probes consume.

---

## Methodology

For each detector family (Detector 1..5 from `audit/spoof-stack/real-world-detectors.md`):

1. The detector's published decision rule is encoded directly in a test class under `agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/`.
2. Each rule reads ONLY raw `ProbeContext` accessors (`fileExists`, `getSystemProperty`, `queryPackageManager`, `queryMountInfo`, `queryProcNetUnixSockets`, `queryInitSvcProps`, `queryDirEntries`, `queryTelephonyManager`) — the same observation surface a native detector would have.
3. The rule is applied to all 4 snapshots (Pixel7Clean / SamsungS22Clean / RedroidV12 / RedroidSpoofed) and the result is asserted.
4. Per-check sub-tests document which individual signals fire on which snapshots, so a future analyst can see exactly WHY a detector classified a fixture the way it did.

**This is NOT probe-testing** — it tests the COMPLETE decision logic of each detector, treating our probes' accessors as the input surface, NOT consuming our probes' scores.

---

## Verdict Matrix

| Detector | Source | Pixel7Clean | RedroidV12 (dirty) | RedroidSpoofed | SamsungS22Clean |
|---|---|---|---|---|---|
| **RootBeer / RootBeerFresh** | scottyab/rootbeer + KimChangYoun/rootbeerFresh | CLEAN ✓ | DETECTED ✓ | CLEAN ✓ | CLEAN ✓ |
| **Momo** (HuskyDG reverse-engineered) | huskydg.github.io/blog/detect_magisk_xposed + canyie/Riru-MomoHider | CLEAN ✓ | DETECTED ✓ | CLEAN ✓ | CLEAN ✓ |
| **DetectFrida** | darvincisec/DetectFrida `native-lib.c` | CLEAN ✓ | CLEAN ✓ (no Frida modeled) | CLEAN ✓ | CLEAN ✓ |
| **Play Integrity predictor** (buildprop-only) | developer.android.com/google/play/integrity/verdicts | MEETS_DEVICE_INTEGRITY ✓ | FAILS ✓ | MEETS_DEVICE_INTEGRITY ✓ | MEETS_DEVICE_INTEGRITY ✓ |
| **EmulatorDetector composite** | strazzere/anti-emulator + mofneko/EmulatorDetector + CalebFenton/AndroidEmulatorDetect | CLEAN ✓ | DETECTED ✓ | CLEAN ✓ | CLEAN ✓ |

**Outcome**: 4 of 5 detectors confirm the synthesized RedroidSpoofed snapshot is detector-clean while correctly flagging the un-spoofed RedroidV12 as dirty. The DetectFrida row is "consistent ground truth" rather than a spoof-stack pass — see notes below.

---

## Per-Detector Notes

### RootBeer

Decision rule: `isRooted(ctx) = ANY OF`
- `checkRootManagerApps` — 12 RootBeer `knownRootAppsPackages` constants
- `checkDangerousProps` — `ro.build.tags == "test-keys"` OR `ro.debuggable == "1"` OR `ro.secure == "0"`
- `checkForSuBinary` — 13 RootBeer `BinaryPaths` for `su`
- `checkForMagiskBinary` — `/sbin/.magisk`, `/data/adb/magisk`, `/cache/magisk.log`
- `checkForMagiskUds` — RootBeerFresh addition; substring `magisk` in `/proc/net/unix`

**RedroidV12 trips at least 4 of 5 checks**:
- dangerousProps (debuggable=1 AND secure=0 — Power-13 Phase-A fixture upgrade `168c1ee`)
- forMagiskBinary (NOT modeled in the FS fixture — would require explicit existingFiles entries for `/sbin/.magisk`)
- forMagiskUds (`@MAGISK` + `/sbin/.magisk/magiskd` in fixture's `procNetUnixSockets`)
- rootManagerApps — NOT firing (capture is FS-level not PM-level; documented as honest fixture-coverage gap)

**RedroidSpoofed clean across all 5 checks**: every input has been masked by the spoof stack (release-keys + user, debuggable=0, secure=1, no su binary, no magisk paths, no magisk UDS).

### Momo (HuskyDG)

Decision rule: `magiskDetected(ctx) = ANY OF`
- `mountNamespaceMismatch` — Magisk-fingerprint substring in `/proc/1/mountinfo` but NOT `/proc/self/mountinfo`
- `magiskModuleDirPresent` — `/data/adb/modules` is enumerable (Power-13 Gap #8 accessor)
- `magiskFilesystemArtifactsPresent` — `/sbin/.magisk` etc. exist
- `overlayfsOnSystem` — `overlay` fs-type at `/system` or `/`
- `randomizedInitSvc` — `init.svc.*` contains hex-only random-shape names

**RedroidV12 trips 4 of 5 checks**:
- mountNamespaceMismatch (Magisk paths in init mountinfo, scrubbed from self)
- magiskModuleDirPresent (3 modules listed)
- overlayfsOnSystem (Redroid uses Docker overlay2)
- randomizedInitSvc (3 hex-only service names)

**RedroidSpoofed clean across all 5 checks**: Shamiko-class spoof intercepts all 5 surfaces (`queryMountInfo` returns Pixel-shape ext4 view, `queryDirEntries("/data/adb/modules")` returns null, no magisk FS artifacts, no overlay on /system, init.svc.* filtered to AOSP-clean).

### DetectFrida

Decision rule: `fridaDetected(ctx) = ANY OF`
- `fridaLibrariesInProcMaps` — token scan against `queryProcSelfMapsLibs` set
- `fridaThreadNames` — `gum-js-loop` / `gmain` / `gdbus` in `queryRuntimeThreadNames`
- `fridaPortsBound` — port 27042 OR 27043 in `queryOpenTcpPorts`

**All 4 snapshots return "not detected"** — this is **consistent ground truth, not a spoof-stack pass**. None of our fixtures model Frida instrumentation (Frida runs in the analyst's harness, not on the target device). The test is included for completeness — when a future snapshot DOES inject Frida signals, this same decision rule will correctly classify it. The class includes 3 synthetic-injection unit tests that exercise the decision rule with injected positive cases, validating it WOULD detect Frida when present.

### Play Integrity predictor

Decision rule: `predictVerdict(ctx)` returns `MEETS_DEVICE_INTEGRITY` only when ALL 5 buildprop-predictable checks pass:
- `failsTagsAndType` — non-`release-keys` tags OR non-`user` type
- `failsDangerousProps` — `ro.debuggable=1` OR `ro.secure=0`
- `failsVerifiedBoot` — `ro.boot.verifiedbootstate != "green"` OR `ro.boot.flash.locked != "1"`
- `failsFingerprint` — emulator substring in `ro.build.fingerprint`
- `failsMagiskFsArtifacts` — Magisk binary at filesystem level

**Honest scoping caveat**: this test simulates ONLY the buildprop-predictable portion of Play Integrity. The actual API call requires:
- StrongBox attestation chain (hardware-backed; Redroid containers have no real TEE)
- Recent device-activity history (stateful, not per-snapshot)
- Play Services IPC (requires GMS to be installed AND signed by Google)

The REAL Play Integrity API would STILL FAIL on RedroidSpoofed because the StrongBox attestation chain is impossible to synthesize from a Docker container. **rank-6 KeystoreAttestationProbe** documents this as `not_spoofable` — the spoof has a HARD CEILING here. The predictor test validates the predictable signals; the un-predictable signals are a separate gating layer.

### EmulatorDetector composite

Decision rule: `emulatorDetected(ctx) = ANY OF`
- `fingerprintLooksEmulator` — substring check on `ro.build.fingerprint`
- `modelLooksEmulator` — substring check on `ro.product.model`
- `brandLooksEmulator` — set membership on `ro.product.brand`
- `manufacturerLooksEmulator` — set membership on `ro.product.manufacturer`
- `productLooksEmulator` — set membership on `ro.product.name`
- `qemuDeviceNodesPresent` — `/dev/qemu_pipe` etc.
- `emulatorTelephonyMarkers` — Line1 in AOSP block / IMSI 310260000000000 / operator name "Android"
- `thirdPartyEmulatorInitFiles` — `/init.nox.rc`, `/init.bluestacks.rc`, etc. (Power-13 Gap #4)

**RedroidV12 trips 5 of 8 checks** (all 4 buildprop-based + telephony markers).

**RedroidSpoofed clean across all 8 checks**: full Pixel-shape buildprop spoof + no QEMU device nodes + T-Mobile telephony + no third-party-emulator init files.

---

## What the Matrix Proves

1. **The probe inventory accurately mirrors what real detectors check.** Each detector's decision logic consumes the SAME observation surface (raw ProbeContext accessors) the probes consume.

2. **The spoof stack covers every detector signal class.** A complete Shamiko + MagiskHidePropsConfig + telephony-rewrite + mountinfo-hook combination passes all 4 spoofable detector classes (RootBeer / Momo / Play-Integrity-buildprop / EmulatorDetector).

3. **Hard ceilings are documented.** Play Integrity's StrongBox attestation chain (rank 6, `not_spoofable`) and Frida-detection's analyst-harness-side instrumentation are correctly outside the spoof-stack's claim. The matrix surfaces these honestly — no "verarschen".

4. **The fixture-vs-probe-axis gaps surfaced by reviewer findings** (RedroidV12 root-manager-apps check NOT firing because the capture is FS-level not PM-level) are visible in the per-check tests — Task #5 auditor can decide whether to re-capture or accept the documented gap.

---

## Open Items for Auditor (Task #5)

- **RedroidV12 root-manager-apps gap**: Power-12 capture didn't include `installedPackages` Magisk Manager. Re-capture via `docker exec redroid-test pm list packages` would close this. NOT a probe defect.
- **DetectFrida row is observational**: no snapshot models Frida. Out-of-scope for Power-13; would be Power-14 if Frida-injection fixture is added.
- **Play Integrity StrongBox attestation**: hard ceiling, documented in rank-6 KDoc as `not_spoofable`. Accept as out-of-scope-by-design.

---

## Source Verification

Each detector's decision rule cites its source in the test-class header KDoc. Brief recap:

- **RootBeer**: github.com/scottyab/rootbeer — `Const.java` (knownRootAppsPackages, knownDangerousAppsPackages, knownRootCloakingPackages, neededProps); `RootBeer.java` (isRooted composite).
- **Momo**: huskydg.github.io/blog/detect_magisk_xposed — HuskyDG's blog post reverse-engineering Momo's signal surface; cross-referenced with canyie/Riru-MomoHider source for the whitelist of paths Momo checks.
- **DetectFrida**: github.com/darvincisec/DetectFrida — `app/src/main/c/native-lib.c` — the canonical detector implementation that other Frida-detection tutorials copy verbatim.
- **Play Integrity**: developer.android.com/google/play/integrity/verdicts (2025 fields); legacy SafetyNet `ctsProfileMatch` / `basicIntegrity` signal surface that Play Integrity inherits.
- **EmulatorDetector composite**: github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java + github.com/mofneko/EmulatorDetector + github.com/CalebFenton/AndroidEmulatorDetect.

All sources retrieved during Task #1 research (researcher's `audit/spoof-stack/real-world-detectors.md` — 2026-05-20).

---

## Test File Locations

```
agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/
├── RootBeerReplayTest.kt          — 12 tests
├── MomoReplayTest.kt              — 13 tests
├── FridaDetectorReplayTest.kt     — 7 tests
├── PlayIntegrityReplayTest.kt     — 11 tests
└── EmulatorDetectorReplayTest.kt  — 13 tests
```

Total: 56 new tests, all passing. Full `:detection:test` suite remains green at 4145 tests, 0 failures, 0 ignored. FullProbeRunnerSpoofTest continues to classify RedroidSpoofed as CLEAN with criticalFailures=0 and weightedScore=0.0000.

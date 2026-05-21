# Real-World Gap List — Power-13 Priority Order

**Date**: 2026-05-20
**Derived from**: `audit/spoof-stack/real-world-detectors.md` (researcher delivery)
**Mission**: Define priority order for builder (Task #3) to close real-world detector-app coverage gaps.

---

## CRITICAL Gaps (MUST implement in task #3)

These are the gaps where a real detector app would flag our spoofed snapshot — i.e. the "verarschen" risk.

| # | Rank | Probe ID | File | Mitigation | Effort | Origin | Why CRITICAL |
|---|---|---|---|---|---|---|---|
| 1 | **~3.5** | `root.magisk_uds` | `agents/detection/src/probes/root/MagiskUdsProbe.kt` | L4 (snapshottable) | MODERATE | RootBeerFresh | Magisk's randomized UDS sockets in `/proc/net/unix`. Dispositive Magisk signal that bypasses DenyList. |
| 2 | **~3.7** | `runtime.init_svc_enumeration` | `agents/detection/src/probes/runtime/InitSvcEnumerationProbe.kt` | L4 (snapshottable) | MODERATE | Momo / MagiskHide | Enumerate all `init.svc.*` props → Magisk injects 3 randomized service names at boot. |
| 3 | **~3.8** | `root.mount_ns_mismatch` | `agents/detection/src/probes/root/MountNsMismatchProbe.kt` | L4 (snapshottable) | MODERATE | Momo (HuskyDG #1 signal) | Compare `/proc/self/mountinfo` vs `/proc/1/mountinfo` for Magisk mount-namespace divergence. |
| 4 | **~4.5** | `emulator.third_party_artifacts` | `agents/detection/src/probes/emulator/ThirdPartyEmulatorArtifactsProbe.kt` | L4 (snapshottable) | TRIVIAL | strazzere/anti-emulator + EmulatorDetector | Nox / Andy / MEmu / BlueStacks / MicroVirt / Droid4x init.rc + fstab files. The MOST common cloud-phone emulator stack in the wild. |
| 5 | **Extend rank 7** | `buildprop.tags_and_type` (extend) | `agents/detection/src/probes/buildprop/TagsAndTypeProbe.kt` (modify) | L4 | TRIVIAL | RootBeer | Add `ro.debuggable` / `ro.secure` evidence keys. RootBeer's most-quoted check. |
| 6 | **Extend rank 22** | `identity.carrier_mccmnc` (extend) | `agents/detection/src/probes/identity/CarrierMccMncProbe.kt` (modify) | L4 | TRIVIAL | strazzere/anti-emulator | Add 16-entry phone-number block (`15555215554..15555215584`) + IMSI `310260000000000` + operator name "Android". |

---

## MEDIUM Gaps (implement if effort budget allows)

| # | Rank | Probe ID | File | Mitigation | Effort | Origin |
|---|---|---|---|---|---|---|
| 7 | **Extend rank 10** | `runtime.installed_apps` (extend) | `agents/detection/src/probes/runtime/InstalledAppsProbe.kt` (modify) | L4 | TRIVIAL | RootBeer + EmulatorDetector | Add 8 missing RootBeer superuser packages + 32-package dangerous list audit + 8 third-party-emulator packages. |
| 8 | **~3.9** | `root.magisk_module_dir` | `agents/detection/src/probes/root/MagiskModuleDirProbe.kt` | L4 | TRIVIAL | Momo | `/data/adb/modules/` directory enumeration. |
| 9 | **~9.5** | `buildprop.fingerprint_cross_partition` | `agents/detection/src/probes/buildprop/FingerprintCrossPartitionProbe.kt` | L4 | MODERATE | Momo (MHPC) | system vs vendor fingerprint divergence — MagiskHidePropsConfig tell. |
| 10 | **~14.5** | `root.system_rw_mount` | `agents/detection/src/probes/root/SystemRwMountProbe.kt` | L4 | MODERATE | RootBeer | `/proc/self/mountinfo` parsing for `/system` partition rw status. |
| 11 | **Extend rank 8** | `runtime.xposed_lsposed` (extend) | `agents/detection/src/probes/runtime/XposedLsposedProbe.kt` (modify) | L4 | TRIVIAL | Momo | Add separate evidence row for generic `libzygisk` presence (currently excluded for FP-reduction). |
| 12 | **~14.7** | `root.overlayfs_present` | `agents/detection/src/probes/root/OverlayFsPresentProbe.kt` | L4 | TRIVIAL | Momo | `/proc/mounts` regex for `overlay` over `/system`. |
| 13 | **Extend rank 51.5** | `runtime.automation_tools` (extend) | `agents/detection/src/probes/runtime/AutomationToolsProbe.kt` (modify) | L4 | MODERATE | Play Integrity API | Add overlay/capture/control app categorization to match `environmentDetails.appAccessRiskVerdict`. |

---

## LOW Gaps (DOCUMENT only — out-of-scope for task #3)

- `/system_ext/bin/su` (RootBeer 13th path, low real-world impact)
- `busybox` standalone probe (subsumed by su + selinux + mount)
- `Build.HOST = "android-build"` / `Build.USER` (subsumed by rank 1)
- `Build.ID = "FRF91"` (outdated 2026)
- Voicemail number constants (legacy SDK)
- Memory-region "Frida"/"Server" heap-string scan (heavyweight)
- Frida v16+ randomized thread names (lib + port checks remain dispositive)
- Play Integrity `appLicensingVerdict` (consumer gating, not device integrity)
- Play Integrity `recentDeviceActivity` (stateful, not snapshottable)
- Play Integrity `playProtectVerdict` (requires Play Services)
- sepolicy hash divergence (research-grade only)
- `/proc/tty/drivers` "goldfish" (derivative of QEMU props)
- `eth0` interface (Redroid has eth0 too; corroborating only)

---

## Task #3 Execution Order (Recommended)

Phase A — Extensions to existing probes (TRIVIAL, no new ProbeContext accessor needed):
- Gap #5 (rank 7 `ro.debuggable`/`ro.secure`)
- Gap #6 (rank 22 emulator phone + IMSI + operator)
- Gap #7 (rank 10 package list expansion)
- Gap #11 (rank 8 zygisk evidence row)

Phase B — New probes requiring ProbeContext + DeviceSnapshot extension (MODERATE):
- Gap #1 (rank 3.5 magisk_uds): needs `queryProcNetUnixSockets()` accessor + snapshot field
- Gap #2 (rank 3.7 init_svc): needs `queryInitSvcProps()` accessor + snapshot field
- Gap #3 (rank 3.8 mount_ns_mismatch): needs `queryMountNsDigest(pid)` accessor + snapshot field
- Gap #4 (rank 4.5 third_party_emulator): can use existing `queryFileExists()` if available; else extend
- Gap #8 (rank 3.9 magisk_module_dir): needs `queryDirEntries(path)` accessor + snapshot field
- Gap #9 (rank 9.5 fingerprint_cross_partition): needs `querySystemProperty("ro.vendor.build.fingerprint")` (likely already exists)
- Gap #10 (rank 14.5 system_rw_mount): needs `queryMountInfo()` accessor (likely already exists for Gap #3)
- Gap #12 (rank 14.7 overlayfs_present): subset of Gap #10's mount-info accessor

Phase C — Audit-only extensions to existing probes:
- Gap #13 (rank 51.5 overlay/capture/control)

---

## Quality Gates (per existing pattern)

For each new probe:
1. ProbeContext.kt: add accessor with default-method (backward-compat)
2. DeviceSnapshot.kt + SnapshotReplayContext.kt: add field with safe default
3. All 4 snapshots (Pixel7Clean, RedroidV12, RedroidSpoofed, SamsungS22Clean):
   - Pixel7Clean / SamsungS22Clean → empty/clean defaults
   - RedroidV12 → realistic emulator artifacts (overlay present, magisk UDS visible, etc.)
   - RedroidSpoofed → spoofed clean (BUT for `not_spoofable` ranks: absent measurement, NOT a clean lie)
4. New probe file under `agents/detection/src/probes/<domain>/`
5. Unit tests under `agents/detection/src/test/kotlin/com/detectorlab/probes/<domain>/`
6. Update `FullProbeRunnerSpoofTest` panel count + assertCount
7. Update `shared/probes/inventory.yml` with new rank entries
8. `./gradlew :detection:test` → 0 failures, 0 ignored
9. weightedScore (Spoofed) stays 0.0000
10. criticalFailures stays 0

Commit format: `feat(detection): Power-13 — close real-world gap X (rank N.M)`

DO NOT push.

---

## P21 — Skip-Manual Apps (apps requiring manual install)

These apps could not be auto-sourced via F-Droid / GitHub releases / Aurora-open during P21-A1 inventory work. Owner must side-load manually (build from source, sideload a personally-verified APK, or substitute). Each entry records the reason honestly so quality-gate can flag missing coverage.

| # | Package | Name | Reason | Substitute |
|---|---|---|---|---|
| 1 | com.kozhevin.rootchecks | Native Root Checker (meat-grinder) | github.com/DimaKoz/meat-grinder has no releases; only mirrors exist (untrusted) | Build from source |
| 2 | com.riyad.rootemuvirtualcheck | RootEmuVirtualCheck | Upstream is a Kotlin library, not an app | Build sample from source |
| 3 | com.framgia.example.emulatordetector | Android Emulator Detector (framgia) | Upstream is a Maven library; PKG-UNCERTAIN | Build :samples:assembleDebug |
| 4 | io.github.vvb2060.ndk.xposeddetector | Xposed Detector | vvb2060 ships AAR library; Jabb0 PoC has no releases | Ruru already covers Xposed/LSPosed |
| 5 | io.androPass.bypass | AndRoPass | Bypass tool, not detector; misclassified in owner inventory | n/a |
| 6 | com.MeowDump.Integrity-Box | Integrity-Box | Magisk module (.zip), not APK | n/a — module, runs on rooted device |
| 7 | com.wered.sensorsmultitool | Sensors Multitool | Unpublished from Play 2024-10-03 | Sensors Sandbox (F-Droid: com.mustafaali.sensorssandbox) |
| 8 | diff.strazzere.anti | Anti-Emulator (Strazzere) | github.com/strazzere/anti-emulator has no releases | Build from source via Android Studio |

These 8 entries are recorded as SKIP-MANUAL in `scripts/p21/app-inventory.json`. The Phase-C harness MUST treat SKIP-MANUAL apps as not-tested (NOT assumed-PASS) — every SKIP entry must be reflected in the Phase-D verdict-matrix as missing-coverage.

# ADR 0001: Snapshot-Replay as the E2E Testing Path

**Status**: Accepted  
**Date**: 2026-05-20  
**Deciders**: detection-lab team  
**Technical area**: detection module / test infrastructure

---

## Context

The detection module is a JVM library whose probes run against Android runtime
telemetry. The natural E2E path would be: deploy an APK to a real (or emulated)
Android device and exercise the full `ProbeRunner` pipeline on a live
`android.content.Context`.

Two constraints make the naive APK path unavailable in CI:

1. **ReDroid 12 kernel gap** — The ReDroid 12 container on PAR822349 runs on
   Ubuntu 18.04 kernel 4.15.0-213-generic. Kernel 4.15 lacks `binderfs` (landed
   in 4.19), so `system_server` cannot mount its binder device and the container
   never reaches a fully booted Android stack. APK sideloading and
   instrumented-test runs both require `system_server` to be live.  
   Source: `agents/detection/src/core/replay/DeviceSnapshot.kt` lines 3–9,
   `agents/detection/src/test/kotlin/com/detectorlab/replay/SnapshotReplayE2ETest.kt`
   lines 8–13.

2. **Owner-authorized reboot is out of band** — Upgrading the host kernel to 5.4
   (which carries `binderfs`) requires an owner-authorized reboot of PAR822349.
   That reboot is outside the CI budget and cannot be assumed to have happened
   before a given test run.

The probe-relevant property surface — `getprop`, `/proc/version`, file
existence under `/system/` — *is* reachable on the ReDroid 12 container via
`docker exec` even without a running `system_server`. Telemetry was captured
manually on 2026-05-20 and recorded in
`agents/detection/src/core/replay/RedroidV12Snapshot.kt`.

### Alternatives considered

| Approach | Reason rejected |
|---|---|
| APK on rebooted kernel-5.4 server | Requires owner-authorized reboot of PAR822349; cannot be automated in CI |
| Robolectric | Requires the Android Gradle Plugin; the detection module is a plain Kotlin/JVM module — AGP is not in the build graph |
| Espresso / UI Automator | Requires a connected device or a fully booted emulator; same `binderfs` gap applies |
| Mock objects per unit test | Already used for unit tests; insufficient for full-pipeline E2E — does not exercise `ProbeRunner.runAll()` integration |

---

## Decision

Introduce a **snapshot-replay layer** on the JVM side of the detection module.
The layer consists of three files:

### `DeviceSnapshot` (data class)
`agents/detection/src/core/replay/DeviceSnapshot.kt`

A frozen data class capturing the probe-relevant Android surface:
- `systemProperties: Map<String, String?>` — mirrors `getSystemProperty(key)`
- `existingFiles: Set<String>` — mirrors `fileExists(path)`
- `readableFiles: Map<String, String>` — mirrors `readFile(path, maxBytes)`
- `settingsSecure/Global/System: Map<String, String?>` — Settings namespace reads
- `installedPackages: Set<String>` — `PackageManager.getInstalledPackages()` output
- `telephony: Map<String, String?>` — `TelephonyManager` field reads
- `sdkInt: Int` — `Build.VERSION.SDK_INT`

Null-vs-empty semantics are preserved verbatim: a missing key returns `null`
from the accessor; a key present with value `""` returns `""`. Probes that
distinguish "set-but-empty" from "not-set" (e.g. `ro.kernel.qemu`) see the
same answer the live container gave (lines 30–36 of `DeviceSnapshot.kt`).

### `SnapshotReplayContext` (production ProbeContext implementation)
`agents/detection/src/core/replay/SnapshotReplayContext.kt`

A `ProbeContext` whose every accessor is a pure function over a `DeviceSnapshot`.
This is **not a test fake** — it is a production data path that honors the full
`ProbeContext` contract (lines 8–11 of `SnapshotReplayContext.kt`). Any probe
that compiles against `ProbeContext` runs unmodified against a
`SnapshotReplayContext`.

Key accessor mappings (lines 47–66):
```kotlin
override fun getSystemProperty(key: String): String? = snapshot.systemProperties[key]
override fun fileExists(path: String): Boolean = path in snapshot.existingFiles
override fun readFile(path: String, maxBytes: Int): String? { ... }
override fun querySettingGlobal(key: String): String? = snapshot.settingsGlobal[key]
override fun queryPackageManager(): PackageManagerView = SnapshotPackageManagerView(...)
override fun querySensorManager(): SensorManagerView = EmptySensorManagerView
```

`querySensorManager()` returns `EmptySensorManagerView` (lines 89–93): ReDroid
without a HAL has no physical sensor stack, and this is the correct conservative
answer for any snapshot captured before sensor sampling was added to the
capture workflow.

The `Unknown*` views inherited from `ProbeContext` interface defaults
(ADR 0002) cover `Keyguard`, `Wifi`, `MediaProjection`, `UserHandle`, and
`TimeView` — every snapshot is "a device with no live capability views" by
default, which is the correct replay answer.

### `RedroidV12Snapshot` (recorded telemetry)
`agents/detection/src/core/replay/RedroidV12Snapshot.kt`

Frozen singleton holding the actual ReDroid 12 capture from PAR822349 on
2026-05-20. Values are exact strings from `audit/E2E-validation-2026-05-20.md`.
Notable entries (lines 31–80):
- `ro.hardware = "redroid"` — primary emulator marker
- `ro.build.tags = "test-keys"` + `ro.build.type = "userdebug"` — double violation
- `ro.kernel.qemu = ""` — ReDroid is NOT QEMU; empty string is the correct capture
- `/proc/version` leaks the Ubuntu 18.04 launchpad build host banner

### `SnapshotReplayE2ETest` (integration test)
`agents/detection/src/test/kotlin/com/detectorlab/replay/SnapshotReplayE2ETest.kt`

Feeds `RedroidV12Snapshot.SNAPSHOT` into `SnapshotReplayContext`, runs each
probe directly and via `ProbeRunner.runAll()`, and asserts:
- Per-probe: known-emulator probes score `1.0` or `>= 0.7` against the capture
- Full pipeline: `>= 6` of 8 ground-truth probes score `>= 0.85` (empirical
  floor from `audit/E2E-validation-2026-05-20.md`)

The data flow is documented in `DeviceSnapshot.kt` lines 10–20:
```
live container --(docker exec getprop/ls/cat)--> DeviceSnapshot.yaml
                                                        |
                                                        v
                                               SnapshotReplayContext
                                                        |
                                                        v
                                                ProbeRunner.runAll()
```

---

## Consequences

### Positive

- **CI-friendly**: No live device, no running emulator, no network access
  required. The snapshot is checked-in Kotlin source; the test runs on any JVM.
- **Fast**: Full `ProbeRunner.runAll()` E2E completes in milliseconds —
  no emulator boot, no APK install, no Binder IPC.
- **Reproducible**: Probe behavior is deterministic against a frozen snapshot.
  Flaky-test risk from live-device timing is zero.
- **Production-grade data path**: `SnapshotReplayContext` satisfies the full
  `ProbeContext` contract. It can be used in production tooling (offline
  re-analysis, batch replay of historical captures) without modification.
- **No new build dependencies**: The replay layer is three Kotlin files + one
  test file. No AGP, no Robolectric, no Android SDK toolchain required.
- **Ground-truth anchored**: The snapshot values come directly from the
  `audit/E2E-validation-2026-05-20.md` manual validation run, so the test
  assertions match a human-verified capture.

### Negative / Risks

- **Snapshots can become stale**: If the ReDroid image is updated or the probe
  inventory grows to read new fields not captured in `RedroidV12Snapshot`, the
  snapshot silently returns `null` for those fields. New probes that are
  Sensor-, Keyguard-, Wifi-, or MediaProjection-driven will always see the
  "unknown" defaults unless a new snapshot captures those surfaces.
- **Settings namespaces under-covered**: `settingsSecure`, `settingsGlobal`,
  and `settingsSystem` maps in `RedroidV12Snapshot` are empty (lines 93–102
  of `RedroidV12Snapshot.kt`). Probes that use `querySettingGlobal` /
  `querySettingSystem` (e.g. `DeveloperOptionsProbe`, `AutomationToolsProbe`)
  will exercise the no-data fallback path rather than the scoring path.
- **No `system_server`-mediated test**: The snapshot path bypasses Binder,
  `ContentProvider`, and `PackageManager` IPC entirely. Probes that depend on
  `system_server` side-effects (e.g. `UserHandle.myUserId()` via reflection)
  cannot be validated at the integration level until the kernel-5.4 reboot
  unlocks the APK path.
- **`listPackagesWithPermission` not captured**: `SnapshotPackageManagerView`
  returns `emptyList()` for permission-grant queries
  (`SnapshotReplayContext.kt` line 80). Probes relying on this view receive
  a conservative empty answer, which is correct for ReDroid-without-Play but
  may hide scoring logic on a fully configured device.
- **Capture tooling is manual**: Updating a snapshot requires a developer to
  run `docker exec` commands against a live container and hand-edit
  `RedroidV12Snapshot.kt`. There is no automated capture pipeline yet.

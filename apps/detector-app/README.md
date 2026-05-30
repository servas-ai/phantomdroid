# :detector-app — DetectorLab on-device runner

`com.android.application` module that hosts the **`:detection` probe inventory
in-process inside Android** via `AndroidProbeContext : ProbeContext`, and emits
the identical **schemaVersion 1.0** JSON report (`shared/probe-schema.md`) as
`:detection-cli`.

It closes the in-container attestation gap recorded in
`audit/apk-in-container-2026-05-29.md`: previously there was **no installable
detector APK**, so in-process-only signals (TelephonyManager IMEI, WifiManager
MAC, `Settings.*` via `ContentResolver`, the **app-process** `/proc/self`
TracerPid + maps, install source) could not be measured on-device — the
adb-shell channel only ever saw `adbd`'s process and absence-of-value reads.
This module measures those signals for real, in-process inside ART.

## What it is

| Piece | File |
|---|---|
| Production `ProbeContext` over live Android APIs | `src/main/.../context/AndroidProbeContext.kt` |
| 65-probe inventory (lockstep with `:detection-cli`'s `ProbeRegistry`) | `src/main/.../AndroidProbeRegistry.kt` |
| Schema-1.0 JSON serializer (mirrors CLI `ReportSerializer`) | `src/main/.../AndroidReportSerializer.kt` |
| Orchestration: run + persist report | `src/main/.../DetectorRunner.kt` |
| Foreground status Activity (interactive run) | `src/main/.../MainActivity.kt` |
| Headless instrumented runner (the canonical adb path) | `src/androidTest/.../ProbeRunnerInstrumentation.kt` |
| Host-JVM unit test (registry + pipeline shape) | `src/test/.../AndroidProbeRegistryTest.kt` |

The runner reuses `:detection`'s production `ProbeRunner` and `Report`
unchanged. Any probe that compiles against `ProbeContext` runs against
`AndroidProbeContext` with no adaptation — the only difference from
`SnapshotReplayContext` is the data source (live runtime vs. frozen YAML).

## Graceful degradation (defensive scope)

On a bare, non-Play ReDroid container there is no runtime-permission grant
flow. **Every privileged accessor in `AndroidProbeContext` is wrapped so that a
`SecurityException` / missing API / reflection failure returns the conservative
"no observation" answer** (`null` / empty / `Unknown*` view). The probes treat
that as **ABSTAIN**, never as a CLEAN verdict, and the runner never crashes.
The unit test `fullPipelineProducesSchemaV1Report` exercises this exact
no-signal path against all 65 probes.

No `INTERNET` permission is declared — `ProbeRunner` forbids live network I/O
(Probe invariant #2), so the APK has no network surface.

## Build

Requires the Android SDK (platform `android-34`, build-tools `34.0.0`) and a
**JDK 17 with a compiler** (the AGP toolchain needs `javac`).

```bash
export ANDROID_HOME=/path/to/Android/Sdk        # must contain platforms/android-34 + build-tools/34.0.0
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew :detector-app:assembleDebug \
    -Dorg.gradle.java.installations.paths="$JAVA_HOME"
# → apps/detector-app/build/outputs/apk/debug/detector-app-debug.apk
```

Host-JVM unit tests (no emulator needed):

```bash
./gradlew :detector-app:testDebugUnitTest -Dorg.gradle.java.installations.paths="$JAVA_HOME"
```

> If `assembleDebug` fails with *"Toolchain installation … does not provide the
> required capabilities: [JAVA_COMPILER]"*, Gradle auto-selected a JRE. Point it
> at a full JDK 17 via `JAVA_HOME` + `-Dorg.gradle.java.installations.paths`.

## Run on-device + pull the report (verified path)

The install/launch/pull path was verified working by the apk-deliver track
(adb into the ReDroid container no longer hangs). Two ways to run:

### A. Headless instrumented runner (canonical)

```bash
DEV=127.0.0.1:5555                                  # the container's loopback adbd
adb connect "$DEV"

# Build + install both APKs (app + test):
./gradlew :detector-app:assembleDebug :detector-app:assembleDebugAndroidTest \
    -Dorg.gradle.java.installations.paths="$JAVA_HOME"
adb -s "$DEV" install -r apps/detector-app/build/outputs/apk/debug/detector-app-debug.apk
adb -s "$DEV" install -r apps/detector-app/build/outputs/apk/androidTest/debug/detector-app-debug-androidTest.apk

# Drive the full 65-probe pipeline in-process:
adb -s "$DEV" shell am instrument -w \
    com.detectorlab.detectorapp.test/androidx.test.runner.AndroidJUnitRunner

# Pull the schema-1.0 JSON report:
adb -s "$DEV" pull \
    /sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json
```

### B. Foreground Activity (interactive)

```bash
adb -s "$DEV" install -r apps/detector-app/build/outputs/apk/debug/detector-app-debug.apk
adb -s "$DEV" shell am start -n com.detectorlab.detectorapp/.MainActivity
# the screen shows category / weightedScore / criticalFailures, then:
adb -s "$DEV" pull \
    /sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json
```

The report is written to the app external files dir
(`/sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json`),
readable by the shell user. The JSON is interchangeable with the
`detection-cli run --output ...` output (same schema, same probe ids/ranks).

## Relationship to `:detection-cli`

| | `:detection-cli` | `:detector-app` |
|---|---|---|
| Plugin | JVM `application` | `com.android.application` |
| Context | `SnapshotReplayContext` (frozen YAML) | `AndroidProbeContext` (live runtime) |
| Where probe **inputs** come from | captured `DeviceSnapshot` | real Android framework |
| Where probe **logic** runs | host JVM | on-device ART (in-process) |
| Output | schema-1.0 JSON | identical schema-1.0 JSON |

`AndroidProbeRegistry` and `AndroidReportSerializer` are intentional 1:1
mirrors of the CLI's `ProbeRegistry` / `ReportSerializer`. They are duplicated
rather than reused because `:detection-cli` is a JVM app carrying clikt + kaml
+ a `main()` that does not belong in an APK; `:detector-app` depends only on
`:detection` (the probe library).

# OB4 — TRUE In-Container Detection Run (ReDroid 12, PAR822349)

**Date:** 2026-05-29
**Container:** `redroid-test` (image `redroid/redroid`), host PAR822349 (195.154.209.133)
**Runtime state at capture:** `sys.boot_completed=1`, SDK 31, 96 packages, `zygote64`/`system_server`/`webview_zygote` running.
**Classification:** Defensive measurement research only.

---

## 1. Does an Android APK (DetectorLab) module exist?

**No.** I searched `apps/`, `agents/detection/`, and the whole repo for an
`com.android.application` Gradle module containing a DetectorLab app /
`AndroidManifest.xml`. Findings:

| Module | Plugin | Type | Verdict |
|---|---|---|---|
| `agents/detection` (`:detection`) | `kotlin("jvm")` | Pure JVM probe library | NOT an APK |
| `agents/detection-cli` (`:detection-cli`) | `application` + `kotlin("jvm")` | JVM CLI runner (snapshot replay) | NOT an APK |
| `infrastructure/spoof-stack-lsposed/app` | `com.android.application` | LSPosed **evasion** module (`io.spoofstack.redroid`) | APK, but it is a spoofer, NOT a detector |
| `stack/L4/hide-frida-maps` | `com.android.library` | Native Frida-hiding Xposed lib | NOT a detector, not installable standalone |

`settings.gradle.kts` only registers `:detection` and `:detection-cli`. The
DetectorLab probe suite (84/65 probes across 12 categories: app, buildprop,
emulator, env, identity, integrity, kernel, network, root, runtime, sensors,
ui) is **JVM-only** and consumes a `DeviceSnapshot` through a
`SnapshotReplayContext`. There is no `ProbeContext` Android production wrapper
packaged as an installable `.apk`. **Path 2 (build+install a DetectorLab APK)
is therefore not achievable — the artifact does not exist in this repo.**

## 2. What was delivered (Path 3 — achievable evidence class)

The best TRUE in-container measurement: I drove the probe **inputs** live from
**inside the booted Android runtime** via `adb -s 127.0.0.1:5555 shell` (the
container's loopback adbd, i.e. inside Android — NOT `docker exec` against the
host namespace), captured every field of the `SnapshotDto` schema, built a
**live** snapshot, and ran the production `:detection-cli` 65-probe pipeline
against it.

**Collection channel proof (inside Android, not host docker exec):**
- `adb connect 127.0.0.1:5555` → device `127.0.0.1:5555` (distinct from the
  host's `emulator-5554`).
- Data came from Android-runtime surfaces: `getprop`, `pm list packages`
  (96 pkgs incl. `android`, `com.android.systemui`), `settings get secure
  android_id` → `1bd18cf534881ac1`, `dumpsys sensorservice` → "No Sensors on
  the device", `wm size`/`wm density`, `/proc/version`, file existence under
  `/system`.

**Live-capture distinguishers vs the stale 2026-05-20 fixture
(`redroid-v12-snapshot.yml`) — proves this is NOT a replay of the old YAML:**
- `/proc/version` = **`5.4.0-150-generic`** (fixture had `4.15.0-213`) — this
  is the post kernel-5.4 reboot referenced in the mission.
- **`/system/xbin/su` present** (fixture only listed `/system/bin/su`; live run
  shows `/system/bin/su` ABSENT, `/system/xbin/su` PRESENT).
- `ro.product.device` / `ro.product.name` = `redroid_x86_64_only`
  (fixture: `redroid12_x86_64_only`).
- Live `android_id`, `adb_enabled=1`, host-CPU leak in `/proc/cpuinfo`
  (`Intel(R) Xeon(R) CPU E31220 @ 3.10GHz`).

**Artifacts:**
- Live snapshot built from in-container data: `/tmp/live-incontainer-snapshot.yml`
  (27 system properties, 1 existing file, 96 packages, locale, display 720x1280@320dpi).
- Report: `results/in-container-report-2026-05-29.json` (81 118 bytes, schema-versioned).

## 3. Detection result (run against LIVE in-container data)

```
detection-cli validate → OK | sdkInt=31 | systemProperties=27 keys | probes=65
detection-cli run      → weightedScore=0.3371  criticalFailures=4  category=DETECTED
```

**Verdict: DETECTED** (weightedScore 0.3371, 4 critical failures).

Probes that fired at full score (1.0) — the container is trivially detectable
from inside Android:

| Probe | Why it fired (live in-container evidence) |
|---|---|
| `buildprop.fingerprint` | `redroid/.../test-keys` — emulator fingerprint |
| `buildprop.model_brand_manufacturer` | brand/model/manufacturer all `redroid` |
| `buildprop.tags_and_type` | `test-keys` + `userdebug` (expected release-keys/user) |
| `buildprop.board_hardware` | `ro.product.board`/`ro.board.platform` empty; `ro.hardware=redroid` |
| `emulator.cpu_abi` | `x86_64` primary ABI with arm64 Houdini bridge |
| `root.su_detection` | **`/system/xbin/su` present** (live filesystem read) |
| `ui.screen_resolution` | 720x1280 generic, no model-spec match |

Other notable contributions: `integrity.play_integrity_signals` 0.95
(predicted basic/device integrity fail), `env.bootloader` 0.85 (empty verified
boot state), `env.developer_options` 0.85 (`adb_enabled=1`),
`emulator.proc_version` 0.7 (generic Ubuntu kernel string),
`sensors.accelerometer_gyro` 0.5 (sensor_count 0 — confirmed by live
`dumpsys sensorservice` "No Sensors").

## 4. Exact remaining gap to a full installed-APK attestation run

This run measured probe **inputs** captured live from inside Android and scored
them with the production probe **logic** running in a host JVM. The
not-yet-closed delta to a full on-device attestation:

1. **No installable DetectorLab APK exists** — to run probe code *in-process*
   inside the Android JVM (ART), someone must add a
   `com.android.application` module that wires the `ProbeContext` interface to a
   real `android.content.Context` (the production impl `ProbeContext.kt`
   references but does not exist as an APK).
2. **In-process-only signals remain unmeasured** by the adb-shell channel:
   `Settings.Secure/Global` via `ContentResolver`, `TelephonyManager` IMEI/ICCID,
   `WifiManager.getMacAddress`, `SensorManager` enumeration in-process,
   `/proc/self/status` TracerPid of the *app* process, Play Integrity
   `IntegrityManager` token, installed-IME component, font enumeration via
   `Typeface`. In this run those probes scored on absence/`<unavailable>`
   rather than a true in-process read.
3. **Attestation channel:** a full run would obtain a signed Play Integrity /
   hardware-attestation verdict from inside the app, which adb-shell cannot do.

**To close fully:** create `:detector-app` (`com.android.application`,
minSdk 28, targetSdk 31), implement `AndroidProbeContext : ProbeContext`,
`./gradlew :detector-app:assembleDebug`, then on the server
`adb -s 127.0.0.1:5555 install -r detector-app-debug.apk`, launch via
`am start`/instrumented runner, and `adb pull` the JSON the app writes to its
files dir. The build-out is the only blocker; the install/launch/pull path is
already verified working (adb into the container no longer hangs).

## 5. Container left running & untouched

```
docker ps → NAME=redroid-test STATUS=Up 4 minutes UPTIME=4 minutes ago
getprop sys.boot_completed → 1   (post-run, still booted)
ps -A → zygote64 (pid 93), system_server (372), webview_zygote (620) all running
```

No reboot, shutdown, `docker rm/stop/kill`, host apt install, or `/data` write
(no `adb install` was issued — no APK to install). Only read-only `adb shell`
queries were executed against the container.

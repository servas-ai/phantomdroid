# Production SpoofStack Hook Specification

**Power-8 closeout artifact.** This document is the executable bridge from
the JVM-side snapshot (`RedroidSpoofedSnapshot.kt`) to the live ReDroid 12
host on PAR822349. For each snapshot mutation, it names the exact
production-side mechanism that must be installed on the live container to
produce the same observable surface, plus the verbatim command / code
snippet that ships in the Magisk / LSPosed module.

**Scope**: every value populated in `RedroidSpoofedSnapshot.SNAPSHOT` that
is NOT preserved from the ground-truth `RedroidV12Snapshot.SNAPSHOT`.
Probe-irrelevant fields (label, capturedAt) are excluded.

**Companion**: `audit/spoof-stack/iter-baseline.md` (phase-progression
record + residual closure log). After all hooks in this spec are
installed, the live host should produce the same `FullProbeRunnerSpoofTest`
outcome — `category=CLEAN`, `criticalFailures=0`, `weightedScore=0.0000`,
zero residual hits — that the snapshot replay produces today.

## Production Hook Categories

The spec uses six hook categories. Each snapshot field is annotated with
exactly one primary category and (where applicable) one belt-and-suspenders
secondary.

| Category | Code path | When to use |
|---|---|---|
| **resetprop** | Magisk `service.d/00-spoof.sh` script invoking `resetprop -n KEY VAL` | Any `ro.*` / `persist.*` / `net.*` system property |
| **settings-put** | Magisk `service.d/01-settings.sh` script invoking `settings put {global,secure,system} KEY VAL` | `Settings.Global` / `Settings.Secure` / `Settings.System` keys |
| **magic-mount** | Magisk module ships a file under `system/<path>` that Magisk magic-mounts over the real `/system/<path>` | `/system/fonts/*`, `/proc/*`, `/sys/*`, `/etc/*` (kernel-virtual or read-only filesystem paths) |
| **lsposed-hook** | LSPosed module Xposed-hooks a specific Java method | API calls that bypass property/file paths (`BluetoothAdapter.getAddress()`, `SensorManager.getSensorList()`, `Locale.getDefault()`, `TimeZone.getDefault()`, `TelephonyManager.getImei()`, etc.) |
| **denylist** | Magisk DenyList unmounts a path for the target app's UID | Hiding the existence of files like `/system/bin/su` per-app |
| **launch-flag** | Container launch-time configuration (Docker `--display`, ReDroid CLI flags) | Display resolution, sensor HAL configuration |

A complete production SpoofStack is a single Magisk module bundling
all six categories. The hook installation order is: launch-flag (boot
time) → resetprop (boot.d, pre-Zygote) → magic-mount (boot, pre-app-launch)
→ settings-put (service.d, post-boot) → lsposed-hook (post-app-launch,
per-target-app scope) → denylist (per-app-UID, refreshed on
install/upgrade).

---

## 1. systemProperties (Magisk `resetprop`)

Magisk `service.d/00-spoof.sh` runs early in boot before Zygote forks
the app processes. All `ro.*` properties are read-only after bootloader,
so `resetprop -n` (force-overwrite) is required.

The full boot script aggregates all 30+ entries; one row per snapshot
field below.

### 1.1 Build-prop family (rank 1 / 7 / 9 / 28)

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `ro.build.fingerprint` | `google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys` | resetprop | `resetprop -n ro.build.fingerprint "google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys"` |
| `ro.build.display.id` | `SP1A.210812.016.C2` | resetprop | `resetprop -n ro.build.display.id "SP1A.210812.016.C2"` |
| `ro.build.tags` | `release-keys` | resetprop | `resetprop -n ro.build.tags release-keys` |
| `ro.build.type` | `user` | resetprop | `resetprop -n ro.build.type user` |
| `ro.product.brand` | `google` | resetprop | `resetprop -n ro.product.brand google` |
| `ro.product.model` | `Pixel 7` | resetprop | `resetprop -n ro.product.model "Pixel 7"` |
| `ro.product.manufacturer` | `Google` | resetprop | `resetprop -n ro.product.manufacturer Google` |
| `ro.product.device` | `panther` | resetprop | `resetprop -n ro.product.device panther` |
| `ro.product.name` | `panther` | resetprop | `resetprop -n ro.product.name panther` |
| `ro.hardware` | `panther` | resetprop | `resetprop -n ro.hardware panther` |
| `ro.product.board` | `panther` | resetprop | `resetprop -n ro.product.board panther` |
| `ro.board.platform` | `gs201` | resetprop | `resetprop -n ro.board.platform gs201` |

### 1.2 CPU ABI family (rank 27) — dual surface

`ro.product.cpu.abi*` are consulted by the Zygote at fork time for
ELF-loader selection. Property-level spoofing alone is detectable by an
app that reads `Build.SUPPORTED_*ABIS` directly. Production needs BOTH
resetprop AND an LSPosed `android.os.Build.SUPPORTED_ABIS` reflection
hook.

| Snapshot field | Spoofed value | Mechanism | Command / code |
|---|---|---|---|
| `ro.product.cpu.abi` | `arm64-v8a` | resetprop | `resetprop -n ro.product.cpu.abi arm64-v8a` |
| `ro.product.cpu.abilist` | `arm64-v8a,armeabi-v7a,armeabi` | resetprop | `resetprop -n ro.product.cpu.abilist "arm64-v8a,armeabi-v7a,armeabi"` |
| `ro.product.cpu.abilist32` | `armeabi-v7a,armeabi` | resetprop | `resetprop -n ro.product.cpu.abilist32 "armeabi-v7a,armeabi"` |
| `ro.product.cpu.abilist64` | `arm64-v8a` | resetprop | `resetprop -n ro.product.cpu.abilist64 arm64-v8a` |
| `android.os.Build.SUPPORTED_ABIS` (Java reflection) | `["arm64-v8a","armeabi-v7a","armeabi"]` | lsposed-hook | `XposedHelpers.setStaticObjectField(Build.class, "SUPPORTED_ABIS", new String[]{"arm64-v8a","armeabi-v7a","armeabi"});` |
| `android.os.Build.SUPPORTED_64_BIT_ABIS` | `["arm64-v8a"]` | lsposed-hook | `setStaticObjectField(Build.class, "SUPPORTED_64_BIT_ABIS", new String[]{"arm64-v8a"});` |
| `android.os.Build.SUPPORTED_32_BIT_ABIS` | `["armeabi-v7a","armeabi"]` | lsposed-hook | `setStaticObjectField(Build.class, "SUPPORTED_32_BIT_ABIS", new String[]{"armeabi-v7a","armeabi"});` |

### 1.3 Bootloader / verified-boot (rank 13 + rank 71)

`ro.debuggable` is the load-bearing flag for PlayIntegrity basicFail.

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `ro.boot.vbmeta.device_state` | `green` | resetprop | `resetprop -n ro.boot.vbmeta.device_state green` |
| `ro.boot.verifiedbootstate` | `green` | resetprop | `resetprop -n ro.boot.verifiedbootstate green` |
| `ro.boot.flash.locked` | `1` | resetprop | `resetprop -n ro.boot.flash.locked 1` |
| `ro.oem_unlock_supported` | `0` | resetprop | `resetprop -n ro.oem_unlock_supported 0` |
| `ro.secure` | `1` | resetprop | `resetprop -n ro.secure 1` |
| `ro.debuggable` | `0` | resetprop | `resetprop -n ro.debuggable 0` |

### 1.4 SELinux (rank 14) — properties

(Properties; the sysfs side lives in §3.4.)

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `ro.boot.selinux` | `enforcing` | resetprop | `resetprop -n ro.boot.selinux enforcing` |
| `ro.build.selinux` | `1` | resetprop | `resetprop -n ro.build.selinux 1` |

### 1.5 DNS properties (rank 37)

`net.dns1..4` are the pre-Pie DNS surface. ConnectivityManager-side
DNS lives in `linkPropertiesDnsSupplier` (§5 lsposed-hook).

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `net.dns1` | `8.25.203.30` | resetprop | `resetprop net.dns1 8.25.203.30` (no `-n` — `net.*` is writable) |
| `net.dns2` | `8.25.203.31` | resetprop | `resetprop net.dns2 8.25.203.31` |

### 1.6 Locale build-time (rank 36)

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `ro.product.locale` | `en-US` | resetprop | `resetprop -n ro.product.locale en-US` |
| `ro.product.locale.language` | `en` | resetprop | `resetprop -n ro.product.locale.language en` |
| `ro.product.locale.region` | `US` | resetprop | `resetprop -n ro.product.locale.region US` |

### 1.7 Serial (rank 12) — dual surface

Property side here; the framework side is in §5 (LSPosed
`Build.getSerial()` hook).

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `ro.serialno` | `HQ7Y0V3RJL` | resetprop | `resetprop -n ro.serialno HQ7Y0V3RJL` |
| `android.os.Build.getSerial()` (API 26+) | `"HQ7Y0V3RJL"` | lsposed-hook | see §5.1 |

### 1.8 QEMU markers (rank 4) — UNCHANGED from baseline

ReDroid baseline already reports these as empty strings; no hook needed.

| Snapshot field | Spoofed value | Mechanism | Note |
|---|---|---|---|
| `ro.kernel.qemu` | `""` | (no hook) | already empty on ground-truth ReDroid |
| `ro.kernel.qemu.gles` | `""` | (no hook) | already empty on ground-truth ReDroid |

---

## 2. existingFiles (Magisk DenyList + magic-mount)

### 2.1 SU binary removal (rank 3) — Magisk DenyList

Ground-truth ReDroid ships `/system/bin/su`. The file STILL EXISTS on the
backing filesystem; Magisk DenyList unmounts the path for the target
app's UID at process-start, so the file is invisible only to the
detected app.

| Snapshot mutation | Mechanism | Command / config |
|---|---|---|
| Remove `/system/bin/su` from observable set | denylist | Add target app's package name to Magisk DenyList via `magisk --denylist add <pkg>`; or programmatically `db.execSQL("INSERT INTO denylist VALUES (?, ?)", pkg, "process")` in the Magisk SQLite DB |

### 2.2 SELinux policy presence (rank 14, Signal 4)

`/sys/fs/selinux/policy` exists on every real Android device with SELinux
enabled — no hook needed. The ReDroid container's mount namespace already
exposes `/sys/fs/selinux` from the host kernel (Ubuntu 18.04 with
`CONFIG_SECURITY_SELINUX=y` — the default).

| Snapshot mutation | Mechanism | Command |
|---|---|---|
| `/sys/fs/selinux/policy` exists | (no hook) | inherited from host kernel; verify with `ls -l /sys/fs/selinux/policy` on PAR822349 |

### 2.3 System fonts (rank 51) — magic-mount

Ship a Magisk module containing `system/fonts/<name>.ttf` for each of
the 32 `WELL_KNOWN_FONT_NAMES`. Magisk magic-mount overlays the module's
`system/` tree onto the live `/system` at boot.

Module layout:

```
spoofstack/
  module.prop
  system/
    fonts/
      Roboto-Regular.ttf
      Roboto-Bold.ttf
      ... (30 more)
      NotoColorEmoji.ttf
```

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| 32 `/system/fonts/*.ttf` entries (matching `SystemFontsProbe.WELL_KNOWN_FONT_NAMES`) | magic-mount | Shipped as a single Magisk module overlay. Each `.ttf` can be a 1-byte stub OR a real Pixel-7 font file from a factory image dump — the probe only checks `fileExists`, not file contents. Real-Pixel-image fonts are preferred so future content-reading probes don't surface a mismatch. |

---

## 3. readableFiles (Magisk magic-mount)

`/proc` and `/sys` are kernel-virtual; `resetprop` doesn't apply.
Mount-overlay is the only write path for these.

### 3.1 /proc/version (rank 30) — magic-mount

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| `/proc/version` = Pixel-7 Android-13 GKI kleaf+clang banner | magic-mount | Magisk module ships `system/proc/version` (file). The path translation happens via the SELinux `mount --bind` overlay. Verify with `cat /proc/version` — should print the Pixel banner, not the Ubuntu launchpad banner. |

### 3.2 /proc/self/status (rank 80) — LSPosed (NOT mount-mask)

Important: bind-mounting `/proc/self/status` is NOT viable. `/proc/self`
is a magic symlink resolved per-process by the kernel; a static
bind-mount would shadow EVERY process's status to the same canned
content, which is itself a detectable fingerprint.

| Snapshot mutation | Mechanism | Code |
|---|---|---|
| `/proc/self/status` contains `TracerPid:\t0` line | lsposed-hook | LSPosed module hooks `java.io.FileInputStream.<init>(File)` and `libc.open()`/`fopen()` JNI bridges. When the path is `/proc/self/status`, redirect reads to a per-process synthesized body. Reference: NeoZygisk's `frida-detector-counter` package. |

```java
// LSPosed module: FrameWork.kt
XposedBridge.hookMethod(
    FileInputStream.class.getConstructor(File.class),
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            File f = (File) param.args[0];
            if (f.getAbsolutePath().equals("/proc/self/status")) {
                String synthesized = String.format(
                    "Name:\tcom.example.app\nUmask:\t0077\nState:\tR (running)\n" +
                    "TracerPid:\t0\nUid:\t%d\t%d\t%d\t%d\nGid:\t%d\t%d\t%d\t%d\n",
                    Process.myUid(), Process.myUid(), Process.myUid(), Process.myUid(),
                    Process.myUid(), Process.myUid(), Process.myUid(), Process.myUid()
                );
                param.setResult(new ByteArrayInputStream(synthesized.getBytes()));
            }
        }
    }
);
```

### 3.3 SELinux enforce (rank 14, Signal 1) — magic-mount

`/sys/fs/selinux/enforce` is owned by selinuxfs and can't be written
directly. Mount-overlay (Magisk magic-mount over a tmpfs file containing
"1") is the standard approach.

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| `/sys/fs/selinux/enforce` = `"1"` | magic-mount | Magisk module ships `system/sys/fs/selinux/enforce` with content "1". This is exactly what MagiskHide/DenyList already does when SELinux mode has been toggled to Permissive for module operation. |

### 3.4 Bluetooth hci0 address (rank 31) — magic-mount

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| `/sys/class/bluetooth/hci0/address` = `3c:5a:b4:8d:f1:27` | magic-mount | Module ships `system/sys/class/bluetooth/hci0/address` with content `3c:5a:b4:8d:f1:27\n`. Combined with the LSPosed `BluetoothAdapter.getAddress()` hook (§5.3) for dual-surface coherence. |

### 3.5 WiFi wlan0 address (rank 15) — magic-mount

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| `/sys/class/net/wlan0/address` = `40:4e:36:7a:b2:c9` | magic-mount | Module ships `system/sys/class/net/wlan0/address` with content `40:4e:36:7a:b2:c9\n`. WifiManagerView side requires `WifiInfo.getMacAddress()` LSPosed hook (§5.4) — sysfs alone clears the probe at `CONFIDENCE_CAP_SINGLE_SURFACE=0.60`. |

### 3.6 /etc/resolv.conf (rank 37) — magic-mount

| Snapshot mutation | Mechanism | Notes |
|---|---|---|
| `/etc/resolv.conf` = T-Mobile US DNS pair | magic-mount | Module ships `system/etc/resolv.conf` with the `nameserver 8.25.203.30\nnameserver 8.25.203.31\n` body. Mirrors the standard SpoofStack `/etc/hosts` patching pattern. |

---

## 4. Settings.Secure / Settings.Global (settings-put)

Settings.* live in SQLite under `/data/system/users/0/settings_{global,
secure,system}.xml`. Two write paths:

- **Persistent**: `settings put {global,secure} KEY VAL` via a Magisk
  `service.d/01-settings.sh` boot script. Visible to ALL apps reading
  the namespace.
- **Per-app surgical**: LSPosed hook on
  `Settings.Secure.getString(ContentResolver, String)` returning spoofed
  values only for the target app. Adds JVM trampoline overhead.

Production typically uses **persistent** for identity-stable keys
(`android_id`) and **LSPosed** for per-evaluation keys
(`default_input_method`, location.*). For the snapshot fields below, the
**persistent** approach matches what an unhooked retail device reports.

### 4.1 Settings.Secure

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `android_id` (rank 11) | `a1b2c3d4e5f60718` | settings-put | `settings put secure android_id a1b2c3d4e5f60718` |
| `default_input_method` (rank 58) | `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` | settings-put | `settings put secure default_input_method "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"` |
| `location.is_from_mock_provider` (rank 82) | `0` | settings-put + lsposed-hook | `settings put secure "location.is_from_mock_provider" 0` — note that this is a wrapper-synthesized key (the rank-82 KDoc); production wrapper writes Location.isFromMockProvider into Settings.Secure via the production ProbeContext impl. For the live host, LSPosed hook on `Location.isFromMockProvider()` to return `false`. |
| `mock_location` (legacy < API 23) | `0` | settings-put | `settings put secure mock_location 0` |
| `mock_location_app` (legacy < API 23) | `""` | settings-put | `settings put secure mock_location_app ""` |
| `enabled_accessibility_services` | `""` | settings-put | `settings put secure enabled_accessibility_services ""` (clean default; first-boot state) |
| `location_providers_allowed` | `gps,network` | settings-put | `settings put secure location_providers_allowed "gps,network"` |

### 4.2 Settings.Global

| Snapshot field | Spoofed value | Mechanism | Command |
|---|---|---|---|
| `development_settings_enabled` | `0` | settings-put | `settings put global development_settings_enabled 0` |
| `adb_enabled` | `0` | settings-put | `settings put global adb_enabled 0` |
| `adb_wifi_enabled` | `0` | settings-put | `settings put global adb_wifi_enabled 0` |
| `package_verifier_enable` | `1` | settings-put | `settings put global package_verifier_enable 1` |
| `http_proxy` (rank 18) | `""` | settings-put OR no-write | `settings put global http_proxy ""` (or leave unset — factory state is unwritten, which the probe also reads as no-proxy) |
| `private_dns_mode` | `off` | settings-put | `settings put global private_dns_mode off` |

---

## 5. Telephony, Bluetooth, WiFi framework — LSPosed

Per-Java-API surfaces that have no system-property / file backing. The
container has no real RIL backend, no real BluetoothAdapter, no real
WifiManager — these are all reads against system services. Only an
LSPosed module can intercept them.

### 5.1 TelephonyManager (rank 12, 21, 22)

LSPosed module hooks `android.telephony.TelephonyManager`.

```java
// TelephonyHook.kt — LSPosed entry point
findAndHookMethod(TelephonyManager.class, "getImei",
    XC_MethodReplacement.returnConstant("353112109876546"));
findAndHookMethod(TelephonyManager.class, "getDeviceId",
    XC_MethodReplacement.returnConstant("353112109876546"));
findAndHookMethod(TelephonyManager.class, "getSerial",
    XC_MethodReplacement.returnConstant("HQ7Y0V3RJL"));
findAndHookMethod(TelephonyManager.class, "getSimSerialNumber",
    XC_MethodReplacement.returnConstant("8901260123456789011"));
findAndHookMethod(TelephonyManager.class, "getNetworkOperatorName",
    XC_MethodReplacement.returnConstant("T-Mobile"));
findAndHookMethod(TelephonyManager.class, "getNetworkOperator",
    XC_MethodReplacement.returnConstant("310260"));
findAndHookMethod(TelephonyManager.class, "getSimOperator",
    XC_MethodReplacement.returnConstant("310260"));
findAndHookMethod(TelephonyManager.class, "getSimCountryIso",
    XC_MethodReplacement.returnConstant("us"));
findAndHookMethod(TelephonyManager.class, "getNetworkCountryIso",
    XC_MethodReplacement.returnConstant("us"));
findAndHookMethod(TelephonyManager.class, "getSimState",
    XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_READY));

// Build.getSerial() also routes through TelephonyManager.getSerial() on
// API ≥26 (READ_PRIVILEGED_PHONE_STATE required). Reflection-based
// Build.SERIAL reads bypass that path.
findAndHookMethod(Build.class, "getSerial",
    XC_MethodReplacement.returnConstant("HQ7Y0V3RJL"));
XposedHelpers.setStaticObjectField(Build.class, "SERIAL", "HQ7Y0V3RJL");
```

| Snapshot field | Spoofed value | API surface |
|---|---|---|
| `telephony.IMEI` | `353112109876546` | `TelephonyManager.getImei()` / `getDeviceId()` |
| `telephony.SERIAL` | `HQ7Y0V3RJL` | `TelephonyManager.getSerial()` / `Build.getSerial()` / `Build.SERIAL` reflection |
| `telephony.SIM_SERIAL` | `8901260123456789011` | `TelephonyManager.getSimSerialNumber()` |
| `telephony.OPERATOR_NAME` | `T-Mobile` | `TelephonyManager.getNetworkOperatorName()` |
| `telephony.MCC_MNC` | `310260` | `TelephonyManager.getNetworkOperator()` / `getSimOperator()` |

Optional belt-and-suspenders: a fake RIL HAL (`libril.so` shim) returning
realistic AT-command responses if any app bypasses the Java layer and
reads `/dev/radio` directly. Most apps don't; LSPosed alone is the
production-grade fix.

### 5.2 Locale + TimeZone (rank 20, 36)

```java
// LocaleHook.kt — LSPosed entry point
findAndHookMethod(Locale.class, "getDefault",
    XC_MethodReplacement.returnConstant(Locale.US));
findAndHookMethod(TimeZone.class, "getDefault",
    XC_MethodReplacement.returnConstant(
        TimeZone.getTimeZone("America/Los_Angeles")));
// Per-context Resources.getConfiguration() — must also be hooked
findAndHookMethod(Resources.class, "getConfiguration", new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam param) {
        Configuration cfg = (Configuration) param.getResult();
        cfg.setLocale(Locale.US);
        param.setResult(cfg);
    }
});
```

| Snapshot field | Spoofed value | API surface |
|---|---|---|
| `timezoneId` | `America/Los_Angeles` | `TimeZone.getDefault().id` |
| `timezoneOffsetMinutes` | `-480` (PST) | `TimeZone.getDefault().getOffset(now)` |
| `localeLanguage` | `en` | `Locale.getDefault().language` |
| `localeCountry` | `US` | `Locale.getDefault().country` |
| `localeDisplayName` | `English (United States)` | `Locale.getDefault().displayName` |

Belt-and-suspenders: `setprop persist.sys.timezone America/Los_Angeles`
and `setprop persist.sys.locale en-US` at first boot via Magisk
`service.d/00-spoof.sh`.

### 5.3 BluetoothAdapter (rank 31)

```java
findAndHookMethod(BluetoothAdapter.class, "getAddress",
    XC_MethodReplacement.returnConstant("3C:5A:B4:8D:F1:27"));
// Optional: hook BluetoothManager.getAdapter() to return a non-null
// adapter on hosts with no real Bluetooth HAL.
findAndHookMethod(BluetoothManager.class, "getAdapter", new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam param) {
        if (param.getResult() == null) {
            param.setResult(BluetoothAdapter.getDefaultAdapter());
        }
    }
});
```

| Snapshot field | Spoofed value | API surface |
|---|---|---|
| `bluetoothMac` | `3c:5a:b4:8d:f1:27` | `BluetoothAdapter.getAddress()` |

### 5.4 WiFi framework (rank 15)

```java
findAndHookMethod(WifiInfo.class, "getMacAddress",
    XC_MethodReplacement.returnConstant("40:4e:36:7a:b2:c9"));
// On API ≥26 the framework redacts to 02:00:00:00:00:00 for
// non-LOCAL_MAC_ADDRESS callers — hook overrides that redaction.
```

| Snapshot field | Spoofed value | API surface |
|---|---|---|
| WifiInfo.getMacAddress() (framework side) | `40:4e:36:7a:b2:c9` | `WifiInfo.getMacAddress()` |

### 5.5 SensorManager (rank 24, 42, 43, 44, 45)

This surface is NOT spoofable via Magisk resetprop alone. The
SensorManager backing on a real Android host is the kernel-side iio /
sensor-hub HAL. Two production approaches:

- **(a) LSPosed**: hook `SensorManager.getSensorList()` to inject six
  fake Sensor objects. Lower effort but probe-side-only — apps that hit
  the HAL directly (rare) bypass it.
- **(b) User-space sensor-HAL shim** (`sensors@2.x.so`): fabricates a
  6-sensor list AND serves canned-but-jittered sample streams. Production-
  grade; covers HAL-direct callers.

Option (a) sample:

```java
findAndHookMethod(SensorManager.class, "getSensorList", int.class,
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            int type = (int) param.args[0];
            List<Sensor> spoofed = new ArrayList<>();
            for (int t : new int[]{1, 2, 4, 5, 6, 8}) {  // ACCEL/MAG/GYRO/LIGHT/PRESS/PROX
                if (type == Sensor.TYPE_ALL || type == t) {
                    spoofed.add(createSpoofedSensor(t));
                }
            }
            param.setResult(spoofed);
        }
    });
```

| Snapshot field | Spoofed value | API surface |
|---|---|---|
| `sensorTypes` | `{1, 2, 4, 5, 6, 8}` (ACCEL, MAG, GYRO, LIGHT, PRESS, PROX) | `SensorManager.getSensorList(TYPE_ALL)` |

Important: probes 24/42/43/44/45 also check the sample stream for
constant-stub patterns (≥2 identical samples → fires the stub rule).
Option (b) is required to cover sample-stream emulator tells; option
(a) alone clears the "missing-on-phone" rule but not the constant-stub
rule on apps that subscribe to `SensorEventListener`.

### 5.6 DisplayMetrics (rank 23)

Production approaches:

- **(a) launch-flag**: launch ReDroid with `--display=1080x2400`
  plus a service.d `wm density 420` + `wm size 1080x2400` boot script.
  Resolves DisplayMetrics at the framework level for every app.
- **(b) lsposed-hook**: hook `WindowManager.defaultDisplay.getMetrics()`
  and `Resources.getDisplayMetrics()` to fabricate per-app values.
  Surgical but adds per-call trampoline.

Option (a) is production-grade and is what TrickyStore's "screen-spoof"
module ships. Combine with `resetprop ro.sf.lcd_density 420` for the
framework-level density override.

| Snapshot field | Spoofed value | Mechanism |
|---|---|---|
| `displayWidthPixels` | `1080` | launch-flag (`ReDroid --display=1080x2400`) |
| `displayHeightPixels` | `2400` | launch-flag |
| `displayDensityDpi` | `420` | settings-put `wm density 420` + resetprop `ro.sf.lcd_density 420` |
| `displayXdpi` | `411.0` | (computed by framework from launch-flag + density) |
| `displayYdpi` | `413.0` | (computed by framework from launch-flag + density) |

---

## 6. installedPackages

The spoof snapshot keeps the ground-truth ReDroid minimal AOSP set
(`android`, `com.android.systemui`, `com.android.settings`). No
emulator-marker packages (`com.bluestacks.*`, `com.vphone.*`, etc.) are
present in the ground truth, so no masking is needed.

Production note: the live PAR822349 host will additionally install Google
Play Services (`com.google.android.gms`) and Play Store
(`com.android.vending`) for PlayIntegrity attestation. These are NOT
emulator markers; they're expected on any device that runs Play-protected
apps. No hook required.

| Snapshot field | Spoofed value | Mechanism |
|---|---|---|
| `installedPackages` | `{android, systemui, settings}` (+ live: gms, vending) | (no hook needed — ground truth is already clean) |

---

## Boot Sequence (executable assembly)

Production SpoofStack module installs the following files. Magisk loads
`/data/adb/modules/spoofstack/` at boot.

```
/data/adb/modules/spoofstack/
├── module.prop                                # Magisk module metadata
├── system.prop                                # Persistent resetprop set (alt to service.d)
├── post-fs-data.sh                            # Runs before Zygote; magic-mount activation
├── service.d/
│   ├── 00-spoof.sh                            # §1 resetprop calls (build-prop family)
│   ├── 01-settings.sh                         # §4 settings put calls
│   └── 02-locale.sh                           # §5.2 setprop persist.sys.{timezone,locale}
├── system/                                    # Magic-mount overlay tree
│   ├── etc/resolv.conf                        # §3.6
│   ├── fonts/                                 # §2.3 — 32 .ttf files
│   ├── proc/version                           # §3.1
│   ├── sys/class/bluetooth/hci0/address       # §3.4
│   ├── sys/class/net/wlan0/address            # §3.5
│   └── sys/fs/selinux/enforce                 # §3.3
├── zygisk/                                    # LSPosed module loader
│   ├── arm64-v8a.so                           # §5.* hooks (compiled with NDK)
│   └── armeabi-v7a.so
└── denylist                                   # §2.1 — list of target app packages
```

`module.prop` skeleton:

```
id=spoofstack-poweright
name=SpoofStack Power-8
version=v1.0.0
versionCode=100
author=cloud-phone-research
description=Pixel-7 spoof overlay for ReDroid 12 (closes all 63 production probes)
```

`post-fs-data.sh` skeleton:

```sh
#!/system/bin/sh
MODDIR=${0%/*}

# Magic-mount activation — Magisk handles the actual mounts; this script
# just ensures the overlay tree is readable.
chmod -R 0644 "$MODDIR/system"
find "$MODDIR/system" -type d -exec chmod 0755 {} \;
```

---

## Verification

After installing the module on PAR822349, run the same probe pipeline
against the live container via `docker exec`:

```sh
docker exec redroid12 sh -c '
  getprop ro.build.fingerprint
  getprop ro.product.model
  getprop ro.hardware
  ls /system/bin/su 2>&1
  cat /proc/version
  cat /sys/fs/selinux/enforce
  cat /sys/class/bluetooth/hci0/address
  cat /sys/class/net/wlan0/address
  settings get global http_proxy
  settings get secure android_id
'
```

Expected outputs:

| Command | Expected |
|---|---|
| `getprop ro.build.fingerprint` | `google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys` |
| `getprop ro.product.model` | `Pixel 7` |
| `getprop ro.hardware` | `panther` |
| `ls /system/bin/su` | `ls: cannot access /system/bin/su: No such file or directory` (when invoked from the target app's namespace — denylist active) |
| `cat /proc/version` | `Linux version 5.10.149-android13-4-... (kleaf@build-host) ...` |
| `cat /sys/fs/selinux/enforce` | `1` |
| `cat /sys/class/bluetooth/hci0/address` | `3c:5a:b4:8d:f1:27` |
| `cat /sys/class/net/wlan0/address` | `40:4e:36:7a:b2:c9` |
| `settings get global http_proxy` | `null` or `""` |
| `settings get secure android_id` | `a1b2c3d4e5f60718` |

Final acceptance criterion: build the production APK pointing at the
real `ProbeContext` wrapper (not `SnapshotReplayContext`), install on
PAR822349, run the full 63-probe panel, confirm `aggregate.category =
CLEAN` and `criticalFailures = 0`.

---

## Hook Inventory Summary

Counts by category:

| Category | Count | Notes |
|---|---:|---|
| resetprop | 30 system properties | One Magisk `service.d/00-spoof.sh` script |
| settings-put | 13 Settings.* keys | One Magisk `service.d/01-settings.sh` script |
| magic-mount | 38 files (32 fonts + 6 individual paths) | One Magisk module `system/` overlay tree |
| lsposed-hook | ~20 Java method hooks | Single LSPosed module with §5 entry points |
| denylist | 1 path (/system/bin/su) per target package | Per-app config |
| launch-flag | 2 (ReDroid `--display`, kernel command line) | Container-level, set at `docker run` time |

Total surface area: **~104 production hooks** consolidated into **one Magisk
module + one LSPosed module + one ReDroid launch-config change**. This is
the minimum production SpoofStack to close every probe in the inventory.

---

## P-12 — Native-Layer Anti-Hook Stack

**Date added**: 2026-05-20
**Scope**: Production-runtime defenses for inventory ranks 9.0, 9.7, 9.8.

> **PAR822349 deployment gap**: these defenses are out-of-scope for the current Magisk + LSPosed deployment plan. They require kernel work and are listed in BEST-STACK §IV. Do not represent them as implemented on the live host until the kernel module work is complete.

### P-12.1 — Rank-9.0: Frida process elimination (FridaKill Magisk module)

**Detection vector**: rank-9.0 `runtime.frida_memory_maps` scans `/proc/self/maps` for Frida gadget / agent / gum library names. A real container running Frida (even via gadget injection) will expose these entries to any app that reads its own maps.

**Production defense**: install a Magisk module `FridaKill` at `post-fs-data.sh` that:

1. Scans `/proc/*/cmdline` and `/proc/*/maps` at boot for any process whose argv or mapped libs match `/frida-gadget|frida-agent|gum/`.
2. Kills matching processes with `SIGKILL` before user-space app launch.
3. Installs iptables rules to drop inbound and outbound traffic on Frida's default TCP listener ports:
   ```sh
   iptables -I INPUT -p tcp --dport 27042 -j DROP
   iptables -I INPUT -p tcp --dport 27043 -j DROP
   iptables -I OUTPUT -p tcp --sport 27042 -j DROP
   iptables -I OUTPUT -p tcp --sport 27043 -j DROP
   ip6tables -I INPUT -p tcp --dport 27042 -j DROP
   ip6tables -I INPUT -p tcp --dport 27043 -j DROP
   ```

The `hide-frida-maps` Xposed module (already in repo at `stack/L4/hide-frida-maps/`) handles the per-app `/proc/self/maps` redirect for any residual Frida presence the process-kill doesn't catch. Both defenses are complementary; FridaKill is the stronger one.

**Mitigation layer**: **L4 + Magisk post-fs-data** — viable, no kernel work required.

### P-12.2 — Rank-9.7: Native prologue-hash bypass (kernel W^X policy)

**Detection vector**: rank-9.7 `runtime.native_prologue_hash` hashes the first 16-32 bytes of `libc.so` / `libart.so` exported functions in memory and compares to on-disk baseline. Any inline hook (Frida `Interceptor.attach`, LSPosed inline hook) overwrites these bytes with a `MOV X16, #addr / BR X16` trampoline — the hash diverges.

**Real defense**: prevent the inline hook from being placed in the first place. The mechanism is a kernel-level no-modify-text-segment policy:

- **SELinux enforcing + W^X** (Write XOR Execute): configure the SELinux policy so that `.text` segments of system libraries (`libc.so`, `libart.so`, `libdvm.so`) are marked `PROT_READ|PROT_EXEC` only. Any attempt to `mprotect()` them to `PROT_WRITE` for hook patching fails with `EACCES`.
- On ReDroid, enforcing this policy requires a kernel module that marks all `.text` segments as immutable at ELF-load time. The module hooks `mmap` / `mprotect` syscalls to enforce the W^X invariant.

**Current status on PAR822349**: not implemented. ReDroid's kernel (Ubuntu HWE 5.4) does not ship with this policy enabled by default. SELinux on the host may be in permissive mode for ReDroid containerization compatibility.

**Mitigation layer**: **L0 in current state** — UNCOUNTERED in FOSS 2026. The W^X kernel module approach is theoretically sound but has no public FOSS implementation.

### P-12.3 — Rank-9.8: GOT/PLT integrity scanning (sealed native lib)

**Detection vector**: rank-9.8 `integrity.prologue_got_hooks` looks for rwxp (read-write-execute-private) memory segments in `/proc/self/maps` and scans GOT entries for values that deviate from known-good offsets. Hook frameworks overwrite GOT entries to redirect calls to trampolines.

**Real defense**: pair the W^X policy from P-12.2 with periodic GOT integrity scans:

1. A sealed native library (`libgotscan.so`) is loaded at Zygote start. It is compiled with `--sealed-sections` and its own `.so` hash is verified at load time.
2. At probe-call time, `libgotscan.so` linearly scans the GOT region of `libc.so` and `libart.so`, comparing each entry to the expected offset (computed from the on-disk ELF at load time, stored in a read-only memory-mapped region).
3. Any GOT entry that points outside the expected `.text` range of the owning library is flagged.

This defense is only meaningful if the W^X policy from P-12.2 is also in place; without it, an attacker can trivially restore GOT entries after hooking.

**Current status on PAR822349**: not implemented. Requires the sealed `libgotscan.so` native library (out of scope for current Magisk + LSPosed deployment) and kernel W^X policy (same as P-12.2).

**Mitigation layer**: **L0 in current state** — UNCOUNTERED in FOSS 2026.

### P-12 Summary

| Rank | Defense | Layer | Status on PAR822349 | Kernel work required? |
|---|---|---|---|---|
| 9.0 `frida_memory_maps` | FridaKill module + hide-frida-maps | L4 + Magisk | **Deployable now** | No |
| 9.7 `native_prologue_hash` | SELinux W^X + kernel no-modify-text module | L0 | **Not implemented** | Yes |
| 9.8 `prologue_got_hooks` | GOT integrity scan + W^X (requires P-12.2) | L0 | **Not implemented** | Yes |

**The 9.0 defense is actionable today.** The 9.7 and 9.8 defenses require kernel-level work and are classified in BEST-STACK §IV Hard Ceiling — they are documented here for completeness but should not be committed to as deliverables without an explicit kernel-module development workstream.

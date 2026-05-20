# SpoofStack LSPosed Module

LSPosed module that implements section 5 of
`audit/spoof-stack/production-hooks-spec.md` — the framework-side Java
method hooks that Magisk `resetprop` cannot reach.

## Module scope

This module covers the **lsposed-hook** category only (~20 of the ~104
total production hooks). The other five categories (resetprop,
settings-put, magic-mount, denylist, launch-flag) are delivered by
a separate Magisk module — see the spec's "Boot Sequence" section.

## Hook inventory

| Hook class | Targets | Spec section |
|---|---|---|
| `TelephonyManagerHook` | `TelephonyManager.{getImei,getDeviceId,getSerial,getSimSerialNumber,getNetworkOperatorName,getNetworkOperator,getSimOperator,getSimOperatorName,getSimCountryIso,getNetworkCountryIso,getSimState}` | §5.1 |
| `BuildSerialHook` | `Build.getSerial()`, `Build.SERIAL` field | §1.7 / §5.1 |
| `BuildAbiHook` | `Build.SUPPORTED_*ABIS`, `Build.{HARDWARE,BRAND,MODEL,MANUFACTURER,DEVICE,PRODUCT,FINGERPRINT,DISPLAY,TAGS,TYPE}` static fields | §1.2 |
| `LocaleHook` | `Locale.getDefault()` (both overloads) | §5.2 |
| `TimeZoneHook` | `TimeZone.getDefault()` | §5.2 |
| `ResourcesHook` | `Resources.getConfiguration()` | §5.2 |
| `BluetoothAdapterHook` | `BluetoothAdapter.getAddress()`, `BluetoothManager.getAdapter()` | §5.3 |
| `WifiInfoHook` | `WifiInfo.getMacAddress()` | §5.4 |
| `SensorManagerHook` | `SensorManager.getSensorList(int)` | §5.5 |
| `DisplayMetricsHook` | `Resources.getDisplayMetrics()`, `Display.getMetrics()`, `Display.getRealMetrics()` | §5.6 |
| `FileInputStreamHook` | `FileInputStream(File\|String)`, `Files.newInputStream()` redirected when path is `/proc/self/status` | §3.2 |
| `LocationHook` | `Location.isFromMockProvider()` | §4.1 |
| `SettingsSecureHook` | `Settings.Secure.getString()` for `location.is_from_mock_provider`, `mock_location`, `mock_location_app`, `enabled_accessibility_services` | §4.1 |
| `PackageManagerHook` | `ApplicationPackageManager.{getInstalledPackages,getInstalledApplications,getPackageInfo,getApplicationInfo}` for self-hide | self-hide |

## Build

The module uses Android Gradle Plugin 8.x; requires JDK 17 and the
Android SDK with `compileSdk=33` available. The `de.robv.android.xposed:api:82`
dependency is `compileOnly` — it is **not** bundled in the APK; LSPosed
supplies it at runtime.

```sh
cd infrastructure/spoof-stack-lsposed
./gradlew :app:assembleRelease
# APK at: app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign the APK with a release keystore before installation:

```sh
apksigner sign --ks release.keystore --out spoofstack.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

## Install (on a rooted ReDroid host with LSPosed installed)

```sh
adb install -r spoofstack.apk
# Open LSPosed Manager → Modules → enable "SpoofStack"
# Open LSPosed Manager → Modules → SpoofStack → scope → enable target app(s)
# Force-stop the target app; relaunch — hooks are now active.
```

## Verify

After enabling for a target app, run a probe (or just `getprop`-style
ADB queries piped into a debug build of the probe runner) against the
container:

```sh
adb shell am start -n com.example.probe/.MainActivity
adb logcat -s LSPosed-Bridge SpoofStack
# Expect log lines: "SpoofStack v1.0.0 loading for com.example.probe"
#                   "SpoofStack all hooks installed for com.example.probe"
```

## Source of truth

Every spoofed value in `SpoofConfig.java` is sourced verbatim from
`audit/spoof-stack/production-hooks-spec.md`. Changing a value here
**must** be paired with a spec update — the property side (resetprop
in a separate Magisk module) and the framework side (this module) must
agree, or the disagreement is itself a detectable signal.

## Limitations

- This module covers Java-API surfaces only. Apps that call libc.open()
  / fopen() via JNI bypass `FileInputStreamHook`; production deployers
  should pair this module with a Zygisk-side libc shim
  (see NeoZygisk's `frida-detector-counter` for the reference impl).
- `SensorManagerHook` clears the "well-known sensor missing" rule but
  not the "constant sample stream" rule — pair with a `sensors@2.x.so`
  HAL shim for sample-stream coverage.
- `BluetoothAdapter.getAddress()` redaction on API 26+ for callers
  without `LOCAL_MAC_ADDRESS` is overridden by this hook — apps that
  use the redacted form as a "non-rooted device" tell will see the
  spoofed MAC instead. This is the intended behavior.

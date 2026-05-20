// agents/detection/src/core/replay/DeviceSnapshot.kt
//
// Closure of the E2E "real-simulator" goal: the detection module is a JVM
// library that needs to run *against* probe-relevant data captured from a
// live Android (or Android-like) runtime. The simulator we have (ReDroid 12
// on PAR822349) does not boot to a fully usable system_server on kernel 4.15
// (binderfs gap), but the probe-relevant property surface IS reachable via
// `docker exec` directly. This file is the data-side of that bridge:
//
//   live container --(docker exec getprop/ls/cat)--> DeviceSnapshot.yaml
//                                                          |
//                                                          v
//                                                 SnapshotReplayContext
//                                                          |
//                                                          v
//                                                  ProbeRunner.runAll()
//
// Snapshots are owner-readable plain data — no live network, no live device
// access required to replay them. The recorded `RedroidV12Snapshot.kt`
// peer file holds the actual ReDroid 12 capture from 2026-05-20.

package com.detectorlab.core.replay

/**
 * Captured probe-relevant device state from a single Android runtime at a
 * single point in time. Field set chosen to cover the probe surface that
 * the existing 62-probe inventory actually reads from `ProbeContext`.
 *
 * Field semantics mirror the corresponding `ProbeContext` accessor:
 *
 *   * `systemProperties` — `getSystemProperty(key)` lookup. Map of
 *     `getprop` key to value. Missing keys map to `null` at the
 *     accessor level (NOT `""`). Empty-string values are preserved
 *     verbatim because some probes distinguish "set-but-empty" from
 *     "not-set".
 *   * `existingFiles` — `fileExists(path)` lookup. Path is treated as
 *     present iff and only if it appears in this set. Order does not
 *     matter; comparison is exact-string match (no globbing).
 *   * `readableFiles` — `readFile(path)` lookup. Used together with
 *     `existingFiles`: a file may exist but not be readable from a
 *     non-root context. Missing key → accessor returns null.
 *   * `settingsSecure` / `settingsGlobal` / `settingsSystem` —
 *     `Settings.Secure` / `Settings.Global` / `Settings.System` namespace
 *     reads. Same null-vs-empty semantics as `systemProperties`.
 *   * `telephony` — `TelephonyManager` field reads. Field-typed enum
 *     map.
 *   * `installedPackages` — `PackageManager.getInstalledPackages()`
 *     output as a flat package-name set.
 *   * `sensorTypes` — `SensorManager.getSensorList(TYPE_ALL)` reduced to
 *     the set of Android sensor-type integer constants the device claims
 *     to expose. Empty set = "no sensors installed" (the ReDroid-without-
 *     HAL default and the conservative answer for snapshots that don't
 *     capture sensor inventory). Values are the canonical Android
 *     `Sensor.TYPE_*` integers (e.g. 1 = TYPE_ACCELEROMETER, 4 =
 *     TYPE_GYROSCOPE, 5 = TYPE_LIGHT, 8 = TYPE_PROXIMITY); see
 *     `AccelerometerGyroProbe.companion` for the canonical lookup table
 *     used by the sensor probe family.
 *   * `bluetoothMac` — `BluetoothAdapter.getDefaultAdapter().getAddress()`
 *     as reported by the framework, normalized to lowercase colon-separated
 *     form (e.g. `"3c:5a:b4:00:11:22"`). `null` = "no BluetoothAdapter
 *     accessor available" (the production / non-injected default — same
 *     answer `BluetoothMacProbe`'s default supplier gives). Snapshots that
 *     model a real-device adapter populate this so test code can construct
 *     `BluetoothMacProbe(adapterMacSupplier = { snapshot.bluetoothMac })`
 *     and exercise the supplier-driven rank-31 surface. `ProbeContext`
 *     itself exposes no Bluetooth accessor (rank-31 KDoc lines 29-32);
 *     this field is the snapshot-side bridge.
 *   * `timezoneId` — Olson timezone identifier the runtime claims
 *     (e.g. `"America/Los_Angeles"`). `null` = "no timezone observation"
 *     (same conservative answer `TimezoneLocaleProbe`'s default ctx
 *     accessor gives). Drives `SnapshotReplayContext.queryTimezoneId()`.
 *   * `timezoneOffsetMinutes` — UTC offset of the runtime's default
 *     timezone in minutes (positive east, negative west). `null` = unknown.
 *     Drives `SnapshotReplayContext.queryTimezoneOffsetMinutes()`.
 *   * `localeLanguage` — ISO 639-1 language code (lowercase, e.g. `"en"`).
 *     `null` = locale unobserved. Drives
 *     `SnapshotReplayContext.queryLocaleLanguage()`.
 *   * `localeCountry` — ISO 3166-1 alpha-2 country code (uppercase, e.g.
 *     `"US"`). `null` = locale unobserved. Distinct from `""` which means
 *     "locale set to ROOT" — both forms are preserved verbatim. Drives
 *     `SnapshotReplayContext.queryLocaleCountry()`.
 *   * `localeDisplayName` — Human-readable locale name (e.g. `"English
 *     (United States)"`). `null` = unobserved. Evidence-only.
 *   * `displayWidthPixels` / `displayHeightPixels` / `displayDensityDpi` /
 *     `displayXdpi` / `displayYdpi` — flat representation of
 *     `DisplayMetrics`. Each `null` = "no display observation"
 *     (= `DisplayMetricsView` accessor returns null on that field). Drive
 *     `SnapshotReplayContext.queryDisplayMetrics()` which synthesizes a
 *     `DisplayMetricsView` over these fields when at least one is populated,
 *     else returns `null` (the "no display observation possible"
 *     conservative default).
 *   * `gpsLat` / `gpsLng` / `gpsAccuracy` / `gpsProvider` / `gpsIsMock` —
 *     flat representation of the most-recent `Location` fix backing the
 *     `LocationManagerView` accessor cluster. Drive
 *     `SnapshotReplayContext.queryLocationManager()` which synthesizes a
 *     `LocationManagerView` over these fields. When ALL five are null the
 *     view's `hasLastKnownLocation()` returns null (= "permission missing
 *     or no observation"), which `GpsCoordinatesProbe` (rank 41) reads as
 *     CONFIDENCE_DEGRADED. When at least lat OR lng is populated,
 *     `hasLastKnownLocation()` returns true. `gpsIsMock=null` (the field
 *     not captured) is distinct from `false` (the runtime explicitly said
 *     "this fix is not from a mock provider"); both branches matter to
 *     the rank-41 scoring cascade.
 *   * `sdkInt` — `Build.VERSION.SDK_INT` claimed by the runtime.
 *
 * `null`-valued entries are equivalent to missing entries — the same
 * "key not in map" branch. Authors should prefer omitting keys to
 * setting explicit `null`.
 */
data class DeviceSnapshot(
    val label: String,
    val capturedAt: String,
    val sdkInt: Int = 0,
    val systemProperties: Map<String, String?> = emptyMap(),
    val existingFiles: Set<String> = emptySet(),
    val readableFiles: Map<String, String> = emptyMap(),
    val settingsSecure: Map<String, String?> = emptyMap(),
    val settingsGlobal: Map<String, String?> = emptyMap(),
    val settingsSystem: Map<String, String?> = emptyMap(),
    val installedPackages: Set<String> = emptySet(),
    val telephony: Map<String, String?> = emptyMap(),
    val sensorTypes: Set<Int> = emptySet(),
    val bluetoothMac: String? = null,
    val timezoneId: String? = null,
    val timezoneOffsetMinutes: Int? = null,
    val localeLanguage: String? = null,
    val localeCountry: String? = null,
    val localeDisplayName: String? = null,
    val displayWidthPixels: Int? = null,
    val displayHeightPixels: Int? = null,
    val displayDensityDpi: Int? = null,
    val displayXdpi: Float? = null,
    val displayYdpi: Float? = null,
    /** Last-known-fix latitude (decimal degrees, WGS84). `null` = no fix in snapshot. */
    val gpsLat: Double? = null,
    /** Last-known-fix longitude (decimal degrees, WGS84). `null` = no fix in snapshot. */
    val gpsLng: Double? = null,
    /** Last-known-fix horizontal accuracy radius in meters. `null` = field unrecorded. */
    val gpsAccuracy: Float? = null,
    /** Provider that produced the last-known fix: `"gps"` / `"network"` / `"fused"`. */
    val gpsProvider: String? = null,
    /**
     * `Location.isFromMockProvider()` reading on the last-known fix. `null`
     * = field unrecorded (API <18 or snapshot pre-dates the accessor);
     * `false` = real fix; `true` = mock-location framework injection.
     */
    val gpsIsMock: Boolean? = null,
)

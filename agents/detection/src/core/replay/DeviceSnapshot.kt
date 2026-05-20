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

    /**
     * Library names observed in `/proc/self/maps` for the calling process.
     * Consumed by `FridaMemoryMapsProbe` (rank 9.0). Tokens are case-
     * insensitive substrings the probe scans for — entries like
     * `"frida-agent"`, `"libfrida-gadget"`, `"libgum"`, `"linjector"`.
     * Empty set = "no frida-class libraries mapped" (the clean state).
     */
    val procSelfMapsLibs: Set<String> = emptySet(),

    /**
     * Thread names harvested from `/proc/self/task/<tid>/comm`. Consumed
     * by `FridaMemoryMapsProbe` (rank 9.0). Frida's gum runtime spawns
     * threads named `"gum-js-loop"`, `"gmain"`, `"gdbus"` etc.; their
     * presence is dispositive. Empty set = no observed threads / no
     * suspicious threads — both clean from the probe's perspective.
     */
    val runtimeThreadNames: Set<String> = emptySet(),

    /**
     * TCP ports observed bound in `/proc/net/tcp`. Consumed by
     * `FridaMemoryMapsProbe` (rank 9.0). Frida's default server listens
     * on 27042 / 27043; their presence in the bound-port set is a
     * smoking-gun signal. Empty set = no relevant ports observed.
     */
    val openTcpPorts: Set<Int> = emptySet(),

    /**
     * Per-function prologue-hash mismatch map produced by a runtime
     * native-side comparison of the first 16-32 bytes of libc.so /
     * libart.so functions against the on-disk baseline. Keys are
     * function symbols; values are `true` when the in-memory bytes
     * differ from the disk image (typically because an inline-hook
     * trampoline was patched in).
     *
     * Consumed by `NativePrologueHashProbe` (rank 9.7, mitigation_layer
     * `not_spoofable`). Cannot be measured from the JVM — the snapshot
     * MUST be populated by a native ptrace-style harness at capture
     * time. An empty map means "no measurement performed", which the
     * probe scores as zero signal (absent != clean).
     */
    val prologueHashDeltas: Map<String, Boolean> = emptyMap(),

    /**
     * Count of MOV X16 / BR X16 (AArch64) trampoline patterns observed
     * across the scanned function prologues. Each occurrence is a
     * single inline-hook indicator; any non-zero count is a critical
     * signal. Consumed by `NativePrologueHashProbe` (rank 9.7).
     */
    val trampolinePatternCount: Int = 0,

    /**
     * GOT/PLT entry anomaly map produced by a runtime native-side
     * comparison of resolved global-offset-table function pointers
     * against the expected (canonical) target library. Keys are
     * function symbols; values are short descriptions of the
     * unexpected target (e.g. `"resolves to /data/local/tmp/frida.so"`).
     *
     * Consumed by `PrologueGotHooksProbe` (rank 9.8, mitigation_layer
     * `not_spoofable`). Cannot be measured from the JVM — captured
     * at runtime by a native-side GOT-table walker. Empty map means
     * "no measurement performed" — absent != clean.
     */
    val gotPltAnomalies: Map<String, String> = emptyMap(),

    /**
     * Memory segments observed with simultaneous write + execute (`rwxp`)
     * permissions in `/proc/self/maps`. Each entry is a smoking-gun
     * signal that text-section memory has been made writable — the
     * canonical state right after an inline-hook installer patches a
     * function prologue. Consumed by `PrologueGotHooksProbe` (rank 9.8).
     * Empty list = clean (no rwxp pages observed).
     */
    val rwxpMemorySegments: List<String> = emptyList(),

    /**
     * `/proc/<pid>/mountinfo` content per PID. Keys are pid strings —
     * canonical entries are `"self"` (calling process) and `"1"` (init).
     * Values are the full mountinfo content verbatim — one mount entry
     * per line in the standard kernel format
     * (`mount-id parent-id major:minor root mount-point opts ...`).
     * Missing key → `queryMountInfo(pid)` returns `null` ("no
     * observation"). Empty-string value → `queryMountInfo(pid)`
     * returns `""` (file existed but was empty).
     *
     * Power-13 Gap #3 (`root.mount_ns_mismatch`) consumes both
     * `mountInfo["self"]` and `mountInfo["1"]` for the digest
     * comparison. Power-13 Gap #10 (`root.system_rw_mount`) and
     * Gap #12 (`root.overlayfs_present`) parse the content for
     * specific lines.
     */
    val mountInfo: Map<String, String?> = emptyMap(),

    /**
     * Unix-Domain Socket names harvested from `/proc/net/unix` —
     * field 8 of each line. Abstract-namespace sockets prefixed
     * with `@`; file-namespace sockets show the filesystem path
     * (e.g. `/dev/socket/installd`). Empty list = no UDS
     * observation captured. Consumed by Power-13 Gap #1
     * (`root.magisk_uds`, rank 3.5) — scans the list for Magisk's
     * dispositive socket-name substrings (`@MAGISK`, `magisk`,
     * `/.magisk` path-fragments).
     */
    val procNetUnixSockets: List<String> = emptyList(),

    /**
     * Enumerated `init.svc.<name>` system properties harvested via
     * `__system_property_foreach()`. Keys are service-name suffixes
     * (e.g. `"zygote"`, `"surfaceflinger"`); values are init-service
     * state literals (`"running"`, `"stopped"`, etc.). Empty map =
     * no init.svc observation captured. Consumed by Power-13
     * Gap #2 (`runtime.init_svc_enumeration`, rank 3.7) — compares
     * observed service-name set against the known-AOSP baseline.
     */
    val initSvcProps: Map<String, String> = emptyMap(),

    /**
     * Directory-listing observations. Keys are absolute filesystem
     * paths; values are lists of basename entries within those
     * directories. Missing key → `queryDirEntries(path)` returns
     * `null` (no observation OR directory doesn't exist OR
     * permission denied). Empty list value → directory observed
     * and confirmed empty.
     *
     * Consumed by Power-13 Gap #8 (`root.magisk_module_dir`,
     * rank 3.9) — checks `/data/adb/modules/` for any entries
     * (including empty-but-present which still indicates Magisk).
     */
    val dirEntries: Map<String, List<String>> = emptyMap(),

    /**
     * Installer package name for THIS app, as reported by
     * `PackageManager.getInstallSourceInfo()` (Android 11+) or the legacy
     * `getInstallerPackageName()` (pre-Android-11). Consumed by
     * `IntegrityInstallSourceProbe` (inventory rank 10.5, freeRASP T5 —
     * Detecting Unofficial Installation).
     *
     * Value semantics:
     *   - `"com.android.vending"` / `"com.google.android.feedback"` /
     *     `"com.huawei.appmarket"` / `"com.sec.android.app.samsungapps"` /
     *     `"com.xiaomi.mipicks"` / `"com.oppo.market"` / `"com.vivo.appstore"`
     *     — installed via the corresponding legitimate store. Probe scores
     *     0.05 (clean).
     *   - Any other package string (e.g. `"com.evil.unofficial"`,
     *     `"org.fdroid.fdroid"`, `"com.apkmirror.helper"`) — installed via
     *     an unofficial third-party store. Probe scores 0.95 (dispositive).
     *   - `null` — installer unknown. Either the app was sideloaded
     *     (`adb install`), or it is a system pre-install where the OEM
     *     didn't record an installer, or the platform's
     *     `getInstallSourceInfo()` accessor returned a null
     *     `installingPackageName`. Probe scores 0.85 (strong suspicion —
     *     real production installs from a store always populate this).
     *
     * Default `null` means "snapshot did not capture installer info"
     * which is observationally equivalent to "installer is unknown"; the
     * probe scoring conflates the two and downstream consumers should
     * read CONFIDENCE rather than re-distinguishing.
     */
    val installSourcePackage: String? = null,
)

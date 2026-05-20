// agents/detection/src/core/replay/SnapshotReplayContext.kt
//
// JVM-side `ProbeContext` whose answers are pure functions of a frozen
// `DeviceSnapshot`. Drives the existing 62-probe ProbeRunner end-to-end
// against captured ReDroid 12 telemetry without requiring a live emulator
// to be booted or an APK to be built.
//
// This is *not* a test fake — it is a production data path. The contract
// with `ProbeContext` is honored verbatim; any probe that compiles against
// `ProbeContext` runs against a `SnapshotReplayContext` with no further
// adaptation.

package com.detectorlab.core.replay

import com.detectorlab.core.DisplayMetricsView
import com.detectorlab.core.LocationManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField

/**
 * `ProbeContext` backed by a recorded `DeviceSnapshot`. Stateless except
 * for the snapshot reference; safe to share across probes.
 *
 * Accessor semantics (mapped against `DeviceSnapshot` field docs):
 *  * `getSystemProperty(key)` → `systemProperties[key]` (missing → null,
 *    empty → empty preserved).
 *  * `fileExists(path)` → `path in existingFiles`.
 *  * `readFile(path, maxBytes)` → first `maxBytes` chars of
 *    `readableFiles[path]` if present, else null.
 *  * `querySettingSecure/Global/System(key)` → respective map lookups.
 *  * `queryTelephonyManager(field)` → `telephony[field.name]`.
 *  * `queryPackageManager()` → view backed by `installedPackages`.
 *  * `querySensorManager()` → view backed by `snapshot.sensorTypes`.
 *    Sensors in the set are reported present via `listSensorTypes()`;
 *    `sampleSensor(type, _)` returns an empty `SensorSample` (no live
 *    samples in snapshot capture). An empty `sensorTypes` set reports
 *    "no sensors installed" — the realistic ReDroid-without-HAL answer
 *    and the conservative default for snapshots that don't capture
 *    sensor inventory.
 *
 * The Unknown* views inherited from `ProbeContext` defaults cover
 * Keyguard / Wifi / MediaProjection / UserHandle / TimeView — every
 * snapshot is a "device with no live capability views" by default,
 * which is exactly the correct conservative answer for a recorded
 * snapshot.
 */
class SnapshotReplayContext(private val snapshot: DeviceSnapshot) : ProbeContext {

    override fun getSystemProperty(key: String): String? = snapshot.systemProperties[key]

    override fun fileExists(path: String): Boolean = path in snapshot.existingFiles

    override fun readFile(path: String, maxBytes: Int): String? {
        val contents = snapshot.readableFiles[path] ?: return null
        return if (contents.length <= maxBytes) contents else contents.substring(0, maxBytes)
    }

    override fun querySettingSecure(key: String): String? = snapshot.settingsSecure[key]
    override fun querySettingGlobal(key: String): String? = snapshot.settingsGlobal[key]
    override fun querySettingSystem(key: String): String? = snapshot.settingsSystem[key]

    override fun queryTelephonyManager(field: TelephonyField): String? =
        snapshot.telephony[field.name]

    override fun queryPackageManager(): PackageManagerView =
        SnapshotPackageManagerView(snapshot.installedPackages)

    override fun querySensorManager(): SensorManagerView =
        SnapshotSensorManagerView(snapshot.sensorTypes)

    /**
     * Snapshot-side bridge for `BluetoothMacProbe` (rank 31). Overrides the
     * `ProbeContext` default (`= null`) to return the snapshot's
     * `bluetoothMac` field. Test code wires the probe with a supplier that
     * delegates here:
     *
     * ```
     * val ctx = SnapshotReplayContext(snap)
     * val probe = BluetoothMacProbe(
     *     bluetoothAdapterMacSupplier = { ctx.queryBluetoothAdapterMac() }
     * )
     * ```
     *
     * Closes the BluetoothAdapter contract gap that the rank-31 KDoc lines
     * 29-32 document; uniform with the `querySettingGlobal` /
     * `queryWifiManager` / `queryKeyguardManager` "default returns the
     * conservative answer, production impl overrides" pattern.
     */
    override fun queryBluetoothAdapterMac(): String? = snapshot.bluetoothMac

    /**
     * Snapshot-side bridge for `TimezoneLocaleProbe` (rank 20). Overrides
     * the `ProbeContext` default (`= null`) to return the snapshot's
     * `timezoneId` field. Closes the Iter-1 probe-quality bug where the
     * probe's default supplier read `java.util.TimeZone.getDefault().id`
     * directly — leaking the host JVM timezone into snapshot replays.
     */
    override fun queryTimezoneId(): String? = snapshot.timezoneId

    /**
     * Snapshot-side bridge for `TimezoneLocaleProbe` evidence row
     * `timezone_offset_minutes`. Overrides the `ProbeContext` default
     * (`= null`) to return the snapshot's `timezoneOffsetMinutes` field.
     */
    override fun queryTimezoneOffsetMinutes(): Int? = snapshot.timezoneOffsetMinutes

    /**
     * Snapshot-side bridge for `LanguageCountryProbe` (rank 36) and
     * `TimezoneLocaleProbe` (rank 20). Overrides the `ProbeContext` default
     * (`= null`) to return the snapshot's `localeLanguage` field. Closes
     * the Iter-1 probe-quality bug where the probe's default supplier read
     * `java.util.Locale.getDefault().language` directly.
     */
    override fun queryLocaleLanguage(): String? = snapshot.localeLanguage

    /**
     * Snapshot-side bridge for `LanguageCountryProbe` and `TimezoneLocaleProbe`.
     * Overrides the `ProbeContext` default (`= null`) to return the
     * snapshot's `localeCountry` field. `null` vs `""` distinction preserved
     * verbatim — see `DeviceSnapshot` field doc.
     */
    override fun queryLocaleCountry(): String? = snapshot.localeCountry

    /**
     * Snapshot-side bridge for `LanguageCountryProbe` (rank 36). Overrides
     * the `ProbeContext` default (`= null`) to return the snapshot's
     * `localeDisplayName` field. Evidence-only signal.
     */
    override fun queryLocaleDisplayName(): String? = snapshot.localeDisplayName

    /**
     * Snapshot-side bridge for `ScreenResolutionProbe` (rank 23). Overrides
     * the `ProbeContext` default (`= null`) to synthesize a
     * `DisplayMetricsView` over the snapshot's flat display fields. Returns
     * `null` (= no display observation possible) when ALL of the
     * `displayWidthPixels` / `displayHeightPixels` / `displayDensityDpi`
     * fields are null — this is the conservative "snapshot did not capture
     * display metrics" answer, which the probe reads as
     * `PATTERN_NO_DISPLAY=0.5`. When at least one of the three load-bearing
     * fields is populated, returns a view that delegates each accessor to
     * the corresponding snapshot field (which may individually still be
     * null — a per-field nullability that matches the
     * `DisplayMetricsView` contract).
     */
    override fun queryDisplayMetrics(): DisplayMetricsView? {
        val anyPresent = snapshot.displayWidthPixels != null ||
            snapshot.displayHeightPixels != null ||
            snapshot.displayDensityDpi != null ||
            snapshot.displayXdpi != null ||
            snapshot.displayYdpi != null
        if (!anyPresent) return null
        return SnapshotDisplayMetricsView(snapshot)
    }

    /**
     * Snapshot-side bridge for `GpsCoordinatesProbe` (rank 41). Overrides the
     * `ProbeContext` default (`= UnknownLocationManagerView`) to synthesize a
     * `LocationManagerView` over the snapshot's flat `gps*` fields. Returns
     * the singleton `UnknownLocationManagerView` (= "permission missing / no
     * observation possible") when ALL of `gpsLat` / `gpsLng` /
     * `gpsAccuracy` / `gpsProvider` / `gpsIsMock` are null — this is the
     * conservative "snapshot did not capture GPS state" answer, matching the
     * rank-41 KDoc CONFIDENCE_DEGRADED branch. When at least one field is
     * populated, returns a view that delegates each accessor to the
     * corresponding snapshot field.
     */
    override fun queryLocationManager(): LocationManagerView {
        val anyPresent = snapshot.gpsLat != null ||
            snapshot.gpsLng != null ||
            snapshot.gpsAccuracy != null ||
            snapshot.gpsProvider != null ||
            snapshot.gpsIsMock != null
        if (!anyPresent) return com.detectorlab.core.UnknownLocationManagerView
        return SnapshotLocationManagerView(snapshot)
    }

    /**
     * Snapshot-side bridge for `FridaMemoryMapsProbe` (rank 9.0). Returns
     * the snapshot's `procSelfMapsLibs` field so the probe sees the
     * captured library set rather than the empty default.
     */
    override fun queryProcSelfMapsLibs(): Set<String> = snapshot.procSelfMapsLibs

    /**
     * Snapshot-side bridge for `FridaMemoryMapsProbe` thread-name surface.
     * Returns the snapshot's `runtimeThreadNames` field.
     */
    override fun queryRuntimeThreadNames(): Set<String> = snapshot.runtimeThreadNames

    /**
     * Snapshot-side bridge for `FridaMemoryMapsProbe` open-port surface.
     * Returns the snapshot's `openTcpPorts` field.
     */
    override fun queryOpenTcpPorts(): Set<Int> = snapshot.openTcpPorts

    /**
     * Snapshot-side bridge for `NativePrologueHashProbe` (rank 9.7).
     * Returns the snapshot's `prologueHashDeltas` field. Empty map =
     * "no native-side measurement recorded at capture time" — probe
     * scores zero, NOT a clean signal.
     */
    override fun queryPrologueHashDeltas(): Map<String, Boolean> =
        snapshot.prologueHashDeltas

    /**
     * Snapshot-side bridge for `NativePrologueHashProbe` trampoline-count
     * surface. Returns the snapshot's `trampolinePatternCount` field.
     */
    override fun queryTrampolinePatternCount(): Int =
        snapshot.trampolinePatternCount

    /**
     * Snapshot-side bridge for `PrologueGotHooksProbe` (rank 9.8).
     * Returns the snapshot's `gotPltAnomalies` field.
     */
    override fun queryGotPltAnomalies(): Map<String, String> =
        snapshot.gotPltAnomalies

    /**
     * Snapshot-side bridge for `PrologueGotHooksProbe` rwxp-segment
     * surface. Returns the snapshot's `rwxpMemorySegments` field.
     */
    override fun queryRwxpMemorySegments(): List<String> =
        snapshot.rwxpMemorySegments

    /**
     * Snapshot-side bridge for Power-13 Gap #3 (`MountNsMismatchProbe`),
     * Gap #10 (`SystemRwMountProbe`), Gap #12 (`OverlayFsPresentProbe`).
     * Returns the snapshot's `mountInfo[pid]` entry verbatim. Missing
     * key → null (no observation); empty-string preserved.
     */
    override fun queryMountInfo(pid: String): String? = snapshot.mountInfo[pid]

    /**
     * Snapshot-side bridge for Power-13 Gap #1 (`MagiskUdsProbe`).
     * Returns the snapshot's `procNetUnixSockets` list verbatim.
     * Empty list = no observation captured.
     */
    override fun queryProcNetUnixSockets(): List<String> = snapshot.procNetUnixSockets

    /**
     * Snapshot-side bridge for Power-13 Gap #2
     * (`InitSvcEnumerationProbe`). Returns the snapshot's
     * `initSvcProps` map verbatim. Empty map = no observation
     * captured.
     */
    override fun queryInitSvcProps(): Map<String, String> = snapshot.initSvcProps

    /**
     * Snapshot-side bridge for Power-13 Gap #8
     * (`MagiskModuleDirProbe`). Returns the snapshot's
     * `dirEntries[path]` entry. Missing key → null (no
     * observation; directory does not exist OR permission denied).
     */
    override fun queryDirEntries(path: String): List<String>? = snapshot.dirEntries[path]

    /**
     * Snapshot-side bridge for `IntegrityInstallSourceProbe` (inventory
     * rank 10.5, freeRASP T5). Returns the snapshot's
     * `installSourcePackage` field verbatim. `null` value preserved as
     * `null` (= "installer unknown / sideload / system pre-install"
     * branch in the probe's scoring).
     */
    override fun queryInstallSourcePackage(): String? = snapshot.installSourcePackage
}

/**
 * Read-only `PackageManagerView` over a snapshot's installed-packages set.
 * `listPackagesWithPermission` returns empty: snapshots do not capture
 * per-package permission grants, and probes that rely on this view should
 * fall back gracefully (per their existing failure-mode contracts).
 */
internal class SnapshotPackageManagerView(
    private val packages: Set<String>,
) : PackageManagerView {
    override fun isPackageInstalled(packageName: String): Boolean = packageName in packages
    override fun listInstalledPackages(): List<String> = packages.toList()
    override fun listPackagesWithPermission(permission: String): List<String> = emptyList()
}

/**
 * Sensor manager backed by a snapshot's `sensorTypes` set. The set holds
 * canonical Android `Sensor.TYPE_*` integers (e.g. 1 = TYPE_ACCELEROMETER,
 * 4 = TYPE_GYROSCOPE, 5 = TYPE_LIGHT, 8 = TYPE_PROXIMITY).
 *
 * `sampleSensor()` returns an empty `SensorSample`: snapshot capture does
 * not include live sensor time-series. Sensor-family probes (rank 24/42/
 * 43/44/45) handle empty samples gracefully — they require ≥2 samples to
 * trigger the constant-stub rule and the implausible-value rule needs at
 * least one reading, so an empty sample produces neither false positive.
 *
 * An empty `sensorTypes` set degrades to the previous `EmptySensorManagerView`
 * behavior: zero installed sensors (the ReDroid-without-HAL reality and
 * the conservative default for snapshots that don't declare sensors).
 */
internal class SnapshotSensorManagerView(
    private val sensorTypes: Set<Int>,
) : SensorManagerView {
    override fun listSensorTypes(): List<Int> = sensorTypes.toList()
    override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample =
        SensorSample(timestamps = LongArray(0), values = emptyArray())
}

/**
 * `DisplayMetricsView` backed by a `DeviceSnapshot`'s flat display fields.
 * Each accessor returns the corresponding snapshot field verbatim — `null`
 * means the snapshot didn't capture that signal, matching the
 * `DisplayMetricsView` contract that any field may be individually
 * unknown.
 *
 * Used by `SnapshotReplayContext.queryDisplayMetrics()`; only instantiated
 * when at least one of the snapshot's display fields is populated (else
 * the override returns `null` to indicate "no display observation
 * possible").
 */
internal class SnapshotDisplayMetricsView(
    private val snapshot: DeviceSnapshot,
) : DisplayMetricsView {
    override fun widthPixels(): Int? = snapshot.displayWidthPixels
    override fun heightPixels(): Int? = snapshot.displayHeightPixels
    override fun densityDpi(): Int? = snapshot.displayDensityDpi
    override fun xdpi(): Float? = snapshot.displayXdpi
    override fun ydpi(): Float? = snapshot.displayYdpi
}

/**
 * `LocationManagerView` backed by a `DeviceSnapshot`'s flat `gps*` fields.
 * `hasLastKnownLocation()` returns true iff at least one of `gpsLat` / `gpsLng`
 * is populated — a "lat-only" or "lng-only" fix is still a fix-frame, just
 * with one coordinate omitted by the provider (matches the per-field
 * nullability the `LocationManagerView` contract permits).
 *
 * `sdkInt()` is taken from `snapshot.sdkInt` so the API-18 mock-provider
 * gate in `GpsCoordinatesProbe` reflects the captured runtime, not a fixed
 * value. Used by `SnapshotReplayContext.queryLocationManager()`; only
 * instantiated when at least one of the snapshot's `gps*` fields is
 * populated (else the override returns `UnknownLocationManagerView`).
 */
internal class SnapshotLocationManagerView(
    private val snapshot: DeviceSnapshot,
) : LocationManagerView {
    override fun sdkInt(): Int = snapshot.sdkInt
    override fun hasLastKnownLocation(): Boolean? =
        snapshot.gpsLat != null || snapshot.gpsLng != null
    override fun lastKnownLatitude(): Double? = snapshot.gpsLat
    override fun lastKnownLongitude(): Double? = snapshot.gpsLng
    override fun lastKnownAccuracy(): Float? = snapshot.gpsAccuracy
    override fun lastKnownProvider(): String? = snapshot.gpsProvider
    override fun isMockProvider(): Boolean? = snapshot.gpsIsMock
}

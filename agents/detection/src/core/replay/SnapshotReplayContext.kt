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

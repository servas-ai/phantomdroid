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
 *  * `querySensorManager()` → empty sensor inventory (snapshots do not
 *    yet capture sensor samples; probes that read sensors will see
 *    "no sensors installed" which is the realistic ReDroid-without-HAL
 *    answer anyway).
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

    override fun querySensorManager(): SensorManagerView = EmptySensorManagerView
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
 * Sensor manager that reports zero installed sensors. Matches the
 * `ReDroid-without-HAL` reality (containerized ReDroid has no physical
 * sensor stack), and is the most conservative answer when replaying
 * a snapshot that does not include sensor data.
 */
internal object EmptySensorManagerView : SensorManagerView {
    override fun listSensorTypes(): List<Int> = emptyList()
    override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample =
        SensorSample(timestamps = LongArray(0), values = emptyArray())
}

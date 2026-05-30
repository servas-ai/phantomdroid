// apps/detector-app/.../AndroidProbeRegistryTest.kt
//
// Local (host-JVM) unit test. Does NOT touch the Android framework — it drives
// the registry through a minimal in-process ProbeContext stub so the test runs
// under `testDebugUnitTest` without an emulator. Guards two invariants:
//   1. The on-device registry instantiates the canonical 65-probe inventory
//      (lockstep with :detection-cli's ProbeRegistry).
//   2. The full pipeline (ProbeRunner + AndroidReportSerializer) produces a
//      schemaVersion-1.0 report against a no-signal context — i.e. every probe
//      ABSTAINs gracefully rather than crashing when the context returns the
//      conservative "no observation" defaults.

package com.detectorlab.detectorapp

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProbeRegistryTest {

    /** Minimal ProbeContext that returns the conservative "no observation"
     *  answer for everything — exercises the graceful-degradation path. */
    private object NoSignalContext : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String): Boolean = false
            override fun listInstalledPackages(): List<String> = emptyList()
            override fun listPackagesWithPermission(permission: String): List<String> = emptyList()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes(): List<Int> = emptyList()
            override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    @Test
    fun registryInstantiatesExpectedCount() {
        val probes = AndroidProbeRegistry.allProbes(NoSignalContext)
        assertEquals(
            AndroidProbeRegistry.EXPECTED_COUNT,
            probes.size,
            "on-device registry must match the canonical 65-probe inventory",
        )
    }

    @Test
    fun probeIdsAreUnique() {
        val ids = AndroidProbeRegistry.allProbes(NoSignalContext).map { it.id }
        assertEquals(ids.size, ids.toSet().size, "probe ids must be unique")
    }

    @Test
    fun fullPipelineProducesSchemaV1Report() = runTest {
        val probes = AndroidProbeRegistry.allProbes(NoSignalContext)
        val runner = ProbeRunner(
            probes = probes,
            ctx = NoSignalContext,
            appVersion = DetectorRunner.APP_VERSION,
            deviceLabel = "unit-test-no-signal",
        )
        val report = runner.runAll()
        assertEquals("1.0", report.schemaVersion)
        assertEquals(65, report.probes.size)

        val json = AndroidReportSerializer.toJson(report)
        assertTrue(json.contains("\"schemaVersion\": \"1.0\""), "JSON must carry schemaVersion 1.0")
        assertTrue(json.contains("\"aggregate\""), "JSON must carry aggregate block")
    }
}

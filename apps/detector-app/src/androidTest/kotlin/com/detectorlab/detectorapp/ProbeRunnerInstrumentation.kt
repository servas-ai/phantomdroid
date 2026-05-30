// apps/detector-app/.../ProbeRunnerInstrumentation.kt
//
// Headless on-device runner — the canonical path apk-deliver uses to close the
// in-container attestation gap. Driven via:
//
//   adb -s 127.0.0.1:5555 install -r detector-app-debug.apk
//   adb -s 127.0.0.1:5555 install -r detector-app-debug-androidTest.apk
//   adb -s 127.0.0.1:5555 shell am instrument -w \
//       com.detectorlab.detectorapp.test/androidx.test.runner.AndroidJUnitRunner
//   adb -s 127.0.0.1:5555 pull \
//       /sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json
//
// Unlike the adb-shell probe-input capture (audit §2), this runs the probe
// LOGIC in-process inside Android (ART), so the in-process-only signals
// (TracerPid of the app, Settings via ContentResolver, TelephonyManager,
// /proc/self/maps of the app) are measured for real here.

package com.detectorlab.detectorapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbeRunnerInstrumentation {

    /**
     * Runs the full 65-probe inventory in-process against the live device and
     * persists the schemaVersion-1.0 report to the app external files dir.
     * Asserts the report shape; the produced JSON is the artifact to `adb pull`.
     */
    @Test
    fun runFullInventoryOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outcome = DetectorRunner.runAndPersist(ctx)

        assertEquals("1.0", outcome.report.schemaVersion)
        assertEquals(
            AndroidProbeRegistry.EXPECTED_COUNT,
            outcome.report.probes.size,
        )
        assertTrue(
            "report file must exist for adb pull",
            outcome.reportFile.exists() && outcome.reportFile.length() > 0,
        )

        // Surface the verdict in the instrumentation log (visible in
        // `am instrument -w` output) so a run is observable without pulling.
        val agg = outcome.report.aggregate
        println(
            "DetectorLab[on-device]: category=${agg.category} " +
                "weightedScore=${"%.4f".format(agg.weightedScore)} " +
                "criticalFailures=${agg.criticalFailures} " +
                "report=${outcome.reportFile.absolutePath}",
        )
    }
}

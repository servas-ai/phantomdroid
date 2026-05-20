package com.detectorlab.probes.integrity

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PrologueGotHooksProbe (integrity.prologue_got_hooks,
 * inventory rank 9.8, code rank 85). Declarative variant —
 * mitigation_layer not_spoofable.
 */
class PrologueGotHooksProbeTest {

    private val probe = PrologueGotHooksProbe()

    private fun fakeCtx(
        anomalies: Map<String, String> = emptyMap(),
        rwxpSegments: List<String> = emptyList(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }

        override fun queryGotPltAnomalies(): Map<String, String> = anomalies
        override fun queryRwxpMemorySegments(): List<String> = rwxpSegments
    }

    // ── Clean (no measurement) ───────────────────────────────────────────────

    @Test
    fun `empty input — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no measurement — confidence is no_measurement floor`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(PrologueGotHooksProbe.CONFIDENCE_NO_MEASUREMENT, result.confidence)
    }

    @Test
    fun `no measurement — pattern is no_measurement`() = runBlocking {
        val result = probe.run(fakeCtx())
        val patternEv = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.pattern"
        }
        assertEquals(PrologueGotHooksProbe.PATTERN_NO_MEASUREMENT, patternEv?.value)
    }

    // ── GOT/PLT anomalies (0.5 per, capped) ──────────────────────────────────

    @Test
    fun `1 GOT anomaly — score is 0_5`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf(
                    "dlopen" to "resolves to /data/local/tmp/frida.so",
                ),
            ),
        )
        assertEquals(0.5, result.score)
        val patternEv = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.pattern"
        }
        assertEquals(PrologueGotHooksProbe.PATTERN_GOT_PLT_ANOMALY, patternEv?.value)
    }

    @Test
    fun `2 GOT anomalies — score is 1_0 (saturated)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf(
                    "dlopen" to "unexpected target",
                    "open" to "unexpected target",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `4 GOT anomalies — score capped at 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf(
                    "dlopen" to "t1",
                    "open" to "t2",
                    "read" to "t3",
                    "fopen" to "t4",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    // ── rwxp memory segments (1.0 dispositive) ───────────────────────────────

    @Test
    fun `1 rwxp segment — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                rwxpSegments = listOf("7f1234000000-7f1234001000 rwxp 00000000"),
            ),
        )
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.pattern"
        }
        assertEquals(PrologueGotHooksProbe.PATTERN_RWXP_SEGMENT, patternEv?.value)
    }

    @Test
    fun `3 rwxp segments — score capped at 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                rwxpSegments = listOf("seg1", "seg2", "seg3"),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `rwxp plus GOT anomalies — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf(
                    "dlopen" to "t1",
                    "open" to "t2",
                    "read" to "t3",
                ),
                rwxpSegments = listOf("seg1", "seg2"),
            ),
        )
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.pattern"
        }
        // rwxp tier wins the pattern label even when both fire.
        assertEquals(PrologueGotHooksProbe.PATTERN_RWXP_SEGMENT, patternEv?.value)
    }

    // ── Evidence structure ───────────────────────────────────────────────────

    @Test
    fun `evidence keys are namespaced under probe id`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue(keys.all { it.startsWith("integrity.prologue_got_hooks.") })
    }

    @Test
    fun `evidence reports GOT anomaly symbols sorted`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf("ptrace" to "t1", "dlopen" to "t2", "open" to "t3"),
            ),
        )
        val ev = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.got_anomalies"
        }
        assertEquals("dlopen,open,ptrace", ev?.value)
    }

    @Test
    fun `evidence reports counts`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                anomalies = mapOf("dlopen" to "t1", "open" to "t2"),
                rwxpSegments = listOf("seg1"),
            ),
        )
        val rwxpCount = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.rwxp_segment_count"
        }
        val gotCount = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.got_anomaly_count"
        }
        assertEquals("1", rwxpCount?.value)
        assertEquals("2", gotCount?.value)
    }

    @Test
    fun `evidence reports measurement_present false on empty input`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find {
            it.key == "integrity.prologue_got_hooks.measurement_present"
        }
        assertEquals("false", ev?.value)
    }

    // ── Severity / inventory metadata ────────────────────────────────────────

    @Test
    fun `inventoryRank is 9_8`() {
        assertEquals(9.8, probe.inventoryRank)
    }

    @Test
    fun `severity is CRITICAL`() {
        assertEquals(com.detectorlab.core.ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe id matches inventory`() {
        assertEquals("integrity.prologue_got_hooks", probe.id)
    }
}

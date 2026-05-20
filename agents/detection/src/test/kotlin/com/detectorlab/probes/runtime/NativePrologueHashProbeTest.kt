package com.detectorlab.probes.runtime

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
 * Unit tests for NativePrologueHashProbe (runtime.native_prologue_hash,
 * inventory rank 9.7, code rank 84). Declarative variant —
 * mitigation_layer not_spoofable.
 */
class NativePrologueHashProbeTest {

    private val probe = NativePrologueHashProbe()

    private fun fakeCtx(
        deltas: Map<String, Boolean> = emptyMap(),
        trampolineCount: Int = 0,
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

        override fun queryPrologueHashDeltas(): Map<String, Boolean> = deltas
        override fun queryTrampolinePatternCount(): Int = trampolineCount
    }

    // ── No measurement (clean snapshot) ──────────────────────────────────────

    @Test
    fun `no measurement — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no measurement — confidence is no_measurement floor`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(NativePrologueHashProbe.CONFIDENCE_NO_MEASUREMENT, result.confidence)
    }

    @Test
    fun `no measurement — pattern is no_measurement`() = runBlocking {
        val result = probe.run(fakeCtx())
        val patternEv = result.evidence.find {
            it.key == "runtime.native_prologue_hash.pattern"
        }
        assertEquals(NativePrologueHashProbe.PATTERN_NO_MEASUREMENT, patternEv?.value)
    }

    // ── Measurement performed, no hooks (clean device with real scan) ────────

    @Test
    fun `measurement performed with no hooks — score is 0`() = runBlocking {
        // All deltas false = scan happened, no mismatches.
        val result = probe.run(
            fakeCtx(deltas = mapOf("dlopen" to false, "open" to false, "read" to false)),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `measurement performed with no hooks — confidence is measured`() = runBlocking {
        val result = probe.run(
            fakeCtx(deltas = mapOf("dlopen" to false, "open" to false)),
        )
        assertEquals(NativePrologueHashProbe.CONFIDENCE_MEASURED, result.confidence)
    }

    // ── Hash mismatch (0.5 per mismatch, capped) ─────────────────────────────

    @Test
    fun `1 prologue mismatch — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(deltas = mapOf("dlopen" to true)))
        assertEquals(0.5, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.native_prologue_hash.pattern"
        }
        assertEquals(NativePrologueHashProbe.PATTERN_PROLOGUE_HASH_MISMATCH, patternEv?.value)
    }

    @Test
    fun `2 prologue mismatches — score is 1_0 (saturated)`() = runBlocking {
        val result = probe.run(
            fakeCtx(deltas = mapOf("dlopen" to true, "open" to true)),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `3+ prologue mismatches — score is capped at 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                deltas = mapOf(
                    "dlopen" to true,
                    "open" to true,
                    "read" to true,
                    "fopen" to true,
                    "ptrace" to true,
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mixed deltas — only true entries count toward score`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                deltas = mapOf(
                    "dlopen" to true,
                    "open" to false,
                    "read" to false,
                ),
            ),
        )
        assertEquals(0.5, result.score)
    }

    // ── Trampoline patterns (1.0 dispositive) ────────────────────────────────

    @Test
    fun `1 trampoline pattern — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(trampolineCount = 1))
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.native_prologue_hash.pattern"
        }
        assertEquals(NativePrologueHashProbe.PATTERN_TRAMPOLINE_DETECTED, patternEv?.value)
    }

    @Test
    fun `5 trampoline patterns — score is capped at 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(trampolineCount = 5))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `trampoline plus mismatches — score is capped at 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                deltas = mapOf("dlopen" to true, "open" to true, "read" to true),
                trampolineCount = 3,
            ),
        )
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.native_prologue_hash.pattern"
        }
        // Trampoline tier wins the pattern label even when both fire.
        assertEquals(NativePrologueHashProbe.PATTERN_TRAMPOLINE_DETECTED, patternEv?.value)
    }

    // ── Evidence structure ───────────────────────────────────────────────────

    @Test
    fun `evidence keys are namespaced under probe id`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue(keys.all { it.startsWith("runtime.native_prologue_hash.") })
    }

    @Test
    fun `evidence reports mismatched symbols sorted`() = runBlocking {
        val result = probe.run(
            fakeCtx(deltas = mapOf("ptrace" to true, "dlopen" to true, "open" to true)),
        )
        val symEv = result.evidence.find {
            it.key == "runtime.native_prologue_hash.mismatched_symbols"
        }
        assertEquals("dlopen,open,ptrace", symEv?.value)
    }

    @Test
    fun `evidence reports measurement_present false on empty input`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find {
            it.key == "runtime.native_prologue_hash.measurement_present"
        }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `evidence reports measurement_present true with deltas`() = runBlocking {
        val result = probe.run(fakeCtx(deltas = mapOf("dlopen" to false)))
        val ev = result.evidence.find {
            it.key == "runtime.native_prologue_hash.measurement_present"
        }
        assertEquals("true", ev?.value)
    }

    // ── Severity / inventory metadata ────────────────────────────────────────

    @Test
    fun `inventoryRank is 9_7`() {
        assertEquals(9.7, probe.inventoryRank)
    }

    @Test
    fun `severity is CRITICAL`() {
        assertEquals(com.detectorlab.core.ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe id matches inventory`() {
        assertEquals("runtime.native_prologue_hash", probe.id)
    }
}

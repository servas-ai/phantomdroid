package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for APatchRootProbe (rank 3.85, code-rank 95).
 *
 * Two principal evidence cascades:
 *   • clean baseline (no files, no prop) → score 0.0 (PATTERN_CLEAN)
 *   • dirty positive paths
 *       – files present                  → score 1.00
 *       – property-only (no files)       → score 0.90
 *
 * Tests also cover:
 *   • file-accessor throws → DEGRADED confidence
 *   • cross-cutting #1 namespacing (every evidence key is `apatch.*`)
 *   • cross-cutting #7 fractional rank (inventoryRank == 3.85)
 */
class APatchRootProbeTest {

    private val probe = APatchRootProbe()

    private fun fakeCtx(
        presentFiles: Set<String> = emptySet(),
        systemProps: Map<String, String> = emptyMap(),
        fileAccessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = systemProps[key]
        override fun fileExists(path: String): Boolean {
            if (fileAccessorThrows) throw RuntimeException("simulated fileExists throw")
            return path in presentFiles
        }
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
    }

    // ── Clean baseline — score 0.0 ───────────────────────────────────────────

    @Test
    fun `clean device — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean device — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "apatch.pattern" }
        assertEquals(APatchRootProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean device — files_hit is none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "apatch.files_hit" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `clean device — signature_property is null sentinel`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "apatch.signature_property" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `clean device — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── Dirty: APatch files present — score 1.0 ──────────────────────────────

    @Test
    fun `apatch working dir present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ap")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `apatch daemon present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ap/bin/apd")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `both apatch files present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/ap", "/data/adb/ap/bin/apd")),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `apatch files present — pattern is apatch_files_present`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ap/bin/apd")))
        val ev = result.evidence.find { it.key == "apatch.pattern" }
        assertEquals(APatchRootProbe.PATTERN_APATCH_FILES_PRESENT, ev?.value)
    }

    @Test
    fun `apatch files present — files_hit lists the path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ap")))
        val ev = result.evidence.find { it.key == "apatch.files_hit" }
        assertEquals("/data/adb/ap", ev?.value)
    }

    @Test
    fun `both apatch files present — files_hit lists both comma-joined`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/ap", "/data/adb/ap/bin/apd")),
        )
        val ev = result.evidence.find { it.key == "apatch.files_hit" }
        // Order follows APATCH_PATHS declaration order: ap before ap/bin/apd.
        assertEquals("/data/adb/ap,/data/adb/ap/bin/apd", ev?.value)
    }

    @Test
    fun `files-present beats property-set (max-wins cascade)`() = runBlocking {
        // When both file AND property are set, files signal (1.00) wins
        // over property-only (0.90).
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/data/adb/ap/bin/apd"),
                systemProps = mapOf("ro.apatch.kernel_signature" to "abcd1234"),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "apatch.pattern" }
        assertEquals(APatchRootProbe.PATTERN_APATCH_FILES_PRESENT, ev?.value)
    }

    // ── Dirty: property-only — score 0.90 ────────────────────────────────────

    @Test
    fun `apatch signature property set, no files — score is 0_90`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.apatch.kernel_signature" to "abcd1234")),
        )
        assertEquals(0.90, result.score)
    }

    @Test
    fun `apatch property-only — pattern is apatch_property_set`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.apatch.kernel_signature" to "abcd1234")),
        )
        val ev = result.evidence.find { it.key == "apatch.pattern" }
        assertEquals(APatchRootProbe.PATTERN_APATCH_PROPERTY_SET, ev?.value)
    }

    @Test
    fun `apatch property-only — signature_property carries the value`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.apatch.kernel_signature" to "abcd1234")),
        )
        val ev = result.evidence.find { it.key == "apatch.signature_property" }
        assertEquals("abcd1234", ev?.value)
    }

    @Test
    fun `empty signature property string is treated as null`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.apatch.kernel_signature" to "")),
        )
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "apatch.pattern" }
        assertEquals(APatchRootProbe.PATTERN_CLEAN, ev?.value)
    }

    // ── Defensive: file accessor throws — DEGRADED ───────────────────────────

    @Test
    fun `fileExists throws on every probed path — confidence DEGRADED`() = runBlocking {
        val result = probe.run(fakeCtx(fileAccessorThrows = true))
        assertFalse(result.failed)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `fileExists throws — observation_ok evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(fileAccessorThrows = true))
        val ev = result.evidence.find { it.key == "apatch.observation_ok" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `fileExists throws but property set — still scores property branch`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                fileAccessorThrows = true,
                systemProps = mapOf("ro.apatch.kernel_signature" to "abcd1234"),
            ),
        )
        assertEquals(0.90, result.score)
    }

    // ── Cross-cutting #1: every evidence key namespaced `apatch.*` ───────────

    @Test
    fun `every evidence key is apatch-namespaced (cross-cutting #1)`() = runBlocking {
        val result = probe.run(fakeCtx())
        for (ev in result.evidence) {
            assertTrue(
                ev.key.startsWith("apatch."),
                "evidence key '${ev.key}' is not apatch.* namespaced (cross-cutting #1)",
            )
        }
    }

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "apatch.files_hit",
                "apatch.signature_property",
                "apatch.observation_ok",
                "apatch.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(APatchRootProbe.METHOD, result.method)
    }

    // ── Probe metadata + cross-cutting #7 fractional rank ────────────────────

    @Test
    fun `probe id is root_apatch`() {
        assertEquals("root.apatch", probe.id)
    }

    @Test
    fun `code-rank is 95`() {
        assertEquals(95, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_85 (cross-cutting #7)`() {
        assertEquals(3.85, probe.inventoryRank)
    }

    @Test
    fun `probe category is ROOT`() {
        assertEquals(ProbeCategory.ROOT, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is KERNEL`() {
        assertEquals(AndroidLayer.KERNEL, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

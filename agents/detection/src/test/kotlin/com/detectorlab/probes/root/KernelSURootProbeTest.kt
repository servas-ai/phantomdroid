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
 * Unit tests for KernelSURootProbe (rank 3.6, code-rank 94).
 *
 * Two principal evidence cascades:
 *   • clean baseline (no files, no prop) → score 0.0 (PATTERN_CLEAN)
 *   • dirty positive paths
 *       – files present                  → score 1.00
 *       – property-only (no files)       → score 0.90
 *
 * Tests also cover:
 *   • file-accessor throws → DEGRADED confidence
 *   • cross-cutting #1 namespacing (every evidence key is `ksu.*`)
 *   • cross-cutting #7 fractional rank (inventoryRank == 3.6)
 */
class KernelSURootProbeTest {

    private val probe = KernelSURootProbe()

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
        val ev = result.evidence.find { it.key == "ksu.pattern" }
        assertEquals(KernelSURootProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean device — files_hit is none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ksu.files_hit" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `clean device — version_property is null sentinel`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ksu.version_property" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `clean device — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── Dirty: KSU files present — score 1.0 ─────────────────────────────────

    @Test
    fun `ksu working dir present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ksu")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ksu daemon present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ksud")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `both ksu files present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/ksu", "/data/adb/ksud")),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ksu files present — pattern is ksu_files_present`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ksud")))
        val ev = result.evidence.find { it.key == "ksu.pattern" }
        assertEquals(KernelSURootProbe.PATTERN_KSU_FILES_PRESENT, ev?.value)
    }

    @Test
    fun `ksu files present — files_hit lists the path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/ksu")))
        val ev = result.evidence.find { it.key == "ksu.files_hit" }
        assertEquals("/data/adb/ksu", ev?.value)
    }

    @Test
    fun `both ksu files present — files_hit lists both comma-joined`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/ksu", "/data/adb/ksud")),
        )
        val ev = result.evidence.find { it.key == "ksu.files_hit" }
        // Order follows KSU_PATHS declaration order: ksu before ksud.
        assertEquals("/data/adb/ksu,/data/adb/ksud", ev?.value)
    }

    @Test
    fun `files-present beats property-set (max-wins cascade)`() = runBlocking {
        // When both file AND property are set, files signal (1.00) wins
        // over property-only (0.90).
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/data/adb/ksud"),
                systemProps = mapOf("ro.kernelsu.version" to "11000"),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "ksu.pattern" }
        assertEquals(KernelSURootProbe.PATTERN_KSU_FILES_PRESENT, ev?.value)
    }

    // ── Dirty: property-only — score 0.90 ────────────────────────────────────

    @Test
    fun `ksu version property set, no files — score is 0_90`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.kernelsu.version" to "11000")),
        )
        assertEquals(0.90, result.score)
    }

    @Test
    fun `ksu property-only — pattern is ksu_property_set`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.kernelsu.version" to "11000")),
        )
        val ev = result.evidence.find { it.key == "ksu.pattern" }
        assertEquals(KernelSURootProbe.PATTERN_KSU_PROPERTY_SET, ev?.value)
    }

    @Test
    fun `ksu property-only — version_property carries the value`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.kernelsu.version" to "11000")),
        )
        val ev = result.evidence.find { it.key == "ksu.version_property" }
        assertEquals("11000", ev?.value)
    }

    @Test
    fun `empty version property string is treated as null`() = runBlocking {
        // An empty-string value indicates "property exists but unset"
        // (a degenerate state); treat as absent per probe semantics.
        val result = probe.run(
            fakeCtx(systemProps = mapOf("ro.kernelsu.version" to "")),
        )
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "ksu.pattern" }
        assertEquals(KernelSURootProbe.PATTERN_CLEAN, ev?.value)
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
        val ev = result.evidence.find { it.key == "ksu.observation_ok" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `fileExists throws but property set — still scores property branch`() = runBlocking {
        // The property branch is independent of fileExists; even when
        // every file check throws, an exposed version property still
        // produces score 0.90.
        val result = probe.run(
            fakeCtx(
                fileAccessorThrows = true,
                systemProps = mapOf("ro.kernelsu.version" to "11000"),
            ),
        )
        assertEquals(0.90, result.score)
    }

    // ── Cross-cutting #1: every evidence key namespaced `ksu.*` ──────────────

    @Test
    fun `every evidence key is ksu-namespaced (cross-cutting #1)`() = runBlocking {
        val result = probe.run(fakeCtx())
        for (ev in result.evidence) {
            assertTrue(
                ev.key.startsWith("ksu."),
                "evidence key '${ev.key}' is not ksu.* namespaced (cross-cutting #1)",
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
                "ksu.files_hit",
                "ksu.version_property",
                "ksu.observation_ok",
                "ksu.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(KernelSURootProbe.METHOD, result.method)
    }

    // ── Probe metadata + cross-cutting #7 fractional rank ────────────────────

    @Test
    fun `probe id is root_kernelsu`() {
        assertEquals("root.kernelsu", probe.id)
    }

    @Test
    fun `code-rank is 94`() {
        assertEquals(94, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_6 (cross-cutting #7)`() {
        assertEquals(3.6, probe.inventoryRank)
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

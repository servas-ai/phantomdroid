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
 * Unit tests for MagiskModuleDirProbe (rank 3.9, code-rank 92,
 * Power-13 Gap #8).
 */
class MagiskModuleDirProbeTest {

    private val probe = MagiskModuleDirProbe()

    private fun fakeCtx(
        entries: List<String>? = null,
        accessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
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
        override fun queryDirEntries(path: String): List<String>? {
            if (accessorThrows) throw RuntimeException("simulated queryDirEntries throw")
            if (path != MagiskModuleDirProbe.MAGISK_MODULES_PATH) return null
            return entries
        }
    }

    // ── Modules present — score 1.0 ──────────────────────────────────────────

    @Test
    fun `modules directory with entries — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(entries = listOf("zygisksu", "safetynet-fix")))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `modules present — pattern is modules_present`() = runBlocking {
        val result = probe.run(fakeCtx(entries = listOf("zygisksu")))
        val ev = result.evidence.find { it.key == "magisk_module_dir.pattern" }
        assertEquals(MagiskModuleDirProbe.PATTERN_MODULES_PRESENT, ev?.value)
    }

    @Test
    fun `modules present — entry_count evidence is accurate`() = runBlocking {
        val result = probe.run(fakeCtx(entries = listOf("a", "b", "c")))
        val ev = result.evidence.find { it.key == "magisk_module_dir.entry_count" }
        assertEquals("3", ev?.value)
    }

    @Test
    fun `modules present — entries_sample evidence shows sorted names`() = runBlocking {
        val result = probe.run(
            fakeCtx(entries = listOf("safetynet-fix", "zygisksu", "MagiskHidePropsConf")),
        )
        val ev = result.evidence.find { it.key == "magisk_module_dir.entries_sample" }
        // Sorted alphabetically (case-sensitive).
        assertEquals("MagiskHidePropsConf,safetynet-fix,zygisksu", ev?.value)
    }

    @Test
    fun `modules present — entries_sample capped at 10 entries`() = runBlocking {
        val many = (1..20).map { "module$it" }
        val result = probe.run(fakeCtx(entries = many))
        val ev = result.evidence.find { it.key == "magisk_module_dir.entries_sample" }
        val sample = ev?.value?.toString()?.split(",") ?: emptyList()
        assertTrue(sample.size <= 10, "sample size ${sample.size} should be capped at 10")
    }

    // ── Empty directory — score 0.95 ─────────────────────────────────────────

    @Test
    fun `modules directory present but empty — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(entries = emptyList()))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `empty directory — pattern is dir_empty`() = runBlocking {
        val result = probe.run(fakeCtx(entries = emptyList()))
        val ev = result.evidence.find { it.key == "magisk_module_dir.pattern" }
        assertEquals(MagiskModuleDirProbe.PATTERN_DIR_EMPTY, ev?.value)
    }

    @Test
    fun `empty directory — entry_count is 0`() = runBlocking {
        val result = probe.run(fakeCtx(entries = emptyList()))
        val ev = result.evidence.find { it.key == "magisk_module_dir.entry_count" }
        assertEquals("0", ev?.value)
    }

    @Test
    fun `empty directory — entries_sample is empty sentinel`() = runBlocking {
        val result = probe.run(fakeCtx(entries = emptyList()))
        val ev = result.evidence.find { it.key == "magisk_module_dir.entries_sample" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `empty directory — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx(entries = emptyList()))
        assertEquals(0.95, result.confidence)
    }

    // ── No observation (null) — score 0.0 ────────────────────────────────────

    @Test
    fun `accessor returns null — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `accessor null — pattern is no_observation`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        val ev = result.evidence.find { it.key == "magisk_module_dir.pattern" }
        assertEquals(MagiskModuleDirProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `accessor null — confidence is 0_50 DEGRADED`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `accessor null — path_observable evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        val ev = result.evidence.find { it.key == "magisk_module_dir.path_observable" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `accessor null — entries_sample evidence is no-observation sentinel`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        val ev = result.evidence.find { it.key == "magisk_module_dir.entries_sample" }
        assertEquals("<no observation>", ev?.value)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `accessor throws — does not crash, no_observation pattern`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "magisk_module_dir.pattern" }
        assertEquals(MagiskModuleDirProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── Evidence schema ──────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "magisk_module_dir.path_observable",
                "magisk_module_dir.entry_count",
                "magisk_module_dir.entries_sample",
                "magisk_module_dir.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx(entries = null))
        assertEquals(
            "Enumerate /data/adb/modules/ directory entries via " +
                "queryDirEntries. Directory existence is dispositive " +
                "of Magisk presence — modules listed at score 1.00, " +
                "empty directory at 0.95, no observation at 0.0 " +
                "(consistent with clean device OR well-hidden Magisk). " +
                "Power-13 Gap #8.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is root_magisk_module_dir`() {
        assertEquals("root.magisk_module_dir", probe.id)
    }

    @Test
    fun `code-rank is 92`() {
        assertEquals(92, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_9`() {
        assertEquals(3.9, probe.inventoryRank)
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
        val result = probe.run(fakeCtx(entries = null))
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

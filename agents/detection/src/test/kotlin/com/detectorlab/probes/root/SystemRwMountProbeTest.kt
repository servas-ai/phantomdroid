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
 * Unit tests for SystemRwMountProbe (rank 14.5, code-rank 88,
 * Power-13 Gap #10).
 */
class SystemRwMountProbeTest {

    private val probe = SystemRwMountProbe()

    /**
     * Build a mountinfo content with /system + / lines at the given
     * rw flags. `null` for either means the line is omitted (used to
     * test Android-10+ system-as-root where /system has no standalone
     * entry).
     */
    private fun mountInfo(
        systemRw: Boolean? = false,
        rootRw: Boolean? = false,
    ): String {
        val lines = mutableListOf<String>()
        if (rootRw != null) {
            val opts = if (rootRw) "rw" else "ro"
            lines.add("1 0 253:0 / / $opts - ext4 /dev/block/dm-0")
        }
        if (systemRw != null) {
            val opts = if (systemRw) "rw" else "ro"
            lines.add("2 1 253:1 / /system $opts - ext4 /dev/block/dm-1")
        }
        // Add a few benign lines so the file has realistic shape
        lines.add("3 1 253:2 / /vendor ro - ext4 /dev/block/dm-2")
        lines.add("4 1 259:1 / /data rw - f2fs /dev/block/userdata")
        return lines.joinToString("\n")
    }

    private fun fakeCtx(
        selfMountInfo: String? = mountInfo(),
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
        override fun queryMountInfo(pid: String): String? {
            if (accessorThrows) throw RuntimeException("simulated mountinfo throw")
            return if (pid == SystemRwMountProbe.PID_SELF) selfMountInfo else null
        }
    }

    // ── /system mounted rw → score 0.85 ──────────────────────────────────────

    @Test
    fun `system mounted rw — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = true)))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `system mounted rw — pattern is system_rw`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = true)))
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_SYSTEM_RW, ev?.value)
    }

    @Test
    fun `system mounted rw — system_rw evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = true)))
        val ev = result.evidence.find { it.key == "system_rw_mount.system_rw" }
        assertEquals("true", ev?.value)
    }

    // ── system-as-root + rw root → score 0.85 ────────────────────────────────

    @Test
    fun `system-as-root + root rw — score is 0_85`() = runBlocking {
        // No /system entry; root has rw. RootBeer's checkForRWPaths
        // catches this transitive case.
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = null, rootRw = true)))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `system-as-root + root rw — pattern is root_rw_sar`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = null, rootRw = true)))
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_ROOT_RW_SAR, ev?.value)
    }

    // ── Production /system ro → score 0.0 ────────────────────────────────────

    @Test
    fun `system mounted ro — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = false)))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `system mounted ro — pattern is system_ro`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = false)))
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_SYSTEM_RO, ev?.value)
    }

    @Test
    fun `system-as-root + root ro — pattern is root_ro_sar, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = null, rootRw = false)))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_ROOT_RO_SAR, ev?.value)
    }

    // ── Cascade — system entry wins over root entry ──────────────────────────

    @Test
    fun `system rw + root rw — system_rw wins (system entry takes precedence)`() = runBlocking {
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemRw = true, rootRw = true)),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_SYSTEM_RW, ev?.value)
    }

    @Test
    fun `system ro + root rw — system_ro wins (cascade prefers system)`() = runBlocking {
        // /system mounted ro overrides /root rw — the system_rw rule
        // fires first, and SYSTEM_RO at 0.0 is the resolved pattern.
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemRw = false, rootRw = true)),
        )
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_SYSTEM_RO, ev?.value)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `mountinfo null — pattern is no_observation, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `mountinfo null — confidence is 0_50 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `mountinfo empty string — pattern is no_system_or_root, confidence 0_60 partial`() = runBlocking {
        // Empty-string is distinct from null at the accessor layer:
        // the production wrapper returned a real string that just
        // happens to be empty (which a hardened-mountinfo proc filter
        // could legitimately produce). Cascade lands in
        // PATTERN_NO_SYSTEM_OR_ROOT (we observed but found no
        // matching mount entry) at confidence PARTIAL. Distinct from
        // null which lands in PATTERN_NO_OBSERVATION at confidence
        // DEGRADED.
        val result = probe.run(fakeCtx(selfMountInfo = ""))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_NO_SYSTEM_OR_ROOT, ev?.value)
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `mountinfo with neither system nor root — pattern is no_system_or_root`() = runBlocking {
        val partial = "4 1 259:1 / /data rw - f2fs /dev/block/userdata"
        val result = probe.run(fakeCtx(selfMountInfo = partial))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_NO_SYSTEM_OR_ROOT, ev?.value)
        assertEquals(0.60, result.confidence)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
        // accessor throw → null result → no_observation
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `malformed lines are skipped without crashing`() = runBlocking {
        // Lines with too few fields should be silently ignored.
        val malformed = """
            garbage
            short line
            1 0 253:0 / / ro - ext4 /dev/block/dm-0
            2 1 253:1 / /system ro - ext4 /dev/block/dm-1
        """.trimIndent()
        val result = probe.run(fakeCtx(selfMountInfo = malformed))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "system_rw_mount.pattern" }
        assertEquals(SystemRwMountProbe.PATTERN_SYSTEM_RO, ev?.value)
    }

    // ── Confidence ───────────────────────────────────────────────────────────

    @Test
    fun `system entry found — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `system-as-root + root entry found — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemRw = null, rootRw = false)))
        assertEquals(0.95, result.confidence)
    }

    // ── findMountStatus helper unit tests ────────────────────────────────────

    @Test
    fun `findMountStatus finds system entry`() {
        val content = "2 1 253:1 / /system ro - ext4 /dev/block/dm-1"
        val status = SystemRwMountProbe.findMountStatus(content, "/system")
        assertEquals(false, status?.rw)
    }

    @Test
    fun `findMountStatus detects rw status`() {
        val content = "2 1 253:1 / /system rw - ext4 /dev/block/dm-1"
        val status = SystemRwMountProbe.findMountStatus(content, "/system")
        assertEquals(true, status?.rw)
    }

    @Test
    fun `findMountStatus returns null when mount-point not found`() {
        val content = "2 1 253:1 / /data rw - f2fs /dev/block/userdata"
        val status = SystemRwMountProbe.findMountStatus(content, "/system")
        assertEquals(null, status)
    }

    @Test
    fun `findMountStatus returns null on null and empty content`() {
        assertEquals(null, SystemRwMountProbe.findMountStatus(null, "/system"))
        assertEquals(null, SystemRwMountProbe.findMountStatus("", "/system"))
    }

    @Test
    fun `findMountStatus skips lines with fewer than 6 fields`() {
        val content = "short line"
        assertEquals(null, SystemRwMountProbe.findMountStatus(content, "/system"))
    }

    // ── Evidence schema ──────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "system_rw_mount.mountinfo_readable",
                "system_rw_mount.system_entry_found",
                "system_rw_mount.system_rw",
                "system_rw_mount.root_entry_found",
                "system_rw_mount.root_rw",
                "system_rw_mount.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Parse /proc/self/mountinfo for /system partition rw status " +
                "(or / partition rw status on Android 10+ system-as-root " +
                "devices) — RootBeer checkForRWPaths() (Power-13 Gap #10).",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is root_system_rw_mount`() {
        assertEquals("root.system_rw_mount", probe.id)
    }

    @Test
    fun `code-rank is 88`() {
        assertEquals(88, probe.rank)
    }

    @Test
    fun `inventoryRank is 14_5`() {
        assertEquals(14.5, probe.inventoryRank)
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
    fun `probe budget is 150ms`() {
        assertEquals(150L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

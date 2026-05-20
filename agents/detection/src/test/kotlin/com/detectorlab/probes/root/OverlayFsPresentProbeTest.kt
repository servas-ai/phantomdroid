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
 * Unit tests for OverlayFsPresentProbe (rank 14.7, code-rank 89,
 * Power-13 Gap #12).
 */
class OverlayFsPresentProbeTest {

    private val probe = OverlayFsPresentProbe()

    /**
     * Build a mountinfo content with /system + / lines at the given
     * fs-types. `null` for either means the line is omitted.
     */
    private fun mountInfo(
        systemFs: String? = "ext4",
        rootFs: String? = "ext4",
    ): String {
        val lines = mutableListOf<String>()
        if (rootFs != null) {
            lines.add("1 0 253:0 / / ro - $rootFs /dev/block/dm-0")
        }
        if (systemFs != null) {
            lines.add("2 1 253:1 / /system ro - $systemFs /dev/block/dm-1")
        }
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
            return if (pid == OverlayFsPresentProbe.PID_SELF) selfMountInfo else null
        }
    }

    // ── Overlay on /system — score 0.85 ──────────────────────────────────────

    @Test
    fun `overlay on system — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "overlay")))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `overlay on system — pattern is overlay_on_system`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "overlay")))
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_OVERLAY_ON_SYSTEM, ev?.value)
    }

    @Test
    fun `overlay on system — overlay_on_system evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "overlay")))
        val ev = result.evidence.find { it.key == "overlayfs.overlay_on_system" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `overlay on system — system_fs_type evidence shows overlay`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "overlay")))
        val ev = result.evidence.find { it.key == "overlayfs.system_fs_type" }
        assertEquals("overlay", ev?.value)
    }

    // ── Overlay on / (system-as-root) — score 0.85 ───────────────────────────

    @Test
    fun `overlay on root + no system — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemFs = null, rootFs = "overlay")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `overlay on root SaR — pattern is overlay_on_root_sar`() = runBlocking {
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemFs = null, rootFs = "overlay")),
        )
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_OVERLAY_ON_ROOT_SAR, ev?.value)
    }

    // ── Cascade — /system wins over / ────────────────────────────────────────

    @Test
    fun `overlay on both system and root — system wins`() = runBlocking {
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemFs = "overlay", rootFs = "overlay")),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_OVERLAY_ON_SYSTEM, ev?.value)
    }

    // ── Clean — ext4 on /system → score 0 ────────────────────────────────────

    @Test
    fun `ext4 on system — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ext4 on system — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `f2fs on system — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "f2fs")))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `squashfs on system — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = mountInfo(systemFs = "squashfs")))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ext4 root and no system — clean`() = runBlocking {
        // SaR clean state: / mounted as ext4, no /system entry.
        val result = probe.run(
            fakeCtx(selfMountInfo = mountInfo(systemFs = null, rootFs = "ext4")),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mountinfo with no system or root entry — pattern is clean`() = runBlocking {
        // Only /data and /vendor entries — neither /system nor /
        // present. Score 0.0 (cannot fire); confidence NORMAL since
        // mountinfo WAS readable.
        val partial = """
            3 1 253:2 / /vendor ro - ext4 /dev/block/dm-2
            4 1 259:1 / /data rw - f2fs /dev/block/userdata
        """.trimIndent()
        val result = probe.run(fakeCtx(selfMountInfo = partial))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_CLEAN, ev?.value)
        assertEquals(0.95, result.confidence)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `mountinfo null — pattern is no_observation, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `mountinfo null — confidence is 0_50 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null))
        assertEquals(0.50, result.confidence)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `malformed lines are skipped`() = runBlocking {
        val malformed = """
            garbage
            short
            1 0 253:0 / / ro - ext4 /dev/block/dm-0
            2 1 253:1 / /system ro - overlay /dev/block/dm-1
        """.trimIndent()
        val result = probe.run(fakeCtx(selfMountInfo = malformed))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `line missing dash separator is skipped`() = runBlocking {
        // Malformed mountinfo with no `-` separator — fs-type can't
        // be parsed, the line is skipped. The /system line below
        // still works because its `-` is present.
        val malformed = """
            xxx 0 253:0 / / ro overlay no_dash_here
            2 1 253:1 / /system ro - ext4 /dev/block/dm-1
        """.trimIndent()
        val result = probe.run(fakeCtx(selfMountInfo = malformed))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "overlayfs.pattern" }
        assertEquals(OverlayFsPresentProbe.PATTERN_CLEAN, ev?.value)
    }

    // ── findFsType helper unit tests ─────────────────────────────────────────

    @Test
    fun `findFsType extracts overlay`() {
        val content = "2 1 253:1 / /system ro - overlay overlay rw,lowerdir=/lower"
        assertEquals("overlay", OverlayFsPresentProbe.findFsType(content, "/system"))
    }

    @Test
    fun `findFsType extracts ext4`() {
        val content = "2 1 253:1 / /system ro - ext4 /dev/block/dm-1"
        assertEquals("ext4", OverlayFsPresentProbe.findFsType(content, "/system"))
    }

    @Test
    fun `findFsType returns null for unknown mount-point`() {
        val content = "2 1 253:1 / /data rw - f2fs /dev/block/userdata"
        assertEquals(null, OverlayFsPresentProbe.findFsType(content, "/system"))
    }

    @Test
    fun `findFsType returns null for null and empty content`() {
        assertEquals(null, OverlayFsPresentProbe.findFsType(null, "/system"))
        assertEquals(null, OverlayFsPresentProbe.findFsType("", "/system"))
    }

    @Test
    fun `findFsType returns null when dash separator missing`() {
        // No `-` field — parser can't locate fs-type.
        val content = "2 1 253:1 / /system ro overlay /dev/block/dm-1"
        assertEquals(null, OverlayFsPresentProbe.findFsType(content, "/system"))
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
                "overlayfs.mountinfo_readable",
                "overlayfs.system_fs_type",
                "overlayfs.root_fs_type",
                "overlayfs.overlay_on_system",
                "overlayfs.overlay_on_root",
                "overlayfs.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Parse /proc/self/mountinfo for `overlay` filesystem type " +
                "mounted at /system or at / (system-as-root). Magisk-on-" +
                "Android 11+ tell — Momo target surface (Power-13 Gap #12).",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is root_overlayfs_present`() {
        assertEquals("root.overlayfs_present", probe.id)
    }

    @Test
    fun `code-rank is 89`() {
        assertEquals(89, probe.rank)
    }

    @Test
    fun `inventoryRank is 14_7`() {
        assertEquals(14.7, probe.inventoryRank)
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

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
 * Unit tests for MountNsMismatchProbe (rank 3.8, code-rank 87,
 * Power-13 Gap #3).
 */
class MountNsMismatchProbeTest {

    private val probe = MountNsMismatchProbe()

    // Realistic clean Pixel-7-shaped mountinfo content with optional
    // injected magisk-fingerprint markers.
    private fun cleanMountInfo(extra: String = ""): String {
        val base = """
            1 0 253:0 / / ro - ext4 /dev/block/dm-0
            2 1 253:1 / /system ro - ext4 /dev/block/dm-1
            3 1 253:2 / /vendor ro - ext4 /dev/block/dm-2
            4 1 259:1 / /data rw - f2fs /dev/block/by-name/userdata
            5 1 0:5 / /sys ro - sysfs sysfs
            6 1 0:6 / /proc ro - proc proc
            7 1 0:7 / /dev rw - tmpfs tmpfs
            8 4 0:8 / /storage/emulated rw - fuse fuse
        """.trimIndent()
        return if (extra.isEmpty()) base else "$base\n$extra"
    }

    private fun fakeCtx(
        selfMountInfo: String? = cleanMountInfo(),
        initMountInfo: String? = cleanMountInfo(),
        selfThrows: Boolean = false,
        initThrows: Boolean = false,
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
        override fun queryMountInfo(pid: String): String? = when (pid) {
            MountNsMismatchProbe.PID_SELF -> {
                if (selfThrows) throw RuntimeException("simulated self mountinfo throw")
                selfMountInfo
            }
            MountNsMismatchProbe.PID_INIT -> {
                if (initThrows) throw RuntimeException("simulated init mountinfo throw")
                initMountInfo
            }
            else -> null
        }
    }

    // ── Canonical Momo signal — magisk in init only ──────────────────────────

    @Test
    fun `magisk in init only — score is 0_95`() = runBlocking {
        val initWithMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(initMountInfo = initWithMagisk))
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `magisk in init only — pattern is magisk_in_init_only`() = runBlocking {
        val initWithMagisk = cleanMountInfo("9 1 0:9 / /data/adb/modules rw - ext4 /dev/block/vda")
        val result = probe.run(fakeCtx(initMountInfo = initWithMagisk))
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_MAGISK_IN_INIT_ONLY, ev?.value)
    }

    @Test
    fun `magisk in init only — magisk_in_init evidence lists substring`() = runBlocking {
        val initWithMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(initMountInfo = initWithMagisk))
        val ev = result.evidence.find { it.key == "mount_ns.magisk_in_init" }
        assertTrue(
            (ev?.value as? String)?.contains("/sbin/.magisk") == true,
            "expected /sbin/.magisk substring, got ${ev?.value}",
        )
    }

    // ── Magisk in self only ──────────────────────────────────────────────────

    @Test
    fun `magisk in self only — score is 0_85`() = runBlocking {
        val selfWithMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(selfMountInfo = selfWithMagisk))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `magisk in self only — pattern is magisk_in_self_only`() = runBlocking {
        val selfWithMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(selfMountInfo = selfWithMagisk))
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_MAGISK_IN_SELF_ONLY, ev?.value)
    }

    // ── Magisk in both ───────────────────────────────────────────────────────

    @Test
    fun `magisk in both — score is 0_70`() = runBlocking {
        val withMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(selfMountInfo = withMagisk, initMountInfo = withMagisk))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `magisk in both — pattern is magisk_in_both`() = runBlocking {
        val withMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(selfMountInfo = withMagisk, initMountInfo = withMagisk))
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_MAGISK_IN_BOTH, ev?.value)
    }

    // ── Magisk-fingerprint substrings ────────────────────────────────────────

    @Test
    fun `data adb fingerprint in init only fires the rule`() = runBlocking {
        val initWithAdb = cleanMountInfo("9 1 0:9 / /data/adb rw - ext4 /dev/block/vda")
        val result = probe.run(fakeCtx(initMountInfo = initWithAdb))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `magisk_tmp fingerprint in init only fires the rule`() = runBlocking {
        val initWithTmp = cleanMountInfo("9 1 0:9 / /tmp rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(initMountInfo = initWithTmp))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `sbin magisk bind fingerprint in init only fires the rule`() = runBlocking {
        val initWithBind = cleanMountInfo("9 1 0:9 / /sbin/magisk rw - bind /sbin/.magisk/busybox")
        val result = probe.run(fakeCtx(initMountInfo = initWithBind))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `findMagiskFingerprints returns all matching substrings`() {
        val content = "line1 /sbin/.magisk line2 /data/adb line3 magisk_tmp line4"
        val hits = MountNsMismatchProbe.findMagiskFingerprints(content)
        assertTrue("/sbin/.magisk" in hits)
        assertTrue("/data/adb" in hits)
        assertTrue("magisk_tmp" in hits)
    }

    @Test
    fun `findMagiskFingerprints returns empty on null and empty`() {
        assertEquals(emptyList(), MountNsMismatchProbe.findMagiskFingerprints(null))
        assertEquals(emptyList(), MountNsMismatchProbe.findMagiskFingerprints(""))
    }

    // ── No-magisk benign mismatch — score 0 ──────────────────────────────────

    @Test
    fun `digests differ but no magisk — score is 0_0`() = runBlocking {
        // The normal Android case: zygote isolation makes self and
        // init differ in legitimate ways (apex mounts, vendor_dlkm).
        val initWithApex = cleanMountInfo(
            "9 2 0:9 / /apex/com.android.runtime ro - bind /apex/com.android.runtime",
        )
        val result = probe.run(fakeCtx(initMountInfo = initWithApex))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `digests differ no magisk — pattern is digest_diff_no_magisk`() = runBlocking {
        val initWithApex = cleanMountInfo("9 2 0:9 / /apex/extra ro - bind /apex/extra")
        val result = probe.run(fakeCtx(initMountInfo = initWithApex))
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_DIGEST_DIFF_NO_MAGISK, ev?.value)
    }

    @Test
    fun `digests match — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_DIGEST_MATCH, ev?.value)
    }

    @Test
    fun `digests match — digests_match evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "mount_ns.digests_match" }
        assertEquals("true", ev?.value)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `both null — pattern is no_observation, score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null, initMountInfo = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `both null — confidence is 0_30 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null, initMountInfo = null))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `only self readable — pattern is no_observation`() = runBlocking {
        // Even with magisk substrings in self, we cannot compare to
        // init — fall through to no_observation. Confidence
        // partial since one side observed.
        val selfWithMagisk = cleanMountInfo("9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp")
        val result = probe.run(fakeCtx(selfMountInfo = selfWithMagisk, initMountInfo = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `only self readable — confidence is 0_60 partial`() = runBlocking {
        val result = probe.run(fakeCtx(initMountInfo = null))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only init readable — confidence is 0_60 partial`() = runBlocking {
        val result = probe.run(fakeCtx(selfMountInfo = null))
        assertEquals(0.60, result.confidence)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `self accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(selfThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `init accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(initThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `both accessors throw — does not crash, no_observation pattern`() = runBlocking {
        val result = probe.run(fakeCtx(selfThrows = true, initThrows = true))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "mount_ns.pattern" }
        assertEquals(MountNsMismatchProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── Confidence ───────────────────────────────────────────────────────────

    @Test
    fun `both observed — confidence is 0_95 FULL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
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
                "mount_ns.self_readable",
                "mount_ns.init_readable",
                "mount_ns.digests_match",
                "mount_ns.magisk_in_self",
                "mount_ns.magisk_in_init",
                "mount_ns.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `clean device — magisk_in_self and magisk_in_init both none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val selfEv = result.evidence.find { it.key == "mount_ns.magisk_in_self" }
        val initEv = result.evidence.find { it.key == "mount_ns.magisk_in_init" }
        assertEquals("none", selfEv?.value)
        assertEquals("none", initEv?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Compare /proc/self/mountinfo vs /proc/1/mountinfo for " +
                "Magisk-fingerprint substring asymmetry (HuskyDG #1 " +
                "Momo signal — Power-13 Gap #3).",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is root_mount_ns_mismatch`() {
        assertEquals("root.mount_ns_mismatch", probe.id)
    }

    @Test
    fun `code-rank is 87 (unique Int slot for fractional inventory 3_8)`() {
        assertEquals(87, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_8`() {
        assertEquals(3.8, probe.inventoryRank)
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
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

package com.example.detectorlab.probes.app

import com.example.detectorlab.core.KeyguardManagerView
import com.example.detectorlab.core.PackageManagerView
import com.example.detectorlab.core.ProbeCategory
import com.example.detectorlab.core.ProbeContext
import com.example.detectorlab.core.SensorManagerView
import com.example.detectorlab.core.SensorSample
import com.example.detectorlab.core.TelephonyField
import com.example.detectorlab.core.UnknownKeyguardManagerView
import com.example.detectorlab.core.UnknownWifiManagerView
import com.example.detectorlab.core.WifiManagerView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TikTokArgusSigningProbe (CLO-19).
 *
 * Covers all four scoring branches, the skipped-when-not-installed path,
 * evidence completeness, and probe metadata invariants.
 */
class TikTokArgusSigningProbeTest {

    private val probe = TikTokArgusSigningProbe()

    private fun fakeCtx(
        installedPackages: Set<String> = emptySet(),
        existingFiles: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean = path in existingFiles
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = packageName in installedPackages
            override fun listInstalledPackages() = installedPackages.toList()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView
        override fun queryWifiManager(): WifiManagerView = UnknownWifiManagerView
    }

    // ── Skipped when TikTok not installed ────────────────────────────────────

    @Test
    fun `skipped when TikTok not installed`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.skipped, "expected skipped, got failed=${result.failed} reason=${result.failureReason}")
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `skipped message references both checked package names`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("com.zhiliaoapp.musically"))
        assertTrue(result.failureReason!!.contains("com.ss.android.ugc.tiktok"))
    }

    // ── Score branches ───────────────────────────────────────────────────────

    @Test
    fun `score 0_85 when both libsscronet and libmetasec_ov present`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf(
                "/data/app/$pkg-1/lib/arm64/libsscronet.so",
                "/data/app/$pkg-1/lib/arm64/libmetasec_ov.so",
            ),
        ))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
        assertEquals(0.90, result.confidence, 0.001)
    }

    @Test
    fun `score 0_55 when only libsscronet present`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf("/data/app/$pkg-1/lib/arm64/libsscronet.so"),
        ))
        assertEquals(0.55, result.score, 0.001)
        assertEquals(0.90, result.confidence, 0.001)
    }

    @Test
    fun `score 0_55 when only libmetasec_ov present`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf("/data/app/$pkg-1/lib/arm64/libmetasec_ov.so"),
        ))
        assertEquals(0.55, result.score, 0.001)
    }

    @Test
    fun `score 0_10 when TikTok installed but native libs not visible`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
        ))
        assertFalse(result.failed)
        assertEquals(0.10, result.score, 0.001)
        assertEquals(0.40, result.confidence, 0.001)
    }

    // ── CN package variant ───────────────────────────────────────────────────

    @Test
    fun `detects CN Douyin variant package`() = runBlocking {
        val pkg = "com.ss.android.ugc.tiktok"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf(
                "/data/app/$pkg-1/lib/arm64/libsscronet.so",
                "/data/app/$pkg-1/lib/arm64/libmetasec_ov.so",
            ),
        ))
        assertEquals(0.85, result.score, 0.001)
        assertEquals(pkg, result.evidence.first { it.key == "tiktok.package" }.value)
    }

    // ── lib slot -2 fallback ────────────────────────────────────────────────

    @Test
    fun `detects libs in slot-2 install path`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf(
                "/data/app/$pkg-2/lib/arm64/libsscronet.so",
                "/data/app/$pkg-2/lib/arm64/libmetasec_ov.so",
            ),
        ))
        assertEquals(0.85, result.score, 0.001)
    }

    // ── Evidence completeness ────────────────────────────────────────────────

    @Test
    fun `evidence includes all required keys when libs present`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf(
                "/data/app/$pkg-1/lib/arm64/libsscronet.so",
                "/data/app/$pkg-1/lib/arm64/libmetasec_ov.so",
            ),
        ))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("tiktok.package" in keys)
        assertTrue("tiktok.libsscronet_present" in keys)
        assertTrue("tiktok.libmetasec_ov_present" in keys)
        assertTrue("tiktok.argus_offset_v5_4_1" in keys)
        assertTrue("tiktok.offset_fragility" in keys)
        assertTrue("tiktok.versions_table" in keys)
    }

    @Test
    fun `evidence reports validated offset for v5_4_1`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(installedPackages = setOf(pkg)))
        val offsetEvidence = result.evidence.first { it.key == "tiktok.argus_offset_v5_4_1" }
        assertEquals("0x88ee0", offsetEvidence.value)
    }

    @Test
    fun `evidence references versions asset filename`() = runBlocking {
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(installedPackages = setOf(pkg)))
        val tableEvidence = result.evidence.first { it.key == "tiktok.versions_table" }
        assertEquals("tiktok_argus_versions.json", tableEvidence.value)
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id matches spec`() {
        assertEquals("app.tiktok_argus_signing", probe.id)
    }

    @Test
    fun `probe category is RUNTIME`() {
        assertEquals(ProbeCategory.RUNTIME, probe.category)
    }

    @Test
    fun `probe budget is within hard ceiling`() {
        assertTrue(probe.budgetMs <= 5000L)
    }

    @Test
    fun `probe rank is set`() {
        assertEquals(TikTokArgusSigningProbe.RANK, probe.rank)
    }
}

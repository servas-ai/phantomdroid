package com.detectorlab.probes.buildprop

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for FingerprintCrossPartitionProbe (rank 9.5,
 * code-rank 93, Power-13 Gap #9).
 */
class FingerprintCrossPartitionProbeTest {

    private val probe = FingerprintCrossPartitionProbe()

    private val cleanPixel = "google/panther/panther:13/TQ1A.230205.002/9471150:user/release-keys"
    private val cleanPixelVendor = "google/panther/panther:13/TQ1A.230205.002.A4/9531234:user/release-keys"
    private val redroidNative = "redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A/eng:userdebug/test-keys"

    private fun fakeCtx(
        systemFp: String? = cleanPixel,
        vendorFp: String? = cleanPixelVendor,
        systemThrows: Boolean = false,
        vendorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = when (key) {
            FingerprintCrossPartitionProbe.PROP_SYSTEM_FINGERPRINT -> {
                if (systemThrows) throw RuntimeException("simulated system FP throw")
                systemFp
            }
            FingerprintCrossPartitionProbe.PROP_VENDOR_FINGERPRINT -> {
                if (vendorThrows) throw RuntimeException("simulated vendor FP throw")
                vendorFp
            }
            else -> null
        }
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
    }

    // ── Clean — matching prefixes ────────────────────────────────────────────

    @Test
    fun `clean Pixel system + vendor share prefix — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is partitions_consistent`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_PARTITIONS_CONSISTENT, ev?.value)
    }

    @Test
    fun `clean — prefixes_match evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.prefixes_match" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── Divergent — MHPC tell ────────────────────────────────────────────────

    @Test
    fun `system Pixel + vendor Redroid — divergent, score 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `divergent — pattern is divergent_fingerprints`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_DIVERGENT_FINGERPRINTS, ev?.value)
    }

    @Test
    fun `divergent — prefixes_match evidence is false`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.prefixes_match" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `divergent — system_prefix evidence shows Pixel`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.system_prefix" }
        assertEquals("google/panther/panther", ev?.value)
    }

    @Test
    fun `divergent — vendor_prefix evidence shows Redroid`() = runBlocking {
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.vendor_prefix" }
        assertEquals("redroid/redroid_x86_64_only/redroid_x86_64_only", ev?.value)
    }

    // ── Vendor absent ────────────────────────────────────────────────────────

    @Test
    fun `system present + vendor null — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(vendorFp = null))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `vendor null — pattern is vendor_absent`() = runBlocking {
        val result = probe.run(fakeCtx(vendorFp = null))
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_VENDOR_ABSENT, ev?.value)
    }

    @Test
    fun `vendor empty string — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(vendorFp = ""))
        assertEquals(0.85, result.score)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `system null — pattern is no_observation, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(systemFp = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `system null — confidence is 0_50 DEGRADED`() = runBlocking {
        val result = probe.run(fakeCtx(systemFp = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `both null — score 0_0, no_observation`() = runBlocking {
        val result = probe.run(fakeCtx(systemFp = null, vendorFp = null))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `system accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(systemThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `vendor accessor throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(vendorThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `both accessors throw — does not crash, no_observation`() = runBlocking {
        val result = probe.run(fakeCtx(systemThrows = true, vendorThrows = true))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "fingerprint_cross_partition.pattern" }
        assertEquals(FingerprintCrossPartitionProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `divergent wins over vendor-absent (both present, divergent fires first)`() = runBlocking {
        // Both present and divergent — divergent fires, NOT vendor_absent.
        val result = probe.run(
            fakeCtx(systemFp = cleanPixel, vendorFp = redroidNative),
        )
        assertEquals(1.0, result.score)
    }

    // ── extractPrefix helper ─────────────────────────────────────────────────

    @Test
    fun `extractPrefix returns first 3 segments without version suffix`() {
        assertEquals(
            "google/panther/panther",
            FingerprintCrossPartitionProbe.extractPrefix(cleanPixel),
        )
    }

    @Test
    fun `extractPrefix strips colon-suffix from third segment`() {
        // panther:13 → panther (only the manufacturer-pipeline
        // portion is preserved).
        assertEquals(
            "google/panther/panther",
            FingerprintCrossPartitionProbe.extractPrefix(
                "google/panther/panther:13/TQ1A.../...",
            ),
        )
    }

    @Test
    fun `extractPrefix returns null on null or empty`() {
        assertNull(FingerprintCrossPartitionProbe.extractPrefix(null))
        assertNull(FingerprintCrossPartitionProbe.extractPrefix(""))
    }

    @Test
    fun `extractPrefix returns null on fingerprint with fewer than 3 segments`() {
        assertNull(FingerprintCrossPartitionProbe.extractPrefix("google/panther"))
    }

    @Test
    fun `extractPrefix handles Samsung fingerprint shape`() {
        val samsung = "samsung/r0qxxx/r0q:14/UP1A.../..."
        assertEquals(
            "samsung/r0qxxx/r0q",
            FingerprintCrossPartitionProbe.extractPrefix(samsung),
        )
    }

    // ── prefixesMatch helper ─────────────────────────────────────────────────

    @Test
    fun `prefixesMatch returns true for matching prefixes`() {
        // Build-id suffix differs but prefix matches.
        val system = "google/panther/panther:13/TQ1A.../9471150"
        val vendor = "google/panther/panther:13/TQ1A.A4/9531234"
        assertEquals(true, FingerprintCrossPartitionProbe.prefixesMatch(system, vendor))
    }

    @Test
    fun `prefixesMatch returns false for divergent prefixes`() {
        assertEquals(false, FingerprintCrossPartitionProbe.prefixesMatch(cleanPixel, redroidNative))
    }

    @Test
    fun `prefixesMatch returns null when either prefix is null`() {
        assertNull(FingerprintCrossPartitionProbe.prefixesMatch(null, cleanPixel))
        assertNull(FingerprintCrossPartitionProbe.prefixesMatch(cleanPixel, null))
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
                "ro.build.fingerprint",
                "ro.vendor.build.fingerprint",
                "fingerprint_cross_partition.system_prefix",
                "fingerprint_cross_partition.vendor_prefix",
                "fingerprint_cross_partition.prefixes_match",
                "fingerprint_cross_partition.pattern",
            ),
            keys,
        )
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Compare ro.build.fingerprint (system partition) vs " +
                "ro.vendor.build.fingerprint (vendor partition) for " +
                "MagiskHidePropsConfig divergence — system spoof " +
                "without vendor erasure is dispositive. Power-13 Gap #9.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is buildprop_fingerprint_cross_partition`() {
        assertEquals("buildprop.fingerprint_cross_partition", probe.id)
    }

    @Test
    fun `code-rank is 93`() {
        assertEquals(93, probe.rank)
    }

    @Test
    fun `inventoryRank is 9_5`() {
        assertEquals(9.5, probe.inventoryRank)
    }

    @Test
    fun `probe category is BUILDPROP`() {
        assertEquals(ProbeCategory.BUILDPROP, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
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

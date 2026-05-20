package com.detectorlab.probes.app

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.core.UnknownWifiManagerView
import com.detectorlab.core.WifiManagerView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for TikTokArgusSigningProbe (CLO-19 + cross-cutting #8 fix).
 *
 * Covers all four scoring branches, the skipped-when-not-installed path,
 * evidence completeness, probe metadata invariants, AND the new A10+
 * accessor-gap branch added per cross-cutting follow-up #8.
 */
class TikTokArgusSigningProbeTest {

    private val probe = TikTokArgusSigningProbe()

    private fun fakeCtx(
        installedPackages: Set<String> = emptySet(),
        existingFiles: Set<String> = emptySet(),
        sdkInt: String? = null,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            return if (key == TikTokArgusSigningProbe.PROP_RO_BUILD_VERSION_SDK) sdkInt else null
        }
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

    // ── Pre-A10 score branches ───────────────────────────────────────────────

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
    fun `score 0_10 when TikTok installed but native libs not visible and SDK unknown`() = runBlocking {
        // SDK unknown → conservative fallback to pre-A10 0.10 behavior.
        // Existing test from before the cross-cutting #8 fix.
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            // sdkInt = null (default) — preserves legacy behavior
        ))
        assertFalse(result.failed)
        assertEquals(0.10, result.score, 0.001)
        assertEquals(0.40, result.confidence, 0.001)
    }

    @Test
    fun `score 0_10 when SDK is legacy (API 28, Android 9) and libs absent`() = runBlocking {
        // SDK known < 29 → pre-A10 path scan is authoritative; libs
        // genuinely not visible scores 0.10.
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "28",
        ))
        assertEquals(0.10, result.score, 0.001)
        assertEquals(0.40, result.confidence, 0.001)
    }

    // ── A10+ accessor-gap branch (cross-cutting #8 fix) ──────────────────────

    @Test
    fun `score 0_0 with confidence 0_5 when API 29 and libs absent (A10+ accessor gap)`() = runBlocking {
        // The cross-cutting #8 fix: API 29+ devices' /data/app/<pkg>-N/lib
        // paths CANNOT exist (layout moved to ~~<base64>==/<pkg>-token).
        // Probe must not score 0.10 silently; it must degrade to
        // explicit accessor-gap pattern at confidence 0.5.
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "29",
        ))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.5, result.confidence, 0.001)
    }

    @Test
    fun `score 0_0 with confidence 0_5 when API 33 (Android 13) and libs absent`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "33",
        ))
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.5, result.confidence, 0.001)
    }

    @Test
    fun `score 0_0 with confidence 0_5 when API 34 (Android 14) and libs absent`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "34",
        ))
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.5, result.confidence, 0.001)
    }

    @Test
    fun `A10 plus accessor gap — pattern is a10_plus_accessor_gap`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "33",
        ))
        val patternEv = result.evidence.first { it.key == "tiktok.pattern" }
        assertEquals(TikTokArgusSigningProbe.PATTERN_A10_PLUS_ACCESSOR_GAP, patternEv.value)
    }

    @Test
    fun `A10 plus accessor gap — a10_plus_accessor_gap evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "33",
        ))
        val gapEv = result.evidence.first { it.key == "tiktok.a10_plus_accessor_gap" }
        assertEquals("true", gapEv.value)
    }

    @Test
    fun `A10 plus accessor gap — strategy evidence is pre_a10_only_a10_plus_gap`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "33",
        ))
        val strategyEv = result.evidence.first { it.key == "tiktok.path_scan_strategy" }
        assertEquals("pre_a10_only_a10_plus_gap", strategyEv.value)
    }

    @Test
    fun `API 29 boundary — gap fires (29-or-above inclusive)`() = runBlocking {
        // Threshold is `>= 29` (inclusive). API 29 exactly fires the
        // A10+ branch.
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "29",
        ))
        val patternEv = result.evidence.first { it.key == "tiktok.pattern" }
        assertEquals(TikTokArgusSigningProbe.PATTERN_A10_PLUS_ACCESSOR_GAP, patternEv.value)
    }

    @Test
    fun `API 28 boundary — legacy branch (not gap)`() = runBlocking {
        // API 28 (Android 9) is pre-A10; pre-A10 scan is authoritative.
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "28",
        ))
        val patternEv = result.evidence.first { it.key == "tiktok.pattern" }
        assertEquals(TikTokArgusSigningProbe.PATTERN_PRE_A10_LIBS_NOT_VISIBLE, patternEv.value)
    }

    // ── Edge case: A10+ device WITH libs present (rare backport) ─────────────

    @Test
    fun `A10 plus with pre-A10 libs present (rare backport) — pre-A10 score fires`() = runBlocking {
        // Edge case: A10+ device but pre-A10 paths happen to be
        // populated (unusual; could be a custom ROM that preserved
        // the legacy layout). The cascade prefers libs-found over
        // the A10+ gap branch — score 0.85 fires.
        val pkg = "com.zhiliaoapp.musically"
        val result = probe.run(fakeCtx(
            installedPackages = setOf(pkg),
            existingFiles = setOf(
                "/data/app/$pkg-1/lib/arm64/libsscronet.so",
                "/data/app/$pkg-1/lib/arm64/libmetasec_ov.so",
            ),
            sdkInt = "33",
        ))
        assertEquals(0.85, result.score, 0.001)
        assertEquals(0.90, result.confidence, 0.001)
    }

    // ── Malformed SDK property handling ──────────────────────────────────────

    @Test
    fun `malformed SDK property falls back to legacy 0_10 (conservative)`() = runBlocking {
        // Non-numeric SDK string → parseSdkInt returns null → SDK
        // unknown → fallback to pre-A10 0.10 behavior (does NOT fire
        // the A10+ gap rule).
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "not-a-number",
        ))
        assertEquals(0.10, result.score, 0.001)
        assertEquals(0.40, result.confidence, 0.001)
    }

    @Test
    fun `empty SDK property falls back to legacy 0_10`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "",
        ))
        assertEquals(0.10, result.score, 0.001)
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
        // New keys added by cross-cutting #8 fix:
        assertTrue("tiktok.android_sdk" in keys)
        assertTrue("tiktok.path_scan_strategy" in keys)
        assertTrue("tiktok.a10_plus_accessor_gap" in keys)
        assertTrue("tiktok.pattern" in keys)
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

    @Test
    fun `evidence android_sdk reflects supplier when readable`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "33",
        ))
        val sdkEv = result.evidence.first { it.key == "tiktok.android_sdk" }
        assertEquals("33", sdkEv.value)
    }

    @Test
    fun `evidence android_sdk unavailable when supplier returns null`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = null,
        ))
        val sdkEv = result.evidence.first { it.key == "tiktok.android_sdk" }
        assertEquals("<unavailable>", sdkEv.value)
    }

    @Test
    fun `evidence path_scan_strategy reflects legacy when SDK known and lt 29`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "28",
        ))
        val strategyEv = result.evidence.first { it.key == "tiktok.path_scan_strategy" }
        assertEquals("pre_a10_only_sdk_legacy", strategyEv.value)
    }

    @Test
    fun `evidence path_scan_strategy reflects unknown when SDK null`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = null,
        ))
        val strategyEv = result.evidence.first { it.key == "tiktok.path_scan_strategy" }
        assertEquals("pre_a10_only_sdk_unknown", strategyEv.value)
    }

    @Test
    fun `legacy 0_10 branch — a10_plus_accessor_gap evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(
            installedPackages = setOf("com.zhiliaoapp.musically"),
            sdkInt = "28",
        ))
        val gapEv = result.evidence.first { it.key == "tiktok.a10_plus_accessor_gap" }
        assertEquals("false", gapEv.value)
    }

    // ── parseSdkInt helper ───────────────────────────────────────────────────

    @Test
    fun `parseSdkInt parses valid integer strings`() {
        assertEquals(28, TikTokArgusSigningProbe.parseSdkInt("28"))
        assertEquals(29, TikTokArgusSigningProbe.parseSdkInt("29"))
        assertEquals(34, TikTokArgusSigningProbe.parseSdkInt("34"))
    }

    @Test
    fun `parseSdkInt returns null for null and empty`() {
        assertNull(TikTokArgusSigningProbe.parseSdkInt(null))
        assertNull(TikTokArgusSigningProbe.parseSdkInt(""))
    }

    @Test
    fun `parseSdkInt returns null for malformed`() {
        assertNull(TikTokArgusSigningProbe.parseSdkInt("not-a-number"))
        assertNull(TikTokArgusSigningProbe.parseSdkInt("29.5"))
    }

    @Test
    fun `A10_SDK_THRESHOLD constant is 29 (Android 10)`() {
        assertEquals(29, TikTokArgusSigningProbe.A10_SDK_THRESHOLD)
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

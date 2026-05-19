package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for MediaDrmProbe (identity.mediadrm, inventory rank 29).
 */
class MediaDrmProbeTest {

    /** A high-entropy 32-byte device unique ID. */
    private val realisticUniqueId: ByteArray = Random(seed = 0x42).nextBytes(32)

    private val zeroId: ByteArray = ByteArray(32)
    private val ffId: ByteArray = ByteArray(32) { 0xFF.toByte() }
    private val lowEntropyId: ByteArray = ByteArray(32) { (it % 2).toByte() }   // 2 unique bytes

    private fun makeProbe(
        widevineSupported: Boolean? = true,
        securityLevel: String? = "L1",
        vendor: String? = "Google",
        version: String? = "17.0.0",
        uniqueId: ByteArray? = realisticUniqueId,
    ): MediaDrmProbe = MediaDrmProbe(
        widevineSupportedSupplier = { widevineSupported },
        securityLevelSupplier = { securityLevel },
        vendorSupplier = { vendor },
        versionSupplier = { version },
        deviceUniqueIdSupplier = { uniqueId },
    )

    private fun ctxWithModel(model: String? = "Pixel 7"): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == MediaDrmProbe.PROP_RO_PRODUCT_MODEL) model else null
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

    // ── Clean real device (L1 + Qualcomm + high entropy) ─────────────────────

    @Test
    fun `Pixel 7 with L1 Google high-entropy ID — score is 0`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean L1 — pattern is clean_l1`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_CLEAN_L1, ev?.value)
    }

    @Test
    fun `clean L1 — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean L1 — device_unique_id_hex is SHA-256 64-char hex`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.device_unique_id_hex" }
        val hex = ev?.value as String
        assertEquals(64, hex.length, "SHA-256 hex is 64 chars; got ${hex.length}")
        assertTrue(hex.all { it.isDigit() || it in 'a'..'f' }, "expected lowercase hex; got $hex")
    }

    @Test
    fun `clean L1 — raw 32-byte ID is NOT in evidence (PII redaction)`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        // Verify no evidence row contains the raw bytes as hex. The
        // realisticUniqueId hex would be 64 chars starting with the seeded
        // random bytes; the SHA-256 has a completely different prefix.
        val rawHex = realisticUniqueId.joinToString("") { "%02x".format(it) }
        val evidenceValues = result.evidence.map { it.value.toString() }
        assertFalse(
            evidenceValues.any { rawHex in it },
            "raw 32-byte hex leaked into evidence — PII redaction failed",
        )
    }

    // ── Widevine not supported (score 1.0) ───────────────────────────────────

    @Test
    fun `Widevine unsupported — score is 1_0`() = runBlocking {
        val result = makeProbe(widevineSupported = false).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Widevine unsupported — pattern is no_widevine`() = runBlocking {
        val result = makeProbe(widevineSupported = false).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_NO_WIDEVINE, ev?.value)
    }

    // ── Device unique ID all-zero / all-FF (score 1.0) ───────────────────────

    @Test
    fun `unique ID all-zero — score is 1_0`() = runBlocking {
        val result = makeProbe(uniqueId = zeroId).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `unique ID all-FF — score is 1_0`() = runBlocking {
        val result = makeProbe(uniqueId = ffId).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero ID — pattern is zero_or_ff_id`() = runBlocking {
        val result = makeProbe(uniqueId = zeroId).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_ZERO_OR_FF_ID, ev?.value)
    }

    // ── Mock vendor (score 0.95) ─────────────────────────────────────────────

    @Test
    fun `Mock vendor — score is 0_95`() = runBlocking {
        val result = makeProbe(vendor = "Mock").run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Generic vendor — score is 0_95`() = runBlocking {
        val result = makeProbe(vendor = "Generic").run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `mock vendor lowercase — score is 0_95 (case-insensitive)`() = runBlocking {
        val result = makeProbe(vendor = "mock").run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    // ── L3 on phone-class model (score 0.95) ─────────────────────────────────

    @Test
    fun `L3 on Pixel 7 — score is 0_95`() = runBlocking {
        val result = makeProbe(securityLevel = "L3").run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `L3 on Galaxy S22 — score is 0_95`() = runBlocking {
        val result = makeProbe(securityLevel = "L3").run(ctxWithModel("SM-S901B"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `L3 on phone — pattern is l3_on_phone`() = runBlocking {
        val result = makeProbe(securityLevel = "L3").run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_L3_ON_PHONE, ev?.value)
    }

    @Test
    fun `L3 on tablet — score is 0_7 (no model cross-check)`() = runBlocking {
        // Tablet not in phone-class list → l3-on-phone rule doesn't fire,
        // falls through to bare L3 → 0.70.
        val result = makeProbe(securityLevel = "L3").run(ctxWithModel("Lenovo Tab P11"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `L3 on tablet — pattern is l3_alone`() = runBlocking {
        val result = makeProbe(securityLevel = "L3").run(ctxWithModel("Lenovo Tab P11"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_L3_ALONE, ev?.value)
    }

    // ── Low-entropy unique ID (score 0.85) ───────────────────────────────────

    @Test
    fun `low-entropy unique ID — score is 0_85`() = runBlocking {
        // Alternating 0,1,0,1,... → only 2 unique byte values → entropy=1.0 bit/byte.
        val result = makeProbe(uniqueId = lowEntropyId).run(ctxWithModel("Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `low-entropy ID — pattern is low_entropy_id`() = runBlocking {
        val result = makeProbe(uniqueId = lowEntropyId).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_LOW_ENTROPY_ID, ev?.value)
    }

    @Test
    fun `realistic random unique ID — entropy is above threshold`() = runBlocking {
        // 32-byte sample caps at log2(32) = 5.0 bits/byte. A real Widevine
        // ID typically lands around 4.5; the seeded random sample here lands
        // at ~4.875. Threshold is 4.0 to catch obvious stubs without false-
        // positive on real entropy fluctuation. See KDoc on
        // ENTROPY_THRESHOLD_BITS_PER_BYTE for the spec-vs-empirical calibration.
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.entropy_bits_per_byte" }
        val entropy = (ev?.value as String).toDouble()
        assertTrue(
            entropy >= MediaDrmProbe.ENTROPY_THRESHOLD_BITS_PER_BYTE,
            "expected entropy >= 4.0, got $entropy",
        )
    }

    // ── Multi-signal precedence (cascade order) ──────────────────────────────

    @Test
    fun `no-Widevine plus L3 on Pixel — score is 1_0 (no-widevine wins)`() = runBlocking {
        val result = makeProbe(
            widevineSupported = false,
            securityLevel = "L3",
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero ID plus L3 on Pixel — score is 1_0 (zero-id wins)`() = runBlocking {
        val result = makeProbe(
            uniqueId = zeroId,
            securityLevel = "L3",
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Mock vendor plus L3 on Pixel — score is 0_95 (Mock dominates L3-on-phone via cascade)`() = runBlocking {
        val result = makeProbe(
            vendor = "Mock",
            securityLevel = "L3",
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_MOCK_VENDOR, ev?.value)
    }

    @Test
    fun `low entropy on phone with L1 — score is 0_85`() = runBlocking {
        // Entropy rule fires before L3-alone in cascade; L1 device with
        // low-entropy ID still scores 0.85.
        val result = makeProbe(
            uniqueId = lowEntropyId,
            securityLevel = "L1",
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.85, result.score)
    }

    // ── Production stub (all suppliers null) ─────────────────────────────────

    @Test
    fun `no-arg production constructor — score is 0_0, confidence 0_5`() = runBlocking {
        val result = MediaDrmProbe().run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production constructor — pattern is no_signals`() = runBlocking {
        val result = MediaDrmProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.pattern" }
        assertEquals(MediaDrmProbe.PATTERN_NO_SIGNALS, ev?.value)
    }

    @Test
    fun `widevine_supported null evidence reads literal null`() = runBlocking {
        val result = MediaDrmProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.widevine_supported" }
        assertEquals("null", ev?.value)
    }

    // ── Supplier-throws crash safety ─────────────────────────────────────────

    @Test
    fun `widevineSupportedSupplier throws — does not crash`() = runBlocking {
        val probe = MediaDrmProbe(
            widevineSupportedSupplier = { throw RuntimeException("simulated MediaDrm") },
            securityLevelSupplier = { "L1" },
            vendorSupplier = { "Google" },
            versionSupplier = { "17.0.0" },
            deviceUniqueIdSupplier = { realisticUniqueId },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `deviceUniqueIdSupplier throws — does not crash`() = runBlocking {
        val probe = MediaDrmProbe(
            widevineSupportedSupplier = { true },
            securityLevelSupplier = { "L1" },
            vendorSupplier = { "Google" },
            versionSupplier = { "17.0.0" },
            deviceUniqueIdSupplier = { throw RuntimeException("simulated DRM denied") },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        // Widevine supported + L1 + vendor present → falls through to CLEAN_L1.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `getSystemProperty throws — phone-class becomes false, falls through cleanly`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = throw RuntimeException("simulated")
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
        // L3 without model cross-check (model accessor throws → not phone-class)
        // should fall to 0.70 (PATTERN_L3_ALONE), not 0.95 (PATTERN_L3_ON_PHONE).
        val result = makeProbe(securityLevel = "L3").run(ctx)
        assertEquals(0.70, result.score)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `shannonEntropyBitsPerByte returns 0 for all-same bytes`() {
        val bytes = ByteArray(16) { 0x42.toByte() }
        assertEquals(0.0, MediaDrmProbe.shannonEntropyBitsPerByte(bytes), 1e-9)
    }

    @Test
    fun `shannonEntropyBitsPerByte returns 1 for two-equal-half bytes`() {
        val bytes = ByteArray(16) { if (it < 8) 0x00.toByte() else 0x01.toByte() }
        assertTrue(
            kotlin.math.abs(MediaDrmProbe.shannonEntropyBitsPerByte(bytes) - 1.0) < 1e-9,
        )
    }

    @Test
    fun `shannonEntropyBitsPerByte handles 256 unique bytes — entropy 8`() {
        val bytes = ByteArray(256) { it.toByte() }
        val h = MediaDrmProbe.shannonEntropyBitsPerByte(bytes)
        assertTrue(kotlin.math.abs(h - 8.0) < 1e-9, "expected 8.0 max entropy, got $h")
    }

    @Test
    fun `shannonEntropyBitsPerByte returns 0 for empty and single-byte`() {
        assertEquals(0.0, MediaDrmProbe.shannonEntropyBitsPerByte(ByteArray(0)))
        assertEquals(0.0, MediaDrmProbe.shannonEntropyBitsPerByte(ByteArray(1)))
    }

    @Test
    fun `isAllZeroOrFF accepts all-zero and all-FF`() {
        assertTrue(MediaDrmProbe.isAllZeroOrFF(ByteArray(32)))
        assertTrue(MediaDrmProbe.isAllZeroOrFF(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun `isAllZeroOrFF rejects mixed and empty`() {
        assertFalse(MediaDrmProbe.isAllZeroOrFF(ByteArray(0)))
        assertFalse(MediaDrmProbe.isAllZeroOrFF(byteArrayOf(0, 0, 1, 0)))
        assertFalse(MediaDrmProbe.isAllZeroOrFF(byteArrayOf(0xFF.toByte(), 0)))
    }

    @Test
    fun `sha256Hex produces 64-char lowercase hex`() {
        val result = MediaDrmProbe.sha256Hex(ByteArray(0))
        // Empty input SHA-256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result,
        )
    }

    @Test
    fun `sha256Hex is deterministic on the same input`() {
        val input = ByteArray(32) { it.toByte() }
        assertEquals(
            MediaDrmProbe.sha256Hex(input),
            MediaDrmProbe.sha256Hex(input),
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "mediadrm.widevine_supported",
                "mediadrm.security_level",
                "mediadrm.vendor",
                "mediadrm.version",
                "mediadrm.device_unique_id_hex",
                "mediadrm.device_unique_id_length",
                "mediadrm.entropy_bits_per_byte",
                "mediadrm.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `device_unique_id_length evidence is 32 for real ID`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.device_unique_id_length" }
        assertEquals(32, ev?.value)
    }

    @Test
    fun `null uniqueId — hex evidence reads null and length is 0`() = runBlocking {
        val result = makeProbe(uniqueId = null).run(ctxWithModel("Pixel 7"))
        assertEquals("null", result.evidence.find { it.key == "mediadrm.device_unique_id_hex" }?.value)
        assertEquals(0, result.evidence.find { it.key == "mediadrm.device_unique_id_length" }?.value)
    }

    @Test
    fun `entropy evidence uses locale-stable decimal formatting`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "mediadrm.entropy_bits_per_byte" }
        val value = ev?.value as String
        assertTrue('.' in value, "expected decimal point, got $value")
        assertFalse(',' in value, "expected no comma, got $value")
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        assertEquals(
            "Query MediaDrm Widevine UUID for security level + vendor + " +
                "version + 32-byte device unique ID; detect L3 software " +
                "fallback and zero/low-entropy unique-ID stubs",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_mediadrm`() {
        assertEquals("identity.mediadrm", makeProbe().id)
    }

    @Test
    fun `probe rank is 29`() {
        assertEquals(29, makeProbe().rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, makeProbe().category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-29 severity=high
        // (BEST-STACK Phase 3 rerank, MASTG MSTG-RESILIENCE-10).
        assertEquals(ProbeSeverity.HIGH, makeProbe().severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 400ms`() {
        assertEquals(400L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

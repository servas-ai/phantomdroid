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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for GsfIdProbe (identity.gsf_id, inventory rank 17).
 */
class GsfIdProbeTest {

    /** A realistic 16-hex GSF ID with full entropy. */
    private val realGsfId = "a1b2c3d4e5f60718"

    private fun makeProbe(gsf: String? = realGsfId): GsfIdProbe =
        GsfIdProbe(gsfIdSupplier = { gsf })

    private fun ctxWithGsf(gsfDirPresent: Boolean = true): ProbeContext =
        object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String): Boolean =
                path == GsfIdProbe.PATH_GSF_DATA && gsfDirPresent
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

    // ── Clean real GSF ID ─────────────────────────────────────────────────────

    @Test
    fun `realistic 16-hex GSF ID — score is 0`() = runBlocking {
        val result = makeProbe(realGsfId).run(ctxWithGsf())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `realistic GSF ID — confidence is 0_95`() = runBlocking {
        val result = makeProbe(realGsfId).run(ctxWithGsf())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `realistic GSF ID — pattern is normal`() = runBlocking {
        val result = makeProbe(realGsfId).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_NORMAL, ev?.value)
    }

    @Test
    fun `uppercase GSF ID normalised to lowercase before format check`() = runBlocking {
        val result = makeProbe(realGsfId.uppercase()).run(ctxWithGsf())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `GSF ID with trailing newline — normalised before scoring`() = runBlocking {
        val result = makeProbe("$realGsfId\n").run(ctxWithGsf())
        assertEquals(0.0, result.score)
    }

    // ── Zero state (score 1.0) ────────────────────────────────────────────────

    @Test
    fun `GSF ID is integer-zero string — score is 1_0`() = runBlocking {
        // Play Services returns the integer 0 stringified as "0".
        val result = makeProbe(gsf = "0").run(ctxWithGsf())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `GSF ID is all-zero 16-hex — score is 1_0`() = runBlocking {
        val result = makeProbe(gsf = "0000000000000000").run(ctxWithGsf())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero state — pattern evidence is zero`() = runBlocking {
        val result = makeProbe(gsf = "0").run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_ZERO, ev?.value)
    }

    @Test
    fun `zero state — is_zero evidence is true`() = runBlocking {
        val result = makeProbe(gsf = "0000000000000000").run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.is_zero" }
        assertEquals("true", ev?.value)
    }

    // ── Factory default (score 1.0) ───────────────────────────────────────────

    @Test
    fun `factory default 9774d56d682e549c — score is 1_0`() = runBlocking {
        val result = makeProbe(gsf = AndroidIdProbe.FACTORY_DEFAULT_ANDROID_ID).run(ctxWithGsf())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `factory default — pattern evidence is factory_default`() = runBlocking {
        val result = makeProbe(gsf = AndroidIdProbe.FACTORY_DEFAULT_ANDROID_ID).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_FACTORY_DEFAULT, ev?.value)
    }

    // ── Sequential debug values (score 0.85) ──────────────────────────────────

    @Test
    fun `0123456789abcdef sequential — score is 0_85`() = runBlocking {
        val result = makeProbe(gsf = "0123456789abcdef").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `1234567890abcdef sequential — score is 0_85`() = runBlocking {
        val result = makeProbe(gsf = "1234567890abcdef").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `deadbeef placeholder — score is 0_85`() = runBlocking {
        val result = makeProbe(gsf = "deadbeefdeadbeef").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `aaaaaaaaaaaaaaaa — pattern is sequential (list dominates low_entropy)`() = runBlocking {
        // aaaaaaaaaaaaaaaa is in SEQUENTIAL_DEBUG_VALUES; pattern should be
        // "sequential" not "low_entropy", and score is 0.85 either way.
        val result = makeProbe(gsf = "aaaaaaaaaaaaaaaa").run(ctxWithGsf())
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_SEQUENTIAL, ev?.value)
    }

    @Test
    fun `ffffffffffffffff in sequential set — score is 0_85`() = runBlocking {
        val result = makeProbe(gsf = "ffffffffffffffff").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    // ── Low entropy NOT on the sequential list (score 0.85) ──────────────────

    @Test
    fun `aaaaaaaabbbbbbbb low-entropy custom — score is 0_85`() = runBlocking {
        // 2 unique chars at 8 each → entropy=1.0 bits/char, below threshold,
        // not in SEQUENTIAL_DEBUG_VALUES.
        val result = makeProbe(gsf = "aaaaaaaabbbbbbbb").run(ctxWithGsf())
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_LOW_ENTROPY, ev?.value)
    }

    @Test
    fun `aabbccddeeff0011 above entropy threshold — score is 0`() = runBlocking {
        // 8 unique chars at 2 each → entropy=3.0 bits/char, above threshold.
        // Confirms the 2.5 boundary doesn't false-positive on real-looking IDs.
        val result = makeProbe(gsf = "aabbccddeeff0011").run(ctxWithGsf())
        assertEquals(0.0, result.score)
    }

    // ── Malformed GSF ID (score 0.85) ─────────────────────────────────────────

    @Test
    fun `15-char GSF ID — score is 0_85 malformed`() = runBlocking {
        val result = makeProbe(gsf = "a1b2c3d4e5f6071").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `17-char GSF ID — score is 0_85 malformed`() = runBlocking {
        val result = makeProbe(gsf = "a1b2c3d4e5f607180").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-hex GSF ID — score is 0_85 malformed`() = runBlocking {
        val result = makeProbe(gsf = "g1b2c3d4e5f60718").run(ctxWithGsf())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `malformed — pattern evidence is malformed`() = runBlocking {
        val result = makeProbe(gsf = "g1b2c3d4e5f60718").run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_MALFORMED, ev?.value)
    }

    // ── Null with GSF framework dir present (score 0.7) ──────────────────────

    @Test
    fun `null GSF ID with GSF framework dir present — score is 0_7`() = runBlocking {
        val result = makeProbe(gsf = null).run(ctxWithGsf(gsfDirPresent = true))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `null GSF ID — pattern evidence is null`() = runBlocking {
        val result = makeProbe(gsf = null).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.pattern" }
        assertEquals(GsfIdProbe.PATTERN_NULL, ev?.value)
    }

    @Test
    fun `null GSF ID — confidence is 0_5 degraded`() = runBlocking {
        val result = makeProbe(gsf = null).run(ctxWithGsf())
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `empty string GSF ID — same as null (score 0_7 with dir, 0 without)`() = runBlocking {
        val withDir = makeProbe(gsf = "").run(ctxWithGsf(gsfDirPresent = true))
        val withoutDir = makeProbe(gsf = "").run(ctxWithGsf(gsfDirPresent = false))
        assertEquals(0.7, withDir.score)
        assertEquals(0.0, withoutDir.score)
    }

    // ── Null without GSF framework (clean state) ──────────────────────────────

    @Test
    fun `null GSF ID without GSF framework dir — score is 0 (consistent absence)`() = runBlocking {
        val result = makeProbe(gsf = null).run(ctxWithGsf(gsfDirPresent = false))
        assertEquals(0.0, result.score)
        assertEquals(0.5, result.confidence)
    }

    // ── Production no-arg constructor ─────────────────────────────────────────

    @Test
    fun `no-arg ctor with GSF dir present — score is 0_7 (stub)`() = runBlocking {
        val result = GsfIdProbe().run(ctxWithGsf(gsfDirPresent = true))
        assertEquals(0.7, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `no-arg ctor without GSF dir — score is 0`() = runBlocking {
        val result = GsfIdProbe().run(ctxWithGsf(gsfDirPresent = false))
        assertEquals(0.0, result.score)
    }

    // ── Supplier throws ───────────────────────────────────────────────────────

    @Test
    fun `gsf supplier throws — falls through to null, no crash`() = runBlocking {
        val probe = GsfIdProbe(gsfIdSupplier = { throw RuntimeException("simulated") })
        val result = probe.run(ctxWithGsf(gsfDirPresent = true))
        assertFalse(result.failed)
        assertEquals(0.7, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `ctx fileExists throws — GSF dir heuristic returns false safely`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String): Boolean = throw RuntimeException("simulated EACCES")
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
        val result = makeProbe(gsf = null).run(ctx)
        assertFalse(result.failed)
        // Heuristic treats throw as "no dir" → null GSF + no dir → 0.0.
        assertEquals(0.0, result.score)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = makeProbe().run(ctxWithGsf())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all six documented keys`() = runBlocking {
        val result = makeProbe().run(ctxWithGsf())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "gsf_id",
                "gsf_id.length",
                "gsf_id.format_valid",
                "gsf_id.is_zero",
                "gsf_id.pattern",
                "gsf_id.entropy_bits_per_char",
            ),
            keys,
        )
    }

    @Test
    fun `null GSF ID — gsf_id evidence is literal null string`() = runBlocking {
        val result = makeProbe(gsf = null).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `gsf_id length evidence is Int matching normalised length`() = runBlocking {
        val result = makeProbe(realGsfId).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.length" }
        assertEquals(16, ev?.value)
    }

    @Test
    fun `entropy evidence uses locale-stable decimal formatting`() = runBlocking {
        // Regression guard: same class as rank-11 / rank-16 — `%.3f`.format on a
        // German-locale build host would emit `3,001` without Locale.ROOT.
        val result = makeProbe(realGsfId).run(ctxWithGsf())
        val ev = result.evidence.find { it.key == "gsf_id.entropy_bits_per_char" }
        val value = ev?.value as String
        assertTrue('.' in value, "expected decimal point, got $value")
        assertFalse(',' in value, "expected no comma, got $value")
    }

    // ── AndroidIdProbe constant reuse — invariant check ──────────────────────

    @Test
    fun `factory default constant is shared with rank-11 AndroidIdProbe`() {
        // The team-lead's spec called out the same factory default `9774d56d682e549c`
        // for GSF ID as for ANDROID_ID. This test locks in that the GSF probe
        // is reading from AndroidIdProbe's constant rather than redefining its own.
        assertEquals("9774d56d682e549c", AndroidIdProbe.FACTORY_DEFAULT_ANDROID_ID)
    }

    @Test
    fun `sequential debug values are shared with rank-11 set`() {
        // Same reuse invariant — the GSF probe iterates the rank-11 set rather
        // than duplicating it.
        assertTrue("0000000000000000" in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES)
        assertTrue("ffffffffffffffff" in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES)
        assertTrue("0123456789abcdef" in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES)
        assertTrue("aaaaaaaaaaaaaaaa" in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES)
        assertTrue("deadbeefdeadbeef" in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(ctxWithGsf())
        assertEquals(
            "Read Google Services Framework android_id via ContentProvider " +
                "gservices, validate 16-hex format, check against factory-" +
                "default and entropy thresholds",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_gsf_id`() {
        assertEquals("identity.gsf_id", makeProbe().id)
    }

    @Test
    fun `probe rank is 17`() {
        assertEquals(17, makeProbe().rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, makeProbe().category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-17 severity=high.
        assertEquals(ProbeSeverity.HIGH, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = makeProbe().run(ctxWithGsf())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }

    private val probe = makeProbe()
}

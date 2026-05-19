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
 * Unit tests for AndroidIdProbe (identity.android_id, inventory rank 11).
 */
class AndroidIdProbeTest {

    private val probe = AndroidIdProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        androidId: String? = "a1b2c3d4e5f60718",
        secureThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? {
            if (secureThrows) throw RuntimeException("simulated SettingNotFoundException")
            return if (key == AndroidIdProbe.SETTINGS_KEY_ANDROID_ID) androidId else null
        }
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

    // ── Normal value ──────────────────────────────────────────────────────────

    @Test
    fun `valid 16-hex with full entropy — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid 16-hex — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `valid 16-hex — pattern is normal`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("normal", ev?.value)
    }

    @Test
    fun `valid 16-hex — length evidence is 16`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        val ev = result.evidence.find { it.key == "android_id.length" }
        assertEquals("16", ev?.value)
    }

    // ── Factory default (9774d56d682e549c) ────────────────────────────────────

    @Test
    fun `factory default value — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("9774d56d682e549c"))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `factory default value — pattern is default`() = runBlocking {
        val result = probe.run(fakeCtx("9774d56d682e549c"))
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("default", ev?.value)
    }

    @Test
    fun `factory default value — confidence is still 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("9774d56d682e549c"))
        assertEquals(0.95, result.confidence)
    }

    // ── Sequential / known-debug values ──────────────────────────────────────

    @Test
    fun `all-zero value — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("0000000000000000"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `all-f value — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("ffffffffffffffff"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `1234567890abcdef sequential — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("1234567890abcdef"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `0123456789abcdef sequential — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("0123456789abcdef"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `deadbeef placeholder — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("deadbeefdeadbeef"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `aaaaaaaaaaaaaaaa — score is 0_95 (sequential list wins over low_entropy)`() = runBlocking {
        // This value would also hit low-entropy (0 bits/char), but the explicit
        // SEQUENTIAL_DEBUG_VALUES list takes precedence, tagged as "sequential".
        val result = probe.run(fakeCtx("aaaaaaaaaaaaaaaa"))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("sequential", ev?.value)
    }

    // ── Empty / null / non-hex ────────────────────────────────────────────────

    @Test
    fun `null value — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(androidId = null))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `null value — pattern is empty`() = runBlocking {
        val result = probe.run(fakeCtx(androidId = null))
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("empty", ev?.value)
    }

    @Test
    fun `empty string value — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(""))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `15-char value — score is 0_85 (length mismatch)`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f6071"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("non_hex", ev?.value)
    }

    @Test
    fun `17-char value — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f6071800"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `uppercase-hex value — score is 0_85 (case-sensitive 16-hex check)`() = runBlocking {
        // Settings.Secure.ANDROID_ID is always lowercase per Android contract.
        // Uppercase input is treated as non-conformant.
        val result = probe.run(fakeCtx("A1B2C3D4E5F60718"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-hex characters — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx("g1b2c3d4e5f60718"))  // 'g' is non-hex
        assertEquals(0.85, result.score)
    }

    // ── Low-entropy patterns NOT on the sequential list ──────────────────────

    @Test
    fun `aabbccddeeff0011 is above entropy threshold — score is 0_0`() = runBlocking {
        // 8 unique chars at p=2/16 each → entropy = 3.0. Above the 2.5 threshold,
        // so it counts as normal. Locks in the threshold boundary.
        val result = probe.run(fakeCtx("aabbccddeeff0011"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `1122334455667788 is above entropy threshold — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx("1122334455667788"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `aaaaaaabbbbbbbbb low-entropy — score is 0_75`() = runBlocking {
        // 7 'a' + 9 'b' = 2 unique chars. Entropy ≈ 0.989 bits/char. Below 2.5.
        val result = probe.run(fakeCtx("aaaaaaabbbbbbbbb"))
        assertEquals(0.75, result.score)
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("low_entropy", ev?.value)
    }

    @Test
    fun `aaaabbbbccccdddd low-entropy — score is 0_75`() = runBlocking {
        // 4 unique chars at p=4/16=0.25 each → entropy = 2.0. Below 2.5.
        val result = probe.run(fakeCtx("aaaabbbbccccdddd"))
        assertEquals(0.75, result.score)
    }

    @Test
    fun `abababab type pattern — score is 0_75 (entropy 1)`() = runBlocking {
        // 2 unique chars at p=0.5 each → entropy = 1.0 bits/char. Below 2.5.
        val result = probe.run(fakeCtx("abababababababab"))
        assertEquals(0.75, result.score)
    }

    // ── Accessor throws ───────────────────────────────────────────────────────

    @Test
    fun `accessor throws — no crash, score 0_0, confidence 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `accessor throws — pattern is empty`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        val ev = result.evidence.find { it.key == "android_id.pattern" }
        assertEquals("empty", ev?.value)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence row count is exactly 4`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence contains the four documented keys`() = runBlocking {
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "android_id",
                "android_id.length",
                "android_id.pattern",
                "android_id.entropy_bits_per_char",
            ),
            keys,
        )
    }

    @Test
    fun `entropy evidence uses locale-stable decimal formatting`() = runBlocking {
        // Regression guard: "%.3f".format(...) with default locale on a
        // German-locale build host emits "3,001" instead of "3.001". The
        // probe pins java.util.Locale.ROOT so JSON downstream stays parseable.
        val result = probe.run(fakeCtx("a1b2c3d4e5f60718"))
        val ev = result.evidence.find { it.key == "android_id.entropy_bits_per_char" }
        assertTrue(
            (ev?.value as String).contains('.'),
            "expected decimal-point formatting, got: ${ev.value}",
        )
        assertFalse(
            (ev.value as String).contains(','),
            "value must not contain comma; got: ${ev.value}",
        )
    }

    // ── Entropy helper unit tests ─────────────────────────────────────────────

    @Test
    fun `entropy of all-same chars is 0`() {
        val h = AndroidIdProbe.shannonEntropyBitsPerChar("aaaaaaaaaaaaaaaa")
        assertEquals(0.0, h, 1e-9)
    }

    @Test
    fun `entropy of two-equal-half chars is 1`() {
        val h = AndroidIdProbe.shannonEntropyBitsPerChar("aaaaaaaabbbbbbbb")
        assertTrue(kotlin.math.abs(h - 1.0) < 1e-9)
    }

    @Test
    fun `entropy of 16 unique chars is 4`() {
        val h = AndroidIdProbe.shannonEntropyBitsPerChar("0123456789abcdef")
        assertTrue(kotlin.math.abs(h - 4.0) < 1e-9)
    }

    @Test
    fun `entropy of empty string is 0`() {
        val h = AndroidIdProbe.shannonEntropyBitsPerChar("")
        assertEquals(0.0, h, 1e-9)
    }

    @Test
    fun `entropy of single char is 0`() {
        val h = AndroidIdProbe.shannonEntropyBitsPerChar("a")
        assertEquals(0.0, h, 1e-9)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Settings.Secure.ANDROID_ID and check against known factory " +
                "defaults, sequential patterns, and Shannon entropy threshold",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_android_id`() {
        assertEquals("identity.android_id", probe.id)
    }

    @Test
    fun `probe rank is 11`() {
        assertEquals(11, probe.rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

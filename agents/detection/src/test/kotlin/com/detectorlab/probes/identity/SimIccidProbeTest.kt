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
 * Unit tests for SimIccidProbe (identity.sim_iccid, inventory rank 21).
 */
class SimIccidProbeTest {

    private val probe = SimIccidProbe()

    /**
     * A 19-digit Luhn-valid ICCID that starts with 89, is NOT in the
     * known-emulator list, and is NOT all-same-digit or monotonic-ascending.
     * Hand-computed: digits `8949000000000000000`, Luhn sum = 30, mod 10 = 0.
     */
    private val validIccid = "8949000000000000000"

    private fun fakeCtx(
        iccid: String? = validIccid,
        accessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? {
            if (accessorThrows) throw RuntimeException("simulated SIM unavailable")
            return if (field == TelephonyField.SIM_SERIAL) iccid else null
        }
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

    // ── Clean valid ICCID ─────────────────────────────────────────────────────

    @Test
    fun `valid 89-prefix Luhn-valid 19-digit ICCID — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(validIccid))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid ICCID — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(validIccid))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `valid ICCID — pattern evidence is valid`() = runBlocking {
        val result = probe.run(fakeCtx(validIccid))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_VALID, ev?.value)
    }

    @Test
    fun `valid ICCID — luhn_valid evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(validIccid))
        val ev = result.evidence.find { it.key == "sim_iccid.luhn_valid" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `valid ICCID with trailing newline — trimmed and accepted`() = runBlocking {
        val result = probe.run(fakeCtx("$validIccid\n"))
        assertEquals(0.0, result.score)
    }

    // ── Known emulator-default ICCIDs (score 1.0) ─────────────────────────────

    @Test
    fun `canonical AOSP emulator ICCID — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("89014103211118510720"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AOSP zero-tail variant — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("89014103200000000000"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `China-region test card — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("89860000000000000000"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `known emulator — pattern is emulator_default`() = runBlocking {
        val result = probe.run(fakeCtx("89014103211118510720"))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_EMULATOR_DEFAULT, ev?.value)
    }

    @Test
    fun `known emulator — is_known_emulator evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx("89014103211118510720"))
        val ev = result.evidence.find { it.key == "sim_iccid.is_known_emulator" }
        assertEquals("true", ev?.value)
    }

    // ── Repeating / sequential (score 1.0) ────────────────────────────────────

    @Test
    fun `all-zero 19-digit ICCID — score is 1_0 repeating`() = runBlocking {
        val result = probe.run(fakeCtx("0".repeat(19)))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_REPEATING, ev?.value)
    }

    @Test
    fun `all-nines 20-digit ICCID — score is 1_0 repeating`() = runBlocking {
        val result = probe.run(fakeCtx("9".repeat(20)))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `monotonic ascending 20-digit — score is 1_0 sequential`() = runBlocking {
        // 01234567890123456789 — strict +1 mod 10 from each digit to next.
        val result = probe.run(fakeCtx("01234567890123456789"))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_SEQUENTIAL, ev?.value)
    }

    // ── Non-89 prefix (score 0.95) ────────────────────────────────────────────

    @Test
    fun `prefix 88 instead of 89 — score is 0_95`() = runBlocking {
        // Same digit body as validIccid but starting with 88. Length and
        // Luhn might happen to pass; the prefix check fires first.
        val result = probe.run(fakeCtx("8849000000000000000"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `prefix 12 non-sequential — score is 0_95`() = runBlocking {
        // 1239111111111111111 — 19 digits, prefix 12 (not 89), not all-same,
        // not monotonic ascending. Falls through to the prefix rule at 0.95.
        val result = probe.run(fakeCtx("1239111111111111111"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `non-89 prefix — pattern is no_89_prefix`() = runBlocking {
        val result = probe.run(fakeCtx("8849000000000000000"))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_NO_89_PREFIX, ev?.value)
    }

    @Test
    fun `non-89 prefix — prefix_valid evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx("8849000000000000000"))
        val ev = result.evidence.find { it.key == "sim_iccid.prefix_valid" }
        assertEquals("false", ev?.value)
    }

    // ── Malformed length (score 0.85) ─────────────────────────────────────────

    @Test
    fun `18-digit ICCID — score is 0_85 malformed_length`() = runBlocking {
        val result = probe.run(fakeCtx("8949" + "0".repeat(14)))   // 18 chars
        assertEquals(0.85, result.score)
    }

    @Test
    fun `21-digit ICCID — score is 0_85 malformed_length`() = runBlocking {
        val result = probe.run(fakeCtx("8949" + "0".repeat(17)))   // 21 chars
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-digit characters — score is 0_85 malformed_length`() = runBlocking {
        val result = probe.run(fakeCtx("89X9000000000000000"))     // 19 chars but contains X
        assertEquals(0.85, result.score)
    }

    @Test
    fun `malformed length — pattern is malformed_length`() = runBlocking {
        val result = probe.run(fakeCtx("8949" + "0".repeat(17)))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_MALFORMED_LENGTH, ev?.value)
    }

    // ── Luhn fail (score 0.90) — only checked after prefix+length pass ────────

    @Test
    fun `89-prefix valid length but Luhn fails — score is 0_90`() = runBlocking {
        // Take validIccid (Luhn passes) and flip the check digit to break Luhn.
        // validIccid last char is '0'; change to '1'.
        val brokenLuhn = validIccid.dropLast(1) + "1"
        val result = probe.run(fakeCtx(brokenLuhn))
        assertEquals(0.90, result.score)
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_LUHN_FAIL, ev?.value)
    }

    // ── Null ICCID ────────────────────────────────────────────────────────────

    @Test
    fun `null ICCID with accessor working — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(iccid = null))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `null ICCID — confidence is 0_5 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(iccid = null))
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `null ICCID — pattern is null`() = runBlocking {
        val result = probe.run(fakeCtx(iccid = null))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_NULL, ev?.value)
    }

    @Test
    fun `empty-string ICCID — treated same as null`() = runBlocking {
        val result = probe.run(fakeCtx(iccid = ""))
        assertEquals(0.7, result.score)   // accessor worked; empty isn't observable as fake
    }

    // ── Accessor throws ──────────────────────────────────────────────────────

    @Test
    fun `accessor throws — score is 0 (no observation), confidence 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
        // accessorWorked=false AND value is null → "null" pattern, SCORE_CLEAN.
        assertEquals(0.0, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `accessor throws — pattern is null`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_NULL, ev?.value)
    }

    // ── Precedence: known-emulator dominates Luhn ────────────────────────────

    @Test
    fun `canonical AOSP ICCID is also Luhn-valid but caught by emulator rule first`() = runBlocking {
        // 89014103211118510720 happens to be Luhn-valid (sum mod 10 == 0).
        // The known-emulator list catches it FIRST at score 1.0, regardless.
        val result = probe.run(fakeCtx("89014103211118510720"))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "sim_iccid.pattern" }
        assertEquals(SimIccidProbe.PATTERN_EMULATOR_DEFAULT, ev?.value)
    }

    // ── Precedence: prefix-fail dominates Luhn-fail ──────────────────────────

    @Test
    fun `non-89 prefix and Luhn fail — score is 0_95 (prefix wins)`() = runBlocking {
        // 7000000000000000000 — 19 digits, all zeros after the 7.
        // Wait this triggers repeating-detection too since first char is 7 and
        // rest aren't 7. So !isAllSame. The "all same digit" check requires
        // every char to equal char[0]. Let me use a different non-89 prefix.
        val result = probe.run(fakeCtx("7849000000000000000"))   // 19 digits, prefix 78
        // Length 19 ✓, digits-only ✓, not all-same, not monotonic, prefix != 89.
        // Cascade catches no_89_prefix → 0.95.
        assertEquals(0.95, result.score)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all six documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "sim_iccid",
                "sim_iccid.length",
                "sim_iccid.prefix_valid",
                "sim_iccid.luhn_valid",
                "sim_iccid.is_known_emulator",
                "sim_iccid.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `null ICCID — sim_iccid evidence is literal null string`() = runBlocking {
        val result = probe.run(fakeCtx(iccid = null))
        val ev = result.evidence.find { it.key == "sim_iccid" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `length evidence is Int matching trimmed length`() = runBlocking {
        val result = probe.run(fakeCtx(validIccid))
        val ev = result.evidence.find { it.key == "sim_iccid.length" }
        assertEquals(19, ev?.value)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `luhn accepts the validIccid test constant`() {
        assertTrue(SimIccidProbe.luhn(validIccid))
    }

    @Test
    fun `luhn accepts the canonical AOSP emulator ICCID`() {
        // Documents that the AOSP emulator ICCID is Luhn-valid, so the
        // emulator-list precedence is doing real work.
        assertTrue(SimIccidProbe.luhn("89014103211118510720"))
    }

    @Test
    fun `luhn rejects validIccid with last digit flipped`() {
        assertFalse(SimIccidProbe.luhn(validIccid.dropLast(1) + "1"))
    }

    @Test
    fun `luhn rejects empty and non-digit input`() {
        assertFalse(SimIccidProbe.luhn(""))
        assertFalse(SimIccidProbe.luhn("89X9000000000000000"))
        assertFalse(SimIccidProbe.luhn("not-a-number"))
    }

    @Test
    fun `luhn handles 20-digit input as well as 19-digit`() {
        // Some carriers use 20-digit ICCIDs. The luhn() helper isn't
        // length-locked (unlike rank-12's luhn15). Verify a 20-digit value.
        // 89014103211118510720 — 20 digits, AOSP canonical → Luhn valid.
        assertTrue(SimIccidProbe.luhn("89014103211118510720"))
    }

    @Test
    fun `isAllSameDigit accepts all-zero and rejects mixed`() {
        assertTrue(SimIccidProbe.isAllSameDigit("00000000"))
        assertTrue(SimIccidProbe.isAllSameDigit("999"))
        assertFalse(SimIccidProbe.isAllSameDigit("0001"))
        assertFalse(SimIccidProbe.isAllSameDigit(""))
        assertFalse(SimIccidProbe.isAllSameDigit("00a0"))    // non-digit
    }

    @Test
    fun `isMonotonicAscending accepts 0123456789 and wraps at 9`() {
        assertTrue(SimIccidProbe.isMonotonicAscending("0123456789"))
        assertTrue(SimIccidProbe.isMonotonicAscending("01234567890"))     // wraps 9→0
        assertTrue(SimIccidProbe.isMonotonicAscending("4567890123"))      // mid-cycle wrap
        assertFalse(SimIccidProbe.isMonotonicAscending("9876543210"))     // descending
        assertFalse(SimIccidProbe.isMonotonicAscending("12345abc"))       // non-digit
        assertFalse(SimIccidProbe.isMonotonicAscending(""))
        assertFalse(SimIccidProbe.isMonotonicAscending("5"))              // < 2 chars
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read TelephonyManager.simSerialNumber, validate 89-prefix + " +
                "length + Luhn checksum, detect known emulator-default ICCIDs",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_sim_iccid`() {
        assertEquals("identity.sim_iccid", probe.id)
    }

    @Test
    fun `probe rank is 21`() {
        assertEquals(21, probe.rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-21 severity=high.
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 150ms`() {
        assertEquals(150L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

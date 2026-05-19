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
 * Unit tests for ImeiSerialProbe (identity.imei_serial, inventory rank 12).
 */
class ImeiSerialProbeTest {

    private val probe = ImeiSerialProbe()

    /**
     * A Luhn-valid IMEI generated for testing. `490154203237518` is a documented
     * valid Luhn-15 example (see Wikipedia: Luhn algorithm). Verified by hand:
     * reversed=815732302451094, alternating doubles → sum=70, 70%10==0.
     */
    private val validImei = "490154203237518"

    private fun fakeCtx(
        imei: String? = null,
        serial: String? = null,
        roSerialno: String? = null,
        imeiThrows: Boolean = false,
        serialThrows: Boolean = false,
        propThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propThrows) throw RuntimeException("simulated prop unavailable")
            return if (key == ImeiSerialProbe.PROP_RO_SERIALNO) roSerialno else null
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = when (field) {
            TelephonyField.IMEI -> {
                if (imeiThrows) throw RuntimeException("simulated TM IMEI unavailable")
                imei
            }
            TelephonyField.SERIAL -> {
                if (serialThrows) throw RuntimeException("simulated TM SERIAL unavailable")
                serial
            }
            else -> null
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

    // ── Clean device ──────────────────────────────────────────────────────────

    @Test
    fun `valid IMEI and unique serial — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(imei = validImei, serial = "R28X1234567", roSerialno = "R28X1234567"),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid IMEI and unique serial — confidence is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(imei = validImei, serial = "R28X1234567", roSerialno = "R28X1234567"),
        )
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `valid IMEI — luhn_valid evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, serial = "R28X1234567"))
        val ev = result.evidence.find { it.key == "imei.luhn_valid" }
        assertEquals("true", ev?.value)
    }

    // ── Known emulator IMEI (score 1.0) ───────────────────────────────────────

    @Test
    fun `all-zero IMEI — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "000000000000000", roSerialno = "R28X1234567"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `older AVD IMEI 004999010640000 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "004999010640000", roSerialno = "R28X1234567"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion IMEI — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "352099001761481", roSerialno = "R28X1234567"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `known-emulator IMEI — known_emulator evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "352099001761481"))
        val ev = result.evidence.find { it.key == "imei.known_emulator" }
        assertEquals("true", ev?.value)
    }

    // ── Emulator serial patterns (score 1.0) ──────────────────────────────────

    @Test
    fun `serial starts with EMULATOR — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, roSerialno = "EMULATOR29X1"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `serial 0123456789ABCDEF stock placeholder — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, roSerialno = "0123456789ABCDEF"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `serial all-zero — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, roSerialno = "0000000000"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `serial all-f — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, roSerialno = "ffffffffffff"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `serial pattern evidence reflects emulator classification`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, roSerialno = "EMULATOR29X1"))
        val ev = result.evidence.find { it.key == "serial.pattern" }
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_EMULATOR, ev?.value)
    }

    // ── Luhn fail (score 0.9) ─────────────────────────────────────────────────

    @Test
    fun `15-digit but Luhn-invalid IMEI — score is 0_9`() = runBlocking {
        // 490154203237519 — last digit changed; sum no longer mod 10.
        val result = probe.run(fakeCtx(imei = "490154203237519", roSerialno = "R28X1234567"))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `Luhn fail — luhn_valid evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "490154203237519"))
        val ev = result.evidence.find { it.key == "imei.luhn_valid" }
        assertEquals("false", ev?.value)
    }

    // ── Malformed IMEI (not 15 digits) (score 0.85) ───────────────────────────

    @Test
    fun `14-digit IMEI — score is 0_85 malformed`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "49015420323751", roSerialno = "R28X1234567"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `16-digit IMEI — score is 0_85 malformed`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "4901542032375180", roSerialno = "R28X1234567"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-digit IMEI — score is 0_85 malformed`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "abcdef123456789", roSerialno = "R28X1234567"))
        assertEquals(0.85, result.score)
    }

    // ── Serial == unknown AND no IMEI (score 0.7) ────────────────────────────

    @Test
    fun `both fingerprints stripped — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(imei = null, serial = null, roSerialno = "unknown"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `Android 8+ serial unknown plus null IMEI — score is 0_7`() = runBlocking {
        // ro.serialno returns the literal "unknown" string on Android 8+ user
        // builds without the special signature permission.
        val result = probe.run(fakeCtx(imei = null, serial = "unknown", roSerialno = null))
        assertEquals(0.7, result.score)
    }

    // ── IMEI null but TM works AND serial NOT unknown (score 0.5 benign) ─────

    @Test
    fun `IMEI null but serial is real — score is 0_5 benign`() = runBlocking {
        // Android 10+ privacy: TM accessor works (no throw) but IMEI is
        // null. Serial reads fine. Score is the benign 0.5 — distinguishes
        // privacy-stripped IMEI from both-stripped (0.7).
        val result = probe.run(fakeCtx(imei = null, serial = "R28X1234567"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `IMEI null and TM serial null but ro_serialno is real — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(imei = null, serial = null, roSerialno = "R28X1234567"))
        assertEquals(0.5, result.score)
    }

    // ── Multi-signal precedence (max wins) ────────────────────────────────────

    @Test
    fun `emulator IMEI plus emulator serial — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "000000000000000", roSerialno = "EMULATOR29X1"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Luhn-fail IMEI plus emulator serial — score is 1_0 (serial dominates)`() = runBlocking {
        val result = probe.run(fakeCtx(imei = "490154203237519", roSerialno = "EMULATOR29X1"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `IMEI null and stripped serial dominates over IMEI-benign`() = runBlocking {
        // IMEI null AND serial unknown → 0.7 (both stripped), not 0.5 (benign).
        val result = probe.run(fakeCtx(imei = null, serial = "unknown"))
        assertEquals(0.7, result.score)
    }

    // ── Accessor failures ─────────────────────────────────────────────────────

    @Test
    fun `TM IMEI throws — does not crash, accessor counted as failed`() = runBlocking {
        val result = probe.run(fakeCtx(imeiThrows = true, serial = "R28X1234567"))
        assertFalse(result.failed)
        // imeiAccessorWorked=false, serial reachable → IMEI null path is
        // NOT taken (imeiAccessorWorked guard). Serial real → score 0.0.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all accessors throw — score 0_0, confidence 0_5 degraded`() = runBlocking {
        val result = probe.run(
            fakeCtx(imeiThrows = true, serialThrows = true, propThrows = true),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `TM SERIAL throws but ro_serialno works — ro_serialno is used`() = runBlocking {
        val result = probe.run(
            fakeCtx(imei = validImei, serialThrows = true, roSerialno = "EMULATOR29X1"),
        )
        // Effective serial fallback path → emulator pattern detected → 1.0.
        assertEquals(1.0, result.score)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, serial = "R28X1234567"))
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all seven documented keys`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, serial = "R28X1234567"))
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "imei",
                "imei.length",
                "imei.luhn_valid",
                "imei.known_emulator",
                "ro.serialno",
                "build.serial",
                "serial.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `null IMEI emits literal null value not the absent placeholder`() = runBlocking {
        val result = probe.run(fakeCtx(imei = null, serial = "R28X1234567"))
        val ev = result.evidence.find { it.key == "imei" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `imei_length evidence is integer matching string length`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei))
        val ev = result.evidence.find { it.key == "imei.length" }
        assertEquals(15, ev?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read TelephonyManager.imei + ro.serialno, validate against " +
                "known emulator IMEIs, Luhn checksum, and default-serial patterns",
            result.method,
        )
    }

    // ── Luhn helper unit tests ────────────────────────────────────────────────

    @Test
    fun `Luhn validates the Wikipedia example`() {
        assertTrue(ImeiSerialProbe.luhn15("490154203237518"))
    }

    @Test
    fun `Luhn rejects one-digit-off variant`() {
        assertFalse(ImeiSerialProbe.luhn15("490154203237519"))
    }

    @Test
    fun `Luhn accepts all-zero string`() {
        // 15 zeros: sum=0, mod 10 == 0. Technically Luhn-valid; flagged
        // elsewhere by the known-emulator list at score 1.0.
        assertTrue(ImeiSerialProbe.luhn15("000000000000000"))
    }

    @Test
    fun `Luhn rejects non-15-length input`() {
        assertFalse(ImeiSerialProbe.luhn15("4901542032375"))     // 13 digits
        assertFalse(ImeiSerialProbe.luhn15("49015420323751800")) // 17 digits
        assertFalse(ImeiSerialProbe.luhn15(""))
    }

    @Test
    fun `Luhn rejects non-digit input`() {
        assertFalse(ImeiSerialProbe.luhn15("49015420323751a"))
        assertFalse(ImeiSerialProbe.luhn15("abcdef123456789"))
    }

    // ── Serial classifier unit tests ──────────────────────────────────────────

    @Test
    fun `classifySerial returns unknown for null and empty`() {
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_UNKNOWN, ImeiSerialProbe.classifySerial(null))
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_UNKNOWN, ImeiSerialProbe.classifySerial(""))
    }

    @Test
    fun `classifySerial returns unknown for literal 'unknown'`() {
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_UNKNOWN, ImeiSerialProbe.classifySerial("unknown"))
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_UNKNOWN, ImeiSerialProbe.classifySerial("UNKNOWN"))
    }

    @Test
    fun `classifySerial returns emulator for EMULATOR prefix`() {
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_EMULATOR, ImeiSerialProbe.classifySerial("EMULATOR29X1"))
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_EMULATOR, ImeiSerialProbe.classifySerial("emulator-7"))
    }

    @Test
    fun `classifySerial returns default for stock placeholder`() {
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_DEFAULT, ImeiSerialProbe.classifySerial("0123456789ABCDEF"))
    }

    @Test
    fun `classifySerial returns valid for realistic serial`() {
        assertEquals(ImeiSerialProbe.SERIAL_PATTERN_VALID, ImeiSerialProbe.classifySerial("R28X1234567"))
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_imei_serial`() {
        assertEquals("identity.imei_serial", probe.id)
    }

    @Test
    fun `probe rank is 12`() {
        assertEquals(12, probe.rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx(imei = validImei, serial = "R28X1234567"))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

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
 * Unit tests for CarrierMccMncProbe (identity.carrier_mccmnc, inventory rank 22).
 */
class CarrierMccMncProbeTest {

    private val probe = CarrierMccMncProbe()

    private fun fakeCtx(
        operatorName: String? = "Deutsche Telekom",
        mccMnc: String? = "26201",
        nameThrows: Boolean = false,
        mccMncThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = when (field) {
            TelephonyField.OPERATOR_NAME -> {
                if (nameThrows) throw RuntimeException("simulated TM name")
                operatorName
            }
            TelephonyField.MCC_MNC -> {
                if (mccMncThrows) throw RuntimeException("simulated TM MCC_MNC")
                mccMnc
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

    // ── Clean real carrier ────────────────────────────────────────────────────

    @Test
    fun `Deutsche Telekom DE 26201 — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Verizon US 311480 — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Verizon", mccMnc = "311480"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean carrier — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean carrier — pattern is valid`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_VALID, ev?.value)
    }

    // ── Emulator operator name (score 1.0) ────────────────────────────────────

    @Test
    fun `operator name Android — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Android", mccMnc = "310260"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `operator name Test — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Test", mccMnc = "00101"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `operator name contains Genymotion — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Genymotion Cloud", mccMnc = "310260"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `operator name contains AVD substring — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "My AVD Device", mccMnc = "310260"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `operator name contains Emulator — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Android Emulator", mccMnc = "310260"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `lowercase android — score is 1_0 (case-insensitive)`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "android", mccMnc = "310260"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emulator name — pattern is emulator_operator_name`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Android", mccMnc = "310260"))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_EMULATOR_OPERATOR_NAME, ev?.value)
    }

    // ── Synthetic MCC (score 0.95) ────────────────────────────────────────────

    @Test
    fun `MCC 000 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Carrier Co", mccMnc = "00099"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `MCC 001 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Carrier Co", mccMnc = "00101"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `synthetic MCC — pattern is synthetic_mcc`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Carrier Co", mccMnc = "00099"))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_SYNTHETIC_MCC, ev?.value)
    }

    // ── AVD canonical signature (score 0.95) ──────────────────────────────────

    @Test
    fun `MCC 310 MNC 260 plus Android prefix — score is 1_0 (emu name dominates)`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Android", mccMnc = "310260"))
        // Emulator-name rule (1.0) fires first, ahead of avd_canonical (0.95).
        assertEquals(1.0, result.score)
    }

    @Test
    fun `MCC 310 MNC 260 plus AndroidThings — score is 1_0 (emu name fires on substring)`() = runBlocking {
        // "AndroidThings" — doesn't match exact lowercase "android" but
        // does contain "android"... wait, "android" isn't a SUBSTRING marker
        // (only "genymotion", "avd", "emulator" are). So "AndroidThings"
        // lowercased "androidthings" — exact-match set is {"android", "test"},
        // contains-check is {"genymotion","avd","emulator"}. None match
        // "androidthings". So the cascade hits avd_canonical 0.95.
        val result = probe.run(fakeCtx(operatorName = "AndroidThings", mccMnc = "310260"))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_AVD_CANONICAL, ev?.value)
    }

    // ── Unknown MCC (score 0.85) ──────────────────────────────────────────────

    @Test
    fun `MCC 999 not in known list — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Mystery Carrier", mccMnc = "99999"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `MCC 800 not in known list — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Some Carrier", mccMnc = "80001"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `unknown MCC — pattern is unknown_mcc`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Mystery Carrier", mccMnc = "99999"))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_UNKNOWN_MCC, ev?.value)
    }

    // ── Empty operator name with valid MCC (score 0.75) ──────────────────────

    @Test
    fun `empty operator name with valid MCC — score is 0_75`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "", mccMnc = "26201"))
        assertEquals(0.75, result.score)
    }

    @Test
    fun `null operator name with valid MCC — score is 0_75`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = null, mccMnc = "26201"))
        assertEquals(0.75, result.score)
    }

    @Test
    fun `empty name pattern — empty_operator_name`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "", mccMnc = "26201"))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_EMPTY_OPERATOR_NAME, ev?.value)
    }

    // ── Both null / accessor-throws ──────────────────────────────────────────

    @Test
    fun `both fields null — score is 0_0 (no observation)`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = null, mccMnc = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both fields null — confidence is 0_30 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = null, mccMnc = null))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `both fields null — pattern is null`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = null, mccMnc = null))
        val ev = result.evidence.find { it.key == "telephony.pattern" }
        assertEquals(CarrierMccMncProbe.PATTERN_NULL, ev?.value)
    }

    @Test
    fun `name accessor throws — falls through to null path`() = runBlocking {
        val result = probe.run(fakeCtx(nameThrows = true, mccMnc = "26201"))
        assertFalse(result.failed)
        // operatorName null, mccMnc=26201 (DE/Vodafone). Empty name + valid MCC → 0.75.
        assertEquals(0.75, result.score)
    }

    @Test
    fun `mcc_mnc accessor throws — falls through to null path`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMncThrows = true))
        assertFalse(result.failed)
        // Name valid, no MCC → no rule fires → 0.0.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both accessors throw — score 0, confidence 0_30`() = runBlocking {
        val result = probe.run(fakeCtx(nameThrows = true, mccMncThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    // ── Confidence tiers ──────────────────────────────────────────────────────

    @Test
    fun `both fields populated — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only operator name populated — confidence is 0_60`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = null))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only MCC populated — confidence is 0_60`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = null, mccMnc = "26201"))
        assertEquals(0.60, result.confidence)
    }

    // ── MCC+MNC parser ────────────────────────────────────────────────────────

    @Test
    fun `splitMccMnc parses 5-digit input`() {
        val (mcc, mnc) = CarrierMccMncProbe.splitMccMnc("26201")
        assertEquals("262", mcc)
        assertEquals("01", mnc)
    }

    @Test
    fun `splitMccMnc parses 6-digit input`() {
        val (mcc, mnc) = CarrierMccMncProbe.splitMccMnc("310260")
        assertEquals("310", mcc)
        assertEquals("260", mnc)
    }

    @Test
    fun `splitMccMnc returns empty pair on null input`() {
        assertEquals("" to "", CarrierMccMncProbe.splitMccMnc(null))
    }

    @Test
    fun `splitMccMnc returns empty pair on 4-digit input`() {
        assertEquals("" to "", CarrierMccMncProbe.splitMccMnc("2620"))
    }

    @Test
    fun `splitMccMnc returns empty pair on non-digit input`() {
        assertEquals("" to "", CarrierMccMncProbe.splitMccMnc("XXXyy"))
    }

    @Test
    fun `splitMccMnc returns empty pair on 7-digit input`() {
        assertEquals("" to "", CarrierMccMncProbe.splitMccMnc("3102601"))
    }

    // ── Emulator operator name classifier ────────────────────────────────────

    @Test
    fun `isEmulatorOperatorName accepts exact Android`() {
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("Android"))
    }

    @Test
    fun `isEmulatorOperatorName accepts case-insensitive variants`() {
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("ANDROID"))
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("test"))
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("TEST"))
    }

    @Test
    fun `isEmulatorOperatorName accepts substring matches`() {
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("Genymotion Cloud"))
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("Pixel AVD"))
        assertTrue(CarrierMccMncProbe.isEmulatorOperatorName("Android Emulator"))
    }

    @Test
    fun `isEmulatorOperatorName rejects real carrier names`() {
        assertFalse(CarrierMccMncProbe.isEmulatorOperatorName("Deutsche Telekom"))
        assertFalse(CarrierMccMncProbe.isEmulatorOperatorName("Verizon"))
        assertFalse(CarrierMccMncProbe.isEmulatorOperatorName("Vodafone"))
    }

    @Test
    fun `isEmulatorOperatorName rejects null and empty`() {
        assertFalse(CarrierMccMncProbe.isEmulatorOperatorName(null))
        assertFalse(CarrierMccMncProbe.isEmulatorOperatorName(""))
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        // Spec listed 7 + 1 added pattern row (mirrors rank-17/21 convention).
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "telephony.network_operator",
                "telephony.network_operator_name",
                "telephony.sim_operator",
                "telephony.sim_operator_name",
                "telephony.mcc",
                "telephony.mnc",
                "telephony.is_known_emulator_carrier",
                "telephony.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `sim_operator evidence mirrors network_operator (single-source gap)`() = runBlocking {
        // Regression guard for the documented gap: TelephonyField has no
        // separate SIM_OPERATOR variant, so the SIM rows mirror network rows.
        // When SIM_OPERATOR lands, this test changes — the placeholder marks
        // the work-to-do.
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        val netOp = result.evidence.find { it.key == "telephony.network_operator" }?.value
        val simOp = result.evidence.find { it.key == "telephony.sim_operator" }?.value
        val netName = result.evidence.find { it.key == "telephony.network_operator_name" }?.value
        val simName = result.evidence.find { it.key == "telephony.sim_operator_name" }?.value
        assertEquals(netOp, simOp)
        assertEquals(netName, simName)
    }

    @Test
    fun `mcc evidence is split correctly from MCC+MNC`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Verizon", mccMnc = "311480"))
        val mcc = result.evidence.find { it.key == "telephony.mcc" }?.value
        val mnc = result.evidence.find { it.key == "telephony.mnc" }?.value
        assertEquals("311", mcc)
        assertEquals("480", mnc)
    }

    @Test
    fun `null MCC_MNC — mcc and mnc evidence read unknown`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = null))
        val mcc = result.evidence.find { it.key == "telephony.mcc" }?.value
        val mnc = result.evidence.find { it.key == "telephony.mnc" }?.value
        assertEquals("unknown", mcc)
        assertEquals("unknown", mnc)
    }

    @Test
    fun `is_known_emulator_carrier evidence is true when emulator name fires`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Android", mccMnc = "310260"))
        val ev = result.evidence.find { it.key == "telephony.is_known_emulator_carrier" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_known_emulator_carrier evidence is false on real carrier`() = runBlocking {
        val result = probe.run(fakeCtx(operatorName = "Deutsche Telekom", mccMnc = "26201"))
        val ev = result.evidence.find { it.key == "telephony.is_known_emulator_carrier" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read TelephonyManager.networkOperator + networkOperatorName + " +
                "simOperator + simOperatorName; detect known emulator-default " +
                "carrier names and MCC/MNC values; cross-check SIM vs network " +
                "for roaming consistency",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_carrier_mccmnc`() {
        assertEquals("identity.carrier_mccmnc", probe.id)
    }

    @Test
    fun `probe rank is 22`() {
        assertEquals(22, probe.rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-22 severity=high.
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

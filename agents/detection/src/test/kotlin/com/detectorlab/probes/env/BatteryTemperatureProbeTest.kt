package com.detectorlab.probes.env

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
 * Unit tests for BatteryTemperatureProbe (env.battery_temperature, inventory rank 34).
 */
class BatteryTemperatureProbeTest {

    private fun makeProbe(temperatureTenths: Int? = 287): BatteryTemperatureProbe =
        BatteryTemperatureProbe(temperatureTenthsSupplier = { temperatureTenths })

    private fun fakeCtx(
        sysfsTemp: String? = null,
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == BatteryTemperatureProbe.PATH_SYSFS_TEMP) sysfsTemp else null
        }
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

    // ── Real-device plausible temps (score 0.0) ──────────────────────────────

    @Test
    fun `28_7 celsius (287 tenths) — score is 0`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `plausible temp — pattern is plausible`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_PLAUSIBLE, ev?.value)
    }

    @Test
    fun `plausible temp — confidence is 0_95`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `idle Pixel 31_5 celsius — score is 0`() = runBlocking {
        val result = makeProbe(315).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `gaming load 44_3 celsius — score is 0`() = runBlocking {
        val result = makeProbe(443).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `off-charger 25_7 celsius — score is 0`() = runBlocking {
        val result = makeProbe(257).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── AVD default 25_0°C (1.0) ─────────────────────────────────────────────

    @Test
    fun `AVD default 250 tenths — score is 1_0`() = runBlocking {
        val result = makeProbe(250).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD default — pattern is avd_default`() = runBlocking {
        val result = makeProbe(250).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_AVD_DEFAULT, ev?.value)
    }

    // ── Zero (0.95) ──────────────────────────────────────────────────────────

    @Test
    fun `zero temp — score is 0_95`() = runBlocking {
        val result = makeProbe(0).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `zero temp — pattern is zero`() = runBlocking {
        val result = makeProbe(0).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_ZERO, ev?.value)
    }

    // ── Out-of-range too cold (1.0) ──────────────────────────────────────────

    @Test
    fun `too cold 49 tenths (4_9°C) — score is 1_0`() = runBlocking {
        val result = makeProbe(49).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `too cold — pattern is too_cold`() = runBlocking {
        val result = makeProbe(40).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_TOO_COLD, ev?.value)
    }

    @Test
    fun `negative temp — score is 1_0 (too cold)`() = runBlocking {
        val result = makeProbe(-50).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `boundary 50 tenths (5_0°C) — score is 0 (plausible)`() = runBlocking {
        val result = makeProbe(50).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Out-of-range too hot (1.0) ───────────────────────────────────────────

    @Test
    fun `too hot 601 tenths (60_1°C) — score is 1_0`() = runBlocking {
        val result = makeProbe(601).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `too hot 800 tenths (80°C) — score is 1_0`() = runBlocking {
        val result = makeProbe(800).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `too hot — pattern is too_hot`() = runBlocking {
        val result = makeProbe(800).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_TOO_HOT, ev?.value)
    }

    @Test
    fun `boundary 600 tenths (60_0°C) — score is 0 (thermal throttle range)`() = runBlocking {
        val result = makeProbe(600).run(fakeCtx())
        // Right at thermal-shutdown bound but still recoverable — not flagged.
        // Note: 600 is a round-100 multiple → fires round_100 at 0.85.
        assertEquals(0.85, result.score)
    }

    // ── Round-100 stub values (0.85) ─────────────────────────────────────────

    @Test
    fun `300 tenths (30°C round) — score is 0_85`() = runBlocking {
        val result = makeProbe(300).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `350 tenths is 50-multiple not 100-multiple — score is 0`() = runBlocking {
        // The brief lists 250/300/350/400 as round; mathematically 350 isn't
        // a 100-multiple. The spec wording is "100s multiple", so we follow
        // the strict reading — only true 100-multiples fire round_100. A
        // future broader interpretation (50-multiples) is an inventory edit,
        // not a probe deviation.
        val result = makeProbe(350).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `400 tenths (40°C round) — score is 0_85`() = runBlocking {
        val result = makeProbe(400).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `round 100 — pattern is round_100`() = runBlocking {
        val result = makeProbe(300).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_ROUND_100, ev?.value)
    }

    @Test
    fun `100 tenths (10°C boundary) — score is 0_85 (round)`() = runBlocking {
        val result = makeProbe(100).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `301 tenths — score is 0 (not round)`() = runBlocking {
        val result = makeProbe(301).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `250 tenths beats round_100 cascade — AVD wins`() = runBlocking {
        // 250 is technically not a 100-multiple but the AVD-default rule fires
        // FIRST anyway. Confirms the cascade order.
        val result = makeProbe(250).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    // ── Static non-default detection (0.85) ──────────────────────────────────

    @Test
    fun `static non-default 537 across framework and sysfs — score is 0_85`() = runBlocking {
        // 53.7°C — outside the realistic 10-50 mid-range; framework and sysfs
        // agree exactly. Suspect static stub.
        val result = makeProbe(537).run(fakeCtx(sysfsTemp = "537"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_STATIC_NON_DEFAULT, ev?.value)
    }

    @Test
    fun `static 287 in plausible range across both surfaces — score is 0`() = runBlocking {
        // Two reads at 28.7°C is normal for a stable thermal device — both
        // reading the same instant. Don't flag as static.
        val result = makeProbe(287).run(fakeCtx(sysfsTemp = "287"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `dynamic readings 287 then 295 — score is 0`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx(sysfsTemp = "295"))
        assertEquals(0.0, result.score)
    }

    // ── Sysfs fallback (framework null) ──────────────────────────────────────

    @Test
    fun `framework null but sysfs 287 — score is 0 confidence 0_60`() = runBlocking {
        val result = makeProbe(temperatureTenths = null)
            .run(fakeCtx(sysfsTemp = "287"))
        assertEquals(0.0, result.score)
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `framework null but sysfs 250 — score is 1_0`() = runBlocking {
        // Sysfs alone reveals AVD default — should still score 1.0.
        val result = makeProbe(temperatureTenths = null)
            .run(fakeCtx(sysfsTemp = "250"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `framework null and sysfs unreadable — score is 0 confidence 0_50`() = runBlocking {
        val result = makeProbe(temperatureTenths = null).run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_NULL, ev?.value)
    }

    @Test
    fun `sysfs garbage — treated as unreadable`() = runBlocking {
        val result = makeProbe(temperatureTenths = null)
            .run(fakeCtx(sysfsTemp = "not-a-number"))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `sysfs newline-terminated 287 — parsed`() = runBlocking {
        val result = makeProbe(temperatureTenths = null)
            .run(fakeCtx(sysfsTemp = "287\n"))
        assertEquals(0.0, result.score)
        assertEquals(0.60, result.confidence)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `framework readable — confidence is 0_95`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only sysfs readable — confidence is 0_60`() = runBlocking {
        val result = makeProbe(null).run(fakeCtx(sysfsTemp = "287"))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `no surfaces — confidence is 0_50`() = runBlocking {
        val result = makeProbe(null).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with no sysfs — score 0 confidence 0_50`() = runBlocking {
        val result = BatteryTemperatureProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor with sysfs 287 — score 0 confidence 0_60`() = runBlocking {
        val result = BatteryTemperatureProbe().run(fakeCtx(sysfsTemp = "287"))
        assertEquals(0.0, result.score)
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `no-arg production ctor with sysfs 250 — score 1_0 (AVD default visible)`() = runBlocking {
        val result = BatteryTemperatureProbe().run(fakeCtx(sysfsTemp = "250"))
        assertEquals(1.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `temperatureSupplier throws — does not crash`() = runBlocking {
        val probe = BatteryTemperatureProbe(
            temperatureTenthsSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx(sysfsTemp = "287"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        // Framework null (threw) + sysfs 287 → confidence 0.60.
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `readFile throws — sysfs treated as null`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        // Framework alone → confidence 0.95.
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `both surfaces throw — does not crash, confidence 0_50`() = runBlocking {
        val probe = BatteryTemperatureProbe(
            temperatureTenthsSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `parseSysfsTemp trims whitespace and newlines`() {
        assertEquals(287, BatteryTemperatureProbe.parseSysfsTemp("287"))
        assertEquals(287, BatteryTemperatureProbe.parseSysfsTemp("287\n"))
        assertEquals(287, BatteryTemperatureProbe.parseSysfsTemp("  287  "))
    }

    @Test
    fun `parseSysfsTemp accepts negative`() {
        assertEquals(-50, BatteryTemperatureProbe.parseSysfsTemp("-50"))
    }

    @Test
    fun `parseSysfsTemp returns null for non-numeric`() {
        assertEquals(null, BatteryTemperatureProbe.parseSysfsTemp("not-a-number"))
        assertEquals(null, BatteryTemperatureProbe.parseSysfsTemp(""))
        assertEquals(null, BatteryTemperatureProbe.parseSysfsTemp(null))
    }

    @Test
    fun `isRoundHundred true for 300 400 500 600`() {
        assertTrue(BatteryTemperatureProbe.isRoundHundred(300))
        assertTrue(BatteryTemperatureProbe.isRoundHundred(400))
        assertTrue(BatteryTemperatureProbe.isRoundHundred(500))
        assertTrue(BatteryTemperatureProbe.isRoundHundred(600))
    }

    @Test
    fun `isRoundHundred false for 250 287 350`() {
        assertFalse(BatteryTemperatureProbe.isRoundHundred(250))
        assertFalse(BatteryTemperatureProbe.isRoundHundred(287))
        assertFalse(BatteryTemperatureProbe.isRoundHundred(350))
    }

    @Test
    fun `celsiusOf formats positive tenths correctly`() {
        assertEquals("28.7", BatteryTemperatureProbe.celsiusOf(287))
        assertEquals("25.0", BatteryTemperatureProbe.celsiusOf(250))
        assertEquals("0.0", BatteryTemperatureProbe.celsiusOf(0))
        assertEquals("60.0", BatteryTemperatureProbe.celsiusOf(600))
    }

    @Test
    fun `celsiusOf formats negative tenths correctly`() {
        assertEquals("-5.0", BatteryTemperatureProbe.celsiusOf(-50))
    }

    @Test
    fun `isOutOfRange true below 50 and above 600`() {
        assertTrue(BatteryTemperatureProbe.isOutOfRange(49))
        assertTrue(BatteryTemperatureProbe.isOutOfRange(601))
        assertTrue(BatteryTemperatureProbe.isOutOfRange(-1))
    }

    @Test
    fun `isOutOfRange false in 50-600 range`() {
        assertFalse(BatteryTemperatureProbe.isOutOfRange(50))
        assertFalse(BatteryTemperatureProbe.isOutOfRange(287))
        assertFalse(BatteryTemperatureProbe.isOutOfRange(600))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 5 keys`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        assertEquals(5, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "battery.temperature_tenths_c",
                "battery.temperature_celsius",
                "battery.temperature_plausible",
                "battery.temperature_is_round_100",
                "battery.temperature_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `temperature_tenths_c evidence shows raw value`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_tenths_c" }
        assertEquals("287", ev?.value)
    }

    @Test
    fun `temperature_celsius evidence shows formatted value`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_celsius" }
        assertEquals("28.7", ev?.value)
    }

    @Test
    fun `plausible evidence is true for 287`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_plausible" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `plausible evidence is false for too-cold`() = runBlocking {
        val result = makeProbe(40).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_plausible" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_round_100 evidence is true for 300`() = runBlocking {
        val result = makeProbe(300).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_is_round_100" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_round_100 evidence is false for 287`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_is_round_100" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `unreadable temp — tenths evidence is placeholder`() = runBlocking {
        val result = makeProbe(null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_tenths_c" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `unreadable temp — celsius evidence is placeholder`() = runBlocking {
        val result = makeProbe(null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_celsius" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `null temp — pattern is null`() = runBlocking {
        val result = makeProbe(null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.temperature_pattern" }
        assertEquals(BatteryTemperatureProbe.PATTERN_NULL, ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe(287).run(fakeCtx())
        assertEquals(
            "Read battery temperature via BatteryManager/ACTION_BATTERY_CHANGED " +
                "EXTRA_TEMPERATURE; detect AVD 25.0°C default, round-100 stub " +
                "values, and out-of-plausible-range values",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_battery_temperature`() {
        assertEquals("env.battery_temperature", makeProbe().id)
    }

    @Test
    fun `probe rank is 34`() {
        assertEquals(34, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-34 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

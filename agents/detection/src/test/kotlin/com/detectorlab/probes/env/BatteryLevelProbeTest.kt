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
 * Unit tests for BatteryLevelProbe (env.battery_level, inventory rank 33).
 */
class BatteryLevelProbeTest {

    private fun makeProbe(
        capacity: Int? = 73,
        stickyLevelScale: Pair<Int, Int>? = 73 to 100,
        chargingState: String? = "DISCHARGING",
    ): BatteryLevelProbe = BatteryLevelProbe(
        capacitySupplier = { capacity },
        stickyLevelScaleSupplier = { stickyLevelScale },
        chargingStateSupplier = { chargingState },
    )

    /** A capacity supplier that returns different values on successive calls. */
    private fun makeDrainingProbe(
        firstSample: Int,
        secondSample: Int,
        stickyLevelScale: Pair<Int, Int>? = firstSample to 100,
        chargingState: String? = "DISCHARGING",
    ): BatteryLevelProbe {
        var call = 0
        return BatteryLevelProbe(
            capacitySupplier = {
                call++
                if (call == 1) firstSample else secondSample
            },
            stickyLevelScaleSupplier = { stickyLevelScale },
            chargingStateSupplier = { chargingState },
        )
    }

    private fun fakeCtx(
        sysfsCapacity: String? = null,
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == BatteryLevelProbe.PATH_SYSFS_CAPACITY) sysfsCapacity else null
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

    // ── Real device clean (score 0.0) ────────────────────────────────────────

    @Test
    fun `real device 73 percent discharging — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95 (primary surface readable)`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `real device 1 percent discharging — score is 0`() = runBlocking {
        val result = makeProbe(capacity = 1, stickyLevelScale = 1 to 100).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real device 99 percent charging — score is 0`() = runBlocking {
        val result = makeProbe(
            capacity = 99,
            stickyLevelScale = 99 to 100,
            chargingState = "CHARGING",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Out-of-range level (1.0) ─────────────────────────────────────────────

    @Test
    fun `level negative — score is 1_0`() = runBlocking {
        val result = makeProbe(capacity = -5, stickyLevelScale = -5 to 100).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `level above 100 — score is 1_0`() = runBlocking {
        val result = makeProbe(capacity = 150, stickyLevelScale = 150 to 100).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `out of range — pattern is out_of_range`() = runBlocking {
        val result = makeProbe(capacity = 200, stickyLevelScale = 200 to 100).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_OUT_OF_RANGE, ev?.value)
    }

    // ── 100% NOT_CHARGING contradiction (1.0) ────────────────────────────────

    @Test
    fun `100 percent NOT_CHARGING — score is 1_0`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "NOT_CHARGING",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `100 percent NOT_CHARGING — pattern is full_not_charging`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "NOT_CHARGING",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_FULL_NOT_CHARGING, ev?.value)
    }

    @Test
    fun `100 percent NOT_CHARGING lowercase — recognized`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "not_charging",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    // ── 0% running (0.95) ────────────────────────────────────────────────────

    @Test
    fun `0 percent — score is 0_95`() = runBlocking {
        val result = makeProbe(
            capacity = 0,
            stickyLevelScale = 0 to 100,
            chargingState = "DISCHARGING",
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `0 percent — pattern is zero_running`() = runBlocking {
        val result = makeProbe(
            capacity = 0,
            stickyLevelScale = 0 to 100,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_ZERO_RUNNING, ev?.value)
    }

    // ── 50% AVD default (0.85) ───────────────────────────────────────────────

    @Test
    fun `50 percent AVD default — score is 0_85`() = runBlocking {
        val result = makeProbe(
            capacity = 50,
            stickyLevelScale = 50 to 100,
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `50 percent — pattern is avd_default_50`() = runBlocking {
        val result = makeProbe(capacity = 50, stickyLevelScale = 50 to 100).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_AVD_DEFAULT_50, ev?.value)
    }

    @Test
    fun `49 percent — score is 0 (not exact AVD default)`() = runBlocking {
        val result = makeProbe(capacity = 49, stickyLevelScale = 49 to 100).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `51 percent — score is 0 (not exact AVD default)`() = runBlocking {
        val result = makeProbe(capacity = 51, stickyLevelScale = 51 to 100).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Static-max emulator detection (0.85) ─────────────────────────────────

    @Test
    fun `static 100 percent across two samples discharging — score is 1_0 (NOT_CHARGING wins)`() =
        runBlocking {
            // 100 + NOT_CHARGING fires before static-max in cascade.
            val result = makeDrainingProbe(
                firstSample = 100,
                secondSample = 100,
                chargingState = "NOT_CHARGING",
            ).run(fakeCtx())
            assertEquals(1.0, result.score)
        }

    @Test
    fun `static 100 percent UNKNOWN charging state — score is 0_85 (static_max)`() = runBlocking {
        // With UNKNOWN state, the 100%+NOT_CHARGING rule doesn't fire;
        // static-max takes over. Models the case where the charging-state
        // supplier is silent but two readings agree on 100.
        val result = makeDrainingProbe(
            firstSample = 100,
            secondSample = 100,
            chargingState = "UNKNOWN",
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `static 100 percent — pattern is static_max`() = runBlocking {
        val result = makeDrainingProbe(
            firstSample = 100,
            secondSample = 100,
            chargingState = "UNKNOWN",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_STATIC_MAX, ev?.value)
    }

    @Test
    fun `static 100 percent — is_static_max evidence is true`() = runBlocking {
        val result = makeDrainingProbe(
            firstSample = 100,
            secondSample = 100,
            chargingState = "UNKNOWN",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.is_static_max" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `static 73 percent across two samples — score is 0 (only 100 counts)`() = runBlocking {
        // Two identical reads at 73% is not a strong signal — real device
        // drains slowly. Only the 100-static combination is suspicious.
        val result = makeDrainingProbe(firstSample = 73, secondSample = 73).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `dynamic 73 then 72 percent — score is 0`() = runBlocking {
        val result = makeDrainingProbe(firstSample = 73, secondSample = 72).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── 100% CHARGING / FULL benign (0.5) ────────────────────────────────────

    @Test
    fun `100 percent CHARGING — score is 0_5`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "CHARGING",
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `100 percent FULL — score is 0_5`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "FULL",
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `100 percent CHARGING — pattern is full_benign`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "CHARGING",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_FULL_BENIGN, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `out-of-range plus NOT_CHARGING — out-of-range wins`() = runBlocking {
        val result = makeProbe(
            capacity = 150,
            stickyLevelScale = 150 to 100,
            chargingState = "NOT_CHARGING",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_OUT_OF_RANGE, ev?.value)
    }

    @Test
    fun `100 NOT_CHARGING beats static-max in cascade`() = runBlocking {
        val result = makeDrainingProbe(
            firstSample = 100,
            secondSample = 100,
            chargingState = "NOT_CHARGING",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `0 percent beats AVD-default 50`() = runBlocking {
        // Hypothetical: capacity 0 with sticky-reading 50 — the primary
        // (capacity) wins. We use primaryLevel = capacityRaw1 first.
        val result = makeProbe(
            capacity = 0,
            stickyLevelScale = 50 to 100,
        ).run(fakeCtx())
        // primaryLevel = 0 → zero_running fires.
        assertEquals(0.95, result.score)
    }

    @Test
    fun `50 AVD-default plus FULL state — 50 wins over benign-full`() = runBlocking {
        val result = makeProbe(
            capacity = 50,
            stickyLevelScale = 50 to 100,
            chargingState = "FULL",
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    // ── Capacity supplier fallback chain ─────────────────────────────────────

    @Test
    fun `capacity null but sticky returns 100 NOT_CHARGING — score is 1_0`() = runBlocking {
        // Falls back to sticky as primary level signal.
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = 100 to 100,
            chargingState = "NOT_CHARGING",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `capacity null and sticky null — sysfs reads 100 NOT_CHARGING — score is 1_0`() =
        runBlocking {
            val result = makeProbe(
                capacity = null,
                stickyLevelScale = null,
                chargingState = "NOT_CHARGING",
            ).run(fakeCtx(sysfsCapacity = "100"))
            assertEquals(1.0, result.score)
        }

    @Test
    fun `all supplier null and sysfs unreadable — score is 0 confidence 0_50`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `capacity null but sysfs returns 73 with newline — score is 0`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = "DISCHARGING",
        ).run(fakeCtx(sysfsCapacity = "73\n"))
        assertEquals(0.0, result.score)
        // Sysfs was the only signal → confidence 0.60.
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `sysfs returns garbage — treated as null`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = "DISCHARGING",
        ).run(fakeCtx(sysfsCapacity = "not a number"))
        // No surfaces → confidence 0.50 / score 0.0.
        assertEquals(0.50, result.confidence)
    }

    // ── Sticky percent computation ───────────────────────────────────────────

    @Test
    fun `sticky scale 200 level 100 — computes 50 percent AVD default`() = runBlocking {
        // (100 / 200) * 100 = 50.
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = 100 to 200,
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sticky scale zero — treated as unparseable`() = runBlocking {
        // Real Android sometimes returns scale=0 transiently. Don't divide.
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = 50 to 0,
            chargingState = "DISCHARGING",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sticky scale negative — treated as unparseable`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = 50 to -10,
            chargingState = "DISCHARGING",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Charging-state normalisation ─────────────────────────────────────────

    @Test
    fun `unknown charging-state string — treated as UNKNOWN`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "frobnicating",
        ).run(fakeCtx())
        // 100 + UNKNOWN (unrecognised string → null → UNKNOWN). Two capacity
        // reads both yield 100 → isStaticMax fires (UNKNOWN does NOT count
        // as "actively charging"). Conservative: an emulator with a broken
        // charging-state field still presents as static-max.
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "battery.pattern" }
        assertEquals(BatteryLevelProbe.PATTERN_STATIC_MAX, ev?.value)
    }

    @Test
    fun `null charging-state — treated as UNKNOWN`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = null,
        ).run(fakeCtx())
        // Same as above — null state degrades to UNKNOWN, static-max still
        // fires for capacity=100 across both reads.
        assertEquals(0.85, result.score)
    }

    @Test
    fun `whitespace charging-state — normalized`() = runBlocking {
        val result = makeProbe(
            capacity = 100,
            stickyLevelScale = 100 to 100,
            chargingState = "  charging  ",
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `capacity readable — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only sticky readable — confidence is 0_60`() = runBlocking {
        val result = makeProbe(capacity = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only sysfs readable — confidence is 0_60`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
        ).run(fakeCtx(sysfsCapacity = "73"))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `no surfaces — confidence is 0_50`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = null,
        ).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with no sysfs — score is 0 confidence 0_50`() = runBlocking {
        // BatteryManager accessor gap: production ctor returns null for all
        // suppliers; with no sysfs nothing scores. Conservative default.
        val result = BatteryLevelProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor with sysfs 100 — score is 0 (no charging-state context)`() =
        runBlocking {
            // primaryLevel=100, chargingState=UNKNOWN (supplier returns null).
            // static-max needs both reads from capacitySupplier (null in prod
            // ctor) → doesn't fire. full_not_charging needs explicit
            // NOT_CHARGING → doesn't fire. Falls to clean.
            val result = BatteryLevelProbe().run(fakeCtx(sysfsCapacity = "100"))
            assertEquals(0.0, result.score)
            assertEquals(0.60, result.confidence)
        }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `capacitySupplier throws — does not crash`() = runBlocking {
        val probe = BatteryLevelProbe(
            capacitySupplier = { throw RuntimeException("simulated") },
            stickyLevelScaleSupplier = { 73 to 100 },
            chargingStateSupplier = { "DISCHARGING" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `stickyLevelScaleSupplier throws — does not crash`() = runBlocking {
        val probe = BatteryLevelProbe(
            capacitySupplier = { 73 },
            stickyLevelScaleSupplier = { throw RuntimeException("simulated") },
            chargingStateSupplier = { "DISCHARGING" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `chargingStateSupplier throws — does not crash`() = runBlocking {
        val probe = BatteryLevelProbe(
            capacitySupplier = { 100 },
            stickyLevelScaleSupplier = { 100 to 100 },
            chargingStateSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // 100 + UNKNOWN (throw treated as null → UNKNOWN). Two capacity reads
        // both yield 100 → isStaticMax fires. Same conservative behaviour
        // as the unknown-string case.
        assertEquals(0.85, result.score)
    }

    @Test
    fun `readFile throws — sysfs treated as null`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = "DISCHARGING",
        ).run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.50, result.confidence)
    }

    // ── normalizeChargingState helper unit tests ─────────────────────────────

    @Test
    fun `normalizeChargingState uppercases valid values`() {
        assertEquals("CHARGING", BatteryLevelProbe.normalizeChargingState("charging"))
        assertEquals(
            "DISCHARGING", BatteryLevelProbe.normalizeChargingState("Discharging"),
        )
        assertEquals(
            "NOT_CHARGING", BatteryLevelProbe.normalizeChargingState(" not_charging "),
        )
        assertEquals("FULL", BatteryLevelProbe.normalizeChargingState("full"))
        assertEquals("UNKNOWN", BatteryLevelProbe.normalizeChargingState("unknown"))
    }

    @Test
    fun `normalizeChargingState returns null for unknown values`() {
        assertEquals(null, BatteryLevelProbe.normalizeChargingState("frobnicating"))
    }

    @Test
    fun `normalizeChargingState returns null for null`() {
        assertEquals(null, BatteryLevelProbe.normalizeChargingState(null))
    }

    @Test
    fun `validCapacityInRange accepts 0-100`() {
        assertEquals(0, BatteryLevelProbe.validCapacityInRange(0))
        assertEquals(50, BatteryLevelProbe.validCapacityInRange(50))
        assertEquals(100, BatteryLevelProbe.validCapacityInRange(100))
    }

    @Test
    fun `validCapacityInRange rejects out-of-range`() {
        assertEquals(null, BatteryLevelProbe.validCapacityInRange(-1))
        assertEquals(null, BatteryLevelProbe.validCapacityInRange(101))
        assertEquals(null, BatteryLevelProbe.validCapacityInRange(null))
    }

    @Test
    fun `parseSysfsCapacity trims whitespace and newlines`() {
        assertEquals(73, BatteryLevelProbe.parseSysfsCapacity("73"))
        assertEquals(73, BatteryLevelProbe.parseSysfsCapacity("73\n"))
        assertEquals(73, BatteryLevelProbe.parseSysfsCapacity("  73  "))
    }

    @Test
    fun `parseSysfsCapacity returns null for non-numeric`() {
        assertEquals(null, BatteryLevelProbe.parseSysfsCapacity("not a number"))
        assertEquals(null, BatteryLevelProbe.parseSysfsCapacity(""))
        assertEquals(null, BatteryLevelProbe.parseSysfsCapacity(null))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "battery.level",
                "battery.scale",
                "battery.percent_computed",
                "battery.charging_state",
                "battery.is_static_max",
                "battery.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `clean — is_static_max is false`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.is_static_max" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `level evidence shows primary level`() = runBlocking {
        val result = makeProbe(capacity = 73).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.level" }
        assertEquals("73", ev?.value)
    }

    @Test
    fun `scale evidence reflects sticky scale`() = runBlocking {
        val result = makeProbe(stickyLevelScale = 73 to 100).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.scale" }
        assertEquals("100", ev?.value)
    }

    @Test
    fun `scale evidence unreadable when sticky null`() = runBlocking {
        val result = makeProbe(stickyLevelScale = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.scale" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `charging_state evidence shows normalized state`() = runBlocking {
        val result = makeProbe(chargingState = "discharging").run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state" }
        assertEquals("DISCHARGING", ev?.value)
    }

    @Test
    fun `null charging_state evidence shows UNKNOWN`() = runBlocking {
        val result = makeProbe(chargingState = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state" }
        assertEquals("UNKNOWN", ev?.value)
    }

    @Test
    fun `level unreadable shows placeholder`() = runBlocking {
        val result = makeProbe(
            capacity = null,
            stickyLevelScale = null,
            chargingState = "DISCHARGING",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.level" }
        assertEquals("<unreadable>", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read BatteryManager BATTERY_PROPERTY_CAPACITY + ACTION_BATTERY_CHANGED " +
                "level/scale + charging state; detect 100%+NOT_CHARGING contradictions " +
                "and AVD-default 50% values",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_battery_level`() {
        assertEquals("env.battery_level", makeProbe().id)
    }

    @Test
    fun `probe rank is 33`() {
        assertEquals(33, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-33 severity=medium.
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

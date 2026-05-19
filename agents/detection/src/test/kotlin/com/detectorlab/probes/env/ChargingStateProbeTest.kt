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
 * Unit tests for ChargingStateProbe (env.charging_state, inventory rank 35).
 */
class ChargingStateProbeTest {

    private fun makeProbe(
        status: Int? = ChargingStateProbe.STATUS_DISCHARGING,
        plugged: Int? = ChargingStateProbe.PLUGGED_UNPLUGGED,
        level: Int? = 73,
    ): ChargingStateProbe = ChargingStateProbe(
        statusSupplier = { status },
        pluggedSupplier = { plugged },
        levelSupplier = { level },
    )

    private fun fakeCtx(): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
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

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `discharging plus unplugged at 73 percent — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `charging plus AC at 73 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `charging plus USB at 50 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_USB,
            level = 50,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `charging plus WIRELESS at 80 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_WIRELESS,
            level = 80,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `charging plus DOCK at 90 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_DOCK,
            level = 90,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `full plus plugged at 100 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 100,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state_pattern" }
        assertEquals(ChargingStateProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── CHARGING + unplugged contradiction (1.0) ─────────────────────────────

    @Test
    fun `charging plus unplugged — score is 1_0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_UNPLUGGED,
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `charging plus unplugged — pattern is charging_unplugged`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_UNPLUGGED,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state_pattern" }
        assertEquals(ChargingStateProbe.PATTERN_CHARGING_UNPLUGGED, ev?.value)
    }

    @Test
    fun `charging plus unplugged — consistent evidence is false`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_UNPLUGGED,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.status_plugged_consistent" }
        assertEquals("false", ev?.value)
    }

    // ── FULL + level < 100 contradiction (0.95) ──────────────────────────────

    @Test
    fun `FULL plus 50 percent — score is 0_95`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 50,
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `FULL plus 99 percent — score is 0_95`() = runBlocking {
        // Off-by-one boundary — strictly < 100 fires.
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 99,
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `FULL plus 100 percent — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 100,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `FULL plus null level — score is 0 (rule cannot fire)`() = runBlocking {
        // No level signal → don't claim contradiction.
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `FULL plus 50 percent — pattern is full_below_100`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_FULL,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 50,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state_pattern" }
        assertEquals(ChargingStateProbe.PATTERN_FULL_BELOW_100, ev?.value)
    }

    // ── Invalid plug enum (0.85) ─────────────────────────────────────────────

    @Test
    fun `plugged value 3 — score is 0_85`() = runBlocking {
        val result = makeProbe(plugged = 3).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `plugged value 99 — score is 0_85`() = runBlocking {
        val result = makeProbe(plugged = 99).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `plugged value -1 — score is 0_85`() = runBlocking {
        val result = makeProbe(plugged = -1).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `invalid plugged — pattern is invalid_plugged`() = runBlocking {
        val result = makeProbe(plugged = 99).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state_pattern" }
        assertEquals(ChargingStateProbe.PATTERN_INVALID_PLUGGED, ev?.value)
    }

    @Test
    fun `invalid plugged — plugged_name evidence is invalid`() = runBlocking {
        val result = makeProbe(plugged = 99).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.plugged_name" }
        assertEquals(ChargingStateProbe.PLUGGED_NAME_INVALID, ev?.value)
    }

    @Test
    fun `plugged 0 (unplugged) — score is 0 (valid)`() = runBlocking {
        val result = makeProbe(plugged = 0).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `plugged 8 (DOCK) — score is 0 (valid)`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = 8,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── NOT_CHARGING plugged + level<100 thermal throttle (0.70) ─────────────

    @Test
    fun `NOT_CHARGING plus AC plus 80 percent — score is 0_70`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 80,
        ).run(fakeCtx())
        assertEquals(0.70, result.score)
    }

    @Test
    fun `NOT_CHARGING plus USB plus 50 percent — score is 0_70`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_USB,
            level = 50,
        ).run(fakeCtx())
        assertEquals(0.70, result.score)
    }

    @Test
    fun `NOT_CHARGING plus unplugged — score is 0 (rule doesn't fire)`() = runBlocking {
        // Real Android sometimes reports NOT_CHARGING when unplugged (transient
        // state); only the plugged+below-100 combination is suspicious.
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_UNPLUGGED,
            level = 80,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `NOT_CHARGING plus AC plus 100 percent — score is 0 (topped off)`() = runBlocking {
        // A topped-off device can be NOT_CHARGING + plugged + level=100
        // (battery-protection logic on some OEMs holds at 100).
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 100,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `NOT_CHARGING plus AC plus null level — score is 0 (rule cannot fire)`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `NOT_CHARGING plugged 80 percent — pattern is not_charging_plugged`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_NOT_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
            level = 80,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.charging_state_pattern" }
        assertEquals(ChargingStateProbe.PATTERN_NOT_CHARGING_PLUGGED, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `CHARGING + unplugged + invalid would-be plugged — CHARGING contradiction wins`() =
        runBlocking {
            // Set plugged=0 (valid unplugged) but status=CHARGING. Fires
            // charging_unplugged at 1.0 before invalid-plugged would.
            val result = makeProbe(
                status = ChargingStateProbe.STATUS_CHARGING,
                plugged = 0,
            ).run(fakeCtx())
            assertEquals(1.0, result.score)
        }

    @Test
    fun `FULL plus 50 plus invalid plugged — FULL contradiction wins over invalid`() =
        runBlocking {
            // FULL + level=50 fires 0.95 before invalid_plugged at 0.85.
            val result = makeProbe(
                status = ChargingStateProbe.STATUS_FULL,
                plugged = 99,
                level = 50,
            ).run(fakeCtx())
            assertEquals(0.95, result.score)
        }

    @Test
    fun `invalid plugged with NOT_CHARGING below 100 — invalid wins over not_charging_plugged`() =
        runBlocking {
            // invalid_plugged (0.85) fires before not_charging_plugged (0.70).
            val result = makeProbe(
                status = ChargingStateProbe.STATUS_NOT_CHARGING,
                plugged = 99,
                level = 50,
            ).run(fakeCtx())
            assertEquals(0.85, result.score)
        }

    // ── Discharging variations (clean) ───────────────────────────────────────

    @Test
    fun `DISCHARGING plus plugged AC — score is 0 (transient, not flagged)`() = runBlocking {
        // Real Android can briefly report DISCHARGING while plugged in
        // (initial cable-plug latency). Defensively don't false-positive.
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_DISCHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `UNKNOWN status — score is 0`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_UNKNOWN,
            plugged = ChargingStateProbe.PLUGGED_AC,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `status invalid value 99 — score is 0 (don't classify)`() = runBlocking {
        // Cascade doesn't fire on invalid status alone — only invalid plugged.
        // status invalid degrades to status_name=invalid but no rule fires.
        val result = makeProbe(
            status = 99,
            plugged = ChargingStateProbe.PLUGGED_AC,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both status and plugged readable — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only status readable — confidence 0_60`() = runBlocking {
        val result = makeProbe(plugged = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only plugged readable — confidence 0_60`() = runBlocking {
        val result = makeProbe(status = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `neither status nor plugged readable — confidence 0_30`() = runBlocking {
        val result = makeProbe(status = null, plugged = null, level = null).run(fakeCtx())
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `neither readable — score is 0 (cannot detect)`() = runBlocking {
        val result = makeProbe(status = null, plugged = null, level = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_30`() = runBlocking {
        val result = ChargingStateProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `statusSupplier throws — does not crash`() = runBlocking {
        val probe = ChargingStateProbe(
            statusSupplier = { throw RuntimeException("simulated") },
            pluggedSupplier = { ChargingStateProbe.PLUGGED_UNPLUGGED },
            levelSupplier = { 73 },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `pluggedSupplier throws — does not crash`() = runBlocking {
        val probe = ChargingStateProbe(
            statusSupplier = { ChargingStateProbe.STATUS_DISCHARGING },
            pluggedSupplier = { throw RuntimeException("simulated") },
            levelSupplier = { 73 },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `levelSupplier throws — does not crash`() = runBlocking {
        val probe = ChargingStateProbe(
            statusSupplier = { ChargingStateProbe.STATUS_FULL },
            pluggedSupplier = { ChargingStateProbe.PLUGGED_AC },
            levelSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Level treated as null → FULL+null doesn't fire → clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all three suppliers throw — does not crash`() = runBlocking {
        val probe = ChargingStateProbe(
            statusSupplier = { throw RuntimeException("a") },
            pluggedSupplier = { throw RuntimeException("b") },
            levelSupplier = { throw RuntimeException("c") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.30, result.confidence)
    }

    // ── statusName helper ────────────────────────────────────────────────────

    @Test
    fun `statusName maps all known constants`() {
        assertEquals(
            ChargingStateProbe.STATUS_NAME_UNKNOWN,
            ChargingStateProbe.statusName(ChargingStateProbe.STATUS_UNKNOWN),
        )
        assertEquals(
            ChargingStateProbe.STATUS_NAME_CHARGING,
            ChargingStateProbe.statusName(ChargingStateProbe.STATUS_CHARGING),
        )
        assertEquals(
            ChargingStateProbe.STATUS_NAME_DISCHARGING,
            ChargingStateProbe.statusName(ChargingStateProbe.STATUS_DISCHARGING),
        )
        assertEquals(
            ChargingStateProbe.STATUS_NAME_NOT_CHARGING,
            ChargingStateProbe.statusName(ChargingStateProbe.STATUS_NOT_CHARGING),
        )
        assertEquals(
            ChargingStateProbe.STATUS_NAME_FULL,
            ChargingStateProbe.statusName(ChargingStateProbe.STATUS_FULL),
        )
    }

    @Test
    fun `statusName returns null for null`() {
        assertEquals(ChargingStateProbe.STATUS_NAME_NULL, ChargingStateProbe.statusName(null))
    }

    @Test
    fun `statusName returns invalid for unknown int`() {
        assertEquals(ChargingStateProbe.STATUS_NAME_INVALID, ChargingStateProbe.statusName(99))
    }

    // ── pluggedName helper ───────────────────────────────────────────────────

    @Test
    fun `pluggedName maps all known constants`() {
        assertEquals(
            ChargingStateProbe.PLUGGED_NAME_UNPLUGGED,
            ChargingStateProbe.pluggedName(0),
        )
        assertEquals(ChargingStateProbe.PLUGGED_NAME_AC, ChargingStateProbe.pluggedName(1))
        assertEquals(ChargingStateProbe.PLUGGED_NAME_USB, ChargingStateProbe.pluggedName(2))
        assertEquals(
            ChargingStateProbe.PLUGGED_NAME_WIRELESS,
            ChargingStateProbe.pluggedName(4),
        )
        assertEquals(ChargingStateProbe.PLUGGED_NAME_DOCK, ChargingStateProbe.pluggedName(8))
    }

    @Test
    fun `pluggedName returns null for null`() {
        assertEquals(ChargingStateProbe.PLUGGED_NAME_NULL, ChargingStateProbe.pluggedName(null))
    }

    @Test
    fun `pluggedName returns invalid for 3 99 -1`() {
        assertEquals(
            ChargingStateProbe.PLUGGED_NAME_INVALID,
            ChargingStateProbe.pluggedName(3),
        )
        assertEquals(
            ChargingStateProbe.PLUGGED_NAME_INVALID,
            ChargingStateProbe.pluggedName(99),
        )
        assertEquals(
            ChargingStateProbe.PLUGGED_NAME_INVALID,
            ChargingStateProbe.pluggedName(-1),
        )
    }

    // ── isConsistent helper ──────────────────────────────────────────────────

    @Test
    fun `isConsistent true for CHARGING plus plugged`() {
        assertTrue(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_CHARGING,
                ChargingStateProbe.PLUGGED_AC,
            ),
        )
    }

    @Test
    fun `isConsistent false for CHARGING plus unplugged`() {
        assertFalse(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_CHARGING,
                ChargingStateProbe.PLUGGED_UNPLUGGED,
            ),
        )
    }

    @Test
    fun `isConsistent true for DISCHARGING plus unplugged`() {
        assertTrue(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_DISCHARGING,
                ChargingStateProbe.PLUGGED_UNPLUGGED,
            ),
        )
    }

    @Test
    fun `isConsistent false for DISCHARGING plus plugged`() {
        assertFalse(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_DISCHARGING,
                ChargingStateProbe.PLUGGED_AC,
            ),
        )
    }

    @Test
    fun `isConsistent true when status null`() {
        assertTrue(
            ChargingStateProbe.isConsistent(null, ChargingStateProbe.PLUGGED_AC),
        )
    }

    @Test
    fun `isConsistent true when plugged null`() {
        assertTrue(
            ChargingStateProbe.isConsistent(ChargingStateProbe.STATUS_CHARGING, null),
        )
    }

    @Test
    fun `isConsistent true for FULL or NOT_CHARGING regardless of plug`() {
        // FULL and NOT_CHARGING handled by separate rules; isConsistent
        // doesn't flag them.
        assertTrue(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_FULL, ChargingStateProbe.PLUGGED_UNPLUGGED,
            ),
        )
        assertTrue(
            ChargingStateProbe.isConsistent(
                ChargingStateProbe.STATUS_NOT_CHARGING, ChargingStateProbe.PLUGGED_AC,
            ),
        )
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
                "battery.status",
                "battery.status_name",
                "battery.plugged",
                "battery.plugged_name",
                "battery.status_plugged_consistent",
                "battery.charging_state_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `status evidence shows raw int`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.status" }
        assertEquals("2", ev?.value)
    }

    @Test
    fun `status_name evidence shows mapped name`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.status_name" }
        assertEquals(ChargingStateProbe.STATUS_NAME_CHARGING, ev?.value)
    }

    @Test
    fun `null status — status evidence is unreadable`() = runBlocking {
        val result = makeProbe(status = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.status" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `null plugged — plugged evidence is unreadable`() = runBlocking {
        val result = makeProbe(plugged = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.plugged" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `plugged 1 — plugged_name evidence is ac`() = runBlocking {
        val result = makeProbe(plugged = 1).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.plugged_name" }
        assertEquals(ChargingStateProbe.PLUGGED_NAME_AC, ev?.value)
    }

    @Test
    fun `consistent evidence true for charging plus plugged`() = runBlocking {
        val result = makeProbe(
            status = ChargingStateProbe.STATUS_CHARGING,
            plugged = ChargingStateProbe.PLUGGED_AC,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "battery.status_plugged_consistent" }
        assertEquals("true", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read BatteryManager BATTERY_PROPERTY_STATUS + EXTRA_PLUGGED; detect " +
                "status-vs-plug contradictions (CHARGING+unplugged, FULL+<100%) and " +
                "invalid plug enum values",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_charging_state`() {
        assertEquals("env.charging_state", makeProbe().id)
    }

    @Test
    fun `probe rank is 35`() {
        assertEquals(35, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-35 severity=medium.
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

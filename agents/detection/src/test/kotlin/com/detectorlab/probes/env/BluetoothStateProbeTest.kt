package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.network.NetworkTypeProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for BluetoothStateProbe (env.bluetooth_state, inventory rank 49).
 */
class BluetoothStateProbeTest {

    private fun makeProbe(
        adapterPresent: Boolean? = true,
        adapterState: Int? = BluetoothStateProbe.STATE_ON,
        featureBluetooth: Boolean? = true,
        featureBluetoothLe: Boolean? = true,
    ): BluetoothStateProbe = BluetoothStateProbe(
        adapterPresentSupplier = { adapterPresent },
        adapterStateSupplier = { adapterState },
        featureBluetoothSupplier = { featureBluetooth },
        featureBluetoothLeSupplier = { featureBluetoothLe },
    )

    private fun fakeCtx(
        model: String? = "Pixel 7",
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return if (key == BluetoothStateProbe.PROP_RO_PRODUCT_MODEL) model else null
        }
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
    fun `Pixel 7 BT ON with adapter + features — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Pixel 7 BT OFF (user toggled) — score is 0`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_OFF).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 7 BT TURNING_ON — score is 0`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_TURNING_ON)
            .run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 7 BT TURNING_OFF — score is 0`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_TURNING_OFF)
            .run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Redmi Note 12 (budget) with BT — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Lenovo Tab P11 (tablet) with BT — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    // ── Adapter-vs-feature contradiction (0.85) ──────────────────────────────

    @Test
    fun `FEATURE_BLUETOOTH true but adapter null — score is 0_85`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = true,
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `contradiction — pattern is feature_vs_adapter_contradiction`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_CONTRADICTION, ev?.value)
    }

    @Test
    fun `contradiction fires on budget Redmi too — not gated by phone-class`() = runBlocking {
        // Structural impossibility — fires regardless of model.
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = true,
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `contradiction fires on tablet too`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = true,
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `contradiction — consistent evidence is false`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.is_consistent" }
        assertEquals("false", ev?.value)
    }

    // ── Missing on phone-class (0.85) — KEY DIFFERENCE from NFC ──────────────

    @Test
    fun `null adapter on Pixel 7 — score is 0_85 (BT is universal)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `null adapter on Redmi Note 12 — score is 0_85 (BT universal, budget still scored)`() =
        runBlocking {
            // KEY DIFFERENCE FROM NFC: Bluetooth is near-universal on Android
            // phones since ~2008. A budget Redmi without BT is structurally
            // suspicious, NOT a "feature missing from budget tier" case.
            // (NFC at rank-48 would score 0 for Redmi-no-NFC; BT scores 0.85.)
            val result = makeProbe(
                adapterPresent = false,
                adapterState = null,
                featureBluetooth = false,
                featureBluetoothLe = null,
            ).run(fakeCtx(model = "Redmi Note 12"))
            assertEquals(0.85, result.score)
        }

    @Test
    fun `null adapter on Samsung phone — score is 0_85`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "SM-S908B"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `null adapter on OnePlus — score is 0_85`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `missing on phone — pattern is missing_on_phone`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `null adapter on tablet — score is 0 (not phone-class)`() = runBlocking {
        // Tablets are NOT in NetworkTypeProbe.PHONE_CLASS_MODEL_SUBSTRINGS;
        // missing-BT-on-tablet doesn't fire phone-class rule.
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null adapter on unknown model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "GenericDevice42"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null adapter on null model — score is 0`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = null))
        assertEquals(0.0, result.score)
    }

    // ── Invalid state (0.70) ─────────────────────────────────────────────────

    @Test
    fun `invalid state value 99 — score is 0_70`() = runBlocking {
        val result = makeProbe(adapterState = 99).run(fakeCtx())
        assertEquals(0.70, result.score)
    }

    @Test
    fun `invalid state 0 — score is 0_70`() = runBlocking {
        val result = makeProbe(adapterState = 0).run(fakeCtx())
        assertEquals(0.70, result.score)
    }

    @Test
    fun `invalid state negative — score is 0_70`() = runBlocking {
        val result = makeProbe(adapterState = -1).run(fakeCtx())
        assertEquals(0.70, result.score)
    }

    @Test
    fun `invalid state — pattern is invalid_state`() = runBlocking {
        val result = makeProbe(adapterState = 99).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_INVALID_STATE, ev?.value)
    }

    @Test
    fun `invalid state when adapter null — does NOT fire (gate requires adapter present)`() =
        runBlocking {
            // adapterPresent=false + state=99 — the missing-on-phone rule
            // fires (Pixel 7 = phone-class), but the invalid-state rule
            // can't fire because it requires adapter present.
            val result = makeProbe(
                adapterPresent = false,
                adapterState = 99,
                featureBluetooth = false,
            ).run(fakeCtx())
            assertEquals(0.85, result.score)
            val ev = result.evidence.find { it.key == "bluetooth.pattern" }
            assertEquals(BluetoothStateProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
        }

    @Test
    fun `valid state 10 (OFF) — does not fire invalid`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_OFF).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid state 11 (TURNING_ON) — does not fire invalid`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_TURNING_ON)
            .run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid state 12 (ON) — does not fire invalid`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_ON).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `valid state 13 (TURNING_OFF) — does not fire invalid`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_TURNING_OFF)
            .run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── No BLE on modern device (0.5 weak) ───────────────────────────────────

    @Test
    fun `adapter present but BLE feature false — score is 0_5`() = runBlocking {
        val result = makeProbe(featureBluetoothLe = false).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no LE on modern — pattern is no_le_on_modern`() = runBlocking {
        val result = makeProbe(featureBluetoothLe = false).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_NO_LE_ON_MODERN, ev?.value)
    }

    @Test
    fun `BLE feature null — does NOT fire (only explicit false fires)`() = runBlocking {
        val result = makeProbe(featureBluetoothLe = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `BLE false when adapter null — does NOT fire (gate requires adapter present)`() =
        runBlocking {
            val result = makeProbe(
                adapterPresent = false,
                adapterState = null,
                featureBluetooth = false,
                featureBluetoothLe = false,
            ).run(fakeCtx(model = "Pixel 7"))
            // missing_on_phone fires (0.85), not no_le_on_modern (0.5).
            assertEquals(0.85, result.score)
        }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `contradiction + missing-on-phone — contradiction wins (same score, source order)`() =
        runBlocking {
            // Both could fire (feature=true + adapter=null on phone-class).
            // contradiction fires first per cascade.
            val result = makeProbe(
                adapterPresent = false,
                adapterState = null,
                featureBluetooth = true,
                featureBluetoothLe = null,
            ).run(fakeCtx(model = "Pixel 7"))
            assertEquals(0.85, result.score)
            val ev = result.evidence.find { it.key == "bluetooth.pattern" }
            assertEquals(BluetoothStateProbe.PATTERN_CONTRADICTION, ev?.value)
        }

    @Test
    fun `missing on phone beats invalid state — invalid requires adapter present`() = runBlocking {
        // adapter=null on Pixel 7 + invalid state (irrelevant since adapter
        // gate blocks invalid-state rule). missing_on_phone fires 0.85.
        val result = makeProbe(
            adapterPresent = false,
            adapterState = 99,
            featureBluetooth = false,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `invalid state beats no_le_on_modern in cascade`() = runBlocking {
        val result = makeProbe(
            adapterState = 99,
            featureBluetoothLe = false,
        ).run(fakeCtx())
        assertEquals(0.70, result.score)
        val ev = result.evidence.find { it.key == "bluetooth.pattern" }
        assertEquals(BluetoothStateProbe.PATTERN_INVALID_STATE, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `adapter supplier returns — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only feature supplier returns — confidence 0_95`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            adapterState = null,
            featureBluetooth = true,
            featureBluetoothLe = null,
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `all suppliers null — confidence is 0_50`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            adapterState = null,
            featureBluetooth = null,
            featureBluetoothLe = null,
        ).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `all suppliers null — score is 0`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            adapterState = null,
            featureBluetooth = null,
            featureBluetoothLe = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_50`() = runBlocking {
        val result = BluetoothStateProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `adapterPresentSupplier throws — does not crash`() = runBlocking {
        val probe = BluetoothStateProbe(
            adapterPresentSupplier = { throw RuntimeException("simulated") },
            adapterStateSupplier = { null },
            featureBluetoothSupplier = { true },
            featureBluetoothLeSupplier = { null },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `adapterStateSupplier throws — does not crash`() = runBlocking {
        val probe = BluetoothStateProbe(
            adapterPresentSupplier = { true },
            adapterStateSupplier = { throw RuntimeException("simulated") },
            featureBluetoothSupplier = { true },
            featureBluetoothLeSupplier = { true },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `featureBluetoothSupplier throws — does not crash`() = runBlocking {
        val probe = BluetoothStateProbe(
            adapterPresentSupplier = { true },
            adapterStateSupplier = { BluetoothStateProbe.STATE_ON },
            featureBluetoothSupplier = { throw RuntimeException("simulated") },
            featureBluetoothLeSupplier = { true },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `featureBluetoothLeSupplier throws — does not crash`() = runBlocking {
        val probe = BluetoothStateProbe(
            adapterPresentSupplier = { true },
            adapterStateSupplier = { BluetoothStateProbe.STATE_ON },
            featureBluetoothSupplier = { true },
            featureBluetoothLeSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = BluetoothStateProbe(
            adapterPresentSupplier = { throw RuntimeException("a") },
            adapterStateSupplier = { throw RuntimeException("b") },
            featureBluetoothSupplier = { throw RuntimeException("c") },
            featureBluetoothLeSupplier = { throw RuntimeException("d") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = makeProbe().run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    // ── Tri-state is_consistent (rank-48 pattern) ────────────────────────────

    @Test
    fun `is_consistent true when both observed and agree`() = runBlocking {
        val result = makeProbe(
            adapterPresent = true,
            featureBluetooth = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.is_consistent" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_consistent true when both observed and both false`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            adapterState = null,
            featureBluetooth = false,
            featureBluetoothLe = null,
        ).run(fakeCtx(model = "GenericDevice42"))
        val ev = result.evidence.find { it.key == "bluetooth.is_consistent" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_consistent partial when only one observed`() = runBlocking {
        val result = makeProbe(
            adapterPresent = true,
            featureBluetooth = null,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.is_consistent" }
        assertEquals("partial", ev?.value)
    }

    @Test
    fun `is_consistent unknown when both null`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            adapterState = null,
            featureBluetooth = null,
            featureBluetoothLe = null,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.is_consistent" }
        assertEquals("unknown", ev?.value)
    }

    // ── stateName helper ─────────────────────────────────────────────────────

    @Test
    fun `stateName maps all four valid states`() {
        assertEquals("OFF", BluetoothStateProbe.stateName(BluetoothStateProbe.STATE_OFF))
        assertEquals(
            "TURNING_ON",
            BluetoothStateProbe.stateName(BluetoothStateProbe.STATE_TURNING_ON),
        )
        assertEquals("ON", BluetoothStateProbe.stateName(BluetoothStateProbe.STATE_ON))
        assertEquals(
            "TURNING_OFF",
            BluetoothStateProbe.stateName(BluetoothStateProbe.STATE_TURNING_OFF),
        )
    }

    @Test
    fun `stateName null returns unavailable`() {
        assertEquals("<unavailable>", BluetoothStateProbe.stateName(null))
    }

    @Test
    fun `stateName invalid value returns INVALID(n)`() {
        assertEquals("INVALID(99)", BluetoothStateProbe.stateName(99))
        assertEquals("INVALID(0)", BluetoothStateProbe.stateName(0))
        assertEquals("INVALID(-1)", BluetoothStateProbe.stateName(-1))
    }

    // ── Cross-rank reuse invariants ──────────────────────────────────────────

    @Test
    fun `isPhoneClassModel delegates to rank-25 NetworkTypeProbe`() {
        // Drift-safe invariant: if rank-25's PHONE_CLASS_MODEL_SUBSTRINGS
        // changes, this probe inherits. Test pins the cross-rank dependency.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("OnePlus 11"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Redmi Note 12"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
    }

    @Test
    fun `VALID_STATES list matches BluetoothAdapter STATE_ constants`() {
        // Drift alarm: if VALID_STATES is modified, this test forces
        // deliberate review against Android source values.
        assertEquals(
            setOf(10, 11, 12, 13),
            BluetoothStateProbe.VALID_STATES,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "bluetooth.adapter_present",
                "bluetooth.state",
                "bluetooth.state_raw",
                "bluetooth.has_system_feature",
                "bluetooth.has_le_feature",
                "bluetooth.is_consistent",
                "bluetooth.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `state_raw evidence reflects int value (ON=12)`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_ON).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state_raw" }
        assertEquals("12", ev?.value)
    }

    @Test
    fun `state_raw evidence reflects invalid int (99)`() = runBlocking {
        // Consumer-parseability: raw int is in its own row, separate from
        // the human-readable `INVALID(99)` in bluetooth.state.
        val result = makeProbe(adapterState = 99).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state_raw" }
        assertEquals("99", ev?.value)
    }

    @Test
    fun `state_raw evidence is unavailable when null`() = runBlocking {
        val result = makeProbe(adapterState = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state_raw" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `adapter_present evidence reflects supplier`() = runBlocking {
        val result = makeProbe(adapterPresent = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.adapter_present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `null adapter — evidence is unavailable`() = runBlocking {
        val result = makeProbe(adapterPresent = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.adapter_present" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `state evidence reflects mapped name (ON)`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_ON).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state" }
        assertEquals("ON", ev?.value)
    }

    @Test
    fun `state evidence reflects mapped name (OFF)`() = runBlocking {
        val result = makeProbe(adapterState = BluetoothStateProbe.STATE_OFF).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state" }
        assertEquals("OFF", ev?.value)
    }

    @Test
    fun `invalid state — evidence shows INVALID(n)`() = runBlocking {
        val result = makeProbe(adapterState = 99).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state" }
        assertEquals("INVALID(99)", ev?.value)
    }

    @Test
    fun `null state — evidence is unavailable`() = runBlocking {
        val result = makeProbe(adapterState = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.state" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `has_system_feature evidence reflects supplier`() = runBlocking {
        val result = makeProbe(featureBluetooth = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.has_system_feature" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_le_feature evidence reflects supplier`() = runBlocking {
        val result = makeProbe(featureBluetoothLe = false).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth.has_le_feature" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read BluetoothAdapter + PackageManager.FEATURE_BLUETOOTH/" +
                "BLUETOOTH_LE; detect missing-adapter on phone-class (BT is " +
                "near-universal on Android), feature-vs-adapter contradictions, " +
                "and invalid state values.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_bluetooth_state`() {
        assertEquals("env.bluetooth_state", makeProbe().id)
    }

    @Test
    fun `probe rank is 49`() {
        assertEquals(49, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-49 severity=low.
        assertEquals(ProbeSeverity.LOW, makeProbe().severity)
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

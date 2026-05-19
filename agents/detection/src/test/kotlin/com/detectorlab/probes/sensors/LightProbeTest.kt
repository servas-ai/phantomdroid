package com.detectorlab.probes.sensors

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
 * Unit tests for LightProbe (sensors.light, inventory rank 43).
 */
class LightProbeTest {

    private fun makeProbe(vendorName: String? = null): LightProbe =
        LightProbe(vendorNameSupplier = { vendorName })

    private fun sensorManagerWith(
        sensorTypes: List<Int>,
        lightSamples: List<Float> = emptyList(),
        sampleThrows: Boolean = false,
        listThrows: Boolean = false,
    ): SensorManagerView = object : SensorManagerView {
        override fun listSensorTypes(): List<Int> {
            if (listThrows) throw RuntimeException("simulated EACCES")
            return sensorTypes
        }
        override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
            if (sampleThrows) throw RuntimeException("simulated sensor failure")
            if (sensorType != AccelerometerGyroProbe.TYPE_LIGHT) {
                return SensorSample(LongArray(0), emptyArray())
            }
            val timestamps = LongArray(lightSamples.size) { it * 10_000_000L }
            val values: Array<FloatArray> =
                lightSamples.map { floatArrayOf(it) }.toTypedArray()
            return SensorSample(timestamps, values)
        }
    }

    private fun fakeCtx(
        sensorManager: SensorManagerView? = sensorManagerWith(
            sensorTypes = listOf(
                AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                AccelerometerGyroProbe.TYPE_GYROSCOPE,
                AccelerometerGyroProbe.TYPE_LIGHT,
                AccelerometerGyroProbe.TYPE_PROXIMITY,
            ),
            lightSamples = listOf(150.0f, 200.0f, 175.0f, 180.0f),
        ),
        model: String? = "Pixel 7",
        sensorAccessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == LightProbe.PROP_RO_PRODUCT_MODEL) model else null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView {
            if (sensorAccessorThrows) throw RuntimeException("simulated SM accessor failure")
            return sensorManager ?: object : SensorManagerView {
                override fun listSensorTypes() = emptyList<Int>()
                override fun sampleSensor(sensorType: Int, durationMs: Long) =
                    SensorSample(LongArray(0), emptyArray())
            }
        }
    }

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `Pixel with light + dynamic lux samples — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `outdoor sunlight 10000 lux — score is 0 (plausible)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(8000.0f, 11000.0f, 10500.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `dark room 0_5 lux — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(0.5f, 0.7f, 0.6f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no live samples — score is 0`() = runBlocking {
        // ON_CHANGE sensor with stationary device may emit zero samples.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator vendor/name marker (1.0) ────────────────────────────────────

    @Test
    fun `Goldfish vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish Light Sensor").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "AVD Light").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Genymotion Light").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu emulator vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "ranchu light").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Vbox emulated light").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `case-insensitive — GOLDFISH uppercase fires 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "GOLDFISH light").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu name marker — pattern is emu_name`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_EMU_NAME, ev?.value)
    }

    @Test
    fun `legit OEM vendor name not flagged`() = runBlocking {
        // Common real light sensors: AMS TMD2725, Capella CM3232, Sensortek STK33562
        val result = makeProbe(vendorName = "AMS AG TMD2725 ambient light").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Sensortek vendor not flagged`() = runBlocking {
        val result = makeProbe(vendorName = "Sensortek STK33562").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null vendor name does not fire emu rule`() = runBlocking {
        val result = makeProbe(vendorName = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty vendor name does not fire emu rule`() = runBlocking {
        val result = makeProbe(vendorName = "").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Missing light sensor on phone-class (0.85) ───────────────────────────

    @Test
    fun `no light on Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                    ),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `no light on Samsung Galaxy — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "SM-S908B",
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `no light — pattern is missing_on_phone`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `no light on tablet — score is 0 (not phone-class)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Lenovo Tab P11",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no light on unknown model — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "GenericDevice42",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `light present on Pixel — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Implausible lux (0.85) ───────────────────────────────────────────────

    @Test
    fun `200000 lux — score is 0_85 (above plausible ceiling)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(200_000.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Float MAX_VALUE — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(Float.MAX_VALUE),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `100000 lux boundary — score is 0 (inclusive plausibility ceiling)`() = runBlocking {
        // Exactly at ceiling — plausible (direct equatorial noon).
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(100_000.0f, 99_999.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `100001 lux — score is 0_85 (just above ceiling)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(100_001.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `implausible lux — pattern is implausible_lux`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(200_000.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_IMPLAUSIBLE_LUX, ev?.value)
    }

    @Test
    fun `implausible_lux evidence is true`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(200_000.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "light.implausible_lux" }
        assertEquals("true", ev?.value)
    }

    // ── Constant stub samples (0.85) ─────────────────────────────────────────

    @Test
    fun `all samples 0_0 — score is 0_85 (dark stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(0.0f, 0.0f, 0.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `all samples 1000_0 — score is 0_85 (indoor stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(1000.0f, 1000.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `constant stub — pattern is constant_stub`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(0.0f, 0.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_CONSTANT_STUB, ev?.value)
    }

    @Test
    fun `single 0_0 sample — does not fire (need 2)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(0.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `constant 250_0 lux — score is 0 (not a known stub value)`() = runBlocking {
        // Identical-non-stub readings shouldn't fire — stable indoor lighting
        // can briefly produce identical readings.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(250.0f, 250.0f, 250.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mixed 0_0 then 1000_0 — not all-same — score is 0`() = runBlocking {
        // Mixed values aren't a constant stub.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(0.0f, 1000.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sample throws — does not crash`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    sampleThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `Goldfish vendor + missing sensor — emu marker wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Goldfish vendor + implausible lux — emu marker wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(200_000.0f),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `missing on phone beats implausible-and-stub in cascade`() = runBlocking {
        // missing_on_phone fires when sensor isn't in list at all, so samples
        // wouldn't be collected. Confirms missing_on_phone fires at 0.85.
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `implausible lux beats constant stub in cascade`() = runBlocking {
        // Samples [200_000, 200_000] — both implausible AND all-same. But
        // 200_000 is not in {0.0, 1000.0} stub set, so constant_stub doesn't
        // fire anyway. implausible_lux fires at 0.85.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(200_000.0f, 200_000.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "light.pattern" }
        assertEquals(LightProbe.PATTERN_IMPLAUSIBLE_LUX, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `sensor accessor works — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `sensor accessor throws — confidence is 0_50`() = runBlocking {
        val result = makeProbe().run(fakeCtx(sensorAccessorThrows = true))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `sensor accessor throws — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx(sensorAccessorThrows = true))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `listSensorTypes throws — does not crash, missing_on_phone fires`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = emptyList(),
                    listThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with sensor present — score is 0`() = runBlocking {
        val result = LightProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no-arg production ctor with no sensor on Pixel — score is 0_85`() = runBlocking {
        val result = LightProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `vendorNameSupplier throws — does not crash`() = runBlocking {
        val probe = LightProbe(
            vendorNameSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? =
                throw RuntimeException("simulated property failure")
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: TelephonyField): String? = null
            override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = false
                override fun listInstalledPackages() = emptyList<String>()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
            override fun querySensorManager(): SensorManagerView = sensorManagerWith(
                sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                lightSamples = listOf(100.0f, 200.0f),
            )
        }
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasImplausibleLux true above 100000`() {
        assertTrue(LightProbe.hasImplausibleLux(listOf(100_001.0f)))
        assertTrue(LightProbe.hasImplausibleLux(listOf(50.0f, 200_000.0f)))
        assertTrue(LightProbe.hasImplausibleLux(listOf(Float.MAX_VALUE)))
    }

    @Test
    fun `hasImplausibleLux false at boundary`() {
        assertFalse(LightProbe.hasImplausibleLux(listOf(100_000.0f)))
        assertFalse(LightProbe.hasImplausibleLux(listOf(99_999.0f)))
    }

    @Test
    fun `hasImplausibleLux false for empty`() {
        assertFalse(LightProbe.hasImplausibleLux(emptyList()))
    }

    @Test
    fun `matchesConstantStub true for all-zero`() {
        assertTrue(LightProbe.matchesConstantStub(listOf(0.0f, 0.0f)))
        assertTrue(LightProbe.matchesConstantStub(listOf(0.0f, 0.0f, 0.0f)))
    }

    @Test
    fun `matchesConstantStub true for all-1000`() {
        assertTrue(LightProbe.matchesConstantStub(listOf(1000.0f, 1000.0f)))
    }

    @Test
    fun `matchesConstantStub false for non-stub constants`() {
        // 250.0 is not in the known stub set (0.0, 1000.0). Real sensors
        // can briefly emit the same value in stable conditions.
        assertFalse(LightProbe.matchesConstantStub(listOf(250.0f, 250.0f)))
        assertFalse(LightProbe.matchesConstantStub(listOf(500.0f, 500.0f, 500.0f)))
    }

    @Test
    fun `matchesConstantStub false for varying values`() {
        assertFalse(LightProbe.matchesConstantStub(listOf(0.0f, 1.0f)))
        assertFalse(LightProbe.matchesConstantStub(listOf(1000.0f, 1001.0f)))
    }

    @Test
    fun `matchesConstantStub false for single sample`() {
        assertFalse(LightProbe.matchesConstantStub(listOf(0.0f)))
        assertFalse(LightProbe.matchesConstantStub(listOf(1000.0f)))
    }

    @Test
    fun `matchesConstantStub false for empty`() {
        assertFalse(LightProbe.matchesConstantStub(emptyList()))
    }

    // ── Cross-rank reuse invariants (rank-17/31/32/42 pattern) ───────────────

    @Test
    fun `TYPE_LIGHT constant reused from rank-24 AccelerometerGyroProbe`() {
        // Drift-safe invariant: if rank-24's TYPE_LIGHT constant ever
        // changes, this test fails immediately. Same anchor pattern as
        // rank-17/31/32/42.
        assertEquals(5, AccelerometerGyroProbe.TYPE_LIGHT)
    }

    @Test
    fun `EMU_NAME_SUBSTRINGS reused from rank-42 ProximityProbe`() {
        // rank 42 picked up rank-24's deferred EMU_NAME_SUBSTRINGS list;
        // rank 43 reuses rank-42's via ProximityProbe.hasEmuNameMarker.
        // Keep the contract visible: any change to rank-42's list affects
        // this probe.
        assertEquals(
            listOf("goldfish", "avd", "genymotion", "ranchu", "vbox"),
            ProximityProbe.EMU_NAME_SUBSTRINGS,
        )
    }

    @Test
    fun `hasEmuNameMarker delegates to rank-42 ProximityProbe`() {
        // Confirm the cross-rank delegation works correctly.
        assertTrue(ProximityProbe.hasEmuNameMarker("Goldfish Light"))
        assertFalse(ProximityProbe.hasEmuNameMarker("AMS TMD2725"))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "light.present",
                "light.vendor_name",
                "light.emu_name_marker",
                "light.sample_summary",
                "light.implausible_lux",
                "light.constant_stub",
                "light.phone_class",
                "light.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `present evidence reflects sensor list`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `missing light — present evidence is false`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "light.present" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `vendor_name evidence reflects supplier value`() = runBlocking {
        val result = makeProbe(vendorName = "AMS AG TMD2725").run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.vendor_name" }
        assertEquals("AMS AG TMD2725", ev?.value)
    }

    @Test
    fun `null vendor_name — evidence is unavailable`() = runBlocking {
        val result = makeProbe(vendorName = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.vendor_name" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `empty vendor_name — evidence is empty placeholder`() = runBlocking {
        val result = makeProbe(vendorName = "").run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.vendor_name" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `sample_summary includes count, first, and average`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                    lightSamples = listOf(100.0f, 200.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "light.sample_summary" }
        assertEquals("n=2 first=100.0 avg=150.0", ev?.value)
    }

    @Test
    fun `sample_summary no samples — placeholder`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_LIGHT),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "light.sample_summary" }
        assertEquals("<no samples>", ev?.value)
    }

    @Test
    fun `implausible_lux evidence is false for plausible lux`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.implausible_lux" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `constant_stub evidence is false for dynamic samples`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.constant_stub" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `phone_class evidence true on Pixel`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "light.phone_class" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `phone_class evidence false on tablet`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Lenovo Tab P11"))
        val ev = result.evidence.find { it.key == "light.phone_class" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Check SensorManager for TYPE_LIGHT presence + emulator vendor " +
                "names + (optional) lux-value plausibility and sample variance",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is sensors_light`() {
        assertEquals("sensors.light", makeProbe().id)
    }

    @Test
    fun `probe rank is 43`() {
        assertEquals(43, makeProbe().rank)
    }

    @Test
    fun `probe category is SENSORS`() {
        assertEquals(ProbeCategory.SENSORS, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-43 severity=low.
        assertEquals(ProbeSeverity.LOW, makeProbe().severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 250ms`() {
        assertEquals(250L, makeProbe().budgetMs)
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

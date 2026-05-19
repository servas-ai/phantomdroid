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
 * Unit tests for BarometerProbe (sensors.barometer, inventory rank 45).
 */
class BarometerProbeTest {

    private fun makeProbe(vendorName: String? = null): BarometerProbe =
        BarometerProbe(vendorNameSupplier = { vendorName })

    private fun sensorManagerWith(
        sensorTypes: List<Int>,
        pressureSamples: List<Float> = emptyList(),
        sampleThrows: Boolean = false,
        listThrows: Boolean = false,
    ): SensorManagerView = object : SensorManagerView {
        override fun listSensorTypes(): List<Int> {
            if (listThrows) throw RuntimeException("simulated EACCES")
            return sensorTypes
        }
        override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
            if (sampleThrows) throw RuntimeException("simulated sensor failure")
            if (sensorType != AccelerometerGyroProbe.TYPE_PRESSURE) {
                return SensorSample(LongArray(0), emptyArray())
            }
            val timestamps = LongArray(pressureSamples.size) { it * 10_000_000L }
            val values: Array<FloatArray> =
                pressureSamples.map { floatArrayOf(it) }.toTypedArray()
            return SensorSample(timestamps, values)
        }
    }

    private fun fakeCtx(
        sensorManager: SensorManagerView? = sensorManagerWith(
            sensorTypes = listOf(
                AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                AccelerometerGyroProbe.TYPE_GYROSCOPE,
                AccelerometerGyroProbe.TYPE_PRESSURE,
                AccelerometerGyroProbe.TYPE_LIGHT,
            ),
            pressureSamples = listOf(1011.3f, 1013.7f, 1014.1f),
        ),
        model: String? = "Pixel 7",
        sensorAccessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == BarometerProbe.PROP_RO_PRODUCT_MODEL) model else null
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
    fun `Pixel 7 with realistic pressure samples — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.pattern" }
        assertEquals(BarometerProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `low altitude near 1020 hPa — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1019.5f, 1020.8f, 1018.2f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mountain altitude 800 hPa — score is 0`() = runBlocking {
        // 2000m altitude ≈ 800 hPa, still plausible.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(795.0f, 801.5f, 800.2f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no live samples — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator vendor/name marker (1.0) ────────────────────────────────────

    @Test
    fun `Goldfish vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish Pressure Sensor").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "AVD Pressure").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Genymotion pressure").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "ranchu pressure").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Vbox emulated").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `case-insensitive uppercase GOLDFISH — fires 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "GOLDFISH pressure").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu name marker — pattern is emu_name`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.pattern" }
        assertEquals(BarometerProbe.PATTERN_EMU_NAME, ev?.value)
    }

    @Test
    fun `legit OEM vendor names not flagged`() = runBlocking {
        // Common real barometer chips: Bosch BMP380, ST LPS22, NXP MPL3115
        listOf(
            "Bosch BMP380 pressure sensor",
            "STMicroelectronics LPS22HB",
            "NXP MPL3115A2",
        ).forEach { vendor ->
            val result = makeProbe(vendorName = vendor).run(fakeCtx())
            assertEquals(0.0, result.score, "vendor=$vendor should not fire")
        }
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

    // ── Out-of-range pressure (0.95) ─────────────────────────────────────────

    @Test
    fun `pressure 200 hPa — score is 0_95 (above Everest altitude impossible)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(200.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `pressure 1200 hPa — score is 0_95 (above world record)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1200.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `pressure 0 hPa — score is 0_95 (out-of-range fires before stub since 0 is below 300)`() =
        runBlocking {
            // 0.0 hPa is below 300 hPa floor → out_of_range fires first at
            // 0.95. The constant_stub rule at 0.85 doesn't get to fire.
            val result = makeProbe().run(
                fakeCtx(
                    sensorManager = sensorManagerWith(
                        sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                        pressureSamples = listOf(0.0f, 0.0f),
                    ),
                ),
            )
            assertEquals(0.95, result.score)
            val ev = result.evidence.find { it.key == "barometer.pattern" }
            assertEquals(BarometerProbe.PATTERN_OUT_OF_RANGE, ev?.value)
        }

    @Test
    fun `pressure negative — score is 0_95`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(-50.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `pressure 300 boundary — score is 0 (plausible at Everest summit)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(300.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `pressure 299 — score is 0_95 (just below floor)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(299.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `pressure 1100 boundary — score is 0 (plausible)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1100.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `pressure 1101 — score is 0_95 (just above ceiling)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1101.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `out of range — pattern is out_of_range`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(200.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.pattern" }
        assertEquals(BarometerProbe.PATTERN_OUT_OF_RANGE, ev?.value)
    }

    @Test
    fun `one spike in stream fires out_of_range`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.0f, 9999.0f, 1014.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    // ── Constant stub samples (0.85) ─────────────────────────────────────────

    @Test
    fun `all samples 1013_25 — score is 0_85 (Goldfish sea-level stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.25f, 1013.25f, 1013.25f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `all samples 1000_0 — score is 0_85 (round-number stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1000.0f, 1000.0f),
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
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.25f, 1013.25f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.pattern" }
        assertEquals(BarometerProbe.PATTERN_CONSTANT_STUB, ev?.value)
    }

    @Test
    fun `single 1013_25 sample — does not fire (need 2)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.25f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `constant 1015_0 — score is 0 (not a known stub value)`() = runBlocking {
        // 1015 hPa is a perfectly plausible stable indoor reading; the
        // constant-stub rule only fires on the three known stub values.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1015.0f, 1015.0f, 1015.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mixed 1013_25 and 1000_0 — does not fire (not all-same)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.25f, 1000.0f),
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
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    sampleThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Missing barometer on flagship (0.5) — weak signal ────────────────────

    @Test
    fun `no barometer on Pixel 7 — score is 0_5 (weak signal)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                    ),
                ),
                model = "Pixel 7",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no barometer on Pixel 8 Pro — score is 0_5`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Pixel 8 Pro",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no barometer on Galaxy S24 — score is 0_5`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "SM-S921B",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no barometer on OnePlus 11 — score is 0_5`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "OnePlus 11",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no barometer on Xiaomi 14 — score is 0_5`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Xiaomi 14",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `missing on flagship — pattern is missing_on_flagship`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Pixel 8",
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.pattern" }
        assertEquals(BarometerProbe.PATTERN_MISSING_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `no barometer on Pixel 4a — score is 0 (NOT in known-barometer list)`() = runBlocking {
        // Pixel 4a doesn't have a barometer — that's expected, not flagged.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Pixel 4a",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no barometer on Redmi Note 12 — score is 0 (budget phone, no barometer expected)`() =
        runBlocking {
            val result = makeProbe().run(
                fakeCtx(
                    sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                    model = "Redmi Note 12",
                ),
            )
            assertEquals(0.0, result.score)
        }

    @Test
    fun `no barometer on Galaxy A52 — score is 0 (mid-range, no barometer expected)`() =
        runBlocking {
            val result = makeProbe().run(
                fakeCtx(
                    sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                    model = "SM-A525F",
                ),
            )
            assertEquals(0.0, result.score)
        }

    @Test
    fun `no barometer on tablet — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Lenovo Tab P11",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no barometer on null model — score is 0 (cannot claim flagship)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = null,
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `barometer present on Pixel 7 — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `Goldfish + missing on flagship — emu wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Pixel 8",
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Goldfish + out-of-range — emu wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(200.0f),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `out-of-range beats constant_stub`() = runBlocking {
        // pressure=0,0 — both predicates match (0 is in stub set AND below 300).
        // out_of_range fires first at 0.95.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(0.0f, 0.0f),
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `constant_stub beats missing_on_flagship`() = runBlocking {
        // Pixel 8 with sensor present + constant 1013.25 — stub wins at 0.85
        // because missing_on_flagship cannot fire when sensor IS present.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1013.25f, 1013.25f),
                ),
                model = "Pixel 8",
            ),
        )
        assertEquals(0.85, result.score)
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
    fun `listSensorTypes throws — does not crash, missing_on_flagship fires`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = emptyList(),
                    listThrows = true,
                ),
                model = "Pixel 8",
            ),
        )
        assertFalse(result.failed)
        // List throws → treated as empty → !hasPressure → flagship → 0.5.
        assertEquals(0.5, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with sensor present — score is 0`() = runBlocking {
        val result = BarometerProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no-arg production ctor with no sensor on flagship — score is 0_5`() = runBlocking {
        val result = BarometerProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Pixel 8",
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no-arg production ctor with no sensor on budget — score is 0`() = runBlocking {
        val result = BarometerProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Redmi Note 12",
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `vendorNameSupplier throws — does not crash`() = runBlocking {
        val probe = BarometerProbe(
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
                sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                pressureSamples = listOf(1013.0f, 1014.0f),
            )
        }
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isKnownBarometerModel detects Pixel 6+`() {
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 6"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 6 Pro"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 7"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 7a"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 8"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel 8 Pro"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Pixel Fold"))
    }

    @Test
    fun `isKnownBarometerModel detects Galaxy S22+ via model codes`() {
        assertTrue(BarometerProbe.isKnownBarometerModel("SM-S901B"))
        assertTrue(BarometerProbe.isKnownBarometerModel("SM-S908"))
        assertTrue(BarometerProbe.isKnownBarometerModel("SM-S921"))
    }

    @Test
    fun `isKnownBarometerModel detects OnePlus 9+`() {
        assertTrue(BarometerProbe.isKnownBarometerModel("OnePlus 9"))
        assertTrue(BarometerProbe.isKnownBarometerModel("OnePlus 11"))
    }

    @Test
    fun `isKnownBarometerModel detects Xiaomi flagships`() {
        assertTrue(BarometerProbe.isKnownBarometerModel("Mi 13"))
        assertTrue(BarometerProbe.isKnownBarometerModel("Xiaomi 14"))
    }

    @Test
    fun `isKnownBarometerModel case-insensitive`() {
        assertTrue(BarometerProbe.isKnownBarometerModel("PIXEL 7"))
        assertTrue(BarometerProbe.isKnownBarometerModel("pixel 7"))
        assertTrue(BarometerProbe.isKnownBarometerModel("PiXeL 7"))
    }

    @Test
    fun `isKnownBarometerModel false for budget phones`() {
        assertFalse(BarometerProbe.isKnownBarometerModel("Pixel 4a"))
        assertFalse(BarometerProbe.isKnownBarometerModel("Pixel 5a"))
        assertFalse(BarometerProbe.isKnownBarometerModel("Redmi Note 12"))
        assertFalse(BarometerProbe.isKnownBarometerModel("SM-A525F"))
        assertFalse(BarometerProbe.isKnownBarometerModel("OnePlus Nord"))
    }

    @Test
    fun `isKnownBarometerModel false for tablets`() {
        assertFalse(BarometerProbe.isKnownBarometerModel("Lenovo Tab P11"))
        assertFalse(BarometerProbe.isKnownBarometerModel("Galaxy Tab S8"))
    }

    @Test
    fun `isKnownBarometerModel false for empty and null`() {
        assertFalse(BarometerProbe.isKnownBarometerModel(null))
        assertFalse(BarometerProbe.isKnownBarometerModel(""))
    }

    @Test
    fun `hasOutOfRangePressure true below 300 or above 1100`() {
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(299.9f)))
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(1100.1f)))
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(0.0f)))
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(-100.0f)))
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(Float.MAX_VALUE)))
    }

    @Test
    fun `hasOutOfRangePressure false at boundaries`() {
        assertFalse(BarometerProbe.hasOutOfRangePressure(listOf(300.0f)))
        assertFalse(BarometerProbe.hasOutOfRangePressure(listOf(1100.0f)))
    }

    @Test
    fun `hasOutOfRangePressure false for plausible`() {
        assertFalse(BarometerProbe.hasOutOfRangePressure(listOf(1013.25f)))
        assertFalse(BarometerProbe.hasOutOfRangePressure(listOf(800.0f, 1000.0f, 1050.0f)))
    }

    @Test
    fun `hasOutOfRangePressure false for empty`() {
        assertFalse(BarometerProbe.hasOutOfRangePressure(emptyList()))
    }

    @Test
    fun `hasOutOfRangePressure true if any sample out`() {
        // 1013 is plausible but 50 is not.
        assertTrue(BarometerProbe.hasOutOfRangePressure(listOf(1013.0f, 50.0f)))
    }

    @Test
    fun `matchesConstantStub true for all-1013_25`() {
        assertTrue(BarometerProbe.matchesConstantStub(listOf(1013.25f, 1013.25f)))
        assertTrue(BarometerProbe.matchesConstantStub(listOf(1013.25f, 1013.25f, 1013.25f)))
    }

    @Test
    fun `matchesConstantStub true for all-1000`() {
        assertTrue(BarometerProbe.matchesConstantStub(listOf(1000.0f, 1000.0f)))
    }

    @Test
    fun `matchesConstantStub true for all-zero`() {
        assertTrue(BarometerProbe.matchesConstantStub(listOf(0.0f, 0.0f)))
    }

    @Test
    fun `matchesConstantStub false for non-stub constants`() {
        // 1015 is plausible and constant but not a known stub value.
        assertFalse(BarometerProbe.matchesConstantStub(listOf(1015.0f, 1015.0f)))
    }

    @Test
    fun `matchesConstantStub false for varying values`() {
        assertFalse(BarometerProbe.matchesConstantStub(listOf(1013.25f, 1013.26f)))
        assertFalse(BarometerProbe.matchesConstantStub(listOf(1000.0f, 1001.0f)))
    }

    @Test
    fun `matchesConstantStub false for single sample`() {
        assertFalse(BarometerProbe.matchesConstantStub(listOf(1013.25f)))
    }

    @Test
    fun `matchesConstantStub false for empty`() {
        assertFalse(BarometerProbe.matchesConstantStub(emptyList()))
    }

    @Test
    fun `matchesConstantStub misses one-ULP-drifted 1013_25 — known fragility`() {
        // KDoc on STUB_PRESSURE_SEA_LEVEL documents this: `1013.25` is not
        // exactly representable in IEEE 754 single precision. If an upstream
        // wrapper applies ANY arithmetic to the Goldfish stub value before
        // passing it through, the bit pattern can drift by one ULP and the
        // `==` equality silently fails. This test locks in that contract so
        // a future change that "improves" the comparison to use `abs(diff)
        // < epsilon` causes a deliberate review of the false-positive
        // surface that change would create.
        val drifted = Math.nextUp(BarometerProbe.STUB_PRESSURE_SEA_LEVEL)
        assertFalse(
            BarometerProbe.matchesConstantStub(listOf(drifted, drifted)),
            "One-ULP drift from 1013.25f silently bypasses the stub rule. " +
                "If this test breaks because matchesConstantStub now uses " +
                "tolerance-based comparison, audit the false-positive impact " +
                "on real sensors emitting values close to 1013.25.",
        )
    }

    // ── Cross-rank reuse invariants (rank-17/31/32/42/43/44 pattern) ─────────

    @Test
    fun `TYPE_PRESSURE constant reused from rank-24 AccelerometerGyroProbe`() {
        // Drift-safe invariant: if rank-24's TYPE_PRESSURE constant changes,
        // this test fails immediately.
        assertEquals(6, AccelerometerGyroProbe.TYPE_PRESSURE)
    }

    @Test
    fun `EMU_NAME_SUBSTRINGS reused from rank-42 ProximityProbe`() {
        assertEquals(
            listOf("goldfish", "avd", "genymotion", "ranchu", "vbox"),
            ProximityProbe.EMU_NAME_SUBSTRINGS,
        )
    }

    @Test
    fun `hasEmuNameMarker delegates to rank-42 ProximityProbe`() {
        assertTrue(ProximityProbe.hasEmuNameMarker("Goldfish pressure"))
        assertFalse(ProximityProbe.hasEmuNameMarker("Bosch BMP380"))
    }

    @Test
    fun `KNOWN_BAROMETER_MODELS list contains expected flagship families`() {
        // Drift alarm: if anyone changes the model list without updating
        // this test, they have to think about which models they're
        // adding/removing.
        val list = BarometerProbe.KNOWN_BAROMETER_MODELS
        assertTrue("pixel 6" in list)
        assertTrue("pixel 7" in list)
        assertTrue("pixel 8" in list)
        assertTrue("sm-s901" in list)
        assertTrue("oneplus 11" in list)
        assertTrue("xiaomi 14" in list)
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
                "barometer.present",
                "barometer.vendor_name",
                "barometer.emu_name_marker",
                "barometer.sample_summary",
                "barometer.out_of_range",
                "barometer.constant_stub",
                "barometer.is_known_barometer_model",
                "barometer.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `present evidence reflects sensor list`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `missing — present evidence is false`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "barometer.present" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `vendor_name evidence reflects supplier`() = runBlocking {
        val result = makeProbe(vendorName = "Bosch BMP380").run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.vendor_name" }
        assertEquals("Bosch BMP380", ev?.value)
    }

    @Test
    fun `null vendor_name — evidence is unavailable`() = runBlocking {
        val result = makeProbe(vendorName = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.vendor_name" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `sample_summary includes count, first, average`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(1000.0f, 1020.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.sample_summary" }
        assertEquals("n=2 first=1000.0 avg=1010.0", ev?.value)
    }

    @Test
    fun `sample_summary no samples — placeholder`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.sample_summary" }
        assertEquals("<no samples>", ev?.value)
    }

    @Test
    fun `out_of_range evidence false for plausible`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.out_of_range" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `out_of_range evidence true for impossible`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PRESSURE),
                    pressureSamples = listOf(200.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "barometer.out_of_range" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_known_barometer_model evidence true on Pixel 7`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "barometer.is_known_barometer_model" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_known_barometer_model evidence false on Redmi Note 12`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Redmi Note 12"))
        val ev = result.evidence.find { it.key == "barometer.is_known_barometer_model" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Check SensorManager for TYPE_PRESSURE presence + emulator vendor " +
                "names + plausibility (300-1100 hPa range) + AVD-stub-value " +
                "detection (1013.25 / 1000.0 / 0.0). Score 'missing sensor' " +
                "lower than other sensor probes since barometer is not universal.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is sensors_barometer`() {
        assertEquals("sensors.barometer", makeProbe().id)
    }

    @Test
    fun `probe rank is 45`() {
        assertEquals(45, makeProbe().rank)
    }

    @Test
    fun `probe category is SENSORS`() {
        assertEquals(ProbeCategory.SENSORS, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-45 severity=low.
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

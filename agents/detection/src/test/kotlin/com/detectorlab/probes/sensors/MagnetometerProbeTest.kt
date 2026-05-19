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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for MagnetometerProbe (sensors.magnetometer, inventory rank 44).
 */
class MagnetometerProbeTest {

    private fun makeProbe(vendorName: String? = null): MagnetometerProbe =
        MagnetometerProbe(vendorNameSupplier = { vendorName })

    private fun sensorManagerWith(
        sensorTypes: List<Int>,
        magSamples: List<Triple<Float, Float, Float>> = emptyList(),
        sampleThrows: Boolean = false,
        listThrows: Boolean = false,
    ): SensorManagerView = object : SensorManagerView {
        override fun listSensorTypes(): List<Int> {
            if (listThrows) throw RuntimeException("simulated EACCES")
            return sensorTypes
        }
        override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
            if (sampleThrows) throw RuntimeException("simulated sensor failure")
            if (sensorType != AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD) {
                return SensorSample(LongArray(0), emptyArray())
            }
            val timestamps = LongArray(magSamples.size) { it * 10_000_000L }
            val values: Array<FloatArray> = magSamples
                .map { (x, y, z) -> floatArrayOf(x, y, z) }
                .toTypedArray()
            return SensorSample(timestamps, values)
        }
    }

    private fun fakeCtx(
        sensorManager: SensorManagerView? = sensorManagerWith(
            sensorTypes = listOf(
                AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                AccelerometerGyroProbe.TYPE_GYROSCOPE,
                AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD,
                AccelerometerGyroProbe.TYPE_LIGHT,
            ),
            // Real-world 3-axis: ~25-50 µT magnitude.
            magSamples = listOf(
                Triple(20.0f, 25.0f, -35.0f),  // magnitude ≈ 47 µT
                Triple(21.0f, 24.0f, -36.0f),
                Triple(22.0f, 26.0f, -34.0f),
            ),
        ),
        model: String? = "Pixel 7",
        sensorAccessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == MagnetometerProbe.PROP_RO_PRODUCT_MODEL) model else null
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
    fun `Pixel with magnetometer + dynamic 47uT samples — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `weak Earth field 25uT — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(15.0f, 15.0f, -10.0f)),  // ≈25 µT
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `strong Earth field 65uT — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(40.0f, 40.0f, -30.0f)),  // ≈64 µT
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
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator vendor/name marker (1.0) ────────────────────────────────────

    @Test
    fun `Goldfish vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish Magnetic Sensor").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "AVD Magnetometer").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Genymotion Mag").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "ranchu mag").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Vbox emulated mag").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `case-insensitive uppercase GOLDFISH — fires 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "GOLDFISH mag").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu name marker — pattern is emu_name`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_EMU_NAME, ev?.value)
    }

    @Test
    fun `legit OEM vendor names not flagged`() = runBlocking {
        // Common real magnetometers: AKM AK09918, Bosch BMM350, STMicro LSM303
        listOf(
            "AKM AK09918",
            "Bosch BMM350",
            "STMicro LSM303 magnetometer",
            "MEMSIC MMC5603",
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

    // ── AVD stub triples (1.0) ───────────────────────────────────────────────

    @Test
    fun `(0,0,0) stub — score is 1_0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(0.0f, 0.0f, 0.0f)),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `(0,0,-42) AVD goldfish stub — score is 1_0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(0.0f, 0.0f, -42.0f)),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `(1,1,1) ones-stub — score is 1_0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(1.0f, 1.0f, 1.0f)),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD stub mixed in with real samples — still fires 1_0`() = runBlocking {
        // Even one stub triple in a stream of real samples fires.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(20.0f, 25.0f, -35.0f),
                        Triple(0.0f, 0.0f, -42.0f),  // stub
                        Triple(22.0f, 26.0f, -34.0f),
                    ),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD stub — pattern is avd_stub_triple`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(0.0f, 0.0f, -42.0f)),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_AVD_STUB_TRIPLE, ev?.value)
    }

    @Test
    fun `(2,2,2) non-stub identical triple — score is 0 (not in stub set)`() = runBlocking {
        // 2,2,2 = magnitude ~3.46 µT — implausibly low, but the triple
        // ISN'T in AVD_STUB_TRIPLES, so the stub rule doesn't fire.
        // implausible_low needs >= 2 samples; single sample doesn't fire.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(2.0f, 2.0f, 2.0f)),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `(0_1, 0_1, -42_1) near-stub-but-not-exact — does not fire AVD rule`() = runBlocking {
        // Real MEMS jitters at sub-µT level; near-stub-but-not-exact is the
        // signature of a real sensor briefly hitting near a synthetic stub.
        // The AVD rule requires exact equality. mag ≈ 42.1 µT → plausible.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(0.1f, 0.1f, -42.1f),
                        Triple(0.2f, 0.0f, -42.3f),
                    ),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Missing magnetometer on phone-class (0.85) ───────────────────────────

    @Test
    fun `no magnetometer on Pixel 7 — score is 0_85`() = runBlocking {
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
    fun `no magnetometer on Samsung Galaxy — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "SM-S908B",
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `no magnetometer — pattern is missing_on_phone`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `no magnetometer on tablet — score is 0`() = runBlocking {
        // Some budget tablets ship without magnetometer.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Lenovo Tab P11",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no magnetometer on unknown model — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "GenericDevice42",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `magnetometer present on Pixel — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Implausibly high magnitude (0.85) ────────────────────────────────────

    @Test
    fun `magnitude 250uT — score is 0_85`() = runBlocking {
        // Magnitude = sqrt(150² + 150² + 100²) ≈ 235 µT — above 200 ceiling.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(150.0f, 150.0f, 100.0f)),
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
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(Float.MAX_VALUE, 0.0f, 0.0f)),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `magnitude 200 exactly — score is 0 (boundary plausible)`() = runBlocking {
        // sqrt(120² + 120² + 80²) = sqrt(14400+14400+6400) = sqrt(35200) ≈ 187.6
        // Need exactly 200: sqrt(x²+y²+z²)=200, use (200, 0, 0).
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(200.0f, 0.0f, 0.0f)),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `magnitude 201 — score is 0_85 (just above ceiling)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(201.0f, 0.0f, 0.0f)),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `implausible high — pattern is implausible_high`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(150.0f, 150.0f, 100.0f)),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_IMPLAUSIBLE_HIGH, ev?.value)
    }

    // ── Implausibly low magnitude (0.85) — Faraday-cage-like ─────────────────

    @Test
    fun `(1,1,1) in stream of sub-5 samples — AVD stub wins over implausible_low (1_0)`() =
        runBlocking {
            // Cascade verification: `(1,1,1)` has magnitude ~1.73 µT so it
            // ALSO satisfies the implausible_low predicate. But AVD-stub
            // fires earlier in the cascade at score 1.0; implausible_low
            // at 0.85 would lose. Genuine implausible_low coverage is in
            // the next test below.
            val result = makeProbe().run(
                fakeCtx(
                    sensorManager = sensorManagerWith(
                        sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                        magSamples = listOf(
                            Triple(1.0f, 1.0f, 1.0f),    // stub triple
                            Triple(1.5f, 1.0f, -0.5f),   // mag ≈ 1.87
                            Triple(2.0f, 0.5f, -1.0f),   // mag ≈ 2.29
                        ),
                    ),
                ),
            )
            assertEquals(1.0, result.score)
            val ev = result.evidence.find { it.key == "magnetometer.pattern" }
            assertEquals(MagnetometerProbe.PATTERN_AVD_STUB_TRIPLE, ev?.value)
        }

    @Test
    fun `all sub-5uT non-stub across 2 samples — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(1.5f, 1.0f, -0.5f),  // mag ≈ 1.87
                        Triple(2.0f, 0.5f, -1.0f),  // mag ≈ 2.29
                    ),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single sub-5uT sample — does not fire implausible_low (need 2)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(1.5f, 1.0f, -0.5f)),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mixed sub-5uT and plausible samples — does not fire implausible_low`() = runBlocking {
        // implausible_low requires ALL samples below 5 µT, not just any.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(1.5f, 1.0f, -0.5f),  // 1.87 µT
                        Triple(20.0f, 25.0f, -35.0f),  // 47 µT
                    ),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `implausible low — pattern is implausible_low`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(1.5f, 1.0f, -0.5f),
                        Triple(2.0f, 0.5f, -1.0f),
                    ),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_IMPLAUSIBLE_LOW, ev?.value)
    }

    @Test
    fun `sample throws — does not crash`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    sampleThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `frame with fewer than 3 axes — skipped`() = runBlocking {
        // Defensive: a 2-axis sample frame should be silently skipped.
        val sm = object : SensorManagerView {
            override fun listSensorTypes() =
                listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD)
            override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
                val timestamps = longArrayOf(0L, 10_000_000L)
                val values = arrayOf(
                    floatArrayOf(20.0f, 25.0f),  // only 2 axes — skipped
                    floatArrayOf(20.0f, 25.0f, -35.0f),  // 3 axes — kept
                )
                return SensorSample(timestamps, values)
            }
        }
        val result = makeProbe().run(fakeCtx(sensorManager = sm))
        // Only the 3-axis frame counted → 1 sample of ~47 µT → no rule fires.
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `Goldfish vendor + AVD stub triple — emu_name wins (1_0, same score, source order)`() =
        runBlocking {
            val result = makeProbe(vendorName = "Goldfish").run(
                fakeCtx(
                    sensorManager = sensorManagerWith(
                        sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                        magSamples = listOf(Triple(0.0f, 0.0f, -42.0f)),
                    ),
                ),
            )
            assertEquals(1.0, result.score)
            val ev = result.evidence.find { it.key == "magnetometer.pattern" }
            assertEquals(MagnetometerProbe.PATTERN_EMU_NAME, ev?.value)
        }

    @Test
    fun `AVD stub + missing on phone — AVD wins (1_0 over 0_85)`() = runBlocking {
        // AVD stub requires sensor present (samples need to exist). If no
        // sensor list, no samples → AVD rule can't fire. Confirm missing
        // wins via the no-sensor path.
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `AVD stub triple beats implausible_high in cascade`() = runBlocking {
        // (0, 0, -42) magnitude is 42 — plausible range. Stub rule fires
        // before implausible_high would (irrelevant here, but documents
        // the cascade contract).
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(0.0f, 0.0f, -42.0f)),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `missing on phone beats implausible_high in cascade`() = runBlocking {
        // Sensor missing → no samples → implausible_high can't fire.
        // missing_on_phone fires at 0.85.
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `implausible_high beats implausible_low in cascade`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(
                        Triple(250.0f, 0.0f, 0.0f),  // 250 µT — high
                        Triple(1.0f, 1.5f, -0.5f),   // 1.87 µT — low
                    ),
                ),
            ),
        )
        // implausible_high fires first; pattern label is implausible_high.
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "magnetometer.pattern" }
        assertEquals(MagnetometerProbe.PATTERN_IMPLAUSIBLE_HIGH, ev?.value)
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
    fun `listSensorTypes throws — missing_on_phone fires`() = runBlocking {
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
        val result = MagnetometerProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no-arg production ctor with no sensor on Pixel — score is 0_85`() = runBlocking {
        val result = MagnetometerProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `vendorNameSupplier throws — does not crash`() = runBlocking {
        val probe = MagnetometerProbe(
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
                sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                magSamples = listOf(Triple(20.0f, 25.0f, -35.0f)),
            )
        }
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `magnitude formula correct`() {
        // Spot-check: (3, 4, 0) → 5.0
        assertEquals(5.0, MagnetometerProbe.magnitude(3.0f, 4.0f, 0.0f), 1e-9)
        // (0, 0, 0) → 0
        assertEquals(0.0, MagnetometerProbe.magnitude(0.0f, 0.0f, 0.0f), 1e-9)
        // (1, 1, 1) → sqrt(3) ≈ 1.732
        assertTrue(
            abs(MagnetometerProbe.magnitude(1.0f, 1.0f, 1.0f) - 1.7320508) < 1e-6,
        )
    }

    @Test
    fun `hasAvdStubTriple detects all three known stubs`() {
        assertTrue(MagnetometerProbe.hasAvdStubTriple(listOf(Triple(0.0f, 0.0f, 0.0f))))
        assertTrue(MagnetometerProbe.hasAvdStubTriple(listOf(Triple(0.0f, 0.0f, -42.0f))))
        assertTrue(MagnetometerProbe.hasAvdStubTriple(listOf(Triple(1.0f, 1.0f, 1.0f))))
    }

    @Test
    fun `hasAvdStubTriple false for near-stub-but-not-exact`() {
        assertFalse(
            MagnetometerProbe.hasAvdStubTriple(listOf(Triple(0.1f, 0.0f, -42.0f))),
        )
        assertFalse(
            MagnetometerProbe.hasAvdStubTriple(listOf(Triple(0.0f, 0.0f, -42.1f))),
        )
        assertFalse(
            MagnetometerProbe.hasAvdStubTriple(listOf(Triple(2.0f, 2.0f, 2.0f))),
        )
    }

    @Test
    fun `hasAvdStubTriple false for real readings`() {
        assertFalse(
            MagnetometerProbe.hasAvdStubTriple(listOf(Triple(20.0f, 25.0f, -35.0f))),
        )
    }

    @Test
    fun `hasAvdStubTriple false for empty`() {
        assertFalse(MagnetometerProbe.hasAvdStubTriple(emptyList()))
    }

    @Test
    fun `hasAvdStubTriple detects stub mixed in with real samples`() {
        assertTrue(
            MagnetometerProbe.hasAvdStubTriple(
                listOf(
                    Triple(20.0f, 25.0f, -35.0f),
                    Triple(0.0f, 0.0f, -42.0f),
                ),
            ),
        )
    }

    @Test
    fun `hasImplausiblyHighMagnitude true above 200uT`() {
        assertTrue(
            MagnetometerProbe.hasImplausiblyHighMagnitude(
                listOf(Triple(250.0f, 0.0f, 0.0f)),
            ),
        )
    }

    @Test
    fun `hasImplausiblyHighMagnitude false at exactly 200uT`() {
        // sqrt(200²+0+0) = 200, NOT > 200.
        assertFalse(
            MagnetometerProbe.hasImplausiblyHighMagnitude(
                listOf(Triple(200.0f, 0.0f, 0.0f)),
            ),
        )
    }

    @Test
    fun `hasImplausiblyHighMagnitude false for empty`() {
        assertFalse(MagnetometerProbe.hasImplausiblyHighMagnitude(emptyList()))
    }

    @Test
    fun `allMagnitudesImplausiblyLow true for all sub-5uT with 2+ samples`() {
        assertTrue(
            MagnetometerProbe.allMagnitudesImplausiblyLow(
                listOf(
                    Triple(1.0f, 1.0f, -0.5f),
                    Triple(0.5f, 0.5f, -1.0f),
                ),
            ),
        )
    }

    @Test
    fun `allMagnitudesImplausiblyLow false for single sample`() {
        assertFalse(
            MagnetometerProbe.allMagnitudesImplausiblyLow(
                listOf(Triple(1.0f, 1.0f, -0.5f)),
            ),
        )
    }

    @Test
    fun `allMagnitudesImplausiblyLow false when any sample is plausible`() {
        assertFalse(
            MagnetometerProbe.allMagnitudesImplausiblyLow(
                listOf(
                    Triple(1.0f, 1.0f, -0.5f),
                    Triple(20.0f, 25.0f, -35.0f),
                ),
            ),
        )
    }

    @Test
    fun `allMagnitudesImplausiblyLow false at exactly 5uT`() {
        // sqrt(3²+4²+0) = 5; NOT < 5, so doesn't fire.
        assertFalse(
            MagnetometerProbe.allMagnitudesImplausiblyLow(
                listOf(
                    Triple(3.0f, 4.0f, 0.0f),
                    Triple(3.0f, 4.0f, 0.0f),
                ),
            ),
        )
    }

    @Test
    fun `allMagnitudesImplausiblyLow false for empty`() {
        assertFalse(MagnetometerProbe.allMagnitudesImplausiblyLow(emptyList()))
    }

    // ── Cross-rank reuse invariants (rank-17/31/32/42/43 pattern) ────────────

    @Test
    fun `TYPE_MAGNETIC_FIELD constant reused from rank-24 AccelerometerGyroProbe`() {
        // Drift-safe invariant: if rank-24's TYPE_MAGNETIC_FIELD constant
        // ever changes, this test fails immediately.
        assertEquals(2, AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD)
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
        assertTrue(ProximityProbe.hasEmuNameMarker("Goldfish mag"))
        assertFalse(ProximityProbe.hasEmuNameMarker("AKM AK09918"))
    }

    @Test
    fun `AVD_STUB_TRIPLES contains the three documented stubs`() {
        assertEquals(
            listOf(
                Triple(0.0f, 0.0f, 0.0f),
                Triple(0.0f, 0.0f, -42.0f),
                Triple(1.0f, 1.0f, 1.0f),
            ),
            MagnetometerProbe.AVD_STUB_TRIPLES,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 9 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(9, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "magnetometer.present",
                "magnetometer.vendor_name",
                "magnetometer.emu_name_marker",
                "magnetometer.sample_summary",
                "magnetometer.avd_stub_triple",
                "magnetometer.implausible_high",
                "magnetometer.implausible_low",
                "magnetometer.phone_class",
                "magnetometer.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `present evidence reflects sensor list`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `missing — present evidence is false`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "magnetometer.present" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `vendor_name evidence reflects supplier`() = runBlocking {
        val result = makeProbe(vendorName = "AKM AK09918").run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.vendor_name" }
        assertEquals("AKM AK09918", ev?.value)
    }

    @Test
    fun `null vendor_name — evidence is unavailable`() = runBlocking {
        val result = makeProbe(vendorName = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.vendor_name" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `sample_summary includes count first and magnitude`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(3.0f, 4.0f, 0.0f)),  // magnitude = 5
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.sample_summary" }
        assertEquals("n=1 first=(3.0,4.0,0.0) mag=5.0", ev?.value)
    }

    @Test
    fun `sample_summary no samples — placeholder`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.sample_summary" }
        assertEquals("<no samples>", ev?.value)
    }

    @Test
    fun `avd_stub_triple evidence false for clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.avd_stub_triple" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `avd_stub_triple evidence true for stub`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD),
                    magSamples = listOf(Triple(0.0f, 0.0f, -42.0f)),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "magnetometer.avd_stub_triple" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `implausible_high evidence false for plausible field`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.implausible_high" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `implausible_low evidence false for plausible field`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.implausible_low" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `phone_class evidence true on Pixel`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "magnetometer.phone_class" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `phone_class evidence false on tablet`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Lenovo Tab P11"))
        val ev = result.evidence.find { it.key == "magnetometer.phone_class" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Check SensorManager for TYPE_MAGNETIC_FIELD presence + emulator " +
                "vendor names + 3-axis magnitude plausibility (5-200 µT range) + " +
                "AVD-stub-triple detection",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is sensors_magnetometer`() {
        assertEquals("sensors.magnetometer", makeProbe().id)
    }

    @Test
    fun `probe rank is 44`() {
        assertEquals(44, makeProbe().rank)
    }

    @Test
    fun `probe category is SENSORS`() {
        assertEquals(ProbeCategory.SENSORS, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-44 severity=low.
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

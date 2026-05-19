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
 * Unit tests for ProximityProbe (sensors.proximity, inventory rank 42).
 */
class ProximityProbeTest {

    private fun makeProbe(vendorName: String? = null): ProximityProbe =
        ProximityProbe(vendorNameSupplier = { vendorName })

    /** Builds a fake SensorManagerView with the given sensor type set + sample values. */
    private fun sensorManagerWith(
        sensorTypes: List<Int>,
        proximitySamples: List<Float> = emptyList(),
        sampleThrows: Boolean = false,
        listThrows: Boolean = false,
    ): SensorManagerView = object : SensorManagerView {
        override fun listSensorTypes(): List<Int> {
            if (listThrows) throw RuntimeException("simulated EACCES")
            return sensorTypes
        }
        override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
            if (sampleThrows) throw RuntimeException("simulated sensor failure")
            if (sensorType != AccelerometerGyroProbe.TYPE_PROXIMITY) {
                return SensorSample(LongArray(0), emptyArray())
            }
            val timestamps = LongArray(proximitySamples.size) { it * 10_000_000L }
            val values: Array<FloatArray> =
                proximitySamples.map { floatArrayOf(it) }.toTypedArray()
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
            proximitySamples = listOf(0.0f, 5.0f, 5.0f, 0.0f),
        ),
        model: String? = "Pixel 7",
        sensorAccessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == ProximityProbe.PROP_RO_PRODUCT_MODEL) model else null
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
    fun `real Pixel with proximity + dynamic samples — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.pattern" }
        assertEquals(ProximityProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `real Pixel with no live sample available — score is 0`() = runBlocking {
        // Sensor reported present but sample empty → no constant-rule fire.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                        AccelerometerGyroProbe.TYPE_PROXIMITY,
                    ),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator vendor/name marker (1.0) ────────────────────────────────────

    @Test
    fun `Goldfish vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish Proximity Sensor").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AVD vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "AVD Generic").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion vendor name — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Genymotion Proximity").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu emulator vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "ranchu sensors").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox vendor — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "Vbox emulated").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `case-insensitive — GOLDFISH uppercase fires 1_0`() = runBlocking {
        val result = makeProbe(vendorName = "GOLDFISH proximity").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu name marker — pattern is emu_name`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.pattern" }
        assertEquals(ProximityProbe.PATTERN_EMU_NAME, ev?.value)
    }

    @Test
    fun `legit OEM vendor name not flagged`() = runBlocking {
        val result = makeProbe(vendorName = "AMS AG TMD2725").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Capella vendor not flagged`() = runBlocking {
        val result = makeProbe(vendorName = "Capella CM36686").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `STMicroelectronics vendor not flagged`() = runBlocking {
        val result = makeProbe(vendorName = "STMicroelectronics VL53L0X").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null vendor name does not fire emu rule`() = runBlocking {
        // hasEmuNameMarker(null) = false; falls through to clean if proximity present.
        val result = makeProbe(vendorName = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty vendor name does not fire emu rule`() = runBlocking {
        val result = makeProbe(vendorName = "").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Missing proximity on phone-class (0.85) ──────────────────────────────

    @Test
    fun `no proximity on Pixel 7 — score is 0_85`() = runBlocking {
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
    fun `no proximity on Samsung Galaxy — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "SM-S908B",
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `no proximity — pattern is missing_on_phone`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "proximity.pattern" }
        assertEquals(ProximityProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
    }

    @Test
    fun `no proximity on tablet — score is 0 (not phone-class)`() = runBlocking {
        // Tablets legitimately ship without proximity sensors.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "Lenovo Tab P11",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no proximity on unknown model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(sensorTypes = emptyList()),
                model = "GenericDevice42",
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `proximity present on Pixel — score is 0 (rule doesn't fire)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    proximitySamples = listOf(0.0f, 5.0f, 0.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Constant samples (0.85) ──────────────────────────────────────────────

    @Test
    fun `all samples 0_0 — score is 0_85 (covered stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                        AccelerometerGyroProbe.TYPE_LIGHT,
                        AccelerometerGyroProbe.TYPE_PROXIMITY,
                    ),
                    proximitySamples = listOf(0.0f, 0.0f, 0.0f, 0.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `all samples 5_0 — score is 0_85 (uncovered stub)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                        AccelerometerGyroProbe.TYPE_LIGHT,
                        AccelerometerGyroProbe.TYPE_PROXIMITY,
                    ),
                    proximitySamples = listOf(5.0f, 5.0f, 5.0f),
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `constant samples — pattern is constant_samples`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(
                        AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                        AccelerometerGyroProbe.TYPE_GYROSCOPE,
                        AccelerometerGyroProbe.TYPE_LIGHT,
                        AccelerometerGyroProbe.TYPE_PROXIMITY,
                    ),
                    proximitySamples = listOf(0.0f, 0.0f),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "proximity.pattern" }
        assertEquals(ProximityProbe.PATTERN_CONSTANT_SAMPLES, ev?.value)
    }

    @Test
    fun `single sample — does not fire constant rule`() = runBlocking {
        // Need at least 2 samples to claim "constant".
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    proximitySamples = listOf(0.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `dynamic samples not flagged`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    proximitySamples = listOf(0.0f, 5.0f, 0.0f, 5.0f),
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sample throws — does not crash, falls back to no samples`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    sampleThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        // No samples → no constant rule fires → clean.
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `Goldfish vendor + missing sensor on phone — emu marker wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Goldfish vendor + constant samples — emu marker wins (1_0)`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    proximitySamples = listOf(0.0f, 0.0f),
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `missing on phone beats constant samples in cascade`() = runBlocking {
        // missing_on_phone fires before constant_samples in source order
        // when both could trigger (same 0.85 score, different pattern label).
        // This test verifies the pattern label.
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = emptyList(),
                    proximitySamples = emptyList(),
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "proximity.pattern" }
        assertEquals(ProximityProbe.PATTERN_MISSING_ON_PHONE, ev?.value)
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
        // Without observation, no rule can fire → clean at degraded confidence.
        val result = makeProbe().run(fakeCtx(sensorAccessorThrows = true))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `listSensorTypes throws — does not crash`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = emptyList(),
                    listThrows = true,
                ),
            ),
        )
        assertFalse(result.failed)
        // List throws → treated as empty. accessorWorked=true (SM accessor
        // worked even though list threw). Phone-class + no proximity →
        // missing_on_phone fires at 0.85.
        assertEquals(0.85, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with sensor present — score is 0`() = runBlocking {
        // No vendor name supplier (returns null) but sensor present →
        // clean. Emu-name rule doesn't fire when vendor is null.
        val result = ProximityProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no-arg production ctor with no sensor on Pixel — score is 0_85`() = runBlocking {
        val result = ProximityProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        assertEquals(0.85, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `vendorNameSupplier throws — does not crash`() = runBlocking {
        val probe = ProximityProbe(
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
                sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                proximitySamples = listOf(0.0f, 5.0f),
            )
        }
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasEmuNameMarker detects goldfish case-insensitive`() {
        assertTrue(ProximityProbe.hasEmuNameMarker("Goldfish"))
        assertTrue(ProximityProbe.hasEmuNameMarker("goldfish"))
        assertTrue(ProximityProbe.hasEmuNameMarker("GOLDFISH proximity"))
    }

    @Test
    fun `hasEmuNameMarker detects all 5 substrings`() {
        assertTrue(ProximityProbe.hasEmuNameMarker("avd"))
        assertTrue(ProximityProbe.hasEmuNameMarker("genymotion"))
        assertTrue(ProximityProbe.hasEmuNameMarker("ranchu"))
        assertTrue(ProximityProbe.hasEmuNameMarker("vbox"))
        assertTrue(ProximityProbe.hasEmuNameMarker("goldfish"))
    }

    @Test
    fun `hasEmuNameMarker false for null and empty`() {
        assertFalse(ProximityProbe.hasEmuNameMarker(null))
        assertFalse(ProximityProbe.hasEmuNameMarker(""))
    }

    @Test
    fun `hasEmuNameMarker false for real OEM vendor names`() {
        assertFalse(ProximityProbe.hasEmuNameMarker("AMS AG TMD2725"))
        assertFalse(ProximityProbe.hasEmuNameMarker("Capella CM36686"))
        assertFalse(ProximityProbe.hasEmuNameMarker("STMicroelectronics VL53L0X"))
    }

    @Test
    fun `allSamplesConstant true for identical values`() {
        assertTrue(ProximityProbe.allSamplesConstant(listOf(0.0f, 0.0f)))
        assertTrue(ProximityProbe.allSamplesConstant(listOf(5.0f, 5.0f, 5.0f)))
    }

    @Test
    fun `allSamplesConstant false for varying values`() {
        assertFalse(ProximityProbe.allSamplesConstant(listOf(0.0f, 5.0f)))
        assertFalse(ProximityProbe.allSamplesConstant(listOf(5.0f, 5.0f, 0.0f)))
    }

    @Test
    fun `allSamplesConstant false for single sample`() {
        // Need >= 2 to claim constant.
        assertFalse(ProximityProbe.allSamplesConstant(listOf(0.0f)))
    }

    @Test
    fun `allSamplesConstant false for empty`() {
        assertFalse(ProximityProbe.allSamplesConstant(emptyList()))
    }

    // ── Cross-rank reuse invariants (rank-17/31/32 pattern) ──────────────────

    @Test
    fun `TYPE_PROXIMITY constant reused from rank-24 AccelerometerGyroProbe`() {
        // Drift-safe invariant: if rank-24's TYPE_PROXIMITY constant ever
        // changes (e.g. someone refactors to use the Android framework
        // constant directly), this test fails immediately. Same anchor
        // pattern as rank-17 → rank-11 and rank-31/32 → rank-15.
        assertEquals(8, AccelerometerGyroProbe.TYPE_PROXIMITY)
    }

    @Test
    fun `EMU_NAME_SUBSTRINGS list documents all 5 markers from rank-24 KDoc`() {
        // Rank 24's KDoc lines 32-37 deferred these markers as a follow-up
        // due to the missing vendor/name accessor. Rank 42 picks them up
        // via constructor injection. The list must stay in sync with what
        // rank-24 documents.
        assertEquals(
            listOf("goldfish", "avd", "genymotion", "ranchu", "vbox"),
            ProximityProbe.EMU_NAME_SUBSTRINGS,
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
                "proximity.present",
                "proximity.vendor_name",
                "proximity.emu_name_marker",
                "proximity.sample_summary",
                "proximity.constant_samples",
                "proximity.phone_class",
                "proximity.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `present evidence reflects sensor list`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `missing proximity — present evidence is false`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(sensorManager = sensorManagerWith(sensorTypes = emptyList())),
        )
        val ev = result.evidence.find { it.key == "proximity.present" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `vendor_name evidence reflects supplier value`() = runBlocking {
        val result = makeProbe(vendorName = "AMS AG TMD2725").run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.vendor_name" }
        assertEquals("AMS AG TMD2725", ev?.value)
    }

    @Test
    fun `null vendor_name — evidence is unavailable`() = runBlocking {
        val result = makeProbe(vendorName = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.vendor_name" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `empty vendor_name — evidence is empty placeholder`() = runBlocking {
        val result = makeProbe(vendorName = "").run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.vendor_name" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `emu_name_marker evidence is false for clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.emu_name_marker" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `emu_name_marker evidence is true for Goldfish`() = runBlocking {
        val result = makeProbe(vendorName = "Goldfish").run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.emu_name_marker" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `sample_summary shows sample count and first value`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.sample_summary" }
        // fakeCtx default: 4 samples, first=0.0
        assertEquals("n=4 first=0.0", ev?.value)
    }

    @Test
    fun `sample_summary no samples — placeholder`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                sensorManager = sensorManagerWith(
                    sensorTypes = listOf(AccelerometerGyroProbe.TYPE_PROXIMITY),
                    proximitySamples = emptyList(),
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "proximity.sample_summary" }
        assertEquals("<no samples>", ev?.value)
    }

    @Test
    fun `phone_class evidence true on Pixel`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "proximity.phone_class" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `phone_class evidence false on tablet`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Lenovo Tab P11"))
        val ev = result.evidence.find { it.key == "proximity.phone_class" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Check SensorManager for TYPE_PROXIMITY presence + emulator " +
                "vendor names + (optional) sample variance",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is sensors_proximity`() {
        assertEquals("sensors.proximity", makeProbe().id)
    }

    @Test
    fun `probe rank is 42`() {
        assertEquals(42, makeProbe().rank)
    }

    @Test
    fun `probe category is SENSORS`() {
        assertEquals(ProbeCategory.SENSORS, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-42 severity=low.
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

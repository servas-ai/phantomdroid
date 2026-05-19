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
 * Unit tests for AccelerometerGyroProbe (sensors.accelerometer_gyro, rank 24).
 */
class AccelerometerGyroProbeTest {

    private val probe = AccelerometerGyroProbe()

    // Real-handset sample: 10 frames of jittery accelerometer data near gravity.
    private val realisticSample: SensorSample = SensorSample(
        timestamps = longArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90),
        values = arrayOf(
            floatArrayOf(0.05f, 9.78f, 0.02f),
            floatArrayOf(-0.03f, 9.82f, -0.01f),
            floatArrayOf(0.07f, 9.79f, 0.04f),
            floatArrayOf(-0.04f, 9.81f, 0.03f),
            floatArrayOf(0.02f, 9.80f, -0.02f),
            floatArrayOf(-0.06f, 9.83f, 0.05f),
            floatArrayOf(0.08f, 9.77f, -0.04f),
            floatArrayOf(-0.01f, 9.84f, 0.01f),
            floatArrayOf(0.03f, 9.79f, 0.02f),
            floatArrayOf(-0.05f, 9.82f, -0.03f),
        ),
    )

    // Static emulator sample: 10 frames of the exact same (0, 9.81, 0) vector.
    private val staticEmulatorSample: SensorSample = SensorSample(
        timestamps = longArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90),
        values = Array(10) { floatArrayOf(0.0f, 9.81f, 0.0f) },
    )

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        sensorTypes: List<Int> = listOf(
            AccelerometerGyroProbe.TYPE_ACCELEROMETER,
            AccelerometerGyroProbe.TYPE_GYROSCOPE,
            AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD,
            AccelerometerGyroProbe.TYPE_PRESSURE,
            AccelerometerGyroProbe.TYPE_LIGHT,
            AccelerometerGyroProbe.TYPE_PROXIMITY,
        ),
        accelerometerSample: SensorSample = realisticSample,
        listThrows: Boolean = false,
        sampleThrows: Boolean = false,
        smThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
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
        override fun querySensorManager(): SensorManagerView {
            if (smThrows) throw RuntimeException("simulated SensorManager throw")
            return object : SensorManagerView {
                override fun listSensorTypes(): List<Int> {
                    if (listThrows) throw RuntimeException("simulated listSensorTypes throw")
                    return sensorTypes
                }
                override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample {
                    if (sampleThrows) throw RuntimeException("simulated sampleSensor throw")
                    return if (sensorType == AccelerometerGyroProbe.TYPE_ACCELEROMETER) {
                        accelerometerSample
                    } else {
                        SensorSample(LongArray(0), emptyArray())
                    }
                }
            }
        }
    }

    // ── Clean realistic device ────────────────────────────────────────────────

    @Test
    fun `realistic 6-sensor device with jittery accelerometer — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `realistic device — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `realistic device — has_accelerometer evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "has_accelerometer" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `realistic device — stub_pattern_detected evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "stub_pattern_detected" }
        assertEquals("false", ev?.value)
    }

    // ── Static-variance emulator (score 1.0) ──────────────────────────────────

    @Test
    fun `static accelerometer sample — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(accelerometerSample = staticEmulatorSample))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `static sample — stub_pattern_detected evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(accelerometerSample = staticEmulatorSample))
        val ev = result.evidence.find { it.key == "stub_pattern_detected" }
        assertEquals("true", ev?.value)
    }

    // ── Suspiciously-low variance (score 0.9) ────────────────────────────────

    @Test
    fun `near-zero variance sample — score is 0_9`() = runBlocking {
        // Use very small jitter — small enough to be below 1e-6 variance but
        // not exactly zero. floats at 1e-4 magnitude → variance around 1e-9.
        val tinyJitter = SensorSample(
            timestamps = longArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90),
            values = arrayOf(
                floatArrayOf(0.0001f, 9.81f, 0.0f),
                floatArrayOf(-0.0001f, 9.81f, 0.0f),
                floatArrayOf(0.0001f, 9.81f, 0.0f),
                floatArrayOf(-0.0001f, 9.81f, 0.0f),
                floatArrayOf(0.0001f, 9.81f, 0.0f),
                floatArrayOf(-0.0001f, 9.81f, 0.0f),
                floatArrayOf(0.0001f, 9.81f, 0.0f),
                floatArrayOf(-0.0001f, 9.81f, 0.0f),
                floatArrayOf(0.0001f, 9.81f, 0.0f),
                floatArrayOf(-0.0001f, 9.81f, 0.0f),
            ),
        )
        val result = probe.run(fakeCtx(accelerometerSample = tinyJitter))
        assertEquals(0.9, result.score)
    }

    // ── Missing core sensors (score 0.85) ────────────────────────────────────

    @Test
    fun `only 2 sensor types — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                    AccelerometerGyroProbe.TYPE_GYROSCOPE,
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `missing accelerometer — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_GYROSCOPE,
                    AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD,
                    AccelerometerGyroProbe.TYPE_PRESSURE,
                    AccelerometerGyroProbe.TYPE_LIGHT,
                    AccelerometerGyroProbe.TYPE_PROXIMITY,
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `missing gyroscope — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                    AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD,
                    AccelerometerGyroProbe.TYPE_PRESSURE,
                    AccelerometerGyroProbe.TYPE_LIGHT,
                    AccelerometerGyroProbe.TYPE_PROXIMITY,
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `exactly 4 sensors with accel and gyro — no missing-sensor signal`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                    AccelerometerGyroProbe.TYPE_GYROSCOPE,
                    AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD,
                    AccelerometerGyroProbe.TYPE_PRESSURE,
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Multi-signal precedence (static variance dominates missing-sensor) ────

    @Test
    fun `static sample plus only 2 sensors — score is 1_0 (variance dominates)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                    AccelerometerGyroProbe.TYPE_GYROSCOPE,
                ),
                accelerometerSample = staticEmulatorSample,
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `low variance plus only 2 sensors — score is 0_9 (low variance dominates 0_85)`() = runBlocking {
        val tinyJitter = SensorSample(
            timestamps = LongArray(5) { (it * 10).toLong() },
            values = Array(5) { i ->
                floatArrayOf(if (i % 2 == 0) 0.0001f else -0.0001f, 9.81f, 0.0f)
            },
        )
        val result = probe.run(
            fakeCtx(
                sensorTypes = listOf(
                    AccelerometerGyroProbe.TYPE_ACCELEROMETER,
                    AccelerometerGyroProbe.TYPE_GYROSCOPE,
                ),
                accelerometerSample = tinyJitter,
            ),
        )
        assertEquals(0.9, result.score)
    }

    // ── Sensor accessor unavailable (score 0.5) ──────────────────────────────

    @Test
    fun `querySensorManager throws — score is 0_5 no_signal`() = runBlocking {
        val result = probe.run(fakeCtx(smThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `listSensorTypes throws — score is 0_5 no_signal`() = runBlocking {
        val result = probe.run(fakeCtx(listThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `empty sensor list — score is 0_5 no_signal`() = runBlocking {
        val result = probe.run(fakeCtx(sensorTypes = emptyList()))
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    // ── Sample sub-sampling cases (size < 4 → variance skipped) ──────────────

    @Test
    fun `live sample with only 2 frames — variance check skipped, no false positive`() = runBlocking {
        val tinySample = SensorSample(
            timestamps = longArrayOf(0, 10),
            values = arrayOf(
                floatArrayOf(0.0f, 9.81f, 0.0f),
                floatArrayOf(0.0f, 9.81f, 0.0f),
            ),
        )
        val result = probe.run(fakeCtx(accelerometerSample = tinySample))
        // Only 2 frames — not enough to make a variance call. Falls through
        // to the realistic 6-sensor base path → score 0.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sampleSensor throws — does not crash, falls through to no-variance path`() = runBlocking {
        val result = probe.run(fakeCtx(sampleThrows = true))
        // Sensors all present, accelerometer/gyroscope both there. Sample
        // throws and gets handled → no variance signal → score 0.
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty sample values array — variance check skipped`() = runBlocking {
        val emptyValuesSample = SensorSample(timestamps = LongArray(0), values = emptyArray())
        val result = probe.run(fakeCtx(accelerometerSample = emptyValuesSample))
        assertEquals(0.0, result.score)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all seven documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "sensor_count",
                "has_accelerometer",
                "has_gyroscope",
                "has_barometer",
                "accelerometer.vendor",
                "accelerometer.maxRange",
                "stub_pattern_detected",
            ),
            keys,
        )
    }

    @Test
    fun `accelerometer vendor evidence carries unavailable placeholder`() = runBlocking {
        // Documenting the SensorManagerView metadata gap: the vendor row
        // emits the placeholder "<unavailable>" until a metadata accessor
        // lands. Future work should remove this assertion after wiring up
        // real vendor reads.
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "accelerometer.vendor" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `sensor_count evidence is Int matching list size`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "sensor_count" }
        assertEquals(6, ev?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Enumerate SensorManager sensors and check for emulator " +
                "vendor/name keywords, stub default values, missing-sensor " +
                "patterns, and (optional) live-sample variance",
            result.method,
        )
    }

    // ── Variance helper unit tests ────────────────────────────────────────────

    @Test
    fun `variance of all-same is 0`() {
        val v = variance(listOf(1.0, 1.0, 1.0, 1.0))
        assertEquals(0.0, v, 1e-12)
    }

    @Test
    fun `variance of empty is 0`() {
        assertEquals(0.0, variance(emptyList()), 1e-12)
    }

    @Test
    fun `variance of single-element is 0`() {
        assertEquals(0.0, variance(listOf(42.0)), 1e-12)
    }

    @Test
    fun `variance of 0 1 — population variance is 0_25`() {
        // Pop variance of {0,1}: mean=0.5, ss=0.5, /n=0.25.
        assertEquals(0.25, variance(listOf(0.0, 1.0)), 1e-12)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is sensors_accelerometer_gyro`() {
        assertEquals("sensors.accelerometer_gyro", probe.id)
    }

    @Test
    fun `probe rank is 24`() {
        assertEquals(24, probe.rank)
    }

    @Test
    fun `probe category is SENSORS`() {
        assertEquals(ProbeCategory.SENSORS, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, probe.androidLayer)
    }

    @Test
    fun `probe budget is 300ms`() {
        assertEquals(300L, probe.budgetMs)
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

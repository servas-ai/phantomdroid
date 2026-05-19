// inventory.yml rank 24, mitigation_layer L5
package com.detectorlab.probes.sensors

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #24 — sensors.accelerometer_gyro.
 *
 * Detects emulators and synthetic device profiles by examining the sensor
 * subsystem. The L5 mitigation layer is exactly the "sensor noise, drift,
 * FFT analysis" surface that high-end emulators struggle to replicate — a
 * real handset has 6-12 distinct sensors emitting non-zero noise patterns,
 * while a generic AVD typically reports `accelerometer` + `gyroscope` only,
 * both stuck at exact static values.
 *
 * Implementable signal classes (against current `SensorManagerView`):
 *   1. **Sensor presence** via `listSensorTypes()` — sensor type count and
 *      presence of accelerometer (Sensor.TYPE_ACCELEROMETER=1) and gyroscope
 *      (Sensor.TYPE_GYROSCOPE=4). Emulator tell: <4 distinct types or
 *      missing one of accel/gyro.
 *   2. **Live sample variance** via `sampleSensor(TYPE_ACCELEROMETER, ...)`.
 *      A real handset jitters around the gravity vector (±0.05 m/s² is
 *      typical, even on a stationary table); an emulator emits a literal
 *      static vector `(0.0, 9.81, 0.0)` with zero variance.
 *
 * **Deferred signal classes** (require `SensorManagerView` enhancements that
 * are out of scope for this probe):
 *   - Vendor / name keyword scan ("Goldfish", "AVD", "Genymotion", "ranchu",
 *     "vbox"). The base view does not surface per-sensor `vendor`/`name`
 *     strings.
 *   - Stub-value pattern (version=1 AND resolution=0.0 AND power=0.0).
 *   - `maxRange == 9.8` accelerometer fingerprint.
 *   - `fifoMaxEventCount == 0` batching-absent signal.
 *
 *   Adding a `getSensorMetadata(type): SensorMetadata?` accessor to
 *   `SensorManagerView` would unlock these. Tracked as a follow-up; KDoc
 *   here makes the gap explicit so future work knows what to plumb.
 *
 * Scoring (max wins; never summed):
 *   1.00  live-sample variance == 0 across all axes AND >= 4 samples
 *         observed (perfect-static emulator tell)
 *   0.90  live-sample variance below `STATIC_VARIANCE_THRESHOLD` per axis
 *         (suspiciously low jitter; real handset never produces this)
 *   0.85  sensor count < 4 OR accelerometer missing OR gyroscope missing
 *   0.50  sensor accessor threw / returned nothing AND no other signal fires
 *   0.00  >=4 sensor types including accelerometer+gyroscope, and either
 *         no live sample available or live sample has realistic variance
 *
 * Confidence: 0.95 when the sensor accessor returned a non-empty list;
 * 0.50 when it returned empty / threw.
 *
 * Reference: shared/probes/inventory.yml rank 24 (mitigation_layer L5).
 */
class AccelerometerGyroProbe : Probe {
    override val id = "sensors.accelerometer_gyro"
    override val rank = 24
    override val category = ProbeCategory.SENSORS
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 300L

    companion object {
        // android.hardware.Sensor type constants (mirrored here to avoid
        // android.* import in the pure-JVM probe).
        const val TYPE_ACCELEROMETER = 1
        const val TYPE_MAGNETIC_FIELD = 2
        const val TYPE_GYROSCOPE = 4
        const val TYPE_LIGHT = 5
        const val TYPE_PRESSURE = 6
        const val TYPE_PROXIMITY = 8

        const val MIN_REALISTIC_SENSOR_TYPE_COUNT = 4

        /**
         * Variance threshold below which the accelerometer is suspected to
         * be a static stub. A stationary real handset jitters at ~0.001 m²/s⁴
         * (≈0.03 m/s² stdev); emulators emit exact 0.0. Threshold at 1e-6
         * means "well below the noise floor of any real MEMS accelerometer".
         */
        const val STATIC_VARIANCE_THRESHOLD = 1e-6

        const val LIVE_SAMPLE_DURATION_MS = 200L

        const val SCORE_STATIC_VARIANCE_ZERO = 1.0
        const val SCORE_STATIC_VARIANCE_LOW = 0.9
        const val SCORE_MISSING_SENSORS = 0.85
        const val SCORE_NO_SIGNAL = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.5

        const val METHOD =
            "Enumerate SensorManager sensors and check for emulator " +
                "vendor/name keywords, stub default values, missing-sensor " +
                "patterns, and (optional) live-sample variance"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val sm = try {
                ctx.querySensorManager()
            } catch (_: Throwable) {
                null
            }

            val sensorTypes: List<Int> = try {
                sm?.listSensorTypes() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            val accessorWorked = sm != null && sensorTypes.isNotEmpty()
            val hasAccelerometer = TYPE_ACCELEROMETER in sensorTypes
            val hasGyroscope = TYPE_GYROSCOPE in sensorTypes
            val hasBarometer = TYPE_PRESSURE in sensorTypes
            val sensorCount = sensorTypes.size

            val missingCoreSensors = accessorWorked && (
                sensorCount < MIN_REALISTIC_SENSOR_TYPE_COUNT ||
                    !hasAccelerometer ||
                    !hasGyroscope
                )

            // Live accelerometer sample (only if the accessor worked AND
            // an accelerometer is reported — sampling a non-existent sensor
            // would just waste budget time).
            data class SampleVerdict(val variance: Double?, val sampleSize: Int)

            val sampleVerdict: SampleVerdict = if (accessorWorked && hasAccelerometer) {
                val sample = try {
                    sm!!.sampleSensor(TYPE_ACCELEROMETER, LIVE_SAMPLE_DURATION_MS)
                } catch (_: Throwable) {
                    null
                }
                if (sample == null || sample.values.isEmpty() || sample.values.size < 4) {
                    SampleVerdict(variance = null, sampleSize = sample?.values?.size ?: 0)
                } else {
                    val perAxis = (0 until sample.values[0].size).map { axis ->
                        val xs = sample.values.map { it[axis].toDouble() }
                        variance(xs)
                    }
                    SampleVerdict(
                        variance = perAxis.maxOrNull() ?: 0.0,
                        sampleSize = sample.values.size,
                    )
                }
            } else {
                SampleVerdict(variance = null, sampleSize = 0)
            }

            val variance = sampleVerdict.variance
            val staticVarianceZero = variance != null && variance == 0.0 && sampleVerdict.sampleSize >= 4
            val staticVarianceLow = variance != null && variance > 0.0 &&
                variance < STATIC_VARIANCE_THRESHOLD

            val candidates = buildList {
                if (staticVarianceZero) add(SCORE_STATIC_VARIANCE_ZERO)
                if (staticVarianceLow) add(SCORE_STATIC_VARIANCE_LOW)
                if (missingCoreSensors) add(SCORE_MISSING_SENSORS)
            }

            val score = when {
                candidates.isNotEmpty() -> candidates.max()
                !accessorWorked -> SCORE_NO_SIGNAL
                else -> SCORE_CLEAN
            }

            val confidence = if (accessorWorked) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val stubPatternDetected = staticVarianceZero || staticVarianceLow

            val evidence = listOf(
                Evidence("sensor_count", sensorCount, expected = ">=6"),
                Evidence("has_accelerometer", hasAccelerometer.toString(), expected = "true"),
                Evidence("has_gyroscope", hasGyroscope.toString(), expected = "true"),
                Evidence("has_barometer", hasBarometer.toString(), expected = "true"),
                Evidence(
                    key = "accelerometer.vendor",
                    value = "<unavailable>",
                    expected = "<known OEM, not Goldfish>",
                ),
                Evidence(
                    key = "accelerometer.maxRange",
                    value = "<unavailable>",
                    expected = "<≠ exact 9.8>",
                ),
                Evidence(
                    key = "stub_pattern_detected",
                    value = stubPatternDetected.toString(),
                    expected = "false",
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "AccelerometerGyroProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

/** Population variance of [xs]. Returns 0.0 for empty / single-element input. */
internal fun variance(xs: List<Double>): Double {
    if (xs.size <= 1) return 0.0
    val mean = xs.sum() / xs.size
    var ss = 0.0
    for (x in xs) ss += (x - mean) * (x - mean)
    return ss / xs.size
}

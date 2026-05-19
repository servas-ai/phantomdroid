// inventory.yml rank 44, mitigation_layer L5
package com.detectorlab.probes.sensors

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe
import kotlin.math.sqrt

/**
 * Probe #44 — sensors.magnetometer.
 *
 * Detects magnetometer (compass) anomalies that a real handset never
 * produces:
 *   • Missing TYPE_MAGNETIC_FIELD on a phone-class device (real phones use
 *     it for compass apps; Pixel/Galaxy/Xiaomi all ship one).
 *   • Vendor / name string matching the rank-24 deferred emulator markers
 *     (`goldfish`, `avd`, `genymotion`, `ranchu`, `vbox`).
 *   • Live-sample 3-axis magnitude implausibly low (`< 5 µT`) — Earth's
 *     ambient magnetic field is 25-65 µT everywhere on the surface; even
 *     inside a shielded room you measure ~10-15 µT. Exact 0 is impossible
 *     outside a degaussed Faraday cage and would be the stub default.
 *   • Live-sample magnitude implausibly high (`> 200 µT`) — Earth's field
 *     never exceeds ~65 µT and even strong local magnets (speakers, motors)
 *     cap below 200 µT at sensor distance.
 *   • Exact AVD constants: `(0, 0, 0)` (uninitialized stub),
 *     `(0, 0, -42)` (Goldfish historical magnetic-field default;
 *     `external/qemu/.../sensors/sensors_qemu.c` shipped this triple as the
 *     hardcoded magnetic-vector reply on early AVD images), and
 *     `(1, 1, 1)` (Genymotion/ranchu sentinel) — known emulator stub triples
 *     that no real MEMS sensor ever emits. Note `(1, 1, 1)` also has
 *     magnitude ~1.73 µT and so *would* trigger the implausible-low rule
 *     too; the cascade order guarantees AVD-stub (1.0) fires first.
 *
 * Reuses rank-24 [AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD] constant via
 * direct companion reference, with an invariant test anchoring the
 * cross-rank dependency. Reuses rank-42 [ProximityProbe.hasEmuNameMarker]
 * and [ProximityProbe.EMU_NAME_SUBSTRINGS] for the vendor-name scan. Same
 * rank-17/31/32/42/43 extract-or-anchor pattern.
 *
 * **`SensorManagerView` vendor/name gap.** Same as rank 24 / 42 / 43 — the
 * view exposes `listSensorTypes()` and `sampleSensor()` only. This probe
 * accepts a `vendorNameSupplier` constructor parameter so wrappers can
 * pass vendor/name when available; production no-arg ctor returns null
 * and the emu-name rule simply doesn't fire.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Vendor/name contains an emulator marker (case-insensitive)
 *   1.00  Any sample is an exact AVD-stub triple (`(0,0,0)`, `(0,0,-42)`,
 *         or `(1,1,1)` — never produced by real MEMS magnetometers)
 *   0.85  Sensor accessor worked AND no TYPE_MAGNETIC_FIELD AND model is
 *         phone-class
 *   0.85  Any sample magnitude exceeds [MAX_PLAUSIBLE_MAGNITUDE_UT]
 *         (= 200 µT)
 *   0.85  All sample magnitudes below [MIN_PLAUSIBLE_MAGNITUDE_UT]
 *         (= 5 µT) AND ≥ 2 samples observed (Faraday-cage-like reading
 *         on a phone is unphysical outside a lab)
 *   0.00  Sensor present + dynamic plausible 3-axis + no emu markers
 *
 * Confidence:
 *   0.95  SensorManager accessor returned
 *   0.50  Accessor threw or returned null
 *
 * Reference: shared/probes/inventory.yml rank 44 (mitigation_layer L5).
 */
class MagnetometerProbe(
    private val vendorNameSupplier: () -> String? = { null },
) : Probe {
    override val id = "sensors.magnetometer"
    override val rank = 44
    override val category = ProbeCategory.SENSORS
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 250L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val LIVE_SAMPLE_DURATION_MS = 200L
        const val MIN_SAMPLES_FOR_VARIANCE_CHECK = 2

        /**
         * Earth's magnetic field is 25-65 µT at the surface; even inside a
         * shielded room you read ~10-15 µT. Anything below 5 µT for a
         * sustained measurement is unphysical for a phone sensor outside a
         * degaussed Faraday cage. Above 200 µT is impossible without a
         * permanent magnet pressed against the sensor.
         */
        const val MIN_PLAUSIBLE_MAGNITUDE_UT = 5.0f
        const val MAX_PLAUSIBLE_MAGNITUDE_UT = 200.0f

        /**
         * Known emulator AVD stub triples. Real MEMS magnetometers never
         * report any of these exact 3-axis values — they always have
         * floating-point jitter at the sub-µT level.
         */
        val AVD_STUB_TRIPLES: List<Triple<Float, Float, Float>> = listOf(
            Triple(0.0f, 0.0f, 0.0f),
            Triple(0.0f, 0.0f, -42.0f),
            Triple(1.0f, 1.0f, 1.0f),
        )

        const val PATTERN_EMU_NAME = "emu_name"
        const val PATTERN_AVD_STUB_TRIPLE = "avd_stub_triple"
        const val PATTERN_MISSING_ON_PHONE = "missing_on_phone"
        const val PATTERN_IMPLAUSIBLE_HIGH = "implausible_high"
        const val PATTERN_IMPLAUSIBLE_LOW = "implausible_low"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_NAME = 1.0
        const val SCORE_AVD_STUB_TRIPLE = 1.0
        const val SCORE_MISSING_ON_PHONE = 0.85
        const val SCORE_IMPLAUSIBLE_HIGH = 0.85
        const val SCORE_IMPLAUSIBLE_LOW = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Check SensorManager for TYPE_MAGNETIC_FIELD presence + emulator " +
                "vendor names + 3-axis magnitude plausibility (5-200 µT range) + " +
                "AVD-stub-triple detection"

        /** Computes √(x² + y² + z²) for a 3-axis sample. */
        internal fun magnitude(x: Float, y: Float, z: Float): Double {
            val xd = x.toDouble()
            val yd = y.toDouble()
            val zd = z.toDouble()
            return sqrt(xd * xd + yd * yd + zd * zd)
        }

        /** True iff any triple matches an exact AVD-stub value. */
        internal fun hasAvdStubTriple(
            triples: List<Triple<Float, Float, Float>>,
        ): Boolean = triples.any { it in AVD_STUB_TRIPLES }

        /** True iff any triple's magnitude exceeds [MAX_PLAUSIBLE_MAGNITUDE_UT]. */
        internal fun hasImplausiblyHighMagnitude(
            triples: List<Triple<Float, Float, Float>>,
        ): Boolean = triples.any {
            magnitude(it.first, it.second, it.third) > MAX_PLAUSIBLE_MAGNITUDE_UT
        }

        /**
         * True iff ALL triple magnitudes are below [MIN_PLAUSIBLE_MAGNITUDE_UT]
         * AND we have at least [MIN_SAMPLES_FOR_VARIANCE_CHECK] samples.
         * Single-sample brief excursions don't fire — we need ≥2 sustained
         * readings before claiming the device is in a magnetic-shielded state.
         */
        internal fun allMagnitudesImplausiblyLow(
            triples: List<Triple<Float, Float, Float>>,
        ): Boolean {
            if (triples.size < MIN_SAMPLES_FOR_VARIANCE_CHECK) return false
            return triples.all {
                magnitude(it.first, it.second, it.third) < MIN_PLAUSIBLE_MAGNITUDE_UT
            }
        }
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
            val accessorWorked = sm != null
            val hasMag = AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD in sensorTypes

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val vendorName: String? = try {
                vendorNameSupplier()
            } catch (_: Throwable) {
                null
            }
            val emuNameMarker = ProximityProbe.hasEmuNameMarker(vendorName)

            val triples: List<Triple<Float, Float, Float>> = if (accessorWorked && hasMag) {
                try {
                    val sample = sm!!.sampleSensor(
                        AccelerometerGyroProbe.TYPE_MAGNETIC_FIELD, LIVE_SAMPLE_DURATION_MS,
                    )
                    if (sample.values.isEmpty()) emptyList()
                    else sample.values.mapNotNull { frame ->
                        if (frame.size < 3) null
                        else Triple(frame[0], frame[1], frame[2])
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val avdStubTriple = hasAvdStubTriple(triples)
            val implausibleHigh = hasImplausiblyHighMagnitude(triples)
            val implausibleLow = allMagnitudesImplausiblyLow(triples)
            val missingOnPhone = accessorWorked && !hasMag && phoneClass

            val (pattern, score) = when {
                emuNameMarker ->
                    PATTERN_EMU_NAME to SCORE_EMU_NAME
                avdStubTriple ->
                    PATTERN_AVD_STUB_TRIPLE to SCORE_AVD_STUB_TRIPLE
                missingOnPhone ->
                    PATTERN_MISSING_ON_PHONE to SCORE_MISSING_ON_PHONE
                implausibleHigh ->
                    PATTERN_IMPLAUSIBLE_HIGH to SCORE_IMPLAUSIBLE_HIGH
                implausibleLow ->
                    PATTERN_IMPLAUSIBLE_LOW to SCORE_IMPLAUSIBLE_LOW
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (accessorWorked) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val vendorDisplay = vendorName?.ifEmpty { "<empty>" } ?: "<unavailable>"
            val sampleSize = triples.size
            val sampleSummary = when {
                triples.isEmpty() -> "<no samples>"
                else -> {
                    val first = triples.first()
                    val mag = magnitude(first.first, first.second, first.third)
                    "n=$sampleSize first=(${first.first},${first.second},${first.third}) mag=$mag"
                }
            }

            val evidence = listOf(
                Evidence(
                    key = "magnetometer.present",
                    value = hasMag.toString(),
                    expected = "true on phone-class",
                ),
                Evidence(
                    key = "magnetometer.vendor_name",
                    value = vendorDisplay,
                    expected = "<known OEM, not Goldfish/AVD/Genymotion>",
                ),
                Evidence(
                    key = "magnetometer.emu_name_marker",
                    value = emuNameMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "magnetometer.sample_summary",
                    value = sampleSummary,
                    expected = "magnitude in 20-65 µT range",
                ),
                Evidence(
                    key = "magnetometer.avd_stub_triple",
                    value = avdStubTriple.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "magnetometer.implausible_high",
                    value = implausibleHigh.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "magnetometer.implausible_low",
                    value = implausibleLow.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "magnetometer.phone_class",
                    value = phoneClass.toString(),
                    expected = "<used in scoring guard>",
                ),
                Evidence(
                    key = "magnetometer.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
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
                "MagnetometerProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

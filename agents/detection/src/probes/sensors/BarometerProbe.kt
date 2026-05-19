// inventory.yml rank 45, mitigation_layer L5
package com.detectorlab.probes.sensors

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #45 — sensors.barometer.
 *
 * Detects atmospheric-pressure-sensor anomalies that a real handset never
 * produces:
 *   • Missing TYPE_PRESSURE on a model from [KNOWN_BAROMETER_MODELS] — a
 *     **weak signal** (0.5), because most budget Android phones legitimately
 *     ship without a barometer. Only flagships (Pixel 6+, Galaxy S22+,
 *     OnePlus 9+, Xiaomi 13+) reliably include one. Distinct from rank-42
 *     proximity / rank-43 light / rank-44 magnetometer which are nearly
 *     universal and score 0.85 on missing.
 *   • Vendor / name string matching the rank-24 deferred emulator markers
 *     (`goldfish`, `avd`, `genymotion`, `ranchu`, `vbox`).
 *   • Live-sample exactly `1013.25` (sea-level constant — Goldfish stub) or
 *     `1000.0` (round-number stub) AND constant across ≥ 2 samples.
 *   • Live-sample outside `[MIN_PLAUSIBLE_HPA, MAX_PLAUSIBLE_HPA]` (300-1100
 *     hPa) — below 300 hPa would be Mount-Everest altitude (no consumer
 *     phone runs there), above 1100 hPa is unphysical at the surface
 *     (record low-altitude pressure is ~1085 hPa).
 *
 * Reuses rank-24 [AccelerometerGyroProbe.TYPE_PRESSURE] constant via direct
 * companion reference, with an invariant test anchoring the cross-rank
 * dependency. Reuses rank-42 [ProximityProbe.hasEmuNameMarker] and
 * [ProximityProbe.EMU_NAME_SUBSTRINGS] for the vendor-name scan. Same
 * rank-17/31/32/42/43/44 extract-or-anchor pattern.
 *
 * **Phone-class gate intentionally NOT used.** Unlike rank-42/43/44 which
 * use [NetworkTypeProbe.isPhoneClassModel] to gate missing-sensor on
 * phone-class, this probe uses an explicit allowlist of flagship models
 * because barometer presence is a feature distinction within phone-class,
 * not a phone-vs-tablet distinction. A Pixel 4a (phone-class) legitimately
 * has no barometer; only Pixel 6+ adds it.
 *
 * **`SensorManagerView` vendor/name gap.** Same as rank 24 / 42 / 43 / 44.
 * Constructor-injected `vendorNameSupplier`; production no-arg ctor returns
 * null and the emu-name rule simply doesn't fire.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Vendor/name contains an emulator marker (case-insensitive)
 *   0.95  Any sample magnitude outside `[300, 1100]` hPa (impossible at
 *         realistic altitudes a phone operates at)
 *   0.85  All samples exactly `1013.25` OR all exactly `1000.0` OR all
 *         exactly `0.0` AND ≥ 2 samples observed (stub)
 *   0.50  Sensor accessor worked AND no TYPE_PRESSURE AND model is in
 *         [KNOWN_BAROMETER_MODELS] (weak signal — flagship missing
 *         feature, but could be a regional SKU variant)
 *   0.00  Sensor present + dynamic plausible pressure + no emu markers, OR
 *         missing on a non-flagship model
 *
 * Confidence:
 *   0.95  SensorManager accessor returned
 *   0.50  Accessor threw or returned null
 *
 * Reference: shared/probes/inventory.yml rank 45 (mitigation_layer L5).
 */
class BarometerProbe(
    private val vendorNameSupplier: () -> String? = { null },
) : Probe {
    override val id = "sensors.barometer"
    override val rank = 45
    override val category = ProbeCategory.SENSORS
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 250L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val LIVE_SAMPLE_DURATION_MS = 200L
        const val MIN_SAMPLES_FOR_STUB_CHECK = 2

        /**
         * Plausibility ceiling/floor in hPa.
         *   300 hPa  ≈ Mt. Everest summit altitude (8848 m) — no consumer
         *              phone operates above this for sustained measurement
         *  1100 hPa ≈ above the all-time-record sea-level pressure
         *              (~1084 hPa, Siberia 1968); a barometer reporting above
         *              this is broken or stubbed
         */
        const val MIN_PLAUSIBLE_HPA = 300.0f
        const val MAX_PLAUSIBLE_HPA = 1100.0f

        const val STUB_PRESSURE_SEA_LEVEL = 1013.25f
        const val STUB_PRESSURE_ROUND = 1000.0f
        const val STUB_PRESSURE_ZERO = 0.0f

        /**
         * Models known to ship with a barometer. Used to gate the
         * "missing sensor → 0.5" rule. Substring match against
         * `ro.product.model` (case-insensitive). Conservative list —
         * better to under-flag a flagship than to false-positive every
         * budget device.
         *
         * Selection criteria:
         *   • Pixel 6 and later (Pixel 6/7/8 + Pro variants + Fold)
         *   • Galaxy S22 and later (model codes SM-S901..SM-S938)
         *   • OnePlus 9 and later
         *   • Xiaomi 13 and later (Mi 13, Xiaomi 13, 14)
         */
        val KNOWN_BAROMETER_MODELS: List<String> = listOf(
            "pixel 6", "pixel 7", "pixel 8", "pixel 9", "pixel fold",
            "sm-s901", "sm-s906", "sm-s908",   // Galaxy S22 family
            "sm-s911", "sm-s916", "sm-s918",   // Galaxy S23 family
            "sm-s921", "sm-s926", "sm-s928",   // Galaxy S24 family
            "sm-s931", "sm-s936", "sm-s938",   // Galaxy S25 family
            "oneplus 9", "oneplus 10", "oneplus 11", "oneplus 12",
            "mi 13", "mi 14", "xiaomi 13", "xiaomi 14",
        )

        const val PATTERN_EMU_NAME = "emu_name"
        const val PATTERN_OUT_OF_RANGE = "out_of_range"
        const val PATTERN_CONSTANT_STUB = "constant_stub"
        const val PATTERN_MISSING_ON_FLAGSHIP = "missing_on_flagship"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_NAME = 1.0
        const val SCORE_OUT_OF_RANGE = 0.95
        const val SCORE_CONSTANT_STUB = 0.85
        const val SCORE_MISSING_ON_FLAGSHIP = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Check SensorManager for TYPE_PRESSURE presence + emulator vendor " +
                "names + plausibility (300-1100 hPa range, non-zero) + sample " +
                "variance. Score 'missing sensor' lower than other sensor probes " +
                "since barometer is not universal."

        /**
         * True iff [model] is in [KNOWN_BAROMETER_MODELS] (case-insensitive
         * substring match). Returns false for null/empty.
         */
        internal fun isKnownBarometerModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return KNOWN_BAROMETER_MODELS.any { it in lower }
        }

        /** True iff any sample is outside `[MIN_PLAUSIBLE_HPA, MAX_PLAUSIBLE_HPA]`. */
        internal fun hasOutOfRangePressure(values: List<Float>): Boolean =
            values.any { it < MIN_PLAUSIBLE_HPA || it > MAX_PLAUSIBLE_HPA }

        /**
         * True iff all samples are exactly one of `{1013.25, 1000.0, 0.0}`
         * AND we have at least [MIN_SAMPLES_FOR_STUB_CHECK] samples.
         */
        internal fun matchesConstantStub(values: List<Float>): Boolean {
            if (values.size < MIN_SAMPLES_FOR_STUB_CHECK) return false
            val first = values[0]
            if (first != STUB_PRESSURE_SEA_LEVEL &&
                first != STUB_PRESSURE_ROUND &&
                first != STUB_PRESSURE_ZERO
            ) return false
            return values.all { it == first }
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
            val hasPressure = AccelerometerGyroProbe.TYPE_PRESSURE in sensorTypes

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val knownBarometerModel = isKnownBarometerModel(model)

            val vendorName: String? = try {
                vendorNameSupplier()
            } catch (_: Throwable) {
                null
            }
            val emuNameMarker = ProximityProbe.hasEmuNameMarker(vendorName)

            val sampleValues: List<Float> = if (accessorWorked && hasPressure) {
                try {
                    val sample = sm!!.sampleSensor(
                        AccelerometerGyroProbe.TYPE_PRESSURE, LIVE_SAMPLE_DURATION_MS,
                    )
                    if (sample.values.isEmpty()) emptyList()
                    else sample.values.mapNotNull { it.getOrNull(0) }
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val outOfRange = hasOutOfRangePressure(sampleValues)
            val constantStub = matchesConstantStub(sampleValues)
            val missingOnFlagship = accessorWorked && !hasPressure && knownBarometerModel

            val (pattern, score) = when {
                emuNameMarker ->
                    PATTERN_EMU_NAME to SCORE_EMU_NAME
                outOfRange ->
                    PATTERN_OUT_OF_RANGE to SCORE_OUT_OF_RANGE
                constantStub ->
                    PATTERN_CONSTANT_STUB to SCORE_CONSTANT_STUB
                missingOnFlagship ->
                    PATTERN_MISSING_ON_FLAGSHIP to SCORE_MISSING_ON_FLAGSHIP
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (accessorWorked) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val vendorDisplay = vendorName?.ifEmpty { "<empty>" } ?: "<unavailable>"
            val sampleSize = sampleValues.size
            val sampleSummary = when {
                sampleValues.isEmpty() -> "<no samples>"
                else -> {
                    val avg = sampleValues.sum() / sampleSize
                    "n=$sampleSize first=${sampleValues.first()} avg=$avg"
                }
            }

            val evidence = listOf(
                Evidence(
                    key = "barometer.present",
                    value = hasPressure.toString(),
                    expected = "true on known-barometer-model phones",
                ),
                Evidence(
                    key = "barometer.vendor_name",
                    value = vendorDisplay,
                    expected = "<known OEM, not Goldfish/AVD/Genymotion>",
                ),
                Evidence(
                    key = "barometer.emu_name_marker",
                    value = emuNameMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "barometer.sample_summary",
                    value = sampleSummary,
                    expected = "dynamic value in 950-1050 hPa",
                ),
                Evidence(
                    key = "barometer.out_of_range",
                    value = outOfRange.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "barometer.constant_stub",
                    value = constantStub.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "barometer.is_known_barometer_model",
                    value = knownBarometerModel.toString(),
                    expected = "<used in scoring guard>",
                ),
                Evidence(
                    key = "barometer.pattern",
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
                "BarometerProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

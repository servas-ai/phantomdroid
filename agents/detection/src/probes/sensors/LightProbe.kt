// inventory.yml rank 43, mitigation_layer L5
package com.detectorlab.probes.sensors

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #43 — sensors.light.
 *
 * Detects ambient-light-sensor anomalies that a real handset never produces:
 *   • Missing TYPE_LIGHT on a phone-class device (auto-brightness needs it
 *     — Pixel / Galaxy / Xiaomi all ship one).
 *   • Vendor / name string matching the rank-24 deferred emulator markers
 *     (`goldfish`, `avd`, `genymotion`, `ranchu`, `vbox`).
 *   • Live samples returning a known stub value (`0.0` or `1000.0`) AND not
 *     varying — real sensors jitter at the 0.1-1.0 lux noise floor.
 *   • Lux value above the physical plausibility ceiling
 *     (`100,000` lux ≈ direct equatorial noon sun) — emulators can return
 *     `999_999` or `Float.MAX_VALUE` from sloppy stubs.
 *
 * Reuses rank-24 [AccelerometerGyroProbe.TYPE_LIGHT] constant via direct
 * companion reference, with an invariant test anchoring the cross-rank
 * dependency. Same rank-17/31/32 extract-or-anchor pattern; mirrors rank-42
 * [ProximityProbe]'s use of the same constant family.
 *
 * Reuses rank-42 [ProximityProbe.EMU_NAME_SUBSTRINGS] for the emu-vendor
 * scan (rank 24 deferred this surface; rank 42 picked it up and documented
 * the contract; rank 43 keeps a single canonical list).
 *
 * **`SensorManagerView` vendor/name gap.** Same as rank 24 / rank 42 —
 * the view exposes `listSensorTypes()` and `sampleSensor()` only. This
 * probe accepts a `vendorNameSupplier` constructor parameter so wrappers
 * can pass vendor/name when available; production no-arg ctor returns
 * null and the emu-name rule simply doesn't fire.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Vendor/name contains an emulator marker (case-insensitive)
 *   0.85  Sensor accessor worked AND no TYPE_LIGHT in sensor list AND
 *         model is phone-class
 *   0.85  Any sample exceeds [MAX_PLAUSIBLE_LUX] (= 100_000 lux)
 *   0.85  Sample average matches a known stub value (`0.0` or `1000.0`)
 *         AND samples are constant across ≥ 2 readings
 *   0.00  Sensor present + dynamic plausible lux + no emu markers
 *
 * Confidence:
 *   0.95  SensorManager accessor returned
 *   0.50  Accessor threw or returned null
 *
 * Reference: shared/probes/inventory.yml rank 43 (mitigation_layer L5).
 */
class LightProbe(
    private val vendorNameSupplier: () -> String? = { null },
) : Probe {
    override val id = "sensors.light"
    override val rank = 43
    override val category = ProbeCategory.SENSORS
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 250L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val LIVE_SAMPLE_DURATION_MS = 200L
        const val MIN_SAMPLES_FOR_STUB_CHECK = 2

        /**
         * Physical plausibility ceiling. Direct equatorial noon sun is
         * ~100_000 lux. Any reading above this is a stub error or a sensor
         * fault; real devices clamp their light-sensor reports below this.
         */
        const val MAX_PLAUSIBLE_LUX = 100_000.0f

        /** Known emulator stub lux values that ALSO appear as constants. */
        const val STUB_LUX_DARK = 0.0f
        const val STUB_LUX_INDOOR = 1000.0f

        const val PATTERN_EMU_NAME = "emu_name"
        const val PATTERN_MISSING_ON_PHONE = "missing_on_phone"
        const val PATTERN_IMPLAUSIBLE_LUX = "implausible_lux"
        const val PATTERN_CONSTANT_STUB = "constant_stub"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_NAME = 1.0
        const val SCORE_MISSING_ON_PHONE = 0.85
        const val SCORE_IMPLAUSIBLE_LUX = 0.85
        const val SCORE_CONSTANT_STUB = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Check SensorManager for TYPE_LIGHT presence + emulator vendor " +
                "names + (optional) lux-value plausibility and sample variance"

        /** True iff any sample exceeds [MAX_PLAUSIBLE_LUX]. */
        internal fun hasImplausibleLux(values: List<Float>): Boolean =
            values.any { it > MAX_PLAUSIBLE_LUX }

        /**
         * True iff all samples are exactly [STUB_LUX_DARK] or all are exactly
         * [STUB_LUX_INDOOR], AND we have at least [MIN_SAMPLES_FOR_STUB_CHECK].
         * Identical-but-non-stub readings (e.g. all 250.0) are NOT flagged —
         * real sensors can briefly report the same lux in stable conditions.
         */
        internal fun matchesConstantStub(values: List<Float>): Boolean {
            if (values.size < MIN_SAMPLES_FOR_STUB_CHECK) return false
            val first = values[0]
            if (first != STUB_LUX_DARK && first != STUB_LUX_INDOOR) return false
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
            val hasLight = AccelerometerGyroProbe.TYPE_LIGHT in sensorTypes

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

            val sampleValues: List<Float> = if (accessorWorked && hasLight) {
                try {
                    val sample = sm!!.sampleSensor(
                        AccelerometerGyroProbe.TYPE_LIGHT, LIVE_SAMPLE_DURATION_MS,
                    )
                    if (sample.values.isEmpty()) emptyList()
                    else sample.values.mapNotNull { it.getOrNull(0) }
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val implausibleLux = hasImplausibleLux(sampleValues)
            val constantStub = matchesConstantStub(sampleValues)
            val missingOnPhone = accessorWorked && !hasLight && phoneClass

            val (pattern, score) = when {
                emuNameMarker ->
                    PATTERN_EMU_NAME to SCORE_EMU_NAME
                missingOnPhone ->
                    PATTERN_MISSING_ON_PHONE to SCORE_MISSING_ON_PHONE
                implausibleLux ->
                    PATTERN_IMPLAUSIBLE_LUX to SCORE_IMPLAUSIBLE_LUX
                constantStub ->
                    PATTERN_CONSTANT_STUB to SCORE_CONSTANT_STUB
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
                    key = "light.present",
                    value = hasLight.toString(),
                    expected = "true on phone-class",
                ),
                Evidence(
                    key = "light.vendor_name",
                    value = vendorDisplay,
                    expected = "<known OEM, not Goldfish/AVD/Genymotion>",
                ),
                Evidence(
                    key = "light.emu_name_marker",
                    value = emuNameMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "light.sample_summary",
                    value = sampleSummary,
                    expected = "dynamic lux when sensor present",
                ),
                Evidence(
                    key = "light.implausible_lux",
                    value = implausibleLux.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "light.constant_stub",
                    value = constantStub.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "light.phone_class",
                    value = phoneClass.toString(),
                    expected = "<used in scoring guard>",
                ),
                Evidence(
                    key = "light.pattern",
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
                "LightProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

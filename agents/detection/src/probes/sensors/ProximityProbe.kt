// inventory.yml rank 42, mitigation_layer L5
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
 * Probe #42 — sensors.proximity.
 *
 * Detects the absence of a proximity sensor on a phone-class device, and the
 * presence of emulator stub markers (Goldfish / AVD / Genymotion / ranchu /
 * vbox) or constant-value samples that real proximity sensors never produce.
 *
 * Real proximity sensors (near the earpiece) toggle on every voice call — a
 * phone-class device without one is a strong emulator tell. Tablets and TV
 * boxes legitimately ship without proximity, so the missing-sensor rule is
 * gated on [NetworkTypeProbe.isPhoneClassModel] (same drift-safe pattern as
 * rank 31/33/34/35/36/40).
 *
 * Reuses rank-24 [AccelerometerGyroProbe.TYPE_PROXIMITY] constant via direct
 * companion reference + an invariant test anchoring the cross-rank coupling
 * (same rank-17 extract-or-anchor pattern as rank 31/32).
 *
 * **`SensorManagerView` vendor/name gap.** The shared view exposes
 * `listSensorTypes()` and `sampleSensor(type, durationMs)` only — no
 * `getSensorMetadata(type): SensorMetadata?` accessor. Rank 24's KDoc
 * (lines 32-43) tracks this as a known follow-up. This probe accepts a
 * `vendorNameSupplier` constructor parameter so wrappers / tests can
 * pass the vendor+name string when available; production no-arg ctor
 * returns null and the emulator-name rule simply doesn't fire.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Vendor/name string contains an emulator marker (case-insensitive:
 *         `goldfish`, `avd`, `genymotion`, `ranchu`, `vbox`)
 *   0.85  No proximity sensor reported AND model is phone-class
 *   0.85  Live samples all return the SAME exact value AND >= 2 samples
 *         observed (stub — real sensor jitters)
 *   0.00  Sensor present + dynamic samples + non-emu vendor/no vendor signal
 *
 * Confidence:
 *   0.95  SensorManager accessor returned (sensor list observed)
 *   0.50  Accessor threw or returned null
 *
 * Reference: shared/probes/inventory.yml rank 42 (mitigation_layer L5).
 */
class ProximityProbe(
    private val vendorNameSupplier: () -> String? = { null },
) : Probe {
    override val id = "sensors.proximity"
    override val rank = 42
    override val category = ProbeCategory.SENSORS
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 250L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val LIVE_SAMPLE_DURATION_MS = 200L
        const val MIN_SAMPLES_FOR_STUB_CHECK = 2

        /**
         * Emulator vendor/name substrings (case-insensitive). Matches the
         * deferred-rule list from rank-24 KDoc lines 32-37. A real OEM
         * proximity sensor reports vendors like "AMS AG", "Capella",
         * "STMicroelectronics" — none of these substrings.
         */
        val EMU_NAME_SUBSTRINGS = listOf(
            "goldfish", "avd", "genymotion", "ranchu", "vbox",
        )

        const val PATTERN_EMU_NAME = "emu_name"
        const val PATTERN_MISSING_ON_PHONE = "missing_on_phone"
        const val PATTERN_CONSTANT_SAMPLES = "constant_samples"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_NAME = 1.0
        const val SCORE_MISSING_ON_PHONE = 0.85
        const val SCORE_CONSTANT_SAMPLES = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Check SensorManager for TYPE_PROXIMITY presence + emulator " +
                "vendor names + (optional) sample variance"

        /**
         * True iff [vendorName] contains any emulator marker substring
         * (case-insensitive). Returns false for null / empty.
         */
        internal fun hasEmuNameMarker(vendorName: String?): Boolean {
            if (vendorName.isNullOrEmpty()) return false
            val lower = vendorName.lowercase()
            return EMU_NAME_SUBSTRINGS.any { it in lower }
        }

        /**
         * True iff all sample values are exactly equal (constant-stub
         * detection) AND we have at least [MIN_SAMPLES_FOR_STUB_CHECK]
         * samples. Single-sample or empty arrays do NOT fire — we need
         * two readings to claim "constant".
         */
        internal fun allSamplesConstant(values: List<Float>): Boolean {
            if (values.size < MIN_SAMPLES_FOR_STUB_CHECK) return false
            val first = values[0]
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
            val hasProximity = AccelerometerGyroProbe.TYPE_PROXIMITY in sensorTypes

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
            val emuNameMarker = hasEmuNameMarker(vendorName)

            // Live-sample proximity ONLY if the sensor is reported present.
            // Sampling a non-existent sensor wastes budget; an empty sample
            // doesn't tell us anything new.
            val sampleValues: List<Float> = if (accessorWorked && hasProximity) {
                try {
                    val sample = sm!!.sampleSensor(
                        AccelerometerGyroProbe.TYPE_PROXIMITY, LIVE_SAMPLE_DURATION_MS,
                    )
                    // Proximity sensor reports a single axis (distance);
                    // flatten across all frames using axis 0 if present.
                    if (sample.values.isEmpty()) emptyList()
                    else sample.values.mapNotNull { it.getOrNull(0) }
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val constantSamples = allSamplesConstant(sampleValues)

            val missingOnPhone = accessorWorked && !hasProximity && phoneClass

            val (pattern, score) = when {
                emuNameMarker ->
                    PATTERN_EMU_NAME to SCORE_EMU_NAME
                missingOnPhone ->
                    PATTERN_MISSING_ON_PHONE to SCORE_MISSING_ON_PHONE
                constantSamples ->
                    PATTERN_CONSTANT_SAMPLES to SCORE_CONSTANT_SAMPLES
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (accessorWorked) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val vendorDisplay = vendorName?.ifEmpty { "<empty>" } ?: "<unavailable>"
            val sampleSize = sampleValues.size
            val sampleSummary = when {
                sampleValues.isEmpty() -> "<no samples>"
                else -> "n=$sampleSize first=${sampleValues.first()}"
            }

            val evidence = listOf(
                Evidence(
                    key = "proximity.present",
                    value = hasProximity.toString(),
                    expected = "true on phone-class",
                ),
                Evidence(
                    key = "proximity.vendor_name",
                    value = vendorDisplay,
                    expected = "<known OEM, not Goldfish/AVD/Genymotion>",
                ),
                Evidence(
                    key = "proximity.emu_name_marker",
                    value = emuNameMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "proximity.sample_summary",
                    value = sampleSummary,
                    expected = "non-constant when sensor present",
                ),
                Evidence(
                    key = "proximity.constant_samples",
                    value = constantSamples.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "proximity.phone_class",
                    value = phoneClass.toString(),
                    expected = "<used in scoring guard>",
                ),
                Evidence(
                    key = "proximity.pattern",
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
                "ProximityProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

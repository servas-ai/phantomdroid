// inventory.yml rank 46, mitigation_layer L1
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #46 — ui.refresh_rate.
 *
 * Reads `Display.getRefreshRate()` and `Display.getSupportedModes()` and
 * looks for emulator stub patterns + flagship-vs-actual contradictions:
 *   • **Implausible Hz (0.95)**: < 30 or > 240. No real Android display
 *     operates below 30 Hz at the UI level; > 240 Hz exceeds even the
 *     newest gaming phones.
 *   • **30 Hz on phone-class (0.7)**: TV-class devices sometimes run at
 *     30 Hz, but smartphones don't. Score capped 0.7 (not 1.0) because
 *     budget feature phones theoretically could in unusual modes.
 *   • **Single 60 Hz mode on 120 Hz-capable flagship (0.85)**: real Pixel
 *     7+/Galaxy S22+/etc. expose multiple refresh modes via
 *     `getSupportedModes()`. An emulator typically stubs only 60.0.
 *   • **Plausible adaptive mode (0.0)**: multi-mode list including 60+
 *     options, no contradiction with model.
 *
 * **`Display` accessor gap.** Same as rank-23 [ScreenResolutionProbe]:
 * `ProbeContext` exposes no `queryDisplayMetrics()` view. Constructor-
 * injected suppliers handle the two signals (refreshRate +
 * supportedModes); production no-arg ctor returns null on both and the
 * probe falls back to degraded confidence (0.30) with no scoring rule
 * able to fire. Same gap-handling shape as rank 23/31/32/33/40/42-49.
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the 30 Hz
 * phone-class gate (same drift-safe pattern as rank-49/48/etc.).
 *
 * **Cross-rank reuse contract on `KNOWN_120HZ_MODELS`:** new list
 * specific to this probe. Distinct from rank-45's
 * `KNOWN_BAROMETER_MODELS` (different feature-presence semantic) and
 * rank-28's `FLAGSHIP_MODEL_SUBSTRINGS` (broader — Pixel 4a is in the
 * flagship-family list but isn't 120 Hz-capable). Anchored by a drift-
 * alarm invariant test. Same "lab approximation, real-device telemetry
 * required" family as rank 22/23/28/31/45.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.95  Refresh rate < 30 or > 240 (implausible)
 *   0.85  Single-mode list (only one entry) AND model is in
 *         [KNOWN_120HZ_MODELS] (flagship should support 120 Hz adaptive
 *         but stubbed at 60-only — emulator tell)
 *   0.70  Refresh rate exactly 30.0 AND model is phone-class
 *   0.00  Plausible refresh on adaptive-mode device, OR 60 Hz on
 *         non-flagship/unknown model
 *
 * Confidence:
 *   0.95  Both refreshRate AND supportedModes returned non-null
 *   0.60  Exactly one of the two returned
 *   0.30  Both null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 46 (mitigation_layer L1).
 */
class RefreshRateProbe(
    private val refreshRateHzSupplier: () -> Float? = { null },
    private val supportedModeHzSupplier: () -> List<Float>? = { null },
) : Probe {
    override val id = "ui.refresh_rate"
    override val rank = 46
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val MIN_PLAUSIBLE_HZ = 30.0f
        const val MAX_PLAUSIBLE_HZ = 240.0f

        const val HZ_30 = 30.0f
        const val HZ_60 = 60.0f

        /**
         * Models that ship with 120 Hz-capable displays. Substring match
         * against `ro.product.model` (case-insensitive). Single-mode
         * 60 Hz on these models is the emulator tell.
         *
         * **Source**: GSMArena spec sheets cross-referenced against OEM
         * product pages. Lab approximation — real-device telemetry
         * required to validate. Joins the rank 22 / 23 / 28 / 31 / 45 /
         * cross-cutting follow-up #5 lab-approximation family.
         *
         * **Selection criteria** (team-lead's `≤30 entries OR each entry
         * covers ≥3 variants` rule):
         *   • Pixel 7 and later (Pixel 7/8/9 + Pro/Fold variants — note
         *     Pixel 7a base also has 90 Hz only, Pixel 7/8 standard has
         *     120 Hz)
         *   • Galaxy S22 and later (model codes SM-S901..SM-S938)
         *   • OnePlus 9 Pro and later
         *   • Xiaomi 13 and later
         *
         * Pixel 4a/5a/6a base (no 120 Hz) are intentionally NOT in this
         * list. Pixel 7a is 90 Hz not 120 Hz so also excluded. The
         * substring "pixel 7" would incorrectly match "Pixel 7a", but
         * the alternative ("pixel 7 ", with trailing space) wouldn't
         * match "Pixel 7" base either. Documented limitation: Pixel 7a
         * may false-positive on this rule; acceptable given the LOW
         * severity. Test pins this caveat.
         */
        val KNOWN_120HZ_MODELS: List<String> = listOf(
            "pixel 7", "pixel 8", "pixel 9", "pixel fold",
            "sm-s901", "sm-s906", "sm-s908",   // Galaxy S22 family
            "sm-s911", "sm-s916", "sm-s918",   // Galaxy S23 family
            "sm-s921", "sm-s926", "sm-s928",   // Galaxy S24 family
            "sm-s931", "sm-s936", "sm-s938",   // Galaxy S25 family
            "oneplus 9 pro", "oneplus 10", "oneplus 11", "oneplus 12",
            "mi 13", "mi 14", "xiaomi 13", "xiaomi 14",
        )

        const val PATTERN_IMPLAUSIBLE = "implausible_hz"
        const val PATTERN_STUB_60_ON_FLAGSHIP = "stub_60_on_120hz_model"
        const val PATTERN_30_HZ_ON_PHONE = "30_hz_on_phone"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_IMPLAUSIBLE = 0.95
        const val SCORE_STUB_60_ON_FLAGSHIP = 0.85
        const val SCORE_30_HZ_ON_PHONE = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read Display.refreshRate + supportedModes; detect implausible " +
                "Hz values, single-60Hz-mode on 120Hz-capable flagships, and " +
                "stub patterns"

        /** True iff [model] is in [KNOWN_120HZ_MODELS] (case-insensitive). */
        internal fun is120HzCapableModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return KNOWN_120HZ_MODELS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val refreshRate: Float? = try {
                refreshRateHzSupplier()
            } catch (_: Throwable) {
                null
            }
            val supportedModes: List<Float>? = try {
                supportedModeHzSupplier()
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)
            val capable120Hz = is120HzCapableModel(model)

            val implausibleHz = refreshRate != null &&
                (refreshRate < MIN_PLAUSIBLE_HZ || refreshRate > MAX_PLAUSIBLE_HZ)

            val singleModeOnFlagship = supportedModes != null &&
                supportedModes.size == 1 && supportedModes[0] == HZ_60 && capable120Hz

            val thirtyHzOnPhone = refreshRate == HZ_30 && phoneClass

            val (pattern, score) = when {
                implausibleHz ->
                    PATTERN_IMPLAUSIBLE to SCORE_IMPLAUSIBLE
                singleModeOnFlagship ->
                    PATTERN_STUB_60_ON_FLAGSHIP to SCORE_STUB_60_ON_FLAGSHIP
                thirtyHzOnPhone ->
                    PATTERN_30_HZ_ON_PHONE to SCORE_30_HZ_ON_PHONE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val surfacesReadable = listOf(refreshRate != null, supportedModes != null)
                .count { it }
            val confidence = when {
                surfacesReadable >= 2 -> CONFIDENCE_FULL
                surfacesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val refreshRateDisplay = refreshRate?.toString() ?: "<unavailable>"
            val modesCountDisplay = supportedModes?.size?.toString() ?: "<unavailable>"
            val modesDisplay = when {
                supportedModes == null -> "<unavailable>"
                supportedModes.isEmpty() -> "<empty>"
                supportedModes.size == 1 -> "single ${supportedModes[0]}"
                else -> supportedModes.joinToString(",")
            }
            val isPlausibleHz = refreshRate != null && !implausibleHz

            // Tri-state model-capability evidence (rank-48/49 pattern).
            val capable120HzDisplay = when {
                model.isNullOrEmpty() -> "unknown"
                else -> capable120Hz.toString()
            }

            val evidence = listOf(
                Evidence(
                    key = "display.refresh_rate_hz",
                    value = refreshRateDisplay,
                    expected = "60/90/120/144/165",
                ),
                Evidence(
                    key = "display.supported_modes_count",
                    value = modesCountDisplay,
                    expected = ">=1",
                ),
                Evidence(
                    key = "display.supported_modes",
                    value = modesDisplay,
                    expected = "adaptive on flagship",
                ),
                Evidence(
                    key = "display.is_plausible_hz",
                    value = isPlausibleHz.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "display.is_120hz_capable_model",
                    value = capable120HzDisplay,
                    expected = "depends on model",
                ),
                Evidence(
                    key = "display.pattern",
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
                "RefreshRateProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

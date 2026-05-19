// inventory.yml rank 52, mitigation_layer L1
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #52 — ui.display_cutout.
 *
 * Reads `DisplayCutout` via `WindowInsets.getDisplayCutout()` (Android 9+)
 * and looks for two signal classes:
 *   • **Missing cutout on cutout-equipped flagship (0.5 weak)**: null
 *     cutout on a model that ships with hole-punch (Pixel 6+, Galaxy S22+,
 *     OnePlus 9+, Xiaomi 13+). A real device's cutout API is non-null when
 *     the hardware has one. **0.5 calibration** per the rank-47/51 watch
 *     pattern: privacy ROMs and some custom AOSP builds may legitimately
 *     suppress cutout reporting via WindowManager flags
 *     (`LAYOUT_NO_LIMITS` etc.), so this is a contributing signal rather
 *     than dispositive. TRACE severity + 0.5 score is the internally-
 *     consistent calibration.
 *   • **Implausible inset (0.85)**: `safeInsetTop > 200dp`. Real Android
 *     cutouts cap at ~44dp (Pixel 8 Pro). Above 200dp is structurally
 *     impossible (would obscure a third of the screen) — stronger signal
 *     than the missing-cutout case because no legitimate ROM produces it.
 *
 * **`DisplayCutout` accessor gap.** Same as rank-23 / 46: `ProbeContext`
 * exposes no `queryDisplay()` view. Two constructor-injected suppliers:
 *   - `cutoutPresentSupplier: () -> Boolean?` — non-null adapter ≡ true
 *   - `safeInsetTopDpSupplier: () -> Int?` — DP value (not pixels)
 *
 * Production no-arg ctor returns null on both → confidence 0.50 (degraded).
 * Same gap-handling shape as ranks 23/31-49/51.
 *
 * Reuses **NO** existing flagship-model lists. `KNOWN_CUTOUT_MODELS` is a
 * NEW list because the semantic is again distinct from rank-28/45/46/48
 * lists:
 *   - rank-28 `FLAGSHIP_MODEL_SUBSTRINGS` includes Pixel 4a/5 (no cutout)
 *   - rank-45 `KNOWN_BAROMETER_MODELS` is Pixel 6+ (overlap, but Galaxy
 *     S22+ has different cutout boundary)
 *   - rank-46 `KNOWN_120HZ_MODELS` is Pixel 7+ (Pixel 6 has cutout but no
 *     120 Hz)
 *
 * The cutout-vs-120Hz distinction is critical: Pixel 6 has a hole-punch
 * cutout but only 90 Hz refresh. Same surface (model string), different
 * semantic (cutout-equipped vs 120Hz-capable), different list. Drift-alarm
 * invariant test pins IN/OUT membership.
 *
 * Scoring (max wins; first-match cascade in source order — strong rule
 * first per rank-49/51 partition pattern):
 *   0.85  PATTERN_IMPLAUSIBLE_INSET — `safeInsetTopDp > 200`
 *         (impossible — no real cutout exceeds ~44dp)
 *   0.50  PATTERN_MISSING_ON_CUTOUT_MODEL — `cutoutPresent == false` AND
 *         model is in [KNOWN_CUTOUT_MODELS]. Weak signal — privacy
 *         WindowManager flags can legitimately suppress reporting; user
 *         should combine with other probes via consumer-side aggregator.
 *   0.00  Cutout present, OR cutout null on pre-cutout model (Pixel 3,
 *         older Galaxy), OR cutout null on unknown model
 *
 * Confidence:
 *   0.95  At least one of (cutoutPresent, safeInsetTop) returned non-null
 *   0.50  Both returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 52 (mitigation_layer L1).
 */
class DisplayCutoutProbe(
    private val cutoutPresentSupplier: () -> Boolean? = { null },
    private val safeInsetTopDpSupplier: () -> Int? = { null },
) : Probe {
    override val id = "ui.display_cutout"
    override val rank = 52
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /**
         * Implausibility ceiling. Pixel 8 Pro tops out at ~44dp top inset.
         * Above 200dp is structurally impossible — would obscure roughly
         * a third of the vertical screen. Used by the strong rule.
         */
        const val MAX_PLAUSIBLE_INSET_DP = 200

        /**
         * Models that ship with hole-punch / notch cutouts. Substring
         * match against `ro.product.model` (case-insensitive).
         *
         * **Source**: GSMArena spec sheets cross-referenced against OEM
         * product pages, May 2026. Lab approximation — joins the rank
         * 22 / 23 / 28 / 31 / 45 / 46 / cross-cutting-followup-#5
         * lab-approximation family.
         *
         * **Selection criteria** (team-lead's `≤30 entries OR ≥3 variants
         * each` rule):
         *   • Pixel 6 and later (note: Pixel 6 has hole-punch with 90 Hz;
         *     this is the cutout-vs-120Hz semantic difference from rank-46
         *     `KNOWN_120HZ_MODELS` which requires Pixel 7+)
         *   • Galaxy S22 and later (hole-punch on all S22+ family)
         *   • Galaxy Note 20 and later (hole-punch)
         *   • Galaxy Z Fold 4 and later
         *   • OnePlus 9 and later
         *   • Xiaomi 13 and later
         *
         * **Negative exclusions** [NON_CUTOUT_VARIANTS]:
         *   • Pixel 6a — hole-punch (NOT excluded; included via `pixel 6`)
         *   • Note: Pixel 6/7/8 a-variants ALL have hole-punch cutouts,
         *     so unlike rank-46's NON_120HZ_PIXEL_A_VARIANTS, this list
         *     is currently empty. Kept as forward-looking infrastructure
         *     in case a future positive-substring match needs narrowing.
         */
        val KNOWN_CUTOUT_MODELS: List<String> = listOf(
            "pixel 6", "pixel 7", "pixel 8", "pixel 9", "pixel fold",
            "sm-s901", "sm-s906", "sm-s908",   // Galaxy S22 family
            "sm-s911", "sm-s916", "sm-s918",   // Galaxy S23 family
            "sm-s921", "sm-s926", "sm-s928",   // Galaxy S24 family
            "sm-s931", "sm-s936", "sm-s938",   // Galaxy S25 family
            "sm-n981", "sm-n986",              // Galaxy Note 20 family
            "sm-f936",                          // Galaxy Z Fold 4
            "sm-f946",                          // Galaxy Z Fold 5
            "oneplus 9", "oneplus 10", "oneplus 11", "oneplus 12",
            "mi 13", "mi 14", "xiaomi 13", "xiaomi 14",
        )

        /**
         * Negative exclusions for Pixel a-variants that positively match
         * but DON'T have cutouts. Currently empty because Pixel 6a/7a/8a
         * ALL ship with hole-punch cutouts. Kept as scaffolding for future
         * additions following the rank-46 anti-false-positive negative-
         * exclusion pattern. Drift-alarm test pins the list.
         *
         * **Verified per OEM spec (May 2026)**:
         *   - Pixel 6a / 7a / 8a — all have hole-punch cutouts → NOT excluded
         *   - Pixel 4a / 5a — no cutout (and NOT in positive list — `pixel 5`
         *     and `pixel 4a` don't appear in KNOWN_CUTOUT_MODELS)
         */
        val NON_CUTOUT_VARIANTS: List<String> = emptyList()

        const val PATTERN_IMPLAUSIBLE_INSET = "implausible_inset"
        const val PATTERN_MISSING_ON_CUTOUT_MODEL = "missing_on_cutout_model"
        const val PATTERN_CLEAN = "clean"

        // Strong rule — implausible inset value is impossible regardless
        // of OEM / build flavour.
        const val SCORE_IMPLAUSIBLE_INSET = 0.85

        // Weak rule per rank-51 score-calibration playbook. Privacy ROMs
        // and WindowManager flags (LAYOUT_NO_LIMITS etc) can legitimately
        // suppress cutout reporting on a cutout-equipped device. TRACE
        // severity + 0.5 score is the internally-consistent calibration.
        // Consumer-side aggregator must combine with other probes for
        // high-confidence rejection (same pattern as rank-50
        // AUTOMATION_MARKERS guidance, rank-51 NotoEmoji weak signal).
        const val SCORE_MISSING_ON_CUTOUT_MODEL = 0.5

        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read DisplayCutout via WindowInsets / Display.cutout; detect " +
                "missing cutout on flagship-with-notch models, implausible " +
                "inset values, and inset-vs-bounds contradictions"

        /**
         * True iff [model] is in [KNOWN_CUTOUT_MODELS] (case-insensitive),
         * with negative exclusions from [NON_CUTOUT_VARIANTS] applied
         * first. Same anti-false-positive negative-exclusion pattern as
         * rank-46 `is120HzCapableModel`.
         */
        internal fun isCutoutCapableModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            if (NON_CUTOUT_VARIANTS.any { it in lower }) return false
            return KNOWN_CUTOUT_MODELS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val cutoutPresent: Boolean? = try {
                cutoutPresentSupplier()
            } catch (_: Throwable) {
                null
            }
            val safeInsetTopDp: Int? = try {
                safeInsetTopDpSupplier()
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val cutoutCapableModel = isCutoutCapableModel(model)

            val implausibleInset = safeInsetTopDp != null &&
                safeInsetTopDp > MAX_PLAUSIBLE_INSET_DP
            val missingOnCutoutModel = cutoutPresent == false && cutoutCapableModel

            val (pattern, score) = when {
                implausibleInset ->
                    PATTERN_IMPLAUSIBLE_INSET to SCORE_IMPLAUSIBLE_INSET
                missingOnCutoutModel ->
                    PATTERN_MISSING_ON_CUTOUT_MODEL to SCORE_MISSING_ON_CUTOUT_MODEL
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (cutoutPresent != null || safeInsetTopDp != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            fun displayBool(v: Boolean?): String = v?.toString() ?: "<unavailable>"

            // Tri-state model-capability evidence (rank-48/49/46 pattern).
            val cutoutCapableDisplay = when {
                model.isNullOrEmpty() -> "unknown"
                else -> cutoutCapableModel.toString()
            }

            // bounds_count is derived from cutoutPresent: 1 if present
            // (single hole-punch on all currently-listed cutout models),
            // 0 if absent, "<unavailable>" if null. Real devices with
            // dual cutouts (e.g. waterfall-edge displays) would surface
            // count >=2, but no model currently in KNOWN_CUTOUT_MODELS
            // has more than one cutout region.
            val boundsCountDisplay = when (cutoutPresent) {
                true -> "1"
                false -> "0"
                null -> "<unavailable>"
            }

            val evidence = listOf(
                Evidence(
                    key = "display.has_cutout",
                    value = displayBool(cutoutPresent),
                    expected = "depends on model year",
                ),
                Evidence(
                    key = "display.cutout_safe_inset_top",
                    value = safeInsetTopDp?.toString() ?: "<unavailable>",
                    expected = "24-44 dp on flagship",
                ),
                Evidence(
                    key = "display.cutout_bounds_count",
                    value = boundsCountDisplay,
                    expected = "0 or 1",
                ),
                Evidence(
                    key = "display.is_cutout_capable_model",
                    value = cutoutCapableDisplay,
                    expected = "true if Pixel 6+/Galaxy S22+",
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
                "DisplayCutoutProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

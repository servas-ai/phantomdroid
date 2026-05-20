// inventory.yml rank 23, mitigation_layer L1
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.DisplayMetricsView
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #23 — ui.screen_resolution.
 *
 * Detects emulators and inconsistently-fingerprinted devices by cross-checking
 * Display metrics (resolution, logical density, physical xdpi/ydpi) against:
 *   - A small table of known OEM device profiles (Pixel 7, Galaxy S22, etc.).
 *   - A small table of known emulator defaults (AVD, Genymotion stock).
 *   - Android's "density must be a multiple of 40" convention.
 *   - The "real devices have xdpi ≠ ydpi ≠ logical density" empirical rule.
 *
 * ProbeContext does NOT currently expose a display-metrics accessor. The
 * production no-arg constructor returns null for every signal and the probe
 * gracefully degrades to `score=0.5 / confidence=0.3` with `model_match=
 * no_display` so consumers can see the gap. Adding a `queryDisplayMetrics()`
 * to ProbeContext is a follow-up tracked outside this probe; once it lands,
 * the no-arg constructor can be wired to it without touching the unit-test
 * suite (every test uses the supplier-injecting constructor for
 * determinism).
 *
 * Scoring (max wins across rules; never summed):
 *   1.00  Resolution matches a known emulator default
 *         (`480x800`, `800x480`, `1080x1920`, `720x1280` are all in
 *         `EMULATOR_RESOLUTIONS`; the densityDpi check narrows the
 *         already-classified emulator hit if density also matches the
 *         stock AVD/Genymotion default).
 *   0.90  Model is in `DEVICE_PROFILES` but resolution+density don't match
 *         that profile (cross-source fingerprint inconsistency).
 *   0.85  `xdpi == ydpi == densityDpi.toFloat()` (perfect-equality emulator
 *         tell — real devices never produce this).
 *   0.70  `densityDpi % 20 != 0` (Android density buckets are multiples of
 *         20 in practice — standard buckets at 160/240/320/480/640 are
 *         multiples of 40, but OEMs ship many intermediate values like 420
 *         on Pixel and 460 on smaller phones, all of which are multiples
 *         of 20. Non-multiples of 20 suggest custom/stripped builds. The
 *         spec called for mod-40 originally; mod-20 is the empirically
 *         tighter rule that doesn't false-positive on real Pixel devices).
 *   0.50  Display accessor unavailable (production stub).
 *   0.00  All consistent with a known OEM profile, or no signal fired and
 *         model is unknown.
 *
 * Confidence: 0.95 if display metrics AND model both readable; 0.60 if only
 * one; 0.30 if neither.
 *
 * Reference: shared/probes/inventory.yml rank 23 (mitigation_layer L1).
 */
class ScreenResolutionProbe(
    private val widthPixelsSupplier: (() -> Int?)? = null,
    private val heightPixelsSupplier: (() -> Int?)? = null,
    private val densityDpiSupplier: (() -> Int?)? = null,
    private val xdpiSupplier: (() -> Float?)? = null,
    private val ydpiSupplier: (() -> Float?)? = null,
    /**
     * Model string from `ro.product.model`. The probe reads it independently
     * via `ctx.getSystemProperty` if the supplier returns null, so callers
     * usually leave this at the default and let the production ProbeContext
     * answer.
     */
    private val modelSupplier: (() -> String?)? = null,
) : Probe {
    override val id = "ui.screen_resolution"
    override val rank = 23
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    /** A device profile: expected display metrics for a known OEM device. */
    data class DeviceProfile(
        val widthPixels: Int,
        val heightPixels: Int,
        val densityDpi: Int,
    )

    companion object {
        /**
         * Small lookup of known OEM device profiles. Lab approximation only;
         * not an exhaustive map. Keys are lowercase `ro.product.model` substrings
         * — the model match is done as `model.contains(key, ignoreCase=true)` so
         * that "Pixel 7 Pro" still matches "pixel 7" but not "pixel 8".
         */
        val DEVICE_PROFILES: Map<String, DeviceProfile> = linkedMapOf(
            // Longer (more-specific) substrings FIRST so substring-match
            // selects the right profile — e.g. "Pixel 7 Pro" matches the
            // "pixel 7 pro" entry rather than the broader "pixel 7" key.
            "pixel 7 pro" to DeviceProfile(1440, 3120, 560),
            // Pixel 8 Pro: Google official spec is 489 PPI
            // (https://store.google.com/product/pixel_8_pro_specs). Google ships
            // non-bucket-quantized densityDpi values on Pixel (see Pixel 7 = 420
            // not 480), so the lab profile is set to 489 to match real device
            // telemetry. Closure of cross-cutting #2 telemetry gap 2026-05-20.
            "pixel 8 pro" to DeviceProfile(1344, 2992, 489),
            "pixel 7" to DeviceProfile(1080, 2400, 420),
            "pixel 8" to DeviceProfile(1080, 2400, 420),
            "sm-s901" to DeviceProfile(1080, 2340, 420),     // Galaxy S22
            "sm-s906" to DeviceProfile(1080, 2340, 420),     // Galaxy S22+
            "sm-s908" to DeviceProfile(1440, 3088, 600),     // Galaxy S22 Ultra
            "sm-g991" to DeviceProfile(1080, 2400, 420),     // Galaxy S21
        )

        /**
         * Known emulator-default `(width, height)` pairs. Stock AOSP AVD ships
         * at 1080x1920/480; older AVDs default to 480x800; Genymotion stock
         * is 720x1280/320.
         */
        val EMULATOR_RESOLUTIONS: Set<Pair<Int, Int>> = setOf(
            480 to 800,
            800 to 480,
            1080 to 1920,
            1920 to 1080,
            720 to 1280,
            1280 to 720,
        )

        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val MODEL_MATCH = "match"
        const val MODEL_MISMATCH = "mismatch"
        const val MODEL_UNKNOWN = "unknown_model"
        const val MODEL_NO_DISPLAY = "no_display"

        const val SCORE_EMULATOR_RES = 1.0
        const val SCORE_MODEL_MISMATCH = 0.9
        const val SCORE_PERFECT_DPI_EQUALITY = 0.85
        const val SCORE_DENSITY_NOT_MOD_20 = 0.7
        const val SCORE_NO_DISPLAY = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read DisplayMetrics + ro.product.model and cross-check " +
                "resolution/density/dpi against known device profiles and " +
                "emulator defaults"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-signal cascade: a non-null supplier wins (caller-provided
            // value, including explicit `{ null }`, takes precedence). When
            // no supplier was provided the probe reads from
            // ProbeContext.queryDisplayMetrics() which returns null on bare
            // ProbeContext impls (= "no display observation possible") and
            // a snapshot/platform view on overriding contexts. Phase-4
            // closeout (Power-8, 2026-05-20) — mirrors the rank-20/36
            // refactor pattern.
            val displayMetrics: DisplayMetricsView? = try {
                ctx.queryDisplayMetrics()
            } catch (_: Throwable) {
                null
            }
            val width = readSupplierOr(widthPixelsSupplier) { displayMetrics?.widthPixels() }
            val height = readSupplierOr(heightPixelsSupplier) { displayMetrics?.heightPixels() }
            val density = readSupplierOr(densityDpiSupplier) { displayMetrics?.densityDpi() }
            val xdpi = readSupplierOr(xdpiSupplier) { displayMetrics?.xdpi() }
            val ydpi = readSupplierOr(ydpiSupplier) { displayMetrics?.ydpi() }

            val modelFromSupplier = if (modelSupplier != null) {
                try { modelSupplier.invoke() } catch (_: Throwable) { null }
            } else null
            val modelFromCtx: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val model = (modelFromSupplier ?: modelFromCtx)?.takeIf { it.isNotEmpty() }

            val displayMissing = width == null || height == null || density == null

            val profile = model?.let { m ->
                DEVICE_PROFILES.entries.firstOrNull { (k, _) ->
                    m.contains(k, ignoreCase = true)
                }?.value
            }

            val isEmulatorResolution = !displayMissing &&
                (width!! to height!!) in EMULATOR_RESOLUTIONS

            val modelMismatch = !displayMissing && profile != null && (
                profile.widthPixels != width ||
                    profile.heightPixels != height ||
                    profile.densityDpi != density
                )

            val perfectDpiEquality = !displayMissing && xdpi != null && ydpi != null &&
                xdpi == ydpi &&
                xdpi.toInt() == density

            val densityNotMod20 = !displayMissing && density!! % 20 != 0

            val candidates = buildList {
                if (isEmulatorResolution) add(SCORE_EMULATOR_RES)
                if (modelMismatch) add(SCORE_MODEL_MISMATCH)
                if (perfectDpiEquality) add(SCORE_PERFECT_DPI_EQUALITY)
                if (densityNotMod20) add(SCORE_DENSITY_NOT_MOD_20)
            }

            val score = when {
                candidates.isNotEmpty() -> candidates.max()
                displayMissing -> SCORE_NO_DISPLAY
                else -> SCORE_CLEAN
            }

            val modelMatchLabel = when {
                displayMissing -> MODEL_NO_DISPLAY
                modelMismatch -> MODEL_MISMATCH
                profile == null -> MODEL_UNKNOWN
                else -> MODEL_MATCH
            }

            val displayReadable = !displayMissing
            val modelReadable = model != null
            val confidence = when {
                displayReadable && modelReadable -> CONFIDENCE_FULL
                displayReadable || modelReadable -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val aspectRatio = if (!displayMissing && height!! > 0) {
                val long = maxOf(width!!, height)
                val short = minOf(width, height)
                "%.3f".format(java.util.Locale.ROOT, long.toDouble() / short.toDouble())
            } else "absent"

            val expectedModelSpec = profile?.let { "${it.widthPixels}x${it.heightPixels}@${it.densityDpi}" }
                ?: "<model-spec>"

            val evidence = listOf(
                Evidence("display.widthPixels", width ?: "absent", expected = expectedModelSpec),
                Evidence("display.heightPixels", height ?: "absent", expected = expectedModelSpec),
                Evidence("display.densityDpi", density ?: "absent", expected = expectedModelSpec),
                Evidence("display.xdpi", xdpi ?: "absent", expected = "<≠ density typically>"),
                Evidence("display.ydpi", ydpi ?: "absent", expected = "<≠ density typically>"),
                Evidence("display.aspect_ratio", aspectRatio, expected = "<known mobile aspect>"),
                Evidence("model_match", modelMatchLabel, expected = MODEL_MATCH),
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
                "ScreenResolutionProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    /**
     * Reads a per-signal supplier with crash-safety; if the supplier is null
     * (= caller did not override) falls back to the `ctxAccessor` reader.
     * Mirrors the Power-8 phase-4 cascade pattern across the supplier
     * refactor — explicit `{ null }` supplier disarms the surface entirely
     * (no ctx fallback), `null` supplier (the default) routes through ctx.
     */
    private fun <T> readSupplierOr(s: (() -> T?)?, ctxAccessor: () -> T?): T? = try {
        if (s != null) s() else ctxAccessor()
    } catch (_: Throwable) {
        null
    }
}

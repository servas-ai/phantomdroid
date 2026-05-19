// inventory.yml rank 51, mitigation_layer L0
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #51 — ui.system_fonts.
 *
 * Inspects the device's font registry for emulator/stripped-build markers.
 * Real Android phones ship `/system/fonts/` with 150+ files including
 * the full Roboto family (50+ weights/styles) plus `NotoColorEmoji.ttf`
 * plus 15-30 Noto international scripts. Emulators commonly strip down
 * to ~30 fonts and drop NotoColorEmoji or some Noto scripts entirely.
 *
 * **`/system/fonts/` listing accessor gap.** `ProbeContext` exposes
 * `fileExists(path)` and `readFile(path)` but no `listDirectory(path)`.
 * Three constructor-injected suppliers handle the signals:
 *   - `fontFilenamesSupplier: () -> List<String>?` — full filename list
 *     if the production wrapper can enumerate the directory
 *   - `defaultFamilySupplier: () -> String?` — `Typeface.DEFAULT.family`
 *     value from the framework
 *   - The third channel is `ctx.fileExists("/system/fonts/<name>.ttf")`
 *     for [WELL_KNOWN_FONT_NAMES] — a curated 32-entry probe set used as
 *     a fallback when the supplier returns null. The fileExists count
 *     stands in for the full directory count.
 *
 * Production no-arg ctor returns null on both suppliers; the probe falls
 * back to fileExists-only with degraded confidence (0.50). The curated
 * list provides a meaningful approximation but the actual
 * directory-count rule cannot fire without the full list.
 *
 * **Weak-signal calibration on minimal-build false-positives.** This is a
 * TRACE-severity probe, which already signals "weak fingerprinting noise,
 * not high-confidence rejection". Two rules that the brief suggested at
 * 0.85 are calibrated DOWN to 0.5 here because they have a real FP class
 * on privacy/minimal Android builds:
 *   - **CalyxOS** strips `NotoColorEmoji.ttf` and reduces font set for
 *     privacy (fingerprint surface reduction)
 *   - **GrapheneOS** minimal builds same
 *   - **Android Go** SKUs (Pixel 5a Go, entry-level OEM) ship ~30 fonts
 *
 * A 0.85-score-on-TRACE-severity is internally inconsistent and would
 * false-positive privacy-conscious real users. The 0.5 calibration treats
 * these rules as "contributing signal — combine with other probes via the
 * downstream aggregator before any rejection decision" (same consumer-side
 * gating pattern as rank-50 AUTOMATION_MARKERS). KDoc honesty correction
 * per reviewer's pre-emptive watch #3.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.85  `defaultFamilySupplier` returns non-null AND value is not in
 *         [STANDARD_DEFAULT_FAMILIES] (Typeface.DEFAULT.family on real
 *         devices is `sans-serif` or one of its variants; non-standard
 *         values are a stripped/custom-build tell that CalyxOS et al
 *         don't trip — privacy ROMs keep standard default-family
 *         registration)
 *   0.50  Directory observed AND `NotoColorEmoji.ttf` missing (real
 *         retail Android since 2017 ships it; CalyxOS / GrapheneOS
 *         minimal / Android Go can legitimately omit — weak signal)
 *   0.50  Full filename list available AND total count < 50 (real Pixel/
 *         Galaxy/Xiaomi retail has 150+; CalyxOS/GrapheneOS minimal also
 *         <50 — weak signal indistinguishable from emulator AOSP without
 *         additional evidence)
 *   0.50  No observation possible (no supplier, zero fileExists hits, no
 *         defaultFamily)
 *   0.50  Full filename list available AND Roboto variant count < 20
 *         (real devices have 50+; reduced Roboto on custom AOSP ROMs is
 *         legitimate — weak signal)
 *   0.00  Real font set: NotoColorEmoji present + (no list OR
 *         count ≥ 50 + Roboto variants ≥ 20) + standard default family
 *
 * Confidence:
 *   0.95  Either the supplier returned a list OR ≥ 1 well-known font
 *         file was observed via fileExists
 *   0.50  No observation possible (supplier null + zero fileExists hits)
 *
 * Reference: shared/probes/inventory.yml rank 51 (mitigation_layer L0,
 * `rasp_analog: none` — A17 §6 deprioritize: browser-canvas technique
 * with no native RASP analog).
 */
class SystemFontsProbe(
    private val fontFilenamesSupplier: () -> List<String>? = { null },
    private val defaultFamilySupplier: () -> String? = { null },
) : Probe {
    override val id = "ui.system_fonts"
    override val rank = 51
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val FONTS_DIR = "/system/fonts"

        const val FILENAME_NOTO_COLOR_EMOJI = "NotoColorEmoji.ttf"

        const val MIN_REALISTIC_FONT_COUNT = 50
        const val MIN_REALISTIC_ROBOTO_VARIANT_COUNT = 20

        /**
         * Curated set of font filenames that ship on essentially every
         * real Android device with stock fonts. Used for the fileExists-
         * fallback path when the directory-listing supplier is absent.
         *
         * **Source**: Pixel 7 / Galaxy S22 / Xiaomi 13 stock font sets,
         * cross-referenced against AOSP `frameworks/base/data/fonts/`.
         * Lab approximation — joins the rank 22 / 23 / 28 / 31 / 45 / 46
         * cross-cutting follow-up #5 lab-approximation family.
         *
         * The list intentionally includes Roboto variants spanning the
         * weight/style axes so the fileExists-count gives a reasonable
         * approximation of the real Roboto-variant count when the full
         * supplier is unavailable.
         */
        val WELL_KNOWN_FONT_NAMES: List<String> = listOf(
            // Roboto family (10 variants — proxy for the full 50+ set)
            "Roboto-Regular.ttf",
            "Roboto-Bold.ttf",
            "Roboto-Italic.ttf",
            "Roboto-BoldItalic.ttf",
            "Roboto-Light.ttf",
            "Roboto-LightItalic.ttf",
            "Roboto-Medium.ttf",
            "Roboto-MediumItalic.ttf",
            "Roboto-Thin.ttf",
            "Roboto-Black.ttf",
            // Emoji
            FILENAME_NOTO_COLOR_EMOJI,
            // Common Noto sans scripts (10 — proxy for the full 15-30 set)
            "NotoSans-Regular.ttf",
            "NotoSansArabic-Regular.ttf",
            "NotoSansCJK-Regular.ttc",
            "NotoSansDevanagari-Regular.ttf",
            "NotoSansHebrew-Regular.ttf",
            "NotoSansThai-Regular.ttf",
            "NotoSansBengali-Regular.ttf",
            "NotoSansEthiopic-Regular.ttf",
            "NotoSansGeorgian-Regular.ttf",
            "NotoSansArmenian-Regular.ttf",
            // Other common stock fonts
            "NotoSerif-Regular.ttf",
            "NotoNaskhArabic-Regular.ttf",
            "DroidSansMono.ttf",
            "CutiveMono.ttf",
            "ComingSoon.ttf",
            "DancingScript-Regular.ttf",
            "CarroisGothicSC-Regular.ttf",
            "AccanthisADFStdNo3-Regular.otf",
            "NotoSansSymbols-Regular.ttf",
            "NotoSansSymbols-Regular-Subsetted.ttf",
            "NotoColorEmojiLegacy.ttf",  // older Android variant
        )

        /**
         * Substring used to identify Roboto variants in a filename list.
         * Match is case-sensitive (Roboto on stock Android always
         * capitalises the R; non-standard ROMs that rename to "roboto"
         * are themselves a signal).
         */
        const val ROBOTO_PREFIX = "Roboto"

        /**
         * Standard values for `Typeface.DEFAULT.family` on stock Android.
         * Non-matching values suggest custom font registry or stripped
         * build.
         */
        val STANDARD_DEFAULT_FAMILIES: Set<String> = setOf(
            "sans-serif",
            "sans-serif-condensed",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-thin",
            "serif",
            "monospace",
        )

        const val PATTERN_NO_NOTO_EMOJI = "no_noto_color_emoji"
        const val PATTERN_LOW_COUNT = "low_font_count"
        const val PATTERN_NON_STANDARD_DEFAULT = "non_standard_default_family"
        const val PATTERN_LOW_ROBOTO_COUNT = "low_roboto_count"
        const val PATTERN_NO_OBSERVATION = "no_observation"
        const val PATTERN_CLEAN = "clean"

        // Non-standard default family stays at 0.85 — CalyxOS / GrapheneOS
        // / Android Go all keep `Typeface.DEFAULT.family == "sans-serif"`,
        // so this rule isn't tripped by the minimal-build FP class. It IS
        // tripped by stripped/custom builds that rewrite the typeface
        // registry, which is the load-bearing emulator/synthetic signal.
        const val SCORE_NON_STANDARD_DEFAULT = 0.85

        // Other rules calibrated to 0.5 weak-signal per reviewer's watch
        // #3: CalyxOS/GrapheneOS/Android Go can legitimately strip
        // NotoColorEmoji and reduce font count. The signal is contributing
        // but not dispositive — consumer must combine with other probes
        // for high-confidence rejection. TRACE severity + 0.5 score is
        // the internally-consistent calibration.
        const val SCORE_NO_NOTO_EMOJI = 0.5
        const val SCORE_LOW_COUNT = 0.5
        const val SCORE_LOW_ROBOTO_COUNT = 0.5
        const val SCORE_NO_OBSERVATION = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Enumerate /system/fonts/ directory; detect missing " +
                "NotoColorEmoji, low font count (<50), low Roboto variant " +
                "count, non-standard default family"

        /** Counts files matching `Roboto*` (case-sensitive). */
        internal fun countRobotoVariants(filenames: List<String>): Int =
            filenames.count { it.startsWith(ROBOTO_PREFIX) }

        /**
         * Returns true for valid stock-Android default-family values
         * (members of [STANDARD_DEFAULT_FAMILIES]) OR for null/empty input
         * (which we can't claim is non-standard without observation —
         * anti-false-positive: missing observation MUST NOT be scored as
         * a non-standard finding).
         *
         * In other words: a `false` return means "definitely observed AND
         * not in the standard set" — load-bearing for the
         * `nonStandardDefault` scoring predicate.
         */
        internal fun isStandardDefaultFamily(defaultFamily: String?): Boolean {
            if (defaultFamily.isNullOrEmpty()) return true
            return defaultFamily in STANDARD_DEFAULT_FAMILIES
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val fontFilenames: List<String>? = try {
                fontFilenamesSupplier()
            } catch (_: Throwable) {
                null
            }

            // Fallback path: query fileExists for each well-known font.
            // Used to verify NotoColorEmoji and as a proxy file-count.
            val wellKnownFontHits: List<String> = WELL_KNOWN_FONT_NAMES.mapNotNull { name ->
                try {
                    if (ctx.fileExists("$FONTS_DIR/$name")) name else null
                } catch (_: Throwable) {
                    null
                }
            }

            val defaultFamily: String? = try {
                defaultFamilySupplier()
            } catch (_: Throwable) {
                null
            }

            // Source of truth for NotoColorEmoji presence:
            //   1. Supplier list (if available)
            //   2. fileExists fallback
            val notoColorEmojiPresent: Boolean = when {
                fontFilenames != null ->
                    fontFilenames.any { it == FILENAME_NOTO_COLOR_EMOJI }
                else ->
                    FILENAME_NOTO_COLOR_EMOJI in wellKnownFontHits
            }

            val accessorObserved = fontFilenames != null || wellKnownFontHits.isNotEmpty()

            // Total count rule fires only with the FULL supplier list, not
            // the fileExists subset (the subset is bounded by WELL_KNOWN
            // size and would always fail the >=50 threshold).
            val totalCount = fontFilenames?.size
            val lowFontCount = totalCount != null && totalCount < MIN_REALISTIC_FONT_COUNT

            val robotoCount = fontFilenames?.let { countRobotoVariants(it) }
            val lowRobotoCount = robotoCount != null &&
                robotoCount < MIN_REALISTIC_ROBOTO_VARIANT_COUNT

            val nonStandardDefault = defaultFamily != null &&
                !isStandardDefaultFamily(defaultFamily)

            val (pattern, score) = when {
                // Strong rule first: non-standard default family is not
                // tripped by privacy ROMs / Android Go (they keep stock
                // typeface registry) — it's the load-bearing emulator/
                // synthetic-build signal at 0.85.
                nonStandardDefault ->
                    PATTERN_NON_STANDARD_DEFAULT to SCORE_NON_STANDARD_DEFAULT
                !accessorObserved && defaultFamily == null ->
                    PATTERN_NO_OBSERVATION to SCORE_NO_OBSERVATION
                // Weak rules below at 0.5 — CalyxOS / GrapheneOS / Android Go
                // can legitimately trip these. Consumer must combine with
                // other probes for high-confidence rejection.
                accessorObserved && !notoColorEmojiPresent ->
                    PATTERN_NO_NOTO_EMOJI to SCORE_NO_NOTO_EMOJI
                lowFontCount ->
                    PATTERN_LOW_COUNT to SCORE_LOW_COUNT
                lowRobotoCount ->
                    PATTERN_LOW_ROBOTO_COUNT to SCORE_LOW_ROBOTO_COUNT
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (accessorObserved || defaultFamily != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            val countDisplay = totalCount?.toString()
                ?: "via fileExists: ${wellKnownFontHits.size}/${WELL_KNOWN_FONT_NAMES.size}"
            val robotoCountDisplay = robotoCount?.toString()
                ?: "via fileExists: ${wellKnownFontHits.count { it.startsWith(ROBOTO_PREFIX) }}"
            val defaultDisplay = defaultFamily?.ifEmpty { "<empty>" } ?: "<unavailable>"

            // Sample limited to first 10 entries for evidence-row brevity.
            // Source preference: supplier list, fallback to fileExists hits.
            val sampleSource = fontFilenames ?: wellKnownFontHits
            val sampleDisplay = when {
                sampleSource.isEmpty() -> "<no observation>"
                sampleSource.size <= 10 -> sampleSource.joinToString(",")
                else -> sampleSource.take(10).joinToString(",") + ",..."
            }

            val evidence = listOf(
                Evidence(
                    key = "fonts.system_count",
                    value = countDisplay,
                    expected = ">=100",
                ),
                Evidence(
                    key = "fonts.has_noto_color_emoji",
                    value = notoColorEmojiPresent.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "fonts.roboto_variant_count",
                    value = robotoCountDisplay,
                    expected = ">=20",
                ),
                Evidence(
                    key = "fonts.default_family",
                    value = defaultDisplay,
                    expected = "sans-serif",
                ),
                Evidence(
                    key = "fonts.sample",
                    value = sampleDisplay,
                    expected = "real font names",
                ),
                Evidence(
                    key = "fonts.pattern",
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
                "SystemFontsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 9, mitigation_layer L1
package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #9 — buildprop.model_brand_manufacturer.
 *
 * Focused-extraction probe over `ro.product.brand` + `ro.product.model`
 * + `ro.product.manufacturer`. Same three properties also read by
 * rank-1 [BuildFingerprintProbe], but scored INDEPENDENTLY of
 * fingerprint coherence — the same yaml-wins discipline as rank-7
 * [TagsAndTypeProbe]. Inventory lists rank 9 as a separate
 * rank-priority slot, so the triplet violation gets its own probe
 * even though source properties overlap with rank-1.
 *
 * **Distinct scoring focus from rank-1**:
 *   - rank-1 scores brand/manufacturer mismatch at 0.45 only as a
 *     co-factor (lower than its tags/type co-factors at 0.85), and
 *     does not specifically scan `ro.product.model` for emulator
 *     keywords — its emulator-keyword scan is over
 *     `ro.build.fingerprint`.
 *   - rank-9 (this) scores the triplet as a **standalone signal**:
 *     model with emulator keyword fires 1.0 dispositive; "unknown"
 *     sentinel value in any of the three fires 1.0; empty values
 *     fire 0.9; brand/manufacturer mismatch (after alias resolution)
 *     fires 0.85. The triplet is independent of fingerprint.
 *
 * Same source properties intentionally double-read — consumer-side
 * aggregator routes on whichever signal it wants. Joins the rank-7
 * focused-extraction pattern as the third "yaml-wins" probe of the
 * detector inventory.
 *
 * Signal classes (CRITICAL because each is dispositive on its own —
 * production Android builds populate all three from OEM source, with
 * known brand/manufacturer aliasing for real OEMs):
 *
 *   • **Model emulator keyword (1.0)**: `ro.product.model` contains
 *     `sdk_gphone64`, `Android SDK built for`, `Emulator`, `Genymobile`,
 *     `goldfish`, or `ranchu`. The model field is where AVD/Genymotion
 *     leak their identity most reliably — emulator-suppression kits
 *     frequently mask board/hardware props but leave the model field
 *     containing the AVD signature.
 *   • **`"unknown"` sentinel (1.0)**: any of the three properties
 *     literally equals `"unknown"`. AOSP source defaults are literal
 *     `"unknown"` when the OEM hasn't pinned the value — production
 *     OEM builds always populate all three.
 *   • **Empty property (0.9)**: any of the three is empty string.
 *     Pre-prod-stub signature; weaker than the "unknown" sentinel
 *     because some custom AOSP forks legitimately ship with an
 *     unpinned brand and an empty value rather than the "unknown"
 *     sentinel.
 *   • **Brand/manufacturer mismatch (0.85)**: brand and manufacturer
 *     differ AND the pair is not in [KNOWN_OEM_ALIAS_PAIRS]. Real
 *     OEMs follow a stable brand↔manufacturer convention (Google ↔
 *     google, samsung ↔ samsung, etc., all matching modulo case).
 *     Divergence is a structural inconsistency.
 *   • **Clean (0.0)**: all three populated, none `"unknown"`, no
 *     emulator keyword in model, brand/manufacturer aligned per
 *     alias table.
 *
 * Uses ONLY the base [ProbeContext] (`getSystemProperty`) — no
 * constructor-injected suppliers. Same shape as rank-4/rank-7.
 *
 * **`MODEL_EMULATOR_KEYWORDS` list reuse decision**: NEW list distinct
 * from existing emu-marker collections:
 *   - rank-28 [BoardHardwareProbe.EMULATOR_BOARD_MARKERS] = `goldfish,
 *     ranchu, redroid, vbox86, cancro` — scans `ro.product.board` /
 *     `ro.hardware` PROPERTIES with substring match
 *   - rank-42 [com.detectorlab.probes.sensors.ProximityProbe.EMU_NAME_SUBSTRINGS]
 *     = `goldfish, avd, genymotion, ranchu, vbox` — scans sensor
 *     vendor/name strings
 *   - rank-9 (this) [MODEL_EMULATOR_KEYWORDS] — scans
 *     `ro.product.model` for AVD-model-specific tokens like
 *     `sdk_gphone64` and human-readable phrases like
 *     `"Android SDK built for"` and `"Emulator"` that appear in the
 *     model field but NOT in board props or sensor names
 *
 * **DO NOT consolidate** with rank-28 or rank-42 lists — different
 * surfaces have different leak vocabularies. AVD models report
 * `sdk_gphone64_x86_64` in `ro.product.model` but the board property
 * is `ranchu` — neither substring appears in the other's vocabulary.
 * Cross-rank invariant test pins the separation.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per rank-49/51/52/53/4/7 partition pattern):
 *   1.00  PATTERN_MODEL_EMULATOR_KEYWORD — model contains AVD/emu token
 *   1.00  PATTERN_UNKNOWN_SENTINEL — any prop == "unknown"
 *   0.90  PATTERN_EMPTY_PROPERTY — any prop == ""
 *   0.85  PATTERN_BRAND_MANUFACTURER_MISMATCH — brand != manufacturer
 *         modulo alias table
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  At least 2 of 3 properties readable (non-null)
 *   0.50  0 or 1 readable (degraded)
 *
 * Reference: shared/probes/inventory.yml rank 9 (mitigation_layer L1).
 */
class ModelBrandManufacturerProbe : Probe {
    override val id = "buildprop.model_brand_manufacturer"
    override val rank = 9
    override val category = ProbeCategory.BUILDPROP
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_BRAND = "ro.product.brand"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"
        const val PROP_RO_PRODUCT_MANUFACTURER = "ro.product.manufacturer"

        const val UNKNOWN_SENTINEL = "unknown"

        /**
         * Emulator/AVD keyword substrings that appear in
         * `ro.product.model` specifically. Match is case-insensitive.
         *
         * **Source**: AVD product names emitted by the Android Emulator
         * (`sdk_gphone*` family for Google API images,
         * `"Android SDK built for x86"` for older AOSP emulator
         * profiles), Genymotion's `Genymobile`-branded model strings,
         * and the canonical goldfish/ranchu kernel names that
         * occasionally leak into the model field on misconfigured
         * builds.
         *
         * **DO NOT consolidate** with rank-28
         * [BoardHardwareProbe.EMULATOR_BOARD_MARKERS] or rank-42
         * [com.detectorlab.probes.sensors.ProximityProbe.EMU_NAME_SUBSTRINGS]:
         * different surfaces have different leak vocabularies. AVD
         * model = `sdk_gphone64_x86_64`; AVD board = `ranchu`; AVD
         * sensor = `Goldfish Proximity sensor`. Substrings that
         * appear in one surface don't necessarily appear in the
         * others (and vice versa: `cancro` from rank-28 is a board-
         * codename test-image marker, never appears in a model string).
         * Cross-rank invariant test pins the separation.
         */
        val MODEL_EMULATOR_KEYWORDS: List<String> = listOf(
            "sdk_gphone",                  // covers sdk_gphone64, sdk_gphone_x86_64, etc.
            "android sdk built for",       // canonical AOSP emulator model phrase
            "emulator",                    // generic AVD/emu model token
            "genymobile",                  // Genymotion brand-in-model leak
            "goldfish",                    // shared with rank-28/42 — defensible
            "ranchu",                      // shared with rank-28/42 — defensible
            // ReDroid's canonical model string is `redroid12_x86_64_only` (also
            // observed: `redroid11_*`, `redroid10_*`) — closes the multi-surface
            // gap caught by the 2026-05-20 PAR822349 E2E validation run, where
            // rank-28 fired on ro.hardware=redroid but rank-9 fell through
            // even though `redroid` was present in ro.product.model. Same
            // shared-with-rank-28 defensibility as `goldfish`/`ranchu`.
            "redroid",
        )

        /**
         * Known OEM brand↔manufacturer pairs (both sides lowercased,
         * unordered comparison). A real device with brand `google` and
         * manufacturer `Google` is consistent (case-insensitive equality
         * suffices), but the table also accepts intentional asymmetry
         * for OEMs whose brand and manufacturer literally diverge —
         * none currently known. Same content as rank-1's
         * `knownBrandMfgPairs` but locally defined: rank-1's set is
         * `private` so reuse via reference isn't possible without
         * surfacing it. Future cross-cutting follow-up could promote
         * a shared `OEM_ALIASES` constant; for now duplicate is
         * acceptable per single-purpose-probe discipline.
         *
         * Stored as Set<Pair<String, String>> with both halves
         * lowercased. Lookup is symmetric: `(brand, mfg)` matches
         * either `(brand, mfg)` or `(mfg, brand)` in the set.
         */
        val KNOWN_OEM_ALIAS_PAIRS: Set<Pair<String, String>> = setOf(
            "google" to "google",
            "samsung" to "samsung",
            "xiaomi" to "xiaomi",
            "oneplus" to "oneplus",
            "oppo" to "oppo",
            "vivo" to "vivo",
            "realme" to "realme",
            "motorola" to "motorola",
            "sony" to "sony",
            "huawei" to "huawei",
            "redmi" to "xiaomi",            // Redmi-branded SKUs report manufacturer=Xiaomi
            "poco" to "xiaomi",             // POCO sub-brand of Xiaomi
            "honor" to "honor",             // post-2020 independent Honor
            "honor" to "huawei",            // pre-2020 legacy Honor (brand=Honor, manufacturer=HUAWEI)
        )

        const val PATTERN_MODEL_EMULATOR_KEYWORD = "model_emulator_keyword"
        const val PATTERN_UNKNOWN_SENTINEL = "unknown_sentinel"
        const val PATTERN_EMPTY_PROPERTY = "empty_property"
        const val PATTERN_BRAND_MANUFACTURER_MISMATCH = "brand_manufacturer_mismatch"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_MODEL_EMULATOR_KEYWORD = 1.0
        const val SCORE_UNKNOWN_SENTINEL = 1.0
        const val SCORE_EMPTY_PROPERTY = 0.9
        const val SCORE_BRAND_MANUFACTURER_MISMATCH = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read ro.product.brand + model + manufacturer; detect emulator " +
                "keywords in model, 'unknown' sentinel values, empty fields, " +
                "and brand-vs-manufacturer mismatches modulo known-OEM aliases. " +
                "Focused-extraction probe separate from rank 1 BuildFingerprint."

        /**
         * True iff [model] contains any emulator-keyword substring
         * (case-insensitive). Returns false for null/empty.
         */
        internal fun hasModelEmulatorKeyword(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return MODEL_EMULATOR_KEYWORDS.any { it in lower }
        }

        /**
         * True iff [brand] and [manufacturer] are aligned per the
         * known-OEM-alias table. Both inputs are lowercased before
         * comparison; lookup is symmetric (matches either ordering
         * of the pair).
         *
         * Returns `true` when the lowercased equality holds (the
         * common case: `google` vs `Google`).
         * Returns `true` when the pair is in the alias set
         * (e.g. `redmi` brand vs `xiaomi` manufacturer).
         * Returns `false` otherwise.
         *
         * Caller must handle null/empty inputs separately — those
         * are caught by the empty-property rule, not this helper.
         */
        internal fun isBrandManufacturerAligned(brand: String, manufacturer: String): Boolean {
            val bLower = brand.lowercase()
            val mLower = manufacturer.lowercase()
            if (bLower == mLower) return true
            if ((bLower to mLower) in KNOWN_OEM_ALIAS_PAIRS) return true
            if ((mLower to bLower) in KNOWN_OEM_ALIAS_PAIRS) return true
            return false
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-property try/catch so one throwing read doesn't
            // suppress observation of the others. Per-property
            // observation counter for the confidence tier.
            var observed = 0

            fun readProp(key: String): String? {
                return try {
                    val v = ctx.getSystemProperty(key)
                    if (v != null) observed++
                    v
                } catch (_: Throwable) {
                    null
                }
            }

            val brand = readProp(PROP_RO_PRODUCT_BRAND)
            val model = readProp(PROP_RO_PRODUCT_MODEL)
            val manufacturer = readProp(PROP_RO_PRODUCT_MANUFACTURER)

            val modelHasEmuKeyword = hasModelEmulatorKeyword(model)

            val anyIsUnknown = listOf(brand, model, manufacturer).any { v ->
                v?.lowercase() == UNKNOWN_SENTINEL
            }
            val anyIsEmpty = listOf(brand, model, manufacturer).any { it == "" }

            // Brand/manufacturer alignment check only fires when both
            // are non-null and non-empty — otherwise the empty-property
            // rule has already fired (cascade-earlier) or the
            // confidence has degraded.
            val brandManufacturerMismatch = brand != null && manufacturer != null &&
                brand.isNotEmpty() && manufacturer.isNotEmpty() &&
                !isBrandManufacturerAligned(brand, manufacturer)

            val (pattern, score) = when {
                modelHasEmuKeyword ->
                    PATTERN_MODEL_EMULATOR_KEYWORD to SCORE_MODEL_EMULATOR_KEYWORD
                anyIsUnknown ->
                    PATTERN_UNKNOWN_SENTINEL to SCORE_UNKNOWN_SENTINEL
                anyIsEmpty ->
                    PATTERN_EMPTY_PROPERTY to SCORE_EMPTY_PROPERTY
                brandManufacturerMismatch ->
                    PATTERN_BRAND_MANUFACTURER_MISMATCH to SCORE_BRAND_MANUFACTURER_MISMATCH
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (observed >= 2) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            // Tri-state alignment evidence — three values currently
            // emitted:
            //   "true"          both observed AND aligned
            //   "false"         both observed AND not aligned (mismatch)
            //   "unknown"       at least one is null/empty — cannot
            //                   compute alignment honestly
            //
            // Forward-looking planning anchor (NOT currently emitted): a
            // future tri-state alias-extension could surface
            // `"unknown_oem"` for the case where both inputs are
            // observed but the alias-table doesn't recognize either
            // OEM. Today, that case collapses to "false" because the
            // mismatch rule already fires at 0.85. The four-valued
            // evidence would let a downstream consumer distinguish
            // "definitely-wrong" from "unrecognized-but-possibly-new-OEM".
            val alignmentDisplay = when {
                brand.isNullOrEmpty() || manufacturer.isNullOrEmpty() -> "unknown"
                isBrandManufacturerAligned(brand, manufacturer) -> "true"
                else -> "false"
            }

            val evidence = listOf(
                Evidence(
                    key = PROP_RO_PRODUCT_BRAND,
                    value = brand ?: "<unavailable>",
                    expected = "<lowercase OEM>",
                ),
                Evidence(
                    key = PROP_RO_PRODUCT_MODEL,
                    value = model ?: "<unavailable>",
                    expected = "<no emu keyword>",
                ),
                Evidence(
                    key = PROP_RO_PRODUCT_MANUFACTURER,
                    value = manufacturer ?: "<unavailable>",
                    expected = "<matches brand case-insensitive>",
                ),
                Evidence(
                    key = "buildprop.brand_manufacturer_aligned",
                    value = alignmentDisplay,
                    expected = "true",
                ),
                Evidence(
                    key = "buildprop.has_emulator_model_keyword",
                    value = if (model == null) "unknown" else modelHasEmuKeyword.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "buildprop.model_brand_manufacturer_pattern",
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
                "ModelBrandManufacturerProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

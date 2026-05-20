// inventory.yml rank 36, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #36 — env.language_country.
 *
 * Reads `Locale.getDefault()` and the build-time `ro.product.locale*`
 * system properties, then looks for emulator-class default states a real
 * device user never persistently sets:
 *   • Empty country code AND empty `ro.product.locale.region` — stock AOSP
 *     emulator default; real Android shipments always pin one.
 *   • Invalid ISO 3166-1 alpha-2 country (`XX`, `ZZ`, `00`, etc.) — synthetic
 *     stub no real OEM ships.
 *   • Empty language on phone-class hardware — emulator default.
 *   • Runtime locale differs from build-time `ro.product.locale*` — capped at
 *     0.5 because legitimate users do change locale after first boot.
 *
 * **No live IP geolocation.** Probe invariant #2 forbids network calls. This
 * probe is the language/country sibling of rank 20 (timezone-vs-locale-country)
 * but checks the *internal* consistency of language+country and the build-vs-
 * runtime locale layer.
 *
 * **Distinct from rank 20.** Rank 20 cross-references timezone against locale
 * country via a static table. This probe checks locale-country self-validity
 * (ISO code well-formedness) and build-vs-runtime divergence — orthogonal
 * signals.
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the empty-language
 * phone-class gate (tablets / TV boxes legitimately ship with stripped locale).
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.85  country `""` AND `ro.product.locale.region` `""` (both layers
 *         report no country — emulator default)
 *   0.85  country present but not a valid ISO 3166-1 alpha-2 code
 *         (`XX`, `ZZ`, `00`, mixed-case stubs)
 *   0.85  language `""` on phone-class hardware (emulator default — tablets
 *         and Android-TV legitimately ship without language)
 *   0.70  language present but country empty AND no system-property region —
 *         "older emulator" partial-locale signal
 *   0.50  Runtime locale (`<lang>_<country>`) differs from build-time
 *         `ro.product.locale*` — capped because users legitimately change
 *         locale after first boot
 *   0.00  Valid ISO language + ISO country, runtime matches build
 *
 * Confidence:
 *   0.95  Locale (language + country) readable AND at least one ro.product
 *         property readable
 *   0.60  Locale readable but no build-time property (one surface)
 *   0.50  Locale unreadable (suppliers all returned null)
 *
 * **Test injection:** the no-arg constructor reads from
 * `ProbeContext.queryLocaleLanguage()` / `queryLocaleCountry()` /
 * `queryLocaleDisplayName()` (default-methods returning `null` on bare
 * `ProbeContext` impls — production wrappers and `SnapshotReplayContext`
 * override). Tests can override per-signal by passing an explicit supplier;
 * a non-null supplier wins over the `ctx.queryX()` fallback, and `{ null }`
 * as a supplier explicitly disarms the surface. The probe-quality refactor
 * (Power-8 phase 3, 2026-05-20) flipped the no-arg default from
 * `Locale.getDefault()` to `ctx.queryLocale*()`.
 *
 * Reference: shared/probes/inventory.yml rank 36 (mitigation_layer per
 * inventory; L1 per team-lead brief alignment with rank 20).
 */
class LanguageCountryProbe(
    private val localeLanguageSupplier: (() -> String?)? = null,
    private val localeCountrySupplier: (() -> String?)? = null,
    private val localeDisplayNameSupplier: (() -> String?)? = null,
) : Probe {
    override val id = "env.language_country"
    override val rank = 36
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_LOCALE = "ro.product.locale"
        const val PROP_RO_PRODUCT_LOCALE_LANGUAGE = "ro.product.locale.language"
        const val PROP_RO_PRODUCT_LOCALE_REGION = "ro.product.locale.region"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /** ISO 3166-1 alpha-2: 2 uppercase letters (A-Z). */
        private val ISO_COUNTRY_REGEX = Regex("^[A-Z]{2}$")

        /** ISO 639-1: 2 lowercase letters (a-z). */
        private val ISO_LANGUAGE_REGEX = Regex("^[a-z]{2,3}$")

        /**
         * Country codes that are syntactically `[A-Z]{2}` but not assigned to any
         * real country in ISO 3166-1. Common test-stub values.
         */
        val KNOWN_STUB_COUNTRIES = setOf("XX", "ZZ", "QQ", "AA")

        const val PATTERN_EMPTY_COUNTRY_BOTH_LAYERS = "empty_country_both_layers"
        const val PATTERN_INVALID_ISO_COUNTRY = "invalid_iso_country"
        const val PATTERN_EMPTY_LANGUAGE_PHONE = "empty_language_phone"
        const val PATTERN_LANGUAGE_WITHOUT_COUNTRY = "language_without_country"
        const val PATTERN_BUILD_VS_RUNTIME_MISMATCH = "build_vs_runtime_mismatch"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMPTY_COUNTRY_BOTH_LAYERS = 0.85
        const val SCORE_INVALID_ISO_COUNTRY = 0.85
        const val SCORE_EMPTY_LANGUAGE_PHONE = 0.85
        const val SCORE_LANGUAGE_WITHOUT_COUNTRY = 0.70
        const val SCORE_BUILD_VS_RUNTIME_MISMATCH = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read Locale.getDefault() + ro.product.locale.* properties; detect " +
                "empty-country/language defaults, invalid ISO codes, and runtime-" +
                "vs-build locale divergence. No live IP geolocation."

        /** True iff [country] is exactly 2 uppercase letters AND not a known stub. */
        internal fun isValidIsoCountry(country: String?): Boolean {
            if (country == null || country.isEmpty()) return false
            if (!ISO_COUNTRY_REGEX.matches(country)) return false
            return country !in KNOWN_STUB_COUNTRIES
        }

        /** True iff [language] is 2-3 lowercase letters (ISO 639-1/639-2). */
        internal fun isValidIsoLanguage(language: String?): Boolean {
            if (language == null || language.isEmpty()) return false
            return ISO_LANGUAGE_REGEX.matches(language)
        }

        /**
         * Normalizes `ro.product.locale` (`zh-CN`, `en_US`) into `<lang>_<country>`
         * with lowercase language and uppercase country. Returns null on
         * empty/null input.
         */
        internal fun normalizeBuildLocale(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('-', '_').filter { it.isNotEmpty() }
            return when (parts.size) {
                1 -> parts[0].lowercase()
                else -> "${parts[0].lowercase()}_${parts[1].uppercase()}"
            }
        }

        /** Formats runtime locale into `<lang>_<country>` for build-vs-runtime compare. */
        internal fun runtimeLocaleString(language: String?, country: String?): String? {
            if (language.isNullOrEmpty()) return null
            return if (country.isNullOrEmpty()) language.lowercase()
            else "${language.lowercase()}_${country.uppercase()}"
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-signal cascade: a non-null supplier wins (caller wants to
            // pin the value explicitly — even returning null disarms the
            // surface). When no supplier was provided the probe reads from
            // ProbeContext default-method accessors, which return null on
            // bare ProbeContext impls and the snapshot field on
            // SnapshotReplayContext / a real platform value on the
            // production ProbeContext wrapper. Phase-3 refactor closeout
            // (Power-8, 2026-05-20): replaces the previous JVM-default
            // fallback that leaked the host Locale.getDefault() into the
            // probe result.
            val languageRaw: String? = try {
                if (localeLanguageSupplier != null) localeLanguageSupplier.invoke()
                else ctx.queryLocaleLanguage()
            } catch (_: Throwable) {
                null
            }
            val countryRaw: String? = try {
                if (localeCountrySupplier != null) localeCountrySupplier.invoke()
                else ctx.queryLocaleCountry()
            } catch (_: Throwable) {
                null
            }
            val displayNameRaw: String? = try {
                if (localeDisplayNameSupplier != null) localeDisplayNameSupplier.invoke()
                else ctx.queryLocaleDisplayName()
            } catch (_: Throwable) {
                null
            }

            val language = languageRaw?.trim()
            val country = countryRaw?.trim()

            val buildLocale = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_LOCALE)
            } catch (_: Throwable) {
                null
            }
            val buildLocaleLang = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_LOCALE_LANGUAGE)
            } catch (_: Throwable) {
                null
            }
            val buildLocaleRegion = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_LOCALE_REGION)
            } catch (_: Throwable) {
                null
            }
            val model = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)
            val countryEmpty = country.isNullOrEmpty()
            val languageEmpty = language.isNullOrEmpty()
            val buildRegionEmpty = buildLocaleRegion.isNullOrEmpty()
            val buildCombinedEmpty = buildLocale.isNullOrEmpty() && buildRegionEmpty

            val invalidIsoCountry = !countryEmpty && !isValidIsoCountry(country)

            val normalizedBuildLocale = normalizeBuildLocale(buildLocale)
                ?: normalizeBuildLocale(
                    if (!buildLocaleLang.isNullOrEmpty() && !buildLocaleRegion.isNullOrEmpty())
                        "${buildLocaleLang}_${buildLocaleRegion}"
                    else null,
                )
            val runtimeLocale = runtimeLocaleString(language, country)
            val buildVsRuntimeMismatch = normalizedBuildLocale != null &&
                runtimeLocale != null && normalizedBuildLocale != runtimeLocale

            val (pattern, score) = when {
                // Empty country + empty build-region: stock emulator default.
                // (Build-region empty is necessary; the combined locale alone
                // may legitimately be missing on a stripped-property build.)
                countryEmpty && buildRegionEmpty && buildCombinedEmpty ->
                    PATTERN_EMPTY_COUNTRY_BOTH_LAYERS to SCORE_EMPTY_COUNTRY_BOTH_LAYERS
                invalidIsoCountry ->
                    PATTERN_INVALID_ISO_COUNTRY to SCORE_INVALID_ISO_COUNTRY
                languageEmpty && phoneClass ->
                    PATTERN_EMPTY_LANGUAGE_PHONE to SCORE_EMPTY_LANGUAGE_PHONE
                !languageEmpty && countryEmpty && buildRegionEmpty ->
                    PATTERN_LANGUAGE_WITHOUT_COUNTRY to SCORE_LANGUAGE_WITHOUT_COUNTRY
                buildVsRuntimeMismatch ->
                    PATTERN_BUILD_VS_RUNTIME_MISMATCH to SCORE_BUILD_VS_RUNTIME_MISMATCH
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val localeReadable = !languageEmpty || !countryEmpty
            val anyBuildReadable = !buildLocale.isNullOrEmpty() ||
                !buildLocaleLang.isNullOrEmpty() || !buildLocaleRegion.isNullOrEmpty()
            val confidence = when {
                localeReadable && anyBuildReadable -> CONFIDENCE_FULL
                localeReadable -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val languageDisplay = language ?: "<unreadable>"
            val countryDisplay = country?.ifEmpty { "<empty>" } ?: "<unreadable>"
            val displayNameDisplay = displayNameRaw?.trim()?.ifEmpty { "<empty>" } ?: "<unreadable>"
            val buildLocaleDisplay = buildLocale?.trim()?.ifEmpty { "<empty>" } ?: "<unreadable>"
            val buildRegionDisplay = buildLocaleRegion?.trim()?.ifEmpty { "<empty>" }
                ?: "<unreadable>"
            val isValidIso = isValidIsoLanguage(language) && isValidIsoCountry(country)

            val evidence = listOf(
                Evidence("locale.language", languageDisplay, expected = "<ISO 639-1>"),
                Evidence("locale.country", countryDisplay, expected = "<ISO 3166-1 alpha-2>"),
                Evidence(
                    key = "locale.display_name",
                    value = displayNameDisplay,
                    expected = "<human-readable>",
                ),
                Evidence(
                    key = "ro_product_locale",
                    value = buildLocaleDisplay,
                    expected = "<matches runtime locale>",
                ),
                Evidence(
                    key = "ro_product_locale_region",
                    value = buildRegionDisplay,
                    expected = "<matches country>",
                ),
                Evidence(
                    key = "locale.is_valid_iso",
                    value = isValidIso.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "locale.mismatch_build_vs_runtime",
                    value = buildVsRuntimeMismatch.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "locale.pattern",
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
                "LanguageCountryProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

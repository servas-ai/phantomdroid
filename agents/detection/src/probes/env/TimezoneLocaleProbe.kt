// inventory.yml rank 20, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import java.util.Locale
import java.util.TimeZone

/**
 * Probe #20 — env.timezone_locale_mismatch.
 *
 * Lab approximation of the IP-vs-timezone-vs-locale cross-check: compares the
 * device's default `java.util.TimeZone` against `java.util.Locale.getDefault()`'s
 * country code, using a small static table of common timezone → country pairs.
 * The IP-geolocation arm of the original detector is intentionally out of
 * scope for this probe — adding network reads would violate Probe invariant #2
 * ("must NOT make network requests to live third-party services").
 *
 * Signal flow (first match wins):
 *   1. **Signal unavailable** (0.50) — TZ id or locale country is null/empty.
 *   2. **Emulator default** (0.95) — TZ id is `UTC`, `GMT`, or starts with
 *      `Etc/`. Stock AOSP emulators ship with `UTC` by default; CI runners
 *      also default here.
 *   3. **Unknown pair** (0.50) — TZ id isn't in the lookup table. The probe
 *      can't make a claim either way; recorded at the same score as
 *      "signal unavailable" but with a distinct `pair_match` evidence value
 *      so consumers can disambiguate.
 *   4. **Locale unknown** (0.70) — TZ is in the table but locale country is
 *      `""` (i.e. `Locale.ROOT`). Locale stripping is a known fingerprint
 *      mitigation; missing the country specifically while the timezone is
 *      set is suspicious.
 *   5. **Mismatch** (1.00) — TZ → expected-country set doesn't include the
 *      device's locale country. Strong fingerprint-inconsistency signal.
 *   6. **Match** (0.00) — TZ → country set contains the locale country.
 *
 * Confidence: 0.95 if both signals were readable (non-null, non-empty);
 * 0.50 if either was missing.
 *
 * **Test injection:** the production constructor reads `TimeZone.getDefault()`
 * and `Locale.getDefault()` at probe-run time. Tests construct via the
 * supplier-injecting constructor so the result is fully deterministic
 * regardless of the JVM's host locale or timezone.
 *
 * **Maintenance note:** the timezone → country table is a deliberate
 * approximation — ~25 of the most common timezone-country pairs only. The
 * goal is "would a fingerprint-mitigation layer hit one of these obvious
 * combinations?", not a definitive timezone-to-country map (which doesn't
 * exist anyway — TZs like `Africa/Cairo` legitimately serve multiple
 * countries, and political timezone reassignments happen).
 *
 * Reference: shared/probes/inventory.yml rank 20 (mitigation_layer L1).
 */
class TimezoneLocaleProbe(
    private val timezoneIdSupplier: () -> String? = { TimeZone.getDefault().id },
    private val timezoneOffsetSupplier: () -> Int = {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    },
    private val localeCountrySupplier: () -> String? = { Locale.getDefault().country },
    private val localeLanguageSupplier: () -> String? = { Locale.getDefault().language },
) : Probe {
    override val id = "env.timezone_locale_mismatch"
    override val rank = 20
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        /**
         * Static lookup of the ~25 most common Olson timezone IDs to the
         * country code(s) that legitimately use them. Deliberately incomplete;
         * see KDoc maintenance note. Country codes are ISO 3166-1 alpha-2,
         * uppercase, matching `Locale.getDefault().country`.
         */
        val TIMEZONE_COUNTRY_TABLE: Map<String, Set<String>> = mapOf(
            "Europe/Berlin" to setOf("DE", "AT"),         // CET; Austria shares
            "Europe/Vienna" to setOf("AT"),
            "Europe/Zurich" to setOf("CH"),
            "Europe/Paris" to setOf("FR"),
            "Europe/Madrid" to setOf("ES"),
            "Europe/Rome" to setOf("IT"),
            "Europe/Amsterdam" to setOf("NL"),
            "Europe/Brussels" to setOf("BE"),
            "Europe/London" to setOf("GB"),
            "Europe/Dublin" to setOf("IE"),
            "Europe/Warsaw" to setOf("PL"),
            "Europe/Stockholm" to setOf("SE"),
            "Europe/Helsinki" to setOf("FI"),
            "Europe/Moscow" to setOf("RU"),
            "America/Los_Angeles" to setOf("US"),
            "America/Denver" to setOf("US"),
            "America/Chicago" to setOf("US"),
            "America/New_York" to setOf("US"),
            "America/Toronto" to setOf("CA"),
            "America/Vancouver" to setOf("CA"),
            "America/Mexico_City" to setOf("MX"),
            "America/Sao_Paulo" to setOf("BR"),
            "Asia/Shanghai" to setOf("CN"),
            "Asia/Hong_Kong" to setOf("HK"),
            "Asia/Tokyo" to setOf("JP"),
            "Asia/Seoul" to setOf("KR"),
            "Asia/Singapore" to setOf("SG"),
            "Asia/Kolkata" to setOf("IN"),
            "Asia/Dubai" to setOf("AE"),
            "Australia/Sydney" to setOf("AU"),
            "Pacific/Auckland" to setOf("NZ"),
            "Africa/Johannesburg" to setOf("ZA"),
            "Africa/Cairo" to setOf("EG"),
        )

        const val PAIR_MATCH = "match"
        const val PAIR_MISMATCH = "mismatch"
        const val PAIR_UNKNOWN_PAIR = "unknown_pair"
        const val PAIR_EMULATOR_DEFAULT = "emulator_default"
        const val PAIR_LOCALE_UNKNOWN = "locale_unknown"
        const val PAIR_SIGNAL_UNAVAILABLE = "signal_unavailable"

        const val SCORE_MISMATCH = 1.0
        const val SCORE_EMULATOR_DEFAULT = 0.95
        const val SCORE_LOCALE_UNKNOWN = 0.70
        const val SCORE_UNKNOWN_PAIR = 0.50
        const val SCORE_SIGNAL_UNAVAILABLE = 0.50
        const val SCORE_MATCH = 0.00

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_PARTIAL = 0.50

        const val METHOD =
            "Compare java.util.TimeZone.getDefault() against " +
                "java.util.Locale.getDefault().country using a static table " +
                "of common timezone→country mappings"

        private fun isEmulatorDefaultTz(tz: String): Boolean =
            tz == "UTC" || tz == "GMT" || tz.startsWith("Etc/")
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val tzId = try { timezoneIdSupplier() } catch (_: Throwable) { null }
            val tzOffsetMin = try { timezoneOffsetSupplier() } catch (_: Throwable) { 0 }
            val country = try { localeCountrySupplier() } catch (_: Throwable) { null }
            val language = try { localeLanguageSupplier() } catch (_: Throwable) { null }

            val tzMissing = tzId.isNullOrEmpty()
            // country == "" (Locale.ROOT) is a distinct signal from country == null
            // (system locale unset). The empty string means "locale was set but
            // country was stripped" — a known fingerprint-mitigation tell;
            // null means the accessor returned nothing at all.
            val countryNull = country == null
            val countryEmpty = country == ""

            val (pairLabel, score) = when {
                tzMissing || countryNull ->
                    PAIR_SIGNAL_UNAVAILABLE to SCORE_SIGNAL_UNAVAILABLE
                isEmulatorDefaultTz(tzId!!) ->
                    PAIR_EMULATOR_DEFAULT to SCORE_EMULATOR_DEFAULT
                tzId !in TIMEZONE_COUNTRY_TABLE ->
                    PAIR_UNKNOWN_PAIR to SCORE_UNKNOWN_PAIR
                countryEmpty ->
                    // TZ in table but locale stripped to Locale.ROOT (country="").
                    PAIR_LOCALE_UNKNOWN to SCORE_LOCALE_UNKNOWN
                country in TIMEZONE_COUNTRY_TABLE.getValue(tzId) ->
                    PAIR_MATCH to SCORE_MATCH
                else ->
                    PAIR_MISMATCH to SCORE_MISMATCH
            }

            val confidence = if (tzMissing || countryNull) {
                CONFIDENCE_PARTIAL
            } else {
                CONFIDENCE_NORMAL
            }

            val expectedTz = if (!country.isNullOrEmpty()) {
                TIMEZONE_COUNTRY_TABLE.entries
                    .firstOrNull { country in it.value }?.key ?: "<country-matching tz>"
            } else "<country-matching tz>"

            val expectedCountry = if (!tzId.isNullOrEmpty()) {
                TIMEZONE_COUNTRY_TABLE[tzId]?.joinToString("|") ?: "<tz-matching country>"
            } else "<tz-matching country>"

            val evidence = listOf(
                Evidence("timezone_id", tzId ?: "absent", expected = expectedTz),
                Evidence("locale_country", country ?: "absent", expected = expectedCountry),
                Evidence("timezone_offset_minutes", tzOffsetMin, expected = "<utc-offset>"),
                Evidence("locale_language", language ?: "absent", expected = "<bcp47>"),
                Evidence("pair_match", pairLabel, expected = PAIR_MATCH),
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
                "TimezoneLocaleProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 16, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #16 — identity.gaid.
 *
 * Reads the Google Advertising ID (GAID / AAID) and the `isLimitAdTracking`
 * opt-out flag from Google Play Services, then validates against:
 *   1. **Consistency**: an all-zero GAID is only legitimate when the user
 *      has opted out (limit-ad-tracking == true). All-zero with the opt-out
 *      flag false (or null) is a strong tampering signal.
 *   2. **Format**: GAIDs are 36-character UUID strings (32 hex + 4 dashes,
 *      e.g. `38400000-8cf0-11bd-b23e-10b96e40000d`). Anything else is
 *      malformed.
 *   3. **Entropy**: low Shannon entropy on the 32 hex characters is the
 *      same emulator-stub signal used by `AndroidIdProbe` for ANDROID_ID.
 *
 * **`AdvertisingIdInfo` accessor gap.** `ProbeContext` does NOT expose
 * a `queryAdvertisingId()` method — Play Services integration would be
 * Android-framework-bound and is out of scope for the pure-JVM probe
 * contract. The production no-arg constructor returns null for both
 * GAID and the opt-out flag and falls back to a Play-Services-installed
 * heuristic via `ctx.fileExists("/data/data/com.google.android.gms/")`.
 * In that mode the probe scores 0.7 (Play Services missing) or 0.0
 * (Play Services present, no further signal) at degraded confidence 0.5.
 * Test-mode injects suppliers for full-cascade exercise.
 *
 * Scoring (max wins across rules; never summed):
 *   1.00  GAID is all-zero AND opt-out flag is NOT `true` (inconsistent —
 *         the opt-out story doesn't match the zero ID, which is what
 *         Google's documentation says you'd only see on a real opt-out)
 *   0.85  GAID present but length != 36 OR doesn't match the UUID format
 *   0.75  GAID present, valid format, NOT all-zero, but Shannon entropy on
 *         the 32 hex digits is below 2.5 bits/char (suspected stub UUID)
 *   0.70  GAID is null AND Play Services dir is present (phone claims
 *         PS support but the AdvertisingIdInfo accessor returned nothing)
 *   0.00  GAID is null AND Play Services dir is absent (consistent —
 *         no PS, no GAID); OR valid GAID, real entropy, OR legitimate
 *         opt-out (all-zero + limit=true)
 *
 * Confidence: 0.95 when GAID supplier returned (production-side
 * AdvertisingIdInfo accessor worked); 0.50 when the supplier didn't
 * return — including the production-no-arg-ctor path where the supplier
 * is `{ null }` because the accessor isn't plumbed yet.
 *
 * **PII note:** GAID is personally identifiable per Google's policy.
 * The probe records it in structured Evidence rows only — no log/file/
 * network escape. Mirrors the rank-11 (AndroidIdProbe) convention.
 *
 * Reference: shared/probes/inventory.yml rank 16 (mitigation_layer L2).
 */
class GaidProbe(
    private val gaidSupplier: () -> String? = { null },
    private val limitAdTrackingSupplier: () -> Boolean? = { null },
) : Probe {
    override val id = "identity.gaid"
    override val rank = 16
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 200L

    companion object {
        const val PATH_PLAY_SERVICES_DATA = "/data/data/com.google.android.gms/"

        const val ALL_ZERO_GAID = "00000000-0000-0000-0000-000000000000"

        const val ENTROPY_THRESHOLD_BITS_PER_CHAR = 2.5

        const val SCORE_OPT_OUT_INCONSISTENT = 1.0
        const val SCORE_MALFORMED = 0.85
        const val SCORE_LOW_ENTROPY = 0.75
        const val SCORE_NULL_BUT_PS_PRESENT = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.5

        const val METHOD =
            "Read AdvertisingIdInfo (Google Play Services), validate UUID " +
                "format, check opt-out consistency, and detect known-emulator-" +
                "stub UUIDs"

        /**
         * GAID format: 8-4-4-4-12 lowercase hex (the canonical UUID layout
         * Google's `AdvertisingIdInfo.id` returns). Not strict UUID v4 —
         * the spec doesn't require the version nibble.
         */
        private val GAID_REGEX = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )

        internal fun isValidGaidFormat(s: String): Boolean = GAID_REGEX.matches(s)

        /** Strip the 4 dashes and return the 32 hex characters. */
        internal fun hexCharsOnly(gaid: String): String = gaid.replace("-", "")
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var gaidAccessorWorked = false
            val gaid: String? = try {
                val v = gaidSupplier()
                gaidAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            val limitAdTracking: Boolean? = try {
                limitAdTrackingSupplier()
            } catch (_: Throwable) {
                null
            }

            val playServicesPresent: Boolean = try {
                ctx.fileExists(PATH_PLAY_SERVICES_DATA)
            } catch (_: Throwable) {
                false
            }

            val gaidNormalized = gaid?.lowercase()
            val gaidIsNull = gaidNormalized == null
            val gaidIsAllZero = gaidNormalized == ALL_ZERO_GAID
            val gaidIsValidFormat = gaidNormalized != null && isValidGaidFormat(gaidNormalized)
            val gaidIsMalformed = !gaidIsNull && !gaidIsValidFormat

            val entropy = if (gaidIsValidFormat && !gaidIsAllZero) {
                AndroidIdProbe.shannonEntropyBitsPerChar(hexCharsOnly(gaidNormalized!!))
            } else 0.0

            val gaidLowEntropy = gaidIsValidFormat && !gaidIsAllZero &&
                entropy < ENTROPY_THRESHOLD_BITS_PER_CHAR

            // Consistency: zero-GAID is legitimate only when limit-ad-tracking
            // is explicitly true. A non-zero GAID is always self-consistent
            // regardless of the flag.
            val optOutConsistent = !gaidIsAllZero || limitAdTracking == true
            val optOutInconsistent = gaidIsAllZero && limitAdTracking != true

            val candidates = buildList {
                if (optOutInconsistent) add(SCORE_OPT_OUT_INCONSISTENT)
                if (gaidIsMalformed) add(SCORE_MALFORMED)
                if (gaidLowEntropy) add(SCORE_LOW_ENTROPY)
                if (gaidIsNull && playServicesPresent) add(SCORE_NULL_BUT_PS_PRESENT)
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            val confidence = if (gaidAccessorWorked && gaid != null) {
                CONFIDENCE_NORMAL
            } else {
                CONFIDENCE_DEGRADED
            }

            val displayGaid = gaid ?: "null"
            val gaidLength = gaid?.length ?: 0
            val limitAdTrackingDisplay = limitAdTracking?.toString() ?: "null"

            val evidence = listOf(
                Evidence("gaid", displayGaid, expected = "<UUID v4>"),
                Evidence("gaid.length", gaidLength, expected = "36"),
                Evidence("gaid.format_valid", gaidIsValidFormat.toString(), expected = "true"),
                Evidence("gaid.is_all_zero", gaidIsAllZero.toString(), expected = "false"),
                Evidence(
                    key = "gaid.limit_ad_tracking",
                    value = limitAdTrackingDisplay,
                    expected = "true_or_false (not null)",
                ),
                Evidence(
                    key = "gaid.entropy_bits_per_char",
                    value = "%.3f".format(java.util.Locale.ROOT, entropy),
                    expected = ">= 3.0",
                ),
                Evidence(
                    key = "gaid.opt_out_consistent",
                    value = optOutConsistent.toString(),
                    expected = "true",
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
                "GaidProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

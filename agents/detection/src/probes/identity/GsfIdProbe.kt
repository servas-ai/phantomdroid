// inventory.yml rank 17, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #17 — identity.gsf_id.
 *
 * Reads the Google Services Framework Android ID (GSF AID) — a 64-bit
 * identifier surfaced as 16 lowercase hex characters by the
 * `content://com.google.android.gsf.gservices` content provider — and
 * validates against:
 *   1. **Zero state** — `"0"` (Play Services returns the integer 0) or the
 *      all-zero 16-hex form. Either signals an emulator without a Play
 *      Services account.
 *   2. **Factory default `9774d56d682e549c`** — same documented AOSP
 *      emulator default as rank-11 ANDROID_ID. Indicates a stock lab image.
 *   3. **Sequential / low-entropy patterns** — the rank-11
 *      `SEQUENTIAL_DEBUG_VALUES` set covers shipped debug placeholders;
 *      Shannon entropy below 2.5 bits/char catches synthetic patterns the
 *      explicit list misses.
 *   4. **Null / empty** — Play Services framework is not installed on the
 *      device, or the gservices provider is not accessible. Suspicious on a
 *      device claiming a retail OS install (handsets pre-load Play Services).
 *
 * **ContentProvider gservices accessor gap.** `ProbeContext` does NOT
 * expose a `queryContentProvider()` method. The production no-arg
 * constructor returns null for the GSF supplier and falls back to a
 * Play-Services-framework-installed heuristic via
 * `ctx.fileExists("/data/data/com.google.android.gsf/")`. In stub mode the
 * probe scores 0.7 (null + GSF dir present) or 0.0 (no GSF dir, consistent
 * absence) at degraded confidence 0.5. Test-mode injects suppliers for the
 * full cascade.
 *
 * Scoring (max wins; never summed):
 *   1.00  GSF is `"0"` OR `"0000000000000000"`         (zero state)
 *   1.00  GSF == `9774d56d682e549c`                    (factory default)
 *   0.85  GSF in rank-11 `SEQUENTIAL_DEBUG_VALUES`     (known debug placeholder)
 *   0.85  GSF entropy < 2.5 bits/char on 16 hex digits (synthetic / stub)
 *   0.85  GSF length != 16 OR not pure lowercase hex   (malformed)
 *   0.70  GSF is null AND `/data/data/com.google.android.gsf/` exists
 *         (Play Services framework claims to be installed but the
 *         gservices provider returned nothing — strong "retail OS
 *         claim but no real account" signal)
 *   0.00  GSF is null AND no GSF dir (consistent absence — no PS, no GSF)
 *         OR valid 16-hex non-zero with entropy >= 2.5
 *
 * Confidence: 0.95 when the GSF supplier returned a non-null value; 0.50
 * when null or threw — including the production no-arg constructor path
 * where the supplier is `{ null }` because the gservices provider isn't
 * plumbed into ProbeContext yet.
 *
 * **PII note:** GSF AID is PII per Google's policy. Probe records it in
 * Evidence rows only — no log/file/network escape. Mirrors rank-11
 * (AndroidIdProbe) and rank-16 (GaidProbe).
 *
 * Reference: shared/probes/inventory.yml rank 17 (mitigation_layer L2).
 */
class GsfIdProbe(
    private val gsfIdSupplier: () -> String? = { null },
) : Probe {
    override val id = "identity.gsf_id"
    override val rank = 17
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        const val PATH_GSF_DATA = "/data/data/com.google.android.gsf/"

        const val ZERO_HEX = "0000000000000000"
        const val ZERO_INT = "0"

        const val ENTROPY_THRESHOLD_BITS_PER_CHAR = 2.5

        const val PATTERN_FACTORY_DEFAULT = "factory_default"
        const val PATTERN_SEQUENTIAL = "sequential"
        const val PATTERN_LOW_ENTROPY = "low_entropy"
        const val PATTERN_ZERO = "zero"
        const val PATTERN_NULL = "null"
        const val PATTERN_MALFORMED = "malformed"
        const val PATTERN_NORMAL = "normal"

        const val SCORE_ZERO_OR_FACTORY = 1.0
        const val SCORE_SEQUENTIAL_OR_LOW_ENTROPY = 0.85
        const val SCORE_MALFORMED = 0.85
        const val SCORE_NULL_BUT_GSF_PRESENT = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.5

        const val METHOD =
            "Read Google Services Framework android_id via ContentProvider " +
                "gservices, validate 16-hex format, check against factory-" +
                "default and entropy thresholds"

        private val HEX_16_REGEX = Regex("^[0-9a-f]{16}$")
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val gsfRaw: String? = try {
                gsfIdSupplier()
            } catch (_: Throwable) {
                null
            }

            val gsfPresent: Boolean = try {
                ctx.fileExists(PATH_GSF_DATA)
            } catch (_: Throwable) {
                false
            }

            // Normalize: lowercase + trim. The gservices provider returns
            // the value as a String already, but kernel-buffered reads may
            // include trailing whitespace.
            val gsf = gsfRaw?.trim()?.lowercase()

            val isNullOrEmpty = gsf.isNullOrEmpty()
            val isZero = gsf == ZERO_INT || gsf == ZERO_HEX
            val isFactoryDefault = gsf == AndroidIdProbe.FACTORY_DEFAULT_ANDROID_ID
            val isExactly16Hex = gsf != null && HEX_16_REGEX.matches(gsf)
            val isMalformed = !isNullOrEmpty && !isExactly16Hex && !isZero
            val isSequential = isExactly16Hex && !isZero && !isFactoryDefault &&
                gsf in AndroidIdProbe.SEQUENTIAL_DEBUG_VALUES

            val entropy = if (isExactly16Hex && !isZero) {
                AndroidIdProbe.shannonEntropyBitsPerChar(gsf!!)
            } else 0.0
            val isLowEntropy = isExactly16Hex && !isZero && !isFactoryDefault &&
                !isSequential && entropy < ENTROPY_THRESHOLD_BITS_PER_CHAR

            val (pattern, candidates) = when {
                isNullOrEmpty -> PATTERN_NULL to buildList<Double> {
                    if (gsfPresent) add(SCORE_NULL_BUT_GSF_PRESENT)
                }
                isZero -> PATTERN_ZERO to listOf(SCORE_ZERO_OR_FACTORY)
                isFactoryDefault -> PATTERN_FACTORY_DEFAULT to listOf(SCORE_ZERO_OR_FACTORY)
                isSequential -> PATTERN_SEQUENTIAL to listOf(SCORE_SEQUENTIAL_OR_LOW_ENTROPY)
                isLowEntropy -> PATTERN_LOW_ENTROPY to listOf(SCORE_SEQUENTIAL_OR_LOW_ENTROPY)
                isMalformed -> PATTERN_MALFORMED to listOf(SCORE_MALFORMED)
                else -> PATTERN_NORMAL to emptyList()
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            val confidence = if (gsf != null) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val displayGsf = gsfRaw ?: "null"
            val gsfLength = gsf?.length ?: 0

            val evidence = listOf(
                Evidence("gsf_id", displayGsf, expected = "<16-hex non-zero>"),
                Evidence("gsf_id.length", gsfLength, expected = "16"),
                Evidence("gsf_id.format_valid", isExactly16Hex.toString(), expected = "true"),
                Evidence("gsf_id.is_zero", isZero.toString(), expected = "false"),
                Evidence("gsf_id.pattern", pattern, expected = PATTERN_NORMAL),
                Evidence(
                    key = "gsf_id.entropy_bits_per_char",
                    value = "%.3f".format(java.util.Locale.ROOT, entropy),
                    expected = ">= 3.0",
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
                "GsfIdProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

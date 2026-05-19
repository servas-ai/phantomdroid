// inventory.yml rank 11, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import kotlin.math.ln

/**
 * Probe #11 — identity.android_id.
 *
 * Reads `Settings.Secure.ANDROID_ID` (a 64-bit hex string surfaced as 16
 * lowercase hex chars). Since Android 8.0 the value is scoped per app + per
 * user + per device, so a properly-provisioned device returns a value that
 * looks like a 16-hex-char random draw with full entropy. Emulator builds,
 * lab images, and naive mitigation layers (which return a placeholder
 * instead of a real per-app value) all produce recognisable distortions of
 * this surface.
 *
 * Score table (first match wins):
 *   1.00  value == `9774d56d682e549c` — documented factory default of the
 *         AOSP emulator and several never-reset lab images.
 *   0.85  value is null / empty / not exactly 16 lowercase hex chars —
 *         mitigation has been applied (random-fake) OR the platform has
 *         been stripped (e.g. cust ROM returning ""). Recorded as a
 *         strong-but-not-certain tampering signal because the user could
 *         also genuinely lack the SDK 26+ ANDROID_ID surface.
 *   0.95  value is in `SEQUENTIAL_DEBUG_VALUES` — all-zero, all-`f`,
 *         straight sequence, or other shipped-debug placeholders.
 *   0.75  Shannon entropy of the 16-char string is below 2.5 bits/char —
 *         catches `aabbccddeeff0011`-style synthetic patterns that aren't
 *         in the explicit debug list.
 *   0.00  Valid 16 hex chars with entropy ≥ 2.5 bits/char (normal).
 *
 * Confidence: 0.95 when the Settings.Secure accessor returned; 0.5 when it
 * threw. The empty/null case still emits 0.95 confidence — `null` is a real
 * answer the platform gave us, distinct from an inaccessible accessor.
 *
 * Reference: shared/probes/inventory.yml rank 11 (mitigation_layer L2).
 */
class AndroidIdProbe : Probe {
    override val id = "identity.android_id"
    override val rank = 11
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        /** `Settings.Secure.ANDROID_ID`. */
        const val SETTINGS_KEY_ANDROID_ID = "android_id"

        /**
         * Documented factory default of the AOSP emulator and several
         * never-reset lab images. Appears verbatim on stock `emulator -avd`
         * runs from at least Android 5 through 11.
         */
        const val FACTORY_DEFAULT_ANDROID_ID = "9774d56d682e549c"

        /**
         * Hex strings that have been seen shipped as `ANDROID_ID` debug
         * placeholders. All exactly 16 chars, all valid hex; some have low
         * Shannon entropy and would be caught by the entropy threshold too,
         * but the explicit list lets us tag them as `sequential` rather than
         * the more ambiguous `low_entropy`.
         */
        val SEQUENTIAL_DEBUG_VALUES: Set<String> = setOf(
            "0000000000000000",
            "ffffffffffffffff",
            "1111111111111111",
            "aaaaaaaaaaaaaaaa",
            "1234567890abcdef",
            "fedcba9876543210",
            "0123456789abcdef",
            "deadbeefdeadbeef",
            "cafebabecafebabe",
        )

        const val ENTROPY_THRESHOLD_BITS_PER_CHAR = 2.5

        const val SCORE_FACTORY_DEFAULT = 1.0
        const val SCORE_SEQUENTIAL = 0.95
        const val SCORE_EMPTY_OR_NON_HEX = 0.85
        const val SCORE_LOW_ENTROPY = 0.75
        const val SCORE_NORMAL = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_ACCESSOR_THREW = 0.5

        const val METHOD =
            "Read Settings.Secure.ANDROID_ID and check against known factory " +
                "defaults, sequential patterns, and Shannon entropy threshold"

        private val HEX_16_REGEX = Regex("^[0-9a-f]{16}$")

        /**
         * Shannon entropy in bits/character of `s`. Empty / single-char inputs
         * are treated as 0.0. Computed over the empirical char distribution
         * of `s` only — for a 16-char hex string this is plenty of samples
         * to distinguish `aaaaaaaaaaaaaaaa` (0 bits) from a real random draw
         * (~4 bits, the theoretical maximum for the hex alphabet).
         */
        internal fun shannonEntropyBitsPerChar(s: String): Double {
            if (s.length <= 1) return 0.0
            val counts = HashMap<Char, Int>()
            for (c in s) counts[c] = (counts[c] ?: 0) + 1
            val n = s.length.toDouble()
            var h = 0.0
            val log2 = ln(2.0)
            for ((_, c) in counts) {
                val p = c / n
                h -= p * (ln(p) / log2)
            }
            return h
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var accessorThrew = false
            val raw: String? = try {
                ctx.querySettingSecure(SETTINGS_KEY_ANDROID_ID)
            } catch (_: Throwable) {
                accessorThrew = true
                null
            }

            val value = raw ?: ""
            val length = value.length
            val isExactly16Hex = HEX_16_REGEX.matches(value)
            val entropy = if (isExactly16Hex) shannonEntropyBitsPerChar(value) else 0.0

            val (pattern, score, confidence) = when {
                accessorThrew ->
                    Triple("empty", 0.0, CONFIDENCE_ACCESSOR_THREW)
                value.isEmpty() ->
                    Triple("empty", SCORE_EMPTY_OR_NON_HEX, CONFIDENCE_NORMAL)
                !isExactly16Hex ->
                    Triple("non_hex", SCORE_EMPTY_OR_NON_HEX, CONFIDENCE_NORMAL)
                value == FACTORY_DEFAULT_ANDROID_ID ->
                    Triple("default", SCORE_FACTORY_DEFAULT, CONFIDENCE_NORMAL)
                value in SEQUENTIAL_DEBUG_VALUES ->
                    Triple("sequential", SCORE_SEQUENTIAL, CONFIDENCE_NORMAL)
                entropy < ENTROPY_THRESHOLD_BITS_PER_CHAR ->
                    Triple("low_entropy", SCORE_LOW_ENTROPY, CONFIDENCE_NORMAL)
                else ->
                    Triple("normal", SCORE_NORMAL, CONFIDENCE_NORMAL)
            }

            val displayValue = when {
                accessorThrew -> "(accessor threw)"
                raw == null -> "(null)"
                else -> raw
            }

            val evidence = listOf(
                Evidence("android_id", displayValue, expected = "<unique 16-hex>"),
                Evidence("android_id.length", length.toString(), expected = "16"),
                Evidence("android_id.pattern", pattern, expected = "normal"),
                Evidence(
                    key = "android_id.entropy_bits_per_char",
                    value = "%.3f".format(java.util.Locale.ROOT, entropy),
                    expected = ">=3.0",
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
                "AndroidIdProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

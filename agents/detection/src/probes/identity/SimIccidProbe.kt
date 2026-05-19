// inventory.yml rank 21, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.TelephonyField

/**
 * Probe #21 — identity.sim_iccid.
 *
 * Reads the SIM card's Integrated Circuit Card Identifier (ICCID) via
 * `TelephonyManager.simSerialNumber` (mapped to `TelephonyField.SIM_SERIAL`
 * on `ProbeContext`) and validates against four properties of a real
 * carrier-issued ICCID:
 *
 *   1. **89-prefix**: every ITU-T E.118 telecom ICCID starts with `89`.
 *      Anything else is either a non-telecom card (rare on phones) or a
 *      fabricated value.
 *   2. **Length 19 or 20**: 19-digit form is standard; 20-digit is used by
 *      some carriers as a Luhn-padded variant.
 *   3. **Luhn-mod-10 checksum**: applies to the full digit string.
 *   4. **Not in the documented emulator-default set** and not a degenerate
 *      pattern (all-same-digit, monotonic-ascending).
 *
 * Scoring (max wins across rules; never summed — first-match cascade in
 * practice because the categories are structurally exclusive):
 *   1.00  ICCID in `KNOWN_EMULATOR_ICCIDS`
 *   1.00  All-same-digit (e.g. all-zero) OR monotonic-ascending sequence
 *   0.95  Doesn't start with "89"
 *   0.85  Length not in {19, 20} or contains non-digit characters
 *   0.90  Luhn checksum fails (only checked after length+prefix pass)
 *   0.70  Null AND TelephonyManager accessor worked (Android 10+ privacy
 *         OR genuine no-SIM emulator — suspicious for a retail OS claim)
 *   0.00  Null AND accessor threw (no observation possible — degraded)
 *         OR valid 19-or-20-digit 89-prefixed Luhn-checked ICCID
 *
 * Confidence: 0.95 when `TelephonyManager.simSerialNumber` accessor
 * returned non-null; 0.50 when null (no SIM / privacy-stripped / accessor
 * threw — can't distinguish without finer-grained accessor flags).
 *
 * **PII note:** ICCID is a unique SIM serial protected by carrier ToS.
 * Probe records it in structured Evidence rows only — no log/file/network
 * escape. Mirrors rank-12 (ImeiSerialProbe) and rank-16 (GaidProbe).
 *
 * Reference: shared/probes/inventory.yml rank 21 (mitigation_layer L2).
 */
class SimIccidProbe : Probe {
    override val id = "identity.sim_iccid"
    override val rank = 21
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val ICCID_PREFIX = "89"

        /**
         * Documented emulator-default ICCIDs. AOSP-emulator ships a baked-in
         * value; carriers regional test cards have shipped publicly too. Both
         * are uniquely identifiable, so an exact-equality match warrants the
         * top score even when Luhn passes.
         */
        val KNOWN_EMULATOR_ICCIDS: Set<String> = setOf(
            "89014103211118510720",     // canonical AOSP emulator ICCID
            "89014103200000000000",     // AOSP emulator zero-tail variant
            "89860000000000000000",     // China-region test card
        )

        const val PATTERN_VALID = "valid"
        const val PATTERN_EMULATOR_DEFAULT = "emulator_default"
        const val PATTERN_REPEATING = "repeating"
        const val PATTERN_SEQUENTIAL = "sequential"
        const val PATTERN_NO_89_PREFIX = "no_89_prefix"
        const val PATTERN_MALFORMED_LENGTH = "malformed_length"
        const val PATTERN_LUHN_FAIL = "luhn_fail"
        const val PATTERN_NULL = "null"

        const val SCORE_KNOWN_EMULATOR = 1.0
        const val SCORE_REPEATING_OR_SEQUENTIAL = 1.0
        const val SCORE_NO_89_PREFIX = 0.95
        const val SCORE_LUHN_FAIL = 0.90
        const val SCORE_MALFORMED_LENGTH = 0.85
        const val SCORE_NULL_BUT_ACCESSOR_WORKED = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read TelephonyManager.simSerialNumber, validate 89-prefix + " +
                "length + Luhn checksum, detect known emulator-default ICCIDs"

        private val DIGITS_19_20_REGEX = Regex("^\\d{19,20}$")

        /**
         * Luhn (mod-10) checksum over a variable-length numeric string.
         * Returns false for any input that isn't all digits.
         *
         * Note: variant of `ImeiSerialProbe.luhn15` — the IMEI version
         * hard-codes the 15-digit length. ICCIDs are 19 or 20 digits so we
         * need the variable-length form. Algorithm is identical: starting
         * from the rightmost digit (check digit, NOT doubled), alternate
         * doubling; subtract 9 if the doubled value exceeds 9; sum all and
         * verify mod 10 == 0.
         */
        internal fun luhn(s: String): Boolean {
            if (s.isEmpty() || s.any { !it.isDigit() }) return false
            var sum = 0
            for ((i, c) in s.reversed().withIndex()) {
                var d = c.digitToInt()
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }

        /** True iff every character of [s] is the same digit. */
        internal fun isAllSameDigit(s: String): Boolean =
            s.isNotEmpty() && s.all { it == s[0] && it.isDigit() }

        /**
         * True iff [s] is a strict monotonic ascending sequence of decimal
         * digits, with wrap-around at 9 (e.g. `01234567890123456789` after
         * `01234567890` repeats — the test wraps because real "sequential"
         * stub values restart at 0 after reaching 9).
         */
        internal fun isMonotonicAscending(s: String): Boolean {
            if (s.length < 2 || s.any { !it.isDigit() }) return false
            for (i in 1 until s.length) {
                val prev = s[i - 1].digitToInt()
                val curr = s[i].digitToInt()
                val expected = (prev + 1) % 10
                if (curr != expected) return false
            }
            return true
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var accessorWorked = false
            val iccidRaw: String? = try {
                val v = ctx.queryTelephonyManager(TelephonyField.SIM_SERIAL)
                accessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            val iccid = iccidRaw?.trim()
            val isNullOrEmpty = iccid.isNullOrEmpty()

            // Pre-compute the classification booleans once; the `when` cascade
            // below picks the first matching category in priority order.
            val isKnownEmulator = !isNullOrEmpty && iccid in KNOWN_EMULATOR_ICCIDS
            val isDigitsOnlyAndCorrectLength = !isNullOrEmpty &&
                DIGITS_19_20_REGEX.matches(iccid!!)
            val isAllSame = isDigitsOnlyAndCorrectLength && isAllSameDigit(iccid!!)
            val isSequential = isDigitsOnlyAndCorrectLength && isMonotonicAscending(iccid!!)
            val hasGoodPrefix = isDigitsOnlyAndCorrectLength && iccid!!.startsWith(ICCID_PREFIX)
            val luhnValid = isDigitsOnlyAndCorrectLength && luhn(iccid!!)

            val (pattern, score) = when {
                isNullOrEmpty && accessorWorked ->
                    PATTERN_NULL to SCORE_NULL_BUT_ACCESSOR_WORKED
                isNullOrEmpty ->
                    PATTERN_NULL to SCORE_CLEAN
                isKnownEmulator ->
                    PATTERN_EMULATOR_DEFAULT to SCORE_KNOWN_EMULATOR
                !isDigitsOnlyAndCorrectLength ->
                    PATTERN_MALFORMED_LENGTH to SCORE_MALFORMED_LENGTH
                isAllSame ->
                    PATTERN_REPEATING to SCORE_REPEATING_OR_SEQUENTIAL
                isSequential ->
                    PATTERN_SEQUENTIAL to SCORE_REPEATING_OR_SEQUENTIAL
                !hasGoodPrefix ->
                    PATTERN_NO_89_PREFIX to SCORE_NO_89_PREFIX
                !luhnValid ->
                    PATTERN_LUHN_FAIL to SCORE_LUHN_FAIL
                else ->
                    PATTERN_VALID to SCORE_CLEAN
            }

            val confidence = if (iccid != null) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val displayIccid = iccidRaw ?: "null"
            val length = iccid?.length ?: 0
            val prefixValid = hasGoodPrefix

            val evidence = listOf(
                Evidence(
                    key = "sim_iccid",
                    value = displayIccid,
                    expected = "<89-prefix 19-or-20-digit Luhn-valid>",
                ),
                Evidence("sim_iccid.length", length, expected = "19 or 20"),
                Evidence(
                    key = "sim_iccid.prefix_valid",
                    value = prefixValid.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "sim_iccid.luhn_valid",
                    value = luhnValid.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "sim_iccid.is_known_emulator",
                    value = isKnownEmulator.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "sim_iccid.pattern",
                    value = pattern,
                    expected = PATTERN_VALID,
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
                "SimIccidProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

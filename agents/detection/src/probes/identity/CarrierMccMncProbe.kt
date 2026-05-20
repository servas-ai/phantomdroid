// inventory.yml rank 22, mitigation_layer L2
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
 * Probe #22 — identity.carrier_mccmnc.
 *
 * Reads the device's mobile-network identity (MCC+MNC, carrier name,
 * Line1 phone number, IMSI) via `TelephonyManager` and validates
 * against:
 *
 *   1. **Known emulator-default carrier names**: `"Android"` (AVD literal),
 *      `"Test"` (older test profile), substring matches for `Genymotion`,
 *      `AVD`, `Emulator`.
 *   2. **Synthetic MCC values**: `000` (no network) and `001` (test network)
 *      per ITU E.212.
 *   3. **AVD canonical signature**: MCC=310 + MNC=260 paired with an
 *      `Android`-prefixed operator name. This is the AOSP emulator default
 *      for the past 10+ years.
 *   4. **MCC outside the ~30-entry known-prefix list**: lab approximation,
 *      NOT a definitive ITU lookup. Catches obvious synthetics like `999`.
 *   5. **Empty operator name when MCC+MNC is observable**: emulator with
 *      no carrier metadata loaded.
 *   6. **Power-13 Gap #6 — Emulator Line1 phone numbers**: AOSP
 *      emulator + Genymotion ship 16 canonical phone numbers in the
 *      `15555215554..15555215584` block (strazzere/anti-emulator,
 *      EmulatorDetector). Reading any of these via
 *      `TelephonyManager.getLine1Number()` is dispositive (`READ_PHONE_NUMBERS`
 *      A8+ or `READ_PHONE_STATE` earlier).
 *   7. **Power-13 Gap #6 — Emulator canonical IMSI**: AOSP emulator
 *      ships subscriber-id `310260000000000` (T-Mobile MCC/MNC +
 *      10-zero suffix). Dispositive when readable
 *      (`READ_PRIVILEGED_PHONE_STATE` A10+ system-only — null on
 *      consumer-app contexts even on real devices, NOT scored as
 *      suspicious).
 *
 * **Single-source gap.** `TelephonyField` (`ProbeContext.kt`) exposes
 * `OPERATOR_NAME` and `MCC_MNC` — there's no separate `SIM_OPERATOR`
 * vs `NETWORK_OPERATOR` enumeration. The spec's network-vs-SIM
 * cross-check for roaming detection therefore isn't implementable in
 * this probe. Evidence rows for `telephony.sim_operator` /
 * `telephony.sim_operator_name` are emitted with the same value as
 * the network rows (single source) so the schema is preserved.
 * Adding `SIM_OPERATOR` + `SIM_OPERATOR_NAME` to `TelephonyField`
 * would unlock the cross-check — tracked as follow-up.
 *
 * Scoring (max wins across rules; never summed):
 *   1.00  operatorName ∈ {`Android`, `Test`} OR contains `Genymotion`,
 *         `AVD`, or `Emulator` (case-insensitive)
 *   1.00  line1Number ∈ EMULATOR_PHONE_NUMBERS (16-entry AOSP block)
 *   0.95  MCC = `000` or `001`
 *   0.95  MCC=`310` + MNC=`260` + operatorName starts with `Android`
 *         (AOSP-emulator canonical signature — collapses into the 1.0
 *         rule in practice because `Android` is in the operatorName
 *         set; kept here for clarity and forensic-distinct pattern
 *         label)
 *   0.95  imsi == AOSP_DEFAULT_IMSI `310260000000000`
 *   0.85  MCC not in `KNOWN_MCC_PREFIXES` (lab approximation; obvious
 *         synthetics like `999` fire)
 *   0.75  operatorName is empty/null AND MCC+MNC is non-empty
 *         (suspicious bare carrier metadata)
 *   0.00  Known-real-MCC + populated operator name (or all unobservable)
 *
 * Confidence: 0.95 when ≥ 2 of the operator-name / MCC+MNC accessors
 * returned non-null cleanly; 0.60 when exactly 1; 0.30 when 0.
 * Line1/IMSI accessors are NOT factored into confidence because they
 * are gated by permissions that are typically absent on consumer
 * contexts — their null IS the expected answer for a clean device
 * and shouldn't degrade confidence below the operator/MCC tier.
 *
 * **PII note**: carrier name + MCC/MNC is low-PII (not per-user) but
 * Line1 + IMSI are PER-USER PII. Same lab-only handling as rank 12 /
 * 21. Evidence rows redact Line1/IMSI to a 4-character prefix +
 * length when value is observed but does NOT match an emulator
 * signature, preserving the dispositive-match diagnostic while
 * scrubbing real PII from telemetry. Exact emulator-matched values
 * are emitted verbatim (the dispositive value IS the diagnostic).
 *
 * Reference: shared/probes/inventory.yml rank 22 (mitigation_layer L2);
 * strazzere/anti-emulator FindEmulator.java
 * (`https://github.com/strazzere/anti-emulator`) — KNOWN_NUMBERS +
 * IMSI constants.
 */
class CarrierMccMncProbe : Probe {
    override val id = "identity.carrier_mccmnc"
    override val rank = 22
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        /** Literal operator names shipped by the AOSP emulator and clones. */
        val KNOWN_EMULATOR_OPERATOR_NAMES_EXACT: Set<String> = setOf(
            "android",
            "test",
        )

        /** Case-insensitive substrings indicating an emulator carrier label. */
        val EMULATOR_OPERATOR_NAME_SUBSTRINGS: List<String> = listOf(
            "genymotion",
            "avd",
            "emulator",
        )

        /**
         * The 16-entry AOSP emulator + Genymotion Line1 phone-number
         * block (`15555215554`..`15555215584`, every even-numbered
         * suffix). Source: strazzere/anti-emulator FindEmulator.java
         * `KNOWN_NUMBERS` constants — these are the literals AOSP
         * boots with for the 16 reserved emulator phone slots. Any
         * `TelephonyManager.getLine1Number()` returning a value in
         * this set is dispositive — a real consumer SIM can never
         * report these. Power-13 Gap #6 closure.
         */
        val EMULATOR_PHONE_NUMBERS: Set<String> = setOf(
            "15555215554", "15555215556", "15555215558", "15555215560",
            "15555215562", "15555215564", "15555215566", "15555215568",
            "15555215570", "15555215572", "15555215574", "15555215576",
            "15555215578", "15555215580", "15555215582", "15555215584",
        )

        /**
         * AOSP emulator canonical IMSI. The `310260` prefix is T-Mobile
         * US (the AOSP-default MCC/MNC pair) and the 10-zero suffix is
         * the AOSP synthetic subscriber slot. EmulatorDetector and
         * strazzere/anti-emulator both flag this exact literal.
         * Power-13 Gap #6 closure.
         */
        const val AOSP_DEFAULT_IMSI = "310260000000000"

        /**
         * ITU E.212 MCC prefixes of the most populous mobile markets. Lab
         * approximation — about 30 entries — NOT an exhaustive list. The
         * detection rule scores 0.85 when an observed MCC is outside this
         * set, which is intentionally a "weak suspicion" rather than a
         * confirmation (a real Senegalese carrier with MCC=608 would
         * trigger this and that's an acceptable lab false-positive).
         */
        val KNOWN_MCC_PREFIXES: Set<String> = setOf(
            // Europe (major markets)
            "232", "262", "234", "208", "214", "222", "234", "240", "242", "244",
            "260", "228", "204", "206", "216", "230",
            // North America
            "302", "310", "311", "312", "313", "314", "315", "316", "330", "334",
            // Asia
            "404", "405", "440", "454", "460", "470", "510", "520", "525", "530",
            "535", "404", "452",
            // Oceania
            "505", "530",
            // South America
            "722", "724",
            // Africa
            "604", "605", "655",
        )

        const val SYNTHETIC_MCC_000 = "000"
        const val SYNTHETIC_MCC_001 = "001"
        const val AVD_DEFAULT_MCC = "310"
        const val AVD_DEFAULT_MNC = "260"
        const val AVD_DEFAULT_OPERATOR_PREFIX = "Android"

        const val PATTERN_VALID = "valid"
        const val PATTERN_EMULATOR_OPERATOR_NAME = "emulator_operator_name"
        const val PATTERN_EMULATOR_PHONE_NUMBER = "emulator_phone_number"
        const val PATTERN_SYNTHETIC_MCC = "synthetic_mcc"
        const val PATTERN_AVD_CANONICAL = "avd_canonical"
        const val PATTERN_EMULATOR_IMSI = "emulator_imsi"
        const val PATTERN_UNKNOWN_MCC = "unknown_mcc"
        const val PATTERN_EMPTY_OPERATOR_NAME = "empty_operator_name"
        const val PATTERN_NULL = "null"

        const val SCORE_EMULATOR_OPERATOR_NAME = 1.0
        const val SCORE_EMULATOR_PHONE_NUMBER = 1.0
        const val SCORE_SYNTHETIC_MCC = 0.95
        const val SCORE_AVD_CANONICAL = 0.95
        const val SCORE_EMULATOR_IMSI = 0.95
        const val SCORE_UNKNOWN_MCC = 0.85
        const val SCORE_EMPTY_OPERATOR_NAME = 0.75
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read TelephonyManager.networkOperator + networkOperatorName + " +
                "line1Number + subscriberId; detect known emulator-default " +
                "carrier names, MCC/MNC values, AOSP-block phone numbers " +
                "(strazzere/anti-emulator 16-entry list), and AOSP " +
                "canonical IMSI 310260000000000. Power-13 Gap #6."

        private val MCC_REGEX = Regex("^\\d{3}$")

        /** Split an `MCC+MNC` string into a pair, returning empty strings on malformed input. */
        internal fun splitMccMnc(raw: String?): Pair<String, String> {
            if (raw == null || raw.length < 5 || raw.length > 6) return "" to ""
            val mcc = raw.take(3)
            val mnc = raw.drop(3)
            if (!MCC_REGEX.matches(mcc)) return "" to ""
            return mcc to mnc
        }

        internal fun isEmulatorOperatorName(name: String?): Boolean {
            if (name.isNullOrEmpty()) return false
            val lower = name.trim().lowercase()
            if (lower in KNOWN_EMULATOR_OPERATOR_NAMES_EXACT) return true
            return EMULATOR_OPERATOR_NAME_SUBSTRINGS.any { it in lower }
        }

        /**
         * True iff [number] is in the 16-entry AOSP/Genymotion emulator
         * phone-number block. Null and empty are NOT matches.
         */
        internal fun isEmulatorPhoneNumber(number: String?): Boolean {
            if (number.isNullOrEmpty()) return false
            return number in EMULATOR_PHONE_NUMBERS
        }

        /**
         * True iff [imsi] equals the AOSP canonical IMSI
         * `310260000000000`. Null and empty are NOT matches.
         */
        internal fun isEmulatorImsi(imsi: String?): Boolean {
            if (imsi.isNullOrEmpty()) return false
            return imsi == AOSP_DEFAULT_IMSI
        }

        /**
         * Redact a phone number or IMSI to its 4-char prefix and length
         * for evidence reporting when the value is observed but does
         * NOT match an emulator signature (i.e. real consumer PII).
         * Returns the raw value unchanged when it IS a known emulator
         * match — the dispositive value IS the diagnostic.
         */
        internal fun redactPiiUnlessEmulator(value: String?, isEmulator: Boolean): String {
            if (value.isNullOrEmpty()) return "<unavailable>"
            if (isEmulator) return value
            val prefix = value.take(4)
            val len = value.length
            return "${prefix}…(len=$len)"
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var nameAccessorWorked = false
            val operatorName: String? = try {
                val v = ctx.queryTelephonyManager(TelephonyField.OPERATOR_NAME)
                nameAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            var mccMncAccessorWorked = false
            val mccMncRaw: String? = try {
                val v = ctx.queryTelephonyManager(TelephonyField.MCC_MNC)
                mccMncAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            // Power-13 Gap #6 — Line1 + IMSI accessors. Both are
            // permission-gated and typically null on consumer
            // contexts; null is the expected answer and NOT scored
            // as suspicious. Per-field try/catch so a throwing
            // accessor doesn't suppress the others.
            val line1Number: String? = try {
                ctx.queryTelephonyManager(TelephonyField.LINE1_NUMBER)
            } catch (_: Throwable) {
                null
            }
            val subscriberId: String? = try {
                ctx.queryTelephonyManager(TelephonyField.SUBSCRIBER_ID)
            } catch (_: Throwable) {
                null
            }

            val (mcc, mnc) = splitMccMnc(mccMncRaw)
            val hasMccMnc = mcc.isNotEmpty()
            val hasName = !operatorName.isNullOrEmpty()

            val isEmuName = isEmulatorOperatorName(operatorName)
            val isEmuPhone = isEmulatorPhoneNumber(line1Number)
            val isEmuImsi = isEmulatorImsi(subscriberId)
            val isSyntheticMcc = mcc == SYNTHETIC_MCC_000 || mcc == SYNTHETIC_MCC_001
            val isAvdCanonical = mcc == AVD_DEFAULT_MCC && mnc == AVD_DEFAULT_MNC &&
                operatorName?.startsWith(AVD_DEFAULT_OPERATOR_PREFIX) == true
            val isUnknownMcc = hasMccMnc && !isSyntheticMcc && mcc !in KNOWN_MCC_PREFIXES
            val isEmptyNameWithMccMnc = !hasName && hasMccMnc

            // First-match cascade by precedence. Power-13 additions:
            //   - emulator_phone_number (1.0) is the most dispositive
            //     after emulator_operator_name and ordered second in
            //     the 1.0 tier; either alone is a smoking gun.
            //   - emulator_imsi (0.95) joins the 0.95 tier after the
            //     existing AVD-canonical rule. It's ordered LAST in
            //     the 0.95 tier so legacy pattern semantics
            //     (synthetic_mcc / avd_canonical) win on score-tie.
            val (pattern, score) = when {
                !nameAccessorWorked && !mccMncAccessorWorked &&
                    line1Number.isNullOrEmpty() && subscriberId.isNullOrEmpty() ->
                    PATTERN_NULL to SCORE_CLEAN
                !hasName && !hasMccMnc &&
                    line1Number.isNullOrEmpty() && subscriberId.isNullOrEmpty() ->
                    PATTERN_NULL to SCORE_CLEAN
                isEmuName ->
                    PATTERN_EMULATOR_OPERATOR_NAME to SCORE_EMULATOR_OPERATOR_NAME
                isEmuPhone ->
                    PATTERN_EMULATOR_PHONE_NUMBER to SCORE_EMULATOR_PHONE_NUMBER
                isSyntheticMcc ->
                    PATTERN_SYNTHETIC_MCC to SCORE_SYNTHETIC_MCC
                isAvdCanonical ->
                    PATTERN_AVD_CANONICAL to SCORE_AVD_CANONICAL
                isEmuImsi ->
                    PATTERN_EMULATOR_IMSI to SCORE_EMULATOR_IMSI
                isUnknownMcc ->
                    PATTERN_UNKNOWN_MCC to SCORE_UNKNOWN_MCC
                isEmptyNameWithMccMnc ->
                    PATTERN_EMPTY_OPERATOR_NAME to SCORE_EMPTY_OPERATOR_NAME
                else ->
                    PATTERN_VALID to SCORE_CLEAN
            }

            // Confidence is intentionally NOT affected by Line1/IMSI
            // null: those accessors are permission-gated and null is
            // the expected answer on consumer contexts even on real
            // devices. Degrading confidence on Line1/IMSI null would
            // misreport a real clean device as "uncertain".
            val sourcesReadable = listOf(
                nameAccessorWorked && !operatorName.isNullOrEmpty(),
                mccMncAccessorWorked && !mccMncRaw.isNullOrEmpty(),
            ).count { it }
            val confidence = when {
                sourcesReadable >= 2 -> CONFIDENCE_FULL
                sourcesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val nameDisplay = operatorName ?: "null"
            val mccMncDisplay = mccMncRaw ?: "null"
            val mccDisplay = if (mcc.isEmpty()) "unknown" else mcc
            val mncDisplay = if (mnc.isEmpty()) "unknown" else mnc

            val evidence = listOf(
                Evidence("telephony.network_operator", mccMncDisplay, expected = "<5-6 digit MCC+MNC>"),
                Evidence("telephony.network_operator_name", nameDisplay, expected = "<non-test carrier>"),
                // Single-source gap: TelephonyField has no SIM_OPERATOR variant,
                // so SIM rows mirror network rows. Same value, distinct rows
                // for schema parity with the 4-row spec.
                Evidence("telephony.sim_operator", mccMncDisplay, expected = "<5-6 digit MCC+MNC>"),
                Evidence("telephony.sim_operator_name", nameDisplay, expected = "<non-test carrier>"),
                Evidence("telephony.mcc", mccDisplay, expected = "<3-digit ITU code, not 000/001>"),
                Evidence("telephony.mnc", mncDisplay, expected = "<2-3 digit operator code>"),
                Evidence(
                    key = "telephony.is_known_emulator_carrier",
                    value = (
                        isEmuName || isAvdCanonical || isSyntheticMcc ||
                            isEmuPhone || isEmuImsi
                        ).toString(),
                    expected = "false",
                ),
                Evidence("telephony.pattern", pattern, expected = PATTERN_VALID),
                // Power-13 Gap #6 — Line1 + IMSI evidence rows under
                // probe-scoped `carrier_mccmnc.*` namespace
                // (cross-cutting #1 convention). Real-PII values are
                // redacted to 4-char prefix + length unless they
                // match a known emulator signature (the dispositive
                // value IS the diagnostic).
                Evidence(
                    key = "carrier_mccmnc.line1_number",
                    value = redactPiiUnlessEmulator(line1Number, isEmuPhone),
                    expected = "<not in AOSP emulator block>",
                ),
                Evidence(
                    key = "carrier_mccmnc.emulator_phone_match",
                    value = isEmuPhone.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "carrier_mccmnc.subscriber_id",
                    value = redactPiiUnlessEmulator(subscriberId, isEmuImsi),
                    expected = "<not AOSP 310260000000000>",
                ),
                Evidence(
                    key = "carrier_mccmnc.emulator_imsi_match",
                    value = isEmuImsi.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "carrier_mccmnc.operator_name_match",
                    value = isEmuName.toString(),
                    expected = "false",
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
                "CarrierMccMncProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

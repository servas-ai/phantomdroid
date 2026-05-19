// inventory.yml rank 12, mitigation_layer L2
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
 * Probe #12 — identity.imei_serial.
 *
 * Reads `TelephonyManager.imei` and the device serial (via either
 * `TelephonyManager.serial` or `ro.serialno`), then runs three validity
 * checks against each value:
 *   1. **Known-emulator list** — exact match against documented AVD /
 *      Genymotion / common-test IMEIs. Matches the L2 fingerprint surface.
 *   2. **Luhn checksum** — every real GSMA-issued IMEI passes Luhn-15.
 *      Failure means the IMEI was hand-coded or stripped to fake digits.
 *   3. **Default-pattern detection on the serial** — `unknown` (Android 8+
 *      privacy default), `EMULATOR*` prefix, all-zero / all-`f` / sequential
 *      hex patterns, or the literal "0123456789ABCDEF" stock serial.
 *
 * Scoring (max wins across rules; never summed):
 *   1.00  IMEI in `KNOWN_EMULATOR_IMEIS`
 *   1.00  Serial matches an emulator pattern (`EMULATOR*`, all-zero,
 *         "0123456789ABCDEF", etc.)
 *   0.90  IMEI is 15 digits but Luhn checksum fails (hand-coded forgery)
 *   0.85  IMEI is non-null but not 15 ASCII digits (malformed)
 *   0.70  Serial == "unknown" AND IMEI is null (both fingerprints stripped —
 *         strong tell on a device that should have at least one)
 *   0.50  IMEI is null/empty AND TelephonyManager accessor worked (Android
 *         10+ privacy default — mostly benign)
 *   0.00  Both readable and pass validity, or only the serial is observable
 *         and looks real
 *
 * Confidence: 0.95 when either accessor (TelephonyManager OR `ro.serialno`)
 * returned a non-null value; 0.50 when neither did.
 *
 * **PII note** — IMEI and serial ARE personally identifiable. This probe
 * records them in evidence (it's a lab/research probe, not a production
 * client). The probe MUST NOT log to stdout / file / network; only the
 * structured `Evidence` rows carry the values, and downstream consumers
 * choose redaction policy.
 *
 * Reference: shared/probes/inventory.yml rank 12 (mitigation_layer L2).
 */
class ImeiSerialProbe : Probe {
    override val id = "identity.imei_serial"
    override val rank = 12
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        const val PROP_RO_SERIALNO = "ro.serialno"

        /**
         * IMEIs documented as factory defaults on emulator / lab images.
         * Exact-equality only; partial-substring matches are too false-positive
         * prone given the small character alphabet.
         */
        val KNOWN_EMULATOR_IMEIS: Set<String> = setOf(
            "000000000000000",
            "004999010640000",
            "352099001761481",
            "353490069873319",
            "355173085192918",
        )

        const val SERIAL_ANDROID8_DEFAULT = "unknown"
        const val SERIAL_STOCK_PLACEHOLDER = "0123456789ABCDEF"

        const val SERIAL_PATTERN_EMULATOR = "emulator"
        const val SERIAL_PATTERN_DEFAULT = "default"
        const val SERIAL_PATTERN_UNKNOWN = "unknown"
        const val SERIAL_PATTERN_VALID = "valid"

        const val SCORE_KNOWN_EMULATOR_IMEI = 1.0
        const val SCORE_EMULATOR_SERIAL = 1.0
        const val SCORE_LUHN_FAIL = 0.9
        const val SCORE_MALFORMED_IMEI = 0.85
        const val SCORE_BOTH_STRIPPED = 0.7
        const val SCORE_IMEI_NULL_BENIGN = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.5

        const val METHOD =
            "Read TelephonyManager.imei + ro.serialno, validate against " +
                "known emulator IMEIs, Luhn checksum, and default-serial patterns"

        private val DIGITS_15_REGEX = Regex("^\\d{15}$")
        private val EMULATOR_SERIAL_PREFIX_REGEX = Regex("(?i)^emulator")
        private val ALL_ZERO_OR_F_REGEX = Regex("^(0+|[fF]+)$")

        /**
         * Luhn (mod-10) checksum over a 15-digit IMEI. Returns false for any
         * input that isn't exactly 15 ASCII digits. The Luhn double-and-sum
         * starts from the rightmost digit (check digit), so position 0 in the
         * reversed string is the check digit and is NOT doubled.
         */
        internal fun luhn15(s: String): Boolean {
            if (!DIGITS_15_REGEX.matches(s)) return false
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

        internal fun classifySerial(serial: String?): String = when {
            serial.isNullOrEmpty() -> SERIAL_PATTERN_UNKNOWN
            serial.equals(SERIAL_ANDROID8_DEFAULT, ignoreCase = true) ->
                SERIAL_PATTERN_UNKNOWN
            EMULATOR_SERIAL_PREFIX_REGEX.containsMatchIn(serial) -> SERIAL_PATTERN_EMULATOR
            serial == SERIAL_STOCK_PLACEHOLDER -> SERIAL_PATTERN_DEFAULT
            ALL_ZERO_OR_F_REGEX.matches(serial) -> SERIAL_PATTERN_DEFAULT
            else -> SERIAL_PATTERN_VALID
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var imeiAccessorWorked = false
            val imei: String? = try {
                val v = ctx.queryTelephonyManager(TelephonyField.IMEI)
                imeiAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            var tmSerialAccessorWorked = false
            val tmSerial: String? = try {
                val v = ctx.queryTelephonyManager(TelephonyField.SERIAL)
                tmSerialAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            var roSerialAccessorWorked = false
            val roSerial: String? = try {
                val v = ctx.getSystemProperty(PROP_RO_SERIALNO)
                roSerialAccessorWorked = true
                v
            } catch (_: Throwable) {
                null
            }

            val anyAccessorObserved = imeiAccessorWorked ||
                tmSerialAccessorWorked || roSerialAccessorWorked

            // Prefer the TelephonyManager-reported serial; fall back to
            // ro.serialno when the platform stripped TM access (Android 10+).
            val effectiveSerial = tmSerial?.takeIf { it.isNotEmpty() } ?: roSerial

            val imeiIsNullOrEmpty = imei.isNullOrEmpty()
            val imeiKnownEmulator = !imeiIsNullOrEmpty && imei in KNOWN_EMULATOR_IMEIS
            val imeiLuhnValid = !imeiIsNullOrEmpty && luhn15(imei!!)
            val imeiMalformed = !imeiIsNullOrEmpty && !DIGITS_15_REGEX.matches(imei!!)

            val serialPattern = classifySerial(effectiveSerial)
            val serialIsEmulator = serialPattern == SERIAL_PATTERN_EMULATOR ||
                serialPattern == SERIAL_PATTERN_DEFAULT
            val serialIsUnknown = serialPattern == SERIAL_PATTERN_UNKNOWN

            val candidates = buildList {
                if (imeiKnownEmulator) add(SCORE_KNOWN_EMULATOR_IMEI)
                if (serialIsEmulator) add(SCORE_EMULATOR_SERIAL)
                if (!imeiIsNullOrEmpty && imeiMalformed) add(SCORE_MALFORMED_IMEI)
                if (!imeiIsNullOrEmpty && !imeiMalformed && !imeiLuhnValid) add(SCORE_LUHN_FAIL)
                if (imeiIsNullOrEmpty && serialIsUnknown && anyAccessorObserved) {
                    add(SCORE_BOTH_STRIPPED)
                }
                if (imeiIsNullOrEmpty && imeiAccessorWorked && !serialIsUnknown) {
                    add(SCORE_IMEI_NULL_BENIGN)
                }
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            val confidence = if (anyAccessorObserved) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val evidence = listOf(
                Evidence("imei", imei ?: "null", expected = "<15-digit Luhn-valid>"),
                Evidence("imei.length", (imei?.length ?: 0), expected = "15"),
                Evidence(
                    key = "imei.luhn_valid",
                    value = (imeiLuhnValid).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "imei.known_emulator",
                    value = imeiKnownEmulator.toString(),
                    expected = "false",
                ),
                Evidence(PROP_RO_SERIALNO, roSerial ?: "null", expected = "<unique non-default>"),
                Evidence("build.serial", tmSerial ?: "null", expected = "<unique non-default>"),
                Evidence("serial.pattern", serialPattern, expected = SERIAL_PATTERN_VALID),
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
                "ImeiSerialProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

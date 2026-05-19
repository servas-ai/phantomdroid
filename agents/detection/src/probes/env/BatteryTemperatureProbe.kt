// inventory.yml rank 34, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #34 — env.battery_temperature.
 *
 * Reads the device's battery temperature in tenths of a degree Celsius (so
 * `250` = 25.0°C) from two surfaces:
 *   1. `ACTION_BATTERY_CHANGED` extra `EXTRA_TEMPERATURE` (via constructor-
 *      injected supplier — `ProbeContext` exposes no BatteryManager view).
 *   2. `/sys/class/power_supply/battery/temp` (via `ctx.readFile`) — kernel
 *      sysfs fallback. Used as the second sample for static-vs-dynamic
 *      cross-check and as the primary signal when the framework accessor
 *      is absent.
 *
 * **BatteryManager accessor gap.** Same as rank 33 ([BatteryLevelProbe]):
 * `ProbeContext` exposes no `queryBatteryManager()`. Constructor-injected
 * supplier; production no-arg ctor returns null and the probe falls back to
 * sysfs-only with degraded confidence.
 *
 * Real-device baselines:
 *   - Idle Pixel: 280-320 (28-32°C)
 *   - Gaming load: 400-450 (40-45°C)
 *   - Off-charger discharge: 250-300 (25-30°C)
 * Real temperatures fluctuate at the 1-2°C granularity over minutes; the
 * AVD default is exactly 250 (25.0°C) and never drifts.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  temp outside [50, 600] inclusive (under 5°C or over 60°C —
 *         implausible for a running device; kernel would have shut down)
 *   1.00  temp == 250 exactly (AVD default — never drifts on real device)
 *   0.95  temp == 0 (uninitialized stub)
 *   0.85  temp rounds to a 100-tenths multiple {300, 350, 400} excluding
 *         the AVD-default 250 and zero (already handled above) — round
 *         stub values
 *   0.85  Two reads (framework + sysfs) return the identical non-default
 *         value AND it's not in the realistic mid-range (suggests static
 *         emulator value)
 *   0.00  temp in plausible 100-500 range, non-round, single-read or two
 *         samples differ
 *
 * Confidence:
 *   0.95  Framework supplier returned (primary surface available)
 *   0.60  Only sysfs returned (degraded fallback)
 *   0.50  Neither surface returned
 *
 * Reference: shared/probes/inventory.yml rank 34 (mitigation_layer L1).
 */
class BatteryTemperatureProbe(
    private val temperatureTenthsSupplier: () -> Int? = { null },
) : Probe {
    override val id = "env.battery_temperature"
    override val rank = 34
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PATH_SYSFS_TEMP = "/sys/class/power_supply/battery/temp"

        const val TEMP_AVD_DEFAULT = 250
        const val TEMP_ZERO = 0
        const val TEMP_MIN_PLAUSIBLE = 50          // 5.0°C — fridge-bench bound
        const val TEMP_MAX_PLAUSIBLE = 600         // 60.0°C — thermal-shutdown bound

        const val TEMP_PLAUSIBLE_RANGE_LOW = 100   // 10.0°C — normal-use bound
        const val TEMP_PLAUSIBLE_RANGE_HIGH = 500  // 50.0°C — normal-use bound

        const val PATTERN_OUT_OF_RANGE = "out_of_range"
        const val PATTERN_AVD_DEFAULT = "avd_default"
        const val PATTERN_ZERO = "zero"
        const val PATTERN_ROUND_100 = "round_100"
        const val PATTERN_STATIC_NON_DEFAULT = "static_non_default"
        const val PATTERN_TOO_COLD = "too_cold"
        const val PATTERN_TOO_HOT = "too_hot"
        const val PATTERN_PLAUSIBLE = "plausible"
        const val PATTERN_NULL = "null"

        const val SCORE_OUT_OF_RANGE = 1.0
        const val SCORE_AVD_DEFAULT = 1.0
        const val SCORE_ZERO = 0.95
        const val SCORE_ROUND_100 = 0.85
        const val SCORE_STATIC_NON_DEFAULT = 0.85
        const val SCORE_PLAUSIBLE = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read battery temperature via BatteryManager/ACTION_BATTERY_CHANGED " +
                "EXTRA_TEMPERATURE; detect AVD 25.0°C default, round-100 stub " +
                "values, and out-of-plausible-range values"

        /** Returns the integer tenths-°C parsed from a sysfs read (trim + parse). */
        internal fun parseSysfsTemp(raw: String?): Int? {
            val trimmed = raw?.trim() ?: return null
            return trimmed.toIntOrNull()
        }

        /** True iff the value is a multiple of 100 tenths-°C (10, 20, 30, ...°C). */
        internal fun isRoundHundred(tenths: Int): Boolean = tenths % 100 == 0

        /** Returns the celsius display string (e.g. `28.7`) from tenths-°C. */
        internal fun celsiusOf(tenths: Int): String {
            val sign = if (tenths < 0) "-" else ""
            val abs = if (tenths < 0) -tenths else tenths
            val whole = abs / 10
            val frac = abs % 10
            return "$sign$whole.$frac"
        }

        /** True iff the value is inside the implausible-running-device range. */
        internal fun isOutOfRange(tenths: Int): Boolean =
            tenths < TEMP_MIN_PLAUSIBLE || tenths > TEMP_MAX_PLAUSIBLE

        internal fun isPlausibleMidRange(tenths: Int): Boolean =
            tenths in TEMP_PLAUSIBLE_RANGE_LOW..TEMP_PLAUSIBLE_RANGE_HIGH
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val frameworkTemp: Int? = try {
                temperatureTenthsSupplier()
            } catch (_: Throwable) {
                null
            }

            val sysfsRaw: String? = try {
                ctx.readFile(PATH_SYSFS_TEMP, maxBytes = 16)
            } catch (_: Throwable) {
                null
            }
            val sysfsTemp: Int? = parseSysfsTemp(sysfsRaw)

            // Primary level: framework supplier; fall back to sysfs.
            val primaryTemp = frameworkTemp ?: sysfsTemp

            val (pattern, score) = when {
                primaryTemp == null ->
                    PATTERN_NULL to SCORE_PLAUSIBLE
                primaryTemp == TEMP_AVD_DEFAULT ->
                    PATTERN_AVD_DEFAULT to SCORE_AVD_DEFAULT
                primaryTemp == TEMP_ZERO ->
                    PATTERN_ZERO to SCORE_ZERO
                primaryTemp < TEMP_MIN_PLAUSIBLE ->
                    PATTERN_TOO_COLD to SCORE_OUT_OF_RANGE
                primaryTemp > TEMP_MAX_PLAUSIBLE ->
                    PATTERN_TOO_HOT to SCORE_OUT_OF_RANGE
                isRoundHundred(primaryTemp) ->
                    PATTERN_ROUND_100 to SCORE_ROUND_100
                frameworkTemp != null && sysfsTemp != null &&
                    frameworkTemp == sysfsTemp && !isPlausibleMidRange(primaryTemp) ->
                    PATTERN_STATIC_NON_DEFAULT to SCORE_STATIC_NON_DEFAULT
                else ->
                    PATTERN_PLAUSIBLE to SCORE_PLAUSIBLE
            }

            val confidence = when {
                frameworkTemp != null -> CONFIDENCE_FULL
                sysfsTemp != null -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val tempDisplay = primaryTemp?.toString() ?: "<unreadable>"
            val celsiusDisplay = primaryTemp?.let { celsiusOf(it) } ?: "<unreadable>"
            val plausible = primaryTemp != null && !isOutOfRange(primaryTemp)
            val isRound = primaryTemp != null && isRoundHundred(primaryTemp)

            val evidence = listOf(
                Evidence(
                    key = "battery.temperature_tenths_c",
                    value = tempDisplay,
                    expected = "100-500 not multiple of 100",
                ),
                Evidence(
                    key = "battery.temperature_celsius",
                    value = celsiusDisplay,
                    expected = "10.0-50.0",
                ),
                Evidence(
                    key = "battery.temperature_plausible",
                    value = plausible.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "battery.temperature_is_round_100",
                    value = isRound.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "battery.temperature_pattern",
                    value = pattern,
                    expected = PATTERN_PLAUSIBLE,
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
                "BatteryTemperatureProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

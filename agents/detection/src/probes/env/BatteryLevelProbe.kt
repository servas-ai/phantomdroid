// inventory.yml rank 33, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #33 — env.battery_level.
 *
 * Reads the device's reported battery capacity (0-100%) and charging state
 * to detect emulator defaults that real-device sensors will never report.
 *
 * Three signal surfaces are inspected (max wins; first-match cascade):
 *   1. `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` — primary
 *      framework API.
 *   2. `ACTION_BATTERY_CHANGED` sticky-broadcast `level` + `scale` (computes
 *      `level / scale * 100`).
 *   3. `/sys/class/power_supply/battery/capacity` — kernel filesystem oracle
 *      (via `ctx.readFile`); used as a static-vs-dynamic cross-check and
 *      as a fallback when the framework accessors are unavailable.
 *
 * **BatteryManager accessor gap.** ProbeContext exposes no
 * `queryBatteryManager()` view. Constructor-injected suppliers; production
 * no-arg ctor returns null for all three accessors and the probe falls back
 * to the sysfs path. Same pattern as ranks 31 ([BluetoothMacProbe]) and 32
 * ([WifiSsidBssidProbe]).
 *
 * **Static-sample detection.** The probe samples the capacity supplier twice
 * (separated by ~10ms; same probe invocation, so the budget is respected).
 * Two identical reads is not by itself proof — real device drains slowly —
 * but together with extreme values (100, 50, 0) it strengthens the signal.
 *
 * Charging state encoding (from `BatteryManager.EXTRA_STATUS`):
 *   1 = UNKNOWN, 2 = CHARGING, 3 = DISCHARGING, 4 = NOT_CHARGING, 5 = FULL
 * The supplier is contract-free; the probe accepts any string matching the
 * uppercase names listed and treats everything else (including `null`) as
 * "unknown".
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Level outside 0-100 inclusive (impossible)
 *   1.00  Level == 100 AND chargingState == `NOT_CHARGING` (impossible:
 *         a fully-charged real device reports `FULL`, never `NOT_CHARGING`)
 *   0.95  Level == 0 (device is still running — kernel would have shut
 *         down — only possible in emulator)
 *   0.85  Level == 50 exactly (AVD default; rare but legitimate on a real
 *         draining device — non-zero false-positive risk)
 *   0.85  Two samples report the identical level AND the value is 100
 *         (emulator-static-max)
 *   0.50  Level == 100 AND chargingState == `CHARGING` or `FULL` (could be
 *         a real phone topped off — low confidence)
 *   0.00  Level in 1-99 with realistic charging-state consistency
 *
 * Confidence:
 *   0.95  Capacity supplier returned (primary surface available)
 *   0.60  Only one of {sticky-broadcast, sysfs} returned (degraded)
 *   0.50  No primary signal — neither supplier returned, sysfs unreadable
 *
 * Reference: shared/probes/inventory.yml rank 33 (mitigation_layer L1).
 */
class BatteryLevelProbe(
    private val capacitySupplier: () -> Int? = { null },
    private val stickyLevelScaleSupplier: () -> Pair<Int, Int>? = { null },
    private val chargingStateSupplier: () -> String? = { null },
) : Probe {
    override val id = "env.battery_level"
    override val rank = 33
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PATH_SYSFS_CAPACITY = "/sys/class/power_supply/battery/capacity"

        const val LEVEL_AVD_DEFAULT = 50
        const val LEVEL_FULL = 100
        const val LEVEL_EMPTY = 0

        const val CHARGE_STATE_CHARGING = "CHARGING"
        const val CHARGE_STATE_DISCHARGING = "DISCHARGING"
        const val CHARGE_STATE_NOT_CHARGING = "NOT_CHARGING"
        const val CHARGE_STATE_FULL = "FULL"
        const val CHARGE_STATE_UNKNOWN = "UNKNOWN"

        val VALID_CHARGING_STATES = setOf(
            CHARGE_STATE_CHARGING,
            CHARGE_STATE_DISCHARGING,
            CHARGE_STATE_NOT_CHARGING,
            CHARGE_STATE_FULL,
            CHARGE_STATE_UNKNOWN,
        )

        const val PATTERN_OUT_OF_RANGE = "out_of_range"
        const val PATTERN_FULL_NOT_CHARGING = "full_not_charging"
        const val PATTERN_ZERO_RUNNING = "zero_running"
        const val PATTERN_AVD_DEFAULT_50 = "avd_default_50"
        const val PATTERN_STATIC_MAX = "static_max"
        const val PATTERN_FULL_BENIGN = "full_benign"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_OUT_OF_RANGE = 1.0
        const val SCORE_FULL_NOT_CHARGING = 1.0
        const val SCORE_ZERO_RUNNING = 0.95
        const val SCORE_AVD_DEFAULT_50 = 0.85
        const val SCORE_STATIC_MAX = 0.85
        const val SCORE_FULL_BENIGN = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read BatteryManager BATTERY_PROPERTY_CAPACITY + ACTION_BATTERY_CHANGED " +
                "level/scale + charging state; detect 100%+NOT_CHARGING contradictions " +
                "and AVD-default 50% values"

        /** Returns the canonical UPPERCASE charging state, or null if unmappable. */
        internal fun normalizeChargingState(raw: String?): String? {
            val upper = raw?.trim()?.uppercase() ?: return null
            return if (upper in VALID_CHARGING_STATES) upper else null
        }

        /** Returns the integer 0-100 capacity, or null if outside range. */
        internal fun validCapacityInRange(value: Int?): Int? =
            if (value != null && value in 0..100) value else null

        /** Parses `"73\n"` or `"73"` from a sysfs read; returns null on garbage. */
        internal fun parseSysfsCapacity(raw: String?): Int? {
            val trimmed = raw?.trim() ?: return null
            return trimmed.toIntOrNull()
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val capacityRaw1: Int? = try {
                capacitySupplier()
            } catch (_: Throwable) {
                null
            }
            val capacityRaw2: Int? = try {
                capacitySupplier()
            } catch (_: Throwable) {
                null
            }

            val stickyPair: Pair<Int, Int>? = try {
                stickyLevelScaleSupplier()
            } catch (_: Throwable) {
                null
            }
            val stickyPercent: Int? = stickyPair?.let { (level, scale) ->
                if (scale > 0) ((level.toLong() * 100) / scale).toInt() else null
            }

            val sysfsRaw: String? = try {
                ctx.readFile(PATH_SYSFS_CAPACITY, maxBytes = 16)
            } catch (_: Throwable) {
                null
            }
            val sysfsPercent: Int? = parseSysfsCapacity(sysfsRaw)

            // Primary level: capacity supplier; fall back to sticky-broadcast,
            // then sysfs. Any single reading is enough to start scoring.
            val primaryLevel = capacityRaw1 ?: stickyPercent ?: sysfsPercent
            val outOfRange = primaryLevel != null && (primaryLevel < 0 || primaryLevel > 100)

            val chargingStateRaw: String? = try {
                chargingStateSupplier()
            } catch (_: Throwable) {
                null
            }
            val chargingState = normalizeChargingState(chargingStateRaw)
                ?: CHARGE_STATE_UNKNOWN

            // Two reads at 100 within the same probe invocation. A real
            // device on a charger legitimately reads 100/100, so gate the
            // rule on "not actively charging" — CHARGING/FULL states
            // explain a stable 100% reading without emulator suspicion.
            val isStaticMax = capacityRaw1 != null && capacityRaw2 != null &&
                capacityRaw1 == capacityRaw2 && capacityRaw1 == LEVEL_FULL &&
                chargingState != CHARGE_STATE_CHARGING &&
                chargingState != CHARGE_STATE_FULL

            val (pattern, score) = when {
                outOfRange ->
                    PATTERN_OUT_OF_RANGE to SCORE_OUT_OF_RANGE
                primaryLevel == LEVEL_FULL && chargingState == CHARGE_STATE_NOT_CHARGING ->
                    PATTERN_FULL_NOT_CHARGING to SCORE_FULL_NOT_CHARGING
                primaryLevel == LEVEL_EMPTY ->
                    PATTERN_ZERO_RUNNING to SCORE_ZERO_RUNNING
                primaryLevel == LEVEL_AVD_DEFAULT ->
                    PATTERN_AVD_DEFAULT_50 to SCORE_AVD_DEFAULT_50
                isStaticMax ->
                    PATTERN_STATIC_MAX to SCORE_STATIC_MAX
                primaryLevel == LEVEL_FULL &&
                    (chargingState == CHARGE_STATE_CHARGING ||
                        chargingState == CHARGE_STATE_FULL) ->
                    PATTERN_FULL_BENIGN to SCORE_FULL_BENIGN
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val capacityReadable = capacityRaw1 != null
            val anySecondarySurface = stickyPercent != null || sysfsPercent != null
            val confidence = when {
                capacityReadable -> CONFIDENCE_FULL
                anySecondarySurface -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val levelDisplay = primaryLevel?.toString() ?: "<unreadable>"
            val scaleDisplay = stickyPair?.second?.toString() ?: "<unreadable>"
            val percentDisplay = (stickyPercent ?: capacityRaw1 ?: sysfsPercent)?.toString()
                ?: "<unreadable>"

            val evidence = listOf(
                Evidence("battery.level", levelDisplay, expected = "1-99 normally"),
                Evidence("battery.scale", scaleDisplay, expected = "100"),
                Evidence(
                    key = "battery.percent_computed",
                    value = percentDisplay,
                    expected = "matches battery.level",
                ),
                Evidence(
                    key = "battery.charging_state",
                    value = chargingState,
                    expected = "CHARGING|DISCHARGING|FULL|NOT_CHARGING|null",
                ),
                Evidence(
                    key = "battery.is_static_max",
                    value = isStaticMax.toString(),
                    expected = "false",
                ),
                Evidence("battery.pattern", pattern, expected = PATTERN_CLEAN),
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
                "BatteryLevelProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

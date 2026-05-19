// inventory.yml rank 35, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #35 — env.charging_state.
 *
 * Reads `BatteryManager.BATTERY_PROPERTY_STATUS` and the `EXTRA_PLUGGED`
 * field from `ACTION_BATTERY_CHANGED` (plus the current battery level for
 * the `FULL`-with-low-percent contradiction). Looks for structural
 * impossibilities a real device can never report:
 *   • CHARGING reported with plug type `unplugged`
 *   • FULL reported with battery level < 100
 *   • Plug-type enum outside the documented {0, 1, 2, 4, 8} set
 *
 * **BatteryManager accessor gap.** Same as rank 33/34: `ProbeContext`
 * exposes no `queryBatteryManager()`. Three constructor-injected
 * suppliers; production no-arg ctor returns null on all three and the
 * probe drops to `CONFIDENCE_DEGRADED` (0.30 per spec) — no scoring rule
 * can fire without at least one surface.
 *
 * Status encoding (`BatteryManager.EXTRA_STATUS`):
 *   1 = UNKNOWN, 2 = CHARGING, 3 = DISCHARGING, 4 = NOT_CHARGING, 5 = FULL
 *
 * Plug-type encoding (`BatteryManager.EXTRA_PLUGGED`):
 *   0 = unplugged, 1 = AC, 2 = USB, 4 = WIRELESS, 8 = DOCK (API 26+)
 *
 * The suppliers are typed `Int?` so wrappers can pass the raw constants
 * without translating to strings.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  status == CHARGING AND plugged == 0 (unplugged but charging —
 *         structural contradiction; impossible on a real device)
 *   0.95  status == FULL AND level < 100 (a real FULL report requires
 *         level == 100; if level is missing the rule does not fire)
 *   0.85  plugged value outside {0, 1, 2, 4, 8} (invalid enum)
 *   0.70  status == NOT_CHARGING AND plugged != 0 AND level < 100
 *         (could be thermal throttle / battery-saver — suspicious in
 *         isolation but legitimate transient on real devices, so capped)
 *   0.00  Consistent: discharging+unplugged, charging+plugged, or any
 *         configuration not matching the above.
 *
 * Confidence:
 *   0.95  Both status AND plugged suppliers returned
 *   0.60  Exactly one of {status, plugged} returned
 *   0.30  Neither returned (degraded — emulator-class observation gap)
 *
 * Reference: shared/probes/inventory.yml rank 35 (mitigation_layer L1).
 */
class ChargingStateProbe(
    private val statusSupplier: () -> Int? = { null },
    private val pluggedSupplier: () -> Int? = { null },
    private val levelSupplier: () -> Int? = { null },
) : Probe {
    override val id = "env.charging_state"
    override val rank = 35
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val STATUS_UNKNOWN = 1
        const val STATUS_CHARGING = 2
        const val STATUS_DISCHARGING = 3
        const val STATUS_NOT_CHARGING = 4
        const val STATUS_FULL = 5

        const val PLUGGED_UNPLUGGED = 0
        const val PLUGGED_AC = 1
        const val PLUGGED_USB = 2
        const val PLUGGED_WIRELESS = 4
        const val PLUGGED_DOCK = 8

        val VALID_PLUGGED_VALUES = setOf(
            PLUGGED_UNPLUGGED, PLUGGED_AC, PLUGGED_USB, PLUGGED_WIRELESS, PLUGGED_DOCK,
        )

        const val STATUS_NAME_UNKNOWN = "UNKNOWN"
        const val STATUS_NAME_CHARGING = "CHARGING"
        const val STATUS_NAME_DISCHARGING = "DISCHARGING"
        const val STATUS_NAME_NOT_CHARGING = "NOT_CHARGING"
        const val STATUS_NAME_FULL = "FULL"
        const val STATUS_NAME_NULL = "null"
        const val STATUS_NAME_INVALID = "invalid"

        const val PLUGGED_NAME_UNPLUGGED = "unplugged"
        const val PLUGGED_NAME_AC = "ac"
        const val PLUGGED_NAME_USB = "usb"
        const val PLUGGED_NAME_WIRELESS = "wireless"
        const val PLUGGED_NAME_DOCK = "dock"
        const val PLUGGED_NAME_INVALID = "invalid"
        const val PLUGGED_NAME_NULL = "null"

        const val PATTERN_CHARGING_UNPLUGGED = "charging_unplugged"
        const val PATTERN_FULL_BELOW_100 = "full_below_100"
        const val PATTERN_INVALID_PLUGGED = "invalid_plugged"
        const val PATTERN_NOT_CHARGING_PLUGGED = "not_charging_plugged"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_CHARGING_UNPLUGGED = 1.0
        const val SCORE_FULL_BELOW_100 = 0.95
        const val SCORE_INVALID_PLUGGED = 0.85
        const val SCORE_NOT_CHARGING_PLUGGED = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read BatteryManager BATTERY_PROPERTY_STATUS + EXTRA_PLUGGED; detect " +
                "status-vs-plug contradictions (CHARGING+unplugged, FULL+<100%) and " +
                "invalid plug enum values"

        internal fun statusName(status: Int?): String = when (status) {
            null -> STATUS_NAME_NULL
            STATUS_UNKNOWN -> STATUS_NAME_UNKNOWN
            STATUS_CHARGING -> STATUS_NAME_CHARGING
            STATUS_DISCHARGING -> STATUS_NAME_DISCHARGING
            STATUS_NOT_CHARGING -> STATUS_NAME_NOT_CHARGING
            STATUS_FULL -> STATUS_NAME_FULL
            else -> STATUS_NAME_INVALID
        }

        internal fun pluggedName(plugged: Int?): String = when (plugged) {
            null -> PLUGGED_NAME_NULL
            PLUGGED_UNPLUGGED -> PLUGGED_NAME_UNPLUGGED
            PLUGGED_AC -> PLUGGED_NAME_AC
            PLUGGED_USB -> PLUGGED_NAME_USB
            PLUGGED_WIRELESS -> PLUGGED_NAME_WIRELESS
            PLUGGED_DOCK -> PLUGGED_NAME_DOCK
            else -> PLUGGED_NAME_INVALID
        }

        internal fun isConsistent(status: Int?, plugged: Int?): Boolean {
            if (status == null || plugged == null) return true
            val plugged0 = plugged == PLUGGED_UNPLUGGED
            return when (status) {
                STATUS_CHARGING -> !plugged0
                STATUS_DISCHARGING -> plugged0
                else -> true
            }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val status: Int? = try {
                statusSupplier()
            } catch (_: Throwable) {
                null
            }
            val plugged: Int? = try {
                pluggedSupplier()
            } catch (_: Throwable) {
                null
            }
            val level: Int? = try {
                levelSupplier()
            } catch (_: Throwable) {
                null
            }

            val pluggedInvalid = plugged != null && plugged !in VALID_PLUGGED_VALUES

            val (pattern, score) = when {
                status == STATUS_CHARGING && plugged == PLUGGED_UNPLUGGED ->
                    PATTERN_CHARGING_UNPLUGGED to SCORE_CHARGING_UNPLUGGED
                status == STATUS_FULL && level != null && level < 100 ->
                    PATTERN_FULL_BELOW_100 to SCORE_FULL_BELOW_100
                pluggedInvalid ->
                    PATTERN_INVALID_PLUGGED to SCORE_INVALID_PLUGGED
                status == STATUS_NOT_CHARGING && plugged != null &&
                    plugged != PLUGGED_UNPLUGGED && level != null && level < 100 ->
                    PATTERN_NOT_CHARGING_PLUGGED to SCORE_NOT_CHARGING_PLUGGED
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val surfacesReadable = listOf(status != null, plugged != null).count { it }
            val confidence = when {
                surfacesReadable >= 2 -> CONFIDENCE_FULL
                surfacesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val statusDisplay = status?.toString() ?: "<unreadable>"
            val pluggedDisplay = plugged?.toString() ?: "<unreadable>"
            val consistent = isConsistent(status, plugged)

            val evidence = listOf(
                Evidence(
                    key = "battery.status",
                    value = statusDisplay,
                    expected = "CHARGING when plugged, DISCHARGING when unplugged",
                ),
                Evidence(
                    key = "battery.status_name",
                    value = statusName(status),
                    expected = "CHARGING|DISCHARGING|FULL based on plug state",
                ),
                Evidence(
                    key = "battery.plugged",
                    value = pluggedDisplay,
                    expected = "0|1|2|4|8",
                ),
                Evidence(
                    key = "battery.plugged_name",
                    value = pluggedName(plugged),
                    expected = "any valid",
                ),
                Evidence(
                    key = "battery.status_plugged_consistent",
                    value = consistent.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "battery.charging_state_pattern",
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
                "ChargingStateProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

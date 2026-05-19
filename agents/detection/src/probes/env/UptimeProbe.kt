// inventory.yml rank 47, mitigation_layer manual
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Probe #47 — env.uptime.
 *
 * Reads `/proc/uptime` (boot-time seconds + idle seconds) and cross-checks
 * against constructor-injected `SystemClock.uptimeMillis()` /
 * `SystemClock.elapsedRealtime()` accessors and the wall-clock timestamp.
 * Looks for emulator stub patterns: zero uptime, implausibly long uptime,
 * future boot time, round-minute exact multiples, and frozen-clock state.
 *
 * **`SystemClock` accessor gap.** `ProbeContext` exposes no direct
 * SystemClock view. Three constructor-injected suppliers handle the
 * uptime/elapsedRealtime/wallClock signals; production no-arg ctor
 * returns nulls and the probe falls back to `/proc/uptime` only with
 * degraded confidence. Same gap-handling pattern as ranks
 * 31/32/33/40/42/43/44/45/50.
 *
 * **Frozen-clock detection intentionally OUT of scope.** Two sequential
 * `/proc/uptime` reads from inside a single probe invocation happen
 * microseconds apart — kernel `/proc/uptime` is updated at HZ
 * resolution (100Hz = 10ms, 1000Hz = 1ms), so a real device commonly
 * returns IDENTICAL content for both reads. The "emulator paused / frozen
 * clock" signature requires observing uptime across two probe invocations
 * separated by a meaningful interval (seconds), which is a stateful
 * cross-probe-run concern beyond this single-invocation probe's scope.
 * Adding a `Thread.sleep(seconds)` inside the probe would blow the
 * 150ms budget by 100x. KDoc anchors this gap; future work can pick it
 * up by adding a `lastUptimeSecAtPreviousRun` cached state to
 * `ProbeContext` or by elevating the frozen detection to a cross-probe
 * aggregator.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Uptime > [MAX_PLAUSIBLE_UPTIME_SEC] (10 years — no consumer
 *         device runs this long)
 *   1.00  Computed boot time (now - uptime) lies in the future (clock
 *         contradiction). Defensive sentinel — mathematically unreachable
 *         in practice given cascade order (implausible fires first on
 *         Long overflow); kept for hypothetical wrappers supplying garbage
 *         wallClock or for runtimes outside the current ProbeContext
 *         contract.
 *   0.85  Uptime == 0.0 exactly (stub or uninitialized)
 *   0.85  Uptime is a round-minute multiple AND > 60.0 (60.0/3600.0/
 *         86400.0 — exact stub values; non-multiple plausible uptimes
 *         like 87234.7 are fine)
 *   0.50  Uptime < 60.0 (just-booted; low-confidence signal — legit
 *         after reboot but emulator instances also start fresh)
 *   0.00  Plausible uptime (>1 min, <10 years, non-round)
 *
 * Confidence:
 *   0.95  `/proc/uptime` returned a parseable value
 *   0.50  `/proc/uptime` unreadable AND all suppliers returned null
 *
 * Reference: shared/probes/inventory.yml rank 47 (mitigation_layer manual).
 */
class UptimeProbe(
    private val uptimeMillisSupplier: () -> Long? = { null },
    private val elapsedRealtimeMillisSupplier: () -> Long? = { null },
    private val wallClockMillisSupplier: () -> Long? = { System.currentTimeMillis() },
) : Probe {
    override val id = "env.uptime"
    override val rank = 47
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val PATH_PROC_UPTIME = "/proc/uptime"

        /** 10 years in seconds — no consumer device runs this long. */
        const val MAX_PLAUSIBLE_UPTIME_SEC = 315_360_000.0

        /** 60 seconds — just-booted boundary (short-uptime signal). */
        const val MIN_PLAUSIBLE_UPTIME_SEC = 60.0

        /**
         * Round-minute multiple constants (commonly seen in emulator stubs:
         * 60s, 3600s, 86400s). Real `/proc/uptime` is updated at kernel-jiffy
         * resolution (HZ=100..1000), so exact integer multiples are
         * statistically improbable on a long-running device.
         */
        const val ROUND_INTERVAL_SEC = 60.0

        const val PATTERN_STUB_ZERO = "stub_zero"
        const val PATTERN_IMPLAUSIBLE = "implausible"
        const val PATTERN_FUTURE_BOOT = "future_boot"
        const val PATTERN_ROUND = "round"
        const val PATTERN_SHORT = "short"
        const val PATTERN_PLAUSIBLE = "plausible"

        const val SCORE_STUB_ZERO = 0.85
        const val SCORE_IMPLAUSIBLE = 1.0
        const val SCORE_FUTURE_BOOT = 1.0
        const val SCORE_ROUND = 0.85
        const val SCORE_SHORT = 0.5
        const val SCORE_PLAUSIBLE = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read /proc/uptime + SystemClock.uptimeMillis() + elapsedRealtime; " +
                "detect zero-uptime stubs, implausible >10y values, future-boot-" +
                "time, round-minute-multiples, and short (<60s) uptime patterns"

        /**
         * Parses `/proc/uptime` content into (boot_seconds, idle_seconds)
         * pair, or null on garbage. Format: `"12345.67 8901.23\n"`.
         */
        internal fun parseUptime(raw: String?): Pair<Double, Double>? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return null
            val boot = parts[0].toDoubleOrNull() ?: return null
            val idle = parts[1].toDoubleOrNull() ?: return null
            return boot to idle
        }

        /** True iff [seconds] is an exact non-zero multiple of 60.0. */
        internal fun isRoundMinute(seconds: Double): Boolean {
            if (seconds == 0.0) return false
            return seconds % ROUND_INTERVAL_SEC == 0.0
        }

        /**
         * Computes the boot wall-clock timestamp in milliseconds (now - uptime).
         * Returns null if either input is missing.
         */
        internal fun bootTimeMillis(nowMillis: Long?, uptimeSec: Double?): Long? {
            if (nowMillis == null || uptimeSec == null) return null
            return nowMillis - (uptimeSec * 1000.0).toLong()
        }

        /** Formats a millis timestamp as ISO-8601 UTC. */
        internal fun formatIso8601(millis: Long): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val raw: String? = try {
                ctx.readFile(PATH_PROC_UPTIME, maxBytes = 128)
            } catch (_: Throwable) {
                null
            }
            val parsed = parseUptime(raw)

            val uptimeSec = parsed?.first
            val idleSec = parsed?.second

            val uptimeMillis: Long? = try {
                uptimeMillisSupplier()
            } catch (_: Throwable) {
                null
            }
            val elapsedRealtimeMillis: Long? = try {
                elapsedRealtimeMillisSupplier()
            } catch (_: Throwable) {
                null
            }
            val nowMillis: Long? = try {
                wallClockMillisSupplier()
            } catch (_: Throwable) {
                null
            }

            val bootMillis = bootTimeMillis(nowMillis, uptimeSec)
            val futureBoot = bootMillis != null && nowMillis != null && bootMillis > nowMillis

            val isStubZero = uptimeSec == 0.0
            val isImplausibleHigh = uptimeSec != null && uptimeSec > MAX_PLAUSIBLE_UPTIME_SEC
            val isRound = uptimeSec != null && uptimeSec > 0.0 &&
                uptimeSec > MIN_PLAUSIBLE_UPTIME_SEC && isRoundMinute(uptimeSec)
            val isShort = uptimeSec != null && uptimeSec > 0.0 &&
                uptimeSec < MIN_PLAUSIBLE_UPTIME_SEC

            val (pattern, score) = when {
                isImplausibleHigh ->
                    PATTERN_IMPLAUSIBLE to SCORE_IMPLAUSIBLE
                futureBoot ->
                    PATTERN_FUTURE_BOOT to SCORE_FUTURE_BOOT
                isStubZero ->
                    PATTERN_STUB_ZERO to SCORE_STUB_ZERO
                isRound ->
                    PATTERN_ROUND to SCORE_ROUND
                isShort ->
                    PATTERN_SHORT to SCORE_SHORT
                else ->
                    PATTERN_PLAUSIBLE to SCORE_PLAUSIBLE
            }

            val procReadable = uptimeSec != null
            val anySupplierReadable = uptimeMillis != null || elapsedRealtimeMillis != null
            val confidence = if (procReadable || anySupplierReadable)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            val bootIsoDisplay = bootMillis?.let { formatIso8601(it) } ?: "<unreadable>"

            val evidence = listOf(
                Evidence(
                    key = "uptime.seconds_since_boot",
                    value = uptimeSec?.toString() ?: "<unreadable>",
                    expected = ">60 normally",
                ),
                Evidence(
                    key = "uptime.idle_seconds",
                    value = idleSec?.toString() ?: "<unreadable>",
                    expected = "<= boot seconds",
                ),
                Evidence(
                    key = "uptime.elapsed_realtime_ms",
                    value = elapsedRealtimeMillis?.toString() ?: "<unavailable>",
                    expected = "matches uptime ± 100ms",
                ),
                Evidence(
                    key = "uptime.boot_time_iso8601",
                    value = bootIsoDisplay,
                    expected = "<not in future, not >10y past>",
                ),
                Evidence(
                    key = "uptime.is_round_minute",
                    value = isRound.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "uptime.pattern",
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
                "UptimeProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

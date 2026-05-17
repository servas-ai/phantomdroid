package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import kotlin.math.abs

/**
 * Probe — env.time_spoofing  (A17 N4)
 *
 * Layer: L4 (runtime) + cross (network NTP)
 *
 * Detection strategy — cross-validate four independent time sources, then flag
 * any delta that exceeds the per-pair threshold documented below (freeRASP T15):
 *
 *   Delta pair                            Threshold (ms)  Score if exceeded
 *   ──────────────────────────────────────────────────────────────────────────
 *   bootEpoch_drift     (D1)              ±30 000          0.55  (drift of
 *                                                                 (wall - elapsed)
 *                                                                 vs the session
 *                                                                 anchor — see D1
 *                                                                 notes below)
 *   wall_vs_gps         (D2)              ±10 000          0.75  (GPS carries
 *                                                                 hardware time)
 *   wall_vs_ntp         (D3)              ±10 000          0.80  (NTP is an
 *                                                                 authoritative
 *                                                                 external source)
 *   gps_vs_ntp          (D4)              ± 5 000          0.90  (both external;
 *                                                                 divergence is
 *                                                                 highly suspicious)
 *
 * Thresholds are mutable via `assets/baselines/time-thresholds.json`; the probe
 * reads defaults from the constants below when the asset file is absent.
 *
 * Score is the maximum over all triggered deltas. Confidence is 0.95 when two
 * or more deltas are triggered, 0.80 when exactly one, 0.97 when all clean.
 *
 * ── D1 — bootEpoch anchor strategy ─────────────────────────────────────────
 *
 * Earlier revisions of this probe computed `abs(wallClockMs - elapsedRealtimeMs)`,
 * which is a unit-error: wall-clock is Unix-epoch ms (~1.7e12), elapsedRealtime
 * is boot-relative ms (small). The difference is dominated by the boot epoch
 * and trivially exceeds the 30 s threshold on every clean device.
 *
 * The correct invariant is that `bootEpoch ≡ wallClockMs - elapsedRealtimeMs`
 * is approximately constant over the life of a process. Wall-clock jumps
 * (manual time changes, NITZ updates, malicious spoofing) shift bootEpoch
 * by the same amount; elapsedRealtime keeps advancing monotonically through
 * deep sleep. So a step in bootEpoch is direct evidence of a wall-clock
 * adjustment.
 *
 * Anchor lifecycle:
 *   • Storage:  in-memory `@Volatile` field on this probe instance. The
 *               anchor is intentionally session-stable, not persisted —
 *               survives across probe invocations within the same process,
 *               resets on process restart (the next run() reseeds).
 *   • TTL:      process lifetime. No external eviction; tied to the probe
 *               instance lifecycle managed by the scheduler.
 *   • Seeding:  on the first run() invocation in a session, the anchor is
 *               null. We compute `bootEpoch = wallMs - elapsedMs`, store it,
 *               record `d1_anchor_seeded_boot_epoch_ms` evidence, and return
 *               D1 score = 0 (we cannot detect drift without a reference).
 *   • Compare:  on subsequent calls, `currentBootEpoch = wallMs - elapsedMs`;
 *               `d1Delta = abs(currentBootEpoch - anchorBootEpoch)`. If the
 *               delta exceeds `d1WallVsElapsedMs` (default 30 000 ms),
 *               D1 fires with `SCORE_D1`.
 *
 * Race notes: read-update of the anchor is done under a small intrinsic lock
 * so two concurrent first-calls can't both observe `null` and seed twice.
 */
class TimeSpoofingProbe : Probe {
    override val id = "env.time_spoofing"
    override val rank = 4
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 3000L

    // Default thresholds (ms). Mutable via assets/baselines/time-thresholds.json.
    internal companion object {
        const val D1_WALL_VS_ELAPSED_MS = 30_000L
        const val D2_WALL_VS_GPS_MS     = 10_000L
        const val D3_WALL_VS_NTP_MS     = 10_000L
        const val D4_GPS_VS_NTP_MS      =  5_000L

        const val SCORE_D1 = 0.55
        const val SCORE_D2 = 0.75
        const val SCORE_D3 = 0.80
        const val SCORE_D4 = 0.90

        const val THRESHOLDS_ASSET = "assets/baselines/time-thresholds.json"
    }

    // Session-stable anchor for D1. See class KDoc for lifecycle.
    @Volatile
    private var bootEpochAnchorMs: Long? = null
    private val anchorLock = Any()

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val thresholds = loadThresholds(ctx)

            val tv = ctx.queryTimeView()
            val wallMs     = tv.wallClockMs()
            val elapsedMs  = tv.elapsedRealtimeMs()
            val gpsMs      = tv.gpsTimestampMs()
            val ntpMs      = tv.ntpTimestampMs()

            val evidence = mutableListOf<Evidence>()
            var maxScore = 0.0
            var triggeredCount = 0

            evidence += Evidence("wall_clock_ms",       wallMs)
            evidence += Evidence("elapsed_realtime_ms", elapsedMs)
            evidence += Evidence("gps_timestamp_ms",    gpsMs ?: "unavailable")
            evidence += Evidence("ntp_timestamp_ms",    ntpMs ?: "unavailable")

            // D1 — drift of (wall - elapsed) bootEpoch vs the session anchor.
            // First call seeds the anchor and yields score=0; subsequent calls
            // compare. See class KDoc "D1 — bootEpoch anchor strategy".
            val currentBootEpochMs = wallMs - elapsedMs
            val priorAnchor: Long? = synchronized(anchorLock) {
                val existing = bootEpochAnchorMs
                if (existing == null) {
                    bootEpochAnchorMs = currentBootEpochMs
                    null   // signals "we just seeded"
                } else {
                    existing
                }
            }

            if (priorAnchor == null) {
                evidence += Evidence("d1_anchor_seeded_boot_epoch_ms", currentBootEpochMs)
            } else {
                val d1Delta = abs(currentBootEpochMs - priorAnchor)
                val d1Threshold = thresholds.d1WallVsElapsedMs
                evidence += Evidence("boot_epoch_anchor_ms", priorAnchor)
                evidence += Evidence("boot_epoch_current_ms", currentBootEpochMs)
                evidence += Evidence("delta_wall_vs_elapsed_ms", d1Delta, expected = "<$d1Threshold")
                if (d1Delta > d1Threshold) {
                    maxScore = maxOf(maxScore, SCORE_D1)
                    triggeredCount++
                }
            }

            // D2 — wall vs GPS
            if (gpsMs != null) {
                val d2Delta = abs(wallMs - gpsMs)
                val d2Threshold = thresholds.d2WallVsGpsMs
                evidence += Evidence("delta_wall_vs_gps_ms", d2Delta, expected = "<$d2Threshold")
                if (d2Delta > d2Threshold) {
                    maxScore = maxOf(maxScore, SCORE_D2)
                    triggeredCount++
                }
            } else {
                evidence += Evidence("delta_wall_vs_gps_ms", "skipped: GPS unavailable")
            }

            // D3 — wall vs NTP
            if (ntpMs != null) {
                val d3Delta = abs(wallMs - ntpMs)
                val d3Threshold = thresholds.d3WallVsNtpMs
                evidence += Evidence("delta_wall_vs_ntp_ms", d3Delta, expected = "<$d3Threshold")
                if (d3Delta > d3Threshold) {
                    maxScore = maxOf(maxScore, SCORE_D3)
                    triggeredCount++
                }
            } else {
                evidence += Evidence("delta_wall_vs_ntp_ms", "skipped: NTP unavailable")
            }

            // D4 — GPS vs NTP (only when both present)
            if (gpsMs != null && ntpMs != null) {
                val d4Delta = abs(gpsMs - ntpMs)
                val d4Threshold = thresholds.d4GpsVsNtpMs
                evidence += Evidence("delta_gps_vs_ntp_ms", d4Delta, expected = "<$d4Threshold")
                if (d4Delta > d4Threshold) {
                    maxScore = maxOf(maxScore, SCORE_D4)
                    triggeredCount++
                }
            } else {
                evidence += Evidence("delta_gps_vs_ntp_ms", "skipped: GPS or NTP unavailable")
            }

            val confidence = when {
                triggeredCount >= 2 -> 0.95
                triggeredCount == 1 -> 0.80
                else -> 0.97
            }

            ProbeResult(
                score = maxScore,
                confidence = confidence,
                evidence = evidence,
                method = "cross-validate bootEpoch anchor + wall/GPS/NTP per freeRASP T15",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "TimeSpoofingProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun loadThresholds(ctx: ProbeContext): Thresholds {
        val raw = ctx.readFile(THRESHOLDS_ASSET) ?: return Thresholds()
        return try {
            Thresholds(
                d1WallVsElapsedMs = parseJsonLong(raw, "wall_vs_elapsed_ms") ?: D1_WALL_VS_ELAPSED_MS,
                d2WallVsGpsMs     = parseJsonLong(raw, "wall_vs_gps_ms")     ?: D2_WALL_VS_GPS_MS,
                d3WallVsNtpMs     = parseJsonLong(raw, "wall_vs_ntp_ms")     ?: D3_WALL_VS_NTP_MS,
                d4GpsVsNtpMs      = parseJsonLong(raw, "gps_vs_ntp_ms")      ?: D4_GPS_VS_NTP_MS,
            )
        } catch (_: Throwable) {
            Thresholds()
        }
    }

    /** Minimal JSON long-value extractor — avoids a full JSON library dependency. */
    private fun parseJsonLong(json: String, key: String): Long? {
        val pattern = Regex(""""$key"\s*:\s*(\d+)""")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    internal data class Thresholds(
        val d1WallVsElapsedMs: Long = D1_WALL_VS_ELAPSED_MS,
        val d2WallVsGpsMs:     Long = D2_WALL_VS_GPS_MS,
        val d3WallVsNtpMs:     Long = D3_WALL_VS_NTP_MS,
        val d4GpsVsNtpMs:      Long = D4_GPS_VS_NTP_MS,
    )
}

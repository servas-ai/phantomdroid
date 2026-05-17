package com.detectorlab.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.Instant

/**
 * Orchestrator: runs all registered probes, enforces timeouts, builds the Report.
 *
 * Round-2 invariant enforcement:
 *   1. Per-probe timeout = probe.budgetMs (hard wall-clock)
 *   2. No-network: probes are forbidden from doing network I/O via Android-side
 *      manifest-permission absence + iptables (host-side, see experiments/runner SPEC §4)
 *   3. Deterministic JSON: schema-validated before persistence
 *   4. Failure isolation: one probe failure does NOT abort the run
 *   5. Declarative metadata: probe id/rank/category surfaced in report
 */
class ProbeRunner(
    private val probes: List<Probe>,
    private val ctx: ProbeContext,
    private val appVersion: String,
    private val deviceLabel: String,
) {
    suspend fun runAll(): Report = coroutineScope {
        val records = probes.map { probe ->
            val started = System.currentTimeMillis()
            val result: ProbeResult = try {
                withTimeout(probe.budgetMs) {
                    val r = probe.run(ctx)
                    require(r.runtimeMs <= probe.budgetMs) { "probe self-reported runtime > budget" }
                    r
                }
            } catch (e: TimeoutCancellationException) {
                ProbeResult.failed(
                    "timeout: exceeded ${probe.budgetMs}ms",
                    runtimeMs = System.currentTimeMillis() - started,
                )
            } catch (e: Throwable) {
                // Failure isolation: log + record, do NOT propagate.
                ProbeResult.failed(
                    "uncaught: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
                    runtimeMs = System.currentTimeMillis() - started,
                )
            }
            ProbeRecord(
                id = probe.id,
                rank = probe.rank,
                category = probe.category.name.lowercase(),
                score = result.score,
                confidence = result.confidence,
                evidence = result.evidence.map { EvidenceRecord(it.key, it.value, it.expected) },
                method = result.method,
                runtimeMs = result.runtimeMs,
                failed = result.failed,
                failureReason = result.failureReason,
            )
        }
        Report(
            schemaVersion = "1.0",
            deviceLabel = deviceLabel,
            timestamp = Instant.now().toString(),
            appVersion = appVersion,
            probes = records,
            aggregate = aggregate(records),
        )
    }

    /**
     * Compute weighted aggregate per /docs/probe-schema.md.
     *   weight[rank] =
     *     3.0 if 1..10   (kritisch)
     *     2.0 if 11..25  (hoch)
     *     1.0 if 26..40  (mittel)
     *     0.5 if 41..75  (niedrig + ergänzend + Round-2 additions)
     */
    private fun aggregate(records: List<ProbeRecord>): Aggregate {
        if (records.isEmpty()) {
            return Aggregate(weightedScore = 0.0, criticalFailures = 0, category = ReportCategory.CLEAN)
        }
        var totalScore = 0.0
        var totalWeight = 0.0
        var critFailures = 0
        for (r in records) {
            val w = when (r.rank) {
                in 1..10 -> 3.0
                in 11..25 -> 2.0
                in 26..40 -> 1.0
                else -> 0.5
            }
            totalScore += r.score * w
            totalWeight += w
            if (r.rank in 1..10 && r.score >= 0.7) critFailures++
        }
        val weighted = totalScore / totalWeight
        val cat = when {
            critFailures >= 3 -> ReportCategory.DETECTED
            weighted < 0.10 -> ReportCategory.CLEAN
            weighted < 0.40 -> ReportCategory.SUSPICIOUS
            else -> ReportCategory.DETECTED
        }
        return Aggregate(weightedScore = weighted, criticalFailures = critFailures, category = cat)
    }
}

package com.detectorlab.core

/**
 * Top-level Probe-Run report. Maps 1:1 to JSON-Schema v1
 * (see /docs/probe-schema.md).
 *
 * Persisted by ProbeRunner.persist() to /sdcard/Download/detectorlab-report.json.
 * Pulled via adb by experiments/runner orchestrator (see experiments/runner/SPEC.md).
 */
data class Report(
    val schemaVersion: String,           // const "1.0"
    val deviceLabel: String,             // lab-internal config ID, e.g. "redroid-12-L0a-001"
    val timestamp: String,               // ISO-8601, UTC
    val appVersion: String,              // semver, e.g. "0.1.0"
    val probes: List<ProbeRecord>,
    val aggregate: Aggregate,
) {
    init {
        require(schemaVersion == "1.0") { "schemaVersion must be 1.0 (v1 schema lock)" }
        require(timestamp.endsWith("Z")) { "timestamp must be UTC (suffix Z)" }
        require(appVersion.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) { "appVersion must be semver" }
    }
}

/**
 * One probe's full record in the report. Mirrors ProbeResult plus identity.
 */
data class ProbeRecord(
    val id: String,                      // matches Probe.id
    val rank: Int,                       // matches Probe.rank
    val category: String,                // ProbeCategory.name in lower_case
    val score: Double,
    val confidence: Double,
    val evidence: List<EvidenceRecord>,
    val method: String,
    val runtimeMs: Long,
    val failed: Boolean = false,
    val failureReason: String? = null,
)

data class EvidenceRecord(
    val key: String,
    val value: Any,                      // String | Number | Boolean
    val expected: Any? = null,
)

/**
 * Aggregate summary computed by ProbeRunner.aggregate().
 * Weights and thresholds defined in /docs/probe-schema.md §"Gewichtung im Aggregate".
 */
data class Aggregate(
    val weightedScore: Double,           // weighted mean over all probes
    val criticalFailures: Int,           // count of critical-rank probes with score >= 0.7
    val category: ReportCategory,        // CLEAN / SUSPICIOUS / DETECTED
)

enum class ReportCategory { CLEAN, SUSPICIOUS, DETECTED }

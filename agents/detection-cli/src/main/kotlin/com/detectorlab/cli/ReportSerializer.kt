// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ReportSerializer.kt
//
// Serialize the production `Report` data class to pretty-printed JSON.
//
// `Report`, `ProbeRecord`, `EvidenceRecord`, and `Aggregate` are NOT
// @Serializable in the :detection module (they pre-date the CLI and
// `EvidenceRecord.value` is intentionally `Any` because evidence values may
// be String / Number / Boolean per the schema). Rather than annotate the
// production data classes, the CLI maps Report → JsonElement manually.
// This keeps :detection free of the kotlinx-serialization runtime dependency.
//
// Output schema (JSON):
//   {
//     "schemaVersion": "1.0",
//     "deviceLabel":   "redroid-12-amd64-2026-05-20",
//     "timestamp":     "2026-05-20T12:34:56.789Z",
//     "appVersion":    "0.1.0",
//     "aggregate": { "weightedScore": 0.123, "criticalFailures": 2, "category": "SUSPICIOUS" },
//     "probes": [
//       { "id": "buildprop.fingerprint", "rank": 1, "category": "buildprop",
//         "score": 1.0, "confidence": 0.95,
//         "evidence": [{"key": "...", "value": "...", "expected": "..."}],
//         "method": "...", "runtimeMs": 12, "failed": false, "failureReason": null }
//     ]
//   }

package com.detectorlab.cli

import com.detectorlab.core.EvidenceRecord
import com.detectorlab.core.ProbeRecord
import com.detectorlab.core.Report
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object ReportSerializer {

    // `prettyPrintIndent` is currently behind ExperimentalSerializationApi in
    // kotlinx-serialization 1.6.x. Opt in at the call site; the field is
    // standard library surface (no API breakage risk) and the alternative
    // (relying on the default 4-space indent) produces less-compact output.
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun toJson(report: Report): String {
        val root = buildJsonObject {
            put("schemaVersion", JsonPrimitive(report.schemaVersion))
            put("deviceLabel", JsonPrimitive(report.deviceLabel))
            put("timestamp", JsonPrimitive(report.timestamp))
            put("appVersion", JsonPrimitive(report.appVersion))
            put(
                "aggregate",
                buildJsonObject {
                    put("weightedScore", JsonPrimitive(report.aggregate.weightedScore))
                    put("criticalFailures", JsonPrimitive(report.aggregate.criticalFailures))
                    put("category", JsonPrimitive(report.aggregate.category.name))
                },
            )
            put("probes", probesArray(report.probes))
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun probesArray(records: List<ProbeRecord>): JsonArray = buildJsonArray {
        for (r in records) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(r.id))
                    put("rank", JsonPrimitive(r.rank))
                    put("category", JsonPrimitive(r.category))
                    put("score", JsonPrimitive(r.score))
                    put("confidence", JsonPrimitive(r.confidence))
                    put("evidence", evidenceArray(r.evidence))
                    put("method", JsonPrimitive(r.method))
                    put("runtimeMs", JsonPrimitive(r.runtimeMs))
                    put("failed", JsonPrimitive(r.failed))
                    put(
                        "failureReason",
                        r.failureReason?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                },
            )
        }
    }

    private fun evidenceArray(evidence: List<EvidenceRecord>): JsonArray = buildJsonArray {
        for (e in evidence) {
            add(
                buildJsonObject {
                    put("key", JsonPrimitive(e.key))
                    put("value", anyToJson(e.value))
                    put("expected", anyToJson(e.expected))
                },
            )
        }
    }

    /**
     * Serialize an `Any?` evidence value to a JSON primitive / null. The
     * `EvidenceRecord` contract permits String / Number / Boolean / null; any
     * other type is `toString()`'d defensively (no probe should produce one,
     * but we shouldn't crash the report on an unexpected type either).
     */
    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}

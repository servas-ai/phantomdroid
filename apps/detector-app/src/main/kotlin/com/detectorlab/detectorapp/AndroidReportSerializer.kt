// apps/detector-app/.../AndroidReportSerializer.kt
//
// Serialize the production `Report` to schemaVersion-1.0 JSON. Byte-for-byte
// equivalent to agents/detection-cli/.../ReportSerializer.kt so the on-device
// report is interchangeable with the CLI's snapshot-replay report (same
// schema, shared/probe-schema.md). Duplicated rather than reused for the same
// reason as the registry: :detection-cli is a JVM app, not an APK dependency,
// and :detection stays free of the kotlinx-serialization runtime.

package com.detectorlab.detectorapp

import com.detectorlab.core.EvidenceRecord
import com.detectorlab.core.ProbeRecord
import com.detectorlab.core.Report
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object AndroidReportSerializer {

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

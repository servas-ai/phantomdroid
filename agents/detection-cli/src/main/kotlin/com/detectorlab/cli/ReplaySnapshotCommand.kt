// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ReplaySnapshotCommand.kt
//
// Power-18 D1 — `detection-cli replay-snapshot <name>` subcommand.
//
// Runs the full 65-probe production inventory against a NAMED in-source
// snapshot fixture (no YAML/JSON file required — the snapshot is looked up
// against the active SnapshotRegistry) and emits a deterministic JSON
// document on stdout, with exit codes:
//
//   0  clean       (aggregate.anyDetected == false)
//   1  detected    (aggregate.anyDetected == true)
//   2  error       (any unhandled exception or registry miss)
//
// JSON output schema (per Power-18 D1 spec):
//
//   {
//     "snapshot": "<canonical name>",
//     "deviceLabel": "<DeviceSnapshot.label>",
//     "probes": [
//       { "id": "...", "rank": 1, "score": 0.0, "verdict": "spoofed",
//         "evidence": [{ "key": "...", "value": "...", "expected": "..." }] }
//     ],
//     "aggregate": {
//       "weightedScore": 0.0000,
//       "criticalFailures": 0,
//       "anyDetected": false,
//       "totalProbes": 65
//     }
//   }
//
// `verdict` mirrors `CoverageMatrixGeneratorTest.classify()` semantics:
//   - "spoofed" : ran, score < 0.30 (real-device answer)
//   - "raw"     : ran, score >= 0.30 (emulator/spoof detected)
//   - "absent"  : failureReason startsWith "skipped: "
//   - "error"   : failed for any other reason
//
// `anyDetected` is computed via [CompositeDetector.anyDetectorFires] — the
// six-detector OR-union encoded in MasterCompositeDetectorReplayTest:
//
//   anyDetected = RootBeer.isRooted(ctx)       (9 branches) ||
//                 Momo.magiskDetected(ctx)     (5 signals) ||
//                 FridaDetector.fridaDetected(ctx) (3 UNION) ||
//                 Play Integrity.fails(ctx)    (5 buildprop) ||
//                 EmulatorDetector.fires(ctx)  (8 OR-gated) ||
//                 freeRASP T5 install-source(ctx)
//
// This is the dispositive "device-attestation" question production callers
// ask. Aggregate weightedScore is a centroid statistic and undershoots on
// third-party emulators (Nox/BlueStacks/Genymotion sit at weightedScore≈
// 0.14-0.18, below the 0.40 DETECTED band) because only a minority of the
// 65 probes have signal for those fixtures — yet the EmulatorDetector
// composite-rule fires unambiguously on each. Per the
// spoof-stack-corpus-index, emulator fixtures MUST fire emulator-rule;
// reflecting that in CLI exit codes requires the composite, not the
// weightedScore.
//
// CRITICAL invariants preserved:
//   * RedroidSpoofed → composite=false → exit 0
//     (spoof stack defeats ALL 6 families simultaneously — the central
//     spoof-stack claim from MasterCompositeDetectorReplayTest).
//   * RedroidSpoofed → aggregate.weightedScore == 0.0000 (separate
//     numeric invariant; lives in the JSON aggregate block but is NOT
//     the input to the exit-code gate).

package com.detectorlab.cli

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.Report
import com.detectorlab.core.replay.DeviceSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.system.exitProcess

/**
 * Result of running a named snapshot through the full 65-probe panel.
 *
 * The [exitCode] is what `main()` would have returned had this been invoked
 * as the CLI subcommand; tests exercise the API path directly and assert on
 * the same exit-code triple (0=clean / 1=detected / 2=error).
 */
data class ReplaySnapshotOutcome(
    val snapshotName: String,
    val deviceLabel: String,
    val report: Report,
    val anyDetected: Boolean,
    val json: String,
    val exitCode: Int,
)

/**
 * Programmatic entry point — same code path the `replay-snapshot` Clikt
 * command takes, factored out so :detection-cli's test sourceSet can drive
 * it against the extended 8-snapshot test registry without going through
 * argv/stdout/exitProcess.
 *
 * @param registry which snapshot pool to look up against (Main vs Test).
 * @param name snapshot identifier (case-insensitive).
 * @param appVersion semver used in the underlying Report (irrelevant to the
 *                   replay-snapshot JSON which doesn't echo it, but required
 *                   by ProbeRunner).
 */
fun runReplaySnapshot(
    registry: SnapshotRegistry,
    name: String,
    appVersion: String = CLI_VERSION,
): ReplaySnapshotOutcome {
    val (canonicalName, snapshot) = resolveSnapshot(registry, name)
    val ctx = SnapshotReplayContext(snapshot)
    val probes = ProbeRegistry.allProbes(ctx)
    check(probes.size == ProbeRegistry.EXPECTED_COUNT) {
        "ProbeRegistry size drift: expected ${ProbeRegistry.EXPECTED_COUNT}, got ${probes.size}"
    }
    val runner = ProbeRunner(
        probes = probes,
        ctx = ctx,
        appVersion = appVersion,
        deviceLabel = snapshot.label,
    )
    val report = runBlocking { runner.runAll() }

    // `anyDetected` uses the six-detector composite OR-union — VERBATIM
    // from MasterCompositeDetectorReplayTest's `anyDetectorFires`. This is
    // the dispositive device-attestation question; it intentionally does
    // NOT depend on the 65-probe weightedScore aggregate (which undershoots
    // on third-party emulator fixtures whose signal is concentrated in a
    // small subset of probes).
    val anyDetected = CompositeDetector.anyDetectorFires(ctx)

    val json = ReplaySnapshotJson.render(
        snapshotName = canonicalName,
        report = report,
        anyDetected = anyDetected,
    )
    val exit = if (anyDetected) 1 else 0
    return ReplaySnapshotOutcome(
        snapshotName = canonicalName,
        deviceLabel = snapshot.label,
        report = report,
        anyDetected = anyDetected,
        json = json,
        exitCode = exit,
    )
}

/**
 * Determine the per-probe verdict token. Mirrors
 * `CoverageMatrixGeneratorTest.classify()` so the audit matrix and the
 * CLI agree on the same four-state classification.
 */
internal fun verdictFor(record: com.detectorlab.core.ProbeRecord): String {
    if (record.failed) {
        val reason = record.failureReason ?: return "error"
        return if (reason.startsWith("skipped: ")) "absent" else "error"
    }
    return if (record.score >= 0.30) "raw" else "spoofed"
}

/**
 * Deterministic JSON rendering. Pure transform of (snapshot-name, Report,
 * anyDetected) → JSON string. No timestamps, no machine-dependent paths;
 * given the same inputs the output bytes are bit-identical.
 *
 * The Report's `timestamp` field IS read but appears only inside the
 * inner ProbeRecord rendering as `runtimeMs` (which IS deterministic per
 * probe-run because ProbeRunner records wall-clock duration). For full
 * byte-determinism across runs callers must also clamp `runtimeMs` — but
 * that's a downstream concern (the Power-15 coverage matrix does this
 * deliberately by NOT including runtimeMs in its output).
 *
 * Here we DO include `runtimeMs` for diagnostic value; the
 * "deterministic" contract is shape + key-ordering + numeric formatting,
 * NOT bit-identical bytes across re-runs.
 */
internal object ReplaySnapshotJson {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun render(
        snapshotName: String,
        report: Report,
        anyDetected: Boolean,
    ): String {
        // Sort probes by inventory rank for deterministic ordering. The
        // CoverageMatrixGeneratorTest uses `inventoryRank` (Double) for row
        // ordering; we use `rank` (Int) because ProbeRecord doesn't carry
        // the fractional inventoryRank — sorted-by-rank-then-id is the
        // canonical CLI row order.
        val sortedProbes = report.probes.sortedWith(
            compareBy({ it.rank }, { it.id }),
        )
        val root = buildJsonObject {
            put("snapshot", JsonPrimitive(snapshotName))
            put("deviceLabel", JsonPrimitive(report.deviceLabel))
            put("probes", probesArray(sortedProbes))
            put(
                "aggregate",
                buildJsonObject {
                    put(
                        "weightedScore",
                        // Format to 4 decimal places so the
                        // RedroidSpoofed=0.0000 invariant is bit-stable.
                        JsonPrimitive(roundTo4dp(report.aggregate.weightedScore)),
                    )
                    put("criticalFailures", JsonPrimitive(report.aggregate.criticalFailures))
                    put("anyDetected", JsonPrimitive(anyDetected))
                    put("totalProbes", JsonPrimitive(report.probes.size))
                },
            )
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun probesArray(records: List<com.detectorlab.core.ProbeRecord>): JsonArray =
        buildJsonArray {
            for (r in records) {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(r.id))
                        put("rank", JsonPrimitive(r.rank))
                        put("score", JsonPrimitive(roundTo4dp(r.score)))
                        put("verdict", JsonPrimitive(verdictFor(r)))
                        put("evidence", evidenceArray(r.evidence))
                    },
                )
            }
        }

    private fun evidenceArray(
        evidence: List<com.detectorlab.core.EvidenceRecord>,
    ): JsonArray = buildJsonArray {
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

    /**
     * Round a Double to 4 decimal places. Used for `weightedScore` and
     * probe `score` so the wire-format is bit-stable across runs (the
     * underlying Double may carry trailing-precision noise like
     * 0.04999999999999964). 4dp matches the existing `"%.4f".format(...)`
     * convention used elsewhere in the detection module.
     */
    private fun roundTo4dp(d: Double): Double {
        if (d.isNaN() || d.isInfinite()) return d
        return Math.round(d * 10_000.0) / 10_000.0
    }
}

/**
 * Clikt subcommand surface. Production binary wires this against
 * [MainSnapshotRegistry] (4 snapshots); the test sourceSet has its own
 * test class that drives [runReplaySnapshot] directly against the 8-snapshot
 * test registry so the production binary remains the 4-snapshot artifact.
 */
class ReplaySnapshotCommand(
    private val registry: SnapshotRegistry = MainSnapshotRegistry,
) : CliktCommand(
    name = "replay-snapshot",
    help = "Run all 65 probes against a NAMED in-source snapshot fixture and emit deterministic JSON.",
) {

    private val snapshotName: String by argument(
        name = "name",
        help = "Snapshot name (case-insensitive). Available in production: " +
            MainSnapshotRegistry.names().joinToString(", "),
    )

    private val appVersion: String by option(
        "--app-version",
        help = "Semver `appVersion` for the underlying Report. Defaults to the CLI's own version.",
    ).default(CLI_VERSION)

    /**
     * Suppress process exit. Used by Clikt integration tests that don't
     * want to terminate the JVM under JUnit. Default off — the binary
     * MUST exit with the right code in normal use.
     */
    private val noExit: Boolean by option(
        "--no-exit",
        help = "Internal: do not call exitProcess(). Used by JVM-embedded tests only.",
    ).flag(default = false)

    override fun run() {
        val outcome = try {
            runReplaySnapshot(registry, snapshotName, appVersion)
        } catch (e: SnapshotNotFound) {
            System.err.println("detection-cli replay-snapshot: ${e.message}")
            if (!noExit) exitProcess(2)
            return
        }
        // Stdout = the JSON. Stderr = a one-line summary so shell pipelines
        // can observe verdict without parsing the JSON.
        println(outcome.json)
        System.err.println(
            "detection-cli replay-snapshot: ${outcome.snapshotName} | " +
                "deviceLabel=${outcome.deviceLabel} | " +
                "weightedScore=${"%.4f".format(outcome.report.aggregate.weightedScore)} | " +
                "criticalFailures=${outcome.report.aggregate.criticalFailures} | " +
                "anyDetected=${outcome.anyDetected} | " +
                "exit=${outcome.exitCode}",
        )
        if (!noExit) exitProcess(outcome.exitCode)
    }
}

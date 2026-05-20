// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/Main.kt
//
// Entry point for the `detection-cli` runnable. Clikt-based command tree:
//
//   detection-cli run      --snapshot <path> [--output <path>] [--app-version <semver>]
//   detection-cli validate --snapshot <path>
//   detection-cli version
//
// Exit codes:
//   0  success
//   1  invalid arguments / file errors / parse failures
//   2  uncaught runtime exception in the probe pipeline
//
// `run` writes the Report JSON to stdout by default. When `--output` is set,
// JSON is written to the file and a one-line summary
// (`weightedScore / category / criticalFailures`) is written to stderr so
// shell pipelines can observe progress without polluting stdout.

package com.detectorlab.cli

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.replay.SnapshotReplayContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

const val CLI_VERSION = "0.1.0"

class DetectionCli : CliktCommand(
    name = "detection-cli",
    help = "Run the DetectorLab probe inventory against a captured DeviceSnapshot.",
) {
    override fun run() = Unit
}

class RunCommand : CliktCommand(
    name = "run",
    help = "Run all 65 probes against a snapshot and emit a JSON report.",
) {
    private val snapshot: File by option(
        "--snapshot", "-s",
        help = "Path to snapshot file (.yml, .yaml, or .json).",
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true).required()

    private val output: File? by option(
        "--output", "-o",
        help = "Write JSON report to this path instead of stdout.",
    ).file(canBeDir = false)

    private val appVersion: String by option(
        "--app-version",
        help = "Semver `appVersion` field for the report. Defaults to the CLI's own version.",
    ).default(CLI_VERSION)

    override fun run() {
        val snap = SnapshotLoader.load(snapshot)
        val ctx = SnapshotReplayContext(snap)
        val probes = ProbeRegistry.allProbes(ctx)
        check(probes.size == ProbeRegistry.EXPECTED_COUNT) {
            "ProbeRegistry size drift: expected ${ProbeRegistry.EXPECTED_COUNT}, got ${probes.size}"
        }
        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = appVersion,
            deviceLabel = snap.label,
        )
        val report = runBlocking { runner.runAll() }
        val jsonText = ReportSerializer.toJson(report)

        val target = output
        if (target != null) {
            target.writeText(jsonText)
            System.err.println(
                "detection-cli: wrote ${jsonText.length} bytes to ${target.absolutePath} | " +
                    "weightedScore=${"%.4f".format(report.aggregate.weightedScore)} " +
                    "criticalFailures=${report.aggregate.criticalFailures} " +
                    "category=${report.aggregate.category}",
            )
        } else {
            println(jsonText)
        }
    }
}

class ValidateCommand : CliktCommand(
    name = "validate",
    help = "Parse a snapshot file and report OK or the parse error. Does not run probes.",
) {
    private val snapshot: File by option(
        "--snapshot", "-s",
        help = "Path to snapshot file (.yml, .yaml, or .json).",
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true).required()

    override fun run() {
        val snap = SnapshotLoader.load(snapshot)
        // Sanity-check that the SnapshotReplayContext + probe registry can
        // be instantiated against the parsed snapshot. Catches configuration
        // drift (renamed probe, missing supplier) without running probes.
        val ctx = SnapshotReplayContext(snap)
        val probes = ProbeRegistry.allProbes(ctx)
        check(probes.size == ProbeRegistry.EXPECTED_COUNT) {
            "ProbeRegistry size drift: expected ${ProbeRegistry.EXPECTED_COUNT}, got ${probes.size}"
        }
        println(
            "OK: ${snap.label} | capturedAt=${snap.capturedAt} | " +
                "sdkInt=${snap.sdkInt} | systemProperties=${snap.systemProperties.size} keys | " +
                "existingFiles=${snap.existingFiles.size} entries | " +
                "probes=${probes.size}",
        )
    }
}

class VersionCommand : CliktCommand(
    name = "version",
    help = "Print the CLI version and exit.",
) {
    override fun run() {
        println("detection-cli $CLI_VERSION")
    }
}

fun main(args: Array<String>) {
    try {
        DetectionCli()
            .subcommands(RunCommand(), ValidateCommand(), VersionCommand())
            .main(args)
    } catch (e: IllegalArgumentException) {
        // Snapshot-loader or registry-instantiation errors. Clikt-internal
        // errors (bad flags, help message) are already handled inside
        // .main() and never propagate here.
        System.err.println("detection-cli: ${e.message}")
        exitProcess(1)
    } catch (e: Throwable) {
        System.err.println("detection-cli: uncaught ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(2)
    }
}

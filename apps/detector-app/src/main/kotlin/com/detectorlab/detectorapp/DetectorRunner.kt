// apps/detector-app/.../DetectorRunner.kt
//
// Shared orchestration: build an AndroidProbeContext over the live runtime,
// instantiate the 65-probe inventory, run the production ProbeRunner, serialize
// the Report to schemaVersion-1.0 JSON, and persist it where `adb pull` can
// reach it. Invoked by BOTH the foreground MainActivity and the headless
// instrumented runner (ProbeRunnerInstrumentation) so the two paths produce an
// identical artifact.

package com.detectorlab.detectorapp

import android.content.Context
import android.os.Build
import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.Report
import com.detectorlab.detectorapp.context.AndroidProbeContext
import java.io.File

object DetectorRunner {

    const val APP_VERSION = "0.1.0"
    const val REPORT_FILENAME = "detectorlab-report.json"

    data class RunOutcome(
        val report: Report,
        val json: String,
        val reportFile: File,
    )

    /**
     * Run the full inventory against the live device and write the JSON report
     * to the app's external files dir (preferred for `adb pull`) with a fallback
     * to the internal files dir. The external path is
     * `/sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json`,
     * world-readable to the shell user — the exact pull path apk-deliver verified.
     *
     * `deviceLabel` defaults to a Build-derived identifier so the report is
     * self-describing without external configuration.
     */
    suspend fun runAndPersist(
        context: Context,
        deviceLabel: String = defaultDeviceLabel(),
    ): RunOutcome {
        val appContext = context.applicationContext
        val ctx = AndroidProbeContext(appContext)
        val probes = AndroidProbeRegistry.allProbes(ctx)
        check(probes.size == AndroidProbeRegistry.EXPECTED_COUNT) {
            "AndroidProbeRegistry size drift: expected " +
                "${AndroidProbeRegistry.EXPECTED_COUNT}, got ${probes.size}"
        }

        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = APP_VERSION,
            deviceLabel = deviceLabel,
        )
        val report = runner.runAll()
        val json = AndroidReportSerializer.toJson(report)

        val targetDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val reportFile = File(targetDir, REPORT_FILENAME)
        reportFile.writeText(json)

        return RunOutcome(report = report, json = json, reportFile = reportFile)
    }

    fun defaultDeviceLabel(): String =
        "android-${Build.MANUFACTURER}-${Build.MODEL}-sdk${Build.VERSION.SDK_INT}"
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .lowercase()
}

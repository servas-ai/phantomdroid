// apps/detector-app/.../MainActivity.kt
//
// Foreground entry point. On launch it runs the full probe inventory against
// the live device and shows the aggregate verdict + report path on screen.
// The report JSON is written to the app's external files dir for `adb pull`.
//
// The Activity exists for manual / interactive runs; the canonical headless
// path apk-deliver uses is the instrumented runner (see
// ProbeRunnerInstrumentation in androidTest). Both call DetectorRunner.

package com.detectorlab.detectorapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.statusText)
        status.text = getString(R.string.running)

        lifecycleScope.launch {
            val text = try {
                val outcome = withContext(Dispatchers.IO) {
                    DetectorRunner.runAndPersist(applicationContext)
                }
                val agg = outcome.report.aggregate
                buildString {
                    appendLine("DetectorLab on-device run complete.")
                    appendLine()
                    appendLine("category:         ${agg.category}")
                    appendLine("weightedScore:    ${"%.4f".format(agg.weightedScore)}")
                    appendLine("criticalFailures: ${agg.criticalFailures}")
                    appendLine("probes:           ${outcome.report.probes.size}")
                    appendLine("schemaVersion:    ${outcome.report.schemaVersion}")
                    appendLine()
                    appendLine("report written to:")
                    appendLine(outcome.reportFile.absolutePath)
                }
            } catch (e: Throwable) {
                "Run failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            status.text = text
        }
    }
}

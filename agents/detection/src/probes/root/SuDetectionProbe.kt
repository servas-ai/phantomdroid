// inventory.yml rank 3, mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3 — root.su_detection.
 *
 * Detects rooted devices by scanning the filesystem for known su binary paths,
 * Magisk artifacts, and installed superuser packages. Mirrors the RootBeer
 * filesystem-check subset (path enumeration + package list); does NOT touch
 * mount tables, busybox, or process listings (those are separate ranks).
 *
 * Scoring:
 *   - 1.0  if any su binary path OR magisk filesystem artifact is present
 *   - 0.85 if only a superuser package is installed (binary hidden / partial
 *          obfuscation — Magisk DenyList commonly hides binaries but rarely
 *          uninstalls the manager APK)
 *   - 0.0  otherwise
 *
 * Confidence:
 *   - 0.95 when the file-existence accessor succeeded (deterministic)
 *   - 0.5  when fileExists threw on every probed path (degraded read)
 *
 * Reference: shared/probes/inventory.yml rank 3 (mitigation_layer L4).
 */
class SuDetectionProbe : Probe {
    override val id = "root.su_detection"
    override val rank = 3
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 300L

    companion object {
        val SU_BINARY_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
        )

        val MAGISK_ARTIFACT_PATHS: List<String> = listOf(
            "/sbin/.magisk",
            "/system/bin/magisk",
            "/cache/magisk.log",
            "/data/adb/magisk",
        )

        val SUPERUSER_PACKAGES: List<String> = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
        )

        const val METHOD =
            "Filesystem path + package-list scan for known root toolchains (RootBeer-pattern subset)"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val evidence = mutableListOf<Evidence>()

            var fileChecksAttempted = 0
            var fileChecksThrew = 0
            var anyBinaryPresent = false

            fun probePath(path: String): Boolean {
                fileChecksAttempted++
                return try {
                    val present = ctx.fileExists(path)
                    evidence.add(
                        Evidence(
                            key = path,
                            value = if (present) "present" else "absent",
                            expected = "absent",
                        )
                    )
                    present
                } catch (_: Throwable) {
                    fileChecksThrew++
                    evidence.add(
                        Evidence(
                            key = path,
                            value = "absent",
                            expected = "absent",
                        )
                    )
                    false
                }
            }

            for (path in SU_BINARY_PATHS) {
                if (probePath(path)) anyBinaryPresent = true
            }
            for (path in MAGISK_ARTIFACT_PATHS) {
                if (probePath(path)) anyBinaryPresent = true
            }

            val pm = try {
                ctx.queryPackageManager()
            } catch (_: Throwable) {
                null
            }

            var anyPackagePresent = false
            for (pkg in SUPERUSER_PACKAGES) {
                val installed = try {
                    pm?.isPackageInstalled(pkg) == true
                } catch (_: Throwable) {
                    false
                }
                if (installed) anyPackagePresent = true
                evidence.add(
                    Evidence(
                        key = "pkg.$pkg",
                        value = if (installed) "installed" else "absent",
                        expected = "absent",
                    )
                )
            }

            val score = when {
                anyBinaryPresent -> 1.0
                anyPackagePresent -> 0.85
                else -> 0.0
            }

            val allFileChecksFailed =
                fileChecksAttempted > 0 && fileChecksThrew == fileChecksAttempted
            val confidence = if (allFileChecksFailed) 0.5 else 0.95

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "SuDetectionProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

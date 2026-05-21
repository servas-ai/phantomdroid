// inventory.yml rank 3.6 (code-rank 94), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.6 — root.kernelsu.
 *
 * Detects KernelSU — the kernel-space root solution (an alternative
 * to Magisk's userspace approach) — by scanning for its canonical
 * runtime artifacts under `/data/adb/` and reading the optional
 * `ro.kernelsu.version` system property. KernelSU is implemented as
 * a kernel patch (`kernel/ksu.c`) that exposes `/data/adb/ksud`
 * (the userspace daemon) and `/data/adb/ksu` (the working
 * directory) once installed.
 *
 * Reference: https://github.com/tiann/KernelSU
 *
 * **Why distinct from SuDetectionProbe (rank 3)**: SuDetectionProbe
 * scans the classic Magisk + SU-binary path set
 * (`/system/bin/su`, `/sbin/.magisk`, `com.topjohnwu.magisk`, etc.).
 * It does NOT cover KernelSU's `/data/adb/ksu*` paths or the
 * `ro.kernelsu.version` system property. KernelSU is a parallel
 * root toolchain — a KernelSU-rooted device with NO Magisk install
 * is fully invisible to rank-3 but dispositively flagged by this
 * probe.
 *
 * Signal classes (HIGH severity — `/data/adb/ksu*` is KernelSU-owned
 * runtime state; no legitimate AOSP/OEM component writes to those
 * paths):
 *
 *   • **KSU files present (1.00)**: at least one of
 *     `/data/adb/ksu` or `/data/adb/ksud` exists. Dispositive of
 *     KernelSU presence — the canonical "I am rooted via the
 *     kernel patch" signature.
 *   • **KSU system property set (0.90)**: `ro.kernelsu.version`
 *     returns a non-null, non-empty value. Strong but slightly
 *     lower than file presence because `getSystemProperty` can be
 *     hooked by Zygisk modules; the file-presence branch is the
 *     harder-to-spoof signal.
 *   • **Clean (0.00)**: no KSU file present AND no version
 *     property set.
 *
 * Scoring (max wins; first-match cascade in severity order):
 *   1.00  PATTERN_KSU_FILES_PRESENT
 *   0.90  PATTERN_KSU_PROPERTY_SET
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  NORMAL    every `fileExists` call returned (success or
 *                   absence; the accessor itself did not throw)
 *   0.50  DEGRADED  every `fileExists` call threw — we couldn't
 *                   observe the filesystem at all
 *
 * **inventoryRank vs code-rank**: inventory 3.6 → code-rank 94
 * (next free slot above MagiskModuleDirProbe's 92 / Fingerprint
 * CrossPartitionProbe's 93). Cross-cutting #7.
 *
 * **Cross-cutting #1 evidence-namespace**: probe-id is
 * `root.kernelsu` → evidence keys are prefixed `ksu.*` (never
 * bare-keyed). Closes cross-cutting #1 namespacing for KernelSU.
 *
 * **Cross-cutting #7 fractional rank**: `inventoryRank = 3.6` is
 * between `root.magisk_uds` (3.5) and `runtime.init_svc_enumeration`
 * (3.7). The `Probe.rank: Int` interface uses code-rank 94.
 *
 * Reference: shared/probes/inventory.yml rank 3.6 (mitigation_layer
 * L4); https://github.com/tiann/KernelSU.
 */
class KernelSURootProbe : Probe {
    override val id = "root.kernelsu"
    override val rank = RANK
    override val inventoryRank = 3.6
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.6; Int slot 94 picked
         * (next free above 93 = `FingerprintCrossPartitionProbe`).
         * Cross-cutting #7.
         */
        const val RANK = 94

        /**
         * Canonical KernelSU runtime paths. Per the KernelSU README
         * (https://github.com/tiann/KernelSU) and source tree:
         *   - `/data/adb/ksu`   — KernelSU working directory
         *   - `/data/adb/ksud`  — KernelSU userspace daemon binary
         */
        val KSU_PATHS: List<String> = listOf(
            "/data/adb/ksu",
            "/data/adb/ksud",
        )

        /**
         * KernelSU exposes its version via this system property when
         * the kernel module is loaded. Pre-Zygisk versions do not
         * populate it; post-KSU-0.5.x builds reliably do.
         */
        const val KSU_VERSION_PROP = "ro.kernelsu.version"

        const val PATTERN_KSU_FILES_PRESENT = "ksu_files_present"
        const val PATTERN_KSU_PROPERTY_SET = "ksu_property_set"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_KSU_FILES = 1.0
        const val SCORE_KSU_PROPERTY = 0.90
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val EV_FILES_HIT = "ksu.files_hit"
        const val EV_VERSION_PROP = "ksu.version_property"
        const val EV_PATTERN = "ksu.pattern"
        const val EV_OBSERVATION_OK = "ksu.observation_ok"

        const val METHOD =
            "Filesystem path scan for /data/adb/ksu + /data/adb/ksud " +
                "(KernelSU runtime artifacts) plus ro.kernelsu.version " +
                "system property read. KernelSU is the kernel-space " +
                "root toolchain (https://github.com/tiann/KernelSU) — " +
                "parallel to and not covered by rank-3 SuDetectionProbe " +
                "(which targets Magisk + classic SU binaries)."
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var fileChecksAttempted = 0
            var fileChecksThrew = 0
            val filesPresent = mutableListOf<String>()

            for (path in KSU_PATHS) {
                fileChecksAttempted++
                val present = try {
                    ctx.fileExists(path)
                } catch (_: Throwable) {
                    fileChecksThrew++
                    false
                }
                if (present) filesPresent.add(path)
            }

            val versionProp: String? = try {
                ctx.getSystemProperty(KSU_VERSION_PROP)?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            }

            val anyFilePresent = filesPresent.isNotEmpty()
            val versionSet = versionProp != null

            val (pattern, score) = when {
                anyFilePresent -> PATTERN_KSU_FILES_PRESENT to SCORE_KSU_FILES
                versionSet -> PATTERN_KSU_PROPERTY_SET to SCORE_KSU_PROPERTY
                else -> PATTERN_CLEAN to SCORE_CLEAN
            }

            val allFileChecksFailed =
                fileChecksAttempted > 0 && fileChecksThrew == fileChecksAttempted
            val confidence = if (allFileChecksFailed) CONFIDENCE_DEGRADED else CONFIDENCE_NORMAL

            val filesHitDisplay = if (filesPresent.isEmpty()) "none" else filesPresent.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = EV_FILES_HIT,
                    value = filesHitDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = EV_VERSION_PROP,
                    value = versionProp ?: "null",
                    expected = "null",
                ),
                Evidence(
                    key = EV_OBSERVATION_OK,
                    value = (!allFileChecksFailed).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = EV_PATTERN,
                    value = pattern,
                    expected = PATTERN_CLEAN,
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "KernelSURootProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

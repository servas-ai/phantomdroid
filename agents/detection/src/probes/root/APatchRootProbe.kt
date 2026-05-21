// inventory.yml rank 3.85 (code-rank 95), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.85 — root.apatch.
 *
 * Detects APatch — the kernel-patch-based root solution that takes
 * the kernel-space approach a step further than KernelSU by
 * applying patches at boot via a runtime `kpatch` mechanism (no
 * kernel rebuild required) — by scanning for its canonical runtime
 * artifacts under `/data/adb/ap/` and reading the optional
 * `ro.apatch.kernel_signature` system property. APatch is the
 * "patch-don't-replace" alternative to Magisk and KernelSU,
 * advertised as compatible with both Magisk modules AND KernelSU
 * modules; that compatibility means APatch's runtime root differs
 * from both — it lives under `/data/adb/ap/` rather than
 * `/data/adb/magisk` or `/data/adb/ksu*`.
 *
 * Reference: https://github.com/bmax121/APatch
 *
 * **Why distinct from SuDetectionProbe (rank 3) AND
 * KernelSURootProbe (rank 3.6)**: rank 3 covers Magisk and classic
 * SU binaries. Rank 3.6 covers KernelSU's `/data/adb/ksu*` paths.
 * APatch's `/data/adb/ap/` directory tree and the
 * `/data/adb/ap/bin/apd` daemon are NOT in either set —
 * an APatch-rooted device with no Magisk and no KernelSU install
 * would be invisible to both prior probes but is dispositively
 * flagged here.
 *
 * Signal classes (HIGH severity — `/data/adb/ap/` paths are APatch-owned
 * runtime state; no legitimate AOSP/OEM component writes to those
 * paths):
 *
 *   • **APatch files present (1.00)**: at least one of
 *     `/data/adb/ap` or `/data/adb/ap/bin/apd` exists. Dispositive
 *     of APatch presence — the canonical "I am rooted via runtime
 *     kernel patching" signature.
 *   • **APatch system property set (0.90)**: `ro.apatch.kernel_signature`
 *     returns a non-null, non-empty value. Strong but slightly
 *     lower than file presence because `getSystemProperty` can be
 *     hooked by Zygisk-compatible modules; the file-presence branch
 *     is the harder-to-spoof signal.
 *   • **Clean (0.00)**: no APatch file present AND no signature
 *     property set.
 *
 * Scoring (max wins; first-match cascade in severity order):
 *   1.00  PATTERN_APATCH_FILES_PRESENT
 *   0.90  PATTERN_APATCH_PROPERTY_SET
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  NORMAL    every `fileExists` call returned (success or
 *                   absence; the accessor itself did not throw)
 *   0.50  DEGRADED  every `fileExists` call threw — we couldn't
 *                   observe the filesystem at all
 *
 * **inventoryRank vs code-rank**: inventory 3.85 → code-rank 95
 * (next free slot above KernelSURootProbe's 94). Cross-cutting #7.
 *
 * **Cross-cutting #1 evidence-namespace**: probe-id is
 * `root.apatch` → evidence keys are prefixed `apatch.*` (never
 * bare-keyed). Closes cross-cutting #1 namespacing for APatch.
 *
 * **Cross-cutting #7 fractional rank**: `inventoryRank = 3.85` is
 * between `root.mount_ns_mismatch` (3.8) and `root.magisk_module_dir`
 * (3.9). The `Probe.rank: Int` interface uses code-rank 95.
 *
 * Reference: shared/probes/inventory.yml rank 3.85 (mitigation_layer
 * L4); https://github.com/bmax121/APatch.
 */
class APatchRootProbe : Probe {
    override val id = "root.apatch"
    override val rank = RANK
    override val inventoryRank = 3.85
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.85; Int slot 95 picked
         * (next free above KernelSURootProbe's 94). Cross-cutting #7.
         */
        const val RANK = 95

        /**
         * Canonical APatch runtime paths. Per the APatch README
         * (https://github.com/bmax121/APatch) and source tree:
         *   - `/data/adb/ap`         — APatch working directory root
         *   - `/data/adb/ap/bin/apd` — APatch daemon binary
         */
        val APATCH_PATHS: List<String> = listOf(
            "/data/adb/ap",
            "/data/adb/ap/bin/apd",
        )

        /**
         * APatch exposes its kernel signature via this system
         * property when the kpatch is active. Provides a secondary,
         * non-filesystem signal in case the runtime directory is
         * hidden or moved by a custom build.
         */
        const val APATCH_SIGNATURE_PROP = "ro.apatch.kernel_signature"

        const val PATTERN_APATCH_FILES_PRESENT = "apatch_files_present"
        const val PATTERN_APATCH_PROPERTY_SET = "apatch_property_set"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_APATCH_FILES = 1.0
        const val SCORE_APATCH_PROPERTY = 0.90
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val EV_FILES_HIT = "apatch.files_hit"
        const val EV_SIGNATURE_PROP = "apatch.signature_property"
        const val EV_PATTERN = "apatch.pattern"
        const val EV_OBSERVATION_OK = "apatch.observation_ok"

        const val METHOD =
            "Filesystem path scan for /data/adb/ap + /data/adb/ap/bin/apd " +
                "(APatch runtime artifacts) plus ro.apatch.kernel_signature " +
                "system property read. APatch is the runtime kernel-patch " +
                "root toolchain (https://github.com/bmax121/APatch) — " +
                "parallel to and not covered by rank-3 SuDetectionProbe " +
                "(Magisk + classic SU) or rank-3.6 KernelSURootProbe (KSU)."
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            var fileChecksAttempted = 0
            var fileChecksThrew = 0
            val filesPresent = mutableListOf<String>()

            for (path in APATCH_PATHS) {
                fileChecksAttempted++
                val present = try {
                    ctx.fileExists(path)
                } catch (_: Throwable) {
                    fileChecksThrew++
                    false
                }
                if (present) filesPresent.add(path)
            }

            val signatureProp: String? = try {
                ctx.getSystemProperty(APATCH_SIGNATURE_PROP)?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            }

            val anyFilePresent = filesPresent.isNotEmpty()
            val signatureSet = signatureProp != null

            val (pattern, score) = when {
                anyFilePresent -> PATTERN_APATCH_FILES_PRESENT to SCORE_APATCH_FILES
                signatureSet -> PATTERN_APATCH_PROPERTY_SET to SCORE_APATCH_PROPERTY
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
                    key = EV_SIGNATURE_PROP,
                    value = signatureProp ?: "null",
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
                "APatchRootProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

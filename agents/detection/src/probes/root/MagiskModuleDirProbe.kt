// inventory.yml rank 3.9 (code-rank 92), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.9 — root.magisk_module_dir.
 *
 * Enumerates `/data/adb/modules/` for installed Magisk modules.
 * The DIRECTORY existence itself (regardless of contents) is the
 * dispositive Magisk-present signal: a clean Android device has
 * no `/data/adb` directory at all, and even a Magisk-with-DenyList
 * configuration cannot hide `/data/adb` from a process that has
 * permission to read it because the path is the Magisk runtime
 * root.
 *
 * Three signal classes:
 *
 *   • **Modules directory non-empty (1.00)**: at least one
 *     subdirectory exists under `/data/adb/modules/`. Each
 *     subdirectory is a Magisk module — Lucky Patcher's
 *     ZygiskNext, MagiskHidePropsConfig, Universal Safetynet
 *     Fix, etc. Dispositive of an active Magisk install with
 *     modules.
 *   • **Modules directory empty (0.95)**: the directory exists
 *     but contains no entries. Still dispositive of Magisk
 *     presence because `/data/adb/modules` is Magisk-owned —
 *     a clean device wouldn't have the directory at all. Slightly
 *     lower than 1.00 because the consumer-side signal "Magisk
 *     installed without modules" is less actionable than "Magisk
 *     installed with active modifications".
 *   • **No observation (0.0)**: `queryDirEntries` returned null —
 *     the directory doesn't exist OR the calling process can't
 *     read it. On a clean device this returns null naturally
 *     (the directory doesn't exist). On a hardened-Magisk
 *     setup (Shamiko + DenyList + isolated_process), the
 *     accessor returns null because Magisk's hide hook
 *     intercepts the openat(`/data/adb`) call. Confidence
 *     DEGRADED to reflect the partial-observation honesty —
 *     this state is consistent with BOTH a clean device AND a
 *     well-hidden Magisk install.
 *
 * **Why distinct from rank-3 SuDetectionProbe**: rank-3 scans
 * specific Magisk paths like `/data/adb/magisk` and
 * `/sbin/.magisk/busybox` via `fileExists`. This probe ENUMERATES
 * a directory rather than checking specific paths, which catches
 * the "Magisk installed with custom path layout" case rank-3
 * misses. The two probes are complementary surfaces.
 *
 * Uses ONLY the new [ProbeContext.queryDirEntries] accessor —
 * Power-13 Gap #8 introduces it (default returns null for
 * backward compat).
 *
 * Scoring (max wins):
 *   1.00  PATTERN_MODULES_PRESENT  (≥ 1 module subdirectory)
 *   0.95  PATTERN_DIR_EMPTY        (directory exists but empty)
 *   0.00  PATTERN_NO_OBSERVATION   (null = clean OR hidden)
 *
 * Confidence:
 *   0.95  NORMAL    queryDirEntries returned non-null
 *   0.50  DEGRADED  queryDirEntries returned null
 *
 * **inventoryRank vs code-rank**: inventory 3.9 → code-rank 92
 * (next free slot above Gap #2's 91). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 3.9 (mitigation_layer
 * L4); audit/spoof-stack/real-world-detectors.md row "Magisk
 * module directory enumeration"; audit/spoof-stack/real-world-gap-
 * list.md Gap #8. Origin: Momo / canyie/Riru-MomoHider target
 * surface.
 */
class MagiskModuleDirProbe : Probe {
    override val id = "root.magisk_module_dir"
    override val rank = RANK
    override val inventoryRank = 3.9
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.9; Int slot 92 picked
         * (next free above Gap #2's 91). Cross-cutting #7.
         */
        const val RANK = 92

        const val MAGISK_MODULES_PATH = "/data/adb/modules"

        const val PATTERN_MODULES_PRESENT = "modules_present"
        const val PATTERN_DIR_EMPTY = "dir_empty"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_MODULES_PRESENT = 1.0
        const val SCORE_DIR_EMPTY = 0.95
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Enumerate /data/adb/modules/ directory entries via " +
                "queryDirEntries. Directory existence is dispositive " +
                "of Magisk presence — modules listed at score 1.00, " +
                "empty directory at 0.95, no observation at 0.0 " +
                "(consistent with clean device OR well-hidden Magisk). " +
                "Power-13 Gap #8."
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val entries: List<String>? = try {
                ctx.queryDirEntries(MAGISK_MODULES_PATH)
            } catch (_: Throwable) {
                null
            }

            val (pattern, score, confidence) = when {
                entries == null ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
                entries.isEmpty() ->
                    Triple(PATTERN_DIR_EMPTY, SCORE_DIR_EMPTY, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_MODULES_PRESENT, SCORE_MODULES_PRESENT, CONFIDENCE_NORMAL)
            }

            val entryCount = entries?.size ?: 0
            val entrySample = entries
                ?.sorted()
                ?.take(10)
                ?.joinToString(",")
                ?.ifEmpty { "<empty>" }
                ?: "<no observation>"

            val evidence = listOf(
                Evidence(
                    key = "magisk_module_dir.path_observable",
                    value = (entries != null).toString(),
                    // expected = null because both true (clean device's
                    // accessor returns null — observable=false — AND
                    // dispositive Magisk-present devices return non-null)
                    // and false are legitimate states.
                    expected = null,
                ),
                Evidence(
                    key = "magisk_module_dir.entry_count",
                    value = entryCount.toString(),
                    expected = "0",
                ),
                Evidence(
                    key = "magisk_module_dir.entries_sample",
                    value = entrySample,
                    expected = "<no observation>",
                ),
                Evidence(
                    key = "magisk_module_dir.pattern",
                    value = pattern,
                    expected = PATTERN_NO_OBSERVATION,
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
                "MagiskModuleDirProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 14.7 (code-rank 89), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #14.7 — root.overlayfs_present.
 *
 * Parses `/proc/self/mountinfo` for an `overlay` filesystem type
 * mounted at `/system` or at the root partition (`/`). Momo
 * flags this as a Magisk-on-Android-11+ tell: since Android 11
 * Magisk has used overlayfs (instead of legacy bind-mounts) to
 * install its module overlays on top of /system. The overlay
 * filesystem type at the system partition is a near-dispositive
 * Magisk-installed signal.
 *
 * Same FS detection surface also catches:
 *   - Redroid Docker containers (whose rootfs IS Docker's overlay2
 *     storage driver, manifesting as `overlay` mounts)
 *   - Custom-recovery flows that flash via overlayfs
 *   - Any legitimate Android system that uses overlayfs for /system
 *     (rare on consumer devices but documented for some OEM
 *     debugging builds)
 *
 * The FP class is therefore non-empty. Severity HIGH but not
 * CRITICAL because legitimate overlay-on-/system cases exist (just
 * not on production consumer phones). A consumer-side aggregator
 * combining this signal with rank-3 su_search or rank-10 magisk
 * manager presence gets dispositive evidence; standalone the
 * signal is strong-but-not-final.
 *
 * Reads via `ProbeContext.queryMountInfo("self")` — same accessor
 * introduced by Power-13 Gap #3 [MountNsMismatchProbe] and reused
 * by Gap #10 [SystemRwMountProbe].
 *
 * mountinfo format (kernel-stable since 2.6.26):
 *   mount-id parent-id major:minor root mount-point opts-1 - fs-type source super-opts
 *
 * The fs-type appears AFTER the `-` separator. Find the position
 * of `-` per line, take fs-type from one position later. Same
 * parsing approach used by Linux mount-info tooling.
 *
 * Signal classes:
 *
 *   • **`overlay` on `/system` (0.85)**: a mountinfo entry has
 *     mount-point `/system` AND fs-type `overlay`. The
 *     canonical Magisk-on-A11+ signature.
 *   • **`overlay` on `/` (system-as-root) (0.85)**: same rule
 *     when system-as-root is in effect — root partition is
 *     overlayfs.
 *   • **Neither (0.0)**: clean state. ext4 / f2fs / squashfs at
 *     /system are the production filesystem types.
 *   • **No observation (0.0)**: queryMountInfo returned null.
 *     Confidence DEGRADED.
 *
 * Scoring (max wins; first-match cascade — overlay on /system
 * wins on tie if both /system and / entries exist):
 *   0.85  PATTERN_OVERLAY_ON_SYSTEM
 *   0.85  PATTERN_OVERLAY_ON_ROOT_SAR
 *   0.00  PATTERN_CLEAN
 *   0.00  PATTERN_NO_OBSERVATION
 *
 * Confidence:
 *   0.95  NORMAL    mountinfo readable
 *   0.50  DEGRADED  mountinfo null
 *
 * **inventoryRank vs code-rank**: inventory 14.7 → code-rank 89
 * (next free slot above Gap #10's 88). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 14.7 (mitigation_layer
 * L4); audit/spoof-stack/real-world-detectors.md row "OverlayFS
 * detection (`/proc/mounts` lists `overlay` over `/system`)";
 * audit/spoof-stack/real-world-gap-list.md Gap #12. Origin: Momo
 * target surface (HuskyDG blog reference).
 */
class OverlayFsPresentProbe : Probe {
    override val id = "root.overlayfs_present"
    override val rank = RANK
    override val inventoryRank = 14.7
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 150L

    companion object {
        /**
         * Code rank. Inventory lists rank 14.7; Int slot 89 picked
         * (next free above Gap #10's 88). Cross-cutting #7.
         */
        const val RANK = 89

        const val PID_SELF = "self"
        const val MOUNTPOINT_SYSTEM = "/system"
        const val MOUNTPOINT_ROOT = "/"
        const val FS_TYPE_OVERLAY = "overlay"

        const val PATTERN_OVERLAY_ON_SYSTEM = "overlay_on_system"
        const val PATTERN_OVERLAY_ON_ROOT_SAR = "overlay_on_root_sar"
        const val PATTERN_CLEAN = "clean"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_OVERLAY_PRESENT = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Parse /proc/self/mountinfo for `overlay` filesystem type " +
                "mounted at /system or at / (system-as-root). Magisk-on-" +
                "Android 11+ tell — Momo target surface (Power-13 Gap #12)."

        /**
         * Extract the filesystem type for the given [mountPoint] from
         * the mountinfo content. Returns null when no entry matches or
         * when content is null/empty.
         *
         * mountinfo line shape (kernel-stable):
         *   <mount-id> <parent-id> <major:minor> <root> <mount-point> <opts-1> - <fs-type> <source> <super-opts>
         *
         * The `-` separator is the canonical pivot; the fs-type lives
         * one position AFTER it. We find the `-` field index and read
         * the next field.
         */
        internal fun findFsType(mountInfo: String?, mountPoint: String): String? {
            if (mountInfo.isNullOrEmpty()) return null
            for (rawLine in mountInfo.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 6) continue
                if (parts[4] != mountPoint) continue
                val dashIdx = parts.indexOf("-")
                if (dashIdx < 0 || dashIdx + 1 >= parts.size) continue
                return parts[dashIdx + 1]
            }
            return null
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val selfInfo: String? = try {
                ctx.queryMountInfo(PID_SELF)
            } catch (_: Throwable) {
                null
            }

            val systemFsType = findFsType(selfInfo, MOUNTPOINT_SYSTEM)
            val rootFsType = findFsType(selfInfo, MOUNTPOINT_ROOT)

            val overlayOnSystem = systemFsType == FS_TYPE_OVERLAY
            val overlayOnRoot = rootFsType == FS_TYPE_OVERLAY

            val (pattern, score, confidence) = when {
                selfInfo == null ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
                overlayOnSystem ->
                    Triple(PATTERN_OVERLAY_ON_SYSTEM, SCORE_OVERLAY_PRESENT, CONFIDENCE_NORMAL)
                overlayOnRoot ->
                    Triple(PATTERN_OVERLAY_ON_ROOT_SAR, SCORE_OVERLAY_PRESENT, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_CLEAN, SCORE_CLEAN, CONFIDENCE_NORMAL)
            }

            val evidence = listOf(
                Evidence(
                    key = "overlayfs.mountinfo_readable",
                    value = (selfInfo != null).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "overlayfs.system_fs_type",
                    value = systemFsType ?: "<no entry>",
                    expected = "ext4|f2fs|squashfs (NOT overlay)",
                ),
                Evidence(
                    key = "overlayfs.root_fs_type",
                    value = rootFsType ?: "<no entry>",
                    expected = "ext4|f2fs|squashfs (NOT overlay)",
                ),
                Evidence(
                    key = "overlayfs.overlay_on_system",
                    value = overlayOnSystem.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "overlayfs.overlay_on_root",
                    value = overlayOnRoot.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "overlayfs.pattern",
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
                "OverlayFsPresentProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

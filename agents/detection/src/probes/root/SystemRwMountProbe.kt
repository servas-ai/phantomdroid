// inventory.yml rank 14.5 (code-rank 88), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #14.5 — root.system_rw_mount.
 *
 * Parses `/proc/self/mountinfo` for the `/system` partition's
 * read/write status. RootBeer's `checkForRWPaths()` flags
 * production Android as `/system mounted ro`; any RW mount is a
 * dispositive root-prep signal. Modern systemless root (Magisk) does
 * NOT remount /system rw — it uses overlayfs or `mount --bind` from
 * `/data/adb/modules` — but legacy `system-mode-root` Magisk does,
 * and many custom recovery flows leave /system rw after flashing.
 *
 * Android 10+ system-as-root caveat: when device boots with
 * `system-as-root=true`, the `/system` partition is mounted as `/`
 * — there is NO standalone `/system` mountinfo entry. In that case
 * we check the ROOT (`/`) entry's rw flag instead. Both surfaces
 * count toward the same probe rule because both expose the same
 * underlying filesystem.
 *
 * Reads via `ProbeContext.queryMountInfo("self")` — same accessor
 * introduced by Power-13 Gap #3 [MountNsMismatchProbe]. Reuse
 * (cross-rank infrastructure sharing) reduces ProbeContext surface
 * by one method while keeping the per-probe logic disjoint.
 *
 * Signal classes:
 *
 *   • **`/system` mounted rw (0.85)**: a standalone mountinfo entry
 *     for `/system` has `rw` in the super-options. Dispositive for
 *     RootBeer-class detectors. Severity HIGH because the FP class
 *     is narrow (legitimate `/system` rw mounts only happen on
 *     userdebug/eng builds and during system-app installs, both
 *     non-production states).
 *   • **`/` mounted rw on system-as-root (0.85)**: same rule when
 *     `/system` has no standalone entry but the root entry has
 *     `rw`. Modern Android (A10+) system-as-root setups fold the
 *     system partition into `/`, so this is the canonical surface
 *     on those devices.
 *   • **`/system` mounted ro (0.0)**: the production state. Score
 *     zero.
 *   • **`/` mounted ro AND no `/system` entry (0.0)**: system-as-
 *     root production state — root is ro, system is ro by transitive
 *     mount. Score zero.
 *   • **No observation (0.0)**: `queryMountInfo("self")` returned
 *     null. Confidence drops to 0.50.
 *   • **Mountinfo present but no `/system` or `/` line (0.0)**:
 *     malformed or empty content — score zero, confidence partial
 *     (0.60) reflecting the observation-was-attempted-but-yielded-
 *     no-data state.
 *
 * Cascade: rw-on-/system fires FIRST; rw-on-root fires only if no
 * `/system` line was found (system-as-root case). This avoids the
 * "both rules fire" ambiguity for transitional devices.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.85  PATTERN_SYSTEM_RW           — /system entry has rw
 *   0.85  PATTERN_ROOT_RW_SAR         — /system absent, root has rw
 *   0.00  PATTERN_SYSTEM_RO           — /system entry has ro
 *   0.00  PATTERN_ROOT_RO_SAR         — /system absent, root has ro
 *   0.00  PATTERN_NO_SYSTEM_OR_ROOT   — neither entry observed
 *   0.00  PATTERN_NO_OBSERVATION      — mountinfo returned null
 *
 * Confidence:
 *   0.95  NORMAL    mountinfo observed + at least one of /system or
 *                    / lines parsed cleanly
 *   0.60  PARTIAL   mountinfo observed but no system/root entry
 *                    found
 *   0.50  DEGRADED  mountinfo null (no observation)
 *
 * **inventoryRank vs code-rank**: inventory 14.5 → code-rank 88
 * (next free slot above Gap #3's 87). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 14.5 (mitigation_layer
 * L4); audit/spoof-stack/real-world-detectors.md row "RW mount
 * points"; audit/spoof-stack/real-world-gap-list.md Gap #10.
 * Origin: scottyab/rootbeer `checkForRWPaths()`.
 */
class SystemRwMountProbe : Probe {
    override val id = "root.system_rw_mount"
    override val rank = RANK
    override val inventoryRank = 14.5
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 150L

    companion object {
        /**
         * Code rank. Inventory lists rank 14.5; Int slot 88 picked
         * (next free above Gap #3's 87). Cross-cutting #7.
         */
        const val RANK = 88

        const val PID_SELF = "self"
        const val MOUNTPOINT_SYSTEM = "/system"
        const val MOUNTPOINT_ROOT = "/"

        const val PATTERN_SYSTEM_RW = "system_rw"
        const val PATTERN_ROOT_RW_SAR = "root_rw_sar"
        const val PATTERN_SYSTEM_RO = "system_ro"
        const val PATTERN_ROOT_RO_SAR = "root_ro_sar"
        const val PATTERN_NO_SYSTEM_OR_ROOT = "no_system_or_root"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_RW_MOUNT = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Parse /proc/self/mountinfo for /system partition rw status " +
                "(or / partition rw status on Android 10+ system-as-root " +
                "devices) — RootBeer checkForRWPaths() (Power-13 Gap #10)."

        /**
         * Mount status for a single mountinfo entry. `rw` true means
         * the super-options field starts with `rw`. `null` here means
         * the entry was not found in the mountinfo content.
         */
        internal data class MountStatus(val mountPoint: String, val rw: Boolean)

        /**
         * Find the mountinfo entry for [mountPoint] (exact-match) and
         * return its rw/ro flag. Returns null when no entry matches.
         *
         * mountinfo format (kernel-stable since 2.6.26):
         *   mount-id parent-id major:minor root mount-point options-1 - fs-type source super-options
         *
         * Field 5 (0-indexed: `parts[4]`) is the mount-point. The
         * options-1 field (parts[5]) starts with `ro` or `rw` —
         * that's the per-mount rw status we score on.
         */
        internal fun findMountStatus(
            mountInfo: String?,
            mountPoint: String,
        ): MountStatus? {
            if (mountInfo.isNullOrEmpty()) return null
            for (rawLine in mountInfo.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 6) continue
                if (parts[4] != mountPoint) continue
                val options = parts[5]
                val rw = options.startsWith("rw")
                return MountStatus(mountPoint = mountPoint, rw = rw)
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

            val systemStatus = findMountStatus(selfInfo, MOUNTPOINT_SYSTEM)
            val rootStatus = findMountStatus(selfInfo, MOUNTPOINT_ROOT)

            val (pattern, score, confidence) = when {
                selfInfo == null ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
                systemStatus != null && systemStatus.rw ->
                    Triple(PATTERN_SYSTEM_RW, SCORE_RW_MOUNT, CONFIDENCE_NORMAL)
                systemStatus != null && !systemStatus.rw ->
                    Triple(PATTERN_SYSTEM_RO, SCORE_CLEAN, CONFIDENCE_NORMAL)
                rootStatus != null && rootStatus.rw ->
                    // system-as-root + rw root = system rw via transitive
                    // mount. RootBeer dispositive.
                    Triple(PATTERN_ROOT_RW_SAR, SCORE_RW_MOUNT, CONFIDENCE_NORMAL)
                rootStatus != null && !rootStatus.rw ->
                    Triple(PATTERN_ROOT_RO_SAR, SCORE_CLEAN, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_NO_SYSTEM_OR_ROOT, SCORE_CLEAN, CONFIDENCE_PARTIAL)
            }

            val evidence = listOf(
                Evidence(
                    key = "system_rw_mount.mountinfo_readable",
                    value = (selfInfo != null).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "system_rw_mount.system_entry_found",
                    value = (systemStatus != null).toString(),
                    // expected = null because both true (typical Android
                    // <10) and false (Android 10+ system-as-root) are
                    // legitimate states.
                    expected = null,
                ),
                Evidence(
                    key = "system_rw_mount.system_rw",
                    value = (systemStatus?.rw ?: false).toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "system_rw_mount.root_entry_found",
                    value = (rootStatus != null).toString(),
                    expected = null,
                ),
                Evidence(
                    key = "system_rw_mount.root_rw",
                    value = (rootStatus?.rw ?: false).toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "system_rw_mount.pattern",
                    value = pattern,
                    // Two production states exist (system_ro vs root_ro_sar
                    // for system-as-root devices); leave expected unset so
                    // either passes audit.
                    expected = null,
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
                "SystemRwMountProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

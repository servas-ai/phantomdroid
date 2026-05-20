// inventory.yml rank 3.8 (code-rank 87), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.8 — root.mount_ns_mismatch.
 *
 * Detects Magisk's mount-namespace isolation by comparing
 * `/proc/self/mountinfo` against `/proc/1/mountinfo`. HuskyDG
 * documents this as the **#1 Momo signal**: Magisk uses
 * `mount --bind` to install its module overlay (and to hide its
 * own metadata) by ENTERING the init mount namespace AND THEN
 * making per-app fork-bombs that selectively unmount the Magisk
 * paths in the child namespace. The effect: `/proc/1/mountinfo`
 * shows the Magisk mounts; `/proc/self/mountinfo` does NOT —
 * even though both processes are otherwise "rooted" in the same
 * filesystem.
 *
 * **Why a focused-discriminator probe**: a naive "do the digests
 * differ?" check is useless on real Android because zygote
 * always forks app processes into a per-app mount namespace
 * (storage isolation, user-data isolation, sandbox boundaries),
 * so digests ALWAYS differ between `self` and `1` on every real
 * device. The Magisk discriminator is more specific: **magisk-
 * fingerprint substrings present in `/proc/1/mountinfo` but
 * ABSENT from `/proc/self/mountinfo`**. That's the structural
 * signature of "init has Magisk mounts; my app doesn't" — i.e.
 * Magisk is actively hiding from this process.
 *
 * Magisk-fingerprint substrings the probe looks for:
 *   - `/sbin/.magisk`   — Magisk-init runtime root (pre-A11)
 *   - `/data/adb`       — Magisk modules / data root (all versions)
 *   - `.magisk`         — generic Magisk metadata path
 *   - `magisk_tmp`      — Magisk's temp filesystem
 *   - `/sbin/magisk`    — Magisk binary mount-bind
 *
 * Signal classes (HIGH severity because the discriminator is
 * dispositive when it fires — but observability requires reading
 * `/proc/1/mountinfo` which is permission-gated on recent
 * Android; the conservative null-answer is correct):
 *
 *   • **Magisk fingerprint asymmetry (0.95)**: at least one
 *     magisk-fingerprint substring appears in `mountinfo("1")`
 *     but NOT in `mountinfo("self")`. Dispositive of mount-
 *     namespace hiding.
 *   • **Magisk in self only (0.85)**: at least one substring
 *     appears in `mountinfo("self")` but NOT in `mountinfo("1")`.
 *     Less canonical — typically a Magisk-without-DenyList state
 *     where the app sees its own bind but init doesn't (rare
 *     configuration but documented). The score is lower because
 *     this is the "haven't bothered to hide" case rather than
 *     active hiding.
 *   • **Magisk in both (0.70)**: at least one substring appears
 *     in BOTH `self` and `1`. Magisk is installed without
 *     namespace isolation — easier to detect on other surfaces
 *     (rank-3 su_search, rank-10 magisk manager) but still a
 *     positive signal here for cross-rank corroboration.
 *   • **Digests differ, no magisk fingerprint (0.0)**: the
 *     normal Android case (zygote isolation). Score zero — the
 *     mismatch is legitimate and tells us nothing about Magisk.
 *   • **Digests match (0.0)**: zygote isolation did not kick in
 *     (probe running OUTSIDE an app process) or both mountinfos
 *     are empty. Score zero.
 *   • **No observation (0.0)**: at least one mountinfo is null
 *     (permission denied or accessor unavailable). Confidence
 *     drops to 0.50 to reflect the partial-observation honesty.
 *
 * Uses ONLY the new [ProbeContext.queryMountInfo] accessor — no
 * other state needed. Power-13 Gap #3 introduces the accessor
 * (default returns null for backward compat); production wrappers
 * read `/proc/<pid>/mountinfo`. Snapshots populate
 * `mountInfo[pid]` per DeviceSnapshot KDoc.
 *
 * Scoring (max wins; first-match cascade in severity order):
 *   0.95  PATTERN_MAGISK_IN_INIT_ONLY (the canonical Momo signal)
 *   0.85  PATTERN_MAGISK_IN_SELF_ONLY
 *   0.70  PATTERN_MAGISK_IN_BOTH
 *   0.00  PATTERN_DIGEST_DIFF_NO_MAGISK
 *   0.00  PATTERN_DIGEST_MATCH
 *   0.00  PATTERN_NO_OBSERVATION
 *
 * Confidence:
 *   0.95  Both `mountinfo("self")` AND `mountinfo("1")` returned
 *         non-null
 *   0.60  Exactly one returned non-null (partial observation)
 *   0.30  Both returned null (no observation — the typical
 *         consumer-app outcome on hardened Android)
 *
 * **inventoryRank vs code-rank**: inventory 3.8 → code-rank 87
 * (next available slot above Gap #4's 86). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 3.8 (mitigation_layer L4);
 * audit/spoof-stack/real-world-detectors.md row "Mount namespace
 * mismatch (zygote namespace differs from app's because of Magisk's
 * `mount --bind` magic)"; audit/spoof-stack/real-world-gap-list.md
 * Gap #3. Origin: HuskyDG blog `detect_magisk_xposed` (the canonical
 * write-up of the Momo signal).
 */
class MountNsMismatchProbe : Probe {
    override val id = "root.mount_ns_mismatch"
    override val rank = RANK
    override val inventoryRank = 3.8
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 200L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.8; Int slot 87 picked
         * (next free above the existing 80-86 fractional-rank
         * cluster). Cross-cutting #7.
         */
        const val RANK = 87

        /**
         * PID tokens passed to `ProbeContext.queryMountInfo`. `"self"`
         * is the kernel-recognised alias for the calling process;
         * `"1"` is init (always PID 1 on Android).
         */
        const val PID_SELF = "self"
        const val PID_INIT = "1"

        /**
         * Substrings whose presence in a `/proc/<pid>/mountinfo`
         * dump indicates Magisk has applied mount-bind operations
         * visible to that PID. Lowercase prefix-match; the kernel
         * mountinfo format places the mount path verbatim, so a
         * simple `contains` is sufficient and resilient against
         * format minor-version variations.
         */
        val MAGISK_FINGERPRINT_SUBSTRINGS: List<String> = listOf(
            "/sbin/.magisk",
            "/data/adb",
            ".magisk",
            "magisk_tmp",
            "/sbin/magisk",
        )

        const val PATTERN_MAGISK_IN_INIT_ONLY = "magisk_in_init_only"
        const val PATTERN_MAGISK_IN_SELF_ONLY = "magisk_in_self_only"
        const val PATTERN_MAGISK_IN_BOTH = "magisk_in_both"
        const val PATTERN_DIGEST_DIFF_NO_MAGISK = "digest_diff_no_magisk"
        const val PATTERN_DIGEST_MATCH = "digest_match"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_MAGISK_IN_INIT_ONLY = 0.95
        const val SCORE_MAGISK_IN_SELF_ONLY = 0.85
        const val SCORE_MAGISK_IN_BOTH = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Compare /proc/self/mountinfo vs /proc/1/mountinfo for " +
                "Magisk-fingerprint substring asymmetry (HuskyDG #1 " +
                "Momo signal — Power-13 Gap #3)."

        /**
         * Find which magisk-fingerprint substrings appear in [content].
         * Returns empty list when [content] is null or contains no
         * fingerprint. Substring match is case-sensitive — kernel
         * mountinfo paths are case-preserved verbatim from the mount
         * call site.
         */
        internal fun findMagiskFingerprints(content: String?): List<String> {
            if (content.isNullOrEmpty()) return emptyList()
            return MAGISK_FINGERPRINT_SUBSTRINGS.filter { it in content }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-pid try/catch so a throwing accessor doesn't
            // suppress observation of the other.
            val selfInfo: String? = try {
                ctx.queryMountInfo(PID_SELF)
            } catch (_: Throwable) {
                null
            }
            val initInfo: String? = try {
                ctx.queryMountInfo(PID_INIT)
            } catch (_: Throwable) {
                null
            }

            val selfFingerprints = findMagiskFingerprints(selfInfo)
            val initFingerprints = findMagiskFingerprints(initInfo)
            val magiskInSelf = selfFingerprints.isNotEmpty()
            val magiskInInit = initFingerprints.isNotEmpty()

            // Digests compared verbatim — same content => same digest.
            // null vs null is "match" only in the no-observation
            // pattern below; we handle the null case separately to
            // distinguish "couldn't observe" from "observed match".
            val bothObservable = selfInfo != null && initInfo != null
            val digestsMatch = bothObservable && selfInfo == initInfo

            val (pattern, score) = when {
                !bothObservable ->
                    PATTERN_NO_OBSERVATION to SCORE_CLEAN
                magiskInInit && !magiskInSelf ->
                    PATTERN_MAGISK_IN_INIT_ONLY to SCORE_MAGISK_IN_INIT_ONLY
                !magiskInInit && magiskInSelf ->
                    PATTERN_MAGISK_IN_SELF_ONLY to SCORE_MAGISK_IN_SELF_ONLY
                magiskInInit && magiskInSelf ->
                    PATTERN_MAGISK_IN_BOTH to SCORE_MAGISK_IN_BOTH
                digestsMatch ->
                    PATTERN_DIGEST_MATCH to SCORE_CLEAN
                else ->
                    PATTERN_DIGEST_DIFF_NO_MAGISK to SCORE_CLEAN
            }

            val confidence = when {
                selfInfo != null && initInfo != null -> CONFIDENCE_FULL
                selfInfo != null || initInfo != null -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            fun listOrNone(items: List<String>): String =
                if (items.isEmpty()) "none" else items.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "mount_ns.self_readable",
                    value = (selfInfo != null).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "mount_ns.init_readable",
                    value = (initInfo != null).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "mount_ns.digests_match",
                    value = digestsMatch.toString(),
                    // expected = null — both true (probe outside app
                    // process, no zygote isolation) and false (normal
                    // app process) are legitimate; only the
                    // magisk-fingerprint axis is dispositive.
                    expected = null,
                ),
                Evidence(
                    key = "mount_ns.magisk_in_self",
                    value = listOrNone(selfFingerprints),
                    expected = "none",
                ),
                Evidence(
                    key = "mount_ns.magisk_in_init",
                    value = listOrNone(initFingerprints),
                    expected = "none",
                ),
                Evidence(
                    key = "mount_ns.pattern",
                    value = pattern,
                    expected = PATTERN_DIGEST_MATCH,
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
                "MountNsMismatchProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 9.5 (code-rank 93), mitigation_layer L1
package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #9.5 — buildprop.fingerprint_cross_partition.
 *
 * Compares `ro.build.fingerprint` (the system-partition fingerprint
 * exposed by `Build.FINGERPRINT`) against `ro.vendor.build.fingerprint`
 * (the vendor-partition fingerprint, exposed by `Build.VENDOR.FINGERPRINT`
 * since API 26). On a factory-clean Android device the two values
 * MUST share the manufacturer/brand/device prefix and the
 * build-id suffix — both partitions are signed by the same OEM
 * release pipeline.
 *
 * **MagiskHidePropsConfig (MHPC) tell**: MHPC's default prop sets
 * spoof the system-partition fingerprint (`ro.build.fingerprint` +
 * neighbour `ro.product.*` props) to match a Google-certified
 * reference fingerprint (typically the user's own Pixel model)
 * but DO NOT touch the vendor-partition fingerprint. The
 * result is a divergence between system and vendor fingerprints
 * that no genuine OEM build would have. Momo's MHPC detection
 * reads both properties and compares prefixes.
 *
 * Signal classes (CRITICAL because the divergence is structurally
 * impossible on a factory-clean device — both partitions are
 * cryptographically tied to the same release manifest):
 *
 *   • **System spoofed, vendor untouched (1.00)**: vendor
 *     fingerprint prefix does NOT match system fingerprint prefix.
 *     The canonical MHPC signature. Dispositive.
 *   • **Vendor absent but system present (0.85)**: system
 *     fingerprint readable, vendor fingerprint null/empty. Could
 *     be a legacy device (pre-A8 vendor-partition split) or
 *     could be MHPC's vendor-erasure variant. Weaker signal
 *     because the vendor-partition split was introduced in
 *     Android 8 (API 26) — pre-Treble devices legitimately
 *     have no vendor fingerprint.
 *   • **Both clean and matching (0.0)**: both readable + prefix
 *     match. Production state.
 *   • **No observation (0.0)**: both null. Confidence DEGRADED.
 *
 * Cascade ordering: divergence (1.00) fires FIRST so vendor-
 * absent (0.85) only fires when no divergence is detectable.
 * Production-clean (0.0) fires when both are present and
 * compatible.
 *
 * **Prefix-match contract**: compares the first 3 dotted
 * fingerprint segments (manufacturer/product/device), which
 * is the OEM-pipeline-shared portion. Suffix differences
 * (build-id, version-incremental) are EXPECTED to differ
 * because vendor and system partitions can be updated
 * independently — only the prefix is OEM-tied.
 *
 * Example legitimate match (Pixel 7, factory-clean):
 *   system: `google/panther/panther:13/TQ3A.230805.001/...`
 *   vendor: `google/panther/panther:13/TQ3A.230805.001.A4/...`
 *   First 3 segments match (`google`, `panther`, `panther:13`).
 *
 * Example MHPC tell:
 *   system: `google/panther/panther:13/TQ3A.230805.001/...`
 *           (spoofed Pixel 7)
 *   vendor: `redroid/redroid_x86_64/redroid:12/SP1A.../...`
 *           (real underlying Redroid x86_64)
 *   First 3 segments differ → divergence rule fires.
 *
 * Scoring (max wins; cascade order):
 *   1.00  PATTERN_DIVERGENT_FINGERPRINTS
 *   0.85  PATTERN_VENDOR_ABSENT
 *   0.00  PATTERN_PARTITIONS_CONSISTENT
 *   0.00  PATTERN_NO_OBSERVATION
 *
 * Confidence:
 *   0.95  NORMAL    system fingerprint readable
 *   0.50  DEGRADED  system fingerprint null
 *
 * Uses ONLY the base [ProbeContext] (`getSystemProperty`) — no
 * new core-contract method.
 *
 * **inventoryRank vs code-rank**: inventory 9.5 → code-rank 93
 * (next free slot above Gap #8's 92). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 9.5 (mitigation_layer
 * L1); audit/spoof-stack/real-world-detectors.md row
 * "MagiskHidePropsConfig module side-effects"; audit/spoof-
 * stack/real-world-gap-list.md Gap #9. Origin: Momo + HuskyDG
 * blog discussion of MHPC's vendor-fingerprint blind spot.
 */
class FingerprintCrossPartitionProbe : Probe {
    override val id = "buildprop.fingerprint_cross_partition"
    override val rank = RANK
    override val inventoryRank = 9.5
    override val category = ProbeCategory.BUILDPROP
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists rank 9.5; Int slot 93 picked
         * (next free above Gap #8's 92). Cross-cutting #7.
         */
        const val RANK = 93

        const val PROP_SYSTEM_FINGERPRINT = "ro.build.fingerprint"
        const val PROP_VENDOR_FINGERPRINT = "ro.vendor.build.fingerprint"

        /**
         * Number of dotted segments to compare for the prefix-match
         * rule. 3 matches the manufacturer/product/device portion
         * which is the OEM-pipeline-tied prefix. The full
         * fingerprint typically has 6-7 segments separated by `/`
         * and `:`, but only the first 3 are partition-cross-
         * invariant.
         */
        const val PREFIX_SEGMENTS = 3

        const val PATTERN_DIVERGENT_FINGERPRINTS = "divergent_fingerprints"
        const val PATTERN_VENDOR_ABSENT = "vendor_absent"
        const val PATTERN_PARTITIONS_CONSISTENT = "partitions_consistent"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_DIVERGENT = 1.0
        const val SCORE_VENDOR_ABSENT = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Compare ro.build.fingerprint (system partition) vs " +
                "ro.vendor.build.fingerprint (vendor partition) for " +
                "MagiskHidePropsConfig divergence — system spoof " +
                "without vendor erasure is dispositive. Power-13 Gap #9."

        /**
         * Extract the first [PREFIX_SEGMENTS] segments of a
         * fingerprint string, splitting on the `/` and `:`
         * delimiters that AOSP uses to separate fingerprint
         * fields. Returns null when the input is null or empty.
         *
         * Example: `"google/panther/panther:13/TQ3A.../..."` with
         * PREFIX_SEGMENTS=3 returns `"google/panther/panther"` (the
         * pre-version manufacturer/product/device tuple).
         */
        internal fun extractPrefix(fingerprint: String?): String? {
            if (fingerprint.isNullOrEmpty()) return null
            // Split on `/` first; the first 3 `/`-separated fields
            // are manufacturer / product / device-with-version.
            // Trim any colon-separated build-version off the third
            // field so we compare ONLY the manufacturer-pipeline
            // tuple, not the per-build version which can drift
            // between system + vendor partitions legitimately.
            val parts = fingerprint.split("/")
            if (parts.size < PREFIX_SEGMENTS) return null
            val third = parts[PREFIX_SEGMENTS - 1].substringBefore(":")
            return "${parts[0]}/${parts[1]}/$third"
        }

        /**
         * True iff [system] and [vendor] share a partition-cross-
         * invariant fingerprint prefix. Both must be non-null AND
         * non-empty for a match; null in either is "cannot
         * compare" (the rule doesn't fire in that case — falls
         * through to vendor_absent or no_observation).
         */
        internal fun prefixesMatch(system: String?, vendor: String?): Boolean? {
            val sp = extractPrefix(system) ?: return null
            val vp = extractPrefix(vendor) ?: return null
            return sp == vp
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val systemFp: String? = try {
                ctx.getSystemProperty(PROP_SYSTEM_FINGERPRINT)
            } catch (_: Throwable) {
                null
            }
            val vendorFp: String? = try {
                ctx.getSystemProperty(PROP_VENDOR_FINGERPRINT)
            } catch (_: Throwable) {
                null
            }

            val systemPrefix = extractPrefix(systemFp)
            val vendorPrefix = extractPrefix(vendorFp)
            val prefixesMatch = prefixesMatch(systemFp, vendorFp)

            val (pattern, score, confidence) = when {
                systemFp.isNullOrEmpty() ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
                vendorFp.isNullOrEmpty() ->
                    Triple(PATTERN_VENDOR_ABSENT, SCORE_VENDOR_ABSENT, CONFIDENCE_NORMAL)
                prefixesMatch == false ->
                    Triple(PATTERN_DIVERGENT_FINGERPRINTS, SCORE_DIVERGENT, CONFIDENCE_NORMAL)
                prefixesMatch == true ->
                    Triple(PATTERN_PARTITIONS_CONSISTENT, SCORE_CLEAN, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
            }

            val evidence = listOf(
                Evidence(
                    key = PROP_SYSTEM_FINGERPRINT,
                    value = systemFp ?: "<unavailable>",
                    expected = null,
                ),
                Evidence(
                    key = PROP_VENDOR_FINGERPRINT,
                    value = vendorFp ?: "<unavailable>",
                    expected = null,
                ),
                Evidence(
                    key = "fingerprint_cross_partition.system_prefix",
                    value = systemPrefix ?: "<unavailable>",
                    expected = null,
                ),
                Evidence(
                    key = "fingerprint_cross_partition.vendor_prefix",
                    value = vendorPrefix ?: "<unavailable>",
                    expected = null,
                ),
                Evidence(
                    key = "fingerprint_cross_partition.prefixes_match",
                    value = prefixesMatch?.toString() ?: "<unavailable>",
                    expected = "true",
                ),
                Evidence(
                    key = "fingerprint_cross_partition.pattern",
                    value = pattern,
                    expected = PATTERN_PARTITIONS_CONSISTENT,
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
                "FingerprintCrossPartitionProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 7, mitigation_layer L1
package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #7 — buildprop.tags_and_type.
 *
 * Focused-extraction probe over `ro.build.tags` + `ro.build.type` —
 * the same two properties also read by rank-1
 * [BuildFingerprintProbe], but scored INDEPENDENTLY of fingerprint /
 * brand / model coherence. The "yaml-wins" discipline: inventory
 * lists rank 7 as a separate probe with its own rank-priority slot,
 * so the violation gets its own probe even though the source
 * properties overlap with rank-1.
 *
 * **Distinct scoring focus from rank-1**:
 *   - rank-1 looks at fingerprint + tags + type + brand/model
 *     COHERENCE; tags+type are co-factors that elevate to 0.85 only
 *     when fingerprint already shows emulator markers.
 *   - rank-7 (this) looks ONLY at tags+type as a standalone signal,
 *     elevating EACH violation to 0.95 strong (1.0 when both fire)
 *     because a production-build claim (`ro.product.model=Pixel 7`)
 *     with `tags=test-keys` and `type=userdebug` is a structural
 *     contradiction regardless of fingerprint shape.
 *
 * Same source properties intentionally double-read: a consumer-side
 * aggregator routes on whichever signal it wants — rank-1's coherence
 * score, or rank-7's focused tags/type score — without one probe
 * having to subsume the other.
 *
 * Signal classes (CRITICAL because each is dispositive on its own —
 * production Android builds are `tags=release-keys` AND `type=user`
 * by AOSP convention; deviation indicates non-prod or tampered build):
 *
 *   • **Both violations (1.0)**: `tags == "test-keys"` AND `type` is
 *     `userdebug` or `eng`. Maximum-strength evidence: every AOSP
 *     production user build sets both to the canonical values; any
 *     deviation from both is unambiguous non-production.
 *   • **Tags violation only (0.95)**: `tags == "test-keys"` AND type
 *     is `user` (or unobservable). FP class: a custom ROM that signs
 *     with test-keys but reports `type=user` — still non-prod, but
 *     less internally contradictory than both-violation.
 *   • **Type violation only (0.95)**: `type` is `userdebug` or `eng`
 *     AND tags is `release-keys` (or unobservable). FP class: Google
 *     developer-builds for Pixel-internal QA (`userdebug` is the
 *     Google-internal build flavor), but those are not on consumer
 *     devices.
 *   • **Empty values on phone-class (0.7)**: either property is empty
 *     string AND model is phone-class (rank-25 `isPhoneClassModel`).
 *     FP class: pre-production stub builds with empty buildprops.
 *     Weak signal — could also be accessor partial-failure surfacing
 *     as empty rather than null.
 *   • **Clean (0.0)**: `tags == "release-keys"` AND `type == "user"`.
 *
 * Uses ONLY the base [ProbeContext] (`getSystemProperty`) — no
 * constructor-injected suppliers, no new core-contract methods. Two
 * property reads with per-property try/catch. Same shape as rank-4
 * [com.detectorlab.probes.emulator.QemuArtifactsProbe].
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the empty-
 * values phone-class gate — same drift-safe delegate pattern as
 * rank-49/53.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the rank-49/51/52/53 partition pattern):
 *   1.00  PATTERN_BOTH_VIOLATIONS — tags=test-keys AND type non-user
 *   0.95  PATTERN_TAGS_VIOLATION — tags=test-keys (type clean or
 *         unobservable)
 *   0.95  PATTERN_TYPE_VIOLATION — type=userdebug|eng (tags clean
 *         or unobservable)
 *   0.70  PATTERN_EMPTY_ON_PHONE_CLASS — either property empty AND
 *         model is phone-class
 *   0.00  PATTERN_CLEAN — release-keys + user
 *
 * Confidence:
 *   0.95  Both properties readable (non-null, regardless of value)
 *   0.50  Either property null/threw (degraded)
 *
 * Reference: shared/probes/inventory.yml rank 7 (mitigation_layer L1).
 */
class TagsAndTypeProbe : Probe {
    override val id = "buildprop.tags_and_type"
    override val rank = 7
    override val category = ProbeCategory.BUILDPROP
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_BUILD_TAGS = "ro.build.tags"
        const val PROP_RO_BUILD_TYPE = "ro.build.type"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val PRODUCTION_TAGS = "release-keys"
        const val PRODUCTION_TYPE = "user"
        const val TEST_KEYS_TAGS = "test-keys"

        /**
         * Non-production `ro.build.type` values. `userdebug` is the
         * Google-internal QA build flavor (Pixel dogfood); `eng` is
         * the AOSP source-engineering build. Both are dispositive
         * non-production on a consumer device.
         */
        val NON_PRODUCTION_TYPES: Set<String> = setOf("userdebug", "eng")

        const val PATTERN_BOTH_VIOLATIONS = "both_violations"
        const val PATTERN_TAGS_VIOLATION = "tags_violation"
        const val PATTERN_TYPE_VIOLATION = "type_violation"
        const val PATTERN_EMPTY_ON_PHONE_CLASS = "empty_on_phone_class"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_BOTH_VIOLATIONS = 1.0
        const val SCORE_TAGS_VIOLATION = 0.95
        const val SCORE_TYPE_VIOLATION = 0.95
        const val SCORE_EMPTY_ON_PHONE_CLASS = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read ro.build.tags + ro.build.type and verify production-build " +
                "pattern (release-keys + user). Scoped focus separate from " +
                "rank 1 BuildFingerprint which checks consistency across the " +
                "full fingerprint string."

        /** True iff [type] is a non-production AOSP build flavor. */
        internal fun isNonProductionType(type: String?): Boolean {
            if (type.isNullOrEmpty()) return false
            return type.lowercase() in NON_PRODUCTION_TYPES
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-property try/catch — one throwing read doesn't
            // suppress observation of the other. Null vs empty-string
            // distinction matters: null means accessor threw or
            // returned no value; empty means the property exists but
            // was set to "" (pre-prod-stub signature for rank-7).
            val tags: String? = try {
                ctx.getSystemProperty(PROP_RO_BUILD_TAGS)
            } catch (_: Throwable) {
                null
            }
            val type: String? = try {
                ctx.getSystemProperty(PROP_RO_BUILD_TYPE)
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val tagsIsTestKeys = tags == TEST_KEYS_TAGS
            val typeIsNonProduction = isNonProductionType(type)

            // Empty signal is distinct from null: only empty-string
            // (property set but blank) triggers the pre-prod-stub
            // rule; null means unobservable and falls through to
            // degraded confidence.
            val tagsIsEmpty = tags == ""
            val typeIsEmpty = type == ""
            val anyEmpty = tagsIsEmpty || typeIsEmpty

            val (pattern, score) = when {
                tagsIsTestKeys && typeIsNonProduction ->
                    PATTERN_BOTH_VIOLATIONS to SCORE_BOTH_VIOLATIONS
                tagsIsTestKeys ->
                    PATTERN_TAGS_VIOLATION to SCORE_TAGS_VIOLATION
                typeIsNonProduction ->
                    PATTERN_TYPE_VIOLATION to SCORE_TYPE_VIOLATION
                anyEmpty && phoneClass ->
                    PATTERN_EMPTY_ON_PHONE_CLASS to SCORE_EMPTY_ON_PHONE_CLASS
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Both readable = NORMAL; either null = DEGRADED (the
            // empty-string-on-phone-class rule already fired above
            // before this confidence assignment, so degrading on
            // null still preserves the empty-value-signal accuracy).
            val confidence = if (tags != null && type != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            val tagsViolation = tagsIsTestKeys
            val typeViolation = typeIsNonProduction
            val isProductionUserBuild = tags == PRODUCTION_TAGS && type == PRODUCTION_TYPE

            val evidence = listOf(
                Evidence(
                    key = PROP_RO_BUILD_TAGS,
                    value = tags ?: "<unavailable>",
                    expected = PRODUCTION_TAGS,
                ),
                Evidence(
                    key = PROP_RO_BUILD_TYPE,
                    value = type ?: "<unavailable>",
                    expected = PRODUCTION_TYPE,
                ),
                Evidence(
                    key = "build.tags_violation",
                    value = tagsViolation.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "build.type_violation",
                    value = typeViolation.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "build.is_production_user_build",
                    value = isProductionUserBuild.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "build.tags_type_pattern",
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
                "TagsAndTypeProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

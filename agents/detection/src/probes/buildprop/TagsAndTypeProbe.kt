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
 * Focused-extraction probe over `ro.build.tags` + `ro.build.type` +
 * `ro.debuggable` + `ro.secure` — the build-flavor + dangerous-prop
 * surface that RootBeer / RootBeerFresh treat as their primary
 * non-package-list root indicator. Properties also read by rank-1
 * [BuildFingerprintProbe] (tags/type only) and rank-13
 * [com.detectorlab.probes.env.BootloaderProbe] (debuggable for AVB
 * coherence), but scored INDEPENDENTLY here per the "yaml-wins"
 * discipline — inventory lists rank 7 as a separate probe with its
 * own rank-priority slot, so the violation gets its own probe even
 * when source properties overlap with neighbour ranks.
 *
 * **Distinct scoring focus from rank-1 / rank-13**:
 *   - rank-1 looks at fingerprint + tags + type + brand/model
 *     COHERENCE; tags+type are co-factors that elevate to 0.85 only
 *     when fingerprint already shows emulator markers.
 *   - rank-13 looks at AVB state (vbmeta + verifiedbootstate +
 *     flash.locked); debuggable is a co-factor in the "dev-build
 *     leak" rule, not a standalone signal.
 *   - rank-7 (this) looks at tags/type AND debuggable/secure as
 *     INDEPENDENT standalone signals, elevating tags/type violations
 *     to 0.95 (1.0 when both fire) AND dangerous-props violations
 *     (`ro.debuggable=1` OR `ro.secure=0`) to 0.95 — every AOSP
 *     production user-build has tags=release-keys, type=user,
 *     debuggable=0, secure=1; ANY of the four properties deviating
 *     is dispositive non-production.
 *
 * Same source properties intentionally double-read: a consumer-side
 * aggregator routes on whichever signal it wants — rank-1's coherence
 * score, rank-7's focused tags/type/dangerous-props score, or rank-13's
 * AVB coherence — without one probe subsuming the others.
 *
 * **Power-13 Gap #5 — RootBeer `ro.debuggable` / `ro.secure`**: RootBeer's
 * `checkForDangerousProps()` reads `ro.debuggable` and `ro.secure` and
 * flags ANY deviation from `0`/`1` respectively. This is RootBeer's
 * most-quoted check in 2024-2026 detector forks. Real-world detector
 * coverage requires reading both properties; they are NOT spoofed by
 * the default Magisk DenyList — a custom resetprop or PIF module is
 * required. See `audit/spoof-stack/real-world-detectors.md` row
 * "Dangerous props (`ro.debuggable=1`, `ro.secure=0`)".
 *
 * Signal classes (CRITICAL because each is dispositive on its own —
 * production Android builds are `tags=release-keys` AND `type=user`
 * AND `debuggable=0` AND `secure=1` by AOSP convention; deviation
 * indicates non-prod or tampered build):
 *
 *   • **Both tags+type violations (1.0)**: `tags == "test-keys"` AND
 *     `type` is `userdebug` or `eng`. Maximum-strength tags/type
 *     evidence: every AOSP production user build sets both to the
 *     canonical values; any deviation from both is unambiguous
 *     non-production.
 *   • **Tags violation only (0.95)**: `tags == "test-keys"` AND type
 *     is `user` (or unobservable). FP class: a custom ROM that signs
 *     with test-keys but reports `type=user` — still non-prod, but
 *     less internally contradictory than both-violation.
 *   • **Type violation only (0.95)**: `type` is `userdebug` or `eng`
 *     AND tags is `release-keys` (or unobservable). FP class: Google
 *     developer-builds for Pixel-internal QA (`userdebug` is the
 *     Google-internal build flavor), but those are not on consumer
 *     devices.
 *   • **Dangerous-props violation (0.95)**: `ro.debuggable == "1"` OR
 *     `ro.secure == "0"`. Either deviation is RootBeer-dispositive on
 *     its own. This rule fires INDEPENDENTLY of tags/type — a clean
 *     `release-keys + user` build with debuggable=1 still trips. When
 *     a higher-tier rule (1.0 / 0.95 tags+type) already fires, the
 *     dangerous-props evidence rows are STILL EMITTED so a consumer
 *     can see the dispositive co-fire, but the score stays at the
 *     higher rule's value (cascade ordering — dangerous-props is the
 *     LAST rule at the 0.95 tier so existing tags-only / type-only
 *     pattern semantics on legacy snapshots are preserved on tie).
 *   • **Empty tags/type on phone-class (0.7)**: either tags/type
 *     property is empty string AND model is phone-class (rank-25
 *     `isPhoneClassModel`). FP class: pre-production stub builds
 *     with empty buildprops. Weak signal — could also be accessor
 *     partial-failure surfacing as empty rather than null. NOT
 *     elevated by debuggable/secure emptiness (treating empty values
 *     on the dangerous-props axis as unobservable to avoid FP from
 *     containers that don't expose those props at all).
 *   • **Clean (0.0)**: `tags == "release-keys"` AND `type == "user"`
 *     AND debuggable!=1 AND secure!=0.
 *
 * Uses ONLY the base [ProbeContext] (`getSystemProperty`) — no
 * constructor-injected suppliers, no new core-contract methods. Four
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
 *   0.95  PATTERN_DANGEROUS_PROPS_VIOLATION — debuggable="1" OR
 *         secure="0", AND tags/type clean (otherwise a higher-tier
 *         rule wins; dangerous-props evidence still emitted)
 *   0.70  PATTERN_EMPTY_ON_PHONE_CLASS — either tags/type property
 *         empty AND model is phone-class
 *   0.00  PATTERN_CLEAN — release-keys + user + debuggable!=1 +
 *         secure!=0
 *
 * Confidence:
 *   0.95  All four properties readable (non-null, regardless of value)
 *   0.50  Any property null/threw (degraded). Debuggable/secure null
 *         degrade confidence even when tags/type are clean, because a
 *         missing dangerous-props reading is a partial-observation
 *         honest tell rather than a false-negative on the new rule.
 *
 * Reference: shared/probes/inventory.yml rank 7 (mitigation_layer L1);
 * RootBeer `checkForDangerousProps()` at
 *   https://github.com/scottyab/rootbeer (Const.java + RootBeer.java).
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
        const val PROP_RO_DEBUGGABLE = "ro.debuggable"
        const val PROP_RO_SECURE = "ro.secure"

        const val PRODUCTION_TAGS = "release-keys"
        const val PRODUCTION_TYPE = "user"
        const val TEST_KEYS_TAGS = "test-keys"

        /**
         * Production `ro.debuggable` value. Production user-builds set
         * this to `"0"`; any other value (typically `"1"`) is the
         * RootBeer dispositive dangerous-prop signal.
         */
        const val PRODUCTION_DEBUGGABLE = "0"

        /**
         * Production `ro.secure` value. Production builds set this to
         * `"1"`; any other value (typically `"0"`) is the RootBeer
         * dispositive dangerous-prop signal — `ro.secure=0` means
         * adbd does NOT run as the `shell` user, i.e. the device has
         * a debuggable bootimage.
         */
        const val PRODUCTION_SECURE = "1"

        /**
         * Dangerous `ro.debuggable` value. RootBeer flags `ro.debuggable=1`
         * as a strong root-prep indicator on consumer devices.
         */
        const val DANGEROUS_DEBUGGABLE = "1"

        /**
         * Dangerous `ro.secure` value. RootBeer flags `ro.secure=0` as
         * a strong root-prep indicator on consumer devices.
         */
        const val DANGEROUS_SECURE = "0"

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
        const val PATTERN_DANGEROUS_PROPS_VIOLATION = "dangerous_props_violation"
        const val PATTERN_EMPTY_ON_PHONE_CLASS = "empty_on_phone_class"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_BOTH_VIOLATIONS = 1.0
        const val SCORE_TAGS_VIOLATION = 0.95
        const val SCORE_TYPE_VIOLATION = 0.95
        const val SCORE_DANGEROUS_PROPS_VIOLATION = 0.95
        const val SCORE_EMPTY_ON_PHONE_CLASS = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read ro.build.tags + ro.build.type + ro.debuggable + ro.secure " +
                "and verify production-build pattern (release-keys + user + " +
                "debuggable=0 + secure=1). Scoped focus separate from rank 1 " +
                "BuildFingerprint (which checks tags/type as fingerprint " +
                "co-factors) and rank 13 Bootloader (which checks debuggable " +
                "as AVB co-factor). Dangerous-props axis closes Power-13 " +
                "real-world gap #5 — RootBeer checkForDangerousProps()."

        /** True iff [type] is a non-production AOSP build flavor. */
        internal fun isNonProductionType(type: String?): Boolean {
            if (type.isNullOrEmpty()) return false
            return type.lowercase() in NON_PRODUCTION_TYPES
        }

        /**
         * True iff [debuggable] is the RootBeer-dispositive `"1"`
         * value. Empty-string and `null` are treated as "not a
         * violation" to avoid FP on containers that don't expose
         * the property at all.
         */
        internal fun isDangerousDebuggable(debuggable: String?): Boolean {
            if (debuggable.isNullOrEmpty()) return false
            return debuggable == DANGEROUS_DEBUGGABLE
        }

        /**
         * True iff [secure] is the RootBeer-dispositive `"0"` value.
         * Empty-string and `null` are treated as "not a violation"
         * to avoid FP on containers that don't expose the property
         * at all.
         */
        internal fun isDangerousSecure(secure: String?): Boolean {
            if (secure.isNullOrEmpty()) return false
            return secure == DANGEROUS_SECURE
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-property try/catch — one throwing read doesn't
            // suppress observation of the others. Null vs empty-string
            // distinction matters: null means accessor threw or
            // returned no value; empty means the property exists but
            // was set to "" (pre-prod-stub signature for tags/type;
            // unobservable-axis sentinel for debuggable/secure).
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
            val debuggable: String? = try {
                ctx.getSystemProperty(PROP_RO_DEBUGGABLE)
            } catch (_: Throwable) {
                null
            }
            val secure: String? = try {
                ctx.getSystemProperty(PROP_RO_SECURE)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val tagsIsTestKeys = tags == TEST_KEYS_TAGS
            val typeIsNonProduction = isNonProductionType(type)
            val debuggableViolation = isDangerousDebuggable(debuggable)
            val secureViolation = isDangerousSecure(secure)
            val dangerousPropsViolation = debuggableViolation || secureViolation

            // Empty signal is distinct from null: only empty-string
            // (property set but blank) triggers the pre-prod-stub
            // rule; null means unobservable and falls through to
            // degraded confidence.
            val tagsIsEmpty = tags == ""
            val typeIsEmpty = type == ""
            val anyTagsTypeEmpty = tagsIsEmpty || typeIsEmpty

            // Cascade — strongest first. Dangerous-props is at the
            // 0.95 tier but ordered LAST among 0.95 rules so existing
            // tags-only / type-only pattern semantics on legacy
            // snapshots are preserved on tie (a snapshot that already
            // trips tags-only stays at PATTERN_TAGS_VIOLATION even if
            // debuggable also leaks).
            val (pattern, score) = when {
                tagsIsTestKeys && typeIsNonProduction ->
                    PATTERN_BOTH_VIOLATIONS to SCORE_BOTH_VIOLATIONS
                tagsIsTestKeys ->
                    PATTERN_TAGS_VIOLATION to SCORE_TAGS_VIOLATION
                typeIsNonProduction ->
                    PATTERN_TYPE_VIOLATION to SCORE_TYPE_VIOLATION
                dangerousPropsViolation ->
                    PATTERN_DANGEROUS_PROPS_VIOLATION to SCORE_DANGEROUS_PROPS_VIOLATION
                anyTagsTypeEmpty && phoneClass ->
                    PATTERN_EMPTY_ON_PHONE_CLASS to SCORE_EMPTY_ON_PHONE_CLASS
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // All four readable = NORMAL; any null = DEGRADED.
            // Debuggable/secure null degrade confidence even when
            // tags/type are clean, because a missing dangerous-props
            // reading IS a partial-observation honest tell rather
            // than a false-negative on the new rule.
            val confidence = if (
                tags != null && type != null &&
                debuggable != null && secure != null
            ) {
                CONFIDENCE_FULL
            } else {
                CONFIDENCE_DEGRADED
            }

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
                // Power-13 Gap #5 — RootBeer dangerous-props evidence
                // rows (probe-scoped namespace `tags_and_type.*` per
                // cross-cutting #1). Emitted on every run so a
                // consumer sees the dispositive co-fire even when a
                // higher-tier (tags/type) rule wins the score.
                Evidence(
                    key = "tags_and_type.${PROP_RO_DEBUGGABLE}",
                    value = debuggable ?: "<unavailable>",
                    expected = PRODUCTION_DEBUGGABLE,
                ),
                Evidence(
                    key = "tags_and_type.${PROP_RO_SECURE}",
                    value = secure ?: "<unavailable>",
                    expected = PRODUCTION_SECURE,
                ),
                Evidence(
                    key = "tags_and_type.debuggable_violation",
                    value = debuggableViolation.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "tags_and_type.secure_violation",
                    value = secureViolation.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "tags_and_type.dangerous_props_violation",
                    value = dangerousPropsViolation.toString(),
                    expected = "false",
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

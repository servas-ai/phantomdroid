// inventory.yml rank 39.5 (code uses rank 82 due to Int interface; tracked
// in audit/cross-cutting-followups-2026-05-19.md #7), freeRASP T16,
// MSTG-RESILIENCE-X
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #39.5 — env.location_mock_rasp.
 *
 * Focused-extraction RASP-attribution variant of rank-39
 * [LocationMockProbe]. Same end-user intent (detect mock location
 * spoofing) but the score map is calibrated against the freeRASP T16
 * specification (MASTG MSTG-RESILIENCE-X dynamic-instrumentation
 * detection) rather than the broader 4-signal-channel approach of
 * rank-39. Joins the yaml-wins focused-extraction discipline of
 * rank-7 / rank-9 / rank-38 / rank-58.
 *
 * **Distinct scoring focus from rank-39**:
 *   - rank-39 reads FOUR independent signals (`isFromMockProvider`,
 *     `ALLOW_MOCK_LOCATION`, `ACCESS_MOCK_LOCATION` permission scan,
 *     reverse-geocoder anomaly) with score-summing-via-max +
 *     per-package step accumulation. Severity HIGH.
 *   - rank-39.5 (this) reads the THREE freeRASP-T16-specific signals
 *     (`isFromMockProvider`, `ALLOW_MOCK_LOCATION` with explicit
 *     API <23 gate, `MOCK_LOCATION_APP` setting) and emits the
 *     T16-attributed score. Severity MEDIUM (RASP-mapped baseline).
 *
 * Same source properties intentionally double-read — consumer-side
 * aggregator routes on whichever signal it wants (rank-39's broader
 * channel score, or rank-39.5's T16-attributed score) without one
 * probe having to subsume the other.
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml
 * lists this probe at fractional rank 39.5 (its "natural" priority
 * slot adjacent to rank-39 LocationMockProbe). The [Probe.rank]
 * interface uses `Int`, which can't represent 39.5, so this code
 * uses rank 82 (unused integer outside the META-22 A17 range
 * 61-71). Same rank-system Int-vs-fractional divergence handling
 * as [com.detectorlab.probes.env.ScreenLockProbe] (inventory 40.5
 * → code 61) and [com.detectorlab.probes.runtime.DebuggerTracerPidProbe]
 * (inventory 8.5 → code 80). Third anchor for this pattern in the
 * session — tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * Signal classes (calibrated against freeRASP T16):
 *
 *   • **`isFromMockProvider` flag (1.0)**: `Location.isFromMockProvider()`
 *     on the last known fix returned true. The strongest possible
 *     signal — the Location API itself has flagged the fix as mock.
 *     This is the freeRASP T16 canonical signal. Same Settings.Secure
 *     sentinel as rank-39 S1 (production wrapper reads
 *     `Location.isFromMockProvider()`).
 *   • **`MOCK_LOCATION_APP` setting (1.0)**: a mock-location app
 *     has been explicitly designated as the default via Android's
 *     Developer Options. Set to a non-empty package name when active.
 *     Distinct from rank-39 S3 (which scans installed-package
 *     permissions): this is the user-action signal, not the
 *     potential-capability signal. NEW surface vs rank-39.
 *   • **`ALLOW_MOCK_LOCATION` on legacy API (0.85)**: the deprecated
 *     `Settings.Secure.mock_location` flag is set to "1" AND the
 *     Android API level is < 23. Score gated on API < 23 because
 *     the setting is meaningful only on legacy devices (removed at
 *     API 23+; production wrapper returns null on newer devices, so
 *     this rule won't fire there even without the explicit gate).
 *     The explicit gate makes the rule's scope intentional and
 *     drift-safe — a future wrapper that backports the setting to
 *     newer devices won't accidentally fire the rule. FP class
 *     documented: legitimate developer mode on a legacy device
 *     (rare on cloud-phone infra targeting modern Android).
 *   • **No-Location-available (0.5)**: no Location surface readable
 *     AND no mock signals observed. Weak signal — could be legitimate
 *     location-permission-denied OR a stub that returns null for
 *     all Location queries. Confidence degraded.
 *   • **Clean (0.0)**: Location observable AND none of the above
 *     fire.
 *
 * **FP class acknowledgment** (per the rank-51 honest-framing pattern):
 * legitimate developer mode on a legacy (pre-2015 Android 4.4+/API 22
 * or earlier) device fires the 0.85 ALLOW_MOCK_LOCATION rule. The
 * 1.0 `isFromMockProvider` and `MOCK_LOCATION_APP` rules have no
 * plausible legitimate FP class on cloud-phone production
 * deployments — neither signal would naturally appear on a real
 * end-user device.
 *
 * **Consumer guidance** (per the rank-50 / rank-51 / rank-52
 * consumer-gating-pattern family): the dispositive 1.0 scores are
 * calibrated for production-device evaluation. On developer/test
 * environments with Developer Options enabled, downstream consumers
 * should combine with rank-7 `TagsAndTypeProbe` (`Build.TYPE ==
 * "userdebug"` cross-check) and rank-19 `DeveloperOptionsProbe`
 * (`development_settings_enabled == 1` cross-check) to discount.
 * Gating responsibility is consumer-side, not probe-side.
 *
 * Uses ONLY base [ProbeContext.querySettingSecure] +
 * [ProbeContext.getSystemProperty] for the SDK-int gate. NO new
 * core-contract methods. Same accessor shape as rank-19 / rank-58 /
 * rank-59 Settings.Secure family.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the established partition pattern):
 *   1.00  PATTERN_IS_FROM_MOCK_PROVIDER — Location.isFromMockProvider() true
 *   1.00  PATTERN_MOCK_LOCATION_APP_SET — MOCK_LOCATION_APP non-empty
 *   0.85  PATTERN_ALLOW_MOCK_ON_LEGACY — ALLOW_MOCK_LOCATION="1" AND
 *         API < 23
 *   0.50  PATTERN_NO_LOCATION_AVAILABLE — no Location surface
 *         observable AND no mock signals fired
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  At least one Settings.Secure accessor returned non-null
 *   0.50  All accessor calls returned null OR threw (degraded;
 *         no observation possible)
 *
 * **Null-vs-empty discipline policy** (per session-level rule):
 *   - null   → unobservable, contributes to confidence degradation
 *   - ""     → score-level "not set" (empty MOCK_LOCATION_APP is
 *              functionally identical to no app designated)
 *
 * Reference: shared/probes/inventory.yml rank 39.5 (mitigation_layer
 * L4, freeRASP T16, MSTG-RESILIENCE-X).
 */
class LocationMockRaspProbe : Probe {
    override val id = "env.location_mock_rasp"
    override val rank = RANK
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank
         * 39.5, but `Probe.rank: Int` can't represent 39.5. Picked
         * 82 (unused integer; one above rank-80 DebuggerTracerPid).
         * Third Int-vs-fractional handling in the session after
         * [com.detectorlab.probes.env.ScreenLockProbe] (40.5 → 61)
         * and [com.detectorlab.probes.runtime.DebuggerTracerPidProbe]
         * (8.5 → 80). Tracked in
         * `audit/cross-cutting-followups-2026-05-19.md` #7.
         */
        const val RANK = 82

        // Settings.Secure keys — same sentinels as rank-39
        // LocationMockProbe for S1 + S2 surfaces. NEW key
        // MOCK_LOCATION_APP is the user-action signal (Developer
        // Options: "Select mock location app").
        const val SETTING_IS_FROM_MOCK_PROVIDER = "location.is_from_mock_provider"
        const val SETTING_ALLOW_MOCK_LOCATION = "mock_location"
        const val SETTING_MOCK_LOCATION_APP = "mock_location_app"

        // SDK-int gate via system property — Android's
        // `Build.VERSION.SDK_INT` is also surfaced as the system prop
        // `ro.build.version.sdk` (string form).
        const val PROP_RO_BUILD_VERSION_SDK = "ro.build.version.sdk"

        /**
         * API level threshold for the `ALLOW_MOCK_LOCATION` rule.
         * Android 23 (Marshmallow, 2015) removed the runtime-
         * effective behavior of this Settings.Secure flag; on API >=23
         * the setting may be readable but has no functional effect.
         * Rule fires only when API < 23 to keep the score
         * semantically meaningful.
         */
        const val ALLOW_MOCK_API_THRESHOLD = 23

        const val PATTERN_IS_FROM_MOCK_PROVIDER = "is_from_mock_provider"
        const val PATTERN_MOCK_LOCATION_APP_SET = "mock_location_app_set"
        const val PATTERN_ALLOW_MOCK_ON_LEGACY = "allow_mock_on_legacy"
        const val PATTERN_NO_LOCATION_AVAILABLE = "no_location_available"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_IS_FROM_MOCK_PROVIDER = 1.0
        const val SCORE_MOCK_LOCATION_APP_SET = 1.0
        const val SCORE_ALLOW_MOCK_ON_LEGACY = 0.85
        const val SCORE_NO_LOCATION_AVAILABLE = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read Location.isFromMockProvider() + ALLOW_MOCK_LOCATION setting + " +
                "MOCK_LOCATION_APP setting; focused RASP-attribution probe per " +
                "freeRASP T16. Augments rank 39 LocationMockProbe with explicit " +
                "RASP-mapped score."

        /**
         * Parse Android SDK int from `ro.build.version.sdk` string
         * form. Real values are like `"23"`, `"30"`, `"34"`.
         * Returns null when null/empty/non-numeric.
         */
        internal fun parseSdkInt(rawSdk: String?): Int? {
            if (rawSdk.isNullOrEmpty()) return null
            return rawSdk.toIntOrNull()
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-accessor try/catch with observation counter so
            // throwing reads don't suppress observation of the other
            // surfaces. Null vs throw both contribute to confidence
            // degradation; empty-string IS observation (per session
            // null-vs-empty discipline).
            var anyObserved = false

            fun readSecure(key: String): String? {
                return try {
                    val v = ctx.querySettingSecure(key)
                    if (v != null) anyObserved = true
                    v
                } catch (_: Throwable) {
                    null
                }
            }

            val isFromMockRaw = readSecure(SETTING_IS_FROM_MOCK_PROVIDER)
            val allowMockRaw = readSecure(SETTING_ALLOW_MOCK_LOCATION)
            val mockLocationAppRaw = readSecure(SETTING_MOCK_LOCATION_APP)

            val sdkPropRaw: String? = try {
                ctx.getSystemProperty(PROP_RO_BUILD_VERSION_SDK)
            } catch (_: Throwable) {
                null
            }
            val sdkInt = parseSdkInt(sdkPropRaw)

            // Score-level predicates.
            val isFromMock = isFromMockRaw == "1"
            val allowMock = allowMockRaw == "1"
            // MOCK_LOCATION_APP is set when non-null AND non-empty
            // (empty string is "not set" per session null-vs-empty
            // discipline).
            val mockLocationAppSet = !mockLocationAppRaw.isNullOrEmpty()
            val isLegacyApi = sdkInt != null && sdkInt < ALLOW_MOCK_API_THRESHOLD
            val allowMockOnLegacy = allowMock && isLegacyApi

            // No-Location-available: no `isFromMockProvider` sentinel
            // observable AND no other mock signals fired. The sentinel
            // null means the production wrapper couldn't get a fix,
            // which on a real device is unusual (location permission
            // denied OR stub).
            val noLocationAvailable = isFromMockRaw == null &&
                !mockLocationAppSet &&
                !allowMockOnLegacy

            val (pattern, score) = when {
                isFromMock ->
                    PATTERN_IS_FROM_MOCK_PROVIDER to SCORE_IS_FROM_MOCK_PROVIDER
                mockLocationAppSet ->
                    PATTERN_MOCK_LOCATION_APP_SET to SCORE_MOCK_LOCATION_APP_SET
                allowMockOnLegacy ->
                    PATTERN_ALLOW_MOCK_ON_LEGACY to SCORE_ALLOW_MOCK_ON_LEGACY
                noLocationAvailable ->
                    PATTERN_NO_LOCATION_AVAILABLE to SCORE_NO_LOCATION_AVAILABLE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (anyObserved) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            // Tri-state mock_detected evidence per the
            // rank-48/49/52/53/9/80 defensive-honesty pattern.
            val mockDetectedDisplay = when {
                !anyObserved -> "unknown"
                isFromMock || mockLocationAppSet || allowMockOnLegacy -> "true"
                else -> "false"
            }

            val sdkDisplay = when {
                sdkInt != null -> sdkInt.toString()
                sdkPropRaw != null -> "<malformed:$sdkPropRaw>"
                else -> "<unavailable>"
            }

            val evidence = listOf(
                Evidence(
                    key = "secure.$SETTING_IS_FROM_MOCK_PROVIDER",
                    value = isFromMockRaw ?: "<unavailable>",
                    expected = "0",
                ),
                Evidence(
                    key = "secure.$SETTING_ALLOW_MOCK_LOCATION",
                    value = allowMockRaw ?: "<unavailable>",
                    expected = "0",
                ),
                Evidence(
                    key = "secure.$SETTING_MOCK_LOCATION_APP",
                    value = mockLocationAppRaw ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "build.version.sdk_int",
                    value = sdkDisplay,
                    expected = ">= 23 on modern Android",
                ),
                Evidence(
                    key = "location.rasp_mock_detected",
                    value = mockDetectedDisplay,
                    expected = "false",
                ),
                Evidence(
                    key = "location.location_mock_rasp_pattern",
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
                "LocationMockRaspProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

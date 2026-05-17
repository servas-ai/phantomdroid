package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — env.screen_lock (A17 N7, freeRASP D1)
 *
 * Detects whether the device has a configured secure lock (PIN, pattern,
 * password, or biometric). A missing lock is a strong correlate of an
 * emulator / throwaway test container and is the explicit D1 signal in
 * freeRASP's DeviceState callback.
 *
 * Signals (per A17-rasp-defensive-probe-mining.md):
 *   - Primary: `KeyguardManager.isDeviceSecure()`  (API ≥23)
 *   - Fallback: `KeyguardManager.isKeyguardSecure()`  (API 16+)
 *
 * Acceptance criteria (CLO-5):
 *   1. Invokes both `isDeviceSecure()` and `isKeyguardSecure()`; emits one
 *      of {pin_set, none, unknown}.
 *   2. Treats `unknown` (API <23 with no secondary signal, or query threw)
 *      as `confidence=weak` — NOT a `strong=false` (clean) verdict.
 *   3. Unit tests cover all three permission/SDK combinations:
 *        (a) API ≥23, device secure        → pin_set
 *        (b) API ≥23, device NOT secure    → none
 *        (c) API <23 with no keyguard data → unknown
 *
 * State→score mapping:
 *   pin_set  → 0.00 score, 0.95 confidence  (clean, strong)
 *   none     → 0.85 score, 0.95 confidence  (detected, strong)
 *   unknown  → 0.00 score, 0.30 confidence  (clean-but-weak: do NOT report
 *                                           a strong negative when the
 *                                           probe could not actually
 *                                           determine the state)
 *
 * Confidence buckets used elsewhere:
 *   ≤ 0.40  → weak
 *   0.40..0.85 → medium
 *   ≥ 0.85  → strong
 */
class ScreenLockProbe : Probe {
    override val id = "env.screen_lock"
    override val rank = RANK
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 500L

    companion object {
        /** A17 N7. Inventory expansion (META-22) reserves 61..71 for A17 N1..N11. */
        const val RANK = 66

        const val STATE_PIN_SET = "pin_set"
        const val STATE_NONE = "none"
        const val STATE_UNKNOWN = "unknown"

        private const val SCORE_PIN_SET = 0.0
        private const val SCORE_NONE = 0.85
        private const val SCORE_UNKNOWN = 0.0

        private const val CONFIDENCE_STRONG = 0.95
        private const val CONFIDENCE_WEAK = 0.30

        private const val API_M = 23  // Build.VERSION_CODES.M — when isDeviceSecure() lands
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val kg = ctx.queryKeyguardManager()
            val sdk = kg.sdkInt()

            // Capture both signals up front so evidence is always complete,
            // regardless of which one drives the verdict.
            val deviceSecure: Boolean? = kg.isDeviceSecure()
            val keyguardSecure: Boolean? = kg.isKeyguardSecure()

            val state: String = classify(sdk, deviceSecure, keyguardSecure)

            val score = when (state) {
                STATE_NONE -> SCORE_NONE
                STATE_PIN_SET -> SCORE_PIN_SET
                else -> SCORE_UNKNOWN
            }
            val confidence = if (state == STATE_UNKNOWN) CONFIDENCE_WEAK else CONFIDENCE_STRONG

            val evidence = listOf(
                Evidence("state", state, expected = STATE_PIN_SET),
                Evidence("Build.VERSION.SDK_INT", sdk),
                Evidence(
                    "KeyguardManager.isDeviceSecure",
                    deviceSecure?.toString() ?: "null",
                    expected = "true",
                ),
                Evidence(
                    "KeyguardManager.isKeyguardSecure",
                    keyguardSecure?.toString() ?: "null",
                    expected = "true",
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = "KeyguardManager.isDeviceSecure() + isKeyguardSecure() with API<23 unknown-fallback",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "ScreenLockProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    /**
     * Decision table:
     *
     *   API ≥23 → trust `isDeviceSecure` (it is the canonical API for the
     *             secure-lockscreen question on Marshmallow+)
     *
     *   API <23 → fall back to `isKeyguardSecure`. Treat its negative reply
     *             as `unknown` (it cannot distinguish "Slide" from "None"
     *             reliably on every OEM), not as a clean `none` verdict.
     *             A positive reply still resolves to `pin_set`.
     *
     *   `isDeviceSecure==null` on API ≥23 means the service threw — fall
     *   through to the API<23 branch.
     */
    internal fun classify(
        sdk: Int,
        deviceSecure: Boolean?,
        keyguardSecure: Boolean?,
    ): String {
        if (sdk >= API_M && deviceSecure != null) {
            return if (deviceSecure) STATE_PIN_SET else STATE_NONE
        }
        // Pre-M, or post-M with a null primary signal: try the fallback.
        return when (keyguardSecure) {
            true -> STATE_PIN_SET
            false -> STATE_UNKNOWN  // cannot distinguish "Slide" from "None" reliably
            null -> STATE_UNKNOWN
        }
    }
}

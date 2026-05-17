package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — runtime.screen_recording (A17 N10, freeRASP T11/T12)
 *
 * Detects an active screen-capture or screen-recording observer attached to
 * the host process. Combines two callback-driven signals exposed by recent
 * Android releases, gated on `Build.VERSION.SDK_INT` per CLO-8 §1:
 *
 *   • `Window.OnScreenCaptureCallback` — API 34+ (Android 14, the
 *     `DETECT_SCREEN_CAPTURE` permission). Fires when a screenshot of the
 *     hosting Activity window is taken; treated as a "screenshot captured"
 *     edge signal.
 *
 *   • `WindowManager.ScreenRecordingCallback` — API 35+ (Android 15, the
 *     `DETECT_SCREEN_RECORDING` permission). Fires when a MediaProjection
 *     session begins/ends capturing any window owned by the registering UID,
 *     i.e. a real-time "frame capture" oracle.
 *
 * The issue acceptance text (CLO-8 §1) describes these two callbacks as
 * "MediaProjectionManager callback (API 34+)" and "Window.OnFrameCaptureListener
 * (API 35+)" respectively; we keep that naming inside the `MediaProjectionManagerView`
 * contract so the wiring reads against the issue verbatim.
 *
 * Gating rules (CLO-8 §2):
 *   1. `sdkInt() < 34` → `ProbeResult.skipped("api_too_low: ...")`. Never
 *      reported as `unknown`, never as `failed`. Pre-A14 has no first-class
 *      capture-observer API and the only known signal would be reading
 *      `MediaProjection` token state via reflection, which is unreliable
 *      and we deliberately refuse.
 *
 *   2. `sdkInt()` in 34..34 → only the API-34 screen-capture callback is
 *      consulted; the API-35 frame-capture signal contributes evidence
 *      with `null` value (key still present so downstream consumers see a
 *      stable schema).
 *
 *   3. `sdkInt() >= 35` → both signals consulted.
 *
 * Score table:
 *   `mediaProjectionFrameCapture == true`  → 0.80  (real-time MediaProjection
 *                                                   session — strong signal;
 *                                                   only fires when the OS
 *                                                   confirms recording is live)
 *   `screenCaptureCallback == true`        → 0.50  (edge-triggered single
 *                                                   screenshot — moderate; a
 *                                                   passing user screenshot
 *                                                   should not be ignored but
 *                                                   is weaker than recording)
 *   Final score is the max of contributing signals (not summed — these are
 *   not independent surfaces; sustained recording will trip both).
 *
 * Confidence:
 *   Any signal observed     → 0.95
 *   No signal, both queryable → 0.90
 *   No signal, only API-34 path queryable → 0.70
 *
 * Severity: LOW per inventory (rank 52.5). Screen capture is one signal in a
 * stack; on its own it does not establish lab/instrumentation with certainty.
 *
 * Smoke-test contract (CLO-8 §3):
 *   The unit-test suite includes a fake context whose
 *   `isMediaProjectionFrameCaptureActive()` flips from `false` to `true`
 *   between consecutive `probe.run()` calls, modelling the device-test
 *   harness launching `MediaProjection.createScreenCaptureIntent()`. The
 *   probe MUST flip from score 0.0 to score >= 0.50 across the two calls
 *   without any other state change.
 */
class ScreenRecordingProbe : Probe {
    override val id = "runtime.screen_recording"
    override val rank = RANK
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 750L

    companion object {
        /** A17 N10. META-22 reserves 61..71 for A17 N1..N11; N10 maps to 70. */
        const val RANK = 70

        /** Android 14 — `Window.addScreenCaptureCallback` / `DETECT_SCREEN_CAPTURE`. */
        const val MIN_SDK_SCREEN_CAPTURE_CALLBACK = 34

        /** Android 15 — `WindowManager.addScreenRecordingCallback` /
         *  `DETECT_SCREEN_RECORDING`. */
        const val MIN_SDK_FRAME_CAPTURE_CALLBACK = 35

        const val SCORE_FRAME_CAPTURE = 0.80
        const val SCORE_SCREENSHOT_CAPTURE = 0.50
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val view = ctx.queryMediaProjectionManager()
            val sdk = view.sdkInt()

            // (2) Pre-A14 gate — never `unknown`, never `failed`. Skipped only.
            if (sdk < MIN_SDK_SCREEN_CAPTURE_CALLBACK) {
                return ProbeResult.skipped(
                    "api_too_low: SDK_INT=$sdk < $MIN_SDK_SCREEN_CAPTURE_CALLBACK",
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            // (1) API-gated signal reads. `null` from the view = "unknown",
            //     which we propagate as evidence with `null` value AND a
            //     zero contribution to the score.
            val frameCaptureSignal: Boolean? =
                if (sdk >= MIN_SDK_FRAME_CAPTURE_CALLBACK) {
                    view.isMediaProjectionFrameCaptureActive()
                } else {
                    null
                }
            val screenshotCaptureSignal: Boolean? =
                view.isScreenCaptureCallbackActive()

            val frameContribution =
                if (frameCaptureSignal == true) SCORE_FRAME_CAPTURE else 0.0
            val screenshotContribution =
                if (screenshotCaptureSignal == true) SCORE_SCREENSHOT_CAPTURE else 0.0

            // Score is max — these surfaces are correlated, not independent.
            val score = maxOf(frameContribution, screenshotContribution)
                .coerceIn(0.0, 1.0)

            val anyDetected = frameCaptureSignal == true || screenshotCaptureSignal == true

            val confidence = when {
                anyDetected -> 0.95
                sdk >= MIN_SDK_FRAME_CAPTURE_CALLBACK -> 0.90
                else -> 0.70   // A14-only — we are blind to live recording
            }

            val evidence = buildList {
                add(
                    Evidence(
                        key = "mediaProjection.frame_capture_active",
                        value = frameCaptureSignal ?: "unknown",
                        expected = false,
                    )
                )
                add(
                    Evidence(
                        key = "window.screen_capture_callback_fired",
                        value = screenshotCaptureSignal ?: "unknown",
                        expected = false,
                    )
                )
                add(Evidence("Build.VERSION.SDK_INT", sdk))
                add(
                    Evidence(
                        key = "api_path",
                        value = when {
                            sdk >= MIN_SDK_FRAME_CAPTURE_CALLBACK ->
                                "WindowManager.addScreenRecordingCallback + Window.addScreenCaptureCallback"
                            else ->
                                "Window.addScreenCaptureCallback"
                        },
                    )
                )
            }

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = buildString {
                    append("media_projection_callback+window_screen_capture_callback")
                    if (anyDetected) append(" [DETECTED]")
                },
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "ScreenRecordingProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

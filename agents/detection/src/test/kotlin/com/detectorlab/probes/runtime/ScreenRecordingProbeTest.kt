package com.detectorlab.probes.runtime

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.MediaProjectionManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.core.UnknownMediaProjectionManagerView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ScreenRecordingProbe (CLO-8, A17 N10).
 *
 * Acceptance criteria:
 *  (1) MediaProjectionManager callback (API 34+) and OnFrameCaptureListener
 *      (API 35+) hooks are read; the API split is gated by `Build.VERSION.SDK_INT`.
 *  (2) Pre-A14 (SDK_INT < 34) → `ProbeResult.skipped` with reason "api_too_low".
 *      Never `unknown`, never `failed`.
 *  (3) Smoke test: flipping `isMediaProjectionFrameCaptureActive()` from false
 *      to true between two consecutive `probe.run()` calls — modelling the
 *      device-test harness launching `MediaProjection.createScreenCaptureIntent()`
 *      — flips the probe from score 0.0 to score >= 0.50.
 */
class ScreenRecordingProbeTest {

    private val probe = ScreenRecordingProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private class MutableMpmView(
        var sdk: Int = 35,
        var frameCapture: Boolean? = false,
        var screenshotCapture: Boolean? = false,
    ) : MediaProjectionManagerView {
        override fun sdkInt(): Int = sdk
        override fun isScreenCaptureCallbackActive(): Boolean? = screenshotCapture
        override fun isMediaProjectionFrameCaptureActive(): Boolean? = frameCapture
    }

    private fun fakeCtx(view: MediaProjectionManagerView): ProbeContext =
        object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: TelephonyField): String? = null
            override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = false
                override fun listInstalledPackages() = emptyList<String>()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
            override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
                override fun listSensorTypes() = emptyList<Int>()
                override fun sampleSensor(sensorType: Int, durationMs: Long) =
                    SensorSample(LongArray(0), emptyArray())
            }
            override fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView
            override fun queryMediaProjectionManager(): MediaProjectionManagerView = view
        }

    private fun ctxWith(
        sdk: Int = 35,
        frameCapture: Boolean? = false,
        screenshotCapture: Boolean? = false,
    ): ProbeContext = fakeCtx(MutableMpmView(sdk, frameCapture, screenshotCapture))

    // ── (2) Pre-A14 gating — must be skipped(api_too_low) ─────────────────────

    @Test
    fun `pre A14 (sdk 33) returns skipped with reason api_too_low`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 33))
        assertTrue(result.skipped, "expected skipped, got failed=${result.failed}, reason=${result.failureReason}")
        assertNotNull(result.failureReason)
        assertTrue(
            result.failureReason!!.contains("api_too_low"),
            "failure reason must contain 'api_too_low', got: ${result.failureReason}",
        )
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `pre A14 boundary (sdk 30) returns skipped api_too_low`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 30))
        assertTrue(result.skipped)
        assertTrue(result.failureReason!!.contains("api_too_low"))
    }

    @Test
    fun `pre A14 (sdk 0 = unknown view) returns skipped api_too_low`() = runBlocking {
        // UnknownMediaProjectionManagerView returns sdk=0 — this exercises the
        // "consumer never overrode queryMediaProjectionManager()" code path.
        val ctx = fakeCtx(UnknownMediaProjectionManagerView)
        val result = probe.run(ctx)
        assertTrue(result.skipped)
        assertTrue(result.failureReason!!.contains("api_too_low"))
    }

    @Test
    fun `pre A14 never reports api_path 'unknown' in failure reason`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 28))
        assertTrue(result.skipped)
        // Acceptance criterion: never "unknown".
        assertFalse(
            result.failureReason!!.contains("unknown", ignoreCase = true),
            "skip reason must say api_too_low, never 'unknown' — got: ${result.failureReason}",
        )
    }

    // ── (1) API-34 gating — MediaProjectionManager only ───────────────────────

    @Test
    fun `API 34 reads screen_capture_callback but not frame_capture`() = runBlocking {
        // On API 34 the frame-capture (API-35) callback is unavailable; the
        // view MAY return null. The probe must still produce a clean,
        // non-failed result.
        val result = probe.run(
            ctxWith(sdk = 34, frameCapture = null, screenshotCapture = false),
        )
        assertFalse(result.failed, "API 34 must not fail; got reason=${result.failureReason}")
        assertFalse(result.skipped)
        assertEquals(0.0, result.score, 0.001)

        val frameCaptureEv = result.evidence.first { it.key == "mediaProjection.frame_capture_active" }
        assertEquals("unknown", frameCaptureEv.value, "API-35 signal must surface as 'unknown' on API 34")

        val apiPath = result.evidence.first { it.key == "api_path" }.value as String
        assertFalse(
            apiPath.contains("addScreenRecordingCallback"),
            "API 34 api_path must not advertise the API-35-only addScreenRecordingCallback path",
        )
        assertTrue(apiPath.contains("addScreenCaptureCallback"))
    }

    @Test
    fun `API 34 ignores frame_capture even if the view spuriously returns true`() = runBlocking {
        // Hardening: even if a misbehaving view returns true for the API-35
        // signal on a pre-API-35 device, the probe must not credit it.
        val result = probe.run(
            ctxWith(sdk = 34, frameCapture = true, screenshotCapture = false),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001,
            "API 34 must NOT credit the API-35 callback signal")
        val ev = result.evidence.first { it.key == "mediaProjection.frame_capture_active" }
        assertEquals("unknown", ev.value)
    }

    // ── (3) Smoke test — flip MediaProjection state across two runs ──────────

    @Test
    fun `smoke test - launching createScreenCaptureIntent flips probe from clean to detected`() = runBlocking {
        // Models the device-test harness: launch MediaProjection.createScreenCaptureIntent()
        // and expect the probe state to flip. Same view instance across two runs.
        val view = MutableMpmView(sdk = 35, frameCapture = false, screenshotCapture = false)
        val ctx = fakeCtx(view)

        val before = probe.run(ctx)
        assertFalse(before.failed)
        assertEquals(0.0, before.score, 0.001, "clean baseline must be score 0.0")

        // Harness "launches" MediaProjection.createScreenCaptureIntent() →
        // WindowManager.ScreenRecordingCallback fires → frame-capture goes true.
        view.frameCapture = true

        val after = probe.run(ctx)
        assertFalse(after.failed)
        assertTrue(
            after.score >= ScreenRecordingProbe.SCORE_SCREENSHOT_CAPTURE,
            "after launching createScreenCaptureIntent the probe must flip to score >= ${ScreenRecordingProbe.SCORE_SCREENSHOT_CAPTURE}, got ${after.score}",
        )
        assertTrue(after.score >= 0.50)
        assertEquals(
            true,
            after.evidence.first { it.key == "mediaProjection.frame_capture_active" }.value,
        )
    }

    // ── Score table coverage ──────────────────────────────────────────────────

    @Test
    fun `frame_capture active emits score 0_80`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35, frameCapture = true, screenshotCapture = false))
        assertEquals(ScreenRecordingProbe.SCORE_FRAME_CAPTURE, result.score, 0.001)
        assertTrue(result.confidence >= 0.90)
    }

    @Test
    fun `screen_capture callback alone emits score 0_50`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35, frameCapture = false, screenshotCapture = true))
        assertEquals(ScreenRecordingProbe.SCORE_SCREENSHOT_CAPTURE, result.score, 0.001)
    }

    @Test
    fun `both signals active scores as max not sum`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35, frameCapture = true, screenshotCapture = true))
        // max(0.80, 0.50) = 0.80 — these surfaces are correlated, NOT independent.
        assertEquals(ScreenRecordingProbe.SCORE_FRAME_CAPTURE, result.score, 0.001)
    }

    @Test
    fun `clean API 35 device emits score 0_0 with strong negative confidence`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35, frameCapture = false, screenshotCapture = false))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertTrue(result.confidence >= 0.85, "clean A15+ device must report high confidence, got ${result.confidence}")
    }

    @Test
    fun `clean API 34 device has lower confidence than API 35 because frame-capture is blind`() = runBlocking {
        val cleanA14 = probe.run(ctxWith(sdk = 34, frameCapture = null, screenshotCapture = false))
        val cleanA15 = probe.run(ctxWith(sdk = 35, frameCapture = false, screenshotCapture = false))
        assertFalse(cleanA14.failed)
        assertFalse(cleanA15.failed)
        assertEquals(0.0, cleanA14.score, 0.001)
        assertEquals(0.0, cleanA15.score, 0.001)
        assertTrue(
            cleanA14.confidence < cleanA15.confidence,
            "API 34 is blind to live recording — its clean-negative confidence must be < API 35; got ${cleanA14.confidence} vs ${cleanA15.confidence}",
        )
    }

    // ── Evidence schema ───────────────────────────────────────────────────────

    @Test
    fun `evidence reports all schema-stable keys regardless of API level`() = runBlocking {
        for (sdk in listOf(34, 35, 36)) {
            val ev = probe.run(ctxWith(sdk = sdk)).evidence.map { it.key }.toSet()
            assertTrue("mediaProjection.frame_capture_active" in ev, "missing key on sdk=$sdk: $ev")
            assertTrue("window.screen_capture_callback_fired" in ev, "missing key on sdk=$sdk: $ev")
            assertTrue("Build.VERSION.SDK_INT" in ev, "missing SDK_INT on sdk=$sdk: $ev")
            assertTrue("api_path" in ev, "missing api_path on sdk=$sdk: $ev")
        }
    }

    @Test
    fun `evidence value is 'unknown' string when the view returns null`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35, frameCapture = null, screenshotCapture = null))
        val frame = result.evidence.first { it.key == "mediaProjection.frame_capture_active" }
        val shot = result.evidence.first { it.key == "window.screen_capture_callback_fired" }
        assertEquals("unknown", frame.value)
        assertEquals("unknown", shot.value)
        assertEquals(0.0, result.score, 0.001)
    }

    @Test
    fun `evidence sdk_int reflects the queried sdk`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 36))
        assertEquals(36, result.evidence.first { it.key == "Build.VERSION.SDK_INT" }.value)
    }

    @Test
    fun `api_path includes addScreenRecordingCallback only when sdk is at least 35`() = runBlocking {
        val a14 = probe.run(ctxWith(sdk = 34)).evidence.first { it.key == "api_path" }.value as String
        val a15 = probe.run(ctxWith(sdk = 35)).evidence.first { it.key == "api_path" }.value as String
        assertFalse(a14.contains("addScreenRecordingCallback"))
        assertTrue(a15.contains("addScreenRecordingCallback"))
        // Both must advertise the API-34 callback.
        assertTrue(a14.contains("addScreenCaptureCallback"))
        assertTrue(a15.contains("addScreenCaptureCallback"))
    }

    // ── Failure path ──────────────────────────────────────────────────────────

    @Test
    fun `throwing view surfaces as ProbeResult failed (not skipped)`() = runBlocking {
        val throwingCtx = fakeCtx(object : MediaProjectionManagerView {
            override fun sdkInt(): Int = 35
            override fun isScreenCaptureCallbackActive(): Boolean? =
                throw IllegalStateException("boom")
            override fun isMediaProjectionFrameCaptureActive(): Boolean? = false
        })
        val result = probe.run(throwingCtx)
        assertTrue(result.failed)
        assertFalse(result.skipped, "internal exceptions must surface as failed, NOT as skipped")
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("ScreenRecordingProbe"))
    }

    // ── Default UnknownMediaProjectionManagerView contract ────────────────────

    @Test
    fun `UnknownMediaProjectionManagerView returns null for both signals`() {
        val v = UnknownMediaProjectionManagerView
        assertEquals(0, v.sdkInt())
        assertNull(v.isScreenCaptureCallbackActive())
        assertNull(v.isMediaProjectionFrameCaptureActive())
    }

    // ── Probe metadata invariants ─────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("runtime.screen_recording", probe.id)
    }

    @Test
    fun `probe rank is in A17 expansion range`() {
        assertTrue(probe.rank in 61..71, "rank ${probe.rank} outside A17 reservation 61..71")
        assertEquals(70, probe.rank, "A17 N10 must allocate to rank 70")
    }

    @Test
    fun `probe budget is within 5-second hard ceiling`() {
        assertTrue(probe.budgetMs <= 5000L)
    }

    @Test
    fun `probe runtime fits within budget on clean fast path`() = runBlocking {
        val result = probe.run(ctxWith(sdk = 35))
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }

    @Test
    fun `probe is deterministic for identical input`() = runBlocking {
        val view = MutableMpmView(sdk = 35, frameCapture = true, screenshotCapture = false)
        val ctx = fakeCtx(view)
        val r1 = probe.run(ctx)
        val r2 = probe.run(ctx)
        assertEquals(r1.score, r2.score, 0.001)
        assertEquals(r1.confidence, r2.confidence, 0.001)
        assertEquals(r1.evidence.map { it.key to it.value.toString() }, r2.evidence.map { it.key to it.value.toString() })
    }
}

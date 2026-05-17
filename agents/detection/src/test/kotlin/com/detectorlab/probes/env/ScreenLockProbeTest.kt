package com.detectorlab.probes.env

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ScreenLockProbe (CLO-5, A17 N7).
 *
 * Covers all three permission/SDK combinations the acceptance criterion
 * names: API ≥23 with `isDeviceSecure` available, API ≥23 with the service
 * throwing (`null` primary signal), and API <23 with only the legacy
 * `isKeyguardSecure` signal. Each branch is asserted for state, score,
 * confidence bucket, and evidence-shape invariants.
 */
class ScreenLockProbeTest {

    private val probe = ScreenLockProbe()

    private fun fakeCtx(
        sdkInt: Int,
        deviceSecure: Boolean?,
        keyguardSecure: Boolean?,
    ): ProbeContext = object : ProbeContext {
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
        override fun queryKeyguardManager(): KeyguardManagerView = object : KeyguardManagerView {
            override fun sdkInt() = sdkInt
            override fun isDeviceSecure() = deviceSecure
            override fun isKeyguardSecure() = keyguardSecure
        }
    }

    // ── Combination (a): API ≥23, device IS secure  →  pin_set ───────────────

    @Test
    fun `API 33 with isDeviceSecure=true emits pin_set with strong confidence and clean score`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 33, deviceSecure = true, keyguardSecure = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertTrue(result.confidence >= 0.85, "expected strong confidence, got ${result.confidence}")
        assertTrue(result.evidence.any { it.key == "state" && it.value == ScreenLockProbe.STATE_PIN_SET })
    }

    // ── Combination (b): API ≥23, device NOT secure  →  none (DETECTED) ──────

    @Test
    fun `API 33 with isDeviceSecure=false emits none with strong confidence and detection score`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 33, deviceSecure = false, keyguardSecure = false))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
        assertTrue(result.confidence >= 0.85)
        assertTrue(result.evidence.any { it.key == "state" && it.value == ScreenLockProbe.STATE_NONE })
    }

    // ── Combination (c): API <23 (no isDeviceSecure)  →  unknown, weak ───────

    @Test
    fun `API 21 with no keyguard signal emits unknown with weak confidence`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 21, deviceSecure = null, keyguardSecure = null))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        // The crux of the acceptance criterion: unknown must NOT be a
        // strong negative — it must be flagged as weak confidence.
        assertTrue(result.confidence <= 0.40, "expected weak confidence, got ${result.confidence}")
        assertTrue(result.evidence.any { it.key == "state" && it.value == ScreenLockProbe.STATE_UNKNOWN })
    }

    // ── Additional resilience: API <23 with positive legacy signal ───────────

    @Test
    fun `API 21 with isKeyguardSecure=true resolves to pin_set`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 21, deviceSecure = null, keyguardSecure = true))
        assertEquals(ScreenLockProbe.STATE_PIN_SET,
            result.evidence.first { it.key == "state" }.value)
        assertEquals(0.0, result.score, 0.001)
    }

    // ── Additional resilience: API ≥23 but primary signal threw (==null) ────

    @Test
    fun `API 33 with null isDeviceSecure falls back to keyguard signal`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 33, deviceSecure = null, keyguardSecure = false))
        // null + false → unknown, not "none" — must NOT be a strong false-negative.
        assertEquals(ScreenLockProbe.STATE_UNKNOWN,
            result.evidence.first { it.key == "state" }.value)
        assertTrue(result.confidence <= 0.40)
    }

    // ── Evidence-completeness invariant: both signals always present ─────────

    @Test
    fun `evidence always reports both KeyguardManager signals and the SDK_INT`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 30, deviceSecure = true, keyguardSecure = false))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("Build.VERSION.SDK_INT" in keys)
        assertTrue("KeyguardManager.isDeviceSecure" in keys)
        assertTrue("KeyguardManager.isKeyguardSecure" in keys)
        assertTrue("state" in keys)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("env.screen_lock", probe.id)
    }

    @Test
    fun `probe rank is in the A17 expansion range`() {
        // META-22 reserves 61..71 for A17 N1..N11.
        assertTrue(probe.rank in 61..71, "rank ${probe.rank} outside A17 reservation")
    }

    @Test
    fun `probe runtime fits within budget`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 33, deviceSecure = true, keyguardSecure = true))
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }

    // ── classify() exhaustive truth table ─────────────────────────────────────

    @Test
    fun `classify truth table - API gte 23`() {
        val p = probe
        assertEquals("pin_set", p.classify(23, deviceSecure = true,  keyguardSecure = true))
        assertEquals("pin_set", p.classify(23, deviceSecure = true,  keyguardSecure = false))
        assertEquals("none",    p.classify(23, deviceSecure = false, keyguardSecure = true))
        assertEquals("none",    p.classify(23, deviceSecure = false, keyguardSecure = false))
        // null primary → fall through to legacy branch
        assertEquals("pin_set", p.classify(23, deviceSecure = null,  keyguardSecure = true))
        assertEquals("unknown", p.classify(23, deviceSecure = null,  keyguardSecure = false))
        assertEquals("unknown", p.classify(23, deviceSecure = null,  keyguardSecure = null))
    }

    @Test
    fun `classify truth table - API lt 23`() {
        val p = probe
        assertEquals("pin_set", p.classify(21, deviceSecure = null, keyguardSecure = true))
        assertEquals("unknown", p.classify(21, deviceSecure = null, keyguardSecure = false))
        assertEquals("unknown", p.classify(21, deviceSecure = null, keyguardSecure = null))
        // even if deviceSecure leaks through on a buggy device, pre-M behaviour
        // ignores it (the platform did not implement that API yet)
        assertEquals("pin_set", p.classify(21, deviceSecure = true,  keyguardSecure = true))
        assertEquals("unknown", p.classify(21, deviceSecure = true,  keyguardSecure = false))
    }
}

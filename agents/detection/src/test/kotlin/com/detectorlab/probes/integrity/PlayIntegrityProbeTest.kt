package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PlayIntegrityProbe (integrity.play_integrity_signals, rank 71).
 *
 * Verifies the buildprop-surface inference of basic/device/strong integrity
 * verdicts described in PlayIntegrityProbe's KDoc.
 */
class PlayIntegrityProbeTest {

    private val probe = PlayIntegrityProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        props: Map<String, String?> = emptyMap(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = props[key]
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
    }

    /** Properties that describe a clean stock production device (all verdicts pass). */
    private fun cleanProductionProps(): Map<String, String> = mapOf(
        PlayIntegrityProbe.PROP_BUILD_TAGS to "release-keys",
        PlayIntegrityProbe.PROP_DEBUGGABLE to "0",
        PlayIntegrityProbe.PROP_BUILD_FINGERPRINT to
            "google/sunfish/sunfish:13/TQ3A.230901.001/10750268:user/release-keys",
        PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE to "green",
        PlayIntegrityProbe.PROP_FLASH_LOCKED to "1",
    )

    // ── basicIntegrity-fail predictor (test-keys + debuggable) ────────────────

    @Test
    fun `test-keys plus debuggable predicts basic integrity fail — score is 0_95`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_TAGS] = "test-keys"
            this[PlayIntegrityProbe.PROP_DEBUGGABLE] = "1"
        }
        val result = probe.run(fakeCtx(props))
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
        val basicEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_BASIC_FAIL }
        assertEquals(true, basicEv?.value)
    }

    @Test
    fun `test-keys alone (without debuggable) does not predict basic integrity fail`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_TAGS] = "test-keys"
            // debuggable stays "0"
        }
        val result = probe.run(fakeCtx(props))
        // Neither basic, device, nor strong verdict fails.
        assertEquals(0.0, result.score)
        val basicEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_BASIC_FAIL }
        assertEquals(false, basicEv?.value)
    }

    // ── deviceIntegrity-fail predictor (emulator fingerprint) ─────────────────

    @Test
    fun `generic fingerprint predicts device integrity fail — score is 0_85`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_FINGERPRINT] =
                "generic/sdk_gphone64_arm64/emu64a:14/UPB5.230623.003/10817346:user/release-keys"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
        val deviceEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_DEVICE_FAIL }
        assertEquals(true, deviceEv?.value)
    }

    @Test
    fun `vbox in fingerprint predicts device integrity fail — score is 0_85`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_FINGERPRINT] =
                "Android-x86/vbox86p/vbox86p:9/PI/eng.buildbot.20191207.121212:userdebug/test-keys"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `redroid in fingerprint predicts device integrity fail`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_FINGERPRINT] =
                "redroid/redroid_arm64/redroid:12/SP1A.210812.016/eng.root.20231201.181155:userdebug/test-keys"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ranchu in fingerprint predicts device integrity fail`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_BUILD_FINGERPRINT] =
                "Android/aosp_arm64-userdebug/ranchu:13/AOSP.MASTER/eng.test.20230401:userdebug/test-keys"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    // ── strongIntegrity-fail predictor (verifiedboot violation) ───────────────

    @Test
    fun `verifiedbootstate orange predicts strong integrity fail — score is 0_70`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE] = "orange"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.70, result.score)
        val strongEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_STRONG_FAIL }
        assertEquals(true, strongEv?.value)
    }

    @Test
    fun `verifiedbootstate yellow predicts strong integrity fail — score is 0_70`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE] = "yellow"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `verifiedbootstate empty does NOT predict strong integrity fail`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE] = ""
        }
        val result = probe.run(fakeCtx(props))
        // Empty string is treated as "unknown / not violated" per spec.
        assertEquals(0.0, result.score)
    }

    // ── strongIntegrity-fail predictor (flash.locked = 0) ─────────────────────

    @Test
    fun `flash_locked 0 alone predicts strong integrity fail — score is 0_50`() = runBlocking {
        // Setup: clean prod props EXCEPT flash.locked = 0 AND verifiedbootstate
        // also empty so the verifiedboot signal does not crowd this case out.
        val props = cleanProductionProps().toMutableMap().apply {
            this[PlayIntegrityProbe.PROP_FLASH_LOCKED] = "0"
            this[PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE] = "green"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.50, result.score)
        val strongEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_STRONG_FAIL }
        assertEquals(true, strongEv?.value)
    }

    // ── Clean device (all verdicts pass) ──────────────────────────────────────

    @Test
    fun `all clean production — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(false, result.evidence.find { it.key == PlayIntegrityProbe.EV_BASIC_FAIL }?.value)
        assertEquals(false, result.evidence.find { it.key == PlayIntegrityProbe.EV_DEVICE_FAIL }?.value)
        assertEquals(false, result.evidence.find { it.key == PlayIntegrityProbe.EV_STRONG_FAIL }?.value)
    }

    @Test
    fun `clean production — confidence is 0_85 (3 or more of 4 props read)`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertEquals(0.85, result.confidence)
    }

    // ── Multi-signal precedence (max wins) ────────────────────────────────────

    @Test
    fun `basic AND device AND strong all fire — score is 0_95 (basic dominates)`() = runBlocking {
        val props = mapOf(
            PlayIntegrityProbe.PROP_BUILD_TAGS to "test-keys",
            PlayIntegrityProbe.PROP_DEBUGGABLE to "1",
            PlayIntegrityProbe.PROP_BUILD_FINGERPRINT to "generic/sdk/x86:13/eng:userdebug/test-keys",
            PlayIntegrityProbe.PROP_VERIFIEDBOOTSTATE to "orange",
            PlayIntegrityProbe.PROP_FLASH_LOCKED to "0",
        )
        val result = probe.run(fakeCtx(props))
        assertEquals(0.95, result.score)
    }

    // ── Degraded contexts ─────────────────────────────────────────────────────

    @Test
    fun `all-null context — score 0_0, NOT failed, degraded confidence`() = runBlocking {
        val result = probe.run(fakeCtx(emptyMap()))
        assertFalse(result.failed, "all-null context must not fail the probe")
        assertEquals(0.0, result.score)
        assertEquals(PlayIntegrityProbe.CONFIDENCE_DEGRADED, result.confidence)
    }

    @Test
    fun `only 2 of 4 gated props readable — confidence is 0_50 (degraded)`() = runBlocking {
        val props = mapOf(
            PlayIntegrityProbe.PROP_BUILD_TAGS to "release-keys",
            PlayIntegrityProbe.PROP_DEBUGGABLE to "0",
        )
        val result = probe.run(fakeCtx(props))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `exactly 3 of 4 gated props readable — confidence is 0_85 (full)`() = runBlocking {
        val props = mapOf(
            PlayIntegrityProbe.PROP_BUILD_TAGS to "release-keys",
            PlayIntegrityProbe.PROP_DEBUGGABLE to "0",
            PlayIntegrityProbe.PROP_BUILD_FINGERPRINT to
                "google/sunfish/sunfish:13/TQ3A.230901.001/10750268:user/release-keys",
        )
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.confidence)
    }

    // ── Evidence shape ────────────────────────────────────────────────────────

    @Test
    fun `evidence includes method tag with buildprop-surface-inference value`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        val methodEv = result.evidence.find { it.key == PlayIntegrityProbe.EV_METHOD }
        assertEquals("buildprop-surface-inference", methodEv?.value)
    }

    @Test
    fun `evidence covers all three predicted-fail booleans`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue(PlayIntegrityProbe.EV_BASIC_FAIL in keys)
        assertTrue(PlayIntegrityProbe.EV_DEVICE_FAIL in keys)
        assertTrue(PlayIntegrityProbe.EV_STRONG_FAIL in keys)
    }

    @Test
    fun `method string is buildprop-surface-inference`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertEquals("buildprop-surface-inference", result.method)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is integrity_play_integrity_signals`() {
        assertEquals("integrity.play_integrity_signals", probe.id)
    }

    @Test
    fun `probe rank is 71`() {
        assertEquals(71, probe.rank)
    }

    @Test
    fun `probe category is INTEGRITY`() {
        assertEquals(ProbeCategory.INTEGRITY, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

package com.detectorlab.probes.ui

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
 * Unit tests for AudioFingerprintProbe (ui.audio_fingerprint, inventory
 * rank 54 — DECLARATIVE VARIANT). Tests cover all five non-clean scoring
 * branches, the clean branch, declarative confidence cap, no-observation
 * fallback, cascade ordering, crash safety, and the isSoftwareHal helper.
 */
class AudioFingerprintProbeTest {

    private val probe = AudioFingerprintProbe()

    /**
     * Fake context backed by an in-memory property map plus an explicit
     * set of file paths that exist. Throws-flag enables crash-safety
     * tests against the property accessor.
     */
    private fun fakeCtx(
        properties: Map<String, String> = mapOf(
            // Default = a clean Pixel-class audio HAL (real codec name).
            AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
        ),
        existingFiles: Set<String> = setOf(
            AudioFingerprintProbe.PATH_ALSA_CONTROL_C0,
        ),
        propertyThrows: Boolean = false,
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return properties[key]
        }
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated EACCES")
            return path in existingFiles
        }
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

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `Pixel 7 trondheim_audio HAL + ALSA device present — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — declarative confidence cap is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.70, result.confidence)
    }

    @Test
    fun `Samsung S22 exynos_audio_2200 HAL — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "exynos_audio_2200",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Qualcomm qcom_aud_codec HAL — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "qcom_aud_codec_xxxx",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `loopback unset + silent_in unset + dock_aplc unset on real codec — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "0",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "0",
                    AudioFingerprintProbe.PROP_RO_CONFIG_AUDIO_DOCK_APLC to "false",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Software-only audio HAL signature (0.85) ─────────────────────────────

    @Test
    fun `snd_card_dummy HAL — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to AudioFingerprintProbe.HAL_SND_CARD_DUMMY,
                ),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `snd_card_dummy — pattern is software_only_audio_hal`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to AudioFingerprintProbe.HAL_SND_CARD_DUMMY,
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_SOFTWARE_HAL, ev?.value)
    }

    @Test
    fun `HAL containing 'stub' substring — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "audio_stub",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `HAL containing 'dummy' substring case-insensitive — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "ALSA_DUMMY_HAL",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    // ── Audio loopback enabled (0.70) ────────────────────────────────────────

    @Test
    fun `persist_audio_loopback equals 1 — score is 0_70`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    // Real codec HAL but loopback is on → still suspect.
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "1",
                ),
            ),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `loopback enabled — pattern is audio_loopback_enabled`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "1",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_LOOPBACK_ENABLED, ev?.value)
    }

    @Test
    fun `loopback set to 0 — does NOT fire`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "0",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `loopback set to true (not '1') — does NOT fire (strict equality)`() = runBlocking {
        // The property surface uses "1"/"0" Android convention; "true" is
        // not the canonical sentinel. Only "1" trips the rule.
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "true",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Silent audio HAL (0.70) ──────────────────────────────────────────────

    @Test
    fun `ro_audio_silent_in equals 1 — score is 0_70`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "1",
                ),
            ),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `silent_in enabled — pattern is silent_audio_hal_in`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "1",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_SILENT_IN, ev?.value)
    }

    @Test
    fun `silent_in set to 0 — does NOT fire`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "0",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── No HAL + no ALSA device (0.50) ───────────────────────────────────────

    @Test
    fun `empty HAL prop AND no controlC0 — score is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                ),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.50, result.score)
    }

    @Test
    fun `empty HAL + no device — pattern is no_hal_no_alsa_device`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                ),
                existingFiles = emptySet(),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_NO_HAL_NO_DEVICE, ev?.value)
    }

    @Test
    fun `empty HAL prop BUT controlC0 present — does NOT fire (rule requires both)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                ),
                existingFiles = setOf(AudioFingerprintProbe.PATH_ALSA_CONTROL_C0),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null HAL prop AND no controlC0 — does NOT fire (rule requires set-but-empty)`() = runBlocking {
        // Distinguishes "key not in map" (null) from "key → empty string"
        // (empty). Only the latter trips the rule — null is "no observation
        // possible" and lands in the clean branch with confidence cap.
        val result = probe.run(
            fakeCtx(
                properties = emptyMap(),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Genymotion docking-audio profile (0.50) ──────────────────────────────

    @Test
    fun `ro_config_audio_dock_uses_aplc equals true — score is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_CONFIG_AUDIO_DOCK_APLC to "true",
                ),
            ),
        )
        assertEquals(0.50, result.score)
    }

    @Test
    fun `dock_aplc enabled — pattern is genymotion_dock_aplc`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_CONFIG_AUDIO_DOCK_APLC to "true",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_GENYMOTION_DOCK_APLC, ev?.value)
    }

    @Test
    fun `dock_aplc set to false — does NOT fire`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_RO_CONFIG_AUDIO_DOCK_APLC to "false",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `software HAL beats loopback in cascade`() = runBlocking {
        // Both predicates true; software_hal (0.85) wins over loopback (0.70).
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "snd_card_dummy",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "1",
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_SOFTWARE_HAL, ev?.value)
    }

    @Test
    fun `loopback beats silent_in in cascade`() = runBlocking {
        // loopback (0.70) cascade-position wins over silent_in (also 0.70) —
        // both have equal score but the source-order cascade has loopback first.
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "trondheim_audio",
                    AudioFingerprintProbe.PROP_PERSIST_AUDIO_LOOPBACK to "1",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "1",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_LOOPBACK_ENABLED, ev?.value)
    }

    @Test
    fun `silent_in beats no_hal_no_device in cascade`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                    AudioFingerprintProbe.PROP_RO_AUDIO_SILENT_IN to "1",
                ),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.70, result.score)
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_SILENT_IN, ev?.value)
    }

    @Test
    fun `no_hal_no_device beats genymotion_dock_aplc in cascade`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                    AudioFingerprintProbe.PROP_RO_CONFIG_AUDIO_DOCK_APLC to "true",
                ),
                existingFiles = emptySet(),
            ),
        )
        // Both 0.50 — source-order cascade picks no_hal_no_device first.
        val ev = result.evidence.find { it.key == "audio.pattern" }
        assertEquals(AudioFingerprintProbe.PATTERN_NO_HAL_NO_DEVICE, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `any property observable — confidence 0_70`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.70, result.confidence)
    }

    @Test
    fun `no properties observable AND no controlC0 — confidence 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = emptyMap(),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no properties observable BUT controlC0 present — confidence 0_70`() = runBlocking {
        // Positive observation of the ALSA device path counts as observable.
        val result = probe.run(
            fakeCtx(
                properties = emptyMap(),
                existingFiles = setOf(AudioFingerprintProbe.PATH_ALSA_CONTROL_C0),
            ),
        )
        assertEquals(0.70, result.confidence)
    }

    @Test
    fun `no observation — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = emptyMap(),
                existingFiles = emptySet(),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `fileExists throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `all surfaces throw — degrades to no-observation`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                propertyThrows = true,
                fileExistsThrows = true,
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isSoftwareHal detects canonical snd_card_dummy`() {
        assertTrue(AudioFingerprintProbe.isSoftwareHal("snd_card_dummy"))
    }

    @Test
    fun `isSoftwareHal detects stub substring case-insensitive`() {
        assertTrue(AudioFingerprintProbe.isSoftwareHal("audio_stub"))
        assertTrue(AudioFingerprintProbe.isSoftwareHal("STUB_HAL"))
        assertTrue(AudioFingerprintProbe.isSoftwareHal("AlsaStubMixer"))
    }

    @Test
    fun `isSoftwareHal detects dummy substring case-insensitive`() {
        assertTrue(AudioFingerprintProbe.isSoftwareHal("audio_dummy"))
        assertTrue(AudioFingerprintProbe.isSoftwareHal("DUMMY"))
        assertTrue(AudioFingerprintProbe.isSoftwareHal("DummyDeviceHal"))
    }

    @Test
    fun `isSoftwareHal returns false for real codec names`() {
        assertFalse(AudioFingerprintProbe.isSoftwareHal("trondheim_audio"))
        assertFalse(AudioFingerprintProbe.isSoftwareHal("exynos_audio_2200"))
        assertFalse(AudioFingerprintProbe.isSoftwareHal("qcom_aud_codec_xxxx"))
        assertFalse(AudioFingerprintProbe.isSoftwareHal("default"))
    }

    @Test
    fun `isSoftwareHal returns false for null and empty`() {
        // Empty HAL has its own separate rule (PATTERN_NO_HAL_NO_DEVICE);
        // isSoftwareHal MUST return false for empty so the cascade routes
        // the empty case to the correct downstream rule.
        assertFalse(AudioFingerprintProbe.isSoftwareHal(null))
        assertFalse(AudioFingerprintProbe.isSoftwareHal(""))
    }

    @Test
    fun `SOFTWARE_HAL_SUBSTRINGS list pinned (drift alarm)`() {
        // Future maintainer changing this list must update this test
        // deliberately. The substrings are intentionally narrow — they're
        // the only tokens that appear in stub HAL names but never in real
        // codec names (verified against Pixel / Samsung / Xiaomi audio HAL
        // strings).
        assertEquals(
            listOf("stub", "dummy"),
            AudioFingerprintProbe.SOFTWARE_HAL_SUBSTRINGS,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "audio.hal_name",
                "audio.loopback_enabled",
                "audio.silent_in",
                "audio.dock_aplc",
                "audio.alsa_controlC0_present",
                "audio.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `hal_name evidence reflects supplier value`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "snd_card_dummy",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.hal_name" }
        assertEquals("snd_card_dummy", ev?.value)
    }

    @Test
    fun `null hal_name — evidence is unavailable`() = runBlocking {
        val result = probe.run(
            fakeCtx(properties = emptyMap()),
        )
        val ev = result.evidence.find { it.key == "audio.hal_name" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `empty hal_name — evidence shows empty placeholder`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    AudioFingerprintProbe.PROP_RO_HARDWARE_AUDIO to "",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "audio.hal_name" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `alsa_controlC0 evidence — true when device present`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "audio.alsa_controlC0_present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `alsa_controlC0 evidence — false when device absent`() = runBlocking {
        val result = probe.run(
            fakeCtx(existingFiles = emptySet()),
        )
        val ev = result.evidence.find { it.key == "audio.alsa_controlC0_present" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Declarative inference from audio HAL properties (ro.hardware.audio, " +
                "persist.audio.loopback, ro.audio.silent.in, ro.config.audio_dock_uses_aplc) " +
                "+ /dev/snd/controlC0 presence; predicts WebView AudioContext FFT " +
                "fingerprint anomaly without measuring it directly",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_audio_fingerprint`() {
        assertEquals("ui.audio_fingerprint", probe.id)
    }

    @Test
    fun `probe rank is 54`() {
        assertEquals(54, probe.rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, probe.category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // Matches shared/probes/inventory.yml rank-54 severity=trace.
        assertEquals(ProbeSeverity.TRACE, probe.severity)
    }

    @Test
    fun `probe android layer is APPLICATION`() {
        // Matches inventory.yml rank-54 android_layer=application
        // (mitigation_layer not_native — WebView-only surface).
        assertEquals(AndroidLayer.APPLICATION, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

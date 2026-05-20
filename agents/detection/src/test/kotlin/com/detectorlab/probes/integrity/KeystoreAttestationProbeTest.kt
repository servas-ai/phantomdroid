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
 * Unit tests for KeystoreAttestationProbe (integrity.keystore_attestation, rank 6).
 *
 * Verifies the declarative-prop-and-file-inference scoring of TEE / hardware-
 * keystore attestation availability described in the probe's KDoc.
 */
class KeystoreAttestationProbeTest {

    private val probe = KeystoreAttestationProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        props: Map<String, String?> = emptyMap(),
        existingFiles: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = props[key]
        override fun fileExists(path: String) = path in existingFiles
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

    /** Pixel-shape clean property surface — TEE present, bootloader locked, Knox OK. */
    private fun cleanPixelProps(): Map<String, String> = mapOf(
        KeystoreAttestationProbe.PROP_VERITYMODE to "enforcing",
        KeystoreAttestationProbe.PROP_FLASH_LOCKED to "1",
        KeystoreAttestationProbe.PROP_WARRANTY_BIT to "0",
        KeystoreAttestationProbe.PROP_WARRANTY to "1",
        KeystoreAttestationProbe.PROP_BOOTMODE to "normal",
        KeystoreAttestationProbe.PROP_HARDWARE_KEYSTORE to "gs201",
        KeystoreAttestationProbe.PROP_CPU_ABILIST to "arm64-v8a,armeabi-v7a,armeabi",
    )

    /** Clean files: /dev/keymaster exists on a real device with a keystore HAL. */
    private fun cleanPixelFiles(): Set<String> = setOf(KeystoreAttestationProbe.PATH_DEV_KEYMASTER)

    // ── Clean Pixel-shape baseline ────────────────────────────────────────────

    @Test
    fun `clean Pixel-shape props score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean Pixel-shape confidence is 0_75 (declarative inference)`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        assertEquals(KeystoreAttestationProbe.CONFIDENCE_DECLARATIVE, result.confidence)
    }

    // ── 0.95 tier — unlocked bootloader AND verity off ────────────────────────

    @Test
    fun `unlocked bootloader plus empty veritymode scores 0_95`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_FLASH_LOCKED] = "0"
            this[KeystoreAttestationProbe.PROP_VERITYMODE] = ""
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `unlocked bootloader alone with veritymode set does not hit 0_95 tier`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_FLASH_LOCKED] = "0"
            // veritymode stays "enforcing" — only HALF of the 0.95 combo fires
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        // 0.95 requires BOTH; with veritymode=enforcing the score lands at 0.0
        // (no other failure surface present).
        assertEquals(0.0, result.score)
    }

    // ── 0.85 tier — Knox warranty tripped ─────────────────────────────────────

    @Test
    fun `warranty_bit 1 scores 0_85 (Knox tripped)`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_WARRANTY_BIT] = "1"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.85, result.score)
        val knoxEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_KNOX_STATUS }
        assertEquals(KeystoreAttestationProbe.KNOX_TRIPPED, knoxEv?.value)
    }

    @Test
    fun `warranty 0 scores 0_85 (Knox tripped via alternate prop)`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_WARRANTY] = "0"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.85, result.score)
    }

    // ── 0.85 tier — abnormal bootmode ─────────────────────────────────────────

    @Test
    fun `bootmode recovery scores 0_85`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_BOOTMODE] = "recovery"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.85, result.score)
        val bootmodeEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_ABNORMAL_BOOTMODE }
        assertEquals(true, bootmodeEv?.value)
    }

    @Test
    fun `bootmode fastboot scores 0_85`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_BOOTMODE] = "fastboot"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.85, result.score)
    }

    // ── 0.70 tier — hardware keystore HAL absent ──────────────────────────────

    @Test
    fun `empty hardware_keystore scores 0_70`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_HARDWARE_KEYSTORE] = ""
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.70, result.score)
    }

    // ── 0.70 tier — Houdini dual-arch bridge ──────────────────────────────────

    @Test
    fun `dual-arch Houdini bridge scores 0_70`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_CPU_ABILIST] = "x86_64,arm64-v8a"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.70, result.score)
        val houdiniEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_HOUDINI_DUAL_ARCH }
        assertEquals(true, houdiniEv?.value)
    }

    @Test
    fun `pure ARM64 abilist does NOT trip Houdini rule`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_CPU_ABILIST] = "arm64-v8a,armeabi-v7a,armeabi"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.0, result.score)
        val houdiniEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_HOUDINI_DUAL_ARCH }
        assertEquals(false, houdiniEv?.value)
    }

    // ── 0.50 tier — keymaster device node missing ─────────────────────────────

    @Test
    fun `keymaster device missing scores 0_5`() = runBlocking {
        // All props clean, but /dev/keymaster missing → 0.50 SCORE_KEYMASTER_DEV_MISSING
        val result = probe.run(fakeCtx(cleanPixelProps(), existingFiles = emptySet()))
        assertEquals(0.50, result.score)
    }

    // ── All-null context (degraded but not failed) ────────────────────────────

    @Test
    fun `all-null context — NOT failed, declarative confidence preserved`() = runBlocking {
        // empty props + empty files: hardware_keystore null → 0.70 surface fires,
        // keymaster missing → 0.50 surface fires; max-wins=0.70. The probe
        // MUST NOT fail, even though the surface is empty.
        val result = probe.run(fakeCtx(emptyMap(), emptySet()))
        assertFalse(result.failed)
        assertEquals(KeystoreAttestationProbe.CONFIDENCE_DECLARATIVE, result.confidence)
        // hardware_keystore_absent (0.70) wins over keymaster_missing (0.50).
        assertEquals(0.70, result.score)
    }

    // ── Multi-signal precedence (max wins) ────────────────────────────────────

    @Test
    fun `unlocked bootloader plus Knox tripped — 0_95 dominates 0_85`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_FLASH_LOCKED] = "0"
            this[KeystoreAttestationProbe.PROP_VERITYMODE] = ""
            this[KeystoreAttestationProbe.PROP_WARRANTY_BIT] = "1"
        }
        val result = probe.run(fakeCtx(props, cleanPixelFiles()))
        assertEquals(0.95, result.score)
    }

    // ── Evidence shape ────────────────────────────────────────────────────────

    @Test
    fun `evidence includes declarative_only true`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        val declarative = result.evidence.find { it.key == KeystoreAttestationProbe.EV_DECLARATIVE_ONLY }
        assertEquals(true, declarative?.value)
    }

    @Test
    fun `evidence includes method tag declarative-prop-and-file-inference`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        val methodEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_METHOD }
        assertEquals(KeystoreAttestationProbe.METHOD, methodEv?.value)
    }

    @Test
    fun `evidence keys are all namespaced under keystore_attestation`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        result.evidence.forEach { ev ->
            assertTrue(
                ev.key.startsWith("keystore_attestation."),
                "evidence key '${ev.key}' is not namespaced under 'keystore_attestation.'",
            )
        }
    }

    @Test
    fun `method string is declarative-prop-and-file-inference`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        assertEquals(KeystoreAttestationProbe.METHOD, result.method)
    }

    @Test
    fun `tee_absent is true when both hardware_keystore empty AND keymaster missing`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[KeystoreAttestationProbe.PROP_HARDWARE_KEYSTORE] = ""
        }
        val result = probe.run(fakeCtx(props, existingFiles = emptySet()))
        val teeEv = result.evidence.find { it.key == KeystoreAttestationProbe.EV_TEE_ABSENT }
        assertEquals(true, teeEv?.value)
    }

    @Test
    fun `bootloader_locked evidence is true when flash_locked is 1`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        val ev = result.evidence.find { it.key == KeystoreAttestationProbe.EV_BOOTLOADER_LOCKED }
        assertEquals(true, ev?.value)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is integrity_keystore_attestation`() {
        assertEquals("integrity.keystore_attestation", probe.id)
    }

    @Test
    fun `probe rank is 6`() {
        assertEquals(6, probe.rank)
    }

    @Test
    fun `probe category is INTEGRITY`() {
        assertEquals(ProbeCategory.INTEGRITY, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPixelProps(), cleanPixelFiles()))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

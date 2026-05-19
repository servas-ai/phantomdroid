package com.detectorlab.probes.env

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
 * Unit tests for BootloaderProbe (env.bootloader, inventory rank 13).
 */
class BootloaderProbeTest {

    private val probe = BootloaderProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        props: Map<String, String?> = emptyMap(),
        propThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propThrows) throw RuntimeException("simulated property service unavailable")
            return props[key]
        }
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

    private fun cleanProductionProps(): Map<String, String> = mapOf(
        BootloaderProbe.PROP_VBMETA_DEVICE_STATE to "green",
        BootloaderProbe.PROP_VERIFIEDBOOTSTATE to "green",
        BootloaderProbe.PROP_FLASH_LOCKED to "1",
        BootloaderProbe.PROP_OEM_UNLOCK_SUPPORTED to "0",
        BootloaderProbe.PROP_RO_SECURE to "1",
        BootloaderProbe.PROP_RO_DEBUGGABLE to "0",
        BootloaderProbe.PROP_WARRANTY_BIT_BOOT to "0",
        BootloaderProbe.PROP_WARRANTY_BIT_LEGACY to "0",
    )

    // ── Clean production device ───────────────────────────────────────────────

    @Test
    fun `clean production device — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean production device — confidence is 0_99`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertEquals(0.99, result.confidence)
    }

    @Test
    fun `clean production device — all primary props recorded with expected values`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        val vbmeta = result.evidence.find { it.key == BootloaderProbe.PROP_VBMETA_DEVICE_STATE }
        assertEquals("green", vbmeta?.value)
        assertEquals("green", vbmeta?.expected)
    }

    // ── vbmeta orange (unlocked) ──────────────────────────────────────────────

    @Test
    fun `vbmeta orange — score is 1_0`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "orange"
        }
        val result = probe.run(fakeCtx(props))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `verifiedbootstate orange — score is 1_0`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VERIFIEDBOOTSTATE] = "orange"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `flash_locked 0 — score is 1_0`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_FLASH_LOCKED] = "0"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(1.0, result.score)
    }

    // ── vbmeta red (verification failed) ──────────────────────────────────────

    @Test
    fun `vbmeta red — score is 1_0`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "red"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(1.0, result.score)
    }

    // ── vbmeta yellow (custom keys) ───────────────────────────────────────────

    @Test
    fun `vbmeta yellow — score is 0_9`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "yellow"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.9, result.score)
    }

    // ── oem_unlock_supported=1 while claiming locked ─────────────────────────

    @Test
    fun `oem_unlock_supported 1 while locked — score is 0_9`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_OEM_UNLOCK_SUPPORTED] = "1"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `oem_unlock_supported 1 when also unlocked — score is 1_0 (unlock dominates)`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_OEM_UNLOCK_SUPPORTED] = "1"
            this[BootloaderProbe.PROP_FLASH_LOCKED] = "0"
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "orange"
            this[BootloaderProbe.PROP_VERIFIEDBOOTSTATE] = "orange"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(1.0, result.score)
    }

    // ── Dev-build leakage ─────────────────────────────────────────────────────

    @Test
    fun `ro_secure 0 — score is 0_85`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_RO_SECURE] = "0"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_debuggable 1 — score is 0_85`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_RO_DEBUGGABLE] = "1"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_secure 0 AND ro_debuggable 1 — score is still 0_85 (max not sum)`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_RO_SECURE] = "0"
            this[BootloaderProbe.PROP_RO_DEBUGGABLE] = "1"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.85, result.score)
    }

    // ── Multi-signal precedence (max wins) ────────────────────────────────────

    @Test
    fun `yellow vbmeta AND ro_debuggable 1 — score is 0_9 (yellow dominates dev-leak)`() = runBlocking {
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "yellow"
            this[BootloaderProbe.PROP_RO_DEBUGGABLE] = "1"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `orange vbmeta AND yellow verifiedbootstate — score is 1_0 (orange wins)`() = runBlocking {
        // Edge case: vbmeta says orange, older verifiedbootstate says yellow.
        // Highest-severity reading dominates.
        val props = cleanProductionProps().toMutableMap().apply {
            this[BootloaderProbe.PROP_VBMETA_DEVICE_STATE] = "orange"
            this[BootloaderProbe.PROP_VERIFIEDBOOTSTATE] = "yellow"
        }
        val result = probe.run(fakeCtx(props))
        assertEquals(1.0, result.score)
    }

    // ── Confidence based on primary-prop count ────────────────────────────────

    @Test
    fun `all 4 primary props present — confidence is 0_99`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertEquals(0.99, result.confidence)
    }

    @Test
    fun `2 primary props present — confidence is 0_99`() = runBlocking {
        val props = mapOf(
            BootloaderProbe.PROP_VBMETA_DEVICE_STATE to "green",
            BootloaderProbe.PROP_FLASH_LOCKED to "1",
        )
        val result = probe.run(fakeCtx(props))
        assertEquals(0.99, result.confidence)
    }

    @Test
    fun `1 primary prop present — confidence is 0_60`() = runBlocking {
        val props = mapOf(
            BootloaderProbe.PROP_VBMETA_DEVICE_STATE to "green",
        )
        val result = probe.run(fakeCtx(props))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `no primary props present — confidence is 0_30 (degraded)`() = runBlocking {
        val result = probe.run(fakeCtx(emptyMap()))
        assertFalse(result.failed)
        assertEquals(0.30, result.confidence)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `no primary props but secondary props say dev build — score is 0_85, confidence 0_30`() = runBlocking {
        val props = mapOf(
            BootloaderProbe.PROP_RO_SECURE to "0",
        )
        val result = probe.run(fakeCtx(props))
        // No primary signal at all → confidence floor 0.30.
        // ro.secure==0 still raises score: dev-build leakage stays observable
        // even with the verified-boot props missing.
        assertEquals(0.85, result.score)
        assertEquals(0.30, result.confidence)
    }

    // ── getSystemProperty throws (e.g. SELinux denies access) ─────────────────

    @Test
    fun `getSystemProperty throws on every key — no crash, score 0_0, confidence 0_30`() = runBlocking {
        val result = probe.run(fakeCtx(propThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — evidence reports missing values`() = runBlocking {
        val result = probe.run(fakeCtx(propThrows = true))
        val vbmeta = result.evidence.find { it.key == BootloaderProbe.PROP_VBMETA_DEVICE_STATE }
        assertEquals("missing", vbmeta?.value)
    }

    // ── Evidence coverage and expected values ────────────────────────────────

    @Test
    fun `evidence covers every probed property`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        val expectedKeys = setOf(
            BootloaderProbe.PROP_VBMETA_DEVICE_STATE,
            BootloaderProbe.PROP_VERIFIEDBOOTSTATE,
            BootloaderProbe.PROP_FLASH_LOCKED,
            BootloaderProbe.PROP_OEM_UNLOCK_SUPPORTED,
            BootloaderProbe.PROP_RO_SECURE,
            BootloaderProbe.PROP_RO_DEBUGGABLE,
            BootloaderProbe.PROP_WARRANTY_BIT_BOOT,
            BootloaderProbe.PROP_WARRANTY_BIT_LEGACY,
        )
        for (key in expectedKeys) {
            assertTrue(
                result.evidence.any { it.key == key },
                "missing evidence for $key",
            )
        }
    }

    @Test
    fun `evidence expected values match spec`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        val expectedMap = mapOf(
            BootloaderProbe.PROP_VBMETA_DEVICE_STATE to "green",
            BootloaderProbe.PROP_VERIFIEDBOOTSTATE to "green",
            BootloaderProbe.PROP_FLASH_LOCKED to "1",
            BootloaderProbe.PROP_OEM_UNLOCK_SUPPORTED to "0",
            BootloaderProbe.PROP_RO_SECURE to "1",
            BootloaderProbe.PROP_RO_DEBUGGABLE to "0",
            BootloaderProbe.PROP_WARRANTY_BIT_BOOT to "0",
            BootloaderProbe.PROP_WARRANTY_BIT_LEGACY to "0",
        )
        for ((key, expected) in expectedMap) {
            val ev = result.evidence.find { it.key == key }
            assertEquals(expected, ev?.expected, "expected mismatch for $key")
        }
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx(cleanProductionProps()))
        assertEquals(
            "Read ro.boot.* / ro.oem_unlock_supported / ro.secure / " +
                "ro.debuggable / ro.boot.warranty_bit properties and compare " +
                "against expected production-locked values",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is env_bootloader`() {
        assertEquals("env.bootloader", probe.id)
    }

    @Test
    fun `probe rank is 13`() {
        assertEquals(13, probe.rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
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

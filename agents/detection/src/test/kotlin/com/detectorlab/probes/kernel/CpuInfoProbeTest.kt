package com.detectorlab.probes.kernel

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
 * Unit tests for CpuInfoProbe (kernel.cpuinfo_bogomips_implementer, CLO-20).
 */
class CpuInfoProbeTest {

    private val probe = CpuInfoProbe()

    // ── Pixel 8-like ARM cpuinfo (clean) ──────────────────────────────────────

    private val pixel8CpuInfo = """
        processor       : 0
        BogoMIPS        : 38.40
        Features        : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp
        CPU implementer : 0x41
        CPU architecture: 8
        CPU variant     : 0x2
        CPU part        : 0xd05
        CPU revision    : 0
    """.trimIndent()

    private val pixel8HwcapArm = (CpuInfoProbe.ARM_NEON_BIT or 0x0000001L).toString()  // NEON present, no x86 FPU

    private fun fakeCtx(
        cpuinfoContent: String? = pixel8CpuInfo,
        hwcapRaw: String? = pixel8HwcapArm,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = true
        override fun readFile(path: String, maxBytes: Int): String? = when (path) {
            CpuInfoProbe.PROC_CPUINFO -> cpuinfoContent
            CpuInfoProbe.HWCAP_PATH   -> hwcapRaw
            else                      -> null
        }
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
    }

    // ── Clean device ──────────────────────────────────────────────────────────

    @Test
    fun `clean ARM device — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean ARM device — confidence is 0_92`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.92, result.confidence)
    }

    @Test
    fun `clean device — evidence contains bogomips and arch`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.evidence.any { it.key == "bogomips" })
        assertTrue(result.evidence.any { it.key == "arch" })
        assertTrue(result.evidence.any { it.key == "cpu_implementer" })
        assertTrue(result.evidence.any { it.key == "hwcap" })
    }

    // ── arch mismatch: x86 HWCAP on ARM-claimed device ────────────────────────

    @Test
    fun `arch mismatch — x86 FPU bit set without ARM NEON scores 0_95`() = runBlocking {
        // AT_HWCAP = x86 FPU bit only, no ARM NEON
        val hwcap = CpuInfoProbe.X86_FPU_BIT.toString()
        val result = probe.run(fakeCtx(hwcapRaw = hwcap))
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `arch mismatch — evidence key is set to true`() = runBlocking {
        val hwcap = CpuInfoProbe.X86_FPU_BIT.toString()
        val result = probe.run(fakeCtx(hwcapRaw = hwcap))
        val ev = result.evidence.find { it.key == "arch_mismatch" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `arch mismatch — confidence is 0_95 when signal fires`() = runBlocking {
        val hwcap = CpuInfoProbe.X86_FPU_BIT.toString()
        val result = probe.run(fakeCtx(hwcapRaw = hwcap))
        assertEquals(0.95, result.confidence)
    }

    // ── x86 HWCAP on ARM (no NEON, non-zero) ─────────────────────────────────

    @Test
    fun `x86 hwcap on arm — non-neon hwcap scores 0_80`() = runBlocking {
        // Non-zero HWCAP but no ARM NEON bit — weaker signal than arch_mismatch
        val hwcap = "1"  // just bit 0, no FPU, no NEON
        val result = probe.run(fakeCtx(hwcapRaw = hwcap))
        // x86_hwcap_on_arm fires (no NEON, non-zero), but not arch_mismatch (no FPU bit)
        assertTrue(result.score >= 0.80)
    }

    // ── CPU implementer mismatch ──────────────────────────────────────────────

    @Test
    fun `unknown implementer — score is 0_70`() = runBlocking {
        val cpuinfo = pixel8CpuInfo.replace("0x41", "0xff")
        // Keep NEON in hwcap so no arch signal fires
        val hwcap = CpuInfoProbe.ARM_NEON_BIT.toString()
        val result = probe.run(fakeCtx(cpuinfoContent = cpuinfo, hwcapRaw = hwcap))
        assertFalse(result.failed)
        assertEquals(0.70, result.score)
    }

    @Test
    fun `known implementer 0x51 — no implementer signal`() = runBlocking {
        val cpuinfo = pixel8CpuInfo.replace("0x41", "0x51")
        val hwcap = CpuInfoProbe.ARM_NEON_BIT.toString()
        val result = probe.run(fakeCtx(cpuinfoContent = cpuinfo, hwcapRaw = hwcap))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── BogoMIPS out of range ─────────────────────────────────────────────────

    @Test
    fun `bogomips above max — score is 0_40`() = runBlocking {
        val cpuinfo = pixel8CpuInfo.replace("38.40", "999999.99")
        val hwcap = CpuInfoProbe.ARM_NEON_BIT.toString()
        val result = probe.run(fakeCtx(cpuinfoContent = cpuinfo, hwcapRaw = hwcap))
        assertFalse(result.failed)
        assertEquals(0.40, result.score)
    }

    // ── cpuinfo unavailable ───────────────────────────────────────────────────

    @Test
    fun `cpuinfo unavailable — probe skips`() = runBlocking {
        val result = probe.run(fakeCtx(cpuinfoContent = null))
        assertTrue(result.failed)
        assertTrue(result.skipped)
    }

    // ── x86 device (vendor_id present) ────────────────────────────────────────

    @Test
    fun `x86 device — arch reported as x86_64`() = runBlocking {
        val x86Cpuinfo = """
            processor : 0
            vendor_id : GenuineIntel
            BogoMIPS  : 1000.00
            CPU implementer : 0x00
        """.trimIndent()
        val result = probe.run(fakeCtx(cpuinfoContent = x86Cpuinfo, hwcapRaw = null))
        val archEv = result.evidence.find { it.key == "arch" }
        assertEquals("x86_64", archEv?.value)
    }

    // ── Parser unit tests ─────────────────────────────────────────────────────

    @Test
    fun `parseCpuInfo — extracts bogomips`() {
        val f = parseCpuInfo(pixel8CpuInfo)
        assertEquals(38.40, f.bogomips)
    }

    @Test
    fun `parseCpuInfo — extracts implementer`() {
        val f = parseCpuInfo(pixel8CpuInfo)
        assertEquals("0x41", f.cpuImplementer)
    }

    @Test
    fun `parseCpuInfo — extracts hwcap features`() {
        val f = parseCpuInfo(pixel8CpuInfo)
        assertTrue(f.hwcap.contains("aes"))
        assertTrue(f.hwcap.contains("asimd"))
    }

    @Test
    fun `parseCpuInfo — ARM device has arm64 arch`() {
        val f = parseCpuInfo(pixel8CpuInfo)
        assertEquals("arm64", f.arch)
    }

    @Test
    fun `parseCpuInfo — x86 device has x86_64 arch`() {
        val f = parseCpuInfo("vendor_id : GenuineIntel\nBogoMIPS : 100.0\n")
        assertEquals("x86_64", f.arch)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is kernel_cpuinfo_bogomips_implementer`() {
        assertEquals("kernel.cpuinfo_bogomips_implementer", probe.id)
    }

    @Test
    fun `probe rank is 9`() {
        assertEquals(9, probe.rank)
    }
}

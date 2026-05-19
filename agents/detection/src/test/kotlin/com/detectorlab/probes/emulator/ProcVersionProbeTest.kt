package com.detectorlab.probes.emulator

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
 * Unit tests for ProcVersionProbe (emulator.proc_version, inventory rank 30).
 */
class ProcVersionProbeTest {

    private val probe = ProcVersionProbe()

    private val realPixelProcVersion = """
        Linux version 5.10.157-android13-4 (build-user@build-hostname) (Android (8508608, based on r450784e) clang version 14.0.6 (https://android.googlesource.com/toolchain/llvm-project 4c603efb0cca074e9238af8b4106c30add4418f6)) #1 SMP PREEMPT Tue Aug 1 03:30:00 UTC 2023
    """.trimIndent()

    private val goldfishProcVersion = """
        Linux version 4.14.175+ (android-build@abfarm-2004) (Android (6443078, based on r383902) clang version 11.0.2 (https://android.googlesource.com/toolchain/llvm-project c935d99d7cf2016289302412d708641d52d2f7ee)) #1 SMP PREEMPT Mon Jan 1 00:00:00 UTC 2020 goldfish
    """.trimIndent()

    private val ranchuProcVersion = """
        Linux version 4.19.95-ranchu (build@build-server) (clang version 14.0.6) #1 SMP PREEMPT Mon Jan 1 00:00:00 UTC 2023
    """.trimIndent()

    private val redroidProcVersion = """
        Linux version 5.15.0-redroid (root@redroid-builder) (clang version 14.0.6) #1 SMP PREEMPT Mon Jan 1 00:00:00 UTC 2023
    """.trimIndent()

    private val wslProcVersion = """
        Linux version 5.15.90.1-microsoft@wsl2-standard-WSL2 (oe-user@oe-host) (gcc 11.2.0) #1 SMP Fri Jan 27 02:56:13 UTC 2023
    """.trimIndent()

    private val x86OnArmProcVersion = """
        Linux version 5.10.157-android13-4 (build-user@build-hostname) (clang version 14.0.6) #1 SMP PREEMPT Tue Aug 1 03:30:00 UTC 2023 x86_64
    """.trimIndent()

    private val localhostProcVersion = """
        Linux version 4.19.95 (root@localhost.localdomain) (clang version 12.0.0) #1 SMP Mon Jan 1 00:00:00 UTC 2023
    """.trimIndent()

    private val gccProcVersion = """
        Linux version 5.10.157-android13-4 (build-user@build-hostname) (gcc version 6.3.0 20170516) #1 SMP PREEMPT Tue Aug 1 03:30:00 UTC 2023
    """.trimIndent()

    private fun fakeCtx(
        procVersion: String? = realPixelProcVersion,
        model: String? = "Pixel 7",
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == ProcVersionProbe.PROP_RO_PRODUCT_MODEL) model else null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == ProcVersionProbe.PATH_PROC_VERSION) procVersion else null
        }
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

    // ── Clean real Pixel ─────────────────────────────────────────────────────

    @Test
    fun `real Pixel kernel — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean_android`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_CLEAN_ANDROID, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — kernel_name evidence is android`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_version.kernel_name" }
        assertEquals("android", ev?.value)
    }

    @Test
    fun `clean — compiler evidence is clang`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_version.compiler" }
        assertEquals("clang", ev?.value)
    }

    // ── Goldfish / Ranchu / Redroid / QEMU emulator kernels (score 1.0) ──────

    @Test
    fun `goldfish kernel — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = goldfishProcVersion))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `goldfish — kernel_name is goldfish`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = goldfishProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.kernel_name" }
        assertEquals("goldfish", ev?.value)
    }

    @Test
    fun `ranchu kernel — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = ranchuProcVersion))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu — kernel_name is ranchu`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = ranchuProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.kernel_name" }
        assertEquals("ranchu", ev?.value)
    }

    @Test
    fun `redroid kernel — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = redroidProcVersion))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `qemu kernel — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = "Linux version 5.0.0-qemu (root@host) (clang) #1"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emulator kernel — pattern is emulator_kernel`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = goldfishProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_EMULATOR_KERNEL, ev?.value)
    }

    // ── WSL (score 1.0) ───────────────────────────────────────────────────────

    @Test
    fun `WSL2 build host — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = wslProcVersion))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `WSL — pattern is wsl_host`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = wslProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_WSL_HOST, ev?.value)
    }

    @Test
    fun `WSL — kernel_name is wsl`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = wslProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.kernel_name" }
        assertEquals("wsl", ev?.value)
    }

    // ── x86 arch leak on phone-class model (score 0.95) ──────────────────────

    @Test
    fun `x86_64 leak on Pixel 7 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(
            procVersion = x86OnArmProcVersion,
            model = "Pixel 7",
        ))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `x86 leak on Galaxy S22 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(
            procVersion = x86OnArmProcVersion,
            model = "SM-S901B",
        ))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `x86 leak on tablet — score is 0_0 (no false-positive)`() = runBlocking {
        // Tablet (non-phone-class) running x86 is legitimate — phone-class
        // guard prevents the rule from firing.
        val result = probe.run(fakeCtx(
            procVersion = x86OnArmProcVersion,
            model = "Lenovo Tab P11",
        ))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `x86 leak — has_x86_arch_leak evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = x86OnArmProcVersion, model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "proc_version.has_x86_arch_leak" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `clean Pixel — has_x86_arch_leak evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_version.has_x86_arch_leak" }
        assertEquals("false", ev?.value)
    }

    // ── localhost.localdomain build host (score 0.85) ────────────────────────

    @Test
    fun `localhost build host — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = localhostProcVersion))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `localhost — build_host evidence is localhost_localdomain`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = localhostProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.build_host" }
        assertEquals("localhost.localdomain", ev?.value)
    }

    @Test
    fun `localhost — pattern is localhost_build_host`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = localhostProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_LOCALHOST_BUILD_HOST, ev?.value)
    }

    // ── GCC compiler (score 0.70) ────────────────────────────────────────────

    @Test
    fun `GCC compiler — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = gccProcVersion))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `GCC — compiler evidence is gcc`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = gccProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.compiler" }
        assertEquals("gcc", ev?.value)
    }

    @Test
    fun `GCC — pattern is gcc_compiler`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = gccProcVersion))
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_GCC_COMPILER, ev?.value)
    }

    // ── Multi-signal precedence ──────────────────────────────────────────────

    @Test
    fun `goldfish plus localhost — emulator wins (1_0 over 0_85)`() = runBlocking {
        val result = probe.run(fakeCtx(
            procVersion = "Linux version goldfish (root@localhost.localdomain) (gcc 6.3.0) #1 SMP $0",
        ))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `x86 plus localhost plus phone-class — x86 wins (0_95 over 0_85)`() = runBlocking {
        val result = probe.run(fakeCtx(
            procVersion = "Linux version 5.0 x86_64 (root@localhost.localdomain) (clang) #1",
            model = "Pixel 7",
        ))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `localhost plus gcc — localhost wins (0_85 over 0_70)`() = runBlocking {
        val result = probe.run(fakeCtx(
            procVersion = "Linux version 5.0 (root@localhost.localdomain) (gcc 6.3.0) #1",
        ))
        assertEquals(0.85, result.score)
    }

    // ── Unreadable / empty (score 0.5) ───────────────────────────────────────

    @Test
    fun `proc_version null — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = null))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `proc_version empty — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = ""))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `readFile throws — score is 0_5, confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `unreadable — pattern is unreadable`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = null))
        val ev = result.evidence.find { it.key == "proc_version.pattern" }
        assertEquals(ProcVersionProbe.PATTERN_UNREADABLE, ev?.value)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `extractBuildHost extracts user@host portion`() {
        assertEquals(
            "build-hostname",
            ProcVersionProbe.extractBuildHost(realPixelProcVersion),
        )
    }

    @Test
    fun `extractBuildHost extracts localhost`() {
        assertEquals(
            "localhost.localdomain",
            ProcVersionProbe.extractBuildHost(localhostProcVersion),
        )
    }

    @Test
    fun `extractBuildHost returns null for malformed`() {
        assertEquals(
            null,
            ProcVersionProbe.extractBuildHost("Linux version 5.0"),
        )
    }

    @Test
    fun `extractCompiler returns clang when clang version is present`() {
        assertEquals("clang", ProcVersionProbe.extractCompiler(realPixelProcVersion))
    }

    @Test
    fun `extractCompiler returns gcc when only gcc is present`() {
        assertEquals("gcc", ProcVersionProbe.extractCompiler(gccProcVersion))
    }

    @Test
    fun `extractCompiler returns unknown for neither`() {
        assertEquals("unknown", ProcVersionProbe.extractCompiler("Linux version 5.0"))
    }

    @Test
    fun `extractCompiler — clang dominates if both mentioned`() {
        // Some Android kernels mention "gcc compatible" alongside clang.
        // Active compiler is clang.
        val s = "Linux version 5.0 (clang version 14.0.6, gcc compatible) #1"
        assertEquals("clang", ProcVersionProbe.extractCompiler(s))
    }

    @Test
    fun `classifyKernelName covers all categories`() {
        assertEquals("goldfish", ProcVersionProbe.classifyKernelName(goldfishProcVersion))
        assertEquals("ranchu", ProcVersionProbe.classifyKernelName(ranchuProcVersion))
        assertEquals("redroid", ProcVersionProbe.classifyKernelName(redroidProcVersion))
        assertEquals("wsl", ProcVersionProbe.classifyKernelName(wslProcVersion))
        assertEquals("android", ProcVersionProbe.classifyKernelName(realPixelProcVersion))
    }

    @Test
    fun `firstMarkerIn returns first matching marker case-insensitively`() {
        assertEquals("goldfish",
            ProcVersionProbe.firstMarkerIn("Linux GOLDFISH 5.0", ProcVersionProbe.EMULATOR_KERNEL_MARKERS))
        assertEquals(null,
            ProcVersionProbe.firstMarkerIn("Linux 5.0", ProcVersionProbe.EMULATOR_KERNEL_MARKERS))
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
                "proc_version.raw",
                "proc_version.kernel_name",
                "proc_version.has_x86_arch_leak",
                "proc_version.build_host",
                "proc_version.compiler",
                "proc_version.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `proc_version_raw evidence truncates to 256 chars`() = runBlocking {
        // Build a >256-char proc_version-like string.
        val longContent = "Linux version 5.0 " + "x".repeat(500) + " end"
        val result = probe.run(fakeCtx(procVersion = longContent))
        val ev = result.evidence.find { it.key == "proc_version.raw" }
        val value = ev?.value as String
        assertTrue(value.length <= 256, "expected ≤256 chars, got ${value.length}")
    }

    @Test
    fun `null proc_version — raw evidence is literal null`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = null))
        val ev = result.evidence.find { it.key == "proc_version.raw" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `unreadable — build_host evidence is unknown`() = runBlocking {
        val result = probe.run(fakeCtx(procVersion = null))
        val ev = result.evidence.find { it.key == "proc_version.build_host" }
        assertEquals("unknown", ev?.value)
    }

    // ── getSystemProperty throws — phone-class falls through cleanly ─────────

    @Test
    fun `getSystemProperty throws — x86 leak rule does NOT fire (phoneClass=false)`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = throw RuntimeException("simulated")
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = x86OnArmProcVersion
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
        val result = probe.run(ctx)
        assertFalse(result.failed)
        // model unknown → phone-class=false → x86 rule doesn't fire → falls
        // through to clean. (real /proc/version with android-13 marker stays
        // clean even with x86_64 mentioned.)
        assertEquals(0.0, result.score)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read /proc/version and parse for emulator kernel-name markers, " +
                "x86 arch leak, localhost build host, GCC compiler leak",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is emulator_proc_version`() {
        assertEquals("emulator.proc_version", probe.id)
    }

    @Test
    fun `probe rank is 30`() {
        assertEquals(30, probe.rank)
    }

    @Test
    fun `probe category is EMULATOR`() {
        assertEquals(ProbeCategory.EMULATOR, probe.category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-30 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, probe.severity)
    }

    @Test
    fun `probe android layer is KERNEL`() {
        assertEquals(AndroidLayer.KERNEL, probe.androidLayer)
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

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
 * Unit tests for GpuRendererProbe (emulator.gpu_renderer, inventory rank 26).
 */
class GpuRendererProbeTest {

    private fun makeProbe(
        glRenderer: String? = "Adreno (TM) 730",
        glVendor: String? = "Qualcomm",
        glVersion: String? = "OpenGL ES 3.2 V@0744.0",
    ): GpuRendererProbe = GpuRendererProbe(
        glRendererSupplier = { glRenderer },
        glVendorSupplier = { glVendor },
        glVersionSupplier = { glVersion },
    )

    private fun fakeCtx(roHardwareEgl: String? = "adreno"): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == GpuRendererProbe.PROP_RO_HARDWARE_EGL) roHardwareEgl else null
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

    // ── Real hardware GPUs (score 0.0) ────────────────────────────────────────

    @Test
    fun `Adreno 730 with Qualcomm vendor — score is 0`() = runBlocking {
        val result = makeProbe(
            glRenderer = "Adreno (TM) 730",
            glVendor = "Qualcomm",
        ).run(fakeCtx("adreno"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Mali-G715 with ARM vendor — score is 0`() = runBlocking {
        val result = makeProbe(
            glRenderer = "Mali-G715-Immortalis",
            glVendor = "ARM",
        ).run(fakeCtx("mali"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `PowerVR Rogue — score is 0`() = runBlocking {
        val result = makeProbe(
            glRenderer = "PowerVR Rogue GE8320",
            glVendor = "Imagination Technologies",
        ).run(fakeCtx("powervr"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `VideoCore — score is 0`() = runBlocking {
        val result = makeProbe(
            glRenderer = "VideoCore VI",
            glVendor = "Broadcom",
        ).run(fakeCtx("videocore"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real hardware — pattern is hardware_gpu`() = runBlocking {
        val result = makeProbe().run(fakeCtx("adreno"))
        val ev = result.evidence.find { it.key == "gpu.pattern" }
        assertEquals(GpuRendererProbe.PATTERN_HARDWARE_GPU, ev?.value)
    }

    @Test
    fun `real hardware — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx("adreno"))
        assertEquals(0.95, result.confidence)
    }

    // ── SwiftShader software renderer (score 1.0) ────────────────────────────

    @Test
    fun `GL_RENDERER SwiftShader — score is 1_0`() = runBlocking {
        val result = makeProbe(
            glRenderer = "Google SwiftShader",
            glVendor = "Google Inc.",
            glVersion = "OpenGL ES 2.0 SwiftShader 4.1.0.7",
        ).run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware_egl swiftshader — score is 1_0`() = runBlocking {
        // EGL property fires the rule even if GL strings are null.
        val result = makeProbe(glRenderer = null, glVendor = null, glVersion = null)
            .run(fakeCtx("swiftshader"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `SwiftShader — pattern reflects EGL detection`() = runBlocking {
        // ro.hardware.egl=swiftshader fires first → pattern is swiftshader_egl.
        val result = makeProbe(
            glRenderer = "Google SwiftShader",
        ).run(fakeCtx("swiftshader"))
        val ev = result.evidence.find { it.key == "gpu.pattern" }
        assertEquals(GpuRendererProbe.PATTERN_SWIFTSHADER_EGL, ev?.value)
    }

    @Test
    fun `SwiftShader — is_software_renderer evidence is true`() = runBlocking {
        val result = makeProbe(glRenderer = "Google SwiftShader").run(fakeCtx(roHardwareEgl = null))
        val ev = result.evidence.find { it.key == "gpu.is_software_renderer" }
        assertEquals("true", ev?.value)
    }

    // ── llvmpipe / Software / Generic / Android Emulator (score 1.0) ─────────

    @Test
    fun `GL_RENDERER llvmpipe — score is 1_0`() = runBlocking {
        val result = makeProbe(glRenderer = "Mesa llvmpipe (LLVM 12.0.0)", glVendor = "Mesa/X.org")
            .run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `GL_RENDERER Generic — score is 1_0`() = runBlocking {
        val result = makeProbe(glRenderer = "Generic Renderer").run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `GL_RENDERER Android Emulator OpenGL ES Translator — score is 1_0`() = runBlocking {
        val result = makeProbe(glRenderer = "Android Emulator OpenGL ES Translator")
            .run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `GL_RENDERER contains Software — score is 1_0`() = runBlocking {
        val result = makeProbe(glRenderer = "Apple Software Renderer")
            .run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `GL_VERSION contains SwiftShader (renderer clean) — score is 1_0`() = runBlocking {
        // GL_VERSION often includes the renderer's vendor when version comes
        // from the same library.
        val result = makeProbe(
            glRenderer = "Some Renderer",
            glVersion = "OpenGL ES 2.0 SwiftShader 4.1.0.7",
        ).run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    // ── ANGLE translator (score 0.95) ────────────────────────────────────────

    @Test
    fun `GL_RENDERER ANGLE — score is 0_95`() = runBlocking {
        val result = makeProbe(
            glRenderer = "ANGLE (Intel(R) HD Graphics 530)",
            glVendor = "Google Inc.",
        ).run(fakeCtx(roHardwareEgl = null))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `ANGLE — pattern is angle_translator`() = runBlocking {
        val result = makeProbe(glRenderer = "ANGLE (D3D11)").run(fakeCtx(roHardwareEgl = null))
        val ev = result.evidence.find { it.key == "gpu.pattern" }
        assertEquals(GpuRendererProbe.PATTERN_ANGLE, ev?.value)
    }

    @Test
    fun `ANGLE — is_emulator_translator evidence is true`() = runBlocking {
        val result = makeProbe(glRenderer = "ANGLE (D3D11)").run(fakeCtx(roHardwareEgl = null))
        val ev = result.evidence.find { it.key == "gpu.is_emulator_translator" }
        assertEquals("true", ev?.value)
    }

    // ── Mesa EGL (score 0.85) ────────────────────────────────────────────────

    @Test
    fun `ro_hardware_egl mesa — score is 0_85`() = runBlocking {
        // GL strings null; only EGL says mesa → 0.85.
        val result = makeProbe(glRenderer = null, glVendor = null, glVersion = null)
            .run(fakeCtx("mesa"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `mesa EGL — pattern is mesa_egl`() = runBlocking {
        val result = makeProbe(glRenderer = null).run(fakeCtx("mesa"))
        val ev = result.evidence.find { it.key == "gpu.pattern" }
        assertEquals(GpuRendererProbe.PATTERN_MESA_EGL, ev?.value)
    }

    // ── Google vendor without hardware EGL (score 0.85) ──────────────────────

    @Test
    fun `Google Inc vendor with non-hardware EGL — score is 0_85`() = runBlocking {
        // Renderer doesn't match software markers, vendor is Google Inc.,
        // EGL is not a hardware vendor → 0.85.
        val result = makeProbe(
            glRenderer = "Some Custom Renderer",
            glVendor = "Google Inc.",
        ).run(fakeCtx(roHardwareEgl = null))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Google Inc vendor WITH adreno EGL — score is 0_0 (real device)`() = runBlocking {
        // Edge case: vendor = Google Inc. but EGL = adreno. Could be a Pixel
        // device that proxies through a Google EGL wrapper. Hardware EGL
        // dominates → 0.0 (PATTERN_HARDWARE_GPU).
        val result = makeProbe(
            glRenderer = "Adreno (TM) 730",
            glVendor = "Google Inc.",
        ).run(fakeCtx("adreno"))
        assertEquals(0.0, result.score)
    }

    // ── No signals (graceful degradation) ────────────────────────────────────

    @Test
    fun `all signals null — score is 0_0 (degraded, no info)`() = runBlocking {
        val result = makeProbe(glRenderer = null, glVendor = null, glVersion = null)
            .run(fakeCtx(roHardwareEgl = null))
        assertEquals(0.0, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `production no-arg constructor without EGL — confidence is 0_5`() = runBlocking {
        val result = GpuRendererProbe().run(fakeCtx(roHardwareEgl = null))
        assertEquals(0.5, result.confidence)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `production no-arg constructor with EGL adreno — score is 0_0 confidence 0_95`() = runBlocking {
        // Even with no GL strings, a real-hardware EGL property carries enough
        // to anchor the score AND raise confidence to NORMAL.
        val result = GpuRendererProbe().run(fakeCtx("adreno"))
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `production no-arg constructor with EGL swiftshader — score is 1_0`() = runBlocking {
        // Property-only signal still fires the strongest rule.
        val result = GpuRendererProbe().run(fakeCtx("swiftshader"))
        assertEquals(1.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Supplier-throws / accessor-throws crash safety ───────────────────────

    @Test
    fun `glRenderer supplier throws — does not crash`() = runBlocking {
        val probe = GpuRendererProbe(
            glRendererSupplier = { throw RuntimeException("simulated") },
            glVendorSupplier = { "Qualcomm" },
            glVersionSupplier = { "OpenGL ES 3.2" },
        )
        val result = probe.run(fakeCtx("adreno"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `getSystemProperty throws — EGL treated as null`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = throw RuntimeException("simulated")
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
        val result = makeProbe(glRenderer = "Adreno (TM) 730").run(ctx)
        // GL renderer still readable → confidence NORMAL.
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Multi-signal precedence ───────────────────────────────────────────────

    @Test
    fun `SwiftShader GL_RENDERER plus mesa EGL — score is 1_0 (SS dominates)`() = runBlocking {
        val result = makeProbe(glRenderer = "Google SwiftShader").run(fakeCtx("mesa"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ANGLE renderer plus mesa EGL — score is 0_95 (ANGLE dominates mesa)`() = runBlocking {
        val result = makeProbe(glRenderer = "ANGLE (D3D11)").run(fakeCtx("mesa"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `swiftshader EGL plus Adreno renderer — score is 1_0 (EGL dominates)`() = runBlocking {
        // Pathological case: EGL property says swiftshader but renderer
        // reports Adreno. Either is faked; EGL is the system-level truth
        // (kernel-side) so it dominates.
        val result = makeProbe(glRenderer = "Adreno (TM) 730").run(fakeCtx("swiftshader"))
        assertEquals(1.0, result.score)
    }

    // ── Case-insensitive matching ─────────────────────────────────────────────

    @Test
    fun `SwiftShader uppercase — still detected`() = runBlocking {
        val result = makeProbe(glRenderer = "SWIFTSHADER").run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `swiftshader lowercase — still detected`() = runBlocking {
        val result = makeProbe(glRenderer = "google swiftshader").run(fakeCtx(roHardwareEgl = null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Mixed-case adreno — still hardware`() = runBlocking {
        val result = makeProbe(glRenderer = "ADRENO 730").run(fakeCtx(roHardwareEgl = null))
        assertEquals(0.0, result.score)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `containsSoftwareMarker covers all software substrings`() {
        for (marker in GpuRendererProbe.SOFTWARE_RENDERER_SUBSTRINGS) {
            assertTrue(
                GpuRendererProbe.containsSoftwareMarker("Some $marker label"),
                "expected match on $marker",
            )
        }
    }

    @Test
    fun `containsSoftwareMarker rejects hardware names`() {
        assertFalse(GpuRendererProbe.containsSoftwareMarker("Adreno 730"))
        assertFalse(GpuRendererProbe.containsSoftwareMarker("Mali-G715"))
        assertFalse(GpuRendererProbe.containsSoftwareMarker("PowerVR Rogue"))
    }

    @Test
    fun `containsAngleMarker accepts ANGLE in various cases`() {
        assertTrue(GpuRendererProbe.containsAngleMarker("ANGLE (D3D11)"))
        assertTrue(GpuRendererProbe.containsAngleMarker("angle (Vulkan)"))
        assertFalse(GpuRendererProbe.containsAngleMarker("Adreno 730"))
        assertFalse(GpuRendererProbe.containsAngleMarker(null))
    }

    @Test
    fun `containsHardwareGpuMarker covers all hardware substrings`() {
        for (marker in GpuRendererProbe.HARDWARE_GPU_SUBSTRINGS) {
            assertTrue(
                GpuRendererProbe.containsHardwareGpuMarker("Some $marker label"),
                "expected match on $marker",
            )
        }
    }

    @Test
    fun `helpers reject null and empty`() {
        assertFalse(GpuRendererProbe.containsSoftwareMarker(null))
        assertFalse(GpuRendererProbe.containsSoftwareMarker(""))
        assertFalse(GpuRendererProbe.containsAngleMarker(null))
        assertFalse(GpuRendererProbe.containsHardwareGpuMarker(null))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        // 6 spec rows + gpu.pattern (same rank-17/21/22 convention).
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "gl.renderer",
                "gl.vendor",
                "gl.version",
                "ro.hardware.egl",
                "gpu.is_software_renderer",
                "gpu.is_emulator_translator",
                "gpu.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `null GL_RENDERER — evidence carries unavailable placeholder`() = runBlocking {
        val result = GpuRendererProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "gl.renderer" }
        assertTrue(
            (ev?.value as String).contains("unavailable"),
            "expected unavailable placeholder, got ${ev.value}",
        )
    }

    @Test
    fun `null ro_hardware_egl — evidence is literal null`() = runBlocking {
        val result = makeProbe().run(fakeCtx(roHardwareEgl = null))
        val ev = result.evidence.find { it.key == "ro.hardware.egl" }
        assertEquals("null", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read GL_RENDERER/GL_VENDOR/GL_VERSION via OpenGL ES context " +
                "(or ProbeContext.queryGlInfo) + ro.hardware.egl property; " +
                "detect SwiftShader/llvmpipe/ANGLE/Software-renderer markers",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is emulator_gpu_renderer`() {
        assertEquals("emulator.gpu_renderer", makeProbe().id)
    }

    @Test
    fun `probe rank is 26`() {
        assertEquals(26, makeProbe().rank)
    }

    @Test
    fun `probe category is EMULATOR`() {
        assertEquals(ProbeCategory.EMULATOR, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-26 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

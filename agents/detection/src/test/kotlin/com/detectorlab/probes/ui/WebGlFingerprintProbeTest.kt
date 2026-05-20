package com.detectorlab.probes.ui

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for WebGlFingerprintProbe (ui.webgl_fingerprint, inventory rank 56).
 *
 * Coverage matrix (15 tests):
 *   • Clean cases — Pixel-class kgsl, Samsung-class mali, GLES boundary 196610
 *   • SwiftShader (0.95) — exact match, case-insensitive
 *   • Emulator GL (0.85) — gltransport set, ranchu gralloc, gles=0
 *   • Cascade ordering — SwiftShader wins over emulator-gl, emulator-gl wins
 *     over no-gpu-hal, no-gpu-hal wins over old-gles
 *   • No-GPU-HAL (0.70) — both driver+vulkan empty
 *   • Old-GLES (0.50) — empty + below boundary, with boundary itself plausible
 *   • Confidence pinned at 0.75 declarative regardless of property presence
 *   • Property accessor crash safety
 *   • Evidence namespace prefix (webgl_fingerprint.*)
 */
class WebGlFingerprintProbeTest {

    /**
     * Build a fake context backed by a property map. Default returns a
     * realistic Pixel-7-class GPU surface (kgsl driver, real Vulkan/GLES
     * versions) so the test fixture itself scores 0.0 clean.
     */
    private fun fakeCtx(
        props: Map<String, String?> = mapOf(
            WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
            WebGlFingerprintProbe.PROP_RO_HARDWARE_GRALLOC to "qcom",
            WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
            WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
        ),
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
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

    // ── Clean ────────────────────────────────────────────────────────────────

    @Test
    fun `Pixel-class kgsl driver with GLES 3_2 — score is 0_0 clean`() = runBlocking {
        val result = WebGlFingerprintProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_CLEAN, pattern?.value)
    }

    @Test
    fun `Samsung-class mali driver with GLES 3_2 — score is 0_0 clean`() = runBlocking {
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "mali-g710_kmd",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_GRALLOC to "samsung",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `boundary GLES 196610 exactly — plausible, score is 0_0`() = runBlocking {
        // Boundary value: strict-less-than semantics, so 196610 itself is
        // PLAUSIBLE. Real Pixel 7 / Samsung S22 retail pin to this value.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── SwiftShader 0.95 ─────────────────────────────────────────────────────

    @Test
    fun `ro_gpu_driver is swiftshader — score is 0_95`() = runBlocking {
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "swiftshader",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.95, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_SWIFTSHADER, pattern?.value)
    }

    @Test
    fun `ro_gpu_driver case-insensitive SwiftShader — score is 0_95`() = runBlocking {
        // SwiftShader equality is case-insensitive — emulator stubs that
        // mix case (`SwiftShader`, `SWIFTSHADER`) must still fire.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "SwiftShader",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    // ── Emulator GL 0.85 ─────────────────────────────────────────────────────

    @Test
    fun `ro_boot_qemu_gltransport set — score is 0_85`() = runBlocking {
        // Any non-empty value triggers — the AVD host-GPU-passthrough property
        // doesn't exist on real devices, presence is the signal.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                    WebGlFingerprintProbe.PROP_RO_BOOT_QEMU_GLTRANSPORT to "pipe",
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_EMULATOR_GL, pattern?.value)
    }

    @Test
    fun `ro_hardware_gralloc is ranchu — score is 0_85`() = runBlocking {
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_GRALLOC to "ranchu",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_kernel_qemu_gles equals 0 — score is 0_85`() = runBlocking {
        // Distinct from rank-4 which fires on "any non-empty value" of the
        // same key — this rule fires only on the literal "0" (AVD without
        // host-GPU passthrough). Both rules are intentional; see KDoc.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                    WebGlFingerprintProbe.PROP_RO_KERNEL_QEMU_GLES to "0",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `SwiftShader wins over emulator GL — cascade ordering`() = runBlocking {
        // Both SwiftShader AND ranchu gralloc — SwiftShader (0.95) is the
        // strongest rule and must fire first regardless of also-firing
        // weaker signals.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "swiftshader",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_GRALLOC to "ranchu",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.95, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_SWIFTSHADER, pattern?.value)
    }

    // ── No-GPU-HAL 0.70 ──────────────────────────────────────────────────────

    @Test
    fun `gpu_driver and vulkan both empty — score is 0_70`() = runBlocking {
        // Containerized ReDroid: no GPU HAL bound, both surfaces empty.
        // GLES version still present and >= boundary so the old-GLES rule
        // does NOT fire — the no-GPU-HAL rule wins.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.70, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_NO_GPU_HAL, pattern?.value)
    }

    @Test
    fun `gpu_driver and vulkan both unset (null) — score is 0_70`() = runBlocking {
        // Missing keys (null) treated equivalent to empty for no-GPU-HAL rule —
        // both shapes mean "no usable GPU HAL identifier observed".
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `emulator GL wins over no-GPU-HAL — cascade ordering`() = runBlocking {
        // Both surfaces empty AND ranchu gralloc — emulator_gl (0.85) is
        // stronger than no_gpu_hal (0.70) and must win.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_GRALLOC to "ranchu",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "196610",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    // ── Old GLES 0.50 ────────────────────────────────────────────────────────

    @Test
    fun `opengles_version below boundary — score is 0_50`() = runBlocking {
        // 196608 = (3<<16)|0 = GLES 3.0 — present but below the 3.2 boundary.
        // ALSO MUST have at least one of gpu_driver/vulkan present so the
        // no-GPU-HAL rule doesn't fire first (stronger signal).
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "131072",
                ),
            ),
        )
        assertEquals(0.50, result.score)
        val pattern = result.evidence.find { it.key == "webgl_fingerprint.pattern" }
        assertEquals(WebGlFingerprintProbe.PATTERN_OLD_GLES, pattern?.value)
    }

    @Test
    fun `opengles_version empty but gpu driver present — score is 0_50`() = runBlocking {
        // Empty version + present driver: empty triggers old_gles rule, but
        // since gpu_driver is non-empty, no_gpu_hal does NOT fire.
        val result = WebGlFingerprintProbe().run(
            fakeCtx(
                props = mapOf(
                    WebGlFingerprintProbe.PROP_RO_GPU_DRIVER to "kgsl",
                    WebGlFingerprintProbe.PROP_RO_HARDWARE_VULKAN to "1.3.0",
                    WebGlFingerprintProbe.PROP_RO_OPENGLES_VERSION to "",
                ),
            ),
        )
        assertEquals(0.50, result.score)
    }

    // ── Confidence + accessor safety ─────────────────────────────────────────

    @Test
    fun `confidence is 0_75 declarative on clean read`() = runBlocking {
        // Per L0 framing: declarative confidence does NOT vary with property
        // availability — the inference layer is not the ground-truth layer.
        val result = WebGlFingerprintProbe().run(fakeCtx())
        assertEquals(0.75, result.confidence)
    }

    @Test
    fun `property accessor throws — probe stays crash-safe, lands in no_gpu_hal`() = runBlocking {
        // Every getSystemProperty throws → all reads return null → both
        // gpu_driver AND vulkan read as null/empty → no_gpu_hal fires (0.70).
        // The probe MUST NOT crash even under total accessor failure.
        val result = WebGlFingerprintProbe().run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
        assertEquals(0.70, result.score)
        assertEquals(0.75, result.confidence)
    }

    @Test
    fun `all evidence keys use webgl_fingerprint namespace prefix`() = runBlocking {
        // Cross-cutting #1 fix: no `pkg.*` raw key collisions with rank-4 or
        // other property-only probes. Every evidence row must namespace.
        val result = WebGlFingerprintProbe().run(fakeCtx())
        result.evidence.forEach { ev ->
            kotlin.test.assertTrue(
                ev.key.startsWith("webgl_fingerprint."),
                "evidence key '${ev.key}' missing webgl_fingerprint.* namespace prefix",
            )
        }
    }
}

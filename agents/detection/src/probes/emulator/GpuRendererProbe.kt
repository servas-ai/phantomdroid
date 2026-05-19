// inventory.yml rank 26, mitigation_layer L1
package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #26 — emulator.gpu_renderer.
 *
 * Detects emulators / software-renderer environments by inspecting the
 * OpenGL ES driver identity (GL_RENDERER, GL_VENDOR, GL_VERSION) and the
 * `ro.hardware.egl` system property. Real Android devices ship hardware
 * GPUs from a small set of vendors (Qualcomm Adreno, ARM Mali, Imagination
 * PowerVR, Broadcom VideoCore). Emulators ship software renderers
 * (SwiftShader, llvmpipe, Android Emulator OpenGL ES Translator) or
 * translation layers (ANGLE over Vulkan/D3D).
 *
 * **OpenGL ES accessor gap.** `ProbeContext` does NOT expose a
 * `queryGlInfo()` method. Capturing GL_RENDERER/GL_VENDOR/GL_VERSION
 * requires an active OpenGL ES context, which is Android-framework-bound
 * (eglCreateContext + eglMakeCurrent + glGetString) and out of scope for
 * the pure-JVM probe contract. The three strings arrive via constructor-
 * injected suppliers; production no-arg ctor returns null and the probe
 * falls back to `ro.hardware.egl` as the sole reachable signal. Same
 * pattern as rank 18/20/23/25.
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  ro.hardware.egl == "swiftshader"
 *   1.00  GL_RENDERER contains "SwiftShader", "llvmpipe", "Software",
 *         "Generic", or "Android Emulator" (case-insensitive)
 *   0.95  GL_RENDERER contains "ANGLE" — Google's translator over
 *         Vulkan/D3D, used by emulators and some Chromebooks
 *   0.85  ro.hardware.egl == "mesa" — open-source Mesa stack, common on
 *         desktop-running emulators
 *   0.85  GL_VENDOR == "Google Inc." AND ro.hardware.egl is NOT a known
 *         hardware vendor (suspect software-renderer-with-stripped-name)
 *   0.00  GL_RENDERER contains a hardware-GPU marker (Adreno, Mali,
 *         PowerVR, VideoCore) OR ro.hardware.egl matches the known-
 *         hardware set
 *
 * Confidence: 0.95 when GL_RENDERER supplier returned non-null OR
 * ro.hardware.egl was readable; 0.50 when neither is available.
 *
 * Reference: shared/probes/inventory.yml rank 26 (mitigation_layer L1).
 */
class GpuRendererProbe(
    private val glRendererSupplier: () -> String? = { null },
    private val glVendorSupplier: () -> String? = { null },
    private val glVersionSupplier: () -> String? = { null },
) : Probe {
    override val id = "emulator.gpu_renderer"
    override val rank = 26
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 200L

    companion object {
        const val PROP_RO_HARDWARE_EGL = "ro.hardware.egl"

        /** GL_RENDERER substrings that are unambiguous software renderers. */
        val SOFTWARE_RENDERER_SUBSTRINGS: List<String> = listOf(
            "swiftshader",
            "llvmpipe",
            "software",
            "generic",
            "android emulator",
        )

        const val ANGLE_TRANSLATOR_SUBSTRING = "angle"

        /** Hardware-vendor markers in GL_RENDERER text. */
        val HARDWARE_GPU_SUBSTRINGS: List<String> = listOf(
            "adreno",
            "mali",
            "powervr",
            "videocore",
        )

        /** `ro.hardware.egl` values shipped by real OEMs. */
        val KNOWN_HARDWARE_EGL_VALUES: Set<String> = setOf(
            "adreno",
            "mali",
            "powervr",
            "videocore",
        )

        const val EGL_SWIFTSHADER = "swiftshader"
        const val EGL_MESA = "mesa"

        const val GOOGLE_INC_VENDOR = "google inc."

        const val PATTERN_SWIFTSHADER_EGL = "swiftshader_egl"
        const val PATTERN_SOFTWARE_RENDERER = "software_renderer"
        const val PATTERN_ANGLE = "angle_translator"
        const val PATTERN_MESA_EGL = "mesa_egl"
        const val PATTERN_GOOGLE_VENDOR_NON_HW = "google_vendor_no_hw_egl"
        const val PATTERN_HARDWARE_GPU = "hardware_gpu"
        const val PATTERN_NONE = "none"

        const val SCORE_SOFTWARE_RENDERER = 1.0
        const val SCORE_ANGLE = 0.95
        const val SCORE_MESA_OR_GOOGLE_VENDOR = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read GL_RENDERER/GL_VENDOR/GL_VERSION via OpenGL ES context " +
                "(or ProbeContext.queryGlInfo) + ro.hardware.egl property; " +
                "detect SwiftShader/llvmpipe/ANGLE/Software-renderer markers"

        internal fun containsSoftwareMarker(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            val lower = s.lowercase()
            return SOFTWARE_RENDERER_SUBSTRINGS.any { it in lower }
        }

        internal fun containsAngleMarker(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            return ANGLE_TRANSLATOR_SUBSTRING in s.lowercase()
        }

        internal fun containsHardwareGpuMarker(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            val lower = s.lowercase()
            return HARDWARE_GPU_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val glRenderer: String? = try { glRendererSupplier() } catch (_: Throwable) { null }
            val glVendor: String? = try { glVendorSupplier() } catch (_: Throwable) { null }
            val glVersion: String? = try { glVersionSupplier() } catch (_: Throwable) { null }

            val roHardwareEgl: String? = try {
                ctx.getSystemProperty(PROP_RO_HARDWARE_EGL)
            } catch (_: Throwable) {
                null
            }
            val eglLower = roHardwareEgl?.trim()?.lowercase()

            val isEglSwiftshader = eglLower == EGL_SWIFTSHADER
            val isEglMesa = eglLower == EGL_MESA
            val isEglKnownHardware = eglLower != null && eglLower in KNOWN_HARDWARE_EGL_VALUES

            // GL string checks across renderer + version (vendor info often
            // appears in version string like "OpenGL ES 2.0 SwiftShader 4.1.0").
            val rendererSoftware = containsSoftwareMarker(glRenderer)
            val versionSoftware = containsSoftwareMarker(glVersion)
            val anySoftwareMarker = rendererSoftware || versionSoftware

            val angleInRenderer = containsAngleMarker(glRenderer)
            val rendererHardware = containsHardwareGpuMarker(glRenderer)

            val googleVendor = glVendor?.trim()?.lowercase() == GOOGLE_INC_VENDOR
            val googleVendorWithoutHwEgl = googleVendor && !isEglKnownHardware

            // ANGLE on a real Pixel 8+ device legitimately appears in
            // GL_RENDERER as "ANGLE (Qualcomm Inc., Adreno (TM) 740, ...)" —
            // the wrapper substring is present BUT the real hardware vendor
            // is also reported. Real-device users would false-positive at
            // 0.95 without this guard. The ANGLE rule only fires when no
            // hardware vendor is observable anywhere — i.e. genuine
            // stripped-hardware ANGLE-over-software case.
            val angleWithoutHardware = angleInRenderer &&
                !rendererHardware && !isEglKnownHardware

            val (pattern, score) = when {
                isEglSwiftshader ->
                    PATTERN_SWIFTSHADER_EGL to SCORE_SOFTWARE_RENDERER
                anySoftwareMarker ->
                    PATTERN_SOFTWARE_RENDERER to SCORE_SOFTWARE_RENDERER
                angleWithoutHardware ->
                    PATTERN_ANGLE to SCORE_ANGLE
                isEglMesa ->
                    PATTERN_MESA_EGL to SCORE_MESA_OR_GOOGLE_VENDOR
                googleVendorWithoutHwEgl ->
                    PATTERN_GOOGLE_VENDOR_NON_HW to SCORE_MESA_OR_GOOGLE_VENDOR
                rendererHardware || isEglKnownHardware ->
                    PATTERN_HARDWARE_GPU to SCORE_CLEAN
                else ->
                    PATTERN_NONE to SCORE_CLEAN
            }

            val isSoftwareRenderer = isEglSwiftshader || anySoftwareMarker || isEglMesa
            // Same guard as the ANGLE scoring rule: real Pixel 8+ devices
            // ship ANGLE as the official GLES→Vulkan translator on TOP of
            // real Adreno hardware. The is_emulator_translator forensic
            // flag should reflect "ANGLE-as-emulator-tell" — not "ANGLE
            // is present anywhere".
            val isEmulatorTranslator = angleWithoutHardware

            val anySignalReadable = glRenderer != null || glVendor != null ||
                glVersion != null || roHardwareEgl != null
            val confidence = if (anySignalReadable) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val evidence = listOf(
                Evidence(
                    key = "gl.renderer",
                    value = glRenderer ?: "<unavailable: no GL accessor>",
                    expected = "<hardware GPU name>",
                ),
                Evidence(
                    key = "gl.vendor",
                    value = glVendor ?: "<unavailable: no GL accessor>",
                    expected = "<hardware vendor: Qualcomm|ARM|Apple|etc>",
                ),
                Evidence(
                    key = "gl.version",
                    value = glVersion ?: "<unavailable: no GL accessor>",
                    expected = "<no software-renderer markers>",
                ),
                Evidence(
                    key = "ro.hardware.egl",
                    value = roHardwareEgl ?: "null",
                    expected = "adreno|mali|powervr|videocore (not swiftshader|mesa)",
                ),
                Evidence(
                    key = "gpu.is_software_renderer",
                    value = isSoftwareRenderer.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "gpu.is_emulator_translator",
                    value = isEmulatorTranslator.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "gpu.pattern",
                    value = pattern,
                    expected = PATTERN_HARDWARE_GPU,
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "GpuRendererProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

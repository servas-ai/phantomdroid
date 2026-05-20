// inventory.yml rank 56, mitigation_layer L0
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #56 — ui.webgl_fingerprint.
 *
 * **L0 vs L3 surface — read this first.** The REAL inventory-#56 detection
 * surface is a WebGL shader fingerprint pulled at runtime through a
 * `WebView` or native GLES context — a hardware-rooted GPU identity probe
 * that is currently **un-snapshottable** (the GL pipeline must execute on
 * the target device to extract its shader trace). That live read is the L0
 * mitigation in `shared/probes/inventory.yml`: hardware-bound, no FOSS
 * spoofing tooling exists for it as of 2026.
 *
 * This declarative probe is the **layer-up signal**: it reads the
 * Android system-property surface that the GPU HAL stack populates at boot
 * and looks for SwiftShader / emulator-GL telltales that imply the runtime
 * is NOT serving a real hardware GPU. The L0 read above remains the
 * dispositive surface; this declarative inference scores against the
 * property-side markers that imply a failing live read, similar to how
 * rank-6 keystore_attestation declares against property markers rather than
 * the L0 hardware attestation surface itself. See
 * `audit/spoof-stack/un-snapshottable.md` for the parallel rank-6 framing.
 *
 * Signal classes (declarative, max-wins cascade, strongest first):
 *
 *   • **SwiftShader software renderer (0.95)**: `ro.gpu.driver == "swiftshader"`.
 *     SwiftShader is Google's CPU-side software OpenGL/Vulkan implementation
 *     shipped with the Android emulator and other host-OS test runners. No
 *     real Android device ships SwiftShader as the system GPU driver — the
 *     canonical software-rendering tell. Distinct from the AVD/QEMU
 *     property markers covered by rank-4 [QemuArtifactsProbe] (different
 *     surface — driver-name vs kernel-property — and one can be masked
 *     independently of the other).
 *
 *   • **AVD/emulator GL transport (0.85)**: ANY of the three emulator-GL
 *     telltale properties fires:
 *       - `ro.boot.qemu.gltransport` set to any non-empty value (the AVD's
 *         host-GPU-passthrough configuration property — present on AVD
 *         even when `ro.kernel.qemu` has been masked). Note this is a
 *         **distinct** property from `ro.kernel.qemu.gles` (covered by
 *         rank-4): `gltransport` is the userspace transport-binding choice
 *         (`pipe`/`virtio-gpu-pipe`/etc.), `gles` is the kernel-side
 *         GLES-version pin. Either can be masked while the other leaks.
 *       - `ro.hardware.gralloc == "ranchu"`. The goldfish/ranchu GL
 *         backend's gralloc HAL identifier. Real GPU vendors set this to
 *         their HAL name (`qcom`, `mali`, `kgsl`).
 *       - `ro.kernel.qemu.gles == "0"`. AVDs configured WITHOUT host-GPU
 *         passthrough pin this to "0" (forcing the guest to use the
 *         CPU-side reference GLES library). Note rank-4's
 *         `PATTERN_AVD_GLES` scores ANY non-empty value of this same
 *         property at 1.0 because the property's mere PRESENCE is the
 *         emulator tell — both rules consult the same key with different
 *         semantics, which is intentional and locked by the per-probe
 *         tests; do NOT consolidate.
 *
 *   • **No GPU HAL identified (0.70)**: `ro.gpu.driver` is empty AND
 *     `ro.hardware.vulkan` is empty. A real Android device since ~2015
 *     populates at least one of these at boot. Both empty implies no GPU
 *     HAL is bound — containerized hosts (ReDroid on a generic Ubuntu
 *     kernel without a vendor Android GPU HAL) are the canonical FP
 *     class, and that is exactly the case this rule is calibrated for.
 *
 *   • **Old/emulator OpenGL ES version (0.50)**: `ro.opengles.version`
 *     is empty OR numerically less than 196610. The integer 196610 in
 *     decimal is 0x30002 in hex = OpenGL ES 3.0 (major-minor packed as
 *     `(major << 16) | minor`). A real Pixel 7 reports 196611 or higher
 *     (GLES 3.0 through 3.2, per Tensor-G2 spec). Anything below is
 *     either pre-2014 hardware (vanishingly rare on cloud-phone infra)
 *     or an emulator stub.
 *
 *   • **Clean (0.0)**: none of the above signal classes fire.
 *
 * Boundary at 196610: the rule uses strict-less-than (`< 196610`), so
 * EXACTLY 196610 (= GLES 3.2 reported with the `(3<<16)|2` packing) is
 * treated as PLAUSIBLE. Real Pixel 7 / Samsung S22 retail values pin to
 * 196610; this boundary call is locked by the cleansnapshot tests.
 *
 * Uses **only** the base [ProbeContext] surface — no constructor-injected
 * suppliers needed (all five signals are `getSystemProperty` reads).
 * Same shape as rank-4 [QemuArtifactsProbe], rank-13 (Bootloader), and the
 * other property-only probes in the inventory. Drives the
 * `getSystemProperty` cascade in a per-key try/catch so a throwing read
 * doesn't suppress observation of the others.
 *
 * **Evidence key namespacing.** All evidence rows carry the
 * `webgl_fingerprint.*` namespace prefix per the cross-cutting-#1 fix
 * (audit/cross-cutting-followups-2026-05-19.md). The `pkg.*` raw property
 * keys would collide with rank-4's evidence rows if duplicated; the
 * namespaced shape keeps each probe's evidence panel addressable in
 * downstream forensic reports without cross-probe key collisions.
 *
 * Scoring (max wins; first-match cascade in source order — strongest first):
 *   0.95  PATTERN_SWIFTSHADER — `ro.gpu.driver == "swiftshader"`
 *   0.85  PATTERN_EMULATOR_GL — any of three AVD GL telltales fires
 *   0.70  PATTERN_NO_GPU_HAL — `ro.gpu.driver` AND `ro.hardware.vulkan` both empty
 *   0.50  PATTERN_OLD_GLES — `ro.opengles.version` empty or numerically < 196610
 *   0.00  PATTERN_CLEAN — none of the above
 *
 * Confidence:
 *   0.75  Declarative inference (the property surface is not the
 *         dispositive L0 GL surface — a real GL trace would over-rule
 *         a clean property read, and a spoofed property read can mask the
 *         signal without changing the underlying GL stack). Per the L0
 *         framing above, the probe's confidence is pinned at 0.75 even
 *         when every property reads successfully because the inference
 *         layer is not the ground-truth layer.
 *
 * Reference: shared/probes/inventory.yml rank 56 (mitigation_layer L0).
 */
class WebGlFingerprintProbe : Probe {
    override val id = "ui.webgl_fingerprint"
    override val rank = 56
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_GPU_DRIVER = "ro.gpu.driver"
        const val PROP_RO_BOOT_QEMU_GLTRANSPORT = "ro.boot.qemu.gltransport"
        const val PROP_RO_HARDWARE_GRALLOC = "ro.hardware.gralloc"
        const val PROP_RO_KERNEL_QEMU_GLES = "ro.kernel.qemu.gles"
        const val PROP_RO_HARDWARE_VULKAN = "ro.hardware.vulkan"
        const val PROP_RO_OPENGLES_VERSION = "ro.opengles.version"

        /**
         * Canonical SwiftShader driver-name marker. SwiftShader is Google's
         * CPU-side software OpenGL/Vulkan reference implementation; no real
         * Android device ships it as the system GPU driver. Exact-match
         * (case-insensitive) — the property value is the literal string
         * "swiftshader" when the software renderer is installed as the
         * default GPU HAL.
         */
        const val SWIFTSHADER_DRIVER = "swiftshader"

        /**
         * Goldfish/ranchu gralloc HAL identifier. Real GPU vendors populate
         * this with their HAL name (`qcom`, `mali`, `kgsl`); `ranchu` is the
         * canonical AOSP emulator gralloc backend.
         */
        const val RANCHU_GRALLOC = "ranchu"

        /**
         * `ro.kernel.qemu.gles == "0"` indicates an AVD configured WITHOUT
         * host-GPU passthrough — guest falls back to the CPU-side reference
         * GLES library. Distinct from rank-4's "any non-empty value" rule
         * on the same key (both rules are intentional; see the KDoc above
         * for the asymmetry rationale).
         */
        const val AVD_GLES_NO_PASSTHROUGH = "0"

        /**
         * Boundary for the "old / emulator GLES" rule. 196610 in decimal is
         * `0x30002` in hex, which under Android's `(major << 16) | minor`
         * packing decodes as OpenGL ES 3.2. A real Pixel 7 (Tensor-G2) and
         * Samsung S22 (Mali-G710) both report 196610 — the boundary value
         * itself is PLAUSIBLE (strict `<` semantics). Anything below is
         * pre-GLES-3.0 (rare on modern phones since 2014) or an emulator
         * stub.
         */
        const val MIN_PLAUSIBLE_OPENGLES_VERSION = 196610

        const val PATTERN_SWIFTSHADER = "swiftshader"
        const val PATTERN_EMULATOR_GL = "emulator_gl"
        const val PATTERN_NO_GPU_HAL = "no_gpu_hal"
        const val PATTERN_OLD_GLES = "old_gles"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_SWIFTSHADER = 0.95
        const val SCORE_EMULATOR_GL = 0.85
        const val SCORE_NO_GPU_HAL = 0.70
        const val SCORE_OLD_GLES = 0.50
        const val SCORE_CLEAN = 0.0

        /**
         * Confidence pinned at 0.75 (declarative). Per the L0 framing in the
         * class KDoc: this probe scores against the property-surface markers
         * that imply a failing live WebGL fingerprint read, not against the
         * dispositive GL trace itself. A successful property cascade does
         * not raise confidence above 0.75 because the inference layer is not
         * the ground-truth layer; a failed property cascade does not drop it
         * below 0.75 because the missing observation IS itself the no-GPU-HAL
         * signal that the cascade scores against.
         */
        const val CONFIDENCE_DECLARATIVE = 0.75

        const val METHOD =
            "Read ro.gpu.driver / ro.boot.qemu.gltransport / ro.hardware.gralloc / " +
                "ro.kernel.qemu.gles / ro.hardware.vulkan / ro.opengles.version; " +
                "infer SwiftShader software-render / emulator GL stack / missing GPU HAL / " +
                "pre-GLES-3.0 (declarative layer-up signal for the un-snapshottable L0 " +
                "WebGL shader fingerprint)"

        /**
         * Parse `ro.opengles.version` as a non-negative integer. Returns
         * `null` on null/empty/non-integer values; callers must treat null
         * as "no observation" distinct from "observed but old". Uses
         * `toIntOrNull` rather than `toInt` to keep the cascade crash-safe
         * under malformed values.
         */
        internal fun parseOpenGlesVersion(raw: String?): Int? {
            if (raw.isNullOrEmpty()) return null
            return raw.toIntOrNull()
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-key try/catch so a throwing read on one prop doesn't
            // suppress observation of the others — same defensive pattern as
            // rank-4 QemuArtifactsProbe.readProp.
            fun readProp(key: String): String? = try {
                ctx.getSystemProperty(key)
            } catch (_: Throwable) {
                null
            }

            val gpuDriver = readProp(PROP_RO_GPU_DRIVER)
            val gltransport = readProp(PROP_RO_BOOT_QEMU_GLTRANSPORT)
            val gralloc = readProp(PROP_RO_HARDWARE_GRALLOC)
            val qemuGles = readProp(PROP_RO_KERNEL_QEMU_GLES)
            val vulkan = readProp(PROP_RO_HARDWARE_VULKAN)
            val openGlesRaw = readProp(PROP_RO_OPENGLES_VERSION)
            val openGlesParsed = parseOpenGlesVersion(openGlesRaw)

            // Predicates
            val isSwiftShader = gpuDriver != null &&
                gpuDriver.equals(SWIFTSHADER_DRIVER, ignoreCase = true)

            val hasGlTransport = !gltransport.isNullOrEmpty()
            val isRanchuGralloc = gralloc != null &&
                gralloc.equals(RANCHU_GRALLOC, ignoreCase = true)
            val isAvdGlesNoPassthrough = qemuGles == AVD_GLES_NO_PASSTHROUGH

            val isEmulatorGl = hasGlTransport || isRanchuGralloc || isAvdGlesNoPassthrough

            // "Both empty" predicate distinguishes null (key not in map) from
            // empty-string ("set-but-empty") at the snapshot layer, but for
            // the no-GPU-HAL rule we treat both as the same signal class
            // ("no usable GPU HAL identifier observed"). Per DeviceSnapshot
            // KDoc, missing-key → null and explicit-empty → "" are both
            // legitimate ground-truth shapes for this property surface.
            val gpuDriverEmpty = gpuDriver.isNullOrEmpty()
            val vulkanEmpty = vulkan.isNullOrEmpty()
            val noGpuHal = gpuDriverEmpty && vulkanEmpty

            // Old / missing GLES rule: empty OR strictly-less-than 196610.
            // 196610 itself is PLAUSIBLE (real Pixel 7 / Samsung S22 value).
            val oldGles = when {
                openGlesRaw.isNullOrEmpty() -> true
                openGlesParsed == null -> false // malformed but present — no signal
                else -> openGlesParsed < MIN_PLAUSIBLE_OPENGLES_VERSION
            }

            val (pattern, score) = when {
                isSwiftShader ->
                    PATTERN_SWIFTSHADER to SCORE_SWIFTSHADER
                isEmulatorGl ->
                    PATTERN_EMULATOR_GL to SCORE_EMULATOR_GL
                noGpuHal ->
                    PATTERN_NO_GPU_HAL to SCORE_NO_GPU_HAL
                oldGles ->
                    PATTERN_OLD_GLES to SCORE_OLD_GLES
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Evidence rows — every key namespaced with `webgl_fingerprint.*`
            // per cross-cutting-#1 (no `pkg.*` raw key collisions with
            // rank-4 or other property-only probes).
            val evidence = listOf(
                Evidence(
                    key = "webgl_fingerprint.gpu_driver",
                    value = gpuDriver ?: "<unavailable>",
                    expected = "real GPU vendor driver name (kgsl/mali/qcom)",
                ),
                Evidence(
                    key = "webgl_fingerprint.gl_transport",
                    value = gltransport ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "webgl_fingerprint.gralloc",
                    value = gralloc ?: "<unavailable>",
                    expected = "real vendor gralloc HAL (qcom/mali)",
                ),
                Evidence(
                    key = "webgl_fingerprint.qemu_gles",
                    value = qemuGles ?: "<unavailable>",
                    expected = "<unset>",
                ),
                Evidence(
                    key = "webgl_fingerprint.vulkan",
                    value = vulkan ?: "<unavailable>",
                    expected = "real Vulkan HAL version (e.g. 1.3.0)",
                ),
                Evidence(
                    key = "webgl_fingerprint.opengles_version",
                    value = openGlesRaw ?: "<unavailable>",
                    expected = ">= $MIN_PLAUSIBLE_OPENGLES_VERSION (GLES 3.2)",
                ),
                Evidence(
                    key = "webgl_fingerprint.opengles_version_parsed",
                    value = openGlesParsed?.toString() ?: "<unparseable>",
                    expected = ">= $MIN_PLAUSIBLE_OPENGLES_VERSION",
                ),
                Evidence(
                    key = "webgl_fingerprint.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
                ),
            )

            ProbeResult(
                score = score,
                confidence = CONFIDENCE_DECLARATIVE,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "WebGlFingerprintProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

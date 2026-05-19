// inventory.yml rank 30, mitigation_layer L0
package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #30 — emulator.proc_version.
 *
 * Reads `/proc/version` and parses for emulator-kernel markers, host-kernel
 * leaks (Windows Subsystem for Linux build hosts, localhost.localdomain
 * defaults), x86 architecture leaks on devices claiming ARM, and stale-
 * compiler signals (GCC instead of clang on Android > 9).
 *
 * A real Pixel `/proc/version` looks like:
 *   `Linux version 5.10.157-android13-4 (build-user@build-hostname) ` +
 *   `(Android (8508608, based on r450784e) clang version 14.0.6) ` +
 *   `#1 SMP PREEMPT Tue Aug 1 03:30:00 UTC 2023`
 *
 * Emulator `/proc/version` shows tells: kernel name (`goldfish`, `ranchu`,
 * `redroid`, `qemu`), build host (`@localhost.localdomain`), arch (`x86_64`
 * in version string), compiler (`gcc 6.3.0` instead of clang).
 *
 * **Mitigation-layer note:** `shared/probes/inventory.yml:248` lists this
 * probe at `mitigation_layer: L0`. The team-lead's task brief said L1.
 * Probe defers to the inventory as the authoritative source (same
 * convention as the rank-12 severity case). KDoc header reference reads
 * "L0 per inventory.yml".
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  proc_version contains `goldfish`, `ranchu`, `redroid`, or `qemu`
 *   1.00  proc_version contains `Microsoft@WSL` or `WSL2` (Windows Subsystem
 *         for Linux build host)
 *   0.95  proc_version contains `x86_64` or `i686` AND model is phone-class
 *         (cross-source mismatch — ARM phone with x86 kernel)
 *   0.85  Build host is `localhost.localdomain` (emulator default; real
 *         OEM builds use a hostname like `<oem>-builder` or similar)
 *   0.70  Compiler is `gcc` (not `clang`) — Android has used clang
 *         exclusively since AOSP 7.0 (2016); GCC presence on a device
 *         claiming Android > 9 indicates a stale or non-AOSP toolchain
 *   0.50  proc_version is null/empty/unreadable (no observation)
 *   0.00  Normal android-N.M kernel + clang + non-localhost build host
 *
 * Confidence: 0.95 when `/proc/version` was readable (non-null);
 * 0.50 when null or throw.
 *
 * Reference: shared/probes/inventory.yml rank 30 (mitigation_layer L0).
 */
class ProcVersionProbe : Probe {
    override val id = "emulator.proc_version"
    override val rank = 30
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 100L

    companion object {
        const val PATH_PROC_VERSION = "/proc/version"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /** Maximum bytes to read — `/proc/version` is short (~300 bytes typical). */
        const val PROC_VERSION_MAX_BYTES = 1024

        /** Emulator kernel-name markers (case-insensitive substring match). */
        val EMULATOR_KERNEL_MARKERS: List<String> = listOf(
            "goldfish",
            "ranchu",
            "redroid",
            "qemu",
        )

        /** Windows Subsystem for Linux build-host markers. */
        val WSL_MARKERS: List<String> = listOf(
            "microsoft@wsl",
            "wsl2",
            "microsoft.wsl",
        )

        /** x86 architecture leak markers (case-insensitive). */
        val X86_ARCH_MARKERS: List<String> = listOf("x86_64", "i686")

        const val LOCALHOST_BUILD_HOST = "localhost.localdomain"

        const val PATTERN_EMULATOR_KERNEL = "emulator_kernel"
        const val PATTERN_WSL_HOST = "wsl_host"
        const val PATTERN_X86_ON_ARM = "x86_on_arm_phone"
        const val PATTERN_LOCALHOST_BUILD_HOST = "localhost_build_host"
        const val PATTERN_GCC_COMPILER = "gcc_compiler"
        const val PATTERN_UNREADABLE = "unreadable"
        const val PATTERN_CLEAN_ANDROID = "clean_android"

        const val SCORE_EMULATOR_OR_WSL = 1.0
        const val SCORE_X86_ON_ARM = 0.95
        const val SCORE_LOCALHOST_HOST = 0.85
        const val SCORE_GCC_COMPILER = 0.70
        const val SCORE_UNREADABLE = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read /proc/version and parse for emulator kernel-name markers, " +
                "x86 arch leak, localhost build host, GCC compiler leak"

        /** Returns the first marker from [markers] that appears (case-insensitively) in [s], or null. */
        internal fun firstMarkerIn(s: String, markers: List<String>): String? {
            val lower = s.lowercase()
            return markers.firstOrNull { it in lower }
        }

        /**
         * Extracts the `user@host` portion of `/proc/version`. Real `/proc/version`
         * format includes `(user@host)` after `Linux version <kernel>`.
         */
        internal fun extractBuildHost(s: String): String? {
            val match = Regex("\\(([^@]+)@([^)\\s]+)").find(s) ?: return null
            return match.groupValues[2].lowercase()
        }

        /**
         * Returns "clang", "gcc", or "unknown" by inspecting [s] for compiler tags.
         */
        internal fun extractCompiler(s: String): String {
            val lower = s.lowercase()
            // clang takes precedence — Android version strings sometimes mention
            // "gcc compatible" alongside clang; in that case the active compiler
            // is clang.
            if ("clang version" in lower || " clang " in lower) return "clang"
            if ("gcc version" in lower || " gcc " in lower) return "gcc"
            return "unknown"
        }

        internal fun classifyKernelName(s: String): String {
            val lower = s.lowercase()
            return when {
                "goldfish" in lower -> "goldfish"
                "ranchu" in lower -> "ranchu"
                "redroid" in lower -> "redroid"
                "qemu" in lower -> "qemu"
                "microsoft@wsl" in lower || "wsl2" in lower -> "wsl"
                "android" in lower -> "android"
                "linaro" in lower -> "linaro"
                else -> "other"
            }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val raw: String? = try {
                ctx.readFile(PATH_PROC_VERSION, maxBytes = PROC_VERSION_MAX_BYTES)
            } catch (_: Throwable) {
                null
            }
            val content = raw?.trim()

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val isUnreadable = content.isNullOrEmpty()

            val emuMarker = if (!isUnreadable) firstMarkerIn(content!!, EMULATOR_KERNEL_MARKERS) else null
            val wslMarker = if (!isUnreadable) firstMarkerIn(content!!, WSL_MARKERS) else null
            val x86Marker = if (!isUnreadable) firstMarkerIn(content!!, X86_ARCH_MARKERS) else null

            val buildHost = if (!isUnreadable) extractBuildHost(content!!) else null
            val isLocalhost = buildHost == LOCALHOST_BUILD_HOST

            val compiler = if (!isUnreadable) extractCompiler(content!!) else "unknown"
            val isGcc = compiler == "gcc"

            val kernelName = if (!isUnreadable) classifyKernelName(content!!) else "unknown"

            val hasX86ArchLeak = x86Marker != null && phoneClass

            val (pattern, score) = when {
                isUnreadable ->
                    PATTERN_UNREADABLE to SCORE_UNREADABLE
                emuMarker != null ->
                    PATTERN_EMULATOR_KERNEL to SCORE_EMULATOR_OR_WSL
                wslMarker != null ->
                    PATTERN_WSL_HOST to SCORE_EMULATOR_OR_WSL
                hasX86ArchLeak ->
                    PATTERN_X86_ON_ARM to SCORE_X86_ON_ARM
                isLocalhost ->
                    PATTERN_LOCALHOST_BUILD_HOST to SCORE_LOCALHOST_HOST
                isGcc ->
                    PATTERN_GCC_COMPILER to SCORE_GCC_COMPILER
                else ->
                    PATTERN_CLEAN_ANDROID to SCORE_CLEAN
            }

            val confidence = if (!isUnreadable) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            // Record only the first 256 chars per spec — /proc/version is short anyway.
            val truncatedRaw = content?.take(256) ?: "null"

            val evidence = listOf(
                Evidence(
                    key = "proc_version.raw",
                    value = truncatedRaw,
                    expected = "<no emulator markers>",
                ),
                Evidence(
                    key = "proc_version.kernel_name",
                    value = kernelName,
                    expected = "android",
                ),
                Evidence(
                    key = "proc_version.has_x86_arch_leak",
                    value = hasX86ArchLeak.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "proc_version.build_host",
                    value = buildHost ?: "unknown",
                    expected = "<not localhost>",
                ),
                Evidence(
                    key = "proc_version.compiler",
                    value = compiler,
                    expected = "clang on recent Android",
                ),
                Evidence(
                    key = "proc_version.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN_ANDROID,
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
                "ProcVersionProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 27, mitigation_layer L1
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
 * Probe #27 — emulator.cpu_abi.
 *
 * Detects emulators by checking the device's CPU ABI list against expected
 * phone-class values. Real Android phones since 2016 are ARM64
 * (`arm64-v8a` primary + `armeabi-v7a` + `armeabi` 32-bit compat). x86
 * emulators report `x86_64` or `x86` primary. Houdini ARM-to-x86
 * translation layers (Genymotion, some Chinese OEM devkits) expose BOTH
 * ARM and x86 entries in the abilist — a dual-arch signature no real
 * phone ships.
 *
 * Signal sources:
 *   • `ro.product.cpu.abi` — primary (first) ABI string
 *   • `ro.product.cpu.abilist` — comma-separated full list
 *   • `ro.product.model` — for the "x86 + ARM-only-OEM model" cross-check
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  Primary ABI is `x86` or `x86_64`            (canonical x86 emulator)
 *   1.00  abilist contains BOTH ARM and x86 entries   (Houdini bridge)
 *   1.00  Primary ABI is x86 AND model is phone-class (cross-source mismatch
 *         — the model claims a known ARM-only OEM)
 *   0.85  abilist is empty OR single-entry with an unknown ABI string
 *   0.85  Only `armeabi` (no v7a, no 64) — stripped/older emulator
 *   0.00  Real ARM phone (arm64-v8a primary; armeabi-v7a + armeabi in 32-bit
 *         set; no x86 entries anywhere)
 *
 * Confidence: 0.95 when at least one of (primary ABI, abilist) is
 * non-null; 0.50 when both null.
 *
 * **Code reuse:** the "model is phone-class" check reuses
 * `NetworkTypeProbe.isPhoneClassModel` via internal-module visibility — same
 * 15-entry Pixel/Galaxy/OnePlus/etc. list. An invariant test anchors the
 * shared dependency so any drift on either probe surfaces immediately.
 *
 * Reference: shared/probes/inventory.yml rank 27 (mitigation_layer L1).
 */
class CpuAbiProbe : Probe {
    override val id = "emulator.cpu_abi"
    override val rank = 27
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 100L

    companion object {
        const val PROP_PRIMARY_ABI = "ro.product.cpu.abi"
        const val PROP_ABILIST = "ro.product.cpu.abilist"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val ABI_ARM64_V8A = "arm64-v8a"
        const val ABI_ARMEABI_V7A = "armeabi-v7a"
        const val ABI_ARMEABI = "armeabi"
        const val ABI_X86 = "x86"
        const val ABI_X86_64 = "x86_64"

        /** ABI strings shipped on real or known emulator Android stacks. */
        val KNOWN_ABIS: Set<String> = setOf(
            ABI_ARM64_V8A, ABI_ARMEABI_V7A, ABI_ARMEABI,
            ABI_X86_64, ABI_X86,
            "mips64", "mips",
        )

        val ARM_ABI_STRINGS: Set<String> = setOf(ABI_ARM64_V8A, ABI_ARMEABI_V7A, ABI_ARMEABI)
        val X86_ABI_STRINGS: Set<String> = setOf(ABI_X86_64, ABI_X86)

        const val PATTERN_X86_PRIMARY = "x86_primary"
        const val PATTERN_HOUDINI_DUAL_ARCH = "houdini_dual_arch"
        const val PATTERN_X86_ON_ARM_OEM_MODEL = "x86_on_arm_oem_model"
        const val PATTERN_EMPTY_OR_UNKNOWN_ABILIST = "empty_or_unknown_abilist"
        const val PATTERN_STRIPPED_ARMEABI_ONLY = "stripped_armeabi_only"
        const val PATTERN_CLEAN_ARM = "clean_arm"

        const val SCORE_X86_OR_HOUDINI = 1.0
        const val SCORE_EMPTY_OR_STRIPPED = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read Build.SUPPORTED_ABIS + ro.product.cpu.abilist; detect x86 " +
                "primary on ARM-claimed model, dual-arch Houdini bridge, and " +
                "minimal emulator-stripped ABI lists"

        /** Split comma-separated ABI list, trim, lowercase, dropping empties. */
        internal fun parseAbiList(raw: String?): List<String> {
            if (raw.isNullOrEmpty()) return emptyList()
            return raw.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
        }

        internal fun isX86(abi: String?): Boolean =
            abi != null && abi.lowercase() in X86_ABI_STRINGS

        internal fun isArm(abi: String?): Boolean =
            abi != null && abi.lowercase() in ARM_ABI_STRINGS
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val primaryAbiRaw: String? = try {
                ctx.getSystemProperty(PROP_PRIMARY_ABI)
            } catch (_: Throwable) {
                null
            }
            val abilistRaw: String? = try {
                ctx.getSystemProperty(PROP_ABILIST)
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val primary = primaryAbiRaw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val abilist = parseAbiList(abilistRaw)

            val hasX86 = primary in X86_ABI_STRINGS || abilist.any { it in X86_ABI_STRINGS }
            val hasArm = primary in ARM_ABI_STRINGS || abilist.any { it in ARM_ABI_STRINGS }
            val hasDualArch = hasX86 && hasArm
            val primaryIsX86 = primary in X86_ABI_STRINGS

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            // "abilist empty or single-entry unknown" — only fires when we
            // actually observed an abilist (raw was non-null) but it parsed
            // to 0 items or 1 unknown item. Pure-null abilist with a valid
            // primary ABI is not the same as "stripped abilist".
            val abilistEmptyOrUnknown = abilistRaw != null && (
                abilist.isEmpty() ||
                    (abilist.size == 1 && abilist[0] !in KNOWN_ABIS)
                )

            // "only armeabi" — primary is the legacy 32-bit armeabi AND abilist
            // contains nothing 64-bit. Real 2016+ phones ship arm64-v8a primary.
            val strippedArmeabiOnly = primary == ABI_ARMEABI &&
                !abilist.any { it == ABI_ARM64_V8A || it == ABI_X86_64 }

            // x86-on-ARM-OEM-model mismatch only meaningful when we actually
            // observed a primary ABI AND a model.
            val x86OnArmOemModel = primaryIsX86 && phoneClass

            val (pattern, score) = when {
                primaryIsX86 ->
                    // Multiple sub-patterns collapse here; pick the most-specific
                    // forensic label.
                    when {
                        x86OnArmOemModel -> PATTERN_X86_ON_ARM_OEM_MODEL to SCORE_X86_OR_HOUDINI
                        else -> PATTERN_X86_PRIMARY to SCORE_X86_OR_HOUDINI
                    }
                hasDualArch ->
                    PATTERN_HOUDINI_DUAL_ARCH to SCORE_X86_OR_HOUDINI
                strippedArmeabiOnly ->
                    PATTERN_STRIPPED_ARMEABI_ONLY to SCORE_EMPTY_OR_STRIPPED
                abilistEmptyOrUnknown ->
                    PATTERN_EMPTY_OR_UNKNOWN_ABILIST to SCORE_EMPTY_OR_STRIPPED
                else ->
                    PATTERN_CLEAN_ARM to SCORE_CLEAN
            }

            val anyReadable = primary != null || abilistRaw != null
            val confidence = if (anyReadable) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val abiMatchesOem = when {
                !phoneClass -> "unknown_model"
                primaryIsX86 -> "false"
                hasArm -> "true"
                else -> "unknown_model"
            }

            val evidence = listOf(
                Evidence(
                    key = "cpu.supported_abis",
                    value = if (abilist.isEmpty()) abilistRaw ?: "null" else abilist.joinToString(","),
                    expected = "<ARM-list>",
                ),
                Evidence(
                    key = "cpu.primary_abi",
                    value = primary ?: "null",
                    expected = "arm64-v8a or similar",
                ),
                Evidence("cpu.has_x86", hasX86.toString(), expected = "false"),
                Evidence("cpu.has_arm", hasArm.toString(), expected = "true"),
                Evidence("cpu.has_dual_arch", hasDualArch.toString(), expected = "false"),
                Evidence(
                    key = "cpu.ro_product_cpu_abi",
                    value = primaryAbiRaw ?: "null",
                    expected = "<arm-prefixed>",
                ),
                Evidence(
                    key = "cpu.abi_matches_model_oem",
                    value = abiMatchesOem,
                    expected = "true",
                ),
                Evidence("cpu.pattern", pattern, expected = PATTERN_CLEAN_ARM),
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
                "CpuAbiProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

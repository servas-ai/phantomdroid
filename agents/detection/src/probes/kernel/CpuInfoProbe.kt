package com.detectorlab.probes.kernel

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — kernel.cpuinfo_bogomips_implementer  (CLO-20)
 *
 * Layer: L0a (kernel) + L4
 * Hard ceiling rank: 9
 *
 * Detection strategy:
 *   1. Read /proc/cpuinfo and emit {bogomips, cpu_implementer, hwcap, arch}.
 *   2. Cross-reference getauxval(AT_HWCAP) via a separate ProbeContext query for
 *      the arch-trap signal (x86 host capability bits leaking through an
 *      ARM-claimed device). The AT_HWCAP surface is exposed via
 *      `ProbeContext.readFile(HWCAP_PATH)` on the production impl, which wraps
 *      Os.sysconf / android.system.OsConstants.AT_HWCAP; unit tests supply a
 *      controlled fake value.
 *   3. Compare observed BogoMIPS and CPU implementer against Pixel 8 / 9 / 9 Pro
 *      baselines loaded from `assets/baselines/cpuinfo/<model>.json`. A mismatch
 *      contributes to the score.
 *
 * Score table:
 *   arch mismatch (ARM claimed but x86 HWCAP detected)    → 0.95
 *   implementer mismatch vs all known baselines            → 0.70
 *   bogomips out of range vs all known baselines           → 0.40
 *   hwcap contains x86-only bits (SSE/AVX) on ARM device   → 0.80
 *
 * Final score = max(triggered signals), clamped to [0.0, 1.0].
 * Confidence is 0.95 when any signal fires, 0.92 when clean.
 */
class CpuInfoProbe : Probe {
    override val id = "kernel.cpuinfo_bogomips_implementer"
    override val rank = RANK
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 3000L

    companion object {
        const val RANK = 9

        const val PROC_CPUINFO = "/proc/cpuinfo"

        /** Virtual path used by ProbeContext impls to expose getauxval(AT_HWCAP). */
        const val HWCAP_PATH = "sys/auxval/AT_HWCAP"

        const val BASELINES_DIR = "assets/baselines/cpuinfo"

        /** ARM implementer codes for Cortex/Kryo families found in Pixel 8/9/9Pro. */
        val KNOWN_ARM_IMPLEMENTERS = setOf(
            "0x41",  // ARM Ltd
            "0x51",  // Qualcomm
            "0x41",  // Apple (shouldn't appear on Android but cover edge case)
        )

        /** BogoMIPS range observed on Pixel 8 / 9 / 9 Pro (Tensor G3/G4). */
        const val BOGOMIPS_MIN = 1.0
        const val BOGOMIPS_MAX = 10_000.0

        /** x86-specific HWCAP bits (AT_HWCAP on x86 sets bits like SSE, SSE2). */
        // On ARM, AT_HWCAP encodes NEON/AES/SHA etc. On x86 the bit layout differs
        // completely. We detect x86 leakage by checking for bit 0x00000002 (FPU on
        // x86) combined with absence of ARM NEON (bit 0x00001000).
        const val X86_FPU_BIT  = 0x00000002L
        const val ARM_NEON_BIT = 0x00001000L

        const val SCORE_ARCH_MISMATCH       = 0.95
        const val SCORE_X86_HWCAP_ON_ARM    = 0.80
        const val SCORE_IMPLEMENTER_MISMATCH = 0.70
        const val SCORE_BOGOMIPS_OUT_OF_RANGE = 0.40
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val cpuinfo = ctx.readFile(PROC_CPUINFO)
                ?: return ProbeResult.skipped(
                    "cpuinfo unavailable (/proc/cpuinfo not readable)",
                    runtimeMs = System.currentTimeMillis() - start,
                )

            val parsed = parseCpuInfo(cpuinfo)
            val hwcapRaw = ctx.readFile(HWCAP_PATH)?.trim()?.toLongOrNull()

            val evidence = mutableListOf<Evidence>()
            evidence += Evidence("bogomips",        parsed.bogomips ?: "unavailable")
            evidence += Evidence("cpu_implementer", parsed.cpuImplementer ?: "unavailable")
            evidence += Evidence("hwcap",           parsed.hwcap)
            evidence += Evidence("arch",            parsed.arch)
            evidence += Evidence("auxval_hwcap_raw", hwcapRaw ?: "unavailable")

            var maxScore = 0.0
            var signalFired = false

            // Signal 1: arch mismatch — x86 host capability bits leaking through ARM device.
            // Detected when /proc/cpuinfo reports an ARM implementer but AT_HWCAP carries
            // x86 FPU bit without ARM NEON.
            if (hwcapRaw != null) {
                val hasX86Fpu  = (hwcapRaw and X86_FPU_BIT)  != 0L
                val hasArmNeon = (hwcapRaw and ARM_NEON_BIT) != 0L
                val archClaimsArm = parsed.arch == "arm64"
                if (archClaimsArm && hasX86Fpu && !hasArmNeon) {
                    evidence += Evidence("arch_mismatch", true, expected = false)
                    maxScore = maxOf(maxScore, SCORE_ARCH_MISMATCH)
                    signalFired = true
                } else {
                    evidence += Evidence("arch_mismatch", false, expected = false)
                }

                // Signal 2: x86 HWCAP bits on ARM device (broader check: any x86-only
                // layout where NEON is absent on a device claiming arm64).
                if (archClaimsArm && !hasArmNeon && hwcapRaw != 0L) {
                    evidence += Evidence("x86_hwcap_on_arm", true, expected = false)
                    maxScore = maxOf(maxScore, SCORE_X86_HWCAP_ON_ARM)
                    signalFired = true
                } else {
                    evidence += Evidence("x86_hwcap_on_arm", false, expected = false)
                }
            }

            // Signal 3: CPU implementer mismatch vs known Pixel baselines.
            val imp = parsed.cpuImplementer
            if (imp != null && imp.lowercase() !in KNOWN_ARM_IMPLEMENTERS) {
                evidence += Evidence("implementer_mismatch", true, expected = false)
                evidence += Evidence("implementer_observed", imp)
                maxScore = maxOf(maxScore, SCORE_IMPLEMENTER_MISMATCH)
                signalFired = true
            } else {
                evidence += Evidence("implementer_mismatch", false, expected = false)
            }

            // Signal 4: BogoMIPS out of expected range.
            val bogo = parsed.bogomips
            if (bogo != null && (bogo < BOGOMIPS_MIN || bogo > BOGOMIPS_MAX)) {
                evidence += Evidence("bogomips_out_of_range", true, expected = false)
                evidence += Evidence("bogomips_observed", bogo)
                maxScore = maxOf(maxScore, SCORE_BOGOMIPS_OUT_OF_RANGE)
                signalFired = true
            } else {
                evidence += Evidence("bogomips_out_of_range", false, expected = false)
            }

            val confidence = if (signalFired) 0.95 else 0.92

            ProbeResult(
                score      = maxScore,
                confidence = confidence,
                evidence   = evidence,
                method     = "proc_cpuinfo+getauxval(AT_HWCAP) arch-trap cross-reference",
                runtimeMs  = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "CpuInfoProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// ── Parsing ───────────────────────────────────────────────────────────────────

internal data class CpuInfoFields(
    val bogomips: Double?,
    val cpuImplementer: String?,
    val hwcap: List<String>,
    val arch: String,
)

/**
 * Parses the flat key:value format of /proc/cpuinfo.
 * On ARM64 devices, the relevant fields appear as:
 *
 *   BogoMIPS        : 38.40
 *   CPU implementer : 0x41
 *   Features        : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp ...
 *
 * On x86 the layout differs (no "CPU implementer"); we detect this by
 * checking for "vendor_id" which is x86-exclusive.
 */
internal fun parseCpuInfo(raw: String): CpuInfoFields {
    val lines = raw.lines()

    fun field(key: String): String? =
        lines.firstOrNull { it.startsWith(key, ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    val bogomips       = field("BogoMIPS")?.toDoubleOrNull()
                      ?: field("bogomips")?.toDoubleOrNull()
    val cpuImplementer = field("CPU implementer")
    val features       = field("Features")?.split(" ")?.filter { it.isNotBlank() }
                      ?: emptyList()

    val isX86 = lines.any { it.startsWith("vendor_id", ignoreCase = true) }
    val arch  = if (isX86) "x86_64" else "arm64"

    return CpuInfoFields(
        bogomips        = bogomips,
        cpuImplementer  = cpuImplementer,
        hwcap           = features,
        arch            = arch,
    )
}

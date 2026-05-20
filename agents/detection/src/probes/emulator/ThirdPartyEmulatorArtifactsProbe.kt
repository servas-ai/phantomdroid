// inventory.yml rank 4.5 (code-rank 86), mitigation_layer L1
package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #4.5 — emulator.third_party_artifacts.
 *
 * Detects the boot-time init.rc and fstab files shipped by the most
 * common THIRD-PARTY (non-AOSP) Android emulator stacks: Nox, Andy,
 * MEmu (MicroVirt), BlueStacks, MicroVirt, Droid4x, and the generic
 * Android-x86 / PhoenixOS lineage. These are the cloud-phone-class
 * emulators the wild-detection inventory shows as the actual targets
 * — distinct from the AOSP goldfish/ranchu stack covered by rank-4
 * [QemuArtifactsProbe].
 *
 * **Why a separate probe**: rank-4 scans for QEMU/Goldfish kernel
 * device nodes + AVD canonical properties. The third-party emulators
 * are typically NOT running QEMU/Goldfish — they ship custom
 * VirtualBox / KVM-based images with their own init.rc surface and
 * their own boot-time fstab files. The leak paths are disjoint:
 * rank-4 catches AOSP-emulator targets; this probe catches the
 * BlueStacks / Nox / MEmu cloud-phone targets that dominate real-world
 * detector telemetry. Per the Power-13 real-world inventory,
 * `audit/spoof-stack/real-world-detectors.md` row "Third-party
 * emulators (Nox/Andy/MEmu/BlueStacks/MicroVirt/Droid4x init files)"
 * — this is a CRITICAL-priority gap precisely because rank-4 was
 * QEMU/Genymotion-only.
 *
 * Signal classes (CRITICAL because each artifact path is uniquely
 * vendor-specific — a real Android device does NOT ship `/init.nox.rc`
 * or `/fstab.bluestacks`):
 *
 *   • **Nox artifacts (1.0)**: `/init.nox.rc`, `/init.bignox.rc`.
 *     Nox Player ships these init scripts as part of its Android-x86
 *     image. Either path's presence is dispositive.
 *   • **Andy artifacts (1.0)**: `/init.andy.rc`, `/fstab.andy`. Andy
 *     emulator (defunct but still on cloud-phone farms) ships these.
 *   • **MEmu / MicroVirt artifacts (1.0)**: `/init.ttVM_x86.rc`,
 *     `/fstab.ttVM_x86`, `/init.microvirt.rc`. The MEmu emulator runs
 *     atop the MicroVirt virtualization stack.
 *   • **BlueStacks artifacts (1.0)**: `/init.bluestacks.rc`,
 *     `/fstab.bluestacks`. BlueStacks is the most prevalent cloud-
 *     phone target in 2024-2026.
 *   • **Droid4x artifacts (1.0)**: `/init.droid4x.rc`, `/fstab.droid4x`.
 *   • **Android-x86 / generic-x86 artifacts (1.0)**:
 *     `/ueventd.android_x86.rc`, `/x86.prop`. The generic Android-x86
 *     lineage covers PhoenixOS, RemixOS, and several cloud-phone
 *     repackagings. NOT to be confused with the ABI-side x86 marker
 *     scanned by rank-27 — these are filesystem init artifacts, not
 *     ABI strings.
 *   • **Clean (0.0)**: no third-party emulator artifact observed.
 *
 * Cascade ordering: vendor-specific paths fire FIRST so the pattern
 * label identifies the vendor on score-tie. Android-x86 generic path
 * is checked LAST because it can co-fire with vendor-specific paths
 * (PhoenixOS for example ships both Android-x86 generic + its own
 * init files); when both fire, the vendor-specific rule wins for
 * forensic clarity.
 *
 * Uses ONLY the base [ProbeContext] surface (`fileExists`) — no
 * constructor-injected suppliers, no new core-contract methods. Same
 * shape as rank-4 [QemuArtifactsProbe] and rank-3
 * [com.detectorlab.probes.root.SuDetectionProbe].
 *
 * Scoring (max wins; first-match cascade in vendor-priority order):
 *   1.00  PATTERN_NOX_ARTIFACT
 *   1.00  PATTERN_ANDY_ARTIFACT
 *   1.00  PATTERN_MEMU_ARTIFACT
 *   1.00  PATTERN_BLUESTACKS_ARTIFACT
 *   1.00  PATTERN_DROID4X_ARTIFACT
 *   1.00  PATTERN_ANDROID_X86_GENERIC
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  Any fileExists accessor succeeded (NORMAL)
 *   0.50  Every fileExists call threw (DEGRADED — no observation
 *         possible)
 *
 * Evidence rows are emitted under the probe-scoped
 * `third_party_emulator.*` namespace per cross-cutting #1. Each
 * vendor gets a comma-joined list of observed paths (or "none") so
 * a consumer can see which exact files leaked.
 *
 * **inventoryRank vs code-rank**: inventory rank is 4.5; code-rank is
 * 86 (next unused high slot, same pattern as
 * [com.detectorlab.probes.runtime.DebuggerTracerPidProbe] which uses
 * code-rank 80 for inventory rank 8.5). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 4.5 (mitigation_layer L1);
 * audit/spoof-stack/real-world-detectors.md row "Third-party emulators";
 * audit/spoof-stack/real-world-gap-list.md Gap #4. Origin:
 * strazzere/anti-emulator FindEmulator.java + mofneko/EmulatorDetector
 * + CalebFenton/AndroidEmulatorDetect canonical paths.
 */
class ThirdPartyEmulatorArtifactsProbe : Probe {
    override val id = "emulator.third_party_artifacts"
    override val rank = RANK
    override val inventoryRank = 4.5
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 200L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank 4.5,
         * but `Probe.rank: Int` can't represent 4.5. Picked 86 (next
         * unused integer above the META-22 A17 reserved range 61-71
         * and the existing 80/82-85 fractional-rank cluster).
         * Cross-cutting #7.
         */
        const val RANK = 86

        // Vendor-specific path lists. Each list is FILESYSTEM-EXISTENCE
        // checked; presence of ANY entry in a list trips that vendor's
        // pattern at score 1.0.

        /** Nox Player Android-x86 init scripts. */
        val NOX_PATHS: List<String> = listOf(
            "/init.nox.rc",
            "/init.bignox.rc",
        )

        /** Andy emulator init + fstab. */
        val ANDY_PATHS: List<String> = listOf(
            "/init.andy.rc",
            "/fstab.andy",
        )

        /** MEmu / MicroVirt (TTVM) init + fstab. */
        val MEMU_PATHS: List<String> = listOf(
            "/init.ttVM_x86.rc",
            "/fstab.ttVM_x86",
            "/init.microvirt.rc",
        )

        /** BlueStacks Android-x86 init + fstab. */
        val BLUESTACKS_PATHS: List<String> = listOf(
            "/init.bluestacks.rc",
            "/fstab.bluestacks",
        )

        /** Droid4x init + fstab. */
        val DROID4X_PATHS: List<String> = listOf(
            "/init.droid4x.rc",
            "/fstab.droid4x",
        )

        /**
         * Generic Android-x86 lineage paths. Cover PhoenixOS, RemixOS,
         * generic x86 cloud-phone repackagings. NOT vendor-specific:
         * ordered LAST in the cascade so vendor-specific paths win on
         * tie for forensic clarity.
         */
        val ANDROID_X86_GENERIC_PATHS: List<String> = listOf(
            "/ueventd.android_x86.rc",
            "/x86.prop",
        )

        const val PATTERN_NOX_ARTIFACT = "nox_artifact"
        const val PATTERN_ANDY_ARTIFACT = "andy_artifact"
        const val PATTERN_MEMU_ARTIFACT = "memu_artifact"
        const val PATTERN_BLUESTACKS_ARTIFACT = "bluestacks_artifact"
        const val PATTERN_DROID4X_ARTIFACT = "droid4x_artifact"
        const val PATTERN_ANDROID_X86_GENERIC = "android_x86_generic"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_THIRD_PARTY_EMULATOR = 1.0
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "fileExists scan for third-party emulator init.rc + fstab " +
                "artifacts: Nox, Andy, MEmu/MicroVirt, BlueStacks, " +
                "Droid4x, generic Android-x86 (Power-13 Gap #4)"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-path try/catch so a throwing accessor doesn't
            // suppress observation of the other paths.
            var fileCheckAttempted = 0
            var fileCheckSucceeded = 0

            fun checkPath(path: String): Boolean {
                fileCheckAttempted++
                return try {
                    val present = ctx.fileExists(path)
                    fileCheckSucceeded++
                    present
                } catch (_: Throwable) {
                    false
                }
            }

            fun checkList(paths: List<String>): List<String> =
                paths.filter { checkPath(it) }

            val noxHits = checkList(NOX_PATHS)
            val andyHits = checkList(ANDY_PATHS)
            val memuHits = checkList(MEMU_PATHS)
            val bluestacksHits = checkList(BLUESTACKS_PATHS)
            val droid4xHits = checkList(DROID4X_PATHS)
            val androidX86Hits = checkList(ANDROID_X86_GENERIC_PATHS)

            // Cascade — vendor-specific FIRST, generic Android-x86 LAST.
            val (pattern, score) = when {
                noxHits.isNotEmpty() ->
                    PATTERN_NOX_ARTIFACT to SCORE_THIRD_PARTY_EMULATOR
                andyHits.isNotEmpty() ->
                    PATTERN_ANDY_ARTIFACT to SCORE_THIRD_PARTY_EMULATOR
                memuHits.isNotEmpty() ->
                    PATTERN_MEMU_ARTIFACT to SCORE_THIRD_PARTY_EMULATOR
                bluestacksHits.isNotEmpty() ->
                    PATTERN_BLUESTACKS_ARTIFACT to SCORE_THIRD_PARTY_EMULATOR
                droid4xHits.isNotEmpty() ->
                    PATTERN_DROID4X_ARTIFACT to SCORE_THIRD_PARTY_EMULATOR
                androidX86Hits.isNotEmpty() ->
                    PATTERN_ANDROID_X86_GENERIC to SCORE_THIRD_PARTY_EMULATOR
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence degraded ONLY when every fileExists call
            // threw. A real device should land in NORMAL on the first
            // successful accessor return (even if all paths return
            // false — that's the clean answer).
            val confidence = if (fileCheckSucceeded > 0)
                CONFIDENCE_NORMAL
            else
                CONFIDENCE_DEGRADED

            fun listOrNone(items: List<String>): String =
                if (items.isEmpty()) "none" else items.joinToString(",")

            val anyHit = noxHits.isNotEmpty() || andyHits.isNotEmpty() ||
                memuHits.isNotEmpty() || bluestacksHits.isNotEmpty() ||
                droid4xHits.isNotEmpty() || androidX86Hits.isNotEmpty()

            val evidence = listOf(
                // Probe-scoped namespace `third_party_emulator.*` per
                // cross-cutting #1.
                Evidence(
                    key = "third_party_emulator.nox_paths_present",
                    value = listOrNone(noxHits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.andy_paths_present",
                    value = listOrNone(andyHits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.memu_paths_present",
                    value = listOrNone(memuHits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.bluestacks_paths_present",
                    value = listOrNone(bluestacksHits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.droid4x_paths_present",
                    value = listOrNone(droid4xHits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.android_x86_generic_paths_present",
                    value = listOrNone(androidX86Hits),
                    expected = "none",
                ),
                Evidence(
                    key = "third_party_emulator.any_artifact_present",
                    value = anyHit.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "third_party_emulator.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
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
                "ThirdPartyEmulatorArtifactsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

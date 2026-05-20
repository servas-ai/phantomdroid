// inventory.yml rank 9.8 (code rank 85 — Int interface, see KDoc),
// BEST-STACK §IV Hard Ceiling #1, MASTG MSTG-RESILIENCE-6
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #9.8 — integrity.prologue_got_hooks (DECLARATIVE VARIANT).
 *
 * Inventory entry: id `integrity.prologue_got_hooks`, severity CRITICAL,
 * android_layer NATIVE, mitigation_layer `not_spoofable`. Two
 * complementary surfaces:
 *
 *   1. GOT/PLT entry comparison — each entry in the Global Offset Table
 *      of every loaded shared object is compared against the expected
 *      function pointer (the canonical resolution to the library that
 *      provides the symbol). An entry that resolves to an unexpected
 *      target library (e.g. `/data/local/tmp/frida.so` instead of
 *      `libc.so`) is direct evidence that the dynamic linker's symbol
 *      resolution table was overwritten — the canonical
 *      `LD_PRELOAD`-style or `__libc_init`-time hook installer
 *      footprint.
 *   2. `rwxp` memory segments — pages in `/proc/self/maps` that have
 *      `rwxp` (simultaneously writable and executable) permissions.
 *      Real Android `.text` sections are mapped `r-xp` (read+execute
 *      only); `rwxp` means an inline-hook installer has temporarily
 *      flipped the page to writable to patch a trampoline AND the
 *      page is still writable (typical after a hook injection that
 *      doesn't restore the original `r-xp` mapping). Any rwxp page in
 *      a target process is a SMOKING-GUN: real Android processes
 *      never produce rwxp pages organically.
 *
 * **BEST-STACK §IV Hard Ceiling #1 — UNCOUNTERED by FOSS in 2026.**
 * Same hard-ceiling story as rank-9.7 `NativePrologueHashProbe`: this
 * surface CANNOT be lied about. A native-side scan reads `/proc/self/maps`
 * via direct syscall and walks each shared object's GOT table by
 * reading the ELF header and section data directly from the memory
 * region. No FOSS SpoofStack in 2026 has a countermeasure against a
 * verifier that performs this scan — the only way to keep this surface
 * clean is to NOT install any inline / GOT-table hooks (i.e. use a
 * hook-free detection-evasion approach: Magisk DenyList, bind-mount
 * overlays, NeoZygisk's hook-free Zygote namespace isolation). The
 * mitigation_layer is `not_spoofable` in inventory for exactly this
 * reason.
 *
 * **Declarative variant note.** This JVM-side probe is the declarative-
 * snapshot complement of the un-snapshottable native scanner.
 * `gotPltAnomalies` and `rwxpMemorySegments` are populated by a
 * RUNTIME NATIVE-SIDE harness at capture time (the actual measurement
 * requires a `/proc/self/maps` walk + GOT-table inspection via direct
 * pointer reads — neither is available to JVM code). When no
 * measurement was performed, both surfaces are empty and the probe
 * scores zero (absent != clean — same semantics as rank-9.7).
 *
 * **Scoring (additive, capped at 1.0):**
 *   1.00  Any non-empty `rwxpMemorySegments` — CRITICAL, dispositive.
 *         An rwxp page in a real Android process IS a hook. One page
 *         is enough.
 *   0.50  Each entry in `gotPltAnomalies` — additive, capped at 1.0.
 *         Two GOT anomalies saturate the score; the cap reflects that
 *         the CRITICAL verdict is binary (any GOT hijack ==
 *         compromised).
 *   0.00  No measurement performed (both surfaces empty) OR
 *         measurement performed and no anomalies found.
 *
 * **No FP class.** The CRITICAL severity + dispositive 1.0 on rwxp
 * has no realistic false-positive class on production cloud-phone
 * targets — legitimate Android apps do not produce rwxp pages.
 * Notable historical exception: the ART JIT compiler once briefly
 * mapped JIT'd code pages as rwx during the compile-then-execute
 * transition, but Android 9+ moved JIT to a separate write-then-exec
 * remapping cycle that never has both permissions simultaneously on
 * the same page. Production cloud-phone targets running API 28+ have
 * no organic rwxp source — the signal is structurally load-bearing.
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml lists
 * this probe at fractional rank 9.8. Code rank 85 picked (unused
 * sequence after `NativePrologueHashProbe` at code rank 84). Same
 * handling pattern as the rest of the rank-9.x family. Tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * **Cross-cutting #1 (evidence-key namespacing)**: all evidence keys
 * are namespaced under `integrity.prologue_got_hooks.*` per commit
 * `fa35fe3`.
 *
 * Confidence:
 *   0.95  At least one measurement was provided (anomalies non-empty
 *         OR rwxp list non-empty).
 *   0.30  No measurement provided — confidence floor reflects
 *         "absent != clean".
 *
 * Reference: shared/probes/inventory.yml rank 9.8 (mitigation_layer
 * `not_spoofable`, BEST-STACK §IV Hard Ceiling #1, MASTG-RESILIENCE-6).
 */
class PrologueGotHooksProbe : Probe {
    override val id = "integrity.prologue_got_hooks"
    override val rank = RANK
    override val inventoryRank = 9.8      // canonical from inventory.yml (cross-cutting #7)
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank 9.8;
         * 85 picked (sequence after `NativePrologueHashProbe` at code
         * rank 84).
         */
        const val RANK = 85

        const val PATTERN_RWXP_SEGMENT = "rwxp_segment_present"
        const val PATTERN_GOT_PLT_ANOMALY = "got_plt_anomaly"
        const val PATTERN_NO_MEASUREMENT = "no_measurement"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_RWXP_DISPOSITIVE = 1.0
        const val SCORE_PER_GOT_ANOMALY = 0.5
        const val SCORE_CAP = 1.0
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_MEASURED = 0.95
        const val CONFIDENCE_NO_MEASUREMENT = 0.30

        const val METHOD =
            "Compare GOT/PLT entries against expected canonical target " +
                "library AND scan /proc/self/maps for rwxp (writable+executable) " +
                "memory segments (BEST-STACK §IV Hard Ceiling #1 / " +
                "MASTG-RESILIENCE-6, declarative variant — actual measurement " +
                "requires native-side /proc/self/maps walk + GOT table " +
                "inspection)"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val anomalies: Map<String, String> = try {
                ctx.queryGotPltAnomalies()
            } catch (_: Throwable) {
                emptyMap()
            }
            val rwxpSegments: List<String> = try {
                ctx.queryRwxpMemorySegments()
            } catch (_: Throwable) {
                emptyList()
            }

            val anyMeasurement = anomalies.isNotEmpty() || rwxpSegments.isNotEmpty()

            val rawScore = (if (rwxpSegments.isNotEmpty()) SCORE_RWXP_DISPOSITIVE else 0.0) +
                anomalies.size * SCORE_PER_GOT_ANOMALY
            val score = rawScore.coerceAtMost(SCORE_CAP)

            val pattern = when {
                rwxpSegments.isNotEmpty() -> PATTERN_RWXP_SEGMENT
                anomalies.isNotEmpty() -> PATTERN_GOT_PLT_ANOMALY
                !anyMeasurement -> PATTERN_NO_MEASUREMENT
                else -> PATTERN_CLEAN
            }

            val confidence = if (anyMeasurement) CONFIDENCE_MEASURED else CONFIDENCE_NO_MEASUREMENT

            val anomalySymbols = anomalies.keys.sorted()
            val anomalyDisplay = if (anomalySymbols.isEmpty()) "none"
                else anomalySymbols.joinToString(",")
            val rwxpDisplay = if (rwxpSegments.isEmpty()) "none"
                else rwxpSegments.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "integrity.prologue_got_hooks.rwxp_segment_count",
                    value = rwxpSegments.size.toString(),
                    expected = "0",
                ),
                Evidence(
                    key = "integrity.prologue_got_hooks.rwxp_segments",
                    value = rwxpDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "integrity.prologue_got_hooks.got_anomaly_count",
                    value = anomalies.size.toString(),
                    expected = "0",
                ),
                Evidence(
                    key = "integrity.prologue_got_hooks.got_anomalies",
                    value = anomalyDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "integrity.prologue_got_hooks.measurement_present",
                    value = anyMeasurement.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "integrity.prologue_got_hooks.pattern",
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
                "PrologueGotHooksProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

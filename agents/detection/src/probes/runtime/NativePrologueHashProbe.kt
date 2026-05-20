// inventory.yml rank 9.7 (code rank 84 — Int interface, see KDoc),
// BEST-STACK §IV Hard Ceiling #1, MASTG MSTG-RESILIENCE-6
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #9.7 — runtime.native_prologue_hash (DECLARATIVE VARIANT).
 *
 * Inventory entry: id `runtime.native_prologue_hash`, severity CRITICAL,
 * android_layer NATIVE, mitigation_layer `not_spoofable`. The probe
 * surface is the first 16-32 bytes of selected libc.so / libart.so /
 * libssl.so / etc. function prologues compared against the on-disk
 * baseline; any mismatch is direct evidence that an inline-hook
 * installer has patched the in-memory function entry point with a
 * trampoline. The canonical AArch64 trampoline pattern is `MOV X16,
 * #<target>` immediately followed by `BR X16` — recognizable by
 * instruction-encoding bit-pattern scan even without symbolicating the
 * trampoline target.
 *
 * **BEST-STACK §IV Hard Ceiling #1 — UNCOUNTERED by FOSS in 2026.** A
 * production-grade inline-hook detection harness performs a native-side
 * walk of every loaded shared object's `.text` section, hashes each
 * function's prologue bytes in-memory, and compares against the
 * baseline computed from the on-disk file. Any mismatch fires
 * CRITICAL; any visible trampoline pattern (MOV X16 / BR X16) is
 * dispositive evidence of an inline hook regardless of who installed it.
 *
 * The defining feature of this surface is that it CANNOT be lied about
 * — the detector reads memory itself and computes hashes locally. An
 * attacker cannot intercept the read with another inline hook (the
 * detector reads via inline assembly or a syscall that bypasses any
 * possible libc-level hook). The mitigation_layer in inventory is
 * `not_spoofable` for exactly this reason: no FOSS-tier SpoofStack
 * known in 2026 has a countermeasure against a verifier that performs
 * this scan.
 *
 * **Declarative variant note.** This JVM-side probe is the
 * declarative-snapshot complement of the un-snapshottable native
 * scanner. The snapshot's `prologueHashDeltas` and
 * `trampolinePatternCount` fields are populated by a RUNTIME PTRACE-
 * EQUIPPED HARNESS at capture time — they are NOT measurable from the
 * JVM, which has no read primitive that bypasses libc-level hooks. The
 * probe scores against the captured measurement when one is provided
 * and contributes zero (NOT a clean-signal contribution) when no
 * measurement exists. Absent measurement != clean signal: a snapshot
 * with empty deltas + zero trampolines could equally be "no scan
 * performed" or "scan performed, no hooks found", and the probe
 * scoring treats both as zero contribution rather than asserting clean.
 *
 * **Scoring (additive, capped at 1.0):**
 *   1.00  Any non-zero `trampolinePatternCount` — dispositive, CRITICAL.
 *         A visible MOV X16/BR X16 trampoline in a scanned prologue is
 *         direct evidence of an inline hook. One hook is enough.
 *   0.50  Each function-symbol entry in `prologueHashDeltas` with
 *         value `true` (hash differs from disk). Additive, capped at
 *         1.0 — two mismatches saturate; the cap reflects that the
 *         CRITICAL verdict is binary (any hook == compromised).
 *   0.00  No measurement performed (both surfaces empty) OR
 *         measurement performed and no hooks found (deltas all `false`,
 *         trampolines zero). The two cases are not distinguishable
 *         from the probe's perspective without an explicit "measurement
 *         was performed" flag; the conservative answer is zero score.
 *
 * **No FP class.** The CRITICAL severity + dispositive 1.0 on
 * trampoline-pattern detection has no realistic false-positive class
 * on production cloud-phone targets — legitimate Android apps do NOT
 * patch libc/libart function prologues. The signal is structurally
 * load-bearing.
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml lists
 * this probe at fractional rank 9.7. Code rank 84 picked (unused
 * sequence after FridaMemoryMapsProbe at code rank 83). Same handling
 * pattern as `DebuggerTracerPidProbe` (inventory 8.5 → code 80) and
 * `FridaMemoryMapsProbe` (inventory 9.0 → code 83). Tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * **Cross-cutting #1 (evidence-key namespacing)**: all evidence keys are
 * namespaced under `runtime.native_prologue_hash.*` per the commit
 * `fa35fe3` evidence-key namespacing rule.
 *
 * Confidence:
 *   0.95  At least one measurement was provided (deltas non-empty OR
 *         trampoline count > 0). The native-side scan IS the load-
 *         bearing surface; a positive measurement carries full
 *         confidence.
 *   0.30  No measurement provided. Confidence floor reflects the
 *         "absent != clean" semantics — the probe has no positive
 *         observation to score, so the result carries minimal
 *         confidence.
 *
 * Reference: shared/probes/inventory.yml rank 9.7 (mitigation_layer
 * `not_spoofable`, BEST-STACK §IV Hard Ceiling #1, MASTG-RESILIENCE-6,
 * DetectFrida reference implementation).
 */
class NativePrologueHashProbe : Probe {
    override val id = "runtime.native_prologue_hash"
    override val rank = RANK
    override val inventoryRank = 9.7      // canonical from inventory.yml (cross-cutting #7)
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank 9.7;
         * integer rank 9 is taken by `ModelBrandManufacturerProbe`. 84
         * picked (sequence after `FridaMemoryMapsProbe` at code rank 83).
         */
        const val RANK = 84

        const val PATTERN_TRAMPOLINE_DETECTED = "trampoline_detected"
        const val PATTERN_PROLOGUE_HASH_MISMATCH = "prologue_hash_mismatch"
        const val PATTERN_NO_MEASUREMENT = "no_measurement"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_TRAMPOLINE_DISPOSITIVE = 1.0
        const val SCORE_PER_HASH_MISMATCH = 0.5
        const val SCORE_CAP = 1.0
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_MEASURED = 0.95
        const val CONFIDENCE_NO_MEASUREMENT = 0.30

        const val METHOD =
            "Compare first 16-32 bytes of libc.so/libart.so function " +
                "prologues against on-disk baseline; detect MOV X16/BR X16 " +
                "inline-hook trampolines (BEST-STACK §IV Hard Ceiling #1 / " +
                "MASTG-RESILIENCE-6, declarative variant — measurement " +
                "populated by native-side ptrace harness at capture time)"

        /**
         * Returns the number of function symbols in [deltas] with hash
         * mismatch flagged true. Lifted internal for unit-test surface.
         */
        internal fun countMismatches(deltas: Map<String, Boolean>): Int =
            deltas.values.count { it }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val deltas: Map<String, Boolean> = try {
                ctx.queryPrologueHashDeltas()
            } catch (_: Throwable) {
                emptyMap()
            }
            val trampolineCount: Int = try {
                ctx.queryTrampolinePatternCount()
            } catch (_: Throwable) {
                0
            }

            val mismatchCount = countMismatches(deltas)
            val anyMeasurement = deltas.isNotEmpty() || trampolineCount > 0

            // Additive scoring: trampoline detection is dispositive (1.0);
            // hash mismatches contribute 0.5 each, capped at 1.0.
            val rawScore = (if (trampolineCount > 0) SCORE_TRAMPOLINE_DISPOSITIVE else 0.0) +
                mismatchCount * SCORE_PER_HASH_MISMATCH
            val score = rawScore.coerceAtMost(SCORE_CAP)

            val pattern = when {
                trampolineCount > 0 -> PATTERN_TRAMPOLINE_DETECTED
                mismatchCount > 0 -> PATTERN_PROLOGUE_HASH_MISMATCH
                !anyMeasurement -> PATTERN_NO_MEASUREMENT
                else -> PATTERN_CLEAN
            }

            val confidence = if (anyMeasurement) CONFIDENCE_MEASURED else CONFIDENCE_NO_MEASUREMENT

            val mismatchedSymbols = deltas.filterValues { it }.keys.sorted()
            val mismatchedDisplay = if (mismatchedSymbols.isEmpty()) "none"
                else mismatchedSymbols.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "runtime.native_prologue_hash.trampoline_count",
                    value = trampolineCount.toString(),
                    expected = "0",
                ),
                Evidence(
                    key = "runtime.native_prologue_hash.mismatch_count",
                    value = mismatchCount.toString(),
                    expected = "0",
                ),
                Evidence(
                    key = "runtime.native_prologue_hash.mismatched_symbols",
                    value = mismatchedDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.native_prologue_hash.measurement_present",
                    value = anyMeasurement.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "runtime.native_prologue_hash.pattern",
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
                "NativePrologueHashProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

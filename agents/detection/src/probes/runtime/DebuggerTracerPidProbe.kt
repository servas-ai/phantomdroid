// inventory.yml rank 8.5 (code uses rank 80 due to Int interface; tracked
// in audit/cross-cutting-followups-2026-05-19.md #7), freeRASP T2,
// MASTG-RESILIENCE-2 / MASTG-KNOW-0033
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #8.5 — runtime.debugger_tracerpid.
 *
 * Detects ptrace-attached debugger by reading `/proc/self/status` and
 * parsing the `TracerPid` field. The Linux kernel records the PID of
 * any process currently ptrace-attached to the calling thread:
 *
 *   • `TracerPid: 0` — no debugger attached (normal state on a real
 *     production device)
 *   • `TracerPid: N` (N != 0) — process `N` is ptrace-attached. On
 *     Android, this is the canonical signal for:
 *       - GDB / lldb runtime debugger
 *       - Frida's `frida-server` ptrace-injection path (separate from
 *         the more-stealthy mmap/dlopen injection — but the legacy
 *         ptrace path is still the most common entry point)
 *       - NeoZygisk attach-window during process spawn
 *       - Anti-cheat reverse-engineering tooling
 *
 * This is freeRASP T2 (Debug Detection) and OWASP MASTG MSTG-RESILIENCE-2
 * (MASTG-KNOW-0033 dynamic-analysis-tools detection).
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml lists
 * this probe at fractional rank 8.5 (its "natural" priority slot between
 * rank 8 XposedLsposed and rank 9 ModelBrandManufacturer). The
 * [Probe.rank] interface uses `Int`, which can't represent 8.5, so this
 * code uses rank 80 (unused integer outside the META-22 A17 range
 * 61-71). Same rank-system Int-vs-fractional divergence handling as
 * [com.detectorlab.probes.env.ScreenLockProbe] (inventory rank 40.5 →
 * code rank 61). Tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * **Reuses base [ProbeContext.readFile]** — same `/proc`-reading
 * shape as rank-8 [XposedLsposedProbe] (reads `/proc/self/maps`) and
 * rank-30 [com.detectorlab.probes.emulator.ProcVersionProbe] (reads
 * `/proc/version`). No new core-contract methods needed.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the rank-49/51/52/53/4/7/9 partition pattern):
 *   1.00  PATTERN_DEBUGGER_ATTACHED — `TracerPid:` field present AND
 *         the integer value is > 0
 *   0.85  PATTERN_TRACERPID_FIELD_MISSING — `/proc/self/status`
 *         readable AND non-empty BUT no `TracerPid:` line found.
 *         Possible status-file truncation / tampering (real Android
 *         `/proc/self/status` always contains the TracerPid field).
 *   0.50  PATTERN_STATUS_UNREADABLE — file is empty or `readFile`
 *         returned null. Suspicious because `/proc/self/status` is
 *         a kernel-virtual file that should always be readable by
 *         the calling thread — failure indicates either an exotic
 *         security policy (rare) or accessor-layer failure.
 *   0.00  PATTERN_CLEAN — `TracerPid: 0`
 *
 * Confidence:
 *   0.95  `/proc/self/status` was read non-null AND parsing succeeded
 *   0.50  file unreadable or accessor threw (the 0.5 SCORE_UNREADABLE
 *         rule fires at score level; confidence also degrades to
 *         reflect the unknowability)
 *
 * Reference: shared/probes/inventory.yml rank 8.5 (mitigation_layer
 * L4, freeRASP T2, MASTG-RESILIENCE-2 / MASTG-KNOW-0033).
 */
class DebuggerTracerPidProbe : Probe {
    override val id = "runtime.debugger_tracerpid"
    override val rank = RANK
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank
         * 8.5, but `Probe.rank: Int` can't represent 8.5. Picked 80
         * (unused integer outside the META-22 A17 reserved range
         * 61-71). Same handling as
         * [com.detectorlab.probes.env.ScreenLockProbe] (inventory
         * 40.5 → code 61). Tracked in
         * `audit/cross-cutting-followups-2026-05-19.md` #7.
         */
        const val RANK = 80

        const val PATH_PROC_SELF_STATUS = "/proc/self/status"

        /**
         * Maximum bytes to read from `/proc/self/status`. The full
         * status pseudo-file is ~1500 bytes on modern Android (lots
         * of memory and capability lines); 4096 is a generous cap
         * with headroom for kernel-version variance.
         */
        const val STATUS_MAX_BYTES = 4096

        const val TRACER_PID_FIELD_PREFIX = "TracerPid:"

        const val PATTERN_DEBUGGER_ATTACHED = "debugger_attached"
        const val PATTERN_TRACERPID_FIELD_MISSING = "tracerpid_field_missing"
        const val PATTERN_STATUS_UNREADABLE = "status_unreadable"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_DEBUGGER_ATTACHED = 1.0
        const val SCORE_TRACERPID_FIELD_MISSING = 0.85
        const val SCORE_STATUS_UNREADABLE = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read /proc/self/status and parse TracerPid field; non-zero " +
                "value indicates ptrace-attached debugger (freeRASP T2 / " +
                "MASTG-RESILIENCE-2)"

        /**
         * Parse the `TracerPid` integer from a `/proc/self/status`
         * file body.
         *
         * Real Android `/proc/self/status` formats the field as a
         * tab-separated line like `"TracerPid:\t0"` or
         * `"TracerPid:\t12345"`. Whitespace handling is liberal —
         * accept any sequence of tabs/spaces between the colon and
         * the integer.
         *
         * Returns:
         *   - non-null Int when a `TracerPid:` line was found AND
         *     the value after parses as a non-negative integer
         *   - null when no `TracerPid:` line was found, OR when the
         *     line was malformed (non-numeric tail)
         */
        internal fun parseTracerPid(statusBody: String): Int? {
            for (line in statusBody.lineSequence()) {
                if (!line.startsWith(TRACER_PID_FIELD_PREFIX)) continue
                val tail = line.substring(TRACER_PID_FIELD_PREFIX.length).trim()
                return tail.toIntOrNull()
            }
            return null
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val statusBody: String? = try {
                ctx.readFile(PATH_PROC_SELF_STATUS, maxBytes = STATUS_MAX_BYTES)
            } catch (_: Throwable) {
                null
            }

            val readable = !statusBody.isNullOrEmpty()
            val tracerPid: Int? = if (readable) parseTracerPid(statusBody!!) else null
            val fieldMissing = readable && tracerPid == null

            val (pattern, score) = when {
                tracerPid != null && tracerPid > 0 ->
                    PATTERN_DEBUGGER_ATTACHED to SCORE_DEBUGGER_ATTACHED
                fieldMissing ->
                    PATTERN_TRACERPID_FIELD_MISSING to SCORE_TRACERPID_FIELD_MISSING
                !readable ->
                    PATTERN_STATUS_UNREADABLE to SCORE_STATUS_UNREADABLE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence degrades when the file was unreadable OR
            // parsing failed (field-missing). The score level above
            // already reflects these as 0.5/0.85 respectively, but
            // confidence-as-separate-axis lets a downstream consumer
            // distinguish "high-score-low-confidence" cases from
            // "high-score-high-confidence" cases.
            val confidence = if (readable && tracerPid != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            // Tri-state `debugger.attached` evidence — rank-48/49/52/53/9
            // tri-state pattern. "unknown" when we couldn't determine.
            val attachedDisplay = when {
                tracerPid == null -> "unknown"
                tracerPid > 0 -> "true"
                else -> "false"
            }

            val tracerPidDisplay = when {
                tracerPid != null -> tracerPid.toString()
                readable -> "absent"     // status file present but no TracerPid line
                else -> "<unavailable>"  // status file unreadable
            }

            val evidence = listOf(
                Evidence(
                    key = "proc_self_status.tracer_pid",
                    value = tracerPidDisplay,
                    expected = "0",
                ),
                Evidence(
                    key = "proc_self_status.readable",
                    value = readable.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "debugger.attached",
                    value = attachedDisplay,
                    expected = "false",
                ),
                Evidence(
                    key = "debugger.tracerpid_pattern",
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
                "DebuggerTracerPidProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

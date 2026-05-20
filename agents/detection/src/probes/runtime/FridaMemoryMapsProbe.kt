// inventory.yml rank 9.0 (code rank 83 — Int interface, see KDoc),
// freeRASP T6, MASTG MSTG-RESILIENCE-4
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #9.0 — runtime.frida_memory_maps (DECLARATIVE VARIANT).
 *
 * Inventory entry: id `runtime.frida_memory_maps`, severity HIGH,
 * mitigation_layer L4. Detects in-process Frida instrumentation by
 * scanning three complementary surfaces:
 *
 *   1. `/proc/self/maps` — loaded shared-object names. Frida's
 *      `frida-agent`, `frida-gadget`, `libgum`, and `linjector` produce
 *      load-bearing library tokens that any live frida-server attach OR
 *      embedded-gadget injection leaves visible at the mmap level.
 *   2. `/proc/self/task/<tid>/comm` — thread names. The gum JS runtime
 *      spawns canonical thread names (`gum-js-loop`, `gmain`, `gdbus`)
 *      that survive any property-spoof and are dispositive evidence of
 *      a live gum context in the process.
 *   3. `/proc/net/tcp` — bound TCP ports. Frida's default server listens
 *      on 27042/27043; presence of either port in the calling process's
 *      bound-port set indicates a frida-server is reachable via loopback
 *      from this UID.
 *
 * **Declarative variant note.** The probe surface is the *static
 * declarative* read of three filesystem oracles via `ProbeContext`
 * accessors — `queryProcSelfMapsLibs()`, `queryRuntimeThreadNames()`,
 * and `queryOpenTcpPorts()`. Production wrappers parse the relevant
 * `/proc` nodes once at process start and populate the accessor; the
 * snapshot-side path drives the accessors from `DeviceSnapshot` fields
 * populated at capture time. Both paths preserve the same `ProbeContext`
 * contract.
 *
 * **Scoring (max wins; first-match cascade in source order — strongest
 * first per the established rank-49/51/52/53/4/7/9 partition pattern):**
 *   1.00  PATTERN_FRIDA_LIBRARY_PRESENT — any of FRIDA_LIBRARY_TOKENS
 *         observed in the maps library set. Dispositive: a real
 *         production Android process never has these libraries mapped.
 *   1.00  PATTERN_FRIDA_PORT_OPEN — port 27042 or 27043 in the bound
 *         set. Equally dispositive — the frida-server attach surface
 *         is reachable. Distinct evidence row from the library rule so
 *         consumers can route on either signal independently.
 *   0.70  PATTERN_GUM_THREAD_PRESENT — any of GUM_THREAD_NAMES observed
 *         in the runtime thread-name set. Strong signal but slightly
 *         below the 1.00 dispositive tier because the thread-name set
 *         has weaker tamper-evidence: an attacker could rename threads
 *         via `pthread_setname_np` to mask the gum signature, while the
 *         library mmap and port-bind surfaces require kernel-side
 *         changes to mask.
 *   0.00  PATTERN_CLEAN — none of the three surfaces show a frida marker.
 *
 * **FP class acknowledgment** (per the rank-51 honest-framing pattern):
 * legitimate developers using Frida for app debugging / security research
 * on their own apps will fire this rule. The HIGH severity + 1.00
 * dispositive score is calibrated for cloud-phone production targets —
 * developer / QA environments out-of-scope. Consumer-side gating
 * responsibility (per rank-50/51 consumer-gating-pattern family).
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml lists
 * this probe at fractional rank 9.0; the `Probe.rank: Int` interface
 * stores rank 9.0 as Int 9, BUT integer rank 9 is already taken by
 * `ModelBrandManufacturerProbe`. The code-rank picked here (83) keeps
 * the runner's slot-keyed routing unique while `inventoryRank = 9.0`
 * surfaces the canonical rank for aggregation/reporting. Same handling
 * pattern as `DebuggerTracerPidProbe` (inventory 8.5 → code 80) and
 * `ScreenLockProbe` (inventory 40.5 → code 61). Tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * **Cross-cutting #1 (evidence-key namespacing)**: all evidence keys are
 * namespaced under `runtime.frida_memory_maps.*` so multi-probe panel
 * consumers can route on the probe id prefix without ambiguity. Closes
 * the cross-cutting bug captured in commit `fa35fe3`.
 *
 * Confidence:
 *   0.85  At least one of the three accessors returned a non-empty set
 *         (positive observation — a real measurement was taken).
 *   0.50  All three accessors returned empty (no observation possible —
 *         the production wrapper or snapshot had no measurement to
 *         report; confidence floor for an un-observed surface).
 *
 * Reference: shared/probes/inventory.yml rank 9.0 (mitigation_layer L4,
 * freeRASP T6, MASTG-RESILIENCE-4).
 */
class FridaMemoryMapsProbe : Probe {
    override val id = "runtime.frida_memory_maps"
    override val rank = RANK
    override val inventoryRank = 9.0      // canonical from inventory.yml (cross-cutting #7)
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank 9.0,
         * but integer rank 9 is already taken by `ModelBrandManufacturerProbe`.
         * 83 is unused (sequence after rank 82 LocationMockRaspProbe).
         * Tracked in `audit/cross-cutting-followups-2026-05-19.md` #7.
         */
        const val RANK = 83

        /**
         * Library-name substrings (case-insensitive) that fire the
         * dispositive PATTERN_FRIDA_LIBRARY_PRESENT rule. Each token is a
         * documented Frida component:
         *
         *   • `frida-agent` — the canonical frida-server attach payload
         *     mapped into the target process during `frida-trace` /
         *     `frida-cli` injection.
         *   • `frida-gadget` / `libfrida-gadget` — the embeddable Frida
         *     runtime that an attacker links into a repackaged APK.
         *   • `gum` — the gum instrumentation core that all frida-* tools
         *     load (matches `libgum.so` / `libgum-android.so` regardless
         *     of how the agent was injected).
         *   • `linjector` — the dlopen-injector library used by some
         *     Frida deployment scripts (cross-process injection helper).
         */
        val FRIDA_LIBRARY_TOKENS: List<String> = listOf(
            "frida-agent",
            "frida-gadget",
            "libfrida-gadget",
            "gum",
            "linjector",
        )

        /**
         * Thread names spawned by the Frida gum runtime when a live
         * instrumentation context is attached. `gum-js-loop` is the
         * JavaScript event-loop thread; `gmain` is the GLib main loop;
         * `gdbus` is the D-Bus IPC thread used for control-channel
         * communication with the frida-server process.
         */
        val GUM_THREAD_NAMES: List<String> = listOf(
            "gum-js-loop",
            "gmain",
            "gdbus",
        )

        /**
         * TCP ports that fire the dispositive PATTERN_FRIDA_PORT_OPEN
         * rule. 27042 is the canonical frida-server listen port; 27043
         * is the secondary port used when the agent operates in
         * persistent-script mode.
         */
        val FRIDA_PORTS: Set<Int> = setOf(27042, 27043)

        const val PATTERN_FRIDA_LIBRARY_PRESENT = "frida_library_present"
        const val PATTERN_FRIDA_PORT_OPEN = "frida_port_open"
        const val PATTERN_GUM_THREAD_PRESENT = "gum_thread_present"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_FRIDA_LIBRARY_PRESENT = 1.0
        const val SCORE_FRIDA_PORT_OPEN = 1.0
        const val SCORE_GUM_THREAD_PRESENT = 0.7
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_OBSERVED = 0.85
        const val CONFIDENCE_NO_OBSERVATION = 0.50

        const val METHOD =
            "Scan /proc/self/maps for frida-agent / gum / frida-gadget / " +
                "linjector libraries, /proc/self/task/*/comm for gum thread " +
                "names, /proc/net/tcp for ports 27042/27043 (freeRASP T6 / " +
                "MASTG-RESILIENCE-4 declarative variant)"

        /**
         * Returns the subset of [observed] library names that match any
         * of [FRIDA_LIBRARY_TOKENS] as a case-insensitive substring.
         * Lifted as an internal helper for unit-test surface visibility.
         */
        internal fun matchingFridaLibs(observed: Set<String>): List<String> {
            if (observed.isEmpty()) return emptyList()
            return observed.filter { lib ->
                val lower = lib.lowercase()
                FRIDA_LIBRARY_TOKENS.any { token -> token in lower }
            }
        }

        /**
         * Returns the subset of [observed] thread names that match any
         * of [GUM_THREAD_NAMES] exactly (case-insensitive).
         */
        internal fun matchingGumThreads(observed: Set<String>): List<String> {
            if (observed.isEmpty()) return emptyList()
            val lowerNames = GUM_THREAD_NAMES.map { it.lowercase() }
            return observed.filter { name -> name.lowercase() in lowerNames }
        }

        /**
         * Returns the subset of [observed] ports that are in [FRIDA_PORTS].
         */
        internal fun matchingFridaPorts(observed: Set<Int>): Set<Int> =
            observed.intersect(FRIDA_PORTS)
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val libs: Set<String> = try {
                ctx.queryProcSelfMapsLibs()
            } catch (_: Throwable) {
                emptySet()
            }
            val threads: Set<String> = try {
                ctx.queryRuntimeThreadNames()
            } catch (_: Throwable) {
                emptySet()
            }
            val ports: Set<Int> = try {
                ctx.queryOpenTcpPorts()
            } catch (_: Throwable) {
                emptySet()
            }

            val matchedLibs = matchingFridaLibs(libs)
            val matchedThreads = matchingGumThreads(threads)
            val matchedPorts = matchingFridaPorts(ports)

            val (pattern, score) = when {
                matchedLibs.isNotEmpty() ->
                    PATTERN_FRIDA_LIBRARY_PRESENT to SCORE_FRIDA_LIBRARY_PRESENT
                matchedPorts.isNotEmpty() ->
                    PATTERN_FRIDA_PORT_OPEN to SCORE_FRIDA_PORT_OPEN
                matchedThreads.isNotEmpty() ->
                    PATTERN_GUM_THREAD_PRESENT to SCORE_GUM_THREAD_PRESENT
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val anyObservation = libs.isNotEmpty() || threads.isNotEmpty() || ports.isNotEmpty()
            val confidence = if (anyObservation) CONFIDENCE_OBSERVED else CONFIDENCE_NO_OBSERVATION

            val libsDisplay = if (matchedLibs.isEmpty()) "none" else matchedLibs.joinToString(",")
            val threadsDisplay = if (matchedThreads.isEmpty()) "none"
                else matchedThreads.joinToString(",")
            val portsDisplay = if (matchedPorts.isEmpty()) "none"
                else matchedPorts.sorted().joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "runtime.frida_memory_maps.libs",
                    value = libsDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.frida_memory_maps.threads",
                    value = threadsDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.frida_memory_maps.ports",
                    value = portsDisplay,
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.frida_memory_maps.pattern",
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
                "FridaMemoryMapsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

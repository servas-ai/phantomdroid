// inventory.yml rank 50, mitigation_layer L4, freeRASP T10
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #50 — runtime.services_processes.
 *
 * Scans `ActivityManager.getRunningServices(Int.MAX_VALUE)` service-name
 * strings + `ActivityManager.getRunningAppProcesses()` process-name strings
 * for substrings that identify hostile root/instrumentation/MITM tools
 * running on the device. Reranked low → medium per BEST-STACK Phase 3
 * (May 2026, freeRASP T10 automation-tool detection).
 *
 * **`ActivityManager` accessor gap.** `ProbeContext` exposes no
 * `queryActivityManager()` view. Two constructor-injected suppliers:
 * `runningServiceNamesSupplier` returns the service-name list;
 * `runningProcessNamesSupplier` returns the process-name list. Production
 * no-arg ctor returns null on both → confidence floors at 0.5 (degraded —
 * Android 8+ unprivileged callers see only their own services/processes
 * anyway, so the inability to observe is itself the expected production
 * baseline). Same gap-handling shape as rank 31/32/33/40/42/43/44/45.
 *
 * **NOTE: Distinct from rank-3 / rank-8 / rank-10 marker lists.** Those
 * scan different surfaces:
 *   - Rank 3 [SuDetectionProbe] scans filesystem artifact PATHS
 *     (`/data/adb/magisk/`, `/system/bin/su`)
 *   - Rank 8 [XposedLsposedProbe.HOOK_LIB_MARKERS] scans `/proc/self/maps`
 *     LIBRARY filenames (`libxposed_art`, `liblspd.so`)
 *   - Rank 10 [InstalledAppsProbe] scans `PackageManager` PACKAGE NAMES
 *     (`com.topjohnwu.magisk`, `re.frida.server`)
 *   - Rank 50 (this probe) scans `ActivityManager` running SERVICE/PROCESS
 *     NAMES (`frida-server` daemon binary name, `magiskd` running process)
 *
 * Lists intentionally diverge by surface; **DO NOT consolidate** —
 * different surfaces have different name conventions (daemon binary names
 * vs library filenames vs package IDs). Per the rank-28 anchor pattern.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Any service or process name contains a hostile-tool marker
 *         from [HOSTILE_RUNTIME_MARKERS] (frida-server, magiskd, lspd,
 *         xposed, etc.) — strong instrumentation/root signal
 *   0.95  Any process name contains a debug-server marker from
 *         [DEBUG_SERVER_MARKERS] (gdbserver, lldb-server) — debugger
 *         on a "production" device is anomalous
 *   0.95  Any process name contains a MITM-tool marker from
 *         [MITM_TOOL_MARKERS] (tcpdump, mitmproxy, charles, burp)
 *   0.50  Any process name contains an automation marker from
 *         [AUTOMATION_MARKERS] (uiautomator, autoinput) — capped because
 *         legitimate Android instrumentation test framework also uses
 *         these binaries
 *   0.00  Clean process list (or accessor returned empty / null — Android
 *         8+ visibility filter expected baseline)
 *
 * Confidence:
 *   0.95  At least one of (services, processes) returned a non-empty list
 *   0.50  Both returned empty / null (could be Android 8+ visibility
 *         filtering, OR could be a real clean device with no observable
 *         services to scan; cannot disambiguate without elevated perms)
 *
 * Reference: shared/probes/inventory.yml rank 50 (mitigation_layer L4,
 * freeRASP T10).
 */
class ServicesProcessesProbe(
    private val runningServiceNamesSupplier: () -> List<String>? = { null },
    private val runningProcessNamesSupplier: () -> List<String>? = { null },
) : Probe {
    override val id = "runtime.services_processes"
    override val rank = 50
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 400L

    companion object {
        /**
         * Hostile root/instrumentation tool markers (case-insensitive
         * substring match against service/process names). These are
         * **daemon binary names**, distinct from rank-3's filesystem
         * paths and rank-10's PackageManager IDs.
         *
         * Source: freeRASP T10 + community-known root-tool process names.
         * Lab approximation — real-device telemetry required to validate
         * (joins the rank 22 / 23 / 28 / 31 / 45 / cross-cutting
         * follow-up #5 lab-approximation family).
         */
        val HOSTILE_RUNTIME_MARKERS: List<String> = listOf(
            "frida-server",         // Frida instrumentation daemon
            "frida-gadget",         // Frida injection gadget
            "re.frida.server",      // Frida server alt name
            "magiskd",              // Magisk daemon
            "magisk-anykernel",
            "lspd",                 // LSPosed daemon
            "liblspd",
            "zygisk_lsposed",
            "xposedbridge",         // Classic Xposed
            "riru",                 // Riru daemon
            "kernelsu",             // KernelSU
        )

        /** Debugger server processes. Anomalous on a production device. */
        val DEBUG_SERVER_MARKERS: List<String> = listOf(
            "gdbserver",
            "lldb-server",
        )

        /** Network MITM / inspection tools running on the device. */
        val MITM_TOOL_MARKERS: List<String> = listOf(
            "tcpdump",
            "mitmproxy",
            "charles",              // Charles Proxy
            "burp",                 // Burp Suite agent
        )

        /**
         * Automation-tool markers (UI test frameworks, accessibility
         * automation). Scored 0.5 because legitimate Android instrumentation
         * test runs (e.g. CI Espresso/UIAutomator suites) produce these
         * processes — they're a lab/dev signal, not a production attack
         * tell.
         *
         * **Consumer guidance**: this 0.5 score should be combined with the
         * debug-state signal (`ro.build.type` from rank-28
         * [com.detectorlab.probes.buildprop.BoardHardwareProbe], or
         * `ApplicationInfo.FLAG_DEBUGGABLE` / `ro.debuggable` from rank-19
         * `EnvDeveloperOptionsProbe`) before high-confidence rejection.
         *   - On a `user` (production) build the 0.5 is high-signal — a
         *     UIAutomator process on a retail Android build is anomalous.
         *   - On `userdebug` / `eng` builds the same processes are routinely
         *     present from CI test runs and the score should be discounted
         *     further (e.g. multiplied by ~0.3 in the consumer-side
         *     aggregator) or suppressed entirely.
         *
         * Gating is intentionally consumer-side: doing it inside the probe
         * would require either a new ProbeContext surface or duplicating
         * property reads from rank-1/19/28, neither of which fits this
         * probe's single-purpose scope.
         */
        val AUTOMATION_MARKERS: List<String> = listOf(
            "uiautomator",
            "autoinput",
        )

        const val PATTERN_HOSTILE_RUNTIME = "hostile_runtime"
        const val PATTERN_DEBUG_SERVER = "debug_server"
        const val PATTERN_MITM_TOOL = "mitm_tool"
        const val PATTERN_AUTOMATION = "automation"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_HOSTILE_RUNTIME = 1.0
        const val SCORE_DEBUG_SERVER = 0.95
        const val SCORE_MITM_TOOL = 0.95
        const val SCORE_AUTOMATION = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read ActivityManager getRunningServices + getRunningAppProcesses; " +
                "scan service/process names for hostile root-tool/instrumentation/" +
                "MITM substrings; document Android 8+ visibility filter as " +
                "confidence-degradation."

        /**
         * Returns the subset of [names] that contain any substring from
         * [markers] (case-insensitive). Sorted + deduplicated for stable
         * evidence output. Empty list if no matches.
         */
        internal fun findMatches(names: List<String>, markers: List<String>): List<String> {
            if (names.isEmpty()) return emptyList()
            val hits = mutableSetOf<String>()
            for (name in names) {
                val lower = name.lowercase()
                for (marker in markers) {
                    if (marker in lower) {
                        hits.add(name)
                        break
                    }
                }
            }
            // ASCII lex sort, NOT case-insensitive. Matching is case-insensitive
            // (haystack lowercased above) but evidence output preserves original
            // case and sorts by ASCII (`"FRIDA-SERVER"` < `"Magiskd"` because
            // uppercase F < lowercase M). Test
            // `findMatches case-insensitive` asserts this exact ordering;
            // do NOT change to `sortedBy { it.lowercase() }` without updating
            // that test and any downstream consumer parsing the evidence row.
            return hits.toList().sorted()
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val services: List<String> = try {
                runningServiceNamesSupplier() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
            val processes: List<String> = try {
                runningProcessNamesSupplier() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            val combined = services + processes

            val hostileMatches = findMatches(combined, HOSTILE_RUNTIME_MARKERS)
            val debugMatches = findMatches(processes, DEBUG_SERVER_MARKERS)
            val mitmMatches = findMatches(processes, MITM_TOOL_MARKERS)
            val automationMatches = findMatches(combined, AUTOMATION_MARKERS)

            val (pattern, score) = when {
                hostileMatches.isNotEmpty() ->
                    PATTERN_HOSTILE_RUNTIME to SCORE_HOSTILE_RUNTIME
                debugMatches.isNotEmpty() ->
                    PATTERN_DEBUG_SERVER to SCORE_DEBUG_SERVER
                mitmMatches.isNotEmpty() ->
                    PATTERN_MITM_TOOL to SCORE_MITM_TOOL
                automationMatches.isNotEmpty() ->
                    PATTERN_AUTOMATION to SCORE_AUTOMATION
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence: did at least one accessor return a non-empty list?
            // Android 8+ filtering returns empty for unprivileged callers;
            // this is the expected production baseline, not a probe failure.
            val anyListReadable = services.isNotEmpty() || processes.isNotEmpty()
            val confidence = if (anyListReadable) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            // Android 8+ unprivileged callers get empty lists; this is
            // expected, not a failure. The flag distinguishes "we observed
            // the list and it was empty" (filtering) from "we observed
            // names and none were hostile" (clean).
            val processListFiltered = !anyListReadable

            fun displayMatches(matches: List<String>): String =
                if (matches.isEmpty()) "none" else matches.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "runtime.process_count",
                    value = processes.size.toString(),
                    expected = "N/A or >=10",
                ),
                Evidence(
                    key = "runtime.service_count",
                    value = services.size.toString(),
                    expected = "N/A or >=5",
                ),
                Evidence(
                    key = "runtime.hostile_processes",
                    value = displayMatches(hostileMatches),
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.debug_processes",
                    value = displayMatches(debugMatches),
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.mitm_processes",
                    value = displayMatches(mitmMatches),
                    expected = "none",
                ),
                Evidence(
                    key = "runtime.automation_processes",
                    value = displayMatches(automationMatches),
                    expected = "none on production",
                ),
                Evidence(
                    key = "runtime.process_list_filtered",
                    value = processListFiltered.toString(),
                    expected = "known on Android 8+ unprivileged",
                ),
                Evidence(
                    key = "runtime.pattern",
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
                "ServicesProcessesProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 60, mitigation_layer L4
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — integrity.app_signature (rank 60, mitigation_layer L4).
 *
 * Tamper / hooking detection via package-name evidence of installed tampering
 * frameworks. The probe is intentionally lightweight: it does not parse APK
 * signatures, does not enumerate certificates, and does not read DEX bytes.
 * Instead it scans the installed-packages list for known SpoofStack / Xposed
 * / LSPosed manager packages — the user-visible packages that ship the hook
 * runtimes. This is the cheapest possible MASTG MSTG-RESILIENCE-3 / Resilience-3
 * (tampering surface) signal we can emit without an Android dependency.
 *
 * The overlap with rank-8 `runtime.xposed_lsposed` (XposedLsposedProbe) is
 * intentional: rank-8 fuses three signal classes (filesystem artifacts +
 * package list + `/proc/self/maps` hook libraries) for a HIGH-confidence
 * verdict. THIS rank-60 probe is the cheap, MEDIUM-severity, MEDIUM-confidence,
 * application-layer companion that fires from a *single* PackageManager
 * accessor — useful when the runtime probe is timing-budgeted out or when an
 * app-layer probe must answer independently of filesystem state.
 *
 * Scoring (max-wins, mutually exclusive in practice):
 *   • 1.00  any SpoofStack-tell package present: `io.spoofstack.*`,
 *           `de.robv.android.xposed.*`, `org.lsposed.*`. The "tell" branch is
 *           the strongest signal because these prefixes are owned by the
 *           specific frameworks themselves — a package matching `io.spoofstack.*`
 *           is the runtime, not just a manager UI.
 *   • 0.85  Xposed family manager package present (regex match against
 *           `^de\.robv\.`, `^org\.lsposed\.`, `^io\.github\.lsposed\.`, or
 *           `^io\.va\.exposed`). Lower than 1.00 because the manager APK can
 *           be present without an active hook runtime (uninstalled module,
 *           wiped /data/adb/lspd, Magisk DenyList).
 *   • 0.70  RESERVED: "any system package recently modified" — file-timestamp
 *           heuristic that would require `PackageInfo.lastUpdateTime` access.
 *           `PackageManagerView` does not currently expose that field, so the
 *           branch is documented as future-work and is unreachable in the
 *           current scoring pipeline. See dead-code comment at the branch
 *           site for the API surface needed.
 *   • 0.00  clean — no SpoofStack/Xposed/LSPosed packages observed.
 *
 * Evidence keys:
 *   • `app_signature.spoofstack_tell_pkg`  — matching package (string) or "absent"
 *   • `app_signature.xposed_family`        — matching package (string) or "absent"
 *   • `app_signature.method`               — "package-name-scan-for-tamper-frameworks"
 *
 * Confidence:
 *   • 0.85 when `queryPackageManager().listInstalledPackages()` returned a
 *     non-null list (PackageManager was queryable). MEDIUM-cap because
 *     package-name scan alone is the weakest of the three tampering signal
 *     classes (rank-8 fuses three and earns 0.85 itself; this probe uses one
 *     class so the cap is the same).
 *   • 0.50 when the PackageManager accessor threw. Score collapses to 0 (no
 *     observation possible) but the probe does NOT fail — the conservative
 *     answer is "no signal" rather than "tampering detected".
 *
 * Reference: shared/probes/inventory.yml rank 60 (BEST-STACK Phase 3 rerank
 * trace → medium, May 2026 MASTG MSTG-RESILIENCE-3).
 */
class AppSignatureProbe : Probe {
    override val id = "integrity.app_signature"
    override val rank = RANK
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 200L

    companion object {
        const val RANK = 60

        /**
         * SpoofStack-tell package prefixes. A match here scores 1.00 because
         * these prefixes are owned by the framework runtime itself, not just
         * a manager UI APK. `io.spoofstack.*` is the SpoofStack umbrella.
         * `de.robv.android.xposed.*` covers the Rovo Xposed runtime; the
         * `*.installer` subpackage is the manager — the runtime hook library
         * normally co-exists in the same prefix tree so the prefix match is
         * a stronger tell than "installer-only present".
         * `org.lsposed.*` covers LSPosed core packages.
         *
         * Order is the search order; first match wins for evidence reporting.
         */
        val SPOOFSTACK_TELL_PREFIXES: List<String> = listOf(
            "io.spoofstack.",
            "de.robv.android.xposed.",
            "org.lsposed.",
        )

        /**
         * Xposed-family manager-package regexes (anchored). These are the
         * weaker "manager UI installed" signal — score 0.85. Patterns:
         *   • `^de\.robv\.`              — Rovo Xposed installer + tooling
         *   • `^org\.lsposed\.`          — LSPosed manager
         *   • `^io\.github\.lsposed\.`   — LSPosed GitHub-build variants
         *   • `^io\.va\.exposed`         — VirtualXposed (also referenced in
         *                                 rank-10 InstalledAppsProbe Group C;
         *                                 cross-rank tension noted but not
         *                                 fixed — separate signal classes,
         *                                 separate severities).
         */
        val XPOSED_FAMILY_REGEX: Regex = Regex(
            "^de\\.robv\\.|^org\\.lsposed\\.|^io\\.github\\.lsposed\\.|^io\\.va\\.exposed",
        )

        const val METHOD = "package-name-scan-for-tamper-frameworks"

        const val EV_SPOOFSTACK_TELL = "app_signature.spoofstack_tell_pkg"
        const val EV_XPOSED_FAMILY = "app_signature.xposed_family"
        const val EV_METHOD = "app_signature.method"

        const val SCORE_SPOOFSTACK_TELL = 1.00
        const val SCORE_XPOSED_FAMILY = 0.85

        // SCORE_RECENT_SYSTEM_MODIFIED is reserved for the future-work branch
        // documented in the probe KDoc; the heuristic requires PackageInfo
        // lastUpdateTime which PackageManagerView does not expose.
        const val SCORE_RECENT_SYSTEM_MODIFIED = 0.70
        const val SCORE_CLEAN = 0.00

        const val CONFIDENCE_PM_QUERYABLE = 0.85
        const val CONFIDENCE_PM_THREW = 0.50
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Single accessor surface — the whole probe is one PackageManager
            // call. Wrapping in a try here keeps the failed-vs-clean
            // distinction inline with the rest of the inventory.
            val pm: PackageManagerView? = try {
                ctx.queryPackageManager()
            } catch (_: Throwable) {
                null
            }

            val packages: List<String>? = try {
                pm?.listInstalledPackages()
            } catch (_: Throwable) {
                null
            }

            // PackageManager unavailable → conservative "no signal" verdict.
            // Score 0.0, confidence degraded, failed=false (we did not error
            // out; we just have no observation).
            if (packages == null) {
                return ProbeResult(
                    score = SCORE_CLEAN,
                    confidence = CONFIDENCE_PM_THREW,
                    evidence = listOf(
                        Evidence(EV_SPOOFSTACK_TELL, "absent", expected = "absent"),
                        Evidence(EV_XPOSED_FAMILY, "absent", expected = "absent"),
                        Evidence(EV_METHOD, METHOD),
                    ),
                    method = METHOD,
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            // ── Signal 1: SpoofStack-tell prefix scan (1.00) ─────────────────
            val spoofstackHit: String? = packages.firstOrNull { pkg ->
                SPOOFSTACK_TELL_PREFIXES.any { prefix -> pkg.startsWith(prefix) }
            }

            // ── Signal 2: Xposed-family regex scan (0.85) ───────────────────
            // Only matters if Signal 1 did not fire — but we always compute
            // and emit the evidence for forensic traceability.
            val xposedFamilyHit: String? = packages.firstOrNull { pkg ->
                XPOSED_FAMILY_REGEX.containsMatchIn(pkg)
            }

            // ── Signal 3 (RESERVED): system-package recent modification ─────
            // Future work: when `PackageManagerView` grows a
            // `lastUpdateTime(pkg: String): Long?` accessor we can compare
            // every system package's lastUpdateTime against the device's
            // build timestamp and score 0.70 on any system package modified
            // after build (re-flash / Magisk system overlay / module that
            // patches a system APK in place). Until that accessor exists,
            // the branch is unreachable. Keeping the SCORE_RECENT_SYSTEM_MODIFIED
            // constant pinned at 0.70 so the future patch is a one-line edit.
            // val recentSystemModified = false  // dead-code; see KDoc.

            // ── Scoring (max-wins) ───────────────────────────────────────────
            val score = when {
                spoofstackHit != null -> SCORE_SPOOFSTACK_TELL
                xposedFamilyHit != null -> SCORE_XPOSED_FAMILY
                else -> SCORE_CLEAN
            }

            val evidence = listOf(
                Evidence(
                    key = EV_SPOOFSTACK_TELL,
                    value = spoofstackHit ?: "absent",
                    expected = "absent",
                ),
                Evidence(
                    key = EV_XPOSED_FAMILY,
                    value = xposedFamilyHit ?: "absent",
                    expected = "absent",
                ),
                Evidence(EV_METHOD, METHOD),
            )

            ProbeResult(
                score = score,
                confidence = CONFIDENCE_PM_QUERYABLE,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "AppSignatureProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

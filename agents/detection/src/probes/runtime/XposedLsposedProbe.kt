// inventory.yml rank 8, mitigation_layer L4
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #8 — runtime.xposed_lsposed.
 *
 * Detects Xposed framework, EdXposed, and LSPosed by combining three independent
 * signal classes (the fourth — classloader introspection — is dropped because
 * `ProbeContext` does not currently expose a `tryClassForName` accessor; see
 * "Confidence cap" below):
 *
 *   1. **Installation artifacts on the filesystem** — `XposedBridge.jar`,
 *      `app_process32/64_xposed`, libxposed_art, `/data/adb/lspd/`,
 *      `/data/adb/modules/zygisk_lsposed/`, `/data/adb/modules/riru_edxposed/`.
 *      Runtime-active dirs only; package-data leftovers like
 *      `/data/data/de.robv.android.xposed.installer/` are intentionally excluded
 *      because they double-count against the manager-package signal and can
 *      persist after manager uninstall.
 *   2. **Manager packages installed via PackageManager** — `de.robv.android.xposed.installer`,
 *      `org.lsposed.manager`, `org.meowcat.edxposed.manager`, etc. Weaker than
 *      artifacts because the manager APK can be present without an active
 *      runtime (e.g. uninstalled magisk module).
 *   3. **`/proc/self/maps` Xposed-family hook libraries** — `libxposed_art`,
 *      `libxposed_dalvik`, `liblspd`, `libriru_lsposed`, `libriru_edxposed`
 *      mapped into the current process. Markers are deliberately narrow:
 *      generic `libriru`, `libzygisk`, and `libsandhook` are NOT used because
 *      they fire on benign Riru modules, every Magisk-zygisk install, and
 *      unrelated RASP/anti-cheat frameworks. Riru modules follow the
 *      `libriru_<modulename>.so` convention so the `libriru_{lsposed,edxposed}`
 *      substrings disambiguate Xposed-family modules from benign ones.
 *      LSPosed-via-zygisk surfaces through `liblspd`, so no separate zygisk
 *      marker is needed.
 *
 * Scoring:
 *   - 1.00  any installation artifact present
 *   - 0.85  only manager package installed (UI without active runtime)
 *   - 0.70  only `/proc/self/maps` hook library detected (ambiguous w/ other hook frameworks)
 *   - 0.00  otherwise
 *
 * Confidence cap: 0.85 normal (instead of the 0.95 used by file+package-only
 * probes like rank 3) — we lack the classloader-introspection signal class and
 * therefore cannot be sure an in-process Xposed bridge isn't running on a
 * device where the filesystem and package signals were both wiped (Magisk
 * DenyList + manager-uninstall). 0.5 when every filesystem accessor throws.
 *
 * Reference: shared/probes/inventory.yml rank 8 (mitigation_layer L4).
 */
class XposedLsposedProbe : Probe {
    override val id = "runtime.xposed_lsposed"
    override val rank = 8
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 400L

    companion object {
        val INSTALLATION_ARTIFACT_PATHS: List<String> = listOf(
            "/system/framework/XposedBridge.jar",
            "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
            "/system/bin/app_process32_xposed",
            "/system/bin/app_process64_xposed",
            "/data/adb/lspd/",
            "/data/adb/modules/zygisk_lsposed/",
            "/data/adb/modules/riru_edxposed/",
        )

        val MANAGER_PACKAGES: List<String> = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "io.va.exposed",
            "com.solohsu.android.edxp.manager",
        )

        /**
         * Substrings to look for inside `/proc/self/maps`. Deliberately narrow
         * to Xposed-family-specific names: generic `libriru`, `libzygisk`, and
         * `libsandhook` are excluded because they fire on benign Riru modules,
         * every Magisk-zygisk install, and unrelated hook frameworks.
         */
        val HOOK_LIB_MARKERS: List<String> = listOf(
            "libxposed_art",
            "libxposed_dalvik",
            "liblspd",
            "libriru_lsposed",
            "libriru_edxposed",
        )

        const val PROC_SELF_MAPS = "/proc/self/maps"

        /**
         * Empirically /proc/self/maps on modern Android apps is 80-250 KiB.
         * 512 KiB covers the 99th percentile and avoids silent truncation of
         * late-mapped hook libraries (modules load late in the zygote-fork
         * lifecycle).
         */
        const val PROC_SELF_MAPS_MAX_BYTES = 524_288

        const val METHOD =
            "Filesystem path + package-list scan + /proc/self/maps " +
                "hook-library detection (classloader signal class deferred — see KDoc)"

        /**
         * Confidence cap when the classloader signal class is unavailable
         * (ProbeContext does not expose `tryClassForName`). Drops below the
         * 0.95 used by probes that hit every signal class to acknowledge the
         * gap. Bump back to 0.95 once a classloader accessor lands.
         */
        const val CONFIDENCE_CAP_NO_CLASSLOADER = 0.85
        const val CONFIDENCE_DEGRADED = 0.5
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val evidence = mutableListOf<Evidence>()

            // ── Signal 1: installation artifacts ──────────────────────────────
            var fileChecksAttempted = 0
            var fileChecksThrew = 0
            var anyArtifactPresent = false

            for (path in INSTALLATION_ARTIFACT_PATHS) {
                fileChecksAttempted++
                val present = try {
                    ctx.fileExists(path)
                } catch (_: Throwable) {
                    fileChecksThrew++
                    false
                }
                if (present) anyArtifactPresent = true
                evidence.add(
                    Evidence(
                        key = path,
                        value = if (present) "present" else "absent",
                        expected = "absent",
                    )
                )
            }

            // ── Signal 2: manager packages ────────────────────────────────────
            val pm = try {
                ctx.queryPackageManager()
            } catch (_: Throwable) {
                null
            }

            var anyManagerInstalled = false
            for (pkg in MANAGER_PACKAGES) {
                val installed = try {
                    pm?.isPackageInstalled(pkg) == true
                } catch (_: Throwable) {
                    false
                }
                if (installed) anyManagerInstalled = true
                evidence.add(
                    Evidence(
                        key = "xposed.pkg.$pkg",
                        value = if (installed) "installed" else "absent",
                        expected = "absent",
                    )
                )
            }

            // ── Signal 3: /proc/self/maps hook libraries ──────────────────────
            val mapsContent = try {
                ctx.readFile(PROC_SELF_MAPS, maxBytes = PROC_SELF_MAPS_MAX_BYTES)
            } catch (_: Throwable) {
                null
            }

            val matchedHookLibs = if (mapsContent != null) {
                HOOK_LIB_MARKERS.filter { marker -> marker in mapsContent }
            } else {
                emptyList()
            }
            val anyHookLibLoaded = matchedHookLibs.isNotEmpty()

            evidence.add(
                Evidence(
                    key = "proc.self.maps.readable",
                    value = mapsContent != null,
                    expected = true,
                )
            )
            evidence.add(
                Evidence(
                    key = "proc.self.maps.hook_lib_matches",
                    value = matchedHookLibs.joinToString(",").ifEmpty { "none" },
                    expected = "none",
                )
            )

            // ── Scoring ──────────────────────────────────────────────────────
            val score = when {
                anyArtifactPresent -> 1.0
                anyManagerInstalled -> 0.85
                anyHookLibLoaded -> 0.70
                else -> 0.0
            }

            // ── Confidence ───────────────────────────────────────────────────
            val allFileChecksFailed =
                fileChecksAttempted > 0 && fileChecksThrew == fileChecksAttempted
            val confidence = if (allFileChecksFailed) {
                CONFIDENCE_DEGRADED
            } else {
                CONFIDENCE_CAP_NO_CLASSLOADER
            }

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "XposedLsposedProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

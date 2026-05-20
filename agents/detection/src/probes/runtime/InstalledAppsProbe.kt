// inventory.yml rank 10, mitigation_layer L4
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #10 — runtime.installed_apps.
 *
 * Detects **hide-manager mitigation failures**: which root/hook manager apps,
 * dynamic-analysis tools, clone/virtualization frameworks, and automation
 * runtimes are visible to PackageManager from inside the calling app.
 *
 * Scope distinction from related probes:
 *   • Rank 3 (root.su_detection) scans the filesystem for `su` binaries.
 *   • Rank 8 (runtime.xposed_lsposed) scans for Xposed runtime/artifact state.
 *   • Rank 10 (this probe) measures **leakage**: a correctly-hidden Magisk
 *     would NOT show up here even if rank 3 found `/system/bin/su`. The score
 *     quantifies how badly the device's hide-manager mitigations have failed,
 *     not the underlying root state.
 *
 * Scoring (max wins across groups):
 *   Group A — Root / hook managers          → 1.00
 *     (Magisk family, KernelSU, APatch, Xposed/LSPosed managers,
 *      SuperSU, KingoRoot, FlashFire)
 *   Group B — Dynamic-analysis / debug      → 0.85
 *     (frida-server, frida client, VNC/RDP servers, SpyNote, adb-kit,
 *      proxy / NSPlist analyzers)
 *   Group C — Clone / virtualization        → 0.70
 *     (Parallel Space, DualAid, MultiAccount, VirtualXposed-derived)
 *   Group D — Automation frameworks         → 0.70
 *     (UIAutomator, AutoJS, AutoXJS, TouchTask, AutoUI mods)
 *   None visible                            → 0.00
 *
 * Confidence: 0.95 normal; 0.5 when `listInstalledPackages()` returns an empty
 * collection (Android 11+ package-visibility filter likely in effect — the
 * calling app would not see these packages even if installed unless declared
 * via `<queries>` in AndroidManifest.xml).
 *
 * Reference: shared/probes/inventory.yml rank 10 (mitigation_layer L4).
 */
class InstalledAppsProbe : Probe {
    override val id = "runtime.installed_apps"
    override val rank = 10
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 500L

    companion object {
        // Group A — root / hook managers (1.0 if any visible)
        val GROUP_A_ROOT_HOOK_MANAGERS: List<String> = listOf(
            "com.topjohnwu.magisk",
            "io.github.huskydg.magisk",
            "com.topjohnwu.magisk.alpha",
            "me.weishu.kernelsu",
            "me.weishu.kernelsu_anykernel",
            "me.bmax.apatch",
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.kingouser.com",
            "eu.chainfire.flashfire",
        )

        // Group B — dynamic-analysis / debugging tools (0.85)
        //
        // Ground-truth pass (2026-05-20, cross-cutting #2 closure):
        //   - `re.frida.server` retained: Frida's official reverse-domain
        //     namespace (`frida.re`). No canonical Play APK (Frida ships
        //     as a native daemon binary), but kept as a defensive
        //     sentinel for sideloaded reproductions.
        //   - `com.iiordanov.bVNC` / `com.iiordanov.aRDP` verified on
        //     Google Play (developer: Iordan Iordanov / Undatech).
        //   - `com.cy8018.spynote` retained: SpyNote RAT is repackaged
        //     per-sample so no single canonical ID exists; this matches
        //     one documented sample namespace.
        //   - Removed `com.frida.frida` / `com.adb.kit` /
        //     `org.proxyman.NSPlist` — fabricated IDs that returned 404
        //     on Play Store and have no published F-Droid / GitHub
        //     equivalent. Proxyman has no Android app (only macOS host;
        //     Atlantis library runs in-app under arbitrary parent IDs).
        val GROUP_B_DYNAMIC_ANALYSIS: List<String> = listOf(
            "re.frida.server",
            "com.iiordanov.bVNC",
            "com.iiordanov.aRDP",
            "com.cy8018.spynote",
        )

        // Group C — clone / dual-instance / virtualization (0.70)
        //
        // Ground-truth pass (2026-05-20, cross-cutting #2 closure):
        //   - Removed `com.android.virtualspace` — AOSP-reserved
        //     `com.android.*` namespace, not a published clone app.
        //   - Added Play Store-verified clone apps:
        //       `com.clone.android.dual.space` (Virtual Master),
        //       `com.pengyou.cloneapp`         (Clone App – Dual App),
        //       `com.waxmoon.ma.gp`            (Multi App: Dual Space).
        val GROUP_C_CLONE_VIRTUALIZATION: List<String> = listOf(
            "com.lbe.parallel",
            "com.lbe.parallel.intl",
            "com.parallel.space.lite",
            "com.parallel.intl",
            "com.excelliance.dualaid",
            "com.excelliance.multiaccount",
            "com.bishrn.android.parallel",
            "io.va.exposed",
            "com.clone.android.dual.space",
            "com.pengyou.cloneapp",
            "com.waxmoon.ma.gp",
        )

        // Group D — automation frameworks (0.70)
        //
        // Ground-truth pass (2026-05-20, cross-cutting #2 closure):
        //   - Removed `mods.autoui` — fabricated ID (Play 404, no
        //     F-Droid / canonical-GitHub equivalent).
        //   - Corrected `com.touchtask` → `com.balda.touchtask`
        //     (verified canonical TouchTask developer namespace, per
        //     AppBrain; ~250 k installs).
        val GROUP_D_AUTOMATION: List<String> = listOf(
            "com.android.uiautomator",
            "org.autojs.autojs",
            "org.autojs.autoxjs.v6",
            "com.balda.touchtask",
        )

        const val SCORE_GROUP_A = 1.0
        const val SCORE_GROUP_B = 0.85
        const val SCORE_GROUP_C = 0.70
        const val SCORE_GROUP_D = 0.70

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_VISIBILITY_FILTERED = 0.5

        const val METHOD =
            "PackageManager-visible scan for known root managers, dynamic-analysis tools, " +
                "clone apps, and automation frameworks (hide-manager mitigation failure signal)"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val pm = try {
                ctx.queryPackageManager()
            } catch (_: Throwable) {
                null
            }

            // Visibility-filter heuristic: if listInstalledPackages() returns empty,
            // the caller is almost certainly subject to Android 11+ package
            // visibility filtering (which silently strips every package the
            // app has not declared in its <queries> manifest). isPackageInstalled
            // queries against undeclared packages all return false in that mode,
            // so the probe's signal is structurally blind.
            val installedListEmpty = try {
                pm?.listInstalledPackages().isNullOrEmpty()
            } catch (_: Throwable) {
                true
            }

            fun detectGroup(group: List<String>): List<String> = group.filter { pkg ->
                try {
                    pm?.isPackageInstalled(pkg) == true
                } catch (_: Throwable) {
                    false
                }
            }

            val hitsA = detectGroup(GROUP_A_ROOT_HOOK_MANAGERS)
            val hitsB = detectGroup(GROUP_B_DYNAMIC_ANALYSIS)
            val hitsC = detectGroup(GROUP_C_CLONE_VIRTUALIZATION)
            val hitsD = detectGroup(GROUP_D_AUTOMATION)

            val (score, leakGroup) = when {
                hitsA.isNotEmpty() -> SCORE_GROUP_A to "A"
                hitsB.isNotEmpty() -> SCORE_GROUP_B to "B"
                hitsC.isNotEmpty() -> SCORE_GROUP_C to "C"
                hitsD.isNotEmpty() -> SCORE_GROUP_D to "D"
                else -> 0.0 to "none"
            }

            val anyHit = score > 0.0
            val confidence = if (installedListEmpty && !anyHit) {
                CONFIDENCE_VISIBILITY_FILTERED
            } else {
                CONFIDENCE_NORMAL
            }

            val allHits = hitsA + hitsB + hitsC + hitsD
            val evidence = buildList {
                add(Evidence(key = "leak_group", value = leakGroup, expected = "none"))
                add(
                    Evidence(
                        key = "installed_list_empty",
                        value = installedListEmpty,
                        expected = false,
                    )
                )
                for (pkg in allHits) {
                    add(Evidence(key = "installed_apps.pkg.$pkg", value = "installed", expected = "absent"))
                }
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
                "InstalledAppsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}


// inventory.yml rank 10.5 (code uses rank 81 due to Int interface; tracked
// in audit/cross-cutting-followups-2026-05-19.md #7), freeRASP T5
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #10.5 — integrity.install_source.
 *
 * freeRASP T5 — Detecting Unofficial Installation. Reads
 * `PackageManager.getInstallSourceInfo()` (Android 11+) /
 * `getInstallerPackageName()` (pre-Android-11) via the
 * `queryInstallSourcePackage()` accessor on [ProbeContext] and compares
 * the installer-package string against an allowlist of legitimate stores
 * (Play Store + 6 major OEM stores).
 *
 * Surface rationale (per Power-16 B2 source-diff §B T5):
 *   - Play Store retail installs always set `installingPackageName` to
 *     `com.android.vending`. Legacy Play feedback codepath sets it to
 *     `com.google.android.feedback`. Both are in the allowlist.
 *   - OEM-store retail installs set `installingPackageName` to the
 *     respective OEM store package. We allowlist the 5 biggest
 *     non-Google Android stores (Samsung Galaxy Store, Huawei AppGallery,
 *     Xiaomi GetApps, Oppo Market, Vivo App Store).
 *   - F-Droid, Aptoide, ApkMirror, Amazon, sideload-from-browser, etc.
 *     are NOT in the allowlist and score dispositively (0.95). This is
 *     the canonical "user fetched the APK from a non-store source" branch.
 *   - `null` installer means either adb-install / pm-install (sideload)
 *     or a system pre-install where the OEM didn't record an installer.
 *     This is the "suspicious but ambiguous" branch (0.85) — strong
 *     enough to flag, weak enough to permit OEM-pre-install whitelisting
 *     by downstream consumers.
 *
 * **Note on the Int-vs-Fractional rank divergence**: inventory.yml lists
 * this probe at fractional rank 10.5 (its "natural" priority slot
 * between rank 10 `runtime.installed_apps` and rank 11
 * `identity.android_id`). The [Probe.rank] interface uses `Int`, which
 * can't represent 10.5, so this code uses rank 81 (unused integer outside
 * the META-22 A17 reserved range 61-71 and adjacent to
 * `DebuggerTracerPidProbe`'s rank 80). Same Int-vs-fractional handling
 * as [DebuggerTracerPidProbe] (inventory 8.5 → code 80),
 * [com.detectorlab.probes.env.ScreenLockProbe] (inventory 40.5 → code
 * 61), and [com.detectorlab.probes.env.LocationMockRaspProbe]
 * (inventory 39.5 → code 62). Tracked in
 * `audit/cross-cutting-followups-2026-05-19.md` #7.
 *
 * Scoring (max-wins, mutually exclusive):
 *   - 0.95  PATTERN_UNOFFICIAL_INSTALLER — `installingPackageName`
 *           present BUT outside the legitimate-store allowlist
 *           (F-Droid / ApkMirror / Aptoide / arbitrary). Dispositive
 *           because retail installs from a legitimate store ALWAYS
 *           populate this field with an allowlist entry.
 *   - 0.85  PATTERN_UNKNOWN_INSTALLER — `installingPackageName` is
 *           `null`. Either sideload (`adb install`) or system
 *           pre-install with no installer recorded. Strong suspicion;
 *           not dispositive because OEM pre-installs share this branch.
 *   - 0.05  PATTERN_CLEAN — `installingPackageName` in
 *           [LEGITIMATE_INSTALLERS]. The 0.05 floor (not 0.00) reflects
 *           that a sophisticated spoof CAN forge the install-source
 *           field; the surface is not strictly tamper-proof. The
 *           non-zero floor is consistent with rank-2 PlayIntegrityProbe
 *           and rank-3 SuDetectionProbe clean-baseline floors.
 *
 * Cross-cutting #1 evidence-namespace: probe-id is `integrity.install_source`
 * → evidence keys are prefixed `install_source.*` (never bare-keyed).
 *
 * Cross-cutting #7 fractional rank: `inventoryRank = 10.5` is between
 * rank-10 `runtime.installed_apps` and rank-11 `identity.android_id`.
 *
 * Evidence keys:
 *   - `install_source.installer`         — installer package string or `"null"`
 *   - `install_source.allowlist_match`   — `"true"` / `"false"` / `"unknown"`
 *   - `install_source.pattern`           — one of PATTERN_* literals
 *
 * Confidence:
 *   - 0.90 when `queryInstallSourcePackage()` returned a value or
 *     deliberate `null`. The accessor is single-shot and can't partially
 *     observe the installer-package surface, so a returned null is a
 *     genuine "no installer recorded" reading (not a degraded one).
 *   - 0.50 when the accessor threw. Score collapses to PATTERN_UNKNOWN
 *     (0.85) but confidence drops to reflect that we couldn't observe
 *     the surface at all.
 *
 * Reference:
 *   - shared/probes/inventory.yml rank 10.5 (mitigation_layer L2)
 *   - audit/spoof-stack/power-16-freerasp-source-diff.md §B T5
 *   - https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/detecting-unofficial-installation.md
 */
class IntegrityInstallSourceProbe : Probe {
    override val id = "integrity.install_source"
    override val rank = RANK
    override val inventoryRank = 10.5     // canonical from inventory.yml (cross-cutting #7)
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 100L

    companion object {
        /**
         * Code rank. Inventory lists this probe at fractional rank 10.5,
         * but `Probe.rank: Int` can't represent 10.5. Picked 81 (unused
         * integer outside the META-22 A17 reserved range 61-71 and
         * adjacent to `DebuggerTracerPidProbe`'s rank 80 for natural
         * inventoryRank-ordered grouping). Tracked in
         * `audit/cross-cutting-followups-2026-05-19.md` #7.
         */
        const val RANK = 81

        /**
         * Allowlist of installer-package strings that indicate a
         * legitimate-store install. Per Power-16 B2 §B T5 + Power-16 B3
         * brief — covers Play Store + 5 major non-Google Android stores
         * + the legacy Play feedback codepath.
         *
         * Order is the source order; lookup is by [Set.contains] so
         * order does not affect verdict.
         */
        val LEGITIMATE_INSTALLERS: Set<String> = setOf(
            "com.android.vending",              // Google Play Store
            "com.google.android.feedback",      // Google Play Store (legacy)
            "com.huawei.appmarket",             // Huawei AppGallery
            "com.sec.android.app.samsungapps",  // Samsung Galaxy Store
            "com.xiaomi.mipicks",               // Xiaomi GetApps
            "com.oppo.market",                  // Oppo App Market
            "com.vivo.appstore",                // Vivo App Store
        )

        const val PATTERN_CLEAN = "clean"
        const val PATTERN_UNKNOWN_INSTALLER = "unknown_installer"
        const val PATTERN_UNOFFICIAL_INSTALLER = "unofficial_installer"

        const val SCORE_CLEAN = 0.05
        const val SCORE_UNKNOWN_INSTALLER = 0.85
        const val SCORE_UNOFFICIAL_INSTALLER = 0.95

        const val CONFIDENCE_FULL = 0.90
        const val CONFIDENCE_DEGRADED = 0.50

        const val EV_INSTALLER = "install_source.installer"
        const val EV_ALLOWLIST_MATCH = "install_source.allowlist_match"
        const val EV_PATTERN = "install_source.pattern"

        const val METHOD =
            "Read PackageManager.getInstallSourceInfo() (Android 11+) / " +
                "getInstallerPackageName() (pre-A11) and compare against " +
                "Play+OEM store allowlist (freeRASP T5)"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Single accessor surface. Catch Throwable to keep the
            // "no observation" branch distinct from the "null installer"
            // branch: a thrown exception means the accessor itself
            // failed (production wrapper crashed, fake threw, etc.); a
            // returned null means the platform/wrapper successfully
            // observed that there IS no installer.
            var accessorThrew = false
            val installer: String? = try {
                ctx.queryInstallSourcePackage()
            } catch (_: Throwable) {
                accessorThrew = true
                null
            }

            val allowlisted = installer != null && installer in LEGITIMATE_INSTALLERS

            val (pattern, score) = when {
                accessorThrew -> PATTERN_UNKNOWN_INSTALLER to SCORE_UNKNOWN_INSTALLER
                installer == null -> PATTERN_UNKNOWN_INSTALLER to SCORE_UNKNOWN_INSTALLER
                allowlisted -> PATTERN_CLEAN to SCORE_CLEAN
                else -> PATTERN_UNOFFICIAL_INSTALLER to SCORE_UNOFFICIAL_INSTALLER
            }

            val confidence = if (accessorThrew) CONFIDENCE_DEGRADED else CONFIDENCE_FULL

            val allowlistMatchDisplay = when {
                accessorThrew -> "unknown"
                installer == null -> "unknown"
                else -> allowlisted.toString()
            }

            val evidence = listOf(
                Evidence(
                    key = EV_INSTALLER,
                    value = installer ?: "null",
                    expected = "com.android.vending",
                ),
                Evidence(
                    key = EV_ALLOWLIST_MATCH,
                    value = allowlistMatchDisplay,
                    expected = "true",
                ),
                Evidence(
                    key = EV_PATTERN,
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
                "IntegrityInstallSourceProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

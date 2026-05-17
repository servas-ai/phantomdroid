package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — runtime.multi_instance (A17 N8, freeRASP T13)
 *
 * Detects whether the app is running inside a multi-instance / clone-app
 * container. Three independent signals are combined:
 *
 *   1. **Secondary-user check** (`UserHandle.myUserId() != 0`)
 *      Any non-zero user ID indicates the process is running as a managed
 *      profile, work profile, guest user, or OEM clone space. This is the
 *      single most reliable signal.
 *
 *   2. **Clone-app package suffix scan** (freeRASP T13 token list)
 *      Installed packages whose names end with `.parallel`, `.dual`, `.clone`,
 *      or `.alpha` strongly indicate an OEM dual-app / clone-app framework.
 *      The suffix matcher MUST NOT match `:remote` process-name decorators — a
 *      package name containing `:` is a process descriptor, not a package, and
 *      must be silently dropped before suffix comparison.
 *
 *   3. **OEM multi-instance framework presence**
 *      Known MIUI (Xiaomi/Redmi) and Samsung Secure Folder system packages
 *      signal that the OEM multi-instance layer is installed, even if no
 *      clone suffix is detected.
 *
 * Confidence is fixed at MEDIUM (0.60) for all results per the Talsec
 * disclosure: "more detection techniques coming" — this acknowledges that the
 * current signal set is incomplete and evasion is possible.
 *
 * Score table (clamped to [0.0, 1.0]):
 *   Secondary-user (`userId != 0`)          → 0.70
 *   Clone-suffix package found              → 0.60
 *   OEM multi-instance framework present    → 0.80
 *   Final score = max of all contributing signals.
 *
 * Severity: MEDIUM — running in a clone space is a strong but not conclusive
 * indicator; some users legitimately use work profiles.
 */
class MultiInstanceProbe : Probe {
    override val id = "runtime.multi_instance"
    override val rank = RANK
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 1500L

    companion object {
        /** A17 N8. META-22 reserves 61..71 for A17 N1..N11; N8 maps to 68. */
        const val RANK = 68

        /** freeRASP T13 clone-app package suffixes. */
        val CLONE_SUFFIXES: List<String> = listOf(".parallel", ".dual", ".clone", ".alpha")

        /** Known MIUI Dual-Apps / clone-space system packages. */
        val MIUI_CLONE_PACKAGES: List<String> = listOf(
            "com.miui.appclone",
            "com.miui.dualapps",
            "com.lbe.parallel.intl",
        )

        /** Known Samsung Secure Folder / Knox container system packages. */
        val SAMSUNG_SECURE_FOLDER_PACKAGES: List<String> = listOf(
            "com.samsung.android.knox.containeragent",
            "com.samsung.knox.securefolder",
        )

        const val SCORE_SECONDARY_USER = 0.70
        const val SCORE_CLONE_SUFFIX = 0.60
        const val SCORE_OEM_FRAMEWORK = 0.80

        /** Fixed medium confidence per Talsec disclosure. */
        const val CONFIDENCE_MEDIUM = 0.60

        /** Talsec disclosure embedded in every result's method string. */
        const val TALSEC_NOTE = "talsec_disclosure:more_techniques_coming"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val userId = ctx.queryUserHandle().myUserId()
            val pm = ctx.queryPackageManager()

            val secondaryUser = userId != null && userId != 0
            val clonePackages = findCloneSuffixPackages(pm)
            val oemFrameworkPackages = findOemFrameworkPackages(pm)

            val cloneFound = clonePackages.isNotEmpty()
            val oemFound = oemFrameworkPackages.isNotEmpty()

            val score = maxOf(
                if (secondaryUser) SCORE_SECONDARY_USER else 0.0,
                if (cloneFound) SCORE_CLONE_SUFFIX else 0.0,
                if (oemFound) SCORE_OEM_FRAMEWORK else 0.0,
            ).coerceIn(0.0, 1.0)

            val anyDetected = secondaryUser || cloneFound || oemFound

            val evidence = buildList {
                add(Evidence("user_handle.my_user_id", userId ?: "unknown", expected = 0))
                add(Evidence("user_handle.is_secondary_user", secondaryUser, expected = false))
                add(
                    Evidence(
                        key = "packages.clone_suffix_found",
                        value = clonePackages.firstOrNull() ?: "none",
                        expected = "none",
                    )
                )
                add(Evidence("packages.clone_suffix_count", clonePackages.size, expected = 0))
                add(
                    Evidence(
                        key = "packages.oem_framework_found",
                        value = oemFrameworkPackages.firstOrNull() ?: "none",
                        expected = "none",
                    )
                )
            }

            ProbeResult(
                score = score,
                confidence = CONFIDENCE_MEDIUM,
                evidence = evidence,
                method = buildString {
                    append("user_handle+clone_suffix_scan+oem_framework_check")
                    append("|$TALSEC_NOTE")
                    if (anyDetected) append(" [DETECTED]")
                },
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "MultiInstanceProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

/**
 * Returns installed packages whose names end with a freeRASP T13 clone suffix.
 *
 * REGRESSION GUARD: Any entry containing `:` is a process-name descriptor
 * (e.g., `com.google.android.gms:remote`), not a package name, and is silently
 * dropped before suffix comparison. This prevents the suffix matcher from
 * falsely matching legitimate `:remote` process names.
 */
internal fun findCloneSuffixPackages(pm: PackageManagerView): List<String> =
    pm.listInstalledPackages()
        .filter { pkg -> ':' !in pkg }   // regression guard: drop process-name entries
        .filter { pkg ->
            MultiInstanceProbe.CLONE_SUFFIXES.any { suffix -> pkg.endsWith(suffix) }
        }

/**
 * Returns installed system packages that indicate an OEM multi-instance
 * framework (MIUI Dual Apps or Samsung Secure Folder / Knox).
 */
internal fun findOemFrameworkPackages(pm: PackageManagerView): List<String> {
    val knownSet = (MultiInstanceProbe.MIUI_CLONE_PACKAGES +
        MultiInstanceProbe.SAMSUNG_SECURE_FOLDER_PACKAGES).toSet()
    return pm.listInstalledPackages().filter { it in knownSet }
}

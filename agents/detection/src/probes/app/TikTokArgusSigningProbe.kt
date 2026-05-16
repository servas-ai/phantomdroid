package com.example.detectorlab.probes.app

import com.example.detectorlab.core.AndroidLayer
import com.example.detectorlab.core.Evidence
import com.example.detectorlab.core.Probe
import com.example.detectorlab.core.ProbeCategory
import com.example.detectorlab.core.ProbeContext
import com.example.detectorlab.core.ProbeResult
import com.example.detectorlab.core.ProbeSeverity

/**
 * Probe — app.tiktok_argus_signing (CLO-19, L4 + L6)
 *
 * Detects the Argus request-signing stack that TikTok embeds for anti-tamper and
 * anti-bot protection. The signing chain is:
 *
 *   1. `libsscronet.so`      — TikTok's forked Chromium net stack (load-time marker)
 *   2. `libmetasec_ov.so`    — Argus SDK native core; houses the X-Argus generator
 *                              at a version-specific ELF offset (see versions.json).
 *
 * Acceptance criteria (CLO-19):
 *   A. Detects `libsscronet.so` installed under the target package's lib directory.
 *   B. Detects `libmetasec_ov.so` alongside it; documents offset `0x88ee0` for v5.4.1.
 *   C. Passive only — no execution of TikTok code, no network requests.
 *   D. Ships `tiktok_argus_versions.json` (asset) as a version-keyed offset table with
 *      a fragility warning for every un-validated entry.
 *
 * Fragility note:
 *   The `libmetasec_ov.so` signing function offset changes with every TikTok release.
 *   Only v5.4.1 (offset 0x88ee0, arm64) has been manually validated. All other entries
 *   in `tiktok_argus_versions.json` are placeholders and must be re-derived via static
 *   analysis (`readelf -s / objdump`) before use.
 *
 * Score table:
 *   Both libs present → 0.85  (Argus SDK confirmed; emulator is detectable)
 *   One lib present   → 0.55  (partial — degraded or stripped APK variant)
 *   TikTok installed but libs not visible → 0.10  (path mismatch / permission)
 *   TikTok not installed → skipped
 */
class TikTokArgusSigningProbe : Probe {
    override val id = "app.tiktok_argus_signing"
    override val rank = RANK
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 2000L

    companion object {
        const val RANK = 66

        val TIKTOK_PACKAGES = listOf(
            "com.zhiliaoapp.musically",   // global TikTok
            "com.ss.android.ugc.tiktok",  // CN / Douyin variant
        )

        val NATIVE_ARCHS = listOf("arm64", "arm", "x86_64", "x86")

        // v5.4.1 validated offset; all others are undetermined until re-derived.
        const val VALIDATED_OFFSET_V541 = "0x88ee0"
        const val VERSIONS_ASSET = "tiktok_argus_versions.json"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val pm = ctx.queryPackageManager()

            val installedPkg = TIKTOK_PACKAGES.firstOrNull { pm.isPackageInstalled(it) }
                ?: return ProbeResult.skipped(
                    "TikTok not installed (checked: ${TIKTOK_PACKAGES.joinToString()})",
                    runtimeMs = System.currentTimeMillis() - start,
                )

            val evidence = mutableListOf<Evidence>()
            evidence += Evidence("tiktok.package", installedPkg)

            // Paths follow the standard AOSP data/app layout; slot suffix -1 / -2 covers
            // typical install variants. We check both to avoid false-negative on re-installs.
            val libDirs = NATIVE_ARCHS.flatMap { arch ->
                listOf(1, 2).map { slot -> "/data/app/$installedPkg-$slot/lib/$arch" }
            }

            val sscronetFound = libDirs.any { ctx.fileExists("$it/libsscronet.so") }
            val metasecFound  = libDirs.any { ctx.fileExists("$it/libmetasec_ov.so") }

            evidence += Evidence("tiktok.libsscronet_present",  sscronetFound, expected = true)
            evidence += Evidence("tiktok.libmetasec_ov_present", metasecFound,  expected = true)

            // Document the validated offset and fragility warning (AC-B / AC-D).
            evidence += Evidence("tiktok.argus_offset_v5_4_1",  VALIDATED_OFFSET_V541)
            evidence += Evidence("tiktok.offset_fragility",
                "offset changes per TikTok release; only v5.4.1 validated")
            evidence += Evidence("tiktok.versions_table", VERSIONS_ASSET)

            val score = when {
                sscronetFound && metasecFound -> 0.85
                sscronetFound || metasecFound -> 0.55
                else -> 0.10
            }
            val confidence = if (sscronetFound || metasecFound) 0.90 else 0.40

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = "PackageManager.isPackageInstalled + /data/app/<pkg>-N/lib/<arch>/ native library presence check",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "TikTokArgusSigningProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

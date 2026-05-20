package com.detectorlab.probes.app

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

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
 * **Android 10+ install-path layout (cross-cutting follow-up #8)**:
 *   Starting with API 29 (Android 10), AOSP changed the `/data/app/` layout from the
 *   legacy `/data/app/<pkg>-<N>/lib/<arch>/` form to
 *   `/data/app/~~<base64>==/<pkg>-<token>/lib/<arch>/`. The base64 segment is
 *   per-install random. The legacy paths this probe scans (slot-1 and slot-2) do
 *   NOT exist on modern Android — all real-world TikTok installs on API 29+
 *   previously scored a silent 0.10 ("TikTok installed but libs not visible")
 *   regardless of whether the Argus libs were actually present. This is
 *   cross-cutting follow-up #8 — silently broken on every modern device.
 *
 *   **Fix in this revision** (cross-cutting #8 partial):
 *     - Read `ro.build.version.sdk` to detect API 29+ environments
 *     - When pre-A10 scan finds nothing AND API >= 29: degrade gracefully —
 *       emit score 0.0 + confidence 0.5 + explicit `a10_plus_accessor_gap`
 *       pattern label, INSTEAD of the misleading 0.10 "libs not visible" score.
 *     - Document that A10+ scan path requires `listDirectory` accessor
 *       (cross-cutting follow-up #3). When that accessor lands, the A10+
 *       branch can enumerate `/data/app/~~*` directories and re-build the
 *       layered scan.
 *
 *   The legacy-API < 29 path is unchanged — pre-A10 emulators (still common
 *   on cloud-phone infrastructure) continue to score correctly. The fix
 *   eliminates the silent-broken case on modern devices without requiring
 *   a core-contract change today.
 *
 * Score table (pre-A10, SDK < 29):
 *   Both libs present                                → 0.85 (Argus SDK confirmed)
 *   One lib present                                  → 0.55 (partial / stripped APK)
 *   TikTok installed but native libs not visible     → 0.10 (path mismatch / permission)
 *   TikTok not installed                             → skipped
 *
 * Score table (A10+, SDK >= 29):
 *   Pre-A10 paths return absent (expected on modern Android): score 0.0,
 *   confidence 0.5, pattern `a10_plus_accessor_gap` — accessor gap documented.
 *   Pre-A10 paths return present (rare backport): score per pre-A10 table.
 *   SDK unknown / property accessor failure: falls through to pre-A10
 *   table (conservative — preserves existing behavior on devices where
 *   we cannot prove API >= 29).
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

        const val PROP_RO_BUILD_VERSION_SDK = "ro.build.version.sdk"

        /**
         * Android API level at which the `/data/app/<pkg>-<N>/lib/`
         * layout was replaced by `/data/app/~~<base64>==/<pkg>-<token>/lib/`.
         * API 29 = Android 10 (2019).
         */
        const val A10_SDK_THRESHOLD = 29

        const val PATTERN_PRE_A10_LIBS_FOUND = "pre_a10_libs_found"
        const val PATTERN_PRE_A10_LIBS_NOT_VISIBLE = "pre_a10_libs_not_visible"
        const val PATTERN_A10_PLUS_ACCESSOR_GAP = "a10_plus_accessor_gap"

        /**
         * Parse Android SDK int from `ro.build.version.sdk`. Returns
         * null for null / empty / non-numeric input.
         */
        internal fun parseSdkInt(rawSdk: String?): Int? {
            if (rawSdk.isNullOrEmpty()) return null
            return rawSdk.toIntOrNull()
        }
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

            // SDK detection for A10+ branch routing. Defensive: null on
            // throw or malformed value; falls through to pre-A10 behavior.
            val sdkRaw: String? = try {
                ctx.getSystemProperty(PROP_RO_BUILD_VERSION_SDK)
            } catch (_: Throwable) {
                null
            }
            val sdkInt = parseSdkInt(sdkRaw)
            val isA10Plus = sdkInt != null && sdkInt >= A10_SDK_THRESHOLD

            // Pre-A10 path scan — unchanged. On A10+ devices these
            // paths are expected absent (the layout moved); on legacy
            // devices the scan still works as before.
            val libDirs = NATIVE_ARCHS.flatMap { arch ->
                listOf(1, 2).map { slot -> "/data/app/$installedPkg-$slot/lib/$arch" }
            }

            val sscronetFound = libDirs.any { ctx.fileExists("$it/libsscronet.so") }
            val metasecFound = libDirs.any { ctx.fileExists("$it/libmetasec_ov.so") }
            val anyLibFound = sscronetFound || metasecFound

            // A10+ accessor-gap branch: SDK known to be >= 29 AND no
            // pre-A10 libs found. We KNOW the pre-A10 paths can't fire
            // on this device (layout moved), so the "libs not visible"
            // signal is unreliable. Degrade gracefully — score 0.0,
            // confidence 0.5, explicit accessor-gap pattern.
            //
            // If a future enumeration accessor (cross-cutting #3
            // `listDirectory`) lands, this branch becomes the place
            // where we'd scan `/data/app/~~*` for the package token.
            val isA10AccessorGap = isA10Plus && !anyLibFound

            val (pattern, score, confidence) = when {
                isA10AccessorGap ->
                    Triple(PATTERN_A10_PLUS_ACCESSOR_GAP, 0.0, 0.5)
                sscronetFound && metasecFound ->
                    Triple(PATTERN_PRE_A10_LIBS_FOUND, 0.85, 0.90)
                sscronetFound || metasecFound ->
                    Triple(PATTERN_PRE_A10_LIBS_FOUND, 0.55, 0.90)
                else ->
                    // Pre-A10 ground: TikTok installed, libs absent on
                    // legacy paths, and either SDK < 29 or SDK unknown.
                    // Conservative — preserves existing 0.10 / 0.40
                    // signal for legacy devices.
                    Triple(PATTERN_PRE_A10_LIBS_NOT_VISIBLE, 0.10, 0.40)
            }

            evidence += Evidence("tiktok.libsscronet_present", sscronetFound, expected = true)
            evidence += Evidence("tiktok.libmetasec_ov_present", metasecFound, expected = true)

            // SDK + path-scan strategy evidence makes the A10+ gap
            // visible to consumer-side analysis.
            evidence += Evidence(
                "tiktok.android_sdk",
                value = sdkInt?.toString() ?: (sdkRaw ?: "<unavailable>"),
                expected = "any",
            )
            evidence += Evidence(
                "tiktok.path_scan_strategy",
                value = when {
                    sdkInt == null -> "pre_a10_only_sdk_unknown"
                    isA10Plus -> "pre_a10_only_a10_plus_gap"
                    else -> "pre_a10_only_sdk_legacy"
                },
                expected = "depends on API level",
            )
            evidence += Evidence(
                "tiktok.a10_plus_accessor_gap",
                value = isA10AccessorGap.toString(),
                expected = "false on legacy, true on A10+ until listDirectory accessor lands",
            )
            evidence += Evidence("tiktok.pattern", pattern, expected = "depends")

            // Document the validated offset and fragility warning (AC-B / AC-D).
            evidence += Evidence("tiktok.argus_offset_v5_4_1", VALIDATED_OFFSET_V541)
            evidence += Evidence(
                "tiktok.offset_fragility",
                "offset changes per TikTok release; only v5.4.1 validated",
            )
            evidence += Evidence("tiktok.versions_table", VERSIONS_ASSET)

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method =
                    "PackageManager.isPackageInstalled + /data/app/<pkg>-N/lib/<arch>/ " +
                        "native library presence check (pre-A10 layout). A10+ devices " +
                        "(API >=29) degrade to accessor-gap pattern at confidence 0.5 " +
                        "pending listDirectory accessor for ~~<base64>== path " +
                        "enumeration (cross-cutting #3 / #8).",
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

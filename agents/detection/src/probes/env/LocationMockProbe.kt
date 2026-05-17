package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — env.location_mock  (inventory rank 39)
 *
 * Layer: L4 (framework)
 *
 * Detection strategy — four independent signals, ranked from strongest to weakest
 * evidence of a fake or manipulated GPS fix. Score is the maximum over all
 * triggered signals; confidence is computed from the count of fired signals.
 *
 *   Signal                                          Score on hit   API gate
 *   ─────────────────────────────────────────────────────────────────────────
 *   S1  Location.isFromMockProvider() = true        1.00           ≥ 18
 *       Reads the most recent fix's `isFromMockProvider` bit (exposed by the
 *       host via Settings.Secure sentinel "location.is_from_mock_provider";
 *       production wrapper reads `Location.isFromMockProvider`).
 *
 *   S2  Settings.Secure.mock_location = "1"         0.85           ≤ 22
 *       Legacy `ALLOW_MOCK_LOCATION` developer toggle. Removed at runtime
 *       in API 23+ but still readable on rooted/spoofed older devices.
 *
 *   S3  ≥1 installed package holds                  0.70 base,     ≥ 23
 *       ACCESS_MOCK_LOCATION                        +0.10 per extra
 *                                                   pkg, capped 1.0
 *       Reverse direction of S2: from API 23 the platform allows specific
 *       apps to act as mock providers via `android:protectionLevel`.
 *
 *   S4  Geocoder anomaly                            0.65           any
 *       The reverse-geocoder result for the device's last fix is
 *       inconsistent with the reported coordinates — e.g. reverse-geocoding
 *       returns no address for a coordinate that should be in a populated
 *       region, or returns an address whose admin area conflicts with the
 *       claimed locale. Surfaced by the host via Settings.Secure sentinel
 *       "location.geocoder_anomaly" (production wrapper performs the
 *       Geocoder.getFromLocation() reverse-lookup and sets the sentinel
 *       based on a tolerance against an offline coastline/admin-area dataset).
 *
 * Severity: HIGH — combines four independent channels (active-fix flag,
 * legacy toggle, package-permission audit, geographic plausibility) that
 * collectively indicate a fake or manipulated GPS fix with high confidence.
 *
 * Confidence:
 *   • S1 fires:                                     0.99
 *   • S2 or S3 or S4 fires (and S1 clean):          0.90
 *   • no signal fires:                              0.95 (true-negative)
 *
 * inventory.yml rank 39 — see shared/probes/inventory.yml.
 */
class LocationMockProbe : Probe {
    override val id = "env.location_mock"
    override val rank = 39
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 2000L

    internal companion object {
        // Settings.Secure sentinel keys — see KDoc above for the production
        // wrapper that populates each one.
        const val SETTING_IS_FROM_MOCK_PROVIDER = "location.is_from_mock_provider"
        const val SETTING_ALLOW_MOCK_LOCATION   = "mock_location"
        const val SETTING_GEOCODER_ANOMALY      = "location.geocoder_anomaly"

        const val PERMISSION_MOCK_LOCATION = "android.permission.ACCESS_MOCK_LOCATION"

        const val SCORE_S1_IS_FROM_MOCK = 1.00
        const val SCORE_S2_ALLOW_MOCK   = 0.85
        const val SCORE_S3_PKG_BASE     = 0.70
        const val SCORE_S3_PKG_STEP     = 0.10
        const val SCORE_S4_GEOCODER     = 0.65
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val evidence = mutableListOf<Evidence>()
            var maxScore = 0.0
            var triggeredCount = 0

            // S1 — isFromMockProvider on the last known fix.
            val mockProviderFlag = ctx.querySettingSecure(SETTING_IS_FROM_MOCK_PROVIDER)
            val isMockFix = mockProviderFlag == "1"
            evidence += Evidence("location.isFromMockProvider", isMockFix, expected = false)
            if (isMockFix) {
                maxScore = maxOf(maxScore, SCORE_S1_IS_FROM_MOCK)
                triggeredCount++
            }

            // S2 — legacy ALLOW_MOCK_LOCATION developer toggle (API ≤22).
            val allowMockSetting = ctx.querySettingSecure(SETTING_ALLOW_MOCK_LOCATION) ?: "0"
            val legacyMockEnabled = allowMockSetting == "1"
            evidence += Evidence("Settings.Secure.mock_location", allowMockSetting, expected = "0")
            if (legacyMockEnabled) {
                maxScore = maxOf(maxScore, SCORE_S2_ALLOW_MOCK)
                triggeredCount++
            }

            // S3 — packages holding ACCESS_MOCK_LOCATION (API ≥23).
            val mockPackages = ctx.queryPackageManager()
                .listPackagesWithPermission(PERMISSION_MOCK_LOCATION)
            evidence += Evidence(
                "packages_with_ACCESS_MOCK_LOCATION",
                mockPackages.size,
                expected = 0,
            )
            mockPackages.forEachIndexed { i, pkg ->
                evidence += Evidence("mock_package[$i]", pkg)
            }
            if (mockPackages.isNotEmpty()) {
                val s3Score = minOf(
                    SCORE_S3_PKG_BASE + (mockPackages.size - 1) * SCORE_S3_PKG_STEP,
                    1.0,
                )
                maxScore = maxOf(maxScore, s3Score)
                triggeredCount++
            }

            // S4 — reverse-geocoder anomaly for the last fix.
            val geocoderAnomalyFlag = ctx.querySettingSecure(SETTING_GEOCODER_ANOMALY)
            val geocoderAnomaly = geocoderAnomalyFlag == "1"
            evidence += Evidence("location.geocoder_anomaly", geocoderAnomaly, expected = false)
            if (geocoderAnomaly) {
                maxScore = maxOf(maxScore, SCORE_S4_GEOCODER)
                triggeredCount++
            }

            val confidence = when {
                isMockFix -> 0.99
                triggeredCount >= 1 -> 0.90
                else -> 0.95
            }

            ProbeResult(
                score = maxScore,
                confidence = confidence,
                evidence = evidence,
                method = "isFromMockProvider + Settings.Secure.mock_location + " +
                    "ACCESS_MOCK_LOCATION package scan + reverse-geocoder anomaly",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "LocationMockProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 41, mitigation_layer L4
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.LocationManagerView
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #41 — env.gps_coordinates.
 *
 * Reads the most-recent fix-frame from `LocationManagerView` (which wraps
 * `android.location.LocationManager.getLastKnownLocation` plus
 * `Location.isFromMockProvider()` on API ≥ 18) and scores it against five
 * independent signals that flag a static, missing, or mock-injected GPS
 * fix. Complements rank-39 (`LocationMockProbe` — settings + package audit)
 * and rank-82 (`LocationMockRaspProbe` — wrapper-synthesized RASP sentinel)
 * by attacking the runtime fix-frame surface directly.
 *
 * Detection strategy — five signals, max-wins:
 *
 *   Signal                                             Score on hit
 *   ────────────────────────────────────────────────────────────────
 *   S1  isMockProvider() == true                       1.00
 *       Canonical mock-location ground truth — the
 *       Android framework explicitly flags the fix as
 *       coming from a registered mock provider.
 *
 *   S2  lat == 0.0 AND lng == 0.0                      0.85
 *       "Null island" — coordinates at (0,0) are the
 *       Atlantic ocean off Africa; no real user is
 *       there. Emulator GPS stubs default here.
 *
 *   S3  hasLastKnownLocation() == false AND            0.85
 *       device-class is phone (rank-25 isPhoneClassModel)
 *       — phones should always have a last-known fix
 *       once location services have ever run. Gate via
 *       `ro.product.model` per the rank-25 helper.
 *
 *   S4  lastKnownAccuracy() < 1.0f                     0.70
 *       Real GPS rarely accurate below ~3m even in
 *       perfect-sky conditions; sub-meter accuracy on
 *       a phone is a mock-provider tell.
 *
 *   S5  lastKnownProvider() == "fused" AND             0.50
 *       device declares no GPS hardware (snapshot
 *       proxy: hasLastKnownLocation()==true but
 *       accuracy is null/sentinel) — suggests the
 *       fused-location framework is being driven by a
 *       mock layer rather than real satellites.
 *
 * Confidence:
 *   • hasLastKnownLocation() returns non-null         0.85
 *     (permission granted; framework reachable)
 *   • hasLastKnownLocation() returns null             0.30
 *     (permission missing or accessor failed)
 *
 * Cross-rank tension with rank-25 (`NetworkTypeProbe.isPhoneClassModel`):
 * S3's phone-class gate reuses the same helper as ranks 7, 13, 24, 27, 30,
 * 31, 36, 42, 43, 44, 45, 51 — same drift-safe extract-or-anchor pattern.
 *
 * Severity: LOW per inventory.yml — GPS spoofing is detectable but the
 * fix-frame surface is high-noise (real phones in tunnels / airplanes
 * legitimately have stale/missing fixes), so this probe contributes to
 * the confidence cascade rather than driving a critical classification
 * on its own.
 *
 * Reference: shared/probes/inventory.yml rank 41 (mitigation_layer L4).
 */
class GpsCoordinatesProbe : Probe {
    override val id = "env.gps_coordinates"
    override val rank = 41
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    internal companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val SCORE_MOCK_PROVIDER = 1.00
        const val SCORE_NULL_ISLAND = 0.85
        const val SCORE_NO_FIX_ON_PHONE = 0.85
        const val SCORE_IMPLAUSIBLE_ACCURACY = 0.70
        const val SCORE_FUSED_WITHOUT_GPS = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_PERMISSION_GRANTED = 0.85
        const val CONFIDENCE_PERMISSION_MISSING = 0.30

        const val ACCURACY_FLOOR_METERS = 1.0f
        const val PROVIDER_FUSED = "fused"

        const val METHOD =
            "LocationManager.getLastKnownLocation() + Location.isFromMockProvider() " +
                "(API >= 18). Five signals: mock-provider flag, null-island " +
                "sentinel coords, missing-fix-on-phone, sub-meter accuracy, " +
                "fused-without-GPS-hardware. Max-wins."
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val view: LocationManagerView = ctx.queryLocationManager()
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val hasFix: Boolean? = view.hasLastKnownLocation()
            val lat: Double? = view.lastKnownLatitude()
            val lng: Double? = view.lastKnownLongitude()
            val accuracy: Float? = view.lastKnownAccuracy()
            val provider: String? = view.lastKnownProvider()
            val isMock: Boolean? = view.isMockProvider()

            val evidence = mutableListOf<Evidence>()
            evidence += Evidence("location.hasLastKnownLocation", hasFix.toString())
            evidence += Evidence("location.latitude", lat?.toString() ?: "null")
            evidence += Evidence("location.longitude", lng?.toString() ?: "null")
            evidence += Evidence("location.accuracy_meters", accuracy?.toString() ?: "null")
            evidence += Evidence("location.provider", provider ?: "null")
            evidence += Evidence("location.isFromMockProvider", isMock.toString())
            evidence += Evidence("model.phone_class", phoneClass)

            var maxScore = SCORE_CLEAN

            // S1 — isMockProvider() flag (canonical mock-location signal).
            if (isMock == true) {
                maxScore = maxOf(maxScore, SCORE_MOCK_PROVIDER)
                evidence += Evidence("signal.S1_mock_provider", true, expected = false)
            }

            // S2 — null-island sentinel (0,0). Both coords must be exactly 0.0.
            if (lat != null && lng != null && lat == 0.0 && lng == 0.0) {
                maxScore = maxOf(maxScore, SCORE_NULL_ISLAND)
                evidence += Evidence("signal.S2_null_island", true, expected = false)
            }

            // S3 — phone with no last-known fix at all.
            if (hasFix == false && phoneClass) {
                maxScore = maxOf(maxScore, SCORE_NO_FIX_ON_PHONE)
                evidence += Evidence("signal.S3_no_fix_on_phone", true, expected = false)
            }

            // S4 — implausibly precise accuracy (sub-meter on a phone GPS).
            if (accuracy != null && accuracy < ACCURACY_FLOOR_METERS) {
                maxScore = maxOf(maxScore, SCORE_IMPLAUSIBLE_ACCURACY)
                evidence += Evidence(
                    "signal.S4_implausible_accuracy",
                    accuracy,
                    expected = ">=$ACCURACY_FLOOR_METERS",
                )
            }

            // S5 — "fused" provider but no GPS hardware (accuracy missing).
            // Snapshot proxy: hasFix=true, provider=fused, accuracy=null means
            // the fused-location framework is responding without a satellite
            // fix backing it — consistent with a mock layer driving the
            // FusedLocationProviderClient from above.
            if (hasFix == true && provider == PROVIDER_FUSED && accuracy == null) {
                maxScore = maxOf(maxScore, SCORE_FUSED_WITHOUT_GPS)
                evidence += Evidence("signal.S5_fused_without_gps", true, expected = false)
            }

            val confidence = if (hasFix == null) {
                CONFIDENCE_PERMISSION_MISSING
            } else {
                CONFIDENCE_PERMISSION_GRANTED
            }

            ProbeResult(
                score = maxScore,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "GpsCoordinatesProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

package com.detectorlab.probes.env

import com.detectorlab.core.LocationManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownLocationManagerView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for GpsCoordinatesProbe (env.gps_coordinates, inventory rank 41).
 *
 * Coverage:
 *   1. clean fix (real GPS in Mountain View)
 *   2. S1 isMockProvider == true (canonical mock-location)
 *   3. S2 (0,0) null-island sentinel coords
 *   4. S3 hasLastKnownLocation() == false AND phone-class model
 *   5. S3 negative: no-fix on non-phone-class model (tablet/TV box)
 *   6. S4 lastKnownAccuracy() < 1.0f (sub-meter implausibility)
 *   7. S4 boundary: accuracy exactly 1.0f clean
 *   8. S5 fused-without-gps (provider=fused, accuracy=null)
 *   9. confidence DEGRADED when hasLastKnownLocation() == null
 *  10. confidence GRANTED when hasLastKnownLocation() == false (still observed)
 *  11. max-wins: S1 beats S2/S3/S4/S5 when all triggered
 *  12. max-wins: S2 beats S4 when both fire without S1/S3
 *  13. probe metadata: id, rank, severity, budget, layer
 *  14. probe never throws on UnknownLocationManagerView default
 */
class GpsCoordinatesProbeTest {

    private val probe = GpsCoordinatesProbe()

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Build a ProbeContext with controllable LocationManagerView + model prop. */
    private fun fakeCtx(
        view: LocationManagerView = UnknownLocationManagerView,
        model: String? = "Pixel 7",
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == GpsCoordinatesProbe.PROP_RO_PRODUCT_MODEL) model else null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun queryLocationManager(): LocationManagerView = view
    }

    /** Minimal LocationManagerView with controllable fields. */
    private fun locView(
        sdkInt: Int = 33,
        hasFix: Boolean? = true,
        lat: Double? = 37.4221,
        lng: Double? = -122.0841,
        accuracy: Float? = 5.5f,
        provider: String? = "gps",
        isMock: Boolean? = false,
    ): LocationManagerView = object : LocationManagerView {
        override fun sdkInt() = sdkInt
        override fun hasLastKnownLocation() = hasFix
        override fun lastKnownLatitude() = lat
        override fun lastKnownLongitude() = lng
        override fun lastKnownAccuracy() = accuracy
        override fun lastKnownProvider() = provider
        override fun isMockProvider() = isMock
    }

    // ── Case 1: clean real-GPS fix ───────────────────────────────────────────

    @Test
    fun `clean real GPS fix scores 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(GpsCoordinatesProbe.CONFIDENCE_PERMISSION_GRANTED, result.confidence, 0.001)
    }

    // ── Case 2: S1 mock-provider ─────────────────────────────────────────────

    @Test
    fun `isMockProvider true — S1 scores 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView(isMock = true)))
        assertFalse(result.failed)
        assertEquals(1.0, result.score, 0.001)
        assertTrue(result.evidence.any {
            it.key == "signal.S1_mock_provider" && it.value == true
        })
    }

    // ── Case 3: S2 null-island (0,0) ─────────────────────────────────────────

    @Test
    fun `null-island 0,0 — S2 scores 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView(lat = 0.0, lng = 0.0)))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
        assertTrue(result.evidence.any {
            it.key == "signal.S2_null_island" && it.value == true
        })
    }

    // ── Case 4: S3 missing-fix on phone ──────────────────────────────────────

    @Test
    fun `hasLastKnownLocation false on phone — S3 scores 0_85`() = runBlocking {
        val view = locView(
            hasFix = false, lat = null, lng = null, accuracy = null,
            provider = null, isMock = null,
        )
        val result = probe.run(fakeCtx(view = view, model = "Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
        assertTrue(result.evidence.any {
            it.key == "signal.S3_no_fix_on_phone" && it.value == true
        })
    }

    // ── Case 5: S3 negative — missing-fix on non-phone-class model ──────────

    @Test
    fun `hasLastKnownLocation false on non-phone — S3 does not fire`() = runBlocking {
        val view = locView(
            hasFix = false, lat = null, lng = null, accuracy = null,
            provider = null, isMock = null,
        )
        // "Android TV" is not in NetworkTypeProbe.PHONE_CLASS_MODEL_SUBSTRINGS;
        // S3 must NOT fire on this device class.
        val result = probe.run(fakeCtx(view = view, model = "AFTKMST12"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
    }

    // ── Case 6: S4 sub-meter accuracy ────────────────────────────────────────

    @Test
    fun `accuracy below 1m — S4 scores 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView(accuracy = 0.5f)))
        assertFalse(result.failed)
        assertEquals(0.70, result.score, 0.001)
        assertTrue(result.evidence.any { it.key == "signal.S4_implausible_accuracy" })
    }

    // ── Case 7: S4 boundary — exactly 1.0m is clean ──────────────────────────

    @Test
    fun `accuracy exactly 1m — S4 does not fire`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView(accuracy = 1.0f)))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
    }

    // ── Case 8: S5 fused-without-gps ─────────────────────────────────────────

    @Test
    fun `fused provider with null accuracy — S5 scores 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(view = locView(provider = "fused", accuracy = null)))
        assertFalse(result.failed)
        assertEquals(0.50, result.score, 0.001)
        assertTrue(result.evidence.any {
            it.key == "signal.S5_fused_without_gps" && it.value == true
        })
    }

    // ── Case 9: confidence degraded when permission missing ──────────────────

    @Test
    fun `permission missing — confidence is DEGRADED`() = runBlocking {
        val result = probe.run(fakeCtx(view = UnknownLocationManagerView))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertEquals(GpsCoordinatesProbe.CONFIDENCE_PERMISSION_MISSING, result.confidence, 0.001)
    }

    // ── Case 10: confidence GRANTED when fix observed false (still permission) ──

    @Test
    fun `hasFix false but observed — confidence is GRANTED`() = runBlocking {
        // Tablet (non-phone-class) with no fix → S3 won't fire, but
        // the framework reachable answer (false) still grants confidence.
        val view = locView(
            hasFix = false, lat = null, lng = null, accuracy = null,
            provider = null, isMock = null,
        )
        val result = probe.run(fakeCtx(view = view, model = "Nexus 7"))
        assertFalse(result.failed)
        // Nexus 7 contains "nexus" which IS phone-class — switch to a tablet
        // model that is NOT phone-class.
        val view2 = locView(
            hasFix = false, lat = null, lng = null, accuracy = null,
            provider = null, isMock = null,
        )
        val result2 = probe.run(fakeCtx(view = view2, model = "Lenovo TB-X606F"))
        assertFalse(result2.failed)
        assertEquals(GpsCoordinatesProbe.CONFIDENCE_PERMISSION_GRANTED, result2.confidence, 0.001)
    }

    // ── Case 11: max-wins — S1 dominates over all others ─────────────────────

    @Test
    fun `S1 beats S2 S3 S4 S5 when all triggered`() = runBlocking {
        // S1 mock=true, S2 (0,0), S4 accuracy=0.1, S5 fused — and S3 requires
        // hasFix=false which is mutually exclusive with S2/S4/S5 (they require
        // a present fix). So we trigger S1+S2+S4 simultaneously.
        val view = locView(
            hasFix = true, lat = 0.0, lng = 0.0, accuracy = 0.1f,
            provider = "fused", isMock = true,
        )
        val result = probe.run(fakeCtx(view = view))
        assertFalse(result.failed)
        assertEquals(1.0, result.score, 0.001)
    }

    // ── Case 12: max-wins — S2 beats S4 when both trigger without S1 ─────────

    @Test
    fun `S2 beats S4 when both triggered without S1`() = runBlocking {
        val view = locView(
            lat = 0.0, lng = 0.0, accuracy = 0.5f, isMock = false,
        )
        val result = probe.run(fakeCtx(view = view))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
    }

    // ── Case 13: probe metadata ──────────────────────────────────────────────

    @Test
    fun `probe metadata matches inventory entry`() {
        assertEquals("env.gps_coordinates", probe.id)
        assertEquals(41, probe.rank)
        assertEquals(41.0, probe.inventoryRank, 0.001)
        assertEquals(com.detectorlab.core.ProbeCategory.ENV, probe.category)
        assertEquals(com.detectorlab.core.ProbeSeverity.LOW, probe.severity)
        assertEquals(com.detectorlab.core.AndroidLayer.FRAMEWORK, probe.androidLayer)
        assertEquals(100L, probe.budgetMs)
    }

    // ── Case 14: probe never throws on UnknownLocationManagerView default ───

    @Test
    fun `probe handles UnknownLocationManagerView without throwing`() = runBlocking {
        // Default ProbeContext with no queryLocationManager override returns
        // UnknownLocationManagerView. The probe must produce a clean ProbeResult
        // with score 0.0 and CONFIDENCE_PERMISSION_MISSING.
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: TelephonyField): String? = null
            override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = false
                override fun listInstalledPackages() = emptyList<String>()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
            override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
                override fun listSensorTypes() = emptyList<Int>()
                override fun sampleSensor(sensorType: Int, durationMs: Long) =
                    SensorSample(LongArray(0), emptyArray())
            }
            // No queryLocationManager override — exercises the default.
        }
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertEquals(GpsCoordinatesProbe.CONFIDENCE_PERMISSION_MISSING, result.confidence, 0.001)
    }
}

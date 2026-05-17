package com.detectorlab.probes.env

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for LocationMockProbe (env.location_mock, inventory rank 39).
 *
 * Mandatory acceptance test cases (CLO-129):
 *   1. clean                         — no signals fire, score = 0
 *   2. isFromMockProvider = true     — S1 fires, score = 1.00
 *   3. allowMockLocation setting = 1 — S2 fires, score = 0.85
 *   4. geocoder-anomaly              — S4 fires, score = 0.65
 *
 * Additional coverage exercises S3 (ACCESS_MOCK_LOCATION packages), signal
 * priority, and probe metadata, but all four required cases above are
 * isolated tests that fire one signal at a time with the others clean.
 */
class LocationMockProbeTest {

    private val probe = LocationMockProbe()

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun fakeCtx(
        isMockFix: Boolean = false,
        allowMockSetting: String = "0",
        mockPackages: List<String> = emptyList(),
        geocoderAnomaly: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun querySettingSecure(key: String): String? = when (key) {
            LocationMockProbe.SETTING_IS_FROM_MOCK_PROVIDER -> if (isMockFix) "1" else "0"
            LocationMockProbe.SETTING_ALLOW_MOCK_LOCATION   -> allowMockSetting
            LocationMockProbe.SETTING_GEOCODER_ANOMALY      -> if (geocoderAnomaly) "1" else "0"
            else -> null
        }
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String): List<String> =
                if (permission == LocationMockProbe.PERMISSION_MOCK_LOCATION) mockPackages
                else emptyList()
        }
    }

    // ── Acceptance case 1: clean device ──────────────────────────────────────

    @Test
    fun `clean — score is 0 when no mock signal fires`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Acceptance case 2: S1 isFromMockProvider = true ──────────────────────

    @Test
    fun `isFromMockProvider true — score is 1_0 and S1 dominates`() = runBlocking {
        val result = probe.run(fakeCtx(isMockFix = true))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        assertEquals(0.99, result.confidence)
        assertTrue(result.evidence.any {
            it.key == "location.isFromMockProvider" && it.value == true
        })
    }

    // ── Acceptance case 3: S2 allowMockLocation setting = 1 ──────────────────

    @Test
    fun `allowMockLocation setting 1 — score is 0_85 from S2`() = runBlocking {
        val result = probe.run(fakeCtx(allowMockSetting = "1"))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
        assertEquals(0.90, result.confidence, 0.001)
        assertTrue(result.evidence.any {
            it.key == "Settings.Secure.mock_location" && it.value == "1"
        })
    }

    // ── Acceptance case 4: S4 geocoder anomaly ───────────────────────────────

    @Test
    fun `geocoder anomaly — score is 0_65 from S4`() = runBlocking {
        val result = probe.run(fakeCtx(geocoderAnomaly = true))
        assertFalse(result.failed)
        assertEquals(0.65, result.score, 0.001)
        assertEquals(0.90, result.confidence, 0.001)
        assertTrue(result.evidence.any {
            it.key == "location.geocoder_anomaly" && it.value == true
        })
    }

    // ── Additional coverage: S3 ACCESS_MOCK_LOCATION packages ────────────────

    @Test
    fun `S3 triggered single package — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(mockPackages = listOf("com.fake.gps")))
        assertFalse(result.failed)
        assertEquals(0.70, result.score, 0.001)
        assertTrue(result.evidence.any {
            it.key == "mock_package[0]" && it.value == "com.fake.gps"
        })
    }

    @Test
    fun `S3 triggered two packages — score is 0_80`() = runBlocking {
        val result = probe.run(fakeCtx(mockPackages = listOf("com.fake.gps", "com.mock.loc")))
        assertFalse(result.failed)
        assertEquals(0.80, result.score, 0.001)
    }

    // ── Signal priority: highest score wins ──────────────────────────────────

    @Test
    fun `S1 beats S2 S3 S4 when all triggered`() = runBlocking {
        val result = probe.run(fakeCtx(
            isMockFix = true,
            allowMockSetting = "1",
            mockPackages = listOf("com.fake.gps"),
            geocoderAnomaly = true,
        ))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        assertEquals(0.99, result.confidence)
    }

    @Test
    fun `S2 beats S4 when both triggered without S1`() = runBlocking {
        val result = probe.run(fakeCtx(
            allowMockSetting = "1",
            geocoderAnomaly = true,
        ))
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("env.location_mock", probe.id)
    }

    @Test
    fun `probe rank matches inventory entry`() {
        assertEquals(39, probe.rank)
    }
}

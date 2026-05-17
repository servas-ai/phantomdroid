package com.detectorlab.probes.env

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.core.WifiManagerView
import com.detectorlab.core.WifiSecurityRead
import com.detectorlab.core.WifiSecurityType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for WifiSecurityTypeProbe (CLO-4, A17 N6).
 *
 * Covers the six classification states the acceptance criterion enumerates
 * ({none, wep, wpa, wpa2, wpa3, unknown}), the API-31-split path label
 * propagation, and the "inert-without-permission" rule that returns
 * `ProbeResult.skipped` rather than throwing.
 */
class WifiSecurityTypeProbeTest {

    private val probe = WifiSecurityTypeProbe()

    private fun fakeCtx(
        sdkInt: Int = 33,
        hasPermission: Boolean = true,
        type: WifiSecurityType = WifiSecurityType.WPA2,
        apiPath: String = "WifiManager.getCurrentNetwork+NetworkCapabilities",
    ): ProbeContext = object : ProbeContext {
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
        override fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView
        override fun queryWifiManager(): WifiManagerView = object : WifiManagerView {
            override fun sdkInt() = sdkInt
            override fun hasWifiAccessPermission() = hasPermission
            override fun currentNetworkSecurityType() = WifiSecurityRead(type, apiPath)
        }
    }

    // ── Classification: all six AC states ────────────────────────────────────

    @Test
    fun `classifies open AP as none with high detection score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.NONE))
        assertFalse(result.skipped)
        assertFalse(result.failed)
        assertEquals(0.90, result.score, 0.001)
        assertEquals("none", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    @Test
    fun `classifies WEP as wep with high detection score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WEP))
        assertEquals(0.85, result.score, 0.001)
        assertEquals("wep", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    @Test
    fun `classifies WPA as wpa with weak-medium score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WPA))
        assertEquals(0.30, result.score, 0.001)
        assertEquals("wpa", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    @Test
    fun `classifies WPA2 as wpa2 with near-zero score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WPA2))
        assertEquals(0.05, result.score, 0.001)
        assertEquals("wpa2", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    @Test
    fun `classifies WPA3 as wpa3 with zero score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WPA3))
        assertEquals(0.0, result.score, 0.001)
        assertEquals("wpa3", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    @Test
    fun `classifies unknown as unknown with weak confidence and zero score`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.UNKNOWN))
        assertEquals(0.0, result.score, 0.001)
        assertTrue(result.confidence <= 0.40, "expected weak confidence, got ${result.confidence}")
        assertEquals("unknown", result.evidence.first { it.key == "wifi.security_type" }.value)
    }

    // ── (3) Inert without permission → skipped ───────────────────────────────

    @Test
    fun `returns skipped when API 33+ lacks NEARBY_WIFI_DEVICES`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 34, hasPermission = false))
        assertTrue(result.skipped, "expected skipped, got failed=${result.failed}")
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("NEARBY_WIFI_DEVICES"))
        assertEquals(0.0, result.score, 0.001)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `returns skipped when pre-33 lacks ACCESS_FINE_LOCATION`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = 31, hasPermission = false))
        assertTrue(result.skipped)
        assertTrue(result.failureReason!!.contains("ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `returns skipped when view declares UNAVAILABLE despite permission held`() = runBlocking {
        val result = probe.run(fakeCtx(hasPermission = true, type = WifiSecurityType.UNAVAILABLE))
        assertTrue(result.skipped)
    }

    // ── (2) API split — propagates apiPath to evidence ────────────────────────

    @Test
    fun `API 31+ path label propagates through to evidence`() = runBlocking {
        val result = probe.run(fakeCtx(
            sdkInt = 33,
            apiPath = "WifiManager.getCurrentNetwork+NetworkCapabilities",
        ))
        assertEquals(
            "WifiManager.getCurrentNetwork+NetworkCapabilities",
            result.evidence.first { it.key == "wifi.api_path" }.value,
        )
    }

    @Test
    fun `pre-31 path label propagates through to evidence`() = runBlocking {
        val result = probe.run(fakeCtx(
            sdkInt = 28,
            apiPath = "WifiConfiguration.allowedKeyManagement",
        ))
        assertEquals(
            "WifiConfiguration.allowedKeyManagement",
            result.evidence.first { it.key == "wifi.api_path" }.value,
        )
    }

    // ── Evidence completeness ────────────────────────────────────────────────

    @Test
    fun `evidence always reports security_type, sdk_int, api_path, permission_granted`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WPA2))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("wifi.security_type" in keys)
        assertTrue("Build.VERSION.SDK_INT" in keys)
        assertTrue("wifi.api_path" in keys)
        assertTrue("wifi.permission_granted" in keys)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("env.wifi_security_type", probe.id)
    }

    @Test
    fun `probe rank is in the A17 expansion range`() {
        assertTrue(probe.rank in 61..71)
    }

    @Test
    fun `probe runtime fits within budget`() = runBlocking {
        val result = probe.run(fakeCtx(type = WifiSecurityType.WPA2))
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }

    // ── scoreFor/labelFor truth tables ───────────────────────────────────────

    @Test
    fun `scoreFor truth table`() {
        assertEquals(0.90, probe.scoreFor(WifiSecurityType.NONE), 0.001)
        assertEquals(0.85, probe.scoreFor(WifiSecurityType.WEP), 0.001)
        assertEquals(0.30, probe.scoreFor(WifiSecurityType.WPA), 0.001)
        assertEquals(0.05, probe.scoreFor(WifiSecurityType.WPA2), 0.001)
        assertEquals(0.00, probe.scoreFor(WifiSecurityType.WPA3), 0.001)
        assertEquals(0.00, probe.scoreFor(WifiSecurityType.UNKNOWN), 0.001)
        assertEquals(0.00, probe.scoreFor(WifiSecurityType.NOT_CONNECTED), 0.001)
    }

    @Test
    fun `labelFor truth table`() {
        assertEquals("none", probe.labelFor(WifiSecurityType.NONE))
        assertEquals("wep", probe.labelFor(WifiSecurityType.WEP))
        assertEquals("wpa", probe.labelFor(WifiSecurityType.WPA))
        assertEquals("wpa2", probe.labelFor(WifiSecurityType.WPA2))
        assertEquals("wpa3", probe.labelFor(WifiSecurityType.WPA3))
        assertEquals("unknown", probe.labelFor(WifiSecurityType.UNKNOWN))
    }
}

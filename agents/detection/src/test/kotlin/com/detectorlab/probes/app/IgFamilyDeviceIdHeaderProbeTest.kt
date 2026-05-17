package com.detectorlab.probes.app

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.probes.app.IgFamilyDeviceIdHeaderProbe.CaptureFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for IgFamilyDeviceIdHeaderProbe (CLO-18).
 *
 * All file I/O is replaced by a fake ProbeContext; no real filesystem
 * or mitmproxy harness is required.
 */
class IgFamilyDeviceIdHeaderProbeTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    private val sandboxJson = """
        {
          "sandbox_marker": "SANDBOX_2026_CLO18_TEST",
          "capture_ts_utc": 1747350000.0,
          "header_present": true,
          "value": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
          "is_valid_uuid": true,
          "entropy_bits": 122.5,
          "capture_count": 25,
          "header_count": 20,
          "apps_seen": ["instagram.com", "threads.net"],
          "unique_values": ["a1b2c3d4-e5f6-7890-abcd-ef1234567890"]
        }
    """.trimIndent()

    private val lowEntropyJson = """
        {
          "sandbox_marker": "SANDBOX_2026_CLO18_TEST",
          "capture_ts_utc": 1747350000.0,
          "header_present": true,
          "value": "00000000-0000-0000-0000-000000000001",
          "is_valid_uuid": true,
          "entropy_bits": 15.2,
          "capture_count": 30,
          "header_count": 30,
          "apps_seen": ["instagram.com"],
          "unique_values": ["00000000-0000-0000-0000-000000000001"]
        }
    """.trimIndent()

    private val absentHeaderJson = """
        {
          "sandbox_marker": "SANDBOX_2026_CLO18_TEST",
          "capture_ts_utc": 1747350000.0,
          "header_present": false,
          "value": null,
          "is_valid_uuid": false,
          "entropy_bits": 0.0,
          "capture_count": 10,
          "header_count": 0,
          "apps_seen": [],
          "unique_values": []
        }
    """.trimIndent()

    private val noMarkerJson = """
        {
          "sandbox_marker": "",
          "capture_ts_utc": 1747350000.0,
          "header_present": true,
          "value": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
          "is_valid_uuid": true,
          "entropy_bits": 122.5,
          "capture_count": 25,
          "header_count": 20,
          "apps_seen": ["instagram.com"],
          "unique_values": ["a1b2c3d4-e5f6-7890-abcd-ef1234567890"]
        }
    """.trimIndent()

    private fun fakeCtx(
        fileExists: Boolean = true,
        fileContent: String? = sandboxJson,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = fileExists
        override fun readFile(path: String, maxBytes: Int): String? = fileContent
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
    }

    private val probe = IgFamilyDeviceIdHeaderProbe()

    // ── Ethics block ──────────────────────────────────────────────────────

    @Test
    fun `returns skipped when capture file is absent`() = runBlocking {
        val result = probe.run(fakeCtx(fileExists = false))
        assertTrue(result.skipped)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("not found", ignoreCase = true))
    }

    @Test
    fun `returns skipped when file is unreadable`() = runBlocking {
        val result = probe.run(fakeCtx(fileExists = true, fileContent = null))
        assertTrue(result.skipped)
    }

    @Test
    fun `returns skipped when sandbox_marker is blank`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = noMarkerJson))
        assertTrue(result.skipped)
        assertTrue(result.failureReason!!.contains("sandbox_marker", ignoreCase = true))
    }

    @Test
    fun `returns failed when JSON is malformed`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = "not-json{{{"))
        assertTrue(result.failed)
    }

    // ── Score table ───────────────────────────────────────────────────────

    @Test
    fun `header present with normal entropy scores 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = sandboxJson))
        assertFalse(result.skipped)
        assertFalse(result.failed)
        assertEquals(0.85, result.score, 0.001)
    }

    @Test
    fun `header present with low entropy scores 0_95 (cloned ID)`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = lowEntropyJson))
        assertEquals(0.95, result.score, 0.001)
    }

    @Test
    fun `header absent scores 0_05`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = absentHeaderJson))
        assertEquals(0.05, result.score, 0.001)
    }

    // ── Confidence table ──────────────────────────────────────────────────

    @Test
    fun `capture_count 25 yields confidence 0_92`() {
        assertEquals(0.92, probe.confidenceFor(25), 0.001)
        assertEquals(0.92, probe.confidenceFor(20), 0.001)
    }

    @Test
    fun `capture_count 5 to 19 yields confidence 0_75`() {
        assertEquals(0.75, probe.confidenceFor(5), 0.001)
        assertEquals(0.75, probe.confidenceFor(19), 0.001)
    }

    @Test
    fun `capture_count below 5 yields low confidence 0_50`() {
        assertEquals(0.50, probe.confidenceFor(0), 0.001)
        assertEquals(0.50, probe.confidenceFor(4), 0.001)
    }

    // ── Evidence completeness ─────────────────────────────────────────────

    @Test
    fun `evidence includes all required keys`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = sandboxJson))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("ig.header_present" in keys)
        assertTrue("ig.header_value" in keys)
        assertTrue("ig.is_valid_uuid" in keys)
        assertTrue("ig.entropy_bits" in keys)
        assertTrue("ig.capture_count" in keys)
        assertTrue("ig.header_count" in keys)
        assertTrue("ig.apps_seen" in keys)
        assertTrue("ig.unique_value_count" in keys)
        assertTrue("ig.sandbox_marker_present" in keys)
    }

    @Test
    fun `evidence reports apps_seen from capture`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = sandboxJson))
        val appsSeen = result.evidence.first { it.key == "ig.apps_seen" }.value as String
        assertTrue(appsSeen.contains("instagram.com"))
        assertTrue(appsSeen.contains("threads.net"))
    }

    // ── JSON parser ───────────────────────────────────────────────────────

    @Test
    fun `parseCaptureFile round-trips sandbox JSON correctly`() {
        val capture = probe.parseCaptureFile(sandboxJson)
        assertNotNull(capture)
        assertEquals("SANDBOX_2026_CLO18_TEST", capture.sandboxMarker)
        assertTrue(capture.headerPresent)
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", capture.value)
        assertTrue(capture.isValidUuid)
        assertEquals(122.5, capture.entropyBits, 0.01)
        assertEquals(25, capture.captureCount)
        assertEquals(20, capture.headerCount)
        assertTrue("instagram.com" in capture.appsSeen)
        assertTrue("threads.net" in capture.appsSeen)
        assertEquals(1, capture.uniqueValues.size)
    }

    @Test
    fun `parseCaptureFile handles null value field`() {
        val capture = probe.parseCaptureFile(absentHeaderJson)
        assertNotNull(capture)
        assertFalse(capture.headerPresent)
        assertNull(capture.value)
        assertTrue(capture.appsSeen.isEmpty())
        assertTrue(capture.uniqueValues.isEmpty())
    }

    @Test
    fun `parseCaptureFile returns null for malformed JSON`() {
        assertNull(probe.parseCaptureFile("{bad json}}}"))
    }

    // ── scoreFor truth table ──────────────────────────────────────────────

    @Test
    fun `scoreFor truth table`() {
        val absent = CaptureFile("m", false, null, false, 0.0, 10, 0, emptyList(), emptyList())
        val lowEntropy = CaptureFile("m", true, "00...", true, 50.0, 10, 10, emptyList(), emptyList())
        val highEntropy = CaptureFile("m", true, "uuid", true, 122.5, 10, 10, emptyList(), emptyList())
        assertEquals(0.05, probe.scoreFor(absent), 0.001)
        assertEquals(0.95, probe.scoreFor(lowEntropy), 0.001)
        assertEquals(0.85, probe.scoreFor(highEntropy), 0.001)
    }

    // ── Probe metadata ─────────────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("app.ig_family_device_id_header", probe.id)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe rank is 67`() {
        assertEquals(67, probe.rank)
    }

    @Test
    fun `probe budget is within the 5s ceiling`() {
        assertTrue(probe.budgetMs <= 5_000L)
    }

    @Test
    fun `probe runs within budget`() = runBlocking {
        val result = probe.run(fakeCtx(fileContent = sandboxJson))
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

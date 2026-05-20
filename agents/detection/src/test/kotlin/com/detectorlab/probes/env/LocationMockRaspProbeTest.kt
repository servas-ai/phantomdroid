package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for LocationMockRaspProbe
 * (env.location_mock_rasp, inventory rank 39.5, code rank 82).
 */
class LocationMockRaspProbeTest {

    private val probe = LocationMockRaspProbe()

    private fun fakeCtx(
        isFromMockProvider: String? = "0",
        allowMockLocation: String? = "0",
        mockLocationApp: String? = "",
        sdkInt: String? = "34",
        secureThrowsOn: Set<String> = emptySet(),
        propertyThrowsOn: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (key in propertyThrowsOn) throw RuntimeException("simulated EACCES on $key")
            return if (key == LocationMockRaspProbe.PROP_RO_BUILD_VERSION_SDK) sdkInt else null
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? {
            if (key in secureThrowsOn) throw RuntimeException("simulated EACCES on $key")
            return when (key) {
                LocationMockRaspProbe.SETTING_IS_FROM_MOCK_PROVIDER -> isFromMockProvider
                LocationMockRaspProbe.SETTING_ALLOW_MOCK_LOCATION -> allowMockLocation
                LocationMockRaspProbe.SETTING_MOCK_LOCATION_APP -> mockLocationApp
                else -> null
            }
        }
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
    }

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `clean modern device — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — mock_detected evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "location.rasp_mock_detected" }
        assertEquals("false", ev?.value)
    }

    // ── isFromMockProvider (1.0) ─────────────────────────────────────────────

    @Test
    fun `isFromMockProvider true — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(isFromMockProvider = "1"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `isFromMockProvider — pattern is is_from_mock_provider`() = runBlocking {
        val result = probe.run(fakeCtx(isFromMockProvider = "1"))
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_IS_FROM_MOCK_PROVIDER, ev?.value)
    }

    @Test
    fun `isFromMockProvider — mock_detected evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(isFromMockProvider = "1"))
        val ev = result.evidence.find { it.key == "location.rasp_mock_detected" }
        assertEquals("true", ev?.value)
    }

    // ── MOCK_LOCATION_APP set (1.0) ──────────────────────────────────────────

    @Test
    fun `mock_location_app set — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(mockLocationApp = "com.lexa.fakegps")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mock_location_app — pattern is mock_location_app_set`() = runBlocking {
        val result = probe.run(
            fakeCtx(mockLocationApp = "com.lexa.fakegps")
        )
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_MOCK_LOCATION_APP_SET, ev?.value)
    }

    @Test
    fun `mock_location_app empty string — score is 0 (null-vs-empty discipline)`() = runBlocking {
        // Empty string = "not set" per session-level null-vs-empty
        // policy — score does NOT fire.
        val result = probe.run(fakeCtx(mockLocationApp = ""))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mock_location_app any-package value — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(mockLocationApp = "com.example.spoofgps")
        )
        assertEquals(1.0, result.score)
    }

    // ── ALLOW_MOCK_LOCATION on legacy API (0.85) ─────────────────────────────

    @Test
    fun `ALLOW_MOCK_LOCATION on API 22 — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "22")
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ALLOW_MOCK_LOCATION on API 18 — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "18")
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ALLOW_MOCK_LOCATION on API 23 boundary — score is 0 (strict gate)`() = runBlocking {
        // API < 23 strict; API 23 exactly passes.
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "23")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ALLOW_MOCK_LOCATION on API 34 — score is 0 (modern Android)`() = runBlocking {
        // Modern Android: ALLOW_MOCK_LOCATION may be readable but
        // has no functional effect — rule deliberately doesn't fire.
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "34")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ALLOW_MOCK_LOCATION on null sdk — score is 0 (cannot claim legacy)`() = runBlocking {
        // Without SDK confirmation, can't fire the legacy rule.
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = null)
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ALLOW_MOCK_LOCATION on malformed sdk — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "not-a-number")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `allow_mock on legacy — pattern is allow_mock_on_legacy`() = runBlocking {
        val result = probe.run(
            fakeCtx(allowMockLocation = "1", sdkInt = "22")
        )
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_ALLOW_MOCK_ON_LEGACY, ev?.value)
    }

    // ── No Location available (0.5) ──────────────────────────────────────────

    @Test
    fun `no location available — score is 0_5`() = runBlocking {
        // isFromMockProvider null, mock_app empty, allow_mock observable
        // but not "1" — degraded weak signal.
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                allowMockLocation = "0",
                mockLocationApp = "",
            )
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no location available — pattern is no_location_available`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                allowMockLocation = "0",
                mockLocationApp = "",
            )
        )
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_NO_LOCATION_AVAILABLE, ev?.value)
    }

    @Test
    fun `mock_app set wins over no_location_available`() = runBlocking {
        // isFromMockProvider null but mock_app explicitly set —
        // mock_app rule (1.0) wins over no_location (0.5).
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                mockLocationApp = "com.example.spoof",
            )
        )
        assertEquals(1.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `isFromMockProvider wins over mock_app (same score, source order)`() = runBlocking {
        // Both at 1.0; source order picks isFromMockProvider first.
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = "1",
                mockLocationApp = "com.example.spoof",
            )
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_IS_FROM_MOCK_PROVIDER, ev?.value)
    }

    @Test
    fun `mock_app wins over allow_mock_on_legacy`() = runBlocking {
        // mock_app at 1.0 wins over allow_mock at 0.85.
        val result = probe.run(
            fakeCtx(
                mockLocationApp = "com.example.spoof",
                allowMockLocation = "1",
                sdkInt = "22",
            )
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "location.location_mock_rasp_pattern" }
        assertEquals(LocationMockRaspProbe.PATTERN_MOCK_LOCATION_APP_SET, ev?.value)
    }

    @Test
    fun `isFromMockProvider wins over allow_mock_on_legacy`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = "1",
                allowMockLocation = "1",
                sdkInt = "22",
            )
        )
        assertEquals(1.0, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `all accessors null — confidence is 0_50 (no observation)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                allowMockLocation = null,
                mockLocationApp = null,
                sdkInt = null,
            )
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `at least one accessor non-null — confidence is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                allowMockLocation = "0",
                mockLocationApp = null,
            )
        )
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `empty-string observation — confidence 0_95 (empty is observation)`() = runBlocking {
        // Per null-vs-empty discipline: empty IS observation for confidence.
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = "",
                allowMockLocation = "",
                mockLocationApp = "",
            )
        )
        assertEquals(0.95, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `single Settings_Secure key throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                secureThrowsOn = setOf(LocationMockRaspProbe.SETTING_IS_FROM_MOCK_PROVIDER)
            )
        )
        assertFalse(result.failed)
    }

    @Test
    fun `all Settings_Secure keys throw — does not crash, degraded`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                secureThrowsOn = setOf(
                    LocationMockRaspProbe.SETTING_IS_FROM_MOCK_PROVIDER,
                    LocationMockRaspProbe.SETTING_ALLOW_MOCK_LOCATION,
                    LocationMockRaspProbe.SETTING_MOCK_LOCATION_APP,
                ),
                sdkInt = null,
            )
        )
        assertFalse(result.failed)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `SDK property throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                propertyThrowsOn = setOf(LocationMockRaspProbe.PROP_RO_BUILD_VERSION_SDK)
            )
        )
        assertFalse(result.failed)
    }

    @Test
    fun `selective throw — other surfaces still observed`() = runBlocking {
        // isFromMockProvider throws but mock_app supplies signal —
        // mock_app rule still fires.
        val result = probe.run(
            fakeCtx(
                mockLocationApp = "com.example.spoof",
                secureThrowsOn = setOf(LocationMockRaspProbe.SETTING_IS_FROM_MOCK_PROVIDER),
            )
        )
        assertEquals(1.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `parseSdkInt parses valid integer strings`() {
        assertEquals(22, LocationMockRaspProbe.parseSdkInt("22"))
        assertEquals(23, LocationMockRaspProbe.parseSdkInt("23"))
        assertEquals(34, LocationMockRaspProbe.parseSdkInt("34"))
    }

    @Test
    fun `parseSdkInt returns null for null and empty`() {
        assertNull(LocationMockRaspProbe.parseSdkInt(null))
        assertNull(LocationMockRaspProbe.parseSdkInt(""))
    }

    @Test
    fun `parseSdkInt returns null for malformed`() {
        assertNull(LocationMockRaspProbe.parseSdkInt("not-a-number"))
        assertNull(LocationMockRaspProbe.parseSdkInt("23.5"))
        assertNull(LocationMockRaspProbe.parseSdkInt(" 22 "))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "secure.location.is_from_mock_provider",
                "secure.mock_location",
                "secure.mock_location_app",
                "build.version.sdk_int",
                "location.rasp_mock_detected",
                "location.location_mock_rasp_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `isFromMockProvider evidence reflects accessor`() = runBlocking {
        val result = probe.run(fakeCtx(isFromMockProvider = "1"))
        val ev = result.evidence.find { it.key == "secure.location.is_from_mock_provider" }
        assertEquals("1", ev?.value)
    }

    @Test
    fun `isFromMockProvider evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(isFromMockProvider = null))
        val ev = result.evidence.find { it.key == "secure.location.is_from_mock_provider" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `mock_location_app evidence reflects accessor`() = runBlocking {
        val result = probe.run(fakeCtx(mockLocationApp = "com.lexa.fakegps"))
        val ev = result.evidence.find { it.key == "secure.mock_location_app" }
        assertEquals("com.lexa.fakegps", ev?.value)
    }

    @Test
    fun `sdk_int evidence reflects parsed value`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = "34"))
        val ev = result.evidence.find { it.key == "build.version.sdk_int" }
        assertEquals("34", ev?.value)
    }

    @Test
    fun `sdk_int evidence shows malformed marker on bad input`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = "bogus"))
        val ev = result.evidence.find { it.key == "build.version.sdk_int" }
        assertEquals("<malformed:bogus>", ev?.value)
    }

    @Test
    fun `sdk_int evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(sdkInt = null))
        val ev = result.evidence.find { it.key == "build.version.sdk_int" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `rasp_mock_detected evidence is unknown when no observation`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                isFromMockProvider = null,
                allowMockLocation = null,
                mockLocationApp = null,
                sdkInt = null,
            )
        )
        val ev = result.evidence.find { it.key == "location.rasp_mock_detected" }
        assertEquals("unknown", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Location.isFromMockProvider() + ALLOW_MOCK_LOCATION setting + " +
                "MOCK_LOCATION_APP setting; focused RASP-attribution probe per " +
                "freeRASP T16. Augments rank 39 LocationMockProbe with explicit " +
                "RASP-mapped score.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_location_mock_rasp`() {
        assertEquals("env.location_mock_rasp", probe.id)
    }

    @Test
    fun `probe rank is 82 in code (inventory says 39_5)`() {
        // Inventory.yml lists this probe at fractional rank 39.5.
        // Probe.rank: Int can't hold 39.5, so the code uses rank 82
        // (third Int-vs-fractional anchor after ScreenLockProbe
        // 40.5→61 and DebuggerTracerPidProbe 8.5→80). Tracked in
        // audit/cross-cutting-followups-2026-05-19.md #7.
        assertEquals(82, probe.rank)
        assertEquals(LocationMockRaspProbe.RANK, probe.rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, probe.category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // inventory.yml rank-39.5 severity = medium (RASP-mapped
        // baseline). Distinct from rank-39 LocationMockProbe HIGH
        // (broader 4-channel score) — same source, different
        // scoring focus per yaml-wins discipline.
        assertEquals(ProbeSeverity.MEDIUM, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

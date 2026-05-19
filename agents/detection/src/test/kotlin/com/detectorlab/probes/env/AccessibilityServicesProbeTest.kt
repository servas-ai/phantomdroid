package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.runtime.InstalledAppsProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for AccessibilityServicesProbe
 * (env.accessibility_services, inventory rank 59).
 */
class AccessibilityServicesProbeTest {

    private val probe = AccessibilityServicesProbe()

    private fun fakeCtx(
        enabledServices: String? = "",
        model: String? = "Pixel 7",
        secureThrows: Boolean = false,
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES on property")
            return if (key == AccessibilityServicesProbe.PROP_RO_PRODUCT_MODEL) model else null
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? {
            if (secureThrows) throw RuntimeException("simulated EACCES on secure")
            return if (key == AccessibilityServicesProbe.SETTING_ENABLED_ACCESSIBILITY_SERVICES)
                enabledServices
            else
                null
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
    fun `empty list — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "accessibility.pattern" }
        assertEquals(AccessibilityServicesProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `TalkBack only — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.google.android.marvin.talkback/.TalkBackService")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `TalkBack + Voice Access (2 real services) — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices =
                    "com.google.android.marvin.talkback/.TalkBackService:" +
                        "com.google.android.apps.accessibility.voiceaccess/.VoiceAccessService"
            )
        )
        assertEquals(0.0, result.score)
    }

    // ── Suspicious accessibility service (1.0) ───────────────────────────────

    @Test
    fun `com_android_uiautomator service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.android.uiautomator/.UiAutomatorService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mods_autoui service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "mods.autoui/.AutoUiAccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `autojs service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "org.autojs.autojs/.service.AccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `com_touchtask service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.touchtask/.AccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `airwatch MDM service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.airwatch/.MDMAccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zdmotion service — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.zdmotion/.AccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `generic automation substring — score is 1_0`() = runBlocking {
        // Unbranded automation tool with "automation" in component name.
        val result = probe.run(
            fakeCtx(enabledServices = "com.example.automation_bot/.MainService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `generic screen_recorder substring — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.example.screen.recorder/.AccessibilityService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `generic botaccessibility substring — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.example.botaccessibility/.Service")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `suspicious mixed with real — suspicious fires`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices =
                    "com.google.android.marvin.talkback/.TalkBackService:" +
                        "com.android.uiautomator/.UiAutomatorService"
            )
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `suspicious — pattern is suspicious_service`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.android.uiautomator/.UiAutomatorService")
        )
        val ev = result.evidence.find { it.key == "accessibility.pattern" }
        assertEquals(AccessibilityServicesProbe.PATTERN_SUSPICIOUS_SERVICE, ev?.value)
    }

    @Test
    fun `suspicious — has_suspicious_service evidence is true`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.android.uiautomator/.UiAutomatorService")
        )
        val ev = result.evidence.find { it.key == "accessibility.has_suspicious_service" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `suspicious match case-insensitive — UPPERCASE token fires`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.example.AUTOMATION_BOT/.Service")
        )
        assertEquals(1.0, result.score)
    }

    // ── Many services on phone-class (0.7) ───────────────────────────────────

    @Test
    fun `4 services on Pixel 7 — score is 0_7`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices =
                    "com.svc1/.Service1:com.svc2/.Service2:com.svc3/.Service3:com.svc4/.Service4"
            )
        )
        assertEquals(0.7, result.score)
    }

    @Test
    fun `5 services on phone — score is 0_7`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices = (1..5).joinToString(":") { "com.svc$it/.Service$it" }
            )
        )
        assertEquals(0.7, result.score)
    }

    @Test
    fun `3 services on phone — score is 0 (boundary, not over threshold)`() = runBlocking {
        // MANY_SERVICES_THRESHOLD is `> 3` strict — exactly 3 passes.
        val result = probe.run(
            fakeCtx(enabledServices = "com.svc1/.S:com.svc2/.S:com.svc3/.S")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `4 services on Lenovo tablet (not phone-class) — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices = "com.svc1/.S:com.svc2/.S:com.svc3/.S:com.svc4/.S",
                model = "Lenovo Tab P11",
            )
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `4 services on null model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices = "com.svc1/.S:com.svc2/.S:com.svc3/.S:com.svc4/.S",
                model = null,
            )
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `many services — pattern is many_services_on_phone`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                enabledServices = "com.svc1/.S:com.svc2/.S:com.svc3/.S:com.svc4/.S"
            )
        )
        val ev = result.evidence.find { it.key == "accessibility.pattern" }
        assertEquals(AccessibilityServicesProbe.PATTERN_MANY_SERVICES_ON_PHONE, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `suspicious wins over many-services on phone`() = runBlocking {
        // 4 services, one suspicious — suspicious fires first.
        val result = probe.run(
            fakeCtx(
                enabledServices =
                    "com.svc1/.S:com.svc2/.S:com.svc3/.S:com.android.uiautomator/.S"
            )
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "accessibility.pattern" }
        assertEquals(AccessibilityServicesProbe.PATTERN_SUSPICIOUS_SERVICE, ev?.value)
    }

    // ── Confidence tiers / crash safety ──────────────────────────────────────

    @Test
    fun `accessor null — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(enabledServices = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `accessor null — score is 0 (no rule fires)`() = runBlocking {
        val result = probe.run(fakeCtx(enabledServices = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `accessor null — service_count evidence is unavailable`() = runBlocking {
        val result = probe.run(fakeCtx(enabledServices = null))
        val ev = result.evidence.find { it.key == "accessibility.service_count" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `accessor null — has_suspicious evidence is unknown`() = runBlocking {
        val result = probe.run(fakeCtx(enabledServices = null))
        val ev = result.evidence.find { it.key == "accessibility.has_suspicious_service" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `secure throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `secure throws — score is 0, confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `property throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `both throw — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true, propertyThrows = true))
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `splitServiceList parses colon-separated`() {
        val result = AccessibilityServicesProbe.splitServiceList("a/.A:b/.B:c/.C")
        assertEquals(listOf("a/.A", "b/.B", "c/.C"), result)
    }

    @Test
    fun `splitServiceList filters empty segments`() {
        // Trailing colon or double colon would produce empty segments;
        // filter them out.
        val result = AccessibilityServicesProbe.splitServiceList("a/.A::b/.B:")
        assertEquals(listOf("a/.A", "b/.B"), result)
    }

    @Test
    fun `splitServiceList returns empty for null and empty`() {
        assertEquals(emptyList<String>(), AccessibilityServicesProbe.splitServiceList(null))
        assertEquals(emptyList<String>(), AccessibilityServicesProbe.splitServiceList(""))
    }

    @Test
    fun `splitServiceList single service`() {
        val result = AccessibilityServicesProbe.splitServiceList("com.example/.Service")
        assertEquals(listOf("com.example/.Service"), result)
    }

    @Test
    fun `hasSuspiciousAccessibilityService detects all listed markers`() {
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.android.uiautomator/.S"
            )
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService("mods.autoui/.S")
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "org.autojs.autojs/.S"
            )
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService("com.touchtask/.S")
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService("com.airwatch/.S")
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService("com.zdmotion/.S")
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.example.automation/.S"
            )
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.example.screen.recorder/.S"
            )
        )
        assertTrue(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.example.botaccessibility/.S"
            )
        )
    }

    @Test
    fun `hasSuspiciousAccessibilityService false for TalkBack`() {
        assertFalse(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.google.android.marvin.talkback/.TalkBackService"
            )
        )
    }

    @Test
    fun `hasSuspiciousAccessibilityService false for Voice Access`() {
        assertFalse(
            AccessibilityServicesProbe.hasSuspiciousAccessibilityService(
                "com.google.android.apps.accessibility.voiceaccess/.VoiceAccessService"
            )
        )
    }

    @Test
    fun `hasSuspiciousAccessibilityService false for empty and null`() {
        assertFalse(AccessibilityServicesProbe.hasSuspiciousAccessibilityService(null))
        assertFalse(AccessibilityServicesProbe.hasSuspiciousAccessibilityService(""))
    }

    @Test
    fun `isPhoneClassModel delegates to rank-25 NetworkTypeProbe`() {
        // Drift-safe reuse anchor — many-services rule depends on
        // NetworkTypeProbe.isPhoneClassModel correctly identifying
        // Pixel/Galaxy/OnePlus/Redmi as phone-class and excluding
        // tablets. Same anchor as rank-49/52/53/9/7.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
    }

    // ── Drift-alarm + cross-rank invariant ───────────────────────────────────

    @Test
    fun `SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS list pinned`() {
        val list = AccessibilityServicesProbe.SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS
        assertTrue("com.android.uiautomator" in list)
        assertTrue("mods.autoui" in list)
        // `org.autojs.` (with trailing dot) covers both `org.autojs.autojs`
        // and `org.autojs.autoxjs.v6` sibling packages from the same author.
        assertTrue("org.autojs." in list)
        assertTrue("com.touchtask" in list)
        assertTrue("com.airwatch" in list)
        assertTrue("com.zdmotion" in list)
        assertTrue("automation" in list)
        assertTrue("screen.recorder" in list)
        assertTrue("botaccessibility" in list)
    }

    @Test
    fun `rank-59 substring list extends rank-10 GROUP_D_AUTOMATION`() {
        // Drift-alarm + cross-rank reuse anchor. Rank-10 GROUP_D
        // packages should all appear as substrings in rank-59's list
        // (same hostile-tool family, different surface). If rank-10
        // adds or removes an automation package, this test surfaces
        // the divergence.
        val rank10 = InstalledAppsProbe.GROUP_D_AUTOMATION
        val rank59 = AccessibilityServicesProbe.SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS

        // All rank-10 GROUP_D entries should be covered by a rank-59
        // substring (either exact or generic prefix match).
        // org.autojs.autoxjs.v6 in rank-10 is covered by `org.autojs.autojs`
        // substring in rank-59 (shared prefix).
        for (pkg in rank10) {
            assertTrue(
                rank59.any { it in pkg.lowercase() } || rank59.any { pkg.lowercase() in it },
                "rank-10 package $pkg has no corresponding rank-59 substring",
            )
        }
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "secure.enabled_accessibility_services",
                "accessibility.service_count",
                "accessibility.has_suspicious_service",
                "accessibility.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `services list evidence reflects accessor`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.google.android.marvin.talkback/.TalkBackService")
        )
        val ev = result.evidence.find { it.key == "secure.enabled_accessibility_services" }
        assertEquals("com.google.android.marvin.talkback/.TalkBackService", ev?.value)
    }

    @Test
    fun `service_count evidence is 0 for empty list`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "accessibility.service_count" }
        assertEquals("0", ev?.value)
    }

    @Test
    fun `service_count evidence is 2 for 2 services`() = runBlocking {
        val result = probe.run(
            fakeCtx(enabledServices = "com.svc1/.S:com.svc2/.S")
        )
        val ev = result.evidence.find { it.key == "accessibility.service_count" }
        assertEquals("2", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Settings.Secure.enabled_accessibility_services and detect " +
                "suspicious automation/RAT accessibility services or many-" +
                "services-enabled on phone-class devices",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_accessibility_services`() {
        assertEquals("env.accessibility_services", probe.id)
    }

    @Test
    fun `probe rank is 59`() {
        assertEquals(59, probe.rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, probe.category)
    }

    @Test
    fun `probe severity is TRACE`() {
        assertEquals(ProbeSeverity.TRACE, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
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

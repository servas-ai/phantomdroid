package com.detectorlab.probes.runtime

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
 * Unit tests for AutomationToolsProbe (CLO-7, A17 N9).
 *
 * Covers the acceptance criteria:
 *   (a) Appium accessibility service active → high-confidence detection
 *   (b) UIAutomator package installed → moderate detection signal
 *   (c) adb_enabled=1 + port-5555 ESTABLISHED → strong combined signal
 *   (d) adb_enabled=1 alone (no active connection) → weak signal
 *   (e) All signals absent → score 0.0, confidence ≥ 0.85 (clean, strong)
 *   (f) Matcher functions usable independently (droidrun harness contract)
 *   (g) Probe metadata invariants
 */
class AutomationToolsProbeTest {

    private val probe = AutomationToolsProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        accessibilityServices: String? = null,
        adbEnabled: String? = null,
        installedPackages: Set<String> = emptySet(),
        procNetTcp: String? = null,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? =
            if (path == AutomationToolsProbe.PROC_NET_TCP) procNetTcp else null
        override fun querySettingSecure(key: String): String? = when (key) {
            "enabled_accessibility_services" -> accessibilityServices
            "adb_enabled" -> adbEnabled
            else -> null
        }
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = packageName in installedPackages
            override fun listInstalledPackages() = installedPackages.toList()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── (a) Appium accessibility service active ───────────────────────────────

    @Test
    fun `Appium service in accessibility settings emits high score and strong confidence`() = runBlocking {
        val ctx = fakeCtx(
            accessibilityServices = "io.appium.uiautomator2.server/io.appium.uiautomator2.server.AppiumUiAutomator2Server",
        )
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(result.score >= 0.85, "expected score >= 0.85, got ${result.score}")
        assertTrue(result.confidence >= 0.85, "expected strong confidence, got ${result.confidence}")
        assertTrue(result.evidence.any { it.key == "appium.accessibility_service_enabled" && it.value == true })
    }

    // ── (b) UIAutomator package installed ────────────────────────────────────

    @Test
    fun `UIAutomator stub package installed emits moderate signal`() = runBlocking {
        val ctx = fakeCtx(installedPackages = setOf("com.github.uiautomator"))
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(result.score > 0.0, "expected nonzero score")
        assertTrue(result.evidence.any { it.key == "uiautomator.package_installed" && it.value == true })
    }

    @Test
    fun `UIAutomator test companion package also triggers detection`() = runBlocking {
        val ctx = fakeCtx(installedPackages = setOf("com.github.uiautomator.test"))
        val result = probe.run(ctx)
        assertTrue(result.score > 0.0)
        assertTrue(result.evidence.any { it.key == "uiautomator.package_installed" && it.value == true })
    }

    // ── (c) adb_enabled=1 + port-5555 ESTABLISHED ────────────────────────────

    private val procNetTcpWithPort5555 = """
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:0035 00000000:0000 0A 00000000:00000000 00:00000000 00000000   101        0 1234 1 0000000000000000 100 0 0 10 0
   1: 0F02000A:15B3 0F02000A:D0A6 01 00000000:00000000 00:00000000 00000000     0        0 5678 1 0000000000000000 20 4 24 10 -1
""".trimIndent()

    @Test
    fun `adb_enabled=1 with port-5555 ESTABLISHED emits strong combined signal`() = runBlocking {
        val ctx = fakeCtx(
            adbEnabled = "1",
            procNetTcp = procNetTcpWithPort5555,
        )
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(result.score >= 0.60, "expected score >= 0.60, got ${result.score}")
        assertTrue(result.confidence >= 0.85)
        assertTrue(result.evidence.any { it.key == "adb_shell_active" && it.value == true })
        assertTrue(result.evidence.any { it.key == "proc_net_tcp.port_5555_established" && it.value == true })
    }

    // ── (d) adb_enabled alone (no active connection) ─────────────────────────

    @Test
    fun `adb_enabled=1 without active port-5555 connection emits weak signal`() = runBlocking {
        val ctx = fakeCtx(adbEnabled = "1", procNetTcp = "")
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(result.score > 0.0, "expected nonzero score for adb_enabled")
        assertTrue(result.score < 0.50, "expected weak score (< 0.50), got ${result.score}")
        assertTrue(result.confidence <= 0.50, "expected weak/medium confidence, got ${result.confidence}")
        assertTrue(result.evidence.any { it.key == "settings.adb_enabled" && it.value == true })
        assertTrue(result.evidence.any { it.key == "adb_shell_active" && it.value == false })
    }

    // ── (e) All signals absent → clean result ────────────────────────────────

    @Test
    fun `all signals absent emits score 0_0 with strong confidence`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        assertTrue(result.confidence >= 0.85, "expected strong confidence, got ${result.confidence}")
        assertTrue(result.evidence.any { it.key == "appium.accessibility_service_enabled" && it.value == false })
        assertTrue(result.evidence.any { it.key == "uiautomator.package_installed" && it.value == false })
        assertTrue(result.evidence.any { it.key == "adb_shell_active" && it.value == false })
    }

    // ── (f) Matcher functions work independently (droidrun harness contract) ──

    @Test
    fun `isAppiumAccessibilityServiceEnabled returns true when pkg in setting`() {
        val ctx = fakeCtx(accessibilityServices = "io.appium.uiautomator2.server/SomeClass")
        assertTrue(isAppiumAccessibilityServiceEnabled(ctx))
    }

    @Test
    fun `isAppiumAccessibilityServiceEnabled returns false when setting absent`() {
        assertFalse(isAppiumAccessibilityServiceEnabled(fakeCtx()))
    }

    @Test
    fun `isUiAutomatorInstalled returns true for stub package`() {
        val pm = fakeCtx(installedPackages = setOf("com.github.uiautomator")).queryPackageManager()
        assertTrue(isUiAutomatorInstalled(pm))
    }

    @Test
    fun `isAdbEnabled returns true when setting is 1`() {
        assertTrue(isAdbEnabled(fakeCtx(adbEnabled = "1")))
    }

    @Test
    fun `isAdbEnabled returns false when setting is 0 or absent`() {
        assertFalse(isAdbEnabled(fakeCtx(adbEnabled = "0")))
        assertFalse(isAdbEnabled(fakeCtx()))
    }

    @Test
    fun `isPort5555Active returns true for ESTABLISHED entry with port 15B3`() {
        assertTrue(isPort5555Active(fakeCtx(procNetTcp = procNetTcpWithPort5555)))
    }

    @Test
    fun `isPort5555Active returns false when proc_net_tcp absent`() {
        assertFalse(isPort5555Active(fakeCtx(procNetTcp = null)))
    }

    @Test
    fun `isPort5555Active returns false for non-ESTABLISHED entry on port 5555`() {
        // state 06 = TIME_WAIT
        val tcpTimewait = "   0: 0F02000A:15B3 0F02000A:D0A6 06 00000000:00000000 00:00000000 00000000     0        0 5678 1"
        assertFalse(isPort5555Active(fakeCtx(procNetTcp = tcpTimewait)))
    }

    // ── (g) Probe metadata invariants ────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("runtime.automation_tools", probe.id)
    }

    @Test
    fun `probe rank is in A17 expansion range`() {
        assertTrue(probe.rank in 61..71, "rank ${probe.rank} outside A17 reservation 61..71")
    }

    @Test
    fun `probe budget is within 5-second hard ceiling`() {
        assertTrue(probe.budgetMs <= 5000L)
    }

    @Test
    fun `probe runtime fits within budget on fast path`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }

    @Test
    fun `evidence set always contains all five expected keys`() = runBlocking {
        val result = probe.run(fakeCtx(adbEnabled = "1"))
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("appium.accessibility_service_enabled" in keys)
        assertTrue("uiautomator.package_installed" in keys)
        assertTrue("settings.adb_enabled" in keys)
        assertTrue("proc_net_tcp.port_5555_established" in keys)
        assertTrue("adb_shell_active" in keys)
    }

    // ── Power-13 Gap #13 — overlay/capture/control categorization ────────────

    @Test
    fun `overlay package present — score gains 0_30`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.facebook.lite")))
        assertTrue(
            result.score >= 0.29 && result.score < 0.40,
            "expected score in [0.29, 0.40) for overlay-only, got ${result.score}",
        )
    }

    @Test
    fun `overlay present — overlay_present evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.facebook.lite")))
        val ev = result.evidence.find { it.key == "automation_tools.overlay_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `overlay present — overlay_hits lists package verbatim`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.facebook.lite")))
        val ev = result.evidence.find { it.key == "automation_tools.overlay_hits" }
        assertEquals("com.facebook.lite", ev?.value)
    }

    @Test
    fun `capture package present — score gains 0_25`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.duapps.recorder")))
        assertTrue(
            result.score >= 0.24 && result.score < 0.35,
            "expected score in [0.24, 0.35) for capture-only, got ${result.score}",
        )
    }

    @Test
    fun `capture present — capture_present evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.duapps.recorder")))
        val ev = result.evidence.find { it.key == "automation_tools.capture_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `control package present — score gains 0_40`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.teamviewer.host")))
        assertTrue(
            result.score >= 0.39 && result.score < 0.50,
            "expected score in [0.39, 0.50) for control-only, got ${result.score}",
        )
    }

    @Test
    fun `control present — control_present evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.teamviewer.host")))
        val ev = result.evidence.find { it.key == "automation_tools.control_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `AnyDesk control — fires control category`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.anydesk.anydeskandroid")))
        val ev = result.evidence.find { it.key == "automation_tools.control_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `AirDroid control — fires control category`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.sand.airdroid")))
        val ev = result.evidence.find { it.key == "automation_tools.control_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `Vysor control — fires control category`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = setOf("com.koushikdutta.vysor")))
        val ev = result.evidence.find { it.key == "automation_tools.control_present" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `all three Power-13 Gap #13 categories — score caps at 0_95 additive`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf(
                "com.facebook.lite",        // overlay 0.30
                "com.duapps.recorder",      // capture 0.25
                "com.teamviewer.host",      // control 0.40
            )),
        )
        // 0.30 + 0.25 + 0.40 = 0.95
        assertTrue(
            result.score >= 0.94 && result.score <= 0.96,
            "expected ~0.95 additive score, got ${result.score}",
        )
    }

    @Test
    fun `clean — all 3 new evidence rows reflect absent state`() = runBlocking {
        val result = probe.run(fakeCtx())
        val overlay = result.evidence.find { it.key == "automation_tools.overlay_present" }
        val capture = result.evidence.find { it.key == "automation_tools.capture_present" }
        val control = result.evidence.find { it.key == "automation_tools.control_present" }
        assertEquals(false, overlay?.value)
        assertEquals(false, capture?.value)
        assertEquals(false, control?.value)
    }

    @Test
    fun `clean — overlay_hits capture_hits control_hits all report none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val overlayHits = result.evidence.find { it.key == "automation_tools.overlay_hits" }
        val captureHits = result.evidence.find { it.key == "automation_tools.capture_hits" }
        val controlHits = result.evidence.find { it.key == "automation_tools.control_hits" }
        assertEquals("none", overlayHits?.value)
        assertEquals("none", captureHits?.value)
        assertEquals("none", controlHits?.value)
    }

    @Test
    fun `evidence set contains 6 new Power-13 Gap 13 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue("automation_tools.overlay_present" in keys)
        assertTrue("automation_tools.overlay_hits" in keys)
        assertTrue("automation_tools.capture_present" in keys)
        assertTrue("automation_tools.capture_hits" in keys)
        assertTrue("automation_tools.control_present" in keys)
        assertTrue("automation_tools.control_hits" in keys)
    }

    @Test
    fun `multiple control packages — hits lists comma-joined`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf(
                "com.teamviewer.host",
                "com.anydesk.anydeskandroid",
            )),
        )
        val ev = result.evidence.find { it.key == "automation_tools.control_hits" }
        val hits = ev?.value?.toString()?.split(",")?.toSet() ?: emptySet()
        assertEquals(
            setOf("com.teamviewer.host", "com.anydesk.anydeskandroid"),
            hits,
        )
    }

    // ── findInstalledFromList helper ─────────────────────────────────────────

    @Test
    fun `findInstalledFromList returns subset that is installed`() {
        val pm = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) =
                packageName == "com.facebook.lite"
            override fun listInstalledPackages() = listOf("com.facebook.lite")
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        val hits = findInstalledFromList(
            pm,
            listOf("com.facebook.lite", "com.zedge.android"),
        )
        assertEquals(listOf("com.facebook.lite"), hits)
    }

    @Test
    fun `findInstalledFromList tolerates per-package throws`() {
        val pm = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String): Boolean {
                if (packageName == "com.facebook.lite") throw RuntimeException("simulated")
                return packageName == "com.teamviewer.host"
            }
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        val hits = findInstalledFromList(
            pm,
            listOf("com.facebook.lite", "com.teamviewer.host"),
        )
        // The throwing call is silently skipped; the second one
        // succeeds.
        assertEquals(listOf("com.teamviewer.host"), hits)
    }

    // ── Power-13 Gap #13 list-size invariants ────────────────────────────────

    @Test
    fun `KNOWN_OVERLAY_PACKAGES has at least 5 entries`() {
        assertTrue(AutomationToolsProbe.KNOWN_OVERLAY_PACKAGES.size >= 5)
    }

    @Test
    fun `KNOWN_CAPTURE_PACKAGES has at least 5 entries`() {
        assertTrue(AutomationToolsProbe.KNOWN_CAPTURE_PACKAGES.size >= 5)
    }

    @Test
    fun `KNOWN_CONTROL_PACKAGES has at least 5 entries`() {
        assertTrue(AutomationToolsProbe.KNOWN_CONTROL_PACKAGES.size >= 5)
    }

    @Test
    fun `Power-13 Gap 13 lists are disjoint`() {
        val o = AutomationToolsProbe.KNOWN_OVERLAY_PACKAGES.toSet()
        val c = AutomationToolsProbe.KNOWN_CAPTURE_PACKAGES.toSet()
        val r = AutomationToolsProbe.KNOWN_CONTROL_PACKAGES.toSet()
        assertTrue((o intersect c).isEmpty())
        assertTrue((o intersect r).isEmpty())
        assertTrue((c intersect r).isEmpty())
    }
}

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
}

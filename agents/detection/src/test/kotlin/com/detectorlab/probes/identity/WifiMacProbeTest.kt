package com.detectorlab.probes.identity

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
import kotlin.test.assertTrue

/**
 * Unit tests for WifiMacProbe (identity.wifi_mac, inventory rank 15).
 */
class WifiMacProbeTest {

    private val probe = WifiMacProbe()

    private fun fakeCtx(
        wlan0Content: String? = "84:c5:a6:12:34:56",
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated sysfs EACCES")
            return if (path == WifiMacProbe.PATH_WLAN0_ADDRESS) wlan0Content else null
        }
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
    }

    // ── Real OEM MAC (score 0.0) ──────────────────────────────────────────────

    @Test
    fun `Pixel-style real OUI mac — score is 0_0`() = runBlocking {
        // 84:c5:a6 is a real Pixel-era OUI. First byte 0x84 has bit-0=0
        // (unicast) and bit-1=0 (globally administered).
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real OUI mac — confidence caps at 0_60 (single-surface)`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `trailing newline in sysfs read — still parsed correctly`() = runBlocking {
        // /sys/class/net/wlan0/address returns "mac\n" on real kernels.
        // The normaliseMac helper trims before regex-matching.
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56\n"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `uppercase mac from sysfs — normalised to lowercase`() = runBlocking {
        val result = probe.run(fakeCtx("84:C5:A6:12:34:56"))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "wifi_mac.sysfs" }
        assertEquals("84:c5:a6:12:34:56", ev?.value)
    }

    // ── Zero MAC (score 1.0) ──────────────────────────────────────────────────

    @Test
    fun `zero MAC — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("00:00:00:00:00:00"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero MAC — evidence reflects the value`() = runBlocking {
        val result = probe.run(fakeCtx("00:00:00:00:00:00"))
        val ev = result.evidence.find { it.key == "wifi_mac.sysfs" }
        assertEquals("00:00:00:00:00:00", ev?.value)
    }

    // ── QEMU / VirtualBox OUIs (score 1.0) ────────────────────────────────────

    @Test
    fun `QEMU OUI 52_54_00 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("52:54:00:12:34:56"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `QEMU OUI — emulator_oui_match evidence is qemu`() = runBlocking {
        val result = probe.run(fakeCtx("52:54:00:12:34:56"))
        val ev = result.evidence.find { it.key == "wifi_mac.emulator_oui_match" }
        assertEquals(WifiMacProbe.EMU_TAG_QEMU, ev?.value)
    }

    @Test
    fun `VirtualBox OUI 08_00_27 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx("08:00:27:12:34:56"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `VirtualBox OUI — emulator_oui_match evidence is vbox`() = runBlocking {
        val result = probe.run(fakeCtx("08:00:27:12:34:56"))
        val ev = result.evidence.find { it.key == "wifi_mac.emulator_oui_match" }
        assertEquals(WifiMacProbe.EMU_TAG_VBOX, ev?.value)
    }

    // ── VMware / Hyper-V / Xen / Docker (score 0.95) ──────────────────────────

    @Test
    fun `VMware OUI 00_0c_29 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("00:0c:29:12:34:56"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `VMware OUI 00_50_56 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("00:50:56:12:34:56"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Hyper-V OUI 00_15_5d — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("00:15:5d:12:34:56"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Xen OUI 00_16_3e — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("00:16:3e:12:34:56"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Docker OUI 0a_00_27 — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx("0a:00:27:12:34:56"))
        assertEquals(0.95, result.score)
    }

    // ── Android 10+ privacy default (score 0.85 via sysfs) ───────────────────

    @Test
    fun `sysfs returns 02_00_00_00_00_00 — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx("02:00:00:00:00:00"))
        assertEquals(0.85, result.score)
    }

    // ── Locally-administered bit (score 0.8) ──────────────────────────────────

    @Test
    fun `locally-administered MAC not in emulator list — score is 0_80`() = runBlocking {
        // 0a:11:22:33:44:55 — first byte 0x0a has bit-1=1 (locally administered)
        // but the OUI 0a:11:22 isn't in any emulator vendor set (Docker is
        // 0a:00:27 specifically). Falls through to the locally-administered
        // catch-all at 0.80.
        val result = probe.run(fakeCtx("0a:11:22:33:44:55"))
        assertEquals(0.80, result.score)
    }

    @Test
    fun `locally-administered — evidence flag is true`() = runBlocking {
        val result = probe.run(fakeCtx("0a:11:22:33:44:55"))
        val ev = result.evidence.find { it.key == "wifi_mac.locally_administered" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `globally-administered real OUI — evidence flag is false`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        val ev = result.evidence.find { it.key == "wifi_mac.locally_administered" }
        assertEquals("false", ev?.value)
    }

    // ── Sysfs unreadable / malformed (score 0.5) ──────────────────────────────

    @Test
    fun `sysfs returns null — score is 0_5 no_signal`() = runBlocking {
        val result = probe.run(fakeCtx(wlan0Content = null))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `sysfs returns null — confidence is 0_30 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(wlan0Content = null))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `sysfs throws — score is 0_5 no_signal`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `sysfs returns malformed (not a MAC) — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx("not-a-mac"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `sysfs returns 5-byte string — score is 0_5 (malformed)`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `sysfs returns 7-byte string — score is 0_5 (malformed)`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56:78"))
        assertEquals(0.5, result.score)
    }

    // ── Multi-signal precedence (max wins) ────────────────────────────────────

    @Test
    fun `zero MAC plus QEMU OUI substring — score is 1_0 (multiple 1_0 rules collapse)`() = runBlocking {
        val result = probe.run(fakeCtx("00:00:00:00:00:00"))
        // OUI 00:00:00 isn't in the emulator list; only the zero-MAC rule fires.
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "wifi_mac.emulator_oui_match" }
        assertEquals(WifiMacProbe.EMU_TAG_NONE, ev?.value)
    }

    @Test
    fun `QEMU OUI dominates locally-administered fallback`() = runBlocking {
        // 52:54:00 has bit-1=0 (52 hex = 0b01010010 — bit 1 is 1, actually).
        // Verify: 0x52 = 82 decimal = 0b01010010. bit-1 (value 2) is set.
        // So 52:54:00 IS locally administered. But the QEMU OUI rule fires
        // FIRST (score 1.0), and max-wins resolves over the 0.80 fallback.
        val result = probe.run(fakeCtx("52:54:00:12:34:56"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Docker OUI dominates locally-administered fallback`() = runBlocking {
        // 0a:00:27 first byte 0x0a — locally administered; OUI rule fires at 0.95.
        val result = probe.run(fakeCtx("0a:00:27:12:34:56"))
        assertEquals(0.95, result.score)
    }

    // ── Multicast bit ─────────────────────────────────────────────────────────

    @Test
    fun `multicast MAC — multicast_bit evidence is true`() = runBlocking {
        // 01:00:00:... has bit-0 (value 0x01) set → multicast.
        val result = probe.run(fakeCtx("01:00:00:00:00:00"))
        val ev = result.evidence.find { it.key == "wifi_mac.multicast_bit" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `unicast MAC — multicast_bit evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        val ev = result.evidence.find { it.key == "wifi_mac.multicast_bit" }
        assertEquals("false", ev?.value)
    }

    // ── WifiManager-gap regression ────────────────────────────────────────────

    @Test
    fun `wifimanager evidence row carries unavailable placeholder`() = runBlocking {
        // Regression guard for the documented gap: WifiManagerView doesn't
        // expose a MAC accessor. When that accessor lands, this assertion
        // changes — the placeholder marks the work-to-do.
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi_mac.wifimanager" }
        assertTrue(
            (ev?.value as String).contains("unavailable"),
            "expected unavailable placeholder, got ${ev.value}",
        )
    }

    @Test
    fun `confidence is capped at 0_60 by single-surface constraint`() = runBlocking {
        // Even on the cleanest possible read, confidence can never reach
        // 0.95 because only one of the two intended surfaces is reachable.
        // When WifiManagerView gains the macAddress accessor, lift this
        // assertion to 0.95.
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        assertEquals(0.60, result.confidence)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all six documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "wifi_mac.wifimanager",
                "wifi_mac.sysfs",
                "wifi_mac.oui_first_byte",
                "wifi_mac.locally_administered",
                "wifi_mac.multicast_bit",
                "wifi_mac.emulator_oui_match",
            ),
            keys,
        )
    }

    @Test
    fun `oui_first_byte evidence is hex-formatted`() = runBlocking {
        val result = probe.run(fakeCtx("84:c5:a6:12:34:56"))
        val ev = result.evidence.find { it.key == "wifi_mac.oui_first_byte" }
        assertEquals("0x84", ev?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read WifiManager.connectionInfo.macAddress + /sys/class/net/wlan0/address, " +
                "validate OUI prefix against emulator-vendor allowlist, " +
                "check locally-administered bit",
            result.method,
        )
    }

    // ── Helper unit tests ─────────────────────────────────────────────────────

    @Test
    fun `normalizeMac trims and lowercases valid input`() {
        assertEquals("84:c5:a6:12:34:56", WifiMacProbe.normalizeMac("  84:C5:A6:12:34:56\n"))
    }

    @Test
    fun `normalizeMac returns null for malformed`() {
        assertEquals(null, WifiMacProbe.normalizeMac(null))
        assertEquals(null, WifiMacProbe.normalizeMac(""))
        assertEquals(null, WifiMacProbe.normalizeMac("not-a-mac"))
        assertEquals(null, WifiMacProbe.normalizeMac("84:c5:a6:12:34"))
        assertEquals(null, WifiMacProbe.normalizeMac("84-c5-a6-12-34-56"))
    }

    @Test
    fun `firstByte extracts the first octet as hex`() {
        assertEquals(0x84, WifiMacProbe.firstByte("84:c5:a6:12:34:56"))
        assertEquals(0x00, WifiMacProbe.firstByte("00:00:00:00:00:00"))
        assertEquals(0xff, WifiMacProbe.firstByte("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `oui returns first three octets joined by colons`() {
        assertEquals("84:c5:a6", WifiMacProbe.oui("84:c5:a6:12:34:56"))
        assertEquals("52:54:00", WifiMacProbe.oui("52:54:00:12:34:56"))
    }

    @Test
    fun `classifyOui maps known vendor OUIs to their tags`() {
        assertEquals(WifiMacProbe.EMU_TAG_QEMU, WifiMacProbe.classifyOui("52:54:00:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_VBOX, WifiMacProbe.classifyOui("08:00:27:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_VMWARE, WifiMacProbe.classifyOui("00:50:56:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_HYPERV, WifiMacProbe.classifyOui("00:15:5d:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_XEN, WifiMacProbe.classifyOui("00:16:3e:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_DOCKER, WifiMacProbe.classifyOui("0a:00:27:12:34:56"))
        assertEquals(WifiMacProbe.EMU_TAG_NONE, WifiMacProbe.classifyOui("84:c5:a6:12:34:56"))
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_wifi_mac`() {
        assertEquals("identity.wifi_mac", probe.id)
    }

    @Test
    fun `probe rank is 15`() {
        assertEquals(15, probe.rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-15 severity=high.
        assertEquals(ProbeSeverity.HIGH, probe.severity)
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

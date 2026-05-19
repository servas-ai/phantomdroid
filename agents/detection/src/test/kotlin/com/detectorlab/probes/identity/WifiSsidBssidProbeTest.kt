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
 * Unit tests for WifiSsidBssidProbe (identity.wifi_ssid_bssid, inventory rank 32).
 */
class WifiSsidBssidProbeTest {

    private fun makeProbe(
        ssid: String? = "\"HomeWifi\"",
        bssid: String? = "84:c5:a6:11:22:33",
        connectionState: String? = "connected",
    ): WifiSsidBssidProbe = WifiSsidBssidProbe(
        ssidSupplier = { ssid },
        bssidSupplier = { bssid },
        connectionStateSupplier = { connectionState },
    )

    private fun fakeCtx(): ProbeContext = object : ProbeContext {
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
    }

    // ── Real wifi (score 0.0) ────────────────────────────────────────────────

    @Test
    fun `real Wi-Fi SSID + real OUI BSSID — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95 (both surfaces readable)`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── AndroidWifi emulator SSID (1.0) ───────────────────────────────────────

    @Test
    fun `AndroidWifi SSID — score is 1_0`() = runBlocking {
        val result = makeProbe(ssid = "AndroidWifi").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AndroidWifi quoted SSID — score is 1_0`() = runBlocking {
        // WifiInfo wraps SSID in quotes by default. The normaliser strips them.
        val result = makeProbe(ssid = "\"AndroidWifi\"").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `AndroidWifi SSID — pattern is emu_ssid_androidwifi`() = runBlocking {
        val result = makeProbe(ssid = "AndroidWifi").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_SSID_ANDROID, ev?.value)
    }

    @Test
    fun `AndroidWifi — is_emulator_ssid evidence is true`() = runBlocking {
        val result = makeProbe(ssid = "AndroidWifi").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.is_emulator_ssid" }
        assertEquals("true", ev?.value)
    }

    // ── Genymotion emulator SSID (1.0) ────────────────────────────────────────

    @Test
    fun `Genymotion SSID — score is 1_0`() = runBlocking {
        val result = makeProbe(ssid = "Genymotion").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion quoted SSID — score is 1_0`() = runBlocking {
        val result = makeProbe(ssid = "\"Genymotion\"").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymotion SSID — pattern is emu_ssid_genymotion`() = runBlocking {
        val result = makeProbe(ssid = "Genymotion").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_SSID_GENYMOTION, ev?.value)
    }

    // ── BSSID OUI matches (rank-15 cross-reference) ──────────────────────────

    @Test
    fun `QEMU OUI BSSID — score is 1_0`() = runBlocking {
        val result = makeProbe(bssid = "52:54:00:12:34:56").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `VirtualBox OUI BSSID — score is 1_0`() = runBlocking {
        val result = makeProbe(bssid = "08:00:27:12:34:56").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `VMware OUI BSSID — score is 0_95`() = runBlocking {
        val result = makeProbe(bssid = "00:0c:29:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Hyper-V OUI BSSID — score is 0_95`() = runBlocking {
        val result = makeProbe(bssid = "00:15:5d:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Xen OUI BSSID — score is 0_95`() = runBlocking {
        val result = makeProbe(bssid = "00:16:3e:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Docker OUI BSSID — score is 0_95`() = runBlocking {
        val result = makeProbe(bssid = "0a:00:27:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `QEMU OUI — pattern is emu_oui_qemu_vbox`() = runBlocking {
        val result = makeProbe(bssid = "52:54:00:12:34:56").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_OUI_QEMU_VBOX, ev?.value)
    }

    @Test
    fun `VMware OUI — pattern is emu_oui_other_vm`() = runBlocking {
        val result = makeProbe(bssid = "00:0c:29:12:34:56").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_OUI_OTHER_VM, ev?.value)
    }

    // ── BSSID all-zero (1.0) ─────────────────────────────────────────────────

    @Test
    fun `BSSID all-zero — score is 1_0`() = runBlocking {
        val result = makeProbe(bssid = "00:00:00:00:00:00").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `BSSID all-zero — pattern is bssid_zero`() = runBlocking {
        val result = makeProbe(bssid = "00:00:00:00:00:00").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_BSSID_ZERO, ev?.value)
    }

    // ── Empty SSID while connected (0.85) ────────────────────────────────────

    @Test
    fun `empty SSID with connectionState connected — score is 0_85`() = runBlocking {
        val result = makeProbe(ssid = "", connectionState = "connected").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `null SSID with connectionState connected — score is 0_85`() = runBlocking {
        val result = makeProbe(ssid = null, connectionState = "connected").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `quoted-empty SSID with connectionState connected — score is 0_85`() = runBlocking {
        // WifiInfo returns "" wrapped in quotes when SSID is empty.
        val result = makeProbe(ssid = "\"\"", connectionState = "connected").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty SSID disconnected — score is 0 (no contradiction)`() = runBlocking {
        // Real disconnected device legitimately has no SSID.
        val result = makeProbe(ssid = "", connectionState = "disconnected").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty SSID connected — pattern is empty_ssid_connected`() = runBlocking {
        val result = makeProbe(ssid = "", connectionState = "connected").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMPTY_SSID_CONNECTED, ev?.value)
    }

    // ── Privacy default + unknown SSID (0.5 benign) ──────────────────────────

    @Test
    fun `privacy-default BSSID and unknown-ssid literal — score is 0_5`() = runBlocking {
        val result = makeProbe(
            ssid = "<unknown ssid>",
            bssid = "02:00:00:00:00:00",
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `privacy-default BSSID only (real SSID) — score is 0`() = runBlocking {
        // The 02:... privacy default alone is not enough — real SSID dominates.
        val result = makeProbe(
            ssid = "\"HomeWifi\"",
            bssid = "02:00:00:00:00:00",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `unknown-ssid literal only (real BSSID) — score is 0`() = runBlocking {
        // The Android-8+ privacy-default SSID literal alone is not enough.
        val result = makeProbe(
            ssid = "<unknown ssid>",
            bssid = "84:c5:a6:11:22:33",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `privacy default benign — pattern is privacy_default_benign`() = runBlocking {
        val result = makeProbe(
            ssid = "<unknown ssid>",
            bssid = "02:00:00:00:00:00",
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_PRIVACY_DEFAULT_BENIGN, ev?.value)
    }

    // ── Multi-signal precedence ──────────────────────────────────────────────

    @Test
    fun `AndroidWifi SSID plus QEMU BSSID — AndroidWifi wins at 1_0`() = runBlocking {
        val result = makeProbe(
            ssid = "AndroidWifi",
            bssid = "52:54:00:12:34:56",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_SSID_ANDROID, ev?.value)
    }

    @Test
    fun `Genymotion SSID plus VMware BSSID — Genymotion wins at 1_0`() = runBlocking {
        val result = makeProbe(
            ssid = "Genymotion",
            bssid = "00:0c:29:12:34:56",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_SSID_GENYMOTION, ev?.value)
    }

    @Test
    fun `QEMU BSSID plus empty SSID connected — QEMU wins at 1_0`() = runBlocking {
        val result = makeProbe(
            ssid = "",
            bssid = "52:54:00:12:34:56",
            connectionState = "connected",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `VMware BSSID plus empty SSID connected — VMware wins at 0_95 over 0_85`() = runBlocking {
        // Specific-to-general cascade puts the VMware OUI before the
        // empty-SSID contradiction.
        val result = makeProbe(
            ssid = "",
            bssid = "00:0c:29:12:34:56",
            connectionState = "connected",
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `BSSID zero plus AndroidWifi — AndroidWifi wins at 1_0`() = runBlocking {
        val result = makeProbe(
            ssid = "AndroidWifi",
            bssid = "00:00:00:00:00:00",
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_EMU_SSID_ANDROID, ev?.value)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score is 0 (clean) confidence 0_50`() = runBlocking {
        val result = WifiSsidBssidProbe().run(fakeCtx())
        // Suppliers all return null → no signals fire → clean.
        // Confidence degraded (0.50) — production WifiManagerView lacks SSID/BSSID accessors.
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor — pattern is clean`() = runBlocking {
        val result = WifiSsidBssidProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.pattern" }
        assertEquals(WifiSsidBssidProbe.PATTERN_CLEAN, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both surfaces returned — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only SSID returned — confidence is 0_60`() = runBlocking {
        val result = makeProbe(bssid = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only BSSID returned — confidence is 0_60`() = runBlocking {
        val result = makeProbe(ssid = null, connectionState = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `neither surface returned — confidence is 0_50`() = runBlocking {
        val result = makeProbe(ssid = null, bssid = null, connectionState = null)
            .run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `ssidSupplier throws — does not crash`() = runBlocking {
        val probe = WifiSsidBssidProbe(
            ssidSupplier = { throw RuntimeException("simulated SecurityException") },
            bssidSupplier = { "84:c5:a6:11:22:33" },
            connectionStateSupplier = { "disconnected" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // SSID treated as null; BSSID real; disconnected → clean (no contradiction).
        assertEquals(0.0, result.score)
    }

    @Test
    fun `ssidSupplier throws while connected — empty_ssid_connected fires`() = runBlocking {
        // Confirms swallowed exception is indistinguishable from null SSID:
        // null + connected → contradiction at 0.85.
        val probe = WifiSsidBssidProbe(
            ssidSupplier = { throw RuntimeException("simulated") },
            bssidSupplier = { "84:c5:a6:11:22:33" },
            connectionStateSupplier = { "connected" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `bssidSupplier throws — does not crash`() = runBlocking {
        val probe = WifiSsidBssidProbe(
            ssidSupplier = { "\"HomeWifi\"" },
            bssidSupplier = { throw RuntimeException("simulated") },
            connectionStateSupplier = { "connected" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `connectionStateSupplier throws — does not crash`() = runBlocking {
        val probe = WifiSsidBssidProbe(
            ssidSupplier = { "" },
            bssidSupplier = { "84:c5:a6:11:22:33" },
            connectionStateSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // empty SSID + unknown connection state → no contradiction, falls to clean.
        assertEquals(0.0, result.score)
    }

    // ── SSID normalisation (quote-stripping) ─────────────────────────────────

    @Test
    fun `normalizeSsid strips surrounding quotes`() {
        assertEquals("HomeWifi", WifiSsidBssidProbe.normalizeSsid("\"HomeWifi\""))
    }

    @Test
    fun `normalizeSsid leaves unquoted strings alone`() {
        assertEquals("HomeWifi", WifiSsidBssidProbe.normalizeSsid("HomeWifi"))
    }

    @Test
    fun `normalizeSsid trims whitespace`() {
        assertEquals("HomeWifi", WifiSsidBssidProbe.normalizeSsid("  \"HomeWifi\"  "))
    }

    @Test
    fun `normalizeSsid returns null for empty`() {
        assertEquals(null, WifiSsidBssidProbe.normalizeSsid(""))
    }

    @Test
    fun `normalizeSsid returns null for null`() {
        assertEquals(null, WifiSsidBssidProbe.normalizeSsid(null))
    }

    @Test
    fun `normalizeSsid returns null for quoted-empty`() {
        assertEquals(null, WifiSsidBssidProbe.normalizeSsid("\"\""))
    }

    @Test
    fun `normalizeSsid does not strip a single quote`() {
        // Single leading quote shouldn't be stripped (no matching closing).
        assertEquals("\"HomeWifi", WifiSsidBssidProbe.normalizeSsid("\"HomeWifi"))
    }

    // ── SSID length validation ───────────────────────────────────────────────

    @Test
    fun `isInvalidSsidLength returns false for 32-byte ssid`() {
        val ssid = "x".repeat(32)
        assertFalse(WifiSsidBssidProbe.isInvalidSsidLength(ssid))
    }

    @Test
    fun `isInvalidSsidLength returns true for 33-byte ssid`() {
        val ssid = "x".repeat(33)
        assertTrue(WifiSsidBssidProbe.isInvalidSsidLength(ssid))
    }

    @Test
    fun `isInvalidSsidLength returns true for null`() {
        assertTrue(WifiSsidBssidProbe.isInvalidSsidLength(null))
    }

    @Test
    fun `isInvalidSsidLength returns false for 1-byte ssid`() {
        assertFalse(WifiSsidBssidProbe.isInvalidSsidLength("x"))
    }

    // ── Cross-rank reuse invariants (rank-17 / rank-31 pattern) ──────────────

    @Test
    fun `OUI sets reuse rank-15 WifiMacProbe constants`() {
        // Drift-safe invariant: if rank-15's emulator OUI set changes,
        // this test fails immediately, surfacing the cross-probe coupling.
        assertEquals("52:54:00", WifiMacProbe.OUI_QEMU.first())
        assertEquals("08:00:27", WifiMacProbe.OUI_VBOX.first())
        assertTrue("00:0c:29" in WifiMacProbe.OUI_VMWARE)
        assertEquals("00:15:5d", WifiMacProbe.OUI_HYPERV.first())
        assertEquals("00:16:3e", WifiMacProbe.OUI_XEN.first())
        assertEquals("0a:00:27", WifiMacProbe.OUI_DOCKER.first())
    }

    @Test
    fun `MAC normalizer reuses rank-15 WifiMacProbe helper`() {
        assertEquals("52:54:00:12:34:56", WifiMacProbe.normalizeMac("52:54:00:12:34:56"))
        assertEquals("52:54:00:12:34:56", WifiMacProbe.normalizeMac("52:54:00:12:34:56\n"))
        assertEquals(null, WifiMacProbe.normalizeMac("invalid"))
    }

    // ── Case-insensitive matching ────────────────────────────────────────────

    @Test
    fun `uppercase BSSID — normalised and classified`() = runBlocking {
        val result = makeProbe(bssid = "52:54:00:AB:CD:EF").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `trailing newline in BSSID — trimmed correctly`() = runBlocking {
        val result = makeProbe(bssid = "52:54:00:12:34:56\n").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `connectionState uppercase CONNECTED — recognized`() = runBlocking {
        val result = makeProbe(ssid = "", connectionState = "CONNECTED").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "wifi.ssid",
                "wifi.bssid",
                "wifi.ssid_length",
                "wifi.bssid_oui",
                "wifi.connection_state",
                "wifi.is_emulator_ssid",
                "wifi.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `ssid evidence is normalized (quotes stripped) for real SSID`() = runBlocking {
        val result = makeProbe(ssid = "\"HomeWifi\"").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.ssid" }
        assertEquals("HomeWifi", ev?.value)
    }

    @Test
    fun `null SSID — ssid evidence is null literal`() = runBlocking {
        val result = makeProbe(ssid = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.ssid" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `empty SSID — ssid evidence is empty placeholder`() = runBlocking {
        val result = makeProbe(ssid = "").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.ssid" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `ssid_length is in bytes for ASCII SSID`() = runBlocking {
        val result = makeProbe(ssid = "\"HomeWifi\"").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.ssid_length" }
        assertEquals("8", ev?.value)
    }

    @Test
    fun `ssid_length is unreadable when SSID is null`() = runBlocking {
        val result = makeProbe(ssid = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.ssid_length" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `bssid_oui evidence shows colon-joined first three octets`() = runBlocking {
        val result = makeProbe(bssid = "84:c5:a6:11:22:33").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.bssid_oui" }
        assertEquals("84:c5:a6", ev?.value)
    }

    @Test
    fun `bssid_oui evidence is unreadable when BSSID is null`() = runBlocking {
        val result = makeProbe(bssid = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.bssid_oui" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `connection_state evidence reflects normalized value`() = runBlocking {
        val result = makeProbe(connectionState = "CONNECTED").run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.connection_state" }
        assertEquals("connected", ev?.value)
    }

    @Test
    fun `connection_state null — evidence is null literal`() = runBlocking {
        val result = makeProbe(connectionState = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.connection_state" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `clean — is_emulator_ssid evidence is false`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "wifi.is_emulator_ssid" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read WifiManager.connectionInfo SSID + BSSID; detect emulator-default " +
                "SSIDs (AndroidWifi/Genymotion) + cross-reference rank-15 OUI table " +
                "for BSSID prefix",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_wifi_ssid_bssid`() {
        assertEquals("identity.wifi_ssid_bssid", makeProbe().id)
    }

    @Test
    fun `probe rank is 32`() {
        assertEquals(32, makeProbe().rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-32 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 150ms`() {
        assertEquals(150L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

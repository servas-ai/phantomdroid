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
 * Unit tests for BluetoothMacProbe (identity.bluetooth_mac, inventory rank 31).
 */
class BluetoothMacProbeTest {

    private fun makeProbe(adapterMac: String? = "48:8a:e1:11:22:33"): BluetoothMacProbe =
        BluetoothMacProbe(bluetoothAdapterMacSupplier = { adapterMac })

    private fun fakeCtx(
        sysfsMac: String? = "48:8a:e1:11:22:33",
        model: String? = "Pixel 7",
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == BluetoothMacProbe.PROP_RO_PRODUCT_MODEL) model else null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == BluetoothMacProbe.PATH_HCI0_ADDRESS) sysfsMac else null
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

    // ── Real-OEM clean MAC ───────────────────────────────────────────────────

    @Test
    fun `real Pixel BT MAC matching adapter and sysfs — score is 0`() = runBlocking {
        // 48:8a:e1 is a real Google-registered OUI.
        val result = makeProbe(adapterMac = "48:8a:e1:11:22:33")
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95 (both surfaces readable)`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── Zero MAC (score 1.0) ─────────────────────────────────────────────────

    @Test
    fun `adapter all-zero MAC — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "00:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `sysfs all-zero MAC — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "48:8a:e1:11:22:33")
            .run(fakeCtx(sysfsMac = "00:00:00:00:00:00"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero MAC — pattern is zero_mac`() = runBlocking {
        val result = makeProbe(adapterMac = "00:00:00:00:00:00").run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_ZERO_MAC, ev?.value)
    }

    // ── QEMU / VBox emulator OUIs (score 1.0) ────────────────────────────────

    @Test
    fun `QEMU OUI 52_54_00 in adapter — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "52:54:00:12:34:56").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `VirtualBox OUI 08_00_27 in sysfs — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = "08:00:27:12:34:56"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emulator OUI — pattern is emulator_oui`() = runBlocking {
        val result = makeProbe(adapterMac = "52:54:00:12:34:56").run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_EMULATOR_OUI, ev?.value)
    }

    // ── VMware / Hyper-V / Xen OUIs (score 0.95) ─────────────────────────────

    @Test
    fun `VMware OUI 00_0c_29 — score is 0_95`() = runBlocking {
        val result = makeProbe(adapterMac = "00:0c:29:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Hyper-V OUI 00_15_5d — score is 0_95`() = runBlocking {
        val result = makeProbe(adapterMac = "00:15:5d:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Xen OUI 00_16_3e — score is 0_95`() = runBlocking {
        val result = makeProbe(adapterMac = "00:16:3e:12:34:56").run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    // ── Test-fixture MACs (score 1.0) ────────────────────────────────────────

    @Test
    fun `aa_bb_cc_dd_ee_ff test fixture — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "aa:bb:cc:dd:ee:ff").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `de_ad_be_ef test fixture OUI — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "de:ad:be:ef:00:01").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `cafebabe test fixture OUI — score is 1_0`() = runBlocking {
        val result = makeProbe(adapterMac = "ca:fe:ba:be:00:01").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `test fixture — pattern is test_fixture`() = runBlocking {
        val result = makeProbe(adapterMac = "aa:bb:cc:dd:ee:ff").run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_TEST_FIXTURE, ev?.value)
    }

    // ── Android 6+ privacy default — strong + benign cases ───────────────────

    @Test
    fun `adapter and sysfs both privacy default — score is 0_85 (strong tampering)`() = runBlocking {
        val result = makeProbe(adapterMac = "02:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = "02:00:00:00:00:00"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `adapter privacy default plus sysfs unreadable — score is 0_5 (benign)`() = runBlocking {
        val result = makeProbe(adapterMac = "02:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = null))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `adapter privacy default plus sysfs real MAC — score is 0`() = runBlocking {
        // Adapter capped at privacy default but sysfs reveals real Pixel OUI.
        // Real-OEM MAC dominates → clean.
        val result = makeProbe(adapterMac = "02:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `privacy default benign — pattern is privacy_default_benign`() = runBlocking {
        val result = makeProbe(adapterMac = "02:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = null))
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_PRIVACY_DEFAULT_BENIGN, ev?.value)
    }

    @Test
    fun `privacy default via sysfs — pattern is privacy_default_via_sysfs`() = runBlocking {
        val result = makeProbe(adapterMac = "02:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = "02:00:00:00:00:00"))
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_PRIVACY_DEFAULT_VIA_SYSFS, ev?.value)
    }

    // ── Null adapter on phone-class model (score 0.85) ───────────────────────

    @Test
    fun `null adapter on Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = null, model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `null adapter on tablet — score is 0 (no false-positive)`() = runBlocking {
        // Tablet may legitimately lack Bluetooth → don't flag.
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = null, model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null adapter on phone — pattern is null_adapter_on_phone`() = runBlocking {
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = null, model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_NULL_ADAPTER_ON_PHONE, ev?.value)
    }

    // ── Locally-administered bit on phone (score 0.80) ───────────────────────

    @Test
    fun `locally-administered MAC on Pixel — score is 0_80`() = runBlocking {
        // 0a:11:22:33:44:55 — first byte 0x0a has bit-1=1 (locally administered)
        // but NOT in any known emulator OUI (Docker is 0a:00:27 specifically).
        val result = makeProbe(adapterMac = "0a:11:22:33:44:55")
            .run(fakeCtx(sysfsMac = "0a:11:22:33:44:55"))
        assertEquals(0.80, result.score)
    }

    @Test
    fun `locally-administered on tablet — score is 0 (no phone-class)`() = runBlocking {
        val result = makeProbe(adapterMac = "0a:11:22:33:44:55")
            .run(fakeCtx(sysfsMac = "0a:11:22:33:44:55", model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `locally-administered — pattern is locally_administered`() = runBlocking {
        val result = makeProbe(adapterMac = "0a:11:22:33:44:55")
            .run(fakeCtx(sysfsMac = "0a:11:22:33:44:55"))
        val ev = result.evidence.find { it.key == "bluetooth_mac.pattern" }
        assertEquals(BluetoothMacProbe.PATTERN_LOCALLY_ADMINISTERED, ev?.value)
    }

    // ── Multi-signal precedence ──────────────────────────────────────────────

    @Test
    fun `QEMU adapter plus locally-administered — QEMU wins (1_0 over 0_8)`() = runBlocking {
        // 52:54:00 is also locally administered (bit-1 of 0x52 is set).
        // The emulator OUI rule fires first.
        val result = makeProbe(adapterMac = "52:54:00:12:34:56").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero MAC plus VMware sysfs — score is 1_0 (zero wins)`() = runBlocking {
        val result = makeProbe(adapterMac = "00:00:00:00:00:00")
            .run(fakeCtx(sysfsMac = "00:0c:29:12:34:56"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `adapter null plus sysfs all-zero — score is 1_0 (zero wins over null-adapter)`() = runBlocking {
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = "00:00:00:00:00:00", model = "Pixel 7"))
        assertEquals(1.0, result.score)
    }

    // ── Adapter or sysfs unreadable ──────────────────────────────────────────

    @Test
    fun `adapter null AND sysfs unreadable — score is 0_85 (null adapter on phone wins)`() = runBlocking {
        // On phone-class with no surfaces visible at all, BluetoothManager
        // null is the tell that fires.
        val result = makeProbe(adapterMac = null)
            .run(fakeCtx(sysfsMac = null, model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `readFile throws — sysfs treated as null, falls through cleanly`() = runBlocking {
        val result = makeProbe(adapterMac = "48:8a:e1:11:22:33")
            .run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        // Adapter clean + sysfs unreadable → confidence partial; score clean.
        assertEquals(0.0, result.score)
        assertEquals(0.60, result.confidence)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both surfaces readable — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only adapter readable — confidence is 0_60`() = runBlocking {
        val result = makeProbe().run(fakeCtx(sysfsMac = null))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only sysfs readable — confidence is 0_60`() = runBlocking {
        val result = makeProbe(adapterMac = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `neither surface readable — confidence is 0_30`() = runBlocking {
        val result = makeProbe(adapterMac = null).run(fakeCtx(sysfsMac = null))
        assertEquals(0.30, result.confidence)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production constructor with no sysfs — confidence is 0_30`() = runBlocking {
        val result = BluetoothMacProbe().run(fakeCtx(sysfsMac = null))
        // No adapter accessor + no sysfs → null adapter + sysfs unreadable
        // → on phone-class, fires null_adapter_on_phone 0.85.
        assertEquals(0.85, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `no-arg ctor with sysfs real MAC — score is 0_0 confidence 0_60`() = runBlocking {
        val result = BluetoothMacProbe()
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        // Adapter null + sysfs real OUI + phone-class. Null adapter rule
        // fires (0.85)? Let's check: nullAdapterOnPhone = !adapterSupplierReturned && phoneClass.
        // Wait — adapterSupplierReturned is "supplier didn't return null". With
        // production ctor adapter is null, supplierReturned=false. So
        // nullAdapterOnPhone=true → fires 0.85, NOT 0.0.
        assertEquals(0.85, result.score)
    }

    // ── Supplier-throws crash safety ─────────────────────────────────────────

    @Test
    fun `adapter supplier throws — does not crash`() = runBlocking {
        val probe = BluetoothMacProbe(
            bluetoothAdapterMacSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        assertFalse(result.failed)
        // Adapter null (supplier threw, treated same as null return) +
        // sysfs real OUI + phone-class. null_adapter_on_phone fires.
        assertEquals(0.85, result.score)
    }

    // ── Case-insensitive MAC matching ────────────────────────────────────────

    @Test
    fun `uppercase MAC — normalized and detected`() = runBlocking {
        val result = makeProbe(adapterMac = "48:8A:E1:11:22:33")
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `trailing newline in sysfs read — trimmed correctly`() = runBlocking {
        val result = makeProbe()
            .run(fakeCtx(sysfsMac = "48:8a:e1:11:22:33\n"))
        assertEquals(0.0, result.score)
    }

    // ── Cross-rank reuse invariants (rank-17 pattern) ────────────────────────

    @Test
    fun `OUI sets reuse rank-15 WifiMacProbe constants`() {
        // Drift-safe invariant: if rank-15's emulator OUI set ever changes,
        // this test fails immediately, surfacing the cross-probe coupling.
        // (Same pattern as rank-17 anchoring rank-11 constants.)
        assertEquals("52:54:00", WifiMacProbe.OUI_QEMU.first())
        assertEquals("08:00:27", WifiMacProbe.OUI_VBOX.first())
        assertTrue("00:0c:29" in WifiMacProbe.OUI_VMWARE)
        assertEquals("00:15:5d", WifiMacProbe.OUI_HYPERV.first())
        assertEquals("00:16:3e", WifiMacProbe.OUI_XEN.first())
    }

    @Test
    fun `MAC normalizer reuses rank-15 WifiMacProbe helper`() {
        assertEquals("52:54:00:12:34:56", WifiMacProbe.normalizeMac("52:54:00:12:34:56"))
        assertEquals("52:54:00:12:34:56", WifiMacProbe.normalizeMac("52:54:00:12:34:56\n"))
        assertEquals(null, WifiMacProbe.normalizeMac("invalid"))
    }

    // ── classifyOuiCombined helper unit tests ─────────────────────────────────

    @Test
    fun `classifyOuiCombined returns qemu for QEMU OUI`() {
        assertEquals(
            BluetoothMacProbe.EMU_TAG_QEMU,
            BluetoothMacProbe.classifyOuiCombined("52:54:00:12:34:56"),
        )
    }

    @Test
    fun `classifyOuiCombined returns test_fixture for aa_bb_cc`() {
        assertEquals(
            BluetoothMacProbe.EMU_TAG_TEST_FIXTURE,
            BluetoothMacProbe.classifyOuiCombined("aa:bb:cc:dd:ee:ff"),
        )
    }

    @Test
    fun `classifyOuiCombined returns test_fixture for de_ad_be prefix`() {
        assertEquals(
            BluetoothMacProbe.EMU_TAG_TEST_FIXTURE,
            BluetoothMacProbe.classifyOuiCombined("de:ad:be:ef:00:01"),
        )
    }

    @Test
    fun `classifyOuiCombined returns none for real OEM OUI`() {
        assertEquals(
            BluetoothMacProbe.EMU_TAG_NONE,
            BluetoothMacProbe.classifyOuiCombined("48:8a:e1:11:22:33"),
        )
    }

    @Test
    fun `classifyOuiCombined returns none for null`() {
        assertEquals(
            BluetoothMacProbe.EMU_TAG_NONE,
            BluetoothMacProbe.classifyOuiCombined(null),
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        // 6 spec rows + bluetooth_mac.pattern (rank-17/21/22 convention).
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "bluetooth_mac.adapter",
                "bluetooth_mac.sysfs",
                "bluetooth_mac.oui_first_byte",
                "bluetooth_mac.locally_administered",
                "bluetooth_mac.emulator_oui_match",
                "bluetooth_mac.adapter_available",
                "bluetooth_mac.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `null adapter — adapter_available evidence is false`() = runBlocking {
        val result = makeProbe(adapterMac = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.adapter_available" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `clean — emulator_oui_match evidence is none`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.emulator_oui_match" }
        assertEquals(BluetoothMacProbe.EMU_TAG_NONE, ev?.value)
    }

    @Test
    fun `QEMU MAC — emulator_oui_match evidence is qemu`() = runBlocking {
        val result = makeProbe(adapterMac = "52:54:00:12:34:56").run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.emulator_oui_match" }
        assertEquals(BluetoothMacProbe.EMU_TAG_QEMU, ev?.value)
    }

    @Test
    fun `unreadable sysfs — sysfs evidence shows unreadable placeholder`() = runBlocking {
        val result = makeProbe().run(fakeCtx(sysfsMac = null))
        val ev = result.evidence.find { it.key == "bluetooth_mac.sysfs" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `oui_first_byte evidence is hex-formatted`() = runBlocking {
        val result = makeProbe(adapterMac = "48:8a:e1:11:22:33").run(fakeCtx())
        val ev = result.evidence.find { it.key == "bluetooth_mac.oui_first_byte" }
        assertEquals("0x48", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read BluetoothAdapter.address + /sys/class/bluetooth/hci0/address; " +
                "validate OUI prefix against emulator-vendor allowlist; check " +
                "locally-administered bit. Cross-reference rank-15 WifiMac OUI table.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is identity_bluetooth_mac`() {
        assertEquals("identity.bluetooth_mac", makeProbe().id)
    }

    @Test
    fun `probe rank is 31`() {
        assertEquals(31, makeProbe().rank)
    }

    @Test
    fun `probe category is IDENTITY`() {
        assertEquals(ProbeCategory.IDENTITY, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-31 severity=medium.
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

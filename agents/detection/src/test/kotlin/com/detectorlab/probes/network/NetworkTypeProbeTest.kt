package com.detectorlab.probes.network

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
 * Unit tests for NetworkTypeProbe (network.network_type, inventory rank 25).
 */
class NetworkTypeProbeTest {

    private fun makeProbe(
        transport: Int? = NetworkTypeProbe.TRANSPORT_WIFI,
        dataType: Int? = NetworkTypeProbe.NETWORK_TYPE_LTE,
        simState: Int? = NetworkTypeProbe.SIM_STATE_READY,
    ): NetworkTypeProbe = NetworkTypeProbe(
        activeTransportSupplier = { transport },
        dataNetworkTypeSupplier = { dataType },
        simStateSupplier = { simState },
    )

    private fun ctxWithModel(model: String? = "Pixel 7"): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == NetworkTypeProbe.PROP_RO_PRODUCT_MODEL) model else null
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

    // ── Clean real device ────────────────────────────────────────────────────

    @Test
    fun `Pixel 7 on WiFi with SIM ready and LTE data — score is 0`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_LTE,
            simState = NetworkTypeProbe.SIM_STATE_READY,
        ).run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean device — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean device — pattern is none`() = runBlocking {
        val result = makeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.is_emulator_pattern" }
        assertEquals(NetworkTypeProbe.PATTERN_NONE, ev?.value)
    }

    @Test
    fun `cellular-only with NR 5G — score is 0`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_CELLULAR,
            dataType = NetworkTypeProbe.NETWORK_TYPE_NR,
            simState = NetworkTypeProbe.SIM_STATE_READY,
        ).run(ctxWithModel("SM-S908B"))
        assertEquals(0.0, result.score)
    }

    // ── TRANSPORT_USB (score 1.0) ────────────────────────────────────────────

    @Test
    fun `TRANSPORT_USB active — score is 1_0`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_USB)
            .run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `USB transport — pattern evidence is usb`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_USB)
            .run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.is_emulator_pattern" }
        assertEquals(NetworkTypeProbe.PATTERN_USB, ev?.value)
    }

    @Test
    fun `USB transport — active_transport evidence reads USB`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_USB)
            .run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.active_transport" }
        assertEquals("USB", ev?.value)
    }

    @Test
    fun `USB transport dominates SIM state — even with SIM_ABSENT scores 1_0`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_USB,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    // ── TRANSPORT_ETHERNET on phone (score 0.95) ─────────────────────────────

    @Test
    fun `Ethernet on Pixel 7 — score is 0_95`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_ETHERNET)
            .run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Ethernet on Galaxy S22 — score is 0_95`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_ETHERNET)
            .run(ctxWithModel("SM-S901B"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Ethernet on non-phone (tablet) — score is 0_0`() = runBlocking {
        // Tablets legitimately have Ethernet (some Pro models, dev kits).
        // Non-phone-class model → ethernet rule doesn't fire.
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_ETHERNET)
            .run(ctxWithModel("Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Ethernet on null model — score is 0_0 (can't conclude phone-class)`() = runBlocking {
        val result = makeProbe(transport = NetworkTypeProbe.TRANSPORT_ETHERNET)
            .run(ctxWithModel(null))
        assertEquals(0.0, result.score)
    }

    // ── SIM absent + cellular data type non-UNKNOWN (score 0.85) ─────────────

    @Test
    fun `SIM_ABSENT but dataNetworkType LTE — score is 0_85`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_LTE,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `contradiction — pattern is sim_absent_data_present`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_LTE,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.is_emulator_pattern" }
        assertEquals(NetworkTypeProbe.PATTERN_SIM_ABSENT_DATA_PRESENT, ev?.value)
    }

    // ── WIFI + SIM_ABSENT + phone-class (score 0.85) ─────────────────────────

    @Test
    fun `WIFI only + SIM absent + Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_UNKNOWN,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `WIFI only + SIM absent + tablet — score is 0_0`() = runBlocking {
        // Tablet without SIM is normal; phone-class guard prevents false-positive.
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_UNKNOWN,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `WIFI only + SIM absent — pattern is wifi_only_no_sim`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_UNKNOWN,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.is_emulator_pattern" }
        assertEquals(NetworkTypeProbe.PATTERN_WIFI_ONLY_NO_SIM, ev?.value)
    }

    // ── WIFI + SIM_READY + dataNetworkType UNKNOWN (score 0.7) ───────────────

    @Test
    fun `WIFI + SIM ready + data UNKNOWN — score is 0_7`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_UNKNOWN,
            simState = NetworkTypeProbe.SIM_STATE_READY,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `WIFI + SIM ready + data UNKNOWN — pattern is sim_ready_data_unknown`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            dataType = NetworkTypeProbe.NETWORK_TYPE_UNKNOWN,
            simState = NetworkTypeProbe.SIM_STATE_READY,
        ).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.is_emulator_pattern" }
        assertEquals(NetworkTypeProbe.PATTERN_SIM_READY_DATA_UNKNOWN, ev?.value)
    }

    // ── Multi-signal precedence (max-wins via first-match cascade) ───────────

    @Test
    fun `USB transport plus SIM absent — USB wins (1_0 over 0_85)`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_USB,
            dataType = NetworkTypeProbe.NETWORK_TYPE_LTE,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Ethernet on phone plus SIM absent — Ethernet wins (0_95 over 0_85)`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_ETHERNET,
            dataType = NetworkTypeProbe.NETWORK_TYPE_LTE,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.score)
    }

    // ── Phone-class detection ────────────────────────────────────────────────

    @Test
    fun `Pixel 7 — phone class detected`() {
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
    }

    @Test
    fun `SM-S908B Galaxy S22 Ultra — phone class detected`() {
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
    }

    @Test
    fun `OnePlus 11 — phone class detected`() {
        assertTrue(NetworkTypeProbe.isPhoneClassModel("OnePlus 11"))
    }

    @Test
    fun `Lenovo Tab P11 — not phone class`() {
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
    }

    @Test
    fun `null or empty model — not phone class`() {
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(""))
    }

    @Test
    fun `case-insensitive phone-class match`() {
        assertTrue(NetworkTypeProbe.isPhoneClassModel("PIXEL 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("pixel 7"))
    }

    // ── Suppliers throw / null ────────────────────────────────────────────────

    @Test
    fun `all suppliers throw — score 0, confidence 0_30`() = runBlocking {
        val probe = NetworkTypeProbe(
            activeTransportSupplier = { throw RuntimeException("simulated") },
            dataNetworkTypeSupplier = { throw RuntimeException("simulated") },
            simStateSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `production no-arg constructor — score 0, confidence 0_30 (graceful stub)`() = runBlocking {
        val result = NetworkTypeProbe().run(ctxWithModel("Pixel 7"))
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `only transport supplier returns — confidence 0_60 partial`() = runBlocking {
        val probe = NetworkTypeProbe(
            activeTransportSupplier = { NetworkTypeProbe.TRANSPORT_WIFI },
            dataNetworkTypeSupplier = { null },
            simStateSupplier = { null },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — phoneClass falls through to false`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = throw RuntimeException("simulated")
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
        val probe = makeProbe(transport = NetworkTypeProbe.TRANSPORT_ETHERNET)
        val result = probe.run(ctx)
        assertFalse(result.failed)
        // Model unknown → phoneClass=false → ethernet-on-phone rule doesn't fire.
        assertEquals(0.0, result.score)
    }

    // ── Transport name display ───────────────────────────────────────────────

    @Test
    fun `transportName covers all known transports`() {
        assertEquals("CELLULAR", NetworkTypeProbe.transportName(0))
        assertEquals("WIFI", NetworkTypeProbe.transportName(1))
        assertEquals("BLUETOOTH", NetworkTypeProbe.transportName(2))
        assertEquals("ETHERNET", NetworkTypeProbe.transportName(3))
        assertEquals("VPN", NetworkTypeProbe.transportName(4))
        assertEquals("USB", NetworkTypeProbe.transportName(8))
        assertEquals("null", NetworkTypeProbe.transportName(null))
        assertEquals("OTHER(99)", NetworkTypeProbe.transportName(99))
    }

    @Test
    fun `simStateName covers all known states`() {
        assertEquals("UNKNOWN", NetworkTypeProbe.simStateName(0))
        assertEquals("ABSENT", NetworkTypeProbe.simStateName(1))
        assertEquals("READY", NetworkTypeProbe.simStateName(5))
        assertEquals("null", NetworkTypeProbe.simStateName(null))
        assertEquals("OTHER(7)", NetworkTypeProbe.simStateName(7))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 5 keys`() = runBlocking {
        val result = makeProbe().run(ctxWithModel())
        assertEquals(5, result.evidence.size)
    }

    @Test
    fun `evidence covers all five documented keys`() = runBlocking {
        val result = makeProbe().run(ctxWithModel())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "net.active_transport",
                "net.has_cellular_capability",
                "telephony.data_network_type",
                "telephony.sim_state",
                "net.is_emulator_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `has_cellular_capability true when SIM ready`() = runBlocking {
        val result = makeProbe(simState = NetworkTypeProbe.SIM_STATE_READY)
            .run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.has_cellular_capability" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_cellular_capability false when SIM absent and transport non-cellular`() = runBlocking {
        val result = makeProbe(
            transport = NetworkTypeProbe.TRANSPORT_WIFI,
            simState = NetworkTypeProbe.SIM_STATE_ABSENT,
        ).run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "net.has_cellular_capability" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `data_network_type evidence is string representation of Int`() = runBlocking {
        val result = makeProbe(dataType = NetworkTypeProbe.NETWORK_TYPE_LTE)
            .run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "telephony.data_network_type" }
        assertEquals("13", ev?.value)
    }

    @Test
    fun `data_network_type null when supplier returns null`() = runBlocking {
        val result = NetworkTypeProbe().run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "telephony.data_network_type" }
        assertEquals("null", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(ctxWithModel())
        assertEquals(
            "Read ConnectivityManager.activeNetwork transport + " +
                "TelephonyManager.dataNetworkType + simState; classify " +
                "WiFi-only-no-SIM and TRANSPORT_USB as emulator signals. " +
                "No live network requests.",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is network_network_type`() {
        assertEquals("network.network_type", makeProbe().id)
    }

    @Test
    fun `probe rank is 25`() {
        assertEquals(25, makeProbe().rank)
    }

    @Test
    fun `probe category is NETWORK`() {
        assertEquals(ProbeCategory.NETWORK, makeProbe().category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // Matches shared/probes/inventory.yml rank-25 severity=high.
        assertEquals(ProbeSeverity.HIGH, makeProbe().severity)
    }

    @Test
    fun `probe android layer is NETWORK`() {
        assertEquals(AndroidLayer.NETWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 150ms`() {
        assertEquals(150L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

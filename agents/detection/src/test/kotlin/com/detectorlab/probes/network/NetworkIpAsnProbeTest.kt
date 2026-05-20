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
 * Unit tests for NetworkIpAsnProbe (network.ip_asn, inventory rank 5).
 *
 * The probe is **declarative-only** — it infers cloud / datacenter / emulator
 * hosting via build-prop and `/proc/net/route` signatures. It makes no live
 * network requests (per Probe invariant #2).
 */
class NetworkIpAsnProbeTest {

    /** Headers-and-clean-route sample mimicking a real phone's wlan0 routes. */
    private val cleanProcNetRoute = """
        Iface	Destination	Gateway	Flags	RefCnt	Use	Metric	Mask	MTU	Window	IRTT
        wlan0	00000000	0101A8C0	0003	0	0	0	00000000	0	0	0
        wlan0	0001A8C0	00000000	0001	0	0	0	00FFFFFF	0	0	0
    """.trimIndent()

    /** Route table with the AVD canonical gateway 10.0.2.1. */
    private val avdProcNetRoute = """
        Iface	Destination	Gateway	Flags	RefCnt	Use	Metric	Mask	MTU	Window	IRTT
        eth0	00000000	0102000A	0003	0	0	0	00000000	0	0	0
    """.trimIndent()

    /** Route table with the Docker default bridge gateway 172.17.0.1. */
    private val dockerProcNetRoute = """
        Iface	Destination	Gateway	Flags	RefCnt	Use	Metric	Mask	MTU	Window	IRTT
        eth0	00000000	010011AC	0003	0	0	0	00000000	0	0	0
    """.trimIndent()

    /** Uppercase-hex variant for case-handling coverage. */
    private val dockerProcNetRouteLowercase = """
        Iface	Destination	Gateway	Flags	RefCnt	Use	Metric	Mask	MTU	Window	IRTT
        eth0	00000000	010011ac	0003	0	0	0	00000000	0	0	0
    """.trimIndent()

    /**
     * Build a ProbeContext with controllable system-property and file-read
     * surfaces. All unset accessors return null without throwing — the
     * "all-null" baseline.
     */
    private fun fakeCtx(
        properties: Map<String, String?> = emptyMap(),
        readableFiles: Map<String, String?> = emptyMap(),
        getSystemPropertyThrows: Boolean = false,
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (getSystemPropertyThrows) throw RuntimeException("simulated property service")
            return properties[key]
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            val content = readableFiles[path] ?: return null
            return if (content.length <= maxBytes) content else content.substring(0, maxBytes)
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

    // ── Signal 1: AVD network stack (score 0.95) ─────────────────────────────

    @Test
    fun `signal 1 avd network stack — cellular empty plus qemud set fires 0_95`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_BOOT_NETWORK_CELLULAR to "",
                NetworkIpAsnProbe.PROP_KERNEL_ANDROID_QEMUD to "1",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_AVD_NETWORK_STACK, sig?.value)
    }

    @Test
    fun `signal 1 — cellular set non-empty plus qemud set does NOT fire`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_BOOT_NETWORK_CELLULAR to "lte",
                NetworkIpAsnProbe.PROP_KERNEL_ANDROID_QEMUD to "1",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        // Cellular is set (real phone with LTE) → signal 1 does not fire.
        // No other signal active → CLEAN.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `signal 1 — qemud missing means no fire even if cellular empty`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_BOOT_NETWORK_CELLULAR to "",
                // qemud not set
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.0, result.score)
    }

    // ── Signal 2: container-tell properties (score 0.85) ─────────────────────

    @Test
    fun `signal 2 — ro_docker_container set fires 0_85`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(NetworkIpAsnProbe.PROP_DOCKER_CONTAINER to "true"),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.85, result.score)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_CONTAINER_TELL_PROPERTY, sig?.value)
    }

    @Test
    fun `signal 2 — ro_cloud_emulator set fires 0_85`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(NetworkIpAsnProbe.PROP_CLOUD_EMULATOR to "genymotion-cloud"),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `signal 2 — redroid_boot_timestamp catches the redroid_ family`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf("redroid.boot.timestamp" to "1716163200"),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.85, result.score)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_CONTAINER_TELL_PROPERTY, sig?.value)
    }

    @Test
    fun `signal 2 — empty-string docker container prop does NOT fire`() = runBlocking {
        // Spoof-stack sentinel: key declared but value masked to "". The probe
        // treats this as "not set" — only meaningful values are score-bearing.
        val ctx = fakeCtx(
            properties = mapOf(NetworkIpAsnProbe.PROP_DOCKER_CONTAINER to ""),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.0, result.score)
    }

    // ── Signal 3: /proc/net/route emulator gateway (score 0.70) ──────────────

    @Test
    fun `signal 3 — avd gateway 10_0_2_1 hex fires 0_70`() = runBlocking {
        val ctx = fakeCtx(
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to avdProcNetRoute),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.70, result.score)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_EMULATOR_GATEWAY_ROUTE, sig?.value)
    }

    @Test
    fun `signal 3 — docker gateway 172_17_0_1 hex fires 0_70`() = runBlocking {
        val ctx = fakeCtx(
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to dockerProcNetRoute),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.70, result.score)
    }

    @Test
    fun `signal 3 — lowercase hex also matches (case-insensitive)`() = runBlocking {
        val ctx = fakeCtx(
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to dockerProcNetRouteLowercase),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.70, result.score)
    }

    @Test
    fun `signal 3 — clean route table (home network 192_168_1_1) does NOT fire`() = runBlocking {
        val ctx = fakeCtx(
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to cleanProcNetRoute),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.0, result.score)
    }

    // ── Signal 4: no USB + emulator boot-hardware (score 0.50) ───────────────

    @Test
    fun `signal 4 — usb empty plus boot_hardware goldfish fires 0_50`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_USB_CONFIG to "",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "goldfish",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.50, result.score)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_NO_USB_PLUS_EMU_HARDWARE, sig?.value)
    }

    @Test
    fun `signal 4 — boot_hardware ranchu fires 0_50`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_USB_CONFIG to "",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "ranchu",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.50, result.score)
    }

    @Test
    fun `signal 4 — boot_hardware REDROID uppercase fires (case-insensitive)`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_USB_CONFIG to "",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "REDROID",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.50, result.score)
    }

    @Test
    fun `signal 4 — usb non-empty disarms even with emu boot_hardware`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_USB_CONFIG to "mtp,adb",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "goldfish",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        // Phones with real USB are not landed by signal 4 even if a stray
        // emulator marker appears in ro.boot.hardware (unlikely on real
        // hardware, but the rule is conservative).
        assertEquals(0.0, result.score)
    }

    @Test
    fun `signal 4 — phone-shape boot_hardware does NOT fire`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_USB_CONFIG to "",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "kalama",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.0, result.score)
    }

    // ── Max-wins cascade ─────────────────────────────────────────────────────

    @Test
    fun `signal 1 wins over signal 2 (0_95 over 0_85) in max-wins cascade`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_BOOT_NETWORK_CELLULAR to "",
                NetworkIpAsnProbe.PROP_KERNEL_ANDROID_QEMUD to "1",
                NetworkIpAsnProbe.PROP_DOCKER_CONTAINER to "true",
            ),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `signal 2 wins over signal 3 (0_85 over 0_70)`() = runBlocking {
        val ctx = fakeCtx(
            properties = mapOf(NetworkIpAsnProbe.PROP_DOCKER_CONTAINER to "true"),
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to dockerProcNetRoute),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertEquals(0.85, result.score)
    }

    // ── Clean / degraded ─────────────────────────────────────────────────────

    @Test
    fun `clean context with phone-shape properties scores 0_0`() = runBlocking {
        // Realistic Pixel-shape readable surface — all properties readable
        // (non-null), no emulator markers, route table is the home-network
        // 192.168.1.1 gateway.
        val ctx = fakeCtx(
            properties = mapOf(
                NetworkIpAsnProbe.PROP_BOOT_NETWORK_CELLULAR to "lte",
                NetworkIpAsnProbe.PROP_USB_CONFIG to "mtp,adb",
                NetworkIpAsnProbe.PROP_BOOT_HARDWARE to "kalama",
            ),
            readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to cleanProcNetRoute),
        )
        val result = NetworkIpAsnProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(NetworkIpAsnProbe.CONFIDENCE_DECLARATIVE, result.confidence)
        val sig = result.evidence.find { it.key == "network.ip_asn.signal" }
        assertEquals(NetworkIpAsnProbe.SIGNAL_CLEAN, sig?.value)
    }

    @Test
    fun `all-null context — score 0, failed false, confidence 0_40 degraded`() = runBlocking {
        val result = NetworkIpAsnProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.40, result.confidence)
    }

    @Test
    fun `both accessors throw — score 0, failed false, confidence 0_40 degraded`() = runBlocking {
        val result = NetworkIpAsnProbe().run(
            fakeCtx(
                getSystemPropertyThrows = true,
                readFileThrows = true,
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.40, result.confidence)
    }

    @Test
    fun `route file readable but no properties — confidence 0_70 (one source observed)`() =
        runBlocking {
            val ctx = fakeCtx(
                readableFiles = mapOf(NetworkIpAsnProbe.PATH_PROC_NET_ROUTE to cleanProcNetRoute),
            )
            val result = NetworkIpAsnProbe().run(ctx)
            assertEquals(0.0, result.score)
            assertEquals(NetworkIpAsnProbe.CONFIDENCE_DECLARATIVE, result.confidence)
        }

    // ── Hex parsing helper ───────────────────────────────────────────────────

    @Test
    fun `extractGatewayTokens parses gateway column lowercase`() {
        val tokens = NetworkIpAsnProbe.extractGatewayTokens(dockerProcNetRoute)
        // Uppercase column "010011AC" should be lowercased to "010011ac"
        assertTrue("010011ac" in tokens, "expected docker gateway hex in tokens; got $tokens")
    }

    @Test
    fun `extractGatewayTokens parses lowercase column verbatim`() {
        val tokens = NetworkIpAsnProbe.extractGatewayTokens(dockerProcNetRouteLowercase)
        assertTrue("010011ac" in tokens, "expected docker gateway hex in tokens; got $tokens")
    }

    @Test
    fun `extractGatewayTokens skips the header row`() {
        val tokens = NetworkIpAsnProbe.extractGatewayTokens(cleanProcNetRoute)
        // Header row contains the literal "Gateway" string, NOT a hex value.
        // The parser should skip it.
        assertFalse(tokens.any { it == "gateway" }, "header row leaked into tokens: $tokens")
    }

    @Test
    fun `routeContainsEmulatorGateway returns false on clean home route`() {
        assertFalse(NetworkIpAsnProbe.routeContainsEmulatorGateway(cleanProcNetRoute))
    }

    @Test
    fun `routeContainsEmulatorGateway returns true on docker route`() {
        assertTrue(NetworkIpAsnProbe.routeContainsEmulatorGateway(dockerProcNetRoute))
    }

    @Test
    fun `routeContainsEmulatorGateway returns true on avd route`() {
        assertTrue(NetworkIpAsnProbe.routeContainsEmulatorGateway(avdProcNetRoute))
    }

    // ── Evidence shape ───────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly three keys`() = runBlocking {
        val result = NetworkIpAsnProbe().run(fakeCtx())
        assertEquals(3, result.evidence.size)
    }

    @Test
    fun `evidence covers all three documented keys`() = runBlocking {
        val result = NetworkIpAsnProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "network.ip_asn.signal",
                "network.ip_asn.declarative_only",
                "network.ip_asn.method",
            ),
            keys,
        )
    }

    @Test
    fun `declarative_only evidence is the boolean true`() = runBlocking {
        val result = NetworkIpAsnProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "network.ip_asn.declarative_only" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `method evidence and method string agree`() = runBlocking {
        val result = NetworkIpAsnProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "network.ip_asn.method" }
        assertEquals(NetworkIpAsnProbe.METHOD, ev?.value)
        assertEquals(NetworkIpAsnProbe.METHOD, result.method)
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is network_ip_asn`() {
        assertEquals("network.ip_asn", NetworkIpAsnProbe().id)
    }

    @Test
    fun `probe rank is 5`() {
        assertEquals(5, NetworkIpAsnProbe().rank)
    }

    @Test
    fun `probe category is NETWORK`() {
        assertEquals(ProbeCategory.NETWORK, NetworkIpAsnProbe().category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, NetworkIpAsnProbe().severity)
    }

    @Test
    fun `probe android layer is NETWORK`() {
        assertEquals(AndroidLayer.NETWORK, NetworkIpAsnProbe().androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, NetworkIpAsnProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget on clean context`() = runBlocking {
        val probe = NetworkIpAsnProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

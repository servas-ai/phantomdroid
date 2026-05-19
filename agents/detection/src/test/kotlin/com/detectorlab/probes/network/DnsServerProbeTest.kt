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
 * Unit tests for DnsServerProbe (network.dns_server, inventory rank 37).
 */
class DnsServerProbeTest {

    private fun makeProbe(
        linkPropertiesDns: List<String>? = null,
        activeTransport: String? = null,
    ): DnsServerProbe = DnsServerProbe(
        linkPropertiesDnsSupplier = { linkPropertiesDns },
        activeTransportSupplier = { activeTransport },
    )

    private fun fakeCtx(
        properties: Map<String, String?> = emptyMap(),
        secureSettings: Map<String, String?> = emptyMap(),
        resolvConf: String? = null,
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = properties[key]
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == DnsServerProbe.PATH_RESOLV_CONF) resolvConf else null
        }
        override fun querySettingSecure(key: String): String? = secureSettings[key]
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
    fun `real ISP DNS plus Google fallback — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42",
                    DnsServerProbe.PROP_NET_DNS2 to "8.8.8.8",
                ),
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real wifi DNS via supplier — score is 0`() = runBlocking {
        val result = makeProbe(
            linkPropertiesDns = listOf("192.168.1.1", "8.8.8.8"),
            activeTransport = "wifi",
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95 (sources readable)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
        )
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — is_emulator_default evidence is false`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
        )
        val ev = result.evidence.find { it.key == "dns.is_emulator_default" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `Cloudflare DNS 1_1_1_1 — score is 0 (legit user choice)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1")),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Quad9 DNS 9_9_9_9 — score is 0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "9.9.9.9")),
        )
        assertEquals(0.0, result.score)
    }

    // ── Android emulator DNS 10.0.2.3 (1.0) ──────────────────────────────────

    @Test
    fun `10_0_2_3 from net_dns1 — score is 1_0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.2.3")),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `10_0_2_3 from supplier — score is 1_0`() = runBlocking {
        val result = makeProbe(linkPropertiesDns = listOf("10.0.2.3")).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `10_0_2_3 from resolv_conf — score is 1_0`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(resolvConf = "nameserver 10.0.2.3\n"),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `10_0_2_3 — pattern is emulator_dns`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.2.3")),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_EMULATOR_DNS, ev?.value)
    }

    @Test
    fun `10_0_2_3 — is_emulator_default evidence is true`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.2.3")),
        )
        val ev = result.evidence.find { it.key == "dns.is_emulator_default" }
        assertEquals("true", ev?.value)
    }

    // ── VirtualBox gateway 10.0.0.x (0.85) ───────────────────────────────────

    @Test
    fun `10_0_0_1 — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.0.1")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `10_0_0_254 — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.0.254")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `vbox gateway — pattern is vbox_gateway`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.0.1")),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_VBOX_GATEWAY, ev?.value)
    }

    @Test
    fun `10_0_1_1 — score is 0 (not in 10_0_0 subnet)`() = runBlocking {
        // /24 specificity matters; 10.0.1.x is a different range that may be
        // legitimate corporate VPN or LAN.
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "10.0.1.1")),
        )
        assertEquals(0.0, result.score)
    }

    // ── Docker bridge 192.168.42.x (0.85) ────────────────────────────────────

    @Test
    fun `192_168_42_1 — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "192.168.42.1")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `192_168_42_99 — score is 0_85`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "192.168.42.99")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `docker bridge — pattern is docker_bridge`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "192.168.42.1")),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_DOCKER_BRIDGE, ev?.value)
    }

    @Test
    fun `192_168_1_1 — score is 0 (normal home LAN gateway)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "192.168.1.1")),
        )
        assertEquals(0.0, result.score)
    }

    // ── Google-only on cellular (0.85) ───────────────────────────────────────

    @Test
    fun `only 8_8_8_8 on cellular — score is 0_85`() = runBlocking {
        val result = makeProbe(activeTransport = "cellular").run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `only 8_8_4_4 on cellular — score is 0_85`() = runBlocking {
        val result = makeProbe(activeTransport = "cellular").run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.4.4")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Google-only on cellular — pattern is google_only_cellular`() = runBlocking {
        val result = makeProbe(activeTransport = "cellular").run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8")),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_GOOGLE_ONLY_CELLULAR, ev?.value)
    }

    @Test
    fun `Google plus ISP DNS on cellular — score is 0 (carrier DNS present)`() = runBlocking {
        val result = makeProbe(activeTransport = "cellular").run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42",
                    DnsServerProbe.PROP_NET_DNS2 to "8.8.8.8",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Google-only on wifi — score is 0 (legit user-set wifi)`() = runBlocking {
        // On wifi, user might legitimately set 8.8.8.8 as the only DNS.
        val result = makeProbe(activeTransport = "wifi").run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8")),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Google-only with no transport — score is 0 (can't claim cellular contradiction)`() =
        runBlocking {
            val result = makeProbe().run(
                fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8")),
            )
            assertEquals(0.0, result.score)
        }

    // ── Local resolver 127.0.0.1 / ::1 (0.70) ────────────────────────────────

    @Test
    fun `127_0_0_1 — score is 0_70`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "127.0.0.1")),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `IPv6 local resolver — score is 0_70`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "::1")),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `local resolver — pattern is local_resolver`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "127.0.0.1")),
        )
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_LOCAL_RESOLVER, ev?.value)
    }

    // ── No DNS configured (0.5) ──────────────────────────────────────────────

    @Test
    fun `no DNS configured anywhere — score is 0_5`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no DNS — pattern is no_dns_configured`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_NO_DNS_CONFIGURED, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `10_0_2_3 plus 10_0_0_1 — emulator wins (1_0 over 0_85)`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "10.0.2.3",
                    DnsServerProbe.PROP_NET_DNS2 to "10.0.0.1",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox plus docker plus google-only-cellular — vbox wins`() = runBlocking {
        // vbox at 0.85 fires before google_only_cellular at 0.85 due to
        // specific-to-general cascade order (specific subnet beats inferred
        // contradiction).
        val result = makeProbe(activeTransport = "cellular").run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "10.0.0.1",
                    DnsServerProbe.PROP_NET_DNS2 to "192.168.42.1",
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "dns.pattern" }
        assertEquals(DnsServerProbe.PATTERN_VBOX_GATEWAY, ev?.value)
    }

    @Test
    fun `127_0_0_1 plus 10_0_2_3 — emulator wins over local resolver`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "127.0.0.1",
                    DnsServerProbe.PROP_NET_DNS2 to "10.0.2.3",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    // ── Multi-source aggregation ─────────────────────────────────────────────

    @Test
    fun `dns merged from properties supplier and resolv_conf — all considered`() =
        runBlocking {
            val result = makeProbe(linkPropertiesDns = listOf("203.0.113.42")).run(
                fakeCtx(
                    properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8"),
                    resolvConf = "nameserver 10.0.2.3\n",
                ),
            )
            // Resolv.conf reveals the emulator DNS — should fire 1.0.
            assertEquals(1.0, result.score)
        }

    @Test
    fun `duplicate DNS across sources — deduplicated`() = runBlocking {
        // Same DNS appearing in properties + supplier + resolv.conf should
        // de-duplicate so the "only Google" rule on cellular doesn't false-
        // negative due to spurious multi-source duplication.
        val result = makeProbe(
            linkPropertiesDns = listOf("8.8.8.8"),
            activeTransport = "cellular",
        ).run(
            fakeCtx(
                properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8"),
                resolvConf = "nameserver 8.8.8.8\n",
            ),
        )
        // After dedup, only one DNS (8.8.8.8) on cellular → google_only_cellular.
        assertEquals(0.85, result.score)
    }

    // ── Resolv.conf parsing ──────────────────────────────────────────────────

    @Test
    fun `parseResolvConf handles standard format`() {
        val raw = """
            # auto-generated
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            options edns0
        """.trimIndent()
        assertEquals(
            listOf("8.8.8.8", "8.8.4.4"),
            DnsServerProbe.parseResolvConf(raw),
        )
    }

    @Test
    fun `parseResolvConf ignores comments and blank lines`() {
        val raw = """
            # comment 1
            nameserver 1.1.1.1

            # comment 2
            nameserver 9.9.9.9
        """.trimIndent()
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9"),
            DnsServerProbe.parseResolvConf(raw),
        )
    }

    @Test
    fun `parseResolvConf returns empty for null`() {
        assertEquals(emptyList(), DnsServerProbe.parseResolvConf(null))
    }

    @Test
    fun `parseResolvConf returns empty for empty`() {
        assertEquals(emptyList(), DnsServerProbe.parseResolvConf(""))
    }

    @Test
    fun `parseResolvConf is case insensitive on nameserver keyword`() {
        assertEquals(
            listOf("1.1.1.1"),
            DnsServerProbe.parseResolvConf("Nameserver 1.1.1.1"),
        )
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `no sources at all — confidence 0_50`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `at least one DNS readable — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1")),
        )
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only private DNS mode readable — confidence 0_95`() = runBlocking {
        // private DNS visibility is enough to claim observation; no DNS
        // server is enough for the no_dns_configured signal.
        val result = makeProbe().run(
            fakeCtx(
                secureSettings = mapOf(
                    DnsServerProbe.SETTING_PRIVATE_DNS_MODE to "off",
                ),
            ),
        )
        assertEquals(0.95, result.confidence)
    }

    // ── Private DNS evidence ─────────────────────────────────────────────────

    @Test
    fun `private DNS opportunistic — evidence reflects mode`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1"),
                secureSettings = mapOf(
                    DnsServerProbe.SETTING_PRIVATE_DNS_MODE to "opportunistic",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.private_dns_mode" }
        assertEquals("opportunistic", ev?.value)
    }

    @Test
    fun `private DNS hostname mode — specifier surfaces in evidence`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1"),
                secureSettings = mapOf(
                    DnsServerProbe.SETTING_PRIVATE_DNS_MODE to "hostname",
                    DnsServerProbe.SETTING_PRIVATE_DNS_SPECIFIER to "dns.cloudflare.com",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.private_dns_specifier" }
        assertEquals("dns.cloudflare.com", ev?.value)
    }

    @Test
    fun `private DNS off — evidence reflects mode`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1"),
                secureSettings = mapOf(
                    DnsServerProbe.SETTING_PRIVATE_DNS_MODE to "off",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.private_dns_mode" }
        assertEquals("off", ev?.value)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with no sources — score 0_5 confidence 0_50`() = runBlocking {
        val result = DnsServerProbe().run(fakeCtx())
        // suppliers null + no system properties + no resolv.conf → no DNS →
        // no_dns_configured at 0.5, confidence degraded.
        assertEquals(0.5, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor with system property — score and confidence based on DNS`() =
        runBlocking {
            val result = DnsServerProbe().run(
                fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
            )
            assertEquals(0.0, result.score)
            assertEquals(0.95, result.confidence)
        }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `linkPropertiesDnsSupplier throws — does not crash`() = runBlocking {
        val probe = DnsServerProbe(
            linkPropertiesDnsSupplier = { throw RuntimeException("simulated") },
            activeTransportSupplier = { null },
        )
        val result = probe.run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `activeTransportSupplier throws — does not crash`() = runBlocking {
        val probe = DnsServerProbe(
            linkPropertiesDnsSupplier = { null },
            activeTransportSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "8.8.8.8")),
        )
        assertFalse(result.failed)
        // No transport observable → google_only_cellular cannot fire → clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `getSystemProperty throws on one key — others still considered`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? =
                if (key == DnsServerProbe.PROP_NET_DNS1) throw RuntimeException("nope")
                else if (key == DnsServerProbe.PROP_NET_DNS2) "203.0.113.42"
                else null
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
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
        // DNS2 = 203.0.113.42 still readable → clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `readFile throws — resolv_conf treated as null`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42"),
                readFileThrows = true,
            ),
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isVboxGateway true for 10_0_0_x`() {
        assertTrue(DnsServerProbe.isVboxGateway("10.0.0.1"))
        assertTrue(DnsServerProbe.isVboxGateway("10.0.0.254"))
        assertTrue(DnsServerProbe.isVboxGateway("10.0.0.0"))
    }

    @Test
    fun `isVboxGateway false for non-10_0_0`() {
        assertFalse(DnsServerProbe.isVboxGateway("10.0.1.1"))
        assertFalse(DnsServerProbe.isVboxGateway("10.0.2.3"))  // android emu specific
        assertFalse(DnsServerProbe.isVboxGateway("192.168.1.1"))
        assertFalse(DnsServerProbe.isVboxGateway(""))
    }

    @Test
    fun `isVboxGateway false for malformed last octet`() {
        assertFalse(DnsServerProbe.isVboxGateway("10.0.0.notanumber"))
        assertFalse(DnsServerProbe.isVboxGateway("10.0.0.256"))
        assertFalse(DnsServerProbe.isVboxGateway("10.0.0.-1"))
    }

    @Test
    fun `isDockerBridge true for 192_168_42_x`() {
        assertTrue(DnsServerProbe.isDockerBridge("192.168.42.1"))
        assertTrue(DnsServerProbe.isDockerBridge("192.168.42.99"))
        assertTrue(DnsServerProbe.isDockerBridge("192.168.42.255"))
    }

    @Test
    fun `isDockerBridge false for non-192_168_42`() {
        assertFalse(DnsServerProbe.isDockerBridge("192.168.1.1"))
        assertFalse(DnsServerProbe.isDockerBridge("192.168.41.1"))
    }

    @Test
    fun `isLocalResolver detects v4 and v6 localhost`() {
        assertTrue(DnsServerProbe.isLocalResolver("127.0.0.1"))
        assertTrue(DnsServerProbe.isLocalResolver("::1"))
    }

    @Test
    fun `isLocalResolver false for other addresses`() {
        assertFalse(DnsServerProbe.isLocalResolver("8.8.8.8"))
        assertFalse(DnsServerProbe.isLocalResolver("127.0.0.2"))
    }

    @Test
    fun `normalizeTransport lowercases and trims`() {
        assertEquals("cellular", DnsServerProbe.normalizeTransport("CELLULAR"))
        assertEquals("wifi", DnsServerProbe.normalizeTransport("  WiFi  "))
    }

    @Test
    fun `normalizeTransport returns null for empty or null`() {
        assertEquals(null, DnsServerProbe.normalizeTransport(""))
        assertEquals(null, DnsServerProbe.normalizeTransport("   "))
        assertEquals(null, DnsServerProbe.normalizeTransport(null))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
        )
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42")),
        )
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "dns.server1",
                "dns.server2",
                "dns.server3",
                "dns.private_dns_mode",
                "dns.private_dns_specifier",
                "dns.is_emulator_default",
                "dns.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `server1 evidence shows first DNS`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42",
                    DnsServerProbe.PROP_NET_DNS2 to "8.8.8.8",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.server1" }
        assertEquals("203.0.113.42", ev?.value)
    }

    @Test
    fun `server2 evidence shows second DNS`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                properties = mapOf(
                    DnsServerProbe.PROP_NET_DNS1 to "203.0.113.42",
                    DnsServerProbe.PROP_NET_DNS2 to "8.8.8.8",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "dns.server2" }
        assertEquals("8.8.8.8", ev?.value)
    }

    @Test
    fun `no DNS — server1 evidence is null literal`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "dns.server1" }
        assertEquals("null", ev?.value)
    }

    @Test
    fun `unreadable private DNS — evidence is unreadable placeholder`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1")),
        )
        val ev = result.evidence.find { it.key == "dns.private_dns_mode" }
        assertEquals("<unreadable>", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1")),
        )
        assertEquals(
            "Read net.dns1-4 properties + ConnectivityManager link DNS + " +
                "Settings.Global private DNS; detect emulator DNS subnets " +
                "(10.0.2.x, 192.168.42.x) and isolated public-DNS-only configs. " +
                "No live DNS lookups.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is network_dns_server`() {
        assertEquals("network.dns_server", makeProbe().id)
    }

    @Test
    fun `probe rank is 37`() {
        assertEquals(37, makeProbe().rank)
    }

    @Test
    fun `probe category is NETWORK`() {
        assertEquals(ProbeCategory.NETWORK, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-37 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
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
        val result = probe.run(
            fakeCtx(properties = mapOf(DnsServerProbe.PROP_NET_DNS1 to "1.1.1.1")),
        )
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

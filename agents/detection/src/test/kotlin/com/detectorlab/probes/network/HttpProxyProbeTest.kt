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
 * Unit tests for HttpProxyProbe (network.http_proxy, inventory rank 38).
 */
class HttpProxyProbeTest {

    private fun makeProbe(jvmProperties: Map<String, String?> = emptyMap()): HttpProxyProbe =
        HttpProxyProbe(jvmPropertySupplier = { key -> jvmProperties[key] })

    private fun makeProbeThrows(throwOn: Set<String>): HttpProxyProbe =
        HttpProxyProbe(
            jvmPropertySupplier = { key ->
                if (key in throwOn) throw RuntimeException("simulated SecurityException on $key")
                else null
            },
        )

    private val emptyCtx: ProbeContext = object : ProbeContext {
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

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `no proxy properties — score is 0`() = runBlocking {
        // Supplier returns null for every key (default test ctx).
        val result = makeProbe().run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `null props — confidence is 0_50 (no observation)`() = runBlocking {
        // All supplier returns null → anyObserved stays false → degraded.
        val result = makeProbe().run(emptyCtx)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `empty-string proxy host — score is 0 (null-vs-empty discipline)`() = runBlocking {
        // Per session-level discipline: empty string is "observed but
        // not set" → no score signal, but DOES count as observation
        // for confidence.
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "",
                HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "",
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "",
            )
        ).run(emptyCtx)
        assertEquals(0.0, result.score)
        // Confidence stays at 0.95 because empty strings were observed.
        assertEquals(0.95, result.confidence)
    }

    // ── Local HTTP proxy (0.95) ──────────────────────────────────────────────

    @Test
    fun `http proxyHost 127_0_0_1 — score is 0_95 (mitmproxy signature)`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `http proxyHost localhost — score is 0_95`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "localhost")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `http proxyHost IPv6 loopback — score is 0_95`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "::1")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `localhost case-insensitive — LOCALHOST fires`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "LOCALHOST")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `local proxy — pattern is local_http_proxy`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_LOCAL_HTTP_PROXY, ev?.value)
    }

    @Test
    fun `local proxy with mitmproxy default port 8080 — score is 0_95`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1",
                HttpProxyProbe.PROP_HTTP_PROXY_PORT to "8080",
            )
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    // ── SOCKS proxy (0.95) ───────────────────────────────────────────────────

    @Test
    fun `socksProxyHost set — score is 0_95`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "10.0.0.1")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `socksProxyHost localhost — score is 0_95`() = runBlocking {
        // SOCKS rule fires regardless of host (no loopback narrowing
        // for SOCKS — any SOCKS configuration is unusual on Android).
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `SOCKS with port 1080 (default) — score is 0_95`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "10.0.0.1",
                HttpProxyProbe.PROP_SOCKS_PROXY_PORT to "1080",
            )
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `SOCKS proxy — pattern is socks_proxy`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "10.0.0.1")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_SOCKS_PROXY, ev?.value)
    }

    // ── HTTP proxy configured (0.85) ─────────────────────────────────────────

    @Test
    fun `http proxyHost non-loopback — score is 0_85`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "proxy.corp.example.com")
        ).run(emptyCtx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `http proxyHost 10_0_0_1 — score is 0_85 (LAN proxy)`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "10.0.0.1")
        ).run(emptyCtx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `https proxyHost only — score is 0_85`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "tls-inspect.corp.example.com")
        ).run(emptyCtx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `https proxyHost localhost — score is 0_85 (not 0_95)`() = runBlocking {
        // The 0.95 local-proxy rule fires on http.proxyHost loopback
        // specifically, not https.proxyHost. https.proxyHost loopback
        // is unusual but treated by the broader proxy_configured rule.
        // Document the explicit decision.
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `http + https both set — score is 0_85`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "proxy.corp.example.com",
                HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "proxy.corp.example.com",
                HttpProxyProbe.PROP_HTTP_PROXY_PORT to "3128",
            )
        ).run(emptyCtx)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `http proxy configured — pattern is http_proxy_configured`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "proxy.example.com")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_HTTP_PROXY_CONFIGURED, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `local http wins over SOCKS (source order — both at 0_95)`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1",
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "10.0.0.1",
            )
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_LOCAL_HTTP_PROXY, ev?.value)
    }

    @Test
    fun `local http wins over http configured`() = runBlocking {
        // Cannot have BOTH http.proxyHost=127.0.0.1 AND non-loopback
        // simultaneously (single property). Test the case where local
        // http fires and any other proxy would be cascade-lower.
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1",
                HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "proxy.example.com",
            )
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_LOCAL_HTTP_PROXY, ev?.value)
    }

    @Test
    fun `SOCKS wins over http configured (0_95 over 0_85)`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "proxy.example.com",
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "10.0.0.1",
            )
        ).run(emptyCtx)
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "proxy.http_proxy_pattern" }
        assertEquals(HttpProxyProbe.PATTERN_SOCKS_PROXY, ev?.value)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `single property accessor throws — does not crash`() = runBlocking {
        val probe = makeProbeThrows(setOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST))
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
    }

    @Test
    fun `all property accessors throw — does not crash, degraded`() = runBlocking {
        val probe = makeProbeThrows(
            setOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST,
                HttpProxyProbe.PROP_HTTP_PROXY_PORT,
                HttpProxyProbe.PROP_HTTPS_PROXY_HOST,
                HttpProxyProbe.PROP_HTTPS_PROXY_PORT,
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST,
                HttpProxyProbe.PROP_SOCKS_PROXY_PORT,
                HttpProxyProbe.PROP_HTTP_NON_PROXY_HOSTS,
            )
        )
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `selective throw — other properties still observed`() = runBlocking {
        // http.proxyHost throws but socksProxyHost succeeds — SOCKS
        // rule still fires.
        val probe = HttpProxyProbe(
            jvmPropertySupplier = { key ->
                when (key) {
                    HttpProxyProbe.PROP_HTTP_PROXY_HOST ->
                        throw RuntimeException("denied")
                    HttpProxyProbe.PROP_SOCKS_PROXY_HOST -> "10.0.0.1"
                    else -> null
                }
            },
        )
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── No-arg production constructor ────────────────────────────────────────

    @Test
    fun `no-arg constructor — score 0 confidence 0_50`() = runBlocking {
        // Default supplier returns null for every key → no rule fires,
        // confidence degrades.
        val probe = HttpProxyProbe()
        val result = probe.run(emptyCtx)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isLoopbackHost detects all listed loopback identifiers`() {
        assertTrue(HttpProxyProbe.isLoopbackHost("127.0.0.1"))
        assertTrue(HttpProxyProbe.isLoopbackHost("localhost"))
        assertTrue(HttpProxyProbe.isLoopbackHost("::1"))
    }

    @Test
    fun `isLoopbackHost case-insensitive`() {
        assertTrue(HttpProxyProbe.isLoopbackHost("LOCALHOST"))
        assertTrue(HttpProxyProbe.isLoopbackHost("Localhost"))
    }

    @Test
    fun `isLoopbackHost false for non-loopback IPs`() {
        assertFalse(HttpProxyProbe.isLoopbackHost("10.0.0.1"))
        assertFalse(HttpProxyProbe.isLoopbackHost("192.168.1.1"))
        assertFalse(HttpProxyProbe.isLoopbackHost("proxy.example.com"))
        assertFalse(HttpProxyProbe.isLoopbackHost("0.0.0.0"))
    }

    @Test
    fun `isLoopbackHost false for null and empty`() {
        assertFalse(HttpProxyProbe.isLoopbackHost(null))
        assertFalse(HttpProxyProbe.isLoopbackHost(""))
    }

    // ── Drift-alarm ──────────────────────────────────────────────────────────

    @Test
    fun `LOOPBACK_HOSTS set pinned`() {
        val set = HttpProxyProbe.LOOPBACK_HOSTS
        assertTrue("127.0.0.1" in set)
        assertTrue("localhost" in set)
        assertTrue("::1" in set)
        assertEquals(3, set.size)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 9 keys`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        assertEquals(9, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "jvm.http.proxyHost",
                "jvm.http.proxyPort",
                "jvm.https.proxyHost",
                "jvm.https.proxyPort",
                "jvm.socksProxyHost",
                "jvm.socksProxyPort",
                "jvm.http.nonProxyHosts",
                "proxy.any_proxy_configured",
                "proxy.http_proxy_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `http_proxyHost evidence reflects supplier`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "jvm.http.proxyHost" }
        assertEquals("127.0.0.1", ev?.value)
    }

    @Test
    fun `http_proxyHost evidence unavailable when null`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        val ev = result.evidence.find { it.key == "jvm.http.proxyHost" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `any_proxy_configured evidence false on clean (with observation)`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(
                HttpProxyProbe.PROP_HTTP_PROXY_HOST to "",
                HttpProxyProbe.PROP_HTTPS_PROXY_HOST to "",
                HttpProxyProbe.PROP_SOCKS_PROXY_HOST to "",
            )
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.any_proxy_configured" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `any_proxy_configured evidence unknown when nothing observed`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.any_proxy_configured" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `any_proxy_configured evidence true when http set`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_PROXY_HOST to "127.0.0.1")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "proxy.any_proxy_configured" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `nonProxyHosts evidence reflects supplier`() = runBlocking {
        val result = makeProbe(
            jvmProperties = mapOf(HttpProxyProbe.PROP_HTTP_NON_PROXY_HOSTS to "localhost|*.internal")
        ).run(emptyCtx)
        val ev = result.evidence.find { it.key == "jvm.http.nonProxyHosts" }
        assertEquals("localhost|*.internal", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(emptyCtx)
        assertEquals(
            "Read JVM System.getProperty for http.proxyHost/Port + https.* + " +
                "socksProxy*; detect locally-running proxy (127.0.0.1), SOCKS " +
                "proxy, and explicit proxy host. Focused-extraction probe " +
                "separate from rank 18 VpnProxy which checks Android system " +
                "properties.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is network_http_proxy`() {
        assertEquals("network.http_proxy", makeProbe().id)
    }

    @Test
    fun `probe rank is 38`() {
        assertEquals(38, makeProbe().rank)
    }

    @Test
    fun `probe category is NETWORK`() {
        assertEquals(ProbeCategory.NETWORK, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(emptyCtx)
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

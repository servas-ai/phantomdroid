// inventory.yml rank 38, mitigation_layer L6
package com.detectorlab.probes.network

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #38 — network.http_proxy.
 *
 * Focused-extraction probe over the JVM `System.getProperty()`
 * proxy surface — DIFFERENT from rank-18 [VpnProxyProbe] which
 * checks the Android-system-property surface (`net.gprs.http-proxy`)
 * and Settings.Global. Both probes detect the same end-user intent
 * (traffic-routing-via-proxy) but at different layers; an analysis
 * kit that sets the JVM property without touching the Android props
 * (or vice versa) leaks through only one of them, so divergence
 * between the two probes is itself diagnostic.
 *
 * **Distinct surface from rank-18** (per the rank-7/9 yaml-wins
 * focused-extraction discipline):
 *   - rank-18 reads Android-level proxy state (system props
 *     `net.gprs.http-proxy`, Settings.Global `http_proxy`) via
 *     `getSystemProperty` and `querySettingSecure` accessors
 *   - rank-38 (this) reads JVM-level proxy state via `System.getProperty`
 *     for `http.proxyHost` / `https.proxyHost` / `socksProxyHost`
 *
 * Same end-user intent, different surface, **independent leak paths**.
 * mitmproxy/Charles/Burp users typically set JVM properties; corporate
 * MDM typically sets Android props. **DO NOT consolidate.**
 *
 * Signal classes (CRITICAL because each indicates traffic routing
 * through a proxy, which is dispositive for the production-target
 * evaluation surface):
 *
 *   • **Local HTTP proxy (0.95)**: `http.proxyHost` equals
 *     `127.0.0.1`, `localhost`, or `::1` (IPv6 loopback). A locally-
 *     bound HTTP proxy is the canonical mitmproxy/Charles/Burp
 *     signature — these tools run on the same device and configure
 *     the JVM to route through them. No legitimate production
 *     workflow sets a localhost HTTP proxy.
 *   • **SOCKS proxy (0.95)**: `socksProxyHost` set to any non-empty
 *     value. SOCKS proxies are unusual for production Android apps —
 *     they're a developer/research tooling signature (Frida-trace
 *     network tunneling, SSH dynamic port forwarding, etc.).
 *   • **HTTP proxy configured (0.85)**: `http.proxyHost` set to a
 *     non-localhost value OR `https.proxyHost` set. Weaker than
 *     the localhost case because corporate MDM legitimately routes
 *     through external HTTPS proxies (e.g. company TLS-inspection
 *     gateway). FP class documented below.
 *   • **Clean (0.0)**: all JVM proxy properties null/empty.
 *
 * **FP class acknowledgment** (per the rank-51 honest-framing pattern):
 * the 0.85 HTTP-proxy-configured rule fires on legitimate enterprise
 * MDM-managed devices that route through the corporate proxy gateway
 * (e.g. for compliance / DLP / TLS inspection). The 0.95 localhost-
 * proxy rule has no plausible legitimate FP class — production
 * deployments never bind a localhost HTTP proxy. The 0.95 SOCKS rule
 * has a narrow FP class (advanced enterprise SOCKS gateways exist
 * but are rare on Android targets).
 *
 * **Consumer guidance** (per the rank-50 / rank-51 / rank-52
 * consumer-gating-pattern family): the dispositive 0.85-0.95 scores
 * are calibrated for production-device evaluation. On corporate
 * MDM environments, downstream consumers should combine with the
 * MDM-presence signal from rank-50 services_processes + rank-59
 * accessibility_services to discount the score when the target is
 * a known-corporate-managed device. The gating responsibility is
 * consumer-side, not probe-side.
 *
 * **`System.getProperty` accessor gap**: `ProbeContext` exposes no
 * `getJvmSystemProperty(key)` method. Production wrapper would call
 * `System.getProperty(...)` directly (pure-JDK, no Android SDK
 * dependency). Test fakes use a constructor-injected supplier so
 * the probe is unit-testable without mocking global JVM state.
 *
 * Per the probe-invariant #2 forbidding live network requests, this
 * probe makes NONE — pure JVM-property reads.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the rank-49/51/52/53/4/7/9 partition pattern):
 *   0.95  PATTERN_LOCAL_HTTP_PROXY — `http.proxyHost` is a loopback
 *         address (127.0.0.1, localhost, ::1)
 *   0.95  PATTERN_SOCKS_PROXY — `socksProxyHost` non-empty
 *   0.85  PATTERN_HTTP_PROXY_CONFIGURED — `http.proxyHost` (non-
 *         loopback) OR `https.proxyHost` set
 *   0.00  PATTERN_CLEAN — all JVM proxy properties null/empty
 *
 * Confidence:
 *   0.95  Any of the JVM proxy properties returned non-null (regardless
 *         of value — empty vs set is a score-level distinction)
 *   0.50  All accessor calls threw (degraded; no observation possible)
 *
 * **Null-vs-empty discipline** (per the session-level policy):
 *   - null   → unobservable, contributes to confidence degradation
 *   - ""     → score-level "no proxy" (treated as not-set since real
 *              JVM proxy props can be empty as the absence sentinel)
 *
 * Reference: shared/probes/inventory.yml rank 38 (mitigation_layer L6).
 */
class HttpProxyProbe(
    private val jvmPropertySupplier: (String) -> String? = { null },
) : Probe {
    override val id = "network.http_proxy"
    override val rank = 38
    override val category = ProbeCategory.NETWORK
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_HTTP_PROXY_HOST = "http.proxyHost"
        const val PROP_HTTP_PROXY_PORT = "http.proxyPort"
        const val PROP_HTTPS_PROXY_HOST = "https.proxyHost"
        const val PROP_HTTPS_PROXY_PORT = "https.proxyPort"
        const val PROP_SOCKS_PROXY_HOST = "socksProxyHost"
        const val PROP_SOCKS_PROXY_PORT = "socksProxyPort"
        const val PROP_HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts"

        /**
         * Loopback host identifiers. A proxy bound to any of these is
         * the canonical mitmproxy/Charles/Burp signature. Match is
         * case-insensitive against the lowercased property value.
         */
        val LOOPBACK_HOSTS: Set<String> = setOf(
            "127.0.0.1",
            "localhost",
            "::1",
        )

        const val PATTERN_LOCAL_HTTP_PROXY = "local_http_proxy"
        const val PATTERN_SOCKS_PROXY = "socks_proxy"
        const val PATTERN_HTTP_PROXY_CONFIGURED = "http_proxy_configured"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_LOCAL_HTTP_PROXY = 0.95
        const val SCORE_SOCKS_PROXY = 0.95
        const val SCORE_HTTP_PROXY_CONFIGURED = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read JVM System.getProperty for http.proxyHost/Port + https.* + " +
                "socksProxy*; detect locally-running proxy (127.0.0.1), SOCKS " +
                "proxy, and explicit proxy host. Focused-extraction probe " +
                "separate from rank 18 VpnProxy which checks Android system " +
                "properties."

        /**
         * True iff [host] is a loopback identifier (`127.0.0.1`,
         * `localhost`, or `::1`). Case-insensitive comparison.
         * Returns false for null/empty.
         */
        internal fun isLoopbackHost(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            return host.lowercase() in LOOPBACK_HOSTS
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Per-property try/catch via the helper. Throwing reads
            // are treated as null (unobservable) per the session-
            // level null-vs-empty discipline; an empty-string return
            // value IS treated as "not set" at the score level.
            var anyObserved = false

            fun readJvmProp(key: String): String? {
                return try {
                    val v = jvmPropertySupplier(key)
                    if (v != null) anyObserved = true
                    v
                } catch (_: Throwable) {
                    null
                }
            }

            val httpProxyHost = readJvmProp(PROP_HTTP_PROXY_HOST)
            val httpProxyPort = readJvmProp(PROP_HTTP_PROXY_PORT)
            val httpsProxyHost = readJvmProp(PROP_HTTPS_PROXY_HOST)
            val httpsProxyPort = readJvmProp(PROP_HTTPS_PROXY_PORT)
            val socksProxyHost = readJvmProp(PROP_SOCKS_PROXY_HOST)
            val socksProxyPort = readJvmProp(PROP_SOCKS_PROXY_PORT)
            val nonProxyHosts = readJvmProp(PROP_HTTP_NON_PROXY_HOSTS)

            // Score-level predicates: empty-string is treated as
            // "not set" for the rule predicates (null-vs-empty
            // distinction at the score level — empty proxy host
            // is functionally identical to no proxy host).
            val httpHostSet = !httpProxyHost.isNullOrEmpty()
            val httpsHostSet = !httpsProxyHost.isNullOrEmpty()
            val socksHostSet = !socksProxyHost.isNullOrEmpty()

            val httpIsLoopback = httpHostSet && isLoopbackHost(httpProxyHost)
            val httpIsNonLoopback = httpHostSet && !httpIsLoopback

            val (pattern, score) = when {
                httpIsLoopback ->
                    PATTERN_LOCAL_HTTP_PROXY to SCORE_LOCAL_HTTP_PROXY
                socksHostSet ->
                    PATTERN_SOCKS_PROXY to SCORE_SOCKS_PROXY
                httpIsNonLoopback || httpsHostSet ->
                    PATTERN_HTTP_PROXY_CONFIGURED to SCORE_HTTP_PROXY_CONFIGURED
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (anyObserved) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            // Tri-state predicate evidence (rank-48/49/52/53/9/80
            // defensive-honesty pattern).
            val anyProxyConfiguredDisplay = when {
                !anyObserved -> "unknown"
                httpHostSet || httpsHostSet || socksHostSet -> "true"
                else -> "false"
            }

            val evidence = listOf(
                Evidence(
                    key = "jvm.$PROP_HTTP_PROXY_HOST",
                    value = httpProxyHost ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_HTTP_PROXY_PORT",
                    value = httpProxyPort ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_HTTPS_PROXY_HOST",
                    value = httpsProxyHost ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_HTTPS_PROXY_PORT",
                    value = httpsProxyPort ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_SOCKS_PROXY_HOST",
                    value = socksProxyHost ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_SOCKS_PROXY_PORT",
                    value = socksProxyPort ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "jvm.$PROP_HTTP_NON_PROXY_HOSTS",
                    value = nonProxyHosts ?: "<unavailable>",
                    expected = "<empty>",
                ),
                Evidence(
                    key = "proxy.any_proxy_configured",
                    value = anyProxyConfiguredDisplay,
                    expected = "false",
                ),
                Evidence(
                    key = "proxy.http_proxy_pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "HttpProxyProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

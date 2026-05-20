// inventory.yml rank 37, mitigation_layer L6
package com.detectorlab.probes.network

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #37 — network.dns_server.
 *
 * Reads the device's DNS configuration from **local state only** — no live
 * lookups, no `InetAddress.getByName`. Probe invariant #2 forbids network
 * requests; this probe makes none.
 *
 * Signal surfaces:
 *   1. `net.dns1`, `net.dns2`, `net.dns3`, `net.dns4` system properties
 *      (Android pre-Pie DNS config; still populated on many real devices).
 *   2. `ConnectivityManager.getLinkProperties(network).dnsServers`
 *      (Android Pie+) — list supplied via constructor injection, same
 *      gap-handling pattern as rank 18 [VpnProxyProbe.transportVpnFlagSupplier].
 *   3. `/etc/resolv.conf` parsed via `ctx.readFile` (rare on real Android,
 *      common on emulators).
 *   4. `Settings.Global` `private_dns_mode` + `private_dns_specifier` for
 *      DoH/DoT detection.
 *
 * **ConnectivityManager accessor gap.** `ProbeContext` exposes no
 * `queryConnectivityManager()`. Constructor-injected supplier; production
 * no-arg ctor returns null and the probe falls back to system properties
 * + resolv.conf. Same pattern as rank 18.
 *
 * **Network-type cross-reference.** The "Google DNS only on cellular"
 * rule needs the active network transport class. Reuses
 * `NetworkTypeProbe`-style supplier injection rather than a hard import
 * dependency, because the network-type itself is a separate probe and
 * we don't want a probe→probe runtime coupling.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Any DNS server is `10.0.2.3` (Android emulator qemu netns)
 *   0.85  Any DNS server is in `10.0.0.0/24` (VirtualBox host gateway range)
 *   0.85  Any DNS server is in `192.168.42.0/24` (Docker bridge default)
 *   0.85  Only Google DNS (`8.8.8.8`/`8.8.4.4`) is configured AND active
 *         transport is cellular (real cell users get carrier DNS, not just
 *         hardcoded Google)
 *   0.70  DNS server is exactly `127.0.0.1` or `::1` (local resolver — could
 *         be Pi-hole, could be emulator stub)
 *   0.50  No DNS server detected (anomaly) — capped because legitimately
 *         transient before network is up
 *   0.00  Configured DNS list looks like real ISP / carrier / DoH-DoT
 *
 * Confidence:
 *   0.95  At least one source surface (any of `net.dns*`, supplier, resolv.conf)
 *         returned at least one DNS address
 *   0.50  Nothing parseable returned (degraded)
 *
 * Reference: shared/probes/inventory.yml rank 37 (mitigation_layer L6).
 */
class DnsServerProbe(
    private val linkPropertiesDnsSupplier: () -> List<String>? = { null },
    private val activeTransportSupplier: () -> String? = { null },
) : Probe {
    override val id = "network.dns_server"
    override val rank = 37
    override val category = ProbeCategory.NETWORK
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 150L

    companion object {
        const val PATH_RESOLV_CONF = "/etc/resolv.conf"

        const val PROP_NET_DNS1 = "net.dns1"
        const val PROP_NET_DNS2 = "net.dns2"
        const val PROP_NET_DNS3 = "net.dns3"
        const val PROP_NET_DNS4 = "net.dns4"

        const val SETTING_PRIVATE_DNS_MODE = "private_dns_mode"
        const val SETTING_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

        const val DNS_ANDROID_EMU = "10.0.2.3"
        const val SUBNET_VBOX_GATEWAY_PREFIX = "10.0.0."
        const val SUBNET_DOCKER_BRIDGE_PREFIX = "192.168.42."
        const val DNS_GOOGLE_PRIMARY = "8.8.8.8"
        const val DNS_GOOGLE_SECONDARY = "8.8.4.4"
        const val DNS_LOCAL_V4 = "127.0.0.1"
        const val DNS_LOCAL_V6 = "::1"

        val GOOGLE_DNS_SET = setOf(DNS_GOOGLE_PRIMARY, DNS_GOOGLE_SECONDARY)

        const val TRANSPORT_CELLULAR = "cellular"

        const val PATTERN_EMULATOR_DNS = "emulator_dns"
        const val PATTERN_VBOX_GATEWAY = "vbox_gateway"
        const val PATTERN_DOCKER_BRIDGE = "docker_bridge"
        const val PATTERN_GOOGLE_ONLY_CELLULAR = "google_only_cellular"
        const val PATTERN_LOCAL_RESOLVER = "local_resolver"
        const val PATTERN_NO_DNS_CONFIGURED = "no_dns_configured"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMULATOR_DNS = 1.0
        const val SCORE_VBOX_GATEWAY = 0.85
        const val SCORE_DOCKER_BRIDGE = 0.85
        const val SCORE_GOOGLE_ONLY_CELLULAR = 0.85
        const val SCORE_LOCAL_RESOLVER = 0.70
        const val SCORE_NO_DNS_CONFIGURED = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read net.dns1-4 properties + ConnectivityManager link DNS + " +
                "Settings.Global private DNS; detect emulator DNS subnets " +
                "(10.0.2.x, 192.168.42.x) and isolated public-DNS-only configs. " +
                "No live DNS lookups."

        /** Parses `/etc/resolv.conf` lines, extracting `nameserver <addr>` entries. */
        internal fun parseResolvConf(raw: String?): List<String> {
            if (raw == null) return emptyList()
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filter { it.startsWith("nameserver ", ignoreCase = true) }
                .map { it.substring("nameserver ".length).trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }

        /** True iff [dns] matches the VBox `10.0.0.0/24` host-gateway range. */
        internal fun isVboxGateway(dns: String): Boolean =
            dns.startsWith(SUBNET_VBOX_GATEWAY_PREFIX) &&
                dns != DNS_ANDROID_EMU &&  // 10.0.2.3 is more specific; let it own its rule
                isValidLastOctet(dns.removePrefix(SUBNET_VBOX_GATEWAY_PREFIX))

        /** True iff [dns] matches the Docker `192.168.42.0/24` bridge range. */
        internal fun isDockerBridge(dns: String): Boolean =
            dns.startsWith(SUBNET_DOCKER_BRIDGE_PREFIX) &&
                isValidLastOctet(dns.removePrefix(SUBNET_DOCKER_BRIDGE_PREFIX))

        private fun isValidLastOctet(octet: String): Boolean {
            val n = octet.toIntOrNull() ?: return false
            return n in 0..255
        }

        /** True iff [dns] is the local-host resolver (Pi-hole, emulator stub). */
        internal fun isLocalResolver(dns: String): Boolean =
            dns == DNS_LOCAL_V4 || dns == DNS_LOCAL_V6

        /** Normalizes a transport string to canonical lowercase token. */
        internal fun normalizeTransport(raw: String?): String? =
            raw?.trim()?.lowercase()?.ifEmpty { null }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val propertyDns = listOf(
                PROP_NET_DNS1, PROP_NET_DNS2, PROP_NET_DNS3, PROP_NET_DNS4,
            ).mapNotNull { key ->
                try {
                    ctx.getSystemProperty(key)?.trim()?.ifEmpty { null }
                } catch (_: Throwable) {
                    null
                }
            }

            val linkPropertiesDns: List<String> = try {
                linkPropertiesDnsSupplier()?.mapNotNull { it.trim().ifEmpty { null } }
                    ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            val resolvConfRaw: String? = try {
                ctx.readFile(PATH_RESOLV_CONF, maxBytes = 4096)
            } catch (_: Throwable) {
                null
            }
            val resolvConfDns = parseResolvConf(resolvConfRaw)

            val allDns = (propertyDns + linkPropertiesDns + resolvConfDns)
                .map { it.lowercase() }
                .distinct()

            // Cross-cutting #3 (FIXED 2026-05-20): Private DNS settings live in
            // Settings.Global. Migrated from querySettingSecure to
            // querySettingGlobal; default delegates to Secure for backward
            // compat with existing fakes.
            val privateDnsMode: String? = try {
                ctx.querySettingGlobal(SETTING_PRIVATE_DNS_MODE)?.trim()
            } catch (_: Throwable) {
                null
            }
            val privateDnsSpecifier: String? = try {
                ctx.querySettingGlobal(SETTING_PRIVATE_DNS_SPECIFIER)?.trim()
            } catch (_: Throwable) {
                null
            }

            val transport = normalizeTransport(
                try {
                    activeTransportSupplier()
                } catch (_: Throwable) {
                    null
                },
            )

            val hasEmuDns = allDns.any { it == DNS_ANDROID_EMU }
            val hasVboxGateway = allDns.any { isVboxGateway(it) }
            val hasDockerBridge = allDns.any { isDockerBridge(it) }
            val hasLocalResolver = allDns.any { isLocalResolver(it) }

            val nonGoogleDns = allDns.filter { it !in GOOGLE_DNS_SET }
            val anyGoogleDns = allDns.any { it in GOOGLE_DNS_SET }
            val onlyGoogleOnCellular = allDns.isNotEmpty() &&
                anyGoogleDns &&
                nonGoogleDns.isEmpty() &&
                transport == TRANSPORT_CELLULAR

            val isEmulatorDefault = hasEmuDns || hasVboxGateway || hasDockerBridge

            val (pattern, score) = when {
                hasEmuDns ->
                    PATTERN_EMULATOR_DNS to SCORE_EMULATOR_DNS
                hasVboxGateway ->
                    PATTERN_VBOX_GATEWAY to SCORE_VBOX_GATEWAY
                hasDockerBridge ->
                    PATTERN_DOCKER_BRIDGE to SCORE_DOCKER_BRIDGE
                onlyGoogleOnCellular ->
                    PATTERN_GOOGLE_ONLY_CELLULAR to SCORE_GOOGLE_ONLY_CELLULAR
                hasLocalResolver ->
                    PATTERN_LOCAL_RESOLVER to SCORE_LOCAL_RESOLVER
                allDns.isEmpty() ->
                    PATTERN_NO_DNS_CONFIGURED to SCORE_NO_DNS_CONFIGURED
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (allDns.isNotEmpty() || !privateDnsMode.isNullOrEmpty()) {
                CONFIDENCE_FULL
            } else {
                CONFIDENCE_DEGRADED
            }

            val server1 = allDns.getOrNull(0) ?: "null"
            val server2 = allDns.getOrNull(1) ?: "null"
            val server3 = allDns.getOrNull(2) ?: "null"

            val evidence = listOf(
                Evidence("dns.server1", server1, expected = "<ISP DNS>"),
                Evidence("dns.server2", server2, expected = "<fallback DNS>"),
                Evidence("dns.server3", server3, expected = "<optional>"),
                Evidence(
                    key = "dns.private_dns_mode",
                    value = privateDnsMode?.ifEmpty { "<empty>" } ?: "<unreadable>",
                    expected = "off|opportunistic|hostname",
                ),
                Evidence(
                    key = "dns.private_dns_specifier",
                    value = privateDnsSpecifier?.ifEmpty { "<empty>" } ?: "<unreadable>",
                    expected = "<DoT hostname if hostname mode>",
                ),
                Evidence(
                    key = "dns.is_emulator_default",
                    value = isEmulatorDefault.toString(),
                    expected = "false",
                ),
                Evidence("dns.pattern", pattern, expected = PATTERN_CLEAN),
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
                "DnsServerProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

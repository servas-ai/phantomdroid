// inventory.yml rank 18, mitigation_layer L6
package com.detectorlab.probes.network

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #18 — network.vpn_proxy.
 *
 * Detects VPN tunnels, system-wide proxies, and container/virtualization
 * networking by reading **local state only** — `/proc/net/dev` interface
 * list, a supplier-injected ConnectivityManager `TRANSPORT_VPN` flag, and
 * the HTTP proxy system property. Probe invariant #2 forbids live network
 * requests; this probe makes none.
 *
 * Signal classes:
 *   1. **Strong VPN interface** in `/proc/net/dev`: `tun*`, `wg*`, `ppp*`
 *      → score 1.00 (any one fires).
 *   2. **`TRANSPORT_VPN` capability set** on the active network → 1.00.
 *   3. **System HTTP proxy** configured → 0.85.
 *   4. **`tap*` interface** (OpenVPN bridge, less common) → 0.85.
 *   5. **Container/virtualization interfaces** (`docker0`, `veth*`, `br-*`)
 *      → 0.70 (not a VPN, but signals lab/container environment).
 *   6. **`vboxnet*`** → 0.70.
 *
 * **`ConnectivityManager` accessor gap.** `ProbeContext` does NOT expose a
 * `queryConnectivityManager()` method. The `TRANSPORT_VPN` flag arrives via
 * a constructor-injected supplier; production no-arg constructor returns
 * null and the rule simply doesn't fire. The other three signal sources
 * (interface enumeration, system property, settings.secure HTTP_PROXY)
 * remain reachable through existing accessors, so the probe is still
 * meaningfully active in production.
 *
 * Scoring (max wins; never summed):
 *   1.00  tun* / wg* / ppp* interface OR TRANSPORT_VPN flag
 *   0.85  System HTTP proxy configured OR tap* interface
 *   0.70  Container interfaces (docker0, veth*, br-*) OR vboxnet*
 *   0.00  No VPN / proxy / container signal
 *
 * Confidence: 0.95 when at least one of (/proc/net/dev readable,
 * connectivityManager flag present, system proxy property readable) returned
 * cleanly; 0.60 when exactly one source; 0.30 when all sources blind.
 *
 * Reference: shared/probes/inventory.yml rank 18 (mitigation_layer L6).
 */
class VpnProxyProbe(
    private val transportVpnFlagSupplier: () -> Boolean? = { null },
) : Probe {
    override val id = "network.vpn_proxy"
    override val rank = 18
    override val category = ProbeCategory.NETWORK
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 300L

    companion object {
        const val PATH_PROC_NET_DEV = "/proc/net/dev"

        const val SETTING_GLOBAL_HTTP_PROXY = "http_proxy"
        const val PROP_NET_GPRS_HTTP_PROXY = "net.gprs.http-proxy"

        // Strong-VPN interface patterns (score 1.0). Allows numeric and
        // alphanumeric suffixes — `tun0`, `tun42`, `wg0`, `wg_main`,
        // `ppp_l2tp` all match. The prefix is one of tun/wg/ppp followed
        // by either nothing or [a-z0-9_-]+.
        val STRONG_VPN_INTERFACE_REGEX = Regex("^(tun|wg|ppp)([0-9_-][a-z0-9_-]*)?$")

        // Medium-confidence VPN interface (OpenVPN bridge mode)
        val TAP_INTERFACE_REGEX = Regex("^tap([0-9_-][a-z0-9_-]*)?$")

        // Container networking (docker0, veth-prefixed virtual eth, br- bridges)
        val CONTAINER_INTERFACE_REGEX = Regex("^(docker[0-9]*|veth.*|br-.*)$")

        // VirtualBox host-only networking
        val VBOX_INTERFACE_REGEX = Regex("^vboxnet[0-9]*$")

        const val SCORE_STRONG_VPN = 1.0
        const val SCORE_TRANSPORT_VPN = 1.0
        const val SCORE_PROXY_OR_TAP = 0.85
        const val SCORE_CONTAINER_OR_VBOX = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read /proc/net/dev interface list + ConnectivityManager transport " +
                "flags + Settings.Global HTTP_PROXY; classify against known VPN, " +
                "container, and virtualization interface patterns. No live network " +
                "requests."

        /**
         * Parse interface names from `/proc/net/dev`. The kernel format is:
         *   line 1: header (begins with "Inter-")
         *   line 2: column headers (begins with " face")
         *   line N: `   ifname: <stats...>` — name precedes the first colon.
         *
         * We treat both header lines defensively (skip any line that doesn't
         * have a colon in a sane position).
         */
        internal fun parseInterfaceNames(content: String): List<String> {
            val out = mutableListOf<String>()
            for (line in content.lineSequence()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx <= 0) continue
                val name = line.substring(0, colonIdx).trim()
                // Header lines have spaces / non-identifier characters in the
                // pre-colon portion. Real interface names are [a-z0-9_-]+.
                if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) continue
                out.add(name.lowercase())
            }
            return out
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // ── Signal 1: /proc/net/dev interface enumeration ────────────────
            val procNetDev: String? = try {
                ctx.readFile(PATH_PROC_NET_DEV, maxBytes = 32_768)
            } catch (_: Throwable) {
                null
            }
            val procReadable = procNetDev != null
            val interfaces: List<String> = procNetDev?.let { parseInterfaceNames(it) } ?: emptyList()

            val strongVpnIfs = interfaces.filter { STRONG_VPN_INTERFACE_REGEX.matches(it) }
            val tapIfs = interfaces.filter { TAP_INTERFACE_REGEX.matches(it) }
            val containerIfs = interfaces.filter { CONTAINER_INTERFACE_REGEX.matches(it) }
            val vboxIfs = interfaces.filter { VBOX_INTERFACE_REGEX.matches(it) }

            // ── Signal 2: TRANSPORT_VPN flag (supplier) ──────────────────────
            val transportVpnFlag: Boolean? = try {
                transportVpnFlagSupplier()
            } catch (_: Throwable) {
                null
            }
            val transportVpnObserved = transportVpnFlag != null
            val transportVpnActive = transportVpnFlag == true

            // ── Signal 3: system HTTP proxy ─────────────────────────────────
            // Cross-cutting #3 (FIXED 2026-05-20): http_proxy lives in
            // Settings.Global. Migrated to querySettingGlobal; default delegates
            // to Secure for backward compat with existing fakes.
            val secureProxy: String? = try {
                ctx.querySettingGlobal(SETTING_GLOBAL_HTTP_PROXY)
            } catch (_: Throwable) {
                null
            }
            val propProxy: String? = try {
                ctx.getSystemProperty(PROP_NET_GPRS_HTTP_PROXY)
            } catch (_: Throwable) {
                null
            }
            val proxySources = listOf(secureProxy, propProxy).filterNot { it.isNullOrEmpty() }
            val systemProxyValue = proxySources.firstOrNull()
            val systemProxyConfigured = systemProxyValue != null
            val proxyAccessorReadable = secureProxy != null || propProxy != null ||
                systemProxyConfigured

            // ── Scoring ──────────────────────────────────────────────────────
            val candidates = buildList {
                if (strongVpnIfs.isNotEmpty()) add(SCORE_STRONG_VPN)
                if (transportVpnActive) add(SCORE_TRANSPORT_VPN)
                if (systemProxyConfigured) add(SCORE_PROXY_OR_TAP)
                if (tapIfs.isNotEmpty()) add(SCORE_PROXY_OR_TAP)
                if (containerIfs.isNotEmpty()) add(SCORE_CONTAINER_OR_VBOX)
                if (vboxIfs.isNotEmpty()) add(SCORE_CONTAINER_OR_VBOX)
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            // Confidence: count sources that returned cleanly.
            // proc readable, transport flag supplier returned non-null, OR
            // either proxy accessor returned non-null. Each independent.
            val sourcesReadable = listOf(
                procReadable,
                transportVpnObserved,
                proxyAccessorReadable,
            ).count { it }
            val confidence = when {
                sourcesReadable >= 2 -> CONFIDENCE_FULL
                sourcesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val vpnInterfacesDetected = (strongVpnIfs + tapIfs)
            val transportVpnDisplay = transportVpnFlag?.toString() ?: "null"

            val evidence = listOf(
                Evidence(
                    key = "vpn.interfaces_detected",
                    value = vpnInterfacesDetected.joinToString(",").ifEmpty { "none" },
                    expected = "none",
                ),
                Evidence(
                    key = "vpn.transport_vpn_flag",
                    value = transportVpnDisplay,
                    expected = "false",
                ),
                Evidence(
                    key = "vpn.system_proxy",
                    value = systemProxyValue ?: "none",
                    expected = "none",
                ),
                Evidence(
                    key = "vpn.container_interfaces",
                    value = containerIfs.joinToString(",").ifEmpty { "none" },
                    expected = "none",
                ),
                Evidence(
                    key = "vpn.virtualization_interfaces",
                    value = vboxIfs.joinToString(",").ifEmpty { "none" },
                    expected = "none",
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
                "VpnProxyProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

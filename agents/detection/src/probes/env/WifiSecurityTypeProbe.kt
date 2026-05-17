package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.WifiSecurityType

/**
 * Probe — env.wifi_security_type (A17 N6, freeRASP T14)
 *
 * Classifies the security type of the currently associated Wi-Fi network as
 * one of: `none, wep, wpa, wpa2, wpa3, unknown`. An open or WEP-encrypted
 * link in a "production" environment correlates strongly with a research/lab
 * setup and is the freeRASP T14 signal.
 *
 * Acceptance criteria (CLO-4):
 *   1. Reads `WifiInfo` + `WifiConfiguration.KeyMgmt`; classifies the link as
 *      `{none, wep, wpa, wpa2, wpa3, unknown}`.
 *   2. API 31+ uses `WifiManager.getCurrentNetwork()` + `NetworkCapabilities`;
 *      older paths use the deprecated `WifiConfiguration.allowedKeyManagement`.
 *      The API split is gated by `Build.VERSION.SDK_INT`.
 *   3. The probe is **inert without `ACCESS_FINE_LOCATION` (API <33) /
 *      `NEARBY_WIFI_DEVICES` (API >=33)** — it returns `ProbeResult.skipped`
 *      rather than throwing or recording a `failed` result.
 *
 * The API-31-split itself is enforced inside `WifiManagerView` implementations
 * (production wrapper around `android.net.wifi.WifiManager`); the probe only
 * consults the unified `currentNetworkSecurityType()` surface and reports
 * which `apiPath` produced the answer.
 *
 * Score table:
 *   NONE          → 0.90  (open AP — strong correlate of lab/research)
 *   WEP           → 0.85  (deprecated, only seen on lab or seriously old setups)
 *   WPA           → 0.30  (legacy but still in the wild — weak signal)
 *   WPA2          → 0.05  (modern baseline, near-noise)
 *   WPA3          → 0.00  (current best practice)
 *   UNKNOWN       → 0.00  with confidence=weak (classified-but-undecipherable)
 *   NOT_CONNECTED → 0.00  with confidence=weak (no signal)
 *   UNAVAILABLE   → ProbeResult.skipped (permission not granted)
 *
 * Severity: LOW per inventory; an insecure link is signal, not certainty.
 */
class WifiSecurityTypeProbe : Probe {
    override val id = "env.wifi_security_type"
    override val rank = RANK
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 750L

    companion object {
        /** A17 N6. Inventory expansion (META-22) places this at logical 43.5;
         *  the probe-class Int rank is allocated from the 61..71 A17 reservation
         *  pending the Int-rank reconciliation called out in the META-22 PR. */
        const val RANK = 65
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val wifi = ctx.queryWifiManager()
            val sdk = wifi.sdkInt()

            // (3) Inert without permission → ProbeResult.skipped, NOT failed.
            if (!wifi.hasWifiAccessPermission()) {
                val permissionName = if (sdk >= 33) "NEARBY_WIFI_DEVICES" else "ACCESS_FINE_LOCATION"
                return ProbeResult.skipped(
                    "$permissionName not granted",
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            val read = wifi.currentNetworkSecurityType()

            // (3) Defence-in-depth: if the view returns UNAVAILABLE despite
            //     hasWifiAccessPermission()==true, still skip rather than guess.
            if (read.type == WifiSecurityType.UNAVAILABLE) {
                return ProbeResult.skipped(
                    "WifiManagerView reported UNAVAILABLE despite permission held",
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            val score = scoreFor(read.type)
            val confidence = confidenceFor(read.type)

            val evidence = listOf(
                Evidence("wifi.security_type", labelFor(read.type), expected = "wpa2_or_wpa3"),
                Evidence("Build.VERSION.SDK_INT", sdk),
                Evidence("wifi.api_path", read.apiPath),
                Evidence("wifi.permission_granted", true, expected = true),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = "WifiManager.currentNetwork (API >=31) | WifiConfiguration.KeyMgmt (API <31)",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "WifiSecurityTypeProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    internal fun scoreFor(type: WifiSecurityType): Double = when (type) {
        WifiSecurityType.NONE -> 0.90
        WifiSecurityType.WEP -> 0.85
        WifiSecurityType.WPA -> 0.30
        WifiSecurityType.WPA2 -> 0.05
        WifiSecurityType.WPA3 -> 0.00
        WifiSecurityType.UNKNOWN, WifiSecurityType.NOT_CONNECTED -> 0.00
        WifiSecurityType.UNAVAILABLE ->
            error("UNAVAILABLE must be intercepted before scoreFor()")
    }

    internal fun confidenceFor(type: WifiSecurityType): Double = when (type) {
        WifiSecurityType.NONE, WifiSecurityType.WEP -> 0.95
        WifiSecurityType.WPA -> 0.90
        WifiSecurityType.WPA2, WifiSecurityType.WPA3 -> 0.95
        WifiSecurityType.UNKNOWN, WifiSecurityType.NOT_CONNECTED -> 0.30
        WifiSecurityType.UNAVAILABLE ->
            error("UNAVAILABLE must be intercepted before confidenceFor()")
    }

    /** Lower-cased label that matches the acceptance-criterion enum spelling. */
    internal fun labelFor(type: WifiSecurityType): String = when (type) {
        WifiSecurityType.NONE -> "none"
        WifiSecurityType.WEP -> "wep"
        WifiSecurityType.WPA -> "wpa"
        WifiSecurityType.WPA2 -> "wpa2"
        WifiSecurityType.WPA3 -> "wpa3"
        WifiSecurityType.UNKNOWN -> "unknown"
        WifiSecurityType.NOT_CONNECTED -> "not_connected"
        WifiSecurityType.UNAVAILABLE -> "unavailable"
    }
}

// inventory.yml rank 25, mitigation_layer L6
package com.detectorlab.probes.network

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #25 — network.network_type.
 *
 * Detects emulator / lab networking by examining the device's transport type
 * (ConnectivityManager) and cellular data state (TelephonyManager). Operates
 * on LOCAL STATE ONLY — Probe invariant #2 forbids live network requests.
 *
 * Signal classes:
 *   1. **TRANSPORT_USB** active — emulators bridged via USB tether for
 *      Internet. Score 1.00.
 *   2. **TRANSPORT_ETHERNET** active on a phone-class device — Pixel/Galaxy
 *      phones don't have Ethernet ports. Score 0.95.
 *   3. **SIM absent + cellular data type non-UNKNOWN** — contradiction:
 *      claims cellular data but no SIM. Score 0.85.
 *   4. **WIFI-only + SIM absent + phone-class model** — phone shouldn't
 *      lack cellular hardware. Score 0.85.
 *   5. **WIFI active + SIM ready + data type UNKNOWN** — SIM inserted but
 *      no data session. Score 0.70 (weak suspicion; valid for SIMs without
 *      data plan).
 *   6. Cellular transport with valid data type, OR WIFI + valid cellular
 *      fallback — Score 0.00.
 *
 * **ConnectivityManager + TelephonyManager-data accessor gap.**
 * `ProbeContext` does NOT expose `queryConnectivityManager()`, nor does
 * `TelephonyField` enum have `DATA_NETWORK_TYPE` or `SIM_STATE` entries.
 * The three signals therefore arrive via constructor-injected suppliers;
 * the production no-arg constructor returns null for all of them and the
 * probe gracefully degrades (sourcesReadable=0, confidence=0.30, score=0.0).
 * The model-is-phone-class check still reaches via `ctx.getSystemProperty(
 * "ro.product.model")`.
 *
 * Once the core contract adds those accessors, the no-arg ctor wires them
 * in. Same pattern as rank 18 (VpnProxyProbe TRANSPORT_VPN), rank 20
 * (TimezoneLocaleProbe), rank 23 (ScreenResolutionProbe).
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  TRANSPORT_USB
 *   0.95  TRANSPORT_ETHERNET + phone-class model
 *   0.85  SIM=ABSENT + dataNetworkType != UNKNOWN (contradiction)
 *   0.85  WIFI-only + SIM=ABSENT + phone-class model
 *   0.70  WIFI active + SIM=READY + dataNetworkType=UNKNOWN
 *   0.00  Cellular with valid data type, OR WIFI + cellular fallback,
 *         OR no observable signals (graceful no-data)
 *
 * Confidence: 0.95 if ≥ 2 of (transport, dataType, simState) suppliers
 * returned non-null; 0.60 if exactly 1; 0.30 if 0.
 *
 * Reference: shared/probes/inventory.yml rank 25 (mitigation_layer L6).
 */
class NetworkTypeProbe(
    private val activeTransportSupplier: () -> Int? = { null },
    private val dataNetworkTypeSupplier: () -> Int? = { null },
    private val simStateSupplier: () -> Int? = { null },
) : Probe {
    override val id = "network.network_type"
    override val rank = 25
    override val category = ProbeCategory.NETWORK
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 150L

    companion object {
        // android.net.NetworkCapabilities.TRANSPORT_* (mirrored to avoid
        // android.* import in the pure-JVM probe).
        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_BLUETOOTH = 2
        const val TRANSPORT_ETHERNET = 3
        const val TRANSPORT_VPN = 4
        const val TRANSPORT_WIFI_AWARE = 5
        const val TRANSPORT_LOWPAN = 6
        const val TRANSPORT_USB = 8

        // android.telephony.TelephonyManager.NETWORK_TYPE_*
        const val NETWORK_TYPE_UNKNOWN = 0
        const val NETWORK_TYPE_LTE = 13
        const val NETWORK_TYPE_NR = 20

        // android.telephony.TelephonyManager.SIM_STATE_*
        const val SIM_STATE_UNKNOWN = 0
        const val SIM_STATE_ABSENT = 1
        const val SIM_STATE_READY = 5

        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /**
         * Lower-cased substrings of `ro.product.model` that indicate a
         * phone-class form factor. Tablets, watches, TVs, and Android Auto
         * have legit Ethernet/no-cellular configurations, so we ONLY apply
         * the "phone-class signals an emulator" rules when the model
         * matches this allowlist.
         */
        val PHONE_CLASS_MODEL_SUBSTRINGS: List<String> = listOf(
            "pixel ",
            "sm-s",       // Samsung Galaxy S
            "sm-g",       // Samsung Galaxy (older)
            "sm-a",       // Samsung Galaxy A
            "sm-n",       // Samsung Galaxy Note
            "sm-f",       // Samsung Galaxy Z Fold
            "oneplus",
            "redmi",
            "mi ",        // Xiaomi Mi
            "oppo",
            "vivo",
            "realme",
            "huawei",
            "motorola",
            "sony xperia",
        )

        const val PATTERN_USB = "usb"
        const val PATTERN_ETHERNET = "ethernet"
        const val PATTERN_WIFI_ONLY_NO_SIM = "wifi_only_no_sim"
        const val PATTERN_SIM_ABSENT_DATA_PRESENT = "sim_absent_data_present"
        const val PATTERN_SIM_READY_DATA_UNKNOWN = "sim_ready_data_unknown"
        const val PATTERN_NONE = "none"

        const val SCORE_USB_TRANSPORT = 1.0
        const val SCORE_ETHERNET_ON_PHONE = 0.95
        const val SCORE_SIM_ABSENT_DATA_PRESENT = 0.85
        const val SCORE_WIFI_NO_SIM_PHONE = 0.85
        const val SCORE_SIM_READY_DATA_UNKNOWN = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read ConnectivityManager.activeNetwork transport + " +
                "TelephonyManager.dataNetworkType + simState; classify " +
                "WiFi-only-no-SIM and TRANSPORT_USB as emulator signals. " +
                "No live network requests."

        internal fun transportName(t: Int?): String = when (t) {
            null -> "null"
            TRANSPORT_CELLULAR -> "CELLULAR"
            TRANSPORT_WIFI -> "WIFI"
            TRANSPORT_BLUETOOTH -> "BLUETOOTH"
            TRANSPORT_ETHERNET -> "ETHERNET"
            TRANSPORT_VPN -> "VPN"
            TRANSPORT_WIFI_AWARE -> "WIFI_AWARE"
            TRANSPORT_LOWPAN -> "LOWPAN"
            TRANSPORT_USB -> "USB"
            else -> "OTHER($t)"
        }

        internal fun simStateName(s: Int?): String = when (s) {
            null -> "null"
            SIM_STATE_UNKNOWN -> "UNKNOWN"
            SIM_STATE_ABSENT -> "ABSENT"
            SIM_STATE_READY -> "READY"
            else -> "OTHER($s)"
        }

        internal fun isPhoneClassModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return PHONE_CLASS_MODEL_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val transport: Int? = try { activeTransportSupplier() } catch (_: Throwable) { null }
            val dataType: Int? = try { dataNetworkTypeSupplier() } catch (_: Throwable) { null }
            val simState: Int? = try { simStateSupplier() } catch (_: Throwable) { null }

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = isPhoneClassModel(model)

            // Signal evaluation
            val isUsb = transport == TRANSPORT_USB
            val isEthernetOnPhone = transport == TRANSPORT_ETHERNET && phoneClass
            val simAbsent = simState == SIM_STATE_ABSENT
            val simReady = simState == SIM_STATE_READY
            val dataPresent = dataType != null && dataType != NETWORK_TYPE_UNKNOWN
            val dataUnknown = dataType == NETWORK_TYPE_UNKNOWN
            val wifiActive = transport == TRANSPORT_WIFI

            val isContradiction = simAbsent && dataPresent
            val isWifiOnlyNoSimPhone = wifiActive && simAbsent && phoneClass
            val isSimReadyDataUnknown = wifiActive && simReady && dataUnknown

            val (pattern, score) = when {
                isUsb ->
                    PATTERN_USB to SCORE_USB_TRANSPORT
                isEthernetOnPhone ->
                    PATTERN_ETHERNET to SCORE_ETHERNET_ON_PHONE
                isContradiction ->
                    PATTERN_SIM_ABSENT_DATA_PRESENT to SCORE_SIM_ABSENT_DATA_PRESENT
                isWifiOnlyNoSimPhone ->
                    PATTERN_WIFI_ONLY_NO_SIM to SCORE_WIFI_NO_SIM_PHONE
                isSimReadyDataUnknown ->
                    PATTERN_SIM_READY_DATA_UNKNOWN to SCORE_SIM_READY_DATA_UNKNOWN
                else ->
                    PATTERN_NONE to SCORE_CLEAN
            }

            val sourcesReadable = listOf(transport, dataType, simState).count { it != null }
            val confidence = when {
                sourcesReadable >= 2 -> CONFIDENCE_FULL
                sourcesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val cellularCapable = transport == TRANSPORT_CELLULAR ||
                (simState != null && simState != SIM_STATE_ABSENT)

            val evidence = listOf(
                Evidence(
                    key = "net.active_transport",
                    value = transportName(transport),
                    expected = "WIFI or CELLULAR",
                ),
                Evidence(
                    key = "net.has_cellular_capability",
                    value = cellularCapable.toString(),
                    expected = "true (on phone-class device)",
                ),
                Evidence(
                    key = "telephony.data_network_type",
                    value = dataType?.toString() ?: "null",
                    expected = "!= UNKNOWN if cellular phone",
                ),
                Evidence(
                    key = "telephony.sim_state",
                    value = simStateName(simState),
                    expected = "READY on phone with SIM",
                ),
                Evidence(
                    key = "net.is_emulator_pattern",
                    value = pattern,
                    expected = PATTERN_NONE,
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
                "NetworkTypeProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

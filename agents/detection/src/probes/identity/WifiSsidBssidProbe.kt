// inventory.yml rank 32, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #32 — identity.wifi_ssid_bssid.
 *
 * Reads the currently-connected Wi-Fi network's SSID and BSSID from
 * `WifiManager.connectionInfo` (WifiInfo). Looks for emulator-default
 * SSIDs (`AndroidWifi`, `Genymotion`), emulator-OUI BSSIDs (cross-checked
 * against rank-15 [WifiMacProbe]), all-zero / invalid BSSID, and the
 * structural contradiction `connectionState == "connected"` with
 * `ssid == null/empty`.
 *
 * **WifiManagerView SSID/BSSID gap.** The shared `WifiManagerView`
 * (`ProbeContext.kt:155`) deliberately omits SSID/BSSID accessors —
 * its KDoc says "this view deliberately avoids exposing PII-grade
 * fields". This probe accepts three constructor-injected suppliers
 * instead and falls back to confidence 0.50 when only the production
 * no-arg ctor is used (suppliers return null). The KDoc + invariant
 * tests anchor the gap so a future view extension can lift the cap to
 * 0.95 with minimal change.
 *
 * Same supplier-injection pattern as rank-31 ([BluetoothMacProbe]).
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  SSID == `AndroidWifi` (Android emulator hardcoded SSID)
 *   1.00  SSID == `Genymotion` (Genymotion hardcoded SSID)
 *   1.00  BSSID OUI matches QEMU (`52:54:00`) or VirtualBox (`08:00:27`)
 *   1.00  BSSID == `00:00:00:00:00:00` (all-zero)
 *   0.95  BSSID OUI matches VMware / Hyper-V / Xen / Docker
 *   0.85  SSID is null/empty AND connectionState == `connected`
 *         (structural contradiction)
 *   0.50  BSSID == `02:00:00:00:00:00` AND SSID == `<unknown ssid>`
 *         (Android-8+ privacy default, low-confidence baseline)
 *   0.00  Both fields readable and values look real
 *
 * Confidence: 0.95 if both SSID and BSSID suppliers returned non-null;
 * 0.60 if exactly one; 0.50 if neither (matches the production no-arg
 * ctor case where the SSID/BSSID gap dominates rather than a transient
 * read failure on a real device).
 *
 * Reference: shared/probes/inventory.yml rank 32 (mitigation_layer L2).
 */
class WifiSsidBssidProbe(
    private val ssidSupplier: () -> String? = { null },
    private val bssidSupplier: () -> String? = { null },
    private val connectionStateSupplier: () -> String? = { null },
) : Probe {
    override val id = "identity.wifi_ssid_bssid"
    override val rank = 32
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val SSID_ANDROID_EMU = "AndroidWifi"
        const val SSID_GENYMOTION = "Genymotion"
        const val SSID_UNKNOWN_LITERAL = "<unknown ssid>"

        const val BSSID_ZERO = "00:00:00:00:00:00"
        const val BSSID_ANDROID8_PRIVACY_DEFAULT = "02:00:00:00:00:00"

        const val CONN_STATE_CONNECTED = "connected"
        const val CONN_STATE_DISCONNECTED = "disconnected"

        const val PATTERN_EMU_SSID_ANDROID = "emu_ssid_androidwifi"
        const val PATTERN_EMU_SSID_GENYMOTION = "emu_ssid_genymotion"
        const val PATTERN_EMU_OUI_QEMU_VBOX = "emu_oui_qemu_vbox"
        const val PATTERN_EMU_OUI_OTHER_VM = "emu_oui_other_vm"
        const val PATTERN_BSSID_ZERO = "bssid_zero"
        const val PATTERN_EMPTY_SSID_CONNECTED = "empty_ssid_connected"
        const val PATTERN_PRIVACY_DEFAULT_BENIGN = "privacy_default_benign"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_SSID = 1.0
        const val SCORE_EMU_OUI_QEMU_VBOX = 1.0
        const val SCORE_BSSID_ZERO = 1.0
        const val SCORE_EMU_OUI_OTHER_VM = 0.95
        const val SCORE_EMPTY_SSID_CONNECTED = 0.85
        const val SCORE_PRIVACY_DEFAULT_BENIGN = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read WifiManager.connectionInfo SSID + BSSID; detect emulator-default " +
                "SSIDs (AndroidWifi/Genymotion) + cross-reference rank-15 OUI table " +
                "for BSSID prefix"

        /**
         * Strips the surrounding quotes WifiInfo returns (`"\"HomeWifi\""`).
         * Returns null on null input or after stripping yields empty string.
         */
        internal fun normalizeSsid(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val unquoted = if (trimmed.length >= 2 &&
                trimmed.startsWith('"') && trimmed.endsWith('"')
            ) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            return unquoted.ifEmpty { null }
        }

        /** True for SSIDs that are too long for the 802.11 spec or zero-length. */
        internal fun isInvalidSsidLength(ssid: String?): Boolean {
            if (ssid == null) return true
            val len = ssid.toByteArray(Charsets.UTF_8).size
            return len < 1 || len > 32
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val ssidRaw: String? = try {
                ssidSupplier()
            } catch (_: Throwable) {
                null
            }
            val bssidRaw: String? = try {
                bssidSupplier()
            } catch (_: Throwable) {
                null
            }
            val connStateRaw: String? = try {
                connectionStateSupplier()
            } catch (_: Throwable) {
                null
            }

            val ssidSupplierReturned = ssidRaw != null
            val bssidSupplierReturned = bssidRaw != null

            val ssidNormalized = normalizeSsid(ssidRaw)
            val bssid = WifiMacProbe.normalizeMac(bssidRaw)

            val isUnknownLiteral = ssidRaw?.trim() == SSID_UNKNOWN_LITERAL
            val ssidEmpty = ssidNormalized == null && !isUnknownLiteral

            val bssidEmuTag = if (bssid != null) WifiMacProbe.classifyOui(bssid) else
                WifiMacProbe.EMU_TAG_NONE
            val bssidIsZero = bssid == BSSID_ZERO
            val bssidIsPrivacyDefault = bssid == BSSID_ANDROID8_PRIVACY_DEFAULT

            val connStateNormalized = connStateRaw?.trim()?.lowercase()
            val isConnected = connStateNormalized == CONN_STATE_CONNECTED

            val (pattern, score) = when {
                ssidNormalized == SSID_ANDROID_EMU ->
                    PATTERN_EMU_SSID_ANDROID to SCORE_EMU_SSID
                ssidNormalized == SSID_GENYMOTION ->
                    PATTERN_EMU_SSID_GENYMOTION to SCORE_EMU_SSID
                bssidEmuTag == WifiMacProbe.EMU_TAG_QEMU ||
                    bssidEmuTag == WifiMacProbe.EMU_TAG_VBOX ->
                    PATTERN_EMU_OUI_QEMU_VBOX to SCORE_EMU_OUI_QEMU_VBOX
                bssidIsZero ->
                    PATTERN_BSSID_ZERO to SCORE_BSSID_ZERO
                bssidEmuTag == WifiMacProbe.EMU_TAG_VMWARE ||
                    bssidEmuTag == WifiMacProbe.EMU_TAG_HYPERV ||
                    bssidEmuTag == WifiMacProbe.EMU_TAG_XEN ||
                    bssidEmuTag == WifiMacProbe.EMU_TAG_DOCKER ->
                    PATTERN_EMU_OUI_OTHER_VM to SCORE_EMU_OUI_OTHER_VM
                ssidEmpty && isConnected ->
                    PATTERN_EMPTY_SSID_CONNECTED to SCORE_EMPTY_SSID_CONNECTED
                bssidIsPrivacyDefault && isUnknownLiteral ->
                    PATTERN_PRIVACY_DEFAULT_BENIGN to SCORE_PRIVACY_DEFAULT_BENIGN
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val surfacesReadable = listOf(ssidSupplierReturned, bssidSupplierReturned)
                .count { it }
            val confidence = when {
                surfacesReadable >= 2 -> CONFIDENCE_FULL
                surfacesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val ssidDisplay = ssidNormalized
                ?: ssidRaw?.trim()?.ifEmpty { "<empty>" }
                ?: "null"
            val bssidDisplay = bssid ?: bssidRaw?.trim()?.ifEmpty { "<empty>" } ?: "null"
            val ssidLengthDisplay = if (ssidNormalized != null) {
                ssidNormalized.toByteArray(Charsets.UTF_8).size.toString()
            } else {
                "<unreadable>"
            }
            val bssidOuiDisplay = if (bssid != null) WifiMacProbe.oui(bssid) else "<unreadable>"
            val connStateDisplay = connStateNormalized ?: "null"
            val isEmuSsid = ssidNormalized == SSID_ANDROID_EMU ||
                ssidNormalized == SSID_GENYMOTION

            val evidence = listOf(
                Evidence(
                    key = "wifi.ssid",
                    value = ssidDisplay,
                    expected = "<real SSID, not AndroidWifi/Genymotion>",
                ),
                Evidence("wifi.bssid", bssidDisplay, expected = "<real OUI>"),
                Evidence("wifi.ssid_length", ssidLengthDisplay, expected = "1-32"),
                Evidence(
                    key = "wifi.bssid_oui",
                    value = bssidOuiDisplay,
                    expected = "<known IEEE OEM, not emulator OUI>",
                ),
                Evidence(
                    key = "wifi.connection_state",
                    value = connStateDisplay,
                    expected = "connected if scoring requires it",
                ),
                Evidence(
                    key = "wifi.is_emulator_ssid",
                    value = isEmuSsid.toString(),
                    expected = "false",
                ),
                Evidence("wifi.pattern", pattern, expected = PATTERN_CLEAN),
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
                "WifiSsidBssidProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

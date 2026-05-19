// inventory.yml rank 15, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #15 — identity.wifi_mac.
 *
 * Reads the WiFi adapter's MAC address from `/sys/class/net/wlan0/address`
 * and validates against:
 *   1. Zero / privacy-default values (`00:00:00:00:00:00`,
 *      `02:00:00:00:00:00`).
 *   2. Known emulator / virtualization vendor OUIs (QEMU `52:54:00`,
 *      VirtualBox `08:00:27`, VMware four-OUI set, Hyper-V `00:15:5d`,
 *      Xen `00:16:3e`, Docker `0a:00:27`).
 *   3. IEEE U/L bit (`firstByte & 0x02`): locally-administered MACs are rare
 *      on real Android handsets and common on randomized/emulator stacks.
 *
 * **WifiManager.getMacAddress gap.** The current `WifiManagerView`
 * (`ProbeContext.kt:155`) does NOT expose a MAC accessor — it surfaces
 * `sdkInt()`, `hasWifiAccessPermission()`, and `currentNetworkSecurityType()`
 * only. The cross-check "WifiManager returns `02:00:00:00:00:00` AND sysfs
 * returns the real MAC" pattern collapses to "sysfs returns `02:...`" in
 * this probe; the latter is itself a strong signal (a privileged sysfs read
 * shouldn't be capped at the Android-10+ privacy default).
 *
 * Confidence caps at 0.60 because only one of the two intended surfaces is
 * readable. Once `WifiManagerView` exposes a `macAddress()` reader, the cap
 * lifts to 0.95 (both surfaces) and the cross-check rule becomes
 * implementable. The KDoc + a regression test anchor the gap.
 *
 * Scoring (max wins; never summed):
 *   1.00  MAC == `00:00:00:00:00:00` (zero — unset or stub)
 *   1.00  MAC OUI in {QEMU `52:54:00`, VirtualBox `08:00:27`}
 *   0.95  MAC OUI in {VMware ×4, Hyper-V `00:15:5d`, Xen `00:16:3e`,
 *         Docker `0a:00:27`}
 *   0.85  MAC == `02:00:00:00:00:00` from sysfs (privileged read returning
 *         the Android-10+ privacy default — strong mitigation-failure tell)
 *   0.80  MAC has locally-administered bit set AND OUI doesn't match any
 *         known emulator/virtualization vendor (suspect random/spoofed MAC)
 *   0.50  Sysfs unreadable / threw / returned malformed value
 *   0.00  MAC has a real globally-administered OUI not in the emulator list
 *
 * Confidence: 0.60 when sysfs returns a parseable MAC (one of two surfaces
 * accessible); 0.30 when sysfs is unreachable (zero surfaces accessible).
 * Never reaches 0.95 in the current ProbeContext.
 *
 * Reference: shared/probes/inventory.yml rank 15 (mitigation_layer L2).
 */
class WifiMacProbe : Probe {
    override val id = "identity.wifi_mac"
    override val rank = 15
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        const val PATH_WLAN0_ADDRESS = "/sys/class/net/wlan0/address"

        const val MAC_ZERO = "00:00:00:00:00:00"
        const val MAC_ANDROID10_PRIVACY_DEFAULT = "02:00:00:00:00:00"

        /**
         * OUI prefixes (first three octets, lowercase, colon-joined) of
         * known emulator / virtualization vendors. Score 1.00 = certainly
         * an emulator (no real device ships these); score 0.95 = could be
         * a VM but might also be a niche real OEM (treat as strong but not
         * certain).
         */
        val OUI_QEMU = setOf("52:54:00")
        val OUI_VBOX = setOf("08:00:27")
        val OUI_VMWARE = setOf("00:05:69", "00:0c:29", "00:50:56", "00:1c:14")
        val OUI_HYPERV = setOf("00:15:5d")
        val OUI_XEN = setOf("00:16:3e")
        val OUI_DOCKER = setOf("0a:00:27")

        const val EMU_TAG_QEMU = "qemu"
        const val EMU_TAG_VBOX = "vbox"
        const val EMU_TAG_VMWARE = "vmware"
        const val EMU_TAG_HYPERV = "hyperv"
        const val EMU_TAG_XEN = "xen"
        const val EMU_TAG_DOCKER = "docker"
        const val EMU_TAG_NONE = "none"

        const val SCORE_ZERO_MAC = 1.0
        const val SCORE_QEMU_OR_VBOX = 1.0
        const val SCORE_OTHER_EMU = 0.95
        const val SCORE_PRIVACY_DEFAULT_VIA_SYSFS = 0.85
        const val SCORE_LOCALLY_ADMINISTERED = 0.80
        const val SCORE_NO_SIGNAL = 0.50
        const val SCORE_CLEAN = 0.0

        /**
         * Confidence cap when only sysfs is available. Once WifiManagerView
         * exposes a MAC accessor, lift to 0.95.
         */
        const val CONFIDENCE_CAP_SINGLE_SURFACE = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read WifiManager.connectionInfo.macAddress + /sys/class/net/wlan0/address, " +
                "validate OUI prefix against emulator-vendor allowlist, " +
                "check locally-administered bit"

        private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** Returns the lowercase normalised MAC, or null if input is malformed. */
        internal fun normalizeMac(raw: String?): String? {
            val trimmed = raw?.trim()?.lowercase() ?: return null
            return if (MAC_REGEX.matches(trimmed)) trimmed else null
        }

        /** Returns the first byte of [mac] as an Int, or null if malformed. */
        internal fun firstByte(mac: String): Int? =
            mac.substringBefore(':').toIntOrNull(16)

        /** Returns the colon-joined OUI prefix `"xx:xx:xx"`. */
        internal fun oui(mac: String): String = mac.split(':').take(3).joinToString(":")

        internal fun classifyOui(mac: String): String {
            val o = oui(mac)
            return when (o) {
                in OUI_QEMU -> EMU_TAG_QEMU
                in OUI_VBOX -> EMU_TAG_VBOX
                in OUI_VMWARE -> EMU_TAG_VMWARE
                in OUI_HYPERV -> EMU_TAG_HYPERV
                in OUI_XEN -> EMU_TAG_XEN
                in OUI_DOCKER -> EMU_TAG_DOCKER
                else -> EMU_TAG_NONE
            }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val raw: String? = try {
                ctx.readFile(PATH_WLAN0_ADDRESS, maxBytes = 32)
            } catch (_: Throwable) {
                null
            }

            val mac = normalizeMac(raw)
            val sysfsReadable = mac != null

            val emuTag = if (mac != null) classifyOui(mac) else EMU_TAG_NONE
            val firstByte = mac?.let { firstByte(it) } ?: 0
            val locallyAdministered = mac != null && (firstByte and 0x02) != 0
            val multicast = mac != null && (firstByte and 0x01) != 0

            val isZeroMac = mac == MAC_ZERO
            val isPrivacyDefault = mac == MAC_ANDROID10_PRIVACY_DEFAULT

            val candidates = buildList {
                if (isZeroMac) add(SCORE_ZERO_MAC)
                when (emuTag) {
                    EMU_TAG_QEMU, EMU_TAG_VBOX -> add(SCORE_QEMU_OR_VBOX)
                    EMU_TAG_VMWARE, EMU_TAG_HYPERV, EMU_TAG_XEN, EMU_TAG_DOCKER ->
                        add(SCORE_OTHER_EMU)
                    else -> { /* no contribution */ }
                }
                if (isPrivacyDefault && sysfsReadable) add(SCORE_PRIVACY_DEFAULT_VIA_SYSFS)
                if (sysfsReadable && locallyAdministered && emuTag == EMU_TAG_NONE &&
                    !isZeroMac && !isPrivacyDefault
                ) {
                    add(SCORE_LOCALLY_ADMINISTERED)
                }
            }
            val score = when {
                candidates.isNotEmpty() -> candidates.max()
                sysfsReadable -> SCORE_CLEAN
                else -> SCORE_NO_SIGNAL
            }

            val confidence = if (sysfsReadable) CONFIDENCE_CAP_SINGLE_SURFACE else CONFIDENCE_DEGRADED

            val displayMac = mac ?: raw?.trim() ?: "<unreadable>"
            val firstByteHex = if (mac != null) {
                "0x" + String.format("%02x", firstByte and 0xFF)
            } else "<unreadable>"

            val evidence = listOf(
                Evidence(
                    key = "wifi_mac.wifimanager",
                    value = "<unavailable: WifiManagerView lacks macAddress accessor>",
                    expected = "<real-OUI mac>",
                ),
                Evidence("wifi_mac.sysfs", displayMac, expected = "<real-OUI mac>"),
                Evidence(
                    key = "wifi_mac.oui_first_byte",
                    value = firstByteHex,
                    expected = "<known IEEE-registered OEM OUI>",
                ),
                Evidence(
                    key = "wifi_mac.locally_administered",
                    value = locallyAdministered.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "wifi_mac.multicast_bit",
                    value = multicast.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "wifi_mac.emulator_oui_match",
                    value = emuTag,
                    expected = EMU_TAG_NONE,
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
                "WifiMacProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 31, mitigation_layer L2
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #31 — identity.bluetooth_mac.
 *
 * Reads the device's Bluetooth MAC address from two surfaces:
 *   1. `BluetoothAdapter.getDefaultAdapter().getAddress()` (via constructor-
 *      injected supplier — `ProbeContext` exposes no BluetoothAdapter
 *      accessor)
 *   2. `/sys/class/bluetooth/hci0/address` (via `ctx.readFile`)
 *
 * Validates against the same OUI surface as rank-15 (WifiMacProbe). The
 * `WifiMacProbe` companion exposes the OUI sets (`OUI_QEMU`, `OUI_VBOX`,
 * `OUI_VMWARE`, `OUI_HYPERV`, `OUI_XEN`) plus the `normalizeMac`,
 * `firstByte`, `oui`, and `classifyOui` helpers via `internal` visibility.
 * This probe **reuses them directly**, with an invariant test anchoring
 * the cross-rank dependency per the rank-17 extract-or-anchor pattern.
 *
 * **BluetoothAdapter accessor gap.** ProbeContext exposes no
 * `queryBluetoothAdapter()`. Constructor-injected supplier; production
 * no-arg ctor returns null and the probe falls back to sysfs-only.
 * Same pattern as rank 15 (WifiManagerView lacks macAddress accessor).
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  Either MAC is `00:00:00:00:00:00` (zero — uninitialized)
 *   1.00  Either MAC matches `aa:bb:cc:dd:ee:ff` or `de:ad:be:ef:*` test
 *         fixture
 *   1.00  Either MAC OUI is QEMU (`52:54:00`) or VirtualBox (`08:00:27`)
 *   0.95  Either MAC OUI is VMware / Hyper-V / Xen
 *   0.85  Adapter returns `02:00:00:00:00:00` AND sysfs returns same value
 *         (privileged sysfs shouldn't be capped at the Android-6+ privacy
 *         default — strong tampering tell)
 *   0.85  Adapter is null AND model is phone-class (Bluetooth unsupported
 *         on a phone shouldn't happen)
 *   0.80  Locally-administered bit set on phone-class device, NOT in any
 *         known emulator OUI (suspect random/spoofed MAC)
 *   0.50  Adapter returns `02:00:00:00:00:00` AND sysfs is unreadable
 *         (legitimate Android-6+ privacy default — low confidence signal)
 *   0.00  Real-OEM-OUI MAC on both surfaces
 *
 * Confidence: 0.95 if both surfaces returned a parseable MAC; 0.60 if
 * only one; 0.30 if neither.
 *
 * Reference: shared/probes/inventory.yml rank 31 (mitigation_layer L2).
 */
class BluetoothMacProbe(
    private val bluetoothAdapterMacSupplier: () -> String? = { null },
) : Probe {
    override val id = "identity.bluetooth_mac"
    override val rank = 31
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val PATH_HCI0_ADDRESS = "/sys/class/bluetooth/hci0/address"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val MAC_ZERO = "00:00:00:00:00:00"
        const val MAC_ANDROID6_PRIVACY_DEFAULT = "02:00:00:00:00:00"

        /** Test-fixture MAC strings that no real device or vendor ever ships. */
        val TEST_FIXTURE_MACS_EXACT: Set<String> = setOf(
            "aa:bb:cc:dd:ee:ff",
            "11:22:33:44:55:66",
        )

        /** Test-fixture OUI prefixes (e.g. "deadbeef" MAC patterns). */
        val TEST_FIXTURE_OUI_PREFIXES: Set<String> = setOf(
            "de:ad:be",        // "deadbeef" placeholder pattern
            "ca:fe:ba",        // "cafebabe" placeholder pattern
        )

        const val EMU_TAG_QEMU = "qemu"
        const val EMU_TAG_VBOX = "vbox"
        const val EMU_TAG_VMWARE = "vmware"
        const val EMU_TAG_HYPERV = "hyperv"
        const val EMU_TAG_XEN = "xen"
        const val EMU_TAG_TEST_FIXTURE = "test_fixture"
        const val EMU_TAG_NONE = "none"

        const val PATTERN_ZERO_MAC = "zero_mac"
        const val PATTERN_TEST_FIXTURE = "test_fixture"
        const val PATTERN_EMULATOR_OUI = "emulator_oui"
        const val PATTERN_PRIVACY_DEFAULT_VIA_SYSFS = "privacy_default_via_sysfs"
        const val PATTERN_NULL_ADAPTER_ON_PHONE = "null_adapter_on_phone"
        const val PATTERN_LOCALLY_ADMINISTERED = "locally_administered"
        const val PATTERN_PRIVACY_DEFAULT_BENIGN = "privacy_default_benign"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_ZERO_OR_TEST_OR_QEMU_VBOX = 1.0
        const val SCORE_OTHER_VM_OUI = 0.95
        const val SCORE_PRIVACY_DEFAULT_VIA_SYSFS = 0.85
        const val SCORE_NULL_ADAPTER_ON_PHONE = 0.85
        const val SCORE_LOCALLY_ADMINISTERED = 0.80
        const val SCORE_PRIVACY_DEFAULT_BENIGN = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read BluetoothAdapter.address + /sys/class/bluetooth/hci0/address; " +
                "validate OUI prefix against emulator-vendor allowlist; check " +
                "locally-administered bit. Cross-reference rank-15 WifiMac OUI table."

        internal fun isTestFixtureMac(mac: String?): Boolean {
            if (mac == null) return false
            if (mac in TEST_FIXTURE_MACS_EXACT) return true
            val oui = WifiMacProbe.oui(mac)
            return oui in TEST_FIXTURE_OUI_PREFIXES
        }

        /**
         * Classifies a normalized MAC string against both the rank-15
         * WifiMacProbe emulator OUI set and the local test-fixture set.
         * Returns one of the EMU_TAG_* constants.
         */
        internal fun classifyOuiCombined(mac: String?): String {
            if (mac == null) return EMU_TAG_NONE
            if (isTestFixtureMac(mac)) return EMU_TAG_TEST_FIXTURE
            return WifiMacProbe.classifyOui(mac)
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val adapterRaw: String? = try {
                bluetoothAdapterMacSupplier()
            } catch (_: Throwable) {
                null
            }
            val adapterMac = WifiMacProbe.normalizeMac(adapterRaw)
            val adapterReadable = adapterMac != null
            val adapterSupplierReturned = adapterRaw != null

            val sysfsRaw: String? = try {
                ctx.readFile(PATH_HCI0_ADDRESS, maxBytes = 32)
            } catch (_: Throwable) {
                null
            }
            val sysfsMac = WifiMacProbe.normalizeMac(sysfsRaw)
            val sysfsReadable = sysfsMac != null

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }
            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            // Pre-compute combined classifications across both MACs.
            val adapterEmuTag = classifyOuiCombined(adapterMac)
            val sysfsEmuTag = classifyOuiCombined(sysfsMac)

            val anyZero = adapterMac == MAC_ZERO || sysfsMac == MAC_ZERO
            val anyTestFixture = adapterEmuTag == EMU_TAG_TEST_FIXTURE ||
                sysfsEmuTag == EMU_TAG_TEST_FIXTURE
            val anyQemuVbox = adapterEmuTag in setOf(EMU_TAG_QEMU, EMU_TAG_VBOX) ||
                sysfsEmuTag in setOf(EMU_TAG_QEMU, EMU_TAG_VBOX)
            val anyOtherVm = adapterEmuTag in setOf(EMU_TAG_VMWARE, EMU_TAG_HYPERV, EMU_TAG_XEN) ||
                sysfsEmuTag in setOf(EMU_TAG_VMWARE, EMU_TAG_HYPERV, EMU_TAG_XEN)

            val privacyDefaultViaSysfs =
                adapterMac == MAC_ANDROID6_PRIVACY_DEFAULT &&
                    sysfsMac == MAC_ANDROID6_PRIVACY_DEFAULT
            val privacyDefaultBenign =
                adapterMac == MAC_ANDROID6_PRIVACY_DEFAULT && !sysfsReadable

            val nullAdapterOnPhone = !adapterSupplierReturned && phoneClass

            fun isLocallyAdministered(mac: String?): Boolean {
                if (mac == null || mac == MAC_ANDROID6_PRIVACY_DEFAULT) return false
                return WifiMacProbe.firstByte(mac)?.let { (it and 0x02) != 0 } ?: false
            }

            val locallyAdministered = (isLocallyAdministered(adapterMac) ||
                isLocallyAdministered(sysfsMac)) && phoneClass &&
                adapterEmuTag == EMU_TAG_NONE && sysfsEmuTag == EMU_TAG_NONE &&
                !anyZero && !anyTestFixture

            val (pattern, score) = when {
                anyZero ->
                    PATTERN_ZERO_MAC to SCORE_ZERO_OR_TEST_OR_QEMU_VBOX
                anyTestFixture ->
                    PATTERN_TEST_FIXTURE to SCORE_ZERO_OR_TEST_OR_QEMU_VBOX
                anyQemuVbox ->
                    PATTERN_EMULATOR_OUI to SCORE_ZERO_OR_TEST_OR_QEMU_VBOX
                anyOtherVm ->
                    PATTERN_EMULATOR_OUI to SCORE_OTHER_VM_OUI
                privacyDefaultViaSysfs ->
                    PATTERN_PRIVACY_DEFAULT_VIA_SYSFS to SCORE_PRIVACY_DEFAULT_VIA_SYSFS
                nullAdapterOnPhone ->
                    PATTERN_NULL_ADAPTER_ON_PHONE to SCORE_NULL_ADAPTER_ON_PHONE
                locallyAdministered ->
                    PATTERN_LOCALLY_ADMINISTERED to SCORE_LOCALLY_ADMINISTERED
                privacyDefaultBenign ->
                    PATTERN_PRIVACY_DEFAULT_BENIGN to SCORE_PRIVACY_DEFAULT_BENIGN
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val surfacesReadable = listOf(adapterReadable, sysfsReadable).count { it }
            val confidence = when {
                surfacesReadable >= 2 -> CONFIDENCE_FULL
                surfacesReadable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            // Combined OUI-match tag — pick the first non-NONE tag seen.
            val combinedEmuTag = when {
                adapterEmuTag != EMU_TAG_NONE -> adapterEmuTag
                sysfsEmuTag != EMU_TAG_NONE -> sysfsEmuTag
                else -> EMU_TAG_NONE
            }

            // Use whichever MAC was readable for the first-byte hex evidence.
            val firstByteForDisplay = adapterMac?.let { WifiMacProbe.firstByte(it) }
                ?: sysfsMac?.let { WifiMacProbe.firstByte(it) }
            val firstByteHex = if (firstByteForDisplay != null) {
                "0x" + String.format("%02x", firstByteForDisplay and 0xFF)
            } else "<unreadable>"

            val anyLocallyAdministered = isLocallyAdministered(adapterMac) ||
                isLocallyAdministered(sysfsMac)

            val adapterDisplay = adapterMac ?: adapterRaw?.trim() ?: "null"
            val sysfsDisplay = sysfsMac ?: sysfsRaw?.trim() ?: "<unreadable>"

            val evidence = listOf(
                Evidence("bluetooth_mac.adapter", adapterDisplay, expected = "<real-OUI mac>"),
                Evidence("bluetooth_mac.sysfs", sysfsDisplay, expected = "<real-OUI mac>"),
                Evidence(
                    key = "bluetooth_mac.oui_first_byte",
                    value = firstByteHex,
                    expected = "<known IEEE-registered OEM OUI>",
                ),
                Evidence(
                    key = "bluetooth_mac.locally_administered",
                    value = anyLocallyAdministered.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "bluetooth_mac.emulator_oui_match",
                    value = combinedEmuTag,
                    expected = EMU_TAG_NONE,
                ),
                Evidence(
                    key = "bluetooth_mac.adapter_available",
                    value = adapterSupplierReturned.toString(),
                    expected = "true on phone-class",
                ),
                Evidence("bluetooth_mac.pattern", pattern, expected = PATTERN_CLEAN),
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
                "BluetoothMacProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

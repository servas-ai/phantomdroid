// inventory.yml rank 13, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #13 — env.bootloader.
 *
 * Reads Android Verified Boot 2.0 (AVB) properties and a small set of related
 * production-build markers to decide whether the bootloader is unlocked, the
 * vbmeta image has been tampered with, or a developer-build property has
 * leaked onto what should be a user build.
 *
 * Primary signals (any may be missing on non-AOSP/older devices):
 *   • `ro.boot.vbmeta.device_state` — `green` (locked, factory-image verified),
 *     `yellow` (locked, but a custom AVB root key is installed — common when
 *     LineageOS/GrapheneOS users re-lock with their own signing key), `orange`
 *     (unlocked, no verification), `red` (verification failed at boot).
 *   • `ro.boot.verifiedbootstate` — same green/yellow/orange/red scale; older
 *     property name still present on many devices.
 *   • `ro.boot.flash.locked` — `1` (locked), `0` (unlocked). The kernel-level
 *     truth-of-the-fuse.
 *   • `ro.oem_unlock_supported` — `1` means OEM unlock is allowed; production
 *     devices that have never been put into developer mode have `0`. A `1`
 *     paired with a "locked" claim is a strong tampering indicator.
 *
 * Secondary signals (dev-build leakage):
 *   • `ro.secure` — must be `1` on user builds.
 *   • `ro.debuggable` — must be `0` on user builds.
 *
 * Warranty signals (Samsung KNOX / OEM tamper flags; recorded as evidence only):
 *   • `ro.boot.warranty_bit`, `ro.warranty_bit` — `1` indicates warranty has
 *     been tripped via OEM unlock / custom firmware. Surfaced in evidence so
 *     consumers can correlate, but does NOT contribute to score in this
 *     revision — the four primary signals plus secondary dev-build markers
 *     already cover the same surface for the L1 scoring contract.
 *
 * Scoring (max over all rules; not summed):
 *   • 1.00  if `vbmeta.device_state == "red"`                  (verification failed)
 *   • 1.00  if `vbmeta.device_state == "orange"`               (unlocked)
 *   • 1.00  if `verifiedbootstate == "orange"`                 (unlocked)
 *   • 1.00  if `boot.flash.locked == "0"`                      (unlocked)
 *   • 0.90  if `vbmeta.device_state == "yellow"`               (custom keys)
 *   • 0.90  if `oem_unlock_supported == "1"` AND claims locked (suspicious)
 *   • 0.85  if `ro.secure == "0"` OR `ro.debuggable == "1"`    (dev-build leak)
 *   • 0.00  if `vbmeta == "green"` AND `flash.locked == "1"` AND
 *           `ro.secure == "1"` AND `ro.debuggable == "0"`      (clean production)
 *
 * Confidence:
 *   • 0.99  if ≥ 2 of the 4 primary properties were readable
 *   • 0.60  if exactly 1 was readable
 *   • 0.30  if 0 were readable (degraded — score is 0.0 but uninformative)
 *
 * Reference: shared/probes/inventory.yml rank 13 (mitigation_layer L1).
 */
class BootloaderProbe : Probe {
    override val id = "env.bootloader"
    override val rank = 13
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 200L

    companion object {
        const val PROP_VBMETA_DEVICE_STATE = "ro.boot.vbmeta.device_state"
        const val PROP_VERIFIEDBOOTSTATE = "ro.boot.verifiedbootstate"
        const val PROP_FLASH_LOCKED = "ro.boot.flash.locked"
        const val PROP_OEM_UNLOCK_SUPPORTED = "ro.oem_unlock_supported"
        const val PROP_RO_SECURE = "ro.secure"
        const val PROP_RO_DEBUGGABLE = "ro.debuggable"
        const val PROP_WARRANTY_BIT_BOOT = "ro.boot.warranty_bit"
        const val PROP_WARRANTY_BIT_LEGACY = "ro.warranty_bit"

        const val STATE_GREEN = "green"
        const val STATE_YELLOW = "yellow"
        const val STATE_ORANGE = "orange"
        const val STATE_RED = "red"

        const val METHOD =
            "Read ro.boot.* / ro.oem_unlock_supported / ro.secure / " +
                "ro.debuggable / ro.boot.warranty_bit properties and compare " +
                "against expected production-locked values"

        const val SCORE_UNLOCKED = 1.0
        const val SCORE_VERIFICATION_FAILED = 1.0
        const val SCORE_YELLOW_CUSTOM_KEYS = 0.9
        const val SCORE_SUSPICIOUS_UNLOCK_ALLOWED = 0.9
        const val SCORE_DEV_BUILD_LEAK = 0.85

        const val CONFIDENCE_FULL = 0.99
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            fun readProp(key: String): String? = try {
                ctx.getSystemProperty(key)
            } catch (_: Throwable) {
                null
            }

            val vbmeta = readProp(PROP_VBMETA_DEVICE_STATE)
            val verifiedBootState = readProp(PROP_VERIFIEDBOOTSTATE)
            val flashLocked = readProp(PROP_FLASH_LOCKED)
            val oemUnlockSupported = readProp(PROP_OEM_UNLOCK_SUPPORTED)
            val roSecure = readProp(PROP_RO_SECURE)
            val roDebuggable = readProp(PROP_RO_DEBUGGABLE)
            val warrantyBitBoot = readProp(PROP_WARRANTY_BIT_BOOT)
            val warrantyBitLegacy = readProp(PROP_WARRANTY_BIT_LEGACY)

            val evidence = listOf(
                Evidence(PROP_VBMETA_DEVICE_STATE, vbmeta ?: "missing", expected = STATE_GREEN),
                Evidence(PROP_VERIFIEDBOOTSTATE, verifiedBootState ?: "missing", expected = STATE_GREEN),
                Evidence(PROP_FLASH_LOCKED, flashLocked ?: "missing", expected = "1"),
                Evidence(PROP_OEM_UNLOCK_SUPPORTED, oemUnlockSupported ?: "missing", expected = "0"),
                Evidence(PROP_RO_SECURE, roSecure ?: "missing", expected = "1"),
                Evidence(PROP_RO_DEBUGGABLE, roDebuggable ?: "missing", expected = "0"),
                Evidence(PROP_WARRANTY_BIT_BOOT, warrantyBitBoot ?: "missing", expected = "0"),
                Evidence(PROP_WARRANTY_BIT_LEGACY, warrantyBitLegacy ?: "missing", expected = "0"),
            )

            // Score rules — max wins. Equal-weight rules collapse to one branch.
            val claimsLocked = flashLocked == "1" ||
                vbmeta == STATE_GREEN ||
                verifiedBootState == STATE_GREEN

            val scoreCandidates = buildList {
                if (vbmeta == STATE_RED) add(SCORE_VERIFICATION_FAILED)
                if (vbmeta == STATE_ORANGE) add(SCORE_UNLOCKED)
                if (verifiedBootState == STATE_ORANGE) add(SCORE_UNLOCKED)
                if (flashLocked == "0") add(SCORE_UNLOCKED)
                if (vbmeta == STATE_YELLOW) add(SCORE_YELLOW_CUSTOM_KEYS)
                if (oemUnlockSupported == "1" && claimsLocked) add(SCORE_SUSPICIOUS_UNLOCK_ALLOWED)
                if (roSecure == "0") add(SCORE_DEV_BUILD_LEAK)
                if (roDebuggable == "1") add(SCORE_DEV_BUILD_LEAK)
            }
            val score = scoreCandidates.maxOrNull() ?: 0.0

            val primaryPropsRead = listOf(vbmeta, verifiedBootState, flashLocked, oemUnlockSupported)
                .count { it != null }
            val confidence = when {
                primaryPropsRead >= 2 -> CONFIDENCE_FULL
                primaryPropsRead == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "BootloaderProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// inventory.yml rank 2, mitigation_layer L3 (declarative inference; real API call = L0)
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — integrity.play_integrity (rank 2, mitigation_layer L3 declarative;
 * real live attestation = L0 / un-spoofable).
 *
 * SISTER PROBE to `PlayIntegrityProbe` (rank 71, id
 * `integrity.play_integrity_signals`). Both probes are declarative in the
 * JVM-pure detection module, but they cover DIFFERENT inference surfaces:
 *
 *   • rank-71 `integrity.play_integrity_signals` — answers the narrow
 *     question "would `basicIntegrity` fail?" via the canonical test-keys +
 *     debuggable + emulator-fingerprint signal triple. It is the cheap
 *     buildprop-only inference.
 *
 *   • rank-2 `integrity.play_integrity` (THIS PROBE) — answers the broader
 *     question "would the LIVE Play Integrity API verdict return
 *     SOMETHING-LESS-THAN-MEETS-STRONG-INTEGRITY?" via a wider signal
 *     surface that includes GMS package presence, GMS signature spoof tells
 *     (TrickyStore-style `gmsspoof` modules, `com.google.android.gms.unaltered`
 *     re-signed builds), bootloader lock + verity state pair, and the
 *     `ro.com.google.*` provisioning surface. It is the "live verdict
 *     inference" entry — the closest declarative approximation to what a
 *     Play Integrity API call would actually return, without invoking the
 *     real API (which requires Google Play Services + an outbound network
 *     call + a server-side challenge — none available in this JVM module).
 *
 * The two probes coexist by design. Real Play Integrity calls report a
 * verdict-TIER (BASIC / DEVICE / STRONG), so we need both:
 *
 *   - rank-71 detects "basic verdict would fail" specifically (severe; means
 *     the app cannot rely on Play Integrity at all)
 *   - rank-2 detects the broader "any verdict would be downgraded" (means the
 *     app may still pass BASIC but cannot reach STRONG / DEVICE — the
 *     attestation-grade decision boundary apps actually consume)
 *
 * Mitigation layer: declared as L3 here because the probe operates on the
 * declarative property surface. The real Play Integrity API surface is L0
 * (un-spoofable in the FOSS-tooling era of 2026 — see
 * `audit/spoof-stack/un-snapshottable.md`). The declarative inference here
 * scores against property-surface markers that imply a failing verdict; a
 * production SpoofStack can close those markers, but a verifier that
 * performs the real Play Integrity API call still bypasses this entire
 * mitigation.
 *
 * Signal sources scored (max-wins):
 *
 *   0.95  GMS package absent AND `ro.com.google.gmsversion` empty —
 *         a device with no Google Play Services AT ALL cannot return a
 *         STRONG or DEVICE verdict; the Play Integrity API itself will not
 *         return any verdict at all (NO_VERDICT). This is the strongest
 *         signal: ReDroid containers and AOSP-only ROMs fall into this tier.
 *
 *   0.95  Bootloader unlocked (`ro.boot.flash.locked == "0"`) AND verity
 *         NOT enforcing (`ro.boot.veritymode != "enforcing"`) — guaranteed
 *         STRONG-verdict fail. dm-verity disabled is the canonical
 *         strongIntegrity violation.
 *
 *   0.85  GMS-hijack package tells — an installed package list that
 *         contains `com.google.android.gms.unaltered` (the TrickyStore
 *         re-signed alternative GMS package) OR `io.tns.gmsspoof` /
 *         `com.tns.gmsspoof` (signature-spoofing modules that hijack
 *         `PackageManager.getPackageInfo()` to return a fake GMS signer).
 *         These tells mean the device has Play Services BUT the
 *         GMS signature is compromised — DEVICE and STRONG would fail at
 *         the GMS-signer attestation step.
 *
 *   0.85  `ro.com.google.clientidbase` empty OR exactly "android-google"
 *         (the default unprovisioned value). A real Pixel / Samsung /
 *         retail device reports a versioned client-id string (e.g.
 *         "android-google-Pixel-7"). The bare default value is the
 *         "device was never provisioned with Google's first-boot OOBE"
 *         tell — a strong indicator that the GMS verdict signer would
 *         reject this device.
 *
 *   0.70  `ro.gms.disabled == "1"` OR `ro.com.google.gmsversion == "0"`
 *         — DEVICE-tier downgrade. GMS is present in some form but has
 *         been explicitly disabled OR reports a zero version (which is
 *         what an early-boot resetprop hack leaves in place). The Play
 *         Integrity API can still return a BASIC verdict in this state
 *         but cannot return DEVICE / STRONG.
 *
 *   0.50  `persist.sys.disable_rescue == "1"` — RecoverableSecurityException
 *         disabled. This is a Magisk / SpoofStack tell: a real device leaves
 *         this property unset (default = enabled), and disabling it is one
 *         of the small handful of toggles SpoofStack tutorials recommend
 *         for "make Play Integrity quieter" workflows. Mild signal, not
 *         load-bearing.
 *
 *   0.00  none of the above (clean — predicted to pass at the highest
 *         verdict tier the device's hardware can attest to)
 *
 * Predicted verdict (recorded as evidence, NOT directly scored):
 *
 *   NO_VERDICT             — GMS absent (cannot run the API at all)
 *   BASIC_ONLY             — GMS present but DEVICE/STRONG downgrade signals fire
 *   DEVICE_BASIC           — GMS+DEVICE pass but STRONG fails (verity off, bootloader unlocked)
 *   STRONG_DEVICE_BASIC    — predicted to pass all three (rare; requires green AVB + locked + hardware keystore)
 *   CLEAN                  — no negative signal observed (predicted full pass)
 *
 * Confidence:
 *   • 0.75 — GMS package observable (PackageManager view returned a list
 *     without throwing). The probe has the highest-leverage signal source
 *     and can make a meaningful verdict prediction.
 *   • 0.40 — PackageManager threw (containerized environments where the
 *     view itself fails). Probe falls back to the property-only signals;
 *     the verdict prediction is less reliable.
 *
 * Evidence keys:
 *   • play_integrity.gms_present           → Boolean
 *   • play_integrity.gms_version           → String (may be empty)
 *   • play_integrity.bootloader_unlocked   → Boolean
 *   • play_integrity.gms_hijack_pkg        → String (matching pkg if found, else "")
 *   • play_integrity.predicted_verdict     → enum-like String
 *   • play_integrity.method                → "live-verdict-inference (declarative)"
 *   • play_integrity.declarative_only      → true
 *
 * Reference: shared/probes/inventory.yml rank 2 (mitigation_layer L3).
 * Sister probe: PlayIntegrityProbe (rank 71, id `integrity.play_integrity_signals`).
 */
class PlayIntegrityLiveProbe : Probe {
    override val id = "integrity.play_integrity"
    override val rank = RANK
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 200L

    companion object {
        const val RANK = 2

        // Property keys
        const val PROP_GMS_VERSION = "ro.com.google.gmsversion"
        const val PROP_CLIENTID_BASE = "ro.com.google.clientidbase"
        const val PROP_FLASH_LOCKED = "ro.boot.flash.locked"
        const val PROP_VERITYMODE = "ro.boot.veritymode"
        const val PROP_GMS_DISABLED = "ro.gms.disabled"
        const val PROP_DISABLE_RESCUE = "persist.sys.disable_rescue"

        // Package names
        const val PKG_GMS = "com.google.android.gms"
        const val PKG_GMS_UNALTERED = "com.google.android.gms.unaltered"
        const val PKG_GMSSPOOF_IO = "io.tns.gmsspoof"
        const val PKG_GMSSPOOF_COM = "com.tns.gmsspoof"

        /** GMS-hijack package tells (DEVICE+STRONG would fail). */
        val GMS_HIJACK_PACKAGES = listOf(
            PKG_GMS_UNALTERED,
            PKG_GMSSPOOF_IO,
            PKG_GMSSPOOF_COM,
        )

        // Property value constants
        const val FLASH_UNLOCKED = "0"
        const val VERITYMODE_ENFORCING = "enforcing"
        const val GMS_DISABLED_TRUE = "1"
        const val GMS_VERSION_ZERO = "0"
        const val DISABLE_RESCUE_TRUE = "1"

        /**
         * The default unprovisioned `ro.com.google.clientidbase` value. A
         * real, OOBE-provisioned device reports a versioned string distinct
         * from this (e.g. "android-google-Pixel-7"). The bare default value
         * is the "device was never provisioned" tell.
         */
        const val CLIENTIDBASE_UNPROVISIONED = "android-google"

        // Scoring constants (max-wins)
        const val SCORE_NO_GMS = 0.95
        const val SCORE_BOOTLOADER_UNLOCKED = 0.95
        const val SCORE_GMS_HIJACK = 0.85
        const val SCORE_UNPROVISIONED_CLIENTID = 0.85
        const val SCORE_GMS_DOWNGRADE = 0.70
        const val SCORE_RESCUE_DISABLED = 0.50
        const val SCORE_CLEAN = 0.0

        // Confidence
        const val CONFIDENCE_FULL = 0.75
        const val CONFIDENCE_DEGRADED = 0.40

        // Method strings
        const val METHOD = "live-verdict-inference (declarative)"

        // Evidence keys
        const val EV_GMS_PRESENT = "play_integrity.gms_present"
        const val EV_GMS_VERSION = "play_integrity.gms_version"
        const val EV_BOOTLOADER_UNLOCKED = "play_integrity.bootloader_unlocked"
        const val EV_GMS_HIJACK_PKG = "play_integrity.gms_hijack_pkg"
        const val EV_PREDICTED_VERDICT = "play_integrity.predicted_verdict"
        const val EV_METHOD = "play_integrity.method"
        const val EV_DECLARATIVE_ONLY = "play_integrity.declarative_only"

        // Predicted-verdict enum values (recorded as evidence)
        const val VERDICT_NO_VERDICT = "NO_VERDICT"
        const val VERDICT_BASIC_ONLY = "BASIC_ONLY"
        const val VERDICT_DEVICE_BASIC = "DEVICE_BASIC"
        const val VERDICT_STRONG_DEVICE_BASIC = "STRONG_DEVICE_BASIC"
        const val VERDICT_CLEAN = "CLEAN"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            fun readProp(key: String): String? = try {
                ctx.getSystemProperty(key)
            } catch (_: Throwable) {
                null
            }

            // ── Property reads ─────────────────────────────────────────────
            val gmsVersion = readProp(PROP_GMS_VERSION)
            val clientIdBase = readProp(PROP_CLIENTID_BASE)
            val flashLocked = readProp(PROP_FLASH_LOCKED)
            val verityMode = readProp(PROP_VERITYMODE)
            val gmsDisabled = readProp(PROP_GMS_DISABLED)
            val disableRescue = readProp(PROP_DISABLE_RESCUE)

            // ── Package-manager surface ────────────────────────────────────
            // PackageManager may throw on containerized contexts (no real PM
            // service backend). Capture the failure and fall back to the
            // property-only signal surface with degraded confidence.
            var pmThrew = false
            var gmsPresent = false
            var hijackPkg = ""
            try {
                val pm = ctx.queryPackageManager()
                gmsPresent = pm.isPackageInstalled(PKG_GMS)
                hijackPkg = GMS_HIJACK_PACKAGES.firstOrNull { pm.isPackageInstalled(it) } ?: ""
            } catch (_: Throwable) {
                pmThrew = true
            }

            // ── Signal predicates ──────────────────────────────────────────
            // 0.95: GMS absent AND gmsversion empty → NO_VERDICT
            val noGmsAtAll = !gmsPresent && gmsVersion.isNullOrEmpty() && !pmThrew

            // 0.95: bootloader unlocked AND verity not enforcing → STRONG fail
            val bootloaderUnlocked = flashLocked == FLASH_UNLOCKED &&
                verityMode != VERITYMODE_ENFORCING

            // 0.85: hijack package observed
            val hijackFound = hijackPkg.isNotEmpty()

            // 0.85: clientidbase empty or exact "android-google" (default
            // unprovisioned value — a real OOBE-provisioned device reports a
            // versioned variant like "android-google-Pixel-7").
            val unprovisionedClientId = clientIdBase != null &&
                (clientIdBase.isEmpty() || clientIdBase == CLIENTIDBASE_UNPROVISIONED)

            // 0.70: GMS downgrade tells
            val gmsDowngrade = gmsDisabled == GMS_DISABLED_TRUE ||
                gmsVersion == GMS_VERSION_ZERO

            // 0.50: rescue mode disabled
            val rescueDisabled = disableRescue == DISABLE_RESCUE_TRUE

            // ── Score (max-wins) ───────────────────────────────────────────
            val candidates = buildList {
                if (noGmsAtAll) add(SCORE_NO_GMS)
                if (bootloaderUnlocked) add(SCORE_BOOTLOADER_UNLOCKED)
                if (hijackFound) add(SCORE_GMS_HIJACK)
                if (unprovisionedClientId) add(SCORE_UNPROVISIONED_CLIENTID)
                if (gmsDowngrade) add(SCORE_GMS_DOWNGRADE)
                if (rescueDisabled) add(SCORE_RESCUE_DISABLED)
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            // ── Predicted verdict (evidence-only) ──────────────────────────
            val predictedVerdict = when {
                noGmsAtAll -> VERDICT_NO_VERDICT
                hijackFound || unprovisionedClientId -> VERDICT_BASIC_ONLY
                bootloaderUnlocked -> VERDICT_BASIC_ONLY
                gmsDowngrade -> VERDICT_DEVICE_BASIC
                rescueDisabled -> VERDICT_DEVICE_BASIC
                gmsPresent -> VERDICT_CLEAN
                else -> VERDICT_CLEAN
            }

            // ── Confidence ─────────────────────────────────────────────────
            val confidence = if (pmThrew) CONFIDENCE_DEGRADED else CONFIDENCE_FULL

            val evidence = listOf(
                Evidence(EV_GMS_PRESENT, gmsPresent, expected = true),
                Evidence(EV_GMS_VERSION, gmsVersion ?: "", expected = "non-empty"),
                Evidence(EV_BOOTLOADER_UNLOCKED, bootloaderUnlocked, expected = false),
                Evidence(EV_GMS_HIJACK_PKG, hijackPkg, expected = ""),
                Evidence(EV_PREDICTED_VERDICT, predictedVerdict, expected = VERDICT_CLEAN),
                Evidence(EV_METHOD, METHOD),
                Evidence(EV_DECLARATIVE_ONLY, true),
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
                "PlayIntegrityLiveProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

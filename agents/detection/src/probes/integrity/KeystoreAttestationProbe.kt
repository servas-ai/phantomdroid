// inventory.yml rank 6, mitigation_layer L3 (declarative inference)
//
// NOTE on mitigation_layer: the inventory.yml entry for rank 6
// (`integrity.keystore_attestation`) lists mitigation_layer L3 — that
// classification stands ONLY for THIS declarative inference variant. A real
// hardware-keystore attestation (`KeyStore.getAttestationChain()` returning a
// X.509 chain rooted in the Google/OEM attestation root) is L0 (not-spoofable)
// per `audit/spoof-stack/un-snapshottable.md`, because the chain is signed by
// a TEE-resident private key that the SpoofStack cannot extract or forge. We
// cannot make the L0 call from this JVM-pure module (it requires the Android
// Plugin and AndroidKeyStore provider), so this probe inspects the same
// surface markers that WOULD CAUSE a real attestation to fail — the L3
// downgrade is the price of operating without the Android Plugin.
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — integrity.keystore_attestation (rank 6, mitigation_layer L3).
 *
 * Declarative inference for whether a hardware-backed keystore attestation
 * (TEE / StrongBox `KeyStore.getAttestationChain()`) would succeed on this
 * device, WITHOUT actually performing the attestation call. The real call
 * requires `android.security.keystore.KeyGenParameterSpec` + the
 * AndroidKeyStore provider — neither is reachable from this JVM-pure
 * detection module — so we score on the *property-and-file surface markers*
 * that imply attestation would either:
 *
 *   • be REFUSED entirely (unlocked bootloader / dm-verity off → the TEE
 *     itself refuses to sign anything; this is the canonical "Verified Boot
 *     is RED/ORANGE/YELLOW" path that any modern keystore HAL ships),
 *   • be MARKED-UNTRUSTED (Samsung Knox warranty bit tripped → the chain is
 *     issued but the device's attestation extension flags it as no-longer-
 *     hardware-rooted),
 *   • be UNAVAILABLE (no hardware keystore HAL present → only software-
 *     attestation is possible, which an anti-fraud verifier rejects), OR
 *   • be IMPOSSIBLE-TO-VERIFY (dual-arch Houdini bridge present → the TEE
 *     on the x86 host cannot sign for an arm64 chain).
 *
 * Scoring (max-wins; the strongest implied-fail signal determines the score):
 *
 *   0.95  Bootloader unlocked AND dm-verity off
 *         (ro.boot.flash.locked = "0" AND ro.boot.veritymode empty/missing)
 *         — a hardware keystore HAL refuses to attest from this state. This
 *         is the highest-confidence attestation-fail predictor we can build
 *         from declarative state alone.
 *   0.85  Samsung Knox warranty tripped
 *         (ro.boot.warranty_bit = "1" OR ro.boot.warranty = "0") — the chain
 *         issues but the Knox attestation extension flags the device as
 *         "trusted-state lost", which a verifier rejects.
 *   0.85  Abnormal boot mode
 *         (ro.bootmode contains "recovery" / "bootloader" / "fastboot") —
 *         hardware keystore is unavailable in any non-"normal" boot state.
 *   0.70  Hardware keystore HAL absent
 *         (ro.hardware.keystore is empty or missing) — only the software
 *         attestation path remains, and a verifier rejects it.
 *   0.70  Dual-arch Houdini bridge present
 *         (ro.product.cpu.abilist contains BOTH "x86_64" AND "arm64-v8a") —
 *         the TEE on the x86 host cannot produce a hardware-backed signature
 *         claiming arm64-v8a as a stable ABI.
 *   0.50  Keymaster device node missing
 *         (`/dev/keymaster` does not exist) — keymaster HAL is not bound;
 *         hardware-backed key operations are physically unreachable.
 *   0.00  None of the above (clean).
 *
 * Confidence: 0.75 (declarative inference). Real attestation via
 * `KeyStore.getAttestationChain()` would be confidence 0.99; we are
 * deliberately downgrading to reflect that any of these markers could be
 * coherently spoofed at L1-L3 even though their absence on a real device is
 * highly anomalous.
 *
 * Evidence keys (namespaced per cross-cutting #1, audit/cross-cutting-
 * followups-2026-05-19.md — every key prefixed with `keystore_attestation.`):
 *
 *   • keystore_attestation.tee_absent             → Boolean
 *   • keystore_attestation.bootloader_locked      → Boolean
 *   • keystore_attestation.knox_status            → String ("ok" / "tripped" / "unknown")
 *   • keystore_attestation.method                 → "declarative-prop-and-file-inference"
 *   • keystore_attestation.declarative_only       → true
 *
 * Reference: shared/probes/inventory.yml rank 6 (mitigation_layer L3 for THIS
 * variant; L0 for the un-implemented real-attestation variant — see
 * `audit/spoof-stack/un-snapshottable.md`).
 */
class KeystoreAttestationProbe : Probe {
    override val id = "integrity.keystore_attestation"
    override val rank = RANK
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 200L

    companion object {
        const val RANK = 6

        // Property keys.
        const val PROP_VERITYMODE = "ro.boot.veritymode"
        const val PROP_FLASH_LOCKED = "ro.boot.flash.locked"
        const val PROP_WARRANTY_BIT = "ro.boot.warranty_bit"
        const val PROP_WARRANTY = "ro.boot.warranty"
        const val PROP_BOOTMODE = "ro.bootmode"
        const val PROP_HARDWARE_KEYSTORE = "ro.hardware.keystore"
        const val PROP_CPU_ABILIST = "ro.product.cpu.abilist"

        // File paths.
        const val PATH_DEV_KEYMASTER = "/dev/keymaster"

        // Sentinel values.
        const val FLASH_UNLOCKED = "0"
        const val WARRANTY_BIT_TRIPPED = "1"
        const val WARRANTY_TRIPPED = "0" // ro.boot.warranty=="0" means warranty void on Knox

        // Abnormal boot modes.
        val ABNORMAL_BOOTMODES = listOf("recovery", "bootloader", "fastboot")

        // Houdini bridge markers — BOTH must be present in abilist for the rule
        // to fire.
        const val ABI_X86_64 = "x86_64"
        const val ABI_ARM64_V8A = "arm64-v8a"

        // Score tiers (max-wins).
        const val SCORE_UNLOCKED_BOOTLOADER_AND_VERITY_OFF = 0.95
        const val SCORE_KNOX_WARRANTY_TRIPPED = 0.85
        const val SCORE_ABNORMAL_BOOTMODE = 0.85
        const val SCORE_HARDWARE_KEYSTORE_ABSENT = 0.70
        const val SCORE_HOUDINI_DUAL_ARCH = 0.70
        const val SCORE_KEYMASTER_DEV_MISSING = 0.50
        const val SCORE_CLEAN = 0.0

        // Confidence — declarative inference path.
        const val CONFIDENCE_DECLARATIVE = 0.75

        // Method tag.
        const val METHOD = "declarative-prop-and-file-inference"

        // Evidence keys (namespaced per cross-cutting #1).
        const val EV_TEE_ABSENT = "keystore_attestation.tee_absent"
        const val EV_BOOTLOADER_LOCKED = "keystore_attestation.bootloader_locked"
        const val EV_KNOX_STATUS = "keystore_attestation.knox_status"
        const val EV_METHOD = "keystore_attestation.method"
        const val EV_DECLARATIVE_ONLY = "keystore_attestation.declarative_only"
        const val EV_KEYMASTER_PRESENT = "keystore_attestation.keymaster_dev_present"
        const val EV_HOUDINI_DUAL_ARCH = "keystore_attestation.houdini_dual_arch"
        const val EV_ABNORMAL_BOOTMODE = "keystore_attestation.abnormal_bootmode"

        // Knox status string values.
        const val KNOX_OK = "ok"
        const val KNOX_TRIPPED = "tripped"
        const val KNOX_UNKNOWN = "unknown"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            fun readProp(key: String): String? = try {
                ctx.getSystemProperty(key)
            } catch (_: Throwable) {
                null
            }

            val veritymode = readProp(PROP_VERITYMODE)
            val flashLocked = readProp(PROP_FLASH_LOCKED)
            val warrantyBit = readProp(PROP_WARRANTY_BIT)
            val warranty = readProp(PROP_WARRANTY)
            val bootmode = readProp(PROP_BOOTMODE)
            val hardwareKeystore = readProp(PROP_HARDWARE_KEYSTORE)
            val abilist = readProp(PROP_CPU_ABILIST)
            val keymasterPresent = try {
                ctx.fileExists(PATH_DEV_KEYMASTER)
            } catch (_: Throwable) {
                false
            }

            // ── Predicate evaluation ────────────────────────────────────────
            val bootloaderUnlockedAndVerityOff =
                flashLocked == FLASH_UNLOCKED && veritymode.isNullOrEmpty()

            val knoxTripped = warrantyBit == WARRANTY_BIT_TRIPPED ||
                warranty == WARRANTY_TRIPPED

            val abnormalBootmode = bootmode != null && ABNORMAL_BOOTMODES.any { marker ->
                bootmode.contains(marker)
            }

            val hardwareKeystoreAbsent = hardwareKeystore.isNullOrEmpty()

            val houdiniDualArch = abilist != null &&
                abilist.contains(ABI_X86_64) &&
                abilist.contains(ABI_ARM64_V8A)

            val keymasterMissing = !keymasterPresent

            // ── Score (max-wins) ────────────────────────────────────────────
            val scoreCandidates = buildList {
                if (bootloaderUnlockedAndVerityOff) add(SCORE_UNLOCKED_BOOTLOADER_AND_VERITY_OFF)
                if (knoxTripped) add(SCORE_KNOX_WARRANTY_TRIPPED)
                if (abnormalBootmode) add(SCORE_ABNORMAL_BOOTMODE)
                if (hardwareKeystoreAbsent) add(SCORE_HARDWARE_KEYSTORE_ABSENT)
                if (houdiniDualArch) add(SCORE_HOUDINI_DUAL_ARCH)
                if (keymasterMissing) add(SCORE_KEYMASTER_DEV_MISSING)
            }
            val score = scoreCandidates.maxOrNull() ?: SCORE_CLEAN

            // ── Synthesized evidence values ─────────────────────────────────
            // tee_absent: hardware-keystore HAL missing AND keymaster dev node
            // missing — both surfaces agree that there is no TEE-backed
            // keystore reachable on this device.
            val teeAbsent = hardwareKeystoreAbsent && keymasterMissing

            // bootloader_locked: we report TRUE iff ro.boot.flash.locked == "1"
            // explicitly. Empty / missing reads as "unknown" → false in the
            // boolean reduction because the probe cannot positively confirm
            // a locked bootloader from an absent signal.
            val bootloaderLocked = flashLocked == "1"

            val knoxStatus = when {
                knoxTripped -> KNOX_TRIPPED
                warrantyBit == "0" || warranty == "1" -> KNOX_OK
                else -> KNOX_UNKNOWN
            }

            val evidence = listOf(
                Evidence(EV_TEE_ABSENT, teeAbsent, expected = false),
                Evidence(EV_BOOTLOADER_LOCKED, bootloaderLocked, expected = true),
                Evidence(EV_KNOX_STATUS, knoxStatus, expected = KNOX_OK),
                Evidence(EV_METHOD, METHOD),
                Evidence(EV_DECLARATIVE_ONLY, true),
                Evidence(EV_KEYMASTER_PRESENT, keymasterPresent, expected = true),
                Evidence(EV_HOUDINI_DUAL_ARCH, houdiniDualArch, expected = false),
                Evidence(EV_ABNORMAL_BOOTMODE, abnormalBootmode, expected = false),
                Evidence("keystore_attestation.$PROP_VERITYMODE", veritymode ?: "missing", expected = "enforcing"),
                Evidence("keystore_attestation.$PROP_FLASH_LOCKED", flashLocked ?: "missing", expected = "1"),
                Evidence("keystore_attestation.$PROP_WARRANTY_BIT", warrantyBit ?: "missing", expected = "0"),
                Evidence("keystore_attestation.$PROP_BOOTMODE", bootmode ?: "missing", expected = "normal"),
                Evidence("keystore_attestation.$PROP_HARDWARE_KEYSTORE", hardwareKeystore ?: "missing", expected = "non-empty"),
                Evidence("keystore_attestation.$PROP_CPU_ABILIST", abilist ?: "missing", expected = "no Houdini bridge"),
            )

            ProbeResult(
                score = score,
                confidence = CONFIDENCE_DECLARATIVE,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "KeystoreAttestationProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

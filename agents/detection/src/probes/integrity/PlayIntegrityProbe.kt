// inventory.yml rank 71, mitigation_layer L3
package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — integrity.play_integrity_signals (rank 71, mitigation_layer L3)
 *
 * Predicts the Play Integrity API verdicts (basicIntegrity / deviceIntegrity /
 * strongIntegrity) from the *build-property surface* alone, without invoking the
 * Play Integrity API itself. The real API requires the Android plugin, Google
 * Play Services, and an outbound network call — none of which are available
 * inside this JVM-pure detection module. Probing the same underlying
 * indicators that Play Integrity itself derives its verdicts from gives us a
 * cheap, offline approximation suitable for pre-deployment risk scoring.
 *
 * The three Play Integrity verdicts and the property-surface proxies for each:
 *
 *   • basicIntegrity — fails when the device is a known-rooted or developer
 *     build. Proxy: `ro.build.tags == "test-keys"` AND `ro.debuggable == "1"`.
 *     This pair is the canonical signature of an AOSP eng-build or a rooted
 *     ROM that has not been re-signed with release keys.
 *
 *   • MEETS_DEVICE_INTEGRITY — fails when the device is an emulator or a
 *     virtualized cloud-phone. Proxy: `ro.build.fingerprint` containing any of
 *     `generic`, `vbox`, `redroid`, `ranchu` — the four most common emulator
 *     fingerprint substrings (Android emulator, VirtualBox-based, redroid
 *     containers, QEMU ranchu boards).
 *
 *   • MEETS_STRONG_INTEGRITY — fails when verified-boot is not green OR the
 *     bootloader is unlocked. Proxies:
 *       - `ro.boot.verifiedbootstate` != "green" AND not empty
 *         (yellow / orange / red — any known violation)
 *       - `ro.boot.flash.locked == "0"` (bootloader unlocked)
 *
 * Scoring (max-wins; the strongest predicted verdict failure determines the
 * overall score — Play Integrity verdicts are a strict superset hierarchy, so
 * a basicIntegrity-fail predictor is the most severe signal even though
 * strongIntegrity-fail predictors fire more often):
 *
 *   0.95  basicIntegrity predicted to fail (test-keys + debuggable)
 *   0.85  deviceIntegrity predicted to fail (emulator fingerprint substring)
 *   0.70  strongIntegrity predicted to fail via verifiedbootstate violation
 *   0.50  strongIntegrity predicted to fail via flash.locked = 0
 *   0.00  none of the above
 *
 * Confidence:
 *   • 0.85  ≥ 3 of 4 properties readable (the probe has the surface area to
 *           make a meaningful inference)
 *   • 0.50  < 3 properties readable (degraded — score may still raise if a
 *           single signal is observed, but caller should down-weight)
 *
 * Evidence keys:
 *   • play_integrity.basic_integrity_predicted_fail   → Boolean
 *   • play_integrity.device_integrity_predicted_fail  → Boolean
 *   • play_integrity.strong_integrity_predicted_fail  → Boolean
 *   • play_integrity.method                           → "buildprop-surface-inference"
 *   • play_integrity.<prop_name>                      → raw observed value or "missing"
 *
 * Reference: shared/probes/inventory.yml rank 71 (mitigation_layer L3).
 */
class PlayIntegrityProbe : Probe {
    override val id = "integrity.play_integrity_signals"
    override val rank = RANK
    override val category = ProbeCategory.INTEGRITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        const val RANK = 71

        const val PROP_BUILD_TAGS = "ro.build.tags"
        const val PROP_DEBUGGABLE = "ro.debuggable"
        const val PROP_BUILD_FINGERPRINT = "ro.build.fingerprint"
        const val PROP_VERIFIEDBOOTSTATE = "ro.boot.verifiedbootstate"
        const val PROP_FLASH_LOCKED = "ro.boot.flash.locked"

        const val TAG_TEST_KEYS = "test-keys"
        const val DEBUGGABLE_TRUE = "1"
        const val FLASH_UNLOCKED = "0"
        const val VERIFIEDBOOTSTATE_GREEN = "green"

        val EMULATOR_FINGERPRINT_MARKERS = listOf("generic", "vbox", "redroid", "ranchu")

        const val METHOD = "buildprop-surface-inference"

        const val SCORE_BASIC_INTEGRITY_FAIL = 0.95
        const val SCORE_DEVICE_INTEGRITY_FAIL = 0.85
        const val SCORE_STRONG_VERIFIEDBOOT_FAIL = 0.70
        const val SCORE_STRONG_FLASHLOCKED_FAIL = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.85
        const val CONFIDENCE_DEGRADED = 0.50

        const val MIN_PROPS_FOR_FULL_CONFIDENCE = 3

        const val EV_BASIC_FAIL = "play_integrity.basic_integrity_predicted_fail"
        const val EV_DEVICE_FAIL = "play_integrity.device_integrity_predicted_fail"
        const val EV_STRONG_FAIL = "play_integrity.strong_integrity_predicted_fail"
        const val EV_METHOD = "play_integrity.method"

        /** The four properties whose readability gates the full-confidence tier. */
        val READABILITY_GATED_PROPS = listOf(
            PROP_BUILD_TAGS,
            PROP_DEBUGGABLE,
            PROP_BUILD_FINGERPRINT,
            PROP_VERIFIEDBOOTSTATE,
        )
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            fun readProp(key: String): String? = try {
                ctx.getSystemProperty(key)
            } catch (_: Throwable) {
                null
            }

            val buildTags = readProp(PROP_BUILD_TAGS)
            val debuggable = readProp(PROP_DEBUGGABLE)
            val fingerprint = readProp(PROP_BUILD_FINGERPRINT)
            val verifiedBootState = readProp(PROP_VERIFIEDBOOTSTATE)
            val flashLocked = readProp(PROP_FLASH_LOCKED)

            // ── Predict each verdict ────────────────────────────────────────
            val basicFail = buildTags == TAG_TEST_KEYS && debuggable == DEBUGGABLE_TRUE

            val deviceFail = fingerprint != null &&
                EMULATOR_FINGERPRINT_MARKERS.any { fingerprint.contains(it) }

            val verifiedBootViolated = !verifiedBootState.isNullOrEmpty() &&
                verifiedBootState != VERIFIEDBOOTSTATE_GREEN
            val flashUnlocked = flashLocked == FLASH_UNLOCKED
            val strongFail = verifiedBootViolated || flashUnlocked

            // ── Score (max-wins) ────────────────────────────────────────────
            val scoreCandidates = buildList {
                if (basicFail) add(SCORE_BASIC_INTEGRITY_FAIL)
                if (deviceFail) add(SCORE_DEVICE_INTEGRITY_FAIL)
                if (verifiedBootViolated) add(SCORE_STRONG_VERIFIEDBOOT_FAIL)
                if (flashUnlocked) add(SCORE_STRONG_FLASHLOCKED_FAIL)
            }
            val score = scoreCandidates.maxOrNull() ?: SCORE_CLEAN

            // ── Confidence ─────────────────────────────────────────────────
            val propsRead = READABILITY_GATED_PROPS
                .map { readabilityOf(it, buildTags, debuggable, fingerprint, verifiedBootState) }
                .count { it != null }
            val confidence = if (propsRead >= MIN_PROPS_FOR_FULL_CONFIDENCE) {
                CONFIDENCE_FULL
            } else {
                CONFIDENCE_DEGRADED
            }

            val evidence = listOf(
                Evidence(EV_BASIC_FAIL, basicFail, expected = false),
                Evidence(EV_DEVICE_FAIL, deviceFail, expected = false),
                Evidence(EV_STRONG_FAIL, strongFail, expected = false),
                Evidence(EV_METHOD, METHOD),
                Evidence("play_integrity.$PROP_BUILD_TAGS", buildTags ?: "missing", expected = "release-keys"),
                Evidence("play_integrity.$PROP_DEBUGGABLE", debuggable ?: "missing", expected = "0"),
                Evidence(
                    "play_integrity.$PROP_BUILD_FINGERPRINT",
                    fingerprint ?: "missing",
                    expected = "no emulator marker",
                ),
                Evidence(
                    "play_integrity.$PROP_VERIFIEDBOOTSTATE",
                    verifiedBootState ?: "missing",
                    expected = VERIFIEDBOOTSTATE_GREEN,
                ),
                Evidence("play_integrity.$PROP_FLASH_LOCKED", flashLocked ?: "missing", expected = "1"),
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
                "PlayIntegrityProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    /**
     * Helper used only to count "how many of the four readability-gated props
     * were actually present" for the confidence tier. Returns the value if
     * non-null, otherwise null. Kept as a tiny dispatcher so the readability
     * count stays in lock-step with [READABILITY_GATED_PROPS].
     */
    private fun readabilityOf(
        key: String,
        buildTags: String?,
        debuggable: String?,
        fingerprint: String?,
        verifiedBootState: String?,
    ): String? = when (key) {
        PROP_BUILD_TAGS -> buildTags
        PROP_DEBUGGABLE -> debuggable
        PROP_BUILD_FINGERPRINT -> fingerprint
        PROP_VERIFIEDBOOTSTATE -> verifiedBootState
        else -> null
    }
}

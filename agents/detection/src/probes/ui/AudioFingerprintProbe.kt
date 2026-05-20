// inventory.yml rank 54, mitigation_layer not_native
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #54 — ui.audio_fingerprint (DECLARATIVE VARIANT).
 *
 * **Declarative limitation — what this probe is and is not.**
 * The real rank-54 surface in the original threat model is the
 * **WebView AudioContext / OfflineAudioContext FFT fingerprint** — a
 * browser-side technique where JavaScript runs an OscillatorNode →
 * DynamicsCompressorNode → AnalyserNode chain inside the page, computes
 * the FFT of the output, and reads back float samples whose lowest bits
 * encode the platform's audio-stack quirks (DSP rounding, denormal
 * handling, FFT implementation choices). Two phones running identical
 * Chrome builds on identical Android versions produce subtly different
 * FFT byte patterns; emulators with software-only audio HAL produce
 * a SIGNATURE_DIFFERENT pattern from any real silicon-DSP path.
 *
 * That surface is **un-snapshottable** in the native probe panel for two
 * reasons:
 *   1. It requires a live JS execution context (WebView, Chrome
 *      CustomTabs, or in-app browser). The detection probe panel runs
 *      Kotlin code in the host app's process; the AudioContext API is
 *      not exposed to non-WebView code paths.
 *   2. The fingerprint is **computed online** from real-time audio-graph
 *      output. There is no system property, sysfs node, or settings key
 *      that snapshots the FFT byte pattern. A native probe can only
 *      observe DECLARATIVE markers in the audio HAL property surface
 *      that PREDICT (but do not produce) the WebView-side fingerprint
 *      anomaly.
 *
 * Per `shared/probes/inventory.yml` rank-54, `mitigation_layer:
 * not_native` — the inventory explicitly tags this rank as a non-native
 * RASP-analog technique. This probe is the **DECLARATIVE FALLBACK**
 * that fires on emulator-shape audio HAL markers that imply the
 * WebView would render audio nonstandardly. Confidence is capped at
 * 0.70 to reflect that we are inferring the WebView fingerprint from
 * proxy signals, not measuring it directly — a real WebView AudioContext
 * FFT measurement would warrant confidence 0.99.
 *
 * **Scoring (max wins; first-match cascade in source order):**
 *   0.85  Software-only audio HAL signature — `ro.hardware.audio` is
 *         `snd_card_dummy` (Linux kernel ALSA dummy driver — the
 *         canonical "no real audio hardware" HAL name used by
 *         containerized Android, Genymotion, and goldfish/ranchu AVDs)
 *         OR contains `stub` / `dummy` substring (case-insensitive).
 *         A real Android device on real silicon never reports these
 *         values — `ro.hardware.audio` on Pixel/Galaxy/Xiaomi names a
 *         real codec (`trondheim_audio`, `exynos_audio_2200`,
 *         `qcom_aud_codec_*`, etc).
 *   0.70  Audio loopback enabled — `persist.audio.loopback == "1"`.
 *         The loopback property routes microphone capture back through
 *         the speaker path as a software loop; emulators set this when
 *         the host forwards container audio out via a virtual sound
 *         card. Real Android devices DO NOT set this — loopback is a
 *         development/test surface, not a production state.
 *   0.70  Silent audio HAL — `ro.audio.silent.in == "1"`. The silent-in
 *         property tells the audio service to discard microphone input
 *         at the HAL level; containerized environments without a real
 *         mic set this to suppress empty-stream errors. A real device
 *         with a working microphone always reports `0` (or omits the
 *         property entirely).
 *   0.50  No HAL + no sound device — `ro.hardware.audio` is empty
 *         (set-but-empty) AND `/dev/snd/controlC0` does not exist.
 *         The ALSA control device `controlC0` is bound by every real
 *         Linux/Android kernel that exposes a working sound card; its
 *         absence + empty HAL property is consistent with a fully
 *         stubbed audio stack (containerized ReDroid mounting a host
 *         /dev without sound devices).
 *   0.50  Genymotion docking-audio profile — `ro.config.audio_dock_uses_aplc
 *         == "true"`. The `audio_dock_uses_aplc` property is a
 *         Genymotion-specific build-time flag that routes audio through
 *         the host's APLC (Audio Policy Configuration) stub when the
 *         emulated device is in a docked state. Real OEM devices do not
 *         emit this property.
 *   0.00  Clean — `ro.hardware.audio` names a real codec, no loopback,
 *         no silent-HAL flag, `/dev/snd/controlC0` present (or HAL name
 *         non-empty), no Genymotion docking flag.
 *
 * **FP class acknowledgment** (per rank-51 honest-framing pattern):
 *  The 0.50 "no HAL + no sound device" rule has a real FP class on
 *  ultra-budget feature phones / Android Go SKUs that ship without a
 *  loudspeaker — these legitimately omit `/dev/snd/controlC0` and may
 *  report empty `ro.hardware.audio`. The TRACE-severity + 0.50 score
 *  treats this rule as "contributing signal — combine with other
 *  probes via the downstream aggregator before any rejection decision".
 *  Same consumer-side gating pattern as rank-50/51.
 *
 * **Cross-rank reuse contract**: this probe reads only `ro.hardware.audio`
 * and `/dev/snd/controlC0` from its own surface. It does NOT consume any
 * shared model-substring lists (no phone-class gate is needed — the
 * software-HAL signature is universally suspect on any Android target).
 *
 * **Confidence:**
 *   0.70  At least one of the four observable surfaces returned a
 *         non-null/non-throwing answer (declarative cap — see KDoc above)
 *   0.50  All surfaces threw or returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 54 (mitigation_layer
 * `not_native`, `rasp_analog: none` — A17 §6 deprioritize: WebView-only
 * technique with no native Android RASP analog).
 */
class AudioFingerprintProbe : Probe {
    override val id = "ui.audio_fingerprint"
    override val rank = 54
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.APPLICATION
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_HARDWARE_AUDIO = "ro.hardware.audio"
        const val PROP_PERSIST_AUDIO_LOOPBACK = "persist.audio.loopback"
        const val PROP_RO_AUDIO_SILENT_IN = "ro.audio.silent.in"
        const val PROP_RO_CONFIG_AUDIO_DOCK_APLC = "ro.config.audio_dock_uses_aplc"
        const val PATH_ALSA_CONTROL_C0 = "/dev/snd/controlC0"

        /**
         * Canonical software-only audio HAL name used by containerized
         * Android (ReDroid), goldfish/ranchu AVDs, and the kernel ALSA
         * dummy driver. Case-sensitive exact match — the property surface
         * is OEM-set and the canonical value preserves underscores.
         */
        const val HAL_SND_CARD_DUMMY = "snd_card_dummy"

        /**
         * Substring markers (case-insensitive) for stubbed audio HAL
         * names other than the canonical `snd_card_dummy`. Real codec
         * names (`trondheim_audio`, `exynos_audio_2200`, `qcom_aud_*`)
         * never contain these tokens.
         */
        val SOFTWARE_HAL_SUBSTRINGS: List<String> = listOf(
            "stub",
            "dummy",
        )

        const val PATTERN_SOFTWARE_HAL = "software_only_audio_hal"
        const val PATTERN_LOOPBACK_ENABLED = "audio_loopback_enabled"
        const val PATTERN_SILENT_IN = "silent_audio_hal_in"
        const val PATTERN_NO_HAL_NO_DEVICE = "no_hal_no_alsa_device"
        const val PATTERN_GENYMOTION_DOCK_APLC = "genymotion_dock_aplc"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_SOFTWARE_HAL = 0.85
        const val SCORE_LOOPBACK_ENABLED = 0.70
        const val SCORE_SILENT_IN = 0.70
        const val SCORE_NO_HAL_NO_DEVICE = 0.50
        const val SCORE_GENYMOTION_DOCK_APLC = 0.50
        const val SCORE_CLEAN = 0.0

        /**
         * Declarative confidence cap. A real WebView AudioContext FFT
         * measurement would warrant 0.99; this probe infers the
         * fingerprint from proxy HAL markers so the cap is 0.70.
         */
        const val CONFIDENCE_DECLARATIVE = 0.70
        const val CONFIDENCE_NO_OBSERVATION = 0.50

        const val METHOD =
            "Declarative inference from audio HAL properties (ro.hardware.audio, " +
                "persist.audio.loopback, ro.audio.silent.in, ro.config.audio_dock_uses_aplc) " +
                "+ /dev/snd/controlC0 presence; predicts WebView AudioContext FFT " +
                "fingerprint anomaly without measuring it directly"

        /**
         * True iff [hal] is the canonical software-HAL name OR contains a
         * known stub/dummy substring (case-insensitive). Null/empty input
         * returns false — empty HAL has its own separate rule
         * (PATTERN_NO_HAL_NO_DEVICE) gated on the ALSA device presence.
         */
        internal fun isSoftwareHal(hal: String?): Boolean {
            if (hal.isNullOrEmpty()) return false
            if (hal == HAL_SND_CARD_DUMMY) return true
            val lower = hal.lowercase()
            return SOFTWARE_HAL_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val halName: String? = try {
                ctx.getSystemProperty(PROP_RO_HARDWARE_AUDIO)
            } catch (_: Throwable) {
                null
            }
            val loopback: String? = try {
                ctx.getSystemProperty(PROP_PERSIST_AUDIO_LOOPBACK)
            } catch (_: Throwable) {
                null
            }
            val silentIn: String? = try {
                ctx.getSystemProperty(PROP_RO_AUDIO_SILENT_IN)
            } catch (_: Throwable) {
                null
            }
            val dockAplc: String? = try {
                ctx.getSystemProperty(PROP_RO_CONFIG_AUDIO_DOCK_APLC)
            } catch (_: Throwable) {
                null
            }
            val alsaControlPresent: Boolean = try {
                ctx.fileExists(PATH_ALSA_CONTROL_C0)
            } catch (_: Throwable) {
                false
            }

            val softwareHal = isSoftwareHal(halName)
            val loopbackEnabled = loopback == "1"
            val silentInEnabled = silentIn == "1"
            // "no HAL + no device" rule only fires when the HAL property is
            // SET-BUT-EMPTY (empty string), not when it's null. A null answer
            // means the supplier couldn't read the property at all; an empty
            // string means the property exists with no value — a meaningful
            // distinction the snapshot harness preserves via "key not in map"
            // (null) vs "key → empty string" (empty).
            val noHalNoDevice = halName != null && halName.isEmpty() && !alsaControlPresent
            val genymotionDockAplc = dockAplc == "true"

            val (pattern, score) = when {
                softwareHal ->
                    PATTERN_SOFTWARE_HAL to SCORE_SOFTWARE_HAL
                loopbackEnabled ->
                    PATTERN_LOOPBACK_ENABLED to SCORE_LOOPBACK_ENABLED
                silentInEnabled ->
                    PATTERN_SILENT_IN to SCORE_SILENT_IN
                noHalNoDevice ->
                    PATTERN_NO_HAL_NO_DEVICE to SCORE_NO_HAL_NO_DEVICE
                genymotionDockAplc ->
                    PATTERN_GENYMOTION_DOCK_APLC to SCORE_GENYMOTION_DOCK_APLC
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence: declarative cap 0.70 if ANY surface was observable
            // (HAL prop returned a value — even empty — or any other prop
            // returned non-null, or the ALSA device path was queryable).
            // The fileExists call always returns a boolean answer, so the
            // observation channel for the device path is never "null" —
            // we treat it as observable when any property surface succeeded
            // OR when the file IS present (a positive observation).
            val anyPropObserved = halName != null ||
                loopback != null ||
                silentIn != null ||
                dockAplc != null
            val confidence = if (anyPropObserved || alsaControlPresent)
                CONFIDENCE_DECLARATIVE
            else
                CONFIDENCE_NO_OBSERVATION

            val halDisplay = halName?.ifEmpty { "<empty>" } ?: "<unavailable>"
            val loopbackDisplay = loopback ?: "<unavailable>"
            val silentInDisplay = silentIn ?: "<unavailable>"
            val dockAplcDisplay = dockAplc ?: "<unavailable>"

            val evidence = listOf(
                Evidence(
                    key = "audio.hal_name",
                    value = halDisplay,
                    expected = "real codec name (e.g. trondheim_audio, exynos_audio_2200)",
                ),
                Evidence(
                    key = "audio.loopback_enabled",
                    value = loopbackDisplay,
                    expected = "0 or unset",
                ),
                Evidence(
                    key = "audio.silent_in",
                    value = silentInDisplay,
                    expected = "0 or unset",
                ),
                Evidence(
                    key = "audio.dock_aplc",
                    value = dockAplcDisplay,
                    expected = "false or unset",
                ),
                Evidence(
                    key = "audio.alsa_controlC0_present",
                    value = alsaControlPresent.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "audio.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
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
                "AudioFingerprintProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

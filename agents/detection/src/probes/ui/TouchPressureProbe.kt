// inventory.yml rank 57, mitigation_layer not_spoofable
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #57 — ui.touch_pressure.
 *
 * **Real probe**: per inventory.yml, the rank-57 detection surface is
 * `MotionEvent.getPressure()` stream analysis — the FFT spectrum of a
 * recorded touch gesture's pressure samples reveals whether the input
 * came from a real capacitive touchscreen (continuous analog readings
 * with characteristic finger-skin/oleophobic-coating bandwidth) or from
 * a synthetic `uinput`-injected event stream (square-wave pressure
 * pulses at the injector's tick rate). That probe surface is
 * **not_spoofable** by design — it requires a runtime gesture from a
 * real human finger, which a cloud-phone container has no way to
 * fabricate.
 *
 * **This declarative variant** is the un-snapshottable surface's
 * complement: it scores against the *static* tells that a touchscreen
 * HAL is absent or synthetic without observing any runtime
 * MotionEvent. Two property + filesystem channels:
 *
 *   • `ro.hardware.touchscreen` system property — real Android phones
 *     populate this with the touch controller's IC name (`fts7250`
 *     on Pixel 7, `stmfts` on Galaxy S22, `qcom_synaptics_dsx` on
 *     several Qualcomm-class devices). Empty = no touchscreen HAL
 *     bound. Substring `uinput` / `virtual` = synthetic input device.
 *   • `/dev/input/event[0-4]` event-device file presence. Real
 *     phone-class Android exposes 3-5 input devices via evdev:
 *     touchscreen (`event0`), volume/power keys, sensors, sometimes
 *     a fingerprint sensor or stylus. Containerized ReDroid usually
 *     exposes only `event0` from a goldfish input stub (or nothing
 *     at all), so the count of populated event nodes is itself a
 *     strong tell.
 *
 * **Two synthetic-stack indicator props**: `ro.boot.touchscreen`
 * (some Cuttlefish / AVD variants set this to `"synthetic"`) and
 * `persist.input.synthetic` (custom uinput-emulating SpoofStack
 * modules sometimes leave this flag set during input-event injection
 * sessions). Both fire the 0.85 "test/spoof input HAL" rule.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the established rank-49/51/52 partition pattern):
 *   0.95  PATTERN_NO_TOUCH_HAL — `ro.hardware.touchscreen` empty AND
 *         NO `/dev/input/event*` files present. The container has no
 *         touchscreen HAL bound at all. This is the dispositive
 *         "container without input HAL" signal — no real Android
 *         phone boots without at least one evdev input device.
 *   0.85  PATTERN_SYNTHETIC_TOUCH_HAL — `ro.hardware.touchscreen`
 *         contains `uinput` or `virtual` (case-insensitive). The
 *         touchscreen IS bound, but to a synthetic uinput device.
 *   0.85  PATTERN_SPOOF_PROP_SET — `ro.boot.touchscreen == "synthetic"`
 *         OR `persist.input.synthetic == "1"`. Test/spoof input HAL
 *         marker prop is set.
 *   0.70  PATTERN_SINGLE_INPUT_DEVICE — `/dev/input/event0` exists but
 *         `/dev/input/event1`..`event4` ALL absent. A real Android
 *         phone has 3-5 input devices (touchscreen + buttons + sensor
 *         hub typically); only one input node is a typical
 *         containerized-goldfish-stub shape.
 *   0.50  PATTERN_AMBIGUOUS_SINGLE_DEVICE — `ro.hardware.touchscreen`
 *         empty AND exactly `/dev/input/event0` exists. Weak signal:
 *         could be a stripped Android Auto / Wear OS / embedded target,
 *         or could be a container with a minimal stub. 0.5 calibration
 *         per the rank-51/52 weak-signal playbook.
 *   0.00  PATTERN_CLEAN — touchscreen HAL named, 3+ event devices
 *         present, no spoof props.
 *
 * **FP class acknowledgment** (per rank-51 honest-framing pattern):
 * The SINGLE_INPUT_DEVICE and AMBIGUOUS_SINGLE_DEVICE rules have an
 * FP class on Android Auto head units and Wear OS watches, which
 * legitimately ship with a reduced input-device surface. Those
 * deployments are out-of-scope for the cloud-phone target evaluation
 * surface — score calibration (0.70 / 0.50) reflects this rather than
 * the dispositive-rule shape of the 0.95 NO_TOUCH_HAL tier.
 *
 * Confidence:
 *   0.75  Declarative inference — this probe stands in for the real
 *         MotionEvent-FFT analysis (not_spoofable per inventory), so
 *         even with full property+file observation the confidence is
 *         capped at the declarative-inference floor. Reflects that an
 *         attacker who fabricates `ro.hardware.touchscreen=fts7250`
 *         via Magisk resetprop AND populates synthetic `/dev/input/
 *         event*` nodes via `mknod` can defeat THIS probe without
 *         defeating the un-snapshottable runtime-pressure surface.
 *
 * Reference: shared/probes/inventory.yml rank 57 (mitigation_layer
 * `not_spoofable`, runtime FFT analysis L0). This declarative L3
 * surface is the snapshot-side fallback for environments where a
 * live touch capture cannot be obtained.
 */
class TouchPressureProbe : Probe {
    override val id = "ui.touch_pressure"
    override val rank = 57
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_HARDWARE_TOUCHSCREEN = "ro.hardware.touchscreen"
        const val PROP_RO_BOOT_TOUCHSCREEN = "ro.boot.touchscreen"
        const val PROP_PERSIST_INPUT_SYNTHETIC = "persist.input.synthetic"

        const val PATH_EVENT0 = "/dev/input/event0"
        const val PATH_EVENT1 = "/dev/input/event1"
        const val PATH_EVENT2 = "/dev/input/event2"
        const val PATH_EVENT3 = "/dev/input/event3"
        const val PATH_EVENT4 = "/dev/input/event4"

        val EVENT_PATHS_ALL: List<String> = listOf(
            PATH_EVENT0, PATH_EVENT1, PATH_EVENT2, PATH_EVENT3, PATH_EVENT4,
        )

        /**
         * Substring markers that identify a synthetic touchscreen HAL in
         * the `ro.hardware.touchscreen` value (case-insensitive).
         * Real touch-controller IC names never contain these tokens —
         * `fts7250` (Pixel 7), `stmfts` (Samsung STM FingerTipS),
         * `qcom_synaptics_dsx` (Qualcomm Synaptics DSX), `goodix_ts`
         * (Goodix), `nvt-ts` (Novatek), `ft5x06_ts` (FocalTech) are
         * representative real values. None contain `uinput` or `virtual`.
         */
        val SYNTHETIC_TOUCHSCREEN_SUBSTRINGS: List<String> = listOf(
            "uinput",
            "virtual",
        )

        /**
         * Value of `ro.boot.touchscreen` that fires the spoof-prop rule.
         * Cuttlefish / AVD variants sometimes leave this set during
         * input-stack initialization; real production Android leaves it
         * unset (null) or set to the same IC name as
         * `ro.hardware.touchscreen`.
         */
        const val BOOT_TOUCHSCREEN_SYNTHETIC = "synthetic"

        /**
         * Value of `persist.input.synthetic` that fires the spoof-prop
         * rule. Custom uinput-emulating SpoofStack modules use this flag
         * to coordinate with userland input-injector daemons; real
         * production Android never sets this property.
         */
        const val PERSIST_INPUT_SYNTHETIC_TRUE = "1"

        const val PATTERN_NO_TOUCH_HAL = "no_touch_hal"
        const val PATTERN_SYNTHETIC_TOUCH_HAL = "synthetic_touch_hal"
        const val PATTERN_SPOOF_PROP_SET = "spoof_prop_set"
        const val PATTERN_SINGLE_INPUT_DEVICE = "single_input_device"
        const val PATTERN_AMBIGUOUS_SINGLE_DEVICE = "ambiguous_single_device"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_NO_TOUCH_HAL = 0.95
        const val SCORE_SYNTHETIC_TOUCH_HAL = 0.85
        const val SCORE_SPOOF_PROP_SET = 0.85
        const val SCORE_SINGLE_INPUT_DEVICE = 0.70
        const val SCORE_AMBIGUOUS_SINGLE_DEVICE = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_DECLARATIVE = 0.75

        const val METHOD =
            "Read ro.hardware.touchscreen + /dev/input/event[0-4] presence; " +
                "detect missing touchscreen HAL, synthetic uinput-class HAL, " +
                "spoof-marker props, and minimal-input-surface containers " +
                "(declarative complement of the un-snapshottable MotionEvent " +
                "pressure FFT surface)"

        /**
         * True iff [touchscreenHal] contains any of the synthetic-HAL
         * markers from [SYNTHETIC_TOUCHSCREEN_SUBSTRINGS] (case-insensitive).
         * Returns false for null/empty.
         */
        internal fun hasSyntheticTouchscreenMarker(touchscreenHal: String?): Boolean {
            if (touchscreenHal.isNullOrEmpty()) return false
            val lower = touchscreenHal.lowercase()
            return SYNTHETIC_TOUCHSCREEN_SUBSTRINGS.any { it in lower }
        }

        /**
         * True iff EITHER of the two spoof-marker props is set to its
         * synthetic-marker value.
         */
        internal fun hasSpoofProp(
            roBootTouchscreen: String?,
            persistInputSynthetic: String?,
        ): Boolean {
            if (roBootTouchscreen == BOOT_TOUCHSCREEN_SYNTHETIC) return true
            if (persistInputSynthetic == PERSIST_INPUT_SYNTHETIC_TRUE) return true
            return false
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val touchscreenHal: String? = try {
                ctx.getSystemProperty(PROP_RO_HARDWARE_TOUCHSCREEN)
            } catch (_: Throwable) {
                null
            }
            val roBootTouchscreen: String? = try {
                ctx.getSystemProperty(PROP_RO_BOOT_TOUCHSCREEN)
            } catch (_: Throwable) {
                null
            }
            val persistInputSynthetic: String? = try {
                ctx.getSystemProperty(PROP_PERSIST_INPUT_SYNTHETIC)
            } catch (_: Throwable) {
                null
            }

            // Probe each /dev/input/event[0-4] for presence.
            val eventPresence: List<Boolean> = EVENT_PATHS_ALL.map { path ->
                try {
                    ctx.fileExists(path)
                } catch (_: Throwable) {
                    false
                }
            }
            val event0Present = eventPresence[0]
            val higherEventsPresent = eventPresence.drop(1).any { it }
            val totalEventDevices = eventPresence.count { it }

            val touchHalEmpty = touchscreenHal.isNullOrEmpty()
            val noTouchHal = touchHalEmpty && totalEventDevices == 0
            val syntheticTouchHal = hasSyntheticTouchscreenMarker(touchscreenHal)
            val spoofPropSet = hasSpoofProp(roBootTouchscreen, persistInputSynthetic)
            val singleInputDevice = event0Present && !higherEventsPresent &&
                !touchHalEmpty
            val ambiguousSingleDevice = touchHalEmpty && event0Present &&
                !higherEventsPresent

            val (pattern, score) = when {
                noTouchHal ->
                    PATTERN_NO_TOUCH_HAL to SCORE_NO_TOUCH_HAL
                syntheticTouchHal ->
                    PATTERN_SYNTHETIC_TOUCH_HAL to SCORE_SYNTHETIC_TOUCH_HAL
                spoofPropSet ->
                    PATTERN_SPOOF_PROP_SET to SCORE_SPOOF_PROP_SET
                singleInputDevice ->
                    PATTERN_SINGLE_INPUT_DEVICE to SCORE_SINGLE_INPUT_DEVICE
                ambiguousSingleDevice ->
                    PATTERN_AMBIGUOUS_SINGLE_DEVICE to SCORE_AMBIGUOUS_SINGLE_DEVICE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val evidence = listOf(
                Evidence(
                    key = "prop.$PROP_RO_HARDWARE_TOUCHSCREEN",
                    value = touchscreenHal ?: "<unavailable>",
                    expected = "<real touch controller IC name>",
                ),
                Evidence(
                    key = "prop.$PROP_RO_BOOT_TOUCHSCREEN",
                    value = roBootTouchscreen ?: "<unavailable>",
                    expected = "<unset or matches hardware.touchscreen>",
                ),
                Evidence(
                    key = "prop.$PROP_PERSIST_INPUT_SYNTHETIC",
                    value = persistInputSynthetic ?: "<unavailable>",
                    expected = "<unset>",
                ),
                Evidence(
                    key = "touch.event_devices_present",
                    value = totalEventDevices.toString(),
                    expected = ">= 3 on phone-class",
                ),
                Evidence(
                    key = "touch.has_synthetic_marker",
                    value = syntheticTouchHal.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "touch.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
                ),
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
                "TouchPressureProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

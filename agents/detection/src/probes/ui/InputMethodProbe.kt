// inventory.yml rank 58, mitigation_layer L1
package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #58 — ui.input_method.
 *
 * Reads `Settings.Secure.DEFAULT_INPUT_METHOD` and looks for two
 * signal classes:
 *
 *   • **Emulator/test IME marker (1.0)**: the default IME
 *     component name contains `emulator`, `test`, or `mock`
 *     substring (case-insensitive). Real OEM IMEs use stable
 *     package identifiers (Gboard = `com.google.android.inputmethod.latin`,
 *     Samsung Keyboard = `com.samsung.android.honeyboard`, SwiftKey
 *     = `com.touchtype.swiftkey`) — none of these substrings appear
 *     in production IME components. AVD/emulator default IMEs and
 *     test-only IMEs leak the keyword in the component name.
 *   • **Empty/null IME on phone-class (0.7)**: `default_input_method`
 *     is empty or null. Real Android phones always have a default
 *     IME — the keyboard is required for system text entry, so the
 *     setting is populated at first boot. An empty value indicates
 *     a stub or partially-initialized device.
 *   • **Clean (0.0)**: real OEM IME component present.
 *
 * Reuses base [ProbeContext.querySettingSecure] — same Settings.Secure
 * accessor used by rank-19 [com.detectorlab.probes.env.DeveloperOptionsProbe]
 * and rank-11 (Android ID).
 *
 * **`Settings.Secure` namespace**: `DEFAULT_INPUT_METHOD` is in
 * `Settings.Secure` per AOSP source (`android.provider.Settings.Secure`)
 * since API 1 — no namespace fallback needed. Same surface as rank-11
 * android_id.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per the established partition pattern):
 *   1.00  PATTERN_EMU_TEST_IME — component contains `emulator`,
 *         `test`, or `mock` substring
 *   0.70  PATTERN_EMPTY_OR_NULL_IME — setting is empty/null
 *         (cannot gate on phone-class because the IME setting is
 *         universal — even Wi-Fi tablets need a keyboard; the rule
 *         is unconditional but score-calibrated at 0.7 weak to
 *         reflect that some Android Auto / Wear OS / embedded
 *         devices legitimately lack a default IME)
 *   0.00  PATTERN_CLEAN — real IME component present
 *
 * **FP class acknowledgment** (per rank-51 honest-framing pattern):
 * the empty-or-null rule's FP class is Android Auto / Wear OS
 * embedded targets that lack an interactive keyboard — score 0.7
 * weak is the calibrated response. Cloud-phone deployments targeted
 * by this probe are full Android handsets, so the FP class is
 * out-of-scope for the target evaluation surface.
 *
 * Confidence:
 *   0.95  `querySettingSecure` returned non-null (regardless of value)
 *   0.50  accessor returned null OR threw
 *
 * Reference: shared/probes/inventory.yml rank 58 (mitigation_layer L1).
 */
class InputMethodProbe : Probe {
    override val id = "ui.input_method"
    override val rank = 58
    override val category = ProbeCategory.UI
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val SETTING_DEFAULT_INPUT_METHOD = "default_input_method"

        /**
         * Emulator / test / mock IME substring markers, case-insensitive.
         * Real OEM IMEs use stable component names that never contain
         * these tokens (Gboard, Samsung Keyboard, SwiftKey, FlorisBoard,
         * Microsoft SwiftKey, etc.). Substring match against the full
         * component name (`package/.ServiceImpl`).
         */
        val EMU_TEST_IME_SUBSTRINGS: List<String> = listOf(
            "emulator",
            "test",
            "mock",
        )

        const val PATTERN_EMU_TEST_IME = "emu_test_ime"
        const val PATTERN_EMPTY_OR_NULL_IME = "empty_or_null_ime"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_TEST_IME = 1.0
        const val SCORE_EMPTY_OR_NULL_IME = 0.7
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read Settings.Secure.default_input_method and detect " +
                "emulator/test/mock IME substrings or empty default IME " +
                "(real devices always populate this setting)"

        /**
         * True iff [imeComponent] contains any of the emulator/test
         * markers from [EMU_TEST_IME_SUBSTRINGS] (case-insensitive).
         * Returns false for null/empty.
         */
        internal fun hasEmuTestImeMarker(imeComponent: String?): Boolean {
            if (imeComponent.isNullOrEmpty()) return false
            val lower = imeComponent.lowercase()
            return EMU_TEST_IME_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val imeComponent: String? = try {
                ctx.querySettingSecure(SETTING_DEFAULT_INPUT_METHOD)
            } catch (_: Throwable) {
                null
            }

            val readable = imeComponent != null
            val hasEmuMarker = hasEmuTestImeMarker(imeComponent)
            val isEmptyOrNull = imeComponent.isNullOrEmpty()

            val (pattern, score) = when {
                hasEmuMarker ->
                    PATTERN_EMU_TEST_IME to SCORE_EMU_TEST_IME
                isEmptyOrNull ->
                    PATTERN_EMPTY_OR_NULL_IME to SCORE_EMPTY_OR_NULL_IME
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (readable) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val evidence = listOf(
                Evidence(
                    key = "secure.$SETTING_DEFAULT_INPUT_METHOD",
                    value = imeComponent ?: "<unavailable>",
                    expected = "<real OEM IME component>",
                ),
                Evidence(
                    key = "input_method.has_emu_marker",
                    value = if (imeComponent == null) "unknown" else hasEmuMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "input_method.is_populated",
                    value = if (imeComponent == null) "unknown" else (!isEmptyOrNull).toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "input_method.pattern",
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
                "InputMethodProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

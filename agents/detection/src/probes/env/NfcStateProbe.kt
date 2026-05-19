// inventory.yml rank 48, mitigation_layer not_spoofable
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.buildprop.BoardHardwareProbe

/**
 * Probe #48 — env.nfc_state.
 *
 * Reads NFC adapter presence + system feature flag + user enablement state
 * and looks for two signals:
 *   • **Contradiction (0.85)**: `PackageManager.FEATURE_NFC == true` but
 *     `NfcAdapter.getDefaultAdapter() == null`. The system claims NFC
 *     hardware exists but no adapter can be obtained — only possible
 *     under partial spoofing (e.g. a kit that masks the feature flag but
 *     doesn't simulate the underlying hardware service).
 *   • **Missing on flagship (0.5 weak)**: adapter is null AND model is a
 *     known flagship-phone-class device. Unlike rank-42/43/44 (proximity/
 *     light/magnetometer — nearly universal on phones) this rule is weak
 *     because NFC is genuinely not universal even on phones — many real
 *     budget Android phones legitimately ship without NFC. Only Pixel/
 *     Galaxy-S/Note/Fold/OnePlus class devices reliably include it.
 *
 * Reuses rank-28 [BoardHardwareProbe.FLAGSHIP_MODEL_SUBSTRINGS] and
 * [BoardHardwareProbe.isFlagshipModel] for the model gate. The semantic
 * here ("flagship Android phone family") is exactly the same as rank-28's
 * "model where empty board is structurally impossible", so the list is
 * shared (not duplicated). Anchored by an invariant test.
 *
 * **`NfcAdapter` / `PackageManager.hasSystemFeature` accessor gap.**
 * `ProbeContext` exposes no `queryNfcAdapter()` view and
 * `PackageManagerView.hasSystemFeature(...)` does not exist on the current
 * interface. Three constructor-injected suppliers handle the signals:
 *   - `adapterPresentSupplier: () -> Boolean?` — true if non-null adapter,
 *     false if null adapter on hardware-capable build, null if accessor
 *     itself unavailable
 *   - `nfcEnabledSupplier: () -> Boolean?` — true/false from
 *     `NfcAdapter.isEnabled()`, null if adapter is null
 *   - `featureNfcSupplier: () -> Boolean?` — true/false from
 *     `PackageManager.hasSystemFeature(FEATURE_NFC)`, null if accessor gap
 *
 * Production no-arg ctor returns null on all three; the probe can still
 * report adapter/feature/state evidence as "<unavailable>" and degrades
 * to confidence 0.50. Same gap-handling shape as ranks
 * 31/32/33/40/42/43/44/45/50/47.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.85  `FEATURE_NFC == true` AND adapter is `false` (i.e. null adapter)
 *         (system-feature-vs-adapter contradiction)
 *   0.50  Adapter is null AND model is in [FLAGSHIP_MODEL_SUBSTRINGS]
 *         (weak signal — flagship missing feature, but regional SKU
 *         variants exist)
 *   0.00  Adapter present, OR adapter null on non-flagship model
 *
 * Confidence:
 *   0.95  At least one of (adapterPresent, featureNfc) returned non-null
 *   0.50  Both returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 48 (mitigation_layer
 * not_spoofable — NFC is hardware-attested via the controller; can't be
 * forged at the user-space level).
 */
class NfcStateProbe(
    private val adapterPresentSupplier: () -> Boolean? = { null },
    private val nfcEnabledSupplier: () -> Boolean? = { null },
    private val featureNfcSupplier: () -> Boolean? = { null },
) : Probe {
    override val id = "env.nfc_state"
    override val rank = 48
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val PATTERN_CONTRADICTION = "feature_vs_adapter_contradiction"
        const val PATTERN_MISSING_ON_FLAGSHIP = "missing_on_flagship"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_CONTRADICTION = 0.85
        const val SCORE_MISSING_ON_FLAGSHIP = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read NfcAdapter + PackageManager.FEATURE_NFC; detect adapter-vs-" +
                "feature contradictions and missing-NFC on flagship-model devices. " +
                "Score 'no NFC' only weakly since NFC is not universal."
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val adapterPresent: Boolean? = try {
                adapterPresentSupplier()
            } catch (_: Throwable) {
                null
            }
            val nfcEnabled: Boolean? = try {
                nfcEnabledSupplier()
            } catch (_: Throwable) {
                null
            }
            val featureNfc: Boolean? = try {
                featureNfcSupplier()
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val flagshipModel = BoardHardwareProbe.isFlagshipModel(model)

            val contradiction = featureNfc == true && adapterPresent == false
            val missingOnFlagship = adapterPresent == false && flagshipModel &&
                // If the feature flag is true, the contradiction rule above
                // already fires (more specific signal). Only fire this
                // weaker rule when feature flag is false or unobservable.
                featureNfc != true
            val consistent = adapterPresent == null || featureNfc == null ||
                adapterPresent == featureNfc

            val (pattern, score) = when {
                contradiction ->
                    PATTERN_CONTRADICTION to SCORE_CONTRADICTION
                missingOnFlagship ->
                    PATTERN_MISSING_ON_FLAGSHIP to SCORE_MISSING_ON_FLAGSHIP
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (adapterPresent != null || featureNfc != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            fun displayBool(v: Boolean?): String = v?.toString() ?: "<unavailable>"

            val flagshipDisplay = when {
                model.isNullOrEmpty() -> "unknown"
                else -> flagshipModel.toString()
            }

            val evidence = listOf(
                Evidence(
                    key = "nfc.adapter_present",
                    value = displayBool(adapterPresent),
                    expected = "depends on model",
                ),
                Evidence(
                    key = "nfc.is_enabled",
                    value = displayBool(nfcEnabled),
                    expected = "depends on user setting",
                ),
                Evidence(
                    key = "nfc.has_system_feature",
                    value = displayBool(featureNfc),
                    expected = "matches adapter_present",
                ),
                Evidence(
                    key = "nfc.is_consistent",
                    value = consistent.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "nfc.is_flagship_model",
                    value = flagshipDisplay,
                    expected = "depends",
                ),
                Evidence(
                    key = "nfc.pattern",
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
                "NfcStateProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

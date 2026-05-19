// inventory.yml rank 49, mitigation_layer L0
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #49 — env.bluetooth_state.
 *
 * Reads Bluetooth adapter presence, state, and system feature flags +
 * BLE feature flag. Detects three signal classes:
 *   • **Contradiction (0.85)**: `PackageManager.FEATURE_BLUETOOTH == true`
 *     but `BluetoothAdapter.getDefaultAdapter() == null`. System claims
 *     BT hardware but no adapter — only possible under partial spoofing.
 *   • **Missing on phone-class (0.85)**: adapter is null AND model is
 *     phone-class. UNLIKE rank-48 NFC's flagship-gated 0.5 weak signal,
 *     Bluetooth is **near-universal** on Android phones — even budget
 *     2008-vintage phones included BT. Missing BT on ANY phone-class
 *     device is structurally suspicious, so the score is 0.85 (strong)
 *     not 0.5 (weak).
 *   • **Invalid state (0.7)**: `getState()` returned a value outside
 *     `BluetoothAdapter.STATE_*` constants (10-13). Real Android only
 *     produces these four values.
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the model gate
 * (same drift-safe pattern as rank-31/33/34/35/36/40/42/43/44). This
 * differs intentionally from rank-48 [NfcStateProbe]'s use of rank-28's
 * `FLAGSHIP_MODEL_SUBSTRINGS`: NFC's missing-feature only matters on
 * flagships, BT's missing-feature matters on ANY phone.
 *
 * **Cross-reference rank-31 [BluetoothMacProbe].** Rank 31 queries the BT
 * adapter's MAC address (a different attribute of the same adapter).
 * Same surface, different aspect — both can fire on the same device
 * independently. Rank 31 looks at the address content; rank 49 looks at
 * adapter presence/state/feature.
 *
 * **`BluetoothAdapter` / `PackageManager.hasSystemFeature` accessor gap.**
 * `ProbeContext` exposes no `queryBluetoothAdapter()` and no
 * `hasSystemFeature(...)`. Four constructor-injected suppliers:
 *   - `adapterPresentSupplier: () -> Boolean?` — null adapter ≡ false
 *   - `adapterStateSupplier: () -> Int?` — `BluetoothAdapter.getState()`
 *     value (10/11/12/13 valid; others invalid)
 *   - `featureBluetoothSupplier: () -> Boolean?` — FEATURE_BLUETOOTH
 *   - `featureBluetoothLeSupplier: () -> Boolean?` — FEATURE_BLUETOOTH_LE
 *
 * Production no-arg ctor returns null on all four; confidence floors at
 * 0.50 (degraded). Same gap-handling shape as rank 31/32/33/40/42-48.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   0.85  PATTERN_CONTRADICTION — `FEATURE_BLUETOOTH == true` AND adapter
 *         is `false` (system-feature-vs-adapter contradiction; not gated
 *         on phone-class since structural impossibility regardless)
 *   0.85  PATTERN_MISSING_ON_PHONE — adapter is `false` AND
 *         [NetworkTypeProbe.isPhoneClassModel] AND featureBluetooth != true
 *         (gated to NOT double-fire when contradiction already fires)
 *   0.70  PATTERN_INVALID_STATE — adapter present AND state value outside
 *         {10, 11, 12, 13}
 *   0.50  PATTERN_NO_LE_ON_MODERN — adapter present AND featureBluetoothLe
 *         explicitly false (weak signal — most modern phones have BLE,
 *         but some Android Go entry-level builds legitimately don't)
 *   0.00  PATTERN_CLEAN — adapter present + valid state + features OK
 *
 * **Cascade structure note.** The four scoring rules partition along the
 * `adapterPresent` axis:
 *   - `contradiction` / `missing_on_phone` require `adapter == false`
 *   - `invalid_state` / `no_le_on_modern` require `adapter == true`
 *
 * The two halves are mutually exclusive at the predicate level, so cross-
 * half rules cannot fire simultaneously. Cross-half cascade ordering
 * (e.g. `missing_on_phone` at 0.85 vs `invalid_state` at 0.70) is academic
 * since they can't both predicate true. Same-half rules DO need explicit
 * ordering — `contradiction` is checked before `missing_on_phone` so the
 * more-specific signal wins when both same-half predicates match.
 *
 * Confidence:
 *   0.95  At least one of (adapterPresent, featureBluetooth) returned
 *         non-null
 *   0.50  Both returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 49 (mitigation_layer L0).
 */
class BluetoothStateProbe(
    private val adapterPresentSupplier: () -> Boolean? = { null },
    private val adapterStateSupplier: () -> Int? = { null },
    private val featureBluetoothSupplier: () -> Boolean? = { null },
    private val featureBluetoothLeSupplier: () -> Boolean? = { null },
) : Probe {
    override val id = "env.bluetooth_state"
    override val rank = 49
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.LOW
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /** `BluetoothAdapter.STATE_*` constants (Android source). */
        const val STATE_OFF = 10
        const val STATE_TURNING_ON = 11
        const val STATE_ON = 12
        const val STATE_TURNING_OFF = 13

        val VALID_STATES = setOf(STATE_OFF, STATE_TURNING_ON, STATE_ON, STATE_TURNING_OFF)

        const val PATTERN_CONTRADICTION = "feature_vs_adapter_contradiction"
        const val PATTERN_MISSING_ON_PHONE = "missing_on_phone"
        const val PATTERN_INVALID_STATE = "invalid_state"
        const val PATTERN_NO_LE_ON_MODERN = "no_le_on_modern"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_CONTRADICTION = 0.85
        const val SCORE_MISSING_ON_PHONE = 0.85
        const val SCORE_INVALID_STATE = 0.70
        const val SCORE_NO_LE_ON_MODERN = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read BluetoothAdapter + PackageManager.FEATURE_BLUETOOTH/" +
                "BLUETOOTH_LE; detect missing-adapter on phone-class (BT is " +
                "near-universal on Android), feature-vs-adapter contradictions, " +
                "and invalid state values."

        /** Maps a raw state int to a human-readable name. */
        internal fun stateName(state: Int?): String = when (state) {
            null -> "<unavailable>"
            STATE_OFF -> "OFF"
            STATE_TURNING_ON -> "TURNING_ON"
            STATE_ON -> "ON"
            STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "INVALID($state)"
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val adapterPresent: Boolean? = try {
                adapterPresentSupplier()
            } catch (_: Throwable) {
                null
            }
            val adapterState: Int? = try {
                adapterStateSupplier()
            } catch (_: Throwable) {
                null
            }
            val featureBluetooth: Boolean? = try {
                featureBluetoothSupplier()
            } catch (_: Throwable) {
                null
            }
            val featureBluetoothLe: Boolean? = try {
                featureBluetoothLeSupplier()
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val contradiction = featureBluetooth == true && adapterPresent == false
            val missingOnPhone = adapterPresent == false && phoneClass &&
                // If feature_bt is true, contradiction rule fires first
                // (more specific signal). Only fire this rule when feature
                // is false or unobservable.
                featureBluetooth != true
            val invalidState = adapterPresent == true && adapterState != null &&
                adapterState !in VALID_STATES
            val noLeOnModern = adapterPresent == true && featureBluetoothLe == false

            // Tri-state consistency for evidence (rank-48 pattern):
            //   "true"     — both observed AND agree
            //   "false"    — both observed AND disagree (contradiction)
            //   "partial"  — exactly one observed
            //   "unknown"  — neither observed
            val consistentDisplay = when {
                adapterPresent == null && featureBluetooth == null -> "unknown"
                adapterPresent == null || featureBluetooth == null -> "partial"
                adapterPresent == featureBluetooth -> "true"
                else -> "false"
            }

            val (pattern, score) = when {
                contradiction ->
                    PATTERN_CONTRADICTION to SCORE_CONTRADICTION
                missingOnPhone ->
                    PATTERN_MISSING_ON_PHONE to SCORE_MISSING_ON_PHONE
                invalidState ->
                    PATTERN_INVALID_STATE to SCORE_INVALID_STATE
                noLeOnModern ->
                    PATTERN_NO_LE_ON_MODERN to SCORE_NO_LE_ON_MODERN
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (adapterPresent != null || featureBluetooth != null)
                CONFIDENCE_FULL
            else
                CONFIDENCE_DEGRADED

            fun displayBool(v: Boolean?): String = v?.toString() ?: "<unavailable>"

            val evidence = listOf(
                Evidence(
                    key = "bluetooth.adapter_present",
                    value = displayBool(adapterPresent),
                    expected = "true on phone-class",
                ),
                Evidence(
                    key = "bluetooth.state",
                    value = stateName(adapterState),
                    expected = "ON|OFF|TURNING_ON|TURNING_OFF",
                ),
                Evidence(
                    key = "bluetooth.state_raw",
                    value = adapterState?.toString() ?: "<unavailable>",
                    expected = "10|11|12|13",
                ),
                Evidence(
                    key = "bluetooth.has_system_feature",
                    value = displayBool(featureBluetooth),
                    expected = "true on phone-class",
                ),
                Evidence(
                    key = "bluetooth.has_le_feature",
                    value = displayBool(featureBluetoothLe),
                    expected = "true on Android 4.3+",
                ),
                Evidence(
                    key = "bluetooth.is_consistent",
                    value = consistentDisplay,
                    expected = "true",
                ),
                Evidence(
                    key = "bluetooth.pattern",
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
                "BluetoothStateProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

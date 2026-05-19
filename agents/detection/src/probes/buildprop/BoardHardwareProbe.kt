// inventory.yml rank 28, mitigation_layer L1
package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #28 — buildprop.board_hardware.
 *
 * Reads the board/platform/hardware-identifying system properties and looks
 * for emulator markers (`goldfish`, `goldfish_arm64`, `ranchu`, `redroid`,
 * `vbox86`, `cancro`) in `ro.hardware` or `ro.product.board`. Also detects
 * the structural impossibility of a Pixel/Galaxy model claim with no board
 * codename at all (real OEM builds always pin `ro.product.board`).
 *
 * Distinct from rank-1 [BuildFingerprintProbe] which checks
 * `ro.build.fingerprint` + tags + type + brand/model/manufacturer triplet.
 * This probe targets the **board/chipset layer**: a different surface that
 * tamper-resistance kits (LSPosed module / TrickyStore) frequently mask
 * differently than the build-fingerprint surface, so divergence between the
 * two probes is itself diagnostic.
 *
 * Reuses rank-1 [BuildFingerprintProbe]'s emulator-marker concept but with
 * an extended substring set tuned for the `ro.hardware`/`ro.product.board`
 * surfaces (rank 1 scans `ro.build.fingerprint`).
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  `ro.hardware` contains an emulator marker substring
 *         (case-insensitive: `goldfish`, `ranchu`, `redroid`, `vbox86`,
 *         `cancro`)
 *   1.00  `ro.product.board` contains an emulator marker substring
 *   0.95  `ro.product.board` is empty/blank AND `ro.product.model` claims
 *         a known flagship family (real OEM retail builds always pin
 *         `ro.product.board`; empty is the AOSP-emulator default)
 *   0.00  Properties readable + no emulator markers + board pinned
 *
 * Confidence:
 *   0.95  At least 2 of the 5 properties readable (non-null, non-empty)
 *   0.50  Exactly 1 readable (degraded — partial observation)
 *   0.30  Zero readable (no signal)
 *
 * Reference: shared/probes/inventory.yml rank 28 (mitigation_layer L1).
 */
class BoardHardwareProbe : Probe {
    override val id = "buildprop.board_hardware"
    override val rank = 28
    override val category = ProbeCategory.BUILDPROP
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 100L

    companion object {
        const val PROP_RO_PRODUCT_BOARD = "ro.product.board"
        const val PROP_RO_BOARD_PLATFORM = "ro.board.platform"
        const val PROP_RO_HARDWARE = "ro.hardware"

        /**
         * OEM-OPTIONAL property. Pixel devices don't always populate this;
         * Samsung/Xiaomi/MediaTek typically do. Empty/missing chipname is
         * NOT diagnostic on its own — never load-bearing for scoring.
         * Included in the [readable] confidence tally but other surfaces
         * (board/platform/hardware/manufacturer) dominate the count.
         */
        const val PROP_RO_HARDWARE_CHIPNAME = "ro.hardware.chipname"

        const val PROP_RO_BOARD_MANUFACTURER = "ro.board.manufacturer"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        /**
         * Emulator-marker substrings (case-insensitive) for `ro.hardware`
         * and `ro.product.board`. Extends rank-1's fingerprint marker list
         * with the board-layer specifics (`goldfish_arm64`, `vbox86`,
         * `cancro` test-image marker).
         *
         * **NOTE: Distinct from rank-42 [ProximityProbe.EMU_NAME_SUBSTRINGS].**
         * Both lists target emulator detection but on different surfaces:
         *   - [ProximityProbe.EMU_NAME_SUBSTRINGS] scans sensor vendor/name
         *     strings (where `avd` and `genymotion` appear naturally as
         *     `"AVD Generic"` / `"Genymotion Proximity"`)
         *   - [EMULATOR_BOARD_MARKERS] scans board/hardware system properties
         *     (where `vbox86` is the specific Genymotion board name and plain
         *     `vbox` alone would false-positive on Android-x86 host strings)
         *
         * Lists intentionally diverge; **DO NOT consolidate**. Differences from
         * rank-1's fingerprint set `{redroid, generic, ranchu, vbox, goldfish}`:
         *   - Added `cancro` (Xiaomi test-image / CI marker on synthetic builds)
         *   - Used `vbox86` (Genymotion-specific) instead of generic `vbox`
         *   - Omitted `generic` — too broad; many real custom AOSP ROMs contain
         *     `generic` in fingerprint strings but have pinned board codenames
         */
        val EMULATOR_BOARD_MARKERS: List<String> = listOf(
            "goldfish",      // covers goldfish + goldfish_arm64
            "ranchu",
            "redroid",
            "vbox86",        // specific Genymotion board name (NOT plain "vbox" —
                             // benign Android-x86 host strings contain "vbox")
            "cancro",        // Xiaomi test-image / CI marker on synthetic builds
        )

        /**
         * Flagship model substrings (case-insensitive) where empty
         * `ro.product.board` is structurally impossible — real OEM builds
         * always pin a codename for the SoC integration. Same family-prefix
         * pattern as rank-45 [BarometerProbe.KNOWN_BAROMETER_MODELS]:
         * substring entries cover regional SKU variants.
         *
         * Source: GSMArena spec sheets cross-referenced against AOSP
         * device trees. Lab approximation — real-device telemetry
         * required to validate (joins the rank 22 / 23 / 31 / 45 /
         * cross-cutting follow-up #5 lab-approximation family).
         */
        val FLAGSHIP_MODEL_SUBSTRINGS: List<String> = listOf(
            "pixel ",                       // Pixel 4/5/6/7/8/9 + variants
            "sm-s",                         // Galaxy S series
            "sm-n",                         // Galaxy Note
            "sm-f",                         // Galaxy Fold/Flip
            "oneplus",
        )

        const val PATTERN_EMU_HARDWARE = "emu_hardware"
        const val PATTERN_EMU_BOARD = "emu_board"
        const val PATTERN_EMPTY_BOARD_ON_FLAGSHIP = "empty_board_on_flagship"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_EMU_HARDWARE = 1.0
        const val SCORE_EMU_BOARD = 1.0
        const val SCORE_EMPTY_BOARD_ON_FLAGSHIP = 0.95
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.50
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read ro.product.board + ro.board.platform + ro.hardware + " +
                "ro.hardware.chipname + ro.board.manufacturer; detect " +
                "goldfish/ranchu/vbox86/redroid emulator markers; cross-check " +
                "board codename vs claimed model"

        /**
         * True iff [value] contains any emulator marker substring
         * (case-insensitive). Returns false for null/empty.
         */
        internal fun hasEmulatorMarker(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val lower = value.lowercase()
            return EMULATOR_BOARD_MARKERS.any { it in lower }
        }

        /**
         * True iff [model] contains any flagship-family substring
         * (case-insensitive). Returns false for null/empty.
         */
        internal fun isFlagshipModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return FLAGSHIP_MODEL_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Read each property independently so one throwing read doesn't
            // poison the rest.
            val board: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_BOARD)
            } catch (_: Throwable) {
                null
            }
            val platform: String? = try {
                ctx.getSystemProperty(PROP_RO_BOARD_PLATFORM)
            } catch (_: Throwable) {
                null
            }
            val hardware: String? = try {
                ctx.getSystemProperty(PROP_RO_HARDWARE)
            } catch (_: Throwable) {
                null
            }
            val chipname: String? = try {
                ctx.getSystemProperty(PROP_RO_HARDWARE_CHIPNAME)
            } catch (_: Throwable) {
                null
            }
            val boardManufacturer: String? = try {
                ctx.getSystemProperty(PROP_RO_BOARD_MANUFACTURER)
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val emuHardware = hasEmulatorMarker(hardware)
            val emuBoard = hasEmulatorMarker(board)
            val isEmulatorMarker = emuHardware || emuBoard

            val boardEmpty = board.isNullOrEmpty()
            val flagshipModel = isFlagshipModel(model)
            val emptyBoardOnFlagship = boardEmpty && flagshipModel

            val (pattern, score) = when {
                emuHardware ->
                    PATTERN_EMU_HARDWARE to SCORE_EMU_HARDWARE
                emuBoard ->
                    PATTERN_EMU_BOARD to SCORE_EMU_BOARD
                emptyBoardOnFlagship ->
                    PATTERN_EMPTY_BOARD_ON_FLAGSHIP to SCORE_EMPTY_BOARD_ON_FLAGSHIP
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence based on how many of the 5 main board/hardware
            // properties returned non-empty values. (model is used for the
            // cascade gate but doesn't count toward the board-observation
            // surface count.)
            val readable = listOf(board, platform, hardware, chipname, boardManufacturer)
                .count { !it.isNullOrEmpty() }
            val confidence = when {
                readable >= 2 -> CONFIDENCE_FULL
                readable == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            fun display(value: String?): String =
                value?.ifEmpty { "<empty>" } ?: "<unreadable>"

            val evidence = listOf(
                Evidence(
                    key = "ro.product.board",
                    value = display(board),
                    expected = "<not empty, not goldfish>",
                ),
                Evidence(
                    key = "ro.board.platform",
                    value = display(platform),
                    expected = "<real SoC>",
                ),
                Evidence(
                    key = "ro.hardware",
                    value = display(hardware),
                    expected = "<not goldfish/ranchu/vbox86/redroid>",
                ),
                Evidence(
                    key = "ro.hardware.chipname",
                    value = display(chipname),
                    expected = "<vendor chipset name>",
                ),
                Evidence(
                    key = "ro.board.manufacturer",
                    value = display(boardManufacturer),
                    expected = "<Qualcomm|Samsung|MediaTek|Google|etc>",
                ),
                Evidence(
                    key = "board.is_emulator_marker",
                    value = isEmulatorMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "board.pattern",
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
                "BoardHardwareProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

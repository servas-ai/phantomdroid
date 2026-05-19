// inventory.yml rank 53, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #53 — env.camera_info.
 *
 * Enumerates `CameraManager.getCameraIdList()` and reads
 * `CameraCharacteristics` per camera, looking for five emulator/spoof
 * tell classes:
 *   • **Vendor emulator keyword (1.0 dispositive)**: vendor metadata
 *     (manufacturer/info strings) contains an emulator marker
 *     (`goldfish`, `ranchu`, `redroid`, `vbox86`, `cancro`). Same surface-
 *     and-semantic as rank-28 [BoardHardwareProbe.EMULATOR_BOARD_MARKERS]
 *     — emulator vendor identifiers leak everywhere the Camera HAL
 *     advertises itself. Reuse, do NOT redefine.
 *   • **Zero cameras on phone-class (0.9)**: `cameraIdList` empty AND
 *     model is phone-class (rank-25 `isPhoneClassModel`). Real Android
 *     phones since ~2010 ship with ≥2 cameras (front + rear). Strong
 *     signal because the FP class is ultra-budget Android Go devices
 *     which are vanishingly rare on cloud-phone infra.
 *   • **Single camera on multi-camera flagship (0.85)**: `cameraIdList`
 *     size == 1 AND model is in [KNOWN_MULTI_CAMERA_FLAGSHIPS]. Pixel
 *     6+ / Galaxy S22+ / OnePlus 9+ / Xiaomi 13+ all expose 3-6 camera
 *     IDs (multi-lens rear arrays + front cam). Single-camera on these
 *     models is structurally implausible.
 *   • **All LEGACY hardware level on multi-camera flagship (0.85)**:
 *     every `INFO_SUPPORTED_HARDWARE_LEVEL` == 0 (LEGACY) AND model is
 *     in [KNOWN_MULTI_CAMERA_FLAGSHIPS]. Pixel 7+ exposes LEVEL_3 (3),
 *     Galaxy S22+ exposes FULL (2). LEGACY on a flagship indicates the
 *     emulator's Camera HAL is the AOSP fallback stub. **FP class
 *     documented per rank-51 playbook**: heavily-custom AOSP ROMs may
 *     strip a real flagship's Camera HAL to LEGACY — rare but possible.
 *     Score stays at 0.85 (strong) per team-lead's brief; consumer-side
 *     aggregator should combine with other camera signals for high-
 *     confidence reject.
 *   • **Implausible sensor size (0.85)**: minimum physical-sensor
 *     diagonal == 0.0f OR > 100mm. Real Android sensors are 2-15mm
 *     (smartphone) up to 35-40mm (rare medium-format add-ons). 0.0 means
 *     the supplier returned a stub value; >100mm is structurally
 *     impossible on any phone form factor. Strong rule (model-agnostic).
 *
 * **`CameraManager` accessor gap.** Same gap-handling shape as rank
 * 23/31-49/51/52. `ProbeContext` exposes no `queryCameraManager()` view.
 * Five constructor-injected suppliers:
 *   - `cameraCountSupplier: () -> Int?` — `cameraIdList.size`
 *   - `cameraFacingsSupplier: () -> List<Int>?` — per-camera
 *     `LENS_FACING` (0=FRONT, 1=BACK, 2=EXTERNAL)
 *   - `cameraHardwareLevelsSupplier: () -> List<Int>?` — per-camera
 *     `INFO_SUPPORTED_HARDWARE_LEVEL` (0=LEGACY, 1=LIMITED, 2=FULL,
 *     3=LEVEL_3, 4=EXTERNAL)
 *   - `cameraMinSensorSizeMmSupplier: () -> Float?` — smallest diagonal
 *     from `SENSOR_INFO_PHYSICAL_SIZE` across all cameras, in mm
 *   - `cameraVendorStringSupplier: () -> String?` — concatenated vendor
 *     metadata (`INFO_VERSION`, `INFO_VENDOR_ID`, manufacturer strings)
 *     for substring scanning
 *
 * Production no-arg ctor returns null on all five → confidence 0.50
 * (degraded). The probe still emits evidence rows as `<unavailable>`.
 *
 * Reuses **TWO** existing helpers per drift-safe reuse pattern:
 *   1. [NetworkTypeProbe.isPhoneClassModel] for the zero-cameras gate
 *      (same as rank-31/33/34/35/36/40/42/43/44/47/49/50).
 *   2. [BoardHardwareProbe.EMULATOR_BOARD_MARKERS] for the vendor-string
 *      keyword scan. Same surface AND same semantic as rank-28: a
 *      Camera HAL vendor field containing `goldfish` is the same
 *      emulator tell as `ro.product.board=goldfish`. Reuse, don't
 *      duplicate.
 *
 * NEW list: [KNOWN_MULTI_CAMERA_FLAGSHIPS] — distinct from rank-46
 * [com.detectorlab.probes.ui.RefreshRateProbe.KNOWN_120HZ_MODELS] and
 * rank-52 [com.detectorlab.probes.ui.DisplayCutoutProbe.KNOWN_CUTOUT_MODELS].
 * Same surface (model string), different semantic (multi-lens rear
 * camera array — Pixel 6+ has dual/triple rear; Pixel 5 is single rear
 * with single front so NOT in this list). Drift-alarm invariant test
 * pins the cutoff distinctions across the three lists.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first per rank-49/51/52 partition pattern):
 *   1.0   PATTERN_VENDOR_EMULATOR_KEYWORD — dispositive; emulator
 *         identifiers in Camera HAL vendor metadata
 *   0.9   PATTERN_NO_CAMERAS_ON_PHONE — count == 0 AND phone-class
 *   0.85  PATTERN_SINGLE_CAMERA_ON_FLAGSHIP — count == 1 AND model in
 *         [KNOWN_MULTI_CAMERA_FLAGSHIPS]
 *   0.85  PATTERN_ALL_LEGACY_ON_FLAGSHIP — every level == 0 AND model
 *         in [KNOWN_MULTI_CAMERA_FLAGSHIPS]
 *   0.85  PATTERN_IMPLAUSIBLE_SENSOR_SIZE — min diagonal == 0.0 OR
 *         > 100mm (model-agnostic structural impossibility)
 *   0.0   PATTERN_CLEAN — multi-camera with FULL+ levels + reasonable
 *         sensor size + clean vendor strings
 *
 * Confidence:
 *   0.95  Any of (count, facings, levels, sensor, vendor) returned
 *         non-null
 *   0.50  All five returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 53 (mitigation_layer L1).
 */
class CameraInfoProbe(
    private val cameraCountSupplier: () -> Int? = { null },
    private val cameraFacingsSupplier: () -> List<Int>? = { null },
    private val cameraHardwareLevelsSupplier: () -> List<Int>? = { null },
    private val cameraMinSensorSizeMmSupplier: () -> Float? = { null },
    private val cameraVendorStringSupplier: () -> String? = { null },
) : Probe {
    override val id = "env.camera_info"
    override val rank = 53
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 300L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        // Android CameraCharacteristics.LENS_FACING constants.
        const val LENS_FACING_FRONT = 0
        const val LENS_FACING_BACK = 1
        const val LENS_FACING_EXTERNAL = 2

        // Android CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL.
        const val HARDWARE_LEVEL_LEGACY = 0
        const val HARDWARE_LEVEL_LIMITED = 1
        const val HARDWARE_LEVEL_FULL = 2
        const val HARDWARE_LEVEL_3 = 3
        const val HARDWARE_LEVEL_EXTERNAL = 4

        /**
         * Maximum plausible physical sensor diagonal in mm. Smartphone
         * sensors are 2-15mm; even large 1-inch sensors (Sony Xperia
         * Pro-I, Xiaomi 13 Ultra) top out near 16mm diagonal. Some
         * external/medium-format cinema attachments reach 35-40mm. Above
         * 100mm is structurally impossible on any phone form factor —
         * exceeds the height of the device itself. Anything > 100mm is
         * a stub value from a broken emulator HAL.
         */
        const val MAX_PLAUSIBLE_SENSOR_DIAGONAL_MM = 100.0f

        /**
         * Models that ship with multi-camera (multi-lens-rear) arrays.
         * Substring match against `ro.product.model` (case-insensitive).
         *
         * **Source**: OEM spec pages + GSMArena cross-reference, May
         * 2026. Lab approximation — joins the rank 22 / 23 / 28 / 31 /
         * 45 / 46 / 52 / cross-cutting-followup-#5 lab-approximation
         * family.
         *
         * **Selection criteria** (team-lead's `≤30 entries OR ≥3
         * variants each` rule):
         *   • Pixel 6 and later (Pixel 6/7/8/9 ALL ship with multi-lens
         *     rear array — wide + ultrawide minimum on standard, +
         *     telephoto on Pro/Fold). NOTE Pixel 5 is INTENTIONALLY
         *     NOT in this list — Pixel 5 has a single rear lens
         *     (12.2MP wide only) so single-camera Pixel 5 is
         *     legitimate. This is the cross-list cutoff distinction
         *     from rank-52 [DisplayCutoutProbe.KNOWN_CUTOUT_MODELS]
         *     which DOES include `pixel 5` (cutout-vs-multicam
         *     semantic difference).
         *   • Galaxy S22 and later (multi-lens rear standard on all
         *     S22+ family; S22 base has triple rear)
         *   • Galaxy Note 20 and later (multi-lens)
         *   • Galaxy Z Fold 4 and later (multi-lens)
         *   • OnePlus 9 and later (Hasselblad-collab multi-lens)
         *   • Xiaomi 13 and later (Leica-collab multi-lens)
         *
         * **Cross-list cutoff comparison** (three lists, three
         * cutoffs):
         *   - rank-46 `KNOWN_120HZ_MODELS`     → Pixel 7+
         *   - rank-52 `KNOWN_CUTOUT_MODELS`    → Pixel 5+
         *   - rank-53 `KNOWN_MULTI_CAMERA_FLAGSHIPS` → Pixel 6+
         *
         * Each cutoff is per-OEM-spec-verified for the specific
         * semantic (120Hz refresh / hole-punch cutout / multi-lens rear).
         */
        val KNOWN_MULTI_CAMERA_FLAGSHIPS: List<String> = listOf(
            "pixel 6", "pixel 7", "pixel 8", "pixel 9", "pixel fold",
            "sm-s901", "sm-s906", "sm-s908",   // Galaxy S22 family
            "sm-s911", "sm-s916", "sm-s918",   // Galaxy S23 family
            "sm-s921", "sm-s926", "sm-s928",   // Galaxy S24 family
            "sm-s931", "sm-s936", "sm-s938",   // Galaxy S25 family
            "sm-n981", "sm-n986",              // Galaxy Note 20 family
            "sm-f936",                          // Galaxy Z Fold 4
            "sm-f946",                          // Galaxy Z Fold 5
            "oneplus 9", "oneplus 10", "oneplus 11", "oneplus 12",
            "mi 13", "mi 14", "xiaomi 13", "xiaomi 14",
        )

        const val PATTERN_VENDOR_EMULATOR_KEYWORD = "vendor_emulator_keyword"
        const val PATTERN_NO_CAMERAS_ON_PHONE = "no_cameras_on_phone"
        const val PATTERN_SINGLE_CAMERA_ON_FLAGSHIP = "single_camera_on_flagship"
        const val PATTERN_ALL_LEGACY_ON_FLAGSHIP = "all_legacy_on_flagship"
        const val PATTERN_IMPLAUSIBLE_SENSOR_SIZE = "implausible_sensor_size"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_VENDOR_EMULATOR_KEYWORD = 1.0
        const val SCORE_NO_CAMERAS_ON_PHONE = 0.9
        const val SCORE_SINGLE_CAMERA_ON_FLAGSHIP = 0.85
        const val SCORE_ALL_LEGACY_ON_FLAGSHIP = 0.85
        const val SCORE_IMPLAUSIBLE_SENSOR_SIZE = 0.85
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Enumerate CameraManager.getCameraIdList() + characteristics " +
                "per camera; detect zero/single-camera on phone-class, " +
                "LEGACY-level on flagship, implausible sensor sizes, and " +
                "emulator vendor keywords"

        /**
         * True iff [model] is in [KNOWN_MULTI_CAMERA_FLAGSHIPS]
         * (case-insensitive). Same shape as rank-46 / rank-52 helpers.
         */
        internal fun isMultiCameraFlagshipModel(model: String?): Boolean {
            if (model.isNullOrEmpty()) return false
            val lower = model.lowercase()
            return KNOWN_MULTI_CAMERA_FLAGSHIPS.any { it in lower }
        }

        /**
         * Format the LENS_FACING code as a human-readable string for
         * evidence rows. Unknown values surface as `OTHER(n)` rather
         * than being dropped — keeps drift visible to operators.
         */
        internal fun facingName(facing: Int): String = when (facing) {
            LENS_FACING_FRONT -> "FRONT"
            LENS_FACING_BACK -> "BACK"
            LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "OTHER($facing)"
        }

        /**
         * Format a hardware-level code as a human-readable string.
         * Unknown values surface as `OTHER(n)`.
         */
        internal fun hardwareLevelName(level: Int): String = when (level) {
            HARDWARE_LEVEL_LEGACY -> "LEGACY"
            HARDWARE_LEVEL_LIMITED -> "LIMITED"
            HARDWARE_LEVEL_FULL -> "FULL"
            HARDWARE_LEVEL_3 -> "LEVEL_3"
            HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "OTHER($level)"
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val count: Int? = try {
                cameraCountSupplier()
            } catch (_: Throwable) {
                null
            }
            val facings: List<Int>? = try {
                cameraFacingsSupplier()
            } catch (_: Throwable) {
                null
            }
            val levels: List<Int>? = try {
                cameraHardwareLevelsSupplier()
            } catch (_: Throwable) {
                null
            }
            val minSensorMm: Float? = try {
                cameraMinSensorSizeMmSupplier()
            } catch (_: Throwable) {
                null
            }
            val vendorString: String? = try {
                cameraVendorStringSupplier()
            } catch (_: Throwable) {
                null
            }

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)
            val multiCameraFlagship = isMultiCameraFlagshipModel(model)

            val vendorHasEmuKeyword = vendorString != null &&
                BoardHardwareProbe.hasEmulatorMarker(vendorString)

            val noCamerasOnPhone = count == 0 && phoneClass

            val singleCameraOnFlagship = count == 1 && multiCameraFlagship

            // `levels` empty list collapses to true vacuously under `all { }`
            // which would mis-fire when a downstream wrapper returned an
            // empty list for an emulator with no cameras. Guard with
            // explicit isNotEmpty() so the rule only fires on observed
            // levels.
            val allLegacyOnFlagship = levels != null && levels.isNotEmpty() &&
                levels.all { it == HARDWARE_LEVEL_LEGACY } &&
                multiCameraFlagship

            val implausibleSensorSize = minSensorMm != null && (
                minSensorMm == 0.0f || minSensorMm > MAX_PLAUSIBLE_SENSOR_DIAGONAL_MM
            )

            val (pattern, score) = when {
                vendorHasEmuKeyword ->
                    PATTERN_VENDOR_EMULATOR_KEYWORD to SCORE_VENDOR_EMULATOR_KEYWORD
                noCamerasOnPhone ->
                    PATTERN_NO_CAMERAS_ON_PHONE to SCORE_NO_CAMERAS_ON_PHONE
                singleCameraOnFlagship ->
                    PATTERN_SINGLE_CAMERA_ON_FLAGSHIP to SCORE_SINGLE_CAMERA_ON_FLAGSHIP
                allLegacyOnFlagship ->
                    PATTERN_ALL_LEGACY_ON_FLAGSHIP to SCORE_ALL_LEGACY_ON_FLAGSHIP
                implausibleSensorSize ->
                    PATTERN_IMPLAUSIBLE_SENSOR_SIZE to SCORE_IMPLAUSIBLE_SENSOR_SIZE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val anyObserved = count != null || facings != null || levels != null ||
                minSensorMm != null || vendorString != null
            val confidence = if (anyObserved) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val facingBackCount = facings?.count { it == LENS_FACING_BACK }
            val facingFrontCount = facings?.count { it == LENS_FACING_FRONT }

            val hardwareLevelsDisplay = when {
                levels == null -> "<unavailable>"
                levels.isEmpty() -> "<empty>"
                else -> levels.joinToString(",") { hardwareLevelName(it) }
            }

            val evidence = listOf(
                Evidence(
                    key = "camera.count",
                    value = count?.toString() ?: "<unavailable>",
                    expected = ">=2 on phone-class",
                ),
                Evidence(
                    key = "camera.facing_back_count",
                    value = facingBackCount?.toString() ?: "<unavailable>",
                    expected = ">=1",
                ),
                Evidence(
                    key = "camera.facing_front_count",
                    value = facingFrontCount?.toString() ?: "<unavailable>",
                    expected = ">=1",
                ),
                Evidence(
                    key = "camera.hardware_levels",
                    value = hardwareLevelsDisplay,
                    expected = "FULL+ on flagship",
                ),
                Evidence(
                    key = "camera.sensor_size_min_mm",
                    value = minSensorMm?.toString() ?: "<unavailable>",
                    expected = "2-15 mm",
                ),
                Evidence(
                    key = "camera.has_emulator_keyword",
                    value = when (vendorString) {
                        null -> "<unavailable>"
                        else -> vendorHasEmuKeyword.toString()
                    },
                    expected = "false",
                ),
                Evidence(
                    key = "camera.pattern",
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
                "CameraInfoProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

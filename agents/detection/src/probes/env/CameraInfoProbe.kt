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
 *   • **Missing direction on phone-class (0.85)**: `facings` non-null
 *     AND model is phone-class AND either front-facing count == 0 OR
 *     back-facing count == 0. Real phones always have BOTH front and
 *     back cameras since ~2011 (front-camera ubiquity post-Android-2.3).
 *     The "count >= 2 but all same direction" case is a structural
 *     impossibility this rule catches that the zero-cameras rule misses.
 *     Tablets ARE excluded via the phone-class gate (Lenovo Tab P11
 *     base legitimately ships without back camera).
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
 *     confidence reject. The empty-list guard at the rule predicate
 *     prevents Kotlin's `emptyList.all { }` from vacuously firing — a
 *     pitfall worth noting as a reusable defensive pattern.
 *   • **Implausible sensor width (0.85)**: minimum physical-sensor
 *     **width** < 2mm OR > 15mm. Reads `SizeF.getWidth()` from
 *     `SENSOR_INFO_PHYSICAL_SIZE` per team-lead's spec (NOT diagonal).
 *     Real Android smartphone sensors are 2-15mm wide; even large
 *     1-inch sensors (Sony Xperia Pro-I, Xiaomi 13 Ultra) cap near
 *     13mm width. < 2mm catches the sub-physical-size stub case
 *     (including the supplier-returns-0.0f sentinel as a special case
 *     of the lower bound); > 15mm catches the implausibly-large stub.
 *     Strong rule (model-agnostic structural impossibility).
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
 *   - `cameraMinSensorWidthMmSupplier: () -> Float?` — smallest WIDTH
 *     value from `SENSOR_INFO_PHYSICAL_SIZE.getWidth()` across all
 *     cameras, in mm (per team-lead spec — NOT diagonal)
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
 *   0.85  PATTERN_MISSING_DIRECTION_ON_PHONE — phone-class AND facings
 *         missing either FRONT or BACK direction entirely (placed AFTER
 *         no_cameras so the latter wins on the all-empty case which is
 *         the more specific signal)
 *   0.85  PATTERN_SINGLE_CAMERA_ON_FLAGSHIP — count == 1 AND model in
 *         [KNOWN_MULTI_CAMERA_FLAGSHIPS]
 *   0.85  PATTERN_ALL_LEGACY_ON_FLAGSHIP — every level == 0 AND model
 *         in [KNOWN_MULTI_CAMERA_FLAGSHIPS]
 *   0.85  PATTERN_IMPLAUSIBLE_SENSOR_WIDTH — min sensor width < 2mm
 *         OR > 15mm (model-agnostic structural impossibility; catches
 *         both stub-zero and stub-out-of-range)
 *   0.0   PATTERN_CLEAN — multi-camera with FULL+ levels + reasonable
 *         sensor size + clean vendor strings
 *
 * Confidence:
 *   0.95  Any of (count, facings, levels, sensor width, vendor)
 *         returned non-null
 *   0.50  All five returned null (no observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 53 (mitigation_layer L1).
 */
class CameraInfoProbe(
    private val cameraCountSupplier: () -> Int? = { null },
    private val cameraFacingsSupplier: () -> List<Int>? = { null },
    private val cameraHardwareLevelsSupplier: () -> List<Int>? = { null },
    private val cameraMinSensorWidthMmSupplier: () -> Float? = { null },
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
         * Plausible smartphone sensor width range in mm, per team-lead's
         * spec read of `SizeF.getWidth()` from
         * `SENSOR_INFO_PHYSICAL_SIZE`. Smartphone main sensors are
         * 5-10mm wide; ultrawide / depth / monochrome sensors run
         * smaller (2-4mm); the largest 1-inch type-1 sensors (Sony
         * Xperia Pro-I, Xiaomi 13 Ultra) cap near 13mm width. The
         * bounds catch both:
         *   • sub-2mm widths — stub `0.0f` and tiny-stub values; a real
         *     sensor below 2mm is structurally smaller than any current
         *     smartphone module
         *   • > 15mm widths — anything in medium-format territory;
         *     no Android phone ships such a sensor as of 2026
         */
        const val MIN_PLAUSIBLE_SENSOR_WIDTH_MM = 2.0f
        const val MAX_PLAUSIBLE_SENSOR_WIDTH_MM = 15.0f

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
        const val PATTERN_MISSING_DIRECTION_ON_PHONE = "missing_direction_on_phone"
        const val PATTERN_SINGLE_CAMERA_ON_FLAGSHIP = "single_camera_on_flagship"
        const val PATTERN_ALL_LEGACY_ON_FLAGSHIP = "all_legacy_on_flagship"
        const val PATTERN_IMPLAUSIBLE_SENSOR_WIDTH = "implausible_sensor_width"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_VENDOR_EMULATOR_KEYWORD = 1.0
        const val SCORE_NO_CAMERAS_ON_PHONE = 0.9
        const val SCORE_MISSING_DIRECTION_ON_PHONE = 0.85
        const val SCORE_SINGLE_CAMERA_ON_FLAGSHIP = 0.85
        const val SCORE_ALL_LEGACY_ON_FLAGSHIP = 0.85
        const val SCORE_IMPLAUSIBLE_SENSOR_WIDTH = 0.85
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
            val minSensorWidthMm: Float? = try {
                cameraMinSensorWidthMmSupplier()
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

            // Missing-direction rule per team-lead watch #4. Predicate
            // requires phoneClass (tablets legitimately ship without back
            // cam — Lenovo Tab P11 base) AND facings observed AND at
            // least one of FRONT / BACK direction entirely absent. The
            // empty-list case where ALL facings are missing is also
            // caught here, but the more-specific no_cameras_on_phone
            // rule fires first when count == 0 (placed earlier in the
            // cascade).
            val missingDirectionOnPhone = facings != null && phoneClass && facings.isNotEmpty() && (
                facings.count { it == LENS_FACING_FRONT } == 0 ||
                    facings.count { it == LENS_FACING_BACK } == 0
            )

            val singleCameraOnFlagship = count == 1 && multiCameraFlagship

            // `levels` empty list collapses to true vacuously under `all { }`
            // (Kotlin stdlib behavior: emptyList.all{} returns true). Guard
            // with explicit isNotEmpty() so the rule only fires on
            // observed levels. Reusable defensive pattern — applies any
            // time a probe predicates on "every element satisfies X" on
            // a list whose emptiness is possible from a degraded supplier.
            val allLegacyOnFlagship = levels != null && levels.isNotEmpty() &&
                levels.all { it == HARDWARE_LEVEL_LEGACY } &&
                multiCameraFlagship

            // Width-based implausible-sensor rule per team-lead watch #3:
            // reads SizeF.getWidth() (NOT diagonal). Strict-inequality
            // boundaries `< 2mm OR > 15mm` catch stub-zero (0.0 < 2.0
            // fires lower bound), sub-physical stub values, and
            // implausibly-large stubs in one rule.
            val implausibleSensorWidth = minSensorWidthMm != null && (
                minSensorWidthMm < MIN_PLAUSIBLE_SENSOR_WIDTH_MM ||
                    minSensorWidthMm > MAX_PLAUSIBLE_SENSOR_WIDTH_MM
            )

            val (pattern, score) = when {
                vendorHasEmuKeyword ->
                    PATTERN_VENDOR_EMULATOR_KEYWORD to SCORE_VENDOR_EMULATOR_KEYWORD
                noCamerasOnPhone ->
                    PATTERN_NO_CAMERAS_ON_PHONE to SCORE_NO_CAMERAS_ON_PHONE
                missingDirectionOnPhone ->
                    PATTERN_MISSING_DIRECTION_ON_PHONE to SCORE_MISSING_DIRECTION_ON_PHONE
                singleCameraOnFlagship ->
                    PATTERN_SINGLE_CAMERA_ON_FLAGSHIP to SCORE_SINGLE_CAMERA_ON_FLAGSHIP
                allLegacyOnFlagship ->
                    PATTERN_ALL_LEGACY_ON_FLAGSHIP to SCORE_ALL_LEGACY_ON_FLAGSHIP
                implausibleSensorWidth ->
                    PATTERN_IMPLAUSIBLE_SENSOR_WIDTH to SCORE_IMPLAUSIBLE_SENSOR_WIDTH
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val anyObserved = count != null || facings != null || levels != null ||
                minSensorWidthMm != null || vendorString != null
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
                    key = "camera.sensor_width_min_mm",
                    value = minSensorWidthMm?.toString() ?: "<unavailable>",
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

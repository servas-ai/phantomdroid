package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.ui.DisplayCutoutProbe
import com.detectorlab.probes.ui.RefreshRateProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for CameraInfoProbe (env.camera_info, inventory rank 53).
 */
class CameraInfoProbeTest {

    private fun makeProbe(
        cameraCount: Int? = 4,
        facings: List<Int>? = listOf(
            CameraInfoProbe.LENS_FACING_BACK,
            CameraInfoProbe.LENS_FACING_BACK,
            CameraInfoProbe.LENS_FACING_BACK,
            CameraInfoProbe.LENS_FACING_FRONT,
        ),
        levels: List<Int>? = listOf(
            CameraInfoProbe.HARDWARE_LEVEL_3,
            CameraInfoProbe.HARDWARE_LEVEL_3,
            CameraInfoProbe.HARDWARE_LEVEL_3,
            CameraInfoProbe.HARDWARE_LEVEL_3,
        ),
        minSensorWidthMm: Float? = 7.06f,
        vendorString: String? = "Google Pixel Camera HAL v2",
    ): CameraInfoProbe = CameraInfoProbe(
        cameraCountSupplier = { cameraCount },
        cameraFacingsSupplier = { facings },
        cameraHardwareLevelsSupplier = { levels },
        cameraMinSensorWidthMmSupplier = { minSensorWidthMm },
        cameraVendorStringSupplier = { vendorString },
    )

    private fun fakeCtx(
        model: String? = "Pixel 7",
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return if (key == CameraInfoProbe.PROP_RO_PRODUCT_MODEL) model else null
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `Pixel 7 with 4 cameras at LEVEL_3 + 7mm sensor — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Galaxy S24 with 3 rear + 1 front + FULL — score is 0`() = runBlocking {
        val result = makeProbe(
            cameraCount = 4,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
            ),
            minSensorWidthMm = 6.4f,
            vendorString = "Samsung Galaxy Camera HAL",
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `budget phone with 2 cams LIMITED + 5mm sensor — score is 0`() = runBlocking {
        // Redmi Note 12 is NOT in KNOWN_MULTI_CAMERA_FLAGSHIPS so single/
        // all-LEGACY rules don't fire; LIMITED is acceptable; sensor 5mm
        // is plausible. Clean.
        val result = makeProbe(
            cameraCount = 2,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LIMITED,
                CameraInfoProbe.HARDWARE_LEVEL_LIMITED,
            ),
            minSensorWidthMm = 5.0f,
            vendorString = "Xiaomi Camera HAL",
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    // ── Vendor emulator keyword (1.0 dispositive) ────────────────────────────

    @Test
    fun `goldfish in vendor string — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorString = "AOSP goldfish Camera HAL v1")
            .run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu in vendor string — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorString = "Android Emulator (ranchu)")
            .run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `redroid in vendor string — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorString = "Redroid Camera HAL")
            .run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vbox86 in vendor string — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorString = "Genymotion vbox86 Camera")
            .run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vendor keyword case-insensitive — score is 1_0`() = runBlocking {
        val result = makeProbe(vendorString = "GOLDFISH Stub HAL").run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `vendor keyword — pattern is vendor_emulator_keyword`() = runBlocking {
        val result = makeProbe(vendorString = "goldfish HAL").run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_VENDOR_EMULATOR_KEYWORD, ev?.value)
    }

    // ── Zero cameras on phone-class (0.9) ────────────────────────────────────

    @Test
    fun `zero cameras on Pixel 7 — score is 0_9`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `zero cameras on Galaxy S24 — score is 0_9`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `zero cameras on Redmi Note 12 (budget phone-class) — score is 0_9`() = runBlocking {
        // Phone-class is broader than flagship-class — Redmi Note is in
        // NetworkTypeProbe.PHONE_CLASS_MODEL_SUBSTRINGS but NOT in
        // KNOWN_MULTI_CAMERA_FLAGSHIPS. Zero cams on a phone-class
        // device is still strong (0.9).
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `zero cameras on Lenovo tablet — score is 0 (not phone-class)`() = runBlocking {
        // Tablets are NOT phone-class; zero cameras is legitimate on
        // some Wi-Fi-only tablet variants. Rule does not fire.
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `zero cameras on null model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `zero cameras — pattern is no_cameras_on_phone`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_NO_CAMERAS_ON_PHONE, ev?.value)
    }

    // ── Missing direction on phone-class (0.85) ──────────────────────────────

    @Test
    fun `phone with only back cameras (no front) — score is 0_85`() = runBlocking {
        // 3 back cameras, 0 front. Phone-class. Real phones always have
        // both — this is a structural impossibility the rule catches.
        val result = makeProbe(
            cameraCount = 3,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `phone with only front cameras (no back) — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 2,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_FRONT,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
            ),
            minSensorWidthMm = 4.0f,
            vendorString = "Samsung HAL",
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `phone-class with only EXTERNAL facing — score is 0_85`() = runBlocking {
        // EXTERNAL is neither FRONT nor BACK; both direction counts are
        // 0, so the rule fires.
        val result = makeProbe(
            cameraCount = 1,
            facings = listOf(CameraInfoProbe.LENS_FACING_EXTERNAL),
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_EXTERNAL),
            minSensorWidthMm = 5.0f,
            vendorString = "Generic HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `tablet with only back facings — score is 0 (not phone-class)`() = runBlocking {
        // Lenovo Tab P11 base legitimately ships without back camera in
        // some SKUs. Tablets are excluded from the phone-class gate.
        val result = makeProbe(
            cameraCount = 1,
            facings = listOf(CameraInfoProbe.LENS_FACING_BACK),
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_LIMITED),
            minSensorWidthMm = 5.0f,
            vendorString = "Lenovo HAL",
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `phone with both front and back — score is 0`() = runBlocking {
        val result = makeProbe(
            cameraCount = 2,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LIMITED,
                CameraInfoProbe.HARDWARE_LEVEL_LIMITED,
            ),
            minSensorWidthMm = 5.0f,
            vendorString = "MediaTek HAL",
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `phone with empty facings list — score is 0 (isNotEmpty guard, not vacuous)`() = runBlocking {
        // Empty facings list does NOT vacuously fire the missing-
        // direction rule. Same defensive-emptiness-guard pattern as
        // the all_legacy_on_flagship rule. count==0 case fires the
        // (more specific) no_cameras_on_phone rule instead, but THIS
        // test isolates count >= 1 with empty facings (anomalous
        // supplier state — possible if count enumerator returned but
        // facing iteration crashed silently).
        val result = makeProbe(
            cameraCount = 4,
            facings = emptyList(),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null facings — missing_direction rule does not fire`() = runBlocking {
        // Predicate gates on `facings != null`. Null means "didn't
        // observe", not "observed empty". No rule fires.
        val result = makeProbe(
            cameraCount = 4,
            facings = null,
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `missing direction — pattern is missing_direction_on_phone`() = runBlocking {
        val result = makeProbe(
            cameraCount = 3,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_3,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_MISSING_DIRECTION_ON_PHONE, ev?.value)
    }

    // ── Single camera on multi-camera flagship (0.85) ────────────────────────
    //
    // Note: these tests use `facings = null` to isolate the
    // single_camera_on_flagship rule from the (cascade-earlier)
    // missing_direction_on_phone rule. A count==1 with both facings
    // missing would also fire missing_direction at 0.85; testing the
    // single_camera rule independently requires the facings predicate
    // to not fire (predicate gates on `facings != null`).

    @Test
    fun `single camera on Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_3),
            minSensorWidthMm = 7.0f,
            vendorString = "Google Camera HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single camera on Galaxy S22 (SM-S901B) — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_FULL),
            minSensorWidthMm = 6.4f,
            vendorString = "Samsung Camera HAL",
        ).run(fakeCtx(model = "SM-S901B"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single camera on OnePlus 11 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_FULL),
            minSensorWidthMm = 7.5f,
            vendorString = "OnePlus Camera HAL",
        ).run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single camera on Redmi Note 12 (not flagship, facings null) — score is 0`() = runBlocking {
        // Redmi Note 12 is NOT in KNOWN_MULTI_CAMERA_FLAGSHIPS, so the
        // single_camera rule does not fire. With facings=null the
        // missing_direction rule's predicate is also not satisfied
        // (gated on `facings != null`). Clean.
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_LIMITED),
            minSensorWidthMm = 5.0f,
            vendorString = "Xiaomi HAL",
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `single camera on Pixel 5 (NOT in multi-camera list) — score is 0`() = runBlocking {
        // Pixel 5 has a single rear lens per Google spec; single-camera
        // is legitimate. Pixel 5 is INTENTIONALLY NOT in
        // KNOWN_MULTI_CAMERA_FLAGSHIPS (rank-53 cutoff is Pixel 6+,
        // distinct from rank-52 KNOWN_CUTOUT_MODELS which starts at
        // Pixel 5 — different semantic). Facings null isolates from
        // missing_direction.
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_FULL),
            minSensorWidthMm = 7.0f,
            vendorString = "Google Camera HAL",
        ).run(fakeCtx(model = "Pixel 5"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `single camera — pattern is single_camera_on_flagship`() = runBlocking {
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_3),
            minSensorWidthMm = 7.0f,
            vendorString = "Google Camera HAL",
        ).run(fakeCtx(model = "Pixel 8"))
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_SINGLE_CAMERA_ON_FLAGSHIP, ev?.value)
    }

    // ── All LEGACY on flagship (0.85) ────────────────────────────────────────

    @Test
    fun `all LEGACY on Pixel 7 with 4 cameras — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 4,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google Camera HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `all LEGACY on Galaxy S24 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            cameraCount = 3,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
            ),
            minSensorWidthMm = 6.4f,
            vendorString = "Samsung Camera HAL",
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `mixed LEGACY and FULL on flagship — score is 0 (only ALL-legacy fires)`() = runBlocking {
        val result = makeProbe(
            cameraCount = 4,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all LEGACY on budget phone — score is 0 (not flagship)`() = runBlocking {
        // Real budget phones LEGITIMATELY ship with all-LEGACY camera
        // HAL — rule must NOT fire for these.
        val result = makeProbe(
            cameraCount = 2,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
            ),
            minSensorWidthMm = 4.5f,
            vendorString = "MediaTek HAL",
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty levels list on flagship — score is 0 (no vacuous all-LEGACY fire)`() = runBlocking {
        // Defense against vacuous `emptyList.all { }` returning true.
        // Empty levels on a flagship should not fire the all-LEGACY rule
        // — count rule would handle the zero-cameras case independently.
        val result = makeProbe(
            cameraCount = 4,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = emptyList(),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all LEGACY — pattern is all_legacy_on_flagship`() = runBlocking {
        val result = makeProbe(
            cameraCount = 4,
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
                CameraInfoProbe.HARDWARE_LEVEL_LEGACY,
            ),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_ALL_LEGACY_ON_FLAGSHIP, ev?.value)
    }

    // ── Implausible sensor width (0.85) ──────────────────────────────────────

    @Test
    fun `sensor width 0_0 mm — score is 0_85 (stub-zero under lower bound)`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 0.0f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sensor width 0_5mm — score is 0_85 (sub-2mm stub catches the gap)`() = runBlocking {
        // This is the FP class that diagonal-with-only-zero-bound missed.
        // Width-based <2mm catches sub-physical stub values that aren't
        // exactly 0.0 but are still implausibly small.
        val result = makeProbe(minSensorWidthMm = 0.5f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sensor width 1_99mm — score is 0_85 (just under lower bound)`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 1.99f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sensor width 2_0mm boundary — score is 0 (lower bound strict)`() = runBlocking {
        // MIN_PLAUSIBLE_SENSOR_WIDTH_MM check is `<` strict; 2.0 exactly passes.
        val result = makeProbe(minSensorWidthMm = 2.0f).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sensor width 15_0mm boundary — score is 0 (upper bound strict)`() = runBlocking {
        // MAX_PLAUSIBLE_SENSOR_WIDTH_MM check is `>` strict; 15.0 exactly passes.
        val result = makeProbe(minSensorWidthMm = 15.0f).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sensor width 15_01mm — score is 0_85 (just above upper bound)`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 15.01f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sensor width 16mm — score is 0_85 (medium-format territory)`() = runBlocking {
        // Original test (diagonal-based) had 16mm pass as "real large
        // sensor". Under width-based rule, 16mm width exceeds even the
        // largest 1-inch type-1 (~13mm width) — no Android phone ships
        // a >15mm-width sensor as of 2026.
        val result = makeProbe(minSensorWidthMm = 16.0f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `sensor width 13mm (Sony 1-inch real width) — score is 0`() = runBlocking {
        // Sony Xperia Pro-I, Xiaomi 13 Ultra 1-inch sensors: ~13mm width.
        // Real, in-range, no rule fires.
        val result = makeProbe(minSensorWidthMm = 13.0f).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sensor width 1000mm — score is 0_85 (absurd stub)`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 1000.0f).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `implausible sensor width — pattern is implausible_sensor_width`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 0.0f).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_IMPLAUSIBLE_SENSOR_WIDTH, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `vendor keyword wins over zero cameras (1_0 over 0_9)`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = null,
            vendorString = "goldfish HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_VENDOR_EMULATOR_KEYWORD, ev?.value)
    }

    @Test
    fun `zero cameras wins over implausible sensor (0_9 over 0_85)`() = runBlocking {
        val result = makeProbe(
            cameraCount = 0,
            facings = emptyList(),
            levels = emptyList(),
            minSensorWidthMm = 0.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `single camera wins over implausible sensor (cascade source order)`() = runBlocking {
        // facings null isolates the single_camera rule from
        // missing_direction. With facings observed, missing_direction
        // would fire FIRST (cascade-earlier rule) — see separate test
        // `missing direction fires before single camera`.
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_3),
            minSensorWidthMm = 200.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_SINGLE_CAMERA_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `single camera fires before all-LEGACY when both predicates true`() = runBlocking {
        // Both single_camera and all_legacy require flagship; both
        // could predicate true (single camera AND its only level is
        // LEGACY). Source-order cascade — single_camera fires first.
        // facings=null isolates from missing_direction (cascade-earlier).
        val result = makeProbe(
            cameraCount = 1,
            facings = null,
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_LEGACY),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_SINGLE_CAMERA_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `missing direction fires before single camera (cascade order)`() = runBlocking {
        // count=1 + facings=[BACK] on a multi-camera flagship: both
        // missing_direction_on_phone AND single_camera_on_flagship
        // predicate true. missing_direction is earlier in source order
        // and wins.
        val result = makeProbe(
            cameraCount = 1,
            facings = listOf(CameraInfoProbe.LENS_FACING_BACK),
            levels = listOf(CameraInfoProbe.HARDWARE_LEVEL_3),
            minSensorWidthMm = 7.0f,
            vendorString = "Google HAL",
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "camera.pattern" }
        assertEquals(CameraInfoProbe.PATTERN_MISSING_DIRECTION_ON_PHONE, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `all five suppliers return — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only count returns — confidence 0_95`() = runBlocking {
        val result = makeProbe(
            cameraCount = 4,
            facings = null,
            levels = null,
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only vendorString returns — confidence 0_95`() = runBlocking {
        val result = makeProbe(
            cameraCount = null,
            facings = null,
            levels = null,
            minSensorWidthMm = null,
            vendorString = "Google HAL",
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `all five suppliers null — confidence 0_50`() = runBlocking {
        val result = makeProbe(
            cameraCount = null,
            facings = null,
            levels = null,
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `all five suppliers null — score is 0 (no rule fires)`() = runBlocking {
        val result = makeProbe(
            cameraCount = null,
            facings = null,
            levels = null,
            minSensorWidthMm = null,
            vendorString = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_50`() = runBlocking {
        val result = CameraInfoProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `cameraCountSupplier throws — does not crash`() = runBlocking {
        val probe = CameraInfoProbe(
            cameraCountSupplier = { throw RuntimeException("simulated") },
            cameraFacingsSupplier = { listOf(CameraInfoProbe.LENS_FACING_BACK) },
            cameraHardwareLevelsSupplier = { listOf(CameraInfoProbe.HARDWARE_LEVEL_3) },
            cameraMinSensorWidthMmSupplier = { 7.0f },
            cameraVendorStringSupplier = { "Google HAL" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
    }

    @Test
    fun `cameraFacingsSupplier throws — does not crash`() = runBlocking {
        val probe = CameraInfoProbe(
            cameraCountSupplier = { 4 },
            cameraFacingsSupplier = { throw RuntimeException("simulated") },
            cameraHardwareLevelsSupplier = { listOf(CameraInfoProbe.HARDWARE_LEVEL_3) },
            cameraMinSensorWidthMmSupplier = { 7.0f },
            cameraVendorStringSupplier = { "Google HAL" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
    }

    @Test
    fun `all five suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = CameraInfoProbe(
            cameraCountSupplier = { throw RuntimeException("a") },
            cameraFacingsSupplier = { throw RuntimeException("b") },
            cameraHardwareLevelsSupplier = { throw RuntimeException("c") },
            cameraMinSensorWidthMmSupplier = { throw RuntimeException("d") },
            cameraVendorStringSupplier = { throw RuntimeException("e") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = makeProbe().run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isMultiCameraFlagshipModel detects Pixel 6 onward`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 6"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 6 Pro"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 7"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 8"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel Fold"))
    }

    @Test
    fun `isMultiCameraFlagshipModel detects Galaxy S22+`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-S901B"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-S908"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-S921"))
    }

    @Test
    fun `isMultiCameraFlagshipModel detects Galaxy Note 20`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-N981B"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-N986"))
    }

    @Test
    fun `isMultiCameraFlagshipModel detects Galaxy Z Fold 4 5`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-F936B"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("SM-F946B"))
    }

    @Test
    fun `isMultiCameraFlagshipModel detects OnePlus 9+`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("OnePlus 9"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("OnePlus 11"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("OnePlus 12"))
    }

    @Test
    fun `isMultiCameraFlagshipModel detects Xiaomi 13+`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Xiaomi 13"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Mi 13"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("Xiaomi 14"))
    }

    @Test
    fun `isMultiCameraFlagshipModel case-insensitive`() {
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("PIXEL 7"))
        assertTrue(CameraInfoProbe.isMultiCameraFlagshipModel("pixel 7"))
    }

    @Test
    fun `isMultiCameraFlagshipModel false for Pixel 5 and earlier`() {
        // Pixel 5 has single rear lens (12.2MP wide only). Cutoff at
        // Pixel 6 — single-camera Pixel 5 is legitimate.
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 5"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 5a"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 4a"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Pixel 3"))
    }

    @Test
    fun `isMultiCameraFlagshipModel false for budget phones`() {
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Redmi Note 12"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("SM-A525F"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("OnePlus Nord"))
    }

    @Test
    fun `isMultiCameraFlagshipModel false for tablets`() {
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Lenovo Tab P11"))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel("Galaxy Tab S8"))
    }

    @Test
    fun `isMultiCameraFlagshipModel false for empty and null`() {
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel(null))
        assertFalse(CameraInfoProbe.isMultiCameraFlagshipModel(""))
    }

    @Test
    fun `facingName covers all known constants`() {
        assertEquals("FRONT", CameraInfoProbe.facingName(0))
        assertEquals("BACK", CameraInfoProbe.facingName(1))
        assertEquals("EXTERNAL", CameraInfoProbe.facingName(2))
        assertEquals("OTHER(99)", CameraInfoProbe.facingName(99))
    }

    @Test
    fun `hardwareLevelName covers all known constants`() {
        assertEquals("LEGACY", CameraInfoProbe.hardwareLevelName(0))
        assertEquals("LIMITED", CameraInfoProbe.hardwareLevelName(1))
        assertEquals("FULL", CameraInfoProbe.hardwareLevelName(2))
        assertEquals("LEVEL_3", CameraInfoProbe.hardwareLevelName(3))
        assertEquals("EXTERNAL", CameraInfoProbe.hardwareLevelName(4))
        assertEquals("OTHER(99)", CameraInfoProbe.hardwareLevelName(99))
    }

    // ── Drift-alarm invariants ───────────────────────────────────────────────

    @Test
    fun `KNOWN_MULTI_CAMERA_FLAGSHIPS list pinned (drift alarm)`() {
        // Future maintainer changing this list must update this test
        // deliberately. Cutoff at Pixel 6+ — Pixel 5 has single rear lens
        // so is intentionally NOT in the list.
        val list = CameraInfoProbe.KNOWN_MULTI_CAMERA_FLAGSHIPS
        // IN: cutoff at Pixel 6
        assertTrue("pixel 6" in list)
        assertTrue("pixel 7" in list)
        assertTrue("pixel 8" in list)
        assertTrue("sm-s901" in list)
        assertTrue("sm-n981" in list)
        assertTrue("sm-f936" in list)
        assertTrue("oneplus 11" in list)
        assertTrue("xiaomi 14" in list)
        // OUT: pre-multi-camera Pixels NOT in list
        assertFalse("pixel 5" in list)
        assertFalse("pixel 4a" in list)
        assertFalse("pixel 3" in list)
    }

    // ── Cross-rank distinct-semantic verification ────────────────────────────

    @Test
    fun `KNOWN_MULTI_CAMERA_FLAGSHIPS distinct from rank-46 KNOWN_120HZ_MODELS`() {
        // Both target flagship Pixels. Cutoff distinction:
        //   rank-46 (120Hz)   → Pixel 7+
        //   rank-53 (multicam) → Pixel 6+
        // Pixel 6 IS in multicam but NOT in 120Hz (Pixel 6 is 90Hz).
        assertTrue("pixel 6" in CameraInfoProbe.KNOWN_MULTI_CAMERA_FLAGSHIPS)
        assertFalse("pixel 6" in RefreshRateProbe.KNOWN_120HZ_MODELS)
    }

    @Test
    fun `KNOWN_MULTI_CAMERA_FLAGSHIPS distinct from rank-52 KNOWN_CUTOUT_MODELS`() {
        // Both target flagship Pixels. Cutoff distinction:
        //   rank-52 (cutout)   → Pixel 5+ (hole-punch generation)
        //   rank-53 (multicam) → Pixel 6+ (multi-lens-rear generation)
        // Pixel 5 IS in cutout (top-left hole-punch) but NOT in multicam
        // (single rear lens). Locks the same-surface-different-semantic
        // cross-rank pattern across three lists.
        assertTrue("pixel 5" in DisplayCutoutProbe.KNOWN_CUTOUT_MODELS)
        assertFalse("pixel 5" in CameraInfoProbe.KNOWN_MULTI_CAMERA_FLAGSHIPS)
    }

    @Test
    fun `isPhoneClassModel delegates to rank-25 NetworkTypeProbe`() {
        // Drift-safe reuse anchor — if NetworkTypeProbe.isPhoneClassModel
        // ever stops accepting Pixel/Galaxy/OnePlus/Redmi or starts
        // accepting tablets, this probe's zero-cameras rule will be
        // affected. Same pattern as rank-49/47/46 delegate-anchor tests.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("OnePlus 11"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Redmi Note 12"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
    }

    @Test
    fun `hasEmulatorMarker delegates to rank-28 BoardHardwareProbe`() {
        // Drift-safe reuse anchor — vendor-keyword scan is the SAME
        // marker-substring list as rank-28 board-props scan. If rank-28
        // adds a new marker (e.g. a new emulator), the camera-vendor
        // rule picks it up automatically.
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("goldfish HAL"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("ranchu camera"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("redroid"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("vbox86 stub"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("cancro test"))
        assertFalse(BoardHardwareProbe.hasEmulatorMarker("Google Pixel Camera HAL"))
        assertFalse(BoardHardwareProbe.hasEmulatorMarker(null))
        assertFalse(BoardHardwareProbe.hasEmulatorMarker(""))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "camera.count",
                "camera.facing_back_count",
                "camera.facing_front_count",
                "camera.hardware_levels",
                "camera.sensor_width_min_mm",
                "camera.has_emulator_keyword",
                "camera.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `count evidence reflects supplier`() = runBlocking {
        val result = makeProbe(cameraCount = 4).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.count" }
        assertEquals("4", ev?.value)
    }

    @Test
    fun `count evidence unavailable when null`() = runBlocking {
        val result = makeProbe(cameraCount = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.count" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `facing_back_count evidence reflects supplier`() = runBlocking {
        val result = makeProbe(
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.facing_back_count" }
        assertEquals("3", ev?.value)
    }

    @Test
    fun `facing_front_count evidence reflects supplier`() = runBlocking {
        val result = makeProbe(
            facings = listOf(
                CameraInfoProbe.LENS_FACING_BACK,
                CameraInfoProbe.LENS_FACING_FRONT,
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.facing_front_count" }
        assertEquals("1", ev?.value)
    }

    @Test
    fun `facing counts unavailable when facings null`() = runBlocking {
        val result = makeProbe(facings = null).run(fakeCtx())
        val backEv = result.evidence.find { it.key == "camera.facing_back_count" }
        val frontEv = result.evidence.find { it.key == "camera.facing_front_count" }
        assertEquals("<unavailable>", backEv?.value)
        assertEquals("<unavailable>", frontEv?.value)
    }

    @Test
    fun `hardware_levels evidence formats names`() = runBlocking {
        val result = makeProbe(
            levels = listOf(
                CameraInfoProbe.HARDWARE_LEVEL_3,
                CameraInfoProbe.HARDWARE_LEVEL_FULL,
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.hardware_levels" }
        assertEquals("LEVEL_3,FULL", ev?.value)
    }

    @Test
    fun `hardware_levels evidence empty when list empty`() = runBlocking {
        val result = makeProbe(levels = emptyList()).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.hardware_levels" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `hardware_levels evidence unavailable when null`() = runBlocking {
        val result = makeProbe(levels = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.hardware_levels" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `sensor_width_min_mm evidence reflects supplier`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = 7.06f).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.sensor_width_min_mm" }
        assertEquals("7.06", ev?.value)
    }

    @Test
    fun `sensor_width_min_mm evidence unavailable when null`() = runBlocking {
        val result = makeProbe(minSensorWidthMm = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.sensor_width_min_mm" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `has_emulator_keyword evidence true for goldfish`() = runBlocking {
        val result = makeProbe(vendorString = "goldfish HAL").run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.has_emulator_keyword" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_emulator_keyword evidence false for real vendor`() = runBlocking {
        val result = makeProbe(vendorString = "Google Pixel Camera HAL").run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.has_emulator_keyword" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `has_emulator_keyword evidence unavailable when vendorString null`() = runBlocking {
        val result = makeProbe(vendorString = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "camera.has_emulator_keyword" }
        assertEquals("<unavailable>", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Enumerate CameraManager.getCameraIdList() + characteristics " +
                "per camera; detect zero/single-camera on phone-class, " +
                "LEGACY-level on flagship, implausible sensor sizes, and " +
                "emulator vendor keywords",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_camera_info`() {
        assertEquals("env.camera_info", makeProbe().id)
    }

    @Test
    fun `probe rank is 53`() {
        assertEquals(53, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // inventory.yml rank-53 severity = trace.
        assertEquals(ProbeSeverity.TRACE, makeProbe().severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        assertEquals(AndroidLayer.HARDWARE, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 300ms`() {
        assertEquals(300L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

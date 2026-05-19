package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.sensors.ProximityProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ModelBrandManufacturerProbe
 * (buildprop.model_brand_manufacturer, inventory rank 9).
 */
class ModelBrandManufacturerProbeTest {

    private val probe = ModelBrandManufacturerProbe()

    private fun fakeCtx(
        brand: String? = "google",
        model: String? = "Pixel 7",
        manufacturer: String? = "Google",
        propertyThrowsOn: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (key in propertyThrowsOn) throw RuntimeException("simulated EACCES on $key")
            return when (key) {
                ModelBrandManufacturerProbe.PROP_RO_PRODUCT_BRAND -> brand
                ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MODEL -> model
                ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MANUFACTURER -> manufacturer
                else -> null
            }
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
    fun `Pixel 7 google_Google clean — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Samsung Galaxy S24 — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "samsung", model = "SM-S921B", manufacturer = "samsung")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `OnePlus 11 — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "OnePlus", model = "OnePlus 11", manufacturer = "OnePlus")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Redmi Note 12 (sub-brand of Xiaomi) — score is 0 via alias`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "Redmi", model = "Redmi Note 12", manufacturer = "Xiaomi")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `POCO (sub-brand of Xiaomi) — score is 0 via alias`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "POCO", model = "POCO X6 Pro", manufacturer = "Xiaomi")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `legacy Honor pre-2020 (brand=Honor, manufacturer=HUAWEI) — score is 0 via alias`() =
        runBlocking {
            // Pre-2020 Honor devices reported brand=Honor + manufacturer=HUAWEI.
            // Alias added per reviewer's rank-9-approval suggestion #2 to
            // prevent legacy-Honor false-positives at 0.85 mismatch.
            val result = probe.run(
                fakeCtx(brand = "Honor", model = "ANE-LX1", manufacturer = "HUAWEI")
            )
            assertEquals(0.0, result.score)
        }

    @Test
    fun `case difference Google_google — score is 0 via lowercase equality`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "Google", manufacturer = "google"))
        assertEquals(0.0, result.score)
    }

    // ── Model emulator keyword (1.0) ─────────────────────────────────────────

    @Test
    fun `sdk_gphone64 in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "google", model = "sdk_gphone64_x86_64", manufacturer = "Google")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Android SDK built for in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "Android", model = "Android SDK built for x86", manufacturer = "Android")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Emulator in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "Android", model = "Android Emulator", manufacturer = "Android")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Genymobile in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "Genymotion", model = "Genymobile Pixel 7", manufacturer = "Genymotion")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `goldfish in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "AOSP", model = "goldfish device", manufacturer = "AOSP")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ranchu in model — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "AOSP", model = "ranchu emulator", manufacturer = "AOSP")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `model keyword case-insensitive — SDK_GPHONE fires`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "google", model = "SDK_GPHONE64_X86_64", manufacturer = "Google")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `model keyword — pattern is model_emulator_keyword`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "google", model = "sdk_gphone64_x86_64", manufacturer = "Google")
        )
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_MODEL_EMULATOR_KEYWORD, ev?.value)
    }

    @Test
    fun `model with no emu keyword but matching brand — score is 0`() = runBlocking {
        // Real Pixel 7 model string is "Pixel 7" — no overlap with any
        // emulator keyword. Sanity check that the keyword scan doesn't
        // false-positive on legitimate values.
        val result = probe.run(fakeCtx())
        assertEquals(0.0, result.score)
        val emuEv = result.evidence.find { it.key == "buildprop.has_emulator_model_keyword" }
        assertEquals("false", emuEv?.value)
    }

    // ── Unknown sentinel (1.0) ───────────────────────────────────────────────

    @Test
    fun `brand equals unknown — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "unknown"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `model equals unknown — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(model = "unknown"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `manufacturer equals unknown — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(manufacturer = "unknown"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `unknown is case-insensitive — UNKNOWN fires`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "UNKNOWN"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `unknown sentinel — pattern is unknown_sentinel`() = runBlocking {
        val result = probe.run(fakeCtx(manufacturer = "unknown"))
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_UNKNOWN_SENTINEL, ev?.value)
    }

    // ── Empty property (0.9) ─────────────────────────────────────────────────

    @Test
    fun `brand empty — score is 0_9`() = runBlocking {
        val result = probe.run(fakeCtx(brand = ""))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `model empty — score is 0_9`() = runBlocking {
        val result = probe.run(fakeCtx(model = ""))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `manufacturer empty — score is 0_9`() = runBlocking {
        val result = probe.run(fakeCtx(manufacturer = ""))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `all three empty — score is 0_9`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "", model = "", manufacturer = ""))
        assertEquals(0.9, result.score)
    }

    @Test
    fun `empty property — pattern is empty_property`() = runBlocking {
        val result = probe.run(fakeCtx(brand = ""))
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_EMPTY_PROPERTY, ev?.value)
    }

    // ── Brand/manufacturer mismatch (0.85) ───────────────────────────────────

    @Test
    fun `brand foo manufacturer bar — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "foo", manufacturer = "bar"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `brand google manufacturer samsung — score is 0_85`() = runBlocking {
        // Anomalous: a Pixel device claiming Samsung as manufacturer.
        // No alias pair covers this — fire mismatch.
        val result = probe.run(fakeCtx(brand = "google", manufacturer = "samsung"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `mismatch — pattern is brand_manufacturer_mismatch`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "foo", manufacturer = "bar"))
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_BRAND_MANUFACTURER_MISMATCH, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `model keyword wins over unknown sentinel (1_0 over 1_0, source order)`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "unknown", model = "sdk_gphone64", manufacturer = "Google")
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_MODEL_EMULATOR_KEYWORD, ev?.value)
    }

    @Test
    fun `unknown wins over empty (1_0 over 0_9)`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "unknown", model = ""))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_UNKNOWN_SENTINEL, ev?.value)
    }

    @Test
    fun `empty wins over mismatch (0_9 over 0_85)`() = runBlocking {
        // brand="" + manufacturer="bar" — anyIsEmpty fires before
        // mismatch check has a chance.
        val result = probe.run(fakeCtx(brand = "", manufacturer = "bar"))
        assertEquals(0.9, result.score)
        val ev = result.evidence.find {
            it.key == "buildprop.model_brand_manufacturer_pattern"
        }
        assertEquals(ModelBrandManufacturerProbe.PATTERN_EMPTY_PROPERTY, ev?.value)
    }

    @Test
    fun `model keyword wins over mismatch`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "foo", model = "sdk_gphone64", manufacturer = "bar")
        )
        assertEquals(1.0, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `all three readable — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `two of three readable — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(manufacturer = null))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `one of three readable — confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(model = null, manufacturer = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `zero readable — confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(brand = null, model = null, manufacturer = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `zero readable — score is 0 (no rule fires)`() = runBlocking {
        val result = probe.run(fakeCtx(brand = null, model = null, manufacturer = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mismatch rule does not fire when only one side readable`() = runBlocking {
        // Mismatch predicate guards on `brand != null && manufacturer != null`.
        // Null brand + observed manufacturer → cannot claim mismatch.
        val result = probe.run(fakeCtx(brand = null, model = "Pixel 7", manufacturer = "Google"))
        assertEquals(0.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `brand read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(ModelBrandManufacturerProbe.PROP_RO_PRODUCT_BRAND))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `model read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MODEL))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `manufacturer read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MANUFACTURER))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `all three throw — does not crash, degraded confidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                propertyThrowsOn = setOf(
                    ModelBrandManufacturerProbe.PROP_RO_PRODUCT_BRAND,
                    ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MODEL,
                    ModelBrandManufacturerProbe.PROP_RO_PRODUCT_MANUFACTURER,
                )
            )
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasModelEmulatorKeyword detects all listed substrings`() {
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("sdk_gphone64_x86_64"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("Android SDK built for x86"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("Android Emulator"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("Genymobile Pixel"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("goldfish_arm64"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("ranchu device"))
    }

    @Test
    fun `hasModelEmulatorKeyword case-insensitive`() {
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("SDK_GPHONE64"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("EMULATOR"))
        assertTrue(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("Genymobile"))
    }

    @Test
    fun `hasModelEmulatorKeyword false for real Pixel 7 model`() {
        assertFalse(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("Pixel 7"))
    }

    @Test
    fun `hasModelEmulatorKeyword false for real Galaxy S24`() {
        assertFalse(ModelBrandManufacturerProbe.hasModelEmulatorKeyword("SM-S921B"))
    }

    @Test
    fun `hasModelEmulatorKeyword false for empty and null`() {
        assertFalse(ModelBrandManufacturerProbe.hasModelEmulatorKeyword(null))
        assertFalse(ModelBrandManufacturerProbe.hasModelEmulatorKeyword(""))
    }

    @Test
    fun `isBrandManufacturerAligned case-insensitive equality`() {
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("Google", "google"))
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("samsung", "Samsung"))
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("OnePlus", "OnePlus"))
    }

    @Test
    fun `isBrandManufacturerAligned via alias table`() {
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("Redmi", "Xiaomi"))
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("POCO", "Xiaomi"))
    }

    @Test
    fun `isBrandManufacturerAligned alias symmetric`() {
        // Lookup is symmetric: (Xiaomi, Redmi) also returns true even
        // though the table stores it as (Redmi, Xiaomi).
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("Xiaomi", "Redmi"))
        assertTrue(ModelBrandManufacturerProbe.isBrandManufacturerAligned("Xiaomi", "POCO"))
    }

    @Test
    fun `isBrandManufacturerAligned false for unknown pairing`() {
        assertFalse(ModelBrandManufacturerProbe.isBrandManufacturerAligned("foo", "bar"))
        assertFalse(ModelBrandManufacturerProbe.isBrandManufacturerAligned("google", "samsung"))
    }

    // ── Drift-alarm invariants ───────────────────────────────────────────────

    @Test
    fun `MODEL_EMULATOR_KEYWORDS list pinned`() {
        val list = ModelBrandManufacturerProbe.MODEL_EMULATOR_KEYWORDS
        assertTrue("sdk_gphone" in list)
        assertTrue("android sdk built for" in list)
        assertTrue("emulator" in list)
        assertTrue("genymobile" in list)
        assertTrue("goldfish" in list)
        assertTrue("ranchu" in list)
    }

    @Test
    fun `KNOWN_OEM_ALIAS_PAIRS pinned`() {
        val pairs = ModelBrandManufacturerProbe.KNOWN_OEM_ALIAS_PAIRS
        assertTrue(("google" to "google") in pairs)
        assertTrue(("samsung" to "samsung") in pairs)
        assertTrue(("redmi" to "xiaomi") in pairs)
        assertTrue(("poco" to "xiaomi") in pairs)
        assertTrue(("honor" to "honor") in pairs)
        assertTrue(("honor" to "huawei") in pairs)
    }

    // ── Cross-rank distinct-list invariants ──────────────────────────────────

    @Test
    fun `MODEL_EMULATOR_KEYWORDS distinct from rank-28 BoardHardwareProbe markers`() {
        // Different surfaces have different leak vocabularies. AVD
        // model = "sdk_gphone64"; AVD board = "ranchu". `sdk_gphone`
        // appears in this probe's list but NOT in rank-28's. `cancro`
        // (test-image board codename) appears in rank-28 but NOT here.
        val rank9 = ModelBrandManufacturerProbe.MODEL_EMULATOR_KEYWORDS
        val rank28 = BoardHardwareProbe.EMULATOR_BOARD_MARKERS

        assertTrue("sdk_gphone" in rank9)
        assertFalse("sdk_gphone" in rank28)
        assertTrue("cancro" in rank28)
        assertFalse("cancro" in rank9)
        // genymobile is model-specific (brand name in model string);
        // rank-42 has "genymotion" (vendor-name string) — different
        // tokens for the same emulator. Documenting the asymmetry.
        assertTrue("genymobile" in rank9)
        assertFalse("genymobile" in rank28)
    }

    @Test
    fun `MODEL_EMULATOR_KEYWORDS distinct from rank-42 ProximityProbe markers`() {
        val rank9 = ModelBrandManufacturerProbe.MODEL_EMULATOR_KEYWORDS
        val rank42 = ProximityProbe.EMU_NAME_SUBSTRINGS

        // sdk_gphone / genymobile / "android sdk built for" / emulator
        // are model-string-specific tokens — NOT in sensor-vendor-name list.
        assertFalse("sdk_gphone" in rank42)
        assertFalse("genymobile" in rank42)
        assertFalse("android sdk built for" in rank42)
        assertFalse("emulator" in rank42)
        // rank-42 has "avd" and "genymotion" — generic emu tokens
        // for sensor-vendor strings, NOT for model strings.
        assertFalse("avd" in rank9)
        assertFalse("genymotion" in rank9)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "ro.product.brand",
                "ro.product.model",
                "ro.product.manufacturer",
                "buildprop.brand_manufacturer_aligned",
                "buildprop.has_emulator_model_keyword",
                "buildprop.model_brand_manufacturer_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `brand evidence reflects supplier`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "samsung"))
        val ev = result.evidence.find { it.key == "ro.product.brand" }
        assertEquals("samsung", ev?.value)
    }

    @Test
    fun `brand evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(brand = null))
        val ev = result.evidence.find { it.key == "ro.product.brand" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `aligned evidence true on clean Pixel`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "buildprop.brand_manufacturer_aligned" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `aligned evidence false on mismatch`() = runBlocking {
        val result = probe.run(fakeCtx(brand = "foo", manufacturer = "bar"))
        val ev = result.evidence.find { it.key == "buildprop.brand_manufacturer_aligned" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `aligned evidence unknown when brand null`() = runBlocking {
        // Tri-state: when one side is null/empty, alignment is
        // unknown rather than false.
        val result = probe.run(fakeCtx(brand = null))
        val ev = result.evidence.find { it.key == "buildprop.brand_manufacturer_aligned" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `aligned evidence unknown when manufacturer empty`() = runBlocking {
        val result = probe.run(fakeCtx(manufacturer = ""))
        val ev = result.evidence.find { it.key == "buildprop.brand_manufacturer_aligned" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `aligned evidence true via Redmi-Xiaomi alias`() = runBlocking {
        val result = probe.run(
            fakeCtx(brand = "Redmi", model = "Redmi Note 12", manufacturer = "Xiaomi")
        )
        val ev = result.evidence.find { it.key == "buildprop.brand_manufacturer_aligned" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_emulator_model_keyword evidence false on real model`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "buildprop.has_emulator_model_keyword" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `has_emulator_model_keyword evidence true on sdk_gphone64`() = runBlocking {
        val result = probe.run(fakeCtx(model = "sdk_gphone64"))
        val ev = result.evidence.find { it.key == "buildprop.has_emulator_model_keyword" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_emulator_model_keyword evidence unknown when model null`() = runBlocking {
        val result = probe.run(fakeCtx(model = null))
        val ev = result.evidence.find { it.key == "buildprop.has_emulator_model_keyword" }
        assertEquals("unknown", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read ro.product.brand + model + manufacturer; detect emulator " +
                "keywords in model, 'unknown' sentinel values, empty fields, " +
                "and brand-vs-manufacturer mismatches modulo known-OEM aliases. " +
                "Focused-extraction probe separate from rank 1 BuildFingerprint.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is buildprop_model_brand_manufacturer`() {
        assertEquals("buildprop.model_brand_manufacturer", probe.id)
    }

    @Test
    fun `probe rank is 9`() {
        assertEquals(9, probe.rank)
    }

    @Test
    fun `probe category is BUILDPROP`() {
        assertEquals(ProbeCategory.BUILDPROP, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        // inventory.yml rank-9 severity = critical. Third CRITICAL
        // probe of session — joins rank-1, 3, 4, 7, 8, 10 in the
        // CRITICAL-severity set.
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

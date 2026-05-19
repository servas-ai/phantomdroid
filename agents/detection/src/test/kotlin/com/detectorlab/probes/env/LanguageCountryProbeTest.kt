package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for LanguageCountryProbe (env.language_country, inventory rank 36).
 */
class LanguageCountryProbeTest {

    private fun makeProbe(
        language: String? = "de",
        country: String? = "DE",
        displayName: String? = "Deutsch (Deutschland)",
    ): LanguageCountryProbe = LanguageCountryProbe(
        localeLanguageSupplier = { language },
        localeCountrySupplier = { country },
        localeDisplayNameSupplier = { displayName },
    )

    private fun fakeCtx(
        properties: Map<String, String?> = mapOf(
            LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "de-DE",
            LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "de",
            LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "DE",
            LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
        ),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = properties[key]
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
    fun `real Pixel de_DE — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real Pixel en_US — score is 0`() = runBlocking {
        val result = makeProbe(language = "en", country = "US").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `real Pixel zh_CN — score is 0`() = runBlocking {
        val result = makeProbe(language = "zh", country = "CN").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "zh-CN",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "zh",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "CN",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(LanguageCountryProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — is_valid_iso evidence is true`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.is_valid_iso" }
        assertEquals("true", ev?.value)
    }

    // ── Empty country + empty build-region (0.85) ────────────────────────────

    @Test
    fun `empty country and empty build region — score is 0_85`() = runBlocking {
        val result = makeProbe(language = "en", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty country both layers — pattern is empty_country_both_layers`() = runBlocking {
        val result = makeProbe(language = "en", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(
            LanguageCountryProbe.PATTERN_EMPTY_COUNTRY_BOTH_LAYERS, ev?.value,
        )
    }

    @Test
    fun `empty country but build region present — falls to build_vs_runtime_mismatch`() =
        runBlocking {
            // Build layer pins DE; runtime country empty → runtime locale
            // collapses to just "en" vs build "en_DE". empty_country_both_layers
            // and language_without_country both require buildRegionEmpty, so
            // they don't fire (region=DE). Falls through to
            // build_vs_runtime_mismatch (0.5).
            val result = makeProbe(language = "en", country = "").run(
                fakeCtx(
                    mapOf(
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-DE",
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "DE",
                        LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                    ),
                ),
            )
            assertEquals(0.5, result.score)
        }

    // ── Invalid ISO country (0.85) ───────────────────────────────────────────

    @Test
    fun `country XX — score is 0_85`() = runBlocking {
        val result = makeProbe(country = "XX").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `country ZZ — score is 0_85`() = runBlocking {
        val result = makeProbe(country = "ZZ").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `country 00 — score is 0_85`() = runBlocking {
        val result = makeProbe(country = "00").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `country lowercase de — score is 0_85 (not uppercase)`() = runBlocking {
        // ISO 3166-1 alpha-2 is uppercase. Lowercase fails validation.
        val result = makeProbe(country = "de").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `country single letter — score is 0_85`() = runBlocking {
        val result = makeProbe(country = "D").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `country mixed-case Xx — score is 0_85`() = runBlocking {
        val result = makeProbe(country = "Xx").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `invalid ISO country — pattern is invalid_iso_country`() = runBlocking {
        val result = makeProbe(country = "XX").run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(LanguageCountryProbe.PATTERN_INVALID_ISO_COUNTRY, ev?.value)
    }

    @Test
    fun `country DE — score is 0 (valid ISO)`() = runBlocking {
        val result = makeProbe(country = "DE").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `country US — score is 0 (valid ISO)`() = runBlocking {
        val result = makeProbe(language = "en", country = "US").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Empty language on phone-class (0.85) ─────────────────────────────────

    @Test
    fun `empty language on Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe(language = "", country = "DE").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty language on Pixel — pattern is empty_language_phone`() = runBlocking {
        val result = makeProbe(language = "", country = "DE").run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(LanguageCountryProbe.PATTERN_EMPTY_LANGUAGE_PHONE, ev?.value)
    }

    @Test
    fun `empty language on tablet — score is 0 (not phone-class)`() = runBlocking {
        // Tablets legitimately ship with stripped locale on some OEM builds.
        val result = makeProbe(language = "", country = "DE").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "de-DE",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "de",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "DE",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Lenovo Tab P11",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Language without country (0.70) ──────────────────────────────────────

    @Test
    fun `language en plus empty country plus empty region — score is 0_70`() = runBlocking {
        // Empty country + empty region (not BOTH layers — build_locale present).
        val result = makeProbe(language = "en", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        // empty_country_both_layers needs (countryEmpty && buildRegionEmpty &&
        // buildCombinedEmpty). buildCombinedEmpty needs buildLocale empty AND
        // region empty. Here buildLocale="en" so buildCombinedEmpty=false →
        // doesn't fire. Falls to language_without_country (0.70).
        assertEquals(0.70, result.score)
    }

    @Test
    fun `language without country — pattern is language_without_country`() = runBlocking {
        val result = makeProbe(language = "en", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(
            LanguageCountryProbe.PATTERN_LANGUAGE_WITHOUT_COUNTRY, ev?.value,
        )
    }

    // ── Build vs runtime mismatch (0.5) ──────────────────────────────────────

    @Test
    fun `runtime zh_CN vs build en_US — score is 0_5`() = runBlocking {
        // User changed locale after first boot. Legit but flagged at low conf.
        val result = makeProbe(language = "zh", country = "CN").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `build vs runtime mismatch — pattern is build_vs_runtime_mismatch`() = runBlocking {
        val result = makeProbe(language = "zh", country = "CN").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(
            LanguageCountryProbe.PATTERN_BUILD_VS_RUNTIME_MISMATCH, ev?.value,
        )
    }

    @Test
    fun `runtime de_DE vs build de_DE (underscore form) — score is 0`() = runBlocking {
        // normalizeBuildLocale handles both `-` and `_` separators.
        val result = makeProbe().run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "de_DE",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "de",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "DE",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `runtime de_DE vs build language+region (no combined property) — score is 0`() =
        runBlocking {
            // Some OEMs split language and region without setting ro.product.locale.
            val result = makeProbe().run(
                fakeCtx(
                    mapOf(
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "",
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "de",
                        LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "DE",
                        LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                    ),
                ),
            )
            assertEquals(0.0, result.score)
        }

    @Test
    fun `no build properties — runtime cannot be compared — score is 0`() = runBlocking {
        // Without build-layer signal, build-vs-runtime cannot fire. Falls clean.
        val result = makeProbe().run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.0, result.score)
        // Confidence drops to 0.60 (locale readable, no build property).
        assertEquals(0.60, result.confidence)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `XX country beats build-mismatch in cascade`() = runBlocking {
        // XX is invalid; build is en-US; runtime locale is en-XX. invalid_iso
        // fires (0.85) before build_vs_runtime would (0.5).
        val result = makeProbe(language = "en", country = "XX").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(LanguageCountryProbe.PATTERN_INVALID_ISO_COUNTRY, ev?.value)
    }

    @Test
    fun `empty country both layers beats invalid_iso_country in cascade`() = runBlocking {
        // Empty country can't be invalid_iso_country; both rules can't compete
        // here. Confirms empty-country fires when both layers absent.
        val result = makeProbe(language = "en", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty country both layers beats empty_language on phone in cascade`() = runBlocking {
        // Both rules trigger; empty_country_both_layers fires first per cascade.
        val result = makeProbe(language = "", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "locale.pattern" }
        assertEquals(
            LanguageCountryProbe.PATTERN_EMPTY_COUNTRY_BOTH_LAYERS, ev?.value,
        )
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `locale and build property readable — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `locale readable but no build property — confidence is 0_60`() = runBlocking {
        val result = makeProbe().run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to null,
                ),
            ),
        )
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `no locale and no build property — confidence is 0_50`() = runBlocking {
        val result = makeProbe(language = "", country = "").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to null,
                ),
            ),
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `null language null country — confidence 0_50`() = runBlocking {
        val result = makeProbe(language = null, country = null).run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to null,
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to null,
                ),
            ),
        )
        assertEquals(0.50, result.confidence)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — does not crash`() = runBlocking {
        // Real JVM Locale.getDefault() is host-dependent. Just check it runs.
        val result = LanguageCountryProbe().run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertFalse(result.failed)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `languageSupplier throws — does not crash`() = runBlocking {
        val probe = LanguageCountryProbe(
            localeLanguageSupplier = { throw RuntimeException("simulated") },
            localeCountrySupplier = { "DE" },
            localeDisplayNameSupplier = { "Deutsch" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Language null on phone-class → empty_language_phone fires.
        assertEquals(0.85, result.score)
    }

    @Test
    fun `countrySupplier throws — does not crash`() = runBlocking {
        val probe = LanguageCountryProbe(
            localeLanguageSupplier = { "de" },
            localeCountrySupplier = { throw RuntimeException("simulated") },
            localeDisplayNameSupplier = { "Deutsch" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Country null but build_locale (de-DE) + region (DE) present →
        // empty_country_both_layers doesn't fire (build_combined isn't empty);
        // falls to language_without_country (0.70).
        // Actually: build_locale="de-DE" so buildCombinedEmpty=false → empty
        // rule doesn't fire. invalidIsoCountry = !countryEmpty && !validIso;
        // country is null/empty so countryEmpty=true → invalidIso doesn't fire.
        // language not empty so empty_language_phone doesn't fire.
        // language not empty + countryEmpty + buildRegionEmpty? region="DE"
        // so buildRegionEmpty=false → language_without_country doesn't fire.
        // Falls to build_vs_runtime_mismatch: runtime "de" vs build "de_DE" →
        // mismatch fires at 0.5.
        assertEquals(0.5, result.score)
    }

    @Test
    fun `displayNameSupplier throws — does not crash`() = runBlocking {
        val probe = LanguageCountryProbe(
            localeLanguageSupplier = { "de" },
            localeCountrySupplier = { "DE" },
            localeDisplayNameSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "locale.display_name" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? =
                throw RuntimeException("simulated property failure")
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
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── normalizeBuildLocale helper ──────────────────────────────────────────

    @Test
    fun `normalizeBuildLocale handles dash separator`() {
        assertEquals("en_US", LanguageCountryProbe.normalizeBuildLocale("en-US"))
        assertEquals("zh_CN", LanguageCountryProbe.normalizeBuildLocale("zh-CN"))
    }

    @Test
    fun `normalizeBuildLocale handles underscore separator`() {
        assertEquals("en_US", LanguageCountryProbe.normalizeBuildLocale("en_US"))
    }

    @Test
    fun `normalizeBuildLocale normalizes case`() {
        assertEquals("en_US", LanguageCountryProbe.normalizeBuildLocale("EN-us"))
        assertEquals("de_DE", LanguageCountryProbe.normalizeBuildLocale("DE_de"))
    }

    @Test
    fun `normalizeBuildLocale handles language-only`() {
        assertEquals("en", LanguageCountryProbe.normalizeBuildLocale("en"))
    }

    @Test
    fun `normalizeBuildLocale returns null for empty`() {
        assertEquals(null, LanguageCountryProbe.normalizeBuildLocale(""))
        assertEquals(null, LanguageCountryProbe.normalizeBuildLocale(null))
        assertEquals(null, LanguageCountryProbe.normalizeBuildLocale("   "))
    }

    @Test
    fun `runtimeLocaleString joins language and country`() {
        assertEquals(
            "de_DE", LanguageCountryProbe.runtimeLocaleString("de", "DE"),
        )
    }

    @Test
    fun `runtimeLocaleString handles language-only`() {
        assertEquals("de", LanguageCountryProbe.runtimeLocaleString("de", ""))
        assertEquals("de", LanguageCountryProbe.runtimeLocaleString("de", null))
    }

    @Test
    fun `runtimeLocaleString returns null when language empty`() {
        assertEquals(null, LanguageCountryProbe.runtimeLocaleString("", "DE"))
        assertEquals(null, LanguageCountryProbe.runtimeLocaleString(null, "DE"))
    }

    // ── isValidIsoCountry helper ─────────────────────────────────────────────

    @Test
    fun `isValidIsoCountry accepts DE US JP`() {
        assertTrue(LanguageCountryProbe.isValidIsoCountry("DE"))
        assertTrue(LanguageCountryProbe.isValidIsoCountry("US"))
        assertTrue(LanguageCountryProbe.isValidIsoCountry("JP"))
    }

    @Test
    fun `isValidIsoCountry rejects stubs XX ZZ QQ AA`() {
        assertFalse(LanguageCountryProbe.isValidIsoCountry("XX"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("ZZ"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("QQ"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("AA"))
    }

    @Test
    fun `isValidIsoCountry rejects empty and null`() {
        assertFalse(LanguageCountryProbe.isValidIsoCountry(null))
        assertFalse(LanguageCountryProbe.isValidIsoCountry(""))
    }

    @Test
    fun `isValidIsoCountry rejects lowercase mixed-case wrong-length`() {
        assertFalse(LanguageCountryProbe.isValidIsoCountry("de"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("Xx"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("DEU"))
        assertFalse(LanguageCountryProbe.isValidIsoCountry("D"))
    }

    @Test
    fun `isValidIsoLanguage accepts en de zh`() {
        assertTrue(LanguageCountryProbe.isValidIsoLanguage("en"))
        assertTrue(LanguageCountryProbe.isValidIsoLanguage("de"))
        assertTrue(LanguageCountryProbe.isValidIsoLanguage("zh"))
    }

    @Test
    fun `isValidIsoLanguage accepts 3-letter codes`() {
        assertTrue(LanguageCountryProbe.isValidIsoLanguage("eng"))
        assertTrue(LanguageCountryProbe.isValidIsoLanguage("deu"))
    }

    @Test
    fun `isValidIsoLanguage rejects empty and null and uppercase`() {
        assertFalse(LanguageCountryProbe.isValidIsoLanguage(null))
        assertFalse(LanguageCountryProbe.isValidIsoLanguage(""))
        assertFalse(LanguageCountryProbe.isValidIsoLanguage("EN"))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        // 7 spec keys + locale.pattern.
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "locale.language",
                "locale.country",
                "locale.display_name",
                "ro_product_locale",
                "ro_product_locale_region",
                "locale.is_valid_iso",
                "locale.mismatch_build_vs_runtime",
                "locale.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `language evidence shows trimmed value`() = runBlocking {
        val result = makeProbe(language = " de ").run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.language" }
        assertEquals("de", ev?.value)
    }

    @Test
    fun `empty country — country evidence is empty placeholder`() = runBlocking {
        val result = makeProbe(country = "").run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.country" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `null country — country evidence is unreadable`() = runBlocking {
        val result = makeProbe(country = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.country" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `display_name evidence shows raw value`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.display_name" }
        assertEquals("Deutsch (Deutschland)", ev?.value)
    }

    @Test
    fun `ro_product_locale evidence reflects property`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro_product_locale" }
        assertEquals("de-DE", ev?.value)
    }

    @Test
    fun `XX country — is_valid_iso evidence is false`() = runBlocking {
        val result = makeProbe(country = "XX").run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.is_valid_iso" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `mismatch_build_vs_runtime evidence true for divergence`() = runBlocking {
        val result = makeProbe(language = "zh", country = "CN").run(
            fakeCtx(
                mapOf(
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE to "en-US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_LANGUAGE to "en",
                    LanguageCountryProbe.PROP_RO_PRODUCT_LOCALE_REGION to "US",
                    LanguageCountryProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "locale.mismatch_build_vs_runtime" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `mismatch_build_vs_runtime evidence false when matching`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "locale.mismatch_build_vs_runtime" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read Locale.getDefault() + ro.product.locale.* properties; detect " +
                "empty-country/language defaults, invalid ISO codes, and runtime-" +
                "vs-build locale divergence. No live IP geolocation.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_language_country`() {
        assertEquals("env.language_country", makeProbe().id)
    }

    @Test
    fun `probe rank is 36`() {
        assertEquals(36, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-36 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, makeProbe().budgetMs)
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

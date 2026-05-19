package com.detectorlab.probes.ui

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
 * Unit tests for SystemFontsProbe (ui.system_fonts, inventory rank 51).
 */
class SystemFontsProbeTest {

    /** Builds a realistic 150-font filename list including all 50 Roboto variants. */
    private fun realisticFontList(): List<String> {
        val roboto = (1..50).map { "Roboto-Variant$it.ttf" }
        val noto = (1..30).map { "NotoSans-Script$it.ttf" }
        val other = (1..70).map { "OtherFont$it.ttf" }
        return roboto + listOf("NotoColorEmoji.ttf") + noto + other
    }

    private fun makeProbe(
        fontFilenames: List<String>? = realisticFontList(),
        defaultFamily: String? = "sans-serif",
    ): SystemFontsProbe = SystemFontsProbe(
        fontFilenamesSupplier = { fontFilenames },
        defaultFamilySupplier = { defaultFamily },
    )

    /**
     * Fake context. `presentFonts` is the set of well-known font names that
     * `fileExists` returns true for. By default returns ALL well-known fonts
     * to simulate a real device.
     */
    private fun fakeCtx(
        presentFonts: Set<String> = SystemFontsProbe.WELL_KNOWN_FONT_NAMES.toSet(),
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated EACCES")
            val name = path.removePrefix("${SystemFontsProbe.FONTS_DIR}/")
            return name in presentFonts
        }
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
    fun `real Pixel 7 with 150 fonts + NotoColorEmoji + sans-serif — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — has_noto_color_emoji evidence is true`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.has_noto_color_emoji" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `monospace default family — score is 0 (standard)`() = runBlocking {
        val result = makeProbe(defaultFamily = "monospace").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `sans-serif-condensed default family — score is 0 (standard)`() = runBlocking {
        val result = makeProbe(defaultFamily = "sans-serif-condensed").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Missing NotoColorEmoji (0.85) ────────────────────────────────────────

    @Test
    fun `NotoColorEmoji missing from supplier list — score is 0_85`() = runBlocking {
        val list = realisticFontList().filterNot {
            it == SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI
        }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `NotoColorEmoji missing — pattern is no_noto_color_emoji`() = runBlocking {
        val list = realisticFontList().filterNot {
            it == SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI
        }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_NO_NOTO_EMOJI, ev?.value)
    }

    @Test
    fun `NotoColorEmoji missing via fileExists fallback (supplier null) — score is 0_85`() =
        runBlocking {
            // supplier null + fileExists returns false for NotoColorEmoji
            // but true for everything else.
            val presentFonts = SystemFontsProbe.WELL_KNOWN_FONT_NAMES.toSet() -
                SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI
            val result = makeProbe(fontFilenames = null)
                .run(fakeCtx(presentFonts = presentFonts))
            assertEquals(0.85, result.score)
        }

    @Test
    fun `NotoColorEmoji present in supplier but missing in fileExists — supplier wins`() =
        runBlocking {
            // If supplier list claims NotoColorEmoji present, that overrides
            // a missing fileExists hit. Supplier is the more reliable signal
            // when both available.
            val result = makeProbe(fontFilenames = realisticFontList()).run(
                fakeCtx(
                    presentFonts = SystemFontsProbe.WELL_KNOWN_FONT_NAMES.toSet() -
                        SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI,
                ),
            )
            assertEquals(0.0, result.score)
        }

    // ── Low total font count (0.85) ──────────────────────────────────────────

    @Test
    fun `40 fonts in supplier list — score is 0_85`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..39).map { "Roboto-Variant$it.ttf" }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `49 fonts — score is 0_85 (just below threshold)`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..48).map { "Roboto-Variant$it.ttf" }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `50 fonts boundary — score is 0 (threshold inclusive)`() = runBlocking {
        // Threshold is `< 50` strict. 50 exactly passes.
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..49).map { "Roboto-Variant$it.ttf" }
        // Need 20+ Roboto variants too — list has 49, all Roboto. OK.
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `low count — pattern is low_font_count`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..30).map { "Roboto-Variant$it.ttf" }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_LOW_COUNT, ev?.value)
    }

    @Test
    fun `low count rule only fires with supplier — fileExists fallback doesn't`() = runBlocking {
        // fileExists hits are bounded by WELL_KNOWN size (32); 50-threshold
        // would always fail. Rule must be supplier-gated.
        // With supplier null + all WELL_KNOWN present + family standard,
        // score should be 0 (no low_count rule fire).
        val result = makeProbe(fontFilenames = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Non-standard default family (0.85) ───────────────────────────────────

    @Test
    fun `default family DroidSans (non-standard) — score is 0_85`() = runBlocking {
        val result = makeProbe(defaultFamily = "DroidSans").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `default family weirdvalue — score is 0_85`() = runBlocking {
        val result = makeProbe(defaultFamily = "weirdvalue").run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-standard default — pattern is non_standard_default_family`() = runBlocking {
        val result = makeProbe(defaultFamily = "DroidSans").run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_NON_STANDARD_DEFAULT, ev?.value)
    }

    @Test
    fun `empty default family — score is 0 (can't claim non-standard)`() = runBlocking {
        // Empty/null defaultFamily means we have no observation — don't
        // false-positive. isStandardDefaultFamily returns true for empty
        // by design.
        val result = makeProbe(defaultFamily = "").run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null default family — score is 0 (no observation)`() = runBlocking {
        val result = makeProbe(defaultFamily = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Low Roboto variant count (0.7) ───────────────────────────────────────

    @Test
    fun `15 Roboto variants — score is 0_7`() = runBlocking {
        // 50 fonts total (passes count rule), Roboto count 15 < 20.
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..15).map { "Roboto-Variant$it.ttf" } +
            (1..34).map { "OtherFont$it.ttf" }  // 1 + 15 + 34 = 50
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.7, result.score)
    }

    @Test
    fun `20 Roboto variants — score is 0 (threshold inclusive)`() = runBlocking {
        // Threshold `< 20` strict. 20 exactly passes.
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..20).map { "Roboto-Variant$it.ttf" } +
            (1..29).map { "OtherFont$it.ttf" }  // 1+20+29=50
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `19 Roboto variants — score is 0_7 (just below threshold)`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..19).map { "Roboto-Variant$it.ttf" } +
            (1..30).map { "OtherFont$it.ttf" }  // 1+19+30=50
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        assertEquals(0.7, result.score)
    }

    @Test
    fun `low Roboto — pattern is low_roboto_count`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..10).map { "Roboto-Variant$it.ttf" } +
            (1..39).map { "OtherFont$it.ttf" }  // 1+10+39=50
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_LOW_ROBOTO_COUNT, ev?.value)
    }

    // ── No observation degraded (0.5) ────────────────────────────────────────

    @Test
    fun `supplier null and zero fileExists hits — score is 0_5`() = runBlocking {
        val result = makeProbe(fontFilenames = null, defaultFamily = null)
            .run(fakeCtx(presentFonts = emptySet()))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no observation — pattern is no_observation`() = runBlocking {
        val result = makeProbe(fontFilenames = null, defaultFamily = null)
            .run(fakeCtx(presentFonts = emptySet()))
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `no observation — confidence is 0_50`() = runBlocking {
        val result = makeProbe(fontFilenames = null, defaultFamily = null)
            .run(fakeCtx(presentFonts = emptySet()))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `default family only (no font surface) — confidence 0_95, score 0`() = runBlocking {
        // defaultFamily observed alone is enough for full confidence.
        val result = makeProbe(fontFilenames = null, defaultFamily = "sans-serif")
            .run(fakeCtx(presentFonts = emptySet()))
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `missing NotoEmoji + low count + non-standard family — NotoEmoji wins`() = runBlocking {
        // Emulator scenario: 30 fonts, no NotoColorEmoji, DroidSans default.
        // NotoColorEmoji rule fires first.
        val list = (1..30).map { "Roboto-Variant$it.ttf" }
        val result = makeProbe(fontFilenames = list, defaultFamily = "DroidSans")
            .run(fakeCtx())
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_NO_NOTO_EMOJI, ev?.value)
    }

    @Test
    fun `low count + non-standard family (with NotoEmoji present) — low_count wins`() =
        runBlocking {
            val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
                (1..30).map { "Roboto-Variant$it.ttf" }
            val result = makeProbe(fontFilenames = list, defaultFamily = "DroidSans")
                .run(fakeCtx())
            assertEquals(0.85, result.score)
            val ev = result.evidence.find { it.key == "fonts.pattern" }
            assertEquals(SystemFontsProbe.PATTERN_LOW_COUNT, ev?.value)
        }

    @Test
    fun `non-standard family + low Roboto — non_standard wins (0_85 over 0_7)`() = runBlocking {
        val list = listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) +
            (1..10).map { "Roboto-Variant$it.ttf" } +
            (1..39).map { "OtherFont$it.ttf" }  // 50 total, 10 Roboto < 20
        val result = makeProbe(fontFilenames = list, defaultFamily = "DroidSans")
            .run(fakeCtx())
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "fonts.pattern" }
        assertEquals(SystemFontsProbe.PATTERN_NON_STANDARD_DEFAULT, ev?.value)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor with real fileExists — confidence 0_95, score 0`() = runBlocking {
        // All well-known fonts present via fileExists; default family null.
        val result = SystemFontsProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `no-arg production ctor with empty fileExists — confidence 0_50, score 0_5`() =
        runBlocking {
            val result = SystemFontsProbe().run(fakeCtx(presentFonts = emptySet()))
            assertEquals(0.5, result.score)
            assertEquals(0.50, result.confidence)
        }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `fontFilenamesSupplier throws — does not crash`() = runBlocking {
        val probe = SystemFontsProbe(
            fontFilenamesSupplier = { throw RuntimeException("simulated") },
            defaultFamilySupplier = { "sans-serif" },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Supplier null → fileExists fallback → all well-known fonts
        // present → clean (since default family is standard).
        assertEquals(0.0, result.score)
    }

    @Test
    fun `defaultFamilySupplier throws — does not crash`() = runBlocking {
        val probe = SystemFontsProbe(
            fontFilenamesSupplier = { listOf(SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI) },
            defaultFamilySupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // defaultFamily null + 1 font (low count, but NotoEmoji is the only
        // entry so NotoColorEmoji rule doesn't fire; low_count fires).
        assertEquals(0.85, result.score)
    }

    @Test
    fun `fileExists throws — does not crash, falls back to supplier`() = runBlocking {
        val result = makeProbe(fontFilenames = realisticFontList())
            .run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
        // Supplier list still valid → clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both suppliers throw + fileExists throws — degraded`() = runBlocking {
        val probe = SystemFontsProbe(
            fontFilenamesSupplier = { throw RuntimeException("a") },
            defaultFamilySupplier = { throw RuntimeException("b") },
        )
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `countRobotoVariants counts only Roboto prefix`() {
        assertEquals(
            3,
            SystemFontsProbe.countRobotoVariants(
                listOf(
                    "Roboto-Regular.ttf",
                    "Roboto-Bold.ttf",
                    "Roboto-Italic.ttf",
                    "NotoSans-Regular.ttf",
                    "DroidSans.ttf",
                ),
            ),
        )
    }

    @Test
    fun `countRobotoVariants case-sensitive (lowercase roboto not counted)`() {
        // Case-sensitive by design per KDoc rationale.
        assertEquals(
            0,
            SystemFontsProbe.countRobotoVariants(listOf("roboto-regular.ttf")),
        )
    }

    @Test
    fun `countRobotoVariants returns 0 for empty`() {
        assertEquals(0, SystemFontsProbe.countRobotoVariants(emptyList()))
    }

    @Test
    fun `isStandardDefaultFamily true for sans-serif`() {
        assertTrue(SystemFontsProbe.isStandardDefaultFamily("sans-serif"))
    }

    @Test
    fun `isStandardDefaultFamily true for all variants in set`() {
        listOf(
            "sans-serif",
            "sans-serif-condensed",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-thin",
            "serif",
            "monospace",
        ).forEach { family ->
            assertTrue(
                SystemFontsProbe.isStandardDefaultFamily(family),
                "expected $family to be standard",
            )
        }
    }

    @Test
    fun `isStandardDefaultFamily false for DroidSans`() {
        assertFalse(SystemFontsProbe.isStandardDefaultFamily("DroidSans"))
    }

    @Test
    fun `isStandardDefaultFamily true for null and empty (can't claim non-standard)`() {
        assertTrue(SystemFontsProbe.isStandardDefaultFamily(null))
        assertTrue(SystemFontsProbe.isStandardDefaultFamily(""))
    }

    // ── Drift-alarm invariants ───────────────────────────────────────────────

    @Test
    fun `WELL_KNOWN_FONT_NAMES contains NotoColorEmoji`() {
        // Critical: NotoColorEmoji rule depends on this being in the
        // fileExists fallback list.
        assertTrue(
            SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI in SystemFontsProbe.WELL_KNOWN_FONT_NAMES,
        )
    }

    @Test
    fun `WELL_KNOWN_FONT_NAMES contains at least 10 Roboto variants`() {
        // Provides proxy Roboto-variant count for fileExists fallback.
        val robotoCount = SystemFontsProbe.WELL_KNOWN_FONT_NAMES
            .count { it.startsWith("Roboto") }
        assertTrue(robotoCount >= 10, "Expected >=10 Roboto variants, found $robotoCount")
    }

    @Test
    fun `STANDARD_DEFAULT_FAMILIES list pinned (drift alarm)`() {
        assertEquals(
            setOf(
                "sans-serif",
                "sans-serif-condensed",
                "sans-serif-light",
                "sans-serif-medium",
                "sans-serif-thin",
                "serif",
                "monospace",
            ),
            SystemFontsProbe.STANDARD_DEFAULT_FAMILIES,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "fonts.system_count",
                "fonts.has_noto_color_emoji",
                "fonts.roboto_variant_count",
                "fonts.default_family",
                "fonts.sample",
                "fonts.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `system_count evidence reflects supplier list size`() = runBlocking {
        val list = (1..150).map { "Font$it.ttf" } + SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.system_count" }
        assertEquals("151", ev?.value)
    }

    @Test
    fun `system_count evidence shows fileExists fallback when supplier null`() = runBlocking {
        val result = makeProbe(fontFilenames = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.system_count" }
        // Fallback format: "via fileExists: N/M"
        assertTrue(ev?.value.toString().startsWith("via fileExists:"))
    }

    @Test
    fun `roboto_variant_count evidence reflects count`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.roboto_variant_count" }
        // realisticFontList() has 50 Roboto variants.
        assertEquals("50", ev?.value)
    }

    @Test
    fun `default_family evidence reflects supplier`() = runBlocking {
        val result = makeProbe(defaultFamily = "sans-serif-condensed").run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.default_family" }
        assertEquals("sans-serif-condensed", ev?.value)
    }

    @Test
    fun `default_family unavailable when null`() = runBlocking {
        val result = makeProbe(defaultFamily = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.default_family" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `default_family empty placeholder when supplier returns empty`() = runBlocking {
        val result = makeProbe(defaultFamily = "").run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.default_family" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `sample evidence shows first 10 entries with continuation marker`() = runBlocking {
        val list = (1..50).map { "Font$it.ttf" } +
            SystemFontsProbe.FILENAME_NOTO_COLOR_EMOJI +
            (1..30).map { "Roboto-Variant$it.ttf" }
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.sample" }
        // > 10 entries → first 10 + ",..."
        assertTrue(ev?.value.toString().endsWith(",..."))
        // First entry is Font1.ttf
        assertTrue(ev?.value.toString().startsWith("Font1.ttf,"))
    }

    @Test
    fun `sample evidence no continuation marker for short list`() = runBlocking {
        val list = listOf("a.ttf", "b.ttf", "c.ttf")
        val result = makeProbe(fontFilenames = list).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.sample" }
        assertEquals("a.ttf,b.ttf,c.ttf", ev?.value)
    }

    @Test
    fun `sample evidence shows fileExists hits when supplier null`() = runBlocking {
        val result = makeProbe(fontFilenames = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "fonts.sample" }
        // Sample drawn from fileExists hits — should contain Roboto-Regular
        assertTrue(ev?.value.toString().contains("Roboto-Regular.ttf"))
    }

    @Test
    fun `sample evidence — no observation placeholder`() = runBlocking {
        val result = makeProbe(fontFilenames = null)
            .run(fakeCtx(presentFonts = emptySet()))
        val ev = result.evidence.find { it.key == "fonts.sample" }
        assertEquals("<no observation>", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Enumerate /system/fonts/ directory; detect missing " +
                "NotoColorEmoji, low font count (<50), low Roboto variant " +
                "count, non-standard default family",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_system_fonts`() {
        assertEquals("ui.system_fonts", makeProbe().id)
    }

    @Test
    fun `probe rank is 51`() {
        assertEquals(51, makeProbe().rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, makeProbe().category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // Matches shared/probes/inventory.yml rank-51 severity=trace.
        // The TRACE level signals low confidence even at high score.
        assertEquals(ProbeSeverity.TRACE, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 150ms`() {
        assertEquals(150L, makeProbe().budgetMs)
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

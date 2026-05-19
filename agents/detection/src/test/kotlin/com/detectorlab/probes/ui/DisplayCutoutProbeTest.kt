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
 * Unit tests for DisplayCutoutProbe (ui.display_cutout, inventory rank 52).
 */
class DisplayCutoutProbeTest {

    private fun makeProbe(
        cutoutPresent: Boolean? = true,
        safeInsetTopDp: Int? = 32,
    ): DisplayCutoutProbe = DisplayCutoutProbe(
        cutoutPresentSupplier = { cutoutPresent },
        safeInsetTopDpSupplier = { safeInsetTopDp },
    )

    private fun fakeCtx(
        model: String? = "Pixel 7",
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return if (key == DisplayCutoutProbe.PROP_RO_PRODUCT_MODEL) model else null
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
    fun `Pixel 7 with hole-punch 32dp — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(DisplayCutoutProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Galaxy S24 with cutout — score is 0`() = runBlocking {
        val result = makeProbe(cutoutPresent = true, safeInsetTopDp = 28)
            .run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `OnePlus 11 with cutout — score is 0`() = runBlocking {
        val result = makeProbe(cutoutPresent = true, safeInsetTopDp = 30)
            .run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 3 (pre-cutout) with null cutout — score is 0 (not in cutout list)`() = runBlocking {
        // Pixel 3 has no notch/hole-punch. Null cutout is expected.
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 3"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null cutout on Pixel 5 — score is 0_5 (coverage-gap closure)`() = runBlocking {
        // Pixel 5 has a top-left hole-punch per Google spec. Added to
        // KNOWN_CUTOUT_MODELS per reviewer's rank-52-approval coverage-gap
        // closure recommendation — was previously documented limitation,
        // now firing per spec.
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 5"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Pixel 5a — score is 0_5`() = runBlocking {
        // Pixel 5a also has top-left hole-punch per Google spec. Caught
        // by `pixel 5` substring (also matches Pixel 5a).
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 5a"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `Lenovo tablet with null cutout — score is 0 (tablets don't have notches)`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Redmi Note 12 with null cutout — score is 0 (not in cutout list)`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null model with null cutout — score is 0 (cannot claim model match)`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = null))
        assertEquals(0.0, result.score)
    }

    // ── Implausible inset (0.85) ─────────────────────────────────────────────

    @Test
    fun `inset 250dp — score is 0_85 (above plausibility ceiling)`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 250).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `inset 500dp — score is 0_85`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 500).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `inset Int_MAX_VALUE — score is 0_85`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = Int.MAX_VALUE).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `inset 200dp boundary — score is 0 (plausibility ceiling strict)`() = runBlocking {
        // Threshold is `> 200` strict. 200 exactly passes.
        val result = makeProbe(safeInsetTopDp = 200).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `inset 201dp — score is 0_85 (just above ceiling)`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 201).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `inset 44dp (Pixel 8 Pro real value) — score is 0`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 44).run(fakeCtx(model = "Pixel 8 Pro"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `inset 0dp on cutout-capable model — score is 0_5 (treated as cutout=false)`() =
        runBlocking {
            // 0dp is plausible (≤ 200) so doesn't fire implausible rule.
            // But this test uses cutoutPresent=false explicitly to check
            // the missing-on-cutout-model rule independent of inset.
            // (0 inset + cutoutPresent=true would actually be acceptable
            // for waterfall-edge displays; test below covers that.)
            val result = makeProbe(cutoutPresent = false, safeInsetTopDp = 0)
                .run(fakeCtx(model = "Pixel 7"))
            assertEquals(0.5, result.score)
        }

    @Test
    fun `implausible inset — pattern is implausible_inset`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 250).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(DisplayCutoutProbe.PATTERN_IMPLAUSIBLE_INSET, ev?.value)
    }

    @Test
    fun `implausible inset on tablet — still fires (model-agnostic strong rule)`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 500).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.85, result.score)
    }

    // ── Missing on cutout-capable flagship (0.5 weak) ────────────────────────

    @Test
    fun `null cutout on Pixel 6 — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 6"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Pixel 7 Pro — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 7 Pro"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Pixel 8 — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 8"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Galaxy S22 (SM-S901B) — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "SM-S901B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Galaxy Note 20 (SM-N981B) — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "SM-N981B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Galaxy Z Fold 4 (SM-F936B) — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "SM-F936B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on OnePlus 11 — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null cutout on Xiaomi 14 — score is 0_5`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Xiaomi 14"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `missing on cutout model — pattern is missing_on_cutout_model`() = runBlocking {
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = null)
            .run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(DisplayCutoutProbe.PATTERN_MISSING_ON_CUTOUT_MODEL, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `implausible inset on Pixel 7 — implausible wins (0_85 over 0_5)`() = runBlocking {
        // Strong rule (implausible inset) fires before weak rule
        // (missing-on-cutout). Pixel 7 cutoutPresent=true + inset 250
        // (impossible value). Strong rule first per partition pattern.
        val result = makeProbe(cutoutPresent = true, safeInsetTopDp = 250)
            .run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(DisplayCutoutProbe.PATTERN_IMPLAUSIBLE_INSET, ev?.value)
    }

    @Test
    fun `implausible inset + cutout absent on Pixel 7 — implausible wins`() = runBlocking {
        // Edge case: inset value supplied (impossibly large) but cutout
        // supplier says false. Implausible rule fires first regardless.
        val result = makeProbe(cutoutPresent = false, safeInsetTopDp = 500)
            .run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both suppliers return — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only cutoutPresent returns — confidence 0_95`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = null).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only safeInsetTopDp returns — confidence 0_95`() = runBlocking {
        val result = makeProbe(cutoutPresent = null).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `both suppliers null — confidence 0_50`() = runBlocking {
        val result = makeProbe(cutoutPresent = null, safeInsetTopDp = null).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `both suppliers null — score is 0 (cannot fire any rule)`() = runBlocking {
        val result = makeProbe(cutoutPresent = null, safeInsetTopDp = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_50`() = runBlocking {
        val result = DisplayCutoutProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `cutoutPresentSupplier throws — does not crash`() = runBlocking {
        val probe = DisplayCutoutProbe(
            cutoutPresentSupplier = { throw RuntimeException("simulated") },
            safeInsetTopDpSupplier = { 32 },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `safeInsetTopDpSupplier throws — does not crash`() = runBlocking {
        val probe = DisplayCutoutProbe(
            cutoutPresentSupplier = { true },
            safeInsetTopDpSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = DisplayCutoutProbe(
            cutoutPresentSupplier = { throw RuntimeException("a") },
            safeInsetTopDpSupplier = { throw RuntimeException("b") },
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
    fun `isCutoutCapableModel detects Pixel 5 onward`() {
        // Pixel 5 added per reviewer's rank-52-approval coverage-gap
        // closure (Pixel 5 has top-left hole-punch per Google spec).
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 5"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 5a"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 6"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 6 Pro"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 7"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 8"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel Fold"))
    }

    @Test
    fun `isCutoutCapableModel detects Pixel 5a 6a 7a 8a (all have hole-punch)`() {
        // Unlike rank-46 KNOWN_120HZ_MODELS where Pixel 6a/7a are excluded,
        // ALL Pixel a-variants from 5a forward have hole-punch cutouts.
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 5a"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 6a"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 7a"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Pixel 8a"))
    }

    @Test
    fun `isCutoutCapableModel detects Galaxy S22+`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-S901B"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-S908"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-S921"))
    }

    @Test
    fun `isCutoutCapableModel detects Galaxy Note 20`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-N981B"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-N986"))
    }

    @Test
    fun `isCutoutCapableModel detects Galaxy Z Fold 4 5`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-F936B"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("SM-F946B"))
    }

    @Test
    fun `isCutoutCapableModel detects OnePlus 9+`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("OnePlus 9"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("OnePlus 11"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("OnePlus 12"))
    }

    @Test
    fun `isCutoutCapableModel detects Xiaomi 13+`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Xiaomi 13"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Mi 13"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("Xiaomi 14"))
    }

    @Test
    fun `isCutoutCapableModel case-insensitive`() {
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("PIXEL 7"))
        assertTrue(DisplayCutoutProbe.isCutoutCapableModel("pixel 7"))
    }

    @Test
    fun `isCutoutCapableModel false for pre-cutout Pixel`() {
        // Pixel 4 and earlier use teardrop notches or no cutout. List
        // cutoff now at Pixel 5 (hole-punch generation start per Google
        // spec — coverage-gap closure from rank-52 review).
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Pixel 4a"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Pixel 4"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Pixel 3"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Pixel 2"))
    }

    @Test
    fun `isCutoutCapableModel false for budget phones`() {
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Redmi Note 12"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("SM-A525F"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("OnePlus Nord"))
    }

    @Test
    fun `isCutoutCapableModel false for tablets`() {
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Lenovo Tab P11"))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel("Galaxy Tab S8"))
    }

    @Test
    fun `isCutoutCapableModel false for empty and null`() {
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel(null))
        assertFalse(DisplayCutoutProbe.isCutoutCapableModel(""))
    }

    // ── Drift-alarm invariants ───────────────────────────────────────────────

    @Test
    fun `KNOWN_CUTOUT_MODELS list pinned (drift alarm with IN OUT membership)`() {
        // Future maintainer changing this list must update this test
        // deliberately. The list is intentionally distinct from rank-46
        // KNOWN_120HZ_MODELS (Pixel 5/6 have cutouts but no 120Hz; both
        // lists start Galaxy at S22 but diverge on Pixel cutoff).
        val list = DisplayCutoutProbe.KNOWN_CUTOUT_MODELS
        // IN: cutoff at Pixel 5 (hole-punch generation start per Google
        // spec — coverage-gap closure from rank-52 review).
        assertTrue("pixel 5" in list)
        assertTrue("pixel 6" in list)
        assertTrue("pixel 7" in list)
        assertTrue("pixel 8" in list)
        assertTrue("sm-s901" in list)
        assertTrue("sm-n981" in list)  // Note 20 family
        assertTrue("sm-f936" in list)  // Z Fold 4
        assertTrue("oneplus 11" in list)
        assertTrue("xiaomi 14" in list)
        // OUT: pre-hole-punch Pixels (notch or no cutout) NOT in list
        assertFalse("pixel 4a" in list)
        assertFalse("pixel 4" in list)
        assertFalse("pixel 3" in list)
    }

    @Test
    fun `NON_CUTOUT_VARIANTS currently empty (Pixel a-variants all have cutouts)`() {
        // Forward-looking scaffolding per rank-46 negative-exclusion pattern.
        // Currently empty because Pixel 6a/7a/8a all ship with hole-punch.
        // If a future Pixel variant has positively-matching substring but
        // no cutout, add it here with spec verification (matching the
        // verified-per-OEM-spec callout pattern from rank-46).
        assertEquals(emptyList(), DisplayCutoutProbe.NON_CUTOUT_VARIANTS)
    }

    // ── Cross-rank distinct-semantic verification ────────────────────────────

    @Test
    fun `KNOWN_CUTOUT_MODELS distinct from rank-46 KNOWN_120HZ_MODELS`() {
        // Both lists target flagship-Pixel-family models, but semantics
        // differ: cutout starts at Pixel 5 (hole-punch generation), 120Hz
        // starts at Pixel 7. `pixel 5` and `pixel 6` are in cutout list
        // but NOT in 120Hz list. Locks the cross-rank-different-semantic
        // decision across two list cutoffs.
        assertTrue("pixel 5" in DisplayCutoutProbe.KNOWN_CUTOUT_MODELS)
        assertFalse("pixel 5" in RefreshRateProbe.KNOWN_120HZ_MODELS)
        assertTrue("pixel 6" in DisplayCutoutProbe.KNOWN_CUTOUT_MODELS)
        assertFalse("pixel 6" in RefreshRateProbe.KNOWN_120HZ_MODELS)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 5 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(5, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "display.has_cutout",
                "display.cutout_safe_inset_top",
                "display.cutout_bounds_count",
                "display.is_cutout_capable_model",
                "display.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `has_cutout evidence reflects supplier (true)`() = runBlocking {
        val result = makeProbe(cutoutPresent = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.has_cutout" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_cutout evidence reflects supplier (false)`() = runBlocking {
        val result = makeProbe(cutoutPresent = false).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.has_cutout" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `has_cutout evidence unavailable when null`() = runBlocking {
        val result = makeProbe(cutoutPresent = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.has_cutout" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `cutout_safe_inset_top evidence reflects supplier`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = 32).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.cutout_safe_inset_top" }
        assertEquals("32", ev?.value)
    }

    @Test
    fun `cutout_safe_inset_top evidence unavailable when null`() = runBlocking {
        val result = makeProbe(safeInsetTopDp = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.cutout_safe_inset_top" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `cutout_bounds_count evidence — 1 when cutout present`() = runBlocking {
        val result = makeProbe(cutoutPresent = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.cutout_bounds_count" }
        assertEquals("1", ev?.value)
    }

    @Test
    fun `cutout_bounds_count evidence — 0 when cutout absent`() = runBlocking {
        val result = makeProbe(cutoutPresent = false).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.cutout_bounds_count" }
        assertEquals("0", ev?.value)
    }

    @Test
    fun `cutout_bounds_count evidence unavailable when null`() = runBlocking {
        val result = makeProbe(cutoutPresent = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.cutout_bounds_count" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `is_cutout_capable_model evidence — true on Pixel 7`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "display.is_cutout_capable_model" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_cutout_capable_model evidence — false on Pixel 3`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Pixel 3"))
        val ev = result.evidence.find { it.key == "display.is_cutout_capable_model" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_cutout_capable_model evidence — unknown for null model`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = null))
        val ev = result.evidence.find { it.key == "display.is_cutout_capable_model" }
        assertEquals("unknown", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read DisplayCutout via WindowInsets / Display.cutout; detect " +
                "missing cutout on flagship-with-notch models, implausible " +
                "inset values, and inset-vs-bounds contradictions",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_display_cutout`() {
        assertEquals("ui.display_cutout", makeProbe().id)
    }

    @Test
    fun `probe rank is 52`() {
        assertEquals(52, makeProbe().rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, makeProbe().category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // Matches shared/probes/inventory.yml rank-52 severity=trace.
        // Second TRACE probe in codebase (after rank-51).
        assertEquals(ProbeSeverity.TRACE, makeProbe().severity)
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

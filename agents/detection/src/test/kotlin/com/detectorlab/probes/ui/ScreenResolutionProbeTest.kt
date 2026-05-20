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
 * Unit tests for ScreenResolutionProbe (ui.screen_resolution, inventory rank 23).
 */
class ScreenResolutionProbeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeProbe(
        width: Int? = 1080,
        height: Int? = 2400,
        density: Int? = 420,
        xdpi: Float? = 411.0f,
        ydpi: Float? = 413.0f,
        modelFromSupplier: String? = null,
    ): ScreenResolutionProbe = ScreenResolutionProbe(
        widthPixelsSupplier = { width },
        heightPixelsSupplier = { height },
        densityDpiSupplier = { density },
        xdpiSupplier = { xdpi },
        ydpiSupplier = { ydpi },
        modelSupplier = { modelFromSupplier },
    )

    private fun ctxWithModel(model: String? = "Pixel 7"): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? =
            if (key == ScreenResolutionProbe.PROP_RO_PRODUCT_MODEL) model else null
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

    // ── Clean OEM device (Pixel 7) ────────────────────────────────────────────

    @Test
    fun `Pixel 7 with matching DisplayMetrics — score is 0`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2400, density = 420, xdpi = 411.0f, ydpi = 413.0f)
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 7 — confidence is 0_95`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2400, density = 420, xdpi = 411.0f, ydpi = 413.0f)
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Pixel 7 — model_match is match`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2400, density = 420, xdpi = 411.0f, ydpi = 413.0f)
        val result = probe.run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_MATCH, ev?.value)
    }

    @Test
    fun `Pixel 7 Pro substring match — score is 0`() = runBlocking {
        // "Pixel 7 Pro" should match the "pixel 7 pro" profile (more-specific),
        // not the generic "pixel 7" entry. Both are in the table; the more-
        // specific one is selected by ordering.
        val probe = makeProbe(width = 1440, height = 3120, density = 560, xdpi = 510f, ydpi = 510.5f)
        val result = probe.run(ctxWithModel("Pixel 7 Pro"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Galaxy S22 with matching DisplayMetrics — score is 0`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2340, density = 420, xdpi = 411.0f, ydpi = 411.5f)
        val result = probe.run(ctxWithModel("SM-S901B"))   // Galaxy S22
        assertEquals(0.0, result.score)
    }

    // ── Emulator resolution (score 1.0) ───────────────────────────────────────

    @Test
    fun `1080x1920 emulator resolution — score is 1_0`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 1920, density = 480, xdpi = 480.0f, ydpi = 480.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `480x800 older AVD — score is 1_0`() = runBlocking {
        val probe = makeProbe(width = 480, height = 800, density = 240, xdpi = 240.0f, ydpi = 240.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `720x1280 Genymotion — score is 1_0`() = runBlocking {
        val probe = makeProbe(width = 720, height = 1280, density = 320, xdpi = 320.0f, ydpi = 320.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `rotated 800x480 — also recognised as emulator default`() = runBlocking {
        val probe = makeProbe(width = 800, height = 480, density = 240, xdpi = 240.0f, ydpi = 240.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(1.0, result.score)
    }

    // ── Model mismatch (score 0.9) ────────────────────────────────────────────

    @Test
    fun `Pixel 7 model with wrong resolution — score is 0_9`() = runBlocking {
        // Model claims Pixel 7 but resolution is 1080x1920 (real Pixel 7 is
        // 1080x2400). 1080x1920 ALSO hits emulator-default 1.0 — emulator
        // dominates; the mismatch rule fires too but max wins.
        val probe = makeProbe(width = 1080, height = 1920, density = 420, xdpi = 411.0f, ydpi = 413.0f)
        val result = probe.run(ctxWithModel("Pixel 7"))
        // Emulator-default rule (1.0) wins over the 0.9 model-mismatch.
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Pixel 7 with non-emulator wrong resolution — score is 0_9`() = runBlocking {
        // 1440x3120 is NOT an emulator default but doesn't match the Pixel 7
        // profile (1080x2400) — the mismatch rule should fire cleanly at 0.9.
        val probe = makeProbe(width = 1440, height = 3120, density = 560, xdpi = 510.0f, ydpi = 510.5f)
        val result = probe.run(ctxWithModel("Pixel 7"))   // NOT Pixel 7 Pro
        // 1440x3120 happens to match Pixel 7 Pro profile, but caller said
        // "Pixel 7" (matches the generic pixel 7 entry first). Mismatch fires.
        assertEquals(0.9, result.score)
    }

    @Test
    fun `Galaxy S22 model with Pixel-style density — score is 0_9`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2340, density = 240, xdpi = 240.0f, ydpi = 240.5f)
        val result = probe.run(ctxWithModel("SM-S901B"))
        // 240dpi isn't an emulator-default-resolution density paired with
        // 1080x2340, but density 240 != 420 expected by SM-S901 profile.
        assertEquals(0.9, result.score)
    }

    @Test
    fun `Pixel 7 mismatch — model_match evidence is mismatch`() = runBlocking {
        val probe = makeProbe(width = 1440, height = 3120, density = 560, xdpi = 510.0f, ydpi = 510.5f)
        val result = probe.run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_MISMATCH, ev?.value)
    }

    // ── Perfect xdpi == ydpi == density (score 0.85) ─────────────────────────

    @Test
    fun `xdpi equals ydpi equals density — score is 0_85`() = runBlocking {
        // Use a NON-emulator-resolution to isolate the perfect-equality rule.
        val probe = makeProbe(
            width = 1170, height = 2532, density = 460,
            xdpi = 460.0f, ydpi = 460.0f, modelFromSupplier = "",
        )
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Pixel 7 resolution but perfect dpi equality — score is 0_85 (emulator tell)`() = runBlocking {
        val probe = makeProbe(width = 1080, height = 2400, density = 420, xdpi = 420.0f, ydpi = 420.0f)
        val result = probe.run(ctxWithModel(null))   // no model → emulator-tell wins
        assertEquals(0.85, result.score)
    }

    // ── Density not multiple of 20 (score 0.7) ────────────────────────────────

    @Test
    fun `density 333 not multiple of 20 — score is 0_7`() = runBlocking {
        val probe = makeProbe(
            width = 1170, height = 2532, density = 333,
            xdpi = 411.0f, ydpi = 413.0f,
        )
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `density 480 (standard bucket) — no density signal fires`() = runBlocking {
        // 480 IS a multiple of 20 → density rule doesn't fire.
        // 1080x2400 isn't an emulator default. No model. xdpi != ydpi.
        // → no rule fires → score 0.0.
        val probe = makeProbe(
            width = 1080, height = 2400, density = 480,
            xdpi = 411.0f, ydpi = 413.0f,
        )
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `density 420 (Pixel intermediate, mod 20 but not mod 40) — no density signal fires`() = runBlocking {
        // Regression guard: 420 % 40 = 20 (would have fired under the original
        // mod-40 rule and false-positived on every real Pixel device). Under
        // mod-20: 420 % 20 = 0, so the rule correctly stays silent.
        val probe = makeProbe(
            width = 1170, height = 2532, density = 420,
            xdpi = 411.0f, ydpi = 413.0f,
        )
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.0, result.score)
    }

    // ── Multi-signal precedence ──────────────────────────────────────────────

    @Test
    fun `emulator resolution dominates density-not-mod-20`() = runBlocking {
        // 480x800 + density 333 (not mod 20). Emulator rule (1.0) wins over
        // density-not-mod-20 (0.7).
        val probe = makeProbe(width = 480, height = 800, density = 333, xdpi = 240.0f, ydpi = 240.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `perfect dpi equality plus density-not-mod-20 — perfect equality wins`() = runBlocking {
        // density=333 isn't mod 20 (0.7); xdpi==ydpi==333 (0.85). Max wins.
        val probe = makeProbe(width = 1170, height = 2532, density = 333, xdpi = 333.0f, ydpi = 333.0f)
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.85, result.score)
    }

    // ── Display accessor unavailable (production stub) ───────────────────────

    @Test
    fun `no display metrics from production-stub constructor — score is 0_5`() = runBlocking {
        val probe = ScreenResolutionProbe()   // all suppliers null
        val result = probe.run(ctxWithModel(null))
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
    }

    @Test
    fun `no display metrics — confidence is 0_30 (degraded)`() = runBlocking {
        val probe = ScreenResolutionProbe()
        val result = probe.run(ctxWithModel(null))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `no display metrics — model_match evidence is no_display`() = runBlocking {
        val probe = ScreenResolutionProbe()
        val result = probe.run(ctxWithModel(null))
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_NO_DISPLAY, ev?.value)
    }

    @Test
    fun `no display but model reachable — confidence is 0_60`() = runBlocking {
        val probe = ScreenResolutionProbe()
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `width missing but height and density present — still treated as no display`() = runBlocking {
        // Partial display → all-or-nothing for the display-readable flag.
        val probe = ScreenResolutionProbe(
            widthPixelsSupplier = { null },
            heightPixelsSupplier = { 2400 },
            densityDpiSupplier = { 420 },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_NO_DISPLAY, ev?.value)
    }

    // ── Unknown model ─────────────────────────────────────────────────────────

    @Test
    fun `unknown model with clean dimensions — model_match is unknown_model`() = runBlocking {
        val probe = makeProbe(
            width = 1170, height = 2532, density = 460,
            xdpi = 411.0f, ydpi = 413.0f,
        )
        val result = probe.run(ctxWithModel("MyObscureBrand X12"))
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_UNKNOWN, ev?.value)
    }

    @Test
    fun `unknown model with clean dimensions — score stays 0_0`() = runBlocking {
        val probe = makeProbe(
            width = 1170, height = 2532, density = 460,
            xdpi = 411.0f, ydpi = 413.0f,
        )
        val result = probe.run(ctxWithModel("MyObscureBrand X12"))
        assertEquals(0.0, result.score)
    }

    // ── Supplier-throws crash-safety ──────────────────────────────────────────

    @Test
    fun `width supplier throws — degrades to no_display, no crash`() = runBlocking {
        val probe = ScreenResolutionProbe(
            widthPixelsSupplier = { throw RuntimeException("simulated") },
            heightPixelsSupplier = { 2400 },
            densityDpiSupplier = { 420 },
            xdpiSupplier = { 411.0f },
            ydpiSupplier = { 413.0f },
        )
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "model_match" }
        assertEquals(ScreenResolutionProbe.MODEL_NO_DISPLAY, ev?.value)
    }

    @Test
    fun `model getSystemProperty throws — does not crash`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = throw RuntimeException("simulated")
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
        val probe = makeProbe()
        val result = probe.run(ctx)
        assertFalse(result.failed)
        // Display still works; only model went missing → confidence drops to PARTIAL.
        assertEquals(0.60, result.confidence)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all seven documented keys`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel("Pixel 7"))
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "display.widthPixels",
                "display.heightPixels",
                "display.densityDpi",
                "display.xdpi",
                "display.ydpi",
                "display.aspect_ratio",
                "model_match",
            ),
            keys,
        )
    }

    @Test
    fun `aspect_ratio evidence uses locale-stable decimal formatting`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel("Pixel 7"))
        val ev = result.evidence.find { it.key == "display.aspect_ratio" }
        val value = ev?.value as String
        assertTrue('.' in value, "expected decimal point in $value")
        assertFalse(',' in value, "expected no comma in $value")
    }

    @Test
    fun `aspect_ratio computed from long over short — never less than 1`() = runBlocking {
        // Portrait or landscape — same ratio.
        val portrait = makeProbe(width = 1080, height = 2400)
        val landscape = makeProbe(width = 2400, height = 1080)
        val portraitResult = portrait.run(ctxWithModel(null))
        val landscapeResult = landscape.run(ctxWithModel(null))
        val pRatio = portraitResult.evidence.find { it.key == "display.aspect_ratio" }?.value as String
        val lRatio = landscapeResult.evidence.find { it.key == "display.aspect_ratio" }?.value as String
        assertEquals(pRatio, lRatio)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(ctxWithModel(null))
        assertEquals(
            "Read DisplayMetrics + ro.product.model and cross-check " +
                "resolution/density/dpi against known device profiles and " +
                "emulator defaults",
            result.method,
        )
    }

    // ── Lookup-table sanity ───────────────────────────────────────────────────

    @Test
    fun `device profile table is small (≤ 15 entries — lab approximation)`() {
        assertTrue(
            ScreenResolutionProbe.DEVICE_PROFILES.size <= 15,
            "table has ${ScreenResolutionProbe.DEVICE_PROFILES.size} entries — keep it small",
        )
    }

    @Test
    fun `all device profile densities are within Android plausible range`() {
        // Previously this test asserted `densityDpi % 20 == 0` as an
        // "empirical OEM rule". Google's Pixel 8 Pro at 489 PPI (official
        // store.google.com spec) disproves the rule — mod-20 was never a
        // contract, just a partial pattern. Closure of cross-cutting #2
        // telemetry gap 2026-05-20: the right invariant is a sanity bound
        // (no zero, no >1000 dpi typos).
        for ((key, profile) in ScreenResolutionProbe.DEVICE_PROFILES) {
            assertTrue(
                profile.densityDpi in 120..720,
                "device $key has implausible density ${profile.densityDpi} " +
                    "(expected 120..720 — Android standard bucket range)",
            )
        }
    }

    @Test
    fun `emulator resolution set is symmetric — every WxH has its rotated HxW`() {
        for ((w, h) in ScreenResolutionProbe.EMULATOR_RESOLUTIONS) {
            assertTrue(
                (h to w) in ScreenResolutionProbe.EMULATOR_RESOLUTIONS,
                "missing rotated pair for ${w}x$h",
            )
        }
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_screen_resolution`() {
        assertEquals("ui.screen_resolution", makeProbe().id)
    }

    @Test
    fun `probe rank is 23`() {
        assertEquals(23, makeProbe().rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
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
        val result = probe.run(ctxWithModel("Pixel 7"))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

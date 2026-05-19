package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.network.NetworkTypeProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for RefreshRateProbe (ui.refresh_rate, inventory rank 46).
 */
class RefreshRateProbeTest {

    private fun makeProbe(
        refreshRateHz: Float? = 120.0f,
        supportedModes: List<Float>? = listOf(60.0f, 90.0f, 120.0f),
    ): RefreshRateProbe = RefreshRateProbe(
        refreshRateHzSupplier = { refreshRateHz },
        supportedModeHzSupplier = { supportedModes },
    )

    private fun fakeCtx(
        model: String? = "Pixel 7",
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return if (key == RefreshRateProbe.PROP_RO_PRODUCT_MODEL) model else null
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
    fun `Pixel 7 with 120Hz adaptive modes — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(RefreshRateProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `budget phone with 60Hz only — score is 0 (not in 120Hz-capable list)`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 4a budget with 60Hz only — score is 0 (not in 120Hz list)`() = runBlocking {
        // Pixel 4a has 60 Hz max — intentionally NOT in KNOWN_120HZ_MODELS.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 4a"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `tablet with 60Hz only — score is 0`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 7 at 90Hz mid-adaptive — score is 0`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 90.0f,
            supportedModes = listOf(60.0f, 90.0f, 120.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `gaming phone at 144Hz — score is 0`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 144.0f,
            supportedModes = listOf(60.0f, 120.0f, 144.0f),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `flagship gaming phone at 165Hz — score is 0`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 165.0f,
            supportedModes = listOf(60.0f, 120.0f, 165.0f),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Implausible Hz (0.95) ────────────────────────────────────────────────

    @Test
    fun `0 Hz — score is 0_95`() = runBlocking {
        val result = makeProbe(refreshRateHz = 0.0f).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `negative Hz — score is 0_95`() = runBlocking {
        val result = makeProbe(refreshRateHz = -10.0f).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `1000 Hz — score is 0_95`() = runBlocking {
        val result = makeProbe(refreshRateHz = 1000.0f).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `boundary 30 Hz — score is 0_7 phone-class fires, not implausible`() = runBlocking {
        // 30.0 is the boundary: `< 30` is implausible (strict), 30.0 itself
        // hits the 30-Hz-on-phone rule.
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `boundary 29_9 Hz — score is 0_95`() = runBlocking {
        val result = makeProbe(refreshRateHz = 29.9f).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `boundary 240 Hz — score is 0 (plausible)`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 240.0f,
            supportedModes = listOf(60.0f, 240.0f),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `boundary 241 Hz — score is 0_95`() = runBlocking {
        val result = makeProbe(refreshRateHz = 241.0f).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `implausible — pattern is implausible_hz`() = runBlocking {
        val result = makeProbe(refreshRateHz = 1000.0f).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(RefreshRateProbe.PATTERN_IMPLAUSIBLE, ev?.value)
    }

    // ── Single 60 Hz on 120Hz-capable flagship (0.85) ────────────────────────

    @Test
    fun `single 60Hz mode on Pixel 7 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz mode on Pixel 8 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 8 Pro"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz on Galaxy S24 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz on OnePlus 11 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz on Xiaomi 14 — score is 0_85`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Xiaomi 14"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz on Pixel 7 — pattern is stub_60_on_120hz_model`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(RefreshRateProbe.PATTERN_STUB_60_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `multi-mode 60+120 on Pixel 7 — does NOT fire (legit adaptive)`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f, 120.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `single 90Hz on Pixel 7 — does NOT fire (rule requires exactly 60_0)`() = runBlocking {
        // Unusual single-mode but not the 60.0 stub pattern.
        val result = makeProbe(
            refreshRateHz = 90.0f,
            supportedModes = listOf(90.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 7a (90Hz max) — score is 0 (negative exclusion guard)`() = runBlocking {
        // Anti-false-positive guard: Pixel 7a positively matches the
        // `"pixel 7"` substring but is 90 Hz max, not 120. Negative
        // exclusion via NON_120HZ_PIXEL_A_VARIANTS prevents the
        // single-60-mode rule from misfiring. Same negative-exclusion
        // pattern as rank-26 ANGLE / rank-31 LA-default / rank-33 max-charger.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 7a"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 6a (60Hz max) — score is 0 (negative exclusion guard)`() = runBlocking {
        // Pixel 6a is 60 Hz max — but `"pixel 6"` isn't in
        // KNOWN_120HZ_MODELS so the positive rule wouldn't fire here
        // regardless. The negative exclusion adds defense-in-depth:
        // if KNOWN_120HZ_MODELS is later extended to include "pixel 6"
        // (e.g. for Pixel 6 Pro coverage), the negative guard prevents
        // Pixel 6a from inheriting that false-positive.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 6a"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel 8a (120Hz max — verified) — fires 0_85 (positive match holds)`() = runBlocking {
        // Pixel 8a IS 120 Hz-capable per Google's spec page (Actua
        // display). Intentionally NOT in NON_120HZ_PIXEL_A_VARIANTS.
        // Single-60-mode on it IS a legitimate emulator tell.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 8a"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single 60Hz on Pixel 6 — does NOT fire (Pixel 6 not in list)`() = runBlocking {
        // Pixel 6 is 90 Hz capable, not 120 — intentionally NOT in
        // KNOWN_120HZ_MODELS. Single-60 is allowed here.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 6"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null supportedModes — does NOT fire single-mode rule`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = null,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty supportedModes — does NOT fire single-mode rule`() = runBlocking {
        // Empty list isn't size==1.
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = emptyList(),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.0, result.score)
    }

    // ── 30 Hz on phone-class (0.7) ───────────────────────────────────────────

    @Test
    fun `30 Hz on Pixel 7 — score is 0_7`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `30 Hz on Redmi (phone-class) — score is 0_7`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `30 Hz on tablet — score is 0 (not phone-class)`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `30 Hz on null model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `30 Hz on phone — pattern is 30_hz_on_phone`() = runBlocking {
        val result = makeProbe(
            refreshRateHz = 30.0f,
            supportedModes = listOf(30.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(RefreshRateProbe.PATTERN_30_HZ_ON_PHONE, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `implausible 1000 Hz on flagship — implausible wins over single-mode`() = runBlocking {
        // 1000 > 240 fires implausible (0.95) before single-mode (0.85)
        // could fire — but supportedModes=[1000.0] is single-mode too.
        val result = makeProbe(
            refreshRateHz = 1000.0f,
            supportedModes = listOf(1000.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "display.pattern" }
        assertEquals(RefreshRateProbe.PATTERN_IMPLAUSIBLE, ev?.value)
    }

    @Test
    fun `single-mode on flagship beats 30Hz on phone in cascade`() = runBlocking {
        // single-60 (0.85) fires before 30-hz-on-phone (0.7) when both
        // could match. (Hard to construct both predicates: 30 Hz IS not
        // 60.0, so single-mode-60 won't fire on 30Hz devices. Different
        // construction: refresh=60.0 single-mode AND model=Pixel 7 →
        // single-mode fires; refresh=30 wouldn't fire single-mode.)
        // This test confirms cascade order for the case where 60.0
        // refresh + flagship + supportedModes=[60.0].
        val result = makeProbe(
            refreshRateHz = 60.0f,
            supportedModes = listOf(60.0f),
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both surfaces readable — confidence 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only refresh rate readable — confidence 0_60`() = runBlocking {
        val result = makeProbe(supportedModes = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `only supportedModes readable — confidence 0_60`() = runBlocking {
        val result = makeProbe(refreshRateHz = null).run(fakeCtx())
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `neither readable — confidence 0_30`() = runBlocking {
        val result = makeProbe(refreshRateHz = null, supportedModes = null).run(fakeCtx())
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `neither readable — score is 0`() = runBlocking {
        val result = makeProbe(refreshRateHz = null, supportedModes = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_30`() = runBlocking {
        val result = RefreshRateProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `refreshRateHzSupplier throws — does not crash`() = runBlocking {
        val probe = RefreshRateProbe(
            refreshRateHzSupplier = { throw RuntimeException("simulated") },
            supportedModeHzSupplier = { listOf(60.0f, 120.0f) },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `supportedModeHzSupplier throws — does not crash`() = runBlocking {
        val probe = RefreshRateProbe(
            refreshRateHzSupplier = { 120.0f },
            supportedModeHzSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = RefreshRateProbe(
            refreshRateHzSupplier = { throw RuntimeException("a") },
            supportedModeHzSupplier = { throw RuntimeException("b") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = makeProbe().run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `is120HzCapableModel detects Pixel 7+`() {
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel 7"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel 7 Pro"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel 8"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel 8 Pro"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel Fold"))
    }

    @Test
    fun `is120HzCapableModel detects Galaxy S22+`() {
        assertTrue(RefreshRateProbe.is120HzCapableModel("SM-S901B"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("SM-S908"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("SM-S921"))
    }

    @Test
    fun `is120HzCapableModel detects OnePlus 10+`() {
        assertTrue(RefreshRateProbe.is120HzCapableModel("OnePlus 10"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("OnePlus 11"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("OnePlus 12"))
    }

    @Test
    fun `is120HzCapableModel detects Xiaomi 13+`() {
        assertTrue(RefreshRateProbe.is120HzCapableModel("Xiaomi 13"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Mi 13"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("Xiaomi 14"))
    }

    @Test
    fun `is120HzCapableModel case-insensitive`() {
        assertTrue(RefreshRateProbe.is120HzCapableModel("PIXEL 7"))
        assertTrue(RefreshRateProbe.is120HzCapableModel("pixel 7"))
    }

    @Test
    fun `is120HzCapableModel false for older Pixel`() {
        // Pixel 4a / 5 / 6 not in the 120 Hz list.
        assertFalse(RefreshRateProbe.is120HzCapableModel("Pixel 4a"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("Pixel 5"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("Pixel 6"))
    }

    @Test
    fun `is120HzCapableModel false for Pixel 6a 7a (negative exclusion)`() {
        // Pixel 6a / 7a positively match `"pixel 6"`/`"pixel 7"` substrings
        // but are EXCLUDED via NON_120HZ_PIXEL_A_VARIANTS. Anti-false-
        // positive guard.
        assertFalse(RefreshRateProbe.is120HzCapableModel("Pixel 6a"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("Pixel 7a"))
    }

    @Test
    fun `is120HzCapableModel true for Pixel 8a (NOT excluded — verified 120Hz)`() {
        // Per Google's spec page, Pixel 8a has 120 Hz Actua display.
        // Intentionally NOT in NON_120HZ_PIXEL_A_VARIANTS.
        assertTrue(RefreshRateProbe.is120HzCapableModel("Pixel 8a"))
    }

    @Test
    fun `NON_120HZ_PIXEL_A_VARIANTS list pinned (drift alarm)`() {
        // Future maintainer changing the negative-exclusion list must
        // update this test deliberately. New a-variants should be added
        // ONLY after verifying refresh-rate spec via Google's page.
        assertEquals(
            listOf("pixel 6a", "pixel 7a"),
            RefreshRateProbe.NON_120HZ_PIXEL_A_VARIANTS,
        )
    }

    @Test
    fun `is120HzCapableModel false for budget phones`() {
        assertFalse(RefreshRateProbe.is120HzCapableModel("Redmi Note 12"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("SM-A525F"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("OnePlus Nord"))
    }

    @Test
    fun `is120HzCapableModel false for tablets`() {
        assertFalse(RefreshRateProbe.is120HzCapableModel("Lenovo Tab P11"))
        assertFalse(RefreshRateProbe.is120HzCapableModel("Galaxy Tab S8"))
    }

    @Test
    fun `is120HzCapableModel false for empty and null`() {
        assertFalse(RefreshRateProbe.is120HzCapableModel(null))
        assertFalse(RefreshRateProbe.is120HzCapableModel(""))
    }

    // ── Cross-rank reuse invariants ──────────────────────────────────────────

    @Test
    fun `isPhoneClassModel delegates to rank-25 NetworkTypeProbe`() {
        // Drift-safe invariant for the 30-Hz-on-phone rule's gate.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Redmi Note 12"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
    }

    @Test
    fun `KNOWN_120HZ_MODELS list pinned (drift alarm)`() {
        // Future maintainer changing this list must update this test
        // deliberately. The list is intentionally NARROWER than rank-28
        // FLAGSHIP_MODEL_SUBSTRINGS (excludes Pixel 4a/5/6 which are not
        // 120 Hz-capable) and NARROWER than rank-45 KNOWN_BAROMETER_MODELS
        // (which includes Pixel 6+; this list excludes Pixel 6).
        val list = RefreshRateProbe.KNOWN_120HZ_MODELS
        assertTrue("pixel 7" in list)
        assertTrue("pixel 8" in list)
        assertTrue("sm-s901" in list)
        assertTrue("oneplus 11" in list)
        assertTrue("xiaomi 14" in list)
        // Pixel 6 NOT in list (90 Hz max, not 120).
        assertFalse("pixel 6" in list)
        // Pixel 4a/5a NOT in list (60 Hz max).
        assertFalse("pixel 4a" in list)
        assertFalse("pixel 5" in list)
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
                "display.refresh_rate_hz",
                "display.supported_modes_count",
                "display.supported_modes",
                "display.is_plausible_hz",
                "display.is_120hz_capable_model",
                "display.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `refresh_rate_hz evidence reflects supplier value`() = runBlocking {
        val result = makeProbe(refreshRateHz = 120.0f).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.refresh_rate_hz" }
        assertEquals("120.0", ev?.value)
    }

    @Test
    fun `null refresh_rate_hz — evidence is unavailable`() = runBlocking {
        val result = makeProbe(refreshRateHz = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.refresh_rate_hz" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `supported_modes_count evidence`() = runBlocking {
        val result = makeProbe(supportedModes = listOf(60.0f, 90.0f, 120.0f))
            .run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.supported_modes_count" }
        assertEquals("3", ev?.value)
    }

    @Test
    fun `supported_modes evidence shows comma-joined values`() = runBlocking {
        val result = makeProbe(supportedModes = listOf(60.0f, 90.0f, 120.0f))
            .run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.supported_modes" }
        assertEquals("60.0,90.0,120.0", ev?.value)
    }

    @Test
    fun `single supported mode — evidence shows 'single X'`() = runBlocking {
        val result = makeProbe(supportedModes = listOf(60.0f)).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.supported_modes" }
        assertEquals("single 60.0", ev?.value)
    }

    @Test
    fun `empty supported modes — evidence shows empty placeholder`() = runBlocking {
        val result = makeProbe(supportedModes = emptyList()).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.supported_modes" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `null supported modes — evidence is unavailable`() = runBlocking {
        val result = makeProbe(supportedModes = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.supported_modes" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `is_plausible_hz evidence — true for 120Hz`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.is_plausible_hz" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_plausible_hz evidence — false for 1000Hz`() = runBlocking {
        val result = makeProbe(refreshRateHz = 1000.0f).run(fakeCtx())
        val ev = result.evidence.find { it.key == "display.is_plausible_hz" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_120hz_capable_model evidence — true on Pixel 7`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "display.is_120hz_capable_model" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_120hz_capable_model evidence — false on Redmi`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Redmi Note 12"))
        val ev = result.evidence.find { it.key == "display.is_120hz_capable_model" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_120hz_capable_model evidence — unknown for null model`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = null))
        val ev = result.evidence.find { it.key == "display.is_120hz_capable_model" }
        assertEquals("unknown", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read Display.refreshRate + supportedModes; detect implausible " +
                "Hz values, single-60Hz-mode on 120Hz-capable flagships, and " +
                "stub patterns",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_refresh_rate`() {
        assertEquals("ui.refresh_rate", makeProbe().id)
    }

    @Test
    fun `probe rank is 46`() {
        assertEquals(46, makeProbe().rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-46 severity=low.
        assertEquals(ProbeSeverity.LOW, makeProbe().severity)
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

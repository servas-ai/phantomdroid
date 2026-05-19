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
 * Unit tests for InputMethodProbe (ui.input_method, inventory rank 58).
 */
class InputMethodProbeTest {

    private val probe = InputMethodProbe()

    private fun fakeCtx(
        defaultInputMethod: String? =
            "com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.libs.lm.LatinIME",
        secureThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? {
            if (secureThrows) throw RuntimeException("simulated EACCES")
            return if (key == InputMethodProbe.SETTING_DEFAULT_INPUT_METHOD)
                defaultInputMethod
            else
                null
        }
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
    fun `Gboard default IME — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "input_method.pattern" }
        assertEquals(InputMethodProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Samsung Keyboard — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.samsung.android.honeyboard/.service.HoneyBoardService")
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `SwiftKey IME — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.touchtype.swiftkey/com.touchtype.KeyboardService")
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator/test IME marker (1.0) ───────────────────────────────────────

    @Test
    fun `emulator in component name — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.android.emulator.ime/.EmulatorImeService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `test in component name — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.example.test_ime/.TestIme")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mock in component name — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.example.mockime/.MockImeService")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu marker case-insensitive — EMULATOR fires`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.android.EMULATOR.IME/.Service")
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `emu marker — pattern is emu_test_ime`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.android.emulator.ime/.Service")
        )
        val ev = result.evidence.find { it.key == "input_method.pattern" }
        assertEquals(InputMethodProbe.PATTERN_EMU_TEST_IME, ev?.value)
    }

    @Test
    fun `emu marker — has_emu_marker evidence is true`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.android.emulator.ime/.Service")
        )
        val ev = result.evidence.find { it.key == "input_method.has_emu_marker" }
        assertEquals("true", ev?.value)
    }

    // ── Empty/null IME (0.7) ─────────────────────────────────────────────────

    @Test
    fun `empty IME — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = ""))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `null IME — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = null))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `empty IME — pattern is empty_or_null_ime`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = ""))
        val ev = result.evidence.find { it.key == "input_method.pattern" }
        assertEquals(InputMethodProbe.PATTERN_EMPTY_OR_NULL_IME, ev?.value)
    }

    @Test
    fun `null IME — confidence is 0_50 (degraded)`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `empty IME — confidence is 0_95 (readable)`() = runBlocking {
        // Distinction: empty-string IS observed (accessor returned), so
        // confidence stays at NORMAL even though the value is anomalous.
        val result = probe.run(fakeCtx(defaultInputMethod = ""))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `empty IME — is_populated evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = ""))
        val ev = result.evidence.find { it.key == "input_method.is_populated" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `null IME — is_populated evidence is unknown`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = null))
        val ev = result.evidence.find { it.key == "input_method.is_populated" }
        assertEquals("unknown", ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `emu marker wins over empty (theoretical — empty doesn't have marker)`() = runBlocking {
        // Empty string can't have an emu substring, so this case
        // doesn't arise in practice. But the cascade ordering rule
        // is: emu_marker check comes before empty check, so even if
        // a hypothetical implementation produced a single-marker
        // empty-after-trim, the marker rule would fire first.
        val result = probe.run(fakeCtx(defaultInputMethod = "emulator"))
        assertEquals(1.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `querySettingSecure throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `accessor throws — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `accessor throws — score is 0_7 (treated as null)`() = runBlocking {
        // Accessor throw → null → null is empty-or-null → 0.7
        val result = probe.run(fakeCtx(secureThrows = true))
        assertEquals(0.7, result.score)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasEmuTestImeMarker detects all listed substrings`() {
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("com.android.emulator.ime"))
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("com.example.test_ime"))
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("com.example.mockime"))
    }

    @Test
    fun `hasEmuTestImeMarker case-insensitive`() {
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("EMULATOR"))
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("TEST"))
        assertTrue(InputMethodProbe.hasEmuTestImeMarker("MOCK"))
    }

    @Test
    fun `hasEmuTestImeMarker false for Gboard`() {
        assertFalse(
            InputMethodProbe.hasEmuTestImeMarker(
                "com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.libs.lm.LatinIME"
            )
        )
    }

    @Test
    fun `hasEmuTestImeMarker false for Samsung Keyboard`() {
        assertFalse(
            InputMethodProbe.hasEmuTestImeMarker(
                "com.samsung.android.honeyboard/.service.HoneyBoardService"
            )
        )
    }

    @Test
    fun `hasEmuTestImeMarker false for empty and null`() {
        assertFalse(InputMethodProbe.hasEmuTestImeMarker(null))
        assertFalse(InputMethodProbe.hasEmuTestImeMarker(""))
    }

    // ── Drift-alarm ──────────────────────────────────────────────────────────

    @Test
    fun `EMU_TEST_IME_SUBSTRINGS list pinned`() {
        val list = InputMethodProbe.EMU_TEST_IME_SUBSTRINGS
        assertTrue("emulator" in list)
        assertTrue("test" in list)
        assertTrue("mock" in list)
        assertEquals(3, list.size)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "secure.default_input_method",
                "input_method.has_emu_marker",
                "input_method.is_populated",
                "input_method.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `default_input_method evidence reflects accessor`() = runBlocking {
        val result = probe.run(
            fakeCtx(defaultInputMethod = "com.touchtype.swiftkey/.Service")
        )
        val ev = result.evidence.find { it.key == "secure.default_input_method" }
        assertEquals("com.touchtype.swiftkey/.Service", ev?.value)
    }

    @Test
    fun `default_input_method evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(defaultInputMethod = null))
        val ev = result.evidence.find { it.key == "secure.default_input_method" }
        assertEquals("<unavailable>", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Settings.Secure.default_input_method and detect " +
                "emulator/test/mock IME substrings or empty default IME " +
                "(real devices always populate this setting)",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_input_method`() {
        assertEquals("ui.input_method", probe.id)
    }

    @Test
    fun `probe rank is 58`() {
        assertEquals(58, probe.rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, probe.category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // inventory.yml rank-58 severity = trace.
        assertEquals(ProbeSeverity.TRACE, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
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

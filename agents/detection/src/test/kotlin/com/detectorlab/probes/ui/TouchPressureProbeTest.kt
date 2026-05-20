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
 * Unit tests for TouchPressureProbe (ui.touch_pressure, inventory rank 57).
 */
class TouchPressureProbeTest {

    private val probe = TouchPressureProbe()

    /**
     * Fake context that returns the specified property values and event
     * device presence set.
     */
    private fun fakeCtx(
        touchscreenHal: String? = "fts7250",
        roBootTouchscreen: String? = null,
        persistInputSynthetic: String? = null,
        eventDevicesPresent: Set<String> = setOf(
            "/dev/input/event0",
            "/dev/input/event1",
            "/dev/input/event2",
        ),
        propThrows: Boolean = false,
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propThrows) throw RuntimeException("simulated property failure")
            return when (key) {
                TouchPressureProbe.PROP_RO_HARDWARE_TOUCHSCREEN -> touchscreenHal
                TouchPressureProbe.PROP_RO_BOOT_TOUCHSCREEN -> roBootTouchscreen
                TouchPressureProbe.PROP_PERSIST_INPUT_SYNTHETIC -> persistInputSynthetic
                else -> null
            }
        }
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated EACCES")
            return path in eventDevicesPresent
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

    // ── Clean (real device) ──────────────────────────────────────────────────

    @Test
    fun `Pixel 7 fts7250 + 3 event devices — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Samsung stmfts + 4 event devices — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "stmfts",
                eventDevicesPresent = setOf(
                    "/dev/input/event0",
                    "/dev/input/event1",
                    "/dev/input/event2",
                    "/dev/input/event3",
                ),
            )
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `Qualcomm Synaptics DSX + 5 event devices — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "qcom_synaptics_dsx",
                eventDevicesPresent = setOf(
                    "/dev/input/event0",
                    "/dev/input/event1",
                    "/dev/input/event2",
                    "/dev/input/event3",
                    "/dev/input/event4",
                ),
            )
        )
        assertEquals(0.0, result.score)
    }

    // ── No touch HAL (0.95) ──────────────────────────────────────────────────

    @Test
    fun `empty touchscreen prop + no event devices — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = emptySet(),
            )
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `null touchscreen prop + no event devices — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = null,
                eventDevicesPresent = emptySet(),
            )
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `no touch HAL — pattern is no_touch_hal`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = emptySet(),
            )
        )
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_NO_TOUCH_HAL, ev?.value)
    }

    // ── Synthetic touch HAL (0.85) ───────────────────────────────────────────

    @Test
    fun `uinput touchscreen — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(touchscreenHal = "uinput_touch"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `virtual touchscreen — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(touchscreenHal = "virtual_touch_stub"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `UINPUT case-insensitive — fires`() = runBlocking {
        val result = probe.run(fakeCtx(touchscreenHal = "UINPUT_TOUCH"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `synthetic touch HAL — pattern is synthetic_touch_hal`() = runBlocking {
        val result = probe.run(fakeCtx(touchscreenHal = "uinput"))
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_SYNTHETIC_TOUCH_HAL, ev?.value)
    }

    // ── Spoof prop set (0.85) ────────────────────────────────────────────────

    @Test
    fun `ro_boot_touchscreen synthetic — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "fts7250",
                roBootTouchscreen = "synthetic",
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `persist_input_synthetic 1 — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "fts7250",
                persistInputSynthetic = "1",
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `spoof prop — pattern is spoof_prop_set`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "fts7250",
                persistInputSynthetic = "1",
            )
        )
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_SPOOF_PROP_SET, ev?.value)
    }

    // ── Single input device (0.70) ───────────────────────────────────────────

    @Test
    fun `only event0 + named touch HAL — score is 0_70`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "fts7250",
                eventDevicesPresent = setOf("/dev/input/event0"),
            )
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `single event device — pattern is single_input_device`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "fts7250",
                eventDevicesPresent = setOf("/dev/input/event0"),
            )
        )
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_SINGLE_INPUT_DEVICE, ev?.value)
    }

    // ── Ambiguous single device (0.50) ───────────────────────────────────────

    @Test
    fun `empty touch HAL + only event0 — score is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = setOf("/dev/input/event0"),
            )
        )
        assertEquals(0.50, result.score)
    }

    @Test
    fun `null touch HAL + only event0 — score is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = null,
                eventDevicesPresent = setOf("/dev/input/event0"),
            )
        )
        assertEquals(0.50, result.score)
    }

    @Test
    fun `ambiguous single device — pattern is ambiguous_single_device`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = setOf("/dev/input/event0"),
            )
        )
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_AMBIGUOUS_SINGLE_DEVICE, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `no_touch_hal beats spoof_prop when both apply`() = runBlocking {
        // touchscreen HAL empty + no events fires NO_TOUCH_HAL (0.95).
        // Even if spoof-marker prop is also set, NO_TOUCH_HAL wins per
        // cascade order.
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                persistInputSynthetic = "1",
                eventDevicesPresent = emptySet(),
            )
        )
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_NO_TOUCH_HAL, ev?.value)
    }

    @Test
    fun `synthetic_touch_hal beats spoof_prop when both apply`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "uinput",
                persistInputSynthetic = "1",
            )
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "touch.pattern" }
        assertEquals(TouchPressureProbe.PATTERN_SYNTHETIC_TOUCH_HAL, ev?.value)
    }

    // ── Confidence ───────────────────────────────────────────────────────────

    @Test
    fun `confidence is declarative 0_75`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.75, result.confidence)
    }

    @Test
    fun `confidence is declarative even on positive hit`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = emptySet(),
            )
        )
        assertEquals(0.75, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(propThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `fileExists throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasSyntheticTouchscreenMarker detects uinput and virtual`() {
        assertTrue(TouchPressureProbe.hasSyntheticTouchscreenMarker("uinput"))
        assertTrue(TouchPressureProbe.hasSyntheticTouchscreenMarker("virtual_touch"))
        assertTrue(TouchPressureProbe.hasSyntheticTouchscreenMarker("touch_uinput_stub"))
    }

    @Test
    fun `hasSyntheticTouchscreenMarker case-insensitive`() {
        assertTrue(TouchPressureProbe.hasSyntheticTouchscreenMarker("UINPUT"))
        assertTrue(TouchPressureProbe.hasSyntheticTouchscreenMarker("Virtual"))
    }

    @Test
    fun `hasSyntheticTouchscreenMarker false for real HAL names`() {
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker("fts7250"))
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker("stmfts"))
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker("qcom_synaptics_dsx"))
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker("goodix_ts"))
    }

    @Test
    fun `hasSyntheticTouchscreenMarker false for null and empty`() {
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker(null))
        assertFalse(TouchPressureProbe.hasSyntheticTouchscreenMarker(""))
    }

    @Test
    fun `hasSpoofProp detects each prop independently`() {
        assertTrue(TouchPressureProbe.hasSpoofProp("synthetic", null))
        assertTrue(TouchPressureProbe.hasSpoofProp(null, "1"))
        assertTrue(TouchPressureProbe.hasSpoofProp("synthetic", "1"))
    }

    @Test
    fun `hasSpoofProp false for clean values`() {
        assertFalse(TouchPressureProbe.hasSpoofProp(null, null))
        assertFalse(TouchPressureProbe.hasSpoofProp("fts7250", "0"))
        assertFalse(TouchPressureProbe.hasSpoofProp("", ""))
    }

    // ── Drift-alarm ──────────────────────────────────────────────────────────

    @Test
    fun `SYNTHETIC_TOUCHSCREEN_SUBSTRINGS list pinned`() {
        val list = TouchPressureProbe.SYNTHETIC_TOUCHSCREEN_SUBSTRINGS
        assertTrue("uinput" in list)
        assertTrue("virtual" in list)
        assertEquals(2, list.size)
    }

    @Test
    fun `EVENT_PATHS_ALL list pinned`() {
        val list = TouchPressureProbe.EVENT_PATHS_ALL
        assertEquals(5, list.size)
        assertEquals("/dev/input/event0", list[0])
        assertEquals("/dev/input/event4", list[4])
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
                "prop.ro.hardware.touchscreen",
                "prop.ro.boot.touchscreen",
                "prop.persist.input.synthetic",
                "touch.event_devices_present",
                "touch.has_synthetic_marker",
                "touch.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `touchscreen prop evidence reflects accessor`() = runBlocking {
        val result = probe.run(fakeCtx(touchscreenHal = "fts7250"))
        val ev = result.evidence.find { it.key == "prop.ro.hardware.touchscreen" }
        assertEquals("fts7250", ev?.value)
    }

    @Test
    fun `touchscreen prop evidence is unavailable when null`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = null,
                eventDevicesPresent = setOf(
                    "/dev/input/event0",
                    "/dev/input/event1",
                    "/dev/input/event2",
                ),
            )
        )
        val ev = result.evidence.find { it.key == "prop.ro.hardware.touchscreen" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `event_devices_present evidence reflects count`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                eventDevicesPresent = setOf(
                    "/dev/input/event0",
                    "/dev/input/event1",
                    "/dev/input/event2",
                    "/dev/input/event3",
                ),
            )
        )
        val ev = result.evidence.find { it.key == "touch.event_devices_present" }
        assertEquals("4", ev?.value)
    }

    @Test
    fun `event_devices_present zero when no events`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                touchscreenHal = "",
                eventDevicesPresent = emptySet(),
            )
        )
        val ev = result.evidence.find { it.key == "touch.event_devices_present" }
        assertEquals("0", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(TouchPressureProbe.METHOD, result.method)
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_touch_pressure`() {
        assertEquals("ui.touch_pressure", probe.id)
    }

    @Test
    fun `probe rank is 57`() {
        assertEquals(57, probe.rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, probe.category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // inventory.yml rank-57 severity = trace.
        assertEquals(ProbeSeverity.TRACE, probe.severity)
    }

    @Test
    fun `probe android layer is HARDWARE`() {
        // inventory.yml rank-57 android_layer = hardware.
        assertEquals(AndroidLayer.HARDWARE, probe.androidLayer)
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

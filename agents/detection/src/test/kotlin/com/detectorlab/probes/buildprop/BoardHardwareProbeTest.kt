package com.detectorlab.probes.buildprop

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
 * Unit tests for BoardHardwareProbe (buildprop.board_hardware, inventory rank 28).
 */
class BoardHardwareProbeTest {

    private val probe = BoardHardwareProbe()

    private fun fakeCtx(
        properties: Map<String, String?> = mapOf(
            BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "panther",
            BoardHardwareProbe.PROP_RO_BOARD_PLATFORM to "gs101",
            BoardHardwareProbe.PROP_RO_HARDWARE to "panther",
            BoardHardwareProbe.PROP_RO_HARDWARE_CHIPNAME to "Tensor G2",
            BoardHardwareProbe.PROP_RO_BOARD_MANUFACTURER to "Google",
            BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
        ),
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return properties[key]
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
    fun `Pixel 7 panther + Tensor G2 — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — is_emulator_marker evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "board.is_emulator_marker" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `Galaxy S24 e1q + kalama — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "e1q",
                    BoardHardwareProbe.PROP_RO_BOARD_PLATFORM to "kalama",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_HARDWARE_CHIPNAME to "SM8650",
                    BoardHardwareProbe.PROP_RO_BOARD_MANUFACTURER to "Qualcomm",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "SM-S921B",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Xiaomi MTK mt6789 — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "mt6789",
                    BoardHardwareProbe.PROP_RO_BOARD_PLATFORM to "mt6789",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "mt6789",
                    BoardHardwareProbe.PROP_RO_HARDWARE_CHIPNAME to "MT6789V/CZ",
                    BoardHardwareProbe.PROP_RO_BOARD_MANUFACTURER to "MediaTek",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Redmi Note 12",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Emulator markers in ro.hardware (1.0) ────────────────────────────────

    @Test
    fun `ro_hardware goldfish — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "goldfish",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Android SDK built for x86",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware goldfish_arm64 — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "goldfish_arm64",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish_arm64",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "sdk_gphone64_arm64",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware ranchu — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "ranchu",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "ranchu",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware redroid — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "redroid",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "redroid",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware vbox86 (Genymotion) — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "vbox86p",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "vbox86",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware cancro test image — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "cancro",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "cancro",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware uppercase GOLDFISH — case-insensitive match`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_HARDWARE to "GOLDFISH",
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "panther",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_hardware goldfish — pattern is emu_hardware`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish_arm64",
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "panther",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_EMU_HARDWARE, ev?.value)
    }

    // ── Emulator markers in ro.product.board (1.0) ───────────────────────────

    @Test
    fun `ro_product_board goldfish without hardware marker — fires emu_board`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "goldfish_arm64",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",  // wrapper-masked
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_EMU_BOARD, ev?.value)
    }

    @Test
    fun `board ranchu — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "ranchu",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(1.0, result.score)
    }

    // ── Empty board on flagship model (0.95) ─────────────────────────────────

    @Test
    fun `empty board on Pixel 7 claim — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `null board on Pixel 8 Pro — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to null,
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 8 Pro",
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `empty board on Galaxy S24 — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "SM-S921B",
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `empty board on OnePlus 11 — score is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "OnePlus 11",
                ),
            ),
        )
        assertEquals(0.95, result.score)
    }

    @Test
    fun `empty board on flagship — pattern is empty_board_on_flagship`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_EMPTY_BOARD_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `empty board on budget Redmi — score is 0 (not flagship)`() = runBlocking {
        // Budget phones with stripped board names are NOT the load-bearing
        // signal — only flagship claims are.
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "mt6789",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Redmi Note 12",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty board on tablet — score is 0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Lenovo Tab P11",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty board null model — score is 0 (cannot claim flagship)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to null,
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `goldfish hardware + empty board + flagship model — emu_hardware wins`() = runBlocking {
        // All three conditions match. emu_hardware (1.0) wins by source order
        // — most-specific signal beats the impossibility heuristic.
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish_arm64",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_EMU_HARDWARE, ev?.value)
    }

    @Test
    fun `goldfish board + clean hardware + flagship — emu_board wins`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "goldfish",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "qcom",
                    BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
                ),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "board.pattern" }
        assertEquals(BoardHardwareProbe.PATTERN_EMU_BOARD, ev?.value)
    }

    @Test
    fun `goldfish hardware AND board — emu_hardware wins (source order)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "goldfish",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish_arm64",
                ),
            ),
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "board.pattern" }
        // emu_hardware is checked first in the cascade.
        assertEquals(BoardHardwareProbe.PATTERN_EMU_HARDWARE, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `5 of 5 properties readable — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `2 of 5 properties readable — confidence is 0_95`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "panther",
                    BoardHardwareProbe.PROP_RO_HARDWARE to "panther",
                ),
            ),
        )
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `1 of 5 properties readable — confidence is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "panther",
                ),
            ),
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `0 of 5 properties readable — confidence is 0_30`() = runBlocking {
        val result = probe.run(fakeCtx(emptyMap()))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `all properties null — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(emptyMap()))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all properties empty strings — score is 0 (no model, can't claim flagship)`() =
        runBlocking {
            val result = probe.run(
                fakeCtx(
                    mapOf(
                        BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                        BoardHardwareProbe.PROP_RO_BOARD_PLATFORM to "",
                        BoardHardwareProbe.PROP_RO_HARDWARE to "",
                        BoardHardwareProbe.PROP_RO_HARDWARE_CHIPNAME to "",
                        BoardHardwareProbe.PROP_RO_BOARD_MANUFACTURER to "",
                        BoardHardwareProbe.PROP_RO_PRODUCT_MODEL to "",
                    ),
                ),
            )
            assertEquals(0.0, result.score)
        }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `getSystemProperty throws on all keys — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `getSystemProperty throws on one key — others still considered`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? =
                if (key == BoardHardwareProbe.PROP_RO_PRODUCT_BOARD)
                    throw RuntimeException("simulated")
                else if (key == BoardHardwareProbe.PROP_RO_HARDWARE) "goldfish"
                else null
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
        val result = probe.run(ctx)
        assertFalse(result.failed)
        // ro.hardware = "goldfish" → emu_hardware fires at 1.0.
        assertEquals(1.0, result.score)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasEmulatorMarker detects all 5 substrings`() {
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("goldfish"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("goldfish_arm64"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("ranchu"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("redroid"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("vbox86p"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("cancro"))
    }

    @Test
    fun `hasEmulatorMarker case-insensitive`() {
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("GOLDFISH"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("Ranchu"))
        assertTrue(BoardHardwareProbe.hasEmulatorMarker("ReDroid"))
    }

    @Test
    fun `hasEmulatorMarker false for real OEM boards`() {
        // Panther, kalama, e1q, mt6789, qcom — all real codenames.
        listOf("panther", "kalama", "e1q", "mt6789", "qcom", "sm8475", "tangorpro").forEach {
            assertFalse(BoardHardwareProbe.hasEmulatorMarker(it), "$it should not fire")
        }
    }

    @Test
    fun `hasEmulatorMarker false for null and empty`() {
        assertFalse(BoardHardwareProbe.hasEmulatorMarker(null))
        assertFalse(BoardHardwareProbe.hasEmulatorMarker(""))
    }

    @Test
    fun `isFlagshipModel detects Pixel family`() {
        assertTrue(BoardHardwareProbe.isFlagshipModel("Pixel 6"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("Pixel 7"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("Pixel 8 Pro"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("Pixel 4a"))  // budget but family
    }

    @Test
    fun `isFlagshipModel detects Galaxy family via SM- codes`() {
        assertTrue(BoardHardwareProbe.isFlagshipModel("SM-S908B"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("SM-S921"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("SM-N976B"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("SM-F926B"))
    }

    @Test
    fun `isFlagshipModel detects OnePlus`() {
        assertTrue(BoardHardwareProbe.isFlagshipModel("OnePlus 9"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("OnePlus 11"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("OnePlus 12"))
    }

    @Test
    fun `isFlagshipModel case-insensitive`() {
        assertTrue(BoardHardwareProbe.isFlagshipModel("PIXEL 7"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("pixel 7"))
    }

    @Test
    fun `isFlagshipModel false for non-flagship Android`() {
        assertFalse(BoardHardwareProbe.isFlagshipModel("Redmi Note 12"))
        assertFalse(BoardHardwareProbe.isFlagshipModel("Moto G Power"))
        assertFalse(BoardHardwareProbe.isFlagshipModel("Lenovo Tab P11"))
        assertFalse(BoardHardwareProbe.isFlagshipModel("GenericDevice42"))
    }

    @Test
    fun `isFlagshipModel false for null and empty`() {
        assertFalse(BoardHardwareProbe.isFlagshipModel(null))
        assertFalse(BoardHardwareProbe.isFlagshipModel(""))
    }

    @Test
    fun `EMULATOR_BOARD_MARKERS list contains expected 5 entries`() {
        // Drift alarm: if anyone changes this list, the test forces a
        // deliberate update.
        assertEquals(
            listOf("goldfish", "ranchu", "redroid", "vbox86", "cancro"),
            BoardHardwareProbe.EMULATOR_BOARD_MARKERS,
        )
    }

    @Test
    fun `FLAGSHIP_MODEL_SUBSTRINGS list contains expected entries`() {
        assertEquals(
            listOf("pixel ", "sm-s", "sm-n", "sm-f", "oneplus"),
            BoardHardwareProbe.FLAGSHIP_MODEL_SUBSTRINGS,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "ro.product.board",
                "ro.board.platform",
                "ro.hardware",
                "ro.hardware.chipname",
                "ro.board.manufacturer",
                "board.is_emulator_marker",
                "board.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `board evidence reflects property value`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.product.board" }
        assertEquals("panther", ev?.value)
    }

    @Test
    fun `empty board evidence shows empty placeholder`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(
                    BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "ro.product.board" }
        assertEquals("<empty>", ev?.value)
    }

    @Test
    fun `null board evidence shows unreadable`() = runBlocking {
        val result = probe.run(fakeCtx(emptyMap()))
        val ev = result.evidence.find { it.key == "ro.product.board" }
        assertEquals("<unreadable>", ev?.value)
    }

    @Test
    fun `platform evidence reflects property value`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.board.platform" }
        assertEquals("gs101", ev?.value)
    }

    @Test
    fun `hardware evidence reflects property value`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.hardware" }
        assertEquals("panther", ev?.value)
    }

    @Test
    fun `chipname evidence reflects property value`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.hardware.chipname" }
        assertEquals("Tensor G2", ev?.value)
    }

    @Test
    fun `manufacturer evidence reflects property value`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.board.manufacturer" }
        assertEquals("Google", ev?.value)
    }

    @Test
    fun `is_emulator_marker true when hardware matches`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(BoardHardwareProbe.PROP_RO_HARDWARE to "goldfish"),
            ),
        )
        val ev = result.evidence.find { it.key == "board.is_emulator_marker" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_emulator_marker true when board matches`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                mapOf(BoardHardwareProbe.PROP_RO_PRODUCT_BOARD to "vbox86p"),
            ),
        )
        val ev = result.evidence.find { it.key == "board.is_emulator_marker" }
        assertEquals("true", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read ro.product.board + ro.board.platform + ro.hardware + " +
                "ro.hardware.chipname + ro.board.manufacturer; detect " +
                "goldfish/ranchu/vbox86/redroid emulator markers; cross-check " +
                "board codename vs claimed model",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is buildprop_board_hardware`() {
        assertEquals("buildprop.board_hardware", probe.id)
    }

    @Test
    fun `probe rank is 28`() {
        assertEquals(28, probe.rank)
    }

    @Test
    fun `probe category is BUILDPROP`() {
        assertEquals(ProbeCategory.BUILDPROP, probe.category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-28 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, probe.severity)
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

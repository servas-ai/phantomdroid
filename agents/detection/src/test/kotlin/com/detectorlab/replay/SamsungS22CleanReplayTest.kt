// agents/detection/src/test/kotlin/com/detectorlab/replay/SamsungS22CleanReplayTest.kt
//
// NEGATIVE-CLASS end-to-end test — the false-positive floor for the probe
// inventory against a Samsung Galaxy S22 (SM-S901B, Exynos 2200, Android 14,
// One UI 6). Counterpart to `Pixel7CleanReplayTest` which asserts the same
// against a Google Pixel 7.
//
// Together the Pixel 7 + Samsung S22 negative-class anchors verify that
// emulator-classification probes catch GENUINE emulator markers (goldfish/
// ranchu/qemu/generic/vbox/redroid) rather than overfitting to "the device
// is a Pixel". A probe that ONLY scores 0.0 on Pixel data but scores >0.0
// on Samsung data is overfit to the Pixel substring; a probe that scores
// 0.0 on both is responding to actual emulator markers.
//
// Feeds the live-shape Samsung S22 retail snapshot through the SAME
// production probe pipeline used in `Pixel7CleanReplayTest` and asserts
// every emulator-classification probe scores **0.0 clean**. Any probe that
// fires non-zero against a factory-clean S22 indicates a false-positive
// regression that needs to be fixed at the probe layer (NOT by adjusting
// this snapshot — the snapshot is ground truth).

package com.detectorlab.replay

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.buildprop.BuildFingerprintProbe
import com.detectorlab.probes.buildprop.ModelBrandManufacturerProbe
import com.detectorlab.probes.buildprop.TagsAndTypeProbe
import com.detectorlab.probes.emulator.CpuAbiProbe
import com.detectorlab.probes.emulator.ProcVersionProbe
import com.detectorlab.probes.env.BootloaderProbe
import com.detectorlab.probes.root.SuDetectionProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SamsungS22CleanReplayTest {

    private val ctx = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)

    // ── rank 1 BuildFingerprint — canonical Samsung S22 retail fingerprint ────

    @Test
    fun `rank 1 BuildFingerprint scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = BuildFingerprintProbe().run(ctx)
        assertFalse(result.failed, "BuildFingerprint should not fail on a valid snapshot")
        assertEquals(
            0.0, result.score,
            "samsung/r0qxxx retail fingerprint must NOT contain any emulator marker " +
                "(redroid/generic/ranchu/vbox/goldfish)",
        )
    }

    // ── rank 3 SuDetection — no su binary, no magisk, no superuser package ────

    @Test
    fun `rank 3 SuDetection scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = SuDetectionProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "factory Samsung S22 ships no su binary, no magisk artifact, no superuser pkg " +
                "(Knox warranty bit untripped)",
        )
    }

    // ── rank 7 TagsAndType — release-keys + user (canonical production) ───────

    @Test
    fun `rank 7 TagsAndType scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = TagsAndTypeProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "release-keys + user is the AOSP production-build convention Samsung ships",
        )
    }

    // ── rank 9 ModelBrandManufacturer — samsung/SM-S901B/samsung (aligned) ────

    @Test
    fun `rank 9 ModelBrandManufacturer scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = ModelBrandManufacturerProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "brand=samsung, manufacturer=samsung align case-insensitively; " +
                "model `SM-S901B` contains no AVD/emu keyword — proves the probe " +
                "is not overfit to the `pixel` substring",
        )
    }

    // ── rank 27 CpuAbi — pure ARM64 (no x86, no Houdini) ──────────────────────

    @Test
    fun `rank 27 CpuAbi scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = CpuAbiProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "arm64-v8a primary + ARM-only abilist is the canonical real-ARM-phone shape " +
                "(identical on Pixel 7 + Samsung S22 — fixed by AOSP CDD)",
        )
    }

    // ── rank 28 BoardHardware — `exynos2200` is a real Samsung SoC platform ───

    @Test
    fun `rank 28 BoardHardware scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = BoardHardwareProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "ro.hardware=exynos2200 and ro.product.board=exynos2200 are the real Samsung " +
                "SoC platform names, not in the emulator marker list " +
                "(goldfish/ranchu/redroid/vbox86/cancro)",
        )
    }

    // ── rank 30 ProcVersion — Samsung kernel build banner ─────────────────────

    @Test
    fun `rank 30 ProcVersion scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = ProcVersionProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "real Samsung kernel banner: sec-build-host + clang compiler + " +
                "Exynos-2200 build tag — no qemu/ranchu/goldfish marker",
        )
    }

    // ── rank 13 Bootloader — AVB green, flash locked, Knox untripped ──────────

    @Test
    fun `rank 13 Bootloader scores 0_0 on Samsung S22 clean snapshot`() = runBlocking {
        val result = BootloaderProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "vbmeta=locked + verifiedbootstate=green + flash.locked=1 + ro.secure=1 + " +
                "ro.debuggable=0 + ro.boot.warranty_bit=0 (Knox untripped factory state)",
        )
    }

    // ── Full ProbeRunner E2E — every probe must classify as clean ─────────────

    @Test
    fun `full ProbeRunner reports zero emulator-hits on Samsung S22 clean snapshot`() =
        runBlocking {
            val probes = listOf(
                BuildFingerprintProbe(),
                TagsAndTypeProbe(),
                ModelBrandManufacturerProbe(),
                CpuAbiProbe(),
                BoardHardwareProbe(),
                ProcVersionProbe(),
                SuDetectionProbe(),
                BootloaderProbe(),
            )
            val runner = ProbeRunner(
                probes = probes,
                ctx = ctx,
                appVersion = "0.1.0",
                deviceLabel = SamsungS22CleanSnapshot.SNAPSHOT.label,
            )
            val report = runner.runAll()
            assertNotNull(report, "ProbeRunner produced a report")
            assertEquals(
                probes.size, report.probes.size,
                "every probe produced a record (no silent drops)",
            )

            // Zero emulator-hits at ANY threshold — the inventory's false-
            // positive floor against a factory-clean retail Samsung flagship.
            val emulatorHits = report.probes.count { it.score >= 0.5 }
            assertEquals(
                0, emulatorHits,
                "expected 0 probes scoring >=0.5 on a factory-clean Samsung S22, " +
                    "got $emulatorHits " +
                    "(records: ${report.probes.map { "${it.id}=${it.score}" }})",
            )

            // Stronger assertion: every score must be strictly below 0.5.
            // The records list above already covers this via `emulatorHits == 0`,
            // but asserting score-by-score gives a precise failure message
            // identifying which probe regressed.
            report.probes.forEach { record ->
                assertTrue(
                    record.score < 0.5,
                    "probe ${record.id} scored ${record.score} on a clean Samsung S22 — " +
                        "FALSE POSITIVE. Snapshot is ground truth; fix the probe. " +
                        "(If this probe ONLY fires on non-Pixel data, it is overfit " +
                        "to the `pixel` substring rather than catching emulator markers.)",
                )
            }
        }
}

// agents/detection/src/test/kotlin/com/detectorlab/replay/Pixel7CleanReplayTest.kt
//
// NEGATIVE-CLASS end-to-end test — the false-positive floor for the probe
// inventory. Counterpart to `SnapshotReplayE2ETest` (which pins the
// true-positive ceiling against `RedroidV12Snapshot`).
//
// Feeds the live-shape Pixel 7 retail snapshot through the SAME production
// probe pipeline used in `SnapshotReplayE2ETest` and asserts every
// emulator-classification probe scores **0.0 clean**. Any probe that fires
// non-zero against a factory-clean Pixel 7 indicates a false-positive
// regression that needs to be fixed at the probe layer (NOT by adjusting
// this snapshot — the snapshot is ground truth).
//
// The snapshot in `Pixel7CleanSnapshot.kt` documents the exact property
// values being replayed and why each one is the correct negative-class
// ground truth.

package com.detectorlab.replay

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.replay.Pixel7CleanSnapshot
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

class Pixel7CleanReplayTest {

    private val ctx = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)

    // ── rank 1 BuildFingerprint — canonical Pixel 7 retail fingerprint ────────

    @Test
    fun `rank 1 BuildFingerprint scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = BuildFingerprintProbe().run(ctx)
        assertFalse(result.failed, "BuildFingerprint should not fail on a valid snapshot")
        assertEquals(
            0.0, result.score,
            "google/panther retail fingerprint must NOT contain any emulator marker " +
                "(redroid/generic/ranchu/vbox/goldfish)",
        )
    }

    // ── rank 3 SuDetection — no su binary, no magisk, no superuser package ────

    @Test
    fun `rank 3 SuDetection scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = SuDetectionProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "factory Pixel 7 ships no su binary, no magisk artifact, no superuser pkg",
        )
    }

    // ── rank 7 TagsAndType — release-keys + user (canonical production) ───────

    @Test
    fun `rank 7 TagsAndType scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = TagsAndTypeProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "release-keys + user is the AOSP production-build convention",
        )
    }

    // ── rank 9 ModelBrandManufacturer — google/Pixel 7/Google (aligned) ───────

    @Test
    fun `rank 9 ModelBrandManufacturer scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = ModelBrandManufacturerProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "brand=google, manufacturer=Google align case-insensitively; " +
                "model `Pixel 7` contains no AVD/emu keyword",
        )
    }

    // ── rank 27 CpuAbi — pure ARM64 (no x86, no Houdini) ──────────────────────

    @Test
    fun `rank 27 CpuAbi scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = CpuAbiProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "arm64-v8a primary + ARM-only abilist is the canonical real-ARM-phone shape",
        )
    }

    // ── rank 28 BoardHardware — `panther` is a real Pixel 7 board codename ────

    @Test
    fun `rank 28 BoardHardware scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = BoardHardwareProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "ro.hardware=panther and ro.product.board=panther are the real Tensor-G2 " +
                "platform codenames, not in the emulator marker list " +
                "(goldfish/ranchu/redroid/vbox86/cancro)",
        )
    }

    // ── rank 30 ProcVersion — Android-13 kleaf-built kernel + clang ───────────

    @Test
    fun `rank 30 ProcVersion scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = ProcVersionProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "real Pixel kernel banner: android13-gki tag, kleaf build host, clang compiler",
        )
    }

    // ── rank 13 Bootloader — AVB green, flash locked, no dev-build leak ───────

    @Test
    fun `rank 13 Bootloader scores 0_0 on Pixel 7 clean snapshot`() = runBlocking {
        val result = BootloaderProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            0.0, result.score,
            "vbmeta=green + flash.locked=1 + ro.secure=1 + ro.debuggable=0 + " +
                "ro.oem_unlock_supported=0 (factory state, never put into dev mode)",
        )
    }

    // ── Full ProbeRunner E2E — every probe must classify as clean ─────────────

    @Test
    fun `full ProbeRunner reports zero emulator-hits on Pixel 7 clean snapshot`() =
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
                deviceLabel = Pixel7CleanSnapshot.SNAPSHOT.label,
            )
            val report = runner.runAll()
            assertNotNull(report, "ProbeRunner produced a report")
            assertEquals(
                probes.size, report.probes.size,
                "every probe produced a record (no silent drops)",
            )

            // Zero emulator-hits at ANY threshold — the inventory's false-
            // positive floor against a factory-clean retail device.
            val emulatorHits = report.probes.count { it.score >= 0.5 }
            assertEquals(
                0, emulatorHits,
                "expected 0 probes scoring >=0.5 on a factory-clean Pixel 7, " +
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
                    "probe ${record.id} scored ${record.score} on a clean Pixel 7 — " +
                        "FALSE POSITIVE. Snapshot is ground truth; fix the probe.",
                )
            }
        }
}

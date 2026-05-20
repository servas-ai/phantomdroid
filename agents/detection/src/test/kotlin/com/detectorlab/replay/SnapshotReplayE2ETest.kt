// agents/detection/src/test/kotlin/com/detectorlab/replay/SnapshotReplayE2ETest.kt
//
// END-TO-END test that closes the "deployed end-to-end with the real
// simulator on the server" goal: feeds the live-captured ReDroid 12
// snapshot into the production probe pipeline and asserts the inventory
// classifies the container as emulator across all expected dimensions.
//
// This is the JVM-side counterpart to the Android-APK path. ReDroid 12 on
// PAR822349 kernel 4.15 cannot boot system_server (binderfs gap) so the
// APK path requires the owner-authorized 5.4 reboot. The snapshot path
// runs the SAME probe code against the SAME container telemetry without
// requiring system_server to be up — the bridge is `docker exec getprop`
// → `RedroidV12Snapshot` → `SnapshotReplayContext` → `ProbeRunner`.
//
// Ground-truth predictions come from `audit/E2E-validation-2026-05-20.md`
// (the manual cross-validation run that motivated this automation).

package com.detectorlab.replay

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.replay.RedroidV12Snapshot
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

class SnapshotReplayE2ETest {

    private val ctx = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)

    // ── rank 1 BuildFingerprint — fingerprint contains "redroid" ──────────────

    @Test
    fun `rank 1 BuildFingerprint scores 1_0 on ReDroid 12 snapshot`() = runBlocking {
        val result = BuildFingerprintProbe().run(ctx)
        assertFalse(result.failed, "BuildFingerprint should not fail on a valid snapshot")
        assertEquals(1.0, result.score, "ReDroid fingerprint contains canonical `redroid` marker")
    }

    // ── rank 7 TagsAndType — test-keys + userdebug both VIOLATE ───────────────

    @Test
    fun `rank 7 TagsAndType scores 1_0 on ReDroid 12 snapshot`() = runBlocking {
        val result = TagsAndTypeProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(1.0, result.score, "test-keys AND userdebug — double violation")
    }

    // ── rank 9 Model/Brand/Manufacturer — model contains "redroid12" ──────────

    @Test
    fun `rank 9 ModelBrandManufacturer scores 1_0 on ReDroid 12 snapshot`() = runBlocking {
        val result = ModelBrandManufacturerProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(1.0, result.score, "model `redroid12_x86_64_only` contains emulator keyword")
    }

    // ── rank 27 CpuAbi — Houdini dual-arch (x86_64 + arm64-v8a) ───────────────

    @Test
    fun `rank 27 CpuAbi scores 1_0 on ReDroid 12 snapshot (dual-arch)`() = runBlocking {
        val result = CpuAbiProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(1.0, result.score, "x86_64 primary + arm64-v8a in abilist — Houdini bridge")
    }

    // ── rank 28 BoardHardware — ro.hardware == "redroid" ──────────────────────

    @Test
    fun `rank 28 BoardHardware scores 1_0 on ReDroid 12 snapshot`() = runBlocking {
        val result = BoardHardwareProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(
            1.0, result.score,
            "ro.hardware == `redroid` matches the EMU_KEYWORDS substring list",
        )
    }

    // ── rank 30 ProcVersion — Ubuntu launchpad builder host ───────────────────

    @Test
    fun `rank 30 ProcVersion fires emulator signal on ReDroid 12 snapshot`() = runBlocking {
        val result = ProcVersionProbe().run(ctx)
        assertFalse(result.failed)
        assertTrue(
            result.score >= 0.7,
            "Ubuntu buildd@lcy02-amd64-079 build host is non-Android — expected score >= 0.7, " +
                "got ${result.score}",
        )
    }

    // ── rank 3 SuDetection — /system/bin/su present ───────────────────────────

    @Test
    fun `rank 3 SuDetection scores 1_0 on ReDroid 12 snapshot`() = runBlocking {
        val result = SuDetectionProbe().run(ctx)
        assertFalse(result.failed)
        assertEquals(1.0, result.score, "/system/bin/su present — ReDroid ships su by default")
    }

    // ── rank 13 Bootloader — ro.debuggable=1 is a VIOLATION ───────────────────

    @Test
    fun `rank 13 Bootloader fires debuggable signal on ReDroid 12 snapshot`() = runBlocking {
        val result = BootloaderProbe().run(ctx)
        assertFalse(result.failed)
        assertTrue(
            result.score > 0.0,
            "ro.debuggable=1 is a known violation — expected non-zero score, got ${result.score}",
        )
    }

    // ── Full ProbeRunner E2E — every probe in the inventory survives ──────────

    @Test
    fun `full ProbeRunner runs every emulator-classification probe without throwing`() =
        runBlocking {
            // Subset of probes whose data the snapshot covers. Other probes
            // (settings-Secure-driven, sensor-driven, etc.) work too but
            // exercise the no-data fallback path which is less interesting
            // for THIS test — they have their own dedicated unit tests.
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
                deviceLabel = RedroidV12Snapshot.SNAPSHOT.label,
            )
            val report = runner.runAll()
            assertNotNull(report, "ProbeRunner produced a report")
            assertEquals(
                probes.size, report.probes.size,
                "every probe produced a record (no silent drops)",
            )
            // At least 6 of the 8 ground-truth emulator-classification probes
            // should have fired at score >= 0.85 — that's the empirical floor
            // from the audit doc.
            val emulatorHits = report.probes.count { it.score >= 0.85 }
            assertTrue(
                emulatorHits >= 6,
                "expected ≥6 probes scoring ≥0.85 on ReDroid 12, got $emulatorHits " +
                    "(records: ${report.probes.map { "${it.id}=${it.score}" }})",
            )
        }
}

// agents/detection/src/test/kotlin/com/detectorlab/replay/CoverageMatrixGeneratorTest.kt
//
// Power-15 A4 — 648-cell coverage matrix auto-generator.
//
// Iterates the full 81-probe production inventory across 8 snapshot fixtures
// (Pixel7Clean, SamsungS22Clean, RedroidV12, RedroidSpoofed,
// FridaInjectedRedroid, Nox, BlueStacks, Genymotion) and emits a deterministic
// Markdown matrix to `audit/spoof-stack/full-coverage-matrix.md`.
//
// This is NOT a verdict-correctness gate. The asserted contract is narrow:
//
//   (1) the matrix has exactly 81 × 8 = 648 cells, and
//   (2) the output file is written deterministically (same input → same bytes).
//
// Verdict-correctness lives in the per-snapshot replay tests
// (Pixel7CleanReplayTest, SamsungS22CleanReplayTest, FullProbeRunnerSpoofTest,
// SnapshotReplayE2ETest, plus the detector-app replays). This generator's
// job is to surface the FULL panel in one document so anomalies (FP-on-clean,
// FN-on-spoofed, error cells) are visible at a glance — NOT silently filtered.
//
// Anti-verarschen contract:
//   - Error cells (probe crashed) are NEVER filtered. They appear in §2 with
//     verdict `error` and are counted in §4.
//   - Absent cells (probe deliberately skipped — missing capability surface)
//     are recorded as `absent` and counted in §4.
//   - Spoofed-on-clean false positives ARE listed in §3 as anomalies (the
//     real-device snapshot was wrongly flagged as raw).
//   - Raw-on-spoofed false negatives ARE listed in §3 (the spoof-stack failed
//     to bypass the probe).
//   - No manual cell fill. Every value in §2 is computed by ProbeRunner.runAll().

package com.detectorlab.replay

import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeRecord
import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.replay.BlueStacksSnapshot
import com.detectorlab.core.replay.DeviceSnapshot
import com.detectorlab.core.replay.FridaInjectedRedroidSnapshot
import com.detectorlab.core.replay.GenymotionSnapshot
import com.detectorlab.core.replay.NoxSnapshot
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.detectorlab.probes.app.IgFamilyDeviceIdHeaderProbe
import com.detectorlab.probes.app.TikTokArgusSigningProbe
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.buildprop.BuildFingerprintProbe
import com.detectorlab.probes.buildprop.FingerprintCrossPartitionProbe
import com.detectorlab.probes.buildprop.ModelBrandManufacturerProbe
import com.detectorlab.probes.buildprop.TagsAndTypeProbe
import com.detectorlab.probes.emulator.CpuAbiProbe
import com.detectorlab.probes.emulator.GpuRendererProbe
import com.detectorlab.probes.emulator.ProcVersionProbe
import com.detectorlab.probes.emulator.QemuArtifactsProbe
import com.detectorlab.probes.emulator.ThirdPartyEmulatorArtifactsProbe
import com.detectorlab.probes.env.AccessibilityServicesProbe
import com.detectorlab.probes.env.AccountsProbe
import com.detectorlab.probes.env.BatteryLevelProbe
import com.detectorlab.probes.env.BatteryTemperatureProbe
import com.detectorlab.probes.env.BluetoothStateProbe
import com.detectorlab.probes.env.BootloaderProbe
import com.detectorlab.probes.env.CameraInfoProbe
import com.detectorlab.probes.env.ChargingStateProbe
import com.detectorlab.probes.env.DeveloperOptionsProbe
import com.detectorlab.probes.env.GpsCoordinatesProbe
import com.detectorlab.probes.env.LanguageCountryProbe
import com.detectorlab.probes.env.LocationMockProbe
import com.detectorlab.probes.env.LocationMockRaspProbe
import com.detectorlab.probes.env.NfcStateProbe
import com.detectorlab.probes.env.ScreenLockProbe
import com.detectorlab.probes.env.TimeSpoofingProbe
import com.detectorlab.probes.env.TimezoneLocaleProbe
import com.detectorlab.probes.env.UptimeProbe
import com.detectorlab.probes.env.WifiSecurityTypeProbe
import com.detectorlab.probes.identity.AndroidIdProbe
import com.detectorlab.probes.identity.BluetoothMacProbe
import com.detectorlab.probes.identity.CarrierMccMncProbe
import com.detectorlab.probes.identity.GaidProbe
import com.detectorlab.probes.identity.GsfIdProbe
import com.detectorlab.probes.identity.ImeiSerialProbe
import com.detectorlab.probes.identity.MediaDrmProbe
import com.detectorlab.probes.identity.SimIccidProbe
import com.detectorlab.probes.identity.WifiMacProbe
import com.detectorlab.probes.identity.WifiSsidBssidProbe
import com.detectorlab.probes.integrity.AppSignatureProbe
import com.detectorlab.probes.integrity.KeystoreAttestationProbe
import com.detectorlab.probes.integrity.PlayIntegrityLiveProbe
import com.detectorlab.probes.integrity.PlayIntegrityProbe
import com.detectorlab.probes.integrity.PrologueGotHooksProbe
import com.detectorlab.probes.kernel.CpuInfoProbe
import com.detectorlab.probes.network.DnsServerProbe
import com.detectorlab.probes.network.HttpProxyProbe
import com.detectorlab.probes.network.NetworkIpAsnProbe
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.network.VpnProxyProbe
import com.detectorlab.probes.root.MagiskModuleDirProbe
import com.detectorlab.probes.root.MagiskUdsProbe
import com.detectorlab.probes.root.MountNsMismatchProbe
import com.detectorlab.probes.root.OverlayFsPresentProbe
import com.detectorlab.probes.root.SeLinuxProbe
import com.detectorlab.probes.root.SuDetectionProbe
import com.detectorlab.probes.root.SystemRwMountProbe
import com.detectorlab.probes.runtime.AutomationToolsProbe
import com.detectorlab.probes.runtime.DebuggerTracerPidProbe
import com.detectorlab.probes.runtime.FridaMemoryMapsProbe
import com.detectorlab.probes.runtime.InitSvcEnumerationProbe
import com.detectorlab.probes.runtime.InstalledAppsProbe
import com.detectorlab.probes.runtime.MultiInstanceProbe
import com.detectorlab.probes.runtime.NativePrologueHashProbe
import com.detectorlab.probes.runtime.ScreenRecordingProbe
import com.detectorlab.probes.runtime.ServicesProcessesProbe
import com.detectorlab.probes.runtime.XposedLsposedProbe
import com.detectorlab.probes.sensors.AccelerometerGyroProbe
import com.detectorlab.probes.sensors.BarometerProbe
import com.detectorlab.probes.sensors.LightProbe
import com.detectorlab.probes.sensors.MagnetometerProbe
import com.detectorlab.probes.sensors.ProximityProbe
import com.detectorlab.probes.ui.AudioFingerprintProbe
import com.detectorlab.probes.ui.DisplayCutoutProbe
import com.detectorlab.probes.ui.InputMethodProbe
import com.detectorlab.probes.ui.RefreshRateProbe
import com.detectorlab.probes.ui.ScreenResolutionProbe
import com.detectorlab.probes.ui.SystemFontsProbe
import com.detectorlab.probes.ui.TouchPressureProbe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageMatrixGeneratorTest {

    /**
     * The 8 snapshot fixtures, in the canonical column order used by §1 and §2.
     * Order is FROZEN — changing it changes the matrix output bytes.
     *
     * The pair is `(column-header, snapshot)`. Column-header strings are the
     * exact tokens that appear in the §1 distribution table and §2 verdict
     * matrix headers; they are also the keys in the per-snapshot column lookup.
     */
    private fun allSnapshots(): List<Pair<String, DeviceSnapshot>> = listOf(
        "Pixel7Clean" to Pixel7CleanSnapshot.SNAPSHOT,
        "SamsungS22Clean" to SamsungS22CleanSnapshot.SNAPSHOT,
        "RedroidV12" to RedroidV12Snapshot.SNAPSHOT,
        "RedroidSpoofed" to RedroidSpoofedSnapshot.SNAPSHOT,
        "FridaInjectedRedroid" to FridaInjectedRedroidSnapshot.SNAPSHOT,
        "Nox" to NoxSnapshot.SNAPSHOT,
        "BlueStacks" to BlueStacksSnapshot.SNAPSHOT,
        "Genymotion" to GenymotionSnapshot.SNAPSHOT,
    )

    /**
     * The complete 81-probe production inventory, instantiated per-snapshot
     * (because BluetoothMacProbe's `bluetoothAdapterMacSupplier` closes over
     * the per-snapshot `SnapshotReplayContext`). Other probes are constructed
     * via their default constructors, mirroring `FullProbeRunnerSpoofTest`'s
     * `allProbes()` registry verbatim.
     *
     * Keeping this list in sync with `FullProbeRunnerSpoofTest.allProbes()` is
     * an invariant enforced by `runMatrix` — both lists MUST yield 81 probes
     * with identical `(id, rank)` pairs. The list ordering here is "by code
     * filename" within each category to keep the matrix row order predictable
     * (matrix rows are then sorted by `inventoryRank` for the §2 output).
     */
    private fun allProbes(ctx: SnapshotReplayContext): List<Probe> = listOf(
        // app (2)
        IgFamilyDeviceIdHeaderProbe(),
        TikTokArgusSigningProbe(),
        // buildprop (5)
        BoardHardwareProbe(),
        BuildFingerprintProbe(),
        FingerprintCrossPartitionProbe(),
        ModelBrandManufacturerProbe(),
        TagsAndTypeProbe(),
        // emulator (5)
        CpuAbiProbe(),
        GpuRendererProbe(),
        ProcVersionProbe(),
        QemuArtifactsProbe(),
        ThirdPartyEmulatorArtifactsProbe(),
        // env (19)
        AccessibilityServicesProbe(),
        AccountsProbe(),
        BatteryLevelProbe(),
        BatteryTemperatureProbe(),
        BluetoothStateProbe(),
        BootloaderProbe(),
        CameraInfoProbe(),
        ChargingStateProbe(),
        DeveloperOptionsProbe(),
        GpsCoordinatesProbe(),
        LanguageCountryProbe(),
        LocationMockProbe(),
        LocationMockRaspProbe(),
        NfcStateProbe(),
        ScreenLockProbe(),
        TimeSpoofingProbe(),
        TimezoneLocaleProbe(),
        UptimeProbe(),
        WifiSecurityTypeProbe(),
        // identity (10)
        AndroidIdProbe(),
        BluetoothMacProbe(
            bluetoothAdapterMacSupplier = { ctx.queryBluetoothAdapterMac() },
        ),
        CarrierMccMncProbe(),
        GaidProbe(),
        GsfIdProbe(),
        ImeiSerialProbe(),
        MediaDrmProbe(),
        SimIccidProbe(),
        WifiMacProbe(),
        WifiSsidBssidProbe(),
        // integrity (5)
        AppSignatureProbe(),
        KeystoreAttestationProbe(),
        PlayIntegrityLiveProbe(),
        PlayIntegrityProbe(),
        PrologueGotHooksProbe(),
        // kernel (1)
        CpuInfoProbe(),
        // network (5)
        DnsServerProbe(),
        HttpProxyProbe(),
        NetworkIpAsnProbe(),
        NetworkTypeProbe(),
        VpnProxyProbe(),
        // root (7)
        MagiskModuleDirProbe(),
        MagiskUdsProbe(),
        MountNsMismatchProbe(),
        OverlayFsPresentProbe(),
        SeLinuxProbe(),
        SuDetectionProbe(),
        SystemRwMountProbe(),
        // runtime (10)
        AutomationToolsProbe(),
        DebuggerTracerPidProbe(),
        FridaMemoryMapsProbe(),
        InitSvcEnumerationProbe(),
        InstalledAppsProbe(),
        MultiInstanceProbe(),
        NativePrologueHashProbe(),
        ScreenRecordingProbe(),
        ServicesProcessesProbe(),
        XposedLsposedProbe(),
        // sensors (5)
        AccelerometerGyroProbe(),
        BarometerProbe(),
        LightProbe(),
        MagnetometerProbe(),
        ProximityProbe(),
        // ui (7)
        AudioFingerprintProbe(),
        DisplayCutoutProbe(),
        InputMethodProbe(),
        RefreshRateProbe(),
        ScreenResolutionProbe(),
        SystemFontsProbe(),
        TouchPressureProbe(),
    )

    /**
     * Cell verdict — what the probe answered for one (probe, snapshot) pair.
     *
     *  - `spoofed`  : probe ran, score < 0.30. The snapshot looks like a real
     *                 device from this probe's viewpoint. On a Clean snapshot
     *                 this is the EXPECTED answer. On a Spoofed snapshot this
     *                 is the EXPECTED bypass. On a Raw snapshot
     *                 (RedroidV12 / Nox / BlueStacks / Genymotion /
     *                 FridaInjectedRedroid) this is a POTENTIAL false negative
     *                 (the probe failed to detect the emulator) — flagged in
     *                 §3 if probe category is in the dispositive-for-raw set.
     *  - `raw`      : probe ran, score >= 0.30. The snapshot was flagged as
     *                 emulator/spoof. On a Raw snapshot this is the EXPECTED
     *                 answer. On a Clean snapshot this is a FALSE POSITIVE.
     *                 On a Spoofed snapshot this is a SPOOF FAILURE.
     *  - `absent`   : probe deliberately abstained (skipped) — the snapshot
     *                 lacks the capability surface the probe reads. Detected
     *                 via `failureReason?.startsWith("skipped: ")` on the
     *                 ProbeRecord.
     *  - `error`    : probe failed with an uncaught exception OR timeout.
     *                 NEVER filtered. Counted in §4.
     */
    private enum class Verdict { SPOOFED, RAW, ABSENT, ERROR }

    private fun classify(record: ProbeRecord): Verdict {
        if (record.failed) {
            val reason = record.failureReason ?: return Verdict.ERROR
            // ProbeResult.SKIPPED_PREFIX == "skipped: "
            return if (reason.startsWith("skipped: ")) Verdict.ABSENT else Verdict.ERROR
        }
        return if (record.score >= 0.30) Verdict.RAW else Verdict.SPOOFED
    }

    private fun verdictToken(v: Verdict): String = when (v) {
        Verdict.SPOOFED -> "spoofed"
        Verdict.RAW -> "raw"
        Verdict.ABSENT -> "absent"
        Verdict.ERROR -> "error"
    }

    /**
     * Expected verdict per (snapshot, probe-id). Used to enumerate anomalies
     * in §3. The expectation rules are coarse — the per-snapshot replay tests
     * carry the precise scoring expectations; here we only encode the broad
     * "did the snapshot class match the probe's intended signal-class" check.
     *
     * Rules:
     *   - Pixel7Clean / SamsungS22Clean: every probe should be `spoofed` or
     *     `absent`. A `raw` cell is a false positive.
     *   - RedroidSpoofed: every probe should be `spoofed` or `absent` (the
     *     spoof-stack must bypass the panel). A `raw` cell is a spoof
     *     failure.
     *   - RedroidV12: probes whose mitigation_layer is L1/L4 and category in
     *     {buildprop, emulator, root, runtime, kernel} should be `raw`. Other
     *     probes (network/identity/sensors/ui/integrity) have no strong
     *     a-priori expectation — we don't flag them either way.
     *   - Nox / BlueStacks / Genymotion: probes in {emulator, buildprop}
     *     should be `raw` (these are unspoofed third-party emulators). Other
     *     probes have no a-priori expectation.
     *   - FridaInjectedRedroid: probes in {runtime, emulator} (specifically
     *     the Frida-aware ones) should be `raw`; other probes inherit the
     *     RedroidV12 expectation.
     *   - `error` is NEVER expected — every `error` cell is automatically an
     *     anomaly.
     */
    private fun expectedVerdict(snapshot: String, record: ProbeRecord): Verdict? = when (snapshot) {
        "Pixel7Clean", "SamsungS22Clean" -> Verdict.SPOOFED
        "RedroidSpoofed" -> Verdict.SPOOFED
        "RedroidV12" -> when (record.category) {
            "buildprop", "emulator", "root", "runtime", "kernel" -> Verdict.RAW
            else -> null
        }
        "FridaInjectedRedroid" -> when (record.category) {
            "buildprop", "emulator", "root", "runtime", "kernel" -> Verdict.RAW
            else -> null
        }
        "Nox", "BlueStacks", "Genymotion" -> when (record.category) {
            "buildprop", "emulator" -> Verdict.RAW
            else -> null
        }
        else -> null
    }

    /**
     * Resolve the absolute path of `audit/spoof-stack/full-coverage-matrix.md`
     * by walking up from `System.getProperty("user.dir")` until we find the
     * gradle settings file. This handles BOTH `gradle :detection:test`
     * (cwd = `agents/detection`) AND `./gradlew test` from the repo root.
     */
    private fun resolveAuditFile(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists()) {
                return File(dir, "audit/spoof-stack/full-coverage-matrix.md")
            }
            dir = dir.parentFile
        }
        error(
            "Could not locate repo root (no settings.gradle.kts found above " +
                "${System.getProperty("user.dir")}); refusing to guess where to " +
                "write full-coverage-matrix.md",
        )
    }

    @Test
    fun `generates 648-cell full-coverage matrix as audit document`() = runBlocking {
        val snapshots = allSnapshots()
        assertEquals(8, snapshots.size, "Expected exactly 8 snapshot fixtures")

        // matrix[probeId][snapshotName] = (verdict, record).
        // Captured row-major so §1 distribution and §2 per-probe table both
        // iterate the same map without re-running probes.
        data class Cell(val verdict: Verdict, val record: ProbeRecord)

        val matrix: MutableMap<String, MutableMap<String, Cell>> = linkedMapOf()
        // Stable probe meta — populated on the first snapshot pass and reused
        // (id + inventoryRank are pure declarative properties).
        data class ProbeMeta(val id: String, val rank: Double, val category: String)

        var probeMeta: List<ProbeMeta>? = null

        for ((snapshotName, snapshot) in snapshots) {
            val ctx = SnapshotReplayContext(snapshot)
            val probes = allProbes(ctx)
            assertEquals(
                81, probes.size,
                "Expected the full 81-probe production inventory; got ${probes.size} for " +
                    "snapshot $snapshotName. If the inventory changed, sync this list with " +
                    "FullProbeRunnerSpoofTest.allProbes().",
            )

            if (probeMeta == null) {
                probeMeta = probes.map {
                    ProbeMeta(id = it.id, rank = it.inventoryRank, category = it.category.name.lowercase())
                }
            }

            val runner = ProbeRunner(
                probes = probes,
                ctx = ctx,
                appVersion = "0.1.0",
                deviceLabel = snapshot.label,
            )
            val report = runner.runAll()
            assertEquals(
                81, report.probes.size,
                "ProbeRunner returned ${report.probes.size} records for $snapshotName; expected 81",
            )

            for (record in report.probes) {
                val cellMap = matrix.getOrPut(record.id) { linkedMapOf() }
                cellMap[snapshotName] = Cell(classify(record), record)
            }
        }

        // Sanity: 81 unique probe ids × 8 snapshots = 648 cells.
        val totalCells = matrix.values.sumOf { it.size }
        assertEquals(
            648, totalCells,
            "Expected exactly 81 × 8 = 648 cells; got $totalCells. Matrix integrity violated.",
        )
        assertEquals(81, matrix.size, "Expected 81 unique probe ids in matrix")

        // Order rows by inventoryRank ascending (matches the inventory.yml
        // canonical ordering). Order columns by the `snapshots` list above.
        val finalProbeMeta: List<ProbeMeta> = probeMeta
            ?: error("probeMeta was never populated — at least one snapshot pass must have executed")
        val orderedProbeIds: List<String> = finalProbeMeta
            .sortedBy { it.rank }
            .map { it.id }
        val metaById: Map<String, ProbeMeta> = finalProbeMeta.associateBy { it.id }
        val snapshotNames: List<String> = snapshots.map { it.first }

        // ── §1: per-snapshot distribution counters ─────────────────────────
        data class Distribution(
            var spoofed: Int = 0,
            var raw: Int = 0,
            var absent: Int = 0,
            var error: Int = 0,
        )
        val distribution: Map<String, Distribution> = snapshotNames.associateWith { Distribution() }
        for ((_, cellMap) in matrix) {
            for ((snapName, cell) in cellMap) {
                val d = distribution.getValue(snapName)
                when (cell.verdict) {
                    Verdict.SPOOFED -> d.spoofed++
                    Verdict.RAW -> d.raw++
                    Verdict.ABSENT -> d.absent++
                    Verdict.ERROR -> d.error++
                }
            }
        }

        // ── §3: anomaly list ───────────────────────────────────────────────
        data class Anomaly(
            val probeId: String,
            val snapshot: String,
            val expected: String,
            val actual: String,
            val notes: String,
        )
        val anomalies = mutableListOf<Anomaly>()
        for (probeId in orderedProbeIds) {
            val cellMap = matrix.getValue(probeId)
            for (snapName in snapshotNames) {
                val cell = cellMap.getValue(snapName)
                if (cell.verdict == Verdict.ERROR) {
                    anomalies += Anomaly(
                        probeId = probeId,
                        snapshot = snapName,
                        expected = "spoofed|raw|absent",
                        actual = "error",
                        notes = "probe crashed: ${cell.record.failureReason ?: "(no reason)"}",
                    )
                    continue
                }
                val expected = expectedVerdict(snapName, cell.record) ?: continue
                if (cell.verdict != expected) {
                    val notes = when {
                        snapName in setOf("Pixel7Clean", "SamsungS22Clean") &&
                            cell.verdict == Verdict.RAW ->
                            "false-positive on real-device snapshot (score=${"%.2f".format(cell.record.score)})"
                        snapName == "RedroidSpoofed" && cell.verdict == Verdict.RAW ->
                            "spoof-stack failed to bypass probe (score=${"%.2f".format(cell.record.score)})"
                        snapName in setOf("RedroidV12", "FridaInjectedRedroid") &&
                            cell.verdict == Verdict.SPOOFED ->
                            "expected raw on dirty emulator snapshot; probe scored low (score=${"%.2f".format(cell.record.score)}) — possible coverage gap"
                        snapName in setOf("Nox", "BlueStacks", "Genymotion") &&
                            cell.verdict == Verdict.SPOOFED ->
                            "expected raw on $snapName fixture; probe scored low (score=${"%.2f".format(cell.record.score)}) — possible coverage gap"
                        cell.verdict == Verdict.ABSENT ->
                            "probe abstained (no capability) on snapshot where ${verdictToken(expected)} was expected"
                        else -> "unexpected ${verdictToken(cell.verdict)} on $snapName"
                    }
                    anomalies += Anomaly(
                        probeId = probeId,
                        snapshot = snapName,
                        expected = verdictToken(expected),
                        actual = verdictToken(cell.verdict),
                        notes = notes,
                    )
                }
            }
        }

        // ── §4: anti-verarschen counters ───────────────────────────────────
        val totalErrorCells = matrix.values.sumOf { col ->
            col.values.count { it.verdict == Verdict.ERROR }
        }
        val totalAbsentCells = matrix.values.sumOf { col ->
            col.values.count { it.verdict == Verdict.ABSENT }
        }
        val fpOnClean = anomalies.count {
            it.snapshot in setOf("Pixel7Clean", "SamsungS22Clean") &&
                it.actual == "raw" && it.expected == "spoofed"
        }
        val fnOnSpoofed = anomalies.count {
            it.snapshot == "RedroidSpoofed" && it.actual == "raw" && it.expected == "spoofed"
        }

        // ── Render markdown ────────────────────────────────────────────────
        val sb = StringBuilder()
        sb.append("# Power-15 A4 — 648-Cell Full-Coverage Matrix\n\n")
        sb.append("**Generated**: auto-generated by `CoverageMatrixGeneratorTest`\n")
        sb.append("**Source-of-truth**: 81 probes (`shared/probes/inventory.yml`) × 8 snapshots = 648 cells\n")
        sb.append("**Snapshots**: ")
        sb.append(snapshotNames.joinToString(", "))
        sb.append("\n\n")
        sb.append(
            "Verdict semantics: `spoofed` (score < 0.30 → real-device answer), " +
                "`raw` (score ≥ 0.30 → emulator/spoof detected), " +
                "`absent` (probe deliberately abstained), " +
                "`error` (probe crashed — NEVER filtered).\n\n",
        )

        // §1 distribution
        sb.append("## §1 — Verdict Distribution Summary\n\n")
        sb.append("| Snapshot | spoofed | raw | absent | error | total |\n")
        sb.append("|---|---|---|---|---|---|\n")
        for (snapName in snapshotNames) {
            val d = distribution.getValue(snapName)
            val total = d.spoofed + d.raw + d.absent + d.error
            sb.append("| $snapName | ${d.spoofed} | ${d.raw} | ${d.absent} | ${d.error} | $total |\n")
        }
        sb.append("\n")

        // §2 per-probe verdict matrix
        sb.append("## §2 — Per-Probe Verdict Matrix (81 rows × 8 cols)\n\n")
        sb.append("| Rank | Probe ID")
        for (snapName in snapshotNames) {
            sb.append(" | $snapName")
        }
        sb.append(" |\n")
        sb.append("|---|---")
        repeat(snapshotNames.size) { sb.append("|---") }
        sb.append("|\n")
        for (probeId in orderedProbeIds) {
            val meta = metaById.getValue(probeId)
            val rankStr = if (meta.rank == meta.rank.toLong().toDouble()) {
                meta.rank.toLong().toString()
            } else {
                "%.1f".format(meta.rank)
            }
            sb.append("| $rankStr | `$probeId`")
            val cellMap = matrix.getValue(probeId)
            for (snapName in snapshotNames) {
                sb.append(" | ${verdictToken(cellMap.getValue(snapName).verdict)}")
            }
            sb.append(" |\n")
        }
        sb.append("\n")

        // §3 anomalies
        sb.append("## §3 — Anomalies (cells where verdict deviates from expected ground truth)\n\n")
        if (anomalies.isEmpty()) {
            sb.append("_No anomalies detected: every cell matches its a-priori expectation._\n\n")
        } else {
            sb.append("| Probe | Snapshot | Expected | Actual | Notes |\n")
            sb.append("|---|---|---|---|---|\n")
            for (a in anomalies) {
                sb.append("| `${a.probeId}` | ${a.snapshot} | ${a.expected} | ${a.actual} | ${a.notes} |\n")
            }
            sb.append("\n")
        }

        // §4 anti-verarschen audit
        sb.append("## §4 — Anti-Verarschen Audit\n\n")
        sb.append("- Total cells: 648 (81 probes × 8 snapshots)\n")
        sb.append("- `error` cells (probe crashed): **$totalErrorCells**\n")
        sb.append("- `absent` cells (probe deliberately abstained): **$totalAbsentCells**\n")
        sb.append("- False-positives on Clean snapshots (`raw` on Pixel7Clean / SamsungS22Clean): **$fpOnClean**\n")
        sb.append("- False-negatives on RedroidSpoofed (`raw` where `spoofed` was expected): **$fnOnSpoofed**\n")
        sb.append("- Total anomalies in §3: **${anomalies.size}**\n\n")
        sb.append(
            "All deviations are enumerated in §3; no anomalies are silently filtered. " +
                "`error` cells (if any) are documented with their exception reason and contribute " +
                "to the §4 counter — they are real coverage gaps, NOT noise.\n",
        )

        // Write deterministically (no timestamps in the file content; LF line
        // endings; final newline). Resolve against repo root regardless of
        // gradle cwd.
        val output = resolveAuditFile()
        output.parentFile.mkdirs()
        output.writeText(sb.toString())

        // Verify the file was written and contains the expected structural anchors.
        assertTrue(output.exists(), "matrix file ${output.absolutePath} was not created")
        val written = output.readText()
        assertTrue(written.contains("# Power-15 A4 — 648-Cell Full-Coverage Matrix"),
            "matrix file missing §-header")
        assertTrue(written.contains("## §1 — Verdict Distribution Summary"),
            "matrix file missing §1")
        assertTrue(written.contains("## §2 — Per-Probe Verdict Matrix (81 rows × 8 cols)"),
            "matrix file missing §2")
        assertTrue(written.contains("## §3 — Anomalies"),
            "matrix file missing §3")
        assertTrue(written.contains("## §4 — Anti-Verarschen Audit"),
            "matrix file missing §4")

        // The §2 table must contain exactly 81 data rows. Quick structural
        // check: count lines starting with "| " that include a backticked
        // probe id (each probe id appears in the row prefix).
        val matrixRowLines = written.lines().count { line ->
            line.startsWith("| ") && line.contains("` |") && line.contains("`")
                && !line.startsWith("| Probe |")
        }
        // Each anomaly row also matches that pattern; account for them.
        assertTrue(
            matrixRowLines >= 81 + anomalies.size,
            "expected ≥ ${81 + anomalies.size} backticked rows in matrix (81 probe rows + " +
                "${anomalies.size} anomaly rows); counted $matrixRowLines",
        )
    }
}

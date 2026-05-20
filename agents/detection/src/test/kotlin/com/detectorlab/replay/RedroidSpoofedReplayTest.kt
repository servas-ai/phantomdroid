// agents/detection/src/test/kotlin/com/detectorlab/replay/RedroidSpoofedReplayTest.kt
//
// OFFENSIVE validation test — runs the Iteration-1 RedroidSpoofedSnapshot
// through the full production probe pipeline and asserts each masked probe
// scores at-or-below the post-spoof threshold (<= 0.3, the SUSPICIOUS upper
// edge). Counterpart to:
//   - SnapshotReplayE2ETest        (true-positive ceiling vs. UN-spoofed)
//   - Pixel7CleanReplayTest        (false-positive floor vs. retail Pixel)
//   - RedroidSpoofedReplayTest     (THIS) — spoof-effectiveness gate
//
// Layout mirrors SnapshotReplayE2ETest: one @Test per masked probe with a
// score-ceiling assertion, plus a full-runner aggregate test that asserts
// the spoof drops the device into CLEAN/SUSPICIOUS (not DETECTED).
//
// Residual-surface assertions: explicitly pin the predicted Iter-2 attack
// surface scores so a regression in either probe code OR snapshot data
// surfaces immediately as a test diff, not silent drift.

package com.detectorlab.replay

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.ReportCategory
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.buildprop.BuildFingerprintProbe
import com.detectorlab.probes.buildprop.ModelBrandManufacturerProbe
import com.detectorlab.probes.buildprop.TagsAndTypeProbe
import com.detectorlab.probes.emulator.CpuAbiProbe
import com.detectorlab.probes.emulator.ProcVersionProbe
import com.detectorlab.probes.env.BootloaderProbe
import com.detectorlab.probes.env.DeveloperOptionsProbe
import com.detectorlab.probes.integrity.PlayIntegrityProbe
import com.detectorlab.probes.root.SuDetectionProbe
import com.detectorlab.probes.sensors.AccelerometerGyroProbe
import com.detectorlab.probes.sensors.BarometerProbe
import com.detectorlab.probes.sensors.LightProbe
import com.detectorlab.probes.sensors.MagnetometerProbe
import com.detectorlab.probes.sensors.ProximityProbe
import com.detectorlab.probes.env.LocationMockRaspProbe
import com.detectorlab.probes.identity.AndroidIdProbe
import com.detectorlab.probes.identity.BluetoothMacProbe
import com.detectorlab.probes.identity.ImeiSerialProbe
import com.detectorlab.probes.identity.SimIccidProbe
import com.detectorlab.probes.root.SeLinuxProbe
import com.detectorlab.probes.runtime.DebuggerTracerPidProbe
import com.detectorlab.probes.ui.InputMethodProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedroidSpoofedReplayTest {

    private val ctx = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    // ── Masked probes: each must score <= 0.3 (SUSPICIOUS upper edge) ───────

    @Test
    fun `rank 1 BuildFingerprint masked to clean on v1 spoof`() = runBlocking {
        val r = BuildFingerprintProbe().run(ctx)
        assertFalse(r.failed, "BuildFingerprint should run cleanly on v1 spoof")
        assertTrue(r.score <= 0.3, "rank 1 expected <=0.3 after fingerprint+brand+tags spoof, got ${r.score}")
        // Stronger pin: predicted exactly 0.0 (no rule in the cascade fires)
        assertEquals(0.0, r.score, "rank 1 predicted 0.0 on Pixel-7-shape fingerprint")
    }

    @Test
    fun `rank 3 SuDetection masked to 0_0 by removing su binary`() = runBlocking {
        val r = SuDetectionProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 3 predicted 0.0 with /system/bin/su removed from existingFiles")
    }

    @Test
    fun `rank 7 TagsAndType masked by release-keys + user`() = runBlocking {
        val r = TagsAndTypeProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 7 predicted 0.0 with release-keys + user")
    }

    @Test
    fun `rank 9 ModelBrandManufacturer masked by Pixel 7 panther triple`() = runBlocking {
        val r = ModelBrandManufacturerProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 9 predicted 0.0 with (Pixel 7, google, Google)")
    }

    @Test
    fun `rank 13 Bootloader masked by green+locked+debuggable=0`() = runBlocking {
        val r = BootloaderProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 13 predicted 0.0 with vbmeta=green + flash.locked=1 + debuggable=0")
    }

    @Test
    fun `rank 19 DeveloperOptions masked by debuggable=0`() = runBlocking {
        val r = DeveloperOptionsProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 19 predicted 0.0 with ro.debuggable=0 (the only firing rule on baseline)")
    }

    @Test
    fun `rank 27 CpuAbi masked by pure ARM64 abilist`() = runBlocking {
        val r = CpuAbiProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 27 predicted 0.0 with arm64-v8a primary + ARM-only abilist (no Houdini)")
    }

    @Test
    fun `rank 28 BoardHardware masked by ro_hardware=panther`() = runBlocking {
        val r = BoardHardwareProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 28 predicted 0.0 with ro.hardware=panther + board=panther")
    }

    @Test
    fun `rank 30 ProcVersion masked by Pixel-shape kernel banner`() = runBlocking {
        val r = ProcVersionProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 30 predicted 0.0 with android13-gki kleaf clang banner")
    }

    @Test
    fun `rank 71 PlayIntegrity masked by release-keys + green + non-emu fingerprint`() = runBlocking {
        val r = PlayIntegrityProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(0.0, r.score, "rank 71 predicted 0.0 — basicFail=false (release-keys + debuggable=0), deviceFail=false (no emu marker)")
    }

    // ── Full ProbeRunner — aggregate must NOT be DETECTED ───────────────────

    @Test
    fun `full runner — v1 spoof aggregate is not DETECTED on 10-probe panel`() = runBlocking {
        val probes = listOf(
            BuildFingerprintProbe(),
            SuDetectionProbe(),
            TagsAndTypeProbe(),
            ModelBrandManufacturerProbe(),
            BootloaderProbe(),
            DeveloperOptionsProbe(),
            CpuAbiProbe(),
            BoardHardwareProbe(),
            ProcVersionProbe(),
            PlayIntegrityProbe(),
        )
        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = "0.1.0",
            deviceLabel = RedroidSpoofedSnapshot.SNAPSHOT.label,
        )
        val report = runner.runAll()
        assertNotNull(report)
        assertEquals(probes.size, report.probes.size, "every probe produced a record (no silent drops)")

        // All 10 high-priority probes must close to <= 0.3
        report.probes.forEach { record ->
            assertTrue(
                record.score <= 0.3,
                "probe ${record.id} scored ${record.score} on v1 spoof — Iter-1 close target missed " +
                    "(expected <=0.3; baseline was >=0.7). Evidence: ${record.evidence.map { "${it.key}=${it.value}" }}"
            )
        }

        // Aggregate: must NOT be DETECTED. Baseline ReDroid lands in DETECTED;
        // a successful spoof must drop the aggregate one or two bands.
        assertNotEquals(
            ReportCategory.DETECTED, report.aggregate.category,
            "v1 spoof aggregate=${report.aggregate.category} weightedScore=${report.aggregate.weightedScore} " +
                "criticalFailures=${report.aggregate.criticalFailures} — Iter-1 spoof failed to leave the DETECTED band",
        )
        assertEquals(
            0, report.aggregate.criticalFailures,
            "v1 spoof must produce zero critical failures (rank 1..10 with score >=0.7); " +
                "got ${report.aggregate.criticalFailures}",
        )
    }

    // ── Iter-2 sensor probes: closed by populating sensorTypes ──────────────
    //
    // v1 regression: spoofing the model to `Pixel 7` turned
    // NetworkTypeProbe.isPhoneClassModel(model) == true, which activated the
    // "missing-on-phone" rule for sensors that returned empty from the
    // previous EmptySensorManagerView. v2 populates DeviceSnapshot.sensorTypes
    // with the 6 canonical Pixel-7 sensors {1,2,4,5,6,8}, closing all four
    // sensor-family fires and flipping rank-24 from SCORE_NO_SIGNAL to
    // SCORE_CLEAN.

    @Test
    fun `rank 24 AccelerometerGyro flips from no-signal to clean on v2 sensorTypes`() = runBlocking {
        val r = AccelerometerGyroProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 24 predicted 0.0 with 6 sensors present — no missing-core, no static-variance " +
                "(empty live sample → variance=null → no stub fires)",
        )
    }

    @Test
    fun `rank 42 Proximity closed by TYPE_PROXIMITY=8 in sensorTypes`() = runBlocking {
        val r = ProximityProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 42 predicted 0.0 with TYPE_PROXIMITY present — missingOnPhone rule no longer fires",
        )
    }

    @Test
    fun `rank 43 Light closed by TYPE_LIGHT=5 in sensorTypes`() = runBlocking {
        val r = LightProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 43 predicted 0.0 with TYPE_LIGHT present — missingOnPhone rule no longer fires",
        )
    }

    @Test
    fun `rank 44 Magnetometer closed by TYPE_MAGNETIC_FIELD=2 in sensorTypes`() = runBlocking {
        val r = MagnetometerProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 44 predicted 0.0 with TYPE_MAGNETIC_FIELD present — missingOnPhone rule no longer fires",
        )
    }

    @Test
    fun `rank 45 Barometer closed by TYPE_PRESSURE=6 in sensorTypes`() = runBlocking {
        val r = BarometerProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 45 predicted 0.0 with TYPE_PRESSURE present on known-barometer-model Pixel 7 — " +
                "missingOnFlagship rule no longer fires",
        )
    }

    @Test
    fun `full runner — v2 sensor panel all clean (5 probes, criticalFailures=0)`() = runBlocking {
        val probes = listOf(
            AccelerometerGyroProbe(),
            ProximityProbe(),
            LightProbe(),
            MagnetometerProbe(),
            BarometerProbe(),
        )
        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = "0.1.0",
            deviceLabel = RedroidSpoofedSnapshot.SNAPSHOT.label,
        )
        val report = runner.runAll()
        assertNotNull(report)
        report.probes.forEach { record ->
            assertEquals(
                0.0, record.score,
                "v2 sensor probe ${record.id} expected 0.0, got ${record.score}. " +
                    "Evidence: ${record.evidence.map { "${it.key}=${it.value}" }}",
            )
        }
        // All sensor probes are rank > 10 (24/42/43/44/45), so criticalFailures
        // only counts those with rank <=10. Sensor band: zero criticals by
        // construction. Confirms the full-runner reduction is consistent.
        assertEquals(
            0, report.aggregate.criticalFailures,
            "v2 sensor panel must produce zero critical failures; " +
                "got ${report.aggregate.criticalFailures}",
        )
    }

    // ── Iter-2 residual probes: closed by settings/telephony/readableFiles ──
    //
    // Iter-1 left 7 "null/empty answer is itself a signal" probes still firing
    // (the rank-11/12/14/21/58/80/82 residual surface from the reviewer's
    // table-b). Each closes by populating realistic positive values in the
    // appropriate snapshot field, NOT by emulator-marker removal.

    @Test
    fun `rank 11 AndroidId masked by valid 16-hex with entropy above threshold`() = runBlocking {
        val r = AndroidIdProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 11 predicted 0.0 with android_id='a1b2c3d4e5f60718' " +
                "(16 lowercase hex, entropy=3.875 bits/char, not factory-default, not sequential)",
        )
    }

    @Test
    fun `rank 12 ImeiSerial masked by Luhn-valid IMEI + Pixel-shape serial`() = runBlocking {
        val r = ImeiSerialProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 12 predicted 0.0 with IMEI=353112109876546 (15-digit Luhn-valid, " +
                "not in KNOWN_EMULATOR_IMEIS) + SERIAL=HQ7Y0V3RJL (SERIAL_PATTERN_VALID)",
        )
    }

    @Test
    fun `rank 14 SeLinux masked by enforcing pair + enforce file + policy path`() = runBlocking {
        val r = SeLinuxProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 14 predicted 0.0 with ro.boot.selinux=enforcing, ro.build.selinux=1, " +
                "/sys/fs/selinux/enforce=1, /sys/fs/selinux/policy present — " +
                "all signals consistent Enforcing, no candidate rule fires, " +
                "propsAllEmptyButAccessible=false",
        )
    }

    @Test
    fun `rank 21 SimIccid masked by 89-prefix 19-digit Luhn-valid ICCID`() = runBlocking {
        val r = SimIccidProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 21 predicted 0.0 with SIM_SERIAL=8901260123456789011 " +
                "(19 digits, 89-prefix, Luhn-valid, T-Mobile US issuer, " +
                "not in KNOWN_EMULATOR_ICCIDS, not monotonic-ascending)",
        )
    }

    @Test
    fun `rank 58 InputMethod masked by canonical Gboard component`() = runBlocking {
        val r = InputMethodProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 58 predicted 0.0 with default_input_method= Gboard LatinIME component " +
                "(no emulator/test/mock substring, not empty)",
        )
    }

    @Test
    fun `rank 80 DebuggerTracerPid masked by valid status body with TracerPid=0`() = runBlocking {
        val r = DebuggerTracerPidProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 80 predicted 0.0 with /proc/self/status body containing 'TracerPid:\\t0' line",
        )
    }

    @Test
    fun `rank 82 LocationMockRasp masked by populated is_from_mock_provider sentinel`() = runBlocking {
        val r = LocationMockRaspProbe().run(ctx)
        assertFalse(r.failed)
        assertEquals(
            0.0, r.score,
            "rank 82 predicted 0.0 with location.is_from_mock_provider='0' " +
                "(non-null sentinel disarms noLocationAvailable; isFromMock=false)",
        )
    }

    // ── Iter-2 closeout: residual surface aggregate ─────────────────────────

    @Test
    fun `full runner — v2_5 residual panel all clean (7 probes, aggregate)`() = runBlocking {
        val probes = listOf(
            AndroidIdProbe(),
            ImeiSerialProbe(),
            SeLinuxProbe(),
            SimIccidProbe(),
            InputMethodProbe(),
            DebuggerTracerPidProbe(),
            LocationMockRaspProbe(),
        )
        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = "0.1.0",
            deviceLabel = RedroidSpoofedSnapshot.SNAPSHOT.label,
        )
        val report = runner.runAll()
        assertNotNull(report)
        report.probes.forEach { record ->
            assertEquals(
                0.0, record.score,
                "v2.5 residual probe ${record.id} expected 0.0, got ${record.score}. " +
                    "Evidence: ${record.evidence.map { "${it.key}=${it.value}" }}",
            )
        }
        assertEquals(
            0, report.aggregate.criticalFailures,
            "v2.5 residual panel must produce zero critical failures; " +
                "got ${report.aggregate.criticalFailures}",
        )
    }

    // ── Iter-2.6 rank-31 bluetooth_mac closure (supplier-wired) ─────────────
    //
    // BluetoothMacProbe is constructor-supplier-driven (no ProbeContext
    // accessor). The probe also reads /sys/class/bluetooth/hci0/address via
    // ctx.readFile — the supplier covers the framework side, the sysfs path
    // covers the kernel side. Closing the probe requires BOTH surfaces
    // returning a coherent real-OUI MAC (one alone is insufficient — supplier
    // null leaves nullAdapterOnPhone=true).
    //
    // Wiring: SnapshotReplayContext.queryBluetoothAdapterMac() overrides the
    // ProbeContext default (null) to return the snapshot's bluetoothMac
    // field. The probe is instantiated with that method as the supplier.
    // The sysfs surface comes from the readableFiles entry
    // "/sys/class/bluetooth/hci0/address" (same MAC for cross-surface
    // coherence — required to avoid the privacyDefaultViaSysfs mismatch
    // branch).

    @Test
    fun `rank 31 BluetoothMac masked by supplier-wired Google OUI MAC + matching sysfs`() = runBlocking {
        val probe = BluetoothMacProbe(
            bluetoothAdapterMacSupplier = { ctx.queryBluetoothAdapterMac() },
        )
        val r = probe.run(ctx)
        assertFalse(r.failed, "BluetoothMacProbe should not fail on v2.6 spoof")
        assertEquals(
            0.0, r.score,
            "rank 31 predicted 0.0 with adapter+sysfs both = 3c:5a:b4:8d:f1:27 " +
                "(Google OUI, not locally-administered, not in any emulator OUI set, " +
                "not zero, not test-fixture, not privacy-default). Evidence: " +
                "${r.evidence.map { "${it.key}=${it.value}" }}",
        )
        assertEquals(
            0.95, r.confidence,
            "rank 31 should report CONFIDENCE_FULL=0.95 — both adapter and sysfs " +
                "surfaces returned parseable MACs",
        )
    }
}

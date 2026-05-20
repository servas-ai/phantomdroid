// agents/detection/src/test/kotlin/com/detectorlab/replay/FullProbeRunnerSpoofTest.kt
//
// FULL-PANEL spoof-effectiveness gate — instantiates every probe in the
// production inventory (all 68) and runs them against RedroidSpoofedSnapshot
// through the real ProbeRunner. Two assertions:
//
//   (1) report.aggregate.category == ReportCategory.CLEAN
//   (2) report.aggregate.criticalFailures == 0
//
// On failure the test prints the residual-hit list (every probe whose score
// > 0.0) so the next spoof iteration knows exactly what surface is still
// leaking. This is the closeout test for the Power-8 mission: the partial
// 26-probe panel in RedroidSpoofedReplayTest is a per-probe regression
// guard; THIS test is the all-up "is the snapshot indistinguishable from a
// real phone in the full pipeline" gate.
//
// Constructor strategy: explicit registry (every probe instantiated via its
// no-arg or default-arg constructor). The only exception is BluetoothMacProbe
// which is constructor-supplier-driven for the BluetoothAdapter MAC; we wire
// it to `ctx.queryBluetoothAdapterMac()` per the supplier pattern documented
// in RedroidSpoofedReplayTest line 416..436.
//
// Locale pin: run with `-Duser.language=en -Duser.country=US -Duser.timezone=
// America/Los_Angeles` to keep the rank-20 (timezone_locale_mismatch) and
// rank-36 (language_country) probes' host-JVM-Locale fallbacks deterministic
// until phase-3 ProbeContext refactor lands. See gradle test task config.

package com.detectorlab.replay

import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.ReportCategory
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.detectorlab.probes.app.IgFamilyDeviceIdHeaderProbe
import com.detectorlab.probes.app.TikTokArgusSigningProbe
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import com.detectorlab.probes.buildprop.BuildFingerprintProbe
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
import com.detectorlab.probes.root.MountNsMismatchProbe
import com.detectorlab.probes.root.SeLinuxProbe
import com.detectorlab.probes.root.SuDetectionProbe
import com.detectorlab.probes.root.SystemRwMountProbe
import com.detectorlab.probes.runtime.AutomationToolsProbe
import com.detectorlab.probes.runtime.DebuggerTracerPidProbe
import com.detectorlab.probes.runtime.FridaMemoryMapsProbe
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
import kotlin.test.Test
import kotlin.test.assertEquals

class FullProbeRunnerSpoofTest {

    private val ctx = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    /**
     * The complete production probe inventory. Probes are instantiated via
     * their default constructors; the only constructor-arg required is
     * BluetoothMacProbe's `bluetoothAdapterMacSupplier`, which routes through
     * `SnapshotReplayContext.queryBluetoothAdapterMac()` per the supplier
     * pattern. This mirrors what `ProbeRunner` would do in production via
     * the spawn-site wiring step.
     */
    private fun allProbes(): List<Probe> = listOf(
        // app (2)
        IgFamilyDeviceIdHeaderProbe(),
        TikTokArgusSigningProbe(),
        // buildprop (4)
        BoardHardwareProbe(),
        BuildFingerprintProbe(),
        ModelBrandManufacturerProbe(),
        TagsAndTypeProbe(),
        // emulator (5) — Power-13 Gap #4 added ThirdPartyEmulatorArtifactsProbe
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
        // Power-12 rank-9.8 declarative variant (mitigation_layer not_spoofable)
        // kernel (1)
        CpuInfoProbe(),
        // network (5)
        DnsServerProbe(),
        HttpProxyProbe(),
        NetworkIpAsnProbe(),
        NetworkTypeProbe(),
        VpnProxyProbe(),
        // root (4) — Power-13 Gap #3 + Gap #10 expansions
        MountNsMismatchProbe(),
        SeLinuxProbe(),
        SuDetectionProbe(),
        SystemRwMountProbe(),
        // runtime (9)
        AutomationToolsProbe(),
        DebuggerTracerPidProbe(),
        FridaMemoryMapsProbe(),
        InstalledAppsProbe(),
        MultiInstanceProbe(),
        NativePrologueHashProbe(),
        ScreenRecordingProbe(),
        ServicesProcessesProbe(),
        XposedLsposedProbe(),
        // Power-12 rank-9.0 (FridaMemoryMapsProbe) + rank-9.7
        // (NativePrologueHashProbe) declarative variants — close the
        // last cross-cutting inventory gaps to bring the JVM probe
        // panel to TRUE 73/73 == 100% coverage.
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

    @Test
    fun `full probe runner classifies spoofed snapshot as CLEAN with zero critical failures`() =
        runBlocking {
            val probes = allProbes()
            // Sanity: panel size matches the inventory. If a new probe is
            // added and this list isn't updated the test should fail loudly
            // here, not silently drop coverage.
            assertEquals(
                76, probes.size,
                "expected the full 76-probe inventory (73 base + Power-13 Gap #4 " +
                    "ThirdPartyEmulatorArtifactsProbe + Power-13 Gap #3 " +
                    "MountNsMismatchProbe + Power-13 Gap #10 " +
                    "SystemRwMountProbe); if the inventory changed, update " +
                    "the allProbes() registry above",
            )
            val runner = ProbeRunner(
                probes = probes,
                ctx = ctx,
                appVersion = "0.1.0",
                deviceLabel = RedroidSpoofedSnapshot.SNAPSHOT.label,
            )
            val report = runner.runAll()

            // Residual hit list: print every probe scoring > 0.0 so a failing
            // iteration immediately shows which surfaces still leak. Use
            // stderr so the output survives gradle's default --info filter.
            val residuals = report.probes
                .filter { it.score > 0.0 }
                .sortedByDescending { it.score }
            if (residuals.isNotEmpty()) {
                System.err.println("──── FULL-PANEL RESIDUAL HITS (score > 0.0) ────")
                System.err.println(
                    "rank | probe id | score | confidence | top-evidence",
                )
                residuals.forEach { r ->
                    val topEv = r.evidence.take(3)
                        .joinToString(" ; ") { "${it.key}=${it.value}" }
                    System.err.println(
                        "${r.rank.toString().padStart(3)} | " +
                            "${r.id.padEnd(40)} | " +
                            "${"%.2f".format(r.score)} | " +
                            "${"%.2f".format(r.confidence)} | " +
                            topEv,
                    )
                }
                System.err.println(
                    "weightedScore=${"%.4f".format(report.aggregate.weightedScore)} " +
                        "criticalFailures=${report.aggregate.criticalFailures} " +
                        "category=${report.aggregate.category}",
                )
            }

            assertEquals(
                0, report.aggregate.criticalFailures,
                "full-panel spoof must produce ZERO critical failures " +
                    "(rank 1..10 probes with score >=0.7). Got " +
                    "${report.aggregate.criticalFailures}. " +
                    "Residuals: ${residuals.map { "${it.id}=${it.score}" }}",
            )
            assertEquals(
                ReportCategory.CLEAN, report.aggregate.category,
                "full-panel spoof must classify as CLEAN. Got " +
                    "${report.aggregate.category} " +
                    "(weightedScore=${"%.4f".format(report.aggregate.weightedScore)}). " +
                    "Residuals: ${residuals.map { "${it.id}=${it.score}" }}",
            )
        }
}

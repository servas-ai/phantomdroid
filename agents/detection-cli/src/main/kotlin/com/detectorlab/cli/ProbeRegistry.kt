// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ProbeRegistry.kt
//
// Explicit instantiation of the CLI SNAPSHOT production inventory (82 probes).
// This is the canonical `FullProbeRunnerSpoofTest.allProbes()` 84-probe panel
// MINUS the two genuinely-live-only / un-capturable probes:
//   1. `KeystoreAttestationProbe` (id `integrity.keystore_attestation`).
//      Hardware-backed key attestation can only be honestly assessed via a
//      LIVE TEE challenge-response; its static snapshot proxy scores
//      SCORE_HARDWARE_KEYSTORE_ABSENT=0.70 from mere ABSENCE of
//      `ro.hardware.keystore` / `/dev/keymaster` — fields real clean-device
//      snapshots do not record — so it fires 0.70 IDENTICALLY on clean Pixel,
//      clean Samsung, and a ReDroid container (non-discriminating
//      absence-noise).
//   2. `IntegrityInstallSourceProbe` (id `integrity.install_source`).
//      Its only signal is `PackageManager.getInstallSourceInfo()` — the
//      installer-package of the DETECTOR APP ITSELF, an APPLICATION-layer fact
//      observable only from inside the running app process. The live_matrix
//      capture is a read-only `docker exec` harness that is NOT running inside
//      the detector app and CANNOT observe the app's installer, so the field
//      is structurally un-capturable on the snapshot path. Its uncaptured
//      `null` hits PATTERN_UNKNOWN_INSTALLER=0.85 — a false absence-nonzero
//      that inflated B2 exactly like the keystore probe. Excluded here
//      (bucket-C, un-capturable on the snapshot path) and RETAINED in the
//      canonical/live panel where the app's real install source is available.
// Both REMAIN in the canonical/live FullProbeRunnerSpoofTest panel (84) for
// the live detection path. See proof/detection-cli-panel-parity/RESULT.md.
//
// Relationship: CLI(82) == canonical(84) − {KeystoreAttestationProbe,
//                                            IntegrityInstallSourceProbe}.
//
// The four mount-namespace / UDS root probes
// (MountNsMismatch / OverlayFsPresent / SystemRwMount / MagiskUds) were added
// 2026-06-01 so the dispositive Momo / RootBeer root signals captured by
// `live_matrix.capture_mount_and_uds` (/proc/{self,1}/mountinfo +
// /proc/net/unix) are actually SCORED rather than silently dropped. The
// remaining 15 canonical probes were added 2026-06-01 to close the
// CLI-vs-canonical under-coverage gap so EVERY proof snapshot (incl B2) is
// scored against the full panel. All 15 are SNAPSHOT-SCOREABLE declarative
// probes — every ctx.* accessor they use is backed by SnapshotReplayContext;
// none require live API/hardware/network (the live attestation variants are
// SEPARATE probe classes, not these). Several read snapshot fields the current
// live_matrix.py capture does not yet populate (documented capture-gaps in
// proof/detection-cli-panel-parity/RESULT.md): those probes score a
// conservative 0.0 (absent != clean) until the capture is extended — never
// fabricated. If a new probe is added to the detection module, THIS list
// and the test list must be updated together. The `assertEquals(EXPECTED_COUNT,
// probes.size, ...)` sanity check in the test catches any drift on the test
// side; the CLI's `validate` subcommand exercises this instantiation path at
// startup so missing/renamed probes surface as a loud build failure rather than
// a silent coverage drop.
//
// The only non-default-arg constructor is `BluetoothMacProbe`, which takes
// a `bluetoothAdapterMacSupplier: () -> String?` that we wire through the
// shared `ProbeContext.queryBluetoothAdapterMac()` accessor — same pattern
// the production probe-runner spawn-site uses.

package com.detectorlab.cli

import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeContext
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
import com.detectorlab.probes.integrity.PlayIntegrityLiveProbe
import com.detectorlab.probes.integrity.PlayIntegrityProbe
import com.detectorlab.probes.integrity.PrologueGotHooksProbe
import com.detectorlab.probes.kernel.CpuInfoProbe
import com.detectorlab.probes.network.DnsServerProbe
import com.detectorlab.probes.network.HttpProxyProbe
import com.detectorlab.probes.network.NetworkIpAsnProbe
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.network.VpnProxyProbe
import com.detectorlab.probes.root.APatchRootProbe
import com.detectorlab.probes.root.KernelSURootProbe
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

/**
 * The CLI SNAPSHOT probe inventory (82 probes) — the canonical
 * `FullProbeRunnerSpoofTest.allProbes()` 84-probe panel in the :detection test
 * source set MINUS the two probes that cannot be honestly assessed from a
 * read-only snapshot: `KeystoreAttestationProbe` (`integrity.keystore_attestation`,
 * needs a live TEE challenge — non-discriminating 0.70 absence-noise) and
 * `IntegrityInstallSourceProbe` (`integrity.install_source`, reads the detector
 * app's own `getInstallSourceInfo()` — an APPLICATION-layer fact the docker-exec
 * live capture cannot observe, so its uncaptured `null` is a false 0.85
 * absence-nonzero). Both are retained in the canonical/live panel. Relationship:
 * CLI(82) == canonical(84) − {KeystoreAttestationProbe, IntegrityInstallSourceProbe}.
 */
object ProbeRegistry {

    const val EXPECTED_COUNT: Int = 82

    fun allProbes(ctx: ProbeContext): List<Probe> = listOf(
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
        // integrity (4) — two probes EXCLUDED (both retained in canonical/live panel):
        //  • KeystoreAttestationProbe (live-only; TEE attestation needs a live
        //    challenge — its snapshot proxy is non-discriminating 0.70 absence-noise).
        //  • IntegrityInstallSourceProbe (un-capturable on the snapshot path; its
        //    only signal is the detector app's OWN getInstallSourceInfo(), an
        //    APPLICATION-layer fact a docker-exec capture cannot observe — uncaptured
        //    null = false 0.85 absence-nonzero that inflated B2).
        AppSignatureProbe(),
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
        // root (9)
        APatchRootProbe(),
        KernelSURootProbe(),
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
}

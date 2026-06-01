// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ProbeRegistry.kt
//
// Explicit instantiation of the CLI SNAPSHOT production inventory (83 probes).
// This is the canonical `FullProbeRunnerSpoofTest.allProbes()` 84-probe panel
// MINUS the one genuinely-live-only probe `KeystoreAttestationProbe`
// (id `integrity.keystore_attestation`). Hardware-backed key attestation can
// only be honestly assessed via a LIVE TEE challenge-response; its static
// snapshot proxy scores SCORE_HARDWARE_KEYSTORE_ABSENT=0.70 from mere ABSENCE
// of `ro.hardware.keystore` / `/dev/keymaster` — fields that real clean-device
// snapshots (Pixel7Clean, SamsungS22Clean) do not record — so the snapshot
// proxy fires 0.70 IDENTICALLY on a clean Pixel, a clean Samsung, and a
// ReDroid container. It is non-discriminating absence-noise on the snapshot
// panel and is therefore EXCLUDED here (bucket-C live-only). It REMAINS in the
// canonical/live FullProbeRunnerSpoofTest panel (84) for the live detection
// path, where a real attestation challenge can run. See
// proof/detection-cli-panel-parity/RESULT.md.
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
import com.detectorlab.probes.integrity.IntegrityInstallSourceProbe
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
 * The CLI SNAPSHOT probe inventory (83 probes) — the canonical
 * `FullProbeRunnerSpoofTest.allProbes()` 84-probe panel in the :detection test
 * source set MINUS the one genuinely-live-only probe `KeystoreAttestationProbe`
 * (`integrity.keystore_attestation`). TEE hardware-key attestation needs a LIVE
 * challenge-response; its static snapshot proxy is non-discriminating
 * absence-noise (fires 0.70 identically on clean Pixel/Samsung and ReDroid), so
 * it is excluded from the snapshot panel but retained in the canonical/live
 * panel. Relationship: CLI(83) == canonical(84) − {KeystoreAttestationProbe}.
 */
object ProbeRegistry {

    const val EXPECTED_COUNT: Int = 83

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
        // integrity (5) — KeystoreAttestationProbe EXCLUDED (live-only, bucket-C;
        // TEE attestation needs a live challenge — its snapshot proxy is
        // non-discriminating absence-noise). It stays in the canonical/live panel.
        AppSignatureProbe(),
        IntegrityInstallSourceProbe(),
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

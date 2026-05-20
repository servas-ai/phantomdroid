// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ProbeRegistry.kt
//
// Explicit instantiation of the full 65-probe production inventory.
// Mirrors `FullProbeRunnerSpoofTest.allProbes()` (the canonical reference
// registry) verbatim — if a new probe is added to the detection module,
// THIS list and the test list must be updated together. The
// `assertEquals(65, probes.size, ...)` sanity check in the test catches any
// drift on the test side; the CLI's `validate` subcommand exercises this
// instantiation path at startup so missing/renamed probes surface as a
// loud build failure rather than a silent coverage drop.
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
import com.detectorlab.probes.buildprop.ModelBrandManufacturerProbe
import com.detectorlab.probes.buildprop.TagsAndTypeProbe
import com.detectorlab.probes.emulator.CpuAbiProbe
import com.detectorlab.probes.emulator.GpuRendererProbe
import com.detectorlab.probes.emulator.ProcVersionProbe
import com.detectorlab.probes.emulator.QemuArtifactsProbe
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
import com.detectorlab.probes.integrity.PlayIntegrityProbe
import com.detectorlab.probes.kernel.CpuInfoProbe
import com.detectorlab.probes.network.DnsServerProbe
import com.detectorlab.probes.network.HttpProxyProbe
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.network.VpnProxyProbe
import com.detectorlab.probes.root.SeLinuxProbe
import com.detectorlab.probes.root.SuDetectionProbe
import com.detectorlab.probes.runtime.AutomationToolsProbe
import com.detectorlab.probes.runtime.DebuggerTracerPidProbe
import com.detectorlab.probes.runtime.InstalledAppsProbe
import com.detectorlab.probes.runtime.MultiInstanceProbe
import com.detectorlab.probes.runtime.ScreenRecordingProbe
import com.detectorlab.probes.runtime.ServicesProcessesProbe
import com.detectorlab.probes.runtime.XposedLsposedProbe
import com.detectorlab.probes.sensors.AccelerometerGyroProbe
import com.detectorlab.probes.sensors.BarometerProbe
import com.detectorlab.probes.sensors.LightProbe
import com.detectorlab.probes.sensors.MagnetometerProbe
import com.detectorlab.probes.sensors.ProximityProbe
import com.detectorlab.probes.ui.DisplayCutoutProbe
import com.detectorlab.probes.ui.InputMethodProbe
import com.detectorlab.probes.ui.RefreshRateProbe
import com.detectorlab.probes.ui.ScreenResolutionProbe
import com.detectorlab.probes.ui.SystemFontsProbe

/**
 * The complete production probe inventory (65 probes). Keep in lockstep with
 * `FullProbeRunnerSpoofTest.allProbes()` in the :detection test source set.
 */
object ProbeRegistry {

    const val EXPECTED_COUNT: Int = 65

    fun allProbes(ctx: ProbeContext): List<Probe> = listOf(
        // app (2)
        IgFamilyDeviceIdHeaderProbe(),
        TikTokArgusSigningProbe(),
        // buildprop (4)
        BoardHardwareProbe(),
        BuildFingerprintProbe(),
        ModelBrandManufacturerProbe(),
        TagsAndTypeProbe(),
        // emulator (4)
        CpuAbiProbe(),
        GpuRendererProbe(),
        ProcVersionProbe(),
        QemuArtifactsProbe(),
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
        // integrity (2)
        AppSignatureProbe(),
        PlayIntegrityProbe(),
        // kernel (1)
        CpuInfoProbe(),
        // network (4)
        DnsServerProbe(),
        HttpProxyProbe(),
        NetworkTypeProbe(),
        VpnProxyProbe(),
        // root (2)
        SeLinuxProbe(),
        SuDetectionProbe(),
        // runtime (7)
        AutomationToolsProbe(),
        DebuggerTracerPidProbe(),
        InstalledAppsProbe(),
        MultiInstanceProbe(),
        ScreenRecordingProbe(),
        ServicesProcessesProbe(),
        XposedLsposedProbe(),
        // sensors (5)
        AccelerometerGyroProbe(),
        BarometerProbe(),
        LightProbe(),
        MagnetometerProbe(),
        ProximityProbe(),
        // ui (5)
        DisplayCutoutProbe(),
        InputMethodProbe(),
        RefreshRateProbe(),
        ScreenResolutionProbe(),
        SystemFontsProbe(),
    )
}

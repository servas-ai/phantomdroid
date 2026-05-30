// apps/detector-app/.../AndroidProbeRegistry.kt
//
// The on-device probe inventory. Kept in LOCKSTEP with
// agents/detection-cli/.../ProbeRegistry.kt (the JVM-side reference registry,
// itself a mirror of FullProbeRunnerSpoofTest.allProbes()). If a probe is
// added/removed in :detection, all three lists must be updated together; the
// `EXPECTED_COUNT` check fails the build/run loud if they drift.
//
// This registry is duplicated rather than reused from :detection-cli because
// that module is a JVM `application` carrying clikt + kaml + a `main()` — none
// of which belong in an APK. The registry is a thin list literal; duplicating
// it keeps :detector-app's only dependency on :detection (the probe library).
//
// Constructor-injected suppliers (the on-device divergence from the CLI):
//   - BluetoothMacProbe       → ctx.queryBluetoothAdapterMac() (shared accessor)
//   - ChargingStateProbe      → AndroidProbeContext battery accessors
//   - WifiSsidBssidProbe      → AndroidProbeContext WifiInfo accessors
//   - HttpProxyProbe          → AndroidProbeContext.queryJvmSystemProperty
//   - ServicesProcessesProbe  → AndroidProbeContext ActivityManager accessors
//
// The CLI keeps these five on default no-arg ctors (it is a host JVM with no
// Android framework). The probe INVENTORY (the 65-probe list + EXPECTED_COUNT)
// stays in lockstep; only the data-source wiring differs, which is the entire
// point of the on-device context. When `ctx` is not an AndroidProbeContext
// (the host-JVM unit-test stub), every supplier falls back to the same null/
// default answer the no-arg ctor would give — so the test's graceful-ABSTAIN
// path is unchanged.
//
// ScreenRecordingProbe takes no ctor args; it reads the SDK level (and the two
// always-null callback signals) from ctx.queryMediaProjectionManager().

package com.detectorlab.detectorapp

import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeContext
import com.detectorlab.detectorapp.context.AndroidProbeContext
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
 * The complete production probe inventory (65 probes), instantiated for
 * on-device execution. Keep in lockstep with
 * `com.detectorlab.cli.ProbeRegistry.allProbes()`.
 */
object AndroidProbeRegistry {

    const val EXPECTED_COUNT: Int = 65

    fun allProbes(ctx: ProbeContext): List<Probe> {
        // Live-data wiring is available only when running against the on-device
        // AndroidProbeContext. The host-JVM unit-test stub uses the default
        // (null-returning) suppliers, exercising the graceful-ABSTAIN path.
        val android: AndroidProbeContext? = ctx as? AndroidProbeContext
        return listOf(
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
        ChargingStateProbe(
            statusSupplier = { android?.queryBatteryStatus() },
            pluggedSupplier = { android?.queryBatteryPlugged() },
            levelSupplier = { android?.queryBatteryLevelPercent() },
        ),
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
        WifiSsidBssidProbe(
            ssidSupplier = { android?.queryWifiSsid() },
            bssidSupplier = { android?.queryWifiBssid() },
            connectionStateSupplier = { android?.queryWifiConnectionState() },
        ),
        // integrity (2)
        AppSignatureProbe(),
        PlayIntegrityProbe(),
        // kernel (1)
        CpuInfoProbe(),
        // network (4)
        DnsServerProbe(),
        HttpProxyProbe(
            jvmPropertySupplier = { key -> android?.queryJvmSystemProperty(key) },
        ),
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
        ServicesProcessesProbe(
            runningServiceNamesSupplier = { android?.queryRunningServiceNames() },
            runningProcessNamesSupplier = { android?.queryRunningProcessNames() },
        ),
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
}

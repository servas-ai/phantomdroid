/*
 * SpoofStack LSPosed module — entry point.
 *
 * LSPosed invokes {@link #handleLoadPackage} once per target-app load.
 * This class dispatches to the individual hook classes in the
 * {@code hooks} subpackage. Each hook class has a static
 * {@code install(ClassLoader)} method that performs the actual
 * {@code XposedHelpers.findAndHookMethod} calls.
 *
 * Source: audit/spoof-stack/production-hooks-spec.md (Power-8 closeout).
 */
package io.spoofstack.redroid;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import io.spoofstack.redroid.hooks.BluetoothAdapterHook;
import io.spoofstack.redroid.hooks.BuildAbiHook;
import io.spoofstack.redroid.hooks.BuildSerialHook;
import io.spoofstack.redroid.hooks.DisplayMetricsHook;
import io.spoofstack.redroid.hooks.FileInputStreamHook;
import io.spoofstack.redroid.hooks.LocaleHook;
import io.spoofstack.redroid.hooks.LocationHook;
import io.spoofstack.redroid.hooks.PackageManagerHook;
import io.spoofstack.redroid.hooks.ResourcesHook;
import io.spoofstack.redroid.hooks.SensorManagerHook;
import io.spoofstack.redroid.hooks.SettingsSecureHook;
import io.spoofstack.redroid.hooks.TelephonyManagerHook;
import io.spoofstack.redroid.hooks.TimeZoneHook;
import io.spoofstack.redroid.hooks.WifiInfoHook;

public final class SpoofStackModule implements IXposedHookLoadPackage {

    /** Module identifier surfaced in LSPosed logs. */
    private static final String TAG = "SpoofStack";

    /** Module version — keep in sync with module.prop versionCode. */
    private static final String VERSION = "1.0.0";

    /**
     * Self-package — never hook our own classes (would recurse).
     */
    private static final String SELF_PACKAGE = "io.spoofstack.redroid";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        // Skip self — we'd recurse if our own classes get re-hooked.
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // Also skip the LSPosed Manager itself; hooking it can brick
        // module management.
        if ("org.lsposed.manager".equals(lpparam.packageName)) {
            return;
        }

        final ClassLoader cl = lpparam.classLoader;
        XposedBridge.log(TAG + " v" + VERSION + " loading for " + lpparam.packageName);

        // Hide self from PackageManager queries before any other hook —
        // some target apps enumerate packages first thing on launch.
        safeInstall("PackageManagerHook", () -> PackageManagerHook.install(cl));

        // §5.1 — TelephonyManager (IMEI, serial, operator, country)
        safeInstall("TelephonyManagerHook", () -> TelephonyManagerHook.install(cl));

        // §5.1 / §1.7 — Build.getSerial() + Build.SERIAL static field
        safeInstall("BuildSerialHook", () -> BuildSerialHook.install(cl));

        // §1.2 — Build.SUPPORTED_*ABIS reflection surface
        safeInstall("BuildAbiHook", () -> BuildAbiHook.install(cl));

        // §5.2 — Locale.getDefault()
        safeInstall("LocaleHook", () -> LocaleHook.install(cl));

        // §5.2 — TimeZone.getDefault()
        safeInstall("TimeZoneHook", () -> TimeZoneHook.install(cl));

        // §5.2 — Resources.getConfiguration() per-context locale
        safeInstall("ResourcesHook", () -> ResourcesHook.install(cl));

        // §5.3 — BluetoothAdapter.getAddress() + BluetoothManager.getAdapter()
        safeInstall("BluetoothAdapterHook", () -> BluetoothAdapterHook.install(cl));

        // §5.4 — WifiInfo.getMacAddress()
        safeInstall("WifiInfoHook", () -> WifiInfoHook.install(cl));

        // §5.5 — SensorManager.getSensorList() six-sensor inventory
        safeInstall("SensorManagerHook", () -> SensorManagerHook.install(cl));

        // §5.6 — Display.getMetrics() / Resources.getDisplayMetrics()
        safeInstall("DisplayMetricsHook", () -> DisplayMetricsHook.install(cl));

        // §3.2 — FileInputStream(File) redirect for /proc/self/status
        safeInstall("FileInputStreamHook", () -> FileInputStreamHook.install(cl));

        // §4.1 (location.is_from_mock_provider) — Location.isFromMockProvider()
        safeInstall("LocationHook", () -> LocationHook.install(cl));

        // §4.1 — Settings.Secure.getString() for per-evaluation keys
        safeInstall("SettingsSecureHook", () -> SettingsSecureHook.install(cl));

        XposedBridge.log(TAG + " all hooks installed for " + lpparam.packageName);
    }

    /**
     * Run a hook installer and log (don't throw) on failure. A single
     * broken hook must not block the rest of the module.
     */
    private static void safeInstall(final String name, final Runnable installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook install failed: " + name + ": " + t);
        }
    }
}

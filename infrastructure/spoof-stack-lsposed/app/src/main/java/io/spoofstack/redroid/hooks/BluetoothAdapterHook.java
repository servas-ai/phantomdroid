/*
 * BluetoothAdapterHook — §5.3 of production-hooks-spec.md.
 *
 * BluetoothAdapter.getAddress() on real devices returns the radio MAC
 * (or "02:00:00:00:00:00" on API 23+ for non-LOCAL_MAC_ADDRESS
 * permission holders). ReDroid has no Bluetooth HAL so getAdapter()
 * returns null — itself a strong emulator tell.
 *
 * We install:
 * - BluetoothAdapter.getAddress() — returns the Pixel 7 BT MAC.
 * - BluetoothManager.getAdapter() — if returning null, returns the
 *   default adapter (which is itself null on no-HAL hosts, so the
 *   probe at rank 31 already passes via the sysfs route).
 */
package io.spoofstack.redroid.hooks;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class BluetoothAdapterHook {

    private BluetoothAdapterHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "getAddress",
                XC_MethodReplacement.returnConstant(SpoofConfig.BLUETOOTH_MAC));

        // Belt-and-suspenders: if BluetoothManager.getAdapter() returns
        // null (no HAL), retry the static default-adapter accessor.
        // Note: on no-HAL hosts the default adapter is also null;
        // this hook is here to align with the spec's stated mechanism
        // and to cover hosts where a stub adapter is registered.
        XposedHelpers.findAndHookMethod(BluetoothManager.class, "getAdapter",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        if (param.getResult() == null) {
                            param.setResult(BluetoothAdapter.getDefaultAdapter());
                        }
                    }
                });
    }
}

/*
 * WifiInfoHook — §5.4 of production-hooks-spec.md.
 *
 * WifiInfo.getMacAddress() returns "02:00:00:00:00:00" on API 26+ for
 * non-LOCAL_MAC_ADDRESS holders. The probe at rank 15 reads this AND
 * the /sys/class/net/wlan0/address sysfs path; both need to agree on
 * the spoofed Pixel 7 wlan0 MAC.
 */
package io.spoofstack.redroid.hooks;

import android.net.wifi.WifiInfo;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class WifiInfoHook {

    private WifiInfoHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(WifiInfo.class, "getMacAddress",
                XC_MethodReplacement.returnConstant(SpoofConfig.WIFI_MAC));
    }
}

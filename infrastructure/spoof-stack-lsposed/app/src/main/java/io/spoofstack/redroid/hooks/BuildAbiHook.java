/*
 * BuildAbiHook — §1.2 of production-hooks-spec.md.
 *
 * resetprop on ro.product.cpu.abi* covers the property surface, but
 * an app reading android.os.Build.SUPPORTED_*ABIS via reflection
 * bypasses property reads. These static fields are initialized once
 * at framework boot, so they need a setStaticObjectField override.
 */
package io.spoofstack.redroid.hooks;

import android.os.Build;

import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class BuildAbiHook {

    private BuildAbiHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.setStaticObjectField(Build.class,
                "SUPPORTED_ABIS", SpoofConfig.SUPPORTED_ABIS);
        XposedHelpers.setStaticObjectField(Build.class,
                "SUPPORTED_64_BIT_ABIS", SpoofConfig.SUPPORTED_64_BIT_ABIS);
        XposedHelpers.setStaticObjectField(Build.class,
                "SUPPORTED_32_BIT_ABIS", SpoofConfig.SUPPORTED_32_BIT_ABIS);

        // Build.HARDWARE / Build.BRAND / Build.MODEL — read directly via
        // reflection by some emulator detectors. The corresponding ro.*
        // properties (covered by resetprop) populate these at Zygote
        // boot, but if our LSPosed module loads after that init, we
        // re-pin the fields here defensively.
        XposedHelpers.setStaticObjectField(Build.class,
                "HARDWARE", SpoofConfig.HARDWARE);
        XposedHelpers.setStaticObjectField(Build.class,
                "BRAND", SpoofConfig.PRODUCT_BRAND);
        XposedHelpers.setStaticObjectField(Build.class,
                "MODEL", SpoofConfig.PRODUCT_MODEL);
        XposedHelpers.setStaticObjectField(Build.class,
                "MANUFACTURER", SpoofConfig.PRODUCT_MANUFACTURER);
        XposedHelpers.setStaticObjectField(Build.class,
                "DEVICE", SpoofConfig.PRODUCT_DEVICE);
        XposedHelpers.setStaticObjectField(Build.class,
                "PRODUCT", SpoofConfig.PRODUCT_NAME);
        XposedHelpers.setStaticObjectField(Build.class,
                "FINGERPRINT", SpoofConfig.BUILD_FINGERPRINT);
        XposedHelpers.setStaticObjectField(Build.class,
                "DISPLAY", SpoofConfig.BUILD_DISPLAY_ID);
        XposedHelpers.setStaticObjectField(Build.class,
                "TAGS", SpoofConfig.BUILD_TAGS);
        XposedHelpers.setStaticObjectField(Build.class,
                "TYPE", SpoofConfig.BUILD_TYPE);
    }
}

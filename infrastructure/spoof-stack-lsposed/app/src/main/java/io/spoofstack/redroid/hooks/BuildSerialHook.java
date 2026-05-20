/*
 * BuildSerialHook — §1.7 / §5.1 of production-hooks-spec.md.
 *
 * Build.getSerial() routes through TelephonyManager.getSerial() on
 * API 26+ (requires READ_PRIVILEGED_PHONE_STATE), but reflection-based
 * reads of Build.SERIAL bypass that path. We hook BOTH.
 */
package io.spoofstack.redroid.hooks;

import android.os.Build;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class BuildSerialHook {

    private BuildSerialHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        // Static Build.getSerial() — API 26+
        XposedHelpers.findAndHookMethod(Build.class, "getSerial",
                XC_MethodReplacement.returnConstant(SpoofConfig.SERIAL));

        // Build.SERIAL — deprecated constant, still accessed by older
        // analytics code via reflection.
        XposedHelpers.setStaticObjectField(Build.class, "SERIAL", SpoofConfig.SERIAL);
    }
}

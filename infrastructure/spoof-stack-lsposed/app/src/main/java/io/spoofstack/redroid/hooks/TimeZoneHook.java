/*
 * TimeZoneHook — §5.2 of production-hooks-spec.md.
 *
 * TimeZone.getDefault() reflects the JVM's persist.sys.timezone via
 * the platform's ZoneInfoDB. We hook the Java-side getter directly so
 * even apps that bypass the setprop side observe the spoofed value.
 */
package io.spoofstack.redroid.hooks;

import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class TimeZoneHook {

    private TimeZoneHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        final TimeZone pst = TimeZone.getTimeZone(SpoofConfig.TIMEZONE_ID);

        XposedHelpers.findAndHookMethod(TimeZone.class, "getDefault",
                XC_MethodReplacement.returnConstant(pst));
    }
}

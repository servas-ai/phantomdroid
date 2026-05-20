/*
 * LocationHook — §4.1 of production-hooks-spec.md.
 *
 * Settings.Secure "location.is_from_mock_provider" is a
 * wrapper-synthesized key (KDoc on rank 82); the live host must
 * intercept Location.isFromMockProvider() to return false at the
 * Java-API surface. Apps that bypass the wrapper and read the
 * Settings.Secure key directly are covered by the settings-put
 * boot script (separate from this LSPosed module).
 */
package io.spoofstack.redroid.hooks;

import android.location.Location;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

public final class LocationHook {

    private LocationHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false));
    }
}

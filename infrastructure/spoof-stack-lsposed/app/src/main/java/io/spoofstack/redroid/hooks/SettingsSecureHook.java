/*
 * SettingsSecureHook — §4.1 of production-hooks-spec.md.
 *
 * Per-app surgical Settings.Secure.getString() hook for keys where
 * persistent settings-put would broadcast the value to all apps
 * (rank 82 mock_provider, rank 58 input_method). For identity-stable
 * keys (android_id) the boot-time `settings put` is preferred and this
 * hook does NOT override them.
 */
package io.spoofstack.redroid.hooks;

import android.content.ContentResolver;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class SettingsSecureHook {

    private SettingsSecureHook() { /* no instances */ }

    /** Keys that this hook surgically rewrites at the per-app boundary. */
    private static final Map<String, String> OVERRIDES = new HashMap<>();
    static {
        // location.is_from_mock_provider — wrapper-synthesized key
        OVERRIDES.put("location.is_from_mock_provider", "0");
        // Legacy mock_location flag (API < 23)
        OVERRIDES.put("mock_location", "0");
        OVERRIDES.put("mock_location_app", "");
        // Hide accessibility services list (probe rank 64-ish)
        OVERRIDES.put("enabled_accessibility_services", "");
    }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        if (param.args.length < 2 || !(param.args[1] instanceof String)) {
                            return;
                        }
                        final String key = (String) param.args[1];
                        if (OVERRIDES.containsKey(key)) {
                            param.setResult(OVERRIDES.get(key));
                        }
                    }
                });
    }
}

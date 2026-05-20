/*
 * ResourcesHook — §5.2 of production-hooks-spec.md.
 *
 * Per-context Resources.getConfiguration() carries the locale that
 * apps actually observe through their attached Context. Hooking
 * Locale.getDefault() alone is insufficient — Activity-level reads
 * go through the Context's Resources, not the JVM-global default.
 */
package io.spoofstack.redroid.hooks;

import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class ResourcesHook {

    private ResourcesHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Resources.class, "getConfiguration",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final Object raw = param.getResult();
                        if (!(raw instanceof Configuration)) {
                            return;
                        }
                        final Configuration cfg = (Configuration) raw;
                        // setLocale() mutates the existing Configuration —
                        // safe since the framework returns a per-Resources
                        // copy on most API levels.
                        cfg.setLocale(Locale.US);
                        param.setResult(cfg);
                    }
                });
    }
}

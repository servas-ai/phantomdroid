/*
 * DisplayMetricsHook — §5.6 of production-hooks-spec.md.
 *
 * The production-grade fix is the launch-flag approach (ReDroid
 * --display=1080x2400 + service.d `wm density 420`). This LSPosed hook
 * is the surgical fallback for hosts where launch-flag is not
 * available. It rewrites the DisplayMetrics object after the framework
 * populates it.
 */
package io.spoofstack.redroid.hooks;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Display;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class DisplayMetricsHook {

    private DisplayMetricsHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        // Resources.getDisplayMetrics() — most common path.
        XposedHelpers.findAndHookMethod(Resources.class, "getDisplayMetrics",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final Object raw = param.getResult();
                        if (raw instanceof DisplayMetrics) {
                            applyTo((DisplayMetrics) raw);
                        }
                    }
                });

        // Display.getMetrics(DisplayMetrics) — caller-supplied buffer.
        XposedHelpers.findAndHookMethod(Display.class, "getMetrics",
                DisplayMetrics.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final Object raw = param.args[0];
                        if (raw instanceof DisplayMetrics) {
                            applyTo((DisplayMetrics) raw);
                        }
                    }
                });

        // Display.getRealMetrics(DisplayMetrics) — bypasses status bar
        // / nav bar insets; used by emulator-detection libraries to
        // catch the "logical vs. real screen" mismatch on emulators.
        XposedHelpers.findAndHookMethod(Display.class, "getRealMetrics",
                DisplayMetrics.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final Object raw = param.args[0];
                        if (raw instanceof DisplayMetrics) {
                            applyTo((DisplayMetrics) raw);
                        }
                    }
                });
    }

    private static void applyTo(final DisplayMetrics m) {
        m.widthPixels = SpoofConfig.DISPLAY_WIDTH;
        m.heightPixels = SpoofConfig.DISPLAY_HEIGHT;
        m.densityDpi = SpoofConfig.DISPLAY_DENSITY_DPI;
        m.density = SpoofConfig.DISPLAY_DENSITY;
        m.scaledDensity = SpoofConfig.DISPLAY_DENSITY;
        m.xdpi = SpoofConfig.DISPLAY_XDPI;
        m.ydpi = SpoofConfig.DISPLAY_YDPI;
    }
}

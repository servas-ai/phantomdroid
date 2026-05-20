/*
 * LocaleHook — §5.2 of production-hooks-spec.md.
 *
 * Locale.getDefault() reflects the current Java VM locale; on ReDroid
 * it follows the host kernel's LANG env which is typically POSIX/C.
 * The spec target is Locale.US (en_US).
 */
package io.spoofstack.redroid.hooks;

import java.util.Locale;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

public final class LocaleHook {

    private LocaleHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        // Static Locale.getDefault() — covers both no-arg and category
        // forms (the category overload was added in API 24).
        XposedHelpers.findAndHookMethod(Locale.class, "getDefault",
                XC_MethodReplacement.returnConstant(Locale.US));
        XposedHelpers.findAndHookMethod(Locale.class, "getDefault",
                Locale.Category.class,
                XC_MethodReplacement.returnConstant(Locale.US));
    }
}

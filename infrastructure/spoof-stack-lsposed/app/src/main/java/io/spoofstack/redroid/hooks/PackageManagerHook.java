/*
 * PackageManagerHook — module self-hide.
 *
 * Hides the SpoofStack module's own package from
 * PackageManager.getInstalledPackages() / .getPackageInfo() lookups
 * within the target app's process. This prevents a target app from
 * detecting the module by scanning for "io.spoofstack.redroid" in
 * the installed package list.
 *
 * Note: a sufficiently determined app can still detect LSPosed itself
 * (via classloader inspection, mounted /data/adb/lspd, etc.) — that's
 * handled by LSPosed's own zygisk-side hide layer, not by this module.
 */
package io.spoofstack.redroid.hooks;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class PackageManagerHook {

    private static final String SELF_PACKAGE = "io.spoofstack.redroid";
    private static final String LSPOSED_MANAGER = "org.lsposed.manager";
    private static final String XPOSED_INSTALLER = "de.robv.android.xposed.installer";

    private PackageManagerHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        final Class<?> pm = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", cl);

        // Filter from list queries
        hookListFilter(pm, "getInstalledPackages", int.class);
        hookListFilter(pm, "getInstalledApplications", int.class);

        // Throw NameNotFound for direct lookups of hidden packages
        XposedHelpers.findAndHookMethod(pm, "getPackageInfo",
                String.class, int.class, new HiddenLookupHook());
        XposedHelpers.findAndHookMethod(pm, "getApplicationInfo",
                String.class, int.class, new HiddenLookupHook());
    }

    private static void hookListFilter(final Class<?> pm,
                                        final String method,
                                        final Class<?>... params) {
        XposedHelpers.findAndHookMethod(pm, method, params,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final Object raw = param.getResult();
                        if (!(raw instanceof List)) {
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        final List<Object> list = new ArrayList<>((List<Object>) raw);
                        final Iterator<Object> it = list.iterator();
                        while (it.hasNext()) {
                            final Object item = it.next();
                            final String pkg = extractPackageName(item);
                            if (isHidden(pkg)) {
                                it.remove();
                            }
                        }
                        param.setResult(list);
                    }
                });
    }

    private static String extractPackageName(final Object item) {
        if (item instanceof PackageInfo) {
            return ((PackageInfo) item).packageName;
        }
        if (item instanceof ApplicationInfo) {
            return ((ApplicationInfo) item).packageName;
        }
        return null;
    }

    private static boolean isHidden(final String pkg) {
        return SELF_PACKAGE.equals(pkg)
                || LSPOSED_MANAGER.equals(pkg)
                || XPOSED_INSTALLER.equals(pkg);
    }

    private static final class HiddenLookupHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) {
            if (param.args.length < 1 || !(param.args[0] instanceof String)) {
                return;
            }
            final String pkg = (String) param.args[0];
            if (isHidden(pkg)) {
                param.setThrowable(
                        new PackageManager.NameNotFoundException(pkg));
            }
        }
    }
}

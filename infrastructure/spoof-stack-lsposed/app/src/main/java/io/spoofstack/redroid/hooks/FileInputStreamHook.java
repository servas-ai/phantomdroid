/*
 * FileInputStreamHook — §3.2 of production-hooks-spec.md.
 *
 * /proc/self is a kernel magic symlink that resolves per-process;
 * bind-mounting it would shadow every process's status to identical
 * content (itself a tell). Instead, we intercept the Java
 * FileInputStream(File) constructor and, when the path is
 * /proc/self/status, synthesize a per-process status body that
 * includes the critical "TracerPid: 0" line.
 *
 * Coverage: this hook only intercepts Java-level reads. Apps that go
 * through libc.open() / fopen() via JNI bypass this — the spec calls
 * out that NeoZygisk's frida-detector-counter is the production
 * reference for full native coverage.
 */
package io.spoofstack.redroid.hooks;

import android.os.Process;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class FileInputStreamHook {

    private static final String TAG = "SpoofStack.FIS";
    private static final String TARGET_PATH = "/proc/self/status";

    private FileInputStreamHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        // Hook the FileInputStream(String) and FileInputStream(File)
        // constructors. The String variant is the more common path for
        // apps that pass literal "/proc/self/status".
        try {
            final Constructor<?> ctorFile =
                    java.io.FileInputStream.class.getConstructor(File.class);
            XposedBridge.hookMethod(ctorFile, new RedirectHook());

            final Constructor<?> ctorString =
                    java.io.FileInputStream.class.getConstructor(String.class);
            XposedBridge.hookMethod(ctorString, new RedirectHook());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + " FileInputStream ctor not found: " + e);
        }

        // Files.newInputStream(Path) — modern NIO path used by some
        // libraries. Falls through to FileInputStream internally on
        // most Android implementations but we hook explicitly for
        // belt-and-suspenders.
        try {
            XposedHelpers.findAndHookMethod("java.nio.file.Files", cl,
                    "newInputStream", java.nio.file.Path.class,
                    java.nio.file.OpenOption[].class,
                    new RedirectNioHook());
        } catch (Throwable t) {
            // OpenJDK NIO surface not always present on older Android;
            // failure is non-fatal.
            XposedBridge.log(TAG + " Files.newInputStream hook skipped: " + t);
        }
    }

    /** Generic "is this the magic path?" — handles both String and File ctor args. */
    private static boolean isTargetPath(final Object arg) {
        if (arg instanceof File) {
            return TARGET_PATH.equals(((File) arg).getAbsolutePath());
        }
        if (arg instanceof String) {
            return TARGET_PATH.equals(arg);
        }
        return false;
    }

    private static String synthesizeBody() {
        final int uid = Process.myUid();
        return String.format(SpoofConfig.PROC_SELF_STATUS_TEMPLATE, uid);
    }

    private static final class RedirectHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) {
            if (param.args.length < 1) {
                return;
            }
            if (!isTargetPath(param.args[0])) {
                return;
            }
            // Replace the constructor's effect by setting the result to
            // null and stashing our synthesized stream on the instance.
            // (FileInputStream ctor hooks are unusual — we can't easily
            // replace the resulting object. Instead, downstream reads
            // need to go through a separate read() hook. The simpler
            // approach used in the spec is to hook a higher-level
            // method on the call sites that read this file. We keep
            // the ctor hook as a marker; production deployers should
            // pair this with a hook on the specific call site in the
            // target app or a libc.open() Zygisk shim.)
            param.setThrowable(null);
            // Mark instance for downstream read hooks (left as
            // extension point for the deployer).
        }
    }

    private static final class RedirectNioHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) {
            if (param.args.length < 1 || param.args[0] == null) {
                return;
            }
            final String s = param.args[0].toString();
            if (TARGET_PATH.equals(s)) {
                param.setResult(new ByteArrayInputStream(synthesizeBody().getBytes()));
            }
        }
    }
}

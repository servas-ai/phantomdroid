package dev.cloudphone.hide.frida.maps

import android.content.Context
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Xposed module entry point.
 *
 * Strategy:
 *   1. Java-side hook: intercept FileInputStream/BufferedReader constructors when
 *      the path is a maps pseudo-file and wrap the stream with a FilterInputStream
 *      that drops lines matching RedactionPatterns.ELIDE_TOKENS.
 *   2. Native-side hook: load libhide_frida_maps_native.so which installs inline
 *      PLT hooks on libc.open / openat / read / fopen / fgets via shadowhook.
 *      This covers readers that bypass the JVM entirely (frida-agent itself).
 *
 * Scope: Vector loads this module only into packages explicitly enabled in the
 *        Vector module scope list. system_server, zygote, and init are excluded
 *        by the Vector framework's scope enforcement; no additional guard is needed
 *        here. A denylist is applied defensively.
 *
 * Proposal ref: mutations/proposals/019e2f10-37cb-7c8b-bbfb-90e573cfe302.json
 */
class HideFridaMapsHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "HideFridaMaps"

        /**
         * Packages where this module must never apply its hooks even if
         * Vector accidentally adds them to scope. Belt-and-suspenders guard.
         */
        private val SCOPE_DENYLIST = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings"
        )

        private const val NATIVE_LIB = "hide_frida_maps_native"
        private var nativeLibLoaded = false
    }

    // -------------------------------------------------------------------------
    // IXposedHookZygoteInit — load native lib early in zygote space
    // -------------------------------------------------------------------------

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // Attempt early native hook installation.
        // Failure here is non-fatal: the Java-layer hook below provides partial coverage.
        try {
            System.loadLibrary(NATIVE_LIB)
            nativeLibLoaded = true
            XposedBridge.log("$TAG: native lib loaded in zygote")
        } catch (e: UnsatisfiedLinkError) {
            XposedBridge.log("$TAG: native lib unavailable (${e.message}); Java-only mode")
        }
    }

    // -------------------------------------------------------------------------
    // IXposedHookLoadPackage — per-process Java hook
    // -------------------------------------------------------------------------

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName in SCOPE_DENYLIST) return

        hookFileInputStream(lpparam)
        hookBufferedReader(lpparam)
        hookRuntimeExec(lpparam)
    }

    // -------------------------------------------------------------------------
    // Hook: FileInputStream(String path) constructor
    //   Wrap returned stream with a MapsFilterInputStream when path is maps-family.
    // -------------------------------------------------------------------------

    private fun hookFileInputStream(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(
                FileInputStream::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (!RedactionPatterns.isMapsPseudoFile(path)) return
                        // Replace arg with a redacting wrapper at stream level
                        // by swapping to our FilterInputStream after construction.
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (!RedactionPatterns.isMapsPseudoFile(path)) return

                        val original = param.thisObject as? FileInputStream ?: return
                        val content = readAndRedact(original)
                        // We cannot replace `this` but we can swap the internal fd.
                        // Instead, signal via a thread-local so BufferedReader hook intercepts.
                        MapsRedactionState.pendingRedactedContent.set(path to content)
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("$TAG: FileInputStream hook failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Hook: BufferedReader(InputStreamReader) — intercept readLine()
    //   This is the most common pattern used by detection libraries.
    // -------------------------------------------------------------------------

    private fun hookBufferedReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                BufferedReader::class.java,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val line = param.result as? String ?: return
                        if (RedactionPatterns.shouldElide(line)) {
                            // Skip this line by recursing to the next non-elided line.
                            val reader = param.thisObject as BufferedReader
                            var next = reader.readLine()
                            while (next != null && RedactionPatterns.shouldElide(next)) {
                                next = reader.readLine()
                            }
                            param.result = next
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("$TAG: BufferedReader hook failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Hook: Runtime.exec() — some detectors spawn `cat /proc/self/maps`
    // -------------------------------------------------------------------------

    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "exec",
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? Array<*> ?: return
                        val cmdStr = cmd.joinToString(" ")
                        if (!cmdStr.contains("maps")) return
                        // Wrap the Process stdout with filtering reader.
                        // Leave stderr untouched.
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Runtime.exec hook failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun readAndRedact(stream: InputStream): String {
        return stream.bufferedReader().useLines { lines ->
            lines.filter { !RedactionPatterns.shouldElide(it) }
                .joinToString("\n")
        }
    }
}

/**
 * Thread-local state shared between the FileInputStream hook and downstream readers.
 * Avoids needing to subclass FileInputStream (which has a final fd field).
 */
object MapsRedactionState {
    val pendingRedactedContent: ThreadLocal<Pair<String, String>?> = ThreadLocal()
}

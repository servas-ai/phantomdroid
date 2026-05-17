package com.detectorlab.core

/**
 * Probe-side abstraction over android.content.Context that lets unit tests
 * provide a fake ProbeContext without instantiating the Android framework.
 *
 * Production impl wraps android.content.Context.
 * Test impl provides controlled fakes.
 */
interface ProbeContext {
    fun getSystemProperty(key: String): String?
    fun fileExists(path: String): Boolean
    fun readFile(path: String, maxBytes: Int = 8192): String?
    fun querySettingSecure(key: String): String?
    fun queryTelephonyManager(field: TelephonyField): String?
    fun queryPackageManager(): PackageManagerView
    fun querySensorManager(): SensorManagerView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.app.KeyguardManager`.
     */
    fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.net.wifi.WifiManager`.
     */
    fun queryWifiManager(): WifiManagerView = UnknownWifiManagerView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.media.projection.MediaProjectionManager` plus the
     * `WindowManager.addScreenRecordingCallback` (API 35+) and
     * `Window.addScreenCaptureCallback` (API 34+) registrations performed at
     * application start.
     */
    fun queryMediaProjectionManager(): MediaProjectionManagerView =
        UnknownMediaProjectionManagerView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.os.UserHandle.myUserId()`.
     */
    fun queryUserHandle(): UserHandleView = UnknownUserHandleView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a real
     * TimeView that reads SystemClock, System.currentTimeMillis, Location, and NTP.
     */
    fun queryTimeView(): TimeView = UnknownTimeView
}

/** Conservative default: claims sdkInt=0 and answers `null` for every probe. */
object UnknownKeyguardManagerView : KeyguardManagerView {
    override fun sdkInt(): Int = 0
    override fun isDeviceSecure(): Boolean? = null
    override fun isKeyguardSecure(): Boolean? = null
}

/**
 * Shell access is INTENTIONALLY NOT in the base ProbeContext.
 *
 * Round-2.5 Finding F36 (architecture-strategist) + F34 (security-auditor):
 * `runShellCommand(cmd: String)` was a leaky capability that punted security
 * policy to each probe author. The fix is to require shell-using probes to
 * declare a separate capability surface via `ShellProbeContext` with an
 * explicit static-string allowlist enforced by ProbeRunner.
 *
 * Specific probes that require shell (Probe #3 root.su_search, Probe #14
 * root.selinux) opt in by accepting `ShellProbeContext` instead of
 * `ProbeContext`. The allowlist is reviewed at PR time, not at runtime.
 */
interface ShellProbeContext : ProbeContext {
    /** Run a SHELL COMMAND from the static allowlist. Throws if cmd not in allowlist. */
    fun runAllowlistedCommand(cmdId: AllowlistedCommand, timeoutMs: Long = 1000L): ShellResult
}

/** The complete set of permitted shell invocations. Add via PR review only. */
enum class AllowlistedCommand {
    GETPROP_ALL,            // `getprop` — full property dump
    LS_SU_PATHS,            // `ls /sbin/su /system/bin/su /system/xbin/su` (presence check only)
    GETENFORCE,             // `getenforce` — SELinux mode
    UNAME_R,                // `uname -r` — kernel version
    CAT_PROC_VERSION,       // `cat /proc/version` — kernel build banner
    CAT_PROC_CPUINFO,       // `cat /proc/cpuinfo` — CPU architecture probe
    DUMPSYS_BATTERY,        // `dumpsys battery` — battery state probe
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

enum class TelephonyField { IMEI, SERIAL, OPERATOR_NAME, MCC_MNC, SIM_SERIAL }

interface PackageManagerView {
    fun isPackageInstalled(packageName: String): Boolean
    fun listInstalledPackages(): List<String>
    /** Returns package names that have been granted [permission] (API ≥23). */
    fun listPackagesWithPermission(permission: String): List<String>
}

interface SensorManagerView {
    fun listSensorTypes(): List<Int>
    fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample
}

/**
 * Read-only view of android.app.KeyguardManager. Implementations must return
 * `null` from the `is*` queries when the answer cannot be determined (API <23
 * lacks `isDeviceSecure`, or the system service threw). Callers MUST treat a
 * `null` reply as "unknown" rather than "false", per freeRASP D1 contract.
 */
interface KeyguardManagerView {
    /**
     * Android `Build.VERSION.SDK_INT` as observed by the runtime.
     * Used by probes to gate API-version-conditional logic.
     */
    fun sdkInt(): Int

    /**
     * `KeyguardManager.isDeviceSecure()`. API ≥23. Returns:
     *   true  — PIN, pattern, password, or biometric is configured
     *   false — no secure lock configured
     *   null  — API <23, or the system service threw / returned indeterminately
     */
    fun isDeviceSecure(): Boolean?

    /**
     * `KeyguardManager.isKeyguardSecure()`. Available since API 16. Returns:
     *   true  — keyguard is secured (any non-`None` method)
     *   false — keyguard is "Slide" / "None"
     *   null  — system service threw / returned indeterminately
     */
    fun isKeyguardSecure(): Boolean?
}

data class SensorSample(val timestamps: LongArray, val values: Array<FloatArray>)

/**
 * Read-only view of android.net.wifi.WifiManager. The probe surface is the
 * *security type* of the currently associated network, not the SSID or BSSID,
 * so this view deliberately avoids exposing PII-grade fields.
 *
 * Implementations MUST return:
 *   - `SecurityType.UNAVAILABLE` if the caller lacks `ACCESS_FINE_LOCATION`
 *     and/or `NEARBY_WIFI_DEVICES` (API 33+) — probes treat this as "skipped".
 *   - `SecurityType.NOT_CONNECTED` if the device is not currently associated
 *     with any Wi-Fi network.
 *   - The actual security type otherwise.
 */
interface WifiManagerView {
    /** Android `Build.VERSION.SDK_INT` — gates API-31+ vs deprecated paths. */
    fun sdkInt(): Int

    /** True iff the caller currently holds the permission required to read
     *  Wi-Fi network details (`ACCESS_FINE_LOCATION` < API 33,
     *  `NEARBY_WIFI_DEVICES` >= API 33). */
    fun hasWifiAccessPermission(): Boolean

    /**
     * Returns the security type of the currently associated network, or the
     * appropriate sentinel value. The "method" output names which underlying
     * API was used so probes can report it back to evidence.
     */
    fun currentNetworkSecurityType(): WifiSecurityRead
}

enum class WifiSecurityType {
    NONE,           // open network
    WEP,            // deprecated since API 28; treated as insecure
    WPA,            // WPA-PSK / WPA-EAP
    WPA2,           // WPA2-PSK / WPA2-EAP
    WPA3,           // WPA3-SAE / WPA3-Enterprise
    UNKNOWN,        // associated but security type unrecognised
    NOT_CONNECTED,  // no current Wi-Fi association
    UNAVAILABLE,    // required permission missing → caller should skip
}

/**
 * Result of a Wi-Fi security-type read.
 *
 * @property type      classified security type
 * @property apiPath   which underlying API was used — "WifiManager.getCurrentNetwork+NetworkCapabilities"
 *                     on API >=31, "WifiConfiguration.allowedKeyManagement" on older.
 */
data class WifiSecurityRead(val type: WifiSecurityType, val apiPath: String)

/** Conservative default: the runtime has no Wi-Fi information at all. */
object UnknownWifiManagerView : WifiManagerView {
    override fun sdkInt(): Int = 0
    override fun hasWifiAccessPermission(): Boolean = false
    override fun currentNetworkSecurityType(): WifiSecurityRead =
        WifiSecurityRead(WifiSecurityType.UNAVAILABLE, "default-stub")
}

/**
 * Read-only view of the screen-capture / screen-recording subsystem.
 *
 * The Android platform exposes two distinct callbacks for capture detection:
 *
 *   • `Window.OnScreenCaptureCallback` — API 34+ (Android 14, DETECT_SCREEN_CAPTURE
 *     permission). Fires once when a screenshot is taken of an Activity window
 *     registered via `Window.addScreenCaptureCallback`. In this view we call
 *     this signal `screenCaptureCallback` and gate it on `sdkInt() >= 34`.
 *
 *   • `WindowManager.ScreenRecordingCallback` — API 35+ (Android 15,
 *     DETECT_SCREEN_RECORDING permission). Fires whenever a MediaProjection
 *     session that includes any window of the registering UID transitions
 *     between visible and not-visible. In this view we call this signal
 *     `mediaProjectionFrameCapture` and gate it on `sdkInt() >= 35`. This is
 *     the canonical "MediaProjection session is live" oracle from API 35
 *     onward.
 *
 * The naming inside this view follows the issue acceptance text
 * (`MediaProjectionManager` callback + `Window.OnFrameCaptureListener`)
 * rather than the precise Android class names so the contract reads against
 * CLO-8 verbatim; the production wrapper translates these to the real
 * platform classes.
 *
 * Implementations MUST return `null` from the `is*` queries when the answer
 * is unknown (callbacks have not yet been registered, system service threw,
 * or the API level does not support the signal). The probe treats `null` as
 * "no signal" rather than "false".
 */
interface MediaProjectionManagerView {
    /** Android `Build.VERSION.SDK_INT` — gates API-34 vs API-35 signal paths. */
    fun sdkInt(): Int

    /**
     * `Window.OnScreenCaptureCallback` (API 34+) — true iff a screenshot
     * capture event has been observed since the last reset. Returns `null`
     * on API <34, or when the callback has not been registered.
     */
    fun isScreenCaptureCallbackActive(): Boolean?

    /**
     * `WindowManager.ScreenRecordingCallback` (API 35+) — true iff a
     * MediaProjection screen-recording session is currently capturing any
     * window of the registering UID. Returns `null` on API <35, or when the
     * callback has not been registered.
     */
    fun isMediaProjectionFrameCaptureActive(): Boolean?
}

/** Conservative default: pre-A14, no callbacks registered. */
object UnknownMediaProjectionManagerView : MediaProjectionManagerView {
    override fun sdkInt(): Int = 0
    override fun isScreenCaptureCallbackActive(): Boolean? = null
    override fun isMediaProjectionFrameCaptureActive(): Boolean? = null
}

/**
 * Read-only view of android.os.UserHandle for multi-instance / clone-app detection.
 *
 * `UserHandle.myUserId()` returns 0 for the primary (owner) user and a non-zero
 * integer for any secondary profile (work profile, clone space, guest user).
 * Running in a secondary profile is a strong signal that multi-instance / clone-app
 * isolation is in effect.
 */
interface UserHandleView {
    /**
     * `UserHandle.myUserId()`. Returns:
     *   0     — primary (owner) user; normal single-instance execution
     *   > 0   — secondary user / managed profile / clone space
     *   null  — could not be determined (reflection failed, or API unavailable)
     */
    fun myUserId(): Int?
}

/** Conservative default: cannot determine the user ID. */
object UnknownUserHandleView : UserHandleView {
    override fun myUserId(): Int? = null
}

/**
 * Read-only view of time sources used by TimeSpoofingProbe.
 *
 * Exposes four independent clocks so the probe can cross-validate them
 * against each other per freeRASP T15:
 *
 *   • elapsedRealtime — monotonic uptime clock (SystemClock.elapsedRealtime)
 *   • wallClock       — wall-clock UTC ms (System.currentTimeMillis)
 *   • gpsTimestamp    — time embedded in the last GPS fix (Location.getTime), null if unavailable
 *   • ntpTimestamp    — time from a reachable NTP server, null if network unavailable
 *
 * Production impls query the real platform sources.
 * Test fakes supply controlled values to exercise threshold crossings.
 */
interface TimeView {
    /** `SystemClock.elapsedRealtime()` in milliseconds. */
    fun elapsedRealtimeMs(): Long

    /** `System.currentTimeMillis()` in milliseconds since Unix epoch. */
    fun wallClockMs(): Long

    /**
     * Time extracted from the last known GPS fix (`Location.getTime()`).
     * Returns `null` when no fix has been obtained or location permission is absent.
     */
    fun gpsTimestampMs(): Long?

    /**
     * Time returned by an NTP query to `time.android.com` (fallback: `pool.ntp.org`).
     * Returns `null` when the network is unreachable or the query timed out.
     */
    fun ntpTimestampMs(): Long?
}

/** Conservative default: all time sources unavailable (network-less, no GPS). */
object UnknownTimeView : TimeView {
    override fun elapsedRealtimeMs(): Long = System.currentTimeMillis()
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun gpsTimestampMs(): Long? = null
    override fun ntpTimestampMs(): Long? = null
}

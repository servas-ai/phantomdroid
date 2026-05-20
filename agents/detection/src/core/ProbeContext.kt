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
     * Read a `Settings.Global` key. Default delegates to `querySettingSecure`
     * for backward compatibility with fakes that predate the split. Production
     * implementations MUST override to read the actual `Settings.Global`
     * namespace.
     *
     * Closes cross-cutting #3: `AutomationToolsProbe`, `DeveloperOptionsProbe`,
     * and future Settings.Global-class probes can now query the correct
     * namespace explicitly without assuming the production wrapper bridges
     * Secure↔Global.
     */
    fun querySettingGlobal(key: String): String? = querySettingSecure(key)

    /**
     * Read a `Settings.System` key. Default delegates to `querySettingSecure`
     * for the same backward-compatibility reason as `querySettingGlobal`.
     * Production wrappers override to read `Settings.System`.
     */
    fun querySettingSystem(key: String): String? = querySettingSecure(key)

    /**
     * Read the default `BluetoothAdapter.getAddress()` value for the calling
     * thread. Returns `null` by default — same backward-compat shape as
     * `queryKeyguardManager` / `queryWifiManager` / `querySettingGlobal`:
     * fakes that predate this method continue to compile and report "no
     * Bluetooth observation possible". Production impls override with a
     * wrapper around `android.bluetooth.BluetoothAdapter.getDefaultAdapter()
     * .getAddress()`.
     *
     * `BluetoothMacProbe` (rank 31) consumes this via its
     * `bluetoothAdapterMacSupplier` constructor parameter — the supplier
     * resolves to `{ ctx.queryBluetoothAdapterMac() }` at the spawn site so
     * `ProbeRunner.runAll` continues to work with the default no-arg probe
     * ctor on any `ProbeContext` impl that overrides this method. The
     * accessor returns a normalized lowercase colon-separated MAC string
     * (e.g. `"3c:5a:b4:8d:f1:27"`) or `null` when:
     *   - the platform has no BluetoothAdapter (containerized hosts), OR
     *   - the calling app lacks LOCAL_MAC_ADDRESS permission AND
     *     `BluetoothAdapter.getAddress()` redacts to the Android-6+ privacy
     *     default (the probe handles the redaction case explicitly via its
     *     `02:00:00:00:00:00` branch).
     */
    fun queryBluetoothAdapterMac(): String? = null

    /**
     * Read the device's current Olson timezone identifier (e.g. `"America/Los_Angeles"`).
     * Returns `null` by default — same backward-compat shape as
     * `queryBluetoothAdapterMac` / `queryKeyguardManager`: fakes that predate
     * this method continue to compile and report "no timezone observation
     * possible". Production impls override with `TimeZone.getDefault().id`.
     *
     * `TimezoneLocaleProbe` (rank 20) consumes this via its
     * `timezoneIdSupplier` constructor parameter — the supplier resolves to
     * `{ ctx.queryTimezoneId() }` at the spawn site so `ProbeRunner.runAll`
     * works with the default no-arg probe ctor on any `ProbeContext` impl
     * that overrides this method. Closes the probe-quality gap where the
     * Iter-1 default read `java.util.TimeZone.getDefault().id` directly,
     * which leaked the host JVM timezone into the probe result and scored
     * 1.00 (`mismatch`) on the FullProbeRunnerSpoofTest when the host JVM
     * was in Europe/Berlin but the spoofed locale was US.
     */
    fun queryTimezoneId(): String? = null

    /**
     * Read the device's current timezone UTC offset in minutes (positive for
     * east of UTC, negative for west). Returns `null` by default.
     * Production impls override with
     * `TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000`.
     *
     * Consumed by `TimezoneLocaleProbe` evidence row `timezone_offset_minutes`.
     * Score-bearing only as supplementary context — the probe scores the
     * timezone-id ↔ country mismatch, not the offset directly.
     */
    fun queryTimezoneOffsetMinutes(): Int? = null

    /**
     * Read the device's current locale language code (ISO 639-1, lowercase,
     * e.g. `"en"`). Returns `null` by default. Production impls override
     * with `Locale.getDefault().language`.
     *
     * Consumed by `TimezoneLocaleProbe` (rank 20) AND `LanguageCountryProbe`
     * (rank 36). Closes the probe-quality gap where the Iter-1 default
     * read `java.util.Locale.getDefault().language` directly, which leaked
     * the host JVM locale into the probe result.
     */
    fun queryLocaleLanguage(): String? = null

    /**
     * Read the device's current locale country code (ISO 3166-1 alpha-2,
     * uppercase, e.g. `"US"`). Returns `null` by default. Production impls
     * override with `Locale.getDefault().country`.
     *
     * Empty-string is a distinct signal from `null` (locale set to `Locale.ROOT`
     * with country stripped vs. accessor failed). Probes that distinguish
     * these two cases must compare against `""` and `null` separately.
     */
    fun queryLocaleCountry(): String? = null

    /**
     * Read the device's current locale display name (e.g. `"English (United States)"`).
     * Returns `null` by default. Production impls override with
     * `Locale.getDefault().displayName`.
     *
     * Consumed by `LanguageCountryProbe` evidence-only — recorded for
     * forensic review, not directly scored.
     */
    fun queryLocaleDisplayName(): String? = null

    /**
     * Read the device's `DisplayMetrics` (resolution, density, xdpi/ydpi).
     * Returns `null` by default — same backward-compat shape as
     * `queryBluetoothAdapterMac` / `queryTimezoneId`: fakes that predate this
     * method continue to compile and report "no display observation".
     * Production impls override with a wrapper around
     * `WindowManager.defaultDisplay.getMetrics(DisplayMetrics())`.
     *
     * Returning a single grouped view rather than five separate accessors
     * mirrors the existing `KeyguardManagerView` / `WifiManagerView` pattern
     * (a related cluster of fields share one view object). Probes that need
     * individual signals do `ctx.queryDisplayMetrics()?.widthPixels` etc.
     *
     * `ScreenResolutionProbe` (rank 23) consumes this via its supplier
     * cascade — the no-arg constructor routes `width/height/density/xdpi/
     * ydpi` reads through this accessor. Closes the constructor-supplier
     * gap where the Iter-1 defaults returned `null` and the probe always
     * scored 0.5 (`SCORE_NO_DISPLAY`) in production.
     */
    fun queryDisplayMetrics(): DisplayMetricsView? = null

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.app.KeyguardManager`.
     */
    fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView

    /**
     * Default returns the "unknown" view so existing fakes that predate this
     * method continue to compile. Production impls override with a wrapper
     * around `android.location.LocationManager.getLastKnownLocation()` plus
     * `Location.isFromMockProvider()` (API >= 18) on the most recently
     * delivered fix.
     *
     * Consumed by `GpsCoordinatesProbe` (rank 41) — same backward-compat
     * shape as `queryKeyguardManager`. The view exposes the small set of
     * fields the probe scores against (mock-provider flag, sentinel
     * coordinates, accuracy floor, provider name) and intentionally does
     * NOT expose PII-grade fix history beyond the single most recent
     * fix-frame. `null` from any per-field accessor means "permission
     * missing OR no fix recorded yet" — probes must distinguish that
     * from "fix observed with field literally zero" by combining the
     * `hasLastKnownLocation()` boolean with the per-field reads.
     */
    fun queryLocationManager(): LocationManagerView = UnknownLocationManagerView

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

    /**
     * Read library/object names observed in `/proc/self/maps` for the
     * calling process. Returns an empty set by default — fakes/contexts
     * that predate this accessor report "no libraries observed" (the
     * conservative answer). Production wrappers parse `/proc/self/maps`
     * once and return the case-folded set of basename tokens.
     *
     * Consumed by `FridaMemoryMapsProbe` (rank 9.0). The accessor surfaces
     * the static-library half of the rank-9.0 detection cascade.
     */
    fun queryProcSelfMapsLibs(): Set<String> = emptySet()

    /**
     * Read thread names from `/proc/self/task/<tid>/comm`. Returns an
     * empty set by default. Production wrappers enumerate the task tree
     * and read each `comm` file. Consumed by `FridaMemoryMapsProbe` —
     * Frida's gum runtime spawns canonical thread names (`gum-js-loop`,
     * `gmain`, `gdbus`) that are dispositive evidence of in-process
     * instrumentation.
     */
    fun queryRuntimeThreadNames(): Set<String> = emptySet()

    /**
     * Read currently bound TCP ports from `/proc/net/tcp`. Returns an
     * empty set by default. Consumed by `FridaMemoryMapsProbe` — port
     * 27042 / 27043 are Frida's canonical server-listen ports.
     */
    fun queryOpenTcpPorts(): Set<Int> = emptySet()

    /**
     * Read the per-function prologue-hash delta map produced by a runtime
     * native-side comparison of in-memory bytes against the on-disk
     * baseline. Returns an empty map by default — JVM-side contexts
     * cannot perform this measurement, and the conservative "no
     * observation" answer is "absent" (NOT "clean"; signal absence is
     * scored as zero, not as a negative signal).
     *
     * Consumed by `NativePrologueHashProbe` (rank 9.7, mitigation_layer
     * `not_spoofable`). Snapshot-side context overrides this method to
     * surface a previously captured measurement.
     */
    fun queryPrologueHashDeltas(): Map<String, Boolean> = emptyMap()

    /**
     * Read the count of MOV X16 / BR X16 (AArch64) trampoline patterns
     * observed across scanned function prologues. Returns 0 by default —
     * the conservative "no observation" answer. Consumed by
     * `NativePrologueHashProbe` (rank 9.7).
     */
    fun queryTrampolinePatternCount(): Int = 0

    /**
     * Read the GOT/PLT entry anomaly map produced by a runtime native-side
     * comparison of resolved global-offset-table function pointers against
     * the expected (canonical) target library. Returns an empty map by
     * default. Consumed by `PrologueGotHooksProbe` (rank 9.8,
     * mitigation_layer `not_spoofable`).
     */
    fun queryGotPltAnomalies(): Map<String, String> = emptyMap()

    /**
     * Read the list of `rwxp` (simultaneously writable AND executable)
     * memory segments observed in `/proc/self/maps`. Returns an empty
     * list by default. Each non-empty entry is a smoking-gun signal of a
     * patched-text-section / inline-hook installer. Consumed by
     * `PrologueGotHooksProbe` (rank 9.8).
     */
    fun queryRwxpMemorySegments(): List<String> = emptyList()
}

/** Conservative default: claims sdkInt=0 and answers `null` for every probe. */
object UnknownKeyguardManagerView : KeyguardManagerView {
    override fun sdkInt(): Int = 0
    override fun isDeviceSecure(): Boolean? = null
    override fun isKeyguardSecure(): Boolean? = null
}

/**
 * Read-only view of `android.location.LocationManager` reduced to the single
 * most-recent fix-frame the probe surface needs. Implementations must return:
 *   - `hasLastKnownLocation() == null` when the caller lacks `ACCESS_FINE_LOCATION`
 *     / `ACCESS_COARSE_LOCATION` OR the production wrapper threw — probes
 *     read `null` as "permission missing, signal unavailable".
 *   - `hasLastKnownLocation() == false` when the framework is reachable but
 *     no provider has yet delivered a fix (cold-boot, airplane mode, indoors
 *     without network locality). This is the load-bearing branch for the
 *     phone-class missing-fix rule in `GpsCoordinatesProbe`.
 *   - Per-field accessors return `null` when `hasLastKnownLocation()` is null
 *     or false; they may also return `null` individually when the underlying
 *     `Location` field is unset on the most recent fix (e.g. some providers
 *     omit `accuracy`).
 *
 * `isMockProvider()` reads `Location.isFromMockProvider()` introduced in API 18.
 * On older SDKs it returns `null` (the conservative answer). Distinct from
 * the rank-39 `LocationMockProbe` settings-secure sentinel: this is the
 * runtime fix-frame flag, not the package/permission audit.
 */
interface LocationManagerView {
    /** Android `Build.VERSION.SDK_INT` — gates the API-18 `isMockProvider` path. */
    fun sdkInt(): Int

    /**
     * Returns true iff `LocationManager.getLastKnownLocation()` returned a
     * non-null `Location` for at least one provider. Returns `false` when
     * the framework is reachable but no fix exists yet. Returns `null` when
     * permission is missing OR the accessor failed.
     */
    fun hasLastKnownLocation(): Boolean?

    /** Latitude (decimal degrees, WGS84) of the most-recent fix, or null. */
    fun lastKnownLatitude(): Double?

    /** Longitude (decimal degrees, WGS84) of the most-recent fix, or null. */
    fun lastKnownLongitude(): Double?

    /**
     * Horizontal accuracy radius in meters (`Location.getAccuracy()`) of the
     * most-recent fix, or null. Real GPS fixes are rarely better than ~3m;
     * sub-meter values are a mock-provider tell.
     */
    fun lastKnownAccuracy(): Float?

    /**
     * Provider that produced the most-recent fix
     * (`LocationManager.GPS_PROVIDER` / `NETWORK_PROVIDER` / `FUSED_PROVIDER`).
     * Canonical lowercase strings: `"gps"`, `"network"`, `"fused"`.
     */
    fun lastKnownProvider(): String?

    /**
     * `Location.isFromMockProvider()` on the most-recent fix. API 18+.
     * Returns `null` on API <18 OR when no fix exists. `true` is the
     * canonical mock-location ground-truth signal.
     */
    fun isMockProvider(): Boolean?
}

/** Conservative default: claims sdkInt=0 and answers null/false for every field. */
object UnknownLocationManagerView : LocationManagerView {
    override fun sdkInt(): Int = 0
    override fun hasLastKnownLocation(): Boolean? = null
    override fun lastKnownLatitude(): Double? = null
    override fun lastKnownLongitude(): Double? = null
    override fun lastKnownAccuracy(): Float? = null
    override fun lastKnownProvider(): String? = null
    override fun isMockProvider(): Boolean? = null
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

/**
 * Per-field selector for [ProbeContext.queryTelephonyManager].
 *
 *  - `IMEI`           — `TelephonyManager.getImei()` (`getDeviceId()` pre-A8).
 *  - `SERIAL`         — `Build.getSerial()` (post-A8; pre-A8 `Build.SERIAL`).
 *  - `OPERATOR_NAME`  — `TelephonyManager.getNetworkOperatorName()`.
 *  - `MCC_MNC`        — `TelephonyManager.getNetworkOperator()` (5-6 digits).
 *  - `SIM_SERIAL`     — `TelephonyManager.getSimSerialNumber()` (ICCID).
 *  - `LINE1_NUMBER`   — `TelephonyManager.getLine1Number()` (phone MSISDN).
 *    Power-13 Gap #6 — AOSP emulator + Genymotion ship the 16-entry
 *    `15555215554..15555215584` block; rank-22 scores these as
 *    dispositive emulator markers. Read requires `READ_PHONE_NUMBERS`
 *    (A8+) or `READ_SMS` / `READ_PHONE_STATE` on older SDKs; returns
 *    null when permission missing (the conservative answer).
 *  - `SUBSCRIBER_ID`  — `TelephonyManager.getSubscriberId()` (IMSI,
 *    15 digits). Power-13 Gap #6 — AOSP emulator ships canonical IMSI
 *    `310260000000000` (T-Mobile MCC/MNC with 10-zero suffix); rank-22
 *    scores this exact value as dispositive. Read requires
 *    `READ_PRIVILEGED_PHONE_STATE` (A10+ system-only); returns null on
 *    consumer-app contexts even on a real device — null is the
 *    expected answer for non-system callers and is NOT scored as
 *    suspicious.
 */
enum class TelephonyField { IMEI, SERIAL, OPERATOR_NAME, MCC_MNC, SIM_SERIAL, LINE1_NUMBER, SUBSCRIBER_ID }

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
 * Read-only view of `android.util.DisplayMetrics` (resolution + density).
 * Implementations must return `null`-shaped semantics on every field when the
 * answer cannot be determined (production wrapper threw, OR caller is a
 * background service without an Activity-attached Window).
 *
 * Consumed by `ScreenResolutionProbe` (rank 23). The probe's no-arg
 * constructor reads `ctx.queryDisplayMetrics()?.widthPixels` and falls back
 * to `SCORE_NO_DISPLAY=0.5` when the accessor returns `null` (no display
 * observation possible — the conservative answer).
 *
 * Production wrapper: `WindowManager.defaultDisplay.getMetrics(DisplayMetrics())`
 * on API <30, `WindowManager.getCurrentWindowMetrics().bounds` plus
 * `Resources.getDisplayMetrics().densityDpi/xdpi/ydpi` on API ≥30.
 *
 * @property widthPixels   Display width in physical pixels (long edge)
 * @property heightPixels  Display height in physical pixels (short edge)
 * @property densityDpi    Logical density (160/240/320/420/480/560/...)
 * @property xdpi          Physical horizontal pixels-per-inch (real devices
 *                         report a value distinct from densityDpi)
 * @property ydpi          Physical vertical pixels-per-inch
 */
interface DisplayMetricsView {
    fun widthPixels(): Int?
    fun heightPixels(): Int?
    fun densityDpi(): Int?
    fun xdpi(): Float?
    fun ydpi(): Float?
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

/**
 * One sampling window of a single sensor.
 *
 * **Invariants (production wrapper guarantees, fakes must honor)**:
 *  - `timestamps.size == values.size` (one timestamp per sample frame).
 *  - All `values[i]` have the same length within a single SensorSample
 *    (uniform axis count per sensor type; e.g. 3 for accelerometer, 1 for
 *    proximity). Probes that index `values[0].size` may rely on this.
 *  - `values[i][j]` is the j-th axis reading at `timestamps[i]`.
 *
 * Cross-cutting #6 (audit/cross-cutting-followups-2026-05-19.md): the
 * uniform-axis-count invariant was previously undocumented; this KDoc
 * closes the contract gap.
 */
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

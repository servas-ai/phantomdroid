// apps/detector-app/.../context/AndroidProbeContext.kt
//
// Production `ProbeContext` backed by REAL Android framework APIs. This is the
// on-device counterpart to `SnapshotReplayContext` (which replays a frozen
// DeviceSnapshot in a host JVM). Any probe that compiles against
// `ProbeContext` runs against this class with no further adaptation — the
// difference is purely the data source: live ART/Android surfaces here vs.
// captured YAML there.
//
// Closes audit/apk-in-container-2026-05-29.md §4: the in-process-only signals
// that adb-shell could not reach are now read in-process by this context:
//   - Settings.Secure/Global/System  via ContentResolver
//   - TelephonyManager IMEI/ICCID/IMSI/operator
//   - WifiManager MAC + security type
//   - /proc/self/* TracerPid + maps of the APP process (not adbd)
//   - BluetoothAdapter MAC, KeyguardManager, LocationManager, SensorManager
//   - PackageManager install source + installed-package enumeration
//
// GRACEFUL DEGRADATION CONTRACT (task requirement): a non-Play, no-runtime-
// permission ReDroid container will deny most privileged reads. Every accessor
// here MUST catch SecurityException / NPE / reflection failure and return the
// conservative "no observation" answer (null / empty / Unknown* view). The
// probes already treat that as ABSTAIN, never as a CLEAN verdict. NOTHING in
// this file may throw out of an accessor — the on-device runner must not crash.

package com.detectorlab.detectorapp.context

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.detectorlab.core.DisplayMetricsView
import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.LocationManagerView
import com.detectorlab.core.MediaProjectionManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.TimeView
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.core.UnknownLocationManagerView
import com.detectorlab.core.UnknownTimeView
import com.detectorlab.core.UnknownWifiManagerView
import com.detectorlab.core.UserHandleView
import com.detectorlab.core.WifiManagerView
import com.detectorlab.core.WifiSecurityRead
import com.detectorlab.core.WifiSecurityType
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * `ProbeContext` whose answers are read live from the booted Android runtime.
 *
 * @param appContext application `Context` (use `applicationContext`, never an
 *        Activity context — this object may outlive the launching Activity when
 *        the headless instrumented runner drives the pipeline).
 */
class AndroidProbeContext(private val appContext: Context) : ProbeContext {

    private val sdkInt: Int = Build.VERSION.SDK_INT

    // ---- system properties (android.os.SystemProperties via reflection) -----
    //
    // SystemProperties is @hide; on a container it is reachable by reflection.
    // We also map a handful of canonical keys onto the public Build.* fields so
    // the buildprop.* probes (fingerprint, model/brand/manufacturer, tags/type,
    // board/hardware) get a real answer even when the reflection path is denied.

    private val systemPropertiesGet: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        }.getOrNull()
    }

    override fun getSystemProperty(key: String): String? {
        // Public Build.* mirrors first — always available, never throws.
        buildPropertyMirror(key)?.let { return it }
        val m = systemPropertiesGet ?: return null
        return runCatching {
            (m.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    @SuppressLint("HardwareIds")
    private fun buildPropertyMirror(key: String): String? = when (key) {
        "ro.build.fingerprint" -> Build.FINGERPRINT
        "ro.product.model" -> Build.MODEL
        "ro.product.brand" -> Build.BRAND
        "ro.product.manufacturer" -> Build.MANUFACTURER
        "ro.product.device" -> Build.DEVICE
        "ro.product.name" -> Build.PRODUCT
        "ro.product.board" -> Build.BOARD
        "ro.hardware" -> Build.HARDWARE
        "ro.build.tags" -> Build.TAGS
        "ro.build.type" -> Build.TYPE
        "ro.build.version.sdk" -> sdkInt.toString()
        "ro.bootloader" -> Build.BOOTLOADER
        else -> null
    }?.takeIf { it.isNotEmpty() && it != Build.UNKNOWN }

    // ---- filesystem ---------------------------------------------------------

    override fun fileExists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)

    override fun readFile(path: String, maxBytes: Int): String? = runCatching {
        val f = File(path)
        if (!f.exists() || !f.canRead()) return null
        f.inputStream().use { stream ->
            val buf = ByteArray(maxBytes)
            val n = stream.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }
    }.getOrNull()

    // ---- Settings.Secure / Global / System ----------------------------------

    override fun querySettingSecure(key: String): String? = runCatching {
        Settings.Secure.getString(appContext.contentResolver, key)
    }.getOrNull()

    override fun querySettingGlobal(key: String): String? = runCatching {
        Settings.Global.getString(appContext.contentResolver, key)
    }.getOrNull()

    override fun querySettingSystem(key: String): String? = runCatching {
        Settings.System.getString(appContext.contentResolver, key)
    }.getOrNull()

    // ---- TelephonyManager ---------------------------------------------------
    //
    // IMEI / SERIAL / SIM_SERIAL / SUBSCRIBER_ID require READ_PHONE_STATE (and
    // on A10+ READ_PRIVILEGED_PHONE_STATE for some). Without the granted
    // permission these throw SecurityException — we catch and return null, the
    // conservative ABSTAIN answer the probes expect.

    private val telephony: TelephonyManager? by lazy {
        runCatching {
            appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    override fun queryTelephonyManager(field: TelephonyField): String? {
        val tm = telephony ?: return null
        return runCatching {
            when (field) {
                TelephonyField.IMEI ->
                    if (sdkInt >= Build.VERSION_CODES.O) tm.imei else @Suppress("DEPRECATION") tm.deviceId
                TelephonyField.SERIAL ->
                    if (sdkInt >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
                TelephonyField.OPERATOR_NAME -> tm.networkOperatorName
                TelephonyField.MCC_MNC -> tm.networkOperator
                TelephonyField.SIM_SERIAL -> @Suppress("DEPRECATION") tm.simSerialNumber
                TelephonyField.LINE1_NUMBER -> @Suppress("DEPRECATION") tm.line1Number
                TelephonyField.SUBSCRIBER_ID -> @Suppress("DEPRECATION") tm.subscriberId
            }?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // ---- PackageManager -----------------------------------------------------

    override fun queryPackageManager(): PackageManagerView =
        AndroidPackageManagerView(appContext)

    // ---- SensorManager ------------------------------------------------------

    override fun querySensorManager(): SensorManagerView =
        AndroidSensorManagerView(appContext)

    // ---- BluetoothAdapter MAC -----------------------------------------------
    //
    // Android 6+ redacts getAddress() to the privacy default 02:00:00:00:00:00
    // unless the caller holds LOCAL_MAC_ADDRESS (system-only). BluetoothMacProbe
    // handles that redaction branch itself; we just surface whatever is read.

    @SuppressLint("HardwareIds", "MissingPermission")
    override fun queryBluetoothAdapterMac(): String? = runCatching {
        val adapter: BluetoothAdapter? =
            if (sdkInt >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } else {
                @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            }
        adapter?.address?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---- Timezone / Locale --------------------------------------------------

    override fun queryTimezoneId(): String? =
        runCatching { TimeZone.getDefault().id }.getOrNull()

    override fun queryTimezoneOffsetMinutes(): Int? = runCatching {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    }.getOrNull()

    override fun queryLocaleLanguage(): String? =
        runCatching { Locale.getDefault().language }.getOrNull()

    override fun queryLocaleCountry(): String? =
        runCatching { Locale.getDefault().country }.getOrNull()

    override fun queryLocaleDisplayName(): String? =
        runCatching { Locale.getDefault().displayName }.getOrNull()

    // ---- DisplayMetrics -----------------------------------------------------

    override fun queryDisplayMetrics(): DisplayMetricsView? = runCatching {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val metrics = DisplayMetrics()
        if (sdkInt >= Build.VERSION_CODES.R) {
            // API 30+: bounds from WindowMetrics, density from Resources.
            val bounds = wm.currentWindowMetrics.bounds
            val rm = appContext.resources.displayMetrics
            AndroidDisplayMetricsView(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = rm.densityDpi,
                xdpi = rm.xdpi,
                ydpi = rm.ydpi,
            )
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            AndroidDisplayMetricsView(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                xdpi = metrics.xdpi,
                ydpi = metrics.ydpi,
            )
        }
    }.getOrNull()

    // ---- KeyguardManager ----------------------------------------------------

    override fun queryKeyguardManager(): KeyguardManagerView = runCatching {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return UnknownKeyguardManagerView
        AndroidKeyguardManagerView(km, sdkInt)
    }.getOrDefault(UnknownKeyguardManagerView)

    // ---- LocationManager ----------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun queryLocationManager(): LocationManagerView = runCatching {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return UnknownLocationManagerView
        // Without ACCESS_FINE/COARSE_LOCATION getLastKnownLocation throws
        // SecurityException → conservative Unknown view (probe reads as
        // "permission missing", which is the expected non-Play-container state).
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )
        var best: Location? = null
        var bestProvider: String? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            val current = best
            if (current == null || loc.time > current.time) {
                best = loc
                bestProvider = p
            }
        }
        AndroidLocationManagerView(best, bestProvider, sdkInt)
    }.getOrDefault(UnknownLocationManagerView)

    // ---- WifiManager --------------------------------------------------------

    override fun queryWifiManager(): WifiManagerView = runCatching {
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return UnknownWifiManagerView
        AndroidWifiManagerView(appContext, wifi, sdkInt)
    }.getOrDefault(UnknownWifiManagerView)

    // ---- UserHandle (multi-instance / clone-app) ----------------------------

    override fun queryUserHandle(): UserHandleView = AndroidUserHandleView()

    // ---- TimeView -----------------------------------------------------------
    //
    // No live NTP query here — the probe contract forbids network I/O, and the
    // conservative null answer (UnknownTimeView's GPS/NTP = null) is correct
    // for the offline container. We DO supply the two local clocks.

    override fun queryTimeView(): TimeView = AndroidTimeView()

    // ---- /proc reads of the APP process (the in-process distinguisher) ------
    //
    // These are read for the calling APP process (this APK), NOT adbd. That is
    // exactly the gap audit §4(2) flagged: the adb-shell channel measured
    // /proc/self of adbd, not of an instrumented app. DebuggerTracerPidProbe
    // reads /proc/self/status TracerPid via readFile("/proc/self/status") which
    // this context serves from the real app-process file.

    override fun queryProcSelfMapsLibs(): Set<String> = runCatching {
        File("/proc/self/maps").useLines { lines ->
            lines.mapNotNull { line ->
                line.substringAfterLast(' ').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() && it != line }
            }.map { it.lowercase(Locale.US) }.toSet()
        }
    }.getOrDefault(emptySet())

    override fun queryRuntimeThreadNames(): Set<String> = runCatching {
        val taskDir = File("/proc/self/task")
        taskDir.listFiles()?.mapNotNull { tid ->
            runCatching { File(tid, "comm").readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    override fun queryOpenTcpPorts(): Set<Int> = runCatching {
        val ports = mutableSetOf<Int>()
        for (proc in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val f = File(proc)
            if (!f.canRead()) continue
            f.useLines { lines ->
                lines.drop(1).forEach { raw ->
                    val cols = raw.trim().split(Regex("\\s+"))
                    if (cols.size > 1) {
                        val local = cols[1] // hex "0100007F:1F90"
                        val hexPort = local.substringAfter(':', "")
                        hexPort.toIntOrNull(16)?.let { ports.add(it) }
                    }
                }
            }
        }
        ports
    }.getOrDefault(emptySet())

    override fun queryMountInfo(pid: String): String? = runCatching {
        File("/proc/$pid/mountinfo").takeIf { it.canRead() }?.readText()
    }.getOrNull()

    override fun queryProcNetUnixSockets(): List<String> = runCatching {
        val f = File("/proc/net/unix")
        if (!f.canRead()) return emptyList()
        f.useLines { lines ->
            lines.drop(1).mapNotNull { raw ->
                val cols = raw.trim().split(Regex("\\s+"))
                if (cols.size >= 8) cols[7] else null
            }.toList()
        }
    }.getOrDefault(emptyList())

    override fun queryInitSvcProps(): Map<String, String> {
        // No public enumeration API for `init.svc.*`; getprop-by-key only works
        // when the name is known. Returning empty is the conservative answer —
        // InitSvcEnumerationProbe reads that as "no observation", not "clean".
        return emptyMap()
    }

    override fun queryDirEntries(path: String): List<String>? = runCatching {
        val dir = File(path)
        if (!dir.exists()) return null
        dir.list()?.toList()
    }.getOrNull()

    override fun queryInstallSourcePackage(): String? = runCatching {
        val pm = appContext.packageManager
        val self = appContext.packageName
        if (sdkInt >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(self).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(self)
        }
    }.getOrNull()

    // ---- ScreenRecording — MediaProjectionManagerView (constructor-supplier
    //      gap closed only for sdkInt) ----------------------------------------
    //
    // Honest partial wiring. The two live signals
    // (`Window.addScreenCaptureCallback`, API 34; and
    // `WindowManager.addScreenRecordingCallback`, API 35) are PUSH callbacks
    // that require a live, focused Activity Window and an async registration
    // window — neither of which the headless instrumented runner guarantees.
    // We therefore CANNOT synchronously poll them here, and faking a poll
    // would violate the no-fabrication contract. What we CAN supply truthfully
    // is the real `sdkInt()`: the upstream `UnknownMediaProjectionManagerView`
    // default reports sdk=0, which forces the probe into a permanent
    // `api_too_low` skip even on a genuine Android 14/15 container. By
    // surfacing the real SDK level (and leaving both signal queries at the
    // conservative `null` = "no callback registered"), the probe reports the
    // correct API path + degraded confidence (0.90 on A15, 0.70 on A14) and
    // only skips when the device truly predates A14. The two signal reads stay
    // null until a future foreground-Activity wiring registers the callbacks.
    override fun queryMediaProjectionManager(): MediaProjectionManagerView =
        AndroidMediaProjectionManagerView(sdkInt)

    // ---- ChargingState suppliers (BatteryManager / ACTION_BATTERY_CHANGED) --
    //
    // ProbeContext exposes no queryBatteryManager(); ChargingStateProbe accepts
    // three Int? suppliers. We read the sticky ACTION_BATTERY_CHANGED broadcast
    // (no permission required, no receiver lifecycle) for EXTRA_STATUS /
    // EXTRA_PLUGGED, and BatteryManager.BATTERY_PROPERTY_CAPACITY for level.
    // Each is runCatching-wrapped → null on any failure → probe ABSTAINs.

    private fun stickyBatteryIntent(): Intent? = runCatching {
        @Suppress("DEPRECATION")
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /** `BatteryManager.EXTRA_STATUS` (1..5) or null if unreadable. */
    fun queryBatteryStatus(): Int? = runCatching {
        val v = stickyBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        v.takeIf { it >= 0 }
    }.getOrNull()

    /** `BatteryManager.EXTRA_PLUGGED` (0/1/2/4/8) or null if unreadable. */
    fun queryBatteryPlugged(): Int? = runCatching {
        val v = stickyBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        v.takeIf { it >= 0 }
    }.getOrNull()

    /** Battery percentage 0..100, derived from EXTRA_LEVEL / EXTRA_SCALE. */
    fun queryBatteryLevelPercent(): Int? = runCatching {
        val intent = stickyBatteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        (level * 100) / scale
    }.getOrNull()

    // ---- WifiSsidBssid suppliers (WifiManager.connectionInfo) ----------------
    //
    // WifiSsidBssidProbe accepts ssid/bssid/connectionState String? suppliers.
    // WifiInfo.getSSID()/getBSSID() require ACCESS_FINE_LOCATION (or A13+
    // NEARBY_WIFI_DEVICES); without it the framework redacts SSID to
    // "<unknown ssid>" and BSSID to the 02:00:00:00:00:00 privacy default —
    // which the probe scores as the benign privacy-default baseline. All
    // runCatching-wrapped → null → probe ABSTAINs / degrades.

    private val wifiManager: WifiManager? by lazy {
        runCatching {
            appContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
    }

    private fun hasWifiReadPermission(): Boolean = runCatching {
        val perm = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        appContext.checkPermission(perm, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun wifiConnectionInfo(): WifiInfo? = runCatching {
        val wifi = wifiManager ?: return null
        if (!wifi.isWifiEnabled) return null
        @Suppress("DEPRECATION")
        wifi.connectionInfo
    }.getOrNull()

    /** Connected SSID (quoted by the framework), or null if unreadable. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun queryWifiSsid(): String? = runCatching {
        val info = wifiConnectionInfo() ?: return null
        info.ssid?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Connected BSSID, or null if unreadable. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun queryWifiBssid(): String? = runCatching {
        val info = wifiConnectionInfo() ?: return null
        info.bssid?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** "connected" / "disconnected", inferred from WifiInfo.networkId. */
    @SuppressLint("MissingPermission")
    fun queryWifiConnectionState(): String? = runCatching {
        val info = wifiConnectionInfo() ?: return "disconnected"
        if (info.networkId != -1) "connected" else "disconnected"
    }.getOrNull()

    // ---- HttpProxy supplier (JVM System.getProperty) ------------------------
    //
    // Pure JDK read, always available, no permission. HttpProxyProbe takes a
    // (String) -> String? supplier.

    fun queryJvmSystemProperty(key: String): String? = runCatching {
        System.getProperty(key)
    }.getOrNull()

    // ---- ServicesProcesses suppliers (ActivityManager) ----------------------
    //
    // ServicesProcessesProbe takes two List<String>? suppliers. On Android 8+
    // unprivileged callers see only their own services/processes (the probe
    // documents this as the expected degraded baseline). All runCatching →
    // null → probe degrades to confidence 0.50.

    private val activityManager: ActivityManager? by lazy {
        runCatching {
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
    }

    fun queryRunningServiceNames(): List<String>? = runCatching {
        val am = activityManager ?: return null
        @Suppress("DEPRECATION")
        am.getRunningServices(Int.MAX_VALUE)
            ?.mapNotNull { it.service?.className?.takeIf { c -> c.isNotEmpty() } }
            ?: emptyList()
    }.getOrNull()

    fun queryRunningProcessNames(): List<String>? = runCatching {
        val am = activityManager ?: return null
        am.runningAppProcesses
            ?.mapNotNull { it.processName?.takeIf { p -> p.isNotEmpty() } }
            ?: emptyList()
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// View implementations backed by live Android services.
// ---------------------------------------------------------------------------

private class AndroidPackageManagerView(private val ctx: Context) : PackageManagerView {
    private val pm: PackageManager = ctx.packageManager

    override fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    override fun listInstalledPackages(): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }.map { it.packageName }
    }.getOrDefault(emptyList())

    override fun listPackagesWithPermission(permission: String): List<String> = runCatching {
        listInstalledPackages().filter { pkg ->
            runCatching {
                pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())
}

private class AndroidSensorManagerView(ctx: Context) : SensorManagerView {
    private val sm: SensorManager? =
        ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun listSensorTypes(): List<Int> = runCatching {
        sm?.getSensorList(Sensor.TYPE_ALL)?.map { it.type } ?: emptyList()
    }.getOrDefault(emptyList())

    // Synchronous one-shot sampling from a background runner is intentionally
    // not implemented here: the SensorEventListener callback model needs a
    // Looper and a live window that the headless runner does not guarantee.
    // An empty SensorSample is the documented "no live samples" answer the
    // sensor-family probes (rank 24/42/43/44/45) handle without false positives.
    override fun sampleSensor(sensorType: Int, durationMs: Long): SensorSample =
        SensorSample(timestamps = LongArray(0), values = emptyArray())
}

private class AndroidDisplayMetricsView(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val xdpi: Float,
    private val ydpi: Float,
) : DisplayMetricsView {
    override fun widthPixels(): Int = width
    override fun heightPixels(): Int = height
    override fun densityDpi(): Int = densityDpi
    override fun xdpi(): Float = xdpi
    override fun ydpi(): Float = ydpi
}

private class AndroidKeyguardManagerView(
    private val km: KeyguardManager,
    private val sdk: Int,
) : KeyguardManagerView {
    override fun sdkInt(): Int = sdk
    override fun isDeviceSecure(): Boolean? = runCatching {
        if (sdk >= Build.VERSION_CODES.M) km.isDeviceSecure else null
    }.getOrNull()

    override fun isKeyguardSecure(): Boolean? =
        runCatching { km.isKeyguardSecure }.getOrNull()
}

private class AndroidLocationManagerView(
    private val loc: Location?,
    private val provider: String?,
    private val sdk: Int,
) : LocationManagerView {
    override fun sdkInt(): Int = sdk
    override fun hasLastKnownLocation(): Boolean = loc != null
    override fun lastKnownLatitude(): Double? = loc?.latitude
    override fun lastKnownLongitude(): Double? = loc?.longitude
    override fun lastKnownAccuracy(): Float? = loc?.takeIf { it.hasAccuracy() }?.accuracy
    override fun lastKnownProvider(): String? = provider
    override fun isMockProvider(): Boolean? = runCatching {
        if (sdk >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            @Suppress("DEPRECATION") loc?.isFromMockProvider
        } else {
            null
        }
    }.getOrNull()
}

private class AndroidWifiManagerView(
    private val ctx: Context,
    private val wifi: WifiManager,
    private val sdk: Int,
) : WifiManagerView {
    override fun sdkInt(): Int = sdk

    override fun hasWifiAccessPermission(): Boolean = runCatching {
        val perm = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        ctx.checkPermission(perm, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun currentNetworkSecurityType(): WifiSecurityRead = runCatching {
        if (!wifi.isWifiEnabled) {
            return WifiSecurityRead(WifiSecurityType.NOT_CONNECTED, "WifiManager.isWifiEnabled=false")
        }
        if (!hasWifiAccessPermission()) {
            return WifiSecurityRead(WifiSecurityType.UNAVAILABLE, "permission-missing")
        }
        if (sdk >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            if (info == null || info.networkId == -1) {
                WifiSecurityRead(WifiSecurityType.NOT_CONNECTED, "WifiInfo.networkId=-1")
            } else {
                val type = info.currentSecurityType
                WifiSecurityRead(mapSecurityType(type), "WifiInfo.getCurrentSecurityType")
            }
        } else {
            WifiSecurityRead(WifiSecurityType.UNKNOWN, "pre-S-no-security-type-api")
        }
    }.getOrDefault(WifiSecurityRead(WifiSecurityType.UNAVAILABLE, "exception"))

    // The SECURITY_TYPE_* constants live on android.net.wifi.WifiInfo (API 31+),
    // returned by WifiInfo.getCurrentSecurityType().
    private fun mapSecurityType(type: Int): WifiSecurityType = when (type) {
        WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurityType.NONE
        WifiInfo.SECURITY_TYPE_WEP -> WifiSecurityType.WEP
        WifiInfo.SECURITY_TYPE_PSK -> WifiSecurityType.WPA2
        WifiInfo.SECURITY_TYPE_EAP -> WifiSecurityType.WPA2
        WifiInfo.SECURITY_TYPE_SAE -> WifiSecurityType.WPA3
        else -> WifiSecurityType.UNKNOWN
    }
}

private class AndroidUserHandleView : UserHandleView {
    override fun myUserId(): Int? = runCatching {
        // UserHandle.myUserId() is @hide; derive from the public getUserId
        // reflection or the well-known uid/100000 convention as a fallback.
        val m = UserHandle::class.java.getMethod("myUserId")
        m.invoke(null) as? Int
    }.getOrElse {
        runCatching { Process.myUid() / 100_000 }.getOrNull()
    }
}

private class AndroidTimeView : TimeView {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun gpsTimestampMs(): Long? = null
    override fun ntpTimestampMs(): Long? = null
}

// Reports the REAL SDK level so ScreenRecordingProbe gates correctly (skip
// only on genuine pre-A14). The two push-callback signals stay null: they are
// async, foreground-Window-bound callbacks the headless runner cannot poll
// synchronously, and faking a value would violate the no-fabrication contract.
// A `null` signal is the documented "no callback registered" answer; the probe
// reads it as "no signal" (degraded confidence), never as a false positive.
private class AndroidMediaProjectionManagerView(
    private val sdk: Int,
) : MediaProjectionManagerView {
    override fun sdkInt(): Int = sdk
    override fun isScreenCaptureCallbackActive(): Boolean? = null
    override fun isMediaProjectionFrameCaptureActive(): Boolean? = null
}

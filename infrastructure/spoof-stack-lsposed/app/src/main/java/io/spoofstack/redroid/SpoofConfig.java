/*
 * SpoofStack — central constants table.
 *
 * Every value here is sourced verbatim from
 * audit/spoof-stack/production-hooks-spec.md. Changing any value here
 * MUST be paired with a spec update; otherwise the property-side
 * (resetprop) and framework-side (LSPosed) surfaces will disagree, which
 * is itself a detectable signal.
 */
package io.spoofstack.redroid;

public final class SpoofConfig {

    private SpoofConfig() { /* no instances */ }

    // ---- Build / hardware (§1.1) ----
    public static final String BUILD_FINGERPRINT =
            "google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys";
    public static final String BUILD_DISPLAY_ID = "SP1A.210812.016.C2";
    public static final String BUILD_TAGS = "release-keys";
    public static final String BUILD_TYPE = "user";
    public static final String PRODUCT_BRAND = "google";
    public static final String PRODUCT_MODEL = "Pixel 7";
    public static final String PRODUCT_MANUFACTURER = "Google";
    public static final String PRODUCT_DEVICE = "panther";
    public static final String PRODUCT_NAME = "panther";
    public static final String HARDWARE = "panther";

    // ---- ABI (§1.2) ----
    public static final String[] SUPPORTED_ABIS =
            new String[]{"arm64-v8a", "armeabi-v7a", "armeabi"};
    public static final String[] SUPPORTED_64_BIT_ABIS =
            new String[]{"arm64-v8a"};
    public static final String[] SUPPORTED_32_BIT_ABIS =
            new String[]{"armeabi-v7a", "armeabi"};

    // ---- Serial (§1.7, §5.1) ----
    public static final String SERIAL = "HQ7Y0V3RJL";

    // ---- Telephony (§5.1) ----
    public static final String IMEI = "353112109876546";
    public static final String SIM_SERIAL = "8901260123456789011";
    public static final String OPERATOR_NAME = "T-Mobile";
    public static final String MCC_MNC = "310260";
    public static final String COUNTRY_ISO = "us";

    // ---- Locale + TimeZone (§5.2) ----
    public static final String LOCALE_LANGUAGE = "en";
    public static final String LOCALE_COUNTRY = "US";
    public static final String TIMEZONE_ID = "America/Los_Angeles";

    // ---- Bluetooth / WiFi MAC (§5.3, §5.4) ----
    public static final String BLUETOOTH_MAC = "3C:5A:B4:8D:F1:27";
    public static final String WIFI_MAC = "40:4e:36:7a:b2:c9";

    // ---- Display (§5.6) ----
    public static final int DISPLAY_WIDTH = 1080;
    public static final int DISPLAY_HEIGHT = 2400;
    public static final int DISPLAY_DENSITY_DPI = 420;
    public static final float DISPLAY_XDPI = 411.0f;
    public static final float DISPLAY_YDPI = 413.0f;
    public static final float DISPLAY_DENSITY = DISPLAY_DENSITY_DPI / 160.0f;

    // ---- Sensor inventory (§5.5) ----
    // ACCEL=1, MAG=2, GYRO=4, LIGHT=5, PRESS=6, PROX=8
    public static final int[] SENSOR_TYPES = new int[]{1, 2, 4, 5, 6, 8};

    // ---- /proc/self/status synthesized body template (§3.2) ----
    public static final String PROC_SELF_STATUS_TEMPLATE =
            "Name:\tcom.android.app\n"
                    + "Umask:\t0077\n"
                    + "State:\tR (running)\n"
                    + "TracerPid:\t0\n"
                    + "Uid:\t%1$d\t%1$d\t%1$d\t%1$d\n"
                    + "Gid:\t%1$d\t%1$d\t%1$d\t%1$d\n";
}

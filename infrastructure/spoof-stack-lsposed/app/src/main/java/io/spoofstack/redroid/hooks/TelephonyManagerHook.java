/*
 * TelephonyManagerHook — §5.1 of production-hooks-spec.md.
 *
 * Replaces every TelephonyManager identifier surface the probe panel
 * touches: IMEI, deviceId, serial, SIM serial, operator name/code,
 * country ISO, and SIM state.
 *
 * The container has no real RIL backend; without these hooks the methods
 * return null/empty/UNKNOWN, which itself is a detectable signal
 * (rank 21 — operator/MCC absence).
 */
package io.spoofstack.redroid.hooks;

import android.telephony.TelephonyManager;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class TelephonyManagerHook {

    private TelephonyManagerHook() { /* no instances */ }

    /** Install all TelephonyManager method hooks under the target classloader. */
    public static void install(final ClassLoader cl) {
        final Class<?> tm = XposedHelpers.findClass(
                "android.telephony.TelephonyManager", cl);

        // IMEI (API 26+) — returns 14-digit GSM IMEI without check digit;
        // historic getDeviceId() returns the same identifier.
        XposedHelpers.findAndHookMethod(tm, "getImei",
                XC_MethodReplacement.returnConstant(SpoofConfig.IMEI));
        XposedHelpers.findAndHookMethod(tm, "getImei", int.class,
                XC_MethodReplacement.returnConstant(SpoofConfig.IMEI));
        XposedHelpers.findAndHookMethod(tm, "getDeviceId",
                XC_MethodReplacement.returnConstant(SpoofConfig.IMEI));
        XposedHelpers.findAndHookMethod(tm, "getDeviceId", int.class,
                XC_MethodReplacement.returnConstant(SpoofConfig.IMEI));

        // Hardware serial — deprecated since API 26 but still queried.
        XposedHelpers.findAndHookMethod(tm, "getSerial",
                XC_MethodReplacement.returnConstant(SpoofConfig.SERIAL));

        // SIM serial (ICCID) — 19 or 20 digit Luhn-checksummed string.
        XposedHelpers.findAndHookMethod(tm, "getSimSerialNumber",
                XC_MethodReplacement.returnConstant(SpoofConfig.SIM_SERIAL));

        // Operator name / numeric — read by every analytics SDK.
        XposedHelpers.findAndHookMethod(tm, "getNetworkOperatorName",
                XC_MethodReplacement.returnConstant(SpoofConfig.OPERATOR_NAME));
        XposedHelpers.findAndHookMethod(tm, "getNetworkOperator",
                XC_MethodReplacement.returnConstant(SpoofConfig.MCC_MNC));
        XposedHelpers.findAndHookMethod(tm, "getSimOperator",
                XC_MethodReplacement.returnConstant(SpoofConfig.MCC_MNC));
        XposedHelpers.findAndHookMethod(tm, "getSimOperatorName",
                XC_MethodReplacement.returnConstant(SpoofConfig.OPERATOR_NAME));

        // Country ISO ("us") — lowercase per Android contract.
        XposedHelpers.findAndHookMethod(tm, "getSimCountryIso",
                XC_MethodReplacement.returnConstant(SpoofConfig.COUNTRY_ISO));
        XposedHelpers.findAndHookMethod(tm, "getNetworkCountryIso",
                XC_MethodReplacement.returnConstant(SpoofConfig.COUNTRY_ISO));

        // SIM state — SIM_STATE_READY=5 — must be ready, not ABSENT (1)
        // or NETWORK_LOCKED (4), which would surface as "no SIM" → emulator.
        XposedHelpers.findAndHookMethod(tm, "getSimState",
                XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_READY));
    }
}

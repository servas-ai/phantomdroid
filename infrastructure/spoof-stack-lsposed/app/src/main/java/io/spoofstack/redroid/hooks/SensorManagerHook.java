/*
 * SensorManagerHook — §5.5 of production-hooks-spec.md.
 *
 * Hooks SensorManager.getSensorList(int) to inject six fabricated
 * Sensor objects (ACCEL, MAG, GYRO, LIGHT, PRESS, PROX). This clears
 * the "missing-on-phone" rule (probes 24/42/43/44/45) but NOT the
 * sample-stream constant-stub rule — apps that subscribe via
 * SensorEventListener will still see no events. The spec recommends
 * pairing this with a HAL-shim (`sensors@2.x.so`) for sample-stream
 * coverage; that lives outside this module.
 *
 * Sensor objects can't be constructed via public API (no public ctor);
 * we use XposedHelpers.newInstance with the @hide constructor. If the
 * Sensor signature shifts in a future Android release, this hook
 * silently falls back to passing through the original result (i.e.
 * empty list) — better to no-op than crash the target app.
 */
package io.spoofstack.redroid.hooks;

import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import io.spoofstack.redroid.SpoofConfig;

public final class SensorManagerHook {

    private static final String TAG = "SpoofStack.Sensor";

    private SensorManagerHook() { /* no instances */ }

    public static void install(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(SensorManager.class, "getSensorList",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        try {
                            final int requested = (int) param.args[0];
                            final List<Sensor> spoofed = new ArrayList<>();
                            for (final int t : SpoofConfig.SENSOR_TYPES) {
                                if (requested == Sensor.TYPE_ALL || requested == t) {
                                    final Sensor s = createSpoofedSensor(t);
                                    if (s != null) {
                                        spoofed.add(s);
                                    }
                                }
                            }
                            param.setResult(spoofed);
                        } catch (Throwable t) {
                            // Don't crash the target app; leave original result.
                            XposedBridge.log(TAG + " getSensorList hook error: " + t);
                        }
                    }
                });
    }

    /**
     * Construct a single Sensor of the given type via the hidden
     * no-arg constructor, then reflectively populate the minimum
     * fields required to pass the "well-known sensor present" check.
     * Returns null if the platform Sensor class signature has drifted.
     */
    private static Sensor createSpoofedSensor(final int type) {
        try {
            final Sensor s = (Sensor) XposedHelpers.newInstance(Sensor.class);
            XposedHelpers.setIntField(s, "mType", type);
            XposedHelpers.setObjectField(s, "mName", nameForType(type));
            XposedHelpers.setObjectField(s, "mVendor", "Google");
            // Version field exists on every Android API since 9.
            XposedHelpers.setIntField(s, "mVersion", 1);
            return s;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " createSpoofedSensor failed for type=" + type + ": " + t);
            return null;
        }
    }

    private static String nameForType(final int type) {
        switch (type) {
            case 1: return "BMI323 Accelerometer";
            case 2: return "AK09918 Magnetometer";
            case 4: return "BMI323 Gyroscope";
            case 5: return "TCS3701 Light Sensor";
            case 6: return "BMP380 Pressure Sensor";
            case 8: return "STK3338 Proximity Sensor";
            default: return "Unknown Sensor";
        }
    }
}

# BLOCKER — L5 Sensor Emulation is NOT achievable on ReDroid 12 (no sensor HAL)

**Layer:** L5 — Sensor Emulation (`layers.md` §L5: VirtualSensor + trace-player; probes #24, #42-#45)
**Status:** GENUINELY BLOCKED. Documented per the HONESTY MANDATE — not faked.
**Container under test:** `b2-build-work` (hardened, non-privileged, Magisk-rooted `redroid/redroid:12.0.0_magisk`)
**Date:** 2026-05-31

## The blocker

ReDroid 12 ships the Android *framework-side* sensor HIDL **client** libraries but **no sensor HAL
implementation / vendor service** for the framework to bind to. With root we can write properties and
bind-mount files, but we cannot synthesize a kernel/HAL sensor device that does not exist — there is no
`/vendor/.../hw/sensors.*.so`, no registered `android.hardware.sensors` HAL service, and the
SensorService init check fails with `ENODEV` (`-19`).

## Live evidence (exact commands + real output, via `docker exec` — NOT adb)

```
$ docker exec b2-build-work su -c 'dumpsys sensorservice | head -3'
No Sensors on the device
devInitCheck : -19

$ docker exec b2-build-work su -c 'ls /vendor/lib*/hw/sensors*; ls /vendor/lib*/*android.hardware.sensors*'
ls: /vendor/lib*/hw/sensors*: No such file or directory
ls: /vendor/lib*/*android.hardware.sensors*: No such file or directory

$ docker exec b2-build-work su -c 'service list | grep -i android.hardware.sensors'
no android.hardware.sensors HAL service registered
```

Note: `/system/lib64/android.hardware.sensors@2.1.so` etc. DO exist, but those are the framework-side
HIDL **interface stubs**, not a backing HAL `passthrough`/`binderized` implementation. `sensorservice`
has nothing to bind to, hence "No Sensors on the device" and `devInitCheck:-19`.

## Why root does NOT unblock this

`resetprop` / property injection cannot create a sensor: `SensorManager.getSensorList()` is populated by
`sensorservice` from the bound HAL, not from a system property. There is no `sensors.*` property that
declares a sensor inventory to the framework. Injecting the HAL would require shipping a custom
`sensors.redroid.so` (a real device-class HAL backed by a synthetic event source) plus a vendor
manifest entry and an SELinux policy to let `hal_sensors` start — that is a HAL-authoring project, not a
property/bind-mount spoof, and it is out of scope for the B2 root-property layer.

## Detector impact (the cost of the blocker)

The L5 blocker is not neutral — it actively *raises* several sensor probe scores once L1 sets the device
to a phone-class model (`ro.product.model="Pixel 7"`), because the sensor-family probes expect a
phone-class device to expose core sensors:

| probe                       | unspoofed | after spoof | rule that fires |
|-----------------------------|-----------|-------------|-----------------|
| `sensors.light`             | 0.00      | 0.85        | MISSING_ON_PHONE (phone-class + no light sensor) |
| `sensors.magnetometer`      | 0.00      | 0.85        | MISSING_ON_PHONE |
| `sensors.proximity`         | 0.00      | 0.85        | MISSING_ON_PHONE |
| `sensors.barometer`         | 0.00      | 0.50        | NO_SIGNAL / missing |
| `sensors.accelerometer_gyro`| 0.50      | 0.50        | NO_SIGNAL (accessor reports zero sensors) |

This is an honest, measured trade-off: the L1/L2 build+identity spoof is a large net win
(0.3294 -> 0.1062, 4 critical -> 0 critical), but it surfaces the irreducible sensor-HAL gap. A real
Pixel 7 has accelerometer/gyro/magnetometer/proximity/light/barometer; ReDroid has none and cannot be
made to without a HAL. This is the architectural ceiling for L5 on ReDroid, alongside the x86_64 ABI
ceiling (`emulator.cpu_abi=1.00`, see RESULT.md).

## What would unblock L5 (future, out of B2 scope)

1. Author a synthetic `sensors` HAL (`android.hardware.sensors@2.1-service.redroid`) backed by a
   trace-player reading the `layers.md` §L5 CSV sequence, register it in the vendor manifest, and ship
   the matching `hal_sensors` SELinux policy. This is a HAL-development task on a custom ReDroid image.
2. Until (1) lands, L5 remains BLOCKED and the sensor probes will read as a no-HAL device.

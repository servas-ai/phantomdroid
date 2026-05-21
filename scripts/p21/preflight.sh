#!/usr/bin/env bash
umask 077
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-172.17.0.2:5555}"
OUT="${OUT:-p21}"
mkdir -p "$OUT"

adb connect "$ADB_SERIAL" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" wait-for-device

adb -s "$ADB_SERIAL" shell 'getprop'                                                            > "$OUT/baseline-props.txt"
adb -s "$ADB_SERIAL" shell 'cat /proc/cpuinfo'                                                  > "$OUT/cpuinfo.txt"
adb -s "$ADB_SERIAL" shell 'pm list packages -3'                                                > "$OUT/pm-list-3.txt"
adb -s "$ADB_SERIAL" shell 'dumpsys package android' | grep -E 'versionName|versionCode|sdkVersion' | head -20 > "$OUT/dumpsys-build.txt"

{
  echo "# native_bridge property:"
  adb -s "$ADB_SERIAL" shell 'getprop ro.dalvik.vm.native.bridge'
  echo
  echo "# /system/lib (32-bit)"
  adb -s "$ADB_SERIAL" shell 'ls /system/lib 2>/dev/null | head -30'
  echo
  echo "# /system/lib64 (64-bit)"
  adb -s "$ADB_SERIAL" shell 'ls /system/lib64 2>/dev/null | head -30'
  echo
  echo "# arm-translation libs present?"
  adb -s "$ADB_SERIAL" shell 'ls /system/lib/arm 2>/dev/null; ls /system/lib64/arm64 2>/dev/null'
} > "$OUT/arm-bridge.txt"

# Recapture B — flar2.devcheck + krypton.tbsafetychecker

Target: docker container `l1-spoof-b` (LIVE SPOOFED ReDroid 12, presenting as Google Pixel 7 / Android 12).
Driven ADB-free via `docker exec`. Screen 720x1280. Date: 2026-05-30.

## Summary

Both apps **crash on launch before any UI renders**. Root cause in both cases is an
`UnsatisfiedLinkError` for a per-ABI native library that is **absent from the installed
package**. Each app was installed from a base-only APK (`splits=[base]`,
`primaryCpuAbi=null`) that contains **no `.so` files at all**. The ABI split APK
(`split_config.x86_64.apk` / arm64) carrying the native lib was never installed.

This is an **install/packaging gap, not a spoof failure and not an anti-emulator
detection** — neither app reached any code that inspects the (spoofed) device identity,
CPU arch, or integrity state. No verdict UI was produced. Both screenshots show only the
launcher home screen.

## Table

| App | Identity reached | Tell(s) | Verdict | Final PNG |
|-----|------------------|---------|---------|-----------|
| flar2.devcheck (DevCheck 6.40) | None — crashed at process start | `UnsatisfiedLinkError: dlopen failed: library "libpairipcore.so" not found` (PairIP/Play protection native lib missing); `splits=[base]`, base.apk has zero `.so` | **UNREADABLE** | `audit/anti-spoof-80/evidence-full/flar2.devcheck-v2.png` (launcher only) |
| krypton.tbsafetychecker (TB Safety Checker 2.8.5) | None — crashed in `MainActivity.<init>` | `UnsatisfiedLinkError: dlopen failed: library "libapplist_detector.so" not found`; `splits=[base]`, apk has zero `.so`; also logged `requires the Google Play Store, but it is missing` | **UNREADABLE** (would be EXCLUDED-attestation if it ran — Play Integrity needs TEE) | `audit/anti-spoof-80/evidence-full/krypton.tbsafetychecker-v2.png` (launcher only) |

## Evidence

DevCheck crash (logcat):
```
E AndroidRuntime: Process: flar2.devcheck, PID: 2059
E AndroidRuntime: java.lang.UnsatisfiedLinkError: dlopen failed: library "libpairipcore.so" not found
    at com.pairip.VMRunner.<clinit>(VMRunner.java:33)
    at com.pairip.StartupLauncher.launch(StartupLauncher.java:14)
W ActivityTaskManager: Force finishing activity flar2.devcheck/.MainActivity
I ActivityManager: Process flar2.devcheck (pid 2059) has died
```
Install: `splits=[base]`, `primaryCpuAbi=null`. `/data/app/.../flar2.devcheck-.../` holds
only `base.apk` (16.6 MB) + `oat/` — no `lib/` dir. Source APK in repo has no `.so` entries.

TB Safety Checker crash (logcat):
```
E AndroidRuntime: java.lang.UnsatisfiedLinkError: dlopen failed: library "libapplist_detector.so" not found
    at krypton.tbsafetychecker.main.MainActivity.<init>(SourceFile:35)
W GooglePlayServicesUtil: krypton.tbsafetychecker requires the Google Play Store, but it is missing.
W ActivityTaskManager: Force finishing activity krypton.tbsafetychecker/.main.MainActivity
I ActivityManager: Process krypton.tbsafetychecker (pid 2185) has died
```
Install: `splits=[base]`, `primaryCpuAbi=null`. Source APK (23 MB) has no `.so` entries.

## Spoof relevance

- Neither crash references emulator/root/x86 detection. The missing libraries
  (`libpairipcore.so` = Play app-signing/PairIP wrapper; `libapplist_detector.so` =
  the app's own detection lib) are simply not present on disk, so `dlopen` fails at
  class-init time. The spoof layer was never exercised.
- **DevCheck did NOT leak x86_64 / arch** — it never ran far enough to read or display
  any CPU/SOC info. No arch tell observed.
- To actually capture verdicts, both apps must be reinstalled with their full split set
  (`pm install-multiple base.apk split_config.x86_64.apk ...`) so the native libs land
  in the app's `lib/` dir. The base-only APKs in `p21/apks/` are insufficient.

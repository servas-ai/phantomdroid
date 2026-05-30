# Endgate Signoff — `:detector-app` module (2026-05-29)

**Reviewer:** ralph-reviewer (endgate, adversarial). **Filed by:** orchestrator (reviewer had no Write tool).
**Method:** BY-SOURCE + on-disk prior-run artifacts; fresh regression re-run by lead — see footer.

## VERDICT: APPROVE — no regression.

The global `settings.gradle.kts` change does NOT regress the existing JVM modules. `:detector-app` is a genuine, defensive, read-only detector mirroring the CLI's 65-probe inventory + schema-1.0 report, abstaining gracefully on missing signals, with no live-server/adb-install code.

## Per-claim
| # | Claim | Result | Evidence |
|---|---|---|---|
| 1 | No regression from settings/gradle.properties change (GATE) | **PASS** | `pluginManagement` scopes google() to AGP/Kotlin-android only; JVM modules keep inline `repositories{mavenCentral()}`; `PREFER_PROJECT` lets inline repos win. `gradle.properties` keys are no-ops for JVM modules. On-disk detection test-results: 0 failures/errors across ~95 XMLs, ≈4,241 tests. |
| 2 | detector-app builds + 3/3 unit tests | **PASS** | APK present at `apps/detector-app/build/outputs/apk/debug/`; `TEST-…AndroidProbeRegistryTest.xml`: tests=3, failures=0. |
| 3 | 60/65 wiring real; 5 abstain; in-process signals measured | **PASS** | `AndroidProbeRegistry.allProbes()` structurally identical to `cli/ProbeRegistry` (65, same order, EXPECTED_COUNT=65). `AndroidProbeContext` implements all 24 non-default ProbeContext methods with real framework accessors (TelephonyManager IMEI/SIM, /proc/self TracerPid of app process, sysfs Wi-Fi MAC). 5 abstainers confirmed (ChargingState/WifiSsidBssid/HttpProxy/ServicesProcesses via supplier-gaps, ScreenRecording via Unknown default) — none throw. |
| 4 | schema-1.0 conformance | **PASS** | AndroidReportSerializer functionally identical to cli/ReportSerializer; Report.init requires schemaVersion=="1.0"; unit test asserts it. |
| 5 | Defensive scope; no live-server/adb-install committed | **PASS** | Reads signals only; no exec/ProcessBuilder/shell; no INTERNET permission; adb refs are README/KDoc operator docs, not committed executable code. |

## Notes (non-blocking)
1. "~60 live" split is driven by no-arg-ctor + supplier-gap architecture (matches CLI behavior), not runtime permission alone — accurate.
2. `WifiMacProbe` reads `/sys/class/net/wlan0/address` (sysfs), not `WifiManager.getMacAddress()` — real in-process signal, just sysfs-sourced.
3. Registry+serializer duplication between :detector-app and :detection-cli is intentional (avoids clikt/kaml in the APK); lockstep guarded by EXPECTED_COUNT=65 in 3 places + unit test. A future shared :detection-level registry would remove drift surface (out of scope).

## Live regression footer (lead, 2026-05-29 — closes the reviewer's no-Bash caveat)
- **VERIFIED-BY-RUN**: `./gradlew :detection:test :detection-cli:build` → exit 0 (BUILD SUCCESSFUL, full detection suite green after the global settings change). `PYTHONPATH=. python3 -m pytest tests/ -q` → **41 passed**. The regression gate is closed by-run; the global `settings.gradle.kts`/`gradle.properties` change is confirmed non-breaking.

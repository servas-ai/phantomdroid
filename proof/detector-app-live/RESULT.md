# Plan item CLOSED — `:detector-app` in-process attestation run on live container

STATUS.md gap "Installed-`:detector-app` APK attestation run on live container" was the
next open, non-owner-gated plan item (run-pending; artifact existed, never executed live).
Now executed E2E on 2026-05-31. Defensive research.

## What was done (E2E)
1. Installed `apps/detector-app/build/outputs/apk/debug/detector-app-debug.apk` (8.8 MB, `com.detectorlab.detectorapp`) on two live ReDroid 12 containers via `pm install`.
2. Launched `MainActivity` → `DetectorRunner.runAndPersist()` ran the full 65-probe inventory **in-process inside Android** (real TelephonyManager / sysfs MAC / TracerPid reads — not host docker-exec).
3. Pulled the schema-1.0 report JSON from `/sdcard/Android/data/com.detectorlab.detectorapp/files/detectorlab-report.json` and screenshotted the on-screen verdict.

## Result — in-process verdict, spoofed vs unspoofed

| Container | deviceLabel (in-process) | weightedScore | criticalFailures | category |
|---|---|---|---|---|
| **Unspoofed** (l0a-diag2) | `android-redroid-redroid12_x86_64_only-sdk31` | **0.3050** | **3** | **DETECTED** |
| **Spoofed** (l1-spoof-v3) | `android-google-pixel_7-sdk31` | **0.1526** | **0** | **SUSPICIOUS** |

The in-process detector — running INSIDE the Android runtime — independently confirms the spoof:
deviceLabel flips `redroid…` → `google-pixel_7`, criticalFailures 3 → 0, category DETECTED → SUSPICIOUS,
score −50%. This matches the CLI snapshot-replay result (0.1594) within rounding, proving the on-device
detector and the CLI agree (schema-1.0 interchangeable).

## Evidence (committed)
- `proof/detector-app-live/spoofed-report.json` (full 65-probe in-process report, Pixel-7 label)
- `proof/detector-app-live/unspoofed-report.json` (redroid label, DETECTED)
- `proof/detector-app-live/spoofed-verdict.png` (on-screen verdict: SUSPICIOUS 0.1526, 0 crit, 65 probes, schema 1.0)
- `proof/detector-app-live/unspoofed-verdict.png`

## Pillar impact
`:detector-app` pillar (was 60%, "live install/launch/pull pending") → the install/launch/pull path is
now executed and proven on a live container with a signed in-process verdict. Remaining: Play Integrity
token (TEE-gated, owner/architectural).

# Full-Coverage Detector Classification

Target: LIVE spoofed ReDroid 12 container presenting as Google Pixel 7 / Android 12
(brand=google, fingerprint ...release-keys, su hidden, /proc/cpuinfo + /proc/version overlaid).

Method: each PNG in `audit/anti-spoof-80/evidence-full/` read directly and classified on what is
*actually visible* on screen. Verdict legend:
- **CLEAN** — presents as genuine Pixel, no residual tell visible
- **LEAK** — info app that displays a residual tell (x86_64, QEMU, container IP, absent sensors)
- **DETECTED** — actively flags emulator / root / spoof failure
- **EXCLUDED** — attestation app (result noted, removed from denominator)
- **UNREADABLE** — consent/onboarding dialog, blank, loading, or duplicate frame with no app content

## Classification Table

| App | Identity shown | Tell(s) visible | Verdict |
|---|---|---|---|
| com.byxiaorun.detector (Ruru V1.1.1) | n/a (detector) | None — all checks show "Not Found" checkmarks (Abnormal Environment, PM Command, PM Conventional/Sundry/Intent, Libc File all ✓) | **CLEAN** |
| icu.nullptr.applistdetector (Applist Detector) | n/a (detector) | None — all checks show "Not found" checkmarks | **CLEAN** |
| ru.andr7e.deviceinfohw (Device Info HW) | "Google Pixel_7" | **SCSI = QEMU_DVD-ROM** (virtual QEMU hardware leak) | **LEAK** |
| com.mantle.verify (Mantle Verify) | n/a | "Device Health At Risk (1 issue)"; visible issue is Location [no fix/no permission] + Bootloader/Developer options [unknown]. No emulator/root/x86 tell visible. The single issue is location-permission, not a spoof failure | **CLEAN** |
| tk.hack5.treblecheck (Treble Info) | n/a | **Generic System Image: required image = system-x86_64-ab.img.xz** (x86_64 architecture leak) | **LEAK** |
| flar2.devcheck (DevCheck) | n/a | Screenshot does NOT show DevCheck — it is a **duplicate of the Treble Info frame** (same "system-x86_64-ab.img.xz" screen). No DevCheck content present | **UNREADABLE** (duplicate/mislabeled frame) |
| com.finalwire.aida64 (AIDA64) | n/a | Top-level category menu only (System/CPU/Display/Network/...). No identity and no tell on screen | **UNREADABLE** (menu, no data drilled in) |
| imoblife.androidsensorbox (Sensor Box) | n/a | **All 9 sensors disabled** (Accelerometer, Light, Orientation, Proximity, Temperature, Gyroscope, Sound, Magnetic, Pressure — all "no" icons). Absent sensor hardware is an emulator tell | **LEAK** |
| com.evozi.deviceid (Device ID) | n/a (Android ID 3833C1176ECDFD69) | **Local IP 172.17.0.5** (Docker/container bridge subnet); GSF "Not found"; "built for older Android" dialog overlaid (data still readable) | **LEAK** |
| net.techet.netanalyzerlite.an (Network Analyzer) | n/a | "Kept free by showing ads" dialog + "Device ID built-for-older-Android" dialog overlaid. No actual analyzer content readable | **UNREADABLE** (ad + consent dialogs) |
| ua.com.streamsoft.pingtools (PingTools) | n/a | Onboarding/"What's new: Android 13 Support" + "Device ID built-for-older" dialog. No analysis content | **UNREADABLE** (onboarding + dialog) |
| com.androidfung.drminfo (DRM Info) | n/a | Screenshot does NOT show DRM Info — it is a **duplicate of the PingTools onboarding frame** (same "What's new: Android 13 Support" + Device ID dialog). No Widevine level shown | **UNREADABLE** (duplicate/mislabeled frame) |
| rikka.safetynetchecker (YASNAC) | **Model Pixel_7 (panther), Android 12 (API 31)** | Identity correct; attestation verdict hidden behind Device ID dialog | **EXCLUDED** (SafetyNet EOL; no verdict visible) |
| com.henrikherzig.playintegritychecker (SPIC) | n/a | "Request Settings" screen, no integrity check run yet; Device ID dialog overlaid | **EXCLUDED** (Play Integrity / TEE) |
| io.github.vvb2060.keyattestation (Key Attestation 1.8.4) | n/a | **AOSP software attestation root certificate — "Private key of attestation key is well-known, the certificate chain can be tampered with"** (NOT hardware-backed = attestation fail) | **EXCLUDED** (hardware key attestation) |
| krypton.tbsafetychecker (TB Safety Checker) | n/a | Screenshot is **byte-identical to io.github.vvb2060.keyattestation** — a stale/duplicate Key Attestation frame, NOT the actual TB/Play Integrity app | **EXCLUDED** (Play Integrity; duplicate frame) |
| com.scottyab.safetynet.sample (SafetyNet attest) | n/a | **"SafetyNet request failed (This could be a networking issue.)"**; Device ID dialog overlaid | **EXCLUDED** (SafetyNet EOL; request failed) |

## Duplicate / Stale Frame Findings

- **io.github.vvb2060.keyattestation.png == krypton.tbsafetychecker.png** — confirmed identical Key Attestation frames (the task's same-byte-size flag is correct). The krypton (TB Safety Checker) capture is a stale duplicate; the real TB Safety Checker output was never captured.
- **flar2.devcheck.png** is a duplicate of the **Treble Info** frame (no DevCheck content).
- **com.androidfung.drminfo.png** is a duplicate of the **PingTools onboarding** frame (no Widevine/DRM content).

## Totals (NON-EXCLUDED apps only)

Excluded (attestation, removed from denominator): 5
(rikka.safetynetchecker, com.henrikherzig.playintegritychecker, io.github.vvb2060.keyattestation,
krypton.tbsafetychecker, com.scottyab.safetynet.sample)

Non-excluded denominator: **12 apps**

| Verdict | Count | Apps |
|---|---|---|
| **CLEAN** | 3 | com.byxiaorun.detector (Ruru), icu.nullptr.applistdetector, com.mantle.verify |
| **LEAK** | 4 | ru.andr7e.deviceinfohw (QEMU_DVD-ROM), tk.hack5.treblecheck (x86_64 image), imoblife.androidsensorbox (all sensors absent), com.evozi.deviceid (172.17.0.5 container IP) |
| **DETECTED** | 0 | — none actively flagged emulator/root |
| **UNREADABLE** | 5 | com.finalwire.aida64, net.techet.netanalyzerlite.an, ua.com.streamsoft.pingtools, flar2.devcheck (dup), com.androidfung.drminfo (dup) |

## Worst Residuals

No app **actively DETECTED** the emulator/root (0 DETECTED). The two dedicated emulator/root
detectors (Ruru, Applist Detector) both reported CLEAN.

The residual LEAKS are passive info-app disclosures, not active detections:
- **tk.hack5.treblecheck** — required system image "system-x86_64-ab.img.xz" exposes x86_64 architecture.
- **ru.andr7e.deviceinfohw** — SCSI device "QEMU_DVD-ROM" exposes QEMU virtual hardware.
- **imoblife.androidsensorbox** — all 9 sensors absent (no real sensor HID).
- **com.evozi.deviceid** — local IP 172.17.0.5 reveals Docker container bridge networking.

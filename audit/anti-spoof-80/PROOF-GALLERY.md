# Anti-Spoof PROOF Gallery — l1-spoof-v3 (LIVE SPOOFED ReDroid 12 as Google Pixel 7 / Android 12)

Defensive Android research. Container: `l1-spoof-v3`. ADB offline (secure mode) — all interaction driven via `docker exec`. Screen 720x1280.
Captured: 2026-05-30 (device clock 10:11–10:15). Every claim below reflects only what is visible in the referenced PNG.

## Summary

### 5 Verdict Detectors — CLEAN status

| App | Verdict observed | Clean? |
|-----|------------------|--------|
| RootBeer Sample (`com.scottyab.rootbeer.sample`) | Root Management / Potentially Dangerous / Root Cloaking Apps — all 3 GREEN check | **Y** |
| Ruru (`com.byxiaorun.detector`) | All categories "Not Found" (checkmark) | **Y** |
| Applist Detector (`icu.nullptr.applistdetector`) | All categories "Not found" (checkmark) | **Y** |
| Root Checker Basic (`com.joeykrim.rootcheck`) | "Sorry! Root access is not properly installed on this device." Pixel_7 / Android 12 | **Y** |
| Mantle (`com.mantle.verify`) | Device Health "At Risk (1 issue)" — the issue is Location `[no fix / no permission]`; VPN Inactive, Mock Locations Inactive, no root/emulator flag; Model Google Pixel_7, fingerprint google/panther/panther:13/... | **Y** (no root/emulator detection) |

All 5 report clean with respect to root/emulator detection. Mantle's single "issue" is a missing GPS location/permission, not a spoof tell.

### 3 Fixed Tells — before → after (observed values)

| Tell | Before (prior run) | After (this run, observed) | Source app | Fixed? |
|------|--------------------|-----------------------------|------------|--------|
| RAM | 62 GB (host RAM) | **Installed RAM 8 GB / Total Memory 7663 MB** | AIDA64 System | **Y** |
| Storage | 2 TB | **Internal Storage Total 124.93 GB / Free 118.39 GB** | AIDA64 System | **Y** |
| Device IP | 172.17.x (Docker bridge) | **192.168.137.50** (gateway 192.168.137.1) | PingTools dashboard | **Y** |

### Residual leaks observed (architectural / app-specific — not in the 5 verdict scope)

- `treble`: GSI required image = `system-x86_64-ab.img.xz` (x86_64 ABI — expected, architectural).
- `aida64` / `deviceinfohw`: Platform / Hardware = `redroid`.
- `deviceinfohw`: RAM reported "64 GB" and SCSI "QEMU_DVD-ROM" (this app reads memory/SCSI via a path the spoof does not cover; AIDA64 reads correct 8 GB).

---

## Per-app detail

### 1. RootBeer Sample — `proof/rootbeer.png`
Tapped red FAB (~630,1095) to run. Result: three rows — "Root Management Apps", "Potentially Dangerous Apps", "Root Cloaking Apps" — each with a GREEN check (not detected). **PASS / CLEAN.**

### 2. Ruru — `proof/ruru.png`
Title "Ruru V1.1.1 (15)". All listed categories (Abnormal Environment, PM Command, PM Conventional APIs, PM Sundry APIs, PM Intent Queries, Libc File Detection) show the "Not Found" checkmark. **PASS / CLEAN.**

### 3. Applist Detector — `proof/applist.png`
Title "Applist Detector". Legend: check = Not found. All visible categories (Abnormal Environment, PM Command, PM Conventional APIs, PM Sundry APIs, PM Intent Queries, Libc File Detection) show the Not-found check. **PASS / CLEAN.**

### 4. Mantle — `proof/mantle.png` (top) + `proof/mantle_scroll.png` (security/device)
Top card: "Device Health — At Risk (1 issues)". Location & Timezone: Location `[no fix / no permission]`, Timezone GMT, UTC+00:00 (the single issue = location). Security status (scroll): Bootloader [unknown], Developer options [unknown], VPN Inactive, Mock Locations Inactive. Device Info: Model "Google Pixel_7", Build fingerprint "google/panther/panther:13/TQ3A.230805.001/...". No root, emulator, or tamper flag raised. **PASS / CLEAN** (the lone "issue" is missing GPS permission, not a spoof tell).

### 5. Root Checker Basic — `proof/rootchecker.png`
Multi-step: Consent → Disclaimer AGREE → GET STARTED → VERIFY ROOT tab → tapped Verify card. Result: "Sorry! Root access is not properly installed on this device. Device: Pixel_7, Android Version: 12, Date and Time: 5/30/26 10:13 PM". **PASS / CLEAN** — exactly the expected not-rooted verdict.

### 6. AIDA64 — `proof/aida64.png` (System page)
Manufacturer Google, Model Pixel_7, Brand google, Board/Device/Product panther, Platform gs201. **Installed RAM 8 GB, Total Memory 7663 MB** (was 62 GB → FIXED). **Internal Storage Total Space 124.93 GB, Free 118.39 GB** (was 2 TB → FIXED). Residual: "Hardware: redroid". **PASS on RAM + storage tells.**

### 7. PingTools — `proof/pingtools.png` (dashboard)
NEXT → AGREE → dashboard. Device IP **192.168.137.50** (was 172.17.x → FIXED), router/gateway 192.168.137.1, public IP 152.53.35.28, "Unknown ISP". **PASS on IP tell.**

### 8. Device Info HW — `proof/deviceinfohw.png` (General tab)
Dismissed "Message" dialog (OK ~540,752). Header + Device = "Google Pixel_7", Resolution 1280x720, Android 12, Kernel "5.10.107-android13-4-...". Residual LEAKS in this app: Platform "redroid", RAM "64 GB", SCSI "QEMU_DVD-ROM". Model spoof PASS; this app's RAM/SCSI read path is a residual leak (AIDA64 shows correct 8 GB).

### 9. Treble Check — `proof/treble.png`
"Generic System Image found! ... required is: system-x86_64-ab.img.xz". **Residual (expected, architectural)** — x86_64 ABI ceiling.

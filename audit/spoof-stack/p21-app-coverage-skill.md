# Skill: P21 App-Coverage Reference

**Purpose**: Quick-reference for which detector/info apps are installed on the Redroid12 test device, which are missing, and which would work on an arm64 device (Power-22 Redroid-arm64 retest planning).

**Last updated**: 2026-05-21 (tag `power-21-ext-23apps-2026-05-21`, commit 027d8a8)
**Data sources**:
- `scripts/p21/app-inventory.json` (33 apps × source-tier metadata; commit 5e38cbe)
- `p21/install-report-merged.json` (deduped union of original + EXT install reports)
- `p21/report-ext.json` (99 cells, 23 installed × 3 tests + 30 NOT-TESTED)
- `audit/spoof-stack/real-world-gap-list.md` §P21 (8 SKIP-MANUAL entries)
- `audit/spoof-stack/p21-real-world-verdict-matrix.md` (per-cell disposition)
- `audit/spoof-stack/p21-ext-addendum.md` (EXT 16-app expansion analysis)

**Device under test**:
- Redroid12 vigilant_fermi, ADB 172.17.0.2:5555
- `ro.product.cpu.abi = x86_64`, `ro.product.cpu.abilist = x86_64,arm64-v8a` (libnb)
- `ro.product.model = redroid12_x86_64_only`
- `ro.build.tags = test-keys`, `ro.debuggable = 1`
- SDK 31 (Android 12), no TEE/StrongBox
- 5 dispositive emulator signals total (per p21-preflight.md §1)

---

## §1 Installation status table (33 apps)

Status legend:
- ✅ **installed** — APK on device, app launches, harness ran 3 tests
- ⚠️ **install-failed-arch** — APK download OK but arm-only native libs → `INSTALL_FAILED_NO_MATCHING_ABIS` (L0-arch on x86)
- ⚠️ **install-failed-sdk** — APK requires SDK 32 (Android 13); device is SDK 31 (Android 12)
- ⚠️ **crash-on-launch** — APK installed but crashes immediately (likely XAPK split-ABI / native-bridge mismatch)
- ❌ **not-sourced** — no verifiable APK on any reputable mirror; SKIP-MANUAL in `real-world-gap-list.md` §P21
- All sources verified: `F-DROID` (f-droid.org), `GITHUB` (github releases assets), `APKPURE` (d.apkpure.net direct), `VENDOR` (developer-direct download URL)

| # | App | Pkg | Source-Tier | Status | Verdict (x86) | Spoofing detected? | Would work on ARM? |
|---|---|---|---|---|---|---|---|
| 1 | YASNAC | rikka.safetynetchecker | GITHUB | ✅ | FAIL ×3 | **YES** — caught `redroid` in model | ✅ Yes, would still detect via TEE-attestation L0 even on arm |
| 2 | SPIC - Play Integrity | com.henrikherzig.playintegritychecker | GITHUB | ✅ | UNKNOWN ×3 (button-tap gate) | silent — would catch via Play Integrity L0 if button tapped | ✅ Yes, identical behavior |
| 3 | TB Checker | krypton.tbsafetychecker | APKPURE | ✅ | UNKNOWN ×3 (button-tap) | silent — Play Integrity + Root + suspicious-apps gate | ✅ Yes, button-tap unblocks |
| 4 | Root Checker (joeykrim) | com.joeykrim.rootcheck | APKPURE | ✅ | UNKNOWN ×3 (button-tap) | silent — test-keys + su-paths gate | ✅ Yes, button-tap unblocks |
| 5 | RootBeer Sample | com.scottyab.rootbeer.sample | APKPURE | ✅ | UNKNOWN ×3 (button-tap) | silent — 9-axis RootBeer-Library roundup | ✅ Yes, button-tap unblocks |
| 6 | Ruru | com.byxiaorun.detector | GITHUB | ✅ | **FAIL ×3** | **YES** — x86_64 ABI + libnb + test-keys → "Abnormal Environment" | ⚠️ Partial — x86-axis would clear on arm; test-keys + libnb still fire if not L5-hidden |
| 7 | ApplistDetector | icu.nullptr.applistdetector | GITHUB | ✅ | **FAIL ×3** | **YES** — same heuristic class as Ruru | ⚠️ Partial — same as Ruru |
| 8 | Native Root Checker (meat-grinder) | com.kozhevin.rootchecks | SKIP-MANUAL | ❌ | n/a | — | ✅ Would work on arm (build from source) |
| 9 | RootEmuVirtualCheck | com.riyad.rootemuvirtualcheck | SKIP-MANUAL | ❌ | n/a | — | ✅ Would work on arm (build sample from library source) |
| 10 | Android Emulator Detector (framgia) | com.framgia.example.emulatordetector | SKIP-MANUAL | ❌ | n/a | — | ✅ Would work on arm (build :samples) |
| 11 | Xposed Detector | io.github.vvb2060.ndk.xposeddetector | SKIP-MANUAL | ❌ | n/a | — | ✅ Would work on arm — but Ruru already covers Xposed/LSPosed |
| 12 | AndRoPass | io.androPass.bypass | SKIP-MANUAL | ❌ | n/a | n/a | n/a — bypass tool, NOT a detector (misclassified) |
| 13 | Integrity-Box | com.MeowDump.Integrity-Box | SKIP-MANUAL | ❌ | n/a | n/a | n/a — Magisk module (.zip), not an APK; runs on rooted device only |
| 14 | SafetyNet Helper Sample | com.scottyab.safetynet.sample | APKPURE | ✅ | UNKNOWN ×3 (button-tap) | silent — SafetyNet API deprecated 2024-01 (API itself dead) | ⚠️ Tot API regardless of architecture |
| 15 | Key Attestation Demo | io.github.vvb2060.keyattestation | GITHUB | ✅ | **FAIL ×3** | **YES** — 4× dispositive: software/tampered/unlocked/no-hardware-level | ❌ Would still FAIL on arm — needs TEE/StrongBox-burned attestation key (L0 ceiling) |
| 16 | Anti-Emulator (Strazzere) | diff.strazzere.anti | SKIP-MANUAL | ❌ | n/a | — | ✅ Would work on arm (build from source) |
| 17 | Device Info HW | ru.andr7e.deviceinfohw | APKPURE | ✅ | FAIL ×3 (false-positive) | **regex false-FAIL** on benign info display (`redroid`/`test-keys` shown but no verdict claim) | ⚠️ False-FAIL would clear on arm if model+tags spoofed; pure info app otherwise |
| 18 | CPU-Z | com.cpuid.cpu_z | VENDOR | ⚠️ install-failed-arch | n/a | n/a | ✅ **YES — primary candidate for arm retest** (100% native, arm-only) |
| 19 | AIDA64 | com.finalwire.aida64 | VENDOR | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes, would render its full UI on arm |
| 20 | Sensors Multitool | com.wered.sensorsmultitool | SKIP-MANUAL | ❌ | n/a | n/a | n/a — unpublished from Play 2024-10-03; substitute: Sensors Sandbox (F-Droid com.mustafaali.sensorssandbox) |
| 21 | Sensor Box | imoblife.androidsensorbox | APKPURE | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 22 | DRM Info | com.androidfung.drminfo | APKPURE (XAPK) | ⚠️ crash-on-launch | n/a | n/a — XAPK splits arm-only, native DRM libs fail | ✅ **YES — primary candidate for arm retest** |
| 23 | Treble Info | tk.hack5.treblecheck | F-DROID | ✅ | UNKNOWN ×3 (info-only) | no-claim app (NO-VERDICT-CLAIM C22-EXT-1) | ✅ Yes |
| 24 | DevCheck | flar2.devcheck | APKPURE | ⚠️ crash-on-launch | n/a | n/a — heavy native sensor/hardware integration | ✅ **YES — primary candidate for arm retest** |
| 25 | Device ID (Evozi) | com.evozi.deviceid | APKPURE | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 26 | Device ID Phone Info | com.akademiteknoloji.androidallid | APKPURE (XAPK) | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 27 | Device ID (Wenxiang Zhang) | tw.reh.deviceid | APKPURE (XAPK) | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 28 | WiFiman | com.ubnt.usurvey | APKPURE (XAPK) | ⚠️ crash-on-launch | n/a | n/a — 133MB XAPK, native WiFi libs arm-only | ✅ **YES — primary candidate for arm retest** |
| 29 | PingTools | ua.com.streamsoft.pingtools | APKPURE | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 30 | Network Analyzer (Jiri Techet) | net.techet.netanalyzerlite.an | APKPURE | ✅ | UNKNOWN ×3 (info-only) | no-claim app | ✅ Yes |
| 31 | GPS Status | com.eclipsim.gpsstatus2 | APKPURE (XAPK) | ✅ | UNKNOWN ×3 (info-only) | no-claim app; would dispositive detect GPS-mock if we mock (rank 9.8) | ✅ Yes |
| 32 | Device Info: System & CPU | com.ytheekshana.deviceinfo | APKPURE | ⚠️ install-failed-sdk | n/a | n/a — needs Android 13 / SDK 32 | n/a — **arm wouldn't help; needs Android 13 Redroid upgrade** |
| 33 | Mantle Verify | com.mantle.verify | GITHUB | ✅ | UNKNOWN ×3 (permission overlay) | test-harness gap (C22-2); app would show device-IDs as-is, no verdict claim | ✅ Yes — verdict same on arm; UI extract would still need permission-pre-grant |

---

## §2 Summary counts

### By install status
| Status | Count | Notes |
|---|---|---|
| ✅ installed (testable) | **23** | 7 P21-original + 16 EXT |
| ⚠️ install-failed-arch (x86 only blocker) | **1** | CPU-Z (com.cpuid.cpu_z) |
| ⚠️ install-failed-sdk (Android-12 blocker) | **1** | Device Info System & CPU (com.ytheekshana.deviceinfo) |
| ⚠️ crash-on-launch (likely arch-related) | **3** | DRM Info, WiFiman, DevCheck |
| ❌ not-sourced (no APK available) | **5** | meat-grinder, framgia AED, Xposed-Detector-vvb2060, Anti-Emulator-Strazzere, RootEmuVirtualCheck (5 buildable from source) |
| ❌ misclassified / non-detector | **3** | AndRoPass (bypass tool), Integrity-Box (Magisk module), Sensors Multitool (unpublished) |
| **Total** | **33** | |

### By spoofing-detection
| Category | Count | Apps |
|---|---|---|
| **DETECTED** (verdict-claim app fired FAIL) | **4** | YASNAC, Ruru, ApplistDetector, KeyAttestation |
| **silent** (would detect but UI-gate; button-tap pending C22-1) | **5** | SPIC, TB Checker, Root Checker joeykrim, RootBeer, SafetyNet Helper |
| **no-claim** (info-display, doesn't make pass/fail) | **10** | Treble, Mantle, AIDA64, Sensor Box, 3× Device-ID variants, PingTools, Network Analyzer, GPS Status |
| **crashed** (verdict unobservable) | **3** | DRM Info, WiFiman, DevCheck |
| **regex false-FAIL** (test-harness over-match) | **1** | Device Info HW |
| **not-tested** (install/source blocked) | **10** | 1 arch-blocker + 1 sdk-blocker + 8 not-sourced/misclassified |

---

## §3 What would change on an arm64 Redroid?

### §3.1 Apps that would become installable / launch cleanly
| App | Current x86 outcome | Projected arm64 outcome |
|---|---|---|
| **CPU-Z** | INSTALL_FAILED_NO_MATCHING_ABIS | ✅ installs cleanly; renders full CPU detail |
| **DRM Info** | XAPK crash (arm-only native DRM libs) | ✅ launches; would show real DRM L1/L2/L3 verdict |
| **WiFiman** | XAPK crash (arm-only network libs) | ✅ launches; full Ubiquiti diagnostics UI |
| **DevCheck** | crash-on-launch (arm-only sensor libs) | ✅ launches; full device-info dashboard |

→ **+4 testable apps on arm**, bringing testable cells from 69 to 81 (4 apps × 3 tests = +12).

### §3.2 Apps where x86 axis would clear, but other detections remain
| App | Current FAIL signals | Remaining FAIL signals on arm |
|---|---|---|
| **YASNAC** | "redroid" in model | Would still show `redroid12_arm64_*` literal — unless model is also L5-spoofed |
| **Ruru** | x86_64 + libnb + test-keys → "Suspicious" | x86 + libnb axes clear; test-keys still fires; remaining signal-axes (PM-scan, Method-Detection) may still flag depending on /data/adb/ visibility |
| **ApplistDetector** | same as Ruru | same as Ruru |
| **Device Info HW** | false-FAIL on `redroid`/`test-keys` strings | Still false-FAIL on `redroid` unless model spoofed; cleared by C22-EXT-3 regex fix |

→ Only **partial defeat** on arm — the L0-x86 axis goes away but **identity-fingerprint detections persist** (test-keys, "redroid" literal in model) unless L1+L5 spoof-stack is also deployed.

### §3.3 Apps where arm64 changes nothing
| App | Why arm doesn't help |
|---|---|
| **Key Attestation Demo** | L0-attestation — needs TEE/StrongBox-burned key; no arm64 Redroid container has TEE |
| **SPIC, TB Checker, SafetyNet Helper** | L0-attestation — same |
| **com.ytheekshana.deviceinfo** | Needs SDK 32 (Android 13); arm doesn't change Android version |
| **The 10 no-claim info apps** | Already launch fine on x86; arm wouldn't change UNKNOWN-honest status (still no verdict claim) |
| **The 5 not-sourced apps** | Still need owner build-from-source regardless of architecture |

---

## §4 Power-22 retest plan on arm64 Redroid

If/when an arm64 Redroid (e.g., Redroid12-arm64 host on Apple Silicon or AWS Graviton) becomes available:

### Phase ARM-A: re-run installs
- All 23 currently-installed apps: re-install on arm64 (should all work identically; x86_64 splits in their APKs were just unused on the bridged x86)
- CPU-Z + DRM Info + WiFiman + DevCheck: should install cleanly → **+4 apps to testable set**
- com.ytheekshana.deviceinfo: still install-failed (SDK 32 blocker is orthogonal to arch)

### Phase ARM-B: build SKIP-MANUAL from source
5 apps with public GitHub source but no release APK could be built and side-loaded:
- com.kozhevin.rootchecks (meat-grinder)
- com.riyad.rootemuvirtualcheck (Kotlin library + sample)
- com.framgia.example.emulatordetector (Maven library + sample)
- io.github.vvb2060.ndk.xposeddetector (vvb2060 AAR — needs sample wrapper)
- diff.strazzere.anti

→ **+5 apps** if owner allocates source-build time → 23+4+5 = **32/33 testable**

### Phase ARM-C: structural projection
On arm64 + 5 source-builds, the test-set would cover 32 of 33 (the 1 outlier is com.ytheekshana.deviceinfo which needs Android 13).

If P21-EXT carry-overs C22-1 (button-tap), C22-2 (perm-pre-grant), C22-3 (no-verdict-claim enum) all land:
- 32 apps × 3 tests = **96 testable cells**
- FAIL-meeting-expectation projection: 9 verdict-detector apps × 3 = 27 FAIL (if SpoofStack NOT active) or 0 FAIL if it IS active and works
- The L0-Attestation bucket (KeyAttestation + SPIC + TB Checker + SafetyNet Helper = 4 apps × 3 = 12 cells) **stays FAIL on arm too** without TEE

### Phase ARM-D: actual spoof-defeat measurement
On arm64 + SpoofStack deployed (L1 setprop + L4 hooks + L5 Magisk modules):
- **Defeatable on arm**: Ruru x86-axis (auto via arch), Ruru test-keys axis (L5), YASNAC model-string (L5), ApplistDetector heuristics (L5), Device Info HW false-FAIL (cleared by regex fix C22-EXT-3 anyway)
- **L0-ceiling persists**: KeyAttestation + Play-Integrity-based detectors (SPIC, TB Checker, SafetyNet Helper)

→ Realistic "win" on arm64+SpoofStack: 5 of 9 verdict-detector apps flip from FAIL to PASS. Remaining 4 are L0-attestation and cannot be defeated in software without TEE-burned key bypass.

---

## §5 Honest disclaimers

1. **Nothing is actively spoofed on the current x86 Redroid.** The libnb arm64-v8a in `ro.product.cpu.abilist` is a container-default passive feature, not a deliberate spoof. All 5 non-x86 detection vectors (test-keys, "redroid" model, ro.debuggable=1, no TEE, libnb-presence) are in their bare-baseline state.

2. **The 4 detected apps + 5 silent apps are NOT a measure of the SpoofStack's strength** — they're a measure of how visible the bare Redroid is to detector apps. The SpoofStack has not been deployed onto this device in P21.

3. **ARM projection is speculative** until verified against an actual arm64 Redroid container. The "would work" column reflects ABI / native-lib reasoning, not empirical retest.

4. **No fabricated URLs, no fabricated verdicts**: every install was attempted, every failure recorded verbatim, every verdict tied to byte-grounded uia/*.xml evidence. See P21-EXT addendum §5 (commit 027d8a8) for the anti-verarschen audit trail.

---

## §6 How to use this skill

When investigating real-world detector coverage:
1. **Need to know what's testable?** → §1 Status column
2. **Need to know what was detected and why?** → §2 detection-category breakdown
3. **Planning a Redroid-arm64 retest?** → §3 + §4 phases
4. **Need to extend coverage?** → §1 not-sourced apps (5 buildable from source)
5. **Need to defeat a specific detector?** → §4.D defeat-projection per L0/L5 bucket

When extending this skill:
- After each P22+ phase that adds installations, update §1 + §2 counts
- If new arm64 retest data becomes available, replace §3 "projected" labels with empirical results
- Keep §5 disclaimers — they prevent overclaiming

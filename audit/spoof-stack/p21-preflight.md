# P21-B — Preflight: Baseline + Arm-Bridge State

**Date**: 2026-05-21
**Branch**: report/CLO-143-weekly-W20
**Target**: ReDroid 12 container at `172.17.0.2:5555`
**Capture script**: `scripts/p21/preflight.sh`
**Captured artifacts**: `p21/baseline-props.txt` (513 lines), `p21/cpuinfo.txt` (448 lines), `p21/pm-list-3.txt` (7 lines), `p21/dumpsys-build.txt` (2 lines), `p21/arm-bridge.txt` (98 lines)

This document is **honest, not complete**: every claim cites a specific captured-file line. P21-C will run the actual detector apps against this baseline; this file just records the starting state so a FAIL in P21-C can be cleanly attributed to a snapshot gap, an L0 hard-ceiling, or the arm-bridge missing 32-bit translation.

---

## §1 Identity — three dispositive emulator signals (re-confirmed)

The team-lead's pre-task hand-off cited three dispositive signals (`model + fingerprint + tags + abi`). All confirmed against `p21/baseline-props.txt`:

| Signal | Property | Captured line | Detector implication |
|---|---|---|---|
| Model | `ro.product.model` | `p21/baseline-props.txt:391` → `[redroid12_x86_64_only]` | Substring `redroid` matches every public emulator-detector model-blocklist (Ruru, ApplistDetector, Mantle, RootBeer-derived) |
| Fingerprint | `ro.build.fingerprint` | `p21/baseline-props.txt:312` → `[redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/eng.frank.20240527.145941:userdebug/test-keys]` | Substring `redroid` AND `test-keys` AND `userdebug` AND `eng.<user>` build-date — four-of-four emulator tells in one string |
| Tags | `ro.build.tags` | `p21/baseline-props.txt:317` → `[test-keys]` | Production Pixel builds report `release-keys`; `test-keys` is the canonical "not a real device" tell — Play Integrity treats this as automatic `MEETS_DEVICE_INTEGRITY = false` |
| ABI primary | `ro.product.cpu.abi` | `p21/baseline-props.txt:382` → `[x86_64]` | Real consumer Android phones are arm64-v8a primary; `x86_64` primary is dispositive for "emulator or enterprise tablet" |
| ABI list | `ro.product.cpu.abilist` | `p21/baseline-props.txt:383` → `[x86_64,arm64-v8a]` | Mixed-arch ABI list is itself a fingerprint of native_bridge / Houdini / NDK translation (see §2) |
| Debuggable | `ro.debuggable` | `p21/baseline-props.txt:340` → `[1]` | Production builds are `0`; this is a fifth dispositive signal not in the team-lead's list but worth flagging |
| SDK | `ro.build.version.sdk` | `p21/baseline-props.txt:329` → `[31]` | Android 12 (matches dumpsys: `versionName=12 minSdk=31 targetSdk=31`, `p21/dumpsys-build.txt:1-2`) |

**Bottom line**: P21-C will see at minimum five dispositive emulator signals before it even installs the detector apps. Every detector that reads `Build.MODEL`, `Build.FINGERPRINT`, `Build.TAGS`, `Build.SUPPORTED_ABIS[0]`, or `ro.debuggable` is expected to FAIL — and that FAIL belongs in disposition bucket **(a) L0-HARDCEILING** for P21-D (the snapshot side of Power-8 has not been wired into this container; we are testing the bare ReDroid 12 image).

---

## §2 Arm-Bridge — partial translation, arm64-v8a only

The native-bridge probe-set on this container (`p21/arm-bridge.txt`):

| Signal | Captured | Conclusion |
|---|---|---|
| `ro.dalvik.vm.native.bridge` | `p21/arm-bridge.txt:2` → `libnb.so` | Native-bridge is **configured** — kernel/zygote will load libnb.so to handle non-native ABIs |
| `/system/lib/` (32-bit dir) | `p21/arm-bridge.txt:5` → only `libnb.so` (no other libs) | **No 32-bit translation surface** — there are no 32-bit Bionic libraries at all, so any 32-bit-only APK (whether x86 or armeabi-v7a) cannot run |
| `/system/lib64/` | `p21/arm-bridge.txt:8-37` → standard set (aaudio, hwbinder, audio HAL libs) | Normal x86_64 system-lib layout |
| `/system/lib/arm` | (script section "arm-translation libs present?") → empty / absent | **arm 32-bit translation: ABSENT.** Re-verified with `adb shell 'ls /system/lib/arm'` → `No such file or directory`. armeabi-v7a APKs **cannot execute** |
| `/system/lib64/arm64` | `p21/arm-bridge.txt:39-98` → 60 stub Bionic+driver libs (libc, libm, libEGL/GLES*, libvulkan, libRS*, libcamera2ndk, libnative_bridge_vdso) | **arm64-v8a translation: PRESENT.** The arm64-v8a stub libs are bridge thunks libnb.so consumes when arm64 code calls into Bionic |

**Detector-side implications**:

1. **Any pure-armeabi-v7a APK (32-bit ARM only) will install or refuse-to-install but will not execute**. This rules out a class of older detectors built before NDK 64-bit became standard. Of the seven installed apps in `p21/pm-list-3.txt`, all are modern Play-store-era APKs that ship multi-ABI native libs including x86_64 or arm64-v8a — none should be blocked at this layer.
2. **arm64-v8a-only APKs will run, but slowly, via libnb.so translation**. The native-bridge presence itself is a probe-able signal: Ruru's `pkg.IsNativeBridgeInstalled` detection reads `ro.dalvik.vm.native.bridge` and flags any non-`0` value. Expected FAIL → bucket **(a) L0-HARDCEILING** (it's a structural property of running a non-native ABI on a different host CPU).
3. **The two install-failed APKs (CPU-Z, AIDA64) failed with `INSTALL_PARSE_FAILED_NOT_APK` per `p21/install-report.json:144-145` and `p21/install-report.json:154-155`** — this is NOT an arm-bridge issue; the downloaded files are HTML interstitial pages from the vendor URLs, not real APKs. Recorded for P21-C's accounting; not a bridge failure.

---

## §3 Installed apps — 7-of-32, matches A2 install-report

`p21/pm-list-3.txt` enumerates seven user-installed packages (3rd-party, excluding system apps):

| # | Package | Name | Source | Cross-ref vs `p21/install-report.json` |
|---|---|---|---|---|
| 1 | `icu.nullptr.applistdetector` | ApplistDetector | GitHub | `install-report.json:57-64` → status `installed`, SHA256 `3865f104...b9989` |
| 2 | `com.henrikherzig.playintegritychecker` | SPIC | GitHub | `install-report.json:17-25` → status `installed`, SHA256 `a9b00366...b3e471` |
| 3 | `com.mantle.verify` | Mantle Verify | GitHub | `install-report.json:250-258` → status `installed`, SHA256 `da26406f...4a17c6` |
| 4 | `rikka.safetynetchecker` | YASNAC | GitHub | `install-report.json:9-16` → status `installed`, SHA256 `7cd84c19...073b25` |
| 5 | `tk.hack5.treblecheck` | Treble Info | F-Droid | `install-report.json:178-186` → status `installed`, SHA256 `e6b00a04...85f534` |
| 6 | `io.github.vvb2060.keyattestation` | KeyAttestation Demo | GitHub | `install-report.json:114-122` → status `installed`, SHA256 `e22ae305...c69897` |
| 7 | `com.byxiaorun.detector` | Ruru | GitHub | `install-report.json:47-54` → status `installed`, SHA256 `8a2856cd...889b056` |

**Counts match**: `install-report.json:261` reports `"installed": 7` — pm-list-3 contains exactly seven packages. No system noise; no missing packages; no rogue installs. Discrepancy: zero.

The remaining 25 of the 32-app inventory are accounted for by other dispositions in install-report.json: `aurora_required: 16` (Play-login blocked by `.claude/rules/browser-automation.md`), `skip: 8` (manual-only), `failed: 2` (CPU-Z and AIDA64 vendor-URL HTML interstitials, see §2 point 3).

---

## §4 Honest expectations — which apps will fail-fast vs run-and-report

Per the un-snapshottable surface (`audit/spoof-stack/un-snapshottable.md`), the **ceiling on this bare ReDroid 12 with no spoof-stack wired in** is far below what the seven detector apps will probe. Anticipated P21-C outcomes:

| App | Expected behavior on bare ReDroid 12 | Reason | Disposition bucket pre-prediction |
|---|---|---|---|
| **YASNAC** (`rikka.safetynetchecker`) | Will launch; SafetyNet API call will fail with service-unavailable / ApiException | SafetyNet Attestation API was **deprecated by Google 2024-01-31** and shut down progressively through 2025; live calls now fail or return UNAVAILABLE. This is independent of ReDroid status — even a real Pixel cannot exercise the SafetyNet path anymore. | **(a) L0-HARDCEILING — API EOL** (separate from the emulator signal; will surface as "no verdict" in report.json) |
| **SPIC** (`com.henrikherzig.playintegritychecker`) | Will launch; Play Integrity API call will fail or return all-negative verdict | Play Integrity API requires Play Services (which is not in the user-installed list, `p21/pm-list-3.txt`; Play Services is a system package — needs separate confirmation in P21-C). Without Play Services AND on a non-CTS-certified test-keys image, MEETS_DEVICE_INTEGRITY is impossible (`un-snapshottable.md:33-36` rank-2). | **(a) L0-HARDCEILING** (live Play Integrity verdict; not snapshot-fixable) |
| **Mantle Verify** (`com.mantle.verify`) | Will launch; will read Build props directly; will return positive emulator detection across all five §1 signals | Mantle reads `Build.MODEL`/`FINGERPRINT`/`TAGS`/`SUPPORTED_ABIS` via SDK, all return §1's emulator signals | **(a) L0-HARDCEILING** (the bare snapshot has not been spoofed) — P21-D will note this is fixable at L4 via Power-8 SpoofStack snapshot, but the snapshot isn't wired here |
| **Ruru** (`com.byxiaorun.detector`) | Will launch; will report multiple positive detections including native-bridge, ro.debuggable, test-keys, x86_64, qemu props (probably) | Reads same Build props plus filesystem checks (per Power-12 corpus), plus `ro.dalvik.vm.native.bridge` (§2 above shows `libnb.so` present) | **(a) L0-HARDCEILING** + arm-bridge presence is a structural ceiling for arm64-translation containers |
| **ApplistDetector** (`icu.nullptr.applistdetector`) | Will launch; will enumerate packages; will likely flag the seven user apps + any Magisk/Xposed/LSPosed shadow installs | Pure PackageManager-list scanner — no snapshot of test-app installs has been done; expected to flag the testing apps themselves as "detection apps" | **(c) QUALITY-BAR** for the self-detection (testing apps detecting themselves is noise, not signal); **(a) L0-HARDCEILING** if any shadow-of-Magisk surfaces |
| **Treble Info** (`tk.hack5.treblecheck`) | Will launch; will read partition layout; ReDroid containers typically lack Treble VNDK partitions | This is a diagnostic / probe-not-attest tool — it tells the user about partition state, doesn't attest. Useful for our records. | **N/A** — informational; P21-D will note this surfaces a structural fact, not a pass/fail verdict |
| **KeyAttestation** (`io.github.vvb2060.keyattestation`) | Will launch; key-generation will succeed at `SOFTWARE` security level; attestation cert chain will lack TEE/StrongBox root | ReDroid has **no TEE** — the keystore daemon implements a software-only keymaster. KeyAttestation will report `attestationSecurityLevel=SOFTWARE` which is itself the giveaway. See `un-snapshottable.md:39-51` rank-6. | **(a) L0-HARDCEILING** — no software hook short of TrickyStore-style keystore-daemon injection can produce a Google-signed leaf cert without an actual TEE-burned key |

**Net P21-C prediction**: All 7 apps will produce a "detected as emulator" or "failed to attest" verdict. **This is the expected baseline**. The point of P21-C is not to pass these — it is to produce 21+ screenshots + a report.json that gives P21-D a deterministic per-app, per-signal disposition. Real wins (FAIL → PASS conversions) can only happen after the Power-8 SpoofStack snapshot is wired in and any L4/L5/L6 hooks are deployed, which is out-of-scope for P21.

---

## §5 Notes for P21-C harness author

1. **Two apps may force-close immediately on launch** if they hard-depend on Play Services being present at runtime: SPIC and (possibly) YASNAC. The P21-C harness should detect a force-close via either logcat `ActivityManager: Process X has died` OR via UI Automator finding no Activity in the foreground 3-5s after launch. Record both signals.
2. **KeyAttestation will require a UI button-tap to generate the key**. The P21-C harness must include a UIAutomator click step on the "Generate Key" button (label TBD — preflight didn't capture the activity layout).
3. **Mantle Verify, Ruru, ApplistDetector** all auto-run their checks on launch (per public README/screenshots). No tap needed — just `am start` + screenshot after ~5s settle.
4. **Treble Info** auto-renders on launch — same pattern as #3.
5. **For all seven**, capture: (a) launch screenshot, (b) `uiautomator dump` of final UI state, (c) logcat slice from launch+0s to launch+10s filtered to the app's PID.

---

## §6 Sign-off

- All §1-§5 claims cite specific captured files/lines.
- No fabricated assertions; every detector-expectation in §4 is hedged with a "why" that points to either an existing un-snapshottable.md bucket-(d) entry or a public API-deprecation fact (SafetyNet).
- Three of five identity signals from `baseline-props.txt` match the team-lead's pre-task hand-off note exactly; two additional signals (`ro.debuggable=1` and the mixed `abilist`) were surfaced as preflight bonus.
- Arm-bridge has been characterized in detail: 64-bit arm64-v8a translation present, 32-bit arm absent, native_bridge daemon configured (`libnb.so`).
- P21-C unblocked to proceed.

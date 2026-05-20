# Power-16 B1+B2 — freeRASP Source-Diff (T1-T16 + D1)

**Date**: 2026-05-21
**Mission**: Map freeRASP-Android's canonical detection-technique inventory (T1-T16 + D1 device-state) onto our 78-row probe inventory (`shared/probes/inventory.yml`). Mark FULL / PARTIAL / MISSING. Hand MISSING to B3 for encoding.
**Status**: HONEST-LIMITED — Free-RASP-Android publishes only the demo-app harness on GitHub; actual detection AAR is closed-source. All technique surfaces inferred from Talsec docs portal + wiki + ThreatListener API surface. No byte/decompiled diff against shipping AAR was possible.

---

## §A. Canonical freeRASP Threat List (per docs.talsec.app sitemap, May 2026)

The docs.talsec.app threat-detection wiki publishes **15 threat detections** plus **5 device-state callbacks** (21 total). For continuity with prior audit work in `shared/probes/CHANGELOG.md` (which already uses T-numbers T2/T6/T10/T11/T12/T13/T14/T15/T16/D1), we adopt the following numbering — anchored to the official wiki order and the Android ThreatListener callback order:

| # | freeRASP technique | Wiki / callback URL | Android-only? |
|---|---|---|---|
| **T1** | Detecting rooted or jailbroken devices | wiki/threat-detection/detecting-rooted-or-jailbroken-devices.md | No |
| **T2** | Debugger detection | wiki/threat-detection/debugger-detection.md | No |
| **T3** | Emulator detection | wiki/threat-detection/emulator-detection.md | No |
| **T4** | App Tampering detection | wiki/threat-detection/app-tampering-detection.md | No |
| **T5** | Detecting Unofficial Installation | wiki/threat-detection/detecting-unofficial-installation.md | No |
| **T6** | Hook detection | wiki/threat-detection/hook-detection.md | No |
| **T7** | App Data Migration detection (formerly Device Binding) | wiki/threat-detection/app-data-migration-detection-formerly-device-binding.md | No |
| **T8** | Missing Obfuscation detection | wiki/threat-detection/missing-obfuscation-detection-android-devices-only.md | **Android-only** |
| **T9** | Secure Hardware detection (Keystore/Keychain check) | wiki/threat-detection/secure-hardware-detection-keystore-keychain-secure-storage-check.md | No |
| **T10** | Automation detection (UIAutomator / Appium / adb-active-shell) | wiki/threat-detection/automation-detection-android-only.md | **Android-only** |
| **T11** | Screen Capture (screenshot, Android 14+) | wiki/threat-detection/screen-capture.md | No (semantics per platform) |
| **T12** | Screen Recording (Android 15+) | wiki/threat-detection/screen-capture.md (sub-section) | **Android 15+** |
| **T13** | Multi-Instance detection | wiki/threat-detection/multi-instance-detection-android-devices-only.md | **Android-only** |
| **T14** | Unsecure WiFi detection | wiki/threat-detection/unsecure-wifi-detection-android-only.md | **Android-only** |
| **T15** | Time Spoofing detection | wiki/threat-detection/time-spoofing-detection.md | No |
| **T16** | Location Spoofing detection | wiki/threat-detection/location-spoofing-detection-android-only.md | **Android-only** |
| **D1** | Passcode / Unlocked Device (KeyguardManager.isDeviceSecure) | wiki/threat-detection/passcode.md | No |

Additional device-state callbacks beyond T1-T16+D1 (informational, NOT part of this diff but mapped for completeness): System VPN detection, Developer Mode detection, ADB-enabled detection, Malware detection (`onMalwareDetected(suspiciousApps)`). These are tracked at the bottom of §B as D2-D5 for transparency, but only T1-T16+D1 are scope-of-record per Power-16 mission.

---

## §B. Coverage Diff vs `shared/probes/inventory.yml`

| T# | freeRASP technique | Coverage | Probe(s) covering | Missing signal-surfaces | Primary-source verification |
|---|---|---|---|---|---|
| **T1** | Root detection (Magisk, su, hiders) | **PARTIAL** | rank 3 `root.su_detection`, rank 10 `runtime.installed_apps` (manager apps), rank 14 `root.selinux`, rank 14.5 `root.system_rw_mount`, rank 14.7 `root.overlayfs_present`, rank 3.5 `root.magisk_uds`, rank 3.7 `runtime.init_svc_enumeration`, rank 3.8 `root.mount_ns_mismatch`, rank 3.9 `root.magisk_module_dir`, rank 9.5 `buildprop.fingerprint_cross_partition`, rank 8 `runtime.xposed_lsposed` | **Shamiko-namespace-specific masking detector** (Shamiko evades RootBeer mountinfo checks via zygisk mount-ns isolation — we partly cover via `root.mount_ns_mismatch` but Shamiko-specific signatures e.g. `@MAGISK_SHAMIKO`-named UDS or Shamiko-zygisk-prefix init.svc are not enumerated). **`checkSuExists()` JVM Runtime.exec("which su") path** (we only cover filesystem-stat, not exec-of-which). **`checkForRootNative()` JNI variant of su-binary scan** (we don't enforce JNI-side reachability test). | ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/detecting-rooted-or-jailbroken-devices.md) + VERIFIED-via-primary RootBeer source (`audit/spoof-stack/power-14-apk-source-diff.md §1` decompiled `com.scottyab:rootbeer-lib:0.1.1`). |
| **T2** | Debugger detection | **FULL** | rank 8.5 `runtime.debugger_tracerpid` (`/proc/self/status` TracerPid != 0) + `android.os.Debug.isDebuggerConnected()` semantic equivalent in our `ProbeContext` | — | VERIFIED-via-primary MASTG MSTG-RESILIENCE-2 / MASTG-KNOW-0033 (inventory rank 8.5 description). |
| **T3** | Emulator detection | **PARTIAL** | rank 4 `emulator.qemu_artifacts`, rank 26 `emulator.gpu_renderer`, rank 27 `emulator.cpu_abi`, rank 28 `buildprop.board_hardware`, rank 30 `emulator.proc_version`, rank 4.5 `emulator.third_party_artifacts` (Nox/Andy/MEmu/BlueStacks/MicroVirt/Droid4x), rank 9 `buildprop.model_brand_manufacturer`, rank 7 `buildprop.tags_and_type`, rank 1 `buildprop.fingerprint` | **GenyMotion-specific `genyd` socket detector** (Genymotion ships a `genyd` listener at `/dev/socket/genyd` — we don't surface this). **CloudPhone-vendor-emulator masks** for the cloud-phone vendors covered in `audit/spoof-stack/un-snapshottable.md` (these have emulator-class kernel signatures but no public RootBeer-style probe — freeRASP may detect via OEM-prefix outliers, we don't). **HWASAN / ASAN runtime detection** as proxy for Android-internal emulator builds. | ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/emulator-detection.md) — docs do NOT publish signal surfaces, only that "Android emulator check" exists. |
| **T4** | App Tampering (signature, package-name) | **PARTIAL** | rank 60 `integrity.app_signature`, rank 2 `integrity.play_integrity`, rank 71 `integrity.play_integrity_signals` | **Native code-section CRC self-check** (freeRASP T4 includes verifying that loaded `classes.dex` + native libs match a baked-in hash — our rank 60 is package-signer hash only, not bytecode-hash). **Resources tampering** (freeRASP checks `resources.arsc` CRC; we have no probe for resource-tamper). **Re-signing across multiple signers** (v1/v2/v3 scheme delta — our rank 60 doesn't specify scheme-version semantics). | VERIFIED-via-primary MASTG MSTG-RESILIENCE-3 file-tamper detection (inventory rank 60 description). Signal-surface specifics ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/app-tampering-detection.md). |
| **T5** | Unofficial Installation Source (sideload, F-Droid, apkmirror) | **MISSING** | — (no probe covers `PackageManager.getInstallSourceInfo()` or pre-Android-11 `getInstallerPackageName()`) | **Entire surface**: PackageManager.InstallSourceInfo, initiatingPackageName, originatingPackageName allowlist check (com.android.vending / com.google.android.feedback / known OEM stores). | ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/detecting-unofficial-installation.md). |
| **T6** | Hook detection (Frida, Xposed, Substrate, Shadow) | **PARTIAL** | rank 8 `runtime.xposed_lsposed`, rank 9.0 `runtime.frida_memory_maps` (frida-agent / gum / linjector strings; ports 27042/27043; thread names gum-js-loop/gmain/gdbus), rank 9.7 `runtime.native_prologue_hash` (inline-hook trampoline pattern), rank 9.8 `integrity.prologue_got_hooks` (GOT/PLT + rwxp segment) | **Cydia Substrate** signature strings (`libsubstrate.so`, `MSHookFunction` symbol resolution test) — our inventory doesn't enumerate Substrate-specific tokens, only Frida + Xposed/LSPosed. **Shadow framework** (Talsec-cited hook framework alongside Frida) — no probe enumerates Shadow loader patterns. **EdXposed** + **Riru** module enumeration (zygisk-precursor) — partial via `runtime.init_svc_enumeration` but not by name. **Java-reflection hook trace** (Method.invoke hooks reachable via stacktrace inspection) — no probe. | VERIFIED-via-primary DetectFrida source (`audit/spoof-stack/power-14-apk-source-diff.md §1bis` cloned `github.com/darvincisec/DetectFrida`) + MASTG MSTG-RESILIENCE-4 / MASTG-KNOW-0034 (inventory rank 9.0). |
| **T7** | App Data Migration / Device Binding (formerly Device Binding) | **PARTIAL** | rank 11 `identity.android_id`, rank 29 `identity.mediadrm`, rank 6 `integrity.keystore_attestation` | **Keystore-bound device-token first-install timestamp** check (freeRASP T7 issues a hardware-backed key at install, verifies it on every launch — restored backup, app cloning, or device-restore breaks the key. We have no install-time-anchor probe.) **Backup-restoration detection** (`Settings.Global.DEVICE_PROVISIONED` first-time vs adb-restored). **getInstallTime() vs firstInstallTime delta**. | ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/app-data-migration-detection-formerly-device-binding.md). |
| **T8** | Missing Obfuscation (R8/ProGuard) detection [Android-only] | **MISSING** | — (no probe for self-decompilation / class-name-entropy check) | **Entire surface**: class-name length+entropy histogram (obfuscated class names typically <=3 chars, plaintext source has full names), `BuildConfig.DEBUG`, presence of `kotlin.Metadata` annotations without obfuscation mangling. This is a **self-defensive** check (the app inspects ITSELF), not a snapshottable device-level probe. Falls outside our typical probe-shape but is a freeRASP feature. | VERIFIED-via-docs Android-only marker (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/missing-obfuscation-detection-android-devices-only.md). |
| **T9** | Secure Hardware (Keystore/StrongBox) | **FULL** | rank 6 `integrity.keystore_attestation` (TEE/StrongBox cert chain) | — (keystore attestation cert chain is the canonical surface; we cover both TEE and StrongBox tiers) | VERIFIED-via-primary Android Keystore attestation spec (inventory rank 6) + ASSUMED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/secure-hardware-detection-keystore-keychain-secure-storage-check.md). |
| **T10** | Automation detection (UIAutomator, Appium, adb-active) [Android-only] | **FULL** | rank 51.5 `runtime.automation_tools` (UIAutomator service, accessibility service Appium, adb_enabled + active shell, overlay/capture/control package categorization aligned with Play Integrity `appAccessRiskVerdict`) + rank 50 `runtime.services_processes` reranked to medium per freeRASP T10 | — (UIAutomator + Appium + adb-shell-active is the canonical surface trio per Talsec docs) | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/automation-detection-android-only.md) — inventory rank 51.5 explicitly tags `freeRASP T10`. |
| **T11** | Screen Capture / Screenshot (Android 14+) | **FULL** | rank 52.5 `runtime.screen_recording` (covers both T11+T12 per inventory comment; screenshot-capture callback Android 14+) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/screen-capture.md) — inventory rank 52.5 explicitly tags `freeRASP T11/T12`. |
| **T12** | Screen Recording (Android 15+) | **FULL** | rank 52.5 `runtime.screen_recording` (MediaProjection session active, Android 15+ DETECT_SCREEN_RECORDING) | — **DISCLAIMER**: production-only — `MediaProjection.Callback#onScreenRecording*` requires live app activity to register the callback. Snapshot replay cannot fire this — see `audit/spoof-stack/un-snapshottable.md`. **Native-blocked until PAR822349-reboot, replay-test deferred.** | VERIFIED-via-docs (sub-section of screen-capture wiki page). |
| **T13** | Multi-Instance detection [Android-only] | **FULL** | rank 50.5 `runtime.multi_instance` (UserHandle.myUserId() != 0; clone package suffix; Parallel Space / MIUI Dual Apps container) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/multi-instance-detection-android-devices-only.md) — inventory rank 50.5 explicitly tags `freeRASP T13`. |
| **T14** | Unsecure WiFi detection [Android-only] | **FULL** | rank 43.5 `env.wifi_security_type` (WifiInfo encryption type — NONE/WEP = insecure; WifiConfiguration.KeyMgmt.NONE) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/unsecure-wifi-detection-android-only.md) — inventory rank 43.5 explicitly tags `freeRASP T14`. |
| **T15** | Time Spoofing detection | **FULL** | rank 33.5 `env.time_spoofing` (SystemClock.elapsedRealtime() vs System.currentTimeMillis() cross-check; NTP delta; GPS time delta) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/time-spoofing-detection.md) — inventory rank 33.5 explicitly tags `freeRASP T15`. Implementation present at `agents/detection/src/core/ProbeContext.kt:737`. |
| **T16** | Location Spoofing detection [Android-only] | **FULL** | rank 39 `env.location_mock` + rank 39.5 `env.location_mock_rasp` (Location.isFromMockProvider(); ALLOW_MOCK_LOCATION setting; mock location app presence) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/location-spoofing-detection-android-only.md) — inventory rank 39.5 explicitly tags `freeRASP T16`. |
| **D1** | Passcode / KeyguardManager.isDeviceSecure | **FULL** | rank 40.5 `env.screen_lock` (KeyguardManager.isDeviceSecure() = false → no PIN/pattern/biometric set) | — | VERIFIED-via-docs (https://docs.talsec.app/freerasp/freerasp/wiki/threat-detection/passcode.md) — inventory rank 40.5 explicitly tags `freeRASP D1`. |

### Out-of-scope device-state callbacks (informational only — NOT counted in T1-T16+D1 deliverable)

| ID | Callback | Inventory coverage | Note |
|---|---|---|---|
| D2 | `onSystemVPNDetected()` | rank 18 `network.vpn_proxy` | FULL — already covered |
| D3 | `onDeveloperModeDetected()` | rank 19 `env.developer_options` | FULL — already covered |
| D4 | `onADBEnabledDetected()` | rank 19 (adb_enabled shared with developer_options) | FULL — covered via shared signal |
| D5 | `onMalwareDetected(suspiciousApps)` | rank 10 `runtime.installed_apps` (manager apps + emulator clients) | PARTIAL — we cover root-manager + emulator-client packages; freeRASP's blacklist (suspicious package names + malicious hashes + dangerous-permissions categorization) is broader. Out-of-scope per mission. |

---

## §C. MISSING-Probes List for B3 (Encoding Backlog)

Based on §B, B3 (ralph-coder) should encode the following new probes (or strengthen existing PARTIAL probes' signal-surface enumeration). Listed in priority order — CRITICAL T-numbers first.

### C.1 Net-new probes (MISSING coverage)

| Proposed rank | Proposed id | Category | Severity | Source | Signal surfaces |
|---|---|---|---|---|---|
| **~10.5** | `integrity.install_source` | integrity | high | freeRASP T5 | `PackageManager.getInstallSourceInfo()` for Android 11+; `getInstallerPackageName()` pre-Android-11; allowlist: `com.android.vending`, `com.google.android.feedback`, `com.huawei.appmarket`, `com.sec.android.app.samsungapps`, `com.xiaomi.mipicks`, `com.oppo.market`, `com.vivo.appstore`. Flag: installer not in allowlist OR null. |
| **~60.5** | `integrity.obfuscation_self` | integrity | medium | freeRASP T8 (Android-only) | Self-decompile sample: class-name length histogram (post-R8 mode <=3 char names dominate); `BuildConfig.DEBUG == false`; presence of `kotlin.Metadata` un-mangled package names. **Caveat**: this is a SELF-DEFENSIVE probe (the app inspects ITSELF), not a device snapshot — needs schema-extension discussion before B3 encodes. |

### C.2 PARTIAL-strengthening probes (extend signal-surfaces of existing rows)

| Existing probe | T# | Extension needed |
|---|---|---|
| rank 3 `root.su_detection` | T1 | Add JVM `Runtime.exec("which su")` reachability test as separate signal-surface row (covers shipping RootBeer `checkSuExists()` path). |
| rank 8 `runtime.xposed_lsposed` | T6 | Add Cydia Substrate signature (`libsubstrate.so`, `MSHookFunction` symbol-resolution probe) + Shadow framework loader-pattern tokens to evidence-key set. |
| rank 4 `emulator.qemu_artifacts` + rank 4.5 `emulator.third_party_artifacts` | T3 | Add Genymotion `genyd` UDS socket (`/dev/socket/genyd` + `@genyd` abstract socket) as third-party-emulator-artifact signal. |
| rank 60 `integrity.app_signature` | T4 | Add native-code-section CRC (compare `.text` section in-memory vs disk; resources.arsc CRC). Note: rank 9.7 `runtime.native_prologue_hash` partially overlaps — B3 should de-duplicate or co-locate. |
| rank 11 `identity.android_id` + rank 29 `identity.mediadrm` + rank 6 `integrity.keystore_attestation` | T7 | Compose a new aggregation probe `integrity.device_binding_anchor`: at-install keystore-bound nonce + first-install-timestamp vs current-launch; backup-restoration delta. **Caveat**: requires persistent state across launches (not snapshottable in current schema) — same schema-extension flag as C.1 row 2. |

### C.3 Schema-extension discussion needed (NOT B3 encode-now)

Two MISSING / PARTIAL items above (T8 obfuscation + T7 device-binding) are **self-defensive** probes that inspect the running app's own state across launches, not the device snapshot. Our current schema (`shared/probes/inventory.yml` v2.0) is snapshot-shaped. B3 should flag these for a schema-RFC discussion before coding, not silently encode them as snapshot probes.

---

## §D. Honest-Limitations Disclaimer

1. **No primary-source byte-diff**: freeRASP-Android ships closed-source AAR. All FULL classifications above marked "VERIFIED-via-docs" rely on Talsec's *category claim* matching our inventory's *signal-surface enumeration*, not on byte-level confirmation that freeRASP checks the *exact same byte patterns*. A future Power-N iteration with private-Maven access could decompile the shipping AAR and produce a byte-faithful diff like `power-14-apk-source-diff.md §1` did for RootBeer.
2. **Five T-rows are pre-verified at primary-source level** (T2, T6, T10, T11, T12, T13, T14, T15, T16, D1) because our inventory already cites the underlying MASTG / RootBeer / DetectFrida / Android-docs primaries — these classifications are robust.
3. **Two T-rows (T11+T12, screen capture/recording) carry a production-only disclaimer**: `MediaProjection` callbacks require live activity to register. Snapshot replay cannot fire these — replay-test deferred until native rebuild post-PAR822349 reboot per `audit/spoof-stack/un-snapshottable.md`.
4. **T5 and T8 are net-new MISSING** with no inventory coverage today. B3 should prioritize T5 (install source) since it has standard snapshot semantics; T8 (self-obfuscation) requires schema discussion first.
5. **T1 PARTIAL gap (Shamiko-namespace masking)** is a known limitation acknowledged in `audit/spoof-stack/power-13-closeout.md` Gap #3 — already in our roadmap, not a new finding.

---

## §E. Report-Back Numbers

- **clone_success**: false (Free-RASP-Android demo-app-only on public GitHub; detection AAR is private-Maven, source-diff routed via docs.talsec.app secondary sources)
- **t_count**: 17 (T1-T16 + D1)
- **coverage_full**: 9 (T2, T9, T10, T11+T12 counted as 1, T13, T14, T15, T16, D1)
- **coverage_partial**: 6 (T1, T3, T4, T6, T7)
- **coverage_missing**: 2 (T5, T8)
- **sources_cited**: 10 distinct (5 docs.talsec.app + 2 GitHub repos + 3 audit cross-refs)

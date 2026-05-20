# Real-World Detector Heuristic Inventory — Power-13 Validation

**Date**: 2026-05-20
**Author**: researcher (team `power-13-real-world-validation`)
**Mission**: Map heuristics from the Top-5 open-source Android detector-app families to our 73-rank probe inventory (`shared/probes/inventory.yml`). Identify FULL / PARTIAL / MISSING coverage to feed Power-13 task #2.

**Ground truth sources** (verified during research, May 2026):
- RootBeer — https://github.com/scottyab/rootbeer (Const.java constants verbatim)
- RootBeerFresh — https://github.com/KimChangYoun/rootbeerFresh
- DetectFrida — https://github.com/darvincisec/DetectFrida (`native-lib.c` thread + prologue technique)
- strazzere/anti-emulator — `FindEmulator.java` qemu pipe + phone-number constants
- mofneko/EmulatorDetector + CalebFenton/AndroidEmulatorDetect — build.prop + telephony patterns
- freeRASP (Talsec) — https://github.com/talsec/Free-RASP-Android, threat detection wiki (T1..T16 + D1)
- Riru-MomoHider — https://github.com/canyie/Riru-MomoHider (Momo target surface)
- HuskyDG — `huskydg.github.io/blog/detect_magisk_xposed` (mount namespace + init service randomization)
- Play Integrity API verdicts — https://developer.android.com/google/play/integrity/verdicts (2025 fields)

**Inventory snapshot referenced**: `shared/probes/inventory.yml` schemaVersion 2.0, 73 ranks (60 baseline + 11 A17 + rank 66 TikTok + rank 71 PI signals).

---

## Detector 1 — RootBeer / RootBeerFresh

Filesystem + package-list + system-prop scanner. Static, single-threaded, easy to bypass with Magisk DenyList + repackaging, but still the most-copied root-detection codebase in Android. RootBeerFresh adds Magisk-UDS and stat-checks.

| Heuristic | What it reads | Our covering rank(s) | Coverage Status | Gap notes |
|---|---|---|---|---|
| `su` binary in 13 canonical paths (`/system/bin/su`, `/sbin/su`, `/su/bin/su`, `/data/local/xbin/su`, `/cache/`, `/system_ext/bin/`, etc.) | direct `fileExists()` | rank 3 `root.su_detection` (`SuDetectionProbe.SU_BINARY_PATHS`) | FULL | Our list has 12/13 paths. Missing: `/system_ext/bin/su` (Android 12+ system_ext partition). NON-CRITICAL gap but add for parity. |
| Magisk filesystem artifacts (`/sbin/.magisk`, `/data/adb/magisk`, `/cache/magisk.log`) | direct `fileExists()` | rank 3 (`SuDetectionProbe.MAGISK_ARTIFACT_PATHS`) | FULL | Already covered (4 paths). |
| `busybox` binary in su paths | direct `fileExists()` | — | MISSING | RootBeer's `checkForBusyBoxBinary()` not represented as a separate rank. Currently sub-summed into rank 3 only for the paths shared with `su`. Low severity (busybox alone is not root). Recommend NOT adding — covered by mount + selinux + su signals. |
| Root manager packages (`com.topjohnwu.magisk`, `eu.chainfire.supersu`, `com.koushikdutta.superuser`, `com.noshufou.android.su[.elite]`, `com.thirdparty.superuser`, `com.yellowes.su`, `com.kingroot.kinguser`, `com.kingo.root`, `com.smedialink.oneclickroot`, `com.zhiqupk.root.global`, `com.alephzain.framaroot`) | `PackageManager.getPackageInfo` | rank 3 (`SUPERUSER_PACKAGES`) + rank 10 `runtime.installed_apps` | PARTIAL | Rank 3 has only 4 packages (`com.topjohnwu.magisk`, `eu.chainfire.supersu`, `com.koushikdutta.superuser`, `com.thirdparty.superuser`). Missing 8 RootBeer constants. CRITICAL gap for parity testing; HIGH-priority list expansion. |
| Dangerous apps (Lucky Patcher, Xposed installer, etc. — 32+ packages) | PackageManager | rank 10 `runtime.installed_apps` | PARTIAL | Need to audit `InstalledAppsProbe`'s package list vs RootBeer's `knownDangerousAppsPackages`. Verify all 32 are present. |
| Root-cloaking apps (`com.devadvance.rootcloak[plus]`, `de.robv.android.xposed.installer`, `com.saurik.substrate`, `com.zachspong.temprootremovejb`, `com.amphoras.hidemyroot[adfree]`, `com.formyhm.hiderootPremium[.hideroot]`) | PackageManager | rank 8 `runtime.xposed_lsposed` (for Xposed mgr only) + rank 10 | PARTIAL | Xposed mgr covered. Other cloakers (rootcloak, hidemyroot) need verification in `InstalledAppsProbe`. Recommend adding cloaker list explicitly as evidence-key namespace. |
| Test-keys build (`ro.build.tags=test-keys`) | `Build.TAGS` | rank 7 `buildprop.tags_and_type` | FULL | `TagsAndTypeProbe` reads `Build.TAGS` + `Build.TYPE` directly. |
| Dangerous props (`ro.debuggable=1`, `ro.secure=0`) | `__system_property_get` | rank 13 `env.bootloader` (partial — only vbmeta) + rank 19 `env.developer_options` | PARTIAL | `ro.debuggable` / `ro.secure` are NOT individually scanned. Rank 13 reads `ro.boot.vbmeta.device_state` only. CRITICAL gap — RootBeer's most-quoted check. Recommend adding a `buildprop.dangerous_props` probe or extending rank 7 to include `ro.debuggable` and `ro.secure`. |
| RW mount points (`/system`, `/system/bin`, `/system/sbin`, `/system/xbin`, `/vendor/bin`, `/sbin`, `/etc` should not be rw) | `mount` parsing | — | MISSING | No rank reads `/proc/self/mountinfo` for system-partition rw status. Modern systemless root doesn't trigger this, but signature detector apps DO check. Recommend adding `root.system_rw_mount` rank (~14.5). HIGH severity for parity but LOW real-world spoofability concern. |
| `which su` shell exec | `Runtime.exec("which su")` | rank 3 (path scan covers same surface) | PARTIAL | Path scan is more reliable than exec, so functional equivalence. No gap. |
| Native root check (RootBeer's `librootbeer.so` calls `access()` on su paths via JNI) | native libc `access(2)` | rank 3 (JVM-side equivalent) | FULL | We mirror the same paths from JVM. RootBeer's native variant has the same coverage surface. |
| SELinux enforcing/permissive | `/sys/fs/selinux/enforce` | rank 14 `root.selinux` | FULL | `SeLinuxProbe` reads exactly this. |
| **RootBeerFresh additions**: Magisk Unix Domain Socket name probing | UDS `/dev/socket/<magisk_random>` enumeration | — | MISSING | RootBeerFresh enumerates `/proc/net/unix` for magisk-prefixed sockets. CRITICAL parity gap. Recommend adding rank `~3.5 root.magisk_uds`. |
| **RootBeerFresh additions**: file `stat` mode-bit checks on `/system/bin/*` to detect chmod tampering | `stat(2)` mode bits | — | MISSING | Not covered. LOW priority (uncommon detector pattern). |

---

## Detector 2 — Momo / MagiskHidePropsConfig (Magisk + Xposed detection)

Closed-source Chinese detector app, considered the "strongest" Magisk detector. Targets are reverse-engineered from MomoHider's whitelist of what it has to hide. Augmented with HuskyDG's blog enumeration.

| Heuristic | What it reads | Our covering rank(s) | Coverage Status | Gap notes |
|---|---|---|---|---|
| `init.svc.<random>` for randomized Magisk service names | `__system_property_foreach` enumerating all `init.svc.*` properties | — | MISSING | We do not enumerate `init.svc.*`. Magisk injects 3 services into init.rc at boot with randomized names. Detector compares service set across boots. CRITICAL gap and UNCOUNTERED by RootBeer pattern matching. Recommend new rank `~3.7 runtime.init_svc_enumeration`. |
| Mount namespace mismatch (zygote namespace differs from app's because of Magisk's `mount --bind` magic) | `/proc/self/mountinfo` vs `/proc/1/mountinfo` diff | — | MISSING | CRITICAL gap. HuskyDG documents this as the #1 Momo signal. Magisk-hide's mount-namespace technique is detectable by comparing mountinfo across PIDs. Recommend rank `~3.8 root.mount_ns_mismatch`. |
| OverlayFS detection (`/proc/mounts` lists `overlay` over `/system`) | `/proc/mounts` regex | — | MISSING | Modern Magisk uses overlayfs on Android 11+. Detector sees `overlay` filesystem on `/system`. Recommend rank `~14.7 root.overlayfs_present`. |
| Magisk-specific zygote ptrace traces (`/proc/self/status TracerPid != 0`) | `/proc/self/status` | rank 8.5 `runtime.debugger_tracerpid` | FULL | `DebuggerTracerPidProbe` covers exact surface. |
| Zygisk traces in `/proc/self/maps` (`libzygisk.so`, `libnative_bridge.so` tampering) | `/proc/self/maps` | rank 8 `runtime.xposed_lsposed` (zygisk-narrow scope) + rank 9.0 | PARTIAL | Rank 8 scans `liblspd`, `libriru_lsposed`, `libriru_edxposed` but EXCLUDES generic `libzygisk` because it fires on benign zygisk modules. This is the Power-12 calibrated trade-off. NEEDS REVIEW: Momo specifically scans for raw `libzygisk` and any module under `/data/adb/modules/`. Recommend adding a separate evidence row for generic zygisk presence in rank 8 (or a new rank). |
| `/data/adb/*` module directory enumeration | `readdir(/data/adb/modules)` | — | MISSING | Not covered. Detector sees module count > 0 even with DenyList. Recommend rank `~3.9 root.magisk_module_dir`. |
| `getprop` invariant violations (props that shouldn't change after first boot, e.g. `ro.boot.boottime`) | `__system_property_get` | rank 1 `buildprop.fingerprint` (partial) | PARTIAL | Rank 1 reads `ro.build.fingerprint` etc but does not do invariant-cross-boot comparison. Out of scope for offline replay; this is a stateful invariant. Acceptable gap. |
| `ro.boot.verifiedbootstate=orange/red` | `__system_property_get` | rank 13 `env.bootloader` | FULL | `BootloaderProbe` reads vbmeta + verifiedbootstate. |
| Magisk MagiskHide enabled state | `/sbin/.magisk/config` | — | MISSING | Magisk DenyList state is queryable via the magisk binary's IPC. Detector apps don't usually probe this directly (they probe the symptoms). Acceptable gap. |
| sepolicy / `/sys/fs/selinux/policy` divergence from stock | hash of policy file | — | MISSING | Advanced — only research detectors do this. LOW priority. |
| `MagiskHidePropsConfig` module side-effects (mismatched fingerprint vs other props) | cross-prop consistency | rank 9 `buildprop.model_brand_manufacturer` | PARTIAL | Rank 9 covers model/brand/manufacturer consistency. Does NOT include vendor fingerprint vs system fingerprint consistency (a key MHPC bypass signal). Recommend extending rank 9 evidence-key set OR adding rank `~9.5 buildprop.fingerprint_cross_partition`. |

---

## Detector 3 — Frida-Detection (DetectFrida + AndroidNativeEmu patterns)

Native C code that scans for Frida instrumentation. The Power-12 closure already added ranks 9.0 / 9.7 / 9.8 covering most of this surface. Coverage is the strongest of all 5 detector families.

| Heuristic | What it reads | Our covering rank(s) | Coverage Status | Gap notes |
|---|---|---|---|---|
| `/proc/self/maps` for `frida-agent`, `frida-gadget`, `libfrida-gadget`, `gum`, `linjector` | `fopen("/proc/self/maps")` + strstr | rank 9.0 `runtime.frida_memory_maps` (FRIDA_LIBRARY_TOKENS) | FULL | All 5 tokens covered verbatim in `FridaMemoryMapsProbe.FRIDA_LIBRARY_TOKENS`. |
| `/proc/self/task/<tid>/comm` for thread names `gum-js-loop`, `gmain`, `gdbus` | `readdir(/proc/self/task) + read(comm)` | rank 9.0 (GUM_THREAD_NAMES) | FULL | All 3 thread names covered. |
| TCP ports 27042 (frida-server primary) and 27043 (persistent mode) | `/proc/net/tcp` | rank 9.0 (FRIDA_PORTS) | FULL | Both ports covered. |
| `/proc/<pid>/fd/*` symlinks for `linjector` pipes | readlink each fd | rank 9.0 (token-based) | PARTIAL | Rank 9.0 matches `linjector` via maps token but not via fd-symlink path. Functionally equivalent because Frida agent leaves both signals; recommend documenting this in `FridaMemoryMapsProbe` KDoc, no new rank needed. |
| Native function prologue hash divergence (first 16-32 bytes of `libc.so` / `libart.so` symbol prologues, in-memory vs on-disk) | `dlsym` + `mmap` + memcmp to disk ELF | rank 9.7 `runtime.native_prologue_hash` | FULL (declarative variant) | Power-12 confirms this is un-snapshottable; the JVM probe is declarative. Score 0 on synthetic snapshots is the correct honest behavior. |
| GOT/PLT entry comparison (`.got` / `.plt` runtime values vs disk ELF) | parse ELF + read .got at runtime | rank 9.8 `integrity.prologue_got_hooks` | FULL (declarative variant) | Same declarative-only status as 9.7. |
| `rwxp` segments in `/proc/self/maps` (write+exec = hooked .text) | `/proc/self/maps` parse permissions | rank 9.8 (declarative) | PARTIAL | Rank 9.8 declarative variant assumes the production wrapper reports rwxp segments. Production-runtime gap documented in `un-snapshottable.md`. Acceptable. |
| `ptrace` self-attach test (Frida ptraces the target) | `ptrace(PTRACE_TRACEME, ...)` | rank 8.5 `runtime.debugger_tracerpid` (read side) | PARTIAL | We read TracerPid but do not actively ptrace ourselves. Production hooks spec covers this (see `production-hooks-spec.md §P-12`). Acceptable for offline replay. |
| Anti-debug `kill(0, 0)` + signal handler checks | signal SIGTRAP | — | MISSING | Not commonly used by detector apps in 2024+. LOW priority. |
| Memory-region scan for "Frida" / "Server" strings in heap | walk all rwxp regions, grep | — | MISSING | Heavyweight, only research detectors. LOW priority. |
| Recent Frida agent versions (v16+) randomize thread names | thread name fuzzy match | rank 9.0 (exact match only) | PARTIAL | This is a moving target. v16 of Frida randomizes thread names; our 3 exact strings will miss those. KNOWN limitation, documented in `FridaMemoryMapsProbe` (the FP-class section). The lib-token and port checks remain dispositive. Acceptable. |

---

## Detector 4 — Play Integrity API (and legacy SafetyNet ctsProfileMatch / basicIntegrity)

SafetyNet was retired January 2025. Play Integrity API is the canonical Google attestation. This detector class is the highest-stakes because it is BACKED BY HARDWARE attestation (StrongBox) and CANNOT be bypassed by JVM-side spoofing alone.

| Heuristic | What it reads | Our covering rank(s) | Coverage Status | Gap notes |
|---|---|---|---|---|
| `deviceIntegrity.deviceRecognitionVerdict` contains `MEETS_DEVICE_INTEGRITY` (genuine certified Android, locked bootloader) | Play Services IPC | rank 2 `integrity.play_integrity` + rank 71 `integrity.play_integrity_signals` | FULL | `PlayIntegrityProbe` (offline) + `PlayIntegrityLiveProbe` (online) cover both prediction-from-buildprop AND actual API call. |
| `MEETS_BASIC_INTEGRITY` (passes basic checks but not certified) | Play Services IPC | rank 2 + rank 71 | FULL | Same. |
| `MEETS_STRONG_INTEGRITY` (recent security update, hardware-backed) | Play Services IPC + StrongBox attestation | rank 2 + rank 6 `integrity.keystore_attestation` | FULL | Rank 6 `KeystoreAttestationProbe` covers the TEE/StrongBox attestation chain side. |
| `MEETS_VIRTUAL_INTEGRITY` (Play Games on PC emulator) | Play Services IPC | rank 2 | PARTIAL | We classify any virtual integrity as suspicious, but this verdict is LEGITIMATE for some flows (Play Games on PC). For our cloud-phone target, virtual integrity = bad. Acceptable framing. |
| Empty `deviceRecognitionVerdict` array (compromised device) | Play Services IPC | rank 2 | FULL | Treated as worst case. |
| `appIntegrity.appRecognitionVerdict = UNRECOGNIZED_VERSION` (sideloaded / repackaged) | Play Services IPC | rank 60 `integrity.app_signature` | PARTIAL | `AppSignatureProbe` covers signature tamper at the local level. The Play side adds Google's view of "is this APK known to Play". The online-only API call is needed; covered indirectly via rank 71. |
| `accountDetails.appLicensingVerdict = UNLICENSED` (no Play purchase entitlement) | Play Services IPC | — | MISSING | We do not query licensing verdict. This is a CONSUMER-GATING signal, not a device-integrity signal. LOW priority — not in scope for detector validation. Document as out-of-scope. |
| `environmentDetails.appAccessRiskVerdict` (overlay / capture / control apps installed) | Play Services IPC | rank 50 `runtime.services_processes` (partial) + rank 51.5 `runtime.automation_tools` | PARTIAL | Rank 51.5 covers UIAutomator + accessibility. Overlay detection not covered. Recommend extending rank 50 / 51.5 to include the `KNOWN_OVERLAYS` / `KNOWN_CAPTURING` / `KNOWN_CONTROLLING` categorization. MEDIUM priority. |
| `environmentDetails.playProtectVerdict` (Play Protect off, malware detected) | Play Services IPC | — | MISSING | Not covered. The probe would need Play Services. Recommend documenting as out-of-scope for offline replay; covered partially by online rank 71 prediction. |
| `recentDeviceActivity.deviceActivityLevel` (LEVEL_1..LEVEL_4, abuse signal) | Play Services IPC | — | MISSING | Behavior over time — not measurable from a single snapshot. Acceptable gap. |
| `deviceRecall` (beta) — per-device custom bits | Play Services IPC | — | MISSING | Developer-specific, beta. Out-of-scope. |
| Legacy `ctsProfileMatch=false` (SafetyNet) | SafetyNet API (retired Jan 2025) | rank 2 (subsumed) + rank 71 | FULL | Legacy API gone, but the prediction-from-buildprop signal is preserved in rank 71 for legacy compatibility. |
| Legacy `basicIntegrity=false` | SafetyNet API (retired) | rank 2 + rank 71 | FULL | Same. |
| Build property surface used to PREDICT Play Integrity verdict offline (ro.build.fingerprint, model, brand, ro.build.tags, ro.build.type, ro.boot.verifiedbootstate, etc.) | `__system_property_get` aggregation | rank 71 `integrity.play_integrity_signals` (explicit) + ranks 1, 7, 9, 13 | FULL | The aggregator at rank 71 is the prediction surface. Confirmed in `PlayIntegrityProbe.kt`. |

---

## Detector 5 — Native / Emulator Detector (strazzere/anti-emulator + EmulatorDetector + AndroidEmulatorDetect)

Composite of three popular open-source emulator-detection libraries. Targets QEMU pipes, build.prop, telephony-stack tells, and CPU/proc artifacts.

| Heuristic | What it reads | Our covering rank(s) | Coverage Status | Gap notes |
|---|---|---|---|---|
| `/dev/socket/qemud`, `/dev/qemu_pipe` | direct file access | rank 4 `emulator.qemu_artifacts` | FULL | `QemuArtifactsProbe` covers QEMU socket paths. |
| `/system/lib/libc_malloc_debug_qemu.so`, `/sys/qemu_trace`, `/system/bin/qemu-props` | direct file access | rank 4 | FULL | Confirmed in QemuArtifactsProbe via grep results. |
| Genymotion: `/dev/socket/genyd`, `/dev/socket/baseband_genyd` | direct file access | rank 4 | FULL | Same — Genymotion variants covered. |
| `/proc/cpuinfo` contains "goldfish" / "ranchu" / "QEMU" | file read + regex | rank 30 `emulator.proc_version` (partial) + dedicated cpuinfo probe via `CpuInfoProbe.kt` | FULL | We have `CpuInfoProbe` at the kernel-layer ProbeContext + rank 30 `ProcVersionProbe`. |
| `/proc/tty/drivers` contains "goldfish" | file read | — | MISSING | We don't read `/proc/tty/drivers`. Recommend adding to rank 30 evidence keys OR a sub-evidence row of rank 4. LOW severity — derivative of QEMU props. |
| `Build.HARDWARE = goldfish / ranchu / vbox86` | `Build.HARDWARE` | rank 4 + rank 28 `buildprop.board_hardware` | FULL | Rank 28 (`BoardHardwareProbe`) covers exact strings. |
| `Build.FINGERPRINT` starts with "generic" / contains "sdk_google" | `Build.FINGERPRINT` | rank 1 `buildprop.fingerprint` | FULL | `BuildFingerprintProbe` covers. |
| `Build.MANUFACTURER = Genymotion / unknown / iToolsAVM` | `Build.MANUFACTURER` | rank 9 `buildprop.model_brand_manufacturer` | FULL | `ModelBrandManufacturerProbe` covers brand consistency including Genymotion. |
| `Build.MODEL` contains "google_sdk" / "Emulator" / "Android SDK built for" / "droid4x" | `Build.MODEL` | rank 9 | FULL | Same. |
| `Build.PRODUCT` = "sdk_x86_64" / "vbox86p" / "google_sdk" | `Build.PRODUCT` | rank 9 + rank 28 | FULL | Same. |
| `Build.BRAND = generic / android / generic_arm64 / generic_x86 / generic_x86_64` | `Build.BRAND` | rank 9 | FULL | Same. |
| `Build.BOARD = unknown` | `Build.BOARD` | rank 28 | FULL | Covered. |
| `Build.TAGS = test-keys` | `Build.TAGS` | rank 7 | FULL | Covered (also flagged by RootBeer). |
| `Build.HOST = "android-build"` | `Build.HOST` | rank 1 (subsumed) | PARTIAL | Not an explicit evidence key. Recommend adding as sub-evidence row on rank 1. LOW priority. |
| `Build.ID = "FRF91"` | `Build.ID` | — | MISSING | Specific to early AOSP emulator IDs. LOW relevance in 2026 (rarely seen). DO NOT ADD — outdated signal. |
| `Build.USER = "android-build"` | `Build.USER` | rank 1 (subsumed) | PARTIAL | Same as Build.HOST. LOW priority. |
| `Build.BOOTLOADER` = "unknown" | `Build.BOOTLOADER` | rank 13 `env.bootloader` | FULL | `BootloaderProbe` covers. |
| `Build.SERIAL` = null / "unknown" | `Build.SERIAL` | rank 12 `identity.imei_serial` | FULL | `ImeiSerialProbe` covers. |
| Known emulator phone numbers (`15555215554`..`15555215584` 16-entry block) | `TelephonyManager.getLine1Number()` | rank 22 `identity.carrier_mccmnc` (partial) | PARTIAL | Rank 22 reads MCC/MNC but NOT line1 number against the known-emulator list. Recommend extending `CarrierMccMncProbe` OR adding a sub-evidence row to rank 21 `identity.sim_iccid`. HIGH priority — these 16 numbers are dispositive. |
| Known emulator device ID `"000000000000000"`, `"e21833235b6eef10"`, `"012345678912345"` | `TelephonyManager.getDeviceId()` | rank 12 (zero check only) | PARTIAL | Rank 12 reads IMEI/Serial but does not specifically match `e21833235b6eef10` and `012345678912345`. Recommend extending evidence key set. MEDIUM priority. |
| Known emulator IMSI `310260000000000` | `TelephonyManager.getSubscriberId()` | rank 22 | PARTIAL | Rank 22 reads MCC=310 MNC=260 (T-Mobile) but does not specifically match the 15-zero-suffix IMSI pattern. Recommend evidence-key extension. MEDIUM priority. |
| Network operator name = "Android" | `TelephonyManager.getNetworkOperatorName()` | rank 22 | PARTIAL | Need to verify `CarrierMccMncProbe` reads operator name and not just MCC/MNC code. |
| `init.svc.qemud`, `init.svc.qemu-props`, `qemu.hw.mainkeys`, `qemu.sf.fake_camera`, `qemu.sf.lcd_density` | `__system_property_get` | rank 4 + rank 28 | FULL | `QemuArtifactsProbe` enumerates qemu.* props. |
| `ro.kernel.qemu`, `ro.kernel.android.qemud`, `ro.kernel.qemu.gles` | `__system_property_get` | rank 4 | FULL | Covered. |
| GPU `GL_RENDERER` = "Android Emulator OpenGL ES Translator" / "SwiftShader" / "llvmpipe" | `GLES20.glGetString(GL_RENDERER)` | rank 26 `emulator.gpu_renderer` | FULL | `GpuRendererProbe` covers. |
| `Build.SUPPORTED_ABIS` contains x86 on ARM-claimed device | `Build.SUPPORTED_ABIS` | rank 27 `emulator.cpu_abi` | FULL | `CpuAbiProbe` covers. |
| eth0 interface present (emulator only) | `NetworkInterface.getNetworkInterfaces()` | rank 25 `network.network_type` + rank 18 `network.vpn_proxy` | PARTIAL | Neither rank specifically checks for `eth0`. Recommend evidence-key extension on rank 25. LOW priority (Redroid has eth0 too, so dispositive only as a corroborating signal). |
| Voicemail number "15552175049" | `TelephonyManager.getVoiceMailNumber()` | — | MISSING | Highly specific to old SDK emulator. LOW priority. Add as a sub-evidence row to rank 21 only if completeness matters. |
| Subscriber ID ending in "0000000000" pattern | regex | rank 12 | PARTIAL | Not specifically pattern-matched. LOW priority. |
| `ueventd.android_x86.rc`, `x86.prop`, `init.ttVM_x86.rc`, `fstab.andy`, `init.nox.rc` (3rd-party emulators: Nox, Andy, MEmu, BlueStacks, MicroVirt, Droid4x) | `fileExists()` | — | MISSING | NONE of these third-party emulator init files are checked. Our QemuArtifactsProbe is QEMU/Genymotion-only. CRITICAL gap — Nox and BlueStacks are the MOST common cloud-phone targets in the wild. Recommend NEW rank `~4.5 emulator.third_party_artifacts` or extending rank 4 with these paths. **HIGH-priority parity gap.** |
| Third-party emulator packages (BlueStacks, Nox, VPhone, iTools, Haima, Kaopu, MicroVirt, Genymotion launcher) | PackageManager | rank 10 | PARTIAL | Need to audit `InstalledAppsProbe` for these 8 emulator packages. |
| Sensors absent (no accelerometer / gyroscope) | `SensorManager.getDefaultSensor()` | rank 24 `sensors.accelerometer_gyro` + ranks 42/43/44/45 | FULL | All sensors covered. |
| Camera count == 0 | `Camera.getNumberOfCameras()` | rank 53 `env.camera_info` | FULL | `CameraInfoProbe` covers. |
| Bluetooth adapter absent | `BluetoothAdapter.getDefaultAdapter() == null` | rank 49 `env.bluetooth_state` | FULL | Covered. |

---

## Aristotle First-Principles Check

**Assumption challenged**: "100% inventory coverage = real-world parity". FALSE.

**Irreducible truth**: Power-12's 73/73 ranks cover the *historical synthetic* signal surface (the ranks were derived from inventory.yml itself). Real detector apps probe a DIFFERENT and PARTIALLY OVERLAPPING surface — driven by 2024-2026 detector evolution (overlayfs, randomized init.svc, mount-namespace diff, third-party emulators).

**Aristotelian move**: Task #3 should prioritize the 6 CRITICAL gaps. They are the difference between "covers our own inventory" and "covers what real detectors actually probe". The MEDIUM list is parity polish; the LOW list is acceptable noise.

**Honest framing**: We did NOT lie in Power-12. We DID complete the inventory we wrote. But the inventory was incomplete relative to the adversarial surface. This is the "lass dich nicht verarschen" check the owner demanded — and it lands.

---

## Source links (verified May 2026)

- [scottyab/rootbeer](https://github.com/scottyab/rootbeer)
- [KimChangYoun/rootbeerFresh](https://github.com/KimChangYoun/rootbeerFresh)
- [darvincisec/DetectFrida](https://github.com/darvincisec/DetectFrida)
- [DetectFrida native-lib.c](https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c)
- [strazzere/anti-emulator FindEmulator.java](https://github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java)
- [mofneko/EmulatorDetector](https://github.com/mofneko/EmulatorDetector)
- [CalebFenton/AndroidEmulatorDetect](https://github.com/CalebFenton/AndroidEmulatorDetect)
- [talsec/Free-RASP-Android](https://github.com/talsec/Free-RASP-Android)
- [Free-RASP threat detection wiki](https://github.com/talsec/Free-RASP-Community/wiki/Threat-detection)
- [canyie/Riru-MomoHider](https://github.com/canyie/Riru-MomoHider)
- [Detect Magisk and Xposed (HuskyDG)](https://huskydg.github.io/blog/detect_magisk_xposed)
- [Play Integrity Overview](https://developer.android.com/google/play/integrity/overview)
- [Play Integrity Verdicts](https://developer.android.com/google/play/integrity/verdicts)
- [Android Developers Blog 2025-10: stronger threat detection](https://android-developers.googleblog.com/2025/10/stronger-threat-detection-simpler.html)

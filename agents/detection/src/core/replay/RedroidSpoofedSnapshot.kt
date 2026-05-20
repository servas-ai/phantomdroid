// agents/detection/src/core/replay/RedroidSpoofedSnapshot.kt
//
// SPOOFED ReDroid 12 snapshot — Iteration 1 (2026-05-20).
//
// This is the offensive counterpart of `RedroidV12Snapshot.SNAPSHOT`. It
// starts from the same captured surface but applies targeted mutations
// that mask the 8 highest-priority probes flagged by spoof-reviewer's
// Iteration-1 baseline. Each mutation is inline-documented with:
//
//   (1) the probe rank + id it masks
//   (2) the ground-truth (ReDroid) value vs. the spoofed value
//   (3) the production-side hook that the real SpoofStack on PAR822349
//       would have to install to achieve the same effect against a live
//       container (LSPosed module / Magisk resetprop / mount-mask /
//       kernel-module).
//
// Iteration-1 goal (per builder brief): drop >= 6 of the 8 high-priority
// probes to 0.0. Remaining "null/empty answer" probes (android_id,
// imei_serial, sim_iccid, input_method, debugger_tracerpid,
// location_mock_rasp, selinux) are NOT addressed here — they are
// Iteration-2 work because they require fabricating realistic positive
// values, not just removing emulator markers.
//
// IMPORTANT: This file is constructed as a FRESH DeviceSnapshot literal
// (not a `.copy(...)` of `RedroidV12Snapshot.SNAPSHOT`). The team-lead
// brief explicitly requested this form so each mutation is locally
// visible at the call site and the diff vs. the ground-truth ReDroid
// capture is easy to audit.
//
// Probe-irrelevant fields the reviewer's baseline confirmed are already
// clean on ReDroid (rank-4 qemu_artifacts, sensor-* family, network/UI
// suppliers defaulting to null) are NOT mutated — the goal is to
// demonstrate the minimum SpoofStack surface, not to copy
// `Pixel7CleanSnapshot` wholesale.

package com.detectorlab.core.replay

object RedroidSpoofedSnapshot {

    /**
     * Spoofed ReDroid 12 surface. Same capture origin as
     * `RedroidV12Snapshot.SNAPSHOT`, with 8 targeted mutations applied
     * to mask the highest-leverage probes from Iteration-1 baseline.
     *
     * Label intentionally carries the `-spoofed-v1` suffix so the test
     * harness reports which side of the decision boundary it's running
     * against.
     */
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "redroid-12-amd64-2026-05-20-spoofed-v1",
        capturedAt = "2026-05-20T00:00:00Z",
        sdkInt = 31, // Android 12 → API 31 — UNCHANGED (no probe scores on SDK alone)
        systemProperties = mapOf(
            // Mask rank-1 (buildprop.fingerprint): was
            //   "redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/eng.frank...userdebug/test-keys"
            // now a coherent Pixel 7 panther/Android-12 fingerprint. Must
            // remain internally consistent with the model_brand_manufacturer
            // mutation below (brand/device/name slots match) and with the
            // tags_and_type mutation (`:user/release-keys` suffix).
            // Real-SpoofStack hook: Magisk `resetprop -n ro.build.fingerprint <value>`
            // run from a `service.d/00-spoof.sh` boot script (these are read-only
            // props so `resetprop -n` is required to bypass the ro.* lock).
            "ro.build.fingerprint" to
                "google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys",

            // Mask rank-1 secondary (buildprop.fingerprint also reads
            // display.id substring): was
            //   "redroid_x86_64_only-userdebug 12 SP1A.210812.016.C2 eng.frank... test-keys"
            // now a Pixel-shape display.id. Strips both "redroid" and "test-keys".
            // Real-SpoofStack hook: Magisk `resetprop -n ro.build.display.id`.
            "ro.build.display.id" to "SP1A.210812.016.C2",

            // Mask rank-7 (buildprop.tags_and_type) first half: was
            //   "test-keys" → "release-keys". This alone drops the rank-7 probe
            // out of SCORE_TEST_KEYS_AND_USERDEBUG (1.00) into the 0.0 branch.
            // Real-SpoofStack hook: Magisk `resetprop -n ro.build.tags release-keys`.
            "ro.build.tags" to "release-keys",

            // Mask rank-7 (buildprop.tags_and_type) second half: was
            //   "userdebug" → "user". Combined with tags=release-keys this puts
            // the probe firmly into the production-build branch (score 0.0).
            // Real-SpoofStack hook: Magisk `resetprop -n ro.build.type user`.
            "ro.build.type" to "user",

            "ro.build.version.release" to "12",     // UNCHANGED — SDK 31 is plausible for stock devices
            "ro.build.version.sdk" to "31",         // UNCHANGED

            // Mask rank-9 (buildprop.model_brand_manufacturer): all five fields
            // below contained the load-bearing substring "redroid". Replacing
            // with the Pixel 7 panther triple drops the substring scan AND
            // satisfies the probe's `isBrandManufacturerAligned` check
            // (lowercase-equal "google"/"google"). `device` and `name` are
            // codename `panther` to match the fingerprint above.
            // Real-SpoofStack hook: Magisk resetprop for each of the five props
            // (single boot-script edit; these are the canonical "build prop"
            // resetprop targets every published SpoofStack tutorial covers).
            "ro.product.brand" to "google",                       // was "redroid"
            "ro.product.model" to "Pixel 7",                      // was "redroid12_x86_64_only"
            "ro.product.manufacturer" to "Google",                // was "redroid"
            "ro.product.device" to "panther",                     // was "redroid12_x86_64_only"
            "ro.product.name" to "panther",                       // was "redroid12_x86_64_only"

            // rank-4 (qemu_artifacts) — UNCHANGED. Baseline confirmed this
            // probe already scores 0.0 on ReDroid (no qemu/* props, no
            // /dev/qemu_* nodes). Preserved as empty-string to match the
            // ground-truth ReDroid capture; no spoofing required.
            "ro.kernel.qemu" to "",
            "ro.kernel.qemu.gles" to "",

            // Mask rank-28 (buildprop.board_hardware): was "redroid" — the single
            // load-bearing string that matches the BoardHardware probe's
            // EMU_KEYWORDS allowlist. Replaced with the Pixel 7 SoC codename
            // `panther`. NOTE this also serves the broader Aristotelian move
            // identified in the baseline (one substring fix cascades across
            // multiple probes). board/platform also set to plausible Pixel-shape
            // values so they don't read empty (an empty `ro.product.board` on
            // an otherwise-Pixel device would itself be anomalous).
            // Real-SpoofStack hook: Magisk `resetprop -n ro.hardware panther`
            // (this is a kernel-set property — `-n` is required to overwrite).
            // For the board/platform pair, a service.d resetprop is sufficient.
            "ro.hardware" to "panther",                           // was "redroid"
            "ro.product.board" to "panther",                      // was ""
            "ro.board.platform" to "gs201",                       // was "" — Google Tensor G2 platform

            // Mask rank-27 (emulator.cpu_abi): was DUAL-ARCH
            //   "x86_64,arm64-v8a" → pure ARM64 stack. This eliminates the
            // Houdini bridge fingerprint that the cpu_abi probe scores against.
            // Real-SpoofStack hook: CANNOT be fully spoofed via resetprop alone
            // — `ro.product.cpu.abi*` are consulted by the Zygote at fork time
            // for ELF-loader selection. A real SpoofStack needs (a) Magisk
            // resetprop for the property surface AND (b) an LSPosed module that
            // hooks `android.os.Build.SUPPORTED_*ABIS` reflection reads to
            // return the spoofed values to user-space callers. Property-level
            // spoofing alone is detectable by an app that reads Build.* fields
            // directly instead of via getprop. Documented here as a "needs
            // both Magisk + LSPosed" surface.
            "ro.product.cpu.abi" to "arm64-v8a",                  // was "x86_64"
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi", // was "x86_64,arm64-v8a"
            "ro.product.cpu.abilist32" to "armeabi-v7a,armeabi",  // was ""
            "ro.product.cpu.abilist64" to "arm64-v8a",            // was "x86_64,arm64-v8a"

            // Mask rank-13 (env.bootloader): was a dev-build leak —
            //   ro.debuggable=1, AVB fields all empty. Flipped to a
            // factory-clean production state (vbmeta=green, flash.locked=1,
            // debuggable=0, oem_unlock=0). This drops the
            // SCORE_DEV_BUILD_LEAK branch (0.85) and lands the probe in the
            // production-locked 0.0 branch.
            // Side-effect: also drops the rank-71 PlayIntegrity basicFail
            // signal from 0.95 toward <=0.30 (per reviewer's note).
            // Real-SpoofStack hook: Magisk resetprop for ro.debuggable and the
            // boot/vbmeta props. ro.debuggable=0 is critical and is one of the
            // first targets in every published Magisk DenyList + PIF
            // (Pixel-Integrity-Fingerprint) module.
            "ro.boot.vbmeta.device_state" to "green",             // was ""
            "ro.boot.verifiedbootstate" to "green",               // was ""
            "ro.boot.flash.locked" to "1",                        // was ""
            "ro.oem_unlock_supported" to "0",                     // was not-set
            "ro.secure" to "1",                                   // UNCHANGED — already "1"
            "ro.debuggable" to "0",                               // was "1" (the load-bearing flag)

            // Mask rank-14 (root.selinux): was empty pair ("","") which fires
            // SCORE_EMPTY_PROPS=0.30. Flipped to canonical enforcing state.
            // SeLinuxProbe scores on four signal sources — these are the two
            // property-side signals; the kernel-virtual file pair lives in
            // `readableFiles` / `existingFiles` below to drive the other two.
            // Real-SpoofStack hook: Magisk `resetprop -n ro.boot.selinux enforcing`
            // + `resetprop -n ro.build.selinux 1`. Both are normally bootloader-
            // and build-set, so `-n` is required to overwrite the read-only props.
            "ro.boot.selinux" to "enforcing",                     // was ""
            "ro.build.selinux" to "1",                            // was ""

            // Mask rank-12 (identity.imei_serial) ro.serialno half: was not set,
            // which combined with telephony[SERIAL]=null produced SERIAL_PATTERN_UNKNOWN
            // and (with imei=null) → SCORE_BOTH_STRIPPED=0.70. Set to a
            // Pixel-shape 10-char alphanum serial that's distinct from
            // SERIAL_STOCK_PLACEHOLDER ("0123456789ABCDEF"), not all-zero/all-f,
            // and doesn't start with "emulator". Must match telephony[SERIAL]
            // below — ImeiSerialProbe prefers tmSerial but falls back to roSerial,
            // and the rank-12 KDoc cross-checks the two surfaces.
            // Real-SpoofStack hook: Magisk `resetprop -n ro.serialno HQ7Y0V3RJL`.
            // The serial is normally written once by bootloader and read-only
            // thereafter, so `-n` is required to overwrite. Production SpoofStack
            // also needs LSPosed hook on `android.os.Build.getSerial()` (API 26+)
            // which since Android 8 routes through `TelephonyManager.getSerial()`
            // requiring READ_PRIVILEGED_PHONE_STATE — most apps now read it via
            // reflection on Build.SERIAL (deprecated but readable). Documented as
            // dual-surface fix: property AND Build.getSerial() Xposed hook.
            "ro.serialno" to "HQ7Y0V3RJL",                        // was not-set
        ),
        existingFiles = setOf(
            // Mask rank-3 (root.su_detection): was {"/system/bin/su"} — the
            // single SU-binary leak in ReDroid. Removed entirely. The probe's
            // SU_PATHS allowlist scan finds nothing → score 0.0.
            // Real-SpoofStack hook: Magisk DenyList — for the target app's UID,
            // the /system/bin/su path is unmounted at process-start via the
            // Magisk mount-namespace isolation. The file still exists on the
            // backing filesystem; it's just not visible to the detected app.

            // Mask rank-14 (root.selinux) Signal 4: SeLinuxProbe checks
            // `fileExists("/sys/fs/selinux/policy")` — absence is suspicious
            // on any modern Android. Adding the path satisfies the
            // "selinux_kernel_support=true" evidence. The probe does NOT score
            // on this signal directly (only on enforce/boot/build property
            // signals); presence is "consistent with enforcing" and contributes
            // to confidence-source count.
            // Real-SpoofStack hook: this path exists on every real Android
            // device with SELinux enabled — no spoofing required on a true-
            // Android host. The ReDroid container's mount namespace already
            // exposes /sys/fs/selinux from the host kernel, so production
            // PAR822349 will see this path natively as long as the host kernel
            // has CONFIG_SECURITY_SELINUX=y (Ubuntu 18.04 default).
            "/sys/fs/selinux/policy",
        ),
        readableFiles = mapOf(
            // Mask rank-30 (emulator.proc_version): was
            //   "Linux version 4.15.0-213-generic (buildd@lcy02-amd64-079) (gcc 7.5.0 ...)"
            // — leaked the Ubuntu launchpad build host. Replaced with the
            // canonical Pixel 7 / Android 13 GKI kernel banner (taken from
            // Pixel7CleanSnapshot.kt's verified value). This banner has:
            //   - "android13-gki" kernel name tag (Android-shape, not Ubuntu)
            //   - "kleaf@build-host" build user (Google's Bazel-based kernel
            //     build system, not buildd@launchpad)
            //   - clang compiler (Android dropped gcc in 2016 — gcc in the
            //     banner is itself a probe signal)
            // Real-SpoofStack hook: bind-mount mask — replace /proc/version
            // with a tmpfs file containing the spoofed banner. Magisk's
            // `magic mount` does this transparently from a module's
            // system/proc/version override file. /proc/* nodes cannot be
            // resetprop'd (they aren't system properties) so a mount-level
            // override is the only way.
            "/proc/version" to
                "Linux version 5.10.149-android13-4-00014-g8d83edf3bdc4-ab9576845 " +
                "(kleaf@build-host) (Android (8508608, based on r450784e) " +
                "clang version 14.0.6 (https://android.googlesource.com/" +
                "toolchain/llvm-project 4c603efb0cca074e9238af8b4106c30add4418f6), " +
                "LLD 14.0.6) #1 SMP PREEMPT Tue Jan 24 18:08:19 UTC 2023",

            // Mask rank-80 (runtime.debugger_tracerpid): was unset, which
            // produced PATTERN_STATUS_UNREADABLE=0.50. Replaced with a minimal
            // but parser-valid /proc/self/status body containing `TracerPid:\t0`.
            // DebuggerTracerPidProbe.parseTracerPid looks for any line starting
            // with "TracerPid:" and parses the integer after. Empty samples and
            // non-zero values trigger different rules; "0" lands in PATTERN_CLEAN.
            // The other lines (Name, State, Uid) are realistic-shape padding so
            // the body resembles a real /proc/self/status enough that a future
            // probe additions reading sibling fields (e.g. Uid for sandbox-uid
            // detection) won't trip.
            // Real-SpoofStack hook: cannot resetprop a /proc node — these are
            // kernel-virtual files. Two options:
            //   (a) Magisk magic-mount bind-mount a static stub file over
            //       /proc/self/status (BREAKS — /proc/self is a magic symlink
            //       resolved per-process; a static bind-mount would shadow EVERY
            //       process's status to the same canned content, which itself is
            //       detectable). NOT viable.
            //   (b) LSPosed module that hooks `java.io.FileInputStream` constructor
            //       and `libc.open()`/`fopen()` JNI bridges to redirect reads of
            //       /proc/self/status to a per-process synthesized body with
            //       TracerPid=0 even when Frida is attached. Real production
            //       SpoofStack ships this as part of the anti-Frida package
            //       (e.g. NeoZygisk's `frida-detector-counter`).
            // Option (b) is the production-grade fix; option (a) noted as the
            // why-not for the simpler bind-mount approach.
            "/proc/self/status" to
                "Name:\tcom.example.app\n" +
                "Umask:\t0077\n" +
                "State:\tR (running)\n" +
                "TracerPid:\t0\n" +
                "Uid:\t10123\t10123\t10123\t10123\n" +
                "Gid:\t10123\t10123\t10123\t10123\n",

            // Mask rank-14 (root.selinux) Signal 1: SeLinuxProbe reads
            // /sys/fs/selinux/enforce — "0" is Permissive (score 1.0), "1" is
            // Enforcing (clean). Set to "1" matching the property pair above.
            // Real-SpoofStack hook: bind-mount-mask — Magisk magic-mount a
            // tmpfs file containing "1" over /sys/fs/selinux/enforce. The
            // kernel-virtual file is owned by selinuxfs and can't be written
            // directly; the mount overlay is the standard approach (and is
            // what Magisk's MagiskHide / DenyList already does for this exact
            // surface when SELinux mode has been toggled to Permissive for
            // module operation).
            "/sys/fs/selinux/enforce" to "1",

            // Mask rank-31 (identity.bluetooth_mac) sysfs surface: BluetoothMacProbe
            // reads /sys/class/bluetooth/hci0/address as one of two MAC sources.
            // ReDroid containers have no Bluetooth HAL → path is absent → sysfsMac=null.
            // Combined with the supplier-side fix below (bluetoothMac field), this
            // gives the probe TWO consistent surfaces and lands it in PATTERN_CLEAN
            // with CONFIDENCE_FULL (both surfaces readable).
            //
            // MAC value: 3c:5a:b4:8d:f1:27
            //   - OUI 3C:5A:B4 = Google Inc. (IEEE-registered)
            //   - First-byte 0x3C = 00111100: bit 0x02 = 0 → NOT locally-administered
            //   - Not in OUI_QEMU/OUI_VBOX/OUI_VMWARE/OUI_HYPERV/OUI_XEN
            //   - Not MAC_ZERO, not MAC_ANDROID6_PRIVACY_DEFAULT (02:00:00:00:00:00),
            //     not a TEST_FIXTURE pattern
            //   - Matches the `bluetoothMac` field below (both surfaces must agree
            //     for CONFIDENCE_FULL; mismatch lands in privacyDefaultViaSysfs branch
            //     only if BOTH equal 02:00:00:00:00:00, which neither does)
            // Real-SpoofStack hook: Magisk magic-mount a synthetic
            // /sys/class/bluetooth/hci0/address file containing the spoofed MAC.
            // The sysfs path is kernel-owned; mount-overlay is the only write path.
            // Combined with the LSPosed BluetoothAdapter.getAddress() hook below
            // (for the supplier side) this gives a coherent dual-surface fix.
            "/sys/class/bluetooth/hci0/address" to "3c:5a:b4:8d:f1:27",
        ),
        // Mask rank-11 (identity.android_id), rank-58 (ui.input_method),
        // rank-82 (env.location_mock_rasp): all three read from settingsSecure
        // and previously returned null → respective "empty/unavailable"
        // patterns. Populated with realistic Pixel-shape values.
        //
        // Real-SpoofStack hook: Settings.Secure is backed by SQLite in
        // `/data/system/users/0/settings_secure.xml`. Three approaches:
        //   (a) Direct SQL write at install-time via Magisk service.d boot
        //       script (`settings put secure <key> <value>`). Persistent
        //       across reboots; visible to ALL apps reading the namespace.
        //       Simplest but app-broad — any app on the device that reads
        //       Settings.Secure sees the spoofed values.
        //   (b) LSPosed module hooking `android.provider.Settings$Secure.
        //       getString(ContentResolver, String)` to return spoofed values
        //       per-app (scoped to the detected target). Surgical but adds a
        //       Java-layer reflection trampoline.
        //   (c) Hybrid: persistent SQL set for android_id (which is supposed
        //       to be stable per-device) + LSPosed for the per-call settings
        //       (default_input_method, location.*) that may differ per
        //       evaluation.
        // Production SpoofStack typically uses (c). The android_id value
        // below is a 16-hex-char value with Shannon entropy 3.875 bits/char
        // (well above the 2.5 threshold) and not in SEQUENTIAL_DEBUG_VALUES
        // / not the factory default 9774d56d682e549c.
        settingsSecure = mapOf(
            // rank-11 (identity.android_id):
            //   was null → SCORE_EMPTY_OR_NON_HEX=0.85
            //   now valid 16-hex with entropy 3.875 bits/char → SCORE_NORMAL=0.0
            "android_id" to "a1b2c3d4e5f60718",

            // rank-58 (ui.input_method):
            //   was null → SCORE_EMPTY_OR_NULL_IME=0.70
            //   now canonical Gboard component (no "emulator"/"test"/"mock"
            //   substring) → SCORE_CLEAN=0.0
            "default_input_method" to
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",

            // rank-82 (env.location_mock_rasp):
            //   was null → SCORE_NO_LOCATION_AVAILABLE=0.50
            //   `location.is_from_mock_provider="0"` is the wrapper-synthesized
            //   sentinel (NOT a real Android Settings.Secure key — production
            //   wrapper writes the result of Location.isFromMockProvider/
            //   isMock() to this synthetic key per the rank-82 KDoc).
            //   Value "0" → isFromMock=false → falls through to PATTERN_CLEAN.
            "location.is_from_mock_provider" to "0",
            //   Same probe ALSO reads SETTING_ALLOW_MOCK_LOCATION (legacy
            //   API <23 surface) — set to "0" for realistic shape. Doesn't
            //   strictly need to be present because sdkInt=31>=23 gates the
            //   rule, but a real Pixel device reports this key as "0" not
            //   null, so populating it improves snapshot realism.
            "mock_location" to "0",
            "mock_location_app" to "",

            // Snapshot-realism (NOT score-changing on current probe panel):
            // AccessibilityServicesProbe already lands at SCORE_CLEAN with
            // a null `enabled_accessibility_services` (no suspicious-service
            // substring + serviceCount=0 < MANY_SERVICES_THRESHOLD). Setting
            // to explicit empty-string matches what a clean Pixel reports
            // and closes the doc gap so a future probe addition reading
            // this key sees the realistic answer.
            // Real-SpoofStack hook: `settings put secure enabled_accessibility_services ""`
            // via a Magisk service.d boot script — or just leave it alone
            // (Android boots with this empty on a clean install).
            "enabled_accessibility_services" to "",

            // Snapshot-realism. LocationMockProbe (rank 39) and several
            // location-aware probes consult `location_providers_allowed` as
            // part of the broader location-availability surface. Not
            // directly scored by rank-82 (already closed via
            // `location.is_from_mock_provider`), but a real Pixel 7 always
            // populates this key with the user's enabled-provider list.
            // "gps,network" is the canonical Pixel default after first-run
            // location-services opt-in.
            // Real-SpoofStack hook: `settings put secure location_providers_allowed gps,network`.
            "location_providers_allowed" to "gps,network",
        ),

        // Snapshot-realism additions to `settingsGlobal`. None of these
        // change scores on the current probe panel — rank-19
        // (DeveloperOptionsProbe) is already closed via `ro.debuggable=0`,
        // and with empty settingsGlobal all the Global-keyed predicates
        // (devOn / adbOn / verifierOff) evaluate false anyway. Population
        // here is purely about matching what a real factory-clean Pixel 7
        // reports, so future probes that consult this surface (or the
        // reviewer's static-trace) see a coherent device shape rather than
        // a "data class default" gap.
        // Real-SpoofStack hook for all entries: `settings put global <key> <value>`
        // via Magisk service.d boot script. Settings.Global is backed by
        // `/data/system/users/0/settings_global.xml`; same write-path
        // discipline as Settings.Secure.
        settingsGlobal = mapOf(
            "development_settings_enabled" to "0",       // Developer Options locked
            "adb_enabled" to "0",                        // USB ADB disabled
            "adb_wifi_enabled" to "0",                   // Wireless ADB disabled
            "package_verifier_enable" to "1",            // Play Protect package verification on
            "http_proxy" to ":0",                        // Android sentinel for "no proxy"
            "private_dns_mode" to "off",                 // DoT off (also valid: "opportunistic")
        ),
        // Mask rank-12 (identity.imei_serial) and rank-21 (identity.sim_iccid).
        // Both read from TelephonyManager surfaces previously empty/null on
        // ReDroid (no SIM, no telephony stack).
        //
        // Real-SpoofStack hook: TelephonyManager is backed by `phone` system
        // service (Telephony/RIL stack). Containerized ReDroid has no real
        // RIL backend, so the fields can ONLY be spoofed at the Java layer.
        // The standard production-grade approach:
        //   (a) LSPosed module hooking the entire TelephonyManager class,
        //       intercepting getImei()/getDeviceId()/getSerial()/
        //       getSimSerialNumber()/getNetworkOperator() etc. to return
        //       fabricated values consistent with a real T-Mobile US Pixel 7.
        //   (b) Build.getSerial() / Build.SERIAL also need hooking (see
        //       ro.serialno above) to keep the cross-surface story consistent.
        //   (c) Optionally a fake RIL HAL (`libril.so` shim) that returns
        //       realistic AT-command-like responses if any app bypasses the
        //       Java layer and reads /dev/radio directly. Most apps don't.
        // Option (a)+(b) is the standard SpoofStack approach (NeoZygisk,
        // TrickyStore module ship this configuration).
        //
        // Value selection:
        //   - IMEI: 353112109876546 — 15 digits, Luhn-valid (mod-10 sum=60),
        //     not in KNOWN_EMULATOR_IMEIS, TAC prefix `35311` is a TAC range
        //     historically allocated to Samsung but Type Approval is just a
        //     prefix range, not a strict OEM lock; using a non-Pixel TAC
        //     avoids the public TAC database flag for the (small) chance a
        //     future probe cross-checks TAC against ro.product.brand.
        //   - SERIAL: HQ7Y0V3RJL — 10-char alphanum, Pixel-shape (real Pixel 7
        //     serial format is 2-letter+8-alphanum or similar). NOT
        //     "0123456789ABCDEF", NOT "unknown", NOT starting with "EMULATOR".
        //     Must match systemProperties["ro.serialno"] above.
        //   - SIM_SERIAL (ICCID): 8901260123456789011 — 19 digits, Luhn-valid
        //     (computed; reviewer's suggested value was Luhn-INVALID and was
        //     corrected here), 89-prefix (ITU-T E.118 telecom card),
        //     issuer code 8901260... = T-Mobile US, NOT in KNOWN_EMULATOR_ICCIDS,
        //     NOT monotonic-ascending, NOT all-same-digit.
        //   - OPERATOR_NAME + MCC_MNC: realistic T-Mobile US tuple. Not
        //     directly scored by rank-12/21 but kept consistent for any
        //     future MCC/MNC cross-validation probe (rank-22 NetworkOperator).
        telephony = mapOf(
            "IMEI" to "353112109876546",          // 15-digit, Luhn-valid (verified)
            "SERIAL" to "HQ7Y0V3RJL",             // matches ro.serialno
            "SIM_SERIAL" to "8901260123456789011", // 19-digit, 89-prefix, Luhn-valid (verified)
            "OPERATOR_NAME" to "T-Mobile",
            "MCC_MNC" to "310260",                // T-Mobile US
        ),
        installedPackages = setOf(
            // UNCHANGED — ReDroid ships a minimal AOSP set; no rank-10 emulator-
            // marker packages (com.bluestacks.*, com.vphone.*, etc.) are
            // present in the ground truth, so no masking needed here.
            "android",
            "com.android.systemui",
            "com.android.settings",
        ),
        // Mask rank-42/43/44/45 (sensors.proximity/light/magnetometer/barometer)
        // + flip rank-24 (sensors.accelerometer_gyro) from PATTERN_NO_SIGNAL
        // to PATTERN_CLEAN. Iter-1 regression: spoofing the model to
        // `Pixel 7` turned `NetworkTypeProbe.isPhoneClassModel(model) == true`,
        // which activated every "missing-on-phone" rule against the previously
        // empty sensor list. Populating with the 6 canonical Pixel-7 sensors
        // turns hasProximity/hasLight/hasMagnetometer/hasBarometer all true,
        // dropping each probe into PATTERN_CLEAN.
        //
        // Real-SpoofStack hook: this surface is NOT spoofable via
        // Magisk-resetprop alone. The SensorManager backing on a real Android
        // host is the kernel-side iio / sensor-hub HAL. To make a containerized
        // ReDroid report a Pixel-7 sensor inventory, the SpoofStack needs:
        //   (a) An LSPosed module that hooks android.hardware.SensorManager.
        //       getSensorList() to inject six fake Sensor objects (type +
        //       vendor "AOSP" + name) on every call, OR
        //   (b) A user-space sensor-HAL shim (sensors@2.x.so) that fabricates
        //       a 6-sensor list AND serves canned-but-jittered sample streams
        //       (avoid the constant-stub branch in rank 24/42/43/44/45 which
        //       fires on N>=2 identical samples).
        // Option (a) is lower effort but probe-side-only — apps that hit the
        // HAL directly (rare) bypass it. Option (b) is the production-grade
        // fix. Either way: one sensor surface, six probes closed.
        //
        // Sensor type constants come from `AccelerometerGyroProbe.companion`:
        //   TYPE_ACCELEROMETER   = 1   (rank 24 — required core sensor)
        //   TYPE_MAGNETIC_FIELD  = 2   (rank 44 — compass; missing-on-phone)
        //   TYPE_GYROSCOPE       = 4   (rank 24 — required core sensor)
        //   TYPE_LIGHT           = 5   (rank 43 — auto-brightness; missing-on-phone)
        //   TYPE_PRESSURE        = 6   (rank 45 — barometer; missing on flagship)
        //   TYPE_PROXIMITY       = 8   (rank 42 — earpiece; missing-on-phone)
        sensorTypes = setOf(1, 2, 4, 5, 6, 8),

        // Mask rank-31 (identity.bluetooth_mac) adapter-supplier surface.
        // BluetoothMacProbe reads from a constructor-injected supplier
        // (default `{ null }`), independently of any ProbeContext accessor.
        // Wiring: in tests, the probe is instantiated as
        //   BluetoothMacProbe(adapterMacSupplier = { ctx.snapshotBluetoothMac() })
        // which routes this value to the supplier (see
        // `SnapshotReplayContext.snapshotBluetoothMac()` accessor).
        //
        // Without this fix, the probe would land in PATTERN_NULL_ADAPTER_ON_PHONE
        // (score 0.85) because `nullAdapterOnPhone = !adapterSupplierReturned
        // && phoneClass`, and with model=Pixel 7 the phoneClass gate is true.
        //
        // Value matches the sysfs entry above for cross-surface coherence.
        // A real Pixel 7's BluetoothAdapter and sysfs disagree only on the
        // privacy-default (02:00:00:00:00:00) variant introduced in Android 6;
        // a real adapter address reads identically from both surfaces only
        // when the calling app holds LOCAL_MAC_ADDRESS permission (system app
        // privilege). For a non-system app reading the same surface, the
        // adapter returns 02:00:00:00:00:00 — which the probe handles via the
        // SCORE_PRIVACY_DEFAULT_BENIGN (0.50) branch if sysfs is unreadable,
        // or SCORE_PRIVACY_DEFAULT_VIA_SYSFS (0.85) if sysfs ALSO returns the
        // privacy default. Our spoof lands BOTH surfaces at the real MAC,
        // mimicking what a system-app or LSPosed-elevated caller sees.
        //
        // Real-SpoofStack hook: LSPosed module hooking
        // `android.bluetooth.BluetoothAdapter.getAddress()` to return the
        // spoofed MAC even for non-LOCAL_MAC_ADDRESS callers. Magisk
        // resetprop CANNOT fix this — the MAC is not a system property; it's
        // a runtime call into the bluetooth-service. Production-grade
        // SpoofStack ships this hook in the same package as the
        // SensorManager hook (see sensorTypes block above for the parallel
        // pattern). Optional belt-and-suspenders: a `/sys/class/bluetooth/
        // hci0/address` bind-mount-mask (see readableFiles above) covers
        // apps that bypass the framework and read sysfs directly.
        bluetoothMac = "3c:5a:b4:8d:f1:27",
    )
}

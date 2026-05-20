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

            // Mask rank-37 (network.dns_server) Signal 1: net.dns1..4 system
            // properties. Pre-Pie Android primary DNS surface — many real
            // devices still populate it for backward-compat readers. Empty
            // values produce DnsServerProbe.allDns == [] which fires
            // PATTERN_NO_DNS_CONFIGURED=0.50. T-Mobile US carrier DNS pair
            // (8.8.8.8 / 8.8.4.4 would be detected via the GoogleOnlyCellular
            // rule, so we use T-Mobile's actual public DNS:
            // 8.25.203.30 / 8.25.203.31 — Akamai-managed for T-Mobile NA).
            // Values land in PATTERN_CLEAN because they're non-emulator
            // subnets, not all-Google, not local-resolver.
            // Real-SpoofStack hook: Magisk `resetprop net.dns1 8.25.203.30`.
            // Production wrappers also need to override the
            // ConnectivityManager.getLinkProperties(network).dnsServers list
            // (covered by phase 4 supplier wiring) for full coverage.
            "net.dns1" to "8.25.203.30",                          // T-Mobile US primary public DNS
            "net.dns2" to "8.25.203.31",                          // T-Mobile US secondary public DNS

            // Mask rank-36 (env.language_country) build-vs-runtime layer.
            // LanguageCountryProbe reads ro.product.locale / .language / .region
            // as the build-time locale; if they're empty AND runtime locale
            // country is empty, the probe fires PATTERN_EMPTY_COUNTRY_BOTH_LAYERS
            // = 0.85. Even when the runtime side is closed by phase-3 ctx
            // routing, the build-side props must be populated for the probe's
            // confidence to reach CONFIDENCE_FULL=0.95 (otherwise stays at
            // CONFIDENCE_PARTIAL=0.60). Pixel-7-US-retail values; consistent
            // with the snapshot's localeLanguage/localeCountry fields below.
            // Real-SpoofStack hook: Magisk `resetprop -n ro.product.locale en-US`
            // (this is normally set once at first-boot via the device
            // setup-wizard; resetprop is required to overwrite the ro.* lock).
            "ro.product.locale" to "en-US",
            "ro.product.locale.language" to "en",
            "ro.product.locale.region" to "US",

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

            // Mask rank-6 (integrity.keystore_attestation): the declarative
            // inference probe scores on six surfaces (unlocked bootloader +
            // verity-off / Knox-warranty-tripped / abnormal bootmode / no
            // hardware keystore HAL / Houdini dual-arch / keymaster device
            // missing). The bootloader/Houdini surfaces are ALREADY closed by
            // the rank-13 and rank-27 fixes above (`ro.boot.flash.locked=1`,
            // pure ARM64 `ro.product.cpu.abilist`). The four remaining
            // surfaces are closed here. Without these, the spoofed snapshot
            // would land at 0.70 (no hardware_keystore + no /dev/keymaster
            // both fire) and the rank-6 probe would block the full-panel
            // CLEAN classification.
            //
            // Real-SpoofStack hook: keystore is the load-bearing surface for
            // hardware attestation. A real SpoofStack on PAR822349 needs:
            //   (a) Magisk `mknod /dev/keymaster c <major> <minor>` from a
            //       service.d boot script to create the keymaster device node
            //       at the path apps `stat()`. The node IS bindable by an
            //       Android keystore HAL — but the HAL itself doesn't exist
            //       in a containerized environment, so this is a "make the
            //       presence check pass" hook, NOT a "make hardware
            //       attestation work" hook.
            //   (b) LSPosed hook on `android.security.keystore.KeyStore.
            //       getAttestationChain()` that returns a CACHED real-device
            //       chain (captured offline from a real Pixel 7 with a
            //       throwaway key). The cached chain has a valid signature
            //       all the way to the Google attestation root, BUT the
            //       attestation-extension `applicationId` and the
            //       `attestationChallenge` are fixed to the cached values —
            //       a verifier that includes a server-side nonce in its
            //       challenge will reject this (the chain's challenge will
            //       not match). This is the canonical "TrickyStore" hack
            //       and it works against verifiers that don't bind the
            //       challenge server-side; it fails against verifiers that
            //       do. Documented here as a known-limited hook.
            //   (c) Magisk resetprop for `ro.hardware.keystore` to a
            //       plausible HAL name (e.g. "default" or "gs201" for Pixel
            //       7 shape). This satisfies the property-surface check
            //       without requiring an actual HAL.
            //   (d) Magisk resetprop for `ro.boot.veritymode=enforcing`,
            //       `ro.boot.warranty_bit=0`, `ro.boot.warranty=1`,
            //       `ro.bootmode=normal` — the production-clean defaults a
            //       real Pixel 7 reports.
            // Per `audit/spoof-stack/un-snapshottable.md`, the REAL
            // attestation surface (not the declarative L3 inference here) is
            // L0 — un-spoofable in the FOSS-tooling era of 2026. The
            // declarative inference here scores against the property-surface
            // markers that imply a failing attestation; the production
            // SpoofStack closes those markers, but a verifier that performs
            // the real KeyStore call still bypasses this entire mitigation.
            "ro.boot.veritymode" to "enforcing",                  // was not-set
            "ro.boot.warranty_bit" to "0",                        // was not-set
            "ro.boot.warranty" to "1",                            // was not-set
            "ro.bootmode" to "normal",                            // was not-set
            "ro.hardware.keystore" to "gs201",                    // was not-set — Pixel 7 Tensor-G2 HAL

            // Mask rank-2 (integrity.play_integrity): PlayIntegrityLiveProbe
            // scores against the broader Play Integrity API verdict surface
            // (vs. the rank-71 sister probe's narrower basicIntegrity-only
            // surface). The probe reads four properties — GMS version,
            // OEM provisioning client-id, gms-disabled flag, and the
            // rescue-disabled tell. A ReDroid container ships NO Google
            // Play Services at all, so an un-spoofed capture trips the
            // 0.95 NO_VERDICT tier; populating these properties together
            // with the GMS package in installedPackages closes the rule
            // cascade to land at CLEAN (score 0.0).
            //
            // Values:
            //   ro.com.google.gmsversion=243532033 — a recent stable GMS
            //     version code (24.35.32 release, late-2024). NOT "0"
            //     (which fires the GMS-downgrade 0.70 tier).
            //   ro.com.google.clientidbase=android-google-Pixel-7 — the
            //     provisioned variant that real OOBE-completed Pixels report.
            //     NOT bare "android-google" (the unprovisioned default that
            //     fires the 0.85 UNPROVISIONED_CLIENTID tier).
            //   ro.gms.disabled=0 — GMS active.
            //   persist.sys.disable_rescue=0 — rescue mode enabled (the
            //     factory default; setting it to "1" would fire 0.50).
            //
            // Real-SpoofStack hook for this rank-2 surface: a real spoof
            // requires the rank-2 verdict to be returned via a live Play
            // Integrity API call, which itself is L0 / un-spoofable in the
            // FOSS-tooling era of 2026. The declarative property-surface
            // here is L3 — closing it is necessary but NOT sufficient
            // against a verifier that actually invokes the API. Production
            // SpoofStack closes these property markers via:
            //   (a) Magisk resetprop `ro.com.google.gmsversion <version>`
            //       at boot (boot-script in `service.d/00-spoof.sh`).
            //   (b) Magisk resetprop `ro.com.google.clientidbase <versioned>`.
            //   (c) Installing a stub `com.google.android.gms` APK that
            //       fakes the package surface (Microg's playservices package
            //       does exactly this, but does NOT pass real attestation).
            // See `audit/spoof-stack/un-snapshottable.md` for the L0 vs L3
            // distinction.
            "ro.com.google.gmsversion" to "243532033",
            "ro.com.google.clientidbase" to "android-google-Pixel-7",
            "ro.gms.disabled" to "0",
            "persist.sys.disable_rescue" to "0",

            // Mask rank-57 (ui.touch_pressure) property surface: was empty
            // (no touchscreen HAL bound on ReDroid) → TouchPressureProbe lands
            // in PATTERN_NO_TOUCH_HAL (0.95) when combined with the missing
            // /dev/input/event* tree. Replaced with the canonical
            // `qcom_synaptics_dsx` IC name — a realistic Pixel-class
            // Synaptics-DSX driver IC name used across multiple Qualcomm-class
            // flagships (NOT in SYNTHETIC_TOUCHSCREEN_SUBSTRINGS so the
            // synthetic-HAL rule disarms; NOT empty so the NO_TOUCH_HAL rule
            // disarms). Combined with the /dev/input/event[0-3] existingFiles
            // additions below this drops the probe into PATTERN_CLEAN (0.0).
            //
            // Real SpoofStack: Magisk overlay /dev/input/event[0-4] via uinput,
            // plus resetprop. Specifically:
            //   (a) Magisk `resetprop -n ro.hardware.touchscreen qcom_synaptics_dsx`
            //       to fabricate the IC name visible to property readers.
            //       `-n` is required to overwrite the read-only kernel-set prop.
            //   (b) Magisk service.d boot script runs `mknod /dev/input/event0
            //       c 13 64` through `mknod /dev/input/event3 c 13 67` to
            //       create four evdev nodes. The character-device numbering
            //       (major 13 / minor 64-67) matches the Linux input subsystem's
            //       standard evdev range. Like the rank-6 /dev/keymaster mknod
            //       hook above, this satisfies the presence check without
            //       binding a real driver.
            //   (c) The un-snapshottable runtime surface (MotionEvent pressure
            //       FFT) requires a userland uinput-injector daemon that
            //       fabricates pressure samples with realistic finger-skin
            //       bandwidth (NOT the square-wave pulses a naive injector
            //       produces). Per audit/spoof-stack/un-snapshottable.md, the
            //       REAL rank-57 surface (NOT this declarative L3 inference)
            //       is L0 — un-spoofable against a verifier that performs
            //       FFT analysis on a recorded gesture. The declarative probe
            //       here scores against the static property + filesystem
            //       markers; the production SpoofStack closes those markers,
            //       but a verifier that records and FFT-analyzes a real touch
            //       gesture bypasses this mitigation entirely.
            "ro.hardware.touchscreen" to "qcom_synaptics_dsx",

            // Mask rank-54 (ui.audio_fingerprint — DECLARATIVE VARIANT):
            // ReDroid ground-truth reports `ro.hardware.audio=snd_card_dummy`
            // (the Linux ALSA dummy driver — the canonical software-only
            // audio HAL name). AudioFingerprintProbe.isSoftwareHal returns
            // TRUE for `snd_card_dummy` AND for any HAL name containing
            // `stub`/`dummy` substring (case-insensitive), firing
            // PATTERN_SOFTWARE_HAL (0.85). Replaced with `qcom_aud_codec_xxxx`,
            // a plausible Qualcomm audio codec name. The spoofed snapshot
            // already advertises `ro.board.platform=gs201` (Pixel 7 Tensor G2)
            // and a Tensor-G2 device's audio HAL is commonly reported as a
            // Qualcomm-derived codec name in `ro.hardware.audio`. The exact
            // substring `qcom_aud_codec_xxxx` intentionally does NOT contain
            // "stub" or "dummy" — those tokens are the load-bearing
            // isSoftwareHal() predicate, so any name lacking them lands the
            // probe cleanly in PATTERN_CLEAN (0.0).
            //
            // No `persist.audio.loopback` / `ro.audio.silent.in` /
            // `ro.config.audio_dock_uses_aplc` masking needed — ReDroid
            // ground-truth never sets these (the loopback/silent_in rules
            // only fire on explicit "1", and the dock_aplc rule only fires
            // on explicit "true"; unset → null → does not fire). The
            // PATTERN_NO_HAL_NO_DEVICE rule also disarms because the HAL
            // prop is now non-empty (it requires set-but-empty).
            //
            // Real-SpoofStack hook: Magisk `resetprop -n ro.hardware.audio
            // qcom_aud_codec_xxxx` (`-n` required to overwrite the kernel-set
            // read-only prop). Belt-and-suspenders: combine with the
            // `/dev/snd/controlC0` mknod below (also a Magisk service.d boot
            // script invoking `mknod /dev/snd/controlC0 c 116 0` with major-116
            // being the Linux ALSA control major device). The character-device
            // numbering is irrelevant for the declarative probe's presence
            // check — `stat /dev/snd/controlC0` only consults the dentry, not
            // the underlying driver — so the mknod satisfies the existence
            // surface without requiring a real audio driver to be bound.
            //
            // Per the rank-54 KDoc's "declarative limitation" section, the
            // REAL un-snapshottable rank-54 surface (WebView AudioContext
            // OscillatorNode → DynamicsCompressorNode → AnalyserNode FFT
            // fingerprint) is L0 — un-spoofable in the FOSS-tooling era of
            // 2026. The declarative inference here scores against the
            // property + device-node markers that imply the WebView would
            // render audio nonstandardly; the production SpoofStack closes
            // those markers, but a verifier that loads a JS context and
            // FFTs the AudioContext output bypasses this mitigation entirely.
            "ro.hardware.audio" to "qcom_aud_codec_xxxx",         // was "snd_card_dummy"

            // Mask rank-56 (ui.webgl_fingerprint — DECLARATIVE VARIANT):
            // ReDroid ground-truth has all GPU-identifying properties empty
            // — gpu.driver/vulkan/opengles.version/gralloc/gltransport — so
            // the WebGlFingerprintProbe lands in PATTERN_NO_GPU_HAL (0.70).
            // Spoofed values present a coherent Pixel-class Qualcomm Adreno
            // GPU stack:
            //   ro.gpu.driver = "qcom_adreno_750" — realistic Adreno-series
            //     vendor driver name (Adreno 7xx is the Pixel/flagship-class
            //     mobile GPU IP block sold to Qualcomm and Google). NOT
            //     literal "swiftshader" (which fires SWIFTSHADER 0.95);
            //     NOT empty (which fires NO_GPU_HAL 0.70 when paired with
            //     empty vulkan). Combined with vulkan="1.3.0" both halves
            //     of the no_gpu_hal predicate disarm.
            //   ro.hardware.gralloc = "qcom" — Qualcomm vendor gralloc HAL
            //     identifier. NOT "ranchu" (the goldfish/AVD GL backend
            //     marker that fires EMULATOR_GL 0.85).
            //   ro.hardware.vulkan = "1.3.0" — realistic Vulkan API version
            //     supported by Adreno 7xx-class GPUs since 2022. Non-empty
            //     disarms the no_gpu_hal rule's vulkan-side predicate.
            //   ro.opengles.version = "196610" — = 0x30002 = OpenGL ES 3.2
            //     packed as `(major << 16) | minor`. EXACTLY the boundary
            //     value the probe treats as PLAUSIBLE (strict-less-than
            //     semantics — 196610 itself does NOT fire old_gles 0.50).
            //     Real Pixel 7 and Samsung S22 retail also pin to this
            //     value; cross-snapshot consistent.
            //   ro.boot.qemu.gltransport (NOT in this map) — preserved as
            //     absent (= null at probe-accessor level), matching real
            //     Pixel ground truth where the AVD-only property is never
            //     set. Setting it to any non-empty value would fire the
            //     EMULATOR_GL rule.
            //   ro.kernel.qemu.gles UNCHANGED — already empty from the
            //     ground-truth ReDroid capture (rank-4 + rank-56 both
            //     consult this key; empty disarms both). See note at
            //     ro.kernel.qemu above for the rank-4 ground-truth.
            //
            // Real-SpoofStack hook: this surface is NOT spoofable by Magisk
            // resetprop alone. The REAL un-snapshottable rank-56 surface
            // is the WebGL shader fingerprint pulled at runtime through a
            // WebView GLES context — that L0 read returns the actual
            // host-GPU shader trace which the property cascade cannot
            // mask. Production-grade SpoofStack approaches for the
            // property-cascade side (necessary but NOT sufficient against
            // a verifier that runs the actual GL trace):
            //   (a) Magisk `resetprop -n ro.gpu.driver qcom_adreno_750`
            //       at boot. `-n` required to overwrite the kernel-set
            //       read-only prop.
            //   (b) Magisk `resetprop -n ro.hardware.gralloc qcom` and
            //       `resetprop -n ro.hardware.vulkan 1.3.0`.
            //   (c) Magisk `resetprop -n ro.opengles.version 196610`.
            //   (d) For the un-snapshottable L0 surface: an LSPosed module
            //       hooking `android.opengl.GLES20.glGetString()` and the
            //       WebView's `WebGLRenderingContext.getParameter` JS
            //       bridge to return canned vendor/renderer strings. This
            //       still does NOT pass a real shader-execution
            //       fingerprint (the GPU pipeline output itself), which
            //       requires a custom GLES implementation linked into the
            //       Android runtime — a research-grade undertaking not
            //       available in the FOSS-tooling era of 2026.
            // See `audit/spoof-stack/un-snapshottable.md` for the L0 vs L3
            // distinction (same framing as rank-2 / rank-6 / rank-54).
            "ro.gpu.driver" to "qcom_adreno_750",                 // was ""
            "ro.hardware.gralloc" to "qcom",                      // was ""
            "ro.hardware.vulkan" to "1.3.0",                      // was ""
            "ro.opengles.version" to "196610",                    // was "" — boundary value, plausible
        ),
        existingFiles = setOf(
            // Mask rank-3 (root.su_detection): was {"/system/bin/su"} — the
            // single SU-binary leak in ReDroid. Removed entirely. The probe's
            // SU_PATHS allowlist scan finds nothing → score 0.0.
            // Real-SpoofStack hook: Magisk DenyList — for the target app's UID,
            // the /system/bin/su path is unmounted at process-start via the
            // Magisk mount-namespace isolation. The file still exists on the
            // backing filesystem; it's just not visible to the detected app.

            // Mask rank-6 (integrity.keystore_attestation) Signal 6: the
            // declarative probe checks `ctx.fileExists("/dev/keymaster")` as
            // its lowest-tier (0.50) attestation-fail predictor — a real
            // hardware-keystore HAL binds to this device node, so its
            // absence implies no keymaster service is reachable.
            // Real-SpoofStack hook: Magisk service.d boot script runs
            //   mknod /dev/keymaster c 10 200
            // (the character-device numbering is irrelevant for a presence
            // check — `stat /dev/keymaster` only consults the dentry, not
            // the underlying driver) plus an `chmod 666 /dev/keymaster` so
            // the target app's UID can open the node without permission
            // gymnastics. This is a "make the existence check pass" hook;
            // the node has no driver bound behind it, so any actual ioctl()
            // call against the keymaster HAL would still fail. The
            // declarative probe at rank 6 only inspects existence, not
            // functionality — closing it via mknod is sufficient for THIS
            // probe but does NOT make hardware-backed attestation work
            // (see the L0 vs L3 note in the rank-6 KDoc and in the
            // ro.hardware.keystore comment block above).
            "/dev/keymaster",

            // Mask rank-57 (ui.touch_pressure) /dev/input/event* surface.
            // ReDroid containers have NO /dev/input/event* nodes (no evdev
            // backend bound). The declarative probe scores against the
            // count of present event devices: 0 → PATTERN_NO_TOUCH_HAL (0.95)
            // combined with empty `ro.hardware.touchscreen`, only event0 →
            // PATTERN_SINGLE_INPUT_DEVICE (0.70). Real Pixel-class phones
            // expose 3-5 input devices: event0=touchscreen, event1=keys,
            // event2=sensor-hub, event3=fingerprint, event4=stylus (when
            // present). We populate four to land cleanly in the >=3 range
            // (matches Pixel 7 panther canonical evdev inventory).
            //
            // Real SpoofStack: Magisk overlay /dev/input/event[0-4] via
            // uinput, plus resetprop. Specifically, a Magisk service.d
            // boot script runs `mknod /dev/input/event0 c 13 64` through
            // `mknod /dev/input/event3 c 13 67` to create four evdev
            // nodes. The major device number 13 is the Linux input
            // subsystem's standard evdev range; the minor numbers
            // (64-67) correspond to event0-event3 per the standard
            // /dev/input numbering. Like the rank-6 /dev/keymaster mknod
            // hook above, this satisfies the file-presence check without
            // binding a real driver.
            "/dev/input/event0",
            "/dev/input/event1",
            "/dev/input/event2",
            "/dev/input/event3",

            // Mask rank-54 (ui.audio_fingerprint — DECLARATIVE VARIANT)
            // /dev/snd/controlC0 surface. ReDroid containers have NO
            // `/dev/snd/*` nodes (the host Ubuntu kernel's ALSA tree is
            // not bound into the container's /dev namespace). The
            // declarative probe checks `ctx.fileExists("/dev/snd/controlC0")`
            // as one of two surfaces for the PATTERN_NO_HAL_NO_DEVICE
            // rule (which requires set-but-empty `ro.hardware.audio` AND
            // missing controlC0). The HAL prop fix above already disarms
            // that rule by making the property non-empty; this mknod adds
            // defense-in-depth so a future probe extension consulting the
            // device node directly (e.g. a future Linux-ALSA-introspection
            // probe via /proc/asound/cards) sees a coherent "audio HAL
            // exists" story.
            //
            // Real SpoofStack: Magisk service.d boot script runs
            //   mknod /dev/snd/controlC0 c 116 0
            // where major-116 is the Linux ALSA control major device.
            // The character-device numbering is irrelevant for the
            // declarative probe's presence check — `stat /dev/snd/controlC0`
            // only consults the dentry, not the underlying driver — so
            // the mknod satisfies the existence surface without requiring
            // a real audio driver to be bound. Same "make the existence
            // check pass" hook discipline as the rank-6 /dev/keymaster
            // and rank-57 /dev/input/event* nodes above.
            "/dev/snd/controlC0",

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

            // Mask rank-51 (ui.system_fonts): SystemFontsProbe falls back to
            // ctx.fileExists("/system/fonts/<name>") when its filename-list
            // supplier returns null (the default). With zero hits AND no
            // defaultFamily supplier the probe lands in PATTERN_NO_OBSERVATION
            // = 0.50. Populating with the full WELL_KNOWN_FONT_NAMES set
            // (32 entries from SystemFontsProbe.companion) gives the probe
            // accessorObserved=true, notoColorEmojiPresent=true (NotoColorEmoji.ttf
            // is in the set), and the lowFontCount/lowRobotoCount rules don't
            // fire because they only activate when the supplier returns a
            // FULL filename list (which we leave null here — the
            // fileExists-fallback path explicitly does NOT trip the count
            // rules per the probe's source-comment at line 274-278).
            // Result: PATTERN_CLEAN, score 0.0, confidence CONFIDENCE_FULL=0.95.
            //
            // List MUST stay in sync with SystemFontsProbe.WELL_KNOWN_FONT_NAMES.
            // Real-SpoofStack hook: cannot resetprop the /system/fonts/ tree —
            // those are real files in /system. Two production approaches:
            //   (a) Bake the spoofed font tree into a Magisk module's
            //       system/fonts/ overlay (the module's `system/` path is
            //       magic-mounted over the real /system at boot). Persistent
            //       across reboots; visible to every app on the device.
            //       Simplest; matches the standard MagiskHide/Zygisk pattern.
            //   (b) LSPosed module that hooks java.io.File.exists() and
            //       libc.access() to fabricate per-app file-existence answers.
            //       Surgical (per-target-app) but adds a JNI trampoline on
            //       every filesystem-existence syscall — measurable overhead.
            // Option (a) is the production-grade fix and what NeoZygisk's
            // "system_fonts" patch already does for Pixel-spoofing tutorials.
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/Roboto-Bold.ttf",
            "/system/fonts/Roboto-Italic.ttf",
            "/system/fonts/Roboto-BoldItalic.ttf",
            "/system/fonts/Roboto-Light.ttf",
            "/system/fonts/Roboto-LightItalic.ttf",
            "/system/fonts/Roboto-Medium.ttf",
            "/system/fonts/Roboto-MediumItalic.ttf",
            "/system/fonts/Roboto-Thin.ttf",
            "/system/fonts/Roboto-Black.ttf",
            "/system/fonts/NotoColorEmoji.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/NotoSansArabic-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansDevanagari-Regular.ttf",
            "/system/fonts/NotoSansHebrew-Regular.ttf",
            "/system/fonts/NotoSansThai-Regular.ttf",
            "/system/fonts/NotoSansBengali-Regular.ttf",
            "/system/fonts/NotoSansEthiopic-Regular.ttf",
            "/system/fonts/NotoSansGeorgian-Regular.ttf",
            "/system/fonts/NotoSansArmenian-Regular.ttf",
            "/system/fonts/NotoSerif-Regular.ttf",
            "/system/fonts/NotoNaskhArabic-Regular.ttf",
            "/system/fonts/DroidSansMono.ttf",
            "/system/fonts/CutiveMono.ttf",
            "/system/fonts/ComingSoon.ttf",
            "/system/fonts/DancingScript-Regular.ttf",
            "/system/fonts/CarroisGothicSC-Regular.ttf",
            "/system/fonts/AccanthisADFStdNo3-Regular.otf",
            "/system/fonts/NotoSansSymbols-Regular.ttf",
            "/system/fonts/NotoSansSymbols-Regular-Subsetted.ttf",
            "/system/fonts/NotoColorEmojiLegacy.ttf",
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

            // Mask rank-15 (identity.wifi_mac): WifiMacProbe reads only one
            // surface in production — `/sys/class/net/wlan0/address`. The
            // WifiManagerView interface lacks a `macAddress()` accessor (see
            // probe KDoc lines 25-36), so the framework-side cross-check is
            // unreachable until a future ProbeContext extension. The sysfs
            // surface alone is sufficient to drop the probe from
            // SCORE_NO_SIGNAL=0.50 (sysfs unreadable) to SCORE_CLEAN=0.0
            // (real-OUI MAC observed) with confidence CONFIDENCE_CAP_SINGLE_SURFACE=0.60.
            //
            // MAC value: 40:4e:36:7a:b2:c9
            //   - OUI 40:4e:36 = Google Inc. (IEEE-registered, WiFi-class).
            //     Distinct from the Bluetooth OUI (3c:5a:b4) so a future
            //     cross-probe check that requires "BT OUI ≠ WiFi OUI" doesn't
            //     trip.
            //   - First byte 0x40 = 0100_0000: bit 0x02 = 0 → NOT
            //     locally-administered (real production MACs are globally-
            //     administered; the WifiMacProbe scores 0.80 on
            //     locally-administered + unknown-OUI, so this must clear).
            //   - First byte multicast bit (0x01) = 0 → unicast (unicast is
            //     the only valid interface address shape).
            //   - Not in OUI_QEMU/OUI_VBOX/OUI_VMWARE/OUI_HYPERV/OUI_XEN/OUI_DOCKER.
            //   - Not MAC_ZERO, not MAC_ANDROID10_PRIVACY_DEFAULT.
            //   - 64-bit-extended MAC body (7a:b2:c9) is arbitrary; chosen for
            //     visual distinctness from the BT body (8d:f1:27).
            // Real-SpoofStack hook: Magisk magic-mount overlay of a synthetic
            // /sys/class/net/wlan0/address file containing the spoofed MAC.
            // Same write-discipline as the Bluetooth hci0/address surface —
            // sysfs is kernel-owned, mount-overlay is the only write path.
            // Production-grade SpoofStack also needs an LSPosed hook on
            // `android.net.wifi.WifiInfo.getMacAddress()` for the framework
            // side; per the KDoc, that call returns `02:00:00:00:00:00` to
            // non-LOCAL_MAC_ADDRESS apps on Android 6+, so a non-system-app
            // consumer doesn't see the real MAC anyway. The sysfs spoof is
            // the load-bearing surface for the probe in its current shape.
            "/sys/class/net/wlan0/address" to "40:4e:36:7a:b2:c9",

            // Mask rank-37 (network.dns_server) Signal 3: /etc/resolv.conf.
            // DnsServerProbe parses `nameserver <addr>` lines. Pre-Pie
            // Android shipped this file; on Pie+ the network-stack moves
            // DNS to Settings.Global + ConnectivityManager but real devices
            // often still expose a vestigial resolv.conf (especially when
            // they boot before the network module attaches). Containerized
            // ReDroid leaks the host's /etc/resolv.conf which on the
            // ground-truth ReDroid baseline shows the Hetzner DNS servers
            // (the container host) — a strong emulator/datacenter tell.
            // Spoofed value mirrors the net.dns1/2 properties for
            // cross-surface coherence (the probe distinct-merges all DNS
            // sources, so duplicates land in PATTERN_CLEAN with a single
            // T-Mobile US public-DNS entry).
            // Real-SpoofStack hook: Magisk magic-mount overlay of a synthetic
            // /etc/resolv.conf file. Alternative is to recompile the kernel
            // without /etc/resolv.conf at all (rare since Android 7), but
            // the mount-overlay is simpler and matches what existing
            // SpoofStack modules already do for /etc/hosts spoofing.
            "/etc/resolv.conf" to
                "# T-Mobile US carrier DNS — autogenerated by netd\n" +
                "nameserver 8.25.203.30\n" +
                "nameserver 8.25.203.31\n",

            // Mask rank-5 (network.ip_asn) Signal 3: NetworkIpAsnProbe scans
            // /proc/net/route for the AVD / Docker canonical gateway hex
            // tokens (0102000A=10.0.2.1, 010011AC=172.17.0.1). Real ReDroid
            // containers reuse the Docker bridge — so an un-spoofed capture
            // would expose 010011AC in the kernel route dump and trip the
            // probe at score 0.70. The spoofed value below is a clean
            // home-network route table with gateway 192.168.1.1
            // (= 0101A8C0 in little-endian kernel format), which is NOT in
            // the EMULATOR_GATEWAY_HEX_TOKENS set and lands the probe in
            // SIGNAL_CLEAN. Column layout matches the canonical Linux 4.x
            // kernel format (tab-separated, 11 columns, single header row).
            //
            // Real-SpoofStack hook: Magisk `mount --bind` to overlay
            // /proc/net/route with a synthetic home-network route table.
            // The /proc/net/* tree is generated by the kernel netlink
            // subsystem on every read (it's a virtual filesystem, not a
            // backed-by-disk file), so the only viable spoofing surfaces
            // are (a) mount-bind a static file over the path — visible to
            // every process on the device for the lifetime of the bind —
            // or (b) an LSPosed-class file-IO hook that redirects opens
            // of "/proc/net/route" to a synthetic stream. Option (a) is
            // the production-grade fix shipped in the Magisk-Hide /
            // DenyList family of modules for the parallel /etc/resolv.conf
            // surface above.
            //
            // NOTE: the ground-truth ReDroid /proc/net/route IS readable
            // via `docker exec redroid-test cat /proc/net/route` — the
            // kernel exposes it transparently through the container's
            // procfs mount. The audit/un-snapshottable.md doc tracks live
            // ASN lookups (which require INTERNET permission + a network
            // call) as the L6 production work that the rank-5 probe
            // stands in for declaratively.
            "/proc/net/route" to
                "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n" +
                "wlan0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n" +
                "wlan0\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n",
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
            // Mask rank-18 (network.vpn_proxy) Signal 3: VpnProxyProbe reads
            // Settings.Global.http_proxy and treats ANY non-empty value as a
            // configured system proxy → SCORE_PROXY_OR_TAP=0.85. The Iter-1
            // value ":0" was an incorrect Android sentinel — the canonical
            // "no proxy" answer on Android is `null` (key not set) or empty
            // string, NOT ":0" (which is parsed by ProxyInfo as host="",
            // port=0, treated as a configured-but-malformed proxy). Switched
            // to empty-string so the key remains visible to a settings dump
            // (mirroring what a real `settings get global http_proxy` reports
            // on a factory-clean Pixel — empty string after first DHCP cycle)
            // while disarming the probe rule.
            // Real-SpoofStack hook: `settings put global http_proxy ""` via
            // Magisk service.d boot script — or simply leave the key
            // unwritten (default state on factory Pixel is unset, which the
            // probe also reads as no-proxy).
            "http_proxy" to "",                          // was ":0" — incorrect sentinel
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
            // Base AOSP set — ReDroid ships these in the minimal package
            // inventory; no rank-10 emulator-marker packages
            // (com.bluestacks.*, com.vphone.*, etc.) are present in the
            // ground truth, so no masking needed for rank-10 itself.
            "android",
            "com.android.systemui",
            "com.android.settings",
            // Mask rank-2 (integrity.play_integrity) package-surface signal.
            // PlayIntegrityLiveProbe asks PackageManager whether
            // `com.google.android.gms` is installed. The un-spoofed ReDroid
            // capture has no GMS at all → probe lands in the 0.95 NO_VERDICT
            // tier. Adding the package here disarms the no-GMS rule. NOTE:
            // a real spoof requires more than just the package being
            // present — it requires the GMS signature chain to verify
            // against Google's published certificate, which a stub APK
            // (Microg-style) does NOT satisfy. The declarative probe here
            // only checks presence; a verifier doing a real Play Integrity
            // API call would still see the missing/invalid signature.
            // Real-SpoofStack hook: ship a stub `com.google.android.gms` APK
            // that exposes the AIDL surface apps consult before invoking
            // the API. Microg's `play-services-fake` package does this for
            // the "make Play Services available" half. The signature-spoof
            // half requires a separate LSPosed hook on
            // `PackageManager.getPackageInfo()` that fakes the signing-cert
            // chain (TrickyStore's `com.google.android.gms.unaltered`
            // pattern — but explicitly DO NOT install that package here,
            // it would trip the 0.85 GMS_HIJACK rule instead).
            "com.google.android.gms",
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
        //   BluetoothMacProbe(adapterMacSupplier = { ctx.queryBluetoothAdapterMac() })
        // which routes this value to the supplier. The accessor is a
        // `ProbeContext` interface method (with default `= null` so existing
        // fakes keep working) — same backward-compat pattern as
        // `querySettingGlobal` / `queryWifiManager` / `queryKeyguardManager`.
        // `SnapshotReplayContext` overrides the method to return the
        // snapshot's `bluetoothMac` field.
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

        // Mask rank-20 (env.timezone_locale_mismatch) and rank-36
        // (env.language_country). Phase-3 ProbeContext refactor (Power-8,
        // 2026-05-20) added `queryTimezoneId()`, `queryTimezoneOffsetMinutes()`,
        // `queryLocaleLanguage()`, `queryLocaleCountry()`, and
        // `queryLocaleDisplayName()` default-methods to ProbeContext (all
        // default to null). SnapshotReplayContext overrides each to return
        // the corresponding snapshot field; both probes' no-arg constructors
        // now route through these accessors instead of reading
        // `TimeZone.getDefault()` / `Locale.getDefault()` from the host JVM.
        //
        // Value selection: America/Los_Angeles + en_US is the canonical
        // Pixel-7 US retail locale pair. The country code US is in
        // TimezoneLocaleProbe.TIMEZONE_COUNTRY_TABLE["America/Los_Angeles"]
        // (= setOf("US")), so the pair lands in PAIR_MATCH → score 0.00.
        // Timezone offset -480 minutes (= -8 hours) is PST standard time;
        // accurate for January (the snapshot's capturedAt is 2026-05-20
        // which would actually be PDT/-420 min, but the offset is
        // evidence-only — not scored — so PST/-480 is acceptable and
        // matches the canonical "non-DST timezone offset" for documentation
        // simplicity).
        //
        // Real-SpoofStack hook: timezone lives in
        //   /data/system/users/0/settings_system.xml (Settings.System) and
        //   the `persist.sys.timezone` system property. Magisk resetprop +
        //   `setprop persist.sys.timezone America/Los_Angeles` at boot,
        //   plus an LSPosed hook on `java.util.TimeZone.getDefault()` for
        //   the per-app spoof. Locale lives in `persist.sys.locale` /
        //   `ro.product.locale.*` system properties + Resources.Configuration
        //   per-context. SpoofStack production needs:
        //   (a) Magisk resetprop `persist.sys.locale en-US`, `persist.sys.timezone
        //       America/Los_Angeles` at first boot,
        //   (b) LSPosed hooks on `Locale.getDefault()`, `TimeZone.getDefault()`,
        //       `Resources.getConfiguration().locale`, and
        //       `Resources.getConfiguration().getLocales().get(0)` for
        //       per-target-app spoofing.
        // Production SpoofStack modules (NeoZygisk, TrickyStore) ship this
        // hook bundle as the "locale-spoof" package.
        timezoneId = "America/Los_Angeles",
        timezoneOffsetMinutes = -480, // PST = UTC-8 = -480 min
        localeLanguage = "en",        // ISO 639-1, lowercase
        localeCountry = "US",         // ISO 3166-1 alpha-2, uppercase
        localeDisplayName = "English (United States)",

        // Mask rank-23 (ui.screen_resolution). Phase-4 ProbeContext refactor
        // (Power-8, 2026-05-20) added `queryDisplayMetrics(): DisplayMetricsView?`
        // default-method to ProbeContext, returning null by default. The
        // SnapshotReplayContext override synthesizes a DisplayMetricsView
        // over the snapshot's flat display fields below. ScreenResolutionProbe
        // now routes its width/height/density/xdpi/ydpi suppliers through
        // this accessor.
        //
        // Value selection: canonical Pixel 7 (panther) Tensor-G2 display.
        // 1080x2400 @ 420 dpi exactly matches `DEVICE_PROFILES["pixel 7"]`
        // in the probe — landing in `MODEL_MATCH` → score 0.00 with
        // CONFIDENCE_FULL=0.95 (display AND model both readable).
        // xdpi/ydpi distinct from densityDpi and slightly distinct from each
        // other — matches real Pixel 7 telemetry. Density 420 is a multiple
        // of 20 (passes the densityNotMod20 rule); not in EMULATOR_RESOLUTIONS
        // (1080x2400 ≠ 1080x1920 / 720x1280); xdpi != ydpi != density (passes
        // the perfect-DPI-equality emulator-tell rule).
        // Real-SpoofStack hook: DisplayMetrics is computed from the physical
        // panel + density override. Two production approaches:
        //   (a) Container-side: launch ReDroid with `--display=1080x2400`
        //       and set Magisk `resetprop ro.sf.lcd_density 420` plus
        //       `wm density 420` via a service.d boot script. The framework
        //       reads these to compute DisplayMetrics. Persistent across
        //       reboots; consistent for every app.
        //   (b) LSPosed module hooking
        //       `android.view.WindowManager.defaultDisplay.getMetrics()` and
        //       `Resources.getDisplayMetrics()` to fabricate per-app values.
        //       Surgical but adds a per-call trampoline.
        // Option (a) is the production-grade fix and is what TrickyStore's
        // "screen-spoof" module ships. SpoofStack production combines (a)
        // with `wm size 1080x2400` (window-manager logical-density override)
        // for full coverage of the four resolution-reading paths apps use.
        displayWidthPixels = 1080,    // Pixel 7 long edge (portrait)
        displayHeightPixels = 2400,   // Pixel 7 short edge (portrait)
        displayDensityDpi = 420,      // Pixel 7 logical density (multiple of 20)
        displayXdpi = 411.0f,         // Pixel 7 physical horizontal pixels-per-inch
        displayYdpi = 413.0f,         // Pixel 7 physical vertical pixels-per-inch

        // Mask rank-41 (env.gps_coordinates). Power-8 GPS-surface phase
        // added `queryLocationManager(): LocationManagerView` default-method
        // to ProbeContext (defaults to `UnknownLocationManagerView`). The
        // SnapshotReplayContext override synthesizes a LocationManagerView
        // over the snapshot's flat `gps*` fields below. GpsCoordinatesProbe
        // reads the view directly — no constructor supplier needed.
        //
        // Value selection: Googleplex (Mountain View, CA) is the
        // canonical "plausible Android user" location — most Pixel devices
        // have it as their last-known fix at some point. The (37.4221,
        // -122.0841) coordinate is the Google HQ visitor lot, NOT (0,0)
        // null-island, NOT the test-fixture (1,1)/(45,45) values.
        // Accuracy 5.5m is realistic for a phone GPS in good sky-view
        // (Pixel 7 spec sheet: 3-5m typical, 1-3m with WiFi-assist;
        // 5.5m comfortably above the 1.0m S4 implausibility floor).
        // Provider "gps" lands the probe in PATTERN_CLEAN — distinct
        // from the "fused" S5 mock-framework branch. gpsIsMock=false is
        // the explicit "framework says this fix is real" answer, which
        // disarms the S1 mock-provider rule.
        //
        // Real-SpoofStack hook: LocationManager.getLastKnownLocation()
        // is a system-service IPC call into the location-service backed
        // by /data/system/locationManagerService.xml + the LocationProvider
        // bound to each registered provider. Production approaches:
        //   (a) LSPosed module hooking
        //       android.location.LocationManager.getLastKnownLocation()
        //       to return a fabricated Location with the spoofed lat/lng,
        //       accuracy, provider, and isFromMockProvider=false. The
        //       hook also needs to fabricate FusedLocationProviderClient.
        //       getLastLocation() (com.google.android.gms.location.*) on
        //       the same surface — Play-Services-using apps hit that path
        //       instead of the framework LocationManager.
        //   (b) A recorded-fix-history backing-store: ship the LSPosed
        //       module with a JSON of timestamp -> Location records
        //       captured from a real Pixel session, and replay them in
        //       monotonic order on each getLastKnownLocation() call.
        //       Avoids the static-coordinates flag a future probe could
        //       add by checking if the fix is identical across N seconds.
        // Option (b) is the production-grade fix; production SpoofStack
        // (NeoZygisk's location-spoof package) ships this as a separate
        // module from the rank-39 isFromMockProvider hook (which is also
        // needed but addresses a different probe surface).
        gpsLat = 37.4221,             // Googleplex Mountain View — "I'm an Android user"
        gpsLng = -122.0841,
        gpsAccuracy = 5.5f,           // realistic Pixel-7 GPS accuracy
        gpsProvider = "gps",          // canonical GPS provider (not fused)
        gpsIsMock = false,            // framework explicitly says "real fix"

        // Power-12 rank-9.0 (runtime.frida_memory_maps) — DECLARATIVE.
        // The spoof story for this surface is "absent signal == clean":
        // a SpoofStack-protected container has no Frida payload in its
        // address space, no gum threads spawned, no frida-server ports
        // bound. Empty sets match the ground-truth ReDroid baseline AND
        // the clean-Pixel ground truth — no spoofing required because
        // the source data is already clean. (The probe lands at
        // PATTERN_CLEAN score 0.0.)
        // Real-SpoofStack hook: NONE NEEDED on the un-spoofed ReDroid
        // baseline. If a future container image bundled Frida (which it
        // currently does not), the SpoofStack would need:
        //   (a) Magisk DenyList for the target app's UID to unmount any
        //       /data/local/tmp/frida-server binary so the binary itself
        //       cannot mmap into the calling process.
        //   (b) LSPosed module hooking `java.io.File`/`libc.open` reads
        //       of `/proc/self/maps`, `/proc/self/task/*/comm`, and
        //       `/proc/net/tcp` to filter out lines containing the
        //       frida-class tokens / ports. Per
        //       audit/spoof-stack/un-snapshottable.md the REAL surface
        //       (a native attacker walking /proc directly via syscalls
        //       not routed through libc) is L0 — un-spoofable in the
        //       FOSS-tooling era of 2026.
        procSelfMapsLibs = emptySet(),
        runtimeThreadNames = emptySet(),
        openTcpPorts = emptySet(),

        // Power-12 rank-9.7 (runtime.native_prologue_hash) — DECLARATIVE,
        // mitigation_layer not_spoofable. The spoof story is "no
        // measurement OR no hooks observed". The probe scores zero
        // against an empty deltas + zero trampolines, which is the
        // intended state for a spoofed-but-not-hooked container.
        // Real-SpoofStack hook: CRITICAL — this surface CANNOT be
        // spoofed by a JVM-side hook. If the SpoofStack itself uses
        // inline hooks to mask other probes (e.g. an LSPosed hook on
        // Build.* readers), those hooks leave MOV X16/BR X16 trampolines
        // in the patched function prologues which a native-side
        // ptrace-equipped detector would observe directly. The only
        // mitigation is to use a HOOK-FREE detection-evasion stack
        // (Magisk DenyList + bind-mount overlays, no LSPosed hooks). Per
        // audit/spoof-stack/un-snapshottable.md and the inventory note
        // "UNCOUNTERED by FOSS in 2026" — this is BEST-STACK §IV Hard
        // Ceiling #1.
        prologueHashDeltas = emptyMap(),
        trampolinePatternCount = 0,

        // Power-12 rank-9.8 (integrity.prologue_got_hooks) — DECLARATIVE,
        // mitigation_layer not_spoofable. Same shape and same hard-
        // ceiling story as rank-9.7. A clean GOT/PLT table and zero
        // `rwxp` memory pages are the intended state. Real-SpoofStack
        // hook: same constraint as rank-9.7 — a hook-FREE detection-
        // evasion stack is required to keep this surface clean. BEST-
        // STACK §IV Hard Ceiling #1 — UNCOUNTERED.
        gotPltAnomalies = emptyMap(),
        rwxpMemorySegments = emptyList(),

        // Power-13 Gap #3 (root.mount_ns_mismatch) + Gap #10
        // (root.system_rw_mount) + Gap #12 (root.overlayfs_present)
        // — spoofed-clean mountinfo. The Shamiko-class spoof stack
        // intercepts /proc/<pid>/mountinfo reads and serves a
        // pruned view that omits Magisk's mount-bind operations,
        // for BOTH self and init. The result: no magisk-fingerprint
        // substrings appear anywhere; the rank-3.8 probe lands in
        // PATTERN_DIGEST_DIFF_NO_MAGISK (zygote isolation normal
        // case) at score 0.0.
        //
        // Real-SpoofStack hook: Shamiko + Zygisk-Assistant +
        // isolated_process — collectively re-route /proc/*/mountinfo
        // reads to a synthetic mount table that matches the
        // Pixel-7 reference rather than the actual ReDroid+Magisk
        // mounts. This is a high-effort spoof: requires (a) syscall
        // hooking for openat() targeting /proc/*/mountinfo,
        // (b) a maintained "clean reference" mount table that
        // matches the spoofed brand/model fingerprint, and (c)
        // re-synthesis of mount IDs / major:minor numbers that
        // don't trivially leak the underlying overlay2 storage.
        //
        // **Spoofed clean variant**: also note that the overlay-on-
        // /system mount (legitimate for Pixel-7 GKI-kernel devices
        // with system_ext partition) IS preserved here. The
        // discriminator the spoof scrubs is Magisk-specific paths
        // (/sbin/.magisk, /data/adb, magisk_tmp), not the overlay
        // filesystem itself — that's a legitimate Android 11+
        // mount type.
        // Power-13 Gap #1 (root.magisk_uds) — spoofed-clean
        // /proc/net/unix output. The Shamiko-class spoof stack
        // hooks the read of /proc/net/unix and filters out any
        // socket name containing magisk-fingerprint substrings,
        // serving the synthetic Android-system-only socket list
        // a clean Pixel-7 would have. Real-SpoofStack hook:
        // /proc/net/unix is one of the load-bearing surfaces
        // Shamiko already filters for the existing rank-3 +
        // rank-8 probes, so this Power-13 probe sees the same
        // already-filtered list.
        procNetUnixSockets = listOf(
            "@android:installd",
            "@android:zygote",
            "@android:netd",
            "@android:wpa_supplicant",
            "/dev/socket/installd",
            "/dev/socket/zygote",
            "/dev/socket/netd",
        ),

        mountInfo = mapOf(
            // /proc/self/mountinfo — synthetic Pixel-7-shaped view.
            // Magisk paths SCRUBBED.
            "self" to """
                1 0 253:0 / / ro - ext4 /dev/block/dm-0
                2 1 253:1 / /system ro - ext4 /dev/block/dm-1
                3 1 253:2 / /vendor ro - ext4 /dev/block/dm-2
                4 1 259:1 / /data rw - f2fs /dev/block/by-name/userdata
                5 1 0:5 / /sys ro - sysfs sysfs
                6 1 0:6 / /proc ro - proc proc
                7 1 0:7 / /dev rw - tmpfs tmpfs
                8 4 0:8 / /storage/emulated rw - fuse fuse
            """.trimIndent(),
            // /proc/1/mountinfo — synthetic Pixel-7 view with init-
            // process-specific entries (apexd mounts, vendor_dlkm
            // overlays) that an app's zygote-forked namespace
            // legitimately does NOT inherit. This makes the
            // digests differ in a SHAPE that matches a real
            // production Pixel device (preserving the zygote-
            // isolation pattern Android has used since A8 user-
            // profile sandboxes), so the rank-3.8 probe lands in
            // PATTERN_DIGEST_DIFF_NO_MAGISK at score 0.0 — the
            // legitimate "zygote isolated my namespace from init"
            // tell. Magisk paths SCRUBBED from both.
            "1" to """
                1 0 253:0 / / ro - ext4 /dev/block/dm-0
                2 1 253:1 / /system ro - ext4 /dev/block/dm-1
                3 1 253:2 / /vendor ro - ext4 /dev/block/dm-2
                4 1 259:1 / /data rw - f2fs /dev/block/by-name/userdata
                5 1 0:5 / /sys ro - sysfs sysfs
                6 1 0:6 / /proc ro - proc proc
                7 1 0:7 / /dev rw - tmpfs tmpfs
                8 4 0:8 / /storage/emulated rw - fuse fuse
                9 2 0:9 / /apex/com.android.runtime ro - bind /apex/com.android.runtime
                10 2 0:10 / /apex/com.android.art ro - bind /apex/com.android.art
                11 3 253:11 / /vendor_dlkm ro - ext4 /dev/block/dm-11
            """.trimIndent(),
        ),
    )
}

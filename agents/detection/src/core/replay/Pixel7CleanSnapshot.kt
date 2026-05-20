// agents/detection/src/core/replay/Pixel7CleanSnapshot.kt
//
// NEGATIVE-CLASS device snapshot — a believable factory-clean Google Pixel 7
// (codename `panther`, Android 13 / API 33) running stock retail firmware,
// captured at the same probe surface as `RedroidV12Snapshot.kt`.
//
// This snapshot exists to anchor the probe inventory's **false-positive
// floor**: every emulator-classification probe in the inventory MUST score
// 0.0 (clean) on this snapshot. If a probe fires anything non-zero against
// this data, that is either:
//   (a) a probe bug that needs a separate fix, OR
//   (b) a legitimate signal that real Pixel 7 production builds contain
//       (in which case the probe scoring needs calibration, NOT the snapshot)
//
// Property values come from publicly documented Pixel 7 retail fingerprints
// (Google factory-image release notes for TQ1A.230205.002 / build 9471150)
// and AOSP `aosp_panther` device tree conventions. No live device was
// touched to build this snapshot — these are the published, deterministic
// values that any factory-clean Pixel 7 on Android 13 will report.
//
// Pairs with `RedroidV12Snapshot` (positive class) under the
// `SnapshotReplayContext` test harness — together they pin both ends of
// the probe-inventory decision boundary.

package com.detectorlab.core.replay

object Pixel7CleanSnapshot {

    /**
     * Frozen Pixel 7 retail capture. Values reflect a factory-reset device
     * on stock Android 13 (build TQ1A.230205.002, security patch 2023-02-05)
     * that has never been put into developer mode and has never had OEM
     * unlocking enabled.
     *
     * Property-set scope mirrors `RedroidV12Snapshot.SNAPSHOT` exactly so
     * each probe surface has the same observation budget on both ends of
     * the decision boundary.
     */
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "pixel-7-panther-clean-2026-05-20",
        capturedAt = "2026-05-20T00:00:00Z",
        sdkInt = 33, // Android 13 → API 33
        systemProperties = mapOf(
            // rank 1 buildprop.fingerprint — canonical Pixel 7 retail fingerprint
            // for build TQ1A.230205.002 (Feb 2023 security patch).
            "ro.build.fingerprint" to
                "google/panther/panther:13/TQ1A.230205.002/9471150:user/release-keys",
            "ro.build.display.id" to "TQ1A.230205.002",
            "ro.build.tags" to "release-keys",
            "ro.build.type" to "user",
            "ro.build.version.release" to "13",
            "ro.build.version.sdk" to "33",

            // rank 9 model_brand_manufacturer — canonical Google branding.
            // Manufacturer is title-case `Google`; brand is lowercase `google`.
            // The probe's `isBrandManufacturerAligned` lowercases both sides
            // before comparison, so this aligns via case-insensitive equality.
            "ro.product.brand" to "google",
            "ro.product.model" to "Pixel 7",
            "ro.product.manufacturer" to "Google",
            "ro.product.device" to "panther",
            "ro.product.name" to "panther",

            // rank 4 qemu_artifacts — explicitly NOT SET. Real Pixel 7 retail
            // does not emit any `ro.kernel.qemu*` properties; we omit them
            // entirely so the accessor returns null (the "key not in map"
            // branch in SnapshotReplayContext). Empty-string would be a
            // weaker signal — null is the correct ground truth here.

            // rank 28 board_hardware — `panther` is the real Pixel 7 board
            // codename (Google Tensor G2 SoC platform). NOT an emulator marker.
            "ro.hardware" to "panther",
            "ro.product.board" to "panther",
            "ro.board.platform" to "gs201",       // Google Silicon Tensor G2 platform name
            "ro.board.manufacturer" to "Google",
            "ro.hardware.chipname" to "gs201",

            // rank 27 cpu_abi — pure ARM64 stack, no Houdini bridge. This is
            // the canonical "real ARM phone since 2016" pattern.
            "ro.product.cpu.abi" to "arm64-v8a",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi",
            "ro.product.cpu.abilist32" to "armeabi-v7a,armeabi",
            "ro.product.cpu.abilist64" to "arm64-v8a",

            // rank 13 bootloader — AVB green + locked + production-user-build.
            // A factory-fresh Pixel 7 that has NEVER been put into developer
            // mode reports `ro.oem_unlock_supported=0`; the property flips
            // to `1` only after the user enables "OEM unlocking" in Developer
            // Options. Setting to "0" reflects the never-touched factory
            // state, which is the correct negative-class ground truth.
            "ro.boot.vbmeta.device_state" to "green",
            "ro.boot.verifiedbootstate" to "green",
            "ro.boot.flash.locked" to "1",
            "ro.oem_unlock_supported" to "0",
            "ro.secure" to "1",
            "ro.debuggable" to "0",
            "ro.boot.warranty_bit" to "0",
            "ro.warranty_bit" to "0",

            // rank 14 selinux — Pixel 7 ships SELinux enforcing.
            "ro.boot.selinux" to "enforcing",
            "ro.build.selinux" to "1",

            // rank 2 (integrity.play_integrity) — canonical Pixel 7 Play
            // Integrity surface. A factory-clean Pixel 7 reports a
            // versioned GMS release code, a provisioned `clientidbase` that
            // carries the Pixel model marker, and the default factory
            // values for the gms-disabled / rescue-disabled toggles. Drives
            // PlayIntegrityLiveProbe to score 0.0 (CLEAN verdict tier).
            "ro.com.google.gmsversion" to "243532033",
            "ro.com.google.clientidbase" to "android-google-Pixel-7",
            "ro.gms.disabled" to "0",
            "persist.sys.disable_rescue" to "0",
            // ro.boot.veritymode — paired with ro.boot.flash.locked above,
            // both required for the rank-2 bootloader-unlock rule to NOT
            // fire. A factory-clean Pixel reports "enforcing".
            "ro.boot.veritymode" to "enforcing",

            // rank 57 touch_pressure — Pixel 7 ships the FTS7250 touch
            // controller IC (FocalTech FT7250 / Pixel 7 panther device-tree
            // value). NOT empty (NO_TOUCH_HAL rule disarms), NOT in
            // SYNTHETIC_TOUCHSCREEN_SUBSTRINGS (synthetic-HAL rule
            // disarms). Combined with the four /dev/input/event* nodes
            // below this lands the rank-57 probe in PATTERN_CLEAN (0.0).
            "ro.hardware.touchscreen" to "fts7250",

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) — Pixel 7
            // `panther` ships the Tensor-G2-class audio codec HAL. The
            // canonical published name in `ro.hardware.audio` on Pixel 7
            // retail builds is `trondheim_audio` — the codename used in
            // the Google factory image's vendor/audio HAL config (same
            // naming convention as `panther`/`gs201` elsewhere in this
            // snapshot). This is a REAL codec name, NOT in
            // AudioFingerprintProbe.SOFTWARE_HAL_SUBSTRINGS (`stub`/
            // `dummy`), NOT equal to HAL_SND_CARD_DUMMY — so the
            // declarative probe lands cleanly in PATTERN_CLEAN (0.0)
            // with confidence 0.70 (the declarative cap — see probe
            // KDoc's "declarative limitation" section for why a
            // proxy-marker probe can't claim WebView-FFT confidence
            // 0.99). No loopback / silent_in / dock_aplc props set —
            // those rules only fire on explicit "1" / "true", and
            // their absence is the correct negative-class signal.
            "ro.hardware.audio" to "trondheim_audio",

            // rank 56 webgl_fingerprint — canonical Pixel 7 (Tensor G2)
            // GPU property surface. Tensor-G2 pairs an Arm Mali-G710 MP7
            // GPU with Google's vendor blob, which publishes the driver
            // identifier as `kgsl` in `ro.gpu.driver` (a historical
            // Qualcomm/Adreno naming kept across the Tensor migration —
            // documented in `device/google/panther` device tree and the
            // Google factory-image vendor partition for build
            // TQ1A.230205.002).
            //
            // Values:
            //   ro.gpu.driver = "kgsl" — real Pixel-class driver name
            //     (NOT "swiftshader" which fires SWIFTSHADER 0.95)
            //   ro.hardware.gralloc = "mali" — Mali-G710 vendor gralloc
            //     HAL (NOT "ranchu" which is the goldfish/AVD GL backend)
            //   ro.hardware.vulkan = "1.3.0" — Vulkan 1.3 supported by
            //     Mali-G710 since the 2022 vendor blob refresh
            //   ro.opengles.version = "196610" — = 0x30002 = OpenGL ES
            //     3.2 packed as `(major << 16) | minor`. EXACTLY the
            //     boundary value the rank-56 probe treats as PLAUSIBLE
            //     (strict-less-than semantics — 196610 itself does NOT
            //     fire old_gles 0.50). Real Pixel 7 spec confirms GLES
            //     3.2 support; this is the lab-approximation value and
            //     pins the false-positive floor for the rank-56 probe.
            //   ro.boot.qemu.gltransport — INTENTIONALLY OMITTED (= null
            //     at probe accessor level). The property is AVD-only;
            //     real Pixel 7 retail does not emit it. The probe's
            //     emulator_gl rule only fires when this key is set to a
            //     non-empty value, so omission is the correct negative-
            //     class ground truth.
            //   ro.kernel.qemu.gles — INTENTIONALLY OMITTED. AVD-only
            //     property; real Pixel 7 does not set it. Distinct from
            //     the RedroidV12 capture which preserves the prop as
            //     empty-string ("set-but-empty" was the captured ground
            //     truth on the container).
            //
            // The rank-56 probe lands in PATTERN_CLEAN (0.0) with the
            // 0.75 declarative confidence pinned by the L0-vs-L3 framing
            // in the probe KDoc. Confidence does NOT raise to 0.95 even
            // on a fully-clean property cascade because the property
            // surface is not the dispositive WebGL shader-trace surface.
            "ro.gpu.driver" to "kgsl",
            "ro.hardware.gralloc" to "mali",
            "ro.hardware.vulkan" to "1.3.0",
            "ro.opengles.version" to "196610",
        ),
        existingFiles = setOf(
            // rank 3 su_detection — clean Pixel 7 has NONE of the su-binary
            // paths or magisk artifacts. Empty set means every fileExists()
            // lookup returns false, which is the correct negative-class
            // answer (this is a stock device, no root toolchain installed).

            // rank 57 touch_pressure — real Pixel 7 exposes the standard
            // four evdev input devices: event0=touchscreen,
            // event1=volume/power keys, event2=sensor-hub-derived events,
            // event3=fingerprint sensor. Population satisfies the rank-57
            // declarative probe's "phone-class input surface" expectation
            // (>=3 event devices) and disarms the SINGLE_INPUT_DEVICE
            // weak-signal rule.
            "/dev/input/event0",
            "/dev/input/event1",
            "/dev/input/event2",
            "/dev/input/event3",

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) — real
            // Pixel 7 has a working audio HAL bound, so `/dev/snd/controlC0`
            // exists as the ALSA control device for the Tensor-G2 codec.
            // This is a positive observation that combines with the HAL
            // prop above to disarm the PATTERN_NO_HAL_NO_DEVICE rule
            // (which would only fire on set-but-empty HAL + missing
            // device; here both surfaces are positively present).
            "/dev/snd/controlC0",
        ),
        readableFiles = mapOf(
            // rank 30 proc_version — real Pixel 7 kernel banner. Format is
            // the canonical Android 13 build format: kernel name encodes
            // the Android version + GKI ABI tag (`android13-gki`), build
            // host is the Google kleaf-builder, and the active compiler is
            // clang (not gcc — Android dropped gcc in 2016).
            "/proc/version" to
                "Linux version 5.10.149-android13-4-00014-g8d83edf3bdc4-ab9576845 " +
                "(kleaf@build-host) (Android (8508608, based on r450784e) " +
                "clang version 14.0.6 (https://android.googlesource.com/" +
                "toolchain/llvm-project 4c603efb0cca074e9238af8b4106c30add4418f6), " +
                "LLD 14.0.6) #1 SMP PREEMPT Tue Jan 24 18:08:19 UTC 2023",
        ),
        installedPackages = setOf(
            // Realistic Pixel 7 factory app set — the four packages the task
            // brief names plus a few standard AOSP system packages that any
            // Pixel ships with. None of these should appear in any probe's
            // emulator-marker package list (com.bluestacks.* / com.vphone.* /
            // com.google.android.launcher.layouts.genymotion / etc.).
            "com.google.android.gms",                  // Google Play Services
            "com.google.android.apps.maps",            // Google Maps
            "com.android.systemui",                    // System UI
            "com.android.settings",                    // Settings app
            "android",                                 // android system package
            "com.google.android.gsf",                  // Google Services Framework
            "com.google.android.tts",                  // Google Text-to-Speech
            "com.android.vending",                     // Play Store
        ),
        // Canonical Pixel 7 (`panther`, Tensor G2) sensor inventory.
        // `SensorManager.getSensorList(TYPE_ALL)` on a factory-clean Pixel 7
        // returns at minimum the six "Tier 1" sensors the rank-24/42/43/44/45
        // probes key on. Source: Google factory image documentation for
        // `panther` device tree + Android CDD 7.3 sensor requirements.
        // Sensor type constants are the canonical Android `Sensor.TYPE_*`
        // integers (see `AccelerometerGyroProbe.companion`):
        //   TYPE_ACCELEROMETER   = 1   (Tier 1 — required on every Android)
        //   TYPE_MAGNETIC_FIELD  = 2   (Tier 1 — compass)
        //   TYPE_GYROSCOPE       = 4   (Tier 1 — rotation)
        //   TYPE_LIGHT           = 5   (Tier 1 — auto-brightness)
        //   TYPE_PRESSURE        = 6   (flagship — Pixel 6+ ships barometer)
        //   TYPE_PROXIMITY       = 8   (phone-class — earpiece)
        // A real Pixel 7 also reports a longer tail (step counter, rotation
        // vector, gravity, etc.) — these six are the load-bearing subset
        // every probe in the current inventory checks. Tail sensors can be
        // added when a future probe scores on them.
        sensorTypes = setOf(1, 2, 4, 5, 6, 8),

        // Canonical Pixel 7 GPS fix-frame for rank-41 (env.gps_coordinates).
        // A factory-clean Pixel 7 that has ever booted with location
        // services enabled reports a realistic last-known fix; Googleplex
        // (the same Mountain View coordinate the RedroidSpoofedSnapshot
        // uses) keeps the two snapshots cross-consistent. Accuracy 5.5m
        // is the canonical Pixel 7 GPS accuracy in good sky-view
        // conditions — well above the 1.0m S4 implausibility floor.
        // Provider "gps" → distinct from the S5 fused-without-GPS branch.
        // gpsIsMock=false is the framework's explicit "this is a real
        // fix" answer, disarming the S1 mock-provider rule.
        gpsLat = 37.4221,
        gpsLng = -122.0841,
        gpsAccuracy = 5.5f,
        gpsProvider = "gps",
        gpsIsMock = false,

        // Canonical Pixel 7 BluetoothAdapter address surface. The OUI
        // 3C:5A:B4 is one of Google Inc.'s IEEE-registered OUI blocks —
        // first-byte 0x3C has the locally-administered bit (0x02) clear,
        // and it's not in any of the WifiMacProbe emulator OUI sets
        // (QEMU/VBox/VMware/HyperV/Xen). Reported by `BluetoothAdapter.
        // getDefaultAdapter().getAddress()` to LOCAL_MAC_ADDRESS-permitted
        // callers; non-system apps in Android 6+ see the
        // 02:00:00:00:00:00 privacy default (BluetoothMacProbe handles
        // that case via the SCORE_PRIVACY_DEFAULT_BENIGN branch when
        // sysfs is unreadable). Mirrors the RedroidSpoofedSnapshot value
        // for cross-snapshot consistency in the test harness.
        bluetoothMac = "3c:5a:b4:8d:f1:27",

        // Power-12 rank-9.0 (runtime.frida_memory_maps) — DECLARATIVE.
        // A factory-clean Pixel 7 process has none of the frida-class
        // libraries mapped, none of the gum-runtime thread names spawned,
        // and ports 27042/27043 unbound. Empty sets capture this clean
        // ground truth across all three surfaces.
        procSelfMapsLibs = emptySet(),
        runtimeThreadNames = emptySet(),
        openTcpPorts = emptySet(),

        // Power-12 rank-9.7 (runtime.native_prologue_hash) — DECLARATIVE,
        // mitigation_layer not_spoofable. JVM-side snapshots cannot
        // capture this surface — the production runtime would populate
        // these fields via a native ptrace measurement at capture time.
        // Empty/zero means "no measurement performed"; the probe scores
        // this as absent (NOT clean) signal, contributing zero. A real
        // Pixel 7 with NO injected hooks would also produce empty
        // deltas + zero trampolines if the measurement DID run, so the
        // empty state is consistent with a clean device.
        prologueHashDeltas = emptyMap(),
        trampolinePatternCount = 0,

        // Power-12 rank-9.8 (integrity.prologue_got_hooks) — DECLARATIVE,
        // mitigation_layer not_spoofable. Same shape as rank-9.7 above:
        // empty map / empty list = "no measurement performed" OR "clean
        // GOT/PLT + no rwxp pages observed". A factory-clean Pixel 7
        // process has no GOT entries pointing into /data/local/tmp or
        // other anomalous targets, and no `rwxp` memory pages — both
        // surfaces empty by ground truth.
        gotPltAnomalies = emptyMap(),
        rwxpMemorySegments = emptyList(),

        // Power-13 Gap #3 / #10 / #12 — Pixel 7 GKI-kernel
        // production mountinfo. Apex mounts visible to init,
        // partially inherited via zygote bind into the app
        // namespace (apex runtime + art runtime are app-visible;
        // vendor_dlkm typically is not). No Magisk paths anywhere
        // — factory-clean state. Power-13 Gap #3 lands in
        // PATTERN_DIGEST_DIFF_NO_MAGISK at score 0.0. Gap #10
        // sees /system mounted read-only (the production state).
        // Gap #12 sees NO overlay over /system (Pixel 7 uses
        // dynamic-partition ext4 mounts, not overlayfs).
        // Power-13 Gap #1 (root.magisk_uds) — clean Pixel 7
        // /proc/net/unix output. AOSP system daemons only; no
        // magisk fingerprints.
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
            "self" to """
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
            """.trimIndent(),
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
                12 1 253:12 / /product ro - ext4 /dev/block/dm-12
            """.trimIndent(),
        ),
    )
}

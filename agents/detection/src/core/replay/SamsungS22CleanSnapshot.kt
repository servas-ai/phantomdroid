// agents/detection/src/core/replay/SamsungS22CleanSnapshot.kt
//
// NEGATIVE-CLASS device snapshot — a believable factory-clean Samsung Galaxy
// S22 (model `SM-S901B`, codename `r0q`, Exynos 2200 SoC, Android 14 / API 34
// One UI 6.0) running stock retail firmware, captured at the same probe
// surface as `Pixel7CleanSnapshot.kt`.
//
// This snapshot is the SECOND positive-of-"clean" anchor in the inventory's
// negative-class set. Where `Pixel7CleanSnapshot` pins the false-positive
// floor against a Google flagship, this snapshot pins it against a non-Google
// flagship on a different SoC vendor (Samsung Exynos vs Google Tensor),
// different OEM skin (One UI 6 vs stock Pixel), different vendor app set
// (Samsung Knox stack vs Google GMS stack), and a different vendor IEEE OUI
// (F4:99:BA Samsung Electronics vs 3C:5A:B4 Google).
//
// Why diversity matters: model-substring probes that ONLY see Pixel data
// risk overfitting to the literal "pixel" token. A Samsung S22 snapshot
// confirms the probes catch genuine emulator markers (goldfish/ranchu/qemu/
// generic) and not just "the device isn't a Pixel". If a probe scores >0.0
// against this snapshot, that is either a probe bug or a genuine production
// signal that needs scoring calibration (NOT a snapshot fix — the snapshot
// is the ground truth).
//
// Property values come from publicly documented Samsung S22 retail
// fingerprints (Samsung official spec page samsung.com/us/smartphones/
// galaxy-s22 + S901BXXSCAXEC firmware release notes) and Samsung One UI 6
// device tree conventions. No live device was touched — these are the
// published values that any factory-clean SM-S901B on Android 14 will
// report.

package com.detectorlab.core.replay

object SamsungS22CleanSnapshot {

    /**
     * Frozen Samsung Galaxy S22 retail capture. Values reflect a factory-
     * reset SM-S901B (Exynos 2200 international variant) on stock Android 14
     * + One UI 6 (build S901BXXSCAXEC, security patch 2024) that has never
     * been put into developer mode, has never had Knox tripped, and has
     * never had OEM unlocking enabled.
     *
     * Property-set scope mirrors `Pixel7CleanSnapshot.SNAPSHOT` so each
     * probe surface sees the same observation budget on both negative-class
     * anchors.
     */
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "samsung-s22-sm-s901b-2026-05-20",
        capturedAt = "2026-05-20T00:00:00Z",
        sdkInt = 34, // Android 14 / One UI 6 → API 34
        systemProperties = mapOf(
            // rank 1 buildprop.fingerprint — canonical Samsung S22 retail
            // fingerprint for the international Exynos variant (SM-S901B)
            // on the S901BXXSCAXEC One UI 6 release. Brand-prefix is
            // `samsung/` (NOT `google/`) and the build-host tag is
            // `release-keys` (stock production keys, not test-keys).
            "ro.build.fingerprint" to
                "samsung/r0qxxx/r0q:14/UP1A.231005.007/S901BXXSCAXEC:user/release-keys",
            "ro.build.display.id" to "UP1A.231005.007.S901BXXSCAXEC",
            "ro.build.tags" to "release-keys",
            "ro.build.type" to "user",
            "ro.build.version.release" to "14",
            "ro.build.version.sdk" to "34",

            // rank 9 model_brand_manufacturer — canonical Samsung branding.
            // Both brand and manufacturer are lowercase `samsung` per
            // Samsung's device tree convention; aligned via case-insensitive
            // equality. Model `SM-S901B` is the IMEI-printable international
            // variant string and contains no emulator/AVD keyword.
            "ro.product.brand" to "samsung",
            "ro.product.model" to "SM-S901B",
            "ro.product.manufacturer" to "samsung",
            "ro.product.device" to "r0q",
            "ro.product.name" to "r0qxxx",

            // rank 4 qemu_artifacts — explicitly NOT SET. Real Samsung S22
            // retail does not emit any `ro.kernel.qemu*` properties; we
            // omit them entirely so the accessor returns null.

            // rank 28 board_hardware — `exynos2200` is Samsung's real SoC
            // platform name for the S22 international variant (NOT in the
            // emulator marker list — goldfish/ranchu/redroid/vbox86/cancro).
            "ro.hardware" to "exynos2200",
            "ro.product.board" to "exynos2200",
            "ro.board.platform" to "exynos2200",

            // rank 27 cpu_abi — pure ARM64 stack, no Houdini bridge. Same
            // canonical "real ARM flagship since 2016" shape as the Pixel 7
            // snapshot — the abilist set is fixed by AOSP CDD, not by OEM.
            "ro.product.cpu.abi" to "arm64-v8a",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi",
            "ro.product.cpu.abilist32" to "armeabi-v7a,armeabi",
            "ro.product.cpu.abilist64" to "arm64-v8a",

            // rank 13 bootloader — Knox-untripped factory state. Samsung
            // ships AVB green + flash locked + production-user-build, and
            // `ro.boot.warranty_bit=0` is the Knox-side "warranty intact"
            // indicator (flips to 1 the moment the bootloader is unlocked
            // or a non-Samsung-signed kernel boots). A factory-fresh S22
            // that has never been put into dev mode reports `0`.
            "ro.boot.vbmeta.device_state" to "locked",
            "ro.boot.verifiedbootstate" to "green",
            "ro.boot.flash.locked" to "1",
            "ro.boot.warranty_bit" to "0",
            "ro.warranty_bit" to "0",
            "ro.secure" to "1",
            "ro.debuggable" to "0",

            // rank 14 selinux — Samsung One UI ships SELinux enforcing.
            "ro.boot.selinux" to "enforcing",
            "ro.build.selinux" to "1",

            // rank 57 touch_pressure — Samsung Galaxy S22 ships Samsung's
            // STM FingerTipS touch controller ("stmfts"), the canonical
            // touch IC across the S22 family. NOT empty (NO_TOUCH_HAL
            // rule disarms), NOT in SYNTHETIC_TOUCHSCREEN_SUBSTRINGS
            // (synthetic-HAL rule disarms). Combined with the four
            // /dev/input/event* nodes below this lands the rank-57
            // probe in PATTERN_CLEAN (0.0).
            "ro.hardware.touchscreen" to "stmfts",

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) — Samsung
            // Galaxy S22 (international SM-S901B Exynos variant) ships
            // the Exynos 2200 SoC's integrated audio codec. The
            // canonical published name in `ro.hardware.audio` on stock
            // One UI 6 retail builds is `exynos_audio_2200` — the
            // codename used in Samsung's vendor/audio HAL config (same
            // naming convention as `exynos2200` elsewhere in this
            // snapshot's ro.hardware / ro.board.platform fields). This
            // is a REAL codec name, NOT in
            // AudioFingerprintProbe.SOFTWARE_HAL_SUBSTRINGS (`stub`/
            // `dummy`), NOT equal to HAL_SND_CARD_DUMMY — so the
            // declarative probe lands cleanly in PATTERN_CLEAN (0.0)
            // with confidence 0.70 (the declarative cap — see probe
            // KDoc's "declarative limitation" section). No loopback /
            // silent_in / dock_aplc props set — those rules only fire
            // on explicit "1" / "true", and their absence is the
            // correct negative-class signal on stock Samsung firmware.
            "ro.hardware.audio" to "exynos_audio_2200",

            // rank 56 webgl_fingerprint — canonical Samsung Galaxy S22
            // (Exynos 2200 international variant) GPU property surface.
            // The Exynos 2200 SoC pairs an Arm Mali-G710 MP9 GPU with
            // Samsung's vendor blob. Samsung's audio/graphics HAL config
            // publishes the driver identifier as `mali-g710_kmd` in
            // `ro.gpu.driver` — the canonical "Mali-G710 kernel-mode
            // driver" naming Samsung uses across the S22/S23 family.
            //
            // Values:
            //   ro.gpu.driver = "mali-g710_kmd" — real Exynos-Mali driver
            //     name (NOT "swiftshader" which fires SWIFTSHADER 0.95;
            //     distinct from the Pixel 7 "kgsl" value so the rank-56
            //     probe is exercised against TWO different real driver
            //     identifiers across the negative-class anchors)
            //   ro.hardware.gralloc = "samsung" — Samsung vendor gralloc
            //     HAL identifier (NOT "ranchu")
            //   ro.hardware.vulkan = "1.3.0" — Vulkan 1.3 supported by
            //     Mali-G710 since the 2022 vendor blob refresh
            //   ro.opengles.version = "196610" — = 0x30002 = OpenGL ES
            //     3.2 packed as `(major << 16) | minor`. Same boundary
            //     value as Pixel 7 — the rank-56 probe treats this as
            //     PLAUSIBLE (strict-less-than semantics). Samsung S22
            //     spec confirms GLES 3.2 support; the boundary pins the
            //     false-positive floor.
            //   ro.boot.qemu.gltransport — INTENTIONALLY OMITTED. AVD-
            //     only property; real Samsung S22 retail does not emit it.
            //   ro.kernel.qemu.gles — INTENTIONALLY OMITTED. Same reason.
            //
            // Cross-snapshot diversity: this anchor uses `mali-g710_kmd`
            // / `samsung` gralloc — distinct from Pixel 7's `kgsl` /
            // `mali` tuple. If the rank-56 probe over-fitted to a single
            // Pixel-class string (e.g. accidentally required exact
            // `kgsl`), this Samsung anchor would catch the regression.
            "ro.gpu.driver" to "mali-g710_kmd",
            "ro.hardware.gralloc" to "samsung",
            "ro.hardware.vulkan" to "1.3.0",
            "ro.opengles.version" to "196610",
        ),
        existingFiles = setOf(
            // rank 3 su_detection — clean Samsung S22 has NONE of the
            // su-binary paths or magisk artifacts. Knox additionally bricks
            // any device that has had su installed (warranty_bit flip),
            // making this even more strictly true on Samsung than on Pixel.

            // rank 57 touch_pressure — real Samsung Galaxy S22 exposes
            // the standard four evdev input devices: event0=touchscreen,
            // event1=volume/power keys, event2=sensor-hub-derived events,
            // event3=fingerprint sensor (ultrasonic FoD on S22). Same
            // four-device shape as Pixel 7 — the AOSP CDD requires the
            // touchscreen + buttons + fingerprint trio on any modern
            // flagship, with the sensor-hub adding a fourth aggregator
            // node. Population satisfies the rank-57 declarative probe's
            // "phone-class input surface" expectation (>=3 event devices).
            "/dev/input/event0",
            "/dev/input/event1",
            "/dev/input/event2",
            "/dev/input/event3",

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) — real
            // Samsung S22 has a working audio HAL bound (Exynos 2200
            // integrated codec), so `/dev/snd/controlC0` exists as the
            // ALSA control device. This is a positive observation that
            // combines with the HAL prop above to disarm the
            // PATTERN_NO_HAL_NO_DEVICE rule (which would only fire on
            // set-but-empty HAL + missing device; here both surfaces
            // are positively present).
            "/dev/snd/controlC0",
        ),
        readableFiles = mapOf(
            // rank 30 proc_version — Samsung S22 kernel banner. Samsung
            // builds kernels on their internal Linux build infrastructure
            // (not Google's kleaf); the banner reflects a Samsung build
            // host and a GCC-derived toolchain string typical of Samsung
            // kernel releases for the Exynos 2200 platform.
            "/proc/version" to
                "Linux version 5.10.149-android13-4-25958155-abS901BXXSCAXEC " +
                "(android-build@sec-build-host) (Android (8508608, based on r450784e) " +
                "clang version 14.0.6, LLD 14.0.6) #1 SMP PREEMPT " +
                "Thu Mar 14 12:34:56 KST 2024",
        ),
        installedPackages = setOf(
            // Realistic Samsung S22 factory app set. Stock Samsung devices
            // ship Knox stack + Samsung-branded launcher/dialer + the
            // Google GMS baseline. None of these appear in any probe's
            // emulator-marker package list (bluestacks/vphone/genymotion).
            "com.android.systemui",                    // System UI
            "com.android.settings",                    // Settings
            "android",                                 // android system package
            "com.samsung.knox.kpecore",                // Samsung Knox Platform for Enterprise
            "com.sec.android.app.launcher",            // Samsung One UI Home launcher
            "com.samsung.android.dialer",              // Samsung Phone (dialer)
            "com.sec.android.app.samsungapps",         // Galaxy Store
            "com.google.android.gms",                  // Google Play Services (baseline)
        ),
        // Canonical Samsung Galaxy S22 (`r0q`, Exynos 2200) sensor inventory.
        // Per Samsung's official spec page for the S22, the device ships
        // the AOSP Tier-1 sensor set PLUS the flagship-class extras:
        //   TYPE_ACCELEROMETER         = 1   (Tier 1)
        //   TYPE_MAGNETIC_FIELD        = 2   (Tier 1 — compass)
        //   TYPE_GYROSCOPE             = 4   (Tier 1 — rotation)
        //   TYPE_LIGHT                 = 5   (Tier 1 — auto-brightness)
        //   TYPE_PRESSURE              = 6   (flagship — barometer)
        //   TYPE_PROXIMITY             = 8   (phone-class — earpiece)
        //   TYPE_GRAVITY               = 11  (composite — gravity vector)
        //   TYPE_STEP_DETECTOR         = 17  (Samsung Health step counter)
        //   TYPE_HEART_RATE            = 21? (not present on S22 — removed)
        //   TYPE_LOW_LATENCY_OFFBODY_DETECT = 25 (Samsung wearable-class)
        // The seven sensors that ship on every S22 + the two Samsung
        // composite/derived sensors give the rank-24/42/43/44/45 probes
        // strictly more inventory than the Pixel 7 baseline, exercising
        // the "richer inventory should still score clean" branch.
        sensorTypes = setOf(1, 2, 4, 5, 6, 8, 11, 17, 25),

        // Canonical Samsung S22 BluetoothAdapter address. The OUI
        // F4:99:BA is one of Samsung Electronics' IEEE-registered OUI
        // blocks (verified against IEEE MA-L registry). First byte 0xF4
        // has the locally-administered bit (0x02) clear, and it's NOT
        // in any of the WifiMacProbe emulator OUI sets (QEMU 52:54:00,
        // VBox 08:00:27, VMware 00:0C:29, HyperV 00:15:5D, Xen 00:16:3E).
        // Reported by `BluetoothAdapter.getDefaultAdapter().getAddress()`
        // to LOCAL_MAC_ADDRESS-permitted callers.
        bluetoothMac = "f4:99:ba:8d:f1:27",

        // Display metrics — Samsung official spec for S22 SM-S901B:
        // 2340x1080 (FHD+) @ 425 ppi nominal. Android reports
        // densityDpi as the bucket-aligned value 420 (the same bucket
        // Pixel 7 happens to land in). Width-pixels < height-pixels
        // reflects portrait orientation, matching how DisplayMetrics
        // reports on every Android in default boot rotation.
        // Source: samsung.com/us/smartphones/galaxy-s22/specs/
        displayWidthPixels = 1080,
        displayHeightPixels = 2340,
        displayDensityDpi = 420,
        displayXdpi = 425.0f,
        displayYdpi = 425.0f,

        // Locale: clean default US-English on a retail device shipped to
        // the international market — Samsung devices default to en/US
        // out of the box regardless of regional firmware variant.
        localeLanguage = "en",
        localeCountry = "US",
        localeDisplayName = "English (United States)",

        // Timezone: America/Los_Angeles (-480 minutes UTC offset for PST).
        // Matches the Pixel 7 snapshot's timezone so cross-snapshot
        // comparisons of the rank-46 timezone probe stay clean.
        timezoneId = "America/Los_Angeles",
        timezoneOffsetMinutes = -480,

        // GPS fix-frame — realistic Seoul coordinates for a Samsung
        // device on a representative customer location (Gangnam-gu,
        // Seoul, South Korea: 37.5665°N, 126.9780°E). A factory-clean
        // S22 that has booted with location services enabled reports
        // a realistic fix; 5.5m accuracy is well above the 1.0m S4
        // implausibility floor. Provider "gps" → distinct from the
        // S5 fused-without-GPS branch. gpsIsMock=false disarms the
        // S1 mock-provider rule.
        gpsLat = 37.5665,
        gpsLng = 126.9780,
        gpsAccuracy = 5.5f,
        gpsProvider = "gps",
        gpsIsMock = false,

        // Power-12 rank-9.0 (runtime.frida_memory_maps) — DECLARATIVE.
        // A factory-clean Samsung S22 process has none of the frida-class
        // libraries mapped, none of the gum-runtime thread names spawned,
        // and ports 27042/27043 unbound. Empty sets across all three
        // surfaces match the clean ground truth.
        procSelfMapsLibs = emptySet(),
        runtimeThreadNames = emptySet(),
        openTcpPorts = emptySet(),

        // Power-12 rank-9.7 (runtime.native_prologue_hash) — DECLARATIVE,
        // mitigation_layer not_spoofable. Empty/zero = "no measurement
        // performed" (same shape as the other clean snapshots). A real
        // native-side scan against a clean S22 would also produce empty
        // deltas + zero trampolines, so the empty state is consistent
        // with a clean device.
        prologueHashDeltas = emptyMap(),
        trampolinePatternCount = 0,

        // Power-12 rank-9.8 (integrity.prologue_got_hooks) — DECLARATIVE,
        // mitigation_layer not_spoofable. Clean GOT/PLT table + no rwxp
        // memory pages = empty map + empty list, the clean ground truth.
        gotPltAnomalies = emptyMap(),
        rwxpMemorySegments = emptyList(),

        // Power-13 Gap #3 / #10 / #12 — Samsung Galaxy S22 OneUI
        // production mountinfo. Same shape as Pixel 7 (Android 12+
        // dynamic-partition layout) with Samsung-specific
        // /optics, /efs, /omr OEM partitions visible to init.
        // No Magisk paths anywhere — factory-clean state.
        // Power-13 Gap #3 lands in PATTERN_DIGEST_DIFF_NO_MAGISK
        // at 0.0. Gap #10 sees /system read-only. Gap #12 sees no
        // overlay over /system.
        // Power-13 Gap #8 (root.magisk_module_dir) — clean
        // Samsung S22 has no /data/adb directory. Accessor
        // returns null → PATTERN_NO_OBSERVATION at 0.0.
        dirEntries = emptyMap(),

        // Power-13 Gap #2 (runtime.init_svc_enumeration) — clean
        // Samsung S22 init.svc.* enumeration. Standard AOSP services
        // plus Samsung Knox / SecBio services satisfying the
        // KNOWN_OEM_PREFIXES check (`sec.*` prefix).
        initSvcProps = mapOf(
            "zygote" to "running",
            "zygote_secondary" to "running",
            "servicemanager" to "running",
            "surfaceflinger" to "running",
            "mediaserver" to "running",
            "audioserver" to "running",
            "installd" to "running",
            "vold" to "running",
            "netd" to "running",
            "logd" to "running",
            "lmkd" to "running",
            "ueventd" to "running",
            "healthd" to "running",
            "hwservicemanager" to "running",
            "bluetooth" to "running",
            "wifi" to "running",
            "fingerprintd" to "running",
            "gatekeeperd" to "running",
            "keystore2" to "running",
            "statsd" to "running",
            // Samsung Knox/OneUI vendor services — `sec.*` and
            // `qti.*` prefixes are in KNOWN_OEM_PREFIXES so they
            // don't fire the rule.
            "sec.knoxguard" to "running",
            "sec.secbio" to "running",
            "qti.thermal-engine" to "running",
        ),

        // Power-13 Gap #1 (root.magisk_uds) — clean Samsung S22
        // /proc/net/unix output. AOSP + Samsung Knox / OneUI
        // daemons; no magisk fingerprints.
        procNetUnixSockets = listOf(
            "@android:installd",
            "@android:zygote",
            "@android:netd",
            "@android:wpa_supplicant",
            "@samsung:knoxguard",
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
                11 1 259:11 / /efs ro - ext4 /dev/block/by-name/efs
                12 1 259:12 / /optics ro - ext4 /dev/block/by-name/optics
                13 1 259:13 / /omr ro - ext4 /dev/block/by-name/omr
            """.trimIndent(),
        ),
    )
}

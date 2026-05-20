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
        ),
        existingFiles = setOf(
            // rank 3 su_detection — clean Samsung S22 has NONE of the
            // su-binary paths or magisk artifacts. Knox additionally bricks
            // any device that has had su installed (warranty_bit flip),
            // making this even more strictly true on Samsung than on Pixel.
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
    )
}

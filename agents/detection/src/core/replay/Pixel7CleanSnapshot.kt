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
        ),
        existingFiles = setOf(
            // rank 3 su_detection — clean Pixel 7 has NONE of the su-binary
            // paths or magisk artifacts. Empty set means every fileExists()
            // lookup returns false, which is the correct negative-class
            // answer (this is a stock device, no root toolchain installed).
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
    )
}

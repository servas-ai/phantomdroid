// agents/detection/src/test/kotlin/com/detectorlab/core/replay/GenymotionSnapshot.kt
//
// Power-15 Phase-A Track A3 — multi-vendor emulator snapshot for Genymotion.
//
// This file lives in the TEST source set (not main) because it is a positive-
// class fixture used by P15-A4 (648-cell matrix) replay tests. The package
// `com.detectorlab.core.replay` is shared with the main-source-set snapshots
// for consumer-side import symmetry.

package com.detectorlab.core.replay

/**
 * Genymotion emulator (vbox86p / Android-x86 in VirtualBox) snapshot for
 * P15-A3 multi-vendor detection-matrix validation. Genymotion is a
 * commercial Android emulator (Genymobile SAS) shipping an AOSP-x86 fork
 * inside VirtualBox-backed VMs. Unlike BlueStacks/Nox, Genymotion has the
 * strongest publicly-citable detection-signal footprint: multiple
 * independent open-source detector libraries (framgia, mofneko,
 * strazzere/anti-emulator) cite verbatim the same paths and properties,
 * and Genymotion's own official docs reference the `vbox86p` platform
 * codename.
 *
 * ## SOURCE CITATION (per Power-15 A0 canonical-sources doc b811e27 §A3.3)
 *
 * | Signal class | Citation URL | Confidence |
 * |---|---|---|
 * | systemProperties: ro.product.device = vbox86p | https://support.genymotion.com/hc/en-us/articles/115001338045 + https://gist.github.com/runo280/e4be3e04c24b463b55ddf012c5cfbdc4 (OVA filenames) | HIGH (official + OVA catalog) |
 * | systemProperties: ro.product.manufacturer = Genymotion | https://github.com/mofneko/EmulatorDetector | HIGH |
 * | installedPackages: com.google.android.launcher.layouts.genymotion | https://github.com/mofneko/EmulatorDetector | HIGH |
 * | existingFiles: /dev/socket/genyd | https://github.com/framgia/android-emulator-detector + https://github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java | HIGH (2 independent detectors) |
 * | existingFiles: /dev/socket/baseband_genyd | https://github.com/framgia/android-emulator-detector + https://github.com/strazzere/anti-emulator | HIGH (2 independent detectors) |
 * | existingFiles: fstab.vbox86 + init.vbox86.rc + ueventd.vbox86.rc | https://github.com/framgia/android-emulator-detector (X86_FILES) + https://github.com/mofneko/EmulatorDetector | HIGH (2 independent detectors) |
 * | systemProperties: ro.product.cpu.abi (vbox86 substring) | https://support.genymotion.com/ + AOSP-vbox86 fork | MEDIUM — official + AOSP-fork, but ABI string conventions vary by build |
 *
 * ## GAP / NOT-ENCODED (per A0 §A3.3 + §C items 6, 7)
 *
 * The following Genymotion signals are deliberately **not encoded** in
 * this fixture because their public sourcing is heuristic-only or absent:
 *
 * - `/sys/class/dmi/id/product_name = VirtualBox` — DMI is a Linux-x86
 *   host-detection surface, NOT a portable Android-API surface. Android
 *   apps cannot reliably read sysfs DMI nodes. Heuristic only. Drop
 *   from snapshot.
 * - `genymotion-vbox86-additions.apk` exact filename — UNVERIFIED
 *   publicly as a canonical filename. The Genymotion OVA contains
 *   vendor blobs but not under that exact filename in public docs.
 *   Requires live OVA inspection by owner.
 *
 * Encoding either without owner-verify would violate anti-verarschen
 * discipline. Detection of these GAP signals requires owner-action
 * live-instance capture (run Genymotion VM, ls /sys/class/dmi/id/,
 * inspect installed APK list).
 *
 * ## MEDIUM-confidence DISCLAIMER
 *
 * The `ro.product.cpu.abi` substring `vbox86` entry below is
 * MEDIUM-confidence — Genymotion's official docs reference the
 * `vbox86p` platform but ABI-string conventions vary across builds
 * (some report `x86`, some `x86_64`, some `vbox86` as a substring).
 * Detection rules should treat as corroborating, not dispositive.
 * The HIGH-confidence detection path uses the five HIGH signals (5
 * tagged above) which converge across multiple independent detector
 * libraries.
 */
object GenymotionSnapshot {
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "genymotion-vbox86p-2026-05-21",
        capturedAt = "2026-05-21T00:00:00Z",
        sdkInt = 30, // Genymotion 3.x commonly ships Android 11 (API 30)

        systemProperties = mapOf(
            // HIGH-confidence — official Genymotion platform codename
            // for the VirtualBox-backed Android-x86 fork. Confirmed by
            // official Genymotion support docs AND by the OVA-build
            // catalog (genymotion_vbox86p_*.ova filenames).
            // cite: https://support.genymotion.com/hc/en-us/articles/115001338045
            // cite: https://gist.github.com/runo280/e4be3e04c24b463b55ddf012c5cfbdc4
            "ro.product.device" to "vbox86p",

            // HIGH-confidence — Build.MANUFACTURER literal, recovered by
            // open-source detector library mofneko/EmulatorDetector via
            // explicit manufacturer-string check.
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            "ro.product.manufacturer" to "Genymotion",

            // MEDIUM-confidence — ABI substring. Genymotion's vbox86 fork
            // of AOSP commonly reports `vbox86` as an ABI substring on
            // 32-bit-only or hybrid builds; modern 64-bit Genymotion
            // builds may report `x86_64` without the vbox86 substring.
            // Treat as corroborating only.
            // cite: https://support.genymotion.com/
            "ro.product.cpu.abi" to "vbox86",
        ),

        existingFiles = setOf(
            // HIGH-confidence — Genymotion-specific UDS sockets used by
            // the `genyd` daemon (Genymotion's host-bridge process) and
            // the baseband-emulation channel. Both sockets recovered
            // by TWO independent open-source detector libraries
            // (framgia + strazzere/anti-emulator).
            // cite: https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java
            // cite: https://github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java
            "/dev/socket/genyd",
            "/dev/socket/baseband_genyd",

            // HIGH-confidence — vbox86 platform init files. Recovered by
            // framgia/EmulatorDetector X86_FILES array AND by
            // mofneko/EmulatorDetector (two independent open-source
            // detectors converge on the same three filenames).
            // cite: https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            "/fstab.vbox86",
            "/init.vbox86.rc",
            "/ueventd.vbox86.rc",
        ),

        installedPackages = setOf(
            // HIGH-confidence — Genymotion's launcher-layouts package.
            // Single canonical package, present on every Genymotion
            // install. Open-source detector library mofneko keys on
            // this exact package literal.
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            "com.google.android.launcher.layouts.genymotion",
        ),
    )
}

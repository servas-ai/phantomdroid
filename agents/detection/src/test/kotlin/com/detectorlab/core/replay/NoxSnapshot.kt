// agents/detection/src/test/kotlin/com/detectorlab/core/replay/NoxSnapshot.kt
//
// Power-15 Phase-A Track A3 — multi-vendor emulator snapshot for Nox.
//
// This file lives in the TEST source set (not main) because it is a positive-
// class fixture used by P15-A4 (648-cell matrix) replay tests. The package
// `com.detectorlab.core.replay` is shared with the main-source-set snapshots
// (Pixel7CleanSnapshot, RedroidV12Snapshot etc.) so test consumers can swap
// fixtures with a one-line import change.

package com.detectorlab.core.replay

/**
 * Nox emulator (vbox86 / Android-x86) snapshot for P15-A3 multi-vendor
 * detection-matrix validation. Nox is a closed-source commercial Android
 * emulator developed by BigNox (Hong Kong), shipping a customised
 * Android-x86 image inside an embedded VirtualBox VM. Its
 * detection-relevant signal surface mixes HIGH-confidence file-path
 * artefacts (init.nox.rc / fstab.nox / ueventd.nox.rc — recovered by
 * independent open-source detector libraries) with MEDIUM-confidence
 * build-prop substrings (blog-cited only, no public firmware decomp).
 *
 * ## SOURCE CITATION (per Power-15 A0 canonical-sources doc b811e27 §A3.1)
 *
 * | Signal class | Citation URL | Confidence |
 * |---|---|---|
 * | existingFiles: fstab.nox | https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java (NOX_FILES) | HIGH |
 * | existingFiles: init.nox.rc | https://github.com/framgia/android-emulator-detector + https://github.com/mofneko/EmulatorDetector | HIGH (2 independent detectors) |
 * | existingFiles: ueventd.nox.rc | https://github.com/framgia/android-emulator-detector + https://github.com/mofneko/EmulatorDetector | HIGH (2 independent detectors) |
 * | existingFiles: /dev/qemu_pipe | https://github.com/framgia/android-emulator-detector (QEMU_DRIVERS) | HIGH (QEMU surface; Nox-binding inferential) |
 * | installedPackages: com.nox.mopen.app | https://github.com/mofneko/EmulatorDetector (PACKAGE_NAMES) | HIGH |
 * | systemProperties: ro.product.board / ro.product.hardware substring "nox" | https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e + https://xdaforums.com/t/nox-emulator-is-getting-detected.4708108/ | MEDIUM — blog-cited, multi-source convergent |
 *
 * ## GAP / NOT-ENCODED (per A0 §A3.1 + §C item 5)
 *
 * The following Nox signals are deliberately **not encoded** in this
 * fixture because their public sourcing is blog-only or absent:
 *
 * - `ro.product.manufacturer = alps` (Nox masquerading as MediaTek-alps
 *   OEM) — XDA-forum-only citation, no firmware decomp available.
 *   Encoding a fabricated literal would violate anti-verarschen
 *   discipline. Recovery requires owner-action live-instance capture.
 * - `Build.PRODUCT = nox` exact literal — blog-only (rayChong medium
 *   article); encoded as MEDIUM via `ro.product.name` substring only.
 *
 * ## MEDIUM-confidence DISCLAIMER
 *
 * The `systemProperties` entries below for `ro.product.board`,
 * `ro.product.name`, and `ro.hardware` are MEDIUM-confidence
 * (blog-cited only, no firmware decomp). Detection rules consuming
 * them should treat as corroborating evidence, NOT dispositive.
 * The HIGH-confidence detection path uses the file-existence triple
 * (fstab.nox + init.nox.rc + ueventd.nox.rc) plus the launcher
 * package (com.nox.mopen.app).
 */
object NoxSnapshot {
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "nox-vbox86-2026-05-21",
        capturedAt = "2026-05-21T00:00:00Z",
        sdkInt = 25, // Nox historically pinned to Android 7.1 (API 25) for compat

        systemProperties = mapOf(
            // MEDIUM-confidence — blog-cited only. Multi-source convergent
            // across rayChong + XDA-forum threads, but no public firmware
            // decomp. Treat as corroborating, NOT dispositive.
            // cite: https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e
            // cite: https://xdaforums.com/t/nox-emulator-is-getting-detected.4708108/
            "ro.product.board" to "nox",
            "ro.hardware" to "nox",
            "ro.product.name" to "nox",
            // Note: ro.product.manufacturer intentionally OMITTED. The "alps"
            // value claimed by XDA threads is LOW-confidence blog-only; no
            // canonical Nox firmware decomp publicly available. See KDoc-GAP
            // section above. Encoding a fabricated literal would violate
            // anti-verarschen discipline.
        ),

        existingFiles = setOf(
            // HIGH-confidence — file paths recovered by two independent
            // open-source emulator-detector libraries (framgia + mofneko).
            // These are the primary detection signals for Nox.
            // cite: https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            "/system/etc/init/init.nox.rc",
            "/ueventd.nox.rc",
            "/fstab.nox",

            // HIGH-confidence — QEMU pipe surface. Nox runs Android-x86
            // inside QEMU/VirtualBox and exposes the QEMU host-bridge pipe
            // at this path. NOT Nox-specific (any QEMU-backed emulator
            // exposes it) but a load-bearing positive signal for the
            // composite Nox-detection rule.
            // cite: https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java
            "/dev/qemu_pipe",
        ),

        installedPackages = setOf(
            // HIGH-confidence — Nox launcher app package. Single canonical
            // package, present on every Nox install for launching apps.
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            "com.nox.mopen.app",
        ),

        mountInfo = mapOf(
            // HIGH-confidence corroboration of the QEMU surface — Nox
            // exposes /dev/qemu_pipe to userland because it runs on a
            // QEMU-backed VirtualBox host. The mountinfo entry encodes
            // the canonical QEMU-pipe driver mount under /dev.
            // cite: https://github.com/framgia/android-emulator-detector (QEMU_DRIVERS)
            "self" to """
                1 0 8:1 / / ro - ext4 /dev/block/sda1
                2 1 0:5 / /sys ro - sysfs sysfs
                3 1 0:6 / /proc ro - proc proc
                4 1 0:7 / /dev rw - tmpfs tmpfs
                5 4 0:8 /qemu_pipe /dev/qemu_pipe rw - qemu_pipe none
            """.trimIndent(),
        ),
    )
}

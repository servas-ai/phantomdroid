// agents/detection/src/test/kotlin/com/detectorlab/core/replay/BlueStacksSnapshot.kt
//
// Power-15 Phase-A Track A3 — multi-vendor emulator snapshot for BlueStacks.
//
// This file lives in the TEST source set (not main) because it is a positive-
// class fixture used by P15-A4 (648-cell matrix) replay tests. The package
// `com.detectorlab.core.replay` is shared with the main-source-set snapshots
// for consumer-side import symmetry.

package com.detectorlab.core.replay

/**
 * BlueStacks emulator snapshot for P15-A3 multi-vendor detection-matrix
 * validation. BlueStacks is a commercial closed-source Android emulator
 * (BlueStack Systems, Inc.) running a heavily-customised Android-x86 image
 * inside its own Hyper-V-or-VirtualBox-backed virtualization layer.
 *
 * ## PUBLIC-UNVERIFIABLE — INTENTIONALLY MINIMAL
 *
 * BlueStacks is closed-source commercial. Only `com.bluestacks.*` package
 * prefix is HIGH-confidence. Property/file signals are LOW/GAP per
 * Power-15 A0 §A3.2 — requires owner-verify on live BlueStacks instance.
 * This fixture intentionally minimal. (Matching Power-14 §1ter convention
 * for the "public-unverifiable" disclaimer applied to closed-source
 * commercial emulator targets.)
 *
 * ## SOURCE CITATION (per Power-15 A0 canonical-sources doc b811e27 §A3.2)
 *
 * | Signal class | Citation URL | Confidence |
 * |---|---|---|
 * | installedPackages: com.bluestacks.appmart | https://github.com/framgia/android-emulator-detector/issues/35 (community comments) + https://github.com/mofneko/EmulatorDetector (`com.bluestacks.` prefix) | HIGH (prefix), MEDIUM (specific suffix) |
 * | installedPackages: com.bluestacks.BstCommandProcessor | https://github.com/framgia/android-emulator-detector/issues/35 | MEDIUM (issue-thread but not in detector code) |
 * | installedPackages: com.bluestacks.help | https://github.com/framgia/android-emulator-detector/issues/35 | MEDIUM |
 * | installedPackages: com.bluestacks.home | https://github.com/framgia/android-emulator-detector/issues/35 | MEDIUM |
 *
 * The `com.bluestacks.` package *prefix* itself is HIGH-confidence via
 * `mofneko/EmulatorDetector`. The four specific suffix packages above are
 * issue-thread-cited only. Detection rules should key on the prefix; the
 * specific suffixes here are illustrative payload to make the prefix
 * match concrete.
 *
 * ## GAP / NOT-ENCODED (per A0 §A3.2 + §C items 3, 4)
 *
 * The following BlueStacks signals are deliberately **not encoded** in
 * this fixture because their public sourcing is blog-only or absent:
 *
 * - `libBstHwHelper.so` library file — claimed in user-question but NO
 *   public source found. Public-unverifiable, blog/heuristic only. DO
 *   NOT use without owner-verify on live BlueStacks decomp.
 * - `ro.product.model = BlueStacks` literal — claimed in build.prop
 *   modification threads; no canonical decomp. UNVERIFIED publicly.
 * - `ro.bluestacks.version` property — claimed but no canonical source
 *   found. UNVERIFIED publicly.
 * - `/data/.bluestacks.prop` file — single-issue-thread citation, LOW
 *   confidence. Needs owner-verify.
 * - `/data/.bstconf.prop` file — blog-only. LOW confidence.
 * - `/boot/bstsetup.env` file — single B4X-forum source. LOW confidence.
 *
 * Encoding any of these without owner-verify would violate
 * anti-verarschen discipline. The minimal fixture below is the honest
 * publicly-verifiable answer.
 */
object BlueStacksSnapshot {
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "bluestacks-x86_64-2026-05-21-minimal-public-only",
        capturedAt = "2026-05-21T00:00:00Z",
        sdkInt = 25, // BlueStacks historically pinned to Android 7.1 (API 25)

        installedPackages = setOf(
            // HIGH-confidence prefix match (`com.bluestacks.*`) per mofneko
            // detector; the four specific suffixes below are MEDIUM
            // (community-issue-thread citations only, not in canonical
            // detector source). Each entry matches the `com.bluestacks.`
            // prefix, which is the load-bearing detection signal.
            // cite: https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java
            // cite: https://github.com/framgia/android-emulator-detector/issues/35
            "com.bluestacks.appmart",
            "com.bluestacks.BstCommandProcessor",
            "com.bluestacks.help",
            "com.bluestacks.home",
        ),
    )
}

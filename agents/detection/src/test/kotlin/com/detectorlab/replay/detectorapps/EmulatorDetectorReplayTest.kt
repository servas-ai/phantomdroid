// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/EmulatorDetectorReplayTest.kt
//
// Power-13 Task #4 — Composite EmulatorDetector (strazzere/anti-
// emulator + mofneko/EmulatorDetector + CalebFenton/
// AndroidEmulatorDetect) replay.
//
// Encodes the FindEmulator.java composite-check decision rule
// against raw ProbeContext signals. Source:
//   - github.com/strazzere/anti-emulator (FindEmulator.java)
//   - github.com/mofneko/EmulatorDetector
//   - github.com/CalebFenton/AndroidEmulatorDetect
//
// The composite rule combines 8 OR-gated checks:
//
//   1. Build.FINGERPRINT starts with "generic" / contains
//      "sdk_google" / "emulator" / "goldfish" / "ranchu" / "redroid"
//   2. Build.MODEL contains "google_sdk" / "Emulator" / "Android SDK
//      built for"
//   3. Build.BRAND in {"generic", "android", "generic_arm64",
//      "generic_x86"}
//   4. Build.MANUFACTURER == "Genymotion" OR "unknown"
//   5. Build.PRODUCT in {"sdk_x86_64", "vbox86p", "google_sdk"} OR
//      "Andy", "ttvm"
//   6. QEMU device nodes (/dev/qemu_pipe, /dev/socket/qemud)
//   7. Telephony emulator-phone block (15555215554..15555215584) OR
//      operator name "Android" OR IMSI 310260000000000
//   8. Third-party emulator init.rc files (Nox/Andy/MEmu/BlueStacks/
//      Droid4x init scripts)
//
// Decision: ANY check fires → emulator detected.
//
// Acceptance:
//   - Pixel7Clean / SamsungS22Clean → NOT detected (sanity)
//   - RedroidV12                    → DETECTED (multiple checks)
//   - RedroidSpoofed                → NOT detected (spoof fixture
//                                     scrubs every check)

package com.detectorlab.replay.detectorapps

import com.detectorlab.core.ProbeContext
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorDetectorReplayTest {

    private val pixel7 = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
    private val samsung = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)
    private val redroidDirty = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
    private val redroidSpoofed = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    // ── Decision rule ────────────────────────────────────────────────────────

    private fun emulatorDetected(ctx: ProbeContext): Boolean {
        return fingerprintLooksEmulator(ctx) ||
            modelLooksEmulator(ctx) ||
            brandLooksEmulator(ctx) ||
            manufacturerLooksEmulator(ctx) ||
            productLooksEmulator(ctx) ||
            qemuDeviceNodesPresent(ctx) ||
            emulatorTelephonyMarkers(ctx) ||
            thirdPartyEmulatorInitFiles(ctx)
    }

    /** strazzere FindEmulator.java FINGERPRINT check. */
    private fun fingerprintLooksEmulator(ctx: ProbeContext): Boolean {
        val fp = ctx.getSystemProperty("ro.build.fingerprint") ?: return false
        val lower = fp.lowercase()
        return fp.startsWith("generic") ||
            "sdk_google" in lower ||
            "emulator" in lower ||
            "goldfish" in lower ||
            "ranchu" in lower ||
            "redroid" in lower
    }

    /** strazzere MODEL check. */
    private fun modelLooksEmulator(ctx: ProbeContext): Boolean {
        val model = ctx.getSystemProperty("ro.product.model") ?: return false
        val lower = model.lowercase()
        return "google_sdk" in lower ||
            "emulator" in lower ||
            "android sdk built for" in lower ||
            "redroid" in lower ||
            "droid4x" in lower
    }

    /** strazzere BRAND check. */
    private fun brandLooksEmulator(ctx: ProbeContext): Boolean {
        val brand = ctx.getSystemProperty("ro.product.brand")?.lowercase() ?: return false
        return brand in setOf("generic", "android", "generic_arm64", "generic_x86", "generic_x86_64", "redroid")
    }

    /** strazzere MANUFACTURER check. */
    private fun manufacturerLooksEmulator(ctx: ProbeContext): Boolean {
        val mfr = ctx.getSystemProperty("ro.product.manufacturer")?.lowercase() ?: return false
        return mfr == "genymotion" || mfr == "unknown" || mfr == "redroid" || mfr == "itoolsavm"
    }

    /** strazzere PRODUCT check. */
    private fun productLooksEmulator(ctx: ProbeContext): Boolean {
        val product = ctx.getSystemProperty("ro.product.name")?.lowercase() ?: return false
        return product in setOf("sdk_x86_64", "vbox86p", "google_sdk") ||
            "andy" in product ||
            "ttvm" in product ||
            "redroid" in product
    }

    /** QEMU device-node check. */
    private fun qemuDeviceNodesPresent(ctx: ProbeContext): Boolean {
        val paths = listOf(
            "/dev/qemu_pipe",
            "/dev/qemu_trace",
            "/dev/socket/qemud",
            "/dev/socket/baseband_genyd",
        )
        return paths.any { ctx.fileExists(it) }
    }

    /** Power-13 telephony markers (EmulatorDetector + strazzere KNOWN_NUMBERS). */
    private fun emulatorTelephonyMarkers(ctx: ProbeContext): Boolean {
        val line1 = ctx.queryTelephonyManager(TelephonyField.LINE1_NUMBER)
        val imsi = ctx.queryTelephonyManager(TelephonyField.SUBSCRIBER_ID)
        val opName = ctx.queryTelephonyManager(TelephonyField.OPERATOR_NAME)?.lowercase()
        val emulatorPhones = setOf(
            "15555215554", "15555215556", "15555215558", "15555215560",
            "15555215562", "15555215564", "15555215566", "15555215568",
            "15555215570", "15555215572", "15555215574", "15555215576",
            "15555215578", "15555215580", "15555215582", "15555215584",
        )
        if (line1 != null && line1 in emulatorPhones) return true
        if (imsi == "310260000000000") return true
        if (opName == "android") return true
        return false
    }

    /** Power-13 Gap #4 third-party emulator init files. */
    private fun thirdPartyEmulatorInitFiles(ctx: ProbeContext): Boolean {
        val paths = listOf(
            "/init.nox.rc",
            "/init.andy.rc",
            "/fstab.andy",
            "/init.ttVM_x86.rc",
            "/init.bluestacks.rc",
            "/init.droid4x.rc",
            "/ueventd.android_x86.rc",
            "/x86.prop",
        )
        return paths.any { ctx.fileExists(it) }
    }

    // ── Per-snapshot assertions ──────────────────────────────────────────────

    @Test
    fun `Pixel 7 clean — NOT detected (sanity)`() {
        assertFalse(emulatorDetected(pixel7))
    }

    @Test
    fun `Samsung S22 clean — NOT detected (sanity)`() {
        assertFalse(emulatorDetected(samsung))
    }

    @Test
    fun `RedroidV12 dirty — DETECTED (multiple checks fire)`() {
        assertTrue(emulatorDetected(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed — NOT detected (spoof fixture passes every check)`() {
        assertFalse(
            emulatorDetected(redroidSpoofed),
            "RedroidSpoofed must pass EmulatorDetector.detected() = false",
        )
    }

    // ── Per-check documentation ──────────────────────────────────────────────

    @Test
    fun `RedroidV12 fingerprintLooksEmulator fires (redroid substring)`() {
        assertTrue(fingerprintLooksEmulator(redroidDirty))
    }

    @Test
    fun `RedroidV12 modelLooksEmulator fires (redroid model)`() {
        assertTrue(modelLooksEmulator(redroidDirty))
    }

    @Test
    fun `RedroidV12 brandLooksEmulator fires (redroid brand)`() {
        assertTrue(brandLooksEmulator(redroidDirty))
    }

    @Test
    fun `RedroidV12 manufacturerLooksEmulator fires (redroid manufacturer)`() {
        assertTrue(manufacturerLooksEmulator(redroidDirty))
    }

    @Test
    fun `RedroidV12 emulatorTelephonyMarkers fires (Line1 in AOSP block)`() {
        assertTrue(emulatorTelephonyMarkers(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed fingerprintLooksEmulator SCRUBBED`() {
        assertFalse(fingerprintLooksEmulator(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed modelLooksEmulator SCRUBBED`() {
        assertFalse(modelLooksEmulator(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed brandLooksEmulator SCRUBBED`() {
        assertFalse(brandLooksEmulator(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed manufacturerLooksEmulator SCRUBBED`() {
        assertFalse(manufacturerLooksEmulator(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed emulatorTelephonyMarkers SCRUBBED (T-Mobile spoof, no Line1 leak)`() {
        assertFalse(emulatorTelephonyMarkers(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed qemuDeviceNodesPresent SCRUBBED`() {
        assertFalse(qemuDeviceNodesPresent(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed thirdPartyEmulatorInitFiles SCRUBBED`() {
        assertFalse(thirdPartyEmulatorInitFiles(redroidSpoofed))
    }
}

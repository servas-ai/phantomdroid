// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/PlayIntegrityReplayTest.kt
//
// Power-13 Task #4 — Play Integrity verdict predictor replay.
//
// Encodes the offline-predictable portion of Play Integrity's
// `deviceIntegrity.deviceRecognitionVerdict` decision against raw
// ProbeContext signals. Source:
// developer.android.com/google/play/integrity/verdicts (2025
// fields) + the legacy SafetyNet ctsProfileMatch/basicIntegrity
// signal surface that Play Integrity inherits.
//
// **Important honesty caveat**: the FINAL Play Integrity verdict
// requires the Google Play Services IPC + hardware-backed
// StrongBox attestation chain — neither of which is observable
// from an offline JVM probe. What this test simulates is the
// PREDICTABLE-FROM-BUILDPROP portion: a real device that fails
// the predictable signals is GUARANTEED to fail the full Play
// Integrity check (StrongBox can only ADD constraints, never
// relax them). The reverse is not true: passing the predictable
// signals does NOT guarantee passing the full check (StrongBox
// hardware-backed attestation can reject even a buildprop-clean
// device).
//
// Predictable signals (all CRITICAL for Play Integrity):
//   1. ro.build.tags == "release-keys"
//   2. ro.build.type == "user"
//   3. ro.debuggable != "1"
//   4. ro.secure != "0"
//   5. ro.boot.verifiedbootstate == "green"
//   6. ro.boot.flash.locked == "1"
//   7. Build.FINGERPRINT does NOT start with "generic" / contain
//      emulator markers
//   8. No Magisk-runtime artifacts (rank-3 su_search proxies)
//
// Verdict prediction:
//   - ALL 8 signals pass     → MEETS_DEVICE_INTEGRITY (clean)
//   - ANY signal fails       → device fails Play Integrity
//
// Acceptance:
//   - Pixel7Clean / SamsungS22Clean → MEETS_DEVICE_INTEGRITY
//   - RedroidV12                    → FAILS (multiple signals)
//   - RedroidSpoofed                → MEETS_DEVICE_INTEGRITY
//                                     (per buildprop only — the
//                                     real Play Integrity API
//                                     would STILL fail due to
//                                     StrongBox attestation
//                                     absence, documented below)

package com.detectorlab.replay.detectorapps

import com.detectorlab.core.ProbeContext
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayIntegrityReplayTest {

    private val pixel7 = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
    private val samsung = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)
    private val redroidDirty = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
    private val redroidSpoofed = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    // ── Verdict ──────────────────────────────────────────────────────────────

    enum class PlayIntegrityVerdict {
        MEETS_DEVICE_INTEGRITY,   // all predictable signals clean
        FAILS_BUILDPROP_CHECK,    // at least one predictable signal fails
    }

    // ── Decision rule ────────────────────────────────────────────────────────

    private fun predictVerdict(ctx: ProbeContext): PlayIntegrityVerdict {
        if (failsTagsAndType(ctx)) return PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK
        if (failsDangerousProps(ctx)) return PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK
        if (failsVerifiedBoot(ctx)) return PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK
        if (failsFingerprint(ctx)) return PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK
        if (failsMagiskFsArtifacts(ctx)) return PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK
        return PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY
    }

    private fun failsTagsAndType(ctx: ProbeContext): Boolean {
        val tags = ctx.getSystemProperty("ro.build.tags")
        val type = ctx.getSystemProperty("ro.build.type")
        return tags != "release-keys" || (type != null && type != "user")
    }

    private fun failsDangerousProps(ctx: ProbeContext): Boolean {
        val debuggable = ctx.getSystemProperty("ro.debuggable")
        val secure = ctx.getSystemProperty("ro.secure")
        return debuggable == "1" || secure == "0"
    }

    private fun failsVerifiedBoot(ctx: ProbeContext): Boolean {
        val vbState = ctx.getSystemProperty("ro.boot.verifiedbootstate")
        val flashLocked = ctx.getSystemProperty("ro.boot.flash.locked")
        // null is treated as "unobserved" — does NOT fail the check
        // (covered ground because clean Pixel 7 captures may omit
        // these from systemProperties). Only explicit non-green /
        // non-1 values fail.
        if (vbState != null && vbState != "green") return true
        if (flashLocked != null && flashLocked != "1") return true
        return false
    }

    private fun failsFingerprint(ctx: ProbeContext): Boolean {
        val fp = ctx.getSystemProperty("ro.build.fingerprint") ?: return false
        return fp.startsWith("generic") ||
            "sdk_google" in fp ||
            "emulator" in fp.lowercase() ||
            "redroid" in fp.lowercase() ||
            "goldfish" in fp.lowercase() ||
            "ranchu" in fp.lowercase()
    }

    private fun failsMagiskFsArtifacts(ctx: ProbeContext): Boolean {
        val paths = listOf("/sbin/.magisk", "/data/adb/magisk", "/system/bin/su")
        return paths.any { ctx.fileExists(it) }
    }

    // ── Per-snapshot assertions ──────────────────────────────────────────────

    @Test
    fun `Pixel 7 clean — MEETS_DEVICE_INTEGRITY`() {
        assertEquals(PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY, predictVerdict(pixel7))
    }

    @Test
    fun `Samsung S22 clean — MEETS_DEVICE_INTEGRITY`() {
        assertEquals(PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY, predictVerdict(samsung))
    }

    @Test
    fun `RedroidV12 dirty — FAILS (multiple signals)`() {
        assertEquals(PlayIntegrityVerdict.FAILS_BUILDPROP_CHECK, predictVerdict(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed — MEETS_DEVICE_INTEGRITY (buildprop-spoof passes — StrongBox would still fail)`() {
        // Honest documentation: the spoof stack passes the
        // BUILDPROP-PREDICTABLE portion of the Play Integrity
        // verdict. The REAL API call would still fail because:
        //   - StrongBox attestation chain absent on Redroid
        //   - Hardware-backed key-attestation not synthesizable
        //   - rank-6 KeystoreAttestationProbe documents this as
        //     "not_spoofable" — the spoof has a HARD CEILING here.
        assertEquals(PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY, predictVerdict(redroidSpoofed))
    }

    // ── Per-check documentation ──────────────────────────────────────────────

    @Test
    fun `RedroidV12 fails tags-and-type check`() {
        assertTrue(failsTagsAndType(redroidDirty))
    }

    @Test
    fun `RedroidV12 fails dangerous-props check (debuggable=1 OR secure=0)`() {
        assertTrue(failsDangerousProps(redroidDirty))
    }

    @Test
    fun `RedroidV12 fails fingerprint check (redroid substring)`() {
        assertTrue(failsFingerprint(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed passes tags-and-type check`() {
        assertFalse(failsTagsAndType(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed passes dangerous-props check`() {
        assertFalse(failsDangerousProps(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed passes fingerprint check (Pixel-shape)`() {
        assertFalse(failsFingerprint(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed passes verified-boot check`() {
        assertFalse(failsVerifiedBoot(redroidSpoofed))
    }
}

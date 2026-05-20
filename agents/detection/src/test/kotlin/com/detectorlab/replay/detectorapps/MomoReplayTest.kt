// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/MomoReplayTest.kt
//
// Power-13 Task #4 — Momo (HuskyDG-reverse-engineered) replay.
//
// Encodes Momo's 5 known Magisk-detection signals against raw
// ProbeContext accessors. Source: HuskyDG
// (huskydg.github.io/blog/detect_magisk_xposed) + canyie/Riru-
// MomoHider whitelist analysis.
//
// Momo's decision rule is "ANY of N signals fires → Magisk detected":
//
//   1. mount-namespace mismatch (init has magisk paths, self doesn't)
//   2. /data/adb/modules directory exists (Magisk module install)
//   3. /sbin/.magisk OR /data/adb/magisk runtime artifacts
//   4. overlayfs mounted at /system (Magisk-on-A11+)
//   5. randomized init.svc.* services (Magisk pre-init injection)
//
// Acceptance:
//   - Pixel7Clean      → NOT detected (sanity)
//   - SamsungS22Clean  → NOT detected (sanity)
//   - RedroidV12       → DETECTED (every signal fires)
//   - RedroidSpoofed   → NOT detected (Shamiko spoof masks all)

package com.detectorlab.replay.detectorapps

import com.detectorlab.core.ProbeContext
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomoReplayTest {

    private val pixel7 = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
    private val samsung = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)
    private val redroidDirty = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
    private val redroidSpoofed = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    // ── Magisk fingerprint substrings shared across checks ───────────────────

    private val magiskSubstrings = listOf(
        "/sbin/.magisk",
        "/data/adb",
        ".magisk",
        "magisk_tmp",
        "/sbin/magisk",
    )

    // ── Decision rule ────────────────────────────────────────────────────────

    private fun magiskDetected(ctx: ProbeContext): Boolean {
        return mountNamespaceMismatch(ctx) ||
            magiskModuleDirPresent(ctx) ||
            magiskFilesystemArtifactsPresent(ctx) ||
            overlayfsOnSystem(ctx) ||
            randomizedInitSvc(ctx)
    }

    /** Signal 1 — magisk-fingerprint substring in init mountinfo but NOT self. */
    private fun mountNamespaceMismatch(ctx: ProbeContext): Boolean {
        val self = ctx.queryMountInfo("self") ?: return false
        val init = ctx.queryMountInfo("1") ?: return false
        val initHasMagisk = magiskSubstrings.any { it in init }
        val selfHasMagisk = magiskSubstrings.any { it in self }
        return initHasMagisk && !selfHasMagisk
    }

    /** Signal 2 — /data/adb/modules exists (directory listing non-null). */
    private fun magiskModuleDirPresent(ctx: ProbeContext): Boolean {
        return ctx.queryDirEntries("/data/adb/modules") != null
    }

    /** Signal 3 — Magisk filesystem artifact paths exist. */
    private fun magiskFilesystemArtifactsPresent(ctx: ProbeContext): Boolean {
        val paths = listOf("/sbin/.magisk", "/data/adb/magisk", "/cache/magisk.log")
        return paths.any { ctx.fileExists(it) }
    }

    /** Signal 4 — overlayfs mounted at /system or /. */
    private fun overlayfsOnSystem(ctx: ProbeContext): Boolean {
        val mountInfo = ctx.queryMountInfo("self") ?: return false
        for (line in mountInfo.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) continue
            val mountPoint = parts[4]
            if (mountPoint != "/system" && mountPoint != "/") continue
            val dashIdx = parts.indexOf("-")
            if (dashIdx < 0 || dashIdx + 1 >= parts.size) continue
            if (parts[dashIdx + 1] == "overlay") return true
        }
        return false
    }

    /** Signal 5 — init.svc.* has random-shape (hex-only) names not in AOSP baseline. */
    private fun randomizedInitSvc(ctx: ProbeContext): Boolean {
        val svc = ctx.queryInitSvcProps()
        if (svc.isEmpty()) return false
        // Heuristic: any service name that is 6+ chars hex-only is
        // Magisk's randomized injection. HuskyDG documents this
        // as the dispositive structural signal.
        val hexOnly = Regex("^[a-f0-9]{6,}$")
        return svc.keys.any { hexOnly.matches(it.lowercase()) }
    }

    // ── Per-snapshot assertions ──────────────────────────────────────────────

    @Test
    fun `Pixel 7 clean — NOT detected (sanity)`() {
        assertFalse(magiskDetected(pixel7))
    }

    @Test
    fun `Samsung S22 clean — NOT detected (sanity)`() {
        assertFalse(magiskDetected(samsung))
    }

    @Test
    fun `RedroidV12 dirty — DETECTED (un-spoofed Magisk-Redroid)`() {
        assertTrue(magiskDetected(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed — NOT detected (Shamiko-class masks every Momo signal)`() {
        assertFalse(
            magiskDetected(redroidSpoofed),
            "RedroidSpoofed must pass Momo.magiskDetected() = false",
        )
    }

    // ── Per-check documentation tests ────────────────────────────────────────

    @Test
    fun `RedroidV12 mountNamespaceMismatch fires (init has magisk paths)`() {
        assertTrue(mountNamespaceMismatch(redroidDirty))
    }

    @Test
    fun `RedroidV12 magiskModuleDirPresent fires (queryDirEntries non-null)`() {
        assertTrue(magiskModuleDirPresent(redroidDirty))
    }

    @Test
    fun `RedroidV12 overlayfsOnSystem fires (Redroid uses Docker overlay2)`() {
        assertTrue(overlayfsOnSystem(redroidDirty))
    }

    @Test
    fun `RedroidV12 randomizedInitSvc fires (hex-only service names)`() {
        assertTrue(randomizedInitSvc(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed mountNamespaceMismatch SCRUBBED`() {
        assertFalse(mountNamespaceMismatch(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed magiskModuleDirPresent SCRUBBED (queryDirEntries returns null)`() {
        assertFalse(magiskModuleDirPresent(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed overlayfsOnSystem SCRUBBED (mountinfo serves Pixel-shape ext4)`() {
        assertFalse(overlayfsOnSystem(redroidSpoofed))
    }

    @Test
    fun `RedroidSpoofed randomizedInitSvc SCRUBBED (init_svc filtered to AOSP-clean)`() {
        assertFalse(randomizedInitSvc(redroidSpoofed))
    }
}

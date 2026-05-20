// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/MasterCompositeDetectorReplayTest.kt
//
// Power-17 Task #47 P17-C1 — Master-composite detector OR-union replay.
//
// Composes the six (6) sibling detector replay decision-rules into a single
// `anyDetectorFires(ctx)` predicate and asserts the per-snapshot expected
// verdict across the full 8-snapshot validation matrix. The composite is the
// dispositive "device-attestation" question that production callers ask:
// "does ANY of our shipping detector families trip on this device?"
//
// Component detectors (all decision-rules VERBATIM from the sibling tests):
//   1. RootBeer 9-branch isRooted() — RootBeerReplayTest.kt (Power-14 AAR-aligned)
//   2. Momo (HuskyDG) 5-signal magiskDetected() — MomoReplayTest.kt
//   3. FridaDetector 3-check UNION fridaDetected() — FridaDetectorReplayTest.kt
//   4. Play Integrity 5-check predictVerdict() — PlayIntegrityReplayTest.kt
//   5. EmulatorDetector 8-check emulatorDetected() — EmulatorDetectorReplayTest.kt
//   6. freeRASP T5 install-source — IntegrityInstallSourceProbe.kt (probe rank 10.5)
//
// Decision rule (OR-union — ANY detector fires → composite fires):
//
//     anyDetectorFires(ctx) = rootBeerIsRooted(ctx) ||
//                             momoIsRooted(ctx)     ||
//                             fridaDetected(ctx)    ||
//                             playIntegrityFails(ctx) ||
//                             emulatorDetectorFires(ctx) ||
//                             freeRaspT5InstallSourceFires(ctx)
//
// Per-snapshot expected verdict matrix:
//
//   | Snapshot              | anyDetectorFires | Reason                                |
//   |-----------------------|------------------|---------------------------------------|
//   | Pixel7Clean           | false            | clean retail device, Play allow-listed|
//   | SamsungS22Clean       | false            | clean retail device, Play allow-listed|
//   | RedroidV12            | true             | Magisk-root + emu fires every family  |
//   | RedroidSpoofed        | false  (CRITICAL)| spoof masks ALL 6 detector families   |
//   | FridaInjectedRedroid  | true             | Frida + Redroid trip multiple families|
//   | NoxSnapshot           | true             | emulator-rule fires (ro.product.name) |
//   | BlueStacksSnapshot    | true             | emulator-rule fires (model literal)   |
//   | GenymotionSnapshot    | true             | emulator-rule fires (manufacturer)    |
//
// **CRITICAL invariant** (the central spoof-stack claim under test):
//   `RedroidSpoofed → false` is the dispositive end-to-end attestation that
//   the spoof stack masks EVERY shipping detector family simultaneously.
//   Any individual family firing on this snapshot is a P0 spoof-stack
//   regression. The companion sanity test
//   `RedroidSpoofed scrubs ALL 6 individually` asserts the per-family
//   verdict explicitly so a regression points to the failing family.
//
// **Honesty disclaimer**: this is a composition over the existing
// decision-rule encodings, NOT a re-derivation. Helper functions are
// copied VERBATIM from the sibling replay tests — the composite's
// verdict is a pure consequence of the sibling verdicts. If a sibling
// rule's encoding changes, this test must be updated in lock-step.
// Source-of-truth pointers are inline above each helper.

package com.detectorlab.replay.detectorapps

import com.detectorlab.core.ProbeContext
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.replay.BlueStacksSnapshot
import com.detectorlab.core.replay.FridaInjectedRedroidSnapshot
import com.detectorlab.core.replay.GenymotionSnapshot
import com.detectorlab.core.replay.NoxSnapshot
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import com.detectorlab.probes.integrity.IntegrityInstallSourceProbe
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterCompositeDetectorReplayTest {

    private val pixel7 = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
    private val samsung = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)
    private val redroidDirty = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
    private val redroidSpoofed = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)
    private val fridaInjected = SnapshotReplayContext(FridaInjectedRedroidSnapshot.SNAPSHOT)
    private val nox = SnapshotReplayContext(NoxSnapshot.SNAPSHOT)
    private val blueStacks = SnapshotReplayContext(BlueStacksSnapshot.SNAPSHOT)
    private val genymotion = SnapshotReplayContext(GenymotionSnapshot.SNAPSHOT)

    // ── Composite decision rule ──────────────────────────────────────────────

    private fun anyDetectorFires(ctx: ProbeContext): Boolean {
        return rootBeerIsRooted(ctx) ||
            momoIsRooted(ctx) ||
            fridaDetected(ctx) ||
            playIntegrityFails(ctx) ||
            emulatorDetectorFires(ctx) ||
            freeRaspT5InstallSourceFires(ctx)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 1 — RootBeer isRooted() (9 branches)
    // Source-of-truth: RootBeerReplayTest.kt (Power-14 AAR-aligned)
    // ═════════════════════════════════════════════════════════════════════════

    private fun rootBeerIsRooted(ctx: ProbeContext): Boolean {
        return rbCheckRootManagerApps(ctx) ||
            rbCheckDangerousApps(ctx) ||
            rbCheckForSuBinary(ctx) ||
            rbCheckDangerousProps(ctx) ||
            rbCheckForRWPaths(ctx) ||
            rbDetectTestKeys(ctx) ||
            rbCheckSuExists(ctx) ||
            rbCheckForMagiskBinary(ctx) ||
            rbCheckForMagiskUds(ctx)
    }

    private fun rbCheckRootManagerApps(ctx: ProbeContext): Boolean {
        val pm = ctx.queryPackageManager()
        val pkgs = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
        )
        return pkgs.any { pm.isPackageInstalled(it) }
    }

    private fun rbCheckDangerousApps(ctx: ProbeContext): Boolean {
        val pm = ctx.queryPackageManager()
        val pkgs = listOf(
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.android.vending.billing.InAppBillingService.COIN",
            "com.android.vending.billing.InAppBillingService.LUCK",
            "com.chelpus.luckypatcher",
            "com.blackmartalpha",
            "org.blackmart.market",
            "com.allinone.free",
            "com.repodroid.app",
            "org.creeplays.hack",
            "com.baseappfull.fwd",
            "com.zmapp",
            "com.dv.marketmod.installer",
            "org.mobilism.android",
            "com.android.wp.net.log",
            "com.android.camera.update",
            "cc.madkite.freedom",
            "com.solohsu.android.edxp.manager",
            "org.meowcat.edxposed.manager",
            "com.xmodgame",
            "com.cih.game_cih",
            "com.charles.lpoqasert",
            "catch_.me_.if_.you_.can_",
        )
        return pkgs.any { pm.isPackageInstalled(it) }
    }

    private fun rbCheckDangerousProps(ctx: ProbeContext): Boolean {
        val debuggable = ctx.getSystemProperty("ro.debuggable")
        val secure = ctx.getSystemProperty("ro.secure")
        return debuggable == "1" || secure == "0"
    }

    private fun rbDetectTestKeys(ctx: ProbeContext): Boolean {
        val tags = ctx.getSystemProperty("ro.build.tags") ?: return false
        return tags.contains("test-keys")
    }

    private fun rbCheckForSuBinary(ctx: ProbeContext): Boolean = rbSuPathsScan(ctx, "su")

    private fun rbCheckForMagiskBinary(ctx: ProbeContext): Boolean = rbSuPathsScan(ctx, "magisk")

    private fun rbCheckSuExists(ctx: ProbeContext): Boolean = rbSuPathsScan(ctx, "su")

    private fun rbSuPathsScan(ctx: ProbeContext, filename: String): Boolean {
        val suPaths = listOf(
            "/data/local/",
            "/data/local/bin/",
            "/data/local/xbin/",
            "/sbin/",
            "/su/bin/",
            "/system/bin/",
            "/system/bin/.ext/",
            "/system/bin/failsafe/",
            "/system/sd/xbin/",
            "/system/usr/we-need-root/",
            "/system/xbin/",
            "/cache/",
            "/data/",
            "/dev/",
        )
        return suPaths.any { ctx.fileExists(it + filename) }
    }

    private fun rbCheckForRWPaths(ctx: ProbeContext): Boolean {
        val pathsThatShouldNotBeWritable = setOf(
            "/system", "/system/bin", "/system/sbin", "/system/xbin",
            "/vendor/bin", "/sbin", "/etc",
        )
        val mountInfo = ctx.queryMountInfo("self") ?: return false
        for (line in mountInfo.split("\n")) {
            val parts = line.split(" ")
            if (parts.size < 6) continue
            val mountPoint = parts[4]
            val opts = parts[5]
            if (mountPoint in pathsThatShouldNotBeWritable && "rw" in opts.split(",")) {
                return true
            }
        }
        return false
    }

    private fun rbCheckForMagiskUds(ctx: ProbeContext): Boolean {
        val sockets = ctx.queryProcNetUnixSockets()
        return sockets.any { socket ->
            val lower = socket.lowercase()
            "magisk" in lower || "/.magisk" in lower
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 2 — Momo (HuskyDG) magiskDetected() (5 signals)
    // Source-of-truth: MomoReplayTest.kt
    // ═════════════════════════════════════════════════════════════════════════

    private val momoMagiskSubstrings = listOf(
        "/sbin/.magisk",
        "/data/adb",
        ".magisk",
        "magisk_tmp",
        "/sbin/magisk",
    )

    private fun momoIsRooted(ctx: ProbeContext): Boolean {
        return momoMountNamespaceMismatch(ctx) ||
            momoMagiskModuleDirPresent(ctx) ||
            momoMagiskFilesystemArtifactsPresent(ctx) ||
            momoOverlayfsOnSystem(ctx) ||
            momoRandomizedInitSvc(ctx)
    }

    private fun momoMountNamespaceMismatch(ctx: ProbeContext): Boolean {
        val self = ctx.queryMountInfo("self") ?: return false
        val init = ctx.queryMountInfo("1") ?: return false
        val initHasMagisk = momoMagiskSubstrings.any { it in init }
        val selfHasMagisk = momoMagiskSubstrings.any { it in self }
        return initHasMagisk && !selfHasMagisk
    }

    private fun momoMagiskModuleDirPresent(ctx: ProbeContext): Boolean {
        return ctx.queryDirEntries("/data/adb/modules") != null
    }

    private fun momoMagiskFilesystemArtifactsPresent(ctx: ProbeContext): Boolean {
        val paths = listOf("/sbin/.magisk", "/data/adb/magisk", "/cache/magisk.log")
        return paths.any { ctx.fileExists(it) }
    }

    private fun momoOverlayfsOnSystem(ctx: ProbeContext): Boolean {
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

    private fun momoRandomizedInitSvc(ctx: ProbeContext): Boolean {
        val svc = ctx.queryInitSvcProps()
        if (svc.isEmpty()) return false
        val hexOnly = Regex("^[a-f0-9]{6,}$")
        return svc.keys.any { hexOnly.matches(it.lowercase()) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 3 — FridaDetector fridaDetected() (3 UNION checks)
    // Source-of-truth: FridaDetectorReplayTest.kt (Power-14 UNION)
    // ═════════════════════════════════════════════════════════════════════════

    private fun fridaDetected(ctx: ProbeContext): Boolean {
        return fridaLibrariesInProcMaps(ctx) ||
            fridaThreadNames(ctx) ||
            fridaPortsBound(ctx)
    }

    private fun fridaLibrariesInProcMaps(ctx: ProbeContext): Boolean {
        val libs = ctx.queryProcSelfMapsLibs()
        val tokens = listOf("frida-agent", "frida-gadget", "libfrida-gadget", "gum", "linjector")
        return libs.any { lib ->
            val lower = lib.lowercase()
            tokens.any { it in lower }
        }
    }

    private fun fridaThreadNames(ctx: ProbeContext): Boolean {
        val names = ctx.queryRuntimeThreadNames()
        val gumNames = setOf("gum-js-loop", "gmain", "gdbus")
        return names.any { it.lowercase() in gumNames }
    }

    private fun fridaPortsBound(ctx: ProbeContext): Boolean {
        val ports = ctx.queryOpenTcpPorts()
        return 27042 in ports || 27043 in ports
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 4 — Play Integrity predictVerdict() (5 buildprop checks)
    // Source-of-truth: PlayIntegrityReplayTest.kt
    // Composite predicate: TRUE when verdict ≠ MEETS_DEVICE_INTEGRITY.
    // ═════════════════════════════════════════════════════════════════════════

    private fun playIntegrityFails(ctx: ProbeContext): Boolean {
        if (piFailsTagsAndType(ctx)) return true
        if (piFailsDangerousProps(ctx)) return true
        if (piFailsVerifiedBoot(ctx)) return true
        if (piFailsFingerprint(ctx)) return true
        if (piFailsMagiskFsArtifacts(ctx)) return true
        return false
    }

    private fun piFailsTagsAndType(ctx: ProbeContext): Boolean {
        val tags = ctx.getSystemProperty("ro.build.tags")
        val type = ctx.getSystemProperty("ro.build.type")
        return tags != "release-keys" || (type != null && type != "user")
    }

    private fun piFailsDangerousProps(ctx: ProbeContext): Boolean {
        val debuggable = ctx.getSystemProperty("ro.debuggable")
        val secure = ctx.getSystemProperty("ro.secure")
        return debuggable == "1" || secure == "0"
    }

    private fun piFailsVerifiedBoot(ctx: ProbeContext): Boolean {
        val vbState = ctx.getSystemProperty("ro.boot.verifiedbootstate")
        val flashLocked = ctx.getSystemProperty("ro.boot.flash.locked")
        if (vbState != null && vbState != "green") return true
        if (flashLocked != null && flashLocked != "1") return true
        return false
    }

    private fun piFailsFingerprint(ctx: ProbeContext): Boolean {
        val fp = ctx.getSystemProperty("ro.build.fingerprint") ?: return false
        return fp.startsWith("generic") ||
            "sdk_google" in fp ||
            "emulator" in fp.lowercase() ||
            "redroid" in fp.lowercase() ||
            "goldfish" in fp.lowercase() ||
            "ranchu" in fp.lowercase()
    }

    private fun piFailsMagiskFsArtifacts(ctx: ProbeContext): Boolean {
        val paths = listOf("/sbin/.magisk", "/data/adb/magisk", "/system/bin/su")
        return paths.any { ctx.fileExists(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 5 — EmulatorDetector emulatorDetected() (8 OR-gated checks)
    // Source-of-truth: EmulatorDetectorReplayTest.kt
    // ═════════════════════════════════════════════════════════════════════════

    private fun emulatorDetectorFires(ctx: ProbeContext): Boolean {
        return emFingerprintLooksEmulator(ctx) ||
            emModelLooksEmulator(ctx) ||
            emBrandLooksEmulator(ctx) ||
            emManufacturerLooksEmulator(ctx) ||
            emProductLooksEmulator(ctx) ||
            emQemuDeviceNodesPresent(ctx) ||
            emEmulatorTelephonyMarkers(ctx) ||
            emThirdPartyEmulatorInitFiles(ctx)
    }

    private fun emFingerprintLooksEmulator(ctx: ProbeContext): Boolean {
        val fp = ctx.getSystemProperty("ro.build.fingerprint") ?: return false
        val lower = fp.lowercase()
        return fp.startsWith("generic") ||
            "sdk_google" in lower ||
            "emulator" in lower ||
            "goldfish" in lower ||
            "ranchu" in lower ||
            "redroid" in lower
    }

    private fun emModelLooksEmulator(ctx: ProbeContext): Boolean {
        val model = ctx.getSystemProperty("ro.product.model") ?: return false
        val lower = model.lowercase()
        return "google_sdk" in lower ||
            "emulator" in lower ||
            "android sdk built for" in lower ||
            "redroid" in lower ||
            "droid4x" in lower
    }

    private fun emBrandLooksEmulator(ctx: ProbeContext): Boolean {
        val brand = ctx.getSystemProperty("ro.product.brand")?.lowercase() ?: return false
        return brand in setOf("generic", "android", "generic_arm64", "generic_x86", "generic_x86_64", "redroid")
    }

    private fun emManufacturerLooksEmulator(ctx: ProbeContext): Boolean {
        val mfr = ctx.getSystemProperty("ro.product.manufacturer")?.lowercase() ?: return false
        return mfr == "genymotion" || mfr == "unknown" || mfr == "redroid" || mfr == "itoolsavm"
    }

    private fun emProductLooksEmulator(ctx: ProbeContext): Boolean {
        val product = ctx.getSystemProperty("ro.product.name")?.lowercase() ?: return false
        return product in setOf("sdk_x86_64", "vbox86p", "google_sdk") ||
            "andy" in product ||
            "ttvm" in product ||
            "redroid" in product
    }

    private fun emQemuDeviceNodesPresent(ctx: ProbeContext): Boolean {
        val paths = listOf(
            "/dev/qemu_pipe",
            "/dev/qemu_trace",
            "/dev/socket/qemud",
            "/dev/socket/baseband_genyd",
        )
        return paths.any { ctx.fileExists(it) }
    }

    private fun emEmulatorTelephonyMarkers(ctx: ProbeContext): Boolean {
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

    private fun emThirdPartyEmulatorInitFiles(ctx: ProbeContext): Boolean {
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

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 6 — freeRASP T5 install-source check
    // Source-of-truth: IntegrityInstallSourceProbe.kt (probe rank 10.5)
    //
    // Encodes the dispositive portion of the probe (PATTERN ≠ "clean"):
    // installer NOT in LEGITIMATE_INSTALLERS → fires. Mirrors the probe's
    // verdict-collapse: PATTERN_UNOFFICIAL_INSTALLER (0.95) AND
    // PATTERN_UNKNOWN_INSTALLER (0.85) both fire the composite; only
    // PATTERN_CLEAN (allow-listed installer) returns false.
    // ═════════════════════════════════════════════════════════════════════════

    private fun freeRaspT5InstallSourceFires(ctx: ProbeContext): Boolean {
        val installer = ctx.queryInstallSourcePackage()
        // PATTERN_CLEAN if and only if installer is in the allow-list.
        // Probe rule: null installer → UNKNOWN_INSTALLER (fires);
        //             non-null but unlisted → UNOFFICIAL_INSTALLER (fires).
        return installer == null ||
            installer !in IntegrityInstallSourceProbe.LEGITIMATE_INSTALLERS
    }

    // ── Per-snapshot composite assertions (8 snapshots) ──────────────────────

    @Test
    fun `Pixel 7 clean — composite NOT fired (clean retail device)`() {
        assertFalse(
            anyDetectorFires(pixel7),
            "Pixel7Clean must pass all 6 detector families",
        )
    }

    @Test
    fun `Samsung S22 clean — composite NOT fired (clean retail device)`() {
        assertFalse(
            anyDetectorFires(samsung),
            "SamsungS22Clean must pass all 6 detector families",
        )
    }

    @Test
    fun `RedroidV12 dirty — composite FIRED (Magisk + emu trip every family)`() {
        assertTrue(
            anyDetectorFires(redroidDirty),
            "RedroidV12 must trip at least one detector family",
        )
    }

    @Test
    fun `RedroidSpoofed — composite NOT fired (CRITICAL — spoof masks ALL 6)`() {
        // This is the dispositive end-to-end attestation that the spoof
        // stack defeats every shipping detector family simultaneously.
        // A regression here is a P0 spoof-stack bug.
        assertFalse(
            anyDetectorFires(redroidSpoofed),
            "RedroidSpoofed MUST pass all 6 detector families (spoof-stack invariant)",
        )
    }

    @Test
    fun `FridaInjectedRedroid — composite FIRED (Frida + Redroid trip multiple families)`() {
        // FridaInjectedRedroid is a positive-path fixture: it intentionally
        // encodes both Frida instrumentation AND the un-spoofed Redroid
        // surface. The composite firing here is expected; the individual
        // family attributions are documented in the dedicated sanity test.
        assertTrue(
            anyDetectorFires(fridaInjected),
            "FridaInjectedRedroid must trip at least one detector family",
        )
    }

    @Test
    fun `NoxSnapshot — composite FIRED (emulator-rule fires on ro_product_name=nox)`() {
        assertTrue(
            anyDetectorFires(nox),
            "Nox must trip at least the EmulatorDetector family",
        )
    }

    @Test
    fun `BlueStacksSnapshot — composite FIRED (emulator-rule fires on model literal)`() {
        assertTrue(
            anyDetectorFires(blueStacks),
            "BlueStacks must trip at least the EmulatorDetector family",
        )
    }

    @Test
    fun `GenymotionSnapshot — composite FIRED (emulator-rule fires on manufacturer)`() {
        assertTrue(
            anyDetectorFires(genymotion),
            "Genymotion must trip at least the EmulatorDetector family",
        )
    }

    // ── Sanity: RedroidSpoofed scrubs ALL 6 detector families individually ──

    @Test
    fun `RedroidSpoofed scrubs ALL 6 individually — per-family assertFalse`() {
        // Per-family attribution of the composite NOT-FIRED verdict on
        // RedroidSpoofed. If this test fails, the failing family identifies
        // exactly which spoof surface regressed.
        assertFalse(
            rootBeerIsRooted(redroidSpoofed),
            "RootBeer.isRooted() must return false on RedroidSpoofed",
        )
        assertFalse(
            momoIsRooted(redroidSpoofed),
            "Momo.magiskDetected() must return false on RedroidSpoofed",
        )
        assertFalse(
            fridaDetected(redroidSpoofed),
            "FridaDetector UNION must return false on RedroidSpoofed",
        )
        assertFalse(
            playIntegrityFails(redroidSpoofed),
            "Play Integrity predict-verdict must be MEETS_DEVICE_INTEGRITY on RedroidSpoofed",
        )
        assertFalse(
            emulatorDetectorFires(redroidSpoofed),
            "EmulatorDetector composite must return false on RedroidSpoofed",
        )
        assertFalse(
            freeRaspT5InstallSourceFires(redroidSpoofed),
            "freeRASP T5 install-source must return PATTERN_CLEAN on RedroidSpoofed",
        )
    }
}

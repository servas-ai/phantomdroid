// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/CompositeDetector.kt
//
// Power-18 D1 follow-up — composite-detector OR-union, copied VERBATIM from
// `MasterCompositeDetectorReplayTest.kt` (Power-17 Task #47 P17-C1, commit
// c202ee8 + downstream). Composes the SIX shipping detector-family
// decision-rules into a single boolean:
//
//   anyDetectorFires(ctx) = RootBeer.isRooted(ctx) ||
//                           Momo.magiskDetected(ctx) ||
//                           FridaDetector.fridaDetected(ctx) ||
//                           PlayIntegrity.predictVerdict(ctx) != MEETS_DEVICE_INTEGRITY ||
//                           EmulatorDetector.emulatorDetected(ctx) ||
//                           freeRASP-T5-install-source-fires(ctx)
//
// Why a duplicate of the test helpers lives here:
//   1. MasterCompositeDetectorReplayTest's helpers are `private` (correctly —
//      they are not designed as a public API surface) and live in the
//      :detection TEST sourceSet. They are not addressable from the
//      production CLI binary's main sourceSet at all.
//   2. The CLI's `replay-snapshot` subcommand's exit-code contract MUST
//      use the same composite the validation matrix uses; using a different
//      semantic (e.g. weightedScore threshold) creates a verdict-disagreement
//      between the binary and the validation harness — exactly the kind of
//      drift Power-15 went out of its way to eliminate.
//   3. Per the source-of-truth's own honesty disclaimer ("Helper functions
//      are copied VERBATIM from the sibling replay tests — the composite's
//      verdict is a pure consequence of the sibling verdicts"), VERBATIM
//      duplication IS the documented integration pattern.
//
// **Source-of-truth pointer**: every helper below is bit-aligned with
// `MasterCompositeDetectorReplayTest.kt` (commit c202ee8). If a detector
// family's encoding changes in the test source-of-truth, this object MUST
// be updated in lock-step. The :detection-cli test
// `ReplaySnapshotCliTest` asserts per-snapshot composite verdicts against
// the same 8-snapshot matrix, which detects any drift between this copy
// and the source-of-truth.

package com.detectorlab.cli

import com.detectorlab.core.ProbeContext
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.integrity.IntegrityInstallSourceProbe

object CompositeDetector {

    // ── Top-level composite OR-union ────────────────────────────────────────

    fun anyDetectorFires(ctx: ProbeContext): Boolean {
        return rootBeerIsRooted(ctx) ||
            momoIsRooted(ctx) ||
            fridaDetected(ctx) ||
            playIntegrityFails(ctx) ||
            emulatorDetectorFires(ctx) ||
            freeRaspT5InstallSourceFires(ctx)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detector 1 — RootBeer isRooted() (9 branches)
    // Source-of-truth: MasterCompositeDetectorReplayTest.rootBeerIsRooted()
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
    // Source-of-truth: MasterCompositeDetectorReplayTest.momoIsRooted()
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
    // Source-of-truth: MasterCompositeDetectorReplayTest.fridaDetected()
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
    // Source-of-truth: MasterCompositeDetectorReplayTest.playIntegrityFails()
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
    // Source-of-truth: MasterCompositeDetectorReplayTest.emulatorDetectorFires()
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
        return brand in setOf(
            "generic", "android", "generic_arm64", "generic_x86", "generic_x86_64", "redroid",
        )
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
    // Source-of-truth: MasterCompositeDetectorReplayTest.freeRaspT5InstallSourceFires()
    //                  + IntegrityInstallSourceProbe.LEGITIMATE_INSTALLERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun freeRaspT5InstallSourceFires(ctx: ProbeContext): Boolean {
        val installer = ctx.queryInstallSourcePackage()
        return installer == null ||
            installer !in IntegrityInstallSourceProbe.LEGITIMATE_INSTALLERS
    }
}

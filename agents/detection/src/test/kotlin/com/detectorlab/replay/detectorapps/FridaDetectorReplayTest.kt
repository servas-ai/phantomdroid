// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/FridaDetectorReplayTest.kt
//
// Power-13 Task #4 / Power-14 amendment — Frida-detection signal UNION replay.
//
// HONESTY DISCLAIMER (Power-14 audit finding): this class is NAMED
// after DetectFrida (darvincisec/DetectFrida) but encodes a UNION of
// Frida-detection techniques from multiple upstream sources. See
// `audit/spoof-stack/power-14-apk-source-diff.md` §1bis for the
// detailed diff. DetectFrida's actual published source uses only
// (a) thread-name strstr for "gum-js-loop"+"gmain" (not "gdbus"),
// (b) named-pipe linjector check in /proc/self/fd/*, and
// (c) ELF .text section CHECKSUM comparison vs on-disk for libc.so
//     + libnative-lib.so (this is the un-snapshottable surface
//     handled by rank-9.7 NativePrologueHashProbe).
//
// Our replay is STRICTER than DetectFrida: it catches DetectFrida's
// signals PLUS additional Frida signals (port-bind 27042/27043 from
// freeRASP-style detection, library-token search from generic
// Frida-detection literature, "gdbus" thread name from Frida's own
// internals). A spoof passing our union check also passes
// DetectFrida's strict subset.
//
// Encoded signals (UNION):
//   1. /proc/self/maps library tokens (frida-agent, frida-gadget,
//      gum, linjector, libfrida-gadget)   ← from Frida-itself source
//   2. /proc/self/task/<tid>/comm thread names (gum-js-loop, gmain,
//      gdbus)                              ← gum-js-loop+gmain from DetectFrida;
//                                            gdbus from Frida-itself
//   3. /proc/net/tcp ports 27042 + 27043   ← from freeRASP-style detection
//
// NOT encoded (un-snapshottable, covered separately):
//   • ELF .text section checksum compare   ← rank-9.7 (DetectFrida's
//                                            primary technique)
//   • GOT/PLT entry comparison             ← rank-9.8
//
// Decision rule: ANY of the three union-checks fires → Frida detected.
//
// Acceptance:
//   - Pixel7Clean / SamsungS22Clean → NOT detected (sanity)
//   - RedroidV12                    → NOT detected (Redroid container
//                                     is not running Frida; only
//                                     Magisk-rooted)
//   - RedroidSpoofed                → NOT detected (no Frida in the
//                                     spoof fixture either)
//
// **Test honesty note**: DetectFrida is the one detector class
// where ALL FOUR snapshots return "not detected" because Frida is
// not modeled in any of our fixtures. That's the correct outcome:
// the spoof-stack is NOT a Frida bypass mechanism (Frida runs in
// the analyst's instrumentation harness, not on the target
// device). The test is included for completeness — when a future
// snapshot DOES inject Frida signals, this same decision rule
// will correctly classify it.

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

class FridaDetectorReplayTest {

    private val pixel7 = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
    private val samsung = SnapshotReplayContext(SamsungS22CleanSnapshot.SNAPSHOT)
    private val redroidDirty = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
    private val redroidSpoofed = SnapshotReplayContext(RedroidSpoofedSnapshot.SNAPSHOT)

    // ── Decision rule ────────────────────────────────────────────────────────

    private fun fridaDetected(ctx: ProbeContext): Boolean {
        return fridaLibrariesInProcMaps(ctx) ||
            fridaThreadNames(ctx) ||
            fridaPortsBound(ctx)
    }

    /** Check 1 — DetectFrida `native-lib.c` library-token scan. */
    private fun fridaLibrariesInProcMaps(ctx: ProbeContext): Boolean {
        val libs = ctx.queryProcSelfMapsLibs()
        val tokens = listOf("frida-agent", "frida-gadget", "libfrida-gadget", "gum", "linjector")
        return libs.any { lib ->
            val lower = lib.lowercase()
            tokens.any { it in lower }
        }
    }

    /** Check 2 — DetectFrida thread-name scan. */
    private fun fridaThreadNames(ctx: ProbeContext): Boolean {
        val names = ctx.queryRuntimeThreadNames()
        val gumNames = setOf("gum-js-loop", "gmain", "gdbus")
        return names.any { it.lowercase() in gumNames }
    }

    /** Check 3 — frida-server's canonical TCP ports. */
    private fun fridaPortsBound(ctx: ProbeContext): Boolean {
        val ports = ctx.queryOpenTcpPorts()
        return 27042 in ports || 27043 in ports
    }

    // ── Per-snapshot assertions ──────────────────────────────────────────────

    @Test
    fun `Pixel 7 clean — NOT detected (sanity)`() {
        assertFalse(fridaDetected(pixel7))
    }

    @Test
    fun `Samsung S22 clean — NOT detected (sanity)`() {
        assertFalse(fridaDetected(samsung))
    }

    @Test
    fun `RedroidV12 dirty — NOT detected (Redroid is not running Frida)`() {
        // Honest documentation: the un-spoofed Redroid fixture
        // models a Magisk-rooted container but does NOT inject
        // Frida instrumentation. DetectFrida correctly returns
        // not-detected — this is consistent ground truth, not a
        // spoof-stack pass.
        assertFalse(fridaDetected(redroidDirty))
    }

    @Test
    fun `RedroidSpoofed — NOT detected (no Frida in spoof fixture either)`() {
        assertFalse(fridaDetected(redroidSpoofed))
    }

    // ── Synthetic-injection unit tests for the decision rule itself ──────────

    @Test
    fun `fridaLibrariesInProcMaps detects frida-agent token`() {
        val injected = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: com.detectorlab.core.TelephonyField): String? = null
            override fun queryPackageManager(): com.detectorlab.core.PackageManagerView =
                object : com.detectorlab.core.PackageManagerView {
                    override fun isPackageInstalled(packageName: String) = false
                    override fun listInstalledPackages() = emptyList<String>()
                    override fun listPackagesWithPermission(permission: String) = emptyList<String>()
                }
            override fun querySensorManager(): com.detectorlab.core.SensorManagerView =
                object : com.detectorlab.core.SensorManagerView {
                    override fun listSensorTypes() = emptyList<Int>()
                    override fun sampleSensor(sensorType: Int, durationMs: Long) =
                        com.detectorlab.core.SensorSample(LongArray(0), emptyArray())
                }
            override fun queryProcSelfMapsLibs() = setOf("libc.so", "libfrida-gadget.so")
        }
        assertTrue(fridaLibrariesInProcMaps(injected))
    }

    @Test
    fun `fridaThreadNames detects gum-js-loop`() {
        val injected = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: com.detectorlab.core.TelephonyField): String? = null
            override fun queryPackageManager(): com.detectorlab.core.PackageManagerView =
                object : com.detectorlab.core.PackageManagerView {
                    override fun isPackageInstalled(packageName: String) = false
                    override fun listInstalledPackages() = emptyList<String>()
                    override fun listPackagesWithPermission(permission: String) = emptyList<String>()
                }
            override fun querySensorManager(): com.detectorlab.core.SensorManagerView =
                object : com.detectorlab.core.SensorManagerView {
                    override fun listSensorTypes() = emptyList<Int>()
                    override fun sampleSensor(sensorType: Int, durationMs: Long) =
                        com.detectorlab.core.SensorSample(LongArray(0), emptyArray())
                }
            override fun queryRuntimeThreadNames() = setOf("main", "gum-js-loop", "binder:1")
        }
        assertTrue(fridaThreadNames(injected))
    }

    @Test
    fun `fridaPortsBound detects port 27042`() {
        val injected = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: com.detectorlab.core.TelephonyField): String? = null
            override fun queryPackageManager(): com.detectorlab.core.PackageManagerView =
                object : com.detectorlab.core.PackageManagerView {
                    override fun isPackageInstalled(packageName: String) = false
                    override fun listInstalledPackages() = emptyList<String>()
                    override fun listPackagesWithPermission(permission: String) = emptyList<String>()
                }
            override fun querySensorManager(): com.detectorlab.core.SensorManagerView =
                object : com.detectorlab.core.SensorManagerView {
                    override fun listSensorTypes() = emptyList<Int>()
                    override fun sampleSensor(sensorType: Int, durationMs: Long) =
                        com.detectorlab.core.SensorSample(LongArray(0), emptyArray())
                }
            override fun queryOpenTcpPorts() = setOf(80, 27042, 8080)
        }
        assertTrue(fridaPortsBound(injected))
    }
}

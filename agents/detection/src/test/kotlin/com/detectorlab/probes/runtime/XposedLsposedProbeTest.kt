package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for XposedLsposedProbe (runtime.xposed_lsposed, inventory rank 8).
 */
class XposedLsposedProbeTest {

    private val probe = XposedLsposedProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        presentFiles: Set<String> = emptySet(),
        installedPackages: Set<String> = emptySet(),
        procSelfMaps: String? = null,
        fileExistsThrows: Boolean = false,
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated EACCES")
            return path in presentFiles
        }
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == XposedLsposedProbe.PROC_SELF_MAPS) procSelfMaps else null
        }
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = packageName in installedPackages
            override fun listInstalledPackages() = installedPackages.toList()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // Realistic-looking /proc/self/maps content with optional injected markers.
    private fun mapsWith(vararg markers: String): String {
        val base = """
            7f0000000000-7f0000001000 r-xp 00000000 fd:00 1 /system/lib64/libc.so
            7f0000001000-7f0000002000 r--p 00001000 fd:00 1 /system/lib64/libc.so
            7f0000002000-7f0000003000 rw-p 00002000 fd:00 1 /apex/com.android.runtime/lib64/bionic/libc.so
            7f0000003000-7f0000004000 r-xp 00000000 fd:00 2 /system/lib64/libart.so
        """.trimIndent()
        val extra = markers.joinToString(separator = "\n") { marker ->
            "7f0000010000-7f0000011000 r-xp 00000000 fd:00 99 /system/lib64/$marker.so"
        }
        return if (extra.isEmpty()) base else "$base\n$extra"
    }

    // ── Clean device ──────────────────────────────────────────────────────────

    @Test
    fun `clean device — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean device — confidence is capped at 0_85 (no classloader signal)`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `clean device — all probed artifact paths reported absent`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        val pathEvidence = result.evidence.filter { it.key.startsWith("/") && it.key != XposedLsposedProbe.PROC_SELF_MAPS }
        assertEquals(XposedLsposedProbe.INSTALLATION_ARTIFACT_PATHS.size, pathEvidence.size)
        assertTrue(pathEvidence.all { it.value == "absent" })
    }

    @Test
    fun `clean device — all probed manager packages reported absent`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        val pkgEvidence = result.evidence.filter { it.key.startsWith("pkg.") }
        assertEquals(XposedLsposedProbe.MANAGER_PACKAGES.size, pkgEvidence.size)
        assertTrue(pkgEvidence.all { it.value == "absent" })
    }

    @Test
    fun `clean device — proc maps reports no hook libs`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        val hooksEv = result.evidence.find { it.key == "proc.self.maps.hook_lib_matches" }
        assertEquals("none", hooksEv?.value)
    }

    // ── Installation artifact present ─────────────────────────────────────────

    @Test
    fun `XposedBridge jar present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/system/framework/XposedBridge.jar"), procSelfMaps = mapsWith()),
        )
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `XposedBridge jar present — evidence flags exact path`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/system/framework/XposedBridge.jar"), procSelfMaps = mapsWith()),
        )
        val ev = result.evidence.find { it.key == "/system/framework/XposedBridge.jar" }
        assertEquals("present", ev?.value)
    }

    @Test
    fun `lspd dir present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/lspd/"), procSelfMaps = mapsWith()),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zygisk lsposed module present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/data/adb/modules/zygisk_lsposed/"), procSelfMaps = mapsWith()),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `app_process64_xposed present — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/system/bin/app_process64_xposed"), procSelfMaps = mapsWith()),
        )
        assertEquals(1.0, result.score)
    }

    // ── Only manager package installed ────────────────────────────────────────

    @Test
    fun `LSPosed manager package only — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("org.lsposed.manager"), procSelfMaps = mapsWith()),
        )
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Xposed installer package only — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("de.robv.android.xposed.installer"), procSelfMaps = mapsWith()),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `EdXposed manager package only — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("org.meowcat.edxposed.manager"), procSelfMaps = mapsWith()),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `manager package — evidence flags installed`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("org.lsposed.manager"), procSelfMaps = mapsWith()),
        )
        val ev = result.evidence.find { it.key == "pkg.org.lsposed.manager" }
        assertEquals("installed", ev?.value)
    }

    // ── Only /proc/self/maps hook library detected ────────────────────────────

    @Test
    fun `liblspd in proc maps only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("liblspd")))
        assertFalse(result.failed)
        assertEquals(0.70, result.score)
    }

    @Test
    fun `libxposed in proc maps only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("libxposed_art")))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `libsandhook in proc maps only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("libsandhook")))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `libriru in proc maps only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("libriru_core")))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `libzygisk in proc maps only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("libzygisk")))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `proc maps hook lib — evidence lists matched marker`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith("liblspd")))
        val ev = result.evidence.find { it.key == "proc.self.maps.hook_lib_matches" }
        assertTrue((ev?.value as String).contains("liblspd"))
    }

    // ── Multi-signal precedence ───────────────────────────────────────────────

    @Test
    fun `artifact plus manager plus hook lib — score is 1_0 (artifact dominates)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/data/adb/lspd/"),
                installedPackages = setOf("org.lsposed.manager"),
                procSelfMaps = mapsWith("liblspd"),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `manager plus hook lib without artifact — score is 0_85 (manager dominates hook lib)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf("org.lsposed.manager"),
                procSelfMaps = mapsWith("liblspd"),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `multiple artifacts — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf(
                    "/system/framework/XposedBridge.jar",
                    "/system/lib64/libxposed_art.so",
                    "/data/adb/lspd/",
                ),
                procSelfMaps = mapsWith(),
            ),
        )
        assertEquals(1.0, result.score)
    }

    // ── Degraded fileExists accessor ──────────────────────────────────────────

    @Test
    fun `fileExists throws on every path — confidence falls back to 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true, procSelfMaps = mapsWith()))
        assertFalse(result.failed)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `fileExists throws — score is 0 when no other signal fires`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true, procSelfMaps = mapsWith()))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `fileExists throws but manager installed — score still 0_85, confidence 0_5`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                fileExistsThrows = true,
                installedPackages = setOf("org.lsposed.manager"),
                procSelfMaps = mapsWith(),
            ),
        )
        assertEquals(0.85, result.score)
        assertEquals(0.5, result.confidence)
    }

    // ── /proc/self/maps unreadable ────────────────────────────────────────────

    @Test
    fun `proc maps unavailable — clean score still 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `proc maps unavailable — evidence flags not readable`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = null))
        val ev = result.evidence.find { it.key == "proc.self.maps.readable" }
        assertEquals(false, ev?.value)
    }

    @Test
    fun `readFile throws — does not crash, score remains 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Evidence coverage ─────────────────────────────────────────────────────

    @Test
    fun `evidence covers every probed artifact path`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        for (path in XposedLsposedProbe.INSTALLATION_ARTIFACT_PATHS) {
            assertTrue(
                result.evidence.any { it.key == path },
                "missing evidence for $path",
            )
        }
    }

    @Test
    fun `evidence covers every probed manager package`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        for (pkg in XposedLsposedProbe.MANAGER_PACKAGES) {
            assertTrue(
                result.evidence.any { it.key == "pkg.$pkg" },
                "missing evidence for pkg.$pkg",
            )
        }
    }

    @Test
    fun `evidence includes proc maps readability and hook matches keys`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        assertTrue(result.evidence.any { it.key == "proc.self.maps.readable" })
        assertTrue(result.evidence.any { it.key == "proc.self.maps.hook_lib_matches" })
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        assertEquals(
            "Class-loader probe + filesystem path + package-list scan + " +
                "/proc/self/maps hook-library detection",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is runtime_xposed_lsposed`() {
        assertEquals("runtime.xposed_lsposed", probe.id)
    }

    @Test
    fun `probe rank is 8`() {
        assertEquals(8, probe.rank)
    }

    @Test
    fun `probe category is RUNTIME`() {
        assertEquals(ProbeCategory.RUNTIME, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
    }

    @Test
    fun `probe budget is 400ms`() {
        assertEquals(400L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx(procSelfMaps = mapsWith()))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

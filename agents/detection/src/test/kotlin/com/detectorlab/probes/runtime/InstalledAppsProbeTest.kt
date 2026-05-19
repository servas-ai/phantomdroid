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
 * Unit tests for InstalledAppsProbe (runtime.installed_apps, inventory rank 10).
 */
class InstalledAppsProbeTest {

    private val probe = InstalledAppsProbe()

    // A realistic baseline package list — Android system + a few user apps —
    // ensures `installed_list_empty` is false in the normal case.
    private val baselinePackages: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.settings",
        "com.android.chrome",
        "com.android.calendar",
    )

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        installedPackages: Set<String> = baselinePackages,
        emptyInstalledList: Boolean = false,
        isPackageInstalledThrows: Boolean = false,
        pmThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView {
            if (pmThrows) throw RuntimeException("simulated PM service throw")
            return object : PackageManagerView {
                override fun isPackageInstalled(packageName: String): Boolean {
                    if (isPackageInstalledThrows) throw RuntimeException("simulated isPackageInstalled throw")
                    return packageName in installedPackages
                }
                override fun listInstalledPackages(): List<String> =
                    if (emptyInstalledList) emptyList() else installedPackages.toList()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── Clean device ──────────────────────────────────────────────────────────

    @Test
    fun `clean device — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean device — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean device — leak_group evidence is none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("none", ev?.value)
        assertEquals("none", ev?.expected)
    }

    @Test
    fun `clean device — no per-package evidence rows emitted`() = runBlocking {
        val result = probe.run(fakeCtx())
        val pkgRows = result.evidence.filter { it.key.startsWith("pkg.") }
        assertTrue(pkgRows.isEmpty())
    }

    // ── Group A (root/hook managers) — score 1.0 ──────────────────────────────

    @Test
    fun `Magisk manager visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.topjohnwu.magisk"))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `KernelSU manager visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "me.weishu.kernelsu"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `APatch manager visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "me.bmax.apatch"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `LSPosed manager visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "org.lsposed.manager"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Xposed Installer visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "de.robv.android.xposed.installer"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `SuperSU visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "eu.chainfire.supersu"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `KingoRoot visible — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.kingouser.com"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Group A — leak_group evidence is A`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.topjohnwu.magisk"))
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("A", ev?.value)
    }

    @Test
    fun `Group A — per-package evidence row emitted`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.topjohnwu.magisk"))
        val ev = result.evidence.find { it.key == "pkg.com.topjohnwu.magisk" }
        assertEquals("installed", ev?.value)
        assertEquals("absent", ev?.expected)
    }

    // ── Group B (dynamic analysis) — score 0.85 ───────────────────────────────

    @Test
    fun `frida server visible only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "re.frida.server"))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `frida client visible only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.frida.frida"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `bVNC visible only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.iiordanov.bVNC"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `SpyNote visible only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.cy8018.spynote"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Group B — leak_group evidence is B`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "re.frida.server"))
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("B", ev?.value)
    }

    // ── Group C (clone / virtualization) — score 0.70 ────────────────────────

    @Test
    fun `Parallel Space visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.lbe.parallel"))
        assertFalse(result.failed)
        assertEquals(0.70, result.score)
    }

    @Test
    fun `DualAid visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.excelliance.dualaid"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `VirtualXposed va_exposed visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "io.va.exposed"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `Group C — leak_group evidence is C`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.lbe.parallel"))
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("C", ev?.value)
    }

    // ── Group D (automation frameworks) — score 0.70 ─────────────────────────

    @Test
    fun `AutoJS visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "org.autojs.autojs"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `UIAutomator visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.android.uiautomator"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `TouchTask visible only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "com.touchtask"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `Group D — leak_group evidence is D`() = runBlocking {
        val result = probe.run(fakeCtx(baselinePackages + "org.autojs.autojs"))
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("D", ev?.value)
    }

    // ── Cross-group precedence (max wins, group rank A>B>C>D) ─────────────────

    @Test
    fun `Group A plus Group B — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(baselinePackages + setOf("com.topjohnwu.magisk", "re.frida.server")),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Group A plus Group B — leak_group is A`() = runBlocking {
        val result = probe.run(
            fakeCtx(baselinePackages + setOf("com.topjohnwu.magisk", "re.frida.server")),
        )
        val ev = result.evidence.find { it.key == "leak_group" }
        assertEquals("A", ev?.value)
    }

    @Test
    fun `Group B plus Group C — score is 0_85 (B dominates)`() = runBlocking {
        val result = probe.run(
            fakeCtx(baselinePackages + setOf("re.frida.server", "com.lbe.parallel")),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Group C plus Group D — score is 0_70`() = runBlocking {
        val result = probe.run(
            fakeCtx(baselinePackages + setOf("com.lbe.parallel", "org.autojs.autojs")),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `Multiple Group A — both per-package evidence rows emitted`() = runBlocking {
        val result = probe.run(
            fakeCtx(baselinePackages + setOf("com.topjohnwu.magisk", "org.lsposed.manager")),
        )
        assertTrue(result.evidence.any { it.key == "pkg.com.topjohnwu.magisk" })
        assertTrue(result.evidence.any { it.key == "pkg.org.lsposed.manager" })
    }

    // ── Android 11+ package-visibility filter ─────────────────────────────────

    @Test
    fun `listInstalledPackages empty AND no hits — confidence falls back to 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = emptySet(), emptyInstalledList = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.confidence)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `installed_list_empty evidence reflects visibility-filter state`() = runBlocking {
        val result = probe.run(fakeCtx(installedPackages = emptySet(), emptyInstalledList = true))
        val ev = result.evidence.find { it.key == "installed_list_empty" }
        assertEquals(true, ev?.value)
    }

    @Test
    fun `installed_list_empty but declared package visible — confidence stays 0_95`() = runBlocking {
        // App declared <queries> for com.topjohnwu.magisk; PM returns true for that
        // one but listInstalledPackages still returns empty. The hit means
        // visibility filtering is NOT total — confidence should stay high.
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: TelephonyField): String? = null
            override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) =
                    packageName == "com.topjohnwu.magisk"
                override fun listInstalledPackages() = emptyList<String>()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
            override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
                override fun listSensorTypes() = emptyList<Int>()
                override fun sampleSensor(sensorType: Int, durationMs: Long) =
                    SensorSample(LongArray(0), emptyArray())
            }
        }
        val result = probe.run(ctx)
        assertEquals(1.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Defensive: thrown PM / thrown isPackageInstalled ─────────────────────

    @Test
    fun `queryPackageManager throws — does not crash, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(pmThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `queryPackageManager throws — confidence falls back to 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(pmThrows = true))
        // pm is null → listInstalledPackages can't be called → treated as empty.
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `isPackageInstalled throws on every call — does not crash, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(isPackageInstalledThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "PackageManager-visible scan for known root managers, " +
                "dynamic-analysis tools, clone apps, and automation frameworks " +
                "(hide-manager mitigation failure signal)",
            result.method,
        )
    }

    // ── Group membership invariants ───────────────────────────────────────────

    @Test
    fun `Group A list does not overlap Group B`() {
        val a = InstalledAppsProbe.GROUP_A_ROOT_HOOK_MANAGERS.toSet()
        val b = InstalledAppsProbe.GROUP_B_DYNAMIC_ANALYSIS.toSet()
        assertTrue((a intersect b).isEmpty())
    }

    @Test
    fun `Group A list does not overlap Group C`() {
        val a = InstalledAppsProbe.GROUP_A_ROOT_HOOK_MANAGERS.toSet()
        val c = InstalledAppsProbe.GROUP_C_CLONE_VIRTUALIZATION.toSet()
        assertTrue((a intersect c).isEmpty())
    }

    @Test
    fun `Group C list does not overlap Group D`() {
        val c = InstalledAppsProbe.GROUP_C_CLONE_VIRTUALIZATION.toSet()
        val d = InstalledAppsProbe.GROUP_D_AUTOMATION.toSet()
        assertTrue((c intersect d).isEmpty())
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is runtime_installed_apps`() {
        assertEquals("runtime.installed_apps", probe.id)
    }

    @Test
    fun `probe rank is 10`() {
        assertEquals(10, probe.rank)
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
    fun `probe budget is 500ms`() {
        assertEquals(500L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

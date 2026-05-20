package com.detectorlab.probes.root

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
 * Unit tests for SuDetectionProbe (root.su_detection, inventory rank 3).
 */
class SuDetectionProbeTest {

    private val probe = SuDetectionProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        presentFiles: Set<String> = emptySet(),
        installedPackages: Set<String> = emptySet(),
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated SELinux EACCES")
            return path in presentFiles
        }
        override fun readFile(path: String, maxBytes: Int): String? = null
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
    fun `clean device — all probed paths reported as absent`() = runBlocking {
        val result = probe.run(fakeCtx())
        val pathEvidence = result.evidence.filter { it.key.startsWith("/") }
        assertTrue(pathEvidence.isNotEmpty(), "expected path evidence")
        assertTrue(pathEvidence.all { it.value == "absent" })
        assertTrue(pathEvidence.all { it.expected == "absent" })
    }

    @Test
    fun `clean device — all probed packages reported as absent`() = runBlocking {
        val result = probe.run(fakeCtx())
        val pkgEvidence = result.evidence.filter { it.key.startsWith("su_search.pkg.") }
        assertEquals(SuDetectionProbe.SUPERUSER_PACKAGES.size, pkgEvidence.size)
        assertTrue(pkgEvidence.all { it.value == "absent" })
    }

    // ── su binary present ─────────────────────────────────────────────────────

    @Test
    fun `system_bin_su present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/system/bin/su")))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `system_bin_su present — evidence flags that exact path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/system/bin/su")))
        val ev = result.evidence.find { it.key == "/system/bin/su" }
        assertEquals("present", ev?.value)
    }

    @Test
    fun `sbin_su present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/sbin/su")))
        assertEquals(1.0, result.score)
    }

    // ── Magisk artifact present ───────────────────────────────────────────────

    @Test
    fun `magisk core dir present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/magisk")))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `magisk core dir present — evidence flags that path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/data/adb/magisk")))
        val ev = result.evidence.find { it.key == "/data/adb/magisk" }
        assertEquals("present", ev?.value)
    }

    // ── Only superuser package installed (binary hidden) ──────────────────────

    @Test
    fun `magisk package only — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("com.topjohnwu.magisk")),
        )
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `magisk package only — evidence flags the package as installed`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("com.topjohnwu.magisk")),
        )
        val ev = result.evidence.find { it.key == "su_search.pkg.com.topjohnwu.magisk" }
        assertEquals("installed", ev?.value)
    }

    @Test
    fun `supersu package only — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = setOf("eu.chainfire.supersu")),
        )
        assertEquals(0.85, result.score)
    }

    // ── Multiple signals ──────────────────────────────────────────────────────

    @Test
    fun `binary plus package — score remains 1_0 (binary dominates)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/system/xbin/su"),
                installedPackages = setOf("com.topjohnwu.magisk"),
            ),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `multiple binaries — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/system/bin/su", "/sbin/su", "/su/bin/su")),
        )
        assertEquals(1.0, result.score)
    }

    @Test
    fun `binary plus magisk artifact — score is 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/system/bin/su", "/sbin/.magisk")),
        )
        assertEquals(1.0, result.score)
    }

    // ── Degraded fileExists accessor ──────────────────────────────────────────

    @Test
    fun `fileExists throws on every path — confidence falls back to 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `fileExists throws on every path — score is 0 (no signal observed)`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `fileExists throws but magisk package installed — score still 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                fileExistsThrows = true,
                installedPackages = setOf("com.topjohnwu.magisk"),
            ),
        )
        // Filesystem blind; package-list still works → degraded confidence + package signal.
        assertEquals(0.85, result.score)
        assertEquals(0.5, result.confidence)
    }

    // ── Evidence coverage ─────────────────────────────────────────────────────

    @Test
    fun `evidence covers every probed binary path`() = runBlocking {
        val result = probe.run(fakeCtx())
        for (path in SuDetectionProbe.SU_BINARY_PATHS) {
            assertTrue(
                result.evidence.any { it.key == path },
                "missing evidence for $path",
            )
        }
    }

    @Test
    fun `evidence covers every probed magisk artifact path`() = runBlocking {
        val result = probe.run(fakeCtx())
        for (path in SuDetectionProbe.MAGISK_ARTIFACT_PATHS) {
            assertTrue(
                result.evidence.any { it.key == path },
                "missing evidence for $path",
            )
        }
    }

    @Test
    fun `evidence covers every probed superuser package`() = runBlocking {
        val result = probe.run(fakeCtx())
        for (pkg in SuDetectionProbe.SUPERUSER_PACKAGES) {
            assertTrue(
                result.evidence.any { it.key == "su_search.pkg.$pkg" },
                "missing evidence for su_search.pkg.$pkg",
            )
        }
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches inventory description`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Filesystem path + package-list scan for known root toolchains (RootBeer-pattern subset)",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is root_su_detection`() {
        assertEquals("root.su_detection", probe.id)
    }

    @Test
    fun `probe rank is 3`() {
        assertEquals(3, probe.rank)
    }

    @Test
    fun `probe category is ROOT`() {
        assertEquals(ProbeCategory.ROOT, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
    }

    @Test
    fun `probe budget is 300ms`() {
        assertEquals(300L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs, "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms")
    }
}

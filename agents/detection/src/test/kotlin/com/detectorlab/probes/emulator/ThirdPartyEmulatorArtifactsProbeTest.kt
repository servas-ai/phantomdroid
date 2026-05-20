package com.detectorlab.probes.emulator

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
 * Unit tests for ThirdPartyEmulatorArtifactsProbe (rank 4.5,
 * code-rank 86, Power-13 Gap #4).
 */
class ThirdPartyEmulatorArtifactsProbeTest {

    private val probe = ThirdPartyEmulatorArtifactsProbe()

    private fun fakeCtx(
        presentFiles: Set<String> = emptySet(),
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated fileExists throw")
            return path in presentFiles
        }
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
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
    fun `clean device — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean device — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean device — any_artifact_present is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "third_party_emulator.any_artifact_present" }
        assertEquals("false", ev?.value)
    }

    // ── Nox ──────────────────────────────────────────────────────────────────

    @Test
    fun `Nox init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Nox bignox rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.bignox.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Nox — pattern is nox_artifact`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_NOX_ARTIFACT, ev?.value)
    }

    @Test
    fun `Nox — nox_paths_present evidence lists path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.nox_paths_present" }
        assertEquals("/init.nox.rc", ev?.value)
    }

    // ── Andy ─────────────────────────────────────────────────────────────────

    @Test
    fun `Andy init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.andy.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Andy fstab present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/fstab.andy")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Andy — pattern is andy_artifact`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/fstab.andy")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_ANDY_ARTIFACT, ev?.value)
    }

    // ── MEmu / MicroVirt ─────────────────────────────────────────────────────

    @Test
    fun `MEmu ttVM_x86 init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.ttVM_x86.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `MEmu ttVM_x86 fstab present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/fstab.ttVM_x86")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `MicroVirt init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.microvirt.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `MEmu — pattern is memu_artifact`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.ttVM_x86.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_MEMU_ARTIFACT, ev?.value)
    }

    // ── BlueStacks ───────────────────────────────────────────────────────────

    @Test
    fun `BlueStacks init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.bluestacks.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `BlueStacks fstab present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/fstab.bluestacks")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `BlueStacks — pattern is bluestacks_artifact`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.bluestacks.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_BLUESTACKS_ARTIFACT, ev?.value)
    }

    // ── Droid4x ──────────────────────────────────────────────────────────────

    @Test
    fun `Droid4x init rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.droid4x.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Droid4x fstab present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/fstab.droid4x")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Droid4x — pattern is droid4x_artifact`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.droid4x.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_DROID4X_ARTIFACT, ev?.value)
    }

    // ── Android-x86 generic ──────────────────────────────────────────────────

    @Test
    fun `ueventd android_x86 rc present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/ueventd.android_x86.rc")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `x86 prop present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/x86.prop")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `Android-x86 generic — pattern is android_x86_generic`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/x86.prop")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_ANDROID_X86_GENERIC, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `Nox + Android-x86 generic — Nox wins (vendor-specific first)`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc", "/x86.prop")))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_NOX_ARTIFACT, ev?.value)
    }

    @Test
    fun `BlueStacks + Android-x86 generic — BlueStacks wins`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.bluestacks.rc", "/x86.prop")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_BLUESTACKS_ARTIFACT, ev?.value)
    }

    @Test
    fun `Nox + BlueStacks — Nox wins (cascade order — Nox before BlueStacks)`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc", "/init.bluestacks.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.pattern" }
        assertEquals(ThirdPartyEmulatorArtifactsProbe.PATTERN_NOX_ARTIFACT, ev?.value)
    }

    @Test
    fun `multiple Nox paths — both listed in evidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/init.nox.rc", "/init.bignox.rc")),
        )
        val ev = result.evidence.find { it.key == "third_party_emulator.nox_paths_present" }
        val paths = ev?.value?.toString()?.split(",")?.toSet() ?: emptySet()
        assertEquals(setOf("/init.nox.rc", "/init.bignox.rc"), paths)
    }

    // ── any_artifact_present convenience evidence ────────────────────────────

    @Test
    fun `any_artifact_present true when Nox fires`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/init.nox.rc")))
        val ev = result.evidence.find { it.key == "third_party_emulator.any_artifact_present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `any_artifact_present true when only generic Android-x86 fires`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/x86.prop")))
        val ev = result.evidence.find { it.key == "third_party_emulator.any_artifact_present" }
        assertEquals("true", ev?.value)
    }

    // ── Defensive — fileExists throws ────────────────────────────────────────

    @Test
    fun `fileExists throws on every call — does not crash, score 0`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `fileExists throws on every call — confidence is 0_50 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertEquals(0.50, result.confidence)
    }

    // ── Evidence schema ──────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        // 6 vendor lists + any_artifact_present + pattern.
        val result = probe.run(fakeCtx())
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "third_party_emulator.nox_paths_present",
                "third_party_emulator.andy_paths_present",
                "third_party_emulator.memu_paths_present",
                "third_party_emulator.bluestacks_paths_present",
                "third_party_emulator.droid4x_paths_present",
                "third_party_emulator.android_x86_generic_paths_present",
                "third_party_emulator.any_artifact_present",
                "third_party_emulator.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `clean device — every vendor list reports none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val vendorKeys = listOf(
            "third_party_emulator.nox_paths_present",
            "third_party_emulator.andy_paths_present",
            "third_party_emulator.memu_paths_present",
            "third_party_emulator.bluestacks_paths_present",
            "third_party_emulator.droid4x_paths_present",
            "third_party_emulator.android_x86_generic_paths_present",
        )
        for (key in vendorKeys) {
            val ev = result.evidence.find { it.key == key }
            assertEquals("none", ev?.value, "expected `none` for $key")
        }
    }

    // ── Path-list invariants ─────────────────────────────────────────────────

    @Test
    fun `NOX_PATHS has exactly 2 entries`() {
        assertEquals(2, ThirdPartyEmulatorArtifactsProbe.NOX_PATHS.size)
    }

    @Test
    fun `BLUESTACKS_PATHS has exactly 2 entries`() {
        assertEquals(2, ThirdPartyEmulatorArtifactsProbe.BLUESTACKS_PATHS.size)
    }

    @Test
    fun `vendor lists are disjoint`() {
        val all = listOf(
            ThirdPartyEmulatorArtifactsProbe.NOX_PATHS,
            ThirdPartyEmulatorArtifactsProbe.ANDY_PATHS,
            ThirdPartyEmulatorArtifactsProbe.MEMU_PATHS,
            ThirdPartyEmulatorArtifactsProbe.BLUESTACKS_PATHS,
            ThirdPartyEmulatorArtifactsProbe.DROID4X_PATHS,
            ThirdPartyEmulatorArtifactsProbe.ANDROID_X86_GENERIC_PATHS,
        )
        val flat = all.flatten()
        assertEquals(flat.size, flat.toSet().size, "vendor path lists must be disjoint")
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "fileExists scan for third-party emulator init.rc + fstab " +
                "artifacts: Nox, Andy, MEmu/MicroVirt, BlueStacks, " +
                "Droid4x, generic Android-x86 (Power-13 Gap #4)",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is emulator_third_party_artifacts`() {
        assertEquals("emulator.third_party_artifacts", probe.id)
    }

    @Test
    fun `code-rank is 86 (unique Int slot for fractional inventory 4_5)`() {
        assertEquals(86, probe.rank)
    }

    @Test
    fun `inventoryRank is 4_5 (canonical from inventory_yml)`() {
        assertEquals(4.5, probe.inventoryRank)
    }

    @Test
    fun `probe category is EMULATOR`() {
        assertEquals(ProbeCategory.EMULATOR, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is KERNEL`() {
        assertEquals(AndroidLayer.KERNEL, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

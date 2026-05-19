package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.network.NetworkTypeProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for CpuAbiProbe (emulator.cpu_abi, inventory rank 27).
 */
class CpuAbiProbeTest {

    private val probe = CpuAbiProbe()

    private fun fakeCtx(
        primaryAbi: String? = "arm64-v8a",
        abilist: String? = "arm64-v8a,armeabi-v7a,armeabi",
        model: String? = "Pixel 7",
        throws: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (throws) throw RuntimeException("simulated property service")
            return when (key) {
                CpuAbiProbe.PROP_PRIMARY_ABI -> primaryAbi
                CpuAbiProbe.PROP_ABILIST -> abilist
                CpuAbiProbe.PROP_RO_PRODUCT_MODEL -> model
                else -> null
            }
        }
        override fun fileExists(path: String) = false
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

    // ── Clean real ARM phone ─────────────────────────────────────────────────

    @Test
    fun `Pixel 7 with standard ARM abilist — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "arm64-v8a,armeabi-v7a,armeabi",
            model = "Pixel 7",
        ))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean ARM device — pattern is clean_arm`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_CLEAN_ARM, ev?.value)
    }

    @Test
    fun `clean ARM device — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean ARM device — abi_matches_model_oem evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "cpu.abi_matches_model_oem" }
        assertEquals("true", ev?.value)
    }

    // ── x86 primary (score 1.0) ───────────────────────────────────────────────

    @Test
    fun `primary x86_64 emulator — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "x86_64",
            abilist = "x86_64,x86",
            model = "Android SDK built for x86_64",
        ))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `primary x86 emulator — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "x86",
            abilist = "x86",
            model = "Android SDK built for x86",
        ))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `x86 primary on Pixel-class model — pattern is x86_on_arm_oem_model`() = runBlocking {
        // Model claims Pixel 7 but primary ABI is x86 → cross-source mismatch.
        // More specific pattern label than generic x86_primary.
        val result = probe.run(fakeCtx(
            primaryAbi = "x86_64",
            abilist = "x86_64,x86",
            model = "Pixel 7",
        ))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_X86_ON_ARM_OEM_MODEL, ev?.value)
    }

    @Test
    fun `x86 primary on unknown model — pattern is x86_primary`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "x86_64",
            abilist = "x86_64",
            model = "Unknown Generic Device",
        ))
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_X86_PRIMARY, ev?.value)
    }

    // ── Houdini dual-arch (score 1.0) ────────────────────────────────────────

    @Test
    fun `Houdini bridge ARM plus x86 abilist — score is 1_0`() = runBlocking {
        // Genymotion / Chinese OEM dev kits — primary is arm64-v8a (translated)
        // but abilist exposes x86_64 too.
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "arm64-v8a,armeabi-v7a,armeabi,x86_64,x86",
            model = "Genymotion Android 11",
        ))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_HOUDINI_DUAL_ARCH, ev?.value)
    }

    @Test
    fun `Houdini — has_dual_arch evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "arm64-v8a,x86_64",
        ))
        val ev = result.evidence.find { it.key == "cpu.has_dual_arch" }
        assertEquals("true", ev?.value)
    }

    // ── Empty / unknown abilist (score 0.85) ─────────────────────────────────

    @Test
    fun `empty abilist string — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "",
        ))
        // abilistRaw is "" (non-null but parses to empty list) → fires the
        // empty_or_unknown_abilist rule.
        assertEquals(0.85, result.score)
    }

    @Test
    fun `single unknown ABI in abilist — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "unknown_arch",
        ))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty abilist — pattern is empty_or_unknown_abilist`() = runBlocking {
        val result = probe.run(fakeCtx(primaryAbi = "arm64-v8a", abilist = ""))
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_EMPTY_OR_UNKNOWN_ABILIST, ev?.value)
    }

    // ── Stripped armeabi-only (score 0.85) ───────────────────────────────────

    @Test
    fun `primary armeabi only without v7a or 64 — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "armeabi",
            abilist = "armeabi",
        ))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `stripped armeabi — pattern is stripped_armeabi_only`() = runBlocking {
        val result = probe.run(fakeCtx(primaryAbi = "armeabi", abilist = "armeabi"))
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        assertEquals(CpuAbiProbe.PATTERN_STRIPPED_ARMEABI_ONLY, ev?.value)
    }

    @Test
    fun `armeabi primary with v7a in abilist — NOT stripped`() = runBlocking {
        // The stripped rule requires NO arm64-v8a AND NO x86_64 in abilist.
        // armeabi primary with v7a (no 64-bit) still fires the stripped rule
        // because no 64-bit entry exists.
        val result = probe.run(fakeCtx(
            primaryAbi = "armeabi",
            abilist = "armeabi-v7a,armeabi",
        ))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `armeabi primary with arm64 in abilist — NOT stripped`() = runBlocking {
        // arm64-v8a present in abilist → not stripped → falls through to clean.
        val result = probe.run(fakeCtx(
            primaryAbi = "armeabi",
            abilist = "arm64-v8a,armeabi-v7a,armeabi",
        ))
        assertEquals(0.0, result.score)
    }

    // ── Precedence: x86 dominates everything ─────────────────────────────────

    @Test
    fun `x86 primary plus dual-arch abilist — primary rule wins via cascade`() = runBlocking {
        // Pass an unknown model so the x86_on_arm_oem_model sub-pattern doesn't
        // fire — isolates the bare x86_primary path. Score is still 1.0
        // regardless of which sub-pattern fires.
        val result = probe.run(fakeCtx(
            primaryAbi = "x86_64",
            abilist = "x86_64,arm64-v8a",
            model = "Unknown Generic Device",
        ))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "cpu.pattern" }
        // x86_primary fires first in the cascade (dual-arch would too, but
        // primaryIsX86 short-circuits before that branch).
        assertEquals(CpuAbiProbe.PATTERN_X86_PRIMARY, ev?.value)
    }

    // ── Null accessor / degraded ─────────────────────────────────────────────

    @Test
    fun `null primary and abilist — score is 0_0, pattern clean_arm`() = runBlocking {
        // When both are null, we can't make a claim. Probe returns clean
        // (consistent with "no observation" convention across the suite).
        val result = probe.run(fakeCtx(primaryAbi = null, abilist = null))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null primary and abilist — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(primaryAbi = null, abilist = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(throws = true))
        assertFalse(result.failed)
        assertEquals(0.50, result.confidence)
        assertEquals(0.0, result.score)
    }

    // ── Case-insensitive primary ABI ─────────────────────────────────────────

    @Test
    fun `uppercase X86_64 — still detected as x86 primary`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "X86_64",
            abilist = "X86_64",
        ))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `uppercase ARM64-V8A in abilist — still matches`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "arm64-v8a",
            abilist = "ARM64-V8A,ARMEABI-V7A,ARMEABI",
        ))
        assertEquals(0.0, result.score)
    }

    // ── Evidence rows ─────────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "cpu.supported_abis",
                "cpu.primary_abi",
                "cpu.has_x86",
                "cpu.has_arm",
                "cpu.has_dual_arch",
                "cpu.ro_product_cpu_abi",
                "cpu.abi_matches_model_oem",
                "cpu.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `has_x86 evidence true on x86 emulator`() = runBlocking {
        val result = probe.run(fakeCtx(primaryAbi = "x86_64", abilist = "x86_64,x86"))
        val ev = result.evidence.find { it.key == "cpu.has_x86" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `has_arm evidence true on real ARM phone`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "cpu.has_arm" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `abi_matches_model_oem unknown_model on non-phone-class`() = runBlocking {
        val result = probe.run(fakeCtx(model = "Lenovo Tab P11"))
        val ev = result.evidence.find { it.key == "cpu.abi_matches_model_oem" }
        assertEquals("unknown_model", ev?.value)
    }

    @Test
    fun `abi_matches_model_oem false on x86 with phone-class model`() = runBlocking {
        val result = probe.run(fakeCtx(
            primaryAbi = "x86_64",
            abilist = "x86_64",
            model = "Pixel 7",
        ))
        val ev = result.evidence.find { it.key == "cpu.abi_matches_model_oem" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `primary_abi evidence reads null when supplier returns null`() = runBlocking {
        val result = probe.run(fakeCtx(primaryAbi = null))
        val ev = result.evidence.find { it.key == "cpu.primary_abi" }
        assertEquals("null", ev?.value)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `parseAbiList splits comma-separated`() {
        val list = CpuAbiProbe.parseAbiList("arm64-v8a,armeabi-v7a,armeabi")
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), list)
    }

    @Test
    fun `parseAbiList trims whitespace`() {
        val list = CpuAbiProbe.parseAbiList(" arm64-v8a , armeabi-v7a ")
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), list)
    }

    @Test
    fun `parseAbiList lowercases`() {
        val list = CpuAbiProbe.parseAbiList("ARM64-V8A,ARMEABI")
        assertEquals(listOf("arm64-v8a", "armeabi"), list)
    }

    @Test
    fun `parseAbiList handles null and empty`() {
        assertEquals(emptyList<String>(), CpuAbiProbe.parseAbiList(null))
        assertEquals(emptyList<String>(), CpuAbiProbe.parseAbiList(""))
    }

    @Test
    fun `parseAbiList drops empty entries from double commas`() {
        val list = CpuAbiProbe.parseAbiList("arm64-v8a,,armeabi")
        assertEquals(listOf("arm64-v8a", "armeabi"), list)
    }

    @Test
    fun `isX86 covers all x86 strings case-insensitively`() {
        assertTrue(CpuAbiProbe.isX86("x86"))
        assertTrue(CpuAbiProbe.isX86("x86_64"))
        assertTrue(CpuAbiProbe.isX86("X86_64"))
        assertFalse(CpuAbiProbe.isX86("arm64-v8a"))
        assertFalse(CpuAbiProbe.isX86(null))
    }

    @Test
    fun `isArm covers all ARM strings case-insensitively`() {
        assertTrue(CpuAbiProbe.isArm("arm64-v8a"))
        assertTrue(CpuAbiProbe.isArm("armeabi-v7a"))
        assertTrue(CpuAbiProbe.isArm("armeabi"))
        assertTrue(CpuAbiProbe.isArm("ARM64-V8A"))
        assertFalse(CpuAbiProbe.isArm("x86_64"))
        assertFalse(CpuAbiProbe.isArm(null))
    }

    // ── Rank-25 isPhoneClassModel reuse invariant ────────────────────────────

    @Test
    fun `phone class detection reuses NetworkTypeProbe (rank-25) helper`() {
        // Same drift-safe pattern as rank-17 anchoring rank-11 constants.
        // If NetworkTypeProbe.isPhoneClassModel ever stops accepting Pixel/
        // Galaxy/OnePlus, this test fails immediately — surfacing the cross-
        // probe coupling.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("OnePlus 11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Build.SUPPORTED_ABIS + ro.product.cpu.abilist; detect x86 " +
                "primary on ARM-claimed model, dual-arch Houdini bridge, and " +
                "minimal emulator-stripped ABI lists",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is emulator_cpu_abi`() {
        assertEquals("emulator.cpu_abi", probe.id)
    }

    @Test
    fun `probe rank is 27`() {
        assertEquals(27, probe.rank)
    }

    @Test
    fun `probe category is EMULATOR`() {
        assertEquals(ProbeCategory.EMULATOR, probe.category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-27 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, probe.severity)
    }

    @Test
    fun `probe android layer is KERNEL`() {
        assertEquals(AndroidLayer.KERNEL, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
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

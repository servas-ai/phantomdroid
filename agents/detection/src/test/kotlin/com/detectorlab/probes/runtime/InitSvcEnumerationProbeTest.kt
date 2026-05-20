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
 * Unit tests for InitSvcEnumerationProbe (rank 3.7, code-rank 91,
 * Power-13 Gap #2).
 */
class InitSvcEnumerationProbeTest {

    private val probe = InitSvcEnumerationProbe()

    private val cleanAosp: Map<String, String> = mapOf(
        "zygote" to "running",
        "servicemanager" to "running",
        "surfaceflinger" to "running",
        "mediaserver" to "running",
        "installd" to "running",
        "netd" to "running",
    )

    private fun fakeCtx(
        svcMap: Map<String, String> = cleanAosp,
        accessorThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
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
        override fun queryInitSvcProps(): Map<String, String> {
            if (accessorThrows) throw RuntimeException("simulated init.svc accessor throw")
            return svcMap
        }
    }

    // ── Magisk literal — score 1.0 ───────────────────────────────────────────

    @Test
    fun `magisk literal service — score is 1_0`() = runBlocking {
        val withMagisk = cleanAosp + ("magiskd" to "running")
        val result = probe.run(fakeCtx(svcMap = withMagisk))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `magisk literal — pattern is magisk_literal_service`() = runBlocking {
        val withMagisk = cleanAosp + ("magiskd_helper" to "running")
        val result = probe.run(fakeCtx(svcMap = withMagisk))
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_MAGISK_LITERAL_SERVICE, ev?.value)
    }

    @Test
    fun `MAGISK uppercase substring — score is 1_0 (case-insensitive)`() = runBlocking {
        val withMagisk = cleanAosp + ("MAGISKDaemon" to "running")
        val result = probe.run(fakeCtx(svcMap = withMagisk))
        assertEquals(1.0, result.score)
    }

    // ── Random-shape injection — score 0.95 ──────────────────────────────────

    @Test
    fun `3 random-shape hex services — score is 0_95`() = runBlocking {
        val withRandom = cleanAosp + mapOf(
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withRandom))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `random-shape injection — pattern is random_shape_injection`() = runBlocking {
        val withRandom = cleanAosp + mapOf(
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withRandom))
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_RANDOM_SHAPE_INJECTION, ev?.value)
    }

    @Test
    fun `random-shape injection — random_shape_hits lists hex names`() = runBlocking {
        val withRandom = cleanAosp + mapOf(
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withRandom))
        val ev = result.evidence.find { it.key == "init_svc.random_shape_hits" }
        val hits = ev?.value?.toString()?.split(",")?.toSet() ?: emptySet()
        assertEquals(setOf("5a3f1c2b", "8d6e9700", "f1a2b3c4"), hits)
    }

    // ── Few unknown services — score 0.50 ────────────────────────────────────

    @Test
    fun `1 unknown service non-random — score is 0_50`() = runBlocking {
        // "weirdservicename" is not in AOSP set, not OEM prefix, and
        // has vowels (not hex-only) → few_unknown rule fires.
        val withUnknown = cleanAosp + ("weirdservicename" to "running")
        val result = probe.run(fakeCtx(svcMap = withUnknown))
        assertEquals(0.50, result.score)
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_FEW_UNKNOWN_SERVICES, ev?.value)
    }

    @Test
    fun `2 unknown non-random services — score is 0_50`() = runBlocking {
        val withUnknown = cleanAosp + mapOf(
            "myweirdservice" to "running",
            "anotherweirdthing" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withUnknown))
        assertEquals(0.50, result.score)
    }

    // ── Clean ────────────────────────────────────────────────────────────────

    @Test
    fun `pure AOSP services — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `pure AOSP — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `OEM-prefixed services pass — score is 0_0`() = runBlocking {
        val withOem = cleanAosp + mapOf(
            "sec.knoxguard" to "running",
            "google.firewall" to "running",
            "qti.thermal-engine" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withOem))
        assertEquals(0.0, result.score)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `empty service map — pattern is no_observation, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(svcMap = emptyMap()))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `empty service map — confidence is 0_50 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(svcMap = emptyMap()))
        assertEquals(0.50, result.confidence)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `magisk literal + random-shape — magisk wins (1_0 over 0_95)`() = runBlocking {
        val withBoth = cleanAosp + mapOf(
            "magiskd" to "running",
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withBoth))
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_MAGISK_LITERAL_SERVICE, ev?.value)
    }

    @Test
    fun `random-shape + few unknowns — random_shape wins (0_95 over 0_50)`() = runBlocking {
        // Three random-shape hex services trigger random_shape; one
        // additional non-random unknown service exists but the
        // dispositive rule wins.
        val withMixed = cleanAosp + mapOf(
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
            "weirdservice" to "running",
        )
        val result = probe.run(fakeCtx(svcMap = withMixed))
        assertEquals(0.95, result.score)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `isMagiskLiteral detects magisk substring`() {
        assertTrue(InitSvcEnumerationProbe.isMagiskLiteral("magiskd"))
        assertTrue(InitSvcEnumerationProbe.isMagiskLiteral("MAGISKDaemon"))
        assertTrue(InitSvcEnumerationProbe.isMagiskLiteral("my_magisk_service"))
    }

    @Test
    fun `isMagiskLiteral rejects unrelated names`() {
        assertFalse(InitSvcEnumerationProbe.isMagiskLiteral("zygote"))
        assertFalse(InitSvcEnumerationProbe.isMagiskLiteral("surfaceflinger"))
    }

    @Test
    fun `hasKnownOemPrefix matches sec dot prefix`() {
        assertTrue(InitSvcEnumerationProbe.hasKnownOemPrefix("sec.knoxguard"))
    }

    @Test
    fun `hasKnownOemPrefix matches sec underscore prefix`() {
        assertTrue(InitSvcEnumerationProbe.hasKnownOemPrefix("sec_secbio"))
    }

    @Test
    fun `hasKnownOemPrefix rejects bare known-prefix word`() {
        // `sec` without separator is NOT an OEM prefix — must be
        // `sec.` or `sec_` to count.
        assertFalse(InitSvcEnumerationProbe.hasKnownOemPrefix("section"))
    }

    @Test
    fun `hasRandomShape detects 8-char hex`() {
        assertTrue(InitSvcEnumerationProbe.hasRandomShape("5a3f1c2b"))
    }

    @Test
    fun `hasRandomShape rejects short names`() {
        // 5 chars below the threshold.
        assertFalse(InitSvcEnumerationProbe.hasRandomShape("abc12"))
    }

    @Test
    fun `hasRandomShape rejects names with non-hex characters`() {
        assertFalse(InitSvcEnumerationProbe.hasRandomShape("zygotexx"))
        assertFalse(InitSvcEnumerationProbe.hasRandomShape("running1"))
    }

    @Test
    fun `findUnknownServices filters AOSP and OEM`() {
        val obs = setOf("zygote", "sec.knoxguard", "myweirdservice", "magiskd")
        val unknown = InitSvcEnumerationProbe.findUnknownServices(obs)
        // zygote (AOSP), sec.knoxguard (OEM), magiskd (literal filtered)
        // → only "myweirdservice" remains.
        assertEquals(setOf("myweirdservice"), unknown)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `accessor throws — does not crash, no_observation pattern`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "init_svc.pattern" }
        assertEquals(InitSvcEnumerationProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── Evidence schema ──────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "init_svc.service_count",
                "init_svc.magisk_literal_hits",
                "init_svc.unknown_service_count",
                "init_svc.random_shape_hits",
                "init_svc.unknown_services_sample",
                "init_svc.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `unknown_services_sample capped at 10 entries`() = runBlocking {
        val many = cleanAosp + (1..20).associate { "weirdsvc$it" to "running" }
        val result = probe.run(fakeCtx(svcMap = many))
        val ev = result.evidence.find { it.key == "init_svc.unknown_services_sample" }
        val sample = ev?.value?.toString()?.split(",") ?: emptyList()
        assertTrue(sample.size <= 10, "sample size ${sample.size} should be capped at 10")
    }

    @Test
    fun `service_count reflects map size`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "init_svc.service_count" }
        assertEquals(cleanAosp.size.toString(), ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Enumerate init.svc.<name> properties; compare observed " +
                "service-name set against curated AOSP_KNOWN_SERVICES " +
                "baseline + OEM-prefix allowlist; detect Magisk-injected " +
                "services by literal `magisk` substring OR random-shape " +
                "(hex-only) name pattern (Power-13 Gap #2).",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is runtime_init_svc_enumeration`() {
        assertEquals("runtime.init_svc_enumeration", probe.id)
    }

    @Test
    fun `code-rank is 91`() {
        assertEquals(91, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_7`() {
        assertEquals(3.7, probe.inventoryRank)
    }

    @Test
    fun `probe category is RUNTIME`() {
        assertEquals(ProbeCategory.RUNTIME, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

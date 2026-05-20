package com.detectorlab.probes.integrity

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
 * Unit tests for AppSignatureProbe (integrity.app_signature, rank 60,
 * mitigation_layer L4).
 *
 * Exercises the package-name scan for tamper frameworks:
 *   • SpoofStack-tell prefix branch (1.00)
 *   • Xposed-family regex branch (0.85)
 *   • PM-threw degraded path (score 0.00 / confidence 0.50 / failed=false)
 *   • Clean baseline (0.00)
 *   • Probe metadata invariants
 */
class AppSignatureProbeTest {

    private val probe = AppSignatureProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    /**
     * Builds a ProbeContext whose `queryPackageManager()` returns a view
     * backed by [packages]. If [throwOnPm] is true, the accessor itself
     * throws — this exercises the "PM unavailable" degraded path.
     */
    private fun fakeCtx(
        packages: List<String> = emptyList(),
        throwOnPm: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView {
            if (throwOnPm) error("PackageManager unavailable")
            return object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = packageName in packages
                override fun listInstalledPackages() = packages
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    /** A minimal AOSP-clean package list — no tamper-framework artefacts. */
    private fun cleanPackages(): List<String> = listOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.gms",
    )

    // ── Test 1: clean baseline ────────────────────────────────────────────────

    @Test
    fun `clean baseline produces score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPackages()))
        assertFalse(result.failed, "clean context must not fail the probe")
        assertEquals(0.0, result.score)
        assertEquals(AppSignatureProbe.CONFIDENCE_PM_QUERYABLE, result.confidence)
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        val familyEv = result.evidence.find { it.key == AppSignatureProbe.EV_XPOSED_FAMILY }
        assertEquals("absent", tellEv?.value)
        assertEquals("absent", familyEv?.value)
    }

    // ── Test 2: SpoofStack umbrella prefix → 1.00 ─────────────────────────────

    @Test
    fun `io_spoofstack tell package present scores 1_0`() = runBlocking {
        val packages = cleanPackages() + "io.spoofstack.core"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        assertEquals("io.spoofstack.core", tellEv?.value)
    }

    // ── Test 3: Rovo Xposed installer → 1.00 (SpoofStack-tell prefix) ─────────

    @Test
    fun `de_robv_android_xposed_installer scores 1_0 via SpoofStack tell prefix`() = runBlocking {
        // The de.robv.android.xposed.* prefix is in SPOOFSTACK_TELL_PREFIXES
        // (Signal 1, score 1.00). The Xposed-family regex ALSO matches but
        // max-wins picks 1.00.
        val packages = cleanPackages() + "de.robv.android.xposed.installer"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        assertEquals("de.robv.android.xposed.installer", tellEv?.value)
    }

    // ── Test 4: LSPosed manager (org.lsposed.*) → 1.00 (SpoofStack-tell) ──────

    @Test
    fun `org_lsposed_manager scores 1_0 via SpoofStack tell prefix`() = runBlocking {
        // org.lsposed.* is in SPOOFSTACK_TELL_PREFIXES (Signal 1, 1.00).
        val packages = cleanPackages() + "org.lsposed.manager"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        assertEquals("org.lsposed.manager", tellEv?.value)
    }

    // ── Test 5: io.va.exposed (VirtualXposed) → 0.85 (Xposed family only) ─────

    @Test
    fun `io_va_exposed scores 0_85 via Xposed family regex`() = runBlocking {
        // io.va.exposed matches XPOSED_FAMILY_REGEX but NOT SPOOFSTACK_TELL_PREFIXES,
        // so the score is 0.85 (Signal 2 only).
        val packages = cleanPackages() + "io.va.exposed"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
        val familyEv = result.evidence.find { it.key == AppSignatureProbe.EV_XPOSED_FAMILY }
        assertEquals("io.va.exposed", familyEv?.value)
        // SpoofStack-tell should be absent.
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        assertEquals("absent", tellEv?.value)
    }

    // ── Test 6: io.github.lsposed.* → 0.85 (Xposed family only) ───────────────

    @Test
    fun `io_github_lsposed_manager scores 0_85 via Xposed family regex`() = runBlocking {
        // io.github.lsposed.* matches XPOSED_FAMILY_REGEX but NOT
        // SPOOFSTACK_TELL_PREFIXES (which is org.lsposed.*, not io.github.lsposed.*).
        val packages = cleanPackages() + "io.github.lsposed.manager"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(0.85, result.score)
        val familyEv = result.evidence.find { it.key == AppSignatureProbe.EV_XPOSED_FAMILY }
        assertEquals("io.github.lsposed.manager", familyEv?.value)
    }

    // ── Test 7: PM accessor throws → degraded path ───────────────────────────

    @Test
    fun `PackageManager accessor throws — score 0 failed false confidence 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(packages = emptyList(), throwOnPm = true))
        assertFalse(result.failed, "PM-threw must NOT fail the probe (conservative no-signal)")
        assertEquals(0.0, result.score)
        assertEquals(AppSignatureProbe.CONFIDENCE_PM_THREW, result.confidence)
        // Evidence keys still present.
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        val familyEv = result.evidence.find { it.key == AppSignatureProbe.EV_XPOSED_FAMILY }
        assertEquals("absent", tellEv?.value)
        assertEquals("absent", familyEv?.value)
    }

    // ── Test 8: empty package list → clean ────────────────────────────────────

    @Test
    fun `empty package list scores 0_0 with full confidence`() = runBlocking {
        val result = probe.run(fakeCtx(packages = emptyList()))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        // Accessor returned a (empty) list — that's a successful query, so
        // confidence is at the queryable cap, not the threw cap.
        assertEquals(AppSignatureProbe.CONFIDENCE_PM_QUERYABLE, result.confidence)
    }

    // ── Test 9: multiple tells co-present → max wins ─────────────────────────

    @Test
    fun `SpoofStack tell plus Xposed family co-present — score 1_0`() = runBlocking {
        val packages = cleanPackages() +
            "io.spoofstack.runtime" +
            "io.va.exposed" +
            "io.github.lsposed.manager"
        val result = probe.run(fakeCtx(packages))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        // Tell evidence reports the FIRST SpoofStack-tell match.
        val tellEv = result.evidence.find { it.key == AppSignatureProbe.EV_SPOOFSTACK_TELL }
        assertEquals("io.spoofstack.runtime", tellEv?.value)
        // Xposed-family evidence still reports its first match alongside.
        val familyEv = result.evidence.find { it.key == AppSignatureProbe.EV_XPOSED_FAMILY }
        assertEquals("io.va.exposed", familyEv?.value)
    }

    // ── Test 10: method tag is wired through ──────────────────────────────────

    @Test
    fun `evidence includes method tag and ProbeResult method string matches`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPackages()))
        val methodEv = result.evidence.find { it.key == AppSignatureProbe.EV_METHOD }
        assertEquals("package-name-scan-for-tamper-frameworks", methodEv?.value)
        assertEquals("package-name-scan-for-tamper-frameworks", result.method)
    }

    // ── Test 11: false-positive guard — io.va.exposed_substring_not_anchored ──

    @Test
    fun `regex is anchored — non-prefix io_va_exposed substring does NOT match`() = runBlocking {
        // A package like `com.foo.io.va.exposed.fake` must NOT match because
        // XPOSED_FAMILY_REGEX is anchored with ^. This is the regression guard.
        val packages = cleanPackages() + "com.foo.io.va.exposed.fake"
        val result = probe.run(fakeCtx(packages))
        assertEquals(0.0, result.score)
    }

    // ── Test 12: probe metadata block ─────────────────────────────────────────

    @Test
    fun `probe id is integrity_app_signature`() {
        assertEquals("integrity.app_signature", probe.id)
    }

    @Test
    fun `probe rank is 60`() {
        assertEquals(60, probe.rank)
        assertEquals(60.0, probe.inventoryRank)
    }

    @Test
    fun `probe category is INTEGRITY`() {
        assertEquals(ProbeCategory.INTEGRITY, probe.category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        assertEquals(ProbeSeverity.MEDIUM, probe.severity)
    }

    @Test
    fun `probe android layer is APPLICATION`() {
        assertEquals(AndroidLayer.APPLICATION, probe.androidLayer)
    }

    @Test
    fun `probe budget is 200ms`() {
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx(cleanPackages()))
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

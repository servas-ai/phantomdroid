package com.detectorlab.probes.runtime

import com.detectorlab.core.KeyguardManagerView
import com.detectorlab.core.MediaProjectionManagerView
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.UnknownKeyguardManagerView
import com.detectorlab.core.UnknownMediaProjectionManagerView
import com.detectorlab.core.UserHandleView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for MultiInstanceProbe (CLO-6, A17 N8, freeRASP T13).
 *
 * Acceptance criteria:
 *   (1) `UserHandle.myUserId() != 0` → secondary-user signal fires.
 *   (2) Package-name suffix patterns (`.parallel`, `.dual`, `.clone`, `.alpha`)
 *       → clone-suffix signal fires.
 *   (3) MIUI / Samsung Secure-Folder system-package presence → OEM-framework
 *       signal fires.
 *   (4) Regression: suffix matcher MUST NOT match legitimate `:remote` process
 *       names.
 *   (5) Talsec disclosure → confidence == CONFIDENCE_MEDIUM (0.60) always,
 *       even when detected.
 */
class MultiInstanceProbeTest {

    private val probe = MultiInstanceProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        userId: Int? = 0,
        packages: List<String> = emptyList(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = packageName in packages
            override fun listInstalledPackages() = packages
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView
        override fun queryMediaProjectionManager(): MediaProjectionManagerView =
            UnknownMediaProjectionManagerView
        override fun queryUserHandle(): UserHandleView = object : UserHandleView {
            override fun myUserId(): Int? = userId
        }
    }

    // ── (1) UserHandle secondary-user check ───────────────────────────────────

    @Test
    fun `primary user (userId=0) scores 0`() = runBlocking {
        val result = probe.run(fakeCtx(userId = 0))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001)
        val ev = result.evidence.first { it.key == "user_handle.is_secondary_user" }
        assertEquals(false, ev.value)
    }

    @Test
    fun `secondary user (userId=10) fires with score 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(userId = 10))
        assertFalse(result.failed)
        assertEquals(MultiInstanceProbe.SCORE_SECONDARY_USER, result.score, 0.001)
        val ev = result.evidence.first { it.key == "user_handle.is_secondary_user" }
        assertEquals(true, ev.value)
    }

    @Test
    fun `userId null treated as unknown but does not score`() = runBlocking {
        val result = probe.run(fakeCtx(userId = null))
        assertFalse(result.failed)
        val ev = result.evidence.first { it.key == "user_handle.my_user_id" }
        assertEquals("unknown", ev.value)
        // unknown userId must not contribute to score on its own
        val secondaryEv = result.evidence.first { it.key == "user_handle.is_secondary_user" }
        assertEquals(false, secondaryEv.value)
    }

    // ── (2) Clone-suffix package scan ─────────────────────────────────────────

    @Test
    fun `parallel suffix detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.example.app.parallel")))
        assertFalse(result.failed)
        assertEquals(MultiInstanceProbe.SCORE_CLONE_SUFFIX, result.score, 0.001)
        val ev = result.evidence.first { it.key == "packages.clone_suffix_count" }
        assertEquals(1, ev.value)
    }

    @Test
    fun `dual suffix detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.foo.dual")))
        assertEquals(MultiInstanceProbe.SCORE_CLONE_SUFFIX, result.score, 0.001)
    }

    @Test
    fun `clone suffix detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.bar.clone")))
        assertEquals(MultiInstanceProbe.SCORE_CLONE_SUFFIX, result.score, 0.001)
    }

    @Test
    fun `alpha suffix detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.baz.alpha")))
        assertEquals(MultiInstanceProbe.SCORE_CLONE_SUFFIX, result.score, 0.001)
    }

    @Test
    fun `no clone suffix package scores 0`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.example.app", "com.another.thing")))
        assertEquals(0.0, result.score, 0.001)
        val ev = result.evidence.first { it.key == "packages.clone_suffix_count" }
        assertEquals(0, ev.value)
    }

    // ── (4) Regression: suffix matcher must NOT match :remote process names ───

    @Test
    fun `remote process name suffix not matched - contains colon`() = runBlocking {
        // com.google.android.gms:remote is a process name, NOT a package name.
        // It must never be mistaken for a clone-suffix package.
        val processNames = listOf(
            "com.google.android.gms:remote",
            "com.android.systemui:remote",
            "com.example.app:remote",
        )
        val result = probe.run(fakeCtx(packages = processNames))
        assertFalse(result.failed)
        assertEquals(0.0, result.score, 0.001,
            "Process names with ':remote' suffix must not trigger the clone-suffix detector")
        val countEv = result.evidence.first { it.key == "packages.clone_suffix_count" }
        assertEquals(0, countEv.value,
            "No clone-suffix entries expected when input is process names with ':remote'")
    }

    @Test
    fun `findCloneSuffixPackages filters colon entries`() {
        val pm = object : com.detectorlab.core.PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = listOf(
                "com.google.android.gms:remote",   // process name — must be filtered
                "com.example.app.parallel",          // real clone package — must match
                "com.another.app:clone",             // process name with clone keyword — must be filtered
            )
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        val found = findCloneSuffixPackages(pm)
        assertEquals(listOf("com.example.app.parallel"), found,
            "Only true package names (no ':') ending with a clone suffix must be returned")
    }

    // ── (3) OEM multi-instance framework detection ────────────────────────────

    @Test
    fun `MIUI appclone package detected with high score`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.miui.appclone")))
        assertFalse(result.failed)
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001)
        val ev = result.evidence.first { it.key == "packages.oem_framework_found" }
        assertEquals("com.miui.appclone", ev.value)
    }

    @Test
    fun `MIUI dualapps package detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.miui.dualapps")))
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001)
    }

    @Test
    fun `Samsung Knox Secure Folder package detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.samsung.knox.securefolder")))
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001)
    }

    @Test
    fun `Samsung Knox containeragent detected`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.samsung.android.knox.containeragent")))
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001)
    }

    @Test
    fun `unknown OEM package not false-positive`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.unknown.vendor.appmanager")))
        assertEquals(0.0, result.score, 0.001)
        val ev = result.evidence.first { it.key == "packages.oem_framework_found" }
        assertEquals("none", ev.value)
    }

    // ── (5) Talsec disclosure → confidence always medium ─────────────────────

    @Test
    fun `clean device confidence is MEDIUM (0_60)`() = runBlocking {
        val result = probe.run(fakeCtx(userId = 0))
        assertEquals(MultiInstanceProbe.CONFIDENCE_MEDIUM, result.confidence, 0.001,
            "Talsec disclosure mandates confidence=medium even on clean result")
    }

    @Test
    fun `detected secondary user confidence is still MEDIUM (0_60)`() = runBlocking {
        val result = probe.run(fakeCtx(userId = 10))
        assertEquals(MultiInstanceProbe.CONFIDENCE_MEDIUM, result.confidence, 0.001,
            "Talsec disclosure mandates confidence=medium even when detected")
    }

    @Test
    fun `method string includes talsec disclosure note`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertNotNull(result.method)
        assertTrue(
            result.method!!.contains("talsec_disclosure"),
            "method must document the Talsec disclosure; got: ${result.method}",
        )
    }

    // ── Score combination ─────────────────────────────────────────────────────

    @Test
    fun `score is max of signals not sum`() = runBlocking {
        // OEM framework (0.80) + clone suffix (0.60) → max = 0.80, not sum = 1.40
        val result = probe.run(fakeCtx(
            packages = listOf("com.miui.appclone", "com.example.app.parallel"),
        ))
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001,
            "Score must be max of signals, not sum")
    }

    @Test
    fun `secondary user plus OEM framework uses OEM score as max`() = runBlocking {
        val result = probe.run(fakeCtx(
            userId = 5,
            packages = listOf("com.samsung.knox.securefolder"),
        ))
        // max(0.70, 0.80) = 0.80
        assertEquals(MultiInstanceProbe.SCORE_OEM_FRAMEWORK, result.score, 0.001)
    }

    // ── Evidence schema invariants ────────────────────────────────────────────

    @Test
    fun `evidence contains all required keys`() = runBlocking {
        val ev = probe.run(fakeCtx()).evidence.map { it.key }.toSet()
        assertTrue("user_handle.my_user_id" in ev)
        assertTrue("user_handle.is_secondary_user" in ev)
        assertTrue("packages.clone_suffix_found" in ev)
        assertTrue("packages.clone_suffix_count" in ev)
        assertTrue("packages.oem_framework_found" in ev)
    }

    @Test
    fun `clone_suffix_found evidence is none when no clone packages`() = runBlocking {
        val result = probe.run(fakeCtx(packages = emptyList()))
        val ev = result.evidence.first { it.key == "packages.clone_suffix_found" }
        assertEquals("none", ev.value)
    }

    @Test
    fun `clone_suffix_found evidence shows first matching package`() = runBlocking {
        val result = probe.run(fakeCtx(packages = listOf("com.example.app.dual")))
        val ev = result.evidence.first { it.key == "packages.clone_suffix_found" }
        assertEquals("com.example.app.dual", ev.value)
    }

    // ── Probe metadata invariants ─────────────────────────────────────────────

    @Test
    fun `probe id matches inventory entry`() {
        assertEquals("runtime.multi_instance", probe.id)
    }

    @Test
    fun `probe rank is A17 N8 (68)`() {
        assertEquals(68, probe.rank)
        assertTrue(probe.rank in 61..71, "rank ${probe.rank} outside A17 reservation 61..71")
    }

    @Test
    fun `probe budget within 5-second ceiling`() {
        assertTrue(probe.budgetMs <= 5000L)
    }

    @Test
    fun `probe runtime fits within budget on clean fast path`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }

    @Test
    fun `probe is deterministic for identical input`() = runBlocking {
        val ctx = fakeCtx(userId = 10, packages = listOf("com.example.app.parallel"))
        val r1 = probe.run(ctx)
        val r2 = probe.run(ctx)
        assertEquals(r1.score, r2.score, 0.001)
        assertEquals(r1.confidence, r2.confidence, 0.001)
    }

    // ── Failure path ──────────────────────────────────────────────────────────

    @Test
    fun `throwing PackageManager surfaces as ProbeResult failed`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? = null
            override fun fileExists(path: String) = false
            override fun readFile(path: String, maxBytes: Int): String? = null
            override fun querySettingSecure(key: String): String? = null
            override fun queryTelephonyManager(field: TelephonyField): String? = null
            override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = false
                override fun listInstalledPackages(): List<String> =
                    throw RuntimeException("pm exploded")
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
            override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
                override fun listSensorTypes() = emptyList<Int>()
                override fun sampleSensor(sensorType: Int, durationMs: Long) =
                    SensorSample(LongArray(0), emptyArray())
            }
            override fun queryKeyguardManager() = UnknownKeyguardManagerView
            override fun queryMediaProjectionManager() = UnknownMediaProjectionManagerView
        }
        val result = probe.run(ctx)
        assertTrue(result.failed)
        assertFalse(result.skipped)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("MultiInstanceProbe"))
    }
}

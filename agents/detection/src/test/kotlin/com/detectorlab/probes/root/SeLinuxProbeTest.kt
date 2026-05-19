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
 * Unit tests for SeLinuxProbe (root.selinux, inventory rank 14).
 */
class SeLinuxProbeTest {

    private val probe = SeLinuxProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        enforceContent: String? = "1",
        bootSelinux: String? = "enforcing",
        buildSelinux: String? = "1",
        policyExists: Boolean = true,
        readFileThrows: Boolean = false,
        getSystemPropertyThrows: Boolean = false,
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (getSystemPropertyThrows) throw RuntimeException("simulated prop service unavailable")
            return when (key) {
                SeLinuxProbe.PROP_RO_BOOT_SELINUX -> bootSelinux
                SeLinuxProbe.PROP_RO_BUILD_SELINUX -> buildSelinux
                else -> null
            }
        }
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated EACCES")
            return path == SeLinuxProbe.PATH_SELINUX_POLICY && policyExists
        }
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated EACCES")
            return if (path == SeLinuxProbe.PATH_SELINUX_ENFORCE) enforceContent else null
        }
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

    // ── Clean Enforcing device ────────────────────────────────────────────────

    @Test
    fun `clean Enforcing — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean Enforcing — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean Enforcing — enforce evidence is 1`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == SeLinuxProbe.PATH_SELINUX_ENFORCE }
        assertEquals("1", ev?.value)
        assertEquals("1", ev?.expected)
    }

    @Test
    fun `clean Enforcing — selinux_kernel_support evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "selinux_kernel_support" }
        assertEquals("true", ev?.value)
    }

    // ── /sys/fs/selinux/enforce reads "0" (Permissive) ────────────────────────

    @Test
    fun `enforce file says permissive — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(enforceContent = "0"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `enforce file with trailing newline reads permissive — score is 1_0`() = runBlocking {
        // /sys/fs/selinux/enforce on a real kernel returns "0\n" — the probe
        // trims whitespace before comparison.
        val result = probe.run(fakeCtx(enforceContent = "0\n"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `enforce file says enforcing — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(enforceContent = "1"))
        assertEquals(0.0, result.score)
    }

    // ── ro.boot.selinux unlocked states ───────────────────────────────────────

    @Test
    fun `ro_boot_selinux is permissive — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(bootSelinux = "permissive"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_boot_selinux is disabled — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(bootSelinux = "disabled"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_boot_selinux uppercase PERMISSIVE — score is 1_0 (case-insensitive)`() = runBlocking {
        // OEM bootloaders sometimes write the property in mixed case; the
        // probe lowercases before comparison so policy detection is robust.
        val result = probe.run(fakeCtx(bootSelinux = "PERMISSIVE"))
        assertEquals(1.0, result.score)
    }

    // ── Inconsistent signal (ro.build.selinux=0 but Enforcing elsewhere) ──────

    @Test
    fun `ro_build_selinux 0 with enforce 1 — score is 0_85 (inconsistent)`() = runBlocking {
        val result = probe.run(
            fakeCtx(enforceContent = "1", bootSelinux = "enforcing", buildSelinux = "0"),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_build_selinux 0 with ro_boot_selinux enforcing — score is 0_85`() = runBlocking {
        // enforce file absent but ro.boot says enforcing while ro.build says 0
        val result = probe.run(
            fakeCtx(enforceContent = null, bootSelinux = "enforcing", buildSelinux = "0"),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_build_selinux 0 but no other signal says enforcing — score stays 0`() = runBlocking {
        // ro.build=0 alone with no Enforcing assertion elsewhere isn't enough
        // to flag inconsistency — the spec requires "claims Enforcing elsewhere".
        val result = probe.run(
            fakeCtx(enforceContent = null, bootSelinux = null, buildSelinux = "0"),
        )
        assertEquals(0.0, result.score)
    }

    // ── Permissive dominates inconsistency (max wins) ────────────────────────

    @Test
    fun `enforce permissive AND ro_build_selinux 0 with boot enforcing — score is 1_0`() = runBlocking {
        // /sys/fs/selinux/enforce=0 (Permissive) dominates the inconsistency
        // signal (which would be 0.85). Locks in max-wins precedence.
        val result = probe.run(
            fakeCtx(enforceContent = "0", bootSelinux = "enforcing", buildSelinux = "0"),
        )
        assertEquals(1.0, result.score)
    }

    // ── All-empty-props with accessor available ──────────────────────────────

    @Test
    fun `all props empty but accessors work — score is 0_30 (suspicious bare)`() = runBlocking {
        // bootSelinux and buildSelinux are reachable but return empty strings;
        // enforce file returns empty too. The accessors aren't broken, the
        // values just aren't there — strong tampering hint at low confidence.
        val result = probe.run(
            fakeCtx(enforceContent = "", bootSelinux = "", buildSelinux = ""),
        )
        assertEquals(0.30, result.score)
    }

    @Test
    fun `all props null but accessor throws — score stays 0 (cannot conclude)`() = runBlocking {
        // The accessors weren't even reachable, so we can't distinguish
        // tampering from a sandboxed lab build. Score 0.0, confidence drops
        // separately.
        val result = probe.run(
            fakeCtx(
                getSystemPropertyThrows = true,
                readFileThrows = true,
                fileExistsThrows = true,
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Confidence tiers ──────────────────────────────────────────────────────

    @Test
    fun `4 sources accessible — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `2 sources accessible — confidence is 0_95`() = runBlocking {
        // enforce file readable + policy fileExists checked = 2 sources
        val result = probe.run(fakeCtx(bootSelinux = null, buildSelinux = null))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `1 source accessible — confidence is 0_60`() = runBlocking {
        // Only fileExists works (policy check). readFile + getSystemProperty
        // all throw.
        val result = probe.run(
            fakeCtx(
                getSystemPropertyThrows = true,
                readFileThrows = true,
            ),
        )
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `0 sources accessible — confidence is 0_30 (fully degraded)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                getSystemPropertyThrows = true,
                readFileThrows = true,
                fileExistsThrows = true,
            ),
        )
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `properties empty count as zero sources — confidence drops`() = runBlocking {
        // bootSelinux returns "" so the accessor works but the value is empty —
        // doesn't count as a "source accessed" for confidence math.
        val result = probe.run(
            fakeCtx(
                enforceContent = null,
                bootSelinux = "",
                buildSelinux = "",
                policyExists = false,  // policyChecked still true → 1 source
            ),
        )
        // policyChecked = true counts as 1; others count as 0.
        assertEquals(0.60, result.confidence)
    }

    // ── Kernel-support evidence ───────────────────────────────────────────────

    @Test
    fun `policy file absent — selinux_kernel_support evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(policyExists = false))
        val ev = result.evidence.find { it.key == "selinux_kernel_support" }
        assertEquals("false", ev?.value)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all four documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                SeLinuxProbe.PATH_SELINUX_ENFORCE,
                SeLinuxProbe.PROP_RO_BOOT_SELINUX,
                SeLinuxProbe.PROP_RO_BUILD_SELINUX,
                "selinux_kernel_support",
            ),
            keys,
        )
    }

    @Test
    fun `missing values reported as absent in evidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(enforceContent = null, bootSelinux = null, buildSelinux = null),
        )
        assertEquals("absent", result.evidence.find { it.key == SeLinuxProbe.PATH_SELINUX_ENFORCE }?.value)
        assertEquals("absent", result.evidence.find { it.key == SeLinuxProbe.PROP_RO_BOOT_SELINUX }?.value)
        assertEquals("absent", result.evidence.find { it.key == SeLinuxProbe.PROP_RO_BUILD_SELINUX }?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read /sys/fs/selinux/enforce + ro.boot.selinux + ro.build.selinux + " +
                "optional getcon context, score Permissive/inconsistent as root indicator",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is root_selinux`() {
        assertEquals("root.selinux", probe.id)
    }

    @Test
    fun `probe rank is 14`() {
        assertEquals(14, probe.rank)
    }

    @Test
    fun `probe category is ROOT`() {
        assertEquals(ProbeCategory.ROOT, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
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
    fun `probe completes within budget on clean device`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

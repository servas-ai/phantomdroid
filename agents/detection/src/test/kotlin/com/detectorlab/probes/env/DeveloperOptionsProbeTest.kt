package com.detectorlab.probes.env

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
 * Unit tests for DeveloperOptionsProbe (env.developer_options, inventory rank 19).
 */
class DeveloperOptionsProbeTest {

    private val probe = DeveloperOptionsProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        devSettings: String? = "0",
        adb: String? = "0",
        adbWifi: String? = "0",
        verifier: String? = "1",
        roDebuggable: String? = "0",
        secureThrows: Boolean = false,
        propThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propThrows) throw RuntimeException("simulated prop service unavailable")
            return if (key == DeveloperOptionsProbe.PROP_RO_DEBUGGABLE) roDebuggable else null
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? {
            if (secureThrows) throw RuntimeException("simulated SettingNotFoundException")
            return when (key) {
                DeveloperOptionsProbe.SETTING_DEV_SETTINGS_ENABLED -> devSettings
                DeveloperOptionsProbe.SETTING_ADB_ENABLED -> adb
                DeveloperOptionsProbe.SETTING_ADB_WIFI_ENABLED -> adbWifi
                DeveloperOptionsProbe.SETTING_PACKAGE_VERIFIER_ENABLE -> verifier
                else -> null
            }
        }
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

    // ── Clean production device ───────────────────────────────────────────────

    @Test
    fun `clean production — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean production — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── dev_settings AND adb_enabled (the canonical lab signature) ────────────

    @Test
    fun `dev_settings 1 AND adb_enabled 1 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1", adb = "1"))
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev_settings 1 AND adb_enabled 1 — confidence stays 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1", adb = "1"))
        assertEquals(0.95, result.confidence)
    }

    // ── dev_settings only ─────────────────────────────────────────────────────

    @Test
    fun `dev_settings 1 only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `dev_settings 1 only — evidence flags it`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1"))
        val ev = result.evidence.find {
            it.key == "global.${DeveloperOptionsProbe.SETTING_DEV_SETTINGS_ENABLED}"
        }
        assertEquals("1", ev?.value)
    }

    // ── adb_enabled only ─────────────────────────────────────────────────────

    @Test
    fun `adb_enabled 1 only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(adb = "1"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `adb_wifi_enabled 1 only — score is 0_85`() = runBlocking {
        val result = probe.run(fakeCtx(adbWifi = "1"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `adb plus adb_wifi both on — score is 0_85`() = runBlocking {
        // Not summed; the highest single rule wins.
        val result = probe.run(fakeCtx(adb = "1", adbWifi = "1"))
        assertEquals(0.85, result.score)
    }

    // ── Verifier off ──────────────────────────────────────────────────────────

    @Test
    fun `package_verifier_enable 0 only — score is 0_70`() = runBlocking {
        val result = probe.run(fakeCtx(verifier = "0"))
        assertEquals(0.70, result.score)
    }

    @Test
    fun `package_verifier_enable 1 evidence carries expected 1 not 0`() = runBlocking {
        // Regression guard: verifier ON is the desired state, so expected="1"
        // for THIS evidence row, unlike all the others which expect "0".
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find {
            it.key == "global.${DeveloperOptionsProbe.SETTING_PACKAGE_VERIFIER_ENABLE}"
        }
        assertEquals("1", ev?.expected)
    }

    // ── ro.debuggable cap ─────────────────────────────────────────────────────

    @Test
    fun `ro_debuggable 1 alone — score is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(roDebuggable = "1"))
        assertEquals(0.50, result.score)
    }

    @Test
    fun `ro_debuggable 1 plus dev_settings 1 — score is 0_85 (dev dominates)`() = runBlocking {
        // The cap rule: ro.debuggable contributes 0.5 but max-wins so the
        // dev_settings 0.85 dominates. ro.debuggable doesn't escalate.
        val result = probe.run(fakeCtx(devSettings = "1", roDebuggable = "1"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `ro_debuggable 1 plus full dev_and_adb — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1", adb = "1", roDebuggable = "1"))
        assertEquals(1.0, result.score)
    }

    // ── Multi-signal precedence (max wins, never summed) ─────────────────────

    @Test
    fun `verifier off plus adb on — score is 0_85 (adb dominates verifier)`() = runBlocking {
        val result = probe.run(fakeCtx(adb = "1", verifier = "0"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `dev_and_adb plus verifier_off — score still 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(devSettings = "1", adb = "1", verifier = "0"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev_settings without adb but adb_wifi — score is 0_85`() = runBlocking {
        // dev_settings=1 fires 0.85 (devOn && !adbOn);
        // adb_wifi=1 fires 0.85 (adbOn || adbWifiOn — adbWifiOn is true).
        // Both add 0.85 → max is 0.85.
        val result = probe.run(fakeCtx(devSettings = "1", adbWifi = "1"))
        assertEquals(0.85, result.score)
    }

    // ── Confidence tiers based on Settings.Secure source count ────────────────

    @Test
    fun `all 4 Settings keys accessible — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `3 Settings keys accessible — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(adbWifi = null))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `2 Settings keys accessible — confidence is 0_60`() = runBlocking {
        val result = probe.run(fakeCtx(adbWifi = null, verifier = null))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `1 Settings key accessible — confidence is 0_60`() = runBlocking {
        val result = probe.run(fakeCtx(adb = null, adbWifi = null, verifier = null))
        assertEquals(0.60, result.confidence)
    }

    @Test
    fun `0 Settings keys accessible — confidence is 0_30 (fully degraded)`() = runBlocking {
        val result = probe.run(
            fakeCtx(devSettings = null, adb = null, adbWifi = null, verifier = null),
        )
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `ro_debuggable does NOT count toward Settings-source tier`() = runBlocking {
        // Only ro.debuggable is reachable; querySettingSecure returns null for
        // every key. Confidence must drop to DEGRADED (0.30), not climb because
        // a separate property accessor worked.
        val result = probe.run(
            fakeCtx(
                devSettings = null,
                adb = null,
                adbWifi = null,
                verifier = null,
                roDebuggable = "1",
            ),
        )
        assertEquals(0.30, result.confidence)
        // ro.debuggable=1 still raises score even at degraded confidence.
        assertEquals(0.50, result.score)
    }

    // ── querySettingSecure throws ─────────────────────────────────────────────

    @Test
    fun `querySettingSecure throws — no crash, score 0_0`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `querySettingSecure throws — confidence falls to 0_30`() = runBlocking {
        val result = probe.run(fakeCtx(secureThrows = true))
        assertEquals(0.30, result.confidence)
    }

    @Test
    fun `getSystemProperty throws but Settings work — score and confidence intact`() = runBlocking {
        val result = probe.run(fakeCtx(propThrows = true))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 5 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(5, result.evidence.size)
    }

    @Test
    fun `evidence covers all five documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "global.${DeveloperOptionsProbe.SETTING_DEV_SETTINGS_ENABLED}",
                "global.${DeveloperOptionsProbe.SETTING_ADB_ENABLED}",
                "global.${DeveloperOptionsProbe.SETTING_ADB_WIFI_ENABLED}",
                "global.${DeveloperOptionsProbe.SETTING_PACKAGE_VERIFIER_ENABLE}",
                DeveloperOptionsProbe.PROP_RO_DEBUGGABLE,
            ),
            keys,
        )
    }

    @Test
    fun `missing values reported as absent in evidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(devSettings = null, adb = null, adbWifi = null, verifier = null, roDebuggable = null),
        )
        for (ev in result.evidence) {
            assertEquals("absent", ev.value, "expected absent for ${ev.key}, got ${ev.value}")
        }
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read Settings.Global dev/adb/verifier toggles + ro.debuggable, " +
                "score escalation pattern from any-flag → both-flags-set",
            result.method,
        )
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is env_developer_options`() {
        assertEquals("env.developer_options", probe.id)
    }

    @Test
    fun `probe rank is 19`() {
        assertEquals(19, probe.rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
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

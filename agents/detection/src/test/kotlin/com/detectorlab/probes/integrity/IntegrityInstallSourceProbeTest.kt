package com.detectorlab.probes.integrity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SnapshotReplayContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [IntegrityInstallSourceProbe] (integrity.install_source,
 * inventory rank 10.5, code rank 81, mitigation_layer L2).
 *
 * Exercises the install-source allowlist comparison:
 *   - Play Store installer (`com.android.vending`) → 0.05 (clean)
 *   - Samsung Galaxy Store (`com.sec.android.app.samsungapps`) → 0.05
 *   - Null installer → 0.85 (sideload / system pre-install)
 *   - Off-allowlist installer (`com.evil.unofficial`) → 0.95
 *   - Accessor throws → 0.85 with CONFIDENCE_DEGRADED
 *   - Probe metadata invariants
 *   - Replay sanity: Pixel7Clean → clean, RedroidV12 → raw
 */
class IntegrityInstallSourceProbeTest {

    private val probe = IntegrityInstallSourceProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    /**
     * Builds a minimal ProbeContext whose `queryInstallSourcePackage()`
     * returns [installer]. If [throwOnAccessor] is true, the accessor
     * itself throws — this exercises the degraded path.
     */
    private fun fakeCtx(
        installer: String? = null,
        throwOnAccessor: Boolean = false,
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
        override fun queryInstallSourcePackage(): String? {
            if (throwOnAccessor) error("InstallSourceInfo accessor unavailable")
            return installer
        }
    }

    // ── Test 1: Play Store installer → 0.05 (clean) ──────────────────────────

    @Test
    fun `com_android_vending installer scores 0_05 clean`() = runBlocking {
        val result = probe.run(fakeCtx(installer = "com.android.vending"))
        assertFalse(result.failed, "clean installer must not fail the probe")
        assertEquals(IntegrityInstallSourceProbe.SCORE_CLEAN, result.score)
        assertEquals(IntegrityInstallSourceProbe.CONFIDENCE_FULL, result.confidence)

        val installerEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_INSTALLER
        }
        assertEquals("com.android.vending", installerEv?.value)

        val allowlistEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_ALLOWLIST_MATCH
        }
        assertEquals("true", allowlistEv?.value)

        val patternEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_PATTERN
        }
        assertEquals(IntegrityInstallSourceProbe.PATTERN_CLEAN, patternEv?.value)
    }

    // ── Test 2: Samsung Galaxy Store → 0.05 (clean) ──────────────────────────

    @Test
    fun `samsung galaxy store installer scores 0_05 clean`() = runBlocking {
        val result = probe.run(fakeCtx(installer = "com.sec.android.app.samsungapps"))
        assertFalse(result.failed)
        assertEquals(IntegrityInstallSourceProbe.SCORE_CLEAN, result.score)
        val patternEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_PATTERN
        }
        assertEquals(IntegrityInstallSourceProbe.PATTERN_CLEAN, patternEv?.value)
    }

    // ── Test 3: Null installer → 0.85 (sideload) ─────────────────────────────

    @Test
    fun `null installer scores 0_85 unknown sideload`() = runBlocking {
        val result = probe.run(fakeCtx(installer = null))
        assertFalse(result.failed, "null installer is a valid observation, not a failure")
        assertEquals(IntegrityInstallSourceProbe.SCORE_UNKNOWN_INSTALLER, result.score)
        assertEquals(IntegrityInstallSourceProbe.CONFIDENCE_FULL, result.confidence)

        val installerEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_INSTALLER
        }
        assertEquals("null", installerEv?.value)

        val allowlistEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_ALLOWLIST_MATCH
        }
        assertEquals("unknown", allowlistEv?.value)

        val patternEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_PATTERN
        }
        assertEquals(IntegrityInstallSourceProbe.PATTERN_UNKNOWN_INSTALLER, patternEv?.value)
    }

    // ── Test 4: Unofficial installer → 0.95 (dispositive) ────────────────────

    @Test
    fun `com_evil_unofficial installer scores 0_95 dispositive`() = runBlocking {
        val result = probe.run(fakeCtx(installer = "com.evil.unofficial"))
        assertFalse(result.failed)
        assertEquals(IntegrityInstallSourceProbe.SCORE_UNOFFICIAL_INSTALLER, result.score)
        assertEquals(IntegrityInstallSourceProbe.CONFIDENCE_FULL, result.confidence)

        val installerEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_INSTALLER
        }
        assertEquals("com.evil.unofficial", installerEv?.value)

        val allowlistEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_ALLOWLIST_MATCH
        }
        assertEquals("false", allowlistEv?.value)

        val patternEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_PATTERN
        }
        assertEquals(IntegrityInstallSourceProbe.PATTERN_UNOFFICIAL_INSTALLER, patternEv?.value)
    }

    // ── Test 5: F-Droid (a real-world unofficial store) → 0.95 ───────────────

    @Test
    fun `org_fdroid_fdroid installer scores 0_95 unofficial`() = runBlocking {
        // F-Droid is the canonical real-world "unofficial store" — popular,
        // legitimate-but-not-in-allowlist. freeRASP T5 treats it the same
        // as any other non-allowlisted installer.
        val result = probe.run(fakeCtx(installer = "org.fdroid.fdroid"))
        assertFalse(result.failed)
        assertEquals(IntegrityInstallSourceProbe.SCORE_UNOFFICIAL_INSTALLER, result.score)
    }

    // ── Test 6: Accessor throws → degraded confidence ────────────────────────

    @Test
    fun `accessor throw produces unknown pattern with degraded confidence`() = runBlocking {
        val result = probe.run(fakeCtx(throwOnAccessor = true))
        assertFalse(result.failed, "accessor throw is absorbed inside the probe; no failed flag")
        assertEquals(IntegrityInstallSourceProbe.SCORE_UNKNOWN_INSTALLER, result.score)
        assertEquals(IntegrityInstallSourceProbe.CONFIDENCE_DEGRADED, result.confidence)

        val installerEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_INSTALLER
        }
        assertEquals("null", installerEv?.value)

        val allowlistEv = result.evidence.find {
            it.key == IntegrityInstallSourceProbe.EV_ALLOWLIST_MATCH
        }
        assertEquals("unknown", allowlistEv?.value)
    }

    // ── Test 7: Probe metadata invariants ────────────────────────────────────

    @Test
    fun `probe metadata is consistent with inventory rank 10_5`() {
        assertEquals("integrity.install_source", probe.id)
        assertEquals(81, probe.rank)
        assertEquals(10.5, probe.inventoryRank)
        assertEquals(ProbeCategory.INTEGRITY, probe.category)
        assertEquals(ProbeSeverity.HIGH, probe.severity)
        assertEquals(AndroidLayer.APPLICATION, probe.androidLayer)
        assertTrue(probe.budgetMs in 1..5000, "budgetMs must be in [1, 5000]")
    }

    // ── Test 8: Evidence keys are namespaced (cross-cutting #1) ──────────────

    @Test
    fun `evidence keys are install_source dot-prefixed`() = runBlocking {
        val result = probe.run(fakeCtx(installer = "com.android.vending"))
        for (ev in result.evidence) {
            assertTrue(
                ev.key.startsWith("install_source."),
                "Evidence key '${ev.key}' must be prefixed 'install_source.' " +
                    "per cross-cutting #1 — never bare-keyed.",
            )
        }
    }

    // ── Test 9: Replay sanity — Pixel7Clean must score clean ─────────────────

    @Test
    fun `Pixel7Clean snapshot scores below 0_30 spoofed`() = runBlocking {
        // Replay-sanity: factory-clean Pixel 7 (installSourcePackage =
        // "com.android.vending") must score < 0.30 so the
        // CoverageMatrixGeneratorTest classifies the cell as "spoofed"
        // (= real-device answer). Score 0.05 means clean.
        val ctx = SnapshotReplayContext(Pixel7CleanSnapshot.SNAPSHOT)
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(
            result.score < 0.30,
            "Pixel7Clean must score < 0.30 (got ${result.score}) — Play " +
                "Store installer should produce the clean branch.",
        )
        assertEquals(IntegrityInstallSourceProbe.SCORE_CLEAN, result.score)
    }

    // ── Test 10: Replay sanity — RedroidV12 must score raw ───────────────────

    @Test
    fun `RedroidV12 snapshot scores above 0_30 raw`() = runBlocking {
        // Replay-sanity: un-spoofed ReDroid 12 container
        // (installSourcePackage = null = sideloaded via adb) must score
        // > 0.30 so the CoverageMatrixGeneratorTest classifies the cell
        // as "raw" (= emulator/spoof detected). Score 0.85 means
        // "unknown installer" branch fired.
        val ctx = SnapshotReplayContext(RedroidV12Snapshot.SNAPSHOT)
        val result = probe.run(ctx)
        assertFalse(result.failed)
        assertTrue(
            result.score > 0.30,
            "RedroidV12 must score > 0.30 (got ${result.score}) — null " +
                "installer (sideload) should produce the unknown-installer branch.",
        )
        assertEquals(IntegrityInstallSourceProbe.SCORE_UNKNOWN_INSTALLER, result.score)
    }
}

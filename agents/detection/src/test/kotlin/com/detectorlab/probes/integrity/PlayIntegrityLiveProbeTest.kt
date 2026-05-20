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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for PlayIntegrityLiveProbe (integrity.play_integrity, rank 2).
 *
 * Sister probe to PlayIntegrityProbe (rank 71) — this one is the LIVE-verdict
 * inference variant covering the broader STRONG / DEVICE / BASIC tiering
 * surface (GMS presence, bootloader+verity pair, GMS-hijack tells, OEM
 * provisioning marker).
 */
class PlayIntegrityLiveProbeTest {

    private val probe = PlayIntegrityLiveProbe()

    // ── Fake context builder ──────────────────────────────────────────────────

    private fun fakeCtx(
        props: Map<String, String?> = emptyMap(),
        installedPkgs: Set<String> = emptySet(),
        pmThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = props[key]
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView {
            if (pmThrows) throw RuntimeException("PackageManager unavailable")
            return object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) = packageName in installedPkgs
                override fun listInstalledPackages() = installedPkgs.toList()
                override fun listPackagesWithPermission(permission: String) = emptyList<String>()
            }
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    /** Property surface of a clean, provisioned Pixel 7 (all verdicts pass). */
    private fun cleanPixelProps(): Map<String, String> = mapOf(
        PlayIntegrityLiveProbe.PROP_GMS_VERSION to "243532033",
        PlayIntegrityLiveProbe.PROP_CLIENTID_BASE to "android-google-Pixel-7",
        PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
        PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
    )

    // ── Declarative metadata sanity ───────────────────────────────────────────

    @Test
    fun `probe id rank category severity layer match inventory rank 2`() {
        assertEquals("integrity.play_integrity", probe.id)
        assertEquals(2, probe.rank)
        assertEquals(2.0, probe.inventoryRank)
        assertEquals(ProbeCategory.INTEGRITY, probe.category)
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
        assertEquals(AndroidLayer.APPLICATION, probe.androidLayer)
        assertEquals(200L, probe.budgetMs)
    }

    @Test
    fun `probe id is distinct from rank 71 sister probe`() {
        // Cross-rank coexistence guarantee — rank-71's id ends in
        // `_signals`, rank-2's id is the canonical inventory entry.
        val rank71 = PlayIntegrityProbe()
        assertEquals("integrity.play_integrity_signals", rank71.id)
        assertEquals("integrity.play_integrity", probe.id)
        assertTrue(rank71.id != probe.id, "rank-2 and rank-71 must have distinct probe ids")
    }

    // ── 0.95 tier: GMS absent (NO_VERDICT) ────────────────────────────────────

    @Test
    fun `no GMS package AND empty gmsversion scores 0_95 NO_VERDICT`() = runBlocking {
        // Empty installed-packages set + empty/missing gmsversion.
        val result = probe.run(
            fakeCtx(
                props = mapOf(
                    PlayIntegrityLiveProbe.PROP_GMS_VERSION to "",
                    PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
                    PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
                ),
                installedPkgs = setOf("android", "com.android.systemui"),
            )
        )
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
        val verdict = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_PREDICTED_VERDICT }
        assertEquals(PlayIntegrityLiveProbe.VERDICT_NO_VERDICT, verdict?.value)
        val gmsPresent = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_GMS_PRESENT }
        assertEquals(false, gmsPresent?.value)
    }

    // ── 0.95 tier: bootloader unlocked + verity off ───────────────────────────

    @Test
    fun `bootloader unlocked AND verity not enforcing scores 0_95`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_FLASH_LOCKED] = "0"
            this[PlayIntegrityLiveProbe.PROP_VERITYMODE] = ""
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.95, result.score)
        val unlocked = result.evidence.find {
            it.key == PlayIntegrityLiveProbe.EV_BOOTLOADER_UNLOCKED
        }
        assertEquals(true, unlocked?.value)
    }

    @Test
    fun `flash locked but verity disabled fires bootloader rule`() = runBlocking {
        // ro.boot.flash.locked = "0" alone is the load-bearing predicate paired
        // with verity NOT enforcing. Even verity="disabled" (not the enforcing
        // sentinel) trips the 0.95 rule.
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_FLASH_LOCKED] = "0"
            this[PlayIntegrityLiveProbe.PROP_VERITYMODE] = "disabled"
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.95, result.score)
    }

    // ── 0.85 tier: GMS-hijack packages ────────────────────────────────────────

    @Test
    fun `com_google_android_gms_unaltered package scores 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                installedPkgs = setOf(
                    PlayIntegrityLiveProbe.PKG_GMS,
                    PlayIntegrityLiveProbe.PKG_GMS_UNALTERED,
                ),
            )
        )
        assertEquals(0.85, result.score)
        val hijackPkg = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_GMS_HIJACK_PKG }
        assertEquals(PlayIntegrityLiveProbe.PKG_GMS_UNALTERED, hijackPkg?.value)
    }

    @Test
    fun `io_tns_gmsspoof package scores 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                installedPkgs = setOf(
                    PlayIntegrityLiveProbe.PKG_GMS,
                    PlayIntegrityLiveProbe.PKG_GMSSPOOF_IO,
                ),
            )
        )
        assertEquals(0.85, result.score)
        val hijackPkg = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_GMS_HIJACK_PKG }
        assertEquals(PlayIntegrityLiveProbe.PKG_GMSSPOOF_IO, hijackPkg?.value)
    }

    @Test
    fun `com_tns_gmsspoof package scores 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                installedPkgs = setOf(
                    PlayIntegrityLiveProbe.PKG_GMS,
                    PlayIntegrityLiveProbe.PKG_GMSSPOOF_COM,
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    // ── 0.85 tier: unprovisioned clientidbase ─────────────────────────────────

    @Test
    fun `default unprovisioned clientidbase scores 0_85`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            // The bare "android-google" default — a real Pixel reports a
            // versioned string. Bare default = "device was never provisioned".
            this[PlayIntegrityLiveProbe.PROP_CLIENTID_BASE] =
                PlayIntegrityLiveProbe.CLIENTIDBASE_UNPROVISIONED
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `empty clientidbase scores 0_85`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_CLIENTID_BASE] = ""
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.85, result.score)
    }

    // ── 0.70 tier: DEVICE downgrade ───────────────────────────────────────────

    @Test
    fun `ro_gms_disabled equals 1 scores 0_70 DEVICE_BASIC`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_GMS_DISABLED] = "1"
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.70, result.score)
        val verdict = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_PREDICTED_VERDICT }
        assertEquals(PlayIntegrityLiveProbe.VERDICT_DEVICE_BASIC, verdict?.value)
    }

    @Test
    fun `gmsversion equals 0 scores 0_70`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_GMS_VERSION] = "0"
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.70, result.score)
    }

    // ── 0.50 tier: rescue disabled ────────────────────────────────────────────

    @Test
    fun `persist_sys_disable_rescue equals 1 scores 0_50`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            this[PlayIntegrityLiveProbe.PROP_DISABLE_RESCUE] = "1"
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertEquals(0.50, result.score)
    }

    // ── Clean tier: 0.00 ──────────────────────────────────────────────────────

    @Test
    fun `clean Pixel 7 scores 0_00 CLEAN with full confidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(PlayIntegrityLiveProbe.CONFIDENCE_FULL, result.confidence)
        val verdict = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_PREDICTED_VERDICT }
        assertEquals(PlayIntegrityLiveProbe.VERDICT_CLEAN, verdict?.value)
        val declarative = result.evidence.find {
            it.key == PlayIntegrityLiveProbe.EV_DECLARATIVE_ONLY
        }
        assertEquals(true, declarative?.value)
    }

    // ── Confidence degradation ────────────────────────────────────────────────

    @Test
    fun `PackageManager throw drops confidence to 0_40`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                pmThrows = true,
            )
        )
        assertFalse(result.failed)
        // GMS presence cannot be determined — the no-GMS rule does NOT fire
        // (requires !pmThrew); only property-side signals contribute.
        assertEquals(0.0, result.score)
        assertEquals(PlayIntegrityLiveProbe.CONFIDENCE_DEGRADED, result.confidence)
    }

    // ── Max-wins ──────────────────────────────────────────────────────────────

    @Test
    fun `multiple signals firing produce the maximum score (max-wins)`() = runBlocking {
        val props = cleanPixelProps().toMutableMap().apply {
            // Rescue disabled (0.50) + GMS disabled (0.70) + unprovisioned (0.85)
            this[PlayIntegrityLiveProbe.PROP_DISABLE_RESCUE] = "1"
            this[PlayIntegrityLiveProbe.PROP_GMS_DISABLED] = "1"
            this[PlayIntegrityLiveProbe.PROP_CLIENTID_BASE] =
                PlayIntegrityLiveProbe.CLIENTIDBASE_UNPROVISIONED
        }
        val result = probe.run(
            fakeCtx(
                props = props,
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        // Highest of 0.50, 0.70, 0.85 = 0.85.
        assertEquals(0.85, result.score)
    }

    // ── Method evidence ───────────────────────────────────────────────────────

    @Test
    fun `method evidence is live-verdict-inference declarative`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                props = cleanPixelProps(),
                installedPkgs = setOf(PlayIntegrityLiveProbe.PKG_GMS),
            )
        )
        val method = result.evidence.find { it.key == PlayIntegrityLiveProbe.EV_METHOD }
        assertNotNull(method)
        assertEquals(PlayIntegrityLiveProbe.METHOD, method.value)
        assertEquals(PlayIntegrityLiveProbe.METHOD, result.method)
    }
}

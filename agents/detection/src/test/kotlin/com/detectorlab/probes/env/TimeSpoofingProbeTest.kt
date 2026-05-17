package com.detectorlab.probes.env

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.core.TimeView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for TimeSpoofingProbe (env.time_spoofing, A17 N4).
 *
 * Each delta pair (D1–D4) is exercised in isolation with all other sources
 * clean, verifying correct threshold crossings and score values.
 *
 * D1 (bootEpoch drift) is stateful: the first run() of a probe instance
 * seeds the anchor and reports score=0; subsequent runs compare current
 * `(wall - elapsed)` against the anchor. See `TimeSpoofingProbe` KDoc.
 */
class TimeSpoofingProbeTest {

    private val probe = TimeSpoofingProbe()

    // Base wall-clock epoch used across tests — any realistic Unix timestamp.
    private val base = 1_700_000_000_000L

    // Default elapsed-realtime is boot-relative (small, monotonic since boot),
    // NOT a Unix epoch. With wall=base and elapsed=cleanElapsed, the inferred
    // bootEpoch ≈ base - cleanElapsed and is stable across calls when neither
    // clock has been spoofed.
    private val cleanElapsed = 1_000L

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun fakeCtx(
        wallMs: Long      = base,
        elapsedMs: Long   = cleanElapsed,  // boot-relative (small), not Unix epoch
        gpsMs: Long?      = base,
        ntpMs: Long?      = base,
        thresholdsJson: String? = null,    // raw JSON for assets/baselines/time-thresholds.json
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = thresholdsJson != null &&
            path == TimeSpoofingProbe.THRESHOLDS_ASSET
        override fun readFile(path: String, maxBytes: Int): String? =
            if (path == TimeSpoofingProbe.THRESHOLDS_ASSET) thresholdsJson else null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun queryTimeView(): TimeView = object : TimeView {
            override fun elapsedRealtimeMs(): Long = elapsedMs
            override fun wallClockMs(): Long = wallMs
            override fun gpsTimestampMs(): Long? = gpsMs
            override fun ntpTimestampMs(): Long? = ntpMs
        }
    }

    // ── clean device — no spoofing ────────────────────────────────────────────

    @Test
    fun `clean device — score is 0 when all sources agree on first call`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertTrue(result.confidence >= 0.95)
    }

    // ── D1: bootEpoch anchor (wall - elapsed drift) ───────────────────────────

    @Test
    fun `D1 first call — anchor seeded, score is 0, evidence recorded`() = runBlocking {
        val result = probe.run(fakeCtx(elapsedMs = cleanElapsed))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertTrue(result.evidence.any { it.key == "d1_anchor_seeded_boot_epoch_ms" })
    }

    @Test
    fun `D1 second call clean — no flag when bootEpoch unchanged`() = runBlocking {
        // First call seeds anchor at bootEpoch = base - 1_000.
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        // 5 s later: both wall and elapsed advanced together — bootEpoch identical.
        val result = probe.run(fakeCtx(wallMs = base + 5_000L, elapsedMs = cleanElapsed + 5_000L))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        // Evidence should be the compare-form, not the seed-form.
        assertTrue(result.evidence.any { it.key == "delta_wall_vs_elapsed_ms" })
        assertTrue(result.evidence.any { it.key == "boot_epoch_anchor_ms" })
    }

    @Test
    fun `D1 second call below threshold — no flag for small clock drift`() = runBlocking {
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        // 10 s of elapsed time later, wall jumped only +5 s extra (5 s bootEpoch
        // drift, well within the 30 s tolerance). Align GPS+NTP with the new
        // wall so D2/D3 stay clean — this test isolates D1.
        val newWall = base + 15_000L
        val result = probe.run(fakeCtx(
            wallMs    = newWall,
            elapsedMs = cleanElapsed + 10_000L,
            gpsMs     = newWall,
            ntpMs     = newWall,
        ))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `D1 triggered — score is 0_55 when bootEpoch diverges by 1 hour from anchor`() = runBlocking {
        // Seed anchor.
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        // 5 s of elapsed time later; wall-clock spoofed +1 h forward.
        // Move GPS+NTP with the new wall so D2/D3 don't also fire — we want
        // to verify D1's score in isolation.
        val newWall = base + 3_605_000L
        val result = probe.run(fakeCtx(
            wallMs    = newWall,
            elapsedMs = cleanElapsed + 5_000L,
            gpsMs     = newWall,
            ntpMs     = newWall,
        ))
        assertFalse(result.failed)
        assertEquals(0.55, result.score)
    }

    @Test
    fun `D1 triggered — evidence carries delta and anchor values`() = runBlocking {
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        val newWall = base + 3_605_000L
        val result = probe.run(fakeCtx(
            wallMs    = newWall,
            elapsedMs = cleanElapsed + 5_000L,
            gpsMs     = newWall,
            ntpMs     = newWall,
        ))
        assertTrue(result.evidence.any { it.key == "delta_wall_vs_elapsed_ms" })
        assertTrue(result.evidence.any { it.key == "boot_epoch_anchor_ms" })
        assertTrue(result.evidence.any { it.key == "boot_epoch_current_ms" })
    }

    // ── D2: wall vs GPS ───────────────────────────────────────────────────────

    @Test
    fun `D2 clean — no flag when wall and GPS agree within 10s`() = runBlocking {
        val result = probe.run(fakeCtx(gpsMs = base + 5_000L))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `D2 triggered — score is 0_75 when wall vs GPS exceeds 10s`() = runBlocking {
        // ntp=null so D4 stays skipped — otherwise a 15s gps offset would also
        // exceed the D4 (gps vs ntp) threshold and inflate the score.
        val result = probe.run(fakeCtx(gpsMs = base + 15_000L, ntpMs = null))
        assertFalse(result.failed)
        assertEquals(0.75, result.score)
    }

    @Test
    fun `D2 skipped when GPS unavailable`() = runBlocking {
        val result = probe.run(fakeCtx(gpsMs = null))
        assertFalse(result.failed)
        assertTrue(result.evidence.any {
            it.key == "delta_wall_vs_gps_ms" && it.value.toString().contains("skipped")
        })
    }

    // ── D3: wall vs NTP ───────────────────────────────────────────────────────

    @Test
    fun `D3 clean — no flag when wall and NTP agree within 10s`() = runBlocking {
        val result = probe.run(fakeCtx(ntpMs = base + 5_000L))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `D3 triggered — score is 0_80 when wall vs NTP exceeds 10s`() = runBlocking {
        // gps=null so D4 stays skipped — otherwise a 20s ntp offset relative to
        // default gps would also exceed the D4 threshold and inflate the score.
        val result = probe.run(fakeCtx(gpsMs = null, ntpMs = base + 20_000L))
        assertFalse(result.failed)
        assertEquals(0.80, result.score)
    }

    @Test
    fun `D3 skipped when NTP unavailable`() = runBlocking {
        val result = probe.run(fakeCtx(ntpMs = null))
        assertFalse(result.failed)
        assertTrue(result.evidence.any {
            it.key == "delta_wall_vs_ntp_ms" && it.value.toString().contains("skipped")
        })
    }

    // ── D4: GPS vs NTP ────────────────────────────────────────────────────────

    @Test
    fun `D4 clean — no flag when GPS and NTP agree within 5s`() = runBlocking {
        val result = probe.run(fakeCtx(gpsMs = base + 1_000L, ntpMs = base + 3_000L))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `D4 triggered — score is 0_90 when GPS vs NTP exceeds 5s`() = runBlocking {
        val result = probe.run(fakeCtx(gpsMs = base, ntpMs = base + 8_000L))
        assertFalse(result.failed)
        assertEquals(0.90, result.score)
    }

    @Test
    fun `D4 skipped when GPS unavailable`() = runBlocking {
        val result = probe.run(fakeCtx(gpsMs = null, ntpMs = base))
        assertTrue(result.evidence.any {
            it.key == "delta_gps_vs_ntp_ms" && it.value.toString().contains("skipped")
        })
    }

    // ── Score priority — highest delta wins ───────────────────────────────────

    @Test
    fun `max score wins when multiple deltas triggered`() = runBlocking {
        // Seed D1 anchor first.
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        // Second call triggers both D1 (bootEpoch jumped +1 h) and D3
        // (wall vs NTP > 10 s). gps=null skips D2 and D4 so we observe the
        // max purely between D1 (0.55) and D3 (0.80) → 0.80.
        val newWall = base + 3_605_000L
        val result = probe.run(fakeCtx(
            wallMs    = newWall,
            elapsedMs = cleanElapsed + 5_000L,
            gpsMs     = null,
            ntpMs     = newWall + 20_000L,
        ))
        assertFalse(result.failed)
        assertEquals(0.80, result.score)
    }

    @Test
    fun `confidence is 0_95 when two or more deltas triggered`() = runBlocking {
        probe.run(fakeCtx(wallMs = base, elapsedMs = cleanElapsed))
        val newWall = base + 3_605_000L
        val result = probe.run(fakeCtx(
            wallMs    = newWall,
            elapsedMs = cleanElapsed + 5_000L,
            gpsMs     = null,
            ntpMs     = newWall + 20_000L,
        ))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `confidence is 0_80 when exactly one delta triggered`() = runBlocking {
        // gps=null skips D2 and D4, so only D3 fires.
        val result = probe.run(fakeCtx(gpsMs = null, ntpMs = base + 20_000L))
        assertEquals(0.80, result.confidence)
    }

    // ── Custom thresholds via asset JSON ─────────────────────────────────────

    @Test
    fun `custom threshold from asset overrides default`() = runBlocking {
        // Lower the wall_vs_ntp threshold to 2s; a 3s delta should now trigger.
        val json = """{"wall_vs_ntp_ms": 2000}"""
        val result = probe.run(fakeCtx(ntpMs = base + 3_000L, thresholdsJson = json))
        assertFalse(result.failed)
        assertEquals(0.80, result.score)
    }

    @Test
    fun `default threshold used when asset absent`() = runBlocking {
        // Default wall_vs_ntp is 10s; a 3s delta should NOT trigger.
        val result = probe.run(fakeCtx(ntpMs = base + 3_000L, thresholdsJson = null))
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is env_time_spoofing`() {
        assertEquals("env.time_spoofing", probe.id)
    }

    @Test
    fun `probe rank is 4`() {
        assertEquals(4, probe.rank)
    }
}

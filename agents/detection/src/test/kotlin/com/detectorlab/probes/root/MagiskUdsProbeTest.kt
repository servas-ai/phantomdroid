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
 * Unit tests for MagiskUdsProbe (rank 3.5, code-rank 90,
 * Power-13 Gap #1).
 */
class MagiskUdsProbeTest {

    private val probe = MagiskUdsProbe()

    private fun fakeCtx(
        sockets: List<String> = listOf(
            "@android:installd",
            "@android:zygote",
            "/dev/socket/installd",
        ),
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
        override fun queryProcNetUnixSockets(): List<String> {
            if (accessorThrows) throw RuntimeException("simulated UDS accessor throw")
            return sockets
        }
    }

    // ── @MAGISK literal — score 0.95 ─────────────────────────────────────────

    @Test
    fun `MAGISK literal socket present — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("@android:zygote", "@MAGISK")))
        assertFalse(result.failed)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `MAGISK literal socket — pattern is magisk_uds_present`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("@MAGISK")))
        val ev = result.evidence.find { it.key == "magisk_uds.pattern" }
        assertEquals(MagiskUdsProbe.PATTERN_MAGISK_UDS_PRESENT, ev?.value)
    }

    @Test
    fun `lowercase magisk substring — score is 0_95 (case-insensitive)`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("@magiskd-helper-socket")))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `socket path with sbin magisk — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("/sbin/.magisk/magiskd")))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `socket path with dev magisk — score is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("/dev/.magisk_unique_socket")))
        assertEquals(0.95, result.score)
    }

    // ── magisk_socket_hits evidence ──────────────────────────────────────────

    @Test
    fun `magisk hit — magisk_socket_hits evidence lists verbatim`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("@android:zygote", "@MAGISK")))
        val ev = result.evidence.find { it.key == "magisk_uds.magisk_socket_hits" }
        // Preserved verbatim — `@MAGISK` matched (case-insensitive)
        // but is emitted in the original case.
        assertEquals("@MAGISK", ev?.value)
    }

    @Test
    fun `multiple hits — comma-joined in evidence`() = runBlocking {
        val result = probe.run(
            fakeCtx(sockets = listOf("@MAGISK", "/sbin/.magisk/magiskd")),
        )
        val ev = result.evidence.find { it.key == "magisk_uds.magisk_socket_hits" }
        val hits = ev?.value?.toString()?.split(",")?.toSet() ?: emptySet()
        assertEquals(setOf("@MAGISK", "/sbin/.magisk/magiskd"), hits)
    }

    @Test
    fun `clean — magisk_socket_hits is none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "magisk_uds.magisk_socket_hits" }
        assertEquals("none", ev?.value)
    }

    // ── Clean state ──────────────────────────────────────────────────────────

    @Test
    fun `realistic clean socket list — score is 0_0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `realistic clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "magisk_uds.pattern" }
        assertEquals(MagiskUdsProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `realistic clean — confidence is 0_95 NORMAL`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── No observation ───────────────────────────────────────────────────────

    @Test
    fun `empty socket list — pattern is no_observation`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = emptyList()))
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "magisk_uds.pattern" }
        assertEquals(MagiskUdsProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    @Test
    fun `empty socket list — confidence is 0_50 degraded`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = emptyList()))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `empty list — socket_list_observed evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = emptyList()))
        val ev = result.evidence.find { it.key == "magisk_uds.socket_list_observed" }
        assertEquals("false", ev?.value)
    }

    // ── Defensive ────────────────────────────────────────────────────────────

    @Test
    fun `accessor throws — does not crash, no_observation pattern`() = runBlocking {
        val result = probe.run(fakeCtx(accessorThrows = true))
        assertFalse(result.failed)
        val ev = result.evidence.find { it.key == "magisk_uds.pattern" }
        assertEquals(MagiskUdsProbe.PATTERN_NO_OBSERVATION, ev?.value)
    }

    // ── findMagiskSockets helper ─────────────────────────────────────────────

    @Test
    fun `findMagiskSockets matches @MAGISK literal`() {
        assertEquals(
            listOf("@MAGISK"),
            MagiskUdsProbe.findMagiskSockets(listOf("@android:zygote", "@MAGISK")),
        )
    }

    @Test
    fun `findMagiskSockets matches case-insensitive magisk substring`() {
        val hits = MagiskUdsProbe.findMagiskSockets(
            listOf("@MagiskD-helper", "@android:zygote"),
        )
        assertEquals(listOf("@MagiskD-helper"), hits)
    }

    @Test
    fun `findMagiskSockets matches sbin magisk path`() {
        val hits = MagiskUdsProbe.findMagiskSockets(listOf("/sbin/.magisk/socket"))
        assertEquals(listOf("/sbin/.magisk/socket"), hits)
    }

    @Test
    fun `findMagiskSockets returns empty on no match`() {
        val hits = MagiskUdsProbe.findMagiskSockets(
            listOf("@android:zygote", "@android:installd"),
        )
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `findMagiskSockets returns empty on empty input`() {
        assertEquals(emptyList(), MagiskUdsProbe.findMagiskSockets(emptyList()))
    }

    @Test
    fun `findMagiskSockets preserves original case in returned hit`() {
        // Case-insensitive match → original-case return.
        val hits = MagiskUdsProbe.findMagiskSockets(listOf("@MAGISK_DAEMON_v25"))
        assertEquals(listOf("@MAGISK_DAEMON_v25"), hits)
    }

    // ── Evidence schema ──────────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 4 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(4, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "magisk_uds.socket_list_observed",
                "magisk_uds.socket_count",
                "magisk_uds.magisk_socket_hits",
                "magisk_uds.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `socket_count reflects input size`() = runBlocking {
        val result = probe.run(fakeCtx(sockets = listOf("a", "b", "c", "d", "e")))
        val ev = result.evidence.find { it.key == "magisk_uds.socket_count" }
        assertEquals("5", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Enumerate /proc/net/unix socket names; match against " +
                "Magisk-fingerprint substrings (@MAGISK / magisk / " +
                "/.magisk / /sbin/.magisk / /dev/.magisk). RootBeerFresh + " +
                "Momo target surface (Power-13 Gap #1).",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is root_magisk_uds`() {
        assertEquals("root.magisk_uds", probe.id)
    }

    @Test
    fun `code-rank is 90`() {
        assertEquals(90, probe.rank)
    }

    @Test
    fun `inventoryRank is 3_5`() {
        assertEquals(3.5, probe.inventoryRank)
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
    fun `probe budget is 150ms`() {
        assertEquals(150L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.runtimeMs <= probe.budgetMs)
    }
}

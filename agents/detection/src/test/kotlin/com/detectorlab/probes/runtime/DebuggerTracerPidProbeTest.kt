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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for DebuggerTracerPidProbe (runtime.debugger_tracerpid,
 * inventory rank 8.5, code rank 80).
 */
class DebuggerTracerPidProbeTest {

    private val probe = DebuggerTracerPidProbe()

    /** Realistic `/proc/self/status` body fragment for tests. */
    private fun statusBody(
        tracerPid: Int? = 0,
        includeTracerPidLine: Boolean = true,
        otherFields: String = "Name:\tcom.example.app\nState:\tR (running)\nPid:\t1234\n",
    ): String {
        val tracerLine = when {
            !includeTracerPidLine -> ""
            tracerPid == null -> "TracerPid:\t\n"     // malformed: no value
            else -> "TracerPid:\t$tracerPid\n"
        }
        return otherFields + tracerLine + "Uid:\t10123\t10123\t10123\t10123\nGid:\t10123\t10123\t10123\t10123\n"
    }

    private fun fakeCtx(
        statusContent: String? = statusBody(tracerPid = 0),
        readFileThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? {
            if (readFileThrows) throw RuntimeException("simulated SELinux EACCES")
            return if (path == DebuggerTracerPidProbe.PATH_PROC_SELF_STATUS)
                statusContent
            else
                null
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

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `TracerPid 0 — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "debugger.tracerpid_pattern" }
        assertEquals(DebuggerTracerPidProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — debugger attached evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "debugger.attached" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `clean — tracer_pid evidence is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_self_status.tracer_pid" }
        assertEquals("0", ev?.value)
    }

    // ── Debugger attached (1.0) ──────────────────────────────────────────────

    @Test
    fun `TracerPid 12345 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 12345)))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `TracerPid 1 — score is 1_0 (smallest non-zero)`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 1)))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `TracerPid large number — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 99999)))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `debugger attached — pattern is debugger_attached`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 12345)))
        val ev = result.evidence.find { it.key == "debugger.tracerpid_pattern" }
        assertEquals(DebuggerTracerPidProbe.PATTERN_DEBUGGER_ATTACHED, ev?.value)
    }

    @Test
    fun `debugger attached — debugger_attached evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 12345)))
        val ev = result.evidence.find { it.key == "debugger.attached" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `debugger attached — tracer_pid evidence reflects value`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 12345)))
        val ev = result.evidence.find { it.key == "proc_self_status.tracer_pid" }
        assertEquals("12345", ev?.value)
    }

    @Test
    fun `debugger attached — confidence is 0_95 (parsing succeeded)`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = 12345)))
        assertEquals(0.95, result.confidence)
    }

    // ── TracerPid field missing (0.85) ───────────────────────────────────────

    @Test
    fun `TracerPid field missing — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(statusContent = statusBody(includeTracerPidLine = false))
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `field missing — pattern is tracerpid_field_missing`() = runBlocking {
        val result = probe.run(
            fakeCtx(statusContent = statusBody(includeTracerPidLine = false))
        )
        val ev = result.evidence.find { it.key == "debugger.tracerpid_pattern" }
        assertEquals(DebuggerTracerPidProbe.PATTERN_TRACERPID_FIELD_MISSING, ev?.value)
    }

    @Test
    fun `field missing — confidence is 0_50 (degraded)`() = runBlocking {
        val result = probe.run(
            fakeCtx(statusContent = statusBody(includeTracerPidLine = false))
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `field missing — debugger attached evidence is unknown`() = runBlocking {
        val result = probe.run(
            fakeCtx(statusContent = statusBody(includeTracerPidLine = false))
        )
        val ev = result.evidence.find { it.key == "debugger.attached" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `field missing — tracer_pid evidence is absent (not unavailable)`() = runBlocking {
        // File was readable but TracerPid line wasn't there — surface
        // as "absent" rather than "<unavailable>" so a downstream
        // consumer can distinguish "status truncated" from "couldn't
        // read status".
        val result = probe.run(
            fakeCtx(statusContent = statusBody(includeTracerPidLine = false))
        )
        val ev = result.evidence.find { it.key == "proc_self_status.tracer_pid" }
        assertEquals("absent", ev?.value)
    }

    @Test
    fun `TracerPid present but value malformed — field missing fires`() = runBlocking {
        // "TracerPid:\t" with no integer after — parseTracerPid returns null,
        // probe treats as field-missing (0.85) rather than as 0.
        val result = probe.run(fakeCtx(statusContent = statusBody(tracerPid = null)))
        assertEquals(0.85, result.score)
    }

    // ── Status unreadable (0.5) ──────────────────────────────────────────────

    @Test
    fun `null status — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `empty status — score is 0_5`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = ""))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `unreadable — pattern is status_unreadable`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        val ev = result.evidence.find { it.key == "debugger.tracerpid_pattern" }
        assertEquals(DebuggerTracerPidProbe.PATTERN_STATUS_UNREADABLE, ev?.value)
    }

    @Test
    fun `unreadable — readable evidence is false`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        val ev = result.evidence.find { it.key == "proc_self_status.readable" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `unreadable — debugger attached evidence is unknown`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        val ev = result.evidence.find { it.key == "debugger.attached" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `unreadable — tracer_pid evidence is unavailable`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        val ev = result.evidence.find { it.key == "proc_self_status.tracer_pid" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `unreadable — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(statusContent = null))
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `readFile throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `readFile throws — score is 0_5 (treated as unreadable)`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `readFile throws — confidence is 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(readFileThrows = true))
        assertEquals(0.50, result.confidence)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `debugger attached takes priority over malformed field`() = runBlocking {
        // Multi-line status with both a valid TracerPid line AND
        // malformed content elsewhere — the valid TracerPid wins.
        val result = probe.run(
            fakeCtx(
                statusContent = "Name:\tapp\nTracerPid:\t12345\nWeirdField:\t\n"
            )
        )
        assertEquals(1.0, result.score)
    }

    // ── parseTracerPid helper ────────────────────────────────────────────────

    @Test
    fun `parseTracerPid extracts 0`() {
        val body = "Name:\tapp\nTracerPid:\t0\nUid:\t10123\n"
        assertEquals(0, DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid extracts non-zero`() {
        val body = "Name:\tapp\nTracerPid:\t12345\nUid:\t10123\n"
        assertEquals(12345, DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid accepts space separator (not just tab)`() {
        // Real Android uses tab but be liberal in what we accept.
        val body = "Name:\tapp\nTracerPid: 12345\nUid:\t10123\n"
        assertEquals(12345, DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid accepts multiple separators`() {
        val body = "TracerPid:\t \t 99\n"
        assertEquals(99, DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid returns null when field absent`() {
        val body = "Name:\tapp\nUid:\t10123\n"
        assertNull(DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid returns null when value malformed`() {
        val body = "Name:\tapp\nTracerPid:\tNaN\nUid:\t10123\n"
        assertNull(DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid returns null when value empty`() {
        val body = "TracerPid:\t\n"
        assertNull(DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid handles last-line-no-newline`() {
        val body = "Name:\tapp\nTracerPid:\t42"
        assertEquals(42, DebuggerTracerPidProbe.parseTracerPid(body))
    }

    @Test
    fun `parseTracerPid case-sensitive (exact match required)`() {
        // Real `/proc/self/status` uses exact "TracerPid:" case. The
        // prefix match in our parser is intentionally case-sensitive
        // — a lowercase variant would be anomalous and should not
        // confuse the parser into accepting it as a valid match.
        val body = "tracerpid:\t12345\n"
        assertNull(DebuggerTracerPidProbe.parseTracerPid(body))
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

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
                "proc_self_status.tracer_pid",
                "proc_self_status.readable",
                "debugger.attached",
                "debugger.tracerpid_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `readable evidence true when status present`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "proc_self_status.readable" }
        assertEquals("true", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read /proc/self/status and parse TracerPid field; non-zero " +
                "value indicates ptrace-attached debugger (freeRASP T2 / " +
                "MASTG-RESILIENCE-2)",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is runtime_debugger_tracerpid`() {
        assertEquals("runtime.debugger_tracerpid", probe.id)
    }

    @Test
    fun `probe rank is 80 in code (inventory says 8_5)`() {
        // Inventory.yml lists this probe at fractional rank 8.5.
        // Probe.rank: Int can't hold 8.5, so the code uses rank 80
        // (unused integer outside the META-22 A17 reserved range
        // 61-71). Same Int-vs-fractional divergence handling as
        // ScreenLockProbe (inventory 40.5 → code 61). Tracked in
        // audit/cross-cutting-followups-2026-05-19.md #7.
        assertEquals(80, probe.rank)
        assertEquals(DebuggerTracerPidProbe.RANK, probe.rank)
    }

    @Test
    fun `probe category is RUNTIME`() {
        assertEquals(ProbeCategory.RUNTIME, probe.category)
    }

    @Test
    fun `probe severity is HIGH`() {
        // inventory.yml rank-8.5 severity = high (RASP T2 /
        // MASTG-RESILIENCE-2). HIGH is the right severity for a
        // ptrace-attach detection — strong indicator of active
        // analysis but not as dispositive as e.g. emulator markers.
        assertEquals(ProbeSeverity.HIGH, probe.severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, probe.androidLayer)
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

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
 * Unit tests for ServicesProcessesProbe (runtime.services_processes,
 * inventory rank 50).
 */
class ServicesProcessesProbeTest {

    private fun makeProbe(
        services: List<String>? = emptyList(),
        processes: List<String>? = listOf(
            "system_server",
            "com.android.systemui",
            "com.google.android.gms",
            "zygote64",
            "zygote",
            "logd",
            "surfaceflinger",
            "adbd",
            "netd",
            "vold",
        ),
    ): ServicesProcessesProbe = ServicesProcessesProbe(
        runningServiceNamesSupplier = { services },
        runningProcessNamesSupplier = { processes },
    )

    private fun fakeCtx(): ProbeContext = object : ProbeContext {
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
    }

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `clean process list — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `clean — process_list_filtered is false`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.process_list_filtered" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `clean — hostile_processes evidence is none`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.hostile_processes" }
        assertEquals("none", ev?.value)
    }

    // ── Hostile runtime markers (1.0) ────────────────────────────────────────

    @Test
    fun `frida-server in process list — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-server", "zygote"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `frida-gadget in process list — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-gadget"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `re_frida_server alt name — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "re.frida.server"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `magiskd in process list — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "magiskd"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `lspd daemon — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "lspd"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `xposedbridge — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "xposedbridge"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `kernelsu — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "kernelsu"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zygisk_lsposed module — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "zygisk_lsposed"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `riru daemon — score is 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "riru"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `hostile in service list — also fires 1_0`() = runBlocking {
        val result = makeProbe(
            services = listOf("ServiceA", "magiskd", "ServiceB"),
            processes = listOf("system_server"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `case-insensitive uppercase FRIDA — fires 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "FRIDA-SERVER"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mixed-case Magiskd — fires 1_0`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "Magiskd"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `hostile — pattern is hostile_runtime`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-server"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_HOSTILE_RUNTIME, ev?.value)
    }

    @Test
    fun `hostile — hostile_processes evidence lists matches`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-server", "magiskd"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.hostile_processes" }
        // Sorted alphabetically.
        assertEquals("frida-server,magiskd", ev?.value)
    }

    // ── Debug server markers (0.95) ──────────────────────────────────────────

    @Test
    fun `gdbserver in process list — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "gdbserver"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `lldb-server — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "lldb-server"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `debug server — pattern is debug_server`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "gdbserver"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_DEBUG_SERVER, ev?.value)
    }

    @Test
    fun `debug_processes evidence lists matches`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "gdbserver", "lldb-server"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.debug_processes" }
        assertEquals("gdbserver,lldb-server", ev?.value)
    }

    // ── MITM tool markers (0.95) ─────────────────────────────────────────────

    @Test
    fun `tcpdump in process list — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "tcpdump"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `mitmproxy — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "mitmproxy"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `charles proxy agent — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "charles-proxy-agent"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `burp suite agent — score is 0_95`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "burp-suite-agent"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `mitm — pattern is mitm_tool`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "mitmproxy"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_MITM_TOOL, ev?.value)
    }

    // ── Automation markers (0.5) ─────────────────────────────────────────────

    @Test
    fun `uiautomator process — score is 0_5`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "uiautomator"),
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `autoinput process — score is 0_5`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "autoinput"),
        ).run(fakeCtx())
        assertEquals(0.5, result.score)
    }

    @Test
    fun `automation — pattern is automation`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "uiautomator"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_AUTOMATION, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `frida + gdbserver — hostile_runtime wins (1_0 over 0_95)`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-server", "gdbserver"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_HOSTILE_RUNTIME, ev?.value)
    }

    @Test
    fun `frida + mitmproxy — hostile_runtime wins`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "frida-server", "mitmproxy"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `gdbserver + mitmproxy — debug_server wins by source order`() = runBlocking {
        // Both fire at 0.95; debug_server is earlier in the cascade.
        val result = makeProbe(
            processes = listOf("system_server", "gdbserver", "mitmproxy"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "runtime.pattern" }
        assertEquals(ServicesProcessesProbe.PATTERN_DEBUG_SERVER, ev?.value)
    }

    @Test
    fun `mitmproxy + uiautomator — mitm_tool wins (0_95 over 0_5)`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "mitmproxy", "uiautomator"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    @Test
    fun `gdbserver + uiautomator — debug_server wins`() = runBlocking {
        val result = makeProbe(
            processes = listOf("system_server", "gdbserver", "uiautomator"),
        ).run(fakeCtx())
        assertEquals(0.95, result.score)
    }

    // ── Multi-source aggregation (service AND process matches) ───────────────

    @Test
    fun `hostile in services AND processes — single match in evidence (dedupe)`() = runBlocking {
        // Same name appearing in both lists shouldn't duplicate in evidence.
        // findMatches uses Set internally, so same string in both should
        // appear once. (Different strings each containing "frida" would both
        // surface independently.)
        val result = makeProbe(
            services = listOf("magiskd"),
            processes = listOf("system_server", "magiskd"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.hostile_processes" }
        assertEquals("magiskd", ev?.value)
    }

    @Test
    fun `different hostile names in services and processes — both listed`() = runBlocking {
        val result = makeProbe(
            services = listOf("magiskd"),
            processes = listOf("system_server", "frida-server"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.hostile_processes" }
        assertEquals("frida-server,magiskd", ev?.value)
    }

    // ── Debug/MITM markers only match against processes, not services ────────

    @Test
    fun `gdbserver in services only — does not fire debug rule`() = runBlocking {
        // The debug-server scan is restricted to the process list because
        // a service named "gdbserver-helper" might just be a debug-feature
        // wrapper, not actual debug-server. The brief specifies
        // "Process name contains gdbserver" for that reason.
        val result = makeProbe(
            services = listOf("gdbserver"),
            processes = listOf("system_server"),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `mitmproxy in services only — does not fire mitm rule`() = runBlocking {
        val result = makeProbe(
            services = listOf("mitmproxy"),
            processes = listOf("system_server"),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Android 8+ visibility filter (empty lists) ───────────────────────────

    @Test
    fun `both lists empty — score is 0 (Android 8+ filter expected)`() = runBlocking {
        val result = makeProbe(services = emptyList(), processes = emptyList())
            .run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `both lists empty — confidence is 0_50 degraded`() = runBlocking {
        val result = makeProbe(services = emptyList(), processes = emptyList())
            .run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `both lists empty — process_list_filtered is true`() = runBlocking {
        val result = makeProbe(services = emptyList(), processes = emptyList())
            .run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.process_list_filtered" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `both lists null — score is 0, confidence 0_50`() = runBlocking {
        val result = makeProbe(services = null, processes = null).run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `services empty processes populated — confidence 0_95`() = runBlocking {
        val result = makeProbe(
            services = emptyList(),
            processes = listOf("system_server", "zygote"),
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `services populated processes empty — confidence 0_95`() = runBlocking {
        val result = makeProbe(
            services = listOf("SystemServiceA"),
            processes = emptyList(),
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score is 0, confidence 0_50`() = runBlocking {
        val result = ServicesProcessesProbe().run(fakeCtx())
        // Both suppliers return null → no signals fire → degraded.
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor — process_list_filtered true`() = runBlocking {
        val result = ServicesProcessesProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.process_list_filtered" }
        assertEquals("true", ev?.value)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `runningServiceNamesSupplier throws — does not crash`() = runBlocking {
        val probe = ServicesProcessesProbe(
            runningServiceNamesSupplier = { throw RuntimeException("simulated") },
            runningProcessNamesSupplier = { listOf("system_server", "frida-server") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Service supplier threw → treated as empty; processes still scanned.
        assertEquals(1.0, result.score)
    }

    @Test
    fun `runningProcessNamesSupplier throws — does not crash`() = runBlocking {
        val probe = ServicesProcessesProbe(
            runningServiceNamesSupplier = { listOf("magiskd") },
            runningProcessNamesSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Service "magiskd" fires hostile rule (1.0).
        assertEquals(1.0, result.score)
    }

    @Test
    fun `both suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = ServicesProcessesProbe(
            runningServiceNamesSupplier = { throw RuntimeException("a") },
            runningProcessNamesSupplier = { throw RuntimeException("b") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── findMatches helper unit tests ────────────────────────────────────────

    @Test
    fun `findMatches returns sorted distinct hits`() {
        val result = ServicesProcessesProbe.findMatches(
            names = listOf("zygote", "magiskd", "frida-server", "magiskd"),
            markers = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS,
        )
        assertEquals(listOf("frida-server", "magiskd"), result)
    }

    @Test
    fun `findMatches case-insensitive`() {
        val result = ServicesProcessesProbe.findMatches(
            names = listOf("FRIDA-SERVER", "Magiskd"),
            markers = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS,
        )
        // Hit values are the ORIGINAL names (case preserved), but the
        // matching is case-insensitive.
        assertEquals(listOf("FRIDA-SERVER", "Magiskd"), result)
    }

    @Test
    fun `findMatches substring match — hits when marker is a substring`() {
        val result = ServicesProcessesProbe.findMatches(
            names = listOf("my-app-frida-injection-helper", "system_server"),
            markers = listOf("frida"),
        )
        assertEquals(listOf("my-app-frida-injection-helper"), result)
    }

    @Test
    fun `findMatches empty names — returns empty`() {
        val result = ServicesProcessesProbe.findMatches(
            names = emptyList(),
            markers = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS,
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `findMatches no hits — returns empty`() {
        val result = ServicesProcessesProbe.findMatches(
            names = listOf("system_server", "zygote", "logd"),
            markers = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS,
        )
        assertEquals(emptyList(), result)
    }

    // ── Cross-rank distinction tests (rank-28 anchor pattern) ────────────────

    @Test
    fun `HOSTILE_RUNTIME_MARKERS list contains expected entries`() {
        // Drift alarm: if anyone modifies this list, the test forces
        // a deliberate review of which tools to add/remove.
        val list = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS
        assertTrue("frida-server" in list)
        assertTrue("frida-gadget" in list)
        assertTrue("re.frida.server" in list)
        assertTrue("magiskd" in list)
        assertTrue("lspd" in list)
        assertTrue("xposedbridge" in list)
        assertTrue("kernelsu" in list)
        assertTrue("zygisk_lsposed" in list)
        assertTrue("riru" in list)
    }

    @Test
    fun `DEBUG_SERVER_MARKERS list contains expected entries`() {
        assertEquals(
            listOf("gdbserver", "lldb-server"),
            ServicesProcessesProbe.DEBUG_SERVER_MARKERS,
        )
    }

    @Test
    fun `MITM_TOOL_MARKERS list contains expected entries`() {
        assertEquals(
            listOf("tcpdump", "mitmproxy", "charles", "burp"),
            ServicesProcessesProbe.MITM_TOOL_MARKERS,
        )
    }

    @Test
    fun `AUTOMATION_MARKERS list contains expected entries`() {
        assertEquals(
            listOf("uiautomator", "autoinput"),
            ServicesProcessesProbe.AUTOMATION_MARKERS,
        )
    }

    @Test
    fun `HOSTILE_RUNTIME_MARKERS distinct from rank-10 InstalledAppsProbe package IDs`() {
        // Rank 10 scans package names like "com.topjohnwu.magisk",
        // "re.frida.server", "de.robv.android.xposed.installer". This probe
        // scans process/service names like "frida-server", "magiskd",
        // "xposedbridge". Both surfaces are intentionally distinct.
        // Documentation drift alarm: if anyone replaces the marker strings
        // here with package IDs (e.g. "com.topjohnwu.magisk"), this test
        // fails because such IDs aren't substrings of process names.
        val list = ServicesProcessesProbe.HOSTILE_RUNTIME_MARKERS
        assertFalse(
            list.any { it.startsWith("com.") || it.startsWith("de.") },
            "HOSTILE_RUNTIME_MARKERS must contain process/service names, " +
                "not package IDs. Rank 10 [InstalledAppsProbe] handles " +
                "package-ID scanning; consolidating would lose surface-" +
                "specific false-positive guards.",
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 8 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(8, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "runtime.process_count",
                "runtime.service_count",
                "runtime.hostile_processes",
                "runtime.debug_processes",
                "runtime.mitm_processes",
                "runtime.automation_processes",
                "runtime.process_list_filtered",
                "runtime.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `process_count evidence reflects supplier list size`() = runBlocking {
        val result = makeProbe(
            processes = listOf("a", "b", "c"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.process_count" }
        assertEquals("3", ev?.value)
    }

    @Test
    fun `service_count evidence reflects supplier list size`() = runBlocking {
        val result = makeProbe(
            services = listOf("S1", "S2"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.service_count" }
        assertEquals("2", ev?.value)
    }

    @Test
    fun `process_count is 0 when supplier returns null`() = runBlocking {
        val result = makeProbe(processes = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.process_count" }
        assertEquals("0", ev?.value)
    }

    @Test
    fun `clean evidence — debug_processes is none`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.debug_processes" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `clean evidence — mitm_processes is none`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.mitm_processes" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `clean evidence — automation_processes is none`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "runtime.automation_processes" }
        assertEquals("none", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read ActivityManager getRunningServices + getRunningAppProcesses; " +
                "scan service/process names for hostile root-tool/instrumentation/" +
                "MITM substrings; document Android 8+ visibility filter as " +
                "confidence-degradation.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is runtime_services_processes`() {
        assertEquals("runtime.services_processes", makeProbe().id)
    }

    @Test
    fun `probe rank is 50`() {
        assertEquals(50, makeProbe().rank)
    }

    @Test
    fun `probe category is RUNTIME`() {
        assertEquals(ProbeCategory.RUNTIME, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-50 severity=medium
        // (BEST-STACK Phase 3 rerank: low → medium per freeRASP T10).
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 400ms`() {
        assertEquals(400L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

package com.detectorlab.probes.runtime

import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for FridaMemoryMapsProbe (runtime.frida_memory_maps,
 * inventory rank 9.0, code rank 83).
 */
class FridaMemoryMapsProbeTest {

    private val probe = FridaMemoryMapsProbe()

    private fun fakeCtx(
        libs: Set<String> = emptySet(),
        threads: Set<String> = emptySet(),
        ports: Set<Int> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String): Boolean = false
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

        override fun queryProcSelfMapsLibs(): Set<String> = libs
        override fun queryRuntimeThreadNames(): Set<String> = threads
        override fun queryOpenTcpPorts(): Set<Int> = ports
    }

    // ── Clean (no measurement / no signal) ───────────────────────────────────

    @Test
    fun `all accessors empty — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty accessors — confidence is no_observation floor`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(FridaMemoryMapsProbe.CONFIDENCE_NO_OBSERVATION, result.confidence)
    }

    @Test
    fun `clean populated maps (real Android-shape libs) — score is 0`() = runBlocking {
        // A real production Android process has libc / libart / libandroid_runtime
        // etc. mapped — none match the frida tokens.
        val result = probe.run(
            fakeCtx(
                libs = setOf("libc.so", "libart.so", "libandroid_runtime.so"),
                threads = setOf("main", "FinalizerDaemon", "JDWP"),
                ports = setOf(8080, 5037),
            ),
        )
        assertEquals(0.0, result.score)
        // anyObservation = true → confidence_observed
        assertEquals(FridaMemoryMapsProbe.CONFIDENCE_OBSERVED, result.confidence)
    }

    // ── Frida library present (1.0 dispositive) ──────────────────────────────

    @Test
    fun `frida-agent in maps — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(libs = setOf("libfrida-agent-arm64.so")))
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.frida_memory_maps.pattern"
        }
        assertEquals(FridaMemoryMapsProbe.PATTERN_FRIDA_LIBRARY_PRESENT, patternEv?.value)
    }

    @Test
    fun `libfrida-gadget in maps — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(libs = setOf("libfrida-gadget.so")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `libgum-android in maps — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(libs = setOf("libgum-android.so")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `library match is case-insensitive`() = runBlocking {
        val result = probe.run(fakeCtx(libs = setOf("LIBFRIDA-AGENT.SO")))
        assertEquals(1.0, result.score)
    }

    // ── Gum thread present (0.7) ─────────────────────────────────────────────

    @Test
    fun `gum-js-loop thread present — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(threads = setOf("main", "gum-js-loop")))
        assertEquals(0.7, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.frida_memory_maps.pattern"
        }
        assertEquals(FridaMemoryMapsProbe.PATTERN_GUM_THREAD_PRESENT, patternEv?.value)
    }

    @Test
    fun `gmain thread present — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(threads = setOf("gmain")))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `gdbus thread present — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(threads = setOf("gdbus")))
        assertEquals(0.7, result.score)
    }

    // ── Frida port open (1.0 dispositive) ────────────────────────────────────

    @Test
    fun `port 27042 open — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(ports = setOf(27042)))
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.frida_memory_maps.pattern"
        }
        assertEquals(FridaMemoryMapsProbe.PATTERN_FRIDA_PORT_OPEN, patternEv?.value)
    }

    @Test
    fun `port 27043 open — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(ports = setOf(27043)))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `port 8080 only — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(ports = setOf(8080, 5037)))
        assertEquals(0.0, result.score)
    }

    // ── Multiple hits — bounded at 1.0 (first-match cascade) ─────────────────

    @Test
    fun `all three signals present — score is 1_0 (library wins)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                libs = setOf("libfrida-agent.so"),
                threads = setOf("gum-js-loop"),
                ports = setOf(27042),
            ),
        )
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.frida_memory_maps.pattern"
        }
        // Library tier fires first in the cascade — pattern reflects that.
        assertEquals(FridaMemoryMapsProbe.PATTERN_FRIDA_LIBRARY_PRESENT, patternEv?.value)
    }

    @Test
    fun `port and thread together — score is 1_0 (port wins)`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                threads = setOf("gum-js-loop"),
                ports = setOf(27042),
            ),
        )
        assertEquals(1.0, result.score)
        val patternEv = result.evidence.find {
            it.key == "runtime.frida_memory_maps.pattern"
        }
        assertEquals(FridaMemoryMapsProbe.PATTERN_FRIDA_PORT_OPEN, patternEv?.value)
    }

    // ── Evidence structure ───────────────────────────────────────────────────

    @Test
    fun `evidence keys are namespaced under probe id`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertTrue(keys.all { it.startsWith("runtime.frida_memory_maps.") })
    }

    @Test
    fun `clean — evidence reports none for each surface`() = runBlocking {
        val result = probe.run(fakeCtx())
        val libsEv = result.evidence.find { it.key == "runtime.frida_memory_maps.libs" }
        val threadsEv = result.evidence.find { it.key == "runtime.frida_memory_maps.threads" }
        val portsEv = result.evidence.find { it.key == "runtime.frida_memory_maps.ports" }
        assertEquals("none", libsEv?.value)
        assertEquals("none", threadsEv?.value)
        assertEquals("none", portsEv?.value)
    }

    @Test
    fun `evidence ports value is sorted`() = runBlocking {
        val result = probe.run(fakeCtx(ports = setOf(27043, 27042)))
        val portsEv = result.evidence.find { it.key == "runtime.frida_memory_maps.ports" }
        assertEquals("27042,27043", portsEv?.value)
    }

    // ── Inventory / declarative metadata ─────────────────────────────────────

    @Test
    fun `inventoryRank is 9_0`() {
        assertEquals(9.0, probe.inventoryRank)
    }

    @Test
    fun `probe id matches inventory`() {
        assertEquals("runtime.frida_memory_maps", probe.id)
    }
}

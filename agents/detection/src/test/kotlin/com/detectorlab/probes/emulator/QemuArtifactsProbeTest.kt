package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for QemuArtifactsProbe (emulator.qemu_artifacts, inventory rank 4).
 */
class QemuArtifactsProbeTest {

    private val probe = QemuArtifactsProbe()

    private fun fakeCtx(
        properties: Map<String, String?> = emptyMap(),
        presentFiles: Set<String> = emptySet(),
        propertyThrows: Boolean = false,
        fileExistsThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return properties[key]
        }
        override fun fileExists(path: String): Boolean {
            if (fileExistsThrows) throw RuntimeException("simulated SELinux EACCES")
            return path in presentFiles
        }
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
    fun `real Pixel clean — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        // Property reads return null for unknown keys (no exception),
        // file checks return false for absent paths — both accessor
        // classes succeed, confidence is NORMAL.
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `real device with ro_kernel_qemu == 0 — score is 0`() = runBlocking {
        // Some legacy AOSP builds explicitly pin `ro.kernel.qemu=0`.
        // Only "1" is the canonical AVD marker; "0" must NOT fire.
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "0")))
        assertEquals(0.0, result.score)
    }

    // ── AVD canonical marker (1.0) ───────────────────────────────────────────

    @Test
    fun `ro_kernel_qemu equals 1 — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "1")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_kernel_qemu equals 1 — pattern is avd_canonical`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "1")))
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_AVD_CANONICAL, ev?.value)
    }

    @Test
    fun `ro_kernel_qemu equals 1 — is_avd_canonical evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "1")))
        val ev = result.evidence.find { it.key == "qemu.is_avd_canonical" }
        assertEquals("true", ev?.value)
    }

    // ── AVD GLES marker (1.0) ────────────────────────────────────────────────

    @Test
    fun `ro_kernel_qemu_gles set to host — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu.gles" to "1")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_kernel_qemu_gles any non-empty value — score is 1_0`() = runBlocking {
        // Any non-empty value indicates the GLES host-passthrough is
        // wired up — even "0" is anomalous on a real device because
        // real devices don't set this property at all.
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu.gles" to "0")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `ro_kernel_qemu_gles empty string — score is 0 (no signal)`() = runBlocking {
        // Empty string is treated the same as null — not set. Real
        // devices return null for this property; some property
        // wrappers normalize null → empty string. Both should be safe.
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu.gles" to "")))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `gles set — pattern is avd_gles`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu.gles" to "1")))
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_AVD_GLES, ev?.value)
    }

    // ── QEMU device nodes (1.0) ──────────────────────────────────────────────

    @Test
    fun `dev qemu_pipe present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/qemu_pipe")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev qemu_trace present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/qemu_trace")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `qemu device node — pattern is qemu_device_node`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/qemu_pipe")))
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_QEMU_DEVICE_NODE, ev?.value)
    }

    @Test
    fun `qemu device node — devices_present evidence lists path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/qemu_pipe")))
        val ev = result.evidence.find { it.key == "qemu.device_nodes_present" }
        assertEquals("/dev/qemu_pipe", ev?.value)
    }

    @Test
    fun `multiple qemu device nodes — both listed`() = runBlocking {
        val result = probe.run(
            fakeCtx(presentFiles = setOf("/dev/qemu_pipe", "/dev/qemu_trace"))
        )
        val ev = result.evidence.find { it.key == "qemu.device_nodes_present" }
        assertEquals("/dev/qemu_pipe,/dev/qemu_trace", ev?.value)
    }

    // ── Goldfish device nodes (1.0) ──────────────────────────────────────────

    @Test
    fun `dev goldfish_pipe present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/goldfish_pipe")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev goldfish_audio present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/goldfish_audio")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev goldfish_events present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/goldfish_events")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `goldfish device node — pattern is goldfish_device_node`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/goldfish_audio")))
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_GOLDFISH_DEVICE_NODE, ev?.value)
    }

    @Test
    fun `goldfish device node — devices_present evidence lists path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/goldfish_pipe")))
        val ev = result.evidence.find { it.key == "goldfish.device_nodes_present" }
        assertEquals("/dev/goldfish_pipe", ev?.value)
    }

    // ── QEMU socket nodes (1.0) ──────────────────────────────────────────────

    @Test
    fun `dev socket qemud present — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/socket/qemud")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `dev socket baseband_genyd present — score is 1_0 (Genymotion)`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/socket/baseband_genyd")))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `qemu socket — pattern is qemu_socket_node`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/socket/qemud")))
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_QEMU_SOCKET, ev?.value)
    }

    @Test
    fun `genymotion socket — sockets_present evidence lists path`() = runBlocking {
        val result = probe.run(fakeCtx(presentFiles = setOf("/dev/socket/baseband_genyd")))
        val ev = result.evidence.find { it.key == "qemu.socket_nodes_present" }
        assertEquals("/dev/socket/baseband_genyd", ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `avd_canonical wins when both canonical and device-node fire`() = runBlocking {
        // AVD canonical AND qemu_pipe present — first-match cascade
        // picks canonical (source order).
        val result = probe.run(
            fakeCtx(
                properties = mapOf("ro.kernel.qemu" to "1"),
                presentFiles = setOf("/dev/qemu_pipe"),
            )
        )
        assertEquals(1.0, result.score)
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_AVD_CANONICAL, ev?.value)
    }

    @Test
    fun `gles wins over device node when both predicate true`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf("ro.kernel.qemu.gles" to "1"),
                presentFiles = setOf("/dev/qemu_pipe"),
            )
        )
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_AVD_GLES, ev?.value)
    }

    @Test
    fun `qemu device node wins over goldfish device node`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/dev/qemu_pipe", "/dev/goldfish_pipe"),
            )
        )
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_QEMU_DEVICE_NODE, ev?.value)
    }

    @Test
    fun `goldfish device node wins over qemu socket`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                presentFiles = setOf("/dev/goldfish_audio", "/dev/socket/qemud"),
            )
        )
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_GOLDFISH_DEVICE_NODE, ev?.value)
    }

    // ── Multiple-artifact aggregate ──────────────────────────────────────────

    @Test
    fun `full AVD config — all surfaces fire, score 1_0`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    "ro.kernel.qemu" to "1",
                    "ro.kernel.qemu.gles" to "1",
                ),
                presentFiles = setOf(
                    "/dev/qemu_pipe",
                    "/dev/qemu_trace",
                    "/dev/goldfish_pipe",
                    "/dev/goldfish_audio",
                    "/dev/socket/qemud",
                ),
            )
        )
        assertEquals(1.0, result.score)
        // Cascade picks AVD_CANONICAL (first matching rule).
        val ev = result.evidence.find { it.key == "qemu.pattern" }
        assertEquals(QemuArtifactsProbe.PATTERN_AVD_CANONICAL, ev?.value)
    }

    @Test
    fun `full AVD config — all evidence rows populated`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                properties = mapOf(
                    "ro.kernel.qemu" to "1",
                    "ro.kernel.qemu.gles" to "1",
                ),
                presentFiles = setOf(
                    "/dev/qemu_pipe",
                    "/dev/qemu_trace",
                    "/dev/goldfish_pipe",
                    "/dev/goldfish_audio",
                    "/dev/socket/qemud",
                ),
            )
        )
        val qemuDev = result.evidence.find { it.key == "qemu.device_nodes_present" }
        val goldDev = result.evidence.find { it.key == "goldfish.device_nodes_present" }
        val sockDev = result.evidence.find { it.key == "qemu.socket_nodes_present" }
        assertEquals("/dev/qemu_pipe,/dev/qemu_trace", qemuDev?.value)
        assertEquals("/dev/goldfish_pipe,/dev/goldfish_audio", goldDev?.value)
        assertEquals("/dev/socket/qemud", sockDev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both accessors succeed — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `property throws but file succeeds — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(propertyThrows = true))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `file throws but property succeeds — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `both accessor classes throw — confidence 0_50 (degraded)`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrows = true, fileExistsThrows = true)
        )
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `both throw — score is 0 (no rule fires under total accessor failure)`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrows = true, fileExistsThrows = true)
        )
        assertEquals(0.0, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `property throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `fileExists throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(fileExistsThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `both throw — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrows = true, fileExistsThrows = true)
        )
        assertFalse(result.failed)
    }

    // ── Cross-rank distinct-surface invariant (anti-duplication) ─────────────

    @Test
    fun `rank-4 device-node paths distinct from rank-28 board-prop markers`() {
        // Drift-alarm: rank-4 (this probe) reads DEVICE NODE PATHS in
        // /dev/* whereas rank-28 BoardHardwareProbe scans for marker
        // SUBSTRINGS in ro.product.board / ro.hardware. Different
        // surface, different match semantic. If a future maintainer
        // tries to consolidate the two lists, this test pins the
        // intentional separation.
        val rank4DeviceNodes = QemuArtifactsProbe.QEMU_DEVICE_NODES +
            QemuArtifactsProbe.GOLDFISH_DEVICE_NODES +
            QemuArtifactsProbe.QEMU_SOCKET_NODES
        val rank28Markers = BoardHardwareProbe.EMULATOR_BOARD_MARKERS

        // No overlap — rank-28 markers are bare substrings like
        // "goldfish"; rank-4 device nodes are full paths like
        // "/dev/goldfish_pipe". Equality across the two sets is
        // empty; substring match is also one-directional (a marker
        // substring "goldfish" appears inside the path
        // "/dev/goldfish_pipe" by design, but the rank-28 scan
        // applies to property VALUES not device-node paths).
        for (path in rank4DeviceNodes) {
            assertFalse(
                path in rank28Markers,
                "device node $path must not appear in rank-28 EMULATOR_BOARD_MARKERS",
            )
        }
        for (marker in rank28Markers) {
            assertFalse(
                marker in rank4DeviceNodes,
                "rank-28 marker $marker must not appear as rank-4 device node",
            )
        }
    }

    // ── List drift-alarm invariants ──────────────────────────────────────────

    @Test
    fun `QEMU_DEVICE_NODES list pinned`() {
        val list = QemuArtifactsProbe.QEMU_DEVICE_NODES
        assertTrue("/dev/qemu_pipe" in list)
        assertTrue("/dev/qemu_trace" in list)
        assertEquals(2, list.size)
    }

    @Test
    fun `GOLDFISH_DEVICE_NODES list pinned`() {
        val list = QemuArtifactsProbe.GOLDFISH_DEVICE_NODES
        assertTrue("/dev/goldfish_pipe" in list)
        assertTrue("/dev/goldfish_audio" in list)
        assertTrue("/dev/goldfish_events" in list)
        assertEquals(3, list.size)
    }

    @Test
    fun `QEMU_SOCKET_NODES list pinned`() {
        val list = QemuArtifactsProbe.QEMU_SOCKET_NODES
        assertTrue("/dev/socket/qemud" in list)
        assertTrue("/dev/socket/baseband_genyd" in list)
        assertEquals(2, list.size)
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 7 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(7, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "ro.kernel.qemu",
                "ro.kernel.qemu.gles",
                "qemu.device_nodes_present",
                "goldfish.device_nodes_present",
                "qemu.socket_nodes_present",
                "qemu.is_avd_canonical",
                "qemu.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `ro_kernel_qemu evidence reflects property`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "1")))
        val ev = result.evidence.find { it.key == "ro.kernel.qemu" }
        assertEquals("1", ev?.value)
    }

    @Test
    fun `ro_kernel_qemu evidence unavailable when property absent`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "ro.kernel.qemu" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `is_avd_canonical evidence false when prop is not 1`() = runBlocking {
        val result = probe.run(fakeCtx(properties = mapOf("ro.kernel.qemu" to "0")))
        val ev = result.evidence.find { it.key == "qemu.is_avd_canonical" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `device_nodes_present evidence is none when no nodes present`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "qemu.device_nodes_present" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `socket_nodes_present evidence is none when no sockets present`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "qemu.socket_nodes_present" }
        assertEquals("none", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read ro.kernel.qemu / ro.kernel.qemu.gles + check /dev/qemu_*, " +
                "/dev/goldfish_*, /dev/socket/qemud/baseband_genyd device nodes; " +
                "detect canonical AVD marker + QEMU/Goldfish-specific kernel surface",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is emulator_qemu_artifacts`() {
        assertEquals("emulator.qemu_artifacts", probe.id)
    }

    @Test
    fun `probe rank is 4`() {
        assertEquals(4, probe.rank)
    }

    @Test
    fun `probe category is EMULATOR`() {
        assertEquals(ProbeCategory.EMULATOR, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        // inventory.yml rank-4 severity = critical. Joins rank-1
        // (BuildFingerprint), rank-3 (SuDetection), rank-8 (Xposed),
        // rank-10 (InstalledApps) as the CRITICAL-severity set.
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
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
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

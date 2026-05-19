// inventory.yml rank 4, mitigation_layer L1
package com.detectorlab.probes.emulator

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #4 — emulator.qemu_artifacts.
 *
 * Detects QEMU/Goldfish-specific kernel artifacts that survive at the
 * device-node + kernel-property surface, distinct from the board-prop,
 * proc-version, and ABI surfaces already covered by rank-28 / rank-30 /
 * rank-27. Three classes of signal, all CRITICAL because each is a
 * canonical-emulator tell with no legitimate FP class on a real device:
 *
 *   • **AVD canonical marker (1.0)**: `ro.kernel.qemu == "1"` is the
 *     load-bearing kernel property the AOSP goldfish/ranchu kernel
 *     sets at boot. No real Android device sets this — it's an emulator-
 *     only property.
 *   • **AVD GLES marker (1.0)**: `ro.kernel.qemu.gles` set to any non-
 *     empty value indicates the host-GPU-passthrough AVD configuration.
 *     Same source as the canonical marker but a separate property — kits
 *     that mask `ro.kernel.qemu` sometimes leak `.gles` independently.
 *   • **QEMU device node (1.0)**: any of `/dev/qemu_pipe`, `/dev/qemu_trace`
 *     present. These are kernel-side device files exposed by QEMU's
 *     paravirtualization layer — they don't exist on real silicon.
 *   • **Goldfish device node (1.0)**: any of `/dev/goldfish_pipe`,
 *     `/dev/goldfish_audio`, `/dev/goldfish_events`. Goldfish-specific
 *     device files — same canonical-emulator tell.
 *   • **QEMU socket node (1.0)**: `/dev/socket/qemud` (canonical QEMU
 *     userspace daemon socket) or `/dev/socket/baseband_genyd`
 *     (Genymotion's baseband daemon socket).
 *   • **Clean (0.0)**: none of the above observed.
 *
 * Distinct from already-built emulator probes (no logic duplication):
 *   - rank-1  [BuildFingerprintProbe] — `ro.build.fingerprint`
 *   - rank-27 [CpuAbiProbe] — x86 / houdini ABI markers
 *   - rank-28 [BoardHardwareProbe] — `ro.product.board`, `ro.hardware`
 *     emulator marker substrings
 *   - rank-30 [ProcVersionProbe] — `/proc/version` kernel-name markers
 *
 * The conceptual overlap is the goldfish/ranchu lineage: rank-28 scans
 * board PROPERTIES for those substrings, this probe scans for
 * **filesystem DEVICE NODES** in `/dev/qemu_*` and `/dev/goldfish_*`
 * plus the canonical `ro.kernel.qemu` boot marker. Different surfaces,
 * different leak paths — emulator-suppression kits (LSPosed modules
 * targeting buildprops) frequently mask one surface but not the other,
 * so divergence between rank-4 and rank-28 is itself diagnostic. **DO
 * NOT consolidate** with rank-28's marker list — the marker substrings
 * here (`qemu_`, `goldfish_`) describe device-node *names* not
 * board-codename *substrings* and have different match semantics.
 *
 * Uses **only** the base [ProbeContext] surface (`getSystemProperty` +
 * `fileExists`) — no constructor-injected suppliers needed because
 * both property reads and file-existence checks are first-class probe
 * surfaces. Same shape as rank-3 [com.detectorlab.probes.root.SuDetectionProbe].
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first; all canonical-emulator rules at 1.0):
 *   1.0   PATTERN_AVD_CANONICAL — `ro.kernel.qemu == "1"`
 *   1.0   PATTERN_AVD_GLES — `ro.kernel.qemu.gles` non-empty
 *   1.0   PATTERN_QEMU_DEVICE_NODE — `/dev/qemu_*` present
 *   1.0   PATTERN_GOLDFISH_DEVICE_NODE — `/dev/goldfish_*` present
 *   1.0   PATTERN_QEMU_SOCKET — `/dev/socket/qemud` or
 *         `/dev/socket/baseband_genyd` present
 *   0.0   PATTERN_CLEAN — no QEMU/Goldfish artifacts observed
 *
 * Pattern labels differ at the same 1.0 score so the consumer-side
 * forensic-report path can distinguish "saw `ro.kernel.qemu=1`" from
 * "saw `/dev/goldfish_pipe`". Same severity, different forensic class.
 *
 * Confidence:
 *   0.95  At least one of (prop-read, file-exists) accessors succeeded
 *   0.50  Both accessor classes threw on every call (degraded; no
 *         observation possible)
 *
 * Reference: shared/probes/inventory.yml rank 4 (mitigation_layer L1).
 */
class QemuArtifactsProbe : Probe {
    override val id = "emulator.qemu_artifacts"
    override val rank = 4
    override val category = ProbeCategory.EMULATOR
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 200L

    companion object {
        const val PROP_RO_KERNEL_QEMU = "ro.kernel.qemu"
        const val PROP_RO_KERNEL_QEMU_GLES = "ro.kernel.qemu.gles"

        const val AVD_CANONICAL_VALUE = "1"

        /**
         * QEMU paravirtualization device nodes. These are kernel-side
         * device files exposed by the AOSP goldfish/ranchu kernel — no
         * real Android device exposes them. Substring match is NOT
         * used; exact-path file-existence check.
         */
        val QEMU_DEVICE_NODES: List<String> = listOf(
            "/dev/qemu_pipe",
            "/dev/qemu_trace",
        )

        /**
         * Goldfish-specific device nodes. Same canonical-emulator tell
         * as [QEMU_DEVICE_NODES] but named for the goldfish family
         * specifically — some emulator kernel builds expose one set,
         * some the other, kits may mask one but not both.
         */
        val GOLDFISH_DEVICE_NODES: List<String> = listOf(
            "/dev/goldfish_pipe",
            "/dev/goldfish_audio",
            "/dev/goldfish_events",
        )

        /**
         * Userspace socket nodes used by QEMU/Genymotion guest agents
         * for host communication. `qemud` is canonical QEMU; the
         * `baseband_genyd` socket is the Genymotion baseband daemon.
         */
        val QEMU_SOCKET_NODES: List<String> = listOf(
            "/dev/socket/qemud",
            "/dev/socket/baseband_genyd",
        )

        const val PATTERN_AVD_CANONICAL = "avd_canonical"
        const val PATTERN_AVD_GLES = "avd_gles"
        const val PATTERN_QEMU_DEVICE_NODE = "qemu_device_node"
        const val PATTERN_GOLDFISH_DEVICE_NODE = "goldfish_device_node"
        const val PATTERN_QEMU_SOCKET = "qemu_socket_node"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_QEMU_ARTIFACT = 1.0
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read ro.kernel.qemu / ro.kernel.qemu.gles + check /dev/qemu_*, " +
                "/dev/goldfish_*, /dev/socket/qemud/baseband_genyd device nodes; " +
                "detect canonical AVD marker + QEMU/Goldfish-specific kernel surface"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Property reads — each one independent so a throwing
            // read doesn't suppress observation of the others.
            var propReadAttempted = 0
            var propReadSucceeded = 0

            fun readProp(key: String): String? {
                propReadAttempted++
                return try {
                    val v = ctx.getSystemProperty(key)
                    propReadSucceeded++
                    v
                } catch (_: Throwable) {
                    null
                }
            }

            val roKernelQemu = readProp(PROP_RO_KERNEL_QEMU)
            val roKernelQemuGles = readProp(PROP_RO_KERNEL_QEMU_GLES)

            // File-existence checks — same per-path try/catch so the
            // probe stays crash-safe under partial accessor failure.
            var fileCheckAttempted = 0
            var fileCheckSucceeded = 0
            val devicePresence = mutableMapOf<String, Boolean>()

            fun checkPath(path: String): Boolean {
                fileCheckAttempted++
                return try {
                    val present = ctx.fileExists(path)
                    fileCheckSucceeded++
                    devicePresence[path] = present
                    present
                } catch (_: Throwable) {
                    devicePresence[path] = false
                    false
                }
            }

            val qemuDevicesPresent = QEMU_DEVICE_NODES.filter { checkPath(it) }
            val goldfishDevicesPresent = GOLDFISH_DEVICE_NODES.filter { checkPath(it) }
            val qemuSocketsPresent = QEMU_SOCKET_NODES.filter { checkPath(it) }

            val isAvdCanonical = roKernelQemu == AVD_CANONICAL_VALUE
            val hasAvdGles = !roKernelQemuGles.isNullOrEmpty()
            val hasQemuDevice = qemuDevicesPresent.isNotEmpty()
            val hasGoldfishDevice = goldfishDevicesPresent.isNotEmpty()
            val hasQemuSocket = qemuSocketsPresent.isNotEmpty()

            val (pattern, score) = when {
                isAvdCanonical ->
                    PATTERN_AVD_CANONICAL to SCORE_QEMU_ARTIFACT
                hasAvdGles ->
                    PATTERN_AVD_GLES to SCORE_QEMU_ARTIFACT
                hasQemuDevice ->
                    PATTERN_QEMU_DEVICE_NODE to SCORE_QEMU_ARTIFACT
                hasGoldfishDevice ->
                    PATTERN_GOLDFISH_DEVICE_NODE to SCORE_QEMU_ARTIFACT
                hasQemuSocket ->
                    PATTERN_QEMU_SOCKET to SCORE_QEMU_ARTIFACT
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            // Confidence: degraded only when EVERY accessor of BOTH
            // classes (property + file) threw. A real device or
            // emulator should land in NORMAL on the first prop read.
            val anyAccessorSucceeded = propReadSucceeded > 0 || fileCheckSucceeded > 0
            val confidence = if (anyAccessorSucceeded)
                CONFIDENCE_NORMAL
            else
                CONFIDENCE_DEGRADED

            fun listOrNone(items: List<String>): String =
                if (items.isEmpty()) "none" else items.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = PROP_RO_KERNEL_QEMU,
                    value = roKernelQemu ?: "<unavailable>",
                    expected = "empty or '0'",
                ),
                Evidence(
                    key = PROP_RO_KERNEL_QEMU_GLES,
                    value = roKernelQemuGles ?: "<unavailable>",
                    expected = "empty",
                ),
                Evidence(
                    key = "qemu.device_nodes_present",
                    value = listOrNone(qemuDevicesPresent),
                    expected = "none",
                ),
                Evidence(
                    key = "goldfish.device_nodes_present",
                    value = listOrNone(goldfishDevicesPresent),
                    expected = "none",
                ),
                Evidence(
                    key = "qemu.socket_nodes_present",
                    value = listOrNone(qemuSocketsPresent),
                    expected = "none",
                ),
                Evidence(
                    key = "qemu.is_avd_canonical",
                    value = isAvdCanonical.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "qemu.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN,
                ),
            )

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = METHOD,
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "QemuArtifactsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

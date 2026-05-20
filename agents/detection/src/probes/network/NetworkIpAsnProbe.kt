// inventory.yml rank 5, mitigation_layer L6
package com.detectorlab.probes.network

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #5 — `network.ip_asn`.
 *
 * **Declarative-only inference** of cloud / datacenter / emulator hosting via
 * the build-prop and `/proc/net/route` surfaces. The L6 production-grade fix
 * for this signal (`mitigation_layer = L6` in `shared/probes/inventory.yml`)
 * is an Android-Plugin + `INTERNET` permission live ASN lookup against the
 * caller's egress IP via a reputation API (MaxMind GeoIP2, IPinfo, IPQS).
 * That live-network path is documented in `audit/un-snapshottable.md` and is
 * **not** what this probe implements — Probe invariant #2 forbids live
 * network requests from any probe.
 *
 * Instead, this probe scans **declarative artifacts** that correlate with
 * cloud-phone / AVD / container hosting:
 *
 *   1. **AVD network-stack canonical pair**: `ro.boot.network.cellular`
 *      empty AND `ro.kernel.android.qemud` set. The QEMU userspace network
 *      daemon (`qemud`) is the load-bearing emulator signal; the cellular
 *      property is empty on any device without a real RIL.
 *      → score 0.95
 *   2. **Container-tell properties** set by Docker / ReDroid / Genymotion
 *      Cloud: `ro.docker.container`, any `redroid.*` property, or
 *      `ro.cloud_emulator`. These are emitted at container-image build time
 *      and cannot be unset without a Magisk module overlay.
 *      → score 0.85
 *   3. **`/proc/net/route` canonical AVD/Docker gateway**: hex-encoded
 *      gateway `0a000201` (= `10.0.2.1`, the AVD default) or `ac110001`
 *      (= `172.17.0.1`, the Docker default bridge). Real-phone routes are
 *      tied to the carrier's APN gateway (typically `192.168.1.1` /
 *      `100.64.x.x` CGNAT) and never use these well-known datacenter prefixes.
 *      → score 0.70
 *   4. **`persist.sys.usb.config` empty AND `ro.boot.hardware` ∈
 *      {`goldfish`, `ranchu`, `redroid`}**: the USB-config property is
 *      empty on containers (no USB stack); combined with an
 *      emulator-board codename it lands the inference.
 *      → score 0.50
 *   5. **Clean**: none of the above fire → score 0.00.
 *
 * Scoring is **max-wins** (never summed): a device that trips both signal
 * 1 (AVD network stack) and signal 4 (emulator board) scores 0.95, not 1.45.
 *
 * **Confidence**:
 *   - 0.70 — at least one of the probed properties returned a non-null
 *     value, OR `/proc/net/route` was readable. Declarative inference is
 *     inherently lower-confidence than a real ASN call (which would be
 *     CONFIDENCE_FULL = 0.95 in the L6 path).
 *   - 0.40 — every property read returned null AND the route file was
 *     unreadable (fully degraded; includes the "all accessors threw" case
 *     and the "all-null fake context" case).
 *
 * **Cross-rank coordination**:
 *   - rank-28 `BoardHardwareProbe` scans `ro.hardware` and `ro.product.board`
 *     with a broader `EMULATOR_BOARD_MARKERS` set (`goldfish, ranchu,
 *     redroid, vbox86, cancro`). This probe scans `ro.boot.hardware` (the
 *     bootloader-set sibling), a different property surface, and lands in
 *     the network category. The two probes do not compete; they cover
 *     different surfaces (board layer vs. network inference) and may
 *     legitimately both fire on the same emulator capture.
 *   - rank-18 `VpnProxyProbe` covers VPN / proxy / container-interface
 *     signals from `/proc/net/dev` and `Settings.Global.http_proxy`. This
 *     probe is orthogonal: it reads `/proc/net/route` (gateway), not
 *     `/proc/net/dev` (interface list).
 *
 * Reference: `shared/probes/inventory.yml` rank 5.
 */
class NetworkIpAsnProbe : Probe {
    override val id = "network.ip_asn"
    override val rank = 5
    override val category = ProbeCategory.NETWORK
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 200L

    companion object {
        // ── Property keys ────────────────────────────────────────────────────
        const val PROP_BOOT_NETWORK_CELLULAR = "ro.boot.network.cellular"
        const val PROP_KERNEL_ANDROID_QEMUD = "ro.kernel.android.qemud"
        const val PROP_DOCKER_CONTAINER = "ro.docker.container"
        const val PROP_CLOUD_EMULATOR = "ro.cloud_emulator"
        const val PROP_USB_CONFIG = "persist.sys.usb.config"
        const val PROP_BOOT_HARDWARE = "ro.boot.hardware"

        /**
         * The set of canonical `redroid.*` property keys that ReDroid images
         * emit at container-image build time. ProbeContext does not expose
         * a "list all properties" accessor (by design — getprop dumps are
         * shell-class capabilities that live behind `ShellProbeContext`),
         * so we sample the well-known keys directly. Hit on ANY one triggers
         * the container-tell rule.
         */
        val REDROID_PROPERTY_PROBES: List<String> = listOf(
            "redroid.boot.timestamp",
            "redroid.version",
            "redroid.ndk_translation",
            "redroid.media_codecs",
        )

        // ── /proc/net/route signatures ───────────────────────────────────────
        const val PATH_PROC_NET_ROUTE = "/proc/net/route"

        /**
         * Hex-encoded canonical emulator/datacenter gateway addresses found
         * in `/proc/net/route`. The kernel emits gateway IPs in little-endian
         * hex (e.g. `10.0.2.1` → bytes `0a 00 02 01` → little-endian dword
         * `0102000a` → hex column `0102000A`). However, real-world dumps and
         * various distros emit both `0102000A` and the visually-reversed
         * `0a000201` form depending on the column layout / endianness
         * documentation cited; we match both for robustness.
         *
         * Match is case-insensitive (some kernels emit uppercase, some
         * lowercase hex). Match is substring-based — we only require the
         * canonical token to appear anywhere on a non-header line of the
         * route table.
         */
        val EMULATOR_GATEWAY_HEX_TOKENS: List<String> = listOf(
            "0a000201",   // AVD default 10.0.2.1 (visually-reversed)
            "0102000a",   // AVD default 10.0.2.1 (little-endian byte order)
            "ac110001",   // Docker default bridge 172.17.0.1 (visually-reversed)
            "010011ac",   // Docker default bridge 172.17.0.1 (little-endian byte order)
        )

        /**
         * Emulator-keyword set scanned in `ro.boot.hardware` for the rank-4
         * (low-confidence) inference branch. Intentionally narrower than
         * rank-28 [com.detectorlab.probes.buildprop.BoardHardwareProbe.EMULATOR_BOARD_MARKERS]
         * — this probe is scoped to the network-category L6-declarative
         * inference, not the board-layer L1 detection.
         */
        val BOOT_HARDWARE_EMU_KEYWORDS: List<String> = listOf(
            "goldfish",
            "ranchu",
            "redroid",
        )

        // ── Signal labels ────────────────────────────────────────────────────
        const val SIGNAL_AVD_NETWORK_STACK = "avd_network_stack"
        const val SIGNAL_CONTAINER_TELL_PROPERTY = "container_tell_property"
        const val SIGNAL_EMULATOR_GATEWAY_ROUTE = "emulator_gateway_route"
        const val SIGNAL_NO_USB_PLUS_EMU_HARDWARE = "no_usb_plus_emu_hardware"
        const val SIGNAL_CLEAN = "clean"

        // ── Scores ───────────────────────────────────────────────────────────
        const val SCORE_AVD_NETWORK_STACK = 0.95
        const val SCORE_CONTAINER_TELL = 0.85
        const val SCORE_EMULATOR_GATEWAY = 0.70
        const val SCORE_NO_USB_PLUS_EMU = 0.50
        const val SCORE_CLEAN = 0.0

        // ── Confidence ───────────────────────────────────────────────────────
        const val CONFIDENCE_DECLARATIVE = 0.70
        const val CONFIDENCE_DEGRADED = 0.40

        const val METHOD =
            "build-prop + /proc/net/route inference (no live network)"

        /**
         * Parse the kernel's `/proc/net/route` table into a list of gateway
         * tokens. The kernel format is column-aligned ASCII with a header
         * row. We extract the "Gateway" column (index 2) from non-header
         * rows and return each entry lowercased for case-insensitive match.
         * Returns the full content lowercased if column-parsing fails (we
         * still want substring matches against the well-known tokens).
         */
        internal fun extractGatewayTokens(content: String): List<String> {
            val out = mutableListOf<String>()
            for ((i, line) in content.lineSequence().withIndex()) {
                if (i == 0) continue // header row: "Iface\tDestination\tGateway\t..."
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                // Split on whitespace (tabs OR spaces — kernels vary)
                val cols = trimmed.split(Regex("\\s+"))
                if (cols.size >= 3) {
                    out.add(cols[2].lowercase())
                }
            }
            return out
        }

        /**
         * True iff [content] contains any canonical emulator/datacenter
         * gateway hex token in its `/proc/net/route` body. Falls back to a
         * full-content substring scan if column parsing returns empty, so
         * malformed-but-still-recognizable route dumps still trip the rule.
         */
        internal fun routeContainsEmulatorGateway(content: String): Boolean {
            val tokens = extractGatewayTokens(content)
            if (tokens.isNotEmpty()) {
                for (token in tokens) {
                    if (token in EMULATOR_GATEWAY_HEX_TOKENS) return true
                }
            }
            // Defensive fallback: substring scan of the whole content
            val lower = content.lowercase()
            return EMULATOR_GATEWAY_HEX_TOKENS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // ── Build-prop reads (each guarded so one throw doesn't poison the rest) ──
            // We track "observed something" rather than "accessor didn't
            // throw" — a fake context that returns null for every key is
            // indistinguishable from a degraded production context where
            // every read threw, and both should land in CONFIDENCE_DEGRADED.
            var anyPropObserved = false
            fun readProp(key: String): String? = try {
                val v = ctx.getSystemProperty(key)
                if (v != null) anyPropObserved = true
                v
            } catch (_: Throwable) {
                null
            }

            val cellularProp = readProp(PROP_BOOT_NETWORK_CELLULAR)
            val qemudProp = readProp(PROP_KERNEL_ANDROID_QEMUD)
            val dockerProp = readProp(PROP_DOCKER_CONTAINER)
            val cloudEmuProp = readProp(PROP_CLOUD_EMULATOR)
            val usbConfigProp = readProp(PROP_USB_CONFIG)
            val bootHardwareProp = readProp(PROP_BOOT_HARDWARE)

            val redroidHits: List<String> = REDROID_PROPERTY_PROBES.mapNotNull { key ->
                val v = readProp(key)
                if (v != null) key else null
            }

            // ── /proc/net/route read ────────────────────────────────────────
            // Same "observed something" gate as the property reads: a fake
            // context that returns null without throwing is indistinguishable
            // from a degraded read at the production layer.
            var routeObserved = false
            val routeContent: String? = try {
                val c = ctx.readFile(PATH_PROC_NET_ROUTE, maxBytes = 32_768)
                if (c != null) routeObserved = true
                c
            } catch (_: Throwable) {
                null
            }

            // ── Signal evaluation (max-wins) ────────────────────────────────
            // Signal 1: AVD network stack
            // `qemud exists` = property is set (non-null). Empty-string is
            // still "set". Real phones never have this property; AVDs do.
            val cellularEmpty = cellularProp.isNullOrEmpty()
            val qemudExists = qemudProp != null
            val signal1 = cellularEmpty && qemudExists

            // Signal 2: container-tell properties.
            // "Set" = non-null AND non-empty. Empty-string is the spoof-stack
            // sentinel for "key declared but value masked" (mirrors the
            // `RedroidSpoofedSnapshot` pattern of leaving the key visible
            // to a `getprop` dump while disarming the score-bearing rule),
            // and we treat it as "not set". For `redroid.*`, presence of
            // the key in the probed-key set is sufficient — the key NAME
            // is itself the leak, regardless of value.
            val dockerSet = !dockerProp.isNullOrEmpty()
            val cloudEmuSet = !cloudEmuProp.isNullOrEmpty()
            val redroidPresent = redroidHits.isNotEmpty()
            val signal2 = dockerSet || cloudEmuSet || redroidPresent

            // Signal 3: emulator gateway in /proc/net/route
            val signal3 = routeContent?.let { routeContainsEmulatorGateway(it) } == true

            // Signal 4: empty USB + emulator board boot-hardware
            val usbEmpty = usbConfigProp.isNullOrEmpty()
            val bootHwLower = bootHardwareProp?.lowercase() ?: ""
            val emuBootHw = bootHardwareProp != null &&
                BOOT_HARDWARE_EMU_KEYWORDS.any { it in bootHwLower }
            val signal4 = usbEmpty && emuBootHw

            // Max-wins cascade with signal label
            val (signal, score) = when {
                signal1 -> SIGNAL_AVD_NETWORK_STACK to SCORE_AVD_NETWORK_STACK
                signal2 -> SIGNAL_CONTAINER_TELL_PROPERTY to SCORE_CONTAINER_TELL
                signal3 -> SIGNAL_EMULATOR_GATEWAY_ROUTE to SCORE_EMULATOR_GATEWAY
                signal4 -> SIGNAL_NO_USB_PLUS_EMU_HARDWARE to SCORE_NO_USB_PLUS_EMU
                else -> SIGNAL_CLEAN to SCORE_CLEAN
            }

            // Confidence: declarative inference is inherently lower than a
            // live ASN call. If neither surface yielded any observation
            // (all-null context OR every accessor threw), drop to fully
            // degraded.
            val confidence = if (anyPropObserved || routeObserved) {
                CONFIDENCE_DECLARATIVE
            } else {
                CONFIDENCE_DEGRADED
            }

            val evidence = listOf(
                Evidence(
                    key = "network.ip_asn.signal",
                    value = signal,
                    expected = SIGNAL_CLEAN,
                ),
                Evidence(
                    key = "network.ip_asn.declarative_only",
                    value = true,
                ),
                Evidence(
                    key = "network.ip_asn.method",
                    value = METHOD,
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
                "NetworkIpAsnProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

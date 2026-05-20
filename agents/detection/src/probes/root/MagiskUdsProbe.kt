// inventory.yml rank 3.5 (code-rank 90), mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.5 — root.magisk_uds.
 *
 * Detects Magisk's `magiskd` daemon by enumerating Unix-Domain
 * Sockets in `/proc/net/unix`. Pre-Magisk-25 versions register
 * the literal `@MAGISK` abstract-namespace socket; Magisk-25+
 * randomizes the socket name to defeat literal-string matching.
 * The probe scans the socket-name list for the canonical Magisk
 * literals AND for filesystem-path fragments (`/sbin/.magisk/`,
 * `/dev/.magisk_unique`) that still leak even on Magisk-25+
 * installations.
 *
 * **Honest false-negative class (documented in KDoc)**: Magisk-25+
 * with the randomized-name patch produces a socket name like
 * `@5a3f1c2b8d6e9700` — completely indistinguishable from a
 * legitimate Android system daemon's abstract socket by literal-
 * string match alone. Detecting that case requires heuristic
 * approaches (entropy analysis, no-associated-binary detection)
 * that are out of scope for an offline JVM probe. The probe is
 * STILL useful because (a) Magisk Stable through 24.3 is still
 * widely deployed, (b) Magisk forks (Delta, Kitsune, KernelSU's
 * sumagisk shim) preserve the `MAGISK` substring in their forked
 * builds, (c) the filesystem-path socket variants leak the
 * fingerprint even when randomized.
 *
 * Magisk fingerprint substrings the probe looks for in socket
 * names (case-insensitive):
 *   - `@MAGISK`            — pre-Magisk-25 canonical literal
 *   - `magisk`             — generic substring (forks + variants)
 *   - `MAGISK`             — uppercase canonical
 *   - `/.magisk`           — file-namespace socket path
 *   - `/sbin/.magisk`      — pre-A11 Magisk runtime root
 *   - `/dev/.magisk`       — Magisk's tmpfs working directory
 *
 * Signal classes (HIGH severity — Magisk DenyList does NOT hide
 * `/proc/net/unix` content; the socket leak is dispositive on
 * any Magisk version pre-25 with no special obfuscation):
 *
 *   • **Magisk socket present (0.95)**: at least one socket name
 *     contains a magisk-fingerprint substring. Dispositive for
 *     Magisk presence — the FP class on this surface is empty
 *     (Android system daemons do not use `magisk` substrings).
 *   • **Clean (0.0)**: no fingerprint match in the captured
 *     socket list.
 *   • **No observation (0.0)**: accessor returned an empty list
 *     AND the snapshot signaled no captured /proc/net/unix data.
 *     Confidence DEGRADED reflects the partial-observation honesty.
 *
 * Cannot distinguish "no UDS captured" from "captured but truly
 * empty" because both produce an empty list. The probe's KDoc
 * notes this; consumer-side aggregation should weight low-
 * confidence observations accordingly.
 *
 * Scoring (max wins; first-match cascade):
 *   0.95  PATTERN_MAGISK_UDS_PRESENT
 *   0.00  PATTERN_CLEAN
 *
 * Confidence:
 *   0.95  NORMAL    socket list non-empty (real observation)
 *   0.50  DEGRADED  socket list empty (could be clean OR could be
 *                    no-observation; the probe defaults to the
 *                    conservative "no signal" answer)
 *
 * **inventoryRank vs code-rank**: inventory 3.5 → code-rank 90
 * (next free slot above Gap #12's 89). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 3.5 (mitigation_layer L4);
 * audit/spoof-stack/real-world-detectors.md row "Magisk Unix
 * Domain Socket name probing"; audit/spoof-stack/real-world-gap-
 * list.md Gap #1. Origin: KimChangYoun/rootbeerFresh (RootBeer
 * fork that added the UDS enumeration scan).
 */
class MagiskUdsProbe : Probe {
    override val id = "root.magisk_uds"
    override val rank = RANK
    override val inventoryRank = 3.5
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 150L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.5; Int slot 90 picked
         * (next free above Gap #12's 89). Cross-cutting #7.
         */
        const val RANK = 90

        /**
         * Magisk-fingerprint substrings to look for inside
         * `/proc/net/unix` socket-name entries. Case-INSENSITIVE
         * because Magisk's literal `MAGISK` and lowercased `magisk`
         * both appear depending on fork and version. The probe
         * lowercases the candidate before matching.
         */
        val MAGISK_FINGERPRINT_SUBSTRINGS: List<String> = listOf(
            "@magisk",
            "magisk",
            "/.magisk",
            "/sbin/.magisk",
            "/dev/.magisk",
        )

        const val PATTERN_MAGISK_UDS_PRESENT = "magisk_uds_present"
        const val PATTERN_CLEAN = "clean"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_MAGISK_UDS = 0.95
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Enumerate /proc/net/unix socket names; match against " +
                "Magisk-fingerprint substrings (@MAGISK / magisk / " +
                "/.magisk / /sbin/.magisk / /dev/.magisk). RootBeerFresh + " +
                "Momo target surface (Power-13 Gap #1)."

        /**
         * Filter [sockets] for entries whose lowercased name
         * contains a Magisk fingerprint substring. Returns the
         * VERBATIM matching socket name (preserving original case)
         * for evidence emission.
         */
        internal fun findMagiskSockets(sockets: List<String>): List<String> {
            if (sockets.isEmpty()) return emptyList()
            return sockets.filter { socket ->
                val lower = socket.lowercase()
                MAGISK_FINGERPRINT_SUBSTRINGS.any { it in lower }
            }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val sockets: List<String> = try {
                ctx.queryProcNetUnixSockets()
            } catch (_: Throwable) {
                emptyList()
            }

            val magiskHits = findMagiskSockets(sockets)
            val hasObservation = sockets.isNotEmpty()
            val magiskPresent = magiskHits.isNotEmpty()

            val (pattern, score, confidence) = when {
                magiskPresent ->
                    Triple(PATTERN_MAGISK_UDS_PRESENT, SCORE_MAGISK_UDS, CONFIDENCE_NORMAL)
                hasObservation ->
                    Triple(PATTERN_CLEAN, SCORE_CLEAN, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
            }

            fun listOrNone(items: List<String>): String =
                if (items.isEmpty()) "none" else items.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "magisk_uds.socket_list_observed",
                    value = hasObservation.toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "magisk_uds.socket_count",
                    value = sockets.size.toString(),
                    expected = null,
                ),
                Evidence(
                    key = "magisk_uds.magisk_socket_hits",
                    value = listOrNone(magiskHits),
                    expected = "none",
                ),
                Evidence(
                    key = "magisk_uds.pattern",
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
                "MagiskUdsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

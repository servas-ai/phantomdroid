package com.detectorlab.probes.app

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — app.ig_family_device_id_header (CLO-18, BEST-STACK row 7)
 *
 * Reads the JSON evidence file written by the companion mitmproxy addon
 * (`ig_family_device_id_addon.py`) and classifies the presence and entropy
 * of the ``x-ig-family-device-id`` header in Instagram / Threads / Facebook /
 * WhatsApp outbound traffic.
 *
 * Layer: L4 + L6 (passive HTTP header capture via mitmproxy MITM harness).
 * The probe itself is read-only: it only consumes the file the addon wrote.
 *
 * Ethics block
 * ─────────────
 * The capture file contains a ``sandbox_marker`` field.  If the file is
 * absent or the field is blank the probe returns ``ProbeResult.skipped``
 * to prevent accidental analysis of production accounts.
 *
 * Score table
 * ───────────
 *   Header present, low entropy (≤ 80 bits)  → 0.95
 *     Cloned or replayed ID — strongest farm signal.
 *   Header present, normal entropy (> 80 bits) → 0.85
 *     Genuine device binding across Meta apps observed.
 *   Header absent from in-scope traffic → 0.05
 *     App may not have sent it yet; inconclusive.
 *   File not found / no sandbox marker → skipped.
 *
 * Confidence
 * ──────────
 *   capture_count ≥ 20  → 0.92
 *   capture_count ≥ 5   → 0.75
 *   capture_count < 5   → 0.50  (too few observations)
 *
 * Depends on: META-22, CLO-17
 * Effort: L (mitmproxy harness + sandbox account required)
 */
class IgFamilyDeviceIdHeaderProbe(
    internal val captureFilePath: String = DEFAULT_CAPTURE_PATH,
) : Probe {

    override val id = "app.ig_family_device_id_header"
    override val rank = RANK
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NETWORK
    override val budgetMs = 500L

    companion object {
        /** BEST-STACK row 7 — Meta Family Device ID cross-app header. */
        const val RANK = 67

        /** Canonical path the mitmproxy addon writes to. */
        const val DEFAULT_CAPTURE_PATH =
            "/data/local/tmp/detectorlab/ig_fam_device_id.json"

        // Shannon-entropy threshold distinguishing cloned from genuine IDs.
        const val LOW_ENTROPY_THRESHOLD = 80.0
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // Ethics block: file must exist and contain a non-blank sandbox marker.
            if (!ctx.fileExists(captureFilePath)) {
                return ProbeResult.skipped(
                    "Capture file not found — mitmproxy harness not running or " +
                        "sandbox account not configured.",
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            val raw = ctx.readFile(captureFilePath, maxBytes = 16_384)
                ?: return ProbeResult.skipped(
                    "Capture file exists but could not be read.",
                    runtimeMs = System.currentTimeMillis() - start,
                )

            val capture = parseCaptureFile(raw)
                ?: return ProbeResult.failed(
                    "Capture file is malformed JSON or missing required fields.",
                    runtimeMs = System.currentTimeMillis() - start,
                )

            // Ethics block: require sandbox marker in the capture.
            if (capture.sandboxMarker.isBlank()) {
                return ProbeResult.skipped(
                    "No sandbox_marker in capture file — refusing to analyse " +
                        "traffic that may belong to a production account.",
                    runtimeMs = System.currentTimeMillis() - start,
                )
            }

            val score = scoreFor(capture)
            val confidence = confidenceFor(capture.captureCount)

            val evidence = buildList {
                add(Evidence("ig.header_present", capture.headerPresent, expected = false))
                add(Evidence("ig.header_value", capture.value ?: "absent"))
                add(Evidence("ig.is_valid_uuid", capture.isValidUuid, expected = true))
                add(Evidence("ig.entropy_bits", capture.entropyBits))
                add(Evidence("ig.capture_count", capture.captureCount))
                add(Evidence("ig.header_count", capture.headerCount))
                add(Evidence("ig.apps_seen", capture.appsSeen.joinToString(",")))
                add(Evidence("ig.unique_value_count", capture.uniqueValues.size))
                add(Evidence("ig.sandbox_marker_present", capture.sandboxMarker.isNotBlank(), expected = true))
            }

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = "passive mitmproxy capture of x-ig-family-device-id header",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "IgFamilyDeviceIdHeaderProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }

    internal fun scoreFor(capture: CaptureFile): Double = when {
        !capture.headerPresent -> 0.05
        capture.entropyBits <= LOW_ENTROPY_THRESHOLD -> 0.95 // cloned/replayed
        else -> 0.85                                          // genuine cross-app binding
    }

    internal fun confidenceFor(captureCount: Int): Double = when {
        captureCount >= 20 -> 0.92
        captureCount >= 5  -> 0.75
        else               -> 0.50
    }

    // ── Simple JSON parser (no library dependency) ────────────────────────

    /**
     * Extracts known fields from the flat JSON object the addon writes.
     * Deliberately avoids a third-party JSON library to keep the module
     * zero-dependency (core constraint).
     */
    internal fun parseCaptureFile(json: String): CaptureFile? {
        return try {
            CaptureFile(
                sandboxMarker  = extractString(json, "sandbox_marker") ?: "",
                headerPresent  = extractBoolean(json, "header_present") ?: false,
                value          = extractString(json, "value"),
                isValidUuid    = extractBoolean(json, "is_valid_uuid") ?: false,
                entropyBits    = extractDouble(json, "entropy_bits") ?: 0.0,
                captureCount   = extractInt(json, "capture_count") ?: 0,
                headerCount    = extractInt(json, "header_count") ?: 0,
                appsSeen       = extractStringList(json, "apps_seen"),
                uniqueValues   = extractStringList(json, "unique_values"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    internal data class CaptureFile(
        val sandboxMarker: String,
        val headerPresent: Boolean,
        val value: String?,
        val isValidUuid: Boolean,
        val entropyBits: Double,
        val captureCount: Int,
        val headerCount: Int,
        val appsSeen: List<String>,
        val uniqueValues: List<String>,
    )

    // ── Minimal JSON field extractors (regex-based, no deps) ─────────────

    private fun extractString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|null)""")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].ifEmpty {
            if (match.value.endsWith("null")) null else ""
        }
    }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val pattern = Regex(""""$key"\s*:\s*(true|false)""")
        return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractDouble(json: String, key: String): Double? {
        val pattern = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = Regex(""""$key"\s*:\s*(-?\d+)""")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractStringList(json: String, key: String): List<String> {
        val arrayPattern = Regex(""""$key"\s*:\s*\[([^\]]*)]""")
        val arrayMatch = arrayPattern.find(json) ?: return emptyList()
        val inner = arrayMatch.groupValues[1].trim()
        if (inner.isBlank()) return emptyList()
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(inner)
            .map { it.groupValues[1] }
            .toList()
    }
}

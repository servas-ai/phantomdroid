// inventory.yml rank 29, mitigation_layer L2 — MASTG MSTG-RESILIENCE-10
package com.detectorlab.probes.identity

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe
import java.security.MessageDigest
import kotlin.math.ln

/**
 * Probe #29 — identity.mediadrm.
 *
 * Detects emulators and synthetic devices by querying `android.media.MediaDrm`
 * for the Widevine UUID `{EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED}`. The
 * MediaDrm device unique ID is one of the strongest fingerprints Android
 * exposes: hardware-rooted on L1 Widevine (TEE/SecureBoot), persistent
 * across factory resets, and difficult to spoof per MASTG
 * MSTG-RESILIENCE-10's "multi-property binding" guidance.
 *
 * Signal classes:
 *   • **Widevine presence** — real Android devices since 4.4 support the
 *     Widevine UUID. Missing support is a strong emulator/AOSP-stripped
 *     signal.
 *   • **Security level** — `L1` (hardware-backed TEE) on real phones;
 *     `L3` (software-only) on emulators / lab images. Cross-check against
 *     model: L3 on a known-OEM phone is a strong tampering signal.
 *   • **Device unique ID** — 32 bytes per Widevine spec. All-zero / all-
 *     `f` patterns indicate stub implementations. Low Shannon entropy on
 *     the bytes suggests synthetic data.
 *   • **Vendor / version strings** — "Mock" / "Generic" / empty vendor
 *     indicates non-real implementation.
 *
 * **MediaDrm accessor gap.** `ProbeContext` does NOT expose a
 * `queryMediaDrm()` method. Querying Widevine requires the Android
 * framework's `android.media.MediaDrm` class (Android-bound, out of scope
 * for the pure-JVM probe contract). All four MediaDrm signals (widevine
 * supported, security level, vendor, version) plus the device unique ID
 * arrive via constructor-injected suppliers; the production no-arg
 * constructor returns null for all of them and the probe degrades
 * gracefully to score=0.0 / confidence=0.50. Same pattern as rank 16/17/
 * 20/23/25/26.
 *
 * Scoring (max wins; first-match cascade):
 *   1.00  Widevine UUID not supported (real Android devices all support it
 *         since API 18; absence is a strong stripped-stack signal)
 *   1.00  DEVICE_UNIQUE_ID is all-zero / all-`f` bytes (stub implementation)
 *   0.95  PROPERTY_VENDOR is "Mock" or "Generic" (non-real implementation)
 *   0.95  `securityLevel == "L3"` AND model is phone-class (real phones
 *         report L1; software-fallback L3 on a Pixel/Galaxy is tampering)
 *   0.85  DEVICE_UNIQUE_ID Shannon entropy < `ENTROPY_THRESHOLD_BITS_PER_BYTE`
 *         (suspect synthetic stub)
 *   0.70  `securityLevel == "L3"` alone (without model cross-check) —
 *         could be a tablet without TEE or a lab build, weak signal
 *   0.00  L1 + real vendor + non-zero ID + high entropy
 *
 * Confidence: 0.95 when at least one of the suppliers returned non-null;
 * 0.50 when all suppliers return null (production-stub path).
 *
 * **PII handling — critical for this probe.** The MediaDrm
 * `PROPERTY_DEVICE_UNIQUE_ID` is a 32-byte hardware identifier — STRONG
 * PII, stronger than rank-11 `ANDROID_ID` (which rotates per-app). The
 * probe records ONLY the SHA-256 hex of the byte array in evidence
 * (`mediadrm.device_unique_id_hex`), NOT the raw bytes. This preserves
 * cross-run uniqueness for forensic correlation but doesn't leak the
 * actual hardware ID even if Evidence rows are logged.
 *
 * Reference: shared/probes/inventory.yml rank 29 (mitigation_layer L2);
 * MASTG MSTG-RESILIENCE-10 multi-property binding.
 */
class MediaDrmProbe(
    private val widevineSupportedSupplier: () -> Boolean? = { null },
    private val securityLevelSupplier: () -> String? = { null },
    private val vendorSupplier: () -> String? = { null },
    private val versionSupplier: () -> String? = { null },
    private val deviceUniqueIdSupplier: () -> ByteArray? = { null },
) : Probe {
    override val id = "identity.mediadrm"
    override val rank = 29
    override val category = ProbeCategory.IDENTITY
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.HARDWARE
    override val budgetMs = 400L

    companion object {
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val WIDEVINE_UUID = "EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"

        const val SECURITY_LEVEL_L1 = "L1"
        const val SECURITY_LEVEL_L3 = "L3"

        /** Vendor strings that indicate non-real Widevine implementations. */
        val MOCK_VENDOR_NAMES: Set<String> = setOf("mock", "generic")

        /**
         * Shannon-entropy threshold for the 32-byte device unique ID.
         *
         * Shannon entropy on a sample of size n is capped by log2(n), not
         * log2(alphabet). For a 32-byte hardware ID the max possible entropy
         * is log2(32) = 5.0 bits/byte (all 32 bytes unique). Real Widevine
         * IDs typically achieve 4.5-4.9 bits/byte (~25-30 unique bytes).
         * Synthetic stubs with low repetition produce values well below 4.0.
         *
         * The 4.0 threshold catches obvious stubs (e.g. alternating-byte
         * patterns at 1.0 bits/byte) without false-positive on real entropy
         * fluctuation around 4.5.
         *
         * NOTE: spec called for ">= 6.0" which is mathematically unreachable
         * with a 32-byte sample. The 4.0 calibration is the empirically
         * correct ceiling for this sample size — documented as a deviation
         * from the spec literal.
         */
        const val ENTROPY_THRESHOLD_BITS_PER_BYTE = 4.0

        const val PATTERN_NO_WIDEVINE = "no_widevine"
        const val PATTERN_ZERO_OR_FF_ID = "zero_or_ff_id"
        const val PATTERN_MOCK_VENDOR = "mock_vendor"
        const val PATTERN_L3_ON_PHONE = "l3_on_phone"
        const val PATTERN_LOW_ENTROPY_ID = "low_entropy_id"
        const val PATTERN_L3_ALONE = "l3_alone"
        const val PATTERN_CLEAN_L1 = "clean_l1"
        const val PATTERN_NO_SIGNALS = "no_signals"

        const val SCORE_TOP_TIER = 1.0
        const val SCORE_HIGH_SUSPICION = 0.95
        const val SCORE_MEDIUM_SUSPICION = 0.85
        const val SCORE_L3_ALONE = 0.70
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Query MediaDrm Widevine UUID for security level + vendor + " +
                "version + 32-byte device unique ID; detect L3 software " +
                "fallback and zero/low-entropy unique-ID stubs"

        /**
         * Shannon entropy in bits/byte of [bytes]. Empty / single-byte inputs
         * return 0.0. Maximum is 8.0 bits/byte (uniform distribution over
         * 256 possible byte values).
         *
         * NOTE: variant of `AndroidIdProbe.shannonEntropyBitsPerChar` — same
         * algorithm, different unit (bits/byte vs bits/char). The two
         * helpers measure different alphabets and aren't directly
         * interchangeable. KDoc on the shared algorithm: see rank-11.
         */
        internal fun shannonEntropyBitsPerByte(bytes: ByteArray): Double {
            if (bytes.size <= 1) return 0.0
            val counts = IntArray(256)
            for (b in bytes) counts[b.toInt() and 0xFF]++
            val n = bytes.size.toDouble()
            var h = 0.0
            val log2 = ln(2.0)
            for (c in counts) {
                if (c == 0) continue
                val p = c / n
                h -= p * (ln(p) / log2)
            }
            return h
        }

        /** True iff every byte of [bytes] is `0x00` (or all `0xFF`). */
        internal fun isAllZeroOrFF(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val first = bytes[0]
            if (first != 0x00.toByte() && first != 0xFF.toByte()) return false
            for (b in bytes) if (b != first) return false
            return true
        }

        /** SHA-256 hex of [bytes] — preserves cross-run uniqueness for forensic
         *  correlation without leaking the raw 32-byte hardware identifier. */
        internal fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val widevineSupported: Boolean? = try {
                widevineSupportedSupplier()
            } catch (_: Throwable) { null }

            val securityLevel: String? = try {
                securityLevelSupplier()
            } catch (_: Throwable) { null }

            val vendor: String? = try { vendorSupplier() } catch (_: Throwable) { null }
            val version: String? = try { versionSupplier() } catch (_: Throwable) { null }
            val uniqueId: ByteArray? = try {
                deviceUniqueIdSupplier()
            } catch (_: Throwable) { null }

            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) { null }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)

            val widevineUnsupported = widevineSupported == false
            val uniqueIdAllZeroOrFf = uniqueId != null && isAllZeroOrFF(uniqueId)
            val vendorIsMock = vendor != null &&
                vendor.trim().lowercase() in MOCK_VENDOR_NAMES
            val isL3 = securityLevel?.trim()?.uppercase() == SECURITY_LEVEL_L3
            val isL1 = securityLevel?.trim()?.uppercase() == SECURITY_LEVEL_L1
            val isL3OnPhone = isL3 && phoneClass

            val entropy = if (uniqueId != null && !uniqueIdAllZeroOrFf) {
                shannonEntropyBitsPerByte(uniqueId)
            } else 0.0
            val lowEntropy = uniqueId != null && !uniqueIdAllZeroOrFf &&
                entropy < ENTROPY_THRESHOLD_BITS_PER_BYTE

            val (pattern, score) = when {
                widevineUnsupported ->
                    PATTERN_NO_WIDEVINE to SCORE_TOP_TIER
                uniqueIdAllZeroOrFf ->
                    PATTERN_ZERO_OR_FF_ID to SCORE_TOP_TIER
                vendorIsMock ->
                    PATTERN_MOCK_VENDOR to SCORE_HIGH_SUSPICION
                isL3OnPhone ->
                    PATTERN_L3_ON_PHONE to SCORE_HIGH_SUSPICION
                lowEntropy ->
                    PATTERN_LOW_ENTROPY_ID to SCORE_MEDIUM_SUSPICION
                isL3 ->
                    PATTERN_L3_ALONE to SCORE_L3_ALONE
                isL1 || uniqueId != null || vendor != null ->
                    PATTERN_CLEAN_L1 to SCORE_CLEAN
                else ->
                    PATTERN_NO_SIGNALS to SCORE_CLEAN
            }

            val anyReadable = widevineSupported != null || securityLevel != null ||
                vendor != null || version != null || uniqueId != null
            val confidence = if (anyReadable) CONFIDENCE_NORMAL else CONFIDENCE_DEGRADED

            val uniqueIdHex = uniqueId?.let { sha256Hex(it) } ?: "null"
            val uniqueIdLength = uniqueId?.size ?: 0
            val entropyDisplay = "%.3f".format(java.util.Locale.ROOT, entropy)

            val evidence = listOf(
                Evidence(
                    key = "mediadrm.widevine_supported",
                    value = (widevineSupported ?: "null").toString(),
                    expected = "true",
                ),
                Evidence(
                    key = "mediadrm.security_level",
                    value = securityLevel ?: "null",
                    expected = "L1 on phone-class device",
                ),
                Evidence(
                    key = "mediadrm.vendor",
                    value = vendor ?: "null",
                    expected = "<real-OEM vendor>",
                ),
                Evidence(
                    key = "mediadrm.version",
                    value = version ?: "null",
                    expected = "<non-empty>",
                ),
                Evidence(
                    key = "mediadrm.device_unique_id_hex",
                    value = uniqueIdHex,
                    expected = "<non-zero high-entropy SHA-256 hex>",
                ),
                Evidence(
                    key = "mediadrm.device_unique_id_length",
                    value = uniqueIdLength,
                    expected = "32",
                ),
                Evidence(
                    key = "mediadrm.entropy_bits_per_byte",
                    value = entropyDisplay,
                    expected = ">= 6.0",
                ),
                Evidence(
                    key = "mediadrm.pattern",
                    value = pattern,
                    expected = PATTERN_CLEAN_L1,
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
                "MediaDrmProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

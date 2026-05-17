package com.detectorlab.core

/**
 * Result of one Probe execution. Maps 1:1 to JSON-Schema-v1
 * (see docs/probe-schema.md).
 *
 * Score semantics:
 *   0.00 - 0.05  Not detected (real device)
 *   0.05 - 0.30  Suspicion, not certain
 *   0.30 - 0.70  Detectable with confidence
 *   0.70 - 1.00  Certainly detected
 */
data class ProbeResult(
    val score: Double,                  // 0.0 .. 1.0, clamped
    val confidence: Double,             // 0.0 .. 1.0, statistical confidence
    val evidence: List<Evidence>,       // ordered, may be empty
    val method: String,                 // human-readable methodology
    val runtimeMs: Long,                // measured wall-clock duration
    val failed: Boolean = false,        // true iff probe could not execute
    val failureReason: String? = null,  // present iff failed=true
) {
    init {
        require(score in 0.0..1.0) { "score out of range: $score" }
        require(confidence in 0.0..1.0) { "confidence out of range: $confidence" }
        require(runtimeMs >= 0) { "runtimeMs must be non-negative: $runtimeMs" }
        require(failed == (failureReason != null)) { "failed-flag and failureReason must agree" }
    }

    /** True iff the probe ran to completion (i.e. neither failed nor skipped). */
    val ok: Boolean get() = !failed

    /** True iff the probe deliberately abstained (no exception, no verdict). */
    val skipped: Boolean get() = failed && failureReason?.startsWith(SKIPPED_PREFIX) == true

    companion object {
        const val SKIPPED_PREFIX = "skipped: "

        fun failed(reason: String, runtimeMs: Long = 0L): ProbeResult = ProbeResult(
            score = 0.0,
            confidence = 0.0,
            evidence = emptyList(),
            method = "(failed)",
            runtimeMs = runtimeMs,
            failed = true,
            failureReason = reason,
        )

        /**
         * Probe deliberately abstained — usually because a required runtime
         * permission was not granted, a target API was unavailable, or the
         * environment lacks the probed surface (e.g. no Wi-Fi adapter). Carries
         * no score, no confidence; downstream consumers MUST treat this as a
         * "no signal" record, NOT a "clean" verdict.
         *
         * Implemented as a flavour of `failed` so existing consumers continue
         * to work; `result.skipped == true` distinguishes the two.
         */
        fun skipped(reason: String, runtimeMs: Long = 0L): ProbeResult = ProbeResult(
            score = 0.0,
            confidence = 0.0,
            evidence = emptyList(),
            method = "(skipped)",
            runtimeMs = runtimeMs,
            failed = true,
            failureReason = SKIPPED_PREFIX + reason,
        )
    }
}

/**
 * Evidence is a single observation that contributed to the probe's score.
 * Type is intentionally permissive (String | Number | Boolean) to fit JSON-Schema.
 */
data class Evidence(
    val key: String,
    val value: Any,                     // String, Number, or Boolean
    val expected: Any? = null,          // null = no a-priori expectation
)

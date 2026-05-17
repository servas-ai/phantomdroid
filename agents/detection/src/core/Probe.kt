package com.detectorlab.core

/**
 * A Probe is a single, contained Android-detection test. The contract is
 * deliberately narrow so probes are interchangeable in the runner and so
 * the JSON-Schema-v1 binding is mechanical.
 *
 * Invariants (enforced by ProbeRunner + tests):
 *   1. run() must complete within budgetMs (hard timeout)
 *   2. run() must NOT make network requests to live third-party services
 *   3. run() must produce a deterministic ProbeResult
 *   4. run() must NOT throw uncaught exceptions (use ProbeResult.failed)
 *   5. id, category, severity, androidLayer are declarative
 */
interface Probe {
    val id: String                 // e.g. "buildprop.fingerprint"
    val rank: Int                  // 1..75 (synced with probes/inventory.yml)
    val category: ProbeCategory
    val severity: ProbeSeverity
    val androidLayer: AndroidLayer
    val budgetMs: Long             // hard timeout, must be <= 5000

    /**
     * Probe execution. Implementations must be idempotent and side-effect-free
     * beyond reading device state.
     *
     * @param ctx ProbeContext provides android.content.Context-equivalent access
     *            without binding probes to the Android framework type system,
     *            so probes are testable in pure-JVM unit tests via fakes.
     * @return ProbeResult with score in [0.0, 1.0] and evidence.
     */
    suspend fun run(ctx: ProbeContext): ProbeResult
}

enum class ProbeCategory {
    BUILDPROP, INTEGRITY, ROOT, EMULATOR, NETWORK,
    IDENTITY, RUNTIME, SENSORS, UI, ENV
}

enum class ProbeSeverity { CRITICAL, HIGH, MEDIUM, LOW, TRACE }

enum class AndroidLayer { APPLICATION, FRAMEWORK, NATIVE, KERNEL, HARDWARE, NETWORK }

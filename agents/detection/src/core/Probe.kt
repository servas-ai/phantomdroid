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
    val rank: Int                  // 1..99 (Int slot for the probe runner)
    val category: ProbeCategory
    val severity: ProbeSeverity
    val androidLayer: AndroidLayer
    val budgetMs: Long             // hard timeout, must be <= 5000

    /**
     * Canonical inventory rank from `shared/probes/inventory.yml`. May be
     * fractional (e.g. 8.5, 39.5, 40.5). Defaults to `rank.toDouble()` so
     * probes whose code-rank matches the inventory don't need to override.
     *
     * Cross-cutting #7 (FIXED 2026-05-20): closes the Int-vs-Double mismatch
     * between this interface and the inventory schema. Probes with fractional
     * inventory ranks (ScreenLockProbe = 40.5, DebuggerTracerPidProbe = 8.5,
     * LocationMockRaspProbe = 39.5) override this to surface their canonical
     * rank for reporting/aggregation, while keeping their Int `rank` for the
     * runner's slot-keyed routing.
     */
    val inventoryRank: Double
        get() = rank.toDouble()

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

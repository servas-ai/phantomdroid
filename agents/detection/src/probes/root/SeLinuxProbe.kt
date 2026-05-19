// inventory.yml rank 14, mitigation_layer L4
package com.detectorlab.probes.root

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #14 — root.selinux.
 *
 * Detects SELinux enforcement-state tampering — Permissive mode on a user
 * build is a strong root indicator (the kernel-level guardrail that prevents
 * many privesc exploits has been switched off). The probe combines four
 * independent signal sources:
 *
 *   1. `/sys/fs/selinux/enforce` — kernel-truth: `1` (Enforcing) / `0`
 *      (Permissive). On a production user build this is `1`.
 *   2. `ro.boot.selinux` — bootloader-set property. `enforcing` on real
 *      production; `permissive` or `disabled` are root indicators.
 *   3. `ro.build.selinux` — build-set property. `1` on Enforcing user builds.
 *      Some OEMs leave this empty even on real production devices.
 *   4. `/sys/fs/selinux/policy` — kernel-level policy file. Existence implies
 *      SELinux kernel support; absence is suspicious on any modern device.
 *
 * The fifth canonical signal — `getcon` to read the current process's
 * SELinux context — is INTENTIONALLY OMITTED in this revision: the base
 * `ProbeContext` does not expose a shell accessor, the `ShellProbeContext`
 * allowlist (see `AllowlistedCommand`) lists `GETENFORCE` but not `GETCON`,
 * and `runAllowlistedCommand` would require this probe to opt into the
 * shell-capability subtype. Acceptable gap for an L4 rank-14 probe given
 * the other four signal sources already cover Permissive-state detection;
 * documented here so a follow-up can add a `GETCON` allowlist entry +
 * `ShellProbeContext` opt-in if needed.
 *
 * Scoring (max wins across rules; never summed):
 *   1.00  `/sys/fs/selinux/enforce` reads "0"          (Permissive — strongest)
 *   1.00  `ro.boot.selinux` ∈ {"permissive", "disabled"} (bootloader override)
 *   0.85  `ro.build.selinux == "0"` AND another signal says Enforcing
 *         (inconsistent — strong tampering hint short of confirmed Permissive)
 *   0.30  All property reads return empty AND accessors were available
 *         (suspiciously bare — recorded at low confidence)
 *   0.00  All signals consistent Enforcing (clean production)
 *
 * Confidence: 0.95 when ≥ 2 signal sources accessed cleanly; 0.60 when
 * exactly 1; 0.30 when 0 (degraded — every accessor threw).
 *
 * Reference: shared/probes/inventory.yml rank 14 (mitigation_layer L4).
 */
class SeLinuxProbe : Probe {
    override val id = "root.selinux"
    override val rank = 14
    override val category = ProbeCategory.ROOT
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.KERNEL
    override val budgetMs = 200L

    companion object {
        const val PATH_SELINUX_ENFORCE = "/sys/fs/selinux/enforce"
        const val PATH_SELINUX_POLICY = "/sys/fs/selinux/policy"

        const val PROP_RO_BOOT_SELINUX = "ro.boot.selinux"
        const val PROP_RO_BUILD_SELINUX = "ro.build.selinux"

        const val ENFORCE_VALUE_ENFORCING = "1"
        const val ENFORCE_VALUE_PERMISSIVE = "0"

        const val BOOT_STATE_ENFORCING = "enforcing"
        const val BOOT_STATE_PERMISSIVE = "permissive"
        const val BOOT_STATE_DISABLED = "disabled"

        const val SCORE_PERMISSIVE = 1.0
        const val SCORE_INCONSISTENT = 0.85
        const val SCORE_EMPTY_PROPS = 0.30
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read /sys/fs/selinux/enforce + ro.boot.selinux + ro.build.selinux + " +
                "optional getcon context, score Permissive/inconsistent as root indicator"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            // ── Signal 1: /sys/fs/selinux/enforce ─────────────────────────────
            val enforceRaw: String? = try {
                ctx.readFile(PATH_SELINUX_ENFORCE, maxBytes = 16)?.trim()
            } catch (_: Throwable) {
                null
            }
            val enforceReadable = enforceRaw != null

            // ── Signal 2: ro.boot.selinux ─────────────────────────────────────
            var bootSelinuxAccessible = false
            val bootSelinux: String? = try {
                val v = ctx.getSystemProperty(PROP_RO_BOOT_SELINUX)
                bootSelinuxAccessible = true
                v
            } catch (_: Throwable) {
                null
            }

            // ── Signal 3: ro.build.selinux ────────────────────────────────────
            var buildSelinuxAccessible = false
            val buildSelinux: String? = try {
                val v = ctx.getSystemProperty(PROP_RO_BUILD_SELINUX)
                buildSelinuxAccessible = true
                v
            } catch (_: Throwable) {
                null
            }

            // ── Signal 4: /sys/fs/selinux/policy existence ────────────────────
            var policyChecked = false
            val policyExists: Boolean = try {
                val present = ctx.fileExists(PATH_SELINUX_POLICY)
                policyChecked = true
                present
            } catch (_: Throwable) {
                false
            }

            // ── Score (max wins across rules) ────────────────────────────────
            val enforceIsPermissive = enforceRaw == ENFORCE_VALUE_PERMISSIVE
            val enforceIsEnforcing = enforceRaw == ENFORCE_VALUE_ENFORCING

            val bootLower = bootSelinux?.lowercase()
            val bootIsPermissiveOrDisabled =
                bootLower == BOOT_STATE_PERMISSIVE || bootLower == BOOT_STATE_DISABLED
            val bootIsEnforcing = bootLower == BOOT_STATE_ENFORCING

            val buildClaimsZero = buildSelinux == "0"
            val anyOtherSignalSaysEnforcing = enforceIsEnforcing || bootIsEnforcing

            val candidates = buildList {
                if (enforceIsPermissive) add(SCORE_PERMISSIVE)
                if (bootIsPermissiveOrDisabled) add(SCORE_PERMISSIVE)
                if (buildClaimsZero && anyOtherSignalSaysEnforcing) add(SCORE_INCONSISTENT)
            }

            // Rule 4: all property reads empty but accessor was available.
            // Only fires if no other rule fired.
            val propsAllEmptyButAccessible =
                bootSelinuxAccessible && buildSelinuxAccessible &&
                    bootSelinux.isNullOrEmpty() && buildSelinux.isNullOrEmpty() &&
                    enforceRaw.isNullOrEmpty()

            val score = when {
                candidates.isNotEmpty() -> candidates.max()
                propsAllEmptyButAccessible -> SCORE_EMPTY_PROPS
                else -> SCORE_CLEAN
            }

            // ── Confidence: how many signal sources answered cleanly ─────────
            val sourcesAccessed = listOf(
                enforceReadable,
                bootSelinuxAccessible && !bootSelinux.isNullOrEmpty(),
                buildSelinuxAccessible && !buildSelinux.isNullOrEmpty(),
                policyChecked,
            ).count { it }

            val confidence = when {
                sourcesAccessed >= 2 -> CONFIDENCE_FULL
                sourcesAccessed == 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            // ── Evidence rows ────────────────────────────────────────────────
            val evidence = listOf(
                Evidence(
                    key = PATH_SELINUX_ENFORCE,
                    value = enforceRaw ?: "absent",
                    expected = ENFORCE_VALUE_ENFORCING,
                ),
                Evidence(
                    key = PROP_RO_BOOT_SELINUX,
                    value = bootSelinux ?: "absent",
                    expected = BOOT_STATE_ENFORCING,
                ),
                Evidence(
                    key = PROP_RO_BUILD_SELINUX,
                    value = buildSelinux ?: "absent",
                    expected = ENFORCE_VALUE_ENFORCING,
                ),
                Evidence(
                    key = "selinux_kernel_support",
                    value = policyExists.toString(),
                    expected = "true",
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
                "SeLinuxProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

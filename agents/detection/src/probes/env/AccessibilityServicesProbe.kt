// inventory.yml rank 59, mitigation_layer L1
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe
import com.detectorlab.probes.runtime.InstalledAppsProbe

/**
 * Probe #59 — env.accessibility_services.
 *
 * Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (a
 * colon-separated list of accessibility-service components) and
 * looks for two signal classes:
 *
 *   • **Suspicious accessibility service (1.0)**: the enabled list
 *     contains a known automation/RAT/scraping accessibility service.
 *     Accessibility services have system-level UI-event access — when
 *     a hostile package abuses this API (e.g. UI-automation bots,
 *     screen-scrapers, MDM remote-control), it must register as an
 *     accessibility service. This is a canonical RAT/automation
 *     fingerprint per OWASP MASTG and freeRASP guidance.
 *   • **Many services enabled on phone-class (0.7)**: more than 3
 *     accessibility services enabled simultaneously on a phone-class
 *     device. Real users typically enable 0-2 (TalkBack + one custom
 *     service like Voice Access). >3 indicates either accessibility-
 *     based automation or a managed-device profile loading many
 *     services at once.
 *   • **Clean (0.0)**: empty list OR one-to-three real accessibility
 *     services (TalkBack, Voice Access, Sound Notifications, etc.).
 *
 * **Suspicious-marker substring list**: extends rank-10
 * [InstalledAppsProbe.GROUP_D_AUTOMATION] with accessibility-
 * service-specific markers. The rank-10 list scans installed
 * packages (`isPackageInstalled`); this probe scans the
 * accessibility-enabled list (different surface — a package can be
 * installed without enabling its accessibility service, and vice
 * versa for system-bundled services). Same hostile-tool family,
 * different surface. Reuse + extend, anchored by invariant test.
 *
 * Substring matching against the lowercased colon-joined list. Generic
 * substring tokens like `automation`, `screen.recorder`, and
 * `botaccessibility` catch unbranded variants without requiring
 * canonical-package-name enumeration.
 *
 * Reuses base [ProbeContext.querySettingSecure] — same Settings.Secure
 * accessor used by rank-19 / rank-11 / rank-58.
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the
 * many-services phone-class gate — same drift-safe delegate pattern
 * as rank-49/52/53/9.
 *
 * Scoring (max wins; first-match cascade in source order — strongest
 * first):
 *   1.00  PATTERN_SUSPICIOUS_SERVICE — any
 *         [SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS] substring matches
 *         the colon-joined list. **FP class acknowledgment**
 *         (per the rank-51 honest-framing pattern): a legitimate
 *         AirWatch / VMware Workspace ONE / Intune MDM-enrolled
 *         corporate device fires this rule at 1.0 because the
 *         `com.airwatch` substring (and equivalent MDM markers) are
 *         intentionally included — MDM remote-control accessibility
 *         services are functionally equivalent to RAT accessibility
 *         services from the kernel's perspective. The 1.0 score is
 *         calibrated for production cloud-phone evaluation where
 *         MDM enrollment is anomalous; consumer-side aggregator
 *         should ignore the rule when the target is a known-MDM
 *         device per the rank-50 / rank-51 / rank-52 consumer-
 *         gating-pattern family.
 *   0.70  PATTERN_MANY_SERVICES_ON_PHONE — > 3 services enabled
 *         AND model is phone-class. FP class: managed-device
 *         (MDM) profiles legitimately enable many services on
 *         corporate handsets — same FP class as the 1.0 rule above
 *         but here applies at a structural threshold rather than a
 *         marker-match. Consumer-side aggregator should combine
 *         with rank-50 services_processes for high-confidence
 *         reject.
 *   0.00  PATTERN_CLEAN — empty list or 1-3 services with none
 *         in the suspicious list
 *
 * Confidence:
 *   0.95  `querySettingSecure` returned non-null (regardless of value)
 *   0.50  accessor returned null OR threw
 *
 * Reference: shared/probes/inventory.yml rank 59 (mitigation_layer L1).
 */
class AccessibilityServicesProbe : Probe {
    override val id = "env.accessibility_services"
    override val rank = 59
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.TRACE
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 100L

    companion object {
        const val SETTING_ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val SERVICE_LIST_SEPARATOR = ":"

        /** Threshold above which "many services enabled" rule fires. */
        const val MANY_SERVICES_THRESHOLD = 3

        /**
         * Suspicious accessibility-service substring markers
         * (case-insensitive substring match against the lowercased
         * enabled-services list). Distinct from rank-10
         * [InstalledAppsProbe.GROUP_D_AUTOMATION] which scans
         * installed packages: same hostile-tool family, different
         * surface. **DO NOT consolidate** — a package can be installed
         * without enabling its accessibility service (rank-10 would
         * still fire) and a system-bundled accessibility service can
         * be enabled without showing up as user-installed (only this
         * probe would fire).
         *
         * Mix of canonical-package and generic-token markers:
         *   - Canonical packages: `com.android.uiautomator`,
         *     `org.autojs.autojs`, `com.balda.touchtask`,
         *     `com.airwatch`, `com.zdmotion` — overlap with rank-10
         *     where both surfaces fire
         *   - Generic tokens: `automation`, `screen.recorder`,
         *     `botaccessibility` — catch unbranded variants
         *
         * Cross-rank invariant test pins the substring overlap with
         * rank-10's automation list.
         *
         * Ground-truth pass (2026-05-20, cross-cutting #2 closure):
         *   - Removed `mods.autoui` (fabricated ID, Play 404).
         *   - Corrected `com.touchtask` → `com.balda.touchtask`
         *     (verified canonical TouchTask namespace).
         */
        val SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS: List<String> = listOf(
            "com.android.uiautomator",
            // `org.autojs.` covers both `org.autojs.autojs` and
            // `org.autojs.autoxjs.v6` (sibling automation products from
            // the same author) — broader than rank-10's two enumerated
            // packages by design.
            "org.autojs.",
            "com.balda.touchtask",
            "com.airwatch",
            "com.zdmotion",
            "automation",
            "screen.recorder",
            "botaccessibility",
        )

        const val PATTERN_SUSPICIOUS_SERVICE = "suspicious_service"
        const val PATTERN_MANY_SERVICES_ON_PHONE = "many_services_on_phone"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_SUSPICIOUS_SERVICE = 1.0
        const val SCORE_MANY_SERVICES_ON_PHONE = 0.7
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read Settings.Secure.enabled_accessibility_services and detect " +
                "suspicious automation/RAT accessibility services or many-" +
                "services-enabled on phone-class devices"

        /**
         * Split a colon-separated services list into individual
         * component names. Returns empty list for null/empty input.
         */
        internal fun splitServiceList(servicesList: String?): List<String> {
            if (servicesList.isNullOrEmpty()) return emptyList()
            return servicesList.split(SERVICE_LIST_SEPARATOR).filter { it.isNotEmpty() }
        }

        /**
         * True iff [servicesList] contains any of the
         * [SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS] (case-insensitive
         * substring match). Operates on the raw colon-joined string
         * so any substring within any component matches.
         */
        internal fun hasSuspiciousAccessibilityService(servicesList: String?): Boolean {
            if (servicesList.isNullOrEmpty()) return false
            val lower = servicesList.lowercase()
            return SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS.any { it in lower }
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val servicesList: String? = try {
                ctx.querySettingSecure(SETTING_ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val readable = servicesList != null
            val services = splitServiceList(servicesList)
            val serviceCount = services.size

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)
            val hasSuspicious = hasSuspiciousAccessibilityService(servicesList)
            val manyServicesOnPhone = serviceCount > MANY_SERVICES_THRESHOLD && phoneClass

            val (pattern, score) = when {
                hasSuspicious ->
                    PATTERN_SUSPICIOUS_SERVICE to SCORE_SUSPICIOUS_SERVICE
                manyServicesOnPhone ->
                    PATTERN_MANY_SERVICES_ON_PHONE to SCORE_MANY_SERVICES_ON_PHONE
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (readable) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            val evidence = listOf(
                Evidence(
                    key = "secure.$SETTING_ENABLED_ACCESSIBILITY_SERVICES",
                    value = servicesList ?: "<unavailable>",
                    expected = "<empty or system-only services>",
                ),
                Evidence(
                    key = "accessibility.service_count",
                    value = if (readable) serviceCount.toString() else "<unavailable>",
                    expected = "0-2 on consumer device",
                ),
                Evidence(
                    key = "accessibility.has_suspicious_service",
                    value = if (servicesList == null) "unknown" else hasSuspicious.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "accessibility.pattern",
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
                "AccessibilityServicesProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

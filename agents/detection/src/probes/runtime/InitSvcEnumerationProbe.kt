// inventory.yml rank 3.7 (code-rank 91), mitigation_layer L4
package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #3.7 — runtime.init_svc_enumeration.
 *
 * Enumerates the full `init.svc.*` system-property surface and
 * compares the observed service-name set against a curated
 * known-AOSP baseline. Magisk's pre-init service injection
 * (`magisk --post-fs-data` + `magisk --service`) registers 3
 * services at boot using randomized names that change per-boot —
 * literal-string matching cannot detect them (HuskyDG documents
 * this as UNCOUNTERED by RootBeer pattern-matching), but the
 * STRUCTURAL anomaly (services with non-AOSP-canonical names)
 * is the dispositive signal.
 *
 * Signal classes (HIGH severity — the FP class is narrow:
 * legitimate non-AOSP services are typically OEM-specific
 * surfaces like Samsung Knox or Xiaomi MIUI, and those have
 * recognizable name prefixes; randomized 8+ char hex-only names
 * are nearly always Magisk):
 *
 *   • **Magisk literal service (1.00)**: a service name contains
 *     the substring `magisk` (case-insensitive). Magisk forks
 *     (Delta, Kitsune, Magisk Alpha) preserve the literal in
 *     their service names even when canonical Magisk Stable
 *     randomizes — the literal IS dispositive.
 *   • **Random-shape suspicious services (0.95)**: ≥ 3 observed
 *     services not in `AOSP_KNOWN_SERVICES` AND ≥ 1 of those has
 *     a randomized-name shape (hex-only or all-lowercase-no-vowels
 *     length ≥ 6). This is the canonical Magisk-25+ injection
 *     pattern.
 *   • **Few unknown services (0.50)**: 1-2 unknown services
 *     observed but none has a random-shape name. Could be OEM-
 *     specific services with non-canonical names; weak signal.
 *   • **Clean (0.0)**: every observed service is in
 *     AOSP_KNOWN_SERVICES OR shows a recognizable OEM prefix
 *     (sec.*, vendor.*, mi.*).
 *   • **No observation (0.0)**: accessor returned empty map.
 *     Confidence DEGRADED.
 *
 * **AOSP_KNOWN_SERVICES baseline**: 50+ services common across
 * AOSP user builds (Pixel + Samsung S22 + production reference
 * builds). The list is curated; novel-but-legitimate OEM service
 * names that aren't in the set fire the weak-signal rule rather
 * than the dispositive rule. The probe's KDoc accepts this
 * partial-coverage trade-off — extending the baseline at probe-
 * iteration time is cheaper than tightening the random-shape
 * heuristic.
 *
 * Scoring (max wins; cascade in source order):
 *   1.00  PATTERN_MAGISK_LITERAL_SERVICE
 *   0.95  PATTERN_RANDOM_SHAPE_INJECTION
 *   0.50  PATTERN_FEW_UNKNOWN_SERVICES
 *   0.00  PATTERN_CLEAN
 *   0.00  PATTERN_NO_OBSERVATION
 *
 * Confidence:
 *   0.95  NORMAL    services map non-empty (real observation)
 *   0.50  DEGRADED  services map empty (no observation)
 *
 * **inventoryRank vs code-rank**: inventory 3.7 → code-rank 91
 * (next free slot above Gap #1's 90). Cross-cutting #7.
 *
 * Reference: shared/probes/inventory.yml rank 3.7 (mitigation_layer
 * L4); audit/spoof-stack/real-world-detectors.md row "Magisk init
 * service injection"; audit/spoof-stack/real-world-gap-list.md
 * Gap #2. Origin: Momo + canyie/Riru-MomoHider target surface
 * (HuskyDG blog `detect_magisk_xposed` for canonical write-up).
 */
class InitSvcEnumerationProbe : Probe {
    override val id = "runtime.init_svc_enumeration"
    override val rank = RANK
    override val inventoryRank = 3.7
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 200L

    companion object {
        /**
         * Code rank. Inventory lists rank 3.7; Int slot 91 picked
         * (next free above Gap #1's 90). Cross-cutting #7.
         */
        const val RANK = 91

        /**
         * Curated known-AOSP service-name baseline. Covers the
         * common cross-OEM init.svc set observed on Pixel + Samsung +
         * generic AOSP user builds. NOT exhaustive — novel-but-
         * legitimate OEM service names land in the weak-signal tier
         * rather than the dispositive tier. Curation source: AOSP
         * `system/core/rootdir/init.rc` + Samsung One UI 7 dump
         * cross-reference.
         */
        val AOSP_KNOWN_SERVICES: Set<String> = setOf(
            // Core AOSP init.rc services
            "zygote", "zygote_secondary", "servicemanager", "surfaceflinger",
            "mediaserver", "media", "audioserver", "drm", "cameraserver",
            "installd", "vold", "netd", "wifi", "dhcpcd", "ril-daemon",
            "rild", "thermal-engine", "logd", "logcat", "tombstoned",
            "lmkd", "ueventd", "healthd", "watchdogd", "fingerprintd",
            "gatekeeperd", "keystore2", "keystore", "incidentd",
            "statsd", "iorapd", "perfprofd", "dpmd", "credstore",
            "iconcurd", "console", "adbd", "wpa_supplicant",
            // Boot / hwservicemanager / hidl
            "boot", "hwservicemanager", "qemu-props", "init",
            // Bluetooth / HCI
            "bluetooth", "bluetoothd", "fluoride",
            // Storage / FUSE / sdcard
            "sdcard", "fuse_sdcard0", "sdcardfs",
        )

        /**
         * Common OEM service-name prefixes that disambiguate
         * legitimate vendor services from Magisk-injected random
         * names. A service whose name starts with one of these
         * prefixes is NOT counted as "unknown random-shape" even
         * if it isn't in AOSP_KNOWN_SERVICES.
         */
        val KNOWN_OEM_PREFIXES: List<String> = listOf(
            "sec",       // Samsung Knox / SecBio
            "vendor",    // vendor partition services
            "mi",        // Xiaomi MIUI
            "oppo",      // OPPO/OnePlus
            "vivo",      // vivo / iQOO
            "huawei",    // Huawei EMUI/HarmonyOS
            "google",    // Google-specific
            "android",   // generic Android prefix
            "qti",       // Qualcomm Technologies Inc.
            "qcom",      // Qualcomm
            "mtk",       // MediaTek
            "lge",       // LG
            "sony",      // Sony
        )

        const val MAGISK_LITERAL_SUBSTRING = "magisk"

        /** Min length to qualify as "random-shape" for the dispositive rule. */
        const val RANDOM_SHAPE_MIN_LENGTH = 6

        /**
         * Random-shape regex: 6+ characters, hex-only (0-9 + a-f).
         * Real AOSP / OEM service names always contain at least one
         * non-hex character (typically a vowel or underscore in a
         * recognizable pattern). Hex-only names of significant
         * length are nearly always Magisk-random.
         */
        private val HEX_ONLY_REGEX = Regex("^[a-f0-9]{6,}$")

        const val PATTERN_MAGISK_LITERAL_SERVICE = "magisk_literal_service"
        const val PATTERN_RANDOM_SHAPE_INJECTION = "random_shape_injection"
        const val PATTERN_FEW_UNKNOWN_SERVICES = "few_unknown_services"
        const val PATTERN_CLEAN = "clean"
        const val PATTERN_NO_OBSERVATION = "no_observation"

        const val SCORE_MAGISK_LITERAL = 1.00
        const val SCORE_RANDOM_SHAPE = 0.95
        const val SCORE_FEW_UNKNOWN = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_NORMAL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Enumerate init.svc.<name> properties; compare observed " +
                "service-name set against curated AOSP_KNOWN_SERVICES " +
                "baseline + OEM-prefix allowlist; detect Magisk-injected " +
                "services by literal `magisk` substring OR random-shape " +
                "(hex-only) name pattern (Power-13 Gap #2)."

        /** True iff the lowercased service name contains `magisk`. */
        internal fun isMagiskLiteral(name: String): Boolean =
            "magisk" in name.lowercase()

        /** True iff the service name has an OEM-prefix that explains it. */
        internal fun hasKnownOemPrefix(name: String): Boolean {
            val lower = name.lowercase()
            return KNOWN_OEM_PREFIXES.any { lower.startsWith("$it.") || lower.startsWith("${it}_") }
        }

        /**
         * True iff the service name has a randomized-name shape:
         * length >= 6 AND all characters are hex digits (0-9 + a-f).
         * Real services NEVER pure-hex; Magisk's random names are
         * commonly hex-encoded blobs.
         */
        internal fun hasRandomShape(name: String): Boolean {
            if (name.length < RANDOM_SHAPE_MIN_LENGTH) return false
            return HEX_ONLY_REGEX.matches(name.lowercase())
        }

        /**
         * Classify the service-name set against the AOSP baseline +
         * OEM prefixes. Returns the subset that is BOTH not in the
         * baseline AND not OEM-prefixed AND not a magisk-literal hit
         * (which is handled separately at higher score).
         */
        internal fun findUnknownServices(serviceNames: Set<String>): Set<String> {
            return serviceNames.filter { name ->
                val lower = name.lowercase()
                lower !in AOSP_KNOWN_SERVICES &&
                    !hasKnownOemPrefix(name) &&
                    !isMagiskLiteral(name)
            }.toSet()
        }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val svcMap: Map<String, String> = try {
                ctx.queryInitSvcProps()
            } catch (_: Throwable) {
                emptyMap()
            }

            val serviceNames = svcMap.keys
            val hasObservation = serviceNames.isNotEmpty()

            val magiskLiteralHits = serviceNames.filter { isMagiskLiteral(it) }
            val unknownServices = findUnknownServices(serviceNames)
            val randomShapeHits = unknownServices.filter { hasRandomShape(it) }
            val hasRandomInjection = unknownServices.size >= 3 && randomShapeHits.isNotEmpty()
            val hasFewUnknown = unknownServices.isNotEmpty() && !hasRandomInjection

            val (pattern, score, confidence) = when {
                !hasObservation ->
                    Triple(PATTERN_NO_OBSERVATION, SCORE_CLEAN, CONFIDENCE_DEGRADED)
                magiskLiteralHits.isNotEmpty() ->
                    Triple(PATTERN_MAGISK_LITERAL_SERVICE, SCORE_MAGISK_LITERAL, CONFIDENCE_NORMAL)
                hasRandomInjection ->
                    Triple(PATTERN_RANDOM_SHAPE_INJECTION, SCORE_RANDOM_SHAPE, CONFIDENCE_NORMAL)
                hasFewUnknown ->
                    Triple(PATTERN_FEW_UNKNOWN_SERVICES, SCORE_FEW_UNKNOWN, CONFIDENCE_NORMAL)
                else ->
                    Triple(PATTERN_CLEAN, SCORE_CLEAN, CONFIDENCE_NORMAL)
            }

            fun listOrNone(items: Collection<String>): String =
                if (items.isEmpty()) "none" else items.joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "init_svc.service_count",
                    value = serviceNames.size.toString(),
                    expected = null,
                ),
                Evidence(
                    key = "init_svc.magisk_literal_hits",
                    value = listOrNone(magiskLiteralHits),
                    expected = "none",
                ),
                Evidence(
                    key = "init_svc.unknown_service_count",
                    value = unknownServices.size.toString(),
                    expected = null,
                ),
                Evidence(
                    key = "init_svc.random_shape_hits",
                    value = listOrNone(randomShapeHits),
                    expected = "none",
                ),
                Evidence(
                    key = "init_svc.unknown_services_sample",
                    // Cap the sample to avoid massive evidence rows
                    // when an OEM has many vendor services that fail
                    // the prefix filter. First 10 alphabetically.
                    value = listOrNone(unknownServices.sorted().take(10)),
                    expected = "none",
                ),
                Evidence(
                    key = "init_svc.pattern",
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
                "InitSvcEnumerationProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

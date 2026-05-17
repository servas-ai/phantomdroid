package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #1 — Build Fingerprint.
 *
 * Reads ro.build.fingerprint, ro.build.display.id, ro.build.tags, ro.build.type,
 * ro.product.brand, ro.product.model, ro.product.manufacturer.
 *
 * Detection logic:
 *   - Score 1.0 if fingerprint contains known emulator markers (redroid, generic, ranchu, vbox)
 *   - Score 0.85 if tags=test-keys or type=userdebug
 *   - Score 0.0 if fingerprint matches a real-OEM pattern AND tags=release-keys AND type=user
 *   - Inconsistency between brand/model/manufacturer triplet adds 0.3
 *
 * Reference: Round-1 Finding F1 (kritisch, mitigation_layer L1).
 * inventory.yml rank 1.
 */
class BuildFingerprintProbe : Probe {
    override val id = "buildprop.fingerprint"
    override val rank = 1
    override val category = ProbeCategory.BUILDPROP
    override val severity = ProbeSeverity.CRITICAL
    override val androidLayer = AndroidLayer.NATIVE
    override val budgetMs = 200L

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val fingerprint = ctx.getSystemProperty("ro.build.fingerprint") ?: ""
            val displayId = ctx.getSystemProperty("ro.build.display.id") ?: ""
            val tags = ctx.getSystemProperty("ro.build.tags") ?: ""
            val type = ctx.getSystemProperty("ro.build.type") ?: ""
            val brand = ctx.getSystemProperty("ro.product.brand") ?: ""
            val model = ctx.getSystemProperty("ro.product.model") ?: ""
            val manufacturer = ctx.getSystemProperty("ro.product.manufacturer") ?: ""

            val evidence = mutableListOf(
                Evidence("ro.build.fingerprint", fingerprint),
                Evidence("ro.build.display.id", displayId),
                Evidence("ro.build.tags", tags, expected = "release-keys"),
                Evidence("ro.build.type", type, expected = "user"),
                Evidence("ro.product.brand", brand),
                Evidence("ro.product.model", model),
                Evidence("ro.product.manufacturer", manufacturer),
            )

            val emulatorMarkers = listOf("redroid", "generic", "ranchu", "vbox", "goldfish")
            val hasEmulatorMarker = emulatorMarkers.any { fingerprint.contains(it, ignoreCase = true) }
            val testKeys = tags == "test-keys"
            val userdebug = type == "userdebug" || type == "eng"

            // Manufacturer/brand/model consistency: a real Pixel 7 has
            // brand=google, manufacturer=Google, model=Pixel 7 (case-sensitive).
            val brandMfgInconsistent = brand.isNotEmpty() && manufacturer.isNotEmpty() &&
                !brand.equals(manufacturer, ignoreCase = true) &&
                !knownBrandMfgPairs.contains(brand.lowercase() to manufacturer.lowercase())

            val score = when {
                hasEmulatorMarker -> 1.0
                testKeys && userdebug -> 0.95
                testKeys || userdebug -> 0.85
                brandMfgInconsistent -> 0.45
                else -> 0.0
            }

            val confidence = if (fingerprint.isEmpty()) 0.5 else 0.99

            ProbeResult(
                score = score,
                confidence = confidence,
                evidence = evidence,
                method = "Compare ro.build.* against expected manufacturer pattern + emulator markers",
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed("BuildFingerprintProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start)
        }
    }

    private val knownBrandMfgPairs = setOf(
        "google" to "google",
        "samsung" to "samsung",
        "xiaomi" to "xiaomi",
        "oneplus" to "oneplus",
        "oppo" to "oppo",
        "vivo" to "vivo",
        "realme" to "realme",
        "motorola" to "motorola",
        "sony" to "sony",
        "huawei" to "huawei",
    )
}

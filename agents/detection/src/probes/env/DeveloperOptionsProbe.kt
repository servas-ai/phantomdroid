// inventory.yml rank 19, mitigation_layer L4
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe #19 — env.developer_options.
 *
 * Detects whether Developer Options / USB-debugging-class toggles have been
 * unlocked on the device. A normal end-user device has every toggle in this
 * probe at its production default; any one being flipped is a moderate signal
 * (someone has been in `Settings → Developer options`), and the combination
 * `development_settings_enabled=1 + adb_enabled=1` is the canonical
 * "this is a development device" signature.
 *
 * Signals (queried via `ctx.querySettingSecure(key)`):
 *   • `development_settings_enabled` — `1` if Developer Options is unlocked.
 *   • `adb_enabled`                  — `1` if USB debugging is on.
 *   • `adb_wifi_enabled`             — Android 11+ wireless ADB. `1` is explicit lab/dev.
 *   • `package_verifier_enable`      — Google Play Protect package verifier;
 *                                       `1` on real devices. `0` is unusual.
 *   • `ro.debuggable` (system prop)  — `0` on production user builds.
 *
 * Namespace caveat: on modern Android, `adb_enabled` and
 * `development_settings_enabled` live in `Settings.Global`, not
 * `Settings.Secure`. `ProbeContext` exposes only `querySettingSecure`; the
 * production wrapper is expected to fall back across namespaces so the same
 * key string surfaces the right value. If the wrapper does not, this probe
 * gracefully degrades — every accessor that returns null is treated as "no
 * signal observed", confidence drops by source count, and score remains
 * conservative. Adding a `querySettingGlobal` accessor would be a
 * core-contract change tracked outside this probe.
 *
 * Scoring (max wins across rules):
 *   1.00  `development_settings_enabled=="1"` AND `adb_enabled=="1"`
 *   0.85  `development_settings_enabled=="1"` only
 *   0.85  `adb_enabled=="1"` OR `adb_wifi_enabled=="1"` only
 *   0.70  `package_verifier_enable=="0"`           (Play Protect off — lab signal)
 *   0.50  `ro.debuggable=="1"`                    (overlap with rank 13; capped)
 *   0.00  All clean
 *
 * Confidence: 0.95 if ≥ 3 Settings.Secure keys returned non-null; 0.60 if 1-2;
 * 0.30 if 0. `ro.debuggable` is NOT counted toward the Settings-source tally
 * because it's a separate property-store accessor — keeps the confidence
 * heuristic focused on whether the Settings.* accessor itself is working.
 *
 * Reference: shared/probes/inventory.yml rank 19 (mitigation_layer L4).
 */
class DeveloperOptionsProbe : Probe {
    override val id = "env.developer_options"
    override val rank = 19
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.HIGH
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 200L

    companion object {
        const val SETTING_DEV_SETTINGS_ENABLED = "development_settings_enabled"
        const val SETTING_ADB_ENABLED = "adb_enabled"
        const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val SETTING_PACKAGE_VERIFIER_ENABLE = "package_verifier_enable"

        const val PROP_RO_DEBUGGABLE = "ro.debuggable"

        const val SCORE_DEV_AND_ADB = 1.0
        const val SCORE_DEV_OR_ADB = 0.85
        const val SCORE_VERIFIER_OFF = 0.70
        const val SCORE_DEBUGGABLE_ONLY = 0.50
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_PARTIAL = 0.60
        const val CONFIDENCE_DEGRADED = 0.30

        const val METHOD =
            "Read Settings.Global dev/adb/verifier toggles + ro.debuggable, " +
                "score escalation pattern from any-flag → both-flags-set"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            fun readSecure(key: String): String? = try {
                ctx.querySettingSecure(key)
            } catch (_: Throwable) {
                null
            }

            val devSettings = readSecure(SETTING_DEV_SETTINGS_ENABLED)
            val adb = readSecure(SETTING_ADB_ENABLED)
            val adbWifi = readSecure(SETTING_ADB_WIFI_ENABLED)
            val verifier = readSecure(SETTING_PACKAGE_VERIFIER_ENABLE)

            val roDebuggable = try {
                ctx.getSystemProperty(PROP_RO_DEBUGGABLE)
            } catch (_: Throwable) {
                null
            }

            val devOn = devSettings == "1"
            val adbOn = adb == "1"
            val adbWifiOn = adbWifi == "1"
            val verifierOff = verifier == "0"
            val debuggableOn = roDebuggable == "1"

            val candidates = buildList {
                if (devOn && adbOn) add(SCORE_DEV_AND_ADB)
                if (devOn && !adbOn) add(SCORE_DEV_OR_ADB)
                if (adbOn || adbWifiOn) add(SCORE_DEV_OR_ADB)
                if (verifierOff) add(SCORE_VERIFIER_OFF)
                if (debuggableOn) add(SCORE_DEBUGGABLE_ONLY)
            }
            val score = candidates.maxOrNull() ?: SCORE_CLEAN

            // Confidence: count Settings.Secure keys that returned non-null.
            // ro.debuggable is a separate accessor and not counted — keeps the
            // tier focused on whether the Settings.* surface is reachable.
            val settingsSourcesAccessed = listOf(devSettings, adb, adbWifi, verifier)
                .count { it != null }
            val confidence = when {
                settingsSourcesAccessed >= 3 -> CONFIDENCE_FULL
                settingsSourcesAccessed >= 1 -> CONFIDENCE_PARTIAL
                else -> CONFIDENCE_DEGRADED
            }

            val evidence = listOf(
                Evidence(
                    key = "global.$SETTING_DEV_SETTINGS_ENABLED",
                    value = devSettings ?: "absent",
                    expected = "0",
                ),
                Evidence(
                    key = "global.$SETTING_ADB_ENABLED",
                    value = adb ?: "absent",
                    expected = "0",
                ),
                Evidence(
                    key = "global.$SETTING_ADB_WIFI_ENABLED",
                    value = adbWifi ?: "absent",
                    expected = "0",
                ),
                Evidence(
                    key = "global.$SETTING_PACKAGE_VERIFIER_ENABLE",
                    value = verifier ?: "absent",
                    expected = "1",
                ),
                Evidence(
                    key = PROP_RO_DEBUGGABLE,
                    value = roDebuggable ?: "absent",
                    expected = "0",
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
                "DeveloperOptionsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

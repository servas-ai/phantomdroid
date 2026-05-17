package com.detectorlab.probes.runtime

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity

/**
 * Probe — runtime.automation_tools (A17 N9, freeRASP T10)
 *
 * Detects active automation tooling by checking three independent signals:
 *   1. Appium accessibility service enabled
 *      (`io.appium.uiautomator2.server` in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
 *   2. UIAutomator package installed
 *      (`com.github.uiautomator` or `androidx.test.uiautomator`)
 *   3. ADB enabled (`adb_enabled == 1`) paired with an active ADB shell
 *      connection on port 5555 (hex `15B3`, state `01`=ESTABLISHED in
 *      `/proc/net/tcp`).
 *
 * Acceptance criteria (CLO-7):
 *   - Detects Appium via ENABLED_ACCESSIBILITY_SERVICES settings key.
 *   - Detects UIAutomator instrumentation host via PackageManager.
 *   - Detects `adb_enabled=1` paired with port-5555 ESTABLISHED entry in
 *     `/proc/net/tcp` (the dual requirement avoids false-positives on devices
 *     that ship with adb_enabled but no active connection).
 *   - Matchers exposed as top-level `internal` functions so the droidrun
 *     bootstrap harness (Issue 09) can reuse them without instantiating the
 *     probe class.
 *
 * Score table:
 *   Appium service active               → +0.90 (strongest signal; only seen in test rigs)
 *   UIAutomator package installed       → +0.40 (moderate; may exist on dev devices)
 *   adb_enabled AND port-5555 active    → +0.70 (strong combined signal)
 *   adb_enabled only (no port-5555)     → +0.15 (weak; many dev phones have adb_enabled)
 *   Final score clamped to [0.0, 1.0].
 *
 * Severity: MEDIUM (inventory rank 51.5, mitigation_layer L4).
 */
class AutomationToolsProbe : Probe {
    override val id = "runtime.automation_tools"
    override val rank = RANK
    override val category = ProbeCategory.RUNTIME
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 1000L

    companion object {
        /** A17 N9. META-22 reserves 61..71 for A17 N1..N11; N9 maps to 69. */
        const val RANK = 69

        const val PKG_APPIUM_SERVER = "io.appium.uiautomator2.server"
        const val PKG_UIAUTOMATOR   = "com.github.uiautomator"
        const val PKG_UIAUTOMATOR_TEST = "com.github.uiautomator.test"
        const val SETTING_ADB_ENABLED = "adb_enabled"
        const val PROC_NET_TCP = "/proc/net/tcp"

        // Port 5555 in big-endian hex as it appears in /proc/net/tcp local_address column.
        // /proc/net/tcp uses little-endian 4-byte IP + big-endian port, so port 5555 = 0x15B3.
        const val PORT_5555_HEX = "15B3"
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val pm = ctx.queryPackageManager()

            val appiumActive = isAppiumAccessibilityServiceEnabled(ctx)
            val uiAutomatorInstalled = isUiAutomatorInstalled(pm)
            val adbEnabled = isAdbEnabled(ctx)
            val port5555Active = isPort5555Active(ctx)
            val adbShellActive = adbEnabled && port5555Active

            val rawScore = sequenceOf(
                if (appiumActive) 0.90 else 0.0,
                if (uiAutomatorInstalled) 0.40 else 0.0,
                if (adbShellActive) 0.70 else if (adbEnabled) 0.15 else 0.0,
            ).sum().coerceAtMost(1.0)

            val anyDetected = appiumActive || uiAutomatorInstalled || adbShellActive

            val confidence = when {
                appiumActive -> 0.95
                adbShellActive -> 0.90
                uiAutomatorInstalled -> 0.80
                adbEnabled && !port5555Active -> 0.40   // weak: enabled but no active connection
                else -> 0.95                             // clean, strong
            }

            val evidence = buildList {
                add(Evidence("appium.accessibility_service_enabled", appiumActive, expected = false))
                add(Evidence("uiautomator.package_installed", uiAutomatorInstalled, expected = false))
                add(Evidence("settings.adb_enabled", adbEnabled, expected = false))
                add(Evidence("proc_net_tcp.port_5555_established", port5555Active, expected = false))
                add(Evidence("adb_shell_active", adbShellActive, expected = false))
            }

            ProbeResult(
                score = rawScore,
                confidence = confidence,
                evidence = evidence,
                method = buildString {
                    append("accessibility_settings+package_manager+proc_net_tcp")
                    if (anyDetected) append(" [DETECTED]")
                },
                runtimeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ProbeResult.failed(
                "AutomationToolsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

// ── Reusable matchers (droidrun bootstrap harness, Issue 09) ──────────────────

/**
 * Returns true when `io.appium.uiautomator2.server` appears in the
 * `enabled_accessibility_services` secure setting — the canonical signal
 * that Appium's UIAutomator2 driver is active on the device.
 */
internal fun isAppiumAccessibilityServiceEnabled(ctx: ProbeContext): Boolean {
    val setting = ctx.querySettingSecure("enabled_accessibility_services") ?: return false
    return AutomationToolsProbe.PKG_APPIUM_SERVER in setting
}

/**
 * Returns true when the UIAutomator stub package (`com.github.uiautomator`)
 * or its test companion is installed — the presence of either is a reliable
 * indicator that UIAutomator-based automation is configured on the device.
 */
internal fun isUiAutomatorInstalled(pm: com.detectorlab.core.PackageManagerView): Boolean =
    pm.isPackageInstalled(AutomationToolsProbe.PKG_UIAUTOMATOR) ||
        pm.isPackageInstalled(AutomationToolsProbe.PKG_UIAUTOMATOR_TEST)

/**
 * Returns true when `Settings.Secure.adb_enabled == "1"`.
 */
internal fun isAdbEnabled(ctx: ProbeContext): Boolean =
    ctx.querySettingSecure(AutomationToolsProbe.SETTING_ADB_ENABLED) == "1"

/**
 * Returns true when an ESTABLISHED TCP connection on local port 5555 exists in
 * `/proc/net/tcp`. The kernel writes port in big-endian hex; 5555 == 0x15B3.
 *
 * `/proc/net/tcp` format (space-separated): sl local_address rem_address st ...
 * `local_address` is `XXXXXXXX:PPPP` where PPPP is the port in upper-case hex.
 * State `01` == ESTABLISHED.
 */
internal fun isPort5555Active(ctx: ProbeContext): Boolean {
    val content = ctx.readFile(AutomationToolsProbe.PROC_NET_TCP) ?: return false
    return content.lines().any { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4) return@any false
        val localAddr = parts[1]   // XXXXXXXX:PPPP
        val state = parts[3]       // e.g. "01"
        val port = localAddr.substringAfterLast(':').uppercase()
        port == AutomationToolsProbe.PORT_5555_HEX && state == "01"
    }
}

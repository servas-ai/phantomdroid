package com.detectorlab.probes.ui

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for CanvasFingerprintProbe (ui.canvas_fingerprint, inventory rank 55).
 */
class CanvasFingerprintProbeTest {

    private val probe = CanvasFingerprintProbe()

    /**
     * Build a fake `ProbeContext` over an installed-packages set and the two
     * relevant system properties. The PackageManagerView's
     * `isPackageInstalled` answers from the same set so the probe sees a
     * consistent view across both the listInstalledPackages and the
     * isPackageInstalled paths.
     */
    private fun fakeCtx(
        installedPackages: Set<String> = setOf(
            "com.google.android.webview",
            "com.android.systemui",
            "com.android.settings",
        ),
        webViewDisabledProp: String? = null,
        safeBrowsingDisabledProp: String? = null,
        pmThrows: Boolean = false,
        getPropThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (getPropThrows) throw RuntimeException("simulated getprop EACCES")
            return when (key) {
                CanvasFingerprintProbe.PROP_WEBVIEW_DISABLED -> webViewDisabledProp
                CanvasFingerprintProbe.PROP_DISABLE_SAFE_BROWSING -> safeBrowsingDisabledProp
                else -> null
            }
        }
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView {
            if (pmThrows) throw RuntimeException("simulated PM failure")
            return object : PackageManagerView {
                override fun isPackageInstalled(packageName: String) =
                    packageName in installedPackages
                override fun listInstalledPackages() = installedPackages.toList()
                override fun listPackagesWithPermission(permission: String) =
                    emptyList<String>()
            }
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `clean Pixel — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(CanvasFingerprintProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_70 (declarative floor)`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.70, result.confidence)
    }

    @Test
    fun `clean — chromium_provider_present evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "webview.chromium_provider_present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `clean — non_standard_providers evidence is none`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "webview.non_standard_providers" }
        assertEquals("none", ev?.value)
    }

    // ── Webview provider missing (0.85) ──────────────────────────────────────

    @Test
    fun `WebView provider missing — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "android",
                    "com.android.systemui",
                    "com.android.settings",
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `WebView provider missing — pattern is webview_provider_missing`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "android",
                    "com.android.systemui",
                ),
            )
        )
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_WEBVIEW_PROVIDER_MISSING,
            ev?.value,
        )
    }

    @Test
    fun `WebView provider missing — chromium_provider_present evidence is false`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf("android", "com.android.systemui"),
            )
        )
        val ev = result.evidence.find { it.key == "webview.chromium_provider_present" }
        assertEquals("false", ev?.value)
    }

    // ── Non-standard WebView installed (0.85) ────────────────────────────────

    @Test
    fun `Brave WebView installed — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview", // also present
                    "com.brave.browser.webview",
                    "com.android.systemui",
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Kiwi WebView installed — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "com.kiwibrowser.browser.webview",
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Bromite WebView installed — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "org.bromite.webview",
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `DuckDuckGo WebView installed — score is 0_85`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "com.duckduckgo.mobile.android.webview",
                ),
            )
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-standard WebView — pattern is non_standard_webview_installed`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "com.brave.browser.webview",
                ),
            )
        )
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_NON_STANDARD_WEBVIEW_INSTALLED,
            ev?.value,
        )
    }

    @Test
    fun `non-standard WebView — evidence row lists the providers`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "com.brave.browser.webview",
                ),
            )
        )
        val ev = result.evidence.find { it.key == "webview.non_standard_providers" }
        assertEquals("com.brave.browser.webview", ev?.value)
    }

    // ── Webview disabled prop (0.70) ─────────────────────────────────────────

    @Test
    fun `webview disabled prop — score is 0_70`() = runBlocking {
        val result = probe.run(
            fakeCtx(webViewDisabledProp = "1"),
        )
        assertEquals(0.70, result.score)
    }

    @Test
    fun `webview disabled prop — pattern is webview_disabled_prop`() = runBlocking {
        val result = probe.run(
            fakeCtx(webViewDisabledProp = "1"),
        )
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_WEBVIEW_DISABLED_PROP,
            ev?.value,
        )
    }

    @Test
    fun `webview disabled prop set to 0 — no trigger`() = runBlocking {
        // "0" is the canonical real-device value; only "1" should fire.
        val result = probe.run(
            fakeCtx(webViewDisabledProp = "0"),
        )
        assertEquals(0.0, result.score)
    }

    // ── Safe browsing disabled prop (0.50) ───────────────────────────────────

    @Test
    fun `safe browsing disabled prop — score is 0_50`() = runBlocking {
        val result = probe.run(
            fakeCtx(safeBrowsingDisabledProp = "1"),
        )
        assertEquals(0.50, result.score)
    }

    @Test
    fun `safe browsing disabled prop — pattern is safe_browsing_disabled_prop`() = runBlocking {
        val result = probe.run(
            fakeCtx(safeBrowsingDisabledProp = "1"),
        )
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_SAFE_BROWSING_DISABLED_PROP,
            ev?.value,
        )
    }

    // ── Cascade ordering (max-wins, strongest first) ─────────────────────────

    @Test
    fun `cascade — provider missing wins over disabled prop`() = runBlocking {
        // Provider missing (0.85) + disabled prop (0.70) → provider missing wins.
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf("android", "com.android.systemui"),
                webViewDisabledProp = "1",
            )
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_WEBVIEW_PROVIDER_MISSING,
            ev?.value,
        )
    }

    @Test
    fun `cascade — non-standard provider wins over disabled prop`() = runBlocking {
        // Non-standard provider (0.85) + disabled prop (0.70) → non-standard wins.
        val result = probe.run(
            fakeCtx(
                installedPackages = setOf(
                    "com.google.android.webview",
                    "com.brave.browser.webview",
                ),
                webViewDisabledProp = "1",
            )
        )
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "canvas_fingerprint.pattern" }
        assertEquals(
            CanvasFingerprintProbe.PATTERN_NON_STANDARD_WEBVIEW_INSTALLED,
            ev?.value,
        )
    }

    @Test
    fun `cascade — disabled prop wins over safe browsing`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                webViewDisabledProp = "1",
                safeBrowsingDisabledProp = "1",
            )
        )
        assertEquals(0.70, result.score)
    }

    // ── Visibility filter degraded confidence ────────────────────────────────

    @Test
    fun `empty installed list + no props — confidence is 0_50 (degraded)`() = runBlocking {
        // Empty package list and no property tells: visibility filter likely
        // ate our signal source. Should land at score 0.0 with degraded
        // confidence, NOT a false positive.
        val result = probe.run(
            fakeCtx(installedPackages = emptySet()),
        )
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `empty installed list + prop fires — confidence stays at declarative`() = runBlocking {
        // When property signals still fire even though PM is blind, the
        // probe has a real signal source and should report the declarative
        // confidence floor (0.70), not the degraded one.
        val result = probe.run(
            fakeCtx(
                installedPackages = emptySet(),
                webViewDisabledProp = "1",
            )
        )
        assertEquals(0.70, result.score)
        assertEquals(0.70, result.confidence)
    }

    @Test
    fun `empty installed list — installed_list_empty evidence is true`() = runBlocking {
        val result = probe.run(
            fakeCtx(installedPackages = emptySet()),
        )
        val ev = result.evidence.find { it.key == "webview.installed_list_empty" }
        assertEquals("true", ev?.value)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `queryPackageManager throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(pmThrows = true))
        assertFalse(result.failed)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = probe.run(fakeCtx(getPropThrows = true))
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `hasNonStandardWebViewProvider detects Brave`() {
        assertTrue(
            CanvasFingerprintProbe.hasNonStandardWebViewProvider(
                listOf("com.brave.browser.webview", "com.example.foo"),
            )
        )
    }

    @Test
    fun `hasNonStandardWebViewProvider false for clean Pixel`() {
        assertFalse(
            CanvasFingerprintProbe.hasNonStandardWebViewProvider(
                listOf("com.google.android.webview", "com.google.android.gms"),
            )
        )
    }

    @Test
    fun `listNonStandardProviders returns only the hits`() {
        val hits = CanvasFingerprintProbe.listNonStandardProviders(
            listOf(
                "com.google.android.webview",
                "com.brave.browser.webview",
                "com.duckduckgo.mobile.android.webview",
                "com.example.foo",
            ),
        )
        assertEquals(
            listOf(
                "com.brave.browser.webview",
                "com.duckduckgo.mobile.android.webview",
            ),
            hits,
        )
    }

    // ── Drift-alarm ──────────────────────────────────────────────────────────

    @Test
    fun `NON_STANDARD_WEBVIEW_PROVIDERS list pinned`() {
        val list = CanvasFingerprintProbe.NON_STANDARD_WEBVIEW_PROVIDERS
        assertTrue("com.brave.browser.webview" in list)
        assertTrue("com.kiwibrowser.browser.webview" in list)
        assertTrue("org.bromite.webview" in list)
        assertTrue("com.duckduckgo.mobile.android.webview" in list)
        assertEquals(4, list.size)
    }

    @Test
    fun `Chromium WebView package constant pinned`() {
        assertEquals(
            "com.google.android.webview",
            CanvasFingerprintProbe.PKG_CHROMIUM_WEBVIEW,
        )
    }

    @Test
    fun `property names pinned`() {
        assertEquals(
            "ro.com.google.android.webview.disabled",
            CanvasFingerprintProbe.PROP_WEBVIEW_DISABLED,
        )
        assertEquals(
            "persist.webview.disable_safe_browsing",
            CanvasFingerprintProbe.PROP_DISABLE_SAFE_BROWSING,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "webview.chromium_provider_present",
                "webview.non_standard_providers",
                "webview.disabled_prop",
                "webview.safe_browsing_disabled_prop",
                "webview.installed_list_empty",
                "canvas_fingerprint.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `disabled_prop evidence shows unset when null`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "webview.disabled_prop" }
        assertEquals("<unset>", ev?.value)
    }

    @Test
    fun `disabled_prop evidence reflects accessor`() = runBlocking {
        val result = probe.run(fakeCtx(webViewDisabledProp = "1"))
        val ev = result.evidence.find { it.key == "webview.disabled_prop" }
        assertEquals("1", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(CanvasFingerprintProbe.METHOD, result.method)
    }

    @Test
    fun `method string mentions WebView`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(result.method.contains("WebView"))
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is ui_canvas_fingerprint`() {
        assertEquals("ui.canvas_fingerprint", probe.id)
    }

    @Test
    fun `probe rank is 55`() {
        assertEquals(55, probe.rank)
    }

    @Test
    fun `probe category is UI`() {
        assertEquals(ProbeCategory.UI, probe.category)
    }

    @Test
    fun `probe severity is TRACE`() {
        // inventory.yml rank-55 severity = trace.
        assertEquals(ProbeSeverity.TRACE, probe.severity)
    }

    @Test
    fun `probe android layer is APPLICATION`() {
        assertEquals(AndroidLayer.APPLICATION, probe.androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, probe.budgetMs)
    }

    @Test
    fun `probe completes within budget`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }

    @Test
    fun `result is non-null with all fields populated`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertNotNull(result)
        assertNotNull(result.evidence)
        assertNotNull(result.method)
    }
}

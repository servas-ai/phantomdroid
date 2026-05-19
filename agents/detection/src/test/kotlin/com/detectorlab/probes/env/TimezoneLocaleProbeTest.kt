package com.detectorlab.probes.env

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
import kotlin.test.assertTrue

/**
 * Unit tests for TimezoneLocaleProbe (env.timezone_locale_mismatch, rank 20).
 *
 * The probe reads `java.util.TimeZone.getDefault()` and
 * `java.util.Locale.getDefault()` in its production constructor. Every test
 * uses the supplier-injecting constructor for determinism regardless of the
 * JVM host locale or timezone.
 */
class TimezoneLocaleProbeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeProbe(
        tzId: String? = "Europe/Berlin",
        tzOffsetMinutes: Int = 60,
        country: String? = "DE",
        language: String? = "de",
    ): TimezoneLocaleProbe = TimezoneLocaleProbe(
        timezoneIdSupplier = { tzId },
        timezoneOffsetSupplier = { tzOffsetMinutes },
        localeCountrySupplier = { country },
        localeLanguageSupplier = { language },
    )

    private val emptyCtx: ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = null
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = false
            override fun listInstalledPackages() = emptyList<String>()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── Matching pairs ────────────────────────────────────────────────────────

    @Test
    fun `Europe Berlin plus DE — score is 0 match`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "DE")
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Europe Berlin plus AT — score is 0 (CET shared with Austria)`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "AT")
        val result = probe.run(emptyCtx)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `America Los_Angeles plus US — score is 0`() = runBlocking {
        val probe = makeProbe(tzId = "America/Los_Angeles", country = "US")
        val result = probe.run(emptyCtx)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Asia Tokyo plus JP — score is 0`() = runBlocking {
        val probe = makeProbe(tzId = "Asia/Tokyo", country = "JP")
        val result = probe.run(emptyCtx)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `match — pair_match evidence is match`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "DE")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_MATCH, ev?.value)
    }

    // ── Mismatch ──────────────────────────────────────────────────────────────

    @Test
    fun `Europe Berlin plus US — score is 1_0 mismatch`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "US")
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(1.0, result.score)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `mismatch — pair_match evidence is mismatch`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "US")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_MISMATCH, ev?.value)
    }

    @Test
    fun `Asia Shanghai plus DE — score is 1_0 mismatch`() = runBlocking {
        val probe = makeProbe(tzId = "Asia/Shanghai", country = "DE")
        val result = probe.run(emptyCtx)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `America New_York plus CN — score is 1_0 mismatch`() = runBlocking {
        val probe = makeProbe(tzId = "America/New_York", country = "CN")
        val result = probe.run(emptyCtx)
        assertEquals(1.0, result.score)
    }

    // ── Emulator default (UTC/GMT/Etc) ────────────────────────────────────────

    @Test
    fun `UTC timezone plus US — score is 0_95 (emulator default beats mismatch)`() = runBlocking {
        // UTC isn't in the table, but the emulator-default rule fires FIRST,
        // so it never reaches the unknown-pair branch. score=0.95, not 0.50.
        val probe = makeProbe(tzId = "UTC", country = "US")
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `GMT timezone — score is 0_95 emulator default`() = runBlocking {
        val probe = makeProbe(tzId = "GMT", country = "DE")
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Etc UTC plus DE — score is 0_95 emulator default`() = runBlocking {
        val probe = makeProbe(tzId = "Etc/UTC", country = "DE")
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `Etc GMT+5 — score is 0_95 (any Etc-prefix is emulator default)`() = runBlocking {
        val probe = makeProbe(tzId = "Etc/GMT+5", country = "US")
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.score)
    }

    @Test
    fun `emulator_default — pair_match evidence reflects it`() = runBlocking {
        val probe = makeProbe(tzId = "UTC", country = "US")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_EMULATOR_DEFAULT, ev?.value)
    }

    // ── Unknown timezone pair (not in lookup table) ──────────────────────────

    @Test
    fun `unknown timezone Pacific Honolulu — score is 0_5 unknown_pair`() = runBlocking {
        // Real TZ but not in the lab approximation table.
        val probe = makeProbe(tzId = "Pacific/Honolulu", country = "US")
        val result = probe.run(emptyCtx)
        assertEquals(0.5, result.score)
    }

    @Test
    fun `unknown_pair — pair_match evidence reflects it`() = runBlocking {
        val probe = makeProbe(tzId = "Pacific/Honolulu", country = "US")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_UNKNOWN_PAIR, ev?.value)
    }

    @Test
    fun `unknown_pair confidence stays 0_95 (both signals readable)`() = runBlocking {
        val probe = makeProbe(tzId = "Pacific/Honolulu", country = "US")
        val result = probe.run(emptyCtx)
        // Both supplier calls returned non-null/non-empty so confidence is FULL,
        // even though the score is the same 0.5 as signal_unavailable.
        assertEquals(0.95, result.confidence)
    }

    // ── Locale stripped to Locale.ROOT (country == "") ───────────────────────

    @Test
    fun `Locale ROOT with valid TZ — score is 0_7 locale_unknown`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "")
        val result = probe.run(emptyCtx)
        assertEquals(0.7, result.score)
    }

    @Test
    fun `Locale ROOT — pair_match evidence is locale_unknown`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_LOCALE_UNKNOWN, ev?.value)
    }

    @Test
    fun `Locale ROOT confidence stays 0_95 (both signals readable, empty is a value)`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "")
        val result = probe.run(emptyCtx)
        assertEquals(0.95, result.confidence)
    }

    // ── Null suppliers (signal unavailable) ──────────────────────────────────

    @Test
    fun `null timezone — score is 0_5 signal_unavailable`() = runBlocking {
        val probe = makeProbe(tzId = null, country = "DE")
        val result = probe.run(emptyCtx)
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `null country — score is 0_5 signal_unavailable`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = null)
        val result = probe.run(emptyCtx)
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `null both — score is 0_5 signal_unavailable`() = runBlocking {
        val probe = makeProbe(tzId = null, country = null)
        val result = probe.run(emptyCtx)
        assertEquals(0.5, result.score)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `empty timezone string — score is 0_5 (empty == null for TZ)`() = runBlocking {
        val probe = makeProbe(tzId = "", country = "DE")
        val result = probe.run(emptyCtx)
        assertEquals(0.5, result.score)
    }

    @Test
    fun `null supplier — pair_match evidence is signal_unavailable`() = runBlocking {
        val probe = makeProbe(tzId = null, country = "DE")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "pair_match" }
        assertEquals(TimezoneLocaleProbe.PAIR_SIGNAL_UNAVAILABLE, ev?.value)
    }

    // ── Supplier-throws crash-safety ──────────────────────────────────────────

    @Test
    fun `tz supplier throws — no crash, score 0_5 signal_unavailable`() = runBlocking {
        val probe = TimezoneLocaleProbe(
            timezoneIdSupplier = { throw RuntimeException("simulated TZ error") },
            timezoneOffsetSupplier = { 0 },
            localeCountrySupplier = { "DE" },
            localeLanguageSupplier = { "de" },
        )
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
    }

    @Test
    fun `locale supplier throws — no crash, score 0_5`() = runBlocking {
        val probe = TimezoneLocaleProbe(
            timezoneIdSupplier = { "Europe/Berlin" },
            timezoneOffsetSupplier = { 60 },
            localeCountrySupplier = { throw RuntimeException("simulated Locale error") },
            localeLanguageSupplier = { "de" },
        )
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
        assertEquals(0.5, result.score)
    }

    @Test
    fun `tz offset supplier throws — does not affect score path`() = runBlocking {
        // Offset is recorded as evidence only; a throw there gets a 0 default
        // and doesn't influence the score/pair_match cascade.
        val probe = TimezoneLocaleProbe(
            timezoneIdSupplier = { "Europe/Berlin" },
            timezoneOffsetSupplier = { throw RuntimeException("simulated offset error") },
            localeCountrySupplier = { "DE" },
            localeLanguageSupplier = { "de" },
        )
        val result = probe.run(emptyCtx)
        assertEquals(0.0, result.score)
        val ev = result.evidence.find { it.key == "timezone_offset_minutes" }
        assertEquals(0, ev?.value)
    }

    // ── Evidence row coverage ─────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 5 keys`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(emptyCtx)
        assertEquals(5, result.evidence.size)
    }

    @Test
    fun `evidence covers all five documented keys`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(emptyCtx)
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "timezone_id",
                "locale_country",
                "timezone_offset_minutes",
                "locale_language",
                "pair_match",
            ),
            keys,
        )
    }

    @Test
    fun `match — locale_country expected reflects table-derived country set`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", country = "DE")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "locale_country" }
        // Berlin maps to {DE, AT} → expected reads "DE|AT" (set order undefined,
        // but contents are deterministic).
        val expected = ev?.expected as String
        assertTrue("DE" in expected && "AT" in expected, "got $expected")
    }

    @Test
    fun `null timezone — timezone_id evidence is absent`() = runBlocking {
        val probe = makeProbe(tzId = null)
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "timezone_id" }
        assertEquals("absent", ev?.value)
    }

    @Test
    fun `offset evidence carries integer minutes value`() = runBlocking {
        val probe = makeProbe(tzId = "Europe/Berlin", tzOffsetMinutes = 60, country = "DE")
        val result = probe.run(emptyCtx)
        val ev = result.evidence.find { it.key == "timezone_offset_minutes" }
        assertEquals(60, ev?.value)
    }

    // ── Method string ─────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(emptyCtx)
        assertEquals(
            "Compare java.util.TimeZone.getDefault() against " +
                "java.util.Locale.getDefault().country using a static table " +
                "of common timezone→country mappings",
            result.method,
        )
    }

    // ── Lookup table sanity ───────────────────────────────────────────────────

    @Test
    fun `lookup table has at least 25 entries`() {
        assertTrue(
            TimezoneLocaleProbe.TIMEZONE_COUNTRY_TABLE.size >= 25,
            "lookup table has ${TimezoneLocaleProbe.TIMEZONE_COUNTRY_TABLE.size} entries",
        )
    }

    @Test
    fun `lookup table country codes are uppercase ISO 3166 alpha-2`() {
        for ((_, countries) in TimezoneLocaleProbe.TIMEZONE_COUNTRY_TABLE) {
            for (cc in countries) {
                assertEquals(2, cc.length, "country code $cc not length 2")
                assertEquals(cc.uppercase(), cc, "country code $cc not uppercase")
            }
        }
    }

    @Test
    fun `lookup table timezone IDs follow Olson Region_City format`() {
        for (tz in TimezoneLocaleProbe.TIMEZONE_COUNTRY_TABLE.keys) {
            assertTrue('/' in tz, "tz $tz missing region prefix")
        }
    }

    // ── Probe metadata ────────────────────────────────────────────────────────

    @Test
    fun `probe id is env_timezone_locale_mismatch`() {
        assertEquals("env.timezone_locale_mismatch", makeProbe().id)
    }

    @Test
    fun `probe rank is 20`() {
        assertEquals(20, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is HIGH`() {
        assertEquals(ProbeSeverity.HIGH, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 100ms`() {
        assertEquals(100L, makeProbe().budgetMs)
    }

    @Test
    fun `probe completes within budget on clean device`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(emptyCtx)
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }

    // ── No-arg constructor smoke ──────────────────────────────────────────────

    @Test
    fun `no-arg constructor uses host JVM defaults (smoke test)`() = runBlocking {
        // Just verifies the no-arg constructor wires the JVM defaults without
        // throwing; the actual values depend on the test host's locale/tz,
        // so we don't assert anything about the score.
        val probe = TimezoneLocaleProbe()
        val result = probe.run(emptyCtx)
        assertFalse(result.failed)
    }
}

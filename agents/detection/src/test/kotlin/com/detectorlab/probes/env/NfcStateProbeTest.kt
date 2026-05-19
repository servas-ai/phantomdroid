package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.buildprop.BoardHardwareProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for NfcStateProbe (env.nfc_state, inventory rank 48).
 */
class NfcStateProbeTest {

    private fun makeProbe(
        adapterPresent: Boolean? = true,
        nfcEnabled: Boolean? = true,
        featureNfc: Boolean? = true,
    ): NfcStateProbe = NfcStateProbe(
        adapterPresentSupplier = { adapterPresent },
        nfcEnabledSupplier = { nfcEnabled },
        featureNfcSupplier = { featureNfc },
    )

    private fun fakeCtx(
        model: String? = "Pixel 7",
        propertyThrows: Boolean = false,
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (propertyThrows) throw RuntimeException("simulated EACCES")
            return if (key == NfcStateProbe.PROP_RO_PRODUCT_MODEL) model else null
        }
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

    // ── Real device clean ────────────────────────────────────────────────────

    @Test
    fun `Pixel 7 with NFC adapter present and feature true — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.pattern" }
        assertEquals(NfcStateProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Pixel 7 with NFC disabled (user toggled off) — score is 0`() = runBlocking {
        // is_enabled=false is legitimate user state, not a signal.
        val result = makeProbe(nfcEnabled = false).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Galaxy S24 with NFC — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `OnePlus 11 with NFC — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.0, result.score)
    }

    // ── Adapter-vs-feature contradiction (0.85) ──────────────────────────────

    @Test
    fun `FEATURE_NFC true but adapter null — score is 0_85`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `contradiction — pattern is feature_vs_adapter_contradiction`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.pattern" }
        assertEquals(NfcStateProbe.PATTERN_CONTRADICTION, ev?.value)
    }

    @Test
    fun `contradiction — is_consistent evidence is false`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `contradiction fires on budget model too — not gated by flagship`() = runBlocking {
        // The contradiction is a structural impossibility regardless of
        // claimed device. Even a Redmi Note 12 with the contradiction
        // pattern is suspicious.
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.85, result.score)
    }

    @Test
    fun `FEATURE_NFC false AND adapter null — clean (no contradiction)`() = runBlocking {
        // Consistent: no feature, no adapter. Either a phone without NFC
        // hardware or a missing/disabled NFC stack. Falls through to
        // missing_on_flagship gate.
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `FEATURE_NFC true AND adapter true — clean`() = runBlocking {
        val result = makeProbe(
            adapterPresent = true,
            nfcEnabled = true,
            featureNfc = true,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Missing NFC on flagship (0.5 weak) ───────────────────────────────────

    @Test
    fun `Pixel 7 with null adapter and feature_false — score is 0_5`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `Pixel 8 Pro with null adapter and feature_null — score is 0_5`() = runBlocking {
        // featureNfc null (not true), so contradiction doesn't fire.
        // missing_on_flagship fires.
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = null,
        ).run(fakeCtx(model = "Pixel 8 Pro"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `Galaxy S24 with null adapter — score is 0_5`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `Galaxy Note with null adapter — score is 0_5 (SM-N family)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "SM-N976B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `Galaxy Z Fold with null adapter — score is 0_5 (SM-F family)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "SM-F926B"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `OnePlus 11 with null adapter — score is 0_5`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "OnePlus 11"))
        assertEquals(0.5, result.score)
    }

    @Test
    fun `missing on flagship — pattern is missing_on_flagship`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "nfc.pattern" }
        assertEquals(NfcStateProbe.PATTERN_MISSING_ON_FLAGSHIP, ev?.value)
    }

    @Test
    fun `Redmi Note 12 with null adapter — score is 0 (budget, no NFC expected)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Redmi Note 12"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Galaxy A52 with null adapter — score is 0 (mid-range)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "SM-A525F"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Lenovo Tab P11 with null adapter — score is 0 (tablet)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `null model with null adapter — score is 0 (cannot claim flagship)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = null))
        assertEquals(0.0, result.score)
    }

    // ── Pixel 4a — has NFC on Pixel 4a per spec, but here adapter null ───────

    @Test
    fun `Pixel 4a with null adapter — score is 0_5 (Pixel family)`() = runBlocking {
        // Pixel 4a/5a ARE flagship-with-NFC family despite being "budget
        // Pixels" — they ship with NFC, so a null adapter is structurally
        // suspicious for them too. The flagship-substring rule fires
        // correctly here (this is not a false-positive — it's the same
        // diagnostic class as null adapter on Pixel 8 Pro).
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = false,
        ).run(fakeCtx(model = "Pixel 4a"))
        assertEquals(0.5, result.score)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `contradiction + flagship — contradiction wins (0_85 over 0_5)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx(model = "Pixel 7"))
        assertEquals(0.85, result.score)
        val ev = result.evidence.find { it.key == "nfc.pattern" }
        assertEquals(NfcStateProbe.PATTERN_CONTRADICTION, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `adapter supplier returns — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `only feature supplier returns — confidence is 0_95`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            nfcEnabled = null,
            featureNfc = true,
        ).run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `all three suppliers return null — confidence is 0_50`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            nfcEnabled = null,
            featureNfc = null,
        ).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `all suppliers null — score is 0 (cannot fire any rule)`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            nfcEnabled = null,
            featureNfc = null,
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_50`() = runBlocking {
        val result = NfcStateProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `adapterPresentSupplier throws — does not crash`() = runBlocking {
        val probe = NfcStateProbe(
            adapterPresentSupplier = { throw RuntimeException("simulated") },
            nfcEnabledSupplier = { null },
            featureNfcSupplier = { true },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // adapterPresent treated as null; feature=true but adapter not
        // false (it's null) → contradiction doesn't fire (requires explicit
        // false). Falls clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `nfcEnabledSupplier throws — does not crash`() = runBlocking {
        val probe = NfcStateProbe(
            adapterPresentSupplier = { true },
            nfcEnabledSupplier = { throw RuntimeException("simulated") },
            featureNfcSupplier = { true },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `featureNfcSupplier throws — does not crash`() = runBlocking {
        val probe = NfcStateProbe(
            adapterPresentSupplier = { true },
            nfcEnabledSupplier = { true },
            featureNfcSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `all three suppliers throw — does not crash, degraded`() = runBlocking {
        val probe = NfcStateProbe(
            adapterPresentSupplier = { throw RuntimeException("a") },
            nfcEnabledSupplier = { throw RuntimeException("b") },
            featureNfcSupplier = { throw RuntimeException("c") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val result = makeProbe().run(fakeCtx(propertyThrows = true))
        assertFalse(result.failed)
    }

    // ── Cross-rank reuse invariants (rank-31/32/42/43/44/45 pattern) ─────────

    @Test
    fun `isFlagshipModel delegates to rank-28 BoardHardwareProbe`() {
        // Drift-safe invariant: if rank-28's FLAGSHIP_MODEL_SUBSTRINGS or
        // isFlagshipModel implementation changes, this probe inherits the
        // change. Test pins the cross-rank dependency.
        assertTrue(BoardHardwareProbe.isFlagshipModel("Pixel 7"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("SM-S921B"))
        assertTrue(BoardHardwareProbe.isFlagshipModel("OnePlus 11"))
        assertFalse(BoardHardwareProbe.isFlagshipModel("Redmi Note 12"))
        assertFalse(BoardHardwareProbe.isFlagshipModel("Lenovo Tab P11"))
        assertFalse(BoardHardwareProbe.isFlagshipModel(null))
    }

    @Test
    fun `FLAGSHIP_MODEL_SUBSTRINGS reused from rank-28 BoardHardwareProbe`() {
        // Rank-28 owns the flagship-substring list; rank-48 reuses it via
        // isFlagshipModel(). Lock the rank-28 list contract.
        assertEquals(
            listOf("pixel ", "sm-s", "sm-n", "sm-f", "oneplus"),
            BoardHardwareProbe.FLAGSHIP_MODEL_SUBSTRINGS,
        )
    }

    // ── Evidence row coverage ────────────────────────────────────────────────

    @Test
    fun `evidence has exactly 6 keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(6, result.evidence.size)
    }

    @Test
    fun `evidence covers all documented keys`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val keys = result.evidence.map { it.key }.toSet()
        assertEquals(
            setOf(
                "nfc.adapter_present",
                "nfc.is_enabled",
                "nfc.has_system_feature",
                "nfc.is_consistent",
                "nfc.is_flagship_model",
                "nfc.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `adapter_present evidence reflects supplier`() = runBlocking {
        val result = makeProbe(adapterPresent = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.adapter_present" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `null adapter_present — evidence is unavailable`() = runBlocking {
        val result = makeProbe(adapterPresent = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.adapter_present" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `is_enabled evidence reflects supplier`() = runBlocking {
        val result = makeProbe(nfcEnabled = false).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_enabled" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `null is_enabled — evidence is unavailable`() = runBlocking {
        val result = makeProbe(nfcEnabled = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_enabled" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `has_system_feature evidence reflects supplier`() = runBlocking {
        val result = makeProbe(featureNfc = true).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.has_system_feature" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_consistent evidence — true when both true`() = runBlocking {
        val result = makeProbe(
            adapterPresent = true,
            featureNfc = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_consistent evidence — true when both false`() = runBlocking {
        val result = makeProbe(
            adapterPresent = false,
            featureNfc = false,
        ).run(fakeCtx(model = "Redmi Note 12"))
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_consistent evidence — partial when one null`() = runBlocking {
        // Tri-state: exactly one of (adapter, feature) observed → cannot
        // confirm consistency, but cannot claim contradiction either.
        // "partial" is more honest than "true" for a downstream consumer
        // scanning evidence — they know to cross-check the individual rows.
        val result = makeProbe(
            adapterPresent = true,
            featureNfc = null,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("partial", ev?.value)
    }

    @Test
    fun `is_consistent evidence — unknown when both null`() = runBlocking {
        // Tri-state: neither observed → vacuous "true" would mislead.
        // "unknown" makes the no-observation case explicit.
        val result = makeProbe(
            adapterPresent = null,
            featureNfc = null,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `is_consistent evidence — partial when only feature observed`() = runBlocking {
        val result = makeProbe(
            adapterPresent = null,
            featureNfc = true,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "nfc.is_consistent" }
        assertEquals("partial", ev?.value)
    }

    @Test
    fun `is_flagship_model evidence — true on Pixel 7`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Pixel 7"))
        val ev = result.evidence.find { it.key == "nfc.is_flagship_model" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_flagship_model evidence — false on Redmi`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = "Redmi Note 12"))
        val ev = result.evidence.find { it.key == "nfc.is_flagship_model" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_flagship_model evidence — unknown when model null`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = null))
        val ev = result.evidence.find { it.key == "nfc.is_flagship_model" }
        assertEquals("unknown", ev?.value)
    }

    @Test
    fun `is_flagship_model evidence — unknown when model empty`() = runBlocking {
        val result = makeProbe().run(fakeCtx(model = ""))
        val ev = result.evidence.find { it.key == "nfc.is_flagship_model" }
        assertEquals("unknown", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read NfcAdapter + PackageManager.FEATURE_NFC; detect adapter-vs-" +
                "feature contradictions and missing-NFC on flagship-model devices. " +
                "Score 'no NFC' only weakly since NFC is not universal.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_nfc_state`() {
        assertEquals("env.nfc_state", makeProbe().id)
    }

    @Test
    fun `probe rank is 48`() {
        assertEquals(48, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is LOW`() {
        // Matches shared/probes/inventory.yml rank-48 severity=low.
        assertEquals(ProbeSeverity.LOW, makeProbe().severity)
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
    fun `probe completes within budget`() = runBlocking {
        val probe = makeProbe()
        val result = probe.run(fakeCtx())
        assertTrue(
            result.runtimeMs <= probe.budgetMs,
            "runtime ${result.runtimeMs}ms exceeded budget ${probe.budgetMs}ms",
        )
    }
}

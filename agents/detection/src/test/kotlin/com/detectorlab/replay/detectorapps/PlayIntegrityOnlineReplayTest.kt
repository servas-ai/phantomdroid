// agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/PlayIntegrityOnlineReplayTest.kt
//
// Power-19 E3 — PlayIntegrityOnlineReplayTest (rank-2 LIVE-API mock replay).
//
// ════════════════════════════════════════════════════════════════════════
// HARD CEILING DISCLAIMER (un-snapshottable.md §1 + corpus-index §4 row "2")
// ════════════════════════════════════════════════════════════════════════
//
// The real `integrity.play_integrity` API (`com.google.android.play.core.
// integrity.IntegrityManager`) returns a JWT signed by Google's TEE-
// provisioned device key. The verdict (BASIC_INTEGRITY /
// MEETS_DEVICE_INTEGRITY / MEETS_STRONG_INTEGRITY) is computed
// server-side by Google after validating the device's hardware-attested
// keystore certificate chain against Google's provisioning database.
//
// **No build-prop mutation can produce a Google-signed JWT — only a real
// TEE-attested Pixel device can.**
//
// Per `audit/spoof-stack/un-snapshottable.md` §1: rank-2 STRONG_INTEGRITY
// is mitigation_layer **L0** (uncountered in FOSS 2026). Per
// `audit/spoof-stack/spoof-stack-corpus-index.md` §4 row "2": ceiling
// type **Partial — DEVICE only, ephemeral** (TrickyStore 4–8 week
// windows). This test does **NOT** claim that any spoof-stack fixture
// produces a real STRONG verdict.
//
// What this test DOES:
//   - Mocks the property + package surface that the LIVE Play Integrity
//     verdict would derive a verdict tier from.
//   - Verifies that PlayIntegrityLiveProbe (the declarative inference
//     entry — rank 2, mitigation_layer L3-declarative) correctly
//     CLASSIFIES the verdict tier when presented with mock signals
//     consistent with each of the four verdict outcomes.
//   - Honest-tests the four verdict-tier classification paths:
//       1. NO_VERDICT  (≈ NO_INTEGRITY)  — GMS absent, gmsversion empty.
//       2. BASIC_ONLY  (≈ BASIC)         — GMS present but downgrade
//                                          signals fire (unprovisioned
//                                          clientid OR bootloader
//                                          unlocked OR GMS-hijack pkg).
//       3. DEVICE_BASIC (≈ DEVICE)       — GMS-disabled / gmsversion=0
//                                          downgrade tells fire but
//                                          STRONG-fail signals do not.
//       4. CLEAN       (≈ STRONG)        — no negative signal observed.
//                                          HARD CEILING: probe cannot
//                                          DISTINGUISH a real STRONG
//                                          device from a build-prop-
//                                          clean spoof. Real STRONG
//                                          requires the Google JWT,
//                                          which is NOT modeled here.
//
// What this test EXPLICITLY does NOT do:
//   - Does NOT replay any real Google-TEE-signed JWT (impossible offline).
//   - Does NOT produce a real STRONG verdict (un-snapshottable.md §1
//     row "Mitigation layer: L0 for STRONG_INTEGRITY").
//   - Does NOT validate cert chains against Google's Hardware Attestation
//     Root (covered separately by KeystoreAttestationProbe; corpus-index
//     §4 row "6" — also L0).
//
// Scope summary: verdict CLASSIFICATION (declarative inference) tested
// here. Verdict GENERATION (live signed JWT) is mitigation_layer L0 —
// out-of-scope for the JVM-pure detection module by design.
//
// ════════════════════════════════════════════════════════════════════════
//
// Anti-Verarschen disposition: every mock fixture is documented as a
// MOCK property-surface. None of them carry a real Google signature.
// Failing to honor this disclaimer would constitute exactly the kind of
// "spoof-stack can produce STRONG" overreach the audit corpus is built
// to prevent.

package com.detectorlab.replay.detectorapps

import com.detectorlab.core.Evidence
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.integrity.PlayIntegrityLiveProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline-mock LIVE-API response replay for [PlayIntegrityLiveProbe].
 *
 * The probe under test is the rank-2 declarative-inference variant of
 * Play Integrity (sister to the rank-71 `integrity.play_integrity_signals`
 * probe). This test feeds four mock property + package fixtures and
 * asserts that the probe classifies each into the expected verdict tier.
 *
 * **HARD CEILING REMINDER**: the live API verdict is a Google-signed JWT.
 * Mock fixtures here represent the BUILDPROP / PACKAGE SURFACE that the
 * declarative inference reads — they do **not** carry, simulate, or
 * imply a real Google TEE signature. See file-level KDoc.
 */
class PlayIntegrityOnlineReplayTest {

    private val probe = PlayIntegrityLiveProbe()

    // ── Mock context builder ─────────────────────────────────────────────

    /**
     * Builds a minimal [ProbeContext] over a property map + installed-
     * package set. This is the MOCK surface — no live PackageManager, no
     * live system_properties daemon, no JWT.
     */
    private fun mockCtx(
        props: Map<String, String?> = emptyMap(),
        installedPkgs: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = props[key]
        override fun fileExists(path: String) = false
        override fun readFile(path: String, maxBytes: Int): String? = null
        override fun querySettingSecure(key: String): String? = null
        override fun queryTelephonyManager(field: TelephonyField): String? = null
        override fun queryPackageManager(): PackageManagerView = object : PackageManagerView {
            override fun isPackageInstalled(packageName: String) = packageName in installedPkgs
            override fun listInstalledPackages() = installedPkgs.toList()
            override fun listPackagesWithPermission(permission: String) = emptyList<String>()
        }
        override fun querySensorManager(): SensorManagerView = object : SensorManagerView {
            override fun listSensorTypes() = emptyList<Int>()
            override fun sampleSensor(sensorType: Int, durationMs: Long) =
                SensorSample(LongArray(0), emptyArray())
        }
    }

    // ── Mock fixtures (one per verdict tier) ─────────────────────────────

    /**
     * Fixture 1 — NO_VERDICT (≈ NO_INTEGRITY).
     *
     * GMS package absent AND `ro.com.google.gmsversion` empty. A device
     * with no Google Play Services at all cannot run the Integrity API;
     * the live API returns NO_VERDICT. ReDroid / AOSP-only ROMs land
     * here. The probe scores SCORE_NO_GMS (0.95) and emits VERDICT_NO_VERDICT.
     *
     * MOCK NOTE: no real JWT involved — the probe makes the verdict
     * prediction from the package surface alone.
     */
    private fun fixtureNoVerdict(): Pair<Map<String, String>, Set<String>> = mapOf(
        PlayIntegrityLiveProbe.PROP_GMS_VERSION to "",
        PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
        PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
    ) to setOf("android", "com.android.systemui")

    /**
     * Fixture 2 — BASIC_ONLY (≈ BASIC).
     *
     * GMS present but the device is unprovisioned: `ro.com.google.
     * clientidbase == "android-google"` (the bare default value a real
     * Pixel never reports — a provisioned device reports the versioned
     * variant like "android-google-Pixel-7"). The live API would return
     * a BASIC verdict but would refuse to upgrade to DEVICE/STRONG. The
     * probe scores SCORE_UNPROVISIONED_CLIENTID (0.85) and emits
     * VERDICT_BASIC_ONLY.
     *
     * MOCK NOTE: no real JWT — the unprovisioned-clientid value is the
     * declarative tell that the live API would surface as a verdict
     * downgrade.
     */
    private fun fixtureBasicOnly(): Pair<Map<String, String>, Set<String>> = mapOf(
        PlayIntegrityLiveProbe.PROP_GMS_VERSION to "243532033",
        PlayIntegrityLiveProbe.PROP_CLIENTID_BASE to PlayIntegrityLiveProbe.CLIENTIDBASE_UNPROVISIONED,
        PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
        PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
    ) to setOf(PlayIntegrityLiveProbe.PKG_GMS)

    /**
     * Fixture 3 — DEVICE_BASIC (≈ DEVICE).
     *
     * GMS present, OOBE-provisioned clientid, locked bootloader, verity
     * enforcing — but `ro.gms.disabled == "1"` is set. This is the
     * "GMS-downgrade tell": the live API can still issue a DEVICE
     * verdict but NOT a STRONG one. The probe scores
     * SCORE_GMS_DOWNGRADE (0.70) and emits VERDICT_DEVICE_BASIC.
     *
     * MOCK NOTE: no real JWT — the gms.disabled property is the
     * declarative analog to the live API's DEVICE-tier-only return.
     */
    private fun fixtureDeviceBasic(): Pair<Map<String, String>, Set<String>> = mapOf(
        PlayIntegrityLiveProbe.PROP_GMS_VERSION to "243532033",
        PlayIntegrityLiveProbe.PROP_CLIENTID_BASE to "android-google-Pixel-7",
        PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
        PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
        PlayIntegrityLiveProbe.PROP_GMS_DISABLED to PlayIntegrityLiveProbe.GMS_DISABLED_TRUE,
    ) to setOf(PlayIntegrityLiveProbe.PKG_GMS)

    /**
     * Fixture 4 — CLEAN (≈ STRONG-equivalent surface).
     *
     * GMS present, OOBE-provisioned clientid, locked bootloader, verity
     * enforcing, no downgrade tells. The probe scores SCORE_CLEAN (0.0)
     * and emits VERDICT_CLEAN.
     *
     * ════ HARD CEILING (rank-2; un-snapshottable.md §1; corpus-index §4)
     *
     * **THIS FIXTURE DOES NOT PRODUCE A REAL STRONG VERDICT.** The
     * `MEETS_STRONG_INTEGRITY` verdict requires a Google-TEE-signed JWT
     * referencing a hardware-attested keystore cert chain rooted at
     * Google's per-OEM HSM-backed Hardware Attestation CA. Build-prop
     * mutation cannot synthesize the JWT or the cert chain. The probe
     * (a declarative inference, mitigation_layer L3) sees only that no
     * NEGATIVE signal fires — which is necessary but NOT sufficient for
     * a live STRONG verdict. The probe correctly emits VERDICT_CLEAN
     * (not VERDICT_STRONG_DEVICE_BASIC) for exactly this reason: it
     * lacks the Google-JWT evidence required to claim STRONG.
     *
     * MOCK NOTE: this fixture's "clean" status reflects ABSENCE of
     * declarative negative tells, NOT presence of TEE-attested
     * positive evidence.
     */
    private fun fixtureStrongClean(): Pair<Map<String, String>, Set<String>> = mapOf(
        PlayIntegrityLiveProbe.PROP_GMS_VERSION to "243532033",
        PlayIntegrityLiveProbe.PROP_CLIENTID_BASE to "android-google-Pixel-7",
        PlayIntegrityLiveProbe.PROP_FLASH_LOCKED to "1",
        PlayIntegrityLiveProbe.PROP_VERITYMODE to "enforcing",
    ) to setOf(PlayIntegrityLiveProbe.PKG_GMS)

    // ── Evidence helper ──────────────────────────────────────────────────

    private fun verdictOf(result: ProbeResult): String? {
        val evidence: List<Evidence> = result.evidence
        return evidence.firstOrNull { it.key == PlayIntegrityLiveProbe.EV_PREDICTED_VERDICT }
            ?.value as? String
    }

    // ── Per-fixture replay tests ─────────────────────────────────────────

    @Test
    fun `fixture 1 (NO_INTEGRITY) classifies as VERDICT_NO_VERDICT with score 0_95`() = runBlocking {
        val (props, pkgs) = fixtureNoVerdict()
        val result = probe.run(mockCtx(props = props, installedPkgs = pkgs))

        assertEquals(PlayIntegrityLiveProbe.SCORE_NO_GMS, result.score)
        assertEquals(PlayIntegrityLiveProbe.VERDICT_NO_VERDICT, verdictOf(result))
        assertEquals(PlayIntegrityLiveProbe.METHOD, result.method)
    }

    @Test
    fun `fixture 2 (BASIC) classifies as VERDICT_BASIC_ONLY with score 0_85`() = runBlocking {
        val (props, pkgs) = fixtureBasicOnly()
        val result = probe.run(mockCtx(props = props, installedPkgs = pkgs))

        assertEquals(PlayIntegrityLiveProbe.SCORE_UNPROVISIONED_CLIENTID, result.score)
        assertEquals(PlayIntegrityLiveProbe.VERDICT_BASIC_ONLY, verdictOf(result))
        assertEquals(PlayIntegrityLiveProbe.METHOD, result.method)
    }

    @Test
    fun `fixture 3 (DEVICE) classifies as VERDICT_DEVICE_BASIC with score 0_70`() = runBlocking {
        val (props, pkgs) = fixtureDeviceBasic()
        val result = probe.run(mockCtx(props = props, installedPkgs = pkgs))

        assertEquals(PlayIntegrityLiveProbe.SCORE_GMS_DOWNGRADE, result.score)
        assertEquals(PlayIntegrityLiveProbe.VERDICT_DEVICE_BASIC, verdictOf(result))
        assertEquals(PlayIntegrityLiveProbe.METHOD, result.method)
    }

    @Test
    fun `fixture 4 (STRONG-surface) classifies as VERDICT_CLEAN with score 0_0 — HARD CEILING does not promote to STRONG`() = runBlocking {
        val (props, pkgs) = fixtureStrongClean()
        val result = probe.run(mockCtx(props = props, installedPkgs = pkgs))

        // Probe sees no negative signal, scores clean.
        assertEquals(PlayIntegrityLiveProbe.SCORE_CLEAN, result.score)

        // CRITICAL: the probe emits VERDICT_CLEAN, NOT
        // VERDICT_STRONG_DEVICE_BASIC. This is the codified hard-ceiling
        // honesty contract — a build-prop-clean surface is necessary but
        // not sufficient for a real STRONG verdict. See file-level KDoc
        // and un-snapshottable.md §1 row "1".
        assertEquals(PlayIntegrityLiveProbe.VERDICT_CLEAN, verdictOf(result))
        assertTrue(
            verdictOf(result) != PlayIntegrityLiveProbe.VERDICT_STRONG_DEVICE_BASIC,
            "HARD CEILING — declarative probe must NEVER claim STRONG_DEVICE_BASIC " +
                "without a real Google-TEE-signed JWT (un-snapshottable.md §1; " +
                "corpus-index §4 row '2').",
        )
    }

    // ── Hard-ceiling regression test ─────────────────────────────────────

    @Test
    fun `HARD CEILING — declarative_only evidence is always true (no live JWT pretense)`() = runBlocking {
        // Every fixture must record `play_integrity.declarative_only = true`.
        // This is the load-bearing anti-Verarschen marker that downstream
        // consumers use to identify the probe as inference-only (rank-2
        // declarative inference, NOT the live API entry).
        val fixtures = listOf(
            fixtureNoVerdict(),
            fixtureBasicOnly(),
            fixtureDeviceBasic(),
            fixtureStrongClean(),
        )
        for ((props, pkgs) in fixtures) {
            val result = probe.run(mockCtx(props = props, installedPkgs = pkgs))
            val declarativeOnly = result.evidence
                .firstOrNull { it.key == PlayIntegrityLiveProbe.EV_DECLARATIVE_ONLY }
                ?.value
            assertEquals(
                true,
                declarativeOnly,
                "Every mock fixture must mark itself as declarative-only — the probe is " +
                    "incapable of producing a live Google-signed JWT (un-snapshottable.md §1).",
            )
        }
    }
}

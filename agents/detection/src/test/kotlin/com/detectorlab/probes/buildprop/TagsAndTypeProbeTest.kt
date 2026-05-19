package com.detectorlab.probes.buildprop

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.PackageManagerView
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.core.SensorManagerView
import com.detectorlab.core.SensorSample
import com.detectorlab.core.TelephonyField
import com.detectorlab.probes.network.NetworkTypeProbe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for TagsAndTypeProbe (buildprop.tags_and_type, inventory rank 7).
 */
class TagsAndTypeProbeTest {

    private val probe = TagsAndTypeProbe()

    private fun fakeCtx(
        tags: String? = "release-keys",
        type: String? = "user",
        model: String? = "Pixel 7",
        propertyThrowsOn: Set<String> = emptySet(),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? {
            if (key in propertyThrowsOn) throw RuntimeException("simulated EACCES on $key")
            return when (key) {
                TagsAndTypeProbe.PROP_RO_BUILD_TAGS -> tags
                TagsAndTypeProbe.PROP_RO_BUILD_TYPE -> type
                TagsAndTypeProbe.PROP_RO_PRODUCT_MODEL -> model
                else -> null
            }
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
    fun `Pixel 7 release-keys + user — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Samsung S24 production build — score is 0`() = runBlocking {
        val result = probe.run(fakeCtx(model = "SM-S921B"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — is_production_user_build evidence is true`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "build.is_production_user_build" }
        assertEquals("true", ev?.value)
    }

    // ── Both violations (1.0) ────────────────────────────────────────────────

    @Test
    fun `test-keys + userdebug — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "userdebug"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `test-keys + eng — score is 1_0`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "eng"))
        assertEquals(1.0, result.score)
    }

    @Test
    fun `both violations — pattern is both_violations`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "userdebug"))
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_BOTH_VIOLATIONS, ev?.value)
    }

    @Test
    fun `both violations — both violation evidence rows are true`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "userdebug"))
        val tagsEv = result.evidence.find { it.key == "build.tags_violation" }
        val typeEv = result.evidence.find { it.key == "build.type_violation" }
        assertEquals("true", tagsEv?.value)
        assertEquals("true", typeEv?.value)
    }

    // ── Tags violation only (0.95) ───────────────────────────────────────────

    @Test
    fun `test-keys + user — score is 0_95`() = runBlocking {
        // Anomalous: build claims user (production) but signed with
        // test-keys. Still non-prod.
        val result = probe.run(fakeCtx(tags = "test-keys", type = "user"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `tags violation only — pattern is tags_violation`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "user"))
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_TAGS_VIOLATION, ev?.value)
    }

    @Test
    fun `tags violation — tags_violation evidence is true, type_violation false`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "user"))
        val tagsEv = result.evidence.find { it.key == "build.tags_violation" }
        val typeEv = result.evidence.find { it.key == "build.type_violation" }
        assertEquals("true", tagsEv?.value)
        assertEquals("false", typeEv?.value)
    }

    // ── Type violation only (0.95) ───────────────────────────────────────────

    @Test
    fun `release-keys + userdebug — score is 0_95 (Google internal build flavor)`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = "userdebug"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `release-keys + eng — score is 0_95 (AOSP source-engineering build)`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = "eng"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `type violation only — pattern is type_violation`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = "userdebug"))
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_TYPE_VIOLATION, ev?.value)
    }

    @Test
    fun `type case-insensitive — USERDEBUG fires`() = runBlocking {
        // Defensive case-insensitivity (real AOSP always lowercases,
        // but a custom ROM could capitalize).
        val result = probe.run(fakeCtx(tags = "release-keys", type = "USERDEBUG"))
        assertEquals(0.95, result.score)
    }

    @Test
    fun `type case-insensitive — ENG fires`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = "ENG"))
        assertEquals(0.95, result.score)
    }

    // ── Empty values on phone-class (0.7) ────────────────────────────────────

    @Test
    fun `tags empty + user on Pixel 7 — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = "user"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `release-keys + type empty on Pixel 7 — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = ""))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `both empty on Pixel 7 — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = ""))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `tags empty on Galaxy S24 (phone-class) — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = "user", model = "SM-S921B"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `tags empty on Redmi Note 12 (phone-class, budget) — score is 0_7`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = "user", model = "Redmi Note 12"))
        assertEquals(0.7, result.score)
    }

    @Test
    fun `tags empty on Lenovo tablet (not phone-class) — score is 0`() = runBlocking {
        // Tablets are NOT phone-class — pre-prod tablet stubs are not
        // dispositive in this rule's scope.
        val result = probe.run(fakeCtx(tags = "", type = "user", model = "Lenovo Tab P11"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `tags empty on null model — score is 0 (cannot claim phone-class)`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = "user", model = null))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `empty on phone-class — pattern is empty_on_phone_class`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "", type = "user"))
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_EMPTY_ON_PHONE_CLASS, ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `test-keys empty type — both violations OR tags-only (test-keys wins source order)`() = runBlocking {
        // Empty type doesn't satisfy isNonProductionType (returns false),
        // so the both-violations rule doesn't fire — only tags rule.
        val result = probe.run(fakeCtx(tags = "test-keys", type = ""))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_TAGS_VIOLATION, ev?.value)
    }

    @Test
    fun `empty tags + userdebug — type_violation wins over empty rule`() = runBlocking {
        // Type=userdebug fires the (cascade-earlier) type_violation
        // rule at 0.95 before the (cascade-later) empty rule at 0.7.
        val result = probe.run(fakeCtx(tags = "", type = "userdebug"))
        assertEquals(0.95, result.score)
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_TYPE_VIOLATION, ev?.value)
    }

    @Test
    fun `tags violation wins over type violation in source order — pattern is tags_violation`() = runBlocking {
        // When tags=test-keys AND type=user, only tags violation fires.
        // No cascade-ordering between tags and type here (both can't be
        // single-violation simultaneously); test pins source order is
        // tags first regardless.
        val result = probe.run(fakeCtx(tags = "test-keys", type = "user"))
        val ev = result.evidence.find { it.key == "build.tags_type_pattern" }
        assertEquals(TagsAndTypeProbe.PATTERN_TAGS_VIOLATION, ev?.value)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `both properties readable — confidence 0_95`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `tags null — confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(tags = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `type null — confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(type = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `both null — confidence 0_50`() = runBlocking {
        val result = probe.run(fakeCtx(tags = null, type = null))
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `tags null + release-keys type clean — score is 0 (no rule fires)`() = runBlocking {
        // Null tags is treated as "unobservable" not "empty" — does
        // NOT trigger the empty-on-phone-class rule. Score stays 0.
        val result = probe.run(fakeCtx(tags = null, type = "user"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `tags null + userdebug — type_violation still fires`() = runBlocking {
        // Per-property try/catch: tags null doesn't suppress type
        // observation. Type rule fires.
        val result = probe.run(fakeCtx(tags = null, type = "userdebug"))
        assertEquals(0.95, result.score)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `tags read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(TagsAndTypeProbe.PROP_RO_BUILD_TAGS))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `type read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(TagsAndTypeProbe.PROP_RO_BUILD_TYPE))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `model read throws — does not crash`() = runBlocking {
        val result = probe.run(
            fakeCtx(propertyThrowsOn = setOf(TagsAndTypeProbe.PROP_RO_PRODUCT_MODEL))
        )
        assertFalse(result.failed)
    }

    @Test
    fun `all reads throw — does not crash, confidence degraded`() = runBlocking {
        val result = probe.run(
            fakeCtx(
                propertyThrowsOn = setOf(
                    TagsAndTypeProbe.PROP_RO_BUILD_TAGS,
                    TagsAndTypeProbe.PROP_RO_BUILD_TYPE,
                    TagsAndTypeProbe.PROP_RO_PRODUCT_MODEL,
                )
            )
        )
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isNonProductionType detects userdebug and eng`() {
        assertTrue(TagsAndTypeProbe.isNonProductionType("userdebug"))
        assertTrue(TagsAndTypeProbe.isNonProductionType("eng"))
    }

    @Test
    fun `isNonProductionType case-insensitive`() {
        assertTrue(TagsAndTypeProbe.isNonProductionType("USERDEBUG"))
        assertTrue(TagsAndTypeProbe.isNonProductionType("UserDebug"))
        assertTrue(TagsAndTypeProbe.isNonProductionType("ENG"))
    }

    @Test
    fun `isNonProductionType false for user (production)`() {
        assertFalse(TagsAndTypeProbe.isNonProductionType("user"))
    }

    @Test
    fun `isNonProductionType false for empty and null`() {
        assertFalse(TagsAndTypeProbe.isNonProductionType(""))
        assertFalse(TagsAndTypeProbe.isNonProductionType(null))
    }

    @Test
    fun `isPhoneClassModel delegates to rank-25 NetworkTypeProbe`() {
        // Drift-safe reuse anchor — empty-on-phone-class rule depends
        // on NetworkTypeProbe.isPhoneClassModel correctly identifying
        // Pixel/Galaxy/OnePlus/Redmi as phone-class and EXCLUDING
        // tablets. Same anchor pattern as rank-49/52/53.
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Pixel 7"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("SM-S908B"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("OnePlus 11"))
        assertTrue(NetworkTypeProbe.isPhoneClassModel("Redmi Note 12"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel("Lenovo Tab P11"))
        assertFalse(NetworkTypeProbe.isPhoneClassModel(null))
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
                "ro.build.tags",
                "ro.build.type",
                "build.tags_violation",
                "build.type_violation",
                "build.is_production_user_build",
                "build.tags_type_pattern",
            ),
            keys,
        )
    }

    @Test
    fun `tags evidence reflects supplier`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys"))
        val ev = result.evidence.find { it.key == "ro.build.tags" }
        assertEquals("test-keys", ev?.value)
    }

    @Test
    fun `tags evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(tags = null))
        val ev = result.evidence.find { it.key == "ro.build.tags" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `tags evidence empty when empty-string`() = runBlocking {
        // Empty-string distinct from <unavailable>: property was
        // observed but had no value (pre-prod-stub signature).
        val result = probe.run(fakeCtx(tags = ""))
        val ev = result.evidence.find { it.key == "ro.build.tags" }
        assertEquals("", ev?.value)
    }

    @Test
    fun `type evidence reflects supplier`() = runBlocking {
        val result = probe.run(fakeCtx(type = "userdebug"))
        val ev = result.evidence.find { it.key == "ro.build.type" }
        assertEquals("userdebug", ev?.value)
    }

    @Test
    fun `type evidence unavailable when null`() = runBlocking {
        val result = probe.run(fakeCtx(type = null))
        val ev = result.evidence.find { it.key == "ro.build.type" }
        assertEquals("<unavailable>", ev?.value)
    }

    @Test
    fun `is_production_user_build evidence false on test-keys`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "test-keys", type = "user"))
        val ev = result.evidence.find { it.key == "build.is_production_user_build" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_production_user_build evidence false on userdebug`() = runBlocking {
        val result = probe.run(fakeCtx(tags = "release-keys", type = "userdebug"))
        val ev = result.evidence.find { it.key == "build.is_production_user_build" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `is_production_user_build evidence false on null tags`() = runBlocking {
        // Null tags means unobservable — cannot claim production.
        val result = probe.run(fakeCtx(tags = null, type = "user"))
        val ev = result.evidence.find { it.key == "build.is_production_user_build" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `tags_violation evidence false on release-keys`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "build.tags_violation" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `type_violation evidence false on user`() = runBlocking {
        val result = probe.run(fakeCtx())
        val ev = result.evidence.find { it.key == "build.type_violation" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = probe.run(fakeCtx())
        assertEquals(
            "Read ro.build.tags + ro.build.type and verify production-build " +
                "pattern (release-keys + user). Scoped focus separate from " +
                "rank 1 BuildFingerprint which checks consistency across the " +
                "full fingerprint string.",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is buildprop_tags_and_type`() {
        assertEquals("buildprop.tags_and_type", probe.id)
    }

    @Test
    fun `probe rank is 7`() {
        assertEquals(7, probe.rank)
    }

    @Test
    fun `probe category is BUILDPROP`() {
        assertEquals(ProbeCategory.BUILDPROP, probe.category)
    }

    @Test
    fun `probe severity is CRITICAL`() {
        // inventory.yml rank-7 severity = critical. Joins rank-1
        // (BuildFingerprint), rank-3 (SuDetection), rank-4
        // (QemuArtifacts), rank-8 (Xposed), rank-10 (InstalledApps)
        // as the CRITICAL-severity set.
        assertEquals(ProbeSeverity.CRITICAL, probe.severity)
    }

    @Test
    fun `probe android layer is NATIVE`() {
        assertEquals(AndroidLayer.NATIVE, probe.androidLayer)
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
}

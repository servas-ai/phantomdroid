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
 * Unit tests for AccountsProbe (env.accounts, inventory rank 40).
 */
class AccountsProbeTest {

    private fun makeProbe(
        accounts: List<Pair<String, String>>? = listOf(
            "alice@gmail.com" to "com.google",
        ),
        googleCount: Int? = null,
    ): AccountsProbe = AccountsProbe(
        accountsSupplier = { accounts },
        googleAccountCountSupplier = { googleCount },
    )

    private fun fakeCtx(
        properties: Map<String, String?> = mapOf(
            AccountsProbe.PROP_RO_PRODUCT_BRAND to "google",
            AccountsProbe.PROP_RO_PRODUCT_MODEL to "Pixel 7",
        ),
    ): ProbeContext = object : ProbeContext {
        override fun getSystemProperty(key: String): String? = properties[key]
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
    fun `Pixel with Google + Dropbox — score is 0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@gmail.com" to "com.google",
                "alice@personal.com" to "com.dropbox",
            ),
        ).run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
    }

    @Test
    fun `Pixel with only Google — score is 0`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `clean — pattern is clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_CLEAN, ev?.value)
    }

    @Test
    fun `clean — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `Samsung with Google + Samsung email — score is 0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@gmail.com" to "com.google",
                "alice@samsung.com" to "com.samsung.android.email.provider",
            ),
        ).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "samsung",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "SM-S908B",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Test marker (1.0) ────────────────────────────────────────────────────

    @Test
    fun `EMULATOR_test account name — score is 1_0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("EMULATOR_test_account" to "com.google"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `TEST_user account name — score is 1_0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("TEST_user1" to "com.google"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `mock_account_42 lowercase — score is 1_0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("mock_account_42" to "com.google"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `MOCK_ uppercase — score is 1_0 (case-insensitive)`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("MOCK_USER" to "com.google"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `test marker in type instead of name — score is 1_0`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@gmail.com" to "com.emulator_test.account"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `test marker — pattern is test_marker`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("EMULATOR_test" to "com.google"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_TEST_MARKER, ev?.value)
    }

    @Test
    fun `test marker — has_test_marker evidence is true`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("EMULATOR_test" to "com.google"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.has_test_marker" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `legitimate Google account name not flagged`() = runBlocking {
        // "test" is a substring of "testify" but the marker is specifically
        // "test_" (with underscore). alice.testimoni@gmail.com shouldn't fire.
        val result = makeProbe(
            accounts = listOf("alice.testimoni@gmail.com" to "com.google"),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    @Test
    fun `account containing 'tested' substring not flagged`() = runBlocking {
        // No "test_" substring → no fire.
        val result = makeProbe(
            accounts = listOf("battle.tested@gmail.com" to "com.google"),
        ).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Zero accounts on phone (0.9) ─────────────────────────────────────────

    @Test
    fun `zero accounts on Pixel — score is 0_9`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(fakeCtx())
        assertEquals(0.9, result.score)
    }

    @Test
    fun `zero accounts on Pixel — pattern is zero_accounts_phone`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_ZERO_ACCOUNTS_PHONE, ev?.value)
    }

    @Test
    fun `zero accounts on Samsung — score is 0_9`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "samsung",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "SM-S908B",
                ),
            ),
        )
        assertEquals(0.9, result.score)
    }

    @Test
    fun `zero accounts on Huawei — score is 0 (exempt)`() = runBlocking {
        // Huawei devices ship without GMS by policy — exempt.
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "Huawei",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "P40 Pro",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `zero accounts on Honor — score is 0 (Huawei family exempt)`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "honor",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "Honor 50",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `zero accounts on tablet — falls to degraded zero (0_5)`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "lenovo",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "Lenovo Tab P11",
                ),
            ),
        )
        assertEquals(0.5, result.score)
    }

    @Test
    fun `zero accounts no model context — score is 0_5 degraded`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to null,
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to null,
                ),
            ),
        )
        assertEquals(0.5, result.score)
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_ZERO_ACCOUNTS_DEGRADED, ev?.value)
    }

    // ── No Google account on phone (0.85) ────────────────────────────────────

    @Test
    fun `Samsung phone with only Samsung account no Google — score is 0_85`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@samsung.com" to "com.samsung.android.email.provider",
            ),
        ).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "samsung",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "SM-S908B",
                ),
            ),
        )
        assertEquals(0.85, result.score)
    }

    @Test
    fun `Pixel with Dropbox but no Google — score is 0_85`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@personal.com" to "com.dropbox"),
        ).run(fakeCtx())
        assertEquals(0.85, result.score)
    }

    @Test
    fun `no Google account — pattern is no_google_account_phone`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@personal.com" to "com.dropbox"),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_NO_GOOGLE_ACCOUNT_PHONE, ev?.value)
    }

    @Test
    fun `Huawei phone with no Google account — score is 0 (exempt)`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@huawei.com" to "huawei.com.account"),
        ).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "huawei",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "P40 Pro",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    @Test
    fun `tablet without Google account — score is 0 (not phone-class)`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@dropbox.com" to "com.dropbox"),
        ).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "lenovo",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "Lenovo Tab P11",
                ),
            ),
        )
        assertEquals(0.0, result.score)
    }

    // ── Explicit googleAccountCountSupplier ──────────────────────────────────

    @Test
    fun `explicit googleCount overrides list count — uses explicit value`() = runBlocking {
        // Suppose list enumeration failed (returned empty) but explicit
        // getAccountsByType returned 1 — should NOT fire no-Google rule.
        val result = makeProbe(
            accounts = emptyList(),
            googleCount = 1,
        ).run(fakeCtx())
        // accounts list empty AND phone-class AND non-Huawei → zero_accounts_phone
        // still fires at 0.9 (total count is 0 regardless of explicit Google count).
        // Confirms the rules are independently gated.
        assertEquals(0.9, result.score)
    }

    @Test
    fun `explicit googleCount of 2 with no list — google_count evidence reflects explicit`() =
        runBlocking {
            val result = makeProbe(
                accounts = emptyList(),
                googleCount = 2,
            ).run(fakeCtx())
            val ev = result.evidence.find { it.key == "accounts.google_count" }
            assertEquals("2", ev?.value)
        }

    @Test
    fun `explicit googleCount null falls back to list count`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("alice@gmail.com" to "com.google"),
            googleCount = null,
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.google_count" }
        assertEquals("1", ev?.value)
    }

    // ── PII handling ─────────────────────────────────────────────────────────

    @Test
    fun `account names never appear in evidence`() = runBlocking {
        val piiName = "alice.secret.user@gmail.com"
        val result = makeProbe(
            accounts = listOf(piiName to "com.google"),
        ).run(fakeCtx())
        result.evidence.forEach { ev ->
            assertFalse(
                ev.value.toString().contains(piiName),
                "Evidence row '${ev.key}' leaked account name PII: ${ev.value}",
            )
        }
    }

    @Test
    fun `account name with test marker not surfaced in evidence`() = runBlocking {
        val piiName = "EMULATOR_secret_user_42"
        val result = makeProbe(
            accounts = listOf(piiName to "com.google"),
        ).run(fakeCtx())
        result.evidence.forEach { ev ->
            assertFalse(
                ev.value.toString().contains(piiName),
                "Evidence row '${ev.key}' leaked test-account name PII: ${ev.value}",
            )
        }
        // Pattern records the classification but NOT the name.
        val ev = result.evidence.find { it.key == "accounts.pattern" }
        assertEquals(AccountsProbe.PATTERN_TEST_MARKER, ev?.value)
    }

    @Test
    fun `types evidence is comma-joined sorted distinct`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@gmail.com" to "com.google",
                "bob@gmail.com" to "com.google",   // dup type
                "alice@dropbox.com" to "com.dropbox",
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.types" }
        assertEquals("com.dropbox,com.google", ev?.value)
    }

    @Test
    fun `empty types evidence shows 'none'`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.types" }
        assertEquals("none", ev?.value)
    }

    @Test
    fun `unreadable types evidence shows unreadable`() = runBlocking {
        val result = makeProbe(accounts = null).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.types" }
        assertEquals("<unreadable>", ev?.value)
    }

    // ── Cascade ordering ─────────────────────────────────────────────────────

    @Test
    fun `test marker plus no Google account — test marker wins`() = runBlocking {
        val result = makeProbe(
            accounts = listOf("EMULATOR_test" to "com.dropbox"),
        ).run(fakeCtx())
        assertEquals(1.0, result.score)
    }

    @Test
    fun `zero accounts on Pixel beats degraded fallback`() = runBlocking {
        // Phone-class non-Huawei: zero_accounts_phone (0.9), not degraded (0.5).
        val result = makeProbe(accounts = emptyList()).run(fakeCtx())
        assertEquals(0.9, result.score)
    }

    // ── Confidence tiers ─────────────────────────────────────────────────────

    @Test
    fun `accounts readable — confidence is 0_95`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `accounts null — confidence is 0_50`() = runBlocking {
        val result = makeProbe(accounts = null).run(fakeCtx())
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `accounts null — score is 0 (cannot score without observation)`() = runBlocking {
        val result = makeProbe(accounts = null).run(fakeCtx())
        assertEquals(0.0, result.score)
    }

    // ── Production no-arg constructor ────────────────────────────────────────

    @Test
    fun `no-arg production ctor — score 0 confidence 0_50`() = runBlocking {
        val result = AccountsProbe().run(fakeCtx())
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `no-arg production ctor — total_count evidence is unreadable`() = runBlocking {
        val result = AccountsProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.total_count" }
        assertEquals("<unreadable>", ev?.value)
    }

    // ── Crash safety ─────────────────────────────────────────────────────────

    @Test
    fun `accountsSupplier throws — does not crash`() = runBlocking {
        val probe = AccountsProbe(
            accountsSupplier = { throw RuntimeException("SecurityException simulated") },
            googleAccountCountSupplier = { 1 },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        assertEquals(0.0, result.score)
        assertEquals(0.50, result.confidence)
    }

    @Test
    fun `googleAccountCountSupplier throws — does not crash`() = runBlocking {
        val probe = AccountsProbe(
            accountsSupplier = { listOf("alice@gmail.com" to "com.google") },
            googleAccountCountSupplier = { throw RuntimeException("simulated") },
        )
        val result = probe.run(fakeCtx())
        assertFalse(result.failed)
        // Explicit count threw → falls back to list count. List has 1 Google. → clean.
        assertEquals(0.0, result.score)
    }

    @Test
    fun `getSystemProperty throws — does not crash`() = runBlocking {
        val ctx = object : ProbeContext {
            override fun getSystemProperty(key: String): String? =
                throw RuntimeException("simulated property failure")
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
        val result = makeProbe().run(ctx)
        assertFalse(result.failed)
    }

    // ── Helper unit tests ────────────────────────────────────────────────────

    @Test
    fun `isHuaweiBrand recognizes huawei case-insensitive`() {
        assertTrue(AccountsProbe.isHuaweiBrand("Huawei"))
        assertTrue(AccountsProbe.isHuaweiBrand("HUAWEI"))
        assertTrue(AccountsProbe.isHuaweiBrand("huawei"))
        assertTrue(AccountsProbe.isHuaweiBrand("  huawei  "))
    }

    @Test
    fun `isHuaweiBrand recognizes honor family member`() {
        assertTrue(AccountsProbe.isHuaweiBrand("honor"))
        assertTrue(AccountsProbe.isHuaweiBrand("Honor"))
    }

    @Test
    fun `isHuaweiBrand false for other brands`() {
        assertFalse(AccountsProbe.isHuaweiBrand("samsung"))
        assertFalse(AccountsProbe.isHuaweiBrand("google"))
        assertFalse(AccountsProbe.isHuaweiBrand("xiaomi"))
        assertFalse(AccountsProbe.isHuaweiBrand(""))
        assertFalse(AccountsProbe.isHuaweiBrand(null))
    }

    @Test
    fun `hasTestMarker detects EMULATOR prefix`() {
        assertTrue(
            AccountsProbe.hasTestMarker(
                listOf("EMULATOR_user" to "com.google"),
            ),
        )
    }

    @Test
    fun `hasTestMarker detects test_ TEST_ MOCK_ mock_`() {
        assertTrue(
            AccountsProbe.hasTestMarker(
                listOf("alice@test_account.com" to "com.google"),
            ),
        )
        assertTrue(
            AccountsProbe.hasTestMarker(
                listOf("TEST_acc" to "com.google"),
            ),
        )
        assertTrue(
            AccountsProbe.hasTestMarker(
                listOf("MOCK_X" to "com.google"),
            ),
        )
        assertTrue(
            AccountsProbe.hasTestMarker(
                listOf("mock_account_1" to "com.google"),
            ),
        )
    }

    @Test
    fun `hasTestMarker false for empty list`() {
        assertFalse(AccountsProbe.hasTestMarker(emptyList()))
    }

    @Test
    fun `hasTestMarker false for normal accounts`() {
        assertFalse(
            AccountsProbe.hasTestMarker(
                listOf(
                    "alice@gmail.com" to "com.google",
                    "alice@dropbox.com" to "com.dropbox",
                ),
            ),
        )
    }

    @Test
    fun `googleAccountCountFrom counts correctly`() {
        assertEquals(
            0,
            AccountsProbe.googleAccountCountFrom(
                listOf("alice@dropbox.com" to "com.dropbox"),
            ),
        )
        assertEquals(
            1,
            AccountsProbe.googleAccountCountFrom(
                listOf("alice@gmail.com" to "com.google"),
            ),
        )
        assertEquals(
            2,
            AccountsProbe.googleAccountCountFrom(
                listOf(
                    "alice@gmail.com" to "com.google",
                    "alice.work@gmail.com" to "com.google",
                    "alice@dropbox.com" to "com.dropbox",
                ),
            ),
        )
    }

    @Test
    fun `googleAccountCountFrom case-insensitive on type`() {
        assertEquals(
            1,
            AccountsProbe.googleAccountCountFrom(
                listOf("alice@gmail.com" to "COM.GOOGLE"),
            ),
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
                "accounts.total_count",
                "accounts.google_count",
                "accounts.types",
                "accounts.has_test_marker",
                "accounts.is_huawei_brand",
                "accounts.pattern",
            ),
            keys,
        )
    }

    @Test
    fun `total_count evidence shows int`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@gmail.com" to "com.google",
                "alice@dropbox.com" to "com.dropbox",
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.total_count" }
        assertEquals("2", ev?.value)
    }

    @Test
    fun `google_count evidence shows int`() = runBlocking {
        val result = makeProbe(
            accounts = listOf(
                "alice@gmail.com" to "com.google",
                "alice.work@gmail.com" to "com.google",
            ),
        ).run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.google_count" }
        assertEquals("2", ev?.value)
    }

    @Test
    fun `is_huawei_brand evidence is true for huawei`() = runBlocking {
        val result = makeProbe(accounts = emptyList()).run(
            fakeCtx(
                mapOf(
                    AccountsProbe.PROP_RO_PRODUCT_BRAND to "Huawei",
                    AccountsProbe.PROP_RO_PRODUCT_MODEL to "P40 Pro",
                ),
            ),
        )
        val ev = result.evidence.find { it.key == "accounts.is_huawei_brand" }
        assertEquals("true", ev?.value)
    }

    @Test
    fun `is_huawei_brand evidence is false for google`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.is_huawei_brand" }
        assertEquals("false", ev?.value)
    }

    @Test
    fun `has_test_marker evidence is false for clean`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        val ev = result.evidence.find { it.key == "accounts.has_test_marker" }
        assertEquals("false", ev?.value)
    }

    // ── Method string ────────────────────────────────────────────────────────

    @Test
    fun `method string matches spec`() = runBlocking {
        val result = makeProbe().run(fakeCtx())
        assertEquals(
            "Read AccountManager.getAccounts() + getAccountsByType('com.google'); " +
                "detect zero-Google-account on non-Huawei phone-class device, " +
                "and EMULATOR/TEST/mock markers in account names",
            result.method,
        )
    }

    // ── Probe metadata ───────────────────────────────────────────────────────

    @Test
    fun `probe id is env_accounts`() {
        assertEquals("env.accounts", makeProbe().id)
    }

    @Test
    fun `probe rank is 40`() {
        assertEquals(40, makeProbe().rank)
    }

    @Test
    fun `probe category is ENV`() {
        assertEquals(ProbeCategory.ENV, makeProbe().category)
    }

    @Test
    fun `probe severity is MEDIUM`() {
        // Matches shared/probes/inventory.yml rank-40 severity=medium.
        assertEquals(ProbeSeverity.MEDIUM, makeProbe().severity)
    }

    @Test
    fun `probe android layer is FRAMEWORK`() {
        assertEquals(AndroidLayer.FRAMEWORK, makeProbe().androidLayer)
    }

    @Test
    fun `probe budget is 150ms`() {
        assertEquals(150L, makeProbe().budgetMs)
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

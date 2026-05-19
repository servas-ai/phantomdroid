// inventory.yml rank 40, mitigation_layer manual
package com.detectorlab.probes.env

import com.detectorlab.core.AndroidLayer
import com.detectorlab.core.Evidence
import com.detectorlab.core.Probe
import com.detectorlab.core.ProbeCategory
import com.detectorlab.core.ProbeContext
import com.detectorlab.core.ProbeResult
import com.detectorlab.core.ProbeSeverity
import com.detectorlab.probes.network.NetworkTypeProbe

/**
 * Probe #40 — env.accounts.
 *
 * Reads the device's configured AccountManager accounts (count + types
 * only, NEVER the user-identifying name) and looks for signal patterns a
 * real Android user almost never produces:
 *   • Phone-class device with zero accounts at all (real phones pin a
 *     Google Account during retail OOBE on non-Huawei brands).
 *   • Phone-class device with zero `com.google` accounts AND a
 *     non-Huawei brand (Huawei devices ship without GMS by policy, so
 *     they're exempt from this rule).
 *   • Any account whose name OR type contains `EMULATOR_`, `TEST_`, or
 *     `mock_` substrings — synthetic test fixtures.
 *
 * **AccountManager accessor gap.** `ProbeContext` exposes no
 * `queryAccountManager()` view. Two constructor-injected suppliers:
 * `accountsSupplier` returns the list of `Account(name, type)` pairs;
 * `googleAccountCountSupplier` is the explicit count from
 * `AccountManager.getAccountsByType("com.google").size`. Production no-arg
 * ctor returns null on both; the probe falls back to confidence 0.50
 * and cannot score above 0.0 (no observations).
 *
 * **PII handling.** Account names ARE the user's identity (e.g. email
 * for `com.google`). The probe records only the COUNT and the TYPE list
 * in Evidence rows. Names are inspected for the test-marker substring
 * check (case-insensitive) but never emitted to evidence. The
 * `accounts.types` row is the type-name list (`com.google`,
 * `com.dropbox`, ...) which is non-PII OEM/app metadata.
 *
 * Reuses rank-25 [NetworkTypeProbe.isPhoneClassModel] for the phone-class
 * gate — same drift-safe pattern as rank 31/33/34/35/36.
 *
 * Scoring (max wins; first-match cascade in source order):
 *   1.00  Any account name OR type contains `EMULATOR_`, `TEST_`, or
 *         `mock_` substring (case-insensitive — synthetic test fixture)
 *   0.90  Zero accounts AND phone-class AND non-Huawei brand
 *   0.85  Zero `com.google` accounts AND phone-class AND non-Huawei
 *         brand (one-or-more other accounts but no Google)
 *   0.50  Zero accounts on non-phone-class non-Huawei device (e.g. unknown
 *         model) — could be fresh-out-of-box, capped low-confidence
 *   0.00  At least one `com.google` account, OR Huawei-brand device
 *         (exempt from the zero-account rules per GMS policy)
 *
 * Confidence:
 *   0.95  Accounts supplier returned (primary surface observed)
 *   0.50  Accounts supplier returned null (degraded — no observation)
 *
 * Reference: shared/probes/inventory.yml rank 40 (mitigation_layer manual).
 */
class AccountsProbe(
    private val accountsSupplier: () -> List<Pair<String, String>>? = { null },
    private val googleAccountCountSupplier: () -> Int? = { null },
) : Probe {
    override val id = "env.accounts"
    override val rank = 40
    override val category = ProbeCategory.ENV
    override val severity = ProbeSeverity.MEDIUM
    override val androidLayer = AndroidLayer.FRAMEWORK
    override val budgetMs = 150L

    companion object {
        const val PROP_RO_PRODUCT_BRAND = "ro.product.brand"
        const val PROP_RO_PRODUCT_MODEL = "ro.product.model"

        const val ACCOUNT_TYPE_GOOGLE = "com.google"
        const val BRAND_HUAWEI = "huawei"
        const val BRAND_HONOR = "honor"

        val HUAWEI_FAMILY_BRANDS = setOf(BRAND_HUAWEI, BRAND_HONOR)

        /** Substrings that mark synthetic test fixtures (case-insensitive). */
        val TEST_MARKER_SUBSTRINGS = listOf("emulator_", "test_", "mock_")

        const val PATTERN_TEST_MARKER = "test_marker"
        const val PATTERN_ZERO_ACCOUNTS_PHONE = "zero_accounts_phone"
        const val PATTERN_NO_GOOGLE_ACCOUNT_PHONE = "no_google_account_phone"
        const val PATTERN_ZERO_ACCOUNTS_DEGRADED = "zero_accounts_degraded"
        const val PATTERN_CLEAN = "clean"

        const val SCORE_TEST_MARKER = 1.0
        const val SCORE_ZERO_ACCOUNTS_PHONE = 0.90
        const val SCORE_NO_GOOGLE_ACCOUNT_PHONE = 0.85
        const val SCORE_ZERO_ACCOUNTS_DEGRADED = 0.5
        const val SCORE_CLEAN = 0.0

        const val CONFIDENCE_FULL = 0.95
        const val CONFIDENCE_DEGRADED = 0.50

        const val METHOD =
            "Read AccountManager.getAccounts() + getAccountsByType('com.google'); " +
                "detect zero-Google-account on non-Huawei phone-class device, " +
                "and EMULATOR/TEST/mock markers in account names"

        /** Lowercases [brand] and tests against `HUAWEI_FAMILY_BRANDS`. */
        internal fun isHuaweiBrand(brand: String?): Boolean {
            val normalized = brand?.trim()?.lowercase() ?: return false
            return normalized in HUAWEI_FAMILY_BRANDS
        }

        /**
         * Returns true iff any element of [accounts] (name or type) contains
         * a known test-marker substring (case-insensitive).
         */
        internal fun hasTestMarker(accounts: List<Pair<String, String>>): Boolean {
            if (accounts.isEmpty()) return false
            return accounts.any { (name, type) ->
                val haystack = "$name|$type".lowercase()
                TEST_MARKER_SUBSTRINGS.any { haystack.contains(it) }
            }
        }

        /** Counts `com.google` entries in the accounts list (case-insensitive). */
        internal fun googleAccountCountFrom(accounts: List<Pair<String, String>>): Int =
            accounts.count { (_, type) -> type.equals(ACCOUNT_TYPE_GOOGLE, ignoreCase = true) }
    }

    override suspend fun run(ctx: ProbeContext): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val accounts: List<Pair<String, String>>? = try {
                accountsSupplier()
            } catch (_: Throwable) {
                null
            }
            val explicitGoogleCount: Int? = try {
                googleAccountCountSupplier()
            } catch (_: Throwable) {
                null
            }
            val brand: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_BRAND)
            } catch (_: Throwable) {
                null
            }
            val model: String? = try {
                ctx.getSystemProperty(PROP_RO_PRODUCT_MODEL)
            } catch (_: Throwable) {
                null
            }

            val phoneClass = NetworkTypeProbe.isPhoneClassModel(model)
            val huaweiBrand = isHuaweiBrand(brand)

            val accountsObserved = accounts != null
            val accountList = accounts ?: emptyList()
            val totalCount = accountList.size
            // Prefer the explicit `getAccountsByType('com.google').size` over
            // counting from the full list, because the explicit accessor can
            // succeed even when the full enumeration fails (different
            // permission scopes).
            val googleCount = explicitGoogleCount ?: googleAccountCountFrom(accountList)

            val testMarker = accountsObserved && hasTestMarker(accountList)

            val (pattern, score) = when {
                testMarker ->
                    PATTERN_TEST_MARKER to SCORE_TEST_MARKER
                accountsObserved && totalCount == 0 && phoneClass && !huaweiBrand ->
                    PATTERN_ZERO_ACCOUNTS_PHONE to SCORE_ZERO_ACCOUNTS_PHONE
                accountsObserved && totalCount > 0 && googleCount == 0 &&
                    phoneClass && !huaweiBrand ->
                    PATTERN_NO_GOOGLE_ACCOUNT_PHONE to SCORE_NO_GOOGLE_ACCOUNT_PHONE
                accountsObserved && totalCount == 0 && !phoneClass && !huaweiBrand ->
                    PATTERN_ZERO_ACCOUNTS_DEGRADED to SCORE_ZERO_ACCOUNTS_DEGRADED
                else ->
                    PATTERN_CLEAN to SCORE_CLEAN
            }

            val confidence = if (accountsObserved) CONFIDENCE_FULL else CONFIDENCE_DEGRADED

            // PII guard: types are non-PII metadata, names are PII. Only
            // surface the type list (and a small set of derived non-PII
            // attributes). Names are NEVER written to Evidence.
            val typesDisplay = if (!accountsObserved) "<unreadable>"
            else if (accountList.isEmpty()) "none"
            else accountList.map { it.second }.distinct().sorted().joinToString(",")

            val evidence = listOf(
                Evidence(
                    key = "accounts.total_count",
                    value = if (accountsObserved) totalCount.toString() else "<unreadable>",
                    expected = ">=1 on phone-class",
                ),
                Evidence(
                    key = "accounts.google_count",
                    value = if (accountsObserved || explicitGoogleCount != null)
                        googleCount.toString() else "<unreadable>",
                    expected = ">=1 on non-Huawei phone",
                ),
                Evidence(
                    key = "accounts.types",
                    value = typesDisplay,
                    expected = "includes com.google",
                ),
                Evidence(
                    key = "accounts.has_test_marker",
                    value = testMarker.toString(),
                    expected = "false",
                ),
                Evidence(
                    key = "accounts.is_huawei_brand",
                    value = huaweiBrand.toString(),
                    expected = "<used in scoring guard>",
                ),
                Evidence(
                    key = "accounts.pattern",
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
                "AccountsProbe: ${e.message ?: e.javaClass.simpleName}",
                runtimeMs = System.currentTimeMillis() - start,
            )
        }
    }
}

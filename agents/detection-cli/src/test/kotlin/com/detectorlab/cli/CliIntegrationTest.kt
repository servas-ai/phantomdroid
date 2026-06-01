// agents/detection-cli/src/test/kotlin/com/detectorlab/cli/CliIntegrationTest.kt
//
// CLI integration tests. Exercises the load-snapshot → ProbeRunner.runAll →
// ReportSerializer pipeline against the two canonical fixtures:
//
//   redroid-v12-snapshot.yml         (unspoofed ReDroid 12 — detection MUST fire)
//   redroid-spoofed-snapshot.yml     (Iter-1 spoof — zero critical failures, CLEAN)
//
// The YAML fixtures are hand-maintained mirrors of the in-source Kotlin
// snapshots (`RedroidV12Snapshot.SNAPSHOT` / `RedroidSpoofedSnapshot.SNAPSHOT`).
// If those .kt objects drift, the YAML fixtures must be re-synced and this
// test will catch the drift via score/category assertions.

package com.detectorlab.cli

import com.detectorlab.core.ProbeRunner
import com.detectorlab.core.ReportCategory
import com.detectorlab.core.replay.SnapshotReplayContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliIntegrationTest {

    private fun loadFixture(resourceName: String): File {
        val url = this::class.java.classLoader.getResource(resourceName)
            ?: error("test resource not found on classpath: $resourceName")
        return File(url.toURI())
    }

    @Test
    fun `loads ReDroid V12 unspoofed snapshot and detects emulator surface`(): Unit = runBlocking {
        val file = loadFixture("redroid-v12-snapshot.yml")
        val snapshot = SnapshotLoader.load(file)

        // Sanity: label survived deserialization and matches the .kt source.
        assertEquals("redroid-12-amd64-2026-05-20", snapshot.label)
        assertEquals(31, snapshot.sdkInt)
        assertTrue(
            "ro.build.fingerprint" in snapshot.systemProperties,
            "fingerprint key must be present after YAML parse",
        )
        assertTrue(
            snapshot.systemProperties["ro.build.fingerprint"]!!.contains("redroid"),
            "fingerprint value must preserve the load-bearing `redroid` substring",
        )

        val ctx = SnapshotReplayContext(snapshot)
        val probes = ProbeRegistry.allProbes(ctx)
        assertEquals(
            ProbeRegistry.EXPECTED_COUNT, probes.size,
            "ProbeRegistry must match the 82-probe snapshot inventory " +
                "(canonical 84-probe panel MINUS the two probes that cannot be " +
                "honestly assessed from a read-only snapshot: KeystoreAttestationProbe " +
                "+ IntegrityInstallSourceProbe)",
        )
        // CLI(82) == canonical(84) − {keystore_attestation, install_source}:
        // both excluded IDs must be ABSENT from the snapshot panel (they fired
        // false absence-nonzero scores from inputs the snapshot path cannot
        // observe). They remain in the canonical/live FullProbeRunnerSpoofTest.
        val ids = probes.map { it.id }.toSet()
        assertTrue(
            "integrity.keystore_attestation" !in ids,
            "KeystoreAttestationProbe must be EXCLUDED from the snapshot panel (live-only)",
        )
        assertTrue(
            "integrity.install_source" !in ids,
            "IntegrityInstallSourceProbe must be EXCLUDED from the snapshot panel " +
                "(its getInstallSourceInfo() signal is un-capturable by the docker-exec live path)",
        )

        val runner = ProbeRunner(
            probes = probes,
            ctx = ctx,
            appVersion = "0.1.0",
            deviceLabel = snapshot.label,
        )
        val report = runner.runAll()

        // Primary assertion: the unspoofed ReDroid surface produces a
        // non-zero weighted score. We don't pin an exact value because the
        // probe inventory's exact contribution per probe is the subject of
        // ongoing iteration; "> 0.0" is the CLI's correctness contract.
        assertTrue(
            report.aggregate.weightedScore > 0.0,
            "unspoofed ReDroid V12 must produce weightedScore > 0.0; " +
                "got ${report.aggregate.weightedScore}",
        )

        // Also exercise the JSON serializer end-to-end on a real report.
        val jsonText = ReportSerializer.toJson(report)
        val parsed = Json.parseToJsonElement(jsonText) as JsonObject
        assertEquals(
            "1.0",
            (parsed["schemaVersion"] as JsonPrimitive).content,
            "report JSON must emit schemaVersion=1.0",
        )
        assertEquals(
            snapshot.label,
            (parsed["deviceLabel"] as JsonPrimitive).content,
            "report deviceLabel must round-trip through serializer",
        )
        val aggregate = parsed["aggregate"] as JsonObject
        assertNotNull(aggregate["weightedScore"], "aggregate.weightedScore must be present")
        assertNotNull(aggregate["criticalFailures"], "aggregate.criticalFailures must be present")
        assertNotNull(aggregate["category"], "aggregate.category must be present")
    }

    @Test
    fun `loads ReDroid spoofed snapshot and classifies as CLEAN with zero critical failures`(): Unit =
        runBlocking {
            val file = loadFixture("redroid-spoofed-snapshot.yml")
            val snapshot = SnapshotLoader.load(file)

            assertEquals("redroid-12-amd64-2026-05-20-spoofed-v1", snapshot.label)
            assertEquals(31, snapshot.sdkInt)
            // Sanity: spoof mutations survived deserialization.
            assertEquals("user", snapshot.systemProperties["ro.build.type"])
            assertEquals("release-keys", snapshot.systemProperties["ro.build.tags"])
            assertEquals("panther", snapshot.systemProperties["ro.hardware"])
            assertEquals("3c:5a:b4:8d:f1:27", snapshot.bluetoothMac)
            assertEquals(setOf(1, 2, 4, 5, 6, 8), snapshot.sensorTypes)
            assertEquals("America/Los_Angeles", snapshot.timezoneId)
            assertEquals(1080, snapshot.displayWidthPixels)
            assertEquals(37.4221, snapshot.gpsLat)

            val ctx = SnapshotReplayContext(snapshot)
            val probes = ProbeRegistry.allProbes(ctx)
            assertEquals(ProbeRegistry.EXPECTED_COUNT, probes.size)

            val runner = ProbeRunner(
                probes = probes,
                ctx = ctx,
                appVersion = "0.1.0",
                deviceLabel = snapshot.label,
            )
            val report = runner.runAll()

            assertEquals(
                0, report.aggregate.criticalFailures,
                "spoofed snapshot must produce 0 critical failures via the CLI path; " +
                    "got ${report.aggregate.criticalFailures}. " +
                    "Residuals: " +
                    report.probes.filter { it.score > 0.0 }
                        .sortedByDescending { it.score }
                        .joinToString(", ") { "${it.id}=${it.score}" },
            )
            assertEquals(
                ReportCategory.CLEAN, report.aggregate.category,
                "spoofed snapshot must classify as CLEAN via the CLI path; " +
                    "got ${report.aggregate.category} " +
                    "(weightedScore=${"%.4f".format(report.aggregate.weightedScore)})",
            )
        }

    @Test
    fun `SnapshotLoader rejects unknown file extension`() {
        val temp = File.createTempFile("snapshot", ".txt")
        try {
            temp.writeText("label: x\ncapturedAt: x\n")
            val err = runCatching { SnapshotLoader.load(temp) }.exceptionOrNull()
            assertNotNull(err, "loader must reject unknown extension")
            assertTrue(
                err is IllegalArgumentException,
                "expected IllegalArgumentException, got ${err::class.simpleName}",
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `report JSON serialization round-trips evidence value types`(): Unit = runBlocking {
        val file = loadFixture("redroid-spoofed-snapshot.yml")
        val snapshot = SnapshotLoader.load(file)
        val ctx = SnapshotReplayContext(snapshot)
        val runner = ProbeRunner(
            probes = ProbeRegistry.allProbes(ctx),
            ctx = ctx,
            appVersion = "0.1.0",
            deviceLabel = snapshot.label,
        )
        val report = runner.runAll()
        val jsonText = ReportSerializer.toJson(report)
        // Pretty-printed JSON must contain newlines and 2-space indent.
        assertTrue(jsonText.contains("\n"), "pretty-printed JSON must contain newlines")
        assertTrue(jsonText.contains("  \"schemaVersion\""), "must use 2-space indent")
        // Parsing back must succeed (i.e. we produced valid JSON).
        val parsed = Json.parseToJsonElement(jsonText)
        assertNotNull(parsed)
    }
}

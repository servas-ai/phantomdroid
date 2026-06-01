// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/SnapshotLoader.kt
//
// Snapshot deserialization. Reads a YAML or JSON document from disk and
// constructs a `DeviceSnapshot` (which lives in the :detection module and is
// intentionally NOT `@Serializable` — it is a pure-Kotlin data class with
// nullable scalars, primitive sets, and nullable-valued maps, all of which
// would require non-trivial @Serializer wiring on the production class).
//
// Strategy: deserialize first into a CLI-local `SnapshotDto` mirror that IS
// @Serializable, then map to the production `DeviceSnapshot` in `toDomain()`.
// The DTO has the same field names as `DeviceSnapshot` so the YAML/JSON
// schema is identical to what a snapshot author would write against the
// production data class.
//
// Format detection is by file extension:
//   *.yml / *.yaml → kaml
//   *.json         → kotlinx-serialization-json
// Unknown extension → error (deliberate; better than guessing).

package com.detectorlab.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.detectorlab.core.replay.DeviceSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * CLI-local mirror of `DeviceSnapshot`. Same field names; @Serializable so
 * kaml + kotlinx-serialization-json can deserialize without modifying the
 * production data class. `toDomain()` produces the real `DeviceSnapshot`.
 *
 * Defaults match the production data class so a minimal snapshot YAML
 * (label + capturedAt only) round-trips correctly.
 */
@Serializable
internal data class SnapshotDto(
    val label: String,
    val capturedAt: String,
    val sdkInt: Int = 0,
    val systemProperties: Map<String, String?> = emptyMap(),
    val existingFiles: Set<String> = emptySet(),
    val readableFiles: Map<String, String> = emptyMap(),
    val settingsSecure: Map<String, String?> = emptyMap(),
    val settingsGlobal: Map<String, String?> = emptyMap(),
    val settingsSystem: Map<String, String?> = emptyMap(),
    val installedPackages: Set<String> = emptySet(),
    val telephony: Map<String, String?> = emptyMap(),
    // Per-PID /proc/<pid>/mountinfo content. Consumed by the mount-namespace
    // root probes (MountNsMismatch / OverlayFsPresent / SystemRwMount). A null
    // value = no observation (read failed) — the probe scores conservative 0.0.
    val mountInfo: Map<String, String?> = emptyMap(),
    // Socket names from /proc/net/unix. Consumed by MagiskUdsProbe.
    val procNetUnixSockets: List<String> = emptyList(),
    val sensorTypes: Set<Int> = emptySet(),
    val bluetoothMac: String? = null,
    val timezoneId: String? = null,
    val timezoneOffsetMinutes: Int? = null,
    val localeLanguage: String? = null,
    val localeCountry: String? = null,
    val localeDisplayName: String? = null,
    val displayWidthPixels: Int? = null,
    val displayHeightPixels: Int? = null,
    val displayDensityDpi: Int? = null,
    val displayXdpi: Float? = null,
    val displayYdpi: Float? = null,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val gpsAccuracy: Float? = null,
    val gpsProvider: String? = null,
    val gpsIsMock: Boolean? = null,
    // Native-hook surfaces consumed by FridaMemoryMapsProbe (rank 9.0),
    // NativePrologueHashProbe (rank 9.7), PrologueGotHooksProbe (rank 9.8).
    // A missing key stays empty/default = "no native-side measurement
    // recorded" — the probe scores 0.0 (absent != clean signal). Never
    // fabricated; a real capture must populate these via a native harness.
    val procSelfMapsLibs: Set<String> = emptySet(),
    val runtimeThreadNames: Set<String> = emptySet(),
    val openTcpPorts: Set<Int> = emptySet(),
    val prologueHashDeltas: Map<String, Boolean> = emptyMap(),
    val trampolinePatternCount: Int = 0,
    val gotPltAnomalies: Map<String, String> = emptyMap(),
    val rwxpMemorySegments: List<String> = emptyList(),
    // Power-13 root/runtime surfaces consumed by InitSvcEnumerationProbe
    // (rank 3.7) and MagiskModuleDirProbe (rank 3.9). Missing key → empty
    // map = "no observation captured"; the probe scores conservative 0.0.
    val initSvcProps: Map<String, String> = emptyMap(),
    val dirEntries: Map<String, List<String>> = emptyMap(),
    // Installer package name consumed by IntegrityInstallSourceProbe
    // (rank 10.5, freeRASP T5). null = "installer unknown / sideload" branch.
    val installSourcePackage: String? = null,
) {
    fun toDomain(): DeviceSnapshot = DeviceSnapshot(
        label = label,
        capturedAt = capturedAt,
        sdkInt = sdkInt,
        systemProperties = systemProperties,
        existingFiles = existingFiles,
        readableFiles = readableFiles,
        settingsSecure = settingsSecure,
        settingsGlobal = settingsGlobal,
        settingsSystem = settingsSystem,
        installedPackages = installedPackages,
        telephony = telephony,
        mountInfo = mountInfo,
        procNetUnixSockets = procNetUnixSockets,
        sensorTypes = sensorTypes,
        bluetoothMac = bluetoothMac,
        timezoneId = timezoneId,
        timezoneOffsetMinutes = timezoneOffsetMinutes,
        localeLanguage = localeLanguage,
        localeCountry = localeCountry,
        localeDisplayName = localeDisplayName,
        displayWidthPixels = displayWidthPixels,
        displayHeightPixels = displayHeightPixels,
        displayDensityDpi = displayDensityDpi,
        displayXdpi = displayXdpi,
        displayYdpi = displayYdpi,
        gpsLat = gpsLat,
        gpsLng = gpsLng,
        gpsAccuracy = gpsAccuracy,
        gpsProvider = gpsProvider,
        gpsIsMock = gpsIsMock,
        procSelfMapsLibs = procSelfMapsLibs,
        runtimeThreadNames = runtimeThreadNames,
        openTcpPorts = openTcpPorts,
        prologueHashDeltas = prologueHashDeltas,
        trampolinePatternCount = trampolinePatternCount,
        gotPltAnomalies = gotPltAnomalies,
        rwxpMemorySegments = rwxpMemorySegments,
        initSvcProps = initSvcProps,
        dirEntries = dirEntries,
        installSourcePackage = installSourcePackage,
    )
}

/**
 * Loads a `DeviceSnapshot` from disk. Format chosen by file extension.
 *
 * @throws IllegalArgumentException if extension is unknown or file missing
 * @throws kotlinx.serialization.SerializationException on parse failure
 */
object SnapshotLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
        ),
    )

    fun load(file: File): DeviceSnapshot {
        require(file.exists()) { "snapshot file does not exist: ${file.absolutePath}" }
        require(file.isFile) { "snapshot path is not a regular file: ${file.absolutePath}" }
        val text = file.readText()
        val dto = when (val ext = file.extension.lowercase()) {
            "yml", "yaml" -> yaml.decodeFromString(SnapshotDto.serializer(), text)
            "json" -> json.decodeFromString(SnapshotDto.serializer(), text)
            "" -> throw IllegalArgumentException(
                "snapshot file has no extension; expected .yml, .yaml, or .json: ${file.name}",
            )
            else -> throw IllegalArgumentException(
                "unsupported snapshot extension '.$ext' — expected .yml, .yaml, or .json",
            )
        }
        return dto.toDomain()
    }
}

# detection-cli

Standalone CLI runner that drives the full 65-probe DetectorLab inventory
against a captured `DeviceSnapshot` (YAML or JSON) and writes a JSON report
to stdout (or `--output <path>`).

This closes the deployer-loop: capture live container state → dump to YAML →
run CLI → measurable score → adjust spoof → iterate.

## Build & install

```bash
# From repo root
./gradlew :detection-cli:installDist
```

The runnable lands at `agents/detection-cli/build/install/detection-cli/bin/detection-cli`.

## Commands

```bash
detection-cli run      --snapshot <path> [--output <path>] [--app-version <semver>]
detection-cli validate --snapshot <path>
detection-cli version
```

* `run` — Load snapshot, run probes, emit report JSON. If `--output` is set,
  the JSON is written to the file and a one-line summary is written to stderr;
  otherwise JSON goes to stdout.
* `validate` — Parse the snapshot and confirm the 65-probe registry can be
  instantiated against it. Does NOT run probes. Use as a fast preflight in CI.
* `version` — Print the CLI version.

## Snapshot schema

Snapshots are YAML or JSON files matching the `DeviceSnapshot` field set:

```yaml
label: "redroid-12-amd64-2026-05-20"
capturedAt: "2026-05-20T00:00:00Z"
sdkInt: 31
systemProperties:
  "ro.build.fingerprint": "redroid/redroid_x86_64_only/redroid_x86_64_only:12/..."
  "ro.hardware": "redroid"
  # ...
existingFiles:
  - "/system/bin/su"
readableFiles:
  "/proc/version": "Linux version 4.15.0-213-generic ..."
sensorTypes: []
bluetoothMac: null
# ... all other DeviceSnapshot fields
```

Format is selected by file extension: `.yml`, `.yaml`, or `.json`. Unknown
extensions are rejected. See
`agents/detection/src/core/replay/DeviceSnapshot.kt` for the authoritative
field set and field-level semantics.

## End-to-end example

Capture a live ReDroid container's state and run probes against it:

```bash
# 1) Capture: dump getprop into a YAML snapshot
docker exec redroid12 getprop > /tmp/snapshot.props

# 2) Convert getprop output to a DeviceSnapshot YAML
#    (use scripts/snapshot-from-getprop.sh or hand-author per the schema)

# 3) Run the CLI
./detection-cli/build/install/detection-cli/bin/detection-cli run \
    --snapshot /tmp/snapshot.yml \
    --output /tmp/report.json

# 4) Inspect score
jq '.aggregate' /tmp/report.json
```

Sample output:

```json
{
  "weightedScore": 0.4321,
  "criticalFailures": 5,
  "category": "DETECTED"
}
```

## Iteration loop

1. Run CLI → observe `weightedScore`, `criticalFailures`, `category`
2. Examine the `probes` array for scoring probes (`.score > 0.0`)
3. Adjust the snapshot or the SpoofStack (Magisk resetprop / LSPosed hook /
   mount-overlay) to neutralize the residual hits
4. Re-capture → re-run CLI → repeat until `category=="CLEAN"`

See `agents/detection/src/core/replay/RedroidSpoofedSnapshot.kt` for an
example of an iteration-1 spoof masking 8 high-priority probes.

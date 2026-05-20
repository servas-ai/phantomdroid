# scripts/

Operational helpers for owner-driven workflows that fall outside the
build-system (Gradle) or CI pipeline.

| Script | Purpose |
|---|---|
| `redroid-recapture.sh` | Owner-helper: refresh a Kotlin `DeviceSnapshot` fixture from a live ReDroid Docker container. |
| `test-quality-gate-ratchet.sh` | Quality-gate-ratchet self-test (existing; pre-Power-17). |
| `render-heatmap.py` | Probe heatmap render utility (existing; pre-Power-17). |
| `governance/` | Governance check helpers (existing). |

---

## redroid-recapture.sh

**Purpose.** Refresh `agents/detection/src/core/replay/RedroidV12Snapshot.kt`
(or a sibling fixture) from a live ReDroid container via `docker exec`.
Carryover from the Power-13 / Power-14 owner-action set after the
PAR822349 host reboot — replaces the one-off ad-hoc capture commands
documented in `audit/E2E-validation-2026-05-20.md` with a single
reproducible helper.

### Usage

```bash
# Live mode (requires a running ReDroid container)
./scripts/redroid-recapture.sh redroid-test \
    agents/detection/src/core/replay/RedroidV12Snapshot.kt \
    RedroidV12

# Dry-run mode (no docker invocation — CI-safe; emits FIELD_UNAVAILABLE
# markers for every field so the resulting fixture is structural-only)
./scripts/redroid-recapture.sh --dry-run /tmp/sanity.kt RedroidV12

# Dry-run to stdout (useful for piping into a diff against an existing
# fixture's structural skeleton)
./scripts/redroid-recapture.sh --dry-run
```

### Arguments

| Position | Name | Required | Default | Notes |
|---|---|---|---|---|
| 1 | `container_name` | live mode only | — | Docker container name (e.g. `redroid-test`). Must be running. |
| 2 | `output.kt` | yes | — | Output Kotlin file path. Use `/dev/stdout` to stream. |
| 3 | `variant` | no | `RedroidV12` | Object-name prefix. `RedroidV13` → `object RedroidV13Snapshot`. |

`--dry-run` shifts the argv: `<output.kt>` becomes optional (defaults to
`/dev/stdout`) and `container_name` is omitted entirely.

### Anti-verarschen contract

The script enforces the project-wide "no fake values" rule:

- Every captured field carries a `// from: docker exec ...` source-comment
  on the line above. The reader can re-run the literal command shown and
  re-verify the value against the live container.
- If a capture command fails or returns empty stdout, the script emits a
  `// FIELD_UNAVAILABLE: <reason>` marker **instead of** the field. No
  synthetic defaults are injected. No "best-guess" values are fabricated.
- The emitted fixture is **idempotent**: running the script twice against
  the same container state produces byte-identical output, except for
  the `capturedAt` timestamp + the date-suffixed `label`.

### Captured fields

| `DeviceSnapshot` field | Source command |
|---|---|
| `sdkInt` | `getprop ro.build.version.sdk` |
| `systemProperties` | `getprop <key>` (40 canonical probe-surface keys) |
| `existingFiles` | `ls -d <path>` (8 canonical root/HAL paths) |
| `installedPackages` | `pm list packages` |
| `procSelfMapsLibs` | `cat /proc/self/maps` filtered for `frida-agent|libfrida-gadget|libgum|linjector` |
| `runtimeThreadNames` | `cat /proc/self/task/*/comm` |
| `openTcpPorts` | `cat /proc/net/tcp` filtered to LISTEN state |
| `mountInfo["self"]` | `cat /proc/self/mountinfo` |
| `mountInfo["1"]` | `cat /proc/1/mountinfo` |
| `installSourcePackage` | `pm dump <pkg> \| grep installerPackageName` (Power-16 T5; defaults to `com.android.shell`, override via `INSTALL_SOURCE_PKG=...`) |

Fields not captured by the helper (`telephony`, `sensorTypes`, settings
namespaces, GPS, native prologue/GOT hashes, etc.) are intentionally
omitted from the literal — they require either a booted `system_server`
(unreachable on the PAR822349 kernel-4.15 binderfs gap) or a native-side
ptrace harness. Owners hand-merge those fields into the emitted fixture
file after re-capture if the upstream device produces them.

### Prerequisites

**Live mode:**
- `docker` CLI on PATH
- Target container running and addressable via `docker exec`
- Container ships a POSIX shell at `/system/bin/sh` (standard for ReDroid)

**Dry-run mode:**
- `bash` 4+ — no external dependencies

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Fixture emitted successfully |
| 1 | Invalid arguments |
| 2 | Container not running or `docker` not on PATH (live mode only) |
| 3 | Output file exists and overwrite was declined |

### Verification (CI-friendly self-test)

```bash
# Syntax-check the script itself
bash -n scripts/redroid-recapture.sh

# Emit a dry-run fixture and verify it contains the required structural keys
./scripts/redroid-recapture.sh --dry-run /tmp/sanity.kt
grep -q 'object RedroidV12Snapshot {' /tmp/sanity.kt
grep -q 'val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(' /tmp/sanity.kt
grep -q 'FIELD_UNAVAILABLE' /tmp/sanity.kt
```

### Workflow: post-reboot recapture

After PAR822349 (or any future host) reboots and the ReDroid container
comes back up:

```bash
# 1. Verify container is up
docker ps --format '{{.Names}}' | grep redroid-test

# 2. Recapture into a sidecar file (DO NOT overwrite the canonical fixture
#    blindly — the canonical fixture carries Power-13/Power-16 fixture
#    upgrades that may not survive a raw recapture).
./scripts/redroid-recapture.sh redroid-test /tmp/redroid-recapture.kt RedroidV12

# 3. Diff against the canonical fixture and hand-merge the deltas
diff -u agents/detection/src/core/replay/RedroidV12Snapshot.kt \
        /tmp/redroid-recapture.kt | less

# 4. Update audit/ docs with the new capture timestamp
```

# Power-18 Phase-D Security Audit

**Branch**: report/CLO-143-weekly-W20
**Scope**: commits 725770c..HEAD (8c53fd3, 5b9ae72, 4c45716)
**Date**: 2026-05-21
**Auditor**: claude-sonnet-4-6 (Security Auditor agent)
**Overall verdict**: APPROVE

---

## Pillar 1 — Credentials Sweep

**Scope**: `git diff 725770c..HEAD -- agents/ .ci/ audit/`
**Method**: Regex scan across 2 225 added lines covering API key patterns, bearer/JWT tokens, hardcoded passwords, AWS/GCP/GitHub credential patterns, and RFC-1918 / internal-URL leaks.

**Findings**: NONE

- Zero matches for `api[_-]?key`, `secret[_-]?key`, `bearer <token>`, `jwt=`, `password="…"`, `token="…"`, `sk-…`, `ghp_…`, `AIza…`, `ya29.…`, `AKIA…`, base64-encoded 40-byte blobs.
- Zero internal URLs (`*.internal`, `*.corp`, `*.lan`) or RFC-1918 addresses found in the diff.
- CI scripts (`.ci/check-weighted-score.sh`, `.ci/check-namespace-compliance.py`, `.ci/check-panel-consistency.py`) contain no exported secrets, no Authorization headers, no inline tokens.

**Result**: CREDENTIALS_BLOCKERS = 0

---

## Pillar 2 — Test-Fixture Leak in Production CLI

**File audited**: `agents/detection-cli/src/main/kotlin/com/detectorlab/cli/SnapshotRegistry.kt`

**Invariant**: `MainSnapshotRegistry` must ship exactly 4 main-sourceSet snapshots and must refuse test-set names at runtime with `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES`.

**Findings**:

`MainSnapshotRegistry.entries` (lines 91–96) contains exactly:
1. `"Pixel7Clean"` → `Pixel7CleanSnapshot.SNAPSHOT`
2. `"SamsungS22Clean"` → `SamsungS22CleanSnapshot.SNAPSHOT`
3. `"RedroidV12"` → `RedroidV12Snapshot.SNAPSHOT`
4. `"RedroidSpoofed"` → `RedroidSpoofedSnapshot.SNAPSHOT`

All 4 imports resolve to `com.detectorlab.core.replay.*` (main-sourceSet). No test-sourceSet imports present in this file.

`TEST_SOURCE_SET_SNAPSHOT_NAMES` (lines 77–82) is an `internal val` — not a `MainSnapshotRegistry` member and not exported via `names()`. It carries exactly the 4 test-set names (`fridainjectedredroid`, `nox`, `bluestacks`, `genymotion`) used only as a sentinel in `resolveSnapshot()` to trigger `SnapshotNotFound.TestFixtureLeakGuard`.

The `resolveSnapshot()` helper (lines 139–153) enforces the correct resolution order: hit → return canonical pair; test-set-name miss → `TestFixtureLeakGuard`; other miss → `UnknownName`. The guard token `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` appears verbatim in the thrown message (line 123–127), satisfying the reviewer invariant.

**Result**: LEAK_BLOCKERS = 0. Production binary exposes exactly 4 main-sourceSet snapshots. Test-set names are permanently refused with the required error token.

---

## Pillar 3 — CI Scripts No Secrets

**Files audited**:
- `.ci/check-weighted-score.sh`
- `.ci/check-namespace-compliance.py`
- `.ci/check-panel-consistency.py`

**check-weighted-score.sh**: Uses `set -euo pipefail`. Accepts an optional `DETECTION_CLI_BIN` environment override (line 37) — correct pattern; the variable name is documented, not a secret. No API keys, tokens, or internal URLs. `mktemp` temp files are trap-cleaned on exit. `jq` and the CLI binary are the only external tools invoked. No curl/wget/auth headers. No secrets written to disk.

**check-namespace-compliance.py**: Pure static analysis script. Reads `.kt` files from the local repo tree using `Path.rglob`. No network calls, no subprocess invocations, no credentials. Uses only `re`, `sys`, `pathlib` from stdlib.

**check-panel-consistency.py**: Pure static analysis script. Reads two Kotlin test files with `Path.read_text`. No network calls, no credentials, no subprocess. Uses only `re`, `sys`, `pathlib`.

**Result**: CI_SECRET_BLOCKERS = 0

---

## Pillar 4 — Namespace Gate Correctness

**File audited**: `.ci/check-namespace-compliance.py`

**Tier-A (regression guard — `pkg.*`)**: Patterns `BARE_PKG_LIT` and `BARE_PKG_INTERP` (lines 62–63) correctly match `Evidence(…"pkg.<anything>"` and `Evidence(…"pkg.$<var>"` in any probe file. This covers both literal and string-interpolated forms of the bare `pkg.*` anti-pattern introduced before cross-cutting #1.

**Tier-B (opt-in strict namespace)**: The `extract_class_kdoc_declaration()` function (lines 74–84) restricts KDoc scanning to the first 200 lines of each file, correctly excluding per-row block comments inside `run()` from triggering probe-wide namespace enforcement. Two KDoc patterns are recognized: `prefixed \`<name>.*\`` and `` `<name>.*` namespace per cross-cutting ``.

**Spot-check 1 — `IntegrityInstallSourceProbe`**: KDoc declares "prefixed `install_source.*`" (lines 70–78 of the probe file). Evidence constants (lines 145–147) are `install_source.installer`, `install_source.allowlist_match`, `install_source.pattern`. All three keys carry the `install_source.` prefix. Tier-B compliance: PASS.

**Spot-check 2 — `ThirdPartyEmulatorArtifactsProbe`**: KDoc declares "`third_party_emulator.*` namespace per cross-cutting #1" (line 87 of probe file). All 8 `Evidence(key = …)` calls at lines 254, 259, 264, 269, 274, 279, 284, 289 use keys prefixed `third_party_emulator.*`. Tier-B compliance: PASS.

The gate correctly exits 0 on zero violations and 1 on any violation (lines 148–155). No false-negative paths found: the 200-line window is a conservative boundary that correctly excludes block comments only when they genuinely appear past the class-level KDoc (inside `run()` method bodies, which begin well after line 200 in these files).

**Result**: NAMESPACE_CORRECTNESS = PASS. Tier-A regression guard is structurally sound. Tier-B opt-in enforcement is correctly scoped to class-KDoc declarations. Both spot-checked probes are fully compliant.

---

## Pillar 5 — CompositeDetector Shell-Injection Check

**File audited**: `agents/detection-cli/src/main/kotlin/com/detectorlab/cli/CompositeDetector.kt`

**Method**: Full read (437 lines) + targeted grep for `Runtime.exec`, `ProcessBuilder`, `exec(`, `system(`, `popen(`.

**Findings**: NONE

`CompositeDetector` is a pure Kotlin `object` with no JVM process-spawning calls. All 6 detector families operate exclusively through the `ProbeContext` interface:
- `ctx.queryPackageManager()` / `pm.isPackageInstalled()`
- `ctx.getSystemProperty()`
- `ctx.fileExists()`
- `ctx.queryMountInfo()`
- `ctx.queryProcNetUnixSockets()`
- `ctx.queryDirEntries()`
- `ctx.queryInitSvcProps()`
- `ctx.queryProcSelfMapsLibs()`
- `ctx.queryRuntimeThreadNames()`
- `ctx.queryOpenTcpPorts()`
- `ctx.queryTelephonyManager()`
- `ctx.queryInstallSourcePackage()`

All of these are accessor methods on the `ProbeContext` abstraction layer — not shell invocations. No string is ever passed to a shell interpreter. No `Runtime.exec`, `ProcessBuilder`, or native process fork is present anywhere in the file or in any other file under `agents/detection-cli/src/main/kotlin/`.

**Result**: SHELL_INJECTION_RISK = NONE

---

## Pillar 6 — License Compliance

**File audited**: `agents/detection-cli/src/main/kotlin/com/detectorlab/cli/CompositeDetector.kt`

The file header (lines 1–36) provides explicit provenance: the composite OR-union is documented as VERBATIM duplication of `MasterCompositeDetectorReplayTest.kt` (commit c202ee8), which is the project's own internal test file. The duplication is intentional — the header explains the three architectural reasons (private scope in test sourceSet, exit-code contract alignment, and the source-of-truth's own "VERBATIM duplication IS the documented integration pattern" disclaimer).

All 437 lines are original Kotlin authored within this repository. The detector logic (package-name lists, system property names, path strings, port numbers) consists of:
- Package identifiers sourced from the referenced FOSS tools (RootBeer, Momo/HuskyDG, FridaDetector, EmulatorDetector), which document these as public detection lists with no proprietary claim.
- The Frida port constants (27042, 27043) are publicly documented by the Frida project.
- The emulator init-file paths are sourced from the FOSS reference libraries cited in `ThirdPartyEmulatorArtifactsProbe` (strazzere/anti-emulator, mofneko/EmulatorDetector, CalebFenton/AndroidEmulatorDetect).

None of these constitute copyrighted code copied beyond fair-use. All three FOSS references are detection-research repositories whose purpose is the public documentation of these artifact paths; citing them is research use, not code copying.

`IntegrityInstallSourceProbe.LEGITIMATE_INSTALLERS` — referenced via `freeRaspT5InstallSourceFires()` — is a data constant (7 package-name strings) within the project's own codebase. No copyrighted freeRASP source code is reproduced.

**Result**: LICENSE_WARNINGS = 0

---

## Summary

| Pillar | Check | Result |
|--------|-------|--------|
| 1 | Credentials in diff (agents/, .ci/, audit/) | CLEAR |
| 2 | Test-fixture leak — MainSnapshotRegistry | CLEAR (4 main snapshots, leak guard verified) |
| 3 | CI scripts no secrets | CLEAR |
| 4 | Namespace gate correctness (Tier-A + Tier-B) | PASS (both spot-checks compliant) |
| 5 | CompositeDetector shell injection | CLEAR (no Runtime.exec / ProcessBuilder) |
| 6 | License compliance | CLEAR (original Kotlin, FOSS-referenced data) |

```
credentials_blockers:  0
leak_blockers:         0
ci_secret_blockers:    0
namespace_correctness: PASS
license_warnings:      0
overall:               APPROVE
```

# Power-15 Phase-A Security Audit

**Date**: 2026-05-21
**Auditor**: claude-sonnet-4-6 (Security Auditor subagent)
**Branch**: `report/CLO-143-weekly-W20`
**Audit range**: `a259e40..HEAD` (6 commits: 5e549c5, b811e27, e74997d, 2ba76d6, 101bc5f, d11020c)
**Anti-verarschen mandate**: only claim verified what was actually verified.

---

## Pillar 1 — Credentials / Secrets Sweep

**Verdict: APPROVE**

Methodology: `git diff a259e40..HEAD -- audit/ agents/detection/src/ shared/ .gitignore` piped through three grep passes:

1. Pattern sweep for API keys, tokens (AWS `AKIA…`, GitHub `ghp_…`, OpenAI `sk-…`), bearer/JWT, passwords, SSH key headers, `.env` content, credential-embedded URLs.
2. Pattern sweep for internal hostnames and SCM URLs of the form `http(s)://user:password@host`.
3. Manual review of all non-comment `+` lines added by the diff.

Findings:

- No API keys, tokens, or secrets found in any added file.
- The only URLs in the diff are public GitHub URLs (`github.com/frida/…`, `github.com/framgia/…`, `github.com/mofneko/…`, `github.com/strazzere/…`, `github.com/OWASP/…`, `github.com/erfur/…`, `frida.re/docs/gadget/`, `support.genymotion.com`, `medium.com/@aimardcr/…`, `xdaforums.com`, `darvincitech.wordpress.com`, `qweraqq.github.io`, `b4x.com`, `ray-chong.medium.com`, `versprite.com`, `cin.ufpe.br`, `gist.github.com/runo280/`, `repo1.maven.org/maven2/com/scottyab/rootbeer-lib/`). None contain embedded credentials.
- The single IP address in the diff (`0100007F` = 127.0.0.1 in /proc/net/tcp little-endian encoding) is a localhost loopback reference in a documentation example, not an internal host.
- The telephony fixture values in `FridaInjectedRedroidSnapshot` (`LINE1_NUMBER = "15555215554"`, `SUBSCRIBER_ID = "310260000000000"`, `MCC_MNC = "310260"`) are the canonical Android emulator placeholder values (T-Mobile test MCCMNC 310260), not real subscriber data.

**Blockers**: 0

---

## Pillar 2 — Fabricated-Value Audit

**Verdict: APPROVE**

Methodology: Cross-referenced the 7 §C GAP items from `audit/spoof-stack/power-15-canonical-sources.md` against all 4 new snapshot files. For each GAP, verified whether the prohibited string literal appears as a fixture value.

### GAP item checks

**GAP 1 — DetectFrida full linjector pipe-path literal**
Canonical sources doc §A1.4 / §C item 1: "substring `linjector` match only; full pipe-path literal is GAP."
`FridaInjectedRedroidSnapshot.kt`: `dirEntries` does NOT contain any entry with `linjector` as a key or value. Explicit KDoc comment at line 296–303 explains the deliberate omission and cites the anti-verarschen mandate. The substring `linjector` appears only in a KDoc comment quoting the GAP rule, not as an encoded fixture value.
Result: COMPLIANT — GAP respected.

**GAP 2 — Frida port 27043 official documentation**
Canonical sources doc §A1.3 / §C item 2: "27043 is MEDIUM (detection-literature only, not in official Frida docs)."
`FridaInjectedRedroidSnapshot.kt` line 270–276: 27043 IS encoded in `openTcpPorts`, with an explicit inline comment: "MEDIUM-confidence… Encoded as a corroborating signal, not a must-detect canonical port."
Assessment: This is the permissible encoding. §C item 2 says "recommend snapshot uses 27042 as MUST-detect, 27043 as ALSO-COMMONLY-OBSERVED." The fixture does exactly this — both ports encoded, 27043 explicitly marked MEDIUM in KDoc. This is not a fabrication; it is a documented MEDIUM-confidence value with honest framing.
Result: COMPLIANT — MEDIUM encoding is within anti-verarschen bounds given the KDoc disclaimer.

**GAP 3 — BlueStacks `libBstHwHelper.so`**
Canonical sources doc §A3.2 / §C item 3: "UNVERIFIED publicly. DO NOT use without owner-verify."
`BlueStacksSnapshot.kt`: `libBstHwHelper.so` does NOT appear anywhere — not in `systemProperties`, `existingFiles`, `installedPackages`, or any other field. KDoc at line 49 explicitly lists it as NOT-ENCODED with the PUBLIC-UNVERIFIABLE explanation.
Result: COMPLIANT — GAP respected.

**GAP 4 — BlueStacks `ro.product.model=BlueStacks`**
Canonical sources doc §A3.2 / §C item 4: "UNVERIFIED publicly."
`BlueStacksSnapshot.kt`: `systemProperties` field is entirely absent from the `DeviceSnapshot(...)` constructor call (the object only populates `installedPackages`). `ro.product.model` does not appear. KDoc at line 51 explicitly lists it as NOT-ENCODED.
Result: COMPLIANT — GAP respected.

**GAP 5 — Nox `ro.product.manufacturer=alps`**
Canonical sources doc §A3.1 / §C item 5: "blog-only, LOW confidence. Do not use without live-device capture."
`NoxSnapshot.kt` line 72–75: `ro.product.manufacturer` is explicitly OMITTED with a KDoc comment (lines 71–76) explaining the alps value is LOW-confidence blog-only and that encoding it would violate anti-verarschen discipline.
Result: COMPLIANT — GAP respected.

**GAP 6 — Genymotion `/sys/class/dmi/id/product_name=VirtualBox`**
Canonical sources doc §A3.3 / §C item 6: "DMI is Linux-x86 host surface, not portable Android-API surface. LOW / heuristic only."
`GenymotionSnapshot.kt`: No `/sys/class/dmi/` entry appears in `existingFiles`, `systemProperties`, or `readableFiles`. KDoc at line 42–45 explicitly lists it as NOT-ENCODED with the "DMI is x86-host-detection surface, not portable Android-API surface" explanation.
Result: COMPLIANT — GAP respected.

**GAP 7 — Genymotion `genymotion-vbox86-additions.apk`**
Canonical sources doc §A3.3 / §C item 7: "exact filename UNVERIFIED. Use /dev/socket/genyd + /dev/socket/baseband_genyd as primary."
`GenymotionSnapshot.kt`: `genymotion-vbox86-additions.apk` does NOT appear anywhere. KDoc at lines 46–49 explicitly lists it as NOT-ENCODED ("exact filename UNVERIFIED publicly").
Result: COMPLIANT — GAP respected.

**Blockers**: 0

All 7 §C GAP items are correctly excluded from snapshot fixtures. The MEDIUM-confidence port 27043 is encoded with explicit KDoc disclaimers, within the anti-verarschen bounds defined by the canonical-sources doc.

---

## Pillar 3 — Cross-Cutting #1 Evidence-Key Namespacing

**Verdict: APPROVE**

Methodology: Reviewed `CoverageMatrixGeneratorTest.kt` to determine whether it loads real probes (it does — it imports and instantiates all 81 production probe classes directly and calls `ProbeRunner.runAll()`). Reviewed the evidence-key namespace fix commit (fa35fe3).

Findings:

1. The cross-cutting #1 fix (`fa35fe3 — fix(detection): namespace pkg.* evidence keys per probe`) was committed BEFORE the audit range (`a259e40..HEAD`). Confirmed via `git log --oneline` — fa35fe3 is not present in `a259e40..HEAD`, meaning the fix predates this Power-15 work.

2. The fix applies the namespacing convention to the three probes that emitted overlapping bare `pkg.<id>` keys:
   - `SuDetectionProbe`: `pkg.<id>` → `su_search.pkg.<id>`
   - `XposedLsposedProbe`: `pkg.<id>` → `xposed.pkg.<id>`
   - `InstalledAppsProbe`: `pkg.<id>` → `installed_apps.pkg.<id>`

3. No production source file in the audit range introduces new bare `pkg.*` evidence keys. Grep of `agents/detection/src/core/` and `agents/detection/src/` (excluding test directories and build artifacts) for `Evidence.*"pkg\.` returned no matches.

4. `CoverageMatrixGeneratorTest.kt` does not itself assert evidence-key prefix format — its contract is narrow (648-cell count + file-write determinism). Evidence-key correctness is enforced by the per-probe unit tests updated in fa35fe3, which are not in this audit range (pre-existing fix).

**Assessment**: Cross-cutting #1 is fixed and the fix predates the Power-15 commits under review. No regression introduced in this range.

**Blockers**: 0

---

## Pillar 4 — Test-Fixture Leak into Production Binary

**Verdict: APPROVE**

Methodology: `grep -r` for `FridaInjectedRedroidSnapshot|NoxSnapshot|BlueStacksSnapshot|GenymotionSnapshot` in:
- `/home/coder/vk-repos/cloud-phone-research-planner/agents/detection/src/main/` — CLEAN (0 matches)
- `/home/coder/vk-repos/cloud-phone-research-planner/agents/detection-cli/src/main/` — CLEAN (0 matches)

The 4 new snapshot objects are referenced only from test-scope files:
- `CoverageMatrixGeneratorTest.kt` (test source set — `src/test/`)
- `FridaDetectorReplayTest.kt` (test source set — `src/test/`)

Both files are correctly placed in `src/test/kotlin/` and will not be compiled into the production binary or CLI artifact.

Additionally, the snapshot files themselves (`FridaInjectedRedroidSnapshot.kt`, `NoxSnapshot.kt`, `BlueStacksSnapshot.kt`, `GenymotionSnapshot.kt`) are located in:
- `agents/detection/src/test/kotlin/com/detectorlab/core/replay/`

This is the test source root, not the main source root. They will not appear in any production JAR, AAR, or CLI distribution.

**Blockers**: 0

---

## Pillar 5 — License Compliance

**Verdict: APPROVE (with informational note)**

Methodology: Reviewed the 26 URLs in §D of `power-15-canonical-sources.md` and extracted all fenced code blocks from that document to assess whether verbatim multi-line code from external repositories was reproduced.

The document contains 5 fenced code blocks from external sources:

**Block 1** (`/proc/self/maps` example line, 3 lines):
Reconstructed example format — not verbatim from any specific source file. Shows how mmap entries appear; the format is documented public knowledge (Linux proc(5) man page format).

**Block 2** (DetectFrida `native-lib.c` C constants, 4 lines):
```c
static const char *FRIDA_THREAD_GUM_JS_LOOP = "gum-js-loop";
static const char *FRIDA_THREAD_GMAIN = "gmain";
static const char *PROC_TASK = "/proc/self/task";
static const char *PROC_STATUS = "/proc/self/task/%s/status";
```
Source: `github.com/darvincisec/DetectFrida` (MIT License per repo). 4-line constant declaration block. This is a research audit document (not distributed software); quotation of 4 lines for citation/evidence purposes is standard fair use in security research documentation. The document explicitly attributes the source URL.

**Block 3** (DetectFrida `native-lib.c` if-statement, 4 lines):
```c
if (my_strstr(buf, FRIDA_THREAD_GUM_JS_LOOP) ||
    my_strstr(buf, FRIDA_THREAD_GMAIN)) {
    // Frida detected
}
```
Same source, same license. 4-line excerpt. Same fair-use assessment.

**Block 4** (`/proc/net/tcp` example line, 1 line):
Reconstructed example — not from any specific source file. Standard Linux kernel /proc/net/tcp format.

**Block 5** (DetectFrida `native-lib.c` linjector constants, 6 lines):
```c
static const char *FRIDA_NAMEDPIPE_LINJECTOR = "linjector";
static const char *PROC_FD = "/proc/self/fd";
// ...
if (NULL != my_strstr(buf, FRIDA_NAMEDPIPE_LINJECTOR)) {
    // Frida detected
}
```
Same source (MIT License), same attribution. 6-line excerpt.

**Assessment**: All external code quotations are small (4–6 lines), explicitly attributed with source URLs, and from MIT-licensed open-source repositories. No verbatim reproduction of substantial portions (function implementations, class bodies, algorithm logic) was found. No GPL-licensed code is quoted verbatim. This is within the bounds of security research citation.

**Informational note**: The owner should be aware that `DetectFrida` source is quoted verbatim (MIT license). MIT permits this without restriction. No action required; noting for completeness.

**Blockers**: 0
**Warnings**: 0

---

## Pillar 6 — `.gitignore` Regression

**Verdict: APPROVE**

Methodology: Read the full `.gitignore` diff from commit `d11020c` and the complete resulting file.

The diff adds exactly 4 lines at the end of `.gitignore`:

```
# Claude Code session-local state
.claude/scheduled_tasks.lock
.claude/scheduled_tasks.json
```

Assessment:

1. **Additive only**: The additions are appended at the end of the file. No existing ignore rules were removed or modified.

2. **No un-ignore regression**: The pre-existing `.gitignore` already ignores `.env`, `.env.local`, `secrets.yml`, `**/keybox*.xml`, `**/keybox*.bin`, `experiments/runs/**/*.json`, `audit-reports/`, and `results/`. The new `.claude/scheduled_tasks.*` entries do not override or negate any of these entries.

3. **Correct scope**: `.claude/scheduled_tasks.lock` and `.claude/scheduled_tasks.json` are Claude Code session-local cron state files, not project artifacts. Excluding them from git is correct practice. They are not sensitive themselves, but committing them would cause spurious dirty-tree noise.

4. **No blanket `.claude/` ignore**: The addition does NOT add a wildcard `.claude/` ignore pattern that could accidentally un-ignore project-relevant `.claude/` content (CLAUDE.md, plans, memory). Only the two specific session-local filenames are targeted.

5. **`git check-ignore` verification**: Running `git check-ignore -v .claude/scheduled_tasks.lock` and `.json` confirms both are matched by the new rules. The `.claude/` directory itself is NOT globally ignored.

**Blockers**: 0

---

## Overall Verdict

**SECURITY_APPROVE_PHASE_A**

| Pillar | Verdict | Blockers | Warnings |
|--------|---------|---------|---------|
| 1. Credentials / Secrets | APPROVE | 0 | 0 |
| 2. Fabricated-value audit | APPROVE | 0 | 0 |
| 3. Cross-cutting #1 namespace | APPROVE | 0 | 0 |
| 4. Test-fixture leak | APPROVE | 0 | 0 |
| 5. License compliance | APPROVE | 0 | 0 (INFO: MIT quoted verbatim, attributed, within research fair use) |
| 6. `.gitignore` regression | APPROVE | 0 | 0 |

**Total blockers**: 0
**Total warnings**: 0

### Summary

The 6 commits in `a259e40..HEAD` are clean across all 6 audit pillars:

- No credentials, secrets, or sensitive data introduced.
- All 7 §C GAP items are correctly NOT encoded in fixtures. The MEDIUM-confidence port 27043 is the sole non-HIGH value encoded, with explicit KDoc disclaimers that match the anti-verarschen framing in the canonical-sources doc.
- Cross-cutting #1 evidence-key namespacing was fixed before this range (commit fa35fe3); no regression introduced.
- All 4 new snapshot fixtures are confined to the test source root (`src/test/`); no leak into production binaries confirmed.
- External code quotations are small (4–6 lines), MIT-licensed, and explicitly attributed.
- `.gitignore` additions are minimal, additive-only, and correctly scoped.

**DEFERRED_TO_OWNER**: None. All items were fully auditable from the diff and source.

---

*Audit produced by claude-sonnet-4-6 (Security Auditor), 2026-05-21. Anti-verarschen mandate: no finding claimed without direct evidence.*

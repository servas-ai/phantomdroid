# Security Endgate — Phase 4 Live Execution (2026-05-29)

**Auditor:** ralph-security v3.0
**Date:** 2026-05-29
**Scope:** Phase 4 live execution security review — `l0b-probe` throwaway container
(Magisk-rooted ReDroid 12, PAR822349). Read-only audit; no code or container
mutations performed.
**Source of truth:** `audit/phase4-live-spoof-delta-2026-05-29.md`, `audit/SESSION-E2E-2026-05-29.md`,
`p21/l0b-probe-spoofed-2026-05-29.yml`, `p21/l0b-probe-unspoofed-2026-05-29.yml`,
`audit/phase4-root-method-2026-05-29.md`, `audit/spoof-stack/endgate-phase3-security-2026-05-29.md`.

---

## VERDICT: APPROVE-WITH-CONDITIONS

Phase 4 live execution is approved as a completed defensive-lab measurement.
Three conditions must be actioned before the next phase or any promotion of
l0b-probe configuration to the hardened compose path:

- **CONDITION-1 (BLOCKING for any further l0b live work):** Tear down `l0b-probe`
  immediately via `docker rm -f l0b-probe && rm -rf /tmp/l0b-probe-data`.
  A `--privileged` + seccomp-disabled container serving a rooted Android
  environment MUST NOT remain running once measurement collection is complete.
- **CONDITION-2 (BLOCKING for L0b promotion):** Re-run the root experiment under the
  hardened compose posture (`L0b.compose.yml` + `redroid-seccomp.json`) to answer
  the `--setup-sbin` tmpfs-vs-seccomp OPEN QUESTION documented in §3 of the
  phase4 report. No L0b promotion to the official stack is valid while this is
  unresolved.
- **CONDITION-3 (STANDING):** Owner must rotate the `paris` SSH password on
  PAR822349 and perform `git filter-repo` history rewrite (S-01 from Phase 3
  endgate). This remains OPEN and is pre-condition for any public sharing of
  the repository or its git history.

---

## Findings Table

| ID | Severity | Pillar | Finding | Status |
|---|---|---|---|---|
| P4-S-01 | CRITICAL | LIVE-ACCESS | `l0b-probe` is documented as LEFT RUNNING (`audit/phase4-live-spoof-delta-2026-05-29.md:246: "l0b-probe LEFT RUNNING for inspection"`). A `--privileged` + `SecurityOpt=[label=disable]` (no seccomp, no AppArmor) container running a Magisk-rooted Android 12 environment is an elevated-risk persistent process on PAR822349. Every minute it remains live extends the window for: (a) escape via CAP_SYS_ADMIN if the Android guest achieves native code exec, (b) unauthorized ADB access (port 15556 is loopback-bound — but the host is accessible via SSH to anyone with `paris` credentials, which remain unrotated per S-01). | OPEN — requires immediate teardown |
| P4-S-02 | HIGH | SECRETS | `.env` at repo root contains live PAR822349 credentials (`paris` username, SSH password `<REDACTED-ssh-pw-see-.env>`, panel password `<REDACTED-panel-pw-see-.env>`, public IPv4 `195.154.209.133`, server panel URL). The `.env` file is in `.gitignore` and confirmed NOT tracked by git (verified: `git ls-files --error-unmatch .env` exits 1). However: (a) the SSH password `<REDACTED-ssh-pw-see-.env>` still exists verbatim in git history at commit 896cd71 (S-01 from Phase 3 endgate — STILL OPEN, no filter-repo performed); (b) the panel password `<REDACTED-panel-pw-see-.env>` is not in git history (verified `git log -S <REDACTED-panel-pw-see-.env>` returns no results) but IS in the untracked `.env` on this VM; (c) `SESSION-E2E-2026-05-29.md` and `endgate-phase3-security-2026-05-29.md` quote the SSH credential inline as audit references — these are untracked/new files, acceptable as references in audit docs, but care must be taken not to commit them while the credential is un-rotated. | OPEN — S-01 history rewrite still pending |
| P4-S-03 | HIGH | SUPPLY-CHAIN | The build used `ayasa520/redroid-script` at a pinned commit (`881f7f00d6a86af4f8e4947af5d587a144a1806c`) and downloaded Magisk Delta v30.6 (`ayasa520/Magisk fork`, `app-debug.apk`, MD5 `77ef9f3538c0767ea45ee5c946f84bc6`). Three supply-chain concerns: (a) **MD5 is an integrity check, not a provenance proof** — it confirms the file was not corrupted in transit but does not prove the binary matches any publicly audited Magisk source tree; (b) `app-debug.apk` is a debug-signed build, not a release-signed artifact — the signing key and build pipeline of the HuskyDG/ayasa520 Magisk Delta fork are not publicly audited; (c) the build container had the **host Docker socket mounted** (`python:3.11-slim` with `-v /var/run/docker.sock:...`), meaning the third-party `redroid.py` script had full Docker daemon access on PAR822349 during the build run. This is a high-trust position for an unaudited third-party script. | CONDITIONAL — owner accepted for this throwaway run; requires SHA-pinning and provenance review before any re-run |
| P4-S-04 | HIGH | CONTAINMENT | `l0b-probe` runs `--privileged` with `SecurityOpt=[label=disable]` (seccomp DISABLED, AppArmor DISABLED). This deviates from the hardened L0b posture (`L0b.compose.yml`: `cap_drop:[ALL]`, `seccomp=redroid-seccomp.json`, `no-new-privileges:true`). The deviation was plan-authorized for this throwaway, and the ADB port IS correctly bound to loopback only (`-p 127.0.0.1:15556:5555` — confirmed in §3 of the phase4 report). Risk is: the privileged posture gives the Magisk-rooted Android guest full kernel capability access. A malicious APK installed inside l0b-probe that achieves native code execution could escape the container namespace via `CAP_SYS_ADMIN` (mount bind to `/proc`, write to `/sys/kernel/...`). | CONDITIONAL — acceptable for throwaway measurement; NOT acceptable for any persistent cell |
| P4-S-05 | MED | CONTAINMENT | ADB port `127.0.0.1:15556:5555` is correctly loopback-bound (VERIFIED from `audit/phase4-live-spoof-delta-2026-05-29.md:72`). This matches the Phase 3 corrected posture and the L0b.compose.yml spec. No network exposure. PASS on ADB binding. | PASS |
| P4-S-06 | MED | SUPPLY-CHAIN | Host persistence from the build: `lzip` package was installed on PAR822349 host (`apt-get install -y lzip 1.20-1` per §1 of phase4 report). This is a system-level package installed permanently on the host. It is a low-risk utility (a compression tool), but represents a host mutation that is not tracked anywhere in the repo and was not in the pre-flight state. Additionally: `/tmp/redroid-script` (the cloned third-party script), `/tmp/l0b-probe-data` (container data volume), `/tmp/*.zip` (module zips), `/tmp/l0b-unspoofed-report.json`, `/tmp/l0b-spoofed-report.json`, and `/tmp/magisk-data-stage` were all written to the host `/tmp`. These are ephemeral and listed in the documented teardown command, but have NOT been confirmed cleaned up (l0b-probe is still running). The rooted image `redroid/redroid:12.0.0_magisk` (`ba09a823a823`, 1.99 GB) remains in the Docker image store on PAR822349. | OPEN — cleanup pending teardown |
| P4-S-07 | MED | OPEN-QUESTION | `--setup-sbin` vs `redroid-seccomp.json` is explicitly flagged as UNTESTED/SIDESTEPPED in the phase4 report (§3 and §8). The seccomp profile allows `mount` only with `MS_BIND`; `magisk --setup-sbin` requires a tmpfs mount (non-bind). Whether Magisk root survives under the hardened seccomp profile is an OPEN QUESTION. This must be resolved empirically — on a throwaway cell under `L0b.compose.yml` posture — before any claim that the hardened stack supports L0b root is valid. | OPEN — must be tested in the hardened re-run |
| P4-S-08 | LOW | SECRETS | The `audit/SESSION-E2E-2026-05-29.md` file contains the string `<REDACTED-ssh-pw-see-.env>` inline (as a quoted reference in the security note). This file is currently UNTRACKED (in git status as `??`). If it is committed without redaction, it will reintroduce the credential into tracked history — exactly what the Phase 3 working-tree scrub was meant to prevent. The string appears only as a reference in the security audit note, not as an operational credential, but the file should be reviewed before committing to ensure the reference is needed or can be paraphrased as "the credential cited in S-01 at commit 896cd71". | OPEN — pre-commit check required |
| P4-S-09 | LOW | SUPPLY-CHAIN | The rooted image `redroid/redroid:12.0.0_magisk` is confirmed local-only (no `docker push` command appears anywhere in the phase4 documentation). The report explicitly says "Output image: `redroid/redroid:12.0.0_magisk` (`ba09a823a823`, 1.99 GB)" with no push step. The image digest and existence on PAR822349 are the only residuals. | PASS — image is local-only |
| P4-S-10 | INFO | RESEARCH-BOUNDARY | Phase 4 deliverables (two YAML snapshots, one delta report) document detection signal changes inside an owned lab container. The language is consistently framed as measurement delta (score tables, per-probe findings, HONEST-LIMITED disclaimers on residuals). No document instructs how to use the spoof result against a production service. The `HONEST RESIDUALS` section of `l0b-probe-spoofed-2026-05-29.yml` explicitly documents what was NOT hidden. Research boundary: PASS. | PASS |

---

## Detailed Findings

### P4-S-01: l0b-probe Still Running — Tear Down Now (CRITICAL)

The phase4 report documents at §9:

> Teardown (documented; l0b-probe LEFT RUNNING for inspection):
> `docker rm -f l0b-probe && rm -rf /tmp/l0b-probe-data`

"For inspection" is not a time-bounded justification. The container provides no
ongoing measurement value — both snapshots are captured and committed
(`p21/l0b-probe-unspoofed-2026-05-29.yml`, `p21/l0b-probe-spoofed-2026-05-29.yml`).
Measurement is complete.

**Why this is CRITICAL:** A privileged ReDroid container with Magisk root means the
Android runtime inside has unrestricted root (`su 0` works, `/sbin/magisk` is live).
Any APK that achieves native code execution inside this container has the full
kernel capability set of the host's Docker daemon on PAR822349. The ADB loopback
binding (`127.0.0.1:15556`) reduces the direct external attack surface, but the
host itself is reachable over SSH with credentials that remain unrotated (S-01,
P4-S-02). Prolonged operation of this container creates a residual risk window
with no measurement benefit.

**Recommended action:** Execute immediately after inspection is complete:
```
docker rm -f l0b-probe
rm -rf /tmp/l0b-probe-data
```

Optional (when the rooted image is no longer needed for re-run prep):
```
docker rmi redroid/redroid:12.0.0_magisk
rm -rf /tmp/redroid-script /tmp/magisk-data-stage /tmp/*.zip
```

### P4-S-03: Docker Socket Mounted in Build Container (HIGH)

The build step ran the third-party `redroid.py` script inside a `python:3.11-slim`
container with the host Docker socket mounted. This means the `redroid.py` script —
from an unaudited third-party repository — had full Docker daemon API access on
PAR822349 during the build. The pinned commit
(`881f7f00d6a86af4f8e4947af5d587a144a1806c`) and the inspectable open-source code
at that commit reduce this risk, but mounting the Docker socket is structurally
equivalent to granting root on the host to the script. For a one-time owner-approved
throwaway run this is an accepted known risk. It must not become the standard pattern
for future runs.

**Hardened re-run path:** Build the rooted image using a dedicated build host or a
Docker BuildKit context that does NOT require the live socket. Alternatively, use
`docker buildx build` with an isolated buildkit instance, avoiding the socket mount
entirely for the `redroid.py` invocation step.

### P4-S-07: setup-sbin vs seccomp — The Key Open Item (MED)

The phase4 report is honest: seccomp was DISABLED (via `--privileged`) for l0b-probe,
so the `--setup-sbin` tmpfs survival question was never answered. From
`audit/phase4-root-method-2026-05-29.md:40`:

> `magisk --setup-sbin` tmpfs mount — AT RISK — profile allows `mount` only with
> MS_BIND; tmpfs is non-bind — may EPERM. MUST test on l0b-probe first.

This is the single most important open technical question before any L0b promotion
to the hardened compose. The current result (root works) is only valid under the
`--privileged` posture. Before claiming the hardened L0b stack supports Magisk root,
the following sequence must be run:

1. Build the same `redroid/redroid:12.0.0_magisk` image.
2. Launch it via `L0b.compose.yml` (NOT `--privileged`; with `redroid-seccomp.json`
   applied and `cap_drop:[ALL]` + narrow cap-add).
3. Test `adb -s 127.0.0.1:15556 shell /sbin/magisk -v` — if this returns
   `30.6:MAGISK:D`, the seccomp profile is compatible with `--setup-sbin`.
4. If it fails with EPERM, the seccomp profile requires a targeted amendment
   to allow tmpfs mounts from within the container — to be board-reviewed, NOT
   resolved by disabling seccomp globally.

### P4-S-08: SESSION-E2E Contains Credential Reference (LOW)

`audit/SESSION-E2E-2026-05-29.md` line 41 contains the string `<REDACTED-ssh-pw-see-.env>`
quoted inline. This file is currently UNTRACKED. If committed as-is, it reintroduces
the credential string into tracked history (a new commit, not filtered by the
planned `git filter-repo` on 896cd71). The reference is clearly contextual (it is
citing the audit finding), but to be safe the string should be redacted to
"the credential cited in S-01 / commit 896cd71" before committing this file.

---

## Quality Pillars Assessment

| Pillar | Verdict | Notes |
|---|---|---|
| 1. THREAT MODEL | CONDITIONAL | No new persistent trust boundaries. The `--privileged` throwaway is a temporary elevated-risk cell, not a new permanent surface. The STRIDE threat of container escape via CAP_SYS_ADMIN is real but bounded to the teardown window. Teardown closes this threat immediately. |
| 2. CODE AUDIT | PASS | No new code was introduced by Phase 4. The two YAML snapshots and three audit documents are data and prose only. The module zips exist only on the server `/tmp`, not in the repo. |
| 3. SECRETS | CONDITIONAL | Working tree clean of the SSH credential (confirmed: no matches in any new Phase 4 committed/tracked files except the reference in SESSION-E2E which is currently untracked). Git history S-01 at 896cd71 remains OPEN — password rotation and filter-repo still required. Panel password `<REDACTED-panel-pw-see-.env>` is in the untracked `.env` only — not in git. ADB is loopback-bound, no network credential exposure. Phase 4 docs do not introduce new credentials. |
| 4. DEPENDENCIES | CONDITIONAL | Third-party supply chain: `ayasa520/redroid-script` SHA-pinned at `881f7f00`; Magisk Delta v30.6 MD5-pinned at `77ef9f35`. MD5 is an integrity check only, not a provenance proof. The Docker socket was mounted during build (HIGH concern for future runs). The rooted image is local-only (not pushed). The `python:3.11-slim` build deps (`requests==2.28.1`, `tqdm==4.64.1`) are pinned by version but not by hash. |
| 5. PLAN REVIEW | PASS | Phase 4 executed exactly what the plan authorized: a single throwaway `l0b-probe` with owner-approved `--privileged` posture, ADB loopback-bound, baseline `redroid-test` untouched (verified Up 2h at start → Up 3h at end). The seccomp OPEN QUESTION is correctly flagged, not silently resolved. The measurement result is honestly reported including all negative residuals. |
| 6. HOOKS INTEGRITY | PASS | No hooks or settings files were modified in Phase 4. Existing security hooks remain registered and functional. No changes to `.claude/settings.json` or equivalent hook configuration files. |

---

## Hardened Re-Run Path (for L0b Promotion)

When the owner wants to repeat the experiment under the hardened posture:

1. **Tear down l0b-probe** (P4-S-01 — do this first, immediately).
2. **Rotate `paris` password on PAR822349** and perform `git filter-repo` on
   commit 896cd71 (S-01 / P4-S-02).
3. **Eliminate the Docker socket mount** from the redroid-script build step
   (P4-S-03 — use an isolated BuildKit instance).
4. **Build the same rooted image** (`redroid/redroid:12.0.0_magisk`) using the
   pinned commit `881f7f00`.
5. **Launch via `L0b.compose.yml`** (NOT `--privileged`; with `redroid-seccomp.json`
   and `cap_drop:[ALL]`).
6. **Test `--setup-sbin` survival** under seccomp (P4-S-07): if `magisk -v`
   succeeds, the hardened stack is proven; if EPERM, draft a targeted seccomp
   amendment for board review.
7. **If proven**: re-run the 65-probe delta measurement under the hardened posture
   and update the spoof delta report with the hardened-posture result.

---

## l0b-probe Residual Risk Summary

| Risk | Severity | Mitigated by | Residual |
|---|---|---|---|
| Container escape via CAP_SYS_ADMIN | HIGH | Measurement complete; no active use | Teardown closes entirely |
| Unauthorized ADB access | MED | 127.0.0.1:15556 loopback binding | Closed once container is down |
| Rooted image on host Docker store | LOW | Image is local-only, not pushed | Delete with `docker rmi` |
| `/tmp` artifacts (script, zips, data) | LOW | Cleanup command documented | Execute with teardown |
| `lzip` installed on host | LOW | System-level utility, no attack surface | Non-actionable; acceptable |

**Bottom line on teardown:** Execute `docker rm -f l0b-probe && rm -rf /tmp/l0b-probe-data` now. Every hour this container remains running is an unnecessary extension of the highest-privilege surface in the lab environment.

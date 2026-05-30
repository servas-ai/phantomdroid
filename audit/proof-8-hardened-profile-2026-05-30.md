# Proof 8 — Phase-6 Hardened Seccomp Profile Verification

**Date:** 2026-05-30
**Verifier:** ralph-security (PhantomDroid E2E proof, slice 8 of 100%)
**Scope:** Local, read-only validation. No containers run, no host changes.
**Artifacts under test:**
- `agents/stability/stack/seccomp/redroid-seccomp-l0b.json` (proposed L0b profile)
- `agents/stability/stack/seccomp/redroid-seccomp.json` (production, must be untouched)
- `audit/phase6-hardened-boot-2026-05-30.md` (the claims being checked)

---

## HEADLINE VERDICT

🟢 **PASS** on all three mechanical gates:

| Gate | Result |
|---|---|
| (1) `redroid-seccomp-l0b.json` is valid JSON | ✅ PASS (`json.load` clean) |
| (2a) Diff additions are ONLY the documented syscalls; nothing dangerous silently added | ✅ PASS |
| (2b) Production `redroid-seccomp.json` is git-unchanged | ✅ PASS |
| (3) Diff matches `phase6-hardened-boot-2026-05-30.md` §2c claimed final set | ✅ PASS (one cosmetic wording nuance, non-substantive) |

**Security verdict on promotability:** ⚠️ **PROMOTABLE ONLY WITH BOARD SIGN-OFF AND ONE RECOMMENDED TIGHTENING** — the profile is honest, correctly scoped, and enforcing, but it re-permits `setns` (the single highest-risk item) which should be omitted unless Magisk per-process mount-ns isolation is a hard requirement. See §4.

---

## 1. JSON validity (Gate 1)

```
python3 -c "import json; json.load(open('agents/stability/stack/seccomp/redroid-seccomp-l0b.json'))"
→ VALID JSON
```
`defaultAction = SCMP_ACT_ERRNO` (deny-by-default / allowlist style) is preserved in the L0b
profile — it is still an *enforcing* profile, not allow-all. ✅

---

## 2. Diff: L0b vs production (Gate 2)

Computed by parsing both files and comparing the full syscall→(action,args) maps.

### 2a. Syscall-set delta
- Production: **369** distinct syscalls allowed. L0b: **372**. Net **+3** names.
- **Added (newly ALLOWED, absent in production):** `arch_prctl`, `personality`, `setns` — exactly
  the three Phase-5/6 documented additions, all unconditional `SCMP_ACT_ALLOW` in the new
  `PHASE5-AMENDMENT` group (group index 0).
- **Removed:** none.
- **Action/arg changes on common syscalls:** none — except `mount` (below).

### 2b. The `mount` re-widening (the 4th documented change — verified, NOT a hidden addition)
`mount` was already present in production, but **arg-filtered**:
```
production: ALLOW mount  ONLY when arg[3] (mountflags) MASKED_EQ 0x1000 (MS_BIND)   → bind-mounts only
l0b       : ALLOW mount  UNCONDITIONALLY (group 0, no args)  +  the old MS_BIND group still present
```
Because seccomp evaluates an unconditional ALLOW match for `mount`, the L0b profile **effectively
permits `mount` with ANY flags** (remount, MS_RDONLY clearing, arbitrary fstypes), not just
MS_BIND. This is a genuine privilege widening — broader than "one new syscall" — and the report
**does disclose it accurately** (see §3). Flagged here so the board is not misled by the
"+3 syscalls" headline: it is +3 syscalls **and** a mount arg-filter removal.

### 2c. No dangerous syscall silently added
All 16 high-risk syscalls the report claims remain denied were verified DENIED-by-default in BOTH
profiles (identical posture):
`process_vm_readv`, `process_vm_writev`, `init_module`, `finit_module`, `delete_module`,
`kexec_load`, `kexec_file_load`, `reboot`, `pivot_root`, `swapon`, `swapoff`, `iopl`, `ioperm`,
`sysfs`, `setdomainname`, `sethostname`.
`ptrace` stays restricted to `PTRACE_TRACEME` (arg[0]==0) in both — unchanged. ✅
Cross-check "any ALLOW in L0b not present anywhere in production" → only `arch_prctl`,
`personality`, `setns`. **No silent additions.** ✅

### 2d. Production profile git-unchanged (Gate 2b)
```
git status --porcelain agents/stability/stack/seccomp/redroid-seccomp.json  → (empty)
git diff --stat       agents/stability/stack/seccomp/redroid-seccomp.json   → (empty)
git ls-files          agents/stability/stack/seccomp/redroid-seccomp.json   → tracked
```
The production profile is tracked and shows **zero modifications**. The L0b file is a separate
untracked artifact. ✅ The report's repeated "production UNTOUCHED" claim holds.

---

## 3. Cross-check vs `phase6-hardened-boot-2026-05-30.md` (Gate 3)

| Report claim | Verified? |
|---|---|
| §2c: only ONE added ALLOW group; `defaultAction` stays `SCMP_ACT_ERRNO` | ✅ exactly group 0, default unchanged |
| §2c table: `arch_prctl`, `personality`, `setns` denied→ALLOW; `mount` MS_BIND→unfiltered | ✅ matches diff precisely |
| §2c: "Everything else byte-identical; process_vm_* stay REMOVED; ptrace TRACEME-only; init_module/kexec_*/reboot/pivot_root/swap*/iopl/ioperm/sysfs/set{domain,host}name stay DENIED" | ✅ all confirmed |
| §6: production profile UNTOUCHED | ✅ git-clean |
| §2 final config: caps = curated 14 + 11 added (SYS_ADMIN, BLOCK_SUSPEND load-bearing); `--device /dev/ashmem`; `--device-cgroup-rule "a *:* rwm"`; `systempaths=unconfined`; `no-new-privileges` DROPPED | These are runtime `docker run` claims, NOT encoded in the seccomp JSON — not independently re-runnable in this read-only slice. The seccomp-encodable subset all matches. Caps/devices/no-new-privileges are accepted on the report's own evidence (LOG-mode exoneration + live boot proofs), consistent and internally coherent. |

**One cosmetic nuance (non-substantive):** Report §1/§2c table calls `personality`/`setns`
production status "explicit-deny list". Mechanically they were denied by **default action**
(not enumerated in any per-syscall deny group). The security effect (denied→allowed) is correctly
stated, and the L0b file's own metadata gets the wording exactly right ("previously DENIED in the
production profile"). No impact on the verdict.

**Self-documentation (a strength):** the L0b JSON carries `_comment_purpose` (labels itself a
PROPOSAL, not production), `_proposal_provenance` (points to this Phase-6 report + Phase-5 §4),
an updated `_comment_hardening` explicitly flagging the personality/setns/mount widening with
`PHASE6-NOTE`s, and a `_comment_compliance` line demanding board review before promotion. The
artifact does not overstate its own safety.

---

## 4. Security trade-off assessment — is the board guidance sound?

The report's §5 board guidance is **honest and largely sound**. My independent assessment:

| Item | Report's call | My verdict |
|---|---|---|
| `arch_prctl` | Low, keep blanket | **Agree.** TLS FS/GS base for the calling thread only; mandatory for any Bionic userspace; in Docker's default profile. No meaningful attack surface. |
| `personality` | Medium; prefer arg-filtered allowlist `{0,0x8,0x20000,0x20008,0xffffffff}` so a process cannot self-clear ASLR | **Agree, and this should be a promotion blocker, not just a "prefer".** Blanket `personality` lets in-container code set `ADDR_NO_RANDOMIZE` (disable ASLR) / `READ_IMPLIES_EXEC` on itself, weakening exploit mitigations for any post-compromise payload. The arg-filtered form is the correct posture and the report already names the exact mask. Recommend: arg-filter before promotion. |
| `setns` | HIGHEST; **recommend OMIT** unless Magisk per-process mount-ns isolation is required; keep only with board sign-off | **Strongly agree — this is the load-bearing risk.** `setns` is namespace re-entry; the production profile's own design removed it precisely to stop an escaped uid attaching to host namespaces. It is bounded here by no `--pid=host`/`--network=host`, but combined with `CAP_SYS_ADMIN` + unconfined AppArmor + unmasked `/proc`,`/sys` + dropped `no-new-privileges`, the defense-in-depth margin is thin. The report's "omit unless required" is the right default. **Do not promote with `setns` enabled by default.** |
| `mount` unfiltered | Medium; keep, bounded by cap/ns scope; seccomp cannot filter fstype (pointer arg) | **Agree on the technical constraint** (seccomp genuinely cannot inspect the fstype string pointer, so MS_BIND-only is incompatible with the required tmpfs/proc mounts — there is no tighter seccomp-only middle ground). The residual risk (arbitrary fstype mounts in the container's own mount ns) is real but bounded; `pivot_root`/`swapon` stay denied. Acceptable **only** in combination with the cap/ns bounding the report documents. |
| Dropped `no-new-privileges:true` | Hard finding: incompatible with Magisk init; had to be dropped | **Agree it is a real incompatibility, and the report is commendably blunt that this is a material hardening regression.** This is the most consequential trade-off in the whole proposal — `no-new-privileges` is a cheap, high-value mitigation, and losing it means any setuid/fcaps path inside the container can escalate. The report's §5 "bigger-picture" paragraph correctly frames the net result: **a Magisk-rooted L0b is materially less hardened than the unrooted L0a/L1 cells.** |

**Board-guidance soundness:** ✅ The report does not overclaim. It explicitly states seccomp was
never disabled for a verdict, distinguishes the seccomp delta from the (larger) cap/device/
no-new-privileges relaxations, ranks `setns` as highest risk and recommends omitting it, and
recommends arg-filtering `personality`. The honest framing in §5 ("the board should weigh whether
the rooted L0b's spoof value justifies this reduced isolation, or whether rooted cells stay on a
dedicated, more-isolated host tier") is exactly the right decision to surface to a board.

---

## 5. PROMOTABILITY VERDICT

⚠️ **CONDITIONAL — not promotable as-is to a shared/general host tier; promotable to an
isolated rooted-cell tier with two conditions.**

**Conditions before promotion (in priority order):**
1. **Drop `setns` from the default profile** (per the report's own §5 recommendation) unless
   Magisk per-process mount-ns isolation is a proven hard requirement. If retained, require
   explicit, logged board sign-off.
2. **Arg-filter `personality`** to the Docker-standard mask so a compromised process cannot
   self-disable ASLR. The report already specifies the exact values; re-test on a throwaway
   (the report acknowledges only the blanket allow was end-to-end validated).

**Accepted-with-bounding (do not block promotion):** `arch_prctl` blanket allow, `mount`
unfiltered (no tighter seccomp-only option exists), and the cap/device widening — all bounded by
the documented no-`--privileged`/no-`--pid=host`/no-`--network=host` posture.

**Hard board decision (outside seccomp scope):** the dropped `no-new-privileges:true` plus
`systempaths=unconfined` plus `CAP_SYS_ADMIN` make the rooted L0b a distinctly weaker isolation
class than L0a/L1. Rooted cells should run on a dedicated, more-isolated host tier, not co-resident
with unrooted production cells. This is a posture/placement decision, not a profile fix.

---

## 6. Evidence summary

- JSON valid; `defaultAction=SCMP_ACT_ERRNO` preserved.
- Diff = exactly `+arch_prctl, +personality, +setns` (new ALLOW) + `mount` MS_BIND→unfiltered;
  zero removals; zero changes to the 16 verified dangerous-syscall denials; ptrace unchanged;
  no silent additions.
- `agents/stability/stack/seccomp/redroid-seccomp.json` git-unchanged (tracked, empty diff).
- Report §2c seccomp claims match the diff byte-for-byte (one cosmetic "explicit-deny" wording
  nuance, non-substantive).
- Board guidance (§5) is honest, correctly risk-ranks `setns` highest, and does not overstate
  safety.

**Files (absolute):**
- `/home/coder/vk-repos/phantomdroid/agents/stability/stack/seccomp/redroid-seccomp-l0b.json`
- `/home/coder/vk-repos/phantomdroid/agents/stability/stack/seccomp/redroid-seccomp.json`
- `/home/coder/vk-repos/phantomdroid/audit/phase6-hardened-boot-2026-05-30.md`
- `/home/coder/vk-repos/phantomdroid/audit/proof-8-hardened-profile-2026-05-30.md` (this report)

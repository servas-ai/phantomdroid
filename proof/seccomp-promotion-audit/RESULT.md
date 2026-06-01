# Seccomp Promotion-Readiness Security Audit — `redroid-seccomp-l0b.json`

> **STATUS: PROMOTED 2026-06-01.** This audit's §5 arg-filter recommendations were APPLIED (commit bded617) and adversarially boot+root+negative-test re-validated (see `APPLIED.md`); the profile is now the board-approved PINNED PRODUCTION L0b seccomp profile (sha256 `d317a7a3…ada66a`, pinned in `agents/stability/stack/image-pins.yml` key `seccomp_l0b_production`). The "PROPOSAL ARTIFACT / board review required" descriptions below are the historical audit-time state and are retained verbatim for provenance.

**Audit type:** STATIC (no container boot; profile NOT modified). Boot evidence already
exists in `audit/phase6-hardened-boot-2026-05-30.md`.
**Auditor:** BUILDER sub-agent (B1-validator follow-up #2)
**Repo:** `git@github.com:servas-ai/phantomdroid.git` — branch `session/e2e-2026-05-30`
**Subject:** `agents/stability/stack/seccomp/redroid-seccomp-l0b.json` (PROPOSAL ARTIFACT,
self-labelled "board review required before promotion")
**Baseline of comparison:** `agents/stability/stack/seccomp/redroid-seccomp.json` (production-pinned, CLO-61)

---

## 0. Executive summary / verdict

**Recommendation: PROMOTE-AFTER-ADDING-RECOMMENDED-ARG-FILTERS (conditional), with a mandatory boot re-test.**

The profile is a genuinely-hardened, deny-by-default allowlist. The 3 *named* re-permitted
syscalls (`arch_prctl`, `personality`, `setns`) are real, load-bearing Android/Magisk
requirements and the name-level diff matches the established fact **exactly**. The two strongest
restrictions advertised in the hardening comment **HOLD**: `process_vm_readv/writev` are absent
from every allow group, and `ptrace` is arg-filtered to `request==0` (PTRACE_TRACEME) only.

However the audit surfaced **two honest discrepancies the board must weigh before promotion**:

1. **`mount` is now effectively UNFILTERED (a hardening regression hidden by the name-level diff).**
   The l0b profile keeps the base profile's MS_BIND-only arg-filtered `mount` rule **and** adds a
   *second, unconditional* `mount` ALLOW (in the PHASE5-AMENDMENT group). Because a seccomp filter
   returns ALLOW if **any** rule matches, the unconditional rule **supersedes** the MS_BIND mask —
   `mount` is now allowed for **all** flags, not just `MS_BIND`. The "3 added, nothing removed"
   framing is true at the syscall-*name* level but understates the change: a previously-narrowed
   syscall became wide-open. This is documented in the profile's own comments (honest), but it is a
   real reduction in restriction and belongs in the promotion decision.

2. **The profile's own bounding comment is factually wrong about the cap set.** The
   PHASE5-AMENDMENT comment asserts `setns` is "Bounded by … cap_drop:[ALL] (no CAP_SYS_ADMIN in
   the curated set)". The cap set actually used by the boot path (`HARDENED_CAP_ADD` in
   `agents/orchestrator/src/container_lifecycle.py`, and `launch-l0b-hardened-spoof.sh`) **DOES
   include CAP_SYS_ADMIN** (plus SYS_MODULE, SYS_BOOT, SYS_PTRACE, NET_ADMIN). So the most
   reassuring sentence in the profile about its single riskiest syscall (`setns`) does not hold.
   The risk is therefore higher than the comment claims and must be re-assessed with CAP_SYS_ADMIN
   present (done below).

Neither finding is a boot blocker, and the profile is still far stronger than Docker-default or
`seccomp=unconfined`. But because of (1) and (2), this auditor does **not** recommend
"promote-as-is". The recommended path is to re-add the MS_BIND filter (drop the unconditional
`mount`) **only if** Phase-5's non-bind-mount requirement can be satisfied another way — and since
Phase 5 proved non-bind mounts are required (`magisk --setup-sbin` tmpfs, init proc/sysfs), the
honest recommendation is to **promote with the mount widening accepted-and-documented**, fix the
incorrect cap comment, and add the two low-cost arg-filters below (personality persona allowlist,
setns nstype allowlist) — each of which needs the board-gated boot re-test before it goes live.

---

## 1. Independently re-computed base → l0b syscall diff

**Method.** Parsed both JSON files, collected the union of `names` across every group whose
`action == "SCMP_ACT_ALLOW"` (set semantics), and diffed the two allow-name sets. Also separately
inspected per-syscall rule *shape* (args) for `mount` and `ptrace`, because a set-of-names diff
cannot see a filter being widened on a name that is already present.

```
defaultAction base: SCMP_ACT_ERRNO     l0b: SCMP_ACT_ERRNO   (deny-by-default, unchanged)
ADDED   (allow-name present in l0b, absent in base): ['arch_prctl', 'personality', 'setns']
REMOVED (allow-name present in base, absent in l0b): []
base allow-name count: 369     l0b allow-name count: 372   (+3)
```

**CONFIRMED:** at the syscall-name level the diff is **exactly** `{arch_prctl, personality, setns}`
added, **nothing removed**, `defaultAction` stays `SCMP_ACT_ERRNO`. The established fact holds.

**Caveat the name-diff hides (the 4th change).** The PHASE5-AMENDMENT group lists *four* names —
`arch_prctl, personality, setns, mount`. `mount` does **not** appear in the name-diff because it
was already an allowed name in the base. But its *rule shape* changed:

```
base mount rules:  [ ALLOW args=[index3 MASKED_EQ value=4096 valueTwo=4096] ]      # MS_BIND only
l0b  mount rules:  [ ALLOW args=null ,                                             # UNCONDITIONAL (new)
                     ALLOW args=[index3 MASKED_EQ value=4096 valueTwo=4096] ]      # MS_BIND (retained, now dead)
```

Seccomp evaluates all rules for a syscall and returns the highest-priority match; an
unconditional ALLOW always matches, so the retained MS_BIND filter is **inert**. Net effect:
`mount` went from MS_BIND-restricted to **fully unfiltered**. Report this as a 4th, sign-significant
delta even though it is name-invisible.

---

## 2. Verification of the two advertised hardening claims

| Claim (`_comment_hardening`) | Verified result | Evidence |
|---|---|---|
| (a) `process_vm_readv` / `process_vm_writev` are REMOVED from allow | **TRUE — HOLDS** | Neither name appears in any `SCMP_ACT_ALLOW` group in l0b (or base). Both are listed only in the `explicit-deny-documentation` group (names:[], denied via default). Programmatic check: `process_vm_readv in l0b allow = False`, `process_vm_writev in l0b allow = False`. |
| (b) `ptrace` restricted to PTRACE_TRACEME (request==0) via arg filter | **TRUE — HOLDS** | `ptrace` appears in exactly one ALLOW group with the arg filter below. PTRACE_TRACEME is request value `0`, so this is correct. PTRACE_ATTACH/PEEK/POKE (≠0) fall through to `SCMP_ACT_ERRNO`. The l0b ptrace rule is **byte-identical** to the base ptrace rule (no relaxation). |

Exact `ptrace` JSON in l0b (quoted):

```json
{
  "_group": "ptrace-TRACEME-only (request==PTRACE_TRACEME==0)...",
  "names": ["ptrace"],
  "action": "SCMP_ACT_ALLOW",
  "args": [
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 0 }
  ]
}
```

Both advertised hardening claims are **truthful**. (The hardening comment also makes a third claim
— that `mount` "is NO LONGER restricted to MS_BIND" — which is honestly disclosed and matches
finding #1 above.)

---

## 3. Per-syscall assessment of the 3 re-permitted syscalls

Context for all three: container runs `--cap-drop ALL` + `HARDENED_CAP_ADD` (which **includes
CAP_SYS_ADMIN**, SYS_MODULE, SYS_BOOT, SYS_PTRACE, NET_ADMIN, MKNOD, DAC_*), `no-new-privileges`,
not `--pid=host`, not `--network=host`, port bound to `127.0.0.1`. Note the live p21 capture
records that `no-new-privileges` was **NOT** set in that specific Magisk-init run (Magisk re-exec
needs privilege gain) — so the "bounded by no-new-privileges" assurance is config-dependent and
not guaranteed in every consumer.

### 3.1 `arch_prctl` (x86_64 syscall 158)

- **Why needed:** Bionic's dynamic linker (`linker64`) calls `arch_prctl(ARCH_SET_FS, …)` to set the
  thread-local-storage base register (`%fs`) at the start of **every** x86_64 process. Denied →
  null-TLS dereference → segfault in `linker64` before `init` even logs (container exits 139). This
  is the most unambiguously-required of the three on x86_64.
- **Risk if unfiltered:** Low. `arch_prctl` only manipulates per-thread segment-base registers
  (FS/GS get/set) of the **calling** thread. No cross-process reach, no privilege transition, no
  namespace/host effect. `ARCH_SET_GS` could be used in niche ROP/JOP gadget chains, but seccomp
  cannot meaningfully narrow this without breaking TLS, and the risk is intra-container only.
- **Arg-filter option:** Could restrict `index 0` (op) to `ARCH_SET_FS (0x1002)` / `ARCH_GET_FS
  (0x1003)`, but Bionic and the JIT/ART runtime also use `ARCH_SET_GS`/`ARCH_GET_GS` in some paths;
  a too-tight filter risks a non-obvious boot/runtime break and would require a boot re-test for
  marginal security value. Not worth it.
- **Verdict: ACCEPT-AS-IS.**

### 3.2 `personality` (syscall 135)

- **Why needed:** Bionic reads/sets the process execution domain on zygote-forked apps; ART/zygote
  expects `personality(PER_LINUX | …)`. Denied → `F libc: error getting old personality value` →
  zygote crash-loop → no boot (Phase 5 evidence). Real and load-bearing.
- **Risk if unfiltered:** **Medium — this is the one worth filtering.** `personality()` can set
  `ADDR_NO_RANDOMIZE (0x0040000)`, which **disables ASLR** for the calling process and its
  children, and `READ_IMPLIES_EXEC (0x0400000)`, which makes all readable pages executable
  (defeats W^X / NX hardening). A compromised in-container process could call
  `personality(ADDR_NO_RANDOMIZE)` to make a follow-on memory-corruption exploit deterministic.
  This is an intra-container weakening of exploit-mitigation, not a host escape — but it directly
  erodes defense-in-depth.
- **Arg-filter option (RECOMMENDED):** Android/Bionic in practice only ever sets the persona to
  `PER_LINUX (0x0)` and queries with `0xffffffff`. Restrict `index 0` to the benign values and deny
  the ASLR-off / RIE personas. Because `personality` packs persona+flags in one arg, the cleanest
  filter is a **masked-equality deny of the dangerous flag bits** — i.e. allow only calls whose
  `ADDR_NO_RANDOMIZE|READ_IMPLIES_EXEC|ADDR_COMPAT_LAYOUT` bits are clear. seccomp expresses ALLOW
  rules, so encode it as: allow `index0 == 0` (set PER_LINUX) and allow `index0 == 0xffffffff`
  (query) and let everything else fall to ERRNO. See §5 for the JSON. **Requires a boot re-test**
  (some Android builds query/restore a non-zero persona; if boot breaks, fall back to ACCEPT-AS-IS
  and document the residual ASLR-off risk).
- **Verdict: ACCEPT-WITH-ARG-FILTER (recommended), fallback ACCEPT-AS-IS-WITH-DOCUMENTED-RISK.**

### 3.3 `setns` (syscall 308) — **single most security-relevant addition**

- **Why needed:** `magiskd` calls `setns()` to (re-)enter a target process's mount namespace when
  applying Magisk's per-process module bind-mount overlay (mount-namespace isolation for the
  module `/system` overlay). Denied → magiskd namespace-path EPERMs → degraded module mount-ns
  isolation (Magisk still boots, but module overlay management is impaired). Load-bearing for the
  rooted-with-modules posture.
- **Risk if unfiltered:** **Highest of the three.** `setns()` moves the caller into an existing
  namespace given an fd. Combined with **CAP_SYS_ADMIN (present in HARDENED_CAP_ADD)**, `setns` is
  the classic container-escape primitive: a process holding an fd to a host namespace (e.g. via a
  leaked `/proc/<hostpid>/ns/*` fd, a bind-mounted host `/proc`, or `--pid=host`) can `setns` into
  it. **The profile's own comment claims "no CAP_SYS_ADMIN in the curated set" — that is FALSE**, so
  the comment's reassurance is invalid and the true risk is higher than advertised. Residual
  mitigations that DO hold: container is **not** `--pid=host` / `--network=host` (so there is no
  trivially-reachable host namespace fd), `/proc` is the container's own. So practical escape
  requires an additional fd-leak primitive — but `setns + CAP_SYS_ADMIN` is exactly the pair that
  turns such a leak into a full escape.
- **Arg-filter option (RECOMMENDED):** `setns(int fd, int nstype)` — `nstype` (arg index 1) can be
  pinned to the namespace types Magisk actually needs (`CLONE_NEWNS` = mount ns = `0x00020000`).
  Restricting `index 1` to `CLONE_NEWNS` blocks `setns` into PID (`CLONE_NEWPID 0x20000000`), NET
  (`CLONE_NEWNET 0x40000000`), USER (`CLONE_NEWUSER 0x10000000`), UTS, IPC, cgroup, and the
  `nstype==0` "any-type" form (which is the most dangerous, as it accepts whatever the fd points
  to). This materially shrinks the escape surface while preserving Magisk's mount-ns re-entry.
  **Requires a boot re-test** (confirm magiskd only ever passes `CLONE_NEWNS`).
- **Verdict: ACCEPT-WITH-ARG-FILTER (strongly recommended) / NEEDS-INVESTIGATION** of the exact
  `nstype` magiskd passes before the filter is finalized.

---

## 4. Overall posture assessment (honest)

**Relative to Docker's DEFAULT seccomp:** Docker default is `defaultAction=SCMP_ACT_ERRNO` and
allows ~300+ syscalls while blocking the ~40–50 most dangerous (keyctl-heavy, `add_key`, `bpf`,
`clone` flags, `mount`, `ptrace`, `setns`, `personality`, kernel-module/`kexec`, `reboot`, etc.).
This l0b profile is **broader** than Docker default in the specific dimensions it must be to boot a
rooted ReDroid: it **re-permits** `mount` (unfiltered), `setns`, `personality`, `add_key/keyctl`,
`bpf`, `perf_event_open`, `clone/unshare`, `init`-less namespace ops. So l0b is NOT "stricter than
Docker default" globally — it is a **purpose-widened** allowlist. Where it stays strict and
**beats** the naive rooted-container approach: `ptrace` is TRACEME-only (Docker default also blocks
ptrace, but many rooted setups re-enable it wholesale — l0b does not), `process_vm_*` stay denied,
and the whole `init_module/finit_module/delete_module/kexec_*/reboot/iopl/ioperm/pivot_root/swapon/
acct/quotactl` dangerous-syscall block remains denied by default.

**Relative to `seccomp=unconfined`:** dramatically stronger. `unconfined` allows literally every
syscall including `kexec_load`, `init_module`, `process_vm_writev`, unfiltered `ptrace` (host
process injection if `--pid=host`), `reboot`, etc. l0b denies all of those. This is the single
biggest win and the reason the profile exists.

**It is explicitly NOT a substitute** for `cap_drop:[ALL]` / `no-new-privileges` / image-digest
pinning — the profile says so (`_comment_compliance`), and that is correct. The seccomp profile and
the cap/NNP/pinning controls are **complementary layers**; promoting this profile does not weaken
them, but it also cannot compensate if any of them is dropped (and the p21 capture shows NNP is
sometimes intentionally off for Magisk re-exec — a coupling the board should note).

**Single most security-relevant addition: `setns`** — because it is the canonical container-escape
primitive and it co-exists with **CAP_SYS_ADMIN** in the real cap set (contradicting the profile's
own "no CAP_SYS_ADMIN" comment). `mount`-unfiltered is the close second (raw `mount` + CAP_SYS_ADMIN
enables overmounting `/proc`, `/sys`, procfs-based info leaks, and bind-mount tricks). `personality`
is third (ASLR-off). `arch_prctl` is benign.

---

## 5. Promotion recommendation + recommended arg-filter JSON

**Decision: PROMOTE-AFTER-ADDING-RECOMMENDED-ARG-FILTERS, conditional on a board-gated boot
re-test, AND after correcting the false CAP_SYS_ADMIN comment.**

Concretely, before promotion to production the board should require:

1. **Fix the documentation lie.** The PHASE5-AMENDMENT comment's "(no CAP_SYS_ADMIN in the curated
   set)" is false vs. `HARDENED_CAP_ADD`. Correct it to state CAP_SYS_ADMIN **is** present and that
   `setns`/`mount` risk is bounded only by "not `--pid=host`/`--network=host`" + no host-ns fd
   leak. (Doc-only; no boot impact.)
2. **Add the `setns` nstype filter** (highest-value, blocks PID/NET/USER ns escape). Boot re-test
   required to confirm magiskd passes only `CLONE_NEWNS`.
3. **Add the `personality` persona filter** (blocks `ADDR_NO_RANDOMIZE`/`READ_IMPLIES_EXEC`). Boot
   re-test required.
4. **Decide on `mount`:** Phase 5 proved non-bind mounts are required, so the unfiltered `mount`
   widening is likely unavoidable; if accepted, **remove the now-dead MS_BIND rule** to avoid
   implying a restriction that no longer exists, and document the acceptance. (Removing a dead rule
   is behaviorally a no-op but still warrants the boot re-test per the board's own gate.)

These are RECOMMENDATIONS for the board. **This audit does NOT modify the live profile** — every
change above alters the enforced BPF and therefore requires the boot re-test the proposal artifact
itself gates on.

### Recommended `setns` arg-filter (replace the unfiltered `setns` allow)

```json
{
  "_group": "setns-mountns-only (magiskd re-enters target mount namespace; nstype pinned to CLONE_NEWNS=0x00020000 — blocks setns into PID/NET/USER/UTS/IPC/cgroup namespaces and the nstype==0 any-type form, which are the escape-relevant cases). Requires boot re-test: confirm magiskd passes only CLONE_NEWNS.",
  "names": ["setns"],
  "action": "SCMP_ACT_ALLOW",
  "args": [
    { "index": 1, "op": "SCMP_CMP_EQ", "value": 131072 }
  ]
}
```
(`CLONE_NEWNS = 0x00020000 = 131072`.)

### Recommended `personality` arg-filter (replace the unfiltered `personality` allow with two rules)

```json
{
  "_group": "personality-PER_LINUX-set (allow setting the benign PER_LINUX persona only; blocks ADDR_NO_RANDOMIZE 0x0040000 / READ_IMPLIES_EXEC 0x0400000). Boot re-test required.",
  "names": ["personality"],
  "action": "SCMP_ACT_ALLOW",
  "args": [
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 0 }
  ]
},
{
  "_group": "personality-query (persona==0xffffffff is the libc 'read current persona' query path).",
  "names": ["personality"],
  "action": "SCMP_ACT_ALLOW",
  "args": [
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 4294967295 }
  ]
}
```
> Note: a single masked-bit deny would be cleaner but seccomp ALLOW rules cannot express
> "deny if these bits set"; the two-equality form above is the conservative ALLOW-list encoding.
> If Android queries/restores a non-zero non-0xffffffff persona at boot, this will break boot —
> hence the mandatory re-test and the ACCEPT-AS-IS fallback.

### `arch_prctl` — leave ACCEPT-AS-IS (no filter recommended).
### `mount` — accept the widening (Phase-5-required), remove the dead MS_BIND rule, document.

---

## 6. Blast radius / consumers of the profile

`grep` across the repo (excluding `.claude/worktrees/`) for consumers of `redroid-seccomp-l0b.json`
/ `HARDENED_SECCOMP`:

| Consumer | How it uses the profile | Promotion impact |
|---|---|---|
| `agents/orchestrator/src/container_lifecycle.py` | `HARDENED_SECCOMP = ".../redroid-seccomp-l0b.json"`; `build_hardened_run_argv()` passes it as `--security-opt seccomp=<abs>` with `HARDENED_CAP_ADD` (incl. CAP_SYS_ADMIN). **This is the production boot path.** | Any change to the profile changes every hardened `docker run` argv this module emits. Primary blast radius. |
| `agents/stability/stack/launch-l0b-hardened-spoof.sh` | Hardcodes `SECCOMP=".../redroid-seccomp-l0b.json"` + the same ~28-cap set incl. `SYS_ADMIN`. | Direct launch script; would inherit any filter changes. Must be re-tested. |
| `p21/l0b-hardened2-unspoofed-2026-05-30.yml` | Documentation capture referencing the profile path (records NNP-off Magisk run). | Doc/evidence only; no runtime dependency, but the parity claim (0.3815 DETECTED) would need re-validation if mount/setns filters change Magisk behavior. |
| `tests/test_orchestrator_container_lifecycle.py` | Asserts `seccomp=` appears in the hardened argv (path-string assertion, not BPF content). | Adding filters does not change the path string, so tests stay green; but if the filename changes on promotion, update this test. |

**Blast radius summary:** the profile is wired into the **single production hardened boot path**
(`container_lifecycle.build_hardened_run_argv`) plus one launch script. Promotion is not isolated —
it affects every non-privileged hardened ReDroid the orchestrator starts. The arg-filter changes
recommended above each require the board's boot re-test specifically because they alter enforced
BPF on that production path.

---

## 7. Static-analysis hygiene

- **No container booted.** Pure JSON parse + repo grep.
- **Profile NOT modified.** All JSON in §5 is a RECOMMENDATION inside this audit doc only.
- **`git diff` secret/canary-token scan: 0 matches** (no secrets in the diff).
- **Python suite untouched:** `pytest --co -q` collects **111 tests** (unchanged; this audit adds only
  this Markdown file). No code changed, so the suite remains green.
- **Deliverable:** `proof/seccomp-promotion-audit/RESULT.md` left in the working tree, **uncommitted**
  (adversarial validator gates the commit).

---

## Appendix A — verification method (reproducible)

```python
import json
base = json.load(open("agents/stability/stack/seccomp/redroid-seccomp.json"))
l0b  = json.load(open("agents/stability/stack/seccomp/redroid-seccomp-l0b.json"))
def allow(p): return {n for g in p["syscalls"] if g["action"]=="SCMP_ACT_ALLOW" for n in g["names"]}
added, removed = allow(l0b)-allow(base), allow(base)-allow(l0b)
# added   == {'arch_prctl','personality','setns'}
# removed == set()
# mount: base has 1 ALLOW rule (MS_BIND arg-filter); l0b has 2 (one unconditional + the MS_BIND one)
# ptrace: identical in both -> ALLOW with args index0 SCMP_CMP_EQ value 0  (PTRACE_TRACEME)
# process_vm_readv / process_vm_writev: in neither allow set (denied by default)
```

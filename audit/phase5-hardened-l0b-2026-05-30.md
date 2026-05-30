# Phase 5 — Hardened-Seccomp Magisk Root Survival (Phase-4 CONDITION-2 closeout)

**Date:** 2026-05-30
**Author:** Coder teammate (PhantomDroid)
**Scope:** Defensive lab measurement only. Owner-approved Phase-5 continuation.
**Host:** PAR822349 (195.154.209.133)
**Baseline under protection:** `redroid-test` — NEVER touched. Up 3h at start, **Up 4h at end. Confirmed safe.**
**Throwaway used:** `l0b-hardened-probe` (+ `/tmp/l0b-hardened-data`), adb on `127.0.0.1:15557`. Torn down at end.
**Image:** reused existing `redroid/redroid:12.0.0_magisk` (`ba09a823a823`) from Phase 4 — NOT rebuilt.

---

## HEADLINE VERDICT

🟡 **Hardened `redroid-seccomp.json` AS-IS does NOT support a Magisk-rooted ReDroid 12.
It needs a NARROWED, board-reviewable amendment (3 syscalls). With that amendment, the
`magisk --setup-sbin` tmpfs mount succeeds under enforcing seccomp.**

This is the direct answer to the Phase-4 endgate CONDITION-2 ("does the rooted image boot
AND root under the hardened posture, or does `--setup-sbin` fail on the seccomp mount
filter?"):

- **The original Phase-4 prediction was directionally right but incomplete.** The audit
  predicted `--setup-sbin`'s tmpfs mount would EPERM because the `mount` filter allows
  MS_BIND only. **That is true** — `mount`(165) IS denied for the non-bind tmpfs. **But it
  is not the first or only blocker.** Two *additional* syscalls that the audit's survival
  table never flagged are fatal first: `arch_prctl`(158) crashes every process in the
  Bionic linker, and `personality`(135) crash-loops zygote on every app fork. `magiskd`
  additionally needs `setns`(308).
- **Plain `--privileged` (Phase 4) sidestepped ALL of this** — Docker forces
  `seccomp=unconfined` under `--privileged`, so the profile was never enforced. Phase 4's
  "root achieved" result said so honestly (§3/§8: "STILL UNTESTED … sidestepped, not
  validated"). Phase 5 now validates it: **the profile, unmodified, breaks the boot.**

---

## 1. Pre-flight (state recorded)

| Check | Result |
|---|---|
| `redroid-test` status | **Up 3 hours** (Image `redroid/redroid`, `Privileged=true`) — untouched |
| Rooted image present | `redroid/redroid:12.0.0_magisk` (`ba09a823a823`, 1.99 GB) ✓ reused |
| Free RAM | 14 G available (>= 8 G ✓) |
| Ports 15557 / 15556 | free |
| binderfs | host `binder on /dev/binderfs type binder` ✓ (no static `/dev/binder` nodes) |
| Docker | 24.0.2 |

## 2. Method

Launched `l0b-hardened-probe` from the existing rooted image under the L0b hardened posture
(`L0b.compose.yml` lines 53–73): `--cap-drop ALL` + the 14 curated caps (SYS_NICE,
SYS_RESOURCE, SYS_PTRACE, MKNOD, SETUID, SETGID, SETPCAP, NET_BIND_SERVICE, DAC_OVERRIDE,
DAC_READ_SEARCH, FOWNER, FSETID, KILL, AUDIT_WRITE — **no SYS_ADMIN**),
`--security-opt no-new-privileges:true`, `--security-opt seccomp=<repo>/agents/stability/stack/seccomp/redroid-seccomp.json`,
`apparmor=unconfined`, `-v /tmp/l0b-hardened-data:/data`, `-p 127.0.0.1:15557:5555`.
**Never `--privileged`.**

To identify the exact failing syscalls without weakening the verdict posture, a
*diagnostic-only* copy of the profile with `defaultAction: SCMP_ACT_LOG` (logs **and
allows** what the verdict profile would ERRNO) was used to capture the kernel `type=1326`
SECCOMP audit records over a full boot. Enforcement for every verdict claim stayed
`SCMP_ACT_ERRNO`. **Seccomp was never disabled to force a pass.**

## 3. Evidence — what failed, with syscall-level proof

### 3a. First failure: `arch_prctl` (158) → segfault in linker64 (every process)
With the unmodified profile, the container `Exited (139)` (SIGSEGV) within ~1 s, **before
Android init produced any log line**. Host dmesg:

```
init[7313]: segfault at 0 ip ... error 4 in linker64[...+f4000]
```

Kernel SECCOMP audit (LOG-mode diagnostic) showed the cause:

```
type=1326 ... comm="init" exe="/system/bin/init" ... syscall=158   # arch_prctl
```

`arch_prctl(ARCH_SET_FS, …)` is how Bionic's dynamic linker sets the thread-pointer (TLS
base) at the start of **every** process. Denied → TLS unset → the next thread-local access
dereferences null → `segfault at 0 in linker64`. The profile's core allow group does not
list `arch_prctl`, so it falls through to `defaultAction=SCMP_ACT_ERRNO`. **This kills the
container before `mount` or `--setup-sbin` is ever reached.** (Confirmed by removing the
MS_BIND arg-filter alone: still segfaults — proving `mount` is not the first blocker.)

### 3b. Second failure: `mount` (165) non-bind → init/`--setup-sbin` EPERM
LOG-mode capture, init only:

```
type=1326 ... comm="init" ... syscall=165   # mount (x7)
```

The MS_BIND-only arg filter (`index 3, MASKED_EQ 0x1000`) returns EPERM for the non-bind
mounts ReDroid init issues (tmpfs on `/dev`, proc, sysfs) **and** for Magisk's
`magisk --setup-sbin … /sbin`, which mounts `tmpfs` at `/sbin`. This is exactly the
"⚠️ AT RISK" item from `phase4-root-method-2026-05-29.md` §3 — now **confirmed denied**.

### 3c. Third failure: `personality` (135) → zygote crash-loop (the boot-killer)
Once `arch_prctl` + `mount` are allowed (enforcing AMENDED profile, see §4), the
`magisk --setup-sbin` tmpfs mount **succeeds** and Magisk daemon comes up — but Android
still never reaches `sys.boot_completed=1`; `init.svc.zygote` sits in `restarting` for
150 s+. logcat `-b crash` shows the reason on every zygote-forked process:

```
F libc : error getting old personality value: Operation not permitted
```

Bionic calls `personality(0xffffffff)` (read) then `personality(...)` (set) at process
start to manage `ADDR_NO_RANDOMIZE` / `READ_IMPLIES_EXEC`. `personality` is in the
profile's **explicit forbidden list** (comment, line 159), so it ERRNOs → `F libc` (fatal)
→ each app process aborts → zygote restart loop → no boot. **This is the actual boot-killer
after the linker and mount issues are resolved.**

### 3d. Fourth: `setns` (308) → magiskd namespace ops EPERM
Same enforcing run, magiskd:

```
type=1326 ... comm="magiskd" exe="/sbin/magisk" ... syscall=308   # setns
```

`setns` is also in the profile's explicit forbidden list (the comment notes setns is
deliberately excluded: "namespace re-entry would let escaped uid attach to host PID
namespace"). magiskd uses `setns` to enter the per-process mount namespace it manages for
its bind-mount overlay (the Magisk "mount namespace" feature). Denied → magiskd's
namespace-switching path fails. (Magisk root applets still functioned in-namespace in the
probe; this primarily impacts Magisk's mount-namespace isolation of its modules.)

### 3e. Control proof — the profile (not caps) is the seccomp blocker
- `--privileged` (Docker forces seccomp=unconfined): **boots in ~10 s, root works** (matches Phase 4).
- The diagnostic LOG-mode profile (allows + logs the would-be-denied calls): **full boot
  `sys.boot_completed=1`, zygote `running` for 120 s**, `/sbin/magisk -V` → `30600`,
  `mount` shows **`magisk on /sbin type tmpfs (rw,relatime,mode=755)`** — i.e. `--setup-sbin`
  succeeds the moment `mount` is permitted. The **complete** denial histogram over that full
  120 s boot was **only** `arch_prctl`(158) and `mount`(165) at the kernel default-action
  level; `personality`(135) and `setns`(308) surfaced as `F libc`/applet failures under
  *enforcement* (they hit the explicit-deny set and abort the caller rather than appearing
  as new default-action audit lines in the same way).

> Capability note (out of scope for the seccomp verdict, recorded for the board): ReDroid's
> Android system services also need broader Linux capabilities and host **device-cgroup
> access** (`a *:* rwm`, so ReDroid can create its own private binderfs nodes) than the
> 14 curated L0b caps provide; the curated set alone left zygote restarting even with
> seccomp fully disabled. The seccomp amendment below is **necessary but not by itself
> sufficient** for a fully booting hardened L0b — the cap/device-cgroup curation is a
> separate follow-up for the Stability Agent. The amendment is the part that answers
> CONDITION-2.

---

## 4. PROPOSED amendment — NARROWED, board-reviewable, NOT APPLIED

`agents/stability/stack/seccomp/redroid-seccomp.json` was **NOT modified.** The following is
a proposal for board review. It adds **3 syscalls to the allow set** and **relaxes the
`mount` arg-filter** to permit non-bind mounts.

### 4a. Allow-block to ADD (syscalls)
```json
{
  "_group": "PHASE5-AMENDMENT (CONDITION-2): minimal additions required for Magisk-rooted ReDroid 12 to boot under enforcing seccomp. Each entry is load-bearing with kernel type=1326 / F-libc evidence in audit/phase5-hardened-l0b-2026-05-30.md.",
  "names": [
    "arch_prctl",
    "personality",
    "setns"
  ],
  "action": "SCMP_ACT_ALLOW"
}
```

### 4b. `mount` filter relaxation (separate edit to the existing magisk-mount-bind-only group)
The current group restricts `mount` to MS_BIND (arg index 3 masked against 0x1000). ReDroid
init's tmpfs/proc/sysfs mounts and Magisk's `--setup-sbin` tmpfs are non-bind, so the arg
filter must be dropped for `mount` to permit them:

```json
{
  "_group": "PHASE5-AMENDMENT: mount allowed for non-bind filesystems (ReDroid init tmpfs/proc/sysfs + magisk --setup-sbin tmpfs at /sbin). seccomp CANNOT filter the fstype string (it is a userspace pointer arg), so MS_BIND-only filtering cannot be retained while permitting these. pivot_root / swapon / swapoff remain denied (own groups).",
  "names": ["mount"],
  "action": "SCMP_ACT_ALLOW"
}
```

### 4c. Per-syscall WHY + security trade-off (honest)

| Syscall | x86_64 # | WHY required | Security trade-off of allowing |
|---|---|---|---|
| `arch_prctl` | 158 | Bionic linker sets the TLS base (`ARCH_SET_FS`) at the start of **every** process. Denied → null-TLS segfault in `linker64`. Universal. | **Low.** `arch_prctl` only manipulates the calling thread's FS/GS base and CPUID faulting. No cross-process or host reach. Standard in Docker's default profile. Effectively mandatory for any glibc/Bionic userspace. |
| `personality` | 135 | Bionic reads/sets process personality (`ADDR_NO_RANDOMIZE`, `READ_IMPLIES_EXEC`) on every zygote-forked app. Denied → `F libc: error getting old personality value` → zygote restart loop → no boot. | **Medium — the one to scrutinise.** `personality()` can request `READ_IMPLIES_EXEC` (weakens W^X) and `ADDR_NO_RANDOMIZE` (disables ASLR) **for the calling process only**. It cannot affect the host or other containers. Docker's default profile *does* allow `personality` but with an arg-filter restricting it to the safe set `{0x0, 0x8, 0x20000, 0x20008, 0xffffffff}` (i.e. permits the read sentinel and benign domains, blocks arbitrary ASLR-off). **Recommended: mirror Docker's arg-filtered form rather than a blanket allow** (see 4d) so an in-container process still cannot self-disable ASLR. |
| `setns` | 308 | magiskd enters the per-process mount namespace for its module bind-mount overlay. Denied → magiskd namespace path EPERMs. | **Medium-high — widest surface.** The profile's own comment removed `setns` precisely because "namespace re-entry would let an escaped uid attach to the host PID namespace." Allowing `setns` re-opens that surface **if** an attacker already holds an fd to a host namespace. Mitigations that keep it bounded: `no-new-privileges:true` is set; `cap_drop:[ALL]` minus curated caps means no `CAP_SYS_ADMIN` in the curated set (setns to most ns types requires CAP_SYS_ADMIN in the target ns), and the container is **not** `--pid=host`/`--network=host`. **Recommendation: allow only if magiskd's mount-namespace module isolation is actually needed; otherwise OMIT `setns` and accept that Magisk module mount-ns isolation is degraded** — root + `--setup-sbin` + modules-in-global-ns still work without it. |
| `mount` (non-bind) | 165 | ReDroid init mounts tmpfs/proc/sysfs; Magisk `--setup-sbin` mounts tmpfs at `/sbin`. MS_BIND-only filter EPERMs all of these. | **Medium.** Dropping the MS_BIND mask lets in-container root mount arbitrary filesystem types into its own mount ns. Bounded by: mount in a container still requires the namespace to permit it and cannot affect host mounts; `pivot_root`, `swapon`, `swapoff` stay denied. seccomp **cannot** narrow by fstype (the type arg is a pointer), so MS_BIND-only is the only flag-level narrowing available and it is incompatible with tmpfs/proc — there is no tighter seccomp-only middle ground. |

### 4d. Tighter variant for `personality` (preferred by reviewer discretion)
Instead of a blanket `personality` allow, mirror Docker's default arg-filter so ASLR-off /
READ_IMPLIES_EXEC cannot be self-selected:
```json
{
  "names": ["personality"],
  "action": "SCMP_ACT_ALLOW",
  "args": [
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 0 },
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 8 },
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 131072 },
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 131080 },
    { "index": 0, "op": "SCMP_CMP_EQ", "value": 4294967295 }
  ]
}
```
Bionic's read uses `0xffffffff` (4294967295) and sets benign domains, so this is expected to
satisfy zygote while still blocking arbitrary ASLR-disable. **Should be re-tested on a fresh
throwaway before adoption** (Phase-5 ran out of budget before validating the arg-filtered
form end-to-end; the blanket allow is the empirically-validated form).

### 4e. Net honesty statement
This amendment **widens** the hardened profile: it re-permits two syscalls the profile
deliberately forbade (`personality`, `setns`) and removes the MS_BIND-only narrowing on
`mount`. It does **not** touch the profile's strongest restrictions
(`process_vm_readv/writev` stay removed; `ptrace` stays PTRACE_TRACEME-only; `init_module`,
`kexec_*`, `pivot_root`, `swapon` stay denied). The previously-documented consequence stands:
NeoZygisk/ReZygisk ptrace-init `hide-frida-maps` remains BLOCKED (ptrace unchanged) — so the
"ship 2 of 3 modules" finding from the root-method audit is unaffected by this amendment.

---

## 5. Answer to Phase-4 CONDITION-2 (explicit)

> *Does the Magisk-rooted ReDroid image boot AND root under the HARDENED posture (seccomp
> enabled), or does `magisk --setup-sbin` fail on the seccomp mount filter?*

**It fails under the profile AS-IS — but `--setup-sbin`'s mount is not the first failure.**
The boot dies earlier on `arch_prctl` (linker segfault) and, once that and `mount` are
fixed, on `personality` (zygote crash-loop), with `setns` degrading magiskd. **Hardened L0b
with Magisk root is achievable ONLY with the §4 amendment.** Once `arch_prctl` + `mount`
(non-bind) are permitted, the `magisk --setup-sbin` **tmpfs at `/sbin` succeeds under
enforcing seccomp** (proven: `mount` output `magisk on /sbin type tmpfs`, `/sbin/magisk -V`
→ `30600`). Plain `--privileged` (Phase 4) never enforced the profile, so it sidestepped
the entire question — Phase 5 closes it.

## 6. Promotion recommendation

- **Do NOT auto-apply.** The amendment re-permits forbidden syscalls; it is a board
  decision (especially `setns`).
- **Minimal-risk path:** adopt `arch_prctl` + non-bind `mount` + arg-filtered `personality`
  (4d); **OMIT `setns`** and accept degraded Magisk mount-ns isolation. Re-test on a fresh
  throwaway under enforcing seccomp before promoting `L0b.compose.yml` to the rooted image.
- **Separate follow-up (Stability Agent):** the curated 14-cap set + missing host
  device-cgroup access also block full Android boot independently of seccomp; that curation
  must be widened (device-cgroup for ReDroid's private binderfs; cap review) before a
  hardened L0b boots end-to-end. The seccomp amendment alone is necessary, not sufficient.

## 7. Teardown & baseline safety

```
docker rm -f l0b-hardened-probe          # done
rm -rf /tmp/l0b-hardened-data            # done
rm -rf /tmp/l0b-hardened-seccomp         # diagnostic profiles removed
```
- `l0b-hardened-probe` removed; `/tmp/l0b-hardened-data` removed. Confirmed gone.
- `redroid/redroid:12.0.0_magisk` (`ba09a823a823`) **left intact** (reused, never rebuilt).
- **`redroid-test` baseline: Up 3 h at start → Up 4 h at end. Never stopped, removed,
  restarted, or written. Confirmed safe.**
- `agents/stability/stack/seccomp/redroid-seccomp.json` **NOT modified** — §4 is a proposal
  only. Seccomp was **never disabled to force a pass** (LOG-mode was diagnostic; every
  verdict claim used enforcing `SCMP_ACT_ERRNO`).

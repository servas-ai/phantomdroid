# Seccomp arg-filters APPLIED + empirically boot+root re-tested — `redroid-seccomp-l0b.json`

**Date:** 2026-06-01
**Actor:** BUILDER sub-agent (board-approved work-item: apply RESULT.md §5 arg-filters + boot re-test)
**Repo:** `git@github.com:servas-ai/phantomdroid.git` — branch `session/e2e-2026-05-30`
**Subject:** `agents/stability/stack/seccomp/redroid-seccomp-l0b.json`
**Posture (every boot):** `redroid/redroid:12.0.0_magisk`, NON-privileged
(`Privileged=false`, `cap_drop:[ALL]` + `HARDENED_CAP_ADD` incl. CAP_SYS_ADMIN),
`build_hardened_run_argv()`, MINIMAL device-cgroup-rules, `no-new-privileges`, port `127.0.0.1`.

This is the empirical follow-through to `RESULT.md` §5 (which only *recommended* the filters and
explicitly did not modify the live profile). Every filter below was applied to the BPF and then
re-tested by booting an actual Magisk-rooted ReDroid 12 and verifying root, serially, one container
at a time, removing each before the next.

---

## 0. Result summary

| Recommended filter (RESULT.md §5) | Applied? | Boot+root re-test | Outcome |
|---|---|---|---|
| `personality` → persona∈{0, 0xffffffff} (block ADDR_NO_RANDOMIZE / READ_IMPLIES_EXEC) | **YES** | `sec-pers` PASS | Kept — boots+roots, no `F libc: error getting old personality value`, zygote/system_server stable |
| `setns` → nstype==CLONE_NEWNS (0x20000) (block PID/NET/USER/cgroup-ns + nstype==0 any-type) | **YES** | `sec-setns` PASS | Kept — boots+roots; **strace proved magiskd passes the explicit CLONE_NEWNS nstype**, so the filter does not EPERM it |
| `arch_prctl` → leave unfiltered | leave-as-is | (covered by all boots) | Unchanged (TLS, non-narrowable; audit §3.1 ACCEPT-AS-IS) |
| `mount` → accept widening, do not narrow | leave-as-is | (covered by all boots) | Unchanged (fstype is a userspace pointer; seccomp cannot compare it — documented accepted tradeoff; instructed NOT to touch) |

**BOTH recommended arg-filters were kept** — neither broke boot or root. The audit's flagged risk
(that `setns` might use the `nstype==0` any-type form, which a CLONE_NEWNS-only filter would EPERM)
**did NOT materialize** and was disproven empirically (see §2).

`defaultAction` stays `SCMP_ACT_ERRNO`. `process_vm_readv/writev`, `init_module`, `kexec_*`,
`reboot`, `pivot_root`, `swapon`, `iopl`, `ioperm`, `acct` etc. remain DENIED. `ptrace` stays
PTRACE_TRACEME-only. `arch_prctl` and `mount` are untouched.

---

## 1. Structural change to the profile

The former group[0] was a single UNCONDITIONAL `SCMP_ACT_ALLOW` over
`[arch_prctl, personality, setns, mount]`. It was SPLIT:

- `arch_prctl`, `mount` — remain in the (now 2-name) unconditional PHASE5-AMENDMENT ALLOW.
- `personality` — moved to **two** arg-filtered ALLOW groups (`index 0 == 0`, `index 0 == 4294967295`).
- `setns` — moved to **one** arg-filtered ALLOW group (`index 1 == 131072` = CLONE_NEWNS).
  There is **no** unconditional `setns` fallback rule, so every non-CLONE_NEWNS `nstype`
  (incl. 0) falls through to `defaultAction = SCMP_ACT_ERRNO`.

Programmatic verification of the final rule shapes:

```
defaultAction: SCMP_ACT_ERRNO | defaultErrnoRet: 1
STRONG DENIALS still denied: True | leaked: []
arch_prctl:   [None]                                               # unfiltered (unchanged)
mount:        [None, MASKED_EQ index3 4096/4096]                   # unfiltered + dead MS_BIND (unchanged)
personality:  [EQ index0 0], [EQ index0 4294967295]               # NEW arg-filter
setns:        [EQ index1 131072]                                   # NEW arg-filter (only rule)
ptrace:       [EQ index0 0]                                        # PTRACE_TRACEME-only (unchanged)
```

---

## 2. Empirical evidence

### 2.0 Baseline (`sec-base`, port 5801, UNMODIFIED profile) — sanity, PASS

Confirmed the unmodified profile boots+roots in this environment before any edit.

```
boot_completed=1 at ~10s
SU_ID: uid=0(root) gid=0(root) groups=0(root)
MAGISK_C: 30.6:MAGISK:D (30600)
MAGISKD_COUNT: 1
DATA_ADB_WRITE: WRITE_OK
RESULT: PASS
```

**Critical strace finding from the baseline (host pid of magiskd):**

```
$ sudo strace -f -e trace=setns,personality -p <magiskd_host_pid>
[pid ...] setns(5, CLONE_NEWNS)      = 0     (x10, every captured call)
```

magiskd passes the **explicit `CLONE_NEWNS` nstype**, NOT the `nstype==0` any-type form.
This is the determining evidence that the CLONE_NEWNS-only filter is safe.
(`personality` is not surfaced at the syscall level for magiskd — it is a Bionic/ART
zygote-startup call; its filter was validated by the boot test instead, per RESULT.md §3.2 method.)

### 2.1 `personality` filter (`sec-pers`, port 5803) — PASS

```
boot_completed=1 at ~10s
SU_ID: uid=0(root) gid=0(root) groups=0(root)
MAGISK_C: 30.6:MAGISK:D (30600)   MAGISKD_COUNT: 1   DATA_ADB_WRITE: WRITE_OK
RESULT: PASS
# logcat: 0 matches for 'personality|SIGSYS|error getting old personality value'
# system_server: UP   zygote64: UP   (no zygote crash-loop)
```

The 2-value persona allowlist (0 / 0xffffffff) is sufficient for boot — Android never needed a
persona with the ASLR-off / READ_IMPLIES_EXEC bits, so the filter blocks those without breaking boot.

### 2.2 `setns` CLONE_NEWNS-only filter (`sec-setns`, port 5805) — PASS

```
boot_completed=1 at ~25s
SU_ID: uid=0(root) gid=0(root) groups=0(root)
MAGISK_C: 30.6:MAGISK:D (30600)   MAGISKD_COUNT: 1   DATA_ADB_WRITE: WRITE_OK
RESULT: PASS
```

Functional re-confirmation that magiskd's mount-ns re-entry is **not** degraded under the filter —
strace of magiskd WHILE the CLONE_NEWNS filter was enforced, generating fresh `su` + app forks:

```
$ sudo strace -f -e trace=setns -p <magiskd_host_pid>   # filter ACTIVE
[pid ...] setns(5, CLONE_NEWNS)      = 0     (x6, every call returns 0)
# setns EPERM/EACCES denials: NONE
```

All magiskd `setns(fd, CLONE_NEWNS)` calls return **0** under the filter — Magisk's per-process
module mount-namespace overlay continues to work; the filter only removes the escape-relevant
nstypes (PID/NET/USER/cgroup + the any-type 0 form) that magiskd never uses.

### 2.3 Final combined profile (`sec-final`, port 5807) — PASS

Both filters together:

```
boot_completed=1 at ~35s
SU_ID: uid=0(root) gid=0(root) groups=0(root)
MAGISK_C: 30.6:MAGISK:D (30600)   MAGISKD_COUNT: 1   DATA_ADB_WRITE: WRITE_OK
RESULT: PASS
Privileged=false   CapDrop=[ALL]   system_server UP   zygote64 UP
logcat SIGSYS / personality-error count: 0
```

---

## 3. What could NOT be narrowed (honest)

- **`mount`** — cannot be narrowed by fstype because the fstype is a userspace `char*` pointer
  argument; seccomp can only compare scalar register values, not dereference the pointer. Phase 5
  proved non-bind mounts (`magisk --setup-sbin` tmpfs, init proc/sysfs) are required, so the
  unfiltered allow stays. This is the documented accepted tradeoff and the work-item explicitly
  instructed NOT to touch `mount`. (The inert MS_BIND rule from the base profile is retained for
  traceability; it is dead because the unconditional allow always matches first.)
- **`arch_prctl`** — left unfiltered (audit §3.1): narrowing to ARCH_SET_FS/GET_FS risks breaking
  ART/JIT paths that use ARCH_SET_GS, for marginal intra-container-only security value. ACCEPT-AS-IS.

Everything the audit recommended narrowing (`personality`, `setns`) **was** narrowed and kept.

---

## 4. Residual risk after this change

- The audit's **top residual risk** (`setns` + CAP_SYS_ADMIN as a container-escape primitive) is now
  **materially mitigated at the seccomp layer**: `setns` can only enter a **mount** namespace; entry
  into a host PID/USER/NET namespace (the actual escape) is blocked by `defaultAction=SCMP_ACT_ERRNO`,
  even though CAP_SYS_ADMIN is present. The escape would now additionally require defeating the
  seccomp filter itself. (`clone/clone3/unshare` remain unfiltered — they create NEW empty
  namespaces, not entry into existing host ones, so they are not the escape primitive `setns` was.)
- The **`personality` ASLR-off / READ_IMPLIES_EXEC** intra-container weakening (audit §3.2 "medium")
  is now closed — those personas hit ERRNO.
- `mount`-unfiltered + CAP_SYS_ADMIN remains the largest residual (overmount `/proc` etc.), unchanged
  and non-narrowable by seccomp; mitigation if desired is non-seccomp (mount-propagation / read-only
  bind hardening), out of scope here.

---

## 5. Validation hygiene

- `python3 -c "import json; json.load(...)"` → **VALID**.
- `python3 -m pytest -q` → **111 passed** (unchanged; lifecycle test asserts the seccomp PATH only,
  not BPF contents, so it stays green — confirmed `tests/test_orchestrator_container_lifecycle.py`
  10 passed).
- Containers created (`sec-base`, `sec-pers`, `sec-setns`, `sec-final`) all **removed**; their
  data dirs (`/home/coder/redroid-data/sec-*`) deleted. `b2-magisk` untouched.
- `git diff` secret/canary scan: 0 matches.
- **NOT committed** — adversarial validator gates the commit.

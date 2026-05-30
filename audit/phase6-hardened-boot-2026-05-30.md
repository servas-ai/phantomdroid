# Phase 6 — Hardened Enforcing-Seccomp Magisk Boot+Root (Phase-5 amendment, PROVEN)

**Date:** 2026-05-30
**Author:** Coder teammate (PhantomDroid)
**Scope:** Defensive lab measurement only. Owner-approved Phase-6 continuation.
**Host:** PAR822349 (195.154.209.133)
**Baseline under protection:** `redroid-test` — NEVER touched. Up 5h at start, **Up 5h at end. Confirmed safe.**
**Throwaway used:** `l0b-hardened2-probe` (+ `/tmp/l0b-hardened2-data`), adb on `127.0.0.1:15558`. Torn down at end.
**Image:** reused existing `redroid/redroid:12.0.0_magisk` (`ba09a823a823`) from Phase 4 — NOT rebuilt.
**Proposed profile artifact:** `agents/stability/stack/seccomp/redroid-seccomp-l0b.json` (board-review proposal; production `redroid-seccomp.json` UNTOUCHED).

---

## HEADLINE VERDICT

🟢 **GREEN — A Magisk-rooted ReDroid 12 fully boots AND roots under an ENFORCING
custom seccomp profile (`SCMP_ACT_ERRNO` default) with NO `--privileged`.**

This turns Phase 5's *predicted* amendment into a *demonstrated* full boot. Captured
live on `l0b-hardened2-probe` under the enforcing proposed profile:

| Proof | Result | Under enforcing seccomp? |
|---|---|---|
| `getprop sys.boot_completed` | **`1`** | ✅ |
| `getprop dev.bootcomplete` | **`1`** | ✅ |
| `getprop init.svc.zygote` | **`running`** (stable, held 60s+) | ✅ |
| `getprop init.svc.zygote_secondary` | **`running`** | ✅ |
| `/sbin/magisk -V` | **`30600`** (`30.6:MAGISK:D`) | ✅ |
| `su 0 id` | **`uid=0(root) gid=0(root) groups=…`** | ✅ |
| `mount \| grep magisk` | **`magisk on /sbin type tmpfs (rw,relatime,mode=755)`** | ✅ |
| seccomp `defaultAction` on probe (docker inspect) | **`SCMP_ACT_ERRNO`** | ✅ |

The `magisk --setup-sbin` tmpfs-at-`/sbin` mount — the exact Phase-4/5 "AT RISK"
item — **succeeded under the enforcing profile** (proven by the `magisk on /sbin
type tmpfs` mount line). Seccomp was **never disabled to force a pass**; the
verdict run used the enforcing `redroid-seccomp-l0b.json` (a LOG-mode copy was
used only diagnostically, see §4).

---

## 1. The honest correction to Phase 5: seccomp was NOT the only — or even the
## primary — blocker for a *full* hardened boot

Phase 5 correctly identified the 4 syscalls (`arch_prctl`, `personality`,
`setns`, non-bind `mount`) needed by Magisk/Bionic, and flagged a "separate
finding" that caps + device-cgroup also block boot. Phase 6 **executed the full
boot** and found the precise truth:

1. **The seccomp amendment alone is necessary but NOT sufficient.** With the 4
   syscalls allowed (enforcing), the container still crash-looped — but **not on
   seccomp.** A LOG-mode diagnostic copy (allow-all + log) proved zero
   seccomp-attributable boot failures: under LOG-mode it *still* crash-looped
   identically, and even `--cap-add ALL` + LOG-mode still failed. **Therefore
   seccomp is exonerated as the full-boot blocker.** (Caveat learned: enforcing
   `SCMP_ACT_ERRNO` does NOT emit `type=1326` audit records, so "no type=1326"
   does not mean "no seccomp denial" — the LOG-mode cross-check is what actually
   exonerates it.)

2. **The real full-boot blockers were two NON-seccomp items the production L0b
   posture imposes:**

   - **(A) Docker's default `/proc` + `/sys` path masking.** Non-privileged
     containers get `maskedPaths`/`readonlyPaths` on `/proc/*` and `/sys/*`.
     Android init's second stage writes those extensively; with the mask it
     crash-looped right after "init second stage started!" (the 154-line
     suppressed burst). **Fix (NOT `--privileged`):** `--security-opt
     systempaths=unconfined` — the documented, scoped Docker flag that removes
     the proc/sys mask without granting full privilege. This single flag flipped
     the probe from crash-loop to `Up`.

   - **(B) The curated 14-cap L0b set is too small for Android init.** With masks
     removed, init reached service start but `system_suspend` then crash-looped:
     `init: cap_set_proc(68719476736) failed: Operation not permitted` /
     `cannot set capabilities for system_suspend`. `68719476736 = 0x1000000000 =
     CAP_BLOCK_SUSPEND (36)`, absent from the curated set. Because init cannot
     hold a cap outside the container bounding set, the per-service `cap_set_proc`
     fails and the service dies, stalling boot. Widening the cap set fixed it.

3. **`no-new-privileges:true` is INCOMPATIBLE with Magisk's patched init.** With
   `no-new-privileges:true` set (a production L0b requirement), the container
   exited in <1s with empty logs and an empty `/data` — Magisk's `magiskinit`
   re-exec path needs to gain privileges during boot, which the flag forbids.
   **`no-new-privileges` had to be DROPPED for the rooted image to boot at all.**
   This is a hard finding for the board: the production L0b hardening flag
   `no-new-privileges:true` and a Magisk-rooted image are mutually exclusive.

---

## 2. FINAL minimal set actually used (the demonstrated GREEN config)

`docker run` (NO `--privileged`, NO `--pid=host`, NO `--network=host`,
NO docker.sock), enforcing custom seccomp:

```
docker run -d --name l0b-hardened2-probe \
  --cap-drop ALL \
  --cap-add SYS_ADMIN \           # mount(2) of proc/sysfs/tmpfs/devpts (kernel cap check, NOT seccomp)
  --cap-add SYS_NICE --cap-add SYS_RESOURCE --cap-add SYS_PTRACE \
  --cap-add MKNOD --cap-add SETUID --cap-add SETGID --cap-add SETPCAP \
  --cap-add NET_BIND_SERVICE --cap-add DAC_OVERRIDE --cap-add DAC_READ_SEARCH \
  --cap-add FOWNER --cap-add FSETID --cap-add KILL --cap-add AUDIT_WRITE \
  --cap-add CHOWN \               # logd socket lchown
  --cap-add BLOCK_SUSPEND \       # system_suspend cap_set_proc (the boot-stall fix)
  --cap-add SETFCAP \
  --cap-add NET_ADMIN --cap-add NET_RAW --cap-add IPC_LOCK \
  --cap-add SYS_BOOT --cap-add WAKE_ALARM --cap-add SYS_TTY_CONFIG \
  --cap-add LINUX_IMMUTABLE --cap-add SYSLOG \
  --security-opt seccomp=<repo>/agents/stability/stack/seccomp/redroid-seccomp-l0b.json \
  --security-opt apparmor=unconfined \
  --security-opt systempaths=unconfined \   # removes /proc /sys mask (NOT --privileged)
  --device-cgroup-rule "a *:* rwm" \         # ReDroid creates its own private binderfs nodes
  --device /dev/ashmem:/dev/ashmem \
  -v /tmp/l0b-hardened2-data:/data -p 127.0.0.1:15558:5555 \
  redroid/redroid:12.0.0_magisk \
  androidboot.hardware=redroid androidboot.redroid_gpu_mode=guest androidboot.redroid_google_play_store=0
```

**Notably NOT set (vs the production L0b compose):** `no-new-privileges:true`
(incompatible with Magisk init — see §1.3).

### 2a. Capability delta vs the curated 14-cap L0b set
**Added (11):** `SYS_ADMIN, CHOWN, BLOCK_SUSPEND, SETFCAP, NET_ADMIN, NET_RAW,
IPC_LOCK, SYS_BOOT, WAKE_ALARM, SYS_TTY_CONFIG, LINUX_IMMUTABLE, SYSLOG`
(SYS_ADMIN and BLOCK_SUSPEND are the two load-bearing for boot; the rest mirror
what Android init expects and were added together to reach a stable boot — a
follow-up minimisation pass could bisect which of the non-load-bearing extras are
strictly required).

### 2b. Device / device-cgroup
- `--device /dev/ashmem:/dev/ashmem` (host ashmem char dev 10:59).
- `--device-cgroup-rule "a *:* rwm"` — ReDroid mounts its **own private
  binderfs** inside the container and `mknod`s `binder`/`hwbinder`/`vndbinder`
  (host has only `/dev/binderfs/binder-control` 241:0, no static binder nodes).
  A narrower `c 241:* rwm` + `c 10:59 rwm` was tried and was NOT itself the
  blocker (boot failed on masks/caps, not device-cgroup), so the broad `a *:* rwm`
  was used for the GREEN run; a follow-up can re-narrow to `c 241:* rwm`+`c 10:59 rwm`
  now that the mask/cap blockers are understood.

### 2c. Seccomp syscall delta — `redroid-seccomp-l0b.json` vs `redroid-seccomp.json`
The proposed profile is an exact copy of the production profile with ONE added
`SCMP_ACT_ALLOW` group (the `PHASE5-AMENDMENT` block) and `defaultAction` still
`SCMP_ACT_ERRNO`. Syscalls moved into ALLOW:

| Syscall | x86_64 # | Was (production) | Now (l0b proposal) | Why |
|---|---|---|---|---|
| `arch_prctl` | 158 | denied (default) | ALLOW | Bionic linker sets TLS base (ARCH_SET_FS) on every process; denied → linker64 segfault |
| `personality` | 135 | explicit-deny list | ALLOW | Bionic reads/sets personality on every zygote fork; denied → `F libc` zygote crash-loop |
| `setns` | 308 | explicit-deny list | ALLOW | magiskd enters per-process mount ns for module overlay |
| `mount` | 165 | ALLOW **only MS_BIND** (arg-filtered) | ALLOW **unfiltered** | ReDroid init tmpfs/proc/sysfs + `magisk --setup-sbin` tmpfs are non-bind; seccomp cannot filter fstype string |

Everything else is byte-identical: `process_vm_readv/writev` stay REMOVED;
`ptrace` stays PTRACE_TRACEME-only; `init_module`, `finit_module`, `kexec_*`,
`reboot`, `pivot_root`, `swapon/swapoff`, `iopl/ioperm`, `sysfs`,
`set{domain,host}name`, etc. stay DENIED.

**Residual EPERMs that did NOT block boot (left as-is — documented, not fixed):**
- `logd` `cap_set_proc(0x440000000)` = AUDIT_CONTROL(30)+SYSLOG(34): logd's
  capset still partially fails (AUDIT_CONTROL not added) → logcat app buffer
  unavailable. **Non-fatal:** boot_completed=1, zygote running regardless.
- one networking service `cap_set_proc(0x13000)` = NET_ADMIN(12)+NET_RAW(13)+
  **SYS_MODULE(16)**: SYS_MODULE deliberately NOT granted (loads kernel modules —
  too dangerous). Service degrades gracefully; non-fatal to boot.
- `createProcessGroup(...) failed: Read-only file system` (cgroup) and
  `mount("selinuxfs") failed No such file or directory` — both also occur on the
  `--privileged` control and are tolerated by ReDroid; non-fatal.

---

## 3. Spoof delta — parity confirmed; delta is a module/snapshot property

The Phase-6 *new* result is the **hardened BOOT+ROOT under enforcing seccomp**,
NOT a fresh module deploy. The 2 Magisk spoof modules were NOT re-deployed in
Phase 6 (re-deploy is costly — Phase 4 needed 3 boot-breaker fixes + asset
seeding — and would risk destabilising the clean hardened boot we set out to
prove). Instead, per the task's authorised fallback, parity was confirmed:

### 3a. Unspoofed parity (LIVE capture from the hardened probe → scored)
Captured the unspoofed surface live from `l0b-hardened2-probe` (enforcing
seccomp) into `p21/l0b-hardened2-unspoofed-2026-05-30.yml`. Every field is
byte-for-byte identical to Phase-4's `p21/l0b-probe-unspoofed-2026-05-29.yml`
(same image): same fingerprint/model/brand/abi, same `/system/xbin/su`,
same `com.topjohnwu.magisk`, same host-kernel `/proc/version` leak.

`detection-cli run --snapshot p21/l0b-hardened2-unspoofed-2026-05-30.yml`:
> **weightedScore = 0.3815, criticalFailures = 5, category = DETECTED**

Identical to Phase-4 unspoofed (0.3815 / 5 / DETECTED). **Parity proven** — the
hardened enforcing-seccomp posture presents the same detection surface as the
privileged Phase-4 posture.

### 3b. Spoofed endpoint (re-score of the Phase-4 spoofed snapshot)
`detection-cli run --snapshot p21/l0b-probe-spoofed-2026-05-29.yml`:
> **weightedScore = 0.2344, criticalFailures = 2, category = SUSPICIOUS**

### 3c. Confirmed delta
| Snapshot | weightedScore | crit | category |
|---|---|---|---|
| hardened-unspoofed (live, enforcing seccomp) | **0.3815** | 5 | DETECTED |
| Phase-4 spoofed (re-scored) | **0.2344** | 2 | SUSPICIOUS |
| **DELTA** | **−0.1471 (−38.6%)** | **−3** | DETECTED → SUSPICIOUS |

**Honest framing:** detection-cli scores a *snapshot* (the getprop/file surface),
not the live security posture. The spoof delta is therefore a property of the
**modules + snapshot inputs**, not of the seccomp/cap posture — the modules mutate
the same prop/file surface whether the container is privileged or hardened. So the
~0.38→~0.23 delta **holds** in the hardened config (the unspoofed endpoint is
proven at parity by live capture; the spoofed endpoint is the same module output
re-scored). What Phase 6 newly proves is that the **unspoofed 0.3815 surface is
now reachable under enforcing seccomp without `--privileged`** — which Phase 4
(privileged) could not claim.

---

## 4. Method note — diagnostic LOG-mode (seccomp never disabled for a verdict)

To exonerate seccomp (since enforcing ERRNO does not audit), a LOG-mode copy
(`defaultAction=SCMP_ACT_LOG`, all explicit-deny groups → LOG) was used purely
diagnostically. It allows+logs what the verdict profile would ERRNO. Under
LOG-mode the boot **still** crash-looped identically (and only logged
`syscall=310 process_vm_readv` ×199, a benign side effect), proving the blocker
was non-seccomp (masks/caps). **Every verdict claim in §HEADLINE used the
enforcing `SCMP_ACT_ERRNO` `redroid-seccomp-l0b.json`** (confirmed via
`docker inspect … SecurityOpt`). LOG/DIAG copies were `/tmp`-only and removed at
teardown.

---

## 5. Security trade-off of the widened profile (board guidance)

The proposed `redroid-seccomp-l0b.json` re-permits two syscalls the production
profile deliberately forbade and drops one arg-filter:

| Item | Risk | Recommendation |
|---|---|---|
| `arch_prctl` | **Low.** Only manipulates the calling thread's FS/GS base. Effectively mandatory for any Bionic/glibc userspace; in Docker's default profile. | Keep (blanket allow). |
| `personality` | **Medium.** Can request `READ_IMPLIES_EXEC`/`ADDR_NO_RANDOMIZE` for the calling process only (cannot affect host/other containers). | Prefer the **arg-filtered** Docker form (allow only `{0x0,0x8,0x20000,0x20008,0xffffffff}`) so a process cannot self-disable ASLR. Re-test the arg-filtered form on a fresh throwaway before promotion (Phase 6 validated the blanket allow end-to-end). |
| `setns` | **HIGHEST.** Namespace re-entry — the production profile's own comment removed it precisely because "namespace re-entry would let an escaped uid attach to the host PID namespace." | **Scrutinise hardest.** Bounded here by no `--pid=host`/`--network=host`. **Recommendation: OMIT `setns` if Magisk module mount-namespace isolation is not required** — root + `--setup-sbin` + global-ns modules still work without it; only Magisk's per-process mount-ns isolation degrades. Keep only with explicit board sign-off. |
| `mount` (unfiltered) | **Medium.** In-container root can mount arbitrary fstypes into its own mount ns; cannot affect host mounts; `pivot_root`/`swapon` stay denied. seccomp cannot narrow by fstype (pointer arg), so MS_BIND-only is incompatible with the required tmpfs/proc mounts — no tighter seccomp-only middle ground. | Keep (unfiltered), bounded by cap/ns scope. |

**Bigger-picture caveat for the board (beyond seccomp):** reaching a *full*
hardened boot also required dropping `no-new-privileges:true` (incompatible with
Magisk init), adding `--security-opt systempaths=unconfined` (un-masks /proc,/sys),
adding `CAP_SYS_ADMIN` + `CAP_BLOCK_SUSPEND` (+ ~9 more caps), and broad
device-cgroup access. **Net: a Magisk-rooted L0b is materially less hardened than
the unrooted L0a/L1 cells** — it is enforcing-seccomp (good) but it is NOT
`no-new-privileges`, NOT minimal-cap, and NOT proc/sys-masked. The board should
weigh whether the rooted L0b's spoof value justifies this reduced isolation, or
whether rooted cells stay on a dedicated, more-isolated host tier.

---

## 6. Artifacts

- **Board-review proposal:** `agents/stability/stack/seccomp/redroid-seccomp-l0b.json`
  (enforcing `SCMP_ACT_ERRNO`; one commented `PHASE5-AMENDMENT` ALLOW group).
- Production `agents/stability/stack/seccomp/redroid-seccomp.json` — **UNTOUCHED**.
- Live parity snapshot: `p21/l0b-hardened2-unspoofed-2026-05-30.yml` (scored 0.3815).
- Reports: `/tmp/l0b-hardened2-unspoofed-report.json` (0.3815),
  `/tmp/l0b-spoofed-rescore.json` (0.2344).

---

## 7. Teardown & baseline safety

```
docker rm -f l0b-hardened2-probe          # done
rm -rf /tmp/l0b-hardened2-data            # done (sudo, root-owned Android dirs)
rm -f /tmp/redroid-seccomp-l0b.json /tmp/redroid-seccomp-l0b-DIAG.json   # diagnostic copies removed
# control containers used during isolation (l0b-ctrl-priv, l0b-priv-ref, l0b-test-nnp) removed
```
- `l0b-hardened2-probe` removed; `/tmp/l0b-hardened2-data` removed.
- `redroid/redroid:12.0.0_magisk` (`ba09a823a823`) left intact (reused, never rebuilt).
- **`redroid-test` baseline: Up 5h at start → Up 5h at end. Never stopped,
  removed, restarted, or written. Confirmed safe.**
- `agents/stability/stack/seccomp/redroid-seccomp.json` NOT modified. Seccomp was
  never disabled to force a pass (LOG-mode was diagnostic only; every verdict
  claim used enforcing `SCMP_ACT_ERRNO`).
- Host change made & reverted: kernel printk verbosity was raised to read init's
  suppressed log burst; this is volatile (no persistent kernel/host change, no
  reboot). No persistent host modification remains.
```

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

---

## 6. PROMOTION to PINNED PRODUCTION (2026-06-01, board-approved)

Following the arg-filter application (§1–§5 above) and its adversarial boot+root+negative-test
validation, the board approved promoting `redroid-seccomp-l0b.json` from PROPOSAL to the **PINNED
PRODUCTION** hardened seccomp profile for the L0b Magisk-rooted cell.

**This promotion is a metadata + integrity-pin governance change only.** No BPF rule was modified —
`defaultAction`, `defaultErrnoRet`, `archMap`, and every `syscalls[].action/names/args` are
byte-identical to the arg-filtered profile from commit bded617. Verified: the functional fingerprint
(over those fields only) is unchanged at `debfd521df856e1c9e31c6d113cb8c41482705fc3ac77e06dbb176c82bef3010`
before and after the relabel.

### What changed
- **Profile metadata (relabel only):** `_comment_purpose`, `_comment_compliance` rewritten to declare
  the board-approved pinned-production status and drop the contradictory "PROPOSAL / NOT production /
  board review required" assertions (technical hardening description preserved). `_proposal_provenance`
  renamed → `_promotion_provenance`, and a new `_promoted` field records the promotion decision.
- **Integrity pin:** new top-level key `seccomp_l0b_production` in
  `agents/stability/stack/image-pins.yml` (layer L0b, status production-pinned,
  `promoted_at_utc: 2026-06-01`) + a `seccomp-l0b-sha256-match` preflight verification check mirroring
  `local-module-file-sha256-match`.
- **Audit cross-ref:** `RESULT.md` gained a one-line `STATUS: PROMOTED 2026-06-01` banner; its
  historical "PROPOSAL ARTIFACT" text is retained for provenance.

### Final pinned sha256
The relabel changes the file bytes, so the pinned hash is the **post-edit** value:

```
PRE-EDIT  (HEAD, arg-filtered proposal): 53913f105ba505d1faeeed86189b26adeae8ec05da128d43a0fb36dd8d5197ac
POST-EDIT (pinned production profile):   d317a7a335f8f7cb3c557342959eb7d36875016c7581bf5433977bf527ada66a
```

`d317a7a3…ada66a` is the value pinned in `image-pins.yml` and enforced by `seccomp-l0b-sha256-match`.

### Validation
- Functional fingerprint byte-identical before/after (only `_comment`/metadata differ). JSON VALID.
- `image-pins.yml` parses as valid YAML; the new pin + check are present.
- Python suite stays green (lifecycle test asserts the seccomp PATH contains `l0b`, not BPF content).
- **NOT committed** — adversarial validator gates the commit and will independently boot-re-test the
  production-labelled profile.

---

## 7. ENFORCEMENT — the pin is now CODE-ENFORCED, not declarative-only (2026-06-01)

§6 promoted the profile and declared the `seccomp-l0b-sha256-match` verification check in
`image-pins.yml`, but **nothing in code actually verified it** — the hardened-boot path
(`build_hardened_run_argv` / `HARDENED_SECCOMP`) used the profile WITHOUT checking its sha256. An
unenforced pin cannot catch tampering or an unapproved BPF edit. This section records wiring the
enforcement.

### Mechanism
New function in `agents/orchestrator/src/container_lifecycle.py`:

```
verify_hardened_seccomp_pin(pins_path: str | None = None,
                            seccomp_path: str = HARDENED_SECCOMP) -> None
```

- Resolves `image-pins.yml` and the profile relative to the repo root
  (`_REPO_ROOT = Path(__file__).resolve().parents[3]`), independent of CWD.
- Reads `seccomp_l0b_production.file_sha256` via PyYAML (the module already imports `yaml` in
  `main()`); a robust single-line regex fallback parses the pin if `yaml` is unimportable, so the
  safety check never silently degrades to "pass".
- Computes `sha256` of the profile bytes and compares to the pin.
- On match → returns `None`. On **mismatch / missing pin key / missing profile** → raises
  `SeccompPinDriftError` with a message stating expected-vs-actual and
  *"refuse to boot: production seccomp profile drifted from pin; file a pin-update mutation"* —
  mirroring the SPEC §7 exit-78 refuse-privileged posture. (`main(["--verify-seccomp-pin"])` maps
  the drift to **exit 78**.)

`build_hardened_run_argv` stays a **pure argv builder** (no file I/O added; its argv output and pure
tests are unchanged). The verification is added at the BOOT CHOKEPOINTS where the argv is actually
executed:

| Chokepoint | Where | Effect |
|---|---|---|
| **L0b launch script** (the real hardened boot) | `agents/stability/stack/launch-l2-l6-sensor-lte-spoof.sh` — calls `verify_hardened_seccomp_pin()` immediately before `subprocess.run(argv)` | A drifted profile raises and aborts before `docker run` |
| **`up(... dry_run=False)`** (compose boot helper) | `container_lifecycle.up()` calls it before `docker compose up` | Real compose boots are gated too |
| **CLI** | `container_lifecycle --verify-seccomp-pin` (exit 78 on drift) | Preflight/CI hook to assert the pin |

### Tests (added to `tests/test_orchestrator_container_lifecycle.py`)
- `test_verify_seccomp_pin_passes_against_real_pinned_profile` — current profile sha == `d317a7a3…ada66a`.
- `test_verify_seccomp_pin_raises_on_tampered_profile` — tampered bytes → `SeccompPinDriftError`
  (asserts the expected sha + "pin-update mutation" appear in the message).
- `test_verify_seccomp_pin_raises_when_pin_key_missing` — pins file without the key → raises.
- `test_verify_seccomp_pin_raises_when_profile_missing` — missing profile → raises (no silent pass).
- `test_main_verify_seccomp_pin_flag_ok` — the `--verify-seccomp-pin` CLI returns 0 on a clean match.

### Validation
- `python3 -m pytest -q` → **116 passed** (was 111; +5). The pure argv / device-cgroup tests are
  unchanged and green; `build_hardened_run_argv` argv output is untouched.
- The seccomp profile bytes and the pinned sha (`d317a7a3…ada66a`) are **unchanged** — this adds
  verification only.
- **NOT committed** — adversarial validator gates the commit.

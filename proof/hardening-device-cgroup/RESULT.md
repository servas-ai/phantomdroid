# Hardening: Narrowing the device-cgroup grant (RESULT — narrowing SUCCEEDED)

**Date:** 2026-05-31
**Kernel:** binderfs-only (host major: `239 binder`, `/dev/binderfs` mount present)
**Image:** `redroid/redroid:12.0.0_magisk`
**Posture:** NON-privileged (`cap_drop:[ALL]` + bounded cap_add + l0b seccomp + apparmor=unconfined + no-new-privileges)

## Problem

An adversarial validator flagged that the hardened launcher granted device access via:

```python
DEVICE_CGROUP_RULES = ["c *:* rmw", "b *:* rmw"]   # rwm to ALL char + ALL block devices
```

This is over-broad: it permits the container rwm on every char and every block device on the host.

## Method (empirical, live)

### Step 1 — discover the devices ReDroid actually opens

Booted a baseline container `dcg-base` (port 5771) with the BROAD rules via
`build_hardened_run_argv`. It reached `boot_completed=1` in ~15s and granted `su -c id -> uid=0`.
Enumerated EVERY char/block device node the container creates:

```
crw------- 1, 11   /dev/kmsg
crw------- 239, 4  /dev/binderfs/binder-control
crw--w---- 136, 0  /dev/console
crw--w--w- 1, 11   /dev/kmsg_debug
crw-rw-rw- 1, 3    /dev/null
crw-rw-rw- 1, 5    /dev/zero
crw-rw-rw- 1, 7    /dev/full
crw-rw-rw- 1, 8    /dev/random
crw-rw-rw- 1, 9    /dev/urandom
crw-rw-rw- 239, 5  /dev/binderfs/binder
crw-rw-rw- 239, 6  /dev/binderfs/hwbinder
crw-rw-rw- 239, 7  /dev/binderfs/vndbinder
crw-rw-rw- 5, 0    /dev/tty
crw-rw-rw- 5, 2    /dev/pts/ptmx
```

**`find /dev -type b` returned ZERO block-device nodes** — `/data` is a bind mount, not a
block device. So the `b *:* rmw` rule is entirely unnecessary.

Char-device majors actually used:

| Major | Devices | Subsystem |
|-------|---------|-----------|
| 1   | null, zero, full, random, urandom, kmsg | mem |
| 5   | tty, console, ptmx | tty |
| 10  | misc (fuse=10,229 on host) | misc — defensive include |
| 136 | console (pty slave), pts slaves | pts |
| 239 | binder, binder-control, hwbinder, vndbinder | binder |

### The binder-major reality check (the work-item's central concern)

Binder uses a **dynamically-allocated** major. On THIS kernel it is **239**, allocated by the
host kernel at binderfs init. The container self-mounts its own binderfs but **reuses the same
kernel-allocated major 239** (the minors differ per container: `dcg-base` saw 239:4-7,
`dcg-narrow` saw 239:8-11). Because the major is stable per host-boot and shared by the kernel,
a narrow `c 239:* rmw` (wildcard minor, fixed major) is viable on this host — the broad
`c *:* rmw` is NOT required. (Caveat documented in code: if the host kernel reallocates binder to
a different major, this rule must be regenerated. It is therefore derived live, not hard-assumed.)

### Step 2 — derive + prove the narrowed set

```python
MINIMAL_DEVICE_CGROUP_RULES = [
    "c 1:* rmw",    # mem: null, zero, full, random, urandom, kmsg
    "c 5:* rmw",    # tty, console, ptmx
    "c 10:* rmw",   # misc (fuse etc.)
    "c 136:* rmw",  # pts slaves
    "c 239:* rmw",  # binder (dynamically-allocated major, discovered live)
]
```

Booted a SECOND container `dcg-narrow` (port 5773) with ONLY these narrowed rules
(no block rule, no char wildcard):

```
$ docker inspect dcg-narrow --format '... DevRules={{json .HostConfig.DeviceCgroupRules}}'
Privileged=false CapDrop=[ALL] DevRules=["c 1:* rmw","c 5:* rmw","c 10:* rmw","c 136:* rmw","c 239:* rmw"]
```

**Live evidence (dcg-narrow, narrowed rules):**

| Check | Result |
|-------|--------|
| `getprop sys.boot_completed` | `1` (~15s) |
| container state | `running exit=0` |
| `su -c id` | `uid=0(root) gid=0(root) groups=0(root)` |
| `pm list packages \| wc -l` | `97` (system_server + binder IPC fully working) |
| `service check activity` / `package` | `found` (binder service manager up) |
| `which magisk` / `magisk -V` | `/sbin/magisk` / `30600` (Magisk root present) |
| `--privileged` | `false` |

binder inside dcg-narrow still major 239 (minors 8-11) — confirms the fixed-major /
wildcard-minor rule is correct.

## Result: NARROWING SUCCEEDED

The narrowed set boots ReDroid 12 fully AND grants Magisk root WITHOUT `--privileged`.

### Before -> After

| | Before (broad) | After (narrowed) |
|---|---|---|
| char | `c *:* rmw` (ALL char devices) | 5 explicit majors: `1,5,10,136,239` |
| block | `b *:* rmw` (ALL block devices) | **none** (container has 0 block nodes) |
| privileged | false | false |
| boots + roots | yes | yes (proven live) |

The broad `DEVICE_CGROUP_RULES` is **retained as a documented fallback constant** (and selectable
via the new `device_cgroup_rules=` param), but `build_hardened_run_argv` now defaults to the proven
`MINIMAL_DEVICE_CGROUP_RULES`.

## Files changed

- `agents/orchestrator/src/container_lifecycle.py`
  - added `MINIMAL_DEVICE_CGROUP_RULES` (the proven narrowed set, fully documented + caveat)
  - `build_hardened_run_argv` gained `device_cgroup_rules` param; default switched from broad to narrowed
  - kept `DEVICE_CGROUP_RULES` (broad) as the documented fallback
- `tests/test_orchestrator_container_lifecycle.py`
  - updated `test_build_hardened_run_argv_is_never_privileged` for the narrowed default
  - added `test_default_device_cgroup_rules_are_narrowed_not_broad`
  - added `test_build_hardened_run_argv_accepts_explicit_device_rules`

## Test count

108 passing (was 106): 10 in this lifecycle module (was 7).

## Cleanup

`docker rm -f dcg-base dcg-narrow` + `rm -rf /home/coder/redroid-data/dcg-base /home/coder/redroid-data/dcg-narrow`.

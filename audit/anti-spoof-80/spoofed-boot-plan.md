# Spoofed Live ReDroid 12 Boot Plan (L1 = Magisk + Props/cpuinfo Overlays)

**Scope:** Defensive research. Bring up ONE spoofed live ReDroid 12 container (`l1-spoof`)
on a binderfs-only privileged host so the in-repo detector apps observe a Pixel-7-class
device instead of the raw `redroid/*` baseline. READ-ONLY audit; this doc is for the
orchestrator to execute. All commands below are grounded in the cited repo files.

**Author:** research agent · **Date:** 2026-05-30 · **Target probe baseline:** `p21/live-k68-report.json` (DETECTED, weightedScore 0.338, 4 critical failures)

---

## 0. TL;DR — the load-bearing facts

| Fact | Evidence (repo file) | Consequence for this plan |
|---|---|---|
| Both candidate hosts are binderfs-only; ReDroid must run `privileged: true` with **NO** `--device` binder mappings and self-mounts its own binderfs | `p21/redroid-v12-live-booted-2026-05-30-server-par822349.yml` header lines 12-18; verified this session against local k6.8 | The hardened compose files (`L0a.yml`, `L0b.compose.yml`, `L1.compose.yml`) that map `/dev/binder` + `cap_drop:[ALL]` will **NOT boot** on these hosts. Do NOT use them as-is. |
| The documented Magisk install path (`pm install` + Direct Install) is FALSE for ReDroid | `audit/phase4-root-method-2026-05-29.md` §1 | ReDroid has no boot.img ramdisk; `pm install` yields only the Manager UI, no `magiskd`. L1-MAGISK-RUNBOOK §4's `pm install` sequence does not produce root. |
| The REAL root method = build a rooted image via `redroid-script` (bootanim.rc hijack + `magisk --setup-sbin`) | `audit/phase4-root-method-2026-05-29.md` §1-§2 | Requires building `redroid/redroid:12.0.0-magisk` locally; pulls a third-party Magisk Delta fork APK. **Owner-gated.** |
| No Magisk APK/zip exists in the repo | `image-pins.yml` `magisk_v27_2.apk_sha256: "pending-runtime-verification"`; `find . -iname '*magisk*'` returns only module dirs + docs, no binary | **BLOCKER B1** (see §6). |

**Bottom line:** A true L1 *spoofed* boot (Magisk daemon + overlays applied) is **owner-gated and
blocked** on a missing/unpinned Magisk artifact and an unbuilt rooted image. What CAN be brought up
unblocked today is the **unspoofed L0a privileged baseline** (proven to boot — it produced
`p21/.../k68.yml` and `live-k68-report.json`). The spoof layer on top requires resolving §6 blockers.

---

## 1. What an L1 spoofed boot requires (component inventory)

### 1.1 Base image (pinned)
- **Image:** `redroid/redroid:12.0.0_64only-latest`
  - amd64 digest: `sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3`
  - arm64 digest: `sha256:3c7f9450188226bf8042c0159b9b38abe2106f1ceb0dfa45fae1cbc6409cd9bb`
  - Source: `agents/stability/stack/image-pins.yml::redroid_12_64only`
- For a **spoofed** boot the base must first be rebuilt into a **rooted** image
  `redroid/redroid:12.0.0-magisk` (see §1.2). The stock base alone gives no root → no overlays.

### 1.2 Magisk apparatus (the root-of-trust — currently a BLOCKER)
- **Documented baseline (layers.md §L0):** Magisk v27.2, ReZygisk v1.3.4+, LSPosed (JingMatrix) v1.10.1,
  built-in Zygisk OFF, Enforce DenyList OFF.
- **Real install mechanism** (`audit/phase4-root-method-2026-05-29.md`): build-time injection of Magisk
  binaries under `/system/etc/init/magisk/`, rewrite `/system/etc/init/bootanim.rc` to run
  `magisk --auto-selinux --setup-sbin`, commit as `redroid/redroid:12.0.0-magisk`. Built via
  `ayasa520/redroid-script` `python3 redroid.py -a 12.0.0 -m`.
- **Magisk APK provenance:** the script downloads a **Magisk Delta / HuskyDG fork** (NOT topjohnwu
  official). This is the supply-chain decision the owner must rule on.
- **Seccomp survival caveats** (if a hardened posture is ever attempted instead of privileged):
  - `magisk --setup-sbin` tmpfs mount: AT RISK under `redroid-seccomp.json` (allows `mount` only with MS_BIND).
  - cpuinfo-overlay bind mount: OK.
  - spoof-stack-magisk resetprop/sysfs binds: OK.
  - NeoZygisk/ReZygisk ptrace-init: BLOCKED by the profile (ptrace restricted to PTRACE_TRACEME).
  - On a **privileged** host (the working posture) these seccomp constraints do not apply.

### 1.3 Spoof props that get overridden
Two overlapping module families set these. **List of key spoof props** (consolidated from
`infrastructure/spoof-stack-magisk/service.d/00-spoof.sh`, `.../post-fs-data.sh`, and the
`feat/CLO-132-props-overlay` branch):

| Property | Spoofed value | Source file |
|---|---|---|
| `ro.build.fingerprint` | `google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys` (Android-12 variant) **OR** `google/panther/panther:14/UQ1A.240205.004/11224170:user/release-keys` (Android-14, CLO-132) | 00-spoof.sh / CLO-132 build.prop |
| `ro.build.display.id` | `SP1A.210812.016.C2` | 00-spoof.sh |
| `ro.build.tags` | `release-keys` | 00-spoof.sh |
| `ro.build.type` | `user` | 00-spoof.sh |
| `ro.build.id` | `UQ1A.240205.004` (CLO-132) | CLO-132 build.prop |
| `ro.product.brand` | `google` | 00-spoof.sh / CLO-132 |
| `ro.product.model` | `Pixel 7` | 00-spoof.sh / CLO-132 |
| `ro.product.manufacturer` | `Google` | 00-spoof.sh / CLO-132 |
| `ro.product.device` / `ro.product.name` | `panther` | 00-spoof.sh |
| `ro.hardware` / `ro.product.board` | `panther` | 00-spoof.sh |
| `ro.board.platform` | `gs201` | 00-spoof.sh |
| `ro.product.cpu.abi` | `arm64-v8a` (single-arch; raw baseline leaks `x86_64,arm64-v8a` dual) | 00-spoof.sh |
| `ro.product.cpu.abilist` | `arm64-v8a,armeabi-v7a,armeabi` | 00-spoof.sh |
| `ro.product.cpu.abilist32` | `armeabi-v7a,armeabi` | 00-spoof.sh |
| `ro.product.cpu.abilist64` | `arm64-v8a` | 00-spoof.sh |
| `ro.boot.verifiedbootstate` / `ro.boot.vbmeta.device_state` | `green` | 00-spoof.sh |
| `ro.boot.flash.locked` | `1` | 00-spoof.sh |
| `ro.oem_unlock_supported` | `0` | 00-spoof.sh |
| `ro.secure` | `1` | 00-spoof.sh |
| `ro.debuggable` | `0` (raw baseline leaks `1`) | 00-spoof.sh |
| `ro.boot.selinux` | `enforcing` | 00-spoof.sh |
| `ro.serialno` | `HQ7Y0V3RJL` | 00-spoof.sh |
| `ro.sf.lcd_density` | `420` | 00-spoof.sh |
| `net.dns1` / `net.dns2` | `8.25.203.30` / `8.25.203.31` | 00-spoof.sh |
| `persist.sys.timezone` / `persist.sys.locale` | `America/Los_Angeles` / `en-US` | 00-spoof.sh |
| qemu props (`ro.kernel.qemu*`) | already empty on the redroid base (no clear needed) | k68.yml |

NOTE — fingerprint mismatch UNVERIFIED: `00-spoof.sh` pins an **Android-12** fingerprint while
`feat/CLO-132-props-overlay` and `layers.md` §L1 target **Android-14 (panther:14/UQ1A.240205.004)**.
These two module sources disagree; the orchestrator must pick ONE profile before applying both,
or `ro.build.fingerprint` will be set twice (last-applied wins). Flagged for owner decision.

### 1.4 /proc/cpuinfo overlay (cpuinfo-overlay module)
- Module dir: `agents/stability/stack/modules/cpuinfo-overlay/` (frozen commit `e0aae491…`).
- `service.sh` (late_start_service): `mount --bind` a synthesized Cortex-A78 / Tensor-G2
  `/system/etc/cpuinfo.spoofed` over `/proc/cpuinfo`; injects a stable per-container Serial.
- Spoofed values: `Hardware: ... Tensor G2`, `CPU implementer 0x41`, `CPU part 0xd44/0xd41`,
  `BogoMIPS 38.40`. (RUNBOOK §5 references `BogoMIPS 2.00` as acceptance — the in-tree
  `cpuinfo.spoofed` actually carries `38.40`; UNVERIFIED discrepancy in the runbook acceptance text.)

### 1.5 Mounts / volumes the modules need
- cpuinfo-overlay module pre-staged read-only: `<repo>/agents/stability/stack/modules/cpuinfo-overlay → /data/adb/modules/cpuinfo-overlay:ro` (`L1.compose.yml` volumes).
- props-overlay (CLO-132) module dir (if used): `<repo>/agents/stability/stack/modules/props-overlay → /data/adb/modules/props-overlay`.
- spoof-stack-magisk module (`infrastructure/spoof-stack-magisk/`): full Magisk magic-mount tree
  (`system/`, `service.d/`, `post-fs-data.sh`, `sysfs-binds.sh`) → `/data/adb/modules/spoof-stack-redroid-12`.
- Magisk-managed persistence dirs: `/data/adb/magisk`, `/data/adb/modules` (writable; `L0b.compose.yml` binds these).
- `mount --bind` over `/proc/cpuinfo` and the sysfs paths requires CAP_SYS_ADMIN inside the mnt
  namespace — satisfied automatically under `privileged: true`.

---

## 2. props-overlay work on branch `feat/CLO-132-props-overlay`

Commit `b9c16ec` "feat(props-overlay): implement Pixel-7 fingerprint Magisk module (CLO-132)".
Adds 5 files under `agents/stability/stack/modules/props-overlay/` (235 insertions):

- `module.prop` — `id=props-overlay`, `version=v0.1.0`, author emulator-builder.
- `system/build.prop` — static 5-field Pixel-7 overlay (Magisk magic-mounts it over `/system/build.prop`):
  - `ro.product.brand=google`
  - `ro.product.manufacturer=Google`
  - `ro.product.model=Pixel 7`
  - `ro.build.id=UQ1A.240205.004`
  - `ro.build.fingerprint=google/panther/panther:14/UQ1A.240205.004/11224170:user/release-keys`
- `service.sh` — late_start_service; re-applies the same 5 fields via `resetprop --persist`
  (idempotent: no-op when already set). Dual approach: static overlay + runtime resetprop, to
  survive both init-time and binder-property-service reads.
- `META-INF/com/google/android/update-binary` — minimal Magisk installer stub (`SKIPUNZIP=1`).
- `tests/profile-check.sh` — acceptance gate (id/version, idempotency 5-then-0 writes, field match).

This branch is the **Android-14** fingerprint source. It overlaps `00-spoof.sh` (Android-12) on
brand/manufacturer/model and conflicts on `ro.build.fingerprint` + `ro.build.id` → §1.3 NOTE.

---

## 3. CONCRETE bring-up sequence — binderfs-only privileged host

> Adapted from `audit/phase4-root-method-2026-05-29.md` §2/§5 (real root method) and the verified
> privileged binderfs launch model in `p21/redroid-v12-live-booted-2026-05-30-server-par822349.yml`.
> **The privileged form below is the ONLY posture verified to boot on these kernels.** It deliberately
> does NOT use `L1.compose.yml` (which maps `/dev/binder` and would fail to boot here).

### Phase A — Build the rooted image (OWNER-GATED — see §6 B1/B2)
```bash
# A1. Clone + pin the community build script (owner reviews diff, pins SHA)
git clone https://github.com/ayasa520/redroid-script.git /tmp/redroid-script
cd /tmp/redroid-script
git checkout <REVIEWED_SHA>            # BLOCKER B2: owner must pin a reviewed SHA

# A2. Build a locally-rooted ReDroid 12 image (downloads 3rd-party Magisk Delta APK — BLOCKER B1)
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python3 redroid.py -a 12.0.0 -m       # emits local image redroid/redroid:12.0.0-magisk
#   -> If owner repoints to topjohnwu official Magisk instead of the Delta fork,
#      substitute the APK before this step and re-verify the MD5/SHA pin.
```
If Phase A is not approved, STOP — only the unspoofed L0a baseline (§3 Phase B with the **stock**
`redroid/redroid@sha256:e6f799…` image and NO modules) can be brought up, and it will not spoof.

### Phase B — Launch `l1-spoof` (privileged, binderfs self-mount, loopback ADB)
```bash
# B1. Stage the module trees on the host (read-only source-of-truth from this repo)
REPO=/home/coder/vk-repos/phantomdroid
mkdir -p /tmp/l1-spoof-data/adb/modules
cp -a "$REPO/agents/stability/stack/modules/cpuinfo-overlay"        /tmp/l1-spoof-data/adb/modules/
cp -a "$REPO/infrastructure/spoof-stack-magisk"                     /tmp/l1-spoof-data/adb/modules/spoof-stack-redroid-12
# Optional Android-14 props module (resolve §1.3 fingerprint conflict FIRST):
# git --git-dir="$REPO/.git" archive feat/CLO-132-props-overlay \
#   agents/stability/stack/modules/props-overlay | tar -x -C /tmp/l1-spoof-stage
# cp -a /tmp/l1-spoof-stage/agents/stability/stack/modules/props-overlay /tmp/l1-spoof-data/adb/modules/

# B2. Launch the container — PRIVILEGED, NO --device binder mappings (self-mounts binderfs),
#     ADB on loopback only. (Posture verified to boot: par822349 + local k6.8.)
docker run -itd --privileged \
  --name l1-spoof \
  -p 127.0.0.1:15558:5555 \
  -v /tmp/l1-spoof-data:/data \
  redroid/redroid:12.0.0-magisk \
  androidboot.redroid_width=1080 \
  androidboot.redroid_height=2400 \
  androidboot.redroid_dpi=420 \
  androidboot.redroid_gpu_mode=guest \
  androidboot.use_memfd=1
#   NOTE: gpu_mode=guest (software) chosen for host-agnostic boot; the layers.md
#   stub used host GPU which requires a host GL device. UNVERIFIED whether guest
#   mode is needed on the specific target host — start with guest, switch to host
#   only if boot is GPU-blocked.

# B3. Wait for first boot_completed=1 (6-min budget, mirrors L1.compose.yml healthcheck)
for i in $(seq 1 36); do
  if docker exec l1-spoof sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "boot_completed=1 after ${i}0s"; break; fi
  sleep 10
done

# B4. Confirm the Magisk daemon is alive (THE real root gate the pm-install method never passed)
docker exec l1-spoof sh -c 'magisk --version' || echo "BLOCKER: no magiskd — image not actually rooted"

# B5. Enable Zygisk (Delta ships it OFF) and confirm modules are staged
docker exec l1-spoof sh -c "magisk --sqlite \"REPLACE INTO settings(key,value) VALUES('zygisk',1)\""
docker exec l1-spoof sh -c 'ls /data/adb/modules'   # expect cpuinfo-overlay, spoof-stack-redroid-12

# B6. Reboot so post-fs-data.sh (resetprop props + sysfs binds) and
#     service.d/00-spoof.sh + cpuinfo-overlay/service.sh (mount --bind /proc/cpuinfo) run
docker restart l1-spoof
for i in $(seq 1 36); do
  if docker exec l1-spoof sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "post-spoof boot_completed=1 after ${i}0s"; break; fi
  sleep 10
done
```

### Phase C — Verify boot + verify spoof took effect
```bash
# C1. Boot health
docker exec l1-spoof sh -c 'getprop sys.boot_completed'        # expect 1
docker exec l1-spoof sh -c 'getprop init.svc.zygote'           # expect running

# C2. Verify a build-prop spoof took effect (raw baseline = redroid/... per k68.yml)
docker exec l1-spoof sh -c 'getprop ro.build.fingerprint'      # expect google/panther/... NOT redroid/...
docker exec l1-spoof sh -c 'getprop ro.product.model'          # expect "Pixel 7" NOT redroid12_x86_64_only
docker exec l1-spoof sh -c 'getprop ro.product.cpu.abilist'    # expect arm64-v8a,... NOT x86_64,arm64-v8a
docker exec l1-spoof sh -c 'getprop ro.debuggable'             # expect 0 NOT 1

# C3. Verify the /proc/cpuinfo overlay
docker exec l1-spoof sh -c 'grep -m1 Hardware /proc/cpuinfo'   # expect Tensor G2 NOT host x86

# C4. Confirm Magisk module list
docker exec l1-spoof sh -c 'magisk --list' 2>/dev/null         # expect cpuinfo-overlay + spoof-stack enabled

# C5. Re-run the detector against 127.0.0.1:15558 and diff weightedScore vs
#     p21/live-k68-report.json (baseline DETECTED 0.338). Target: build-prop/cpuinfo
#     probes flip to clean; full 0.0 NOT expected (L0b root tells remain — see phase4 doc).
```

### Phase D — Teardown
```bash
docker rm -f l1-spoof && rm -rf /tmp/l1-spoof-data
```

---

## 4. Posture decision (why privileged, not the hardened compose)

The repo's `L0a.yml` / `L0b.compose.yml` / `L1.compose.yml` all use `cap_drop:[ALL]` +
explicit `/dev/binder` `--device` passthrough + `redroid-seccomp.json`. That posture:
- Maps host binder nodes that **do not exist** on binderfs-only kernels (k5.4 has binder as a
  module, `/dev/binder` absent; k6.8 is binderfs-only) → ReDroid cannot find binder → no boot.
- Even where nodes exist, the L1-MAGISK-RUNBOOK §6 escalation table itself anticipates
  `BINDER_SET_CONTEXT_MGR failed -EPERM` and a `[PRIVILEGED-OK]` escalation.

Therefore this plan uses `--privileged` with NO `--device` mappings (ReDroid self-mounts binderfs),
which is the **empirically verified** posture (`par822349` container ran `privileged=true`, no
`--device`, and reached `sys.boot_completed=1` with 96 packages). This is a deliberate, owner-gated
deviation from the hardened compose — flagged in §6 B3.

---

## 5. Expected detection delta (grounding the "spoof took effect" claim)

Baseline `p21/live-k68-report.json` = **DETECTED**, weightedScore 0.338, 4 critical failures.
Raw leaks the spoof layer fixes (per `p21/redroid-v12-live-booted-2026-05-30-k68.yml`):
- `ro.build.fingerprint` = `redroid/redroid_x86_64_only/...test-keys` → Pixel-7 release-keys.
- `ro.product.model` = `redroid12_x86_64_only` → `Pixel 7`.
- `ro.product.cpu.abilist` = `x86_64,arm64-v8a` (Houdini dual-arch tell) → single arm64.
- `ro.debuggable` = `1` → `0`; `ro.build.tags` = `test-keys` → `release-keys`.
- `/proc/cpuinfo` host x86 → Tensor G2.
- `ro.hardware` = `redroid` (emulator marker) → `panther`.

Residual tells that will NOT clear (honest caveat from phase4 doc): `/proc/version` host kernel
banner (sysfs-binds.sh attempts a `/proc/version` overlay but k68/par captures still leaked it —
UNVERIFIED whether the bind survives), `/system/xbin/su` presence, Magisk root signals. So target
is a **large delta toward ~0, not exactly 0.0**.

---

## 6. BLOCKERS (must resolve before a spoofed boot is possible)

| ID | Blocker | Exact thing needed | Evidence |
|---|---|---|---|
| **B1** | **No Magisk APK in repo; pin unresolved.** | A concrete Magisk APK (topjohnwu official OR the Delta fork the script pulls) with its SHA256 written into `image-pins.yml::magisk_v27_2.apk_sha256` (currently `pending-runtime-verification`). Without it, no rooted image, no daemon, no overlays. | `image-pins.yml` L90; `find . -iname '*magisk*'` = no binary |
| **B2** | **`redroid-script` SHA not pinned + supply-chain not approved.** | Owner reviews `ayasa520/redroid-script` diff, pins `<REVIEWED_SHA>`, and rules on Magisk Delta-fork vs topjohnwu provenance. | `audit/phase4-root-method-2026-05-29.md` §2,§4; `STATUS.md` L134 |
| **B3** | **`--privileged` vs hardened-posture decision is owner-gated.** | Owner explicitly approves running `l1-spoof` privileged (the repo's refuse-privileged-compose skill hard-blocks `privileged:true`; `container_lifecycle.py` preflight refuses it). | `agents/stability/stack/container_lifecycle.py` L58; `layers.md` §L0 deprecation note; `STATUS.md` L19/L61 |
| **B4** | **The in-repo L1-MAGISK-RUNBOOK §4 install method is wrong for ReDroid.** | Do NOT follow `pm install` + Direct Install — it yields Manager UI only, no `magiskd`. Use the §3 Phase A rooted-image build instead. | `audit/phase4-root-method-2026-05-29.md` §1 |
| **B5** | **Fingerprint profile conflict (Android-12 vs Android-14).** | Owner picks ONE: `00-spoof.sh` (panther:12/SP1A.210812.016.C2) OR `feat/CLO-132` (panther:14/UQ1A.240205.004). Applying both = last-writer-wins on `ro.build.fingerprint`. | §1.3 NOTE; `00-spoof.sh` L20 vs CLO-132 `system/build.prop` |
| **B6 (minor)** | **cpuinfo acceptance text mismatch (UNVERIFIED).** | RUNBOOK §5 expects `BogoMIPS 2.00`; in-tree `cpuinfo.spoofed` ships `38.40`. Reconcile before asserting acceptance. | `L1-MAGISK-RUNBOOK.md` §5 vs `cpuinfo.spoofed` |

What is NOT blocked: bringing up the **unspoofed** L0a privileged baseline (stock
`redroid/redroid@sha256:e6f799…`, no modules) — already proven (it produced k68.yml +
live-k68-report.json). That is the floor; everything above the floor needs B1-B3.

---

## 7. UNVERIFIED items (explicitly flagged)

- gpu_mode `guest` vs `host` on the target host (§3 B2 note) — start guest, confirm empirically.
- `androidboot.use_memfd=1` is a common ReDroid flag but not pinned in any repo compose — included
  as a typical stability flag; remove if boot fails.
- `/proc/version` overlay survival under the Magisk bind (§5).
- Whether the Delta-fork Magisk daemon coexists with `redroid-seccomp.json` is moot here because
  this plan runs privileged (no seccomp profile applied).

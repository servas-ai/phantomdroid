# L0a Hardened ReDroid 12 Baseline — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding); P22.3 (this file)
**Compose:** `agents/stability/stack/compose/L0a.yml`
**Image pins:** `agents/stability/stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L0 — Container Baseline
**E2E ground-truth evidence:** `audit/E2E-validation-2026-05-20.md` (live PAR822349 boot)

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.

---

## §1. Purpose

**L0a is the irreducible ReDroid 12 baseline** — the hardened, identity-neutral
container that every higher layer (L0b root modules, L1 spoof modules, L2..L6)
stacks on top of via compose overrides. It is the FIRST compose file consumed
by `container_lifecycle.py` preflight and the LAST one teared down.

What L0a provides:

- A pinned-by-digest `redroid/redroid` Android 12 container (amd64 by default;
  `container_lifecycle.py` rewrites the digest to arm64 when host is aarch64
  per `image-pins.yml::redroid_12_64only`).
- Kernel-feature passthrough via explicit `devices:` entries — `/dev/binder`,
  `/dev/hwbinder`, `/dev/vndbinder`, `/dev/ashmem`. **No `privileged: true`**;
  the refuse-privileged-compose preflight (`agents/stability/stack/compose/L0a.yml`
  header §1..§6) blocks the entire bring-up if any forbidden capability or
  host-namespace key sneaks in.
- ADB bound to host loopback (`127.0.0.1:15555:5555`) — never `0.0.0.0`.
- Isolated bridge network (`l0a-isolated-net`, 172.30.50.0/29).
- No spoofing. L0a-cell ReDroid 12 self-identifies as `redroid` across brand,
  manufacturer, model, hardware. This is the worst-case unspoofed baseline that
  L1+ spoof modules are measured against.

What L0a does NOT provide (deliberately):

- No Magisk, ReZygisk, LSPosed (those are L0b — see [[L0b-RUNBOOK]]).
- No cpuinfo overlay, no hide-frida-maps, no identity spoof (L1+ layers).
- No GApps / Play Store (`androidboot.redroid_google_play_store: "0"`).

---

## §2. Prerequisites

```bash
# §2.1 — Host kernel must have binder+ashmem
#   Two valid configurations:
#     (a) Ubuntu 22.04+ / kernel ≥5.4 with native binder + binderfs
#         (this is what fully boots ReDroid 12; sys.boot_completed=1)
#     (b) Ubuntu 18.04 / kernel 4.15 with anbox-modules DKMS
#         (boots far enough for props but sys.boot_completed stays empty;
#         see Known Issues §7.1)
for d in /dev/binder /dev/hwbinder /dev/vndbinder /dev/ashmem; do
  [[ -e "$d" ]] || { echo "MISSING $d on host"; exit 78; }
done

# §2.2 — Docker ≥24.0 with compose v2
docker version --format '{{.Server.Version}}' | awk -F. '$1<24 {exit 1}' \
  || { echo "Docker ≥24.0 required"; exit 78; }

# §2.3 — Pin-match check (image digest must match image-pins.yml)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' \
  agents/stability/stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L0a.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.4 — Forbidden-pattern preflight grep (refuse-privileged-compose 1..6)
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L0a.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN in L0a.yml"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.5 — Seccomp profile present
[[ -f agents/stability/stack/seccomp/redroid-seccomp.json ]] \
  || { echo "MISSING seccomp profile"; exit 78; }
```

---

## §3. Provisioning

```bash
# §3.1 — Pull pinned image (idempotent; no-op if already cached)
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' \
  agents/stability/stack/image-pins.yml)"

# §3.2 — Bring L0a baseline up (Stability-driven via container_lifecycle.py)
python3 agents/stability/stack/container_lifecycle.py up \
  --config mutations/sandbox/<proposal-id>/<cell>.json \
  --compose agents/stability/stack/compose/L0a.yml

# §3.2-alt — Direct bring-up for L0a-only debug (still satisfies preflight)
docker compose -f agents/stability/stack/compose/L0a.yml up -d redroid-l0a
```

The compose file applies the `*l0a-hardening` YAML anchor to every service:
`privileged: false`, `cap_drop: [ALL]`, narrow `cap_add` (15 caps, none of
them SYS_ADMIN/ALL), `no-new-privileges`, custom seccomp profile, and
`apparmor=unconfined`. Resource limits are CAX41-class (2 CPUs, 4 GiB
memory) per the `*l0a-resources` anchor.

**Live boot evidence** (PAR822349, 2026-05-20): Ubuntu 18.04 + DKMS modules
took ~30 minutes to go from fresh-OS to running container. ReDroid 12 amd64
pulled 1.52 GB by digest `sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3`.
Container state readable via `docker exec` even though `sys.boot_completed`
stays empty on kernel 4.15 (see §7.1).

---

## §4. Healthcheck

The compose-native healthcheck polls `getprop sys.boot_completed | grep -q 1`
every 10s for up to 24 retries (4-minute boot budget) with a 60s start-period
grace window. On kernel ≥5.4 hosts this returns healthy within ~60s of
container start. On kernel 4.15 hosts the property stays empty (see §7.1) so
the healthcheck eventually marks `unhealthy` — that is the diagnostic signal
to switch to a kernel-5.x host or upgrade DKMS to a binderfs-aware build.

```bash
# §4.1 — Belt-and-suspenders shell-side check (same 36×10s budget as L0b)
C=stability-l0a-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null \
       | grep -q '^1$'; then
    echo "[L0a] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done

# §4.2 — Prop-surface spot-check (these always populate, even pre-boot-complete)
docker exec "$C" sh -c 'getprop ro.build.fingerprint'
docker exec "$C" sh -c 'getprop ro.product.brand'
docker exec "$C" sh -c 'getprop ro.hardware'

# §4.3 — Container health status (compose healthcheck wrapper)
docker inspect --format '{{.State.Health.Status}}' "$C"
```

---

## §5. Expected baseline probe scores (live ground truth)

These are the **measured** probe scores on PAR822349's live L0a-only container
on 2026-05-20. Source: `audit/E2E-validation-2026-05-20.md`. L0a is the
unspoofed baseline, so every probe in this table fires hot — that is by
design. Higher SpoofStack layers (L1+) are measured by **how many of these
nine** they reduce to clean.

| Probe rank | Probe id | Signal observed on L0a | Score |
|---:|---|---|---:|
| 1 | `buildprop.fingerprint` | `redroid/redroid_x86_64_only/...:userdebug/test-keys` | **1.0** |
| 3 | `root.su_detection` | `/system/bin/su` present (ReDroid ships su by default) | **1.0** |
| 4 | `emulator.qemu_artifacts` | `ro.hardware=redroid` matches EMU_KEYWORDS | **1.0** |
| 7 | `buildprop.tags_and_type` | `test-keys` AND `userdebug` (both violations) | **1.0** |
| 9 | `buildprop.model_brand_manufacturer` | model=`redroid12_x86_64_only`, brand=manufacturer=`redroid` | **1.0** |
| 13 | `env.bootloader` | `ro.debuggable=1`; vbmeta props empty | **0.85** |
| 27 | `emulator.cpu_abi` | `ro.product.cpu.abilist = x86_64,arm64-v8a` (Houdini dual-arch) | **1.0** |
| 28 | `buildprop.board_hardware` | `ro.hardware=redroid` | **1.0** |
| 30 | `emulator.proc_version` | `/proc/version` leaks host Ubuntu kernel string | **0.85** |

**Composite emulator-likelihood at L0a**: 9 independent strong signals
(7× 1.0, 2× 0.85). Even if a future SpoofStack masks one or two of these,
the other seven remain — this validates the multi-probe Defense-in-Depth
design (see `audit/E2E-validation-2026-05-20.md` §"Cross-validation").

The remaining 77 probes in the 86-probe inventory did not yield measurable
signal in this single-cell shell-side capture — they require APK-inside-
container delivery (KeyAttestation, PlayIntegrity, sensor traces, etc.),
which is a separate work item (see STATUS.md "Gaps" §APK-inside-container).

---

## §6. Teardown

```bash
# §6.1 — Stability-driven teardown
python3 agents/stability/stack/container_lifecycle.py down \
  --compose agents/stability/stack/compose/L0a.yml

# §6.2 — Direct teardown (debug path)
docker compose -f agents/stability/stack/compose/L0a.yml \
  down --volumes --remove-orphans

# §6.3 — Volume sweep (L0a defines no volumes itself, but L0b/L1 overrides
#        on top of L0a do; this catches leftover stack-level volumes)
docker volume prune -f --filter label=research.owner=stability
```

Non-destructive of `image-pins.yml`, the seccomp profile, and the L0a.yml
file itself.

---

## §7. Known issues

### §7.1 Host-kernel 4.15 binderfs gap

`audit/E2E-validation-2026-05-20.md` documents the root cause: Android 12's
HIDL binder-RPC layer (`libhidlbase.so::configureBinderRpcThreadpool`)
requires binderfs (kernel ≥5.0), which the anbox-modules DKMS binder on
kernel 4.15 does not provide. Symptom: container runs and props populate,
but `sys.boot_completed` stays empty and `init.svc.zygote=restarting`. ADB
shell hangs; `docker exec` continues to work for prop capture.

**Resolution**: install Ubuntu HWE kernel 5.4+ (already staged on PAR822349
per E2E §"Path A executed" — pending owner-authorized reboot). Kernel 5.4+
ships upstream `binder_linux` + binderfs natively, so no DKMS rebuild is
needed for binder; `anbox-ashmem` still rebuilds automatically via DKMS.

### §7.2 `/proc/version` host-kernel leak (rank 30 — by design)

Linux containers share the host kernel; `/proc/version` therefore leaks the
Ubuntu launchpad-builder string and gcc version on a container claiming to
be Android 12. **This is a real ReDroid weakness that probe rank 30
correctly catches** — not an L0a bug. Mitigations live at L2+ (proc mount
masking) and are out of scope for L0a baseline. See `audit/E2E-validation-
2026-05-20.md` §"Spec deltas surfaced" item 1.

### §7.3 Houdini x86_64 emulation surface (rank 27)

ReDroid amd64 ships `abilist = x86_64,arm64-v8a` to allow ARM apps via the
Houdini bridge. This is a high-signal emulator marker that rank 27 catches.
L1+ identity-spoof layers can rewrite `ro.product.cpu.abilist`, but the
Houdini libraries themselves remain mapped in process memory — a deeper
fix lives at L4 (hide-frida-maps / module-list scrubbing) and is again
out of L0a scope.

### §7.4 Magisk APK SHA pinning (cross-layer)

When L0a is combined with L0b (typical operation), the Magisk APK SHA256
in `image-pins.yml::modules[id=magisk].artifact_sha256` is currently
`pending-runtime-verification`. L0a itself does not install Magisk so this
does not block L0a-only bring-up, but operators stacking L0b on top must
read [[L0b-RUNBOOK]] §6 first.

---

## §8. Cross-reference

- [[L0b-RUNBOOK]] — Magisk + ReZygisk root layer (typical override on L0a)
- [[L1-MAGISK-RUNBOOK]] — identity-spoof Magisk module layer
- [[L2-RUNBOOK]] — proc/sys mount masking
- [[L3-DEFAULT]] — TrickyStore (PlayIntegrity STRONG-surface spoof, see `L3-DEFAULT.md`)
- [[L4-RUNBOOK]] — Shamiko + hide-frida-maps
- [[L5-RUNBOOK]] — VirtualSensor synthesis
- [[L6-RUNBOOK]] — host-NAT egress shaping

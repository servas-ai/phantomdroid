# L0b Magisk + ReZygisk Baseline — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding)
**Compose:** `agents/stability/stack/compose/L0b.compose.yml`
**Image pins:** `stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L0 — Container Baseline

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.

---

## §1. Baseline-layer-set decision

**Decision: L0a + L0b.**

L0b is defined in `agents/stability/stack/layers.md` §L0 as: Magisk v27.2
with built-in Zygisk OFF, ReZygisk v1.3.4 supplying Zygisk, and LSPosed
(JingMatrix fork) v1.10.1+ for Java-side hooking. Enforce DenyList is OFF
at this layer (Shamiko takes over at L4).

The intent of an L0b cell is to measure rank-1..3 `root.*` probe signals
(magisk-binary file presence, magisk socket, magisk module list) BEFORE
any L4 hiding kicks in. This produces the worst-case rooted-baseline
signature that L4 mitigations are measured against.

---

## §2. Pre-flight

```bash
# §2.1 — Pin-match check (image digest)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L0b.compose.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.2 — Forbidden-pattern grep (refuse-privileged-compose checks 1..5)
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L0b.compose.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.3 — Host kernel binder/ashmem check
for d in /dev/binder /dev/hwbinder /dev/vndbinder /dev/ashmem; do
  [[ -e "$d" ]] || { echo "MISSING $d on host"; exit 78; }
done

# §2.4 — Results dirs
mkdir -p agents/stability/stack/compose/results/l0b/{data-adb-magisk,data-adb-modules,out}
chmod 0777 agents/stability/stack/compose/results/l0b/{data-adb-magisk,data-adb-modules,out}
```

---

## §3. Bring-up

```bash
# §3.1 — Pull pinned image
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)"

# §3.2 — Compose up
cd agents/stability/stack/compose
docker compose \
  -f L0a.yml \
  -f L0b.compose.yml \
  up -d

# §3.3 — Wait for boot_completed=1 (compose healthcheck does this; this is a
#        belt-and-suspenders check using the same 36×10s budget).
C=stability-l0b-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "[L0b] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done

# §3.4 — Install Magisk + ReZygisk + LSPosed (deterministic, hash-verified)
# Magisk APK and ReZygisk/LSPosed zips are downloaded once on the host
# and SHA-verified against stack/image-pins.yml::modules entries before
# pm install / module sideload. See the L1 runbook §4 for the canonical
# Magisk install sequence; the L0b sequence is identical except no
# cpuinfo-overlay module is pre-staged.
```

---

## §4. Acceptance criteria

- `getprop sys.boot_completed` returns `1` within the 36×10s budget.
- `docker exec $C su -c 'magisk --version'` returns `27.2`.
- `docker exec $C su -c 'magisk --list 2>/dev/null'` lists ReZygisk and
  LSPosed modules with status `enabled`.
- The Detection rank-1..3 `root.*` probe sweep (root.magisk_present,
  root.magisk_socket, root.su_binary_present) MUST positively detect
  Magisk on this cell. L0b is the worst-case rooted-baseline, NOT a
  hiding layer.
- `logcat -d -s ServiceManager:E *:F | head` is empty (no boot-time
  ServiceManager fatals).

---

## §5. Rollback

```bash
cd agents/stability/stack/compose
docker compose -f L0a.yml -f L0b.compose.yml down --volumes --remove-orphans
rm -rf results/l0b/
```

This is intentionally non-destructive of `stack/image-pins.yml` and the
seccomp profile.

---

## §6. Open questions / known gaps

- The Magisk APK SHA256 is currently `pending-runtime-verification` in
  `stack/image-pins.yml::modules[id=magisk].artifact_sha256`. The L0b
  bring-up MUST refuse to install if the SHA is unpinned; the pin must
  be filled via a pin-update mutation before this runbook can be
  considered production-grade.
- ReZygisk v1.3.4 / NeoZygisk v2.3 fallback selection is currently
  manual. A future stack-bootstrap script SHOULD detect ReZygisk
  unavailability and fall back to NeoZygisk per the alternative entry
  in `stack/image-pins.yml::modules[id=rezygisk].alternative`.

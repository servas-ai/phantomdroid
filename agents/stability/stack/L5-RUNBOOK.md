# L5 Sensor Emulation — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding)
**Compose:** `agents/stability/stack/compose/L5.compose.yml`
**Image pins:** `stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L5 — Sensor Emulation

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.

---

## §1. Baseline-layer-set decision

**Decision: L0a + L0b + L1 + L2 + L3 + L4 + L5.**

L5 sits on top of the full stack. Per `agents/stability/stack/layers.md`
§L5 the canonical modules are "VirtualSensor (modifiziert)" and a
trace-player replaying a 10-minute Pixel 7 sensor recording as a CSV
sequence. Target probes: `sensor.fft_accelerometer`,
`sensor.gyroscope_noise`, `sensor.gps_consistency` (#24, #42..#45).

**Known gap.** No concrete in-tree L5 module exists. The compose
bind-mounts `./results/l5/sensor-noise-module` and
`./results/l5/sensor-traces` are placeholders; until a module + a recorded
trace land, sensor probes will NOT show a hardened signature.

---

## §2. Pre-flight

```bash
# §2.1 — Pin-match check (image digest)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L5.compose.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.2 — Forbidden-pattern grep
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L5.compose.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.3 — Results dirs
mkdir -p agents/stability/stack/compose/results/l5/{sensor-noise-module,sensor-traces,out}
chmod 0777 agents/stability/stack/compose/results/l5/{sensor-noise-module,out}
chmod 0755 agents/stability/stack/compose/results/l5/sensor-traces  # read-only inside container
```

---

## §3. Bring-up

```bash
# §3.1 — Pull pinned image
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)"

# §3.2 — Compose up the full L0a..L5 stack
cd agents/stability/stack/compose
docker compose \
  -f L0a.yml \
  -f L0b.compose.yml \
  -f L1.compose.yml \
  -f L2.compose.yml \
  -f L3.compose.yml \
  -f L4.compose.yml \
  -f L5.compose.yml \
  up -d

# §3.3 — Wait for boot_completed=1 (36×10s budget)
C=stability-l5-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "[L5] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done
```

---

## §4. Acceptance criteria

- `getprop sys.boot_completed` returns `1` within the 36×10s budget.
- All lower-layer modules still load (`magisk --list` shows the L1..L4
  modules `enabled`).
- Once a concrete L5 module + a sensor-trace CSV are wired:
  - `sensor.fft_accelerometer` probe scores `<0.5` against a Pixel-7
    "in pocket" trace baseline.
  - `sensor.gyroscope_noise` probe scores `<0.5`.
  - `sensor.gps_consistency` probe scores `<0.5` for a stationary
    trace baseline.
- Fail-safe: removing the L5 bind-mounts and re-running compose-up
  MUST produce a clean L0a..L4 cell (no L5 residue).

---

## §5. Rollback

```bash
cd agents/stability/stack/compose
docker compose -f L0a.yml -f L0b.compose.yml -f L1.compose.yml \
               -f L2.compose.yml -f L3.compose.yml -f L4.compose.yml \
               -f L5.compose.yml \
               down --volumes --remove-orphans
rm -rf results/l5/
```

---

## §6. Open questions / known gaps

- No in-tree L5 module. Tracking gap: per `layers.md` §L5 the canonical
  reference is VirtualSensor (modified) plus a trace-player. Neither is
  pinned in `stack/image-pins.yml::modules` yet.
- The 10-minute Pixel-7 trace (pocket / hand / table) referenced in
  `layers.md` §L5 has not been captured. The trace CSV format and the
  ingest path inside the container (`/data/sensor-traces`) are
  documented in the compose bind-mount but no schema is defined.
- Interaction with the L3 attestation backend is not yet measured —
  some detectors cross-check the sensor signal against an attested
  device class; the layer-composition matrix needs an L3+L5 cell to
  measure this.

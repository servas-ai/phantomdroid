# L2 Identity Spoofing — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding)
**Compose:** `agents/stability/stack/compose/L2.compose.yml`
**Image pins:** `stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L2 — Identity Spoofing

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.

---

## §1. Baseline-layer-set decision

**Decision: L0a + L0b + L1 + L2.**

L2 sits on top of the full L0+L1 props baseline. Per
`agents/stability/stack/layers.md` §L2, the canonical module is "Android
Faker" supplying per-app-profile unique IDs for IMEI, Android ID, MAC,
BT-MAC, SSID, MediaDRM, SIM, and Operator. The module must persist across
container restarts for stable measurement.

**Known gap.** No concrete in-tree L2 module exists yet. The compose
bind-mount `./results/l2/identity-spoof-module:/data/adb/modules/identity-spoof`
is a placeholder; until a module lands, L2 cells will boot but the rank-3..6
identity probes (framework.android_id, telephony.imei, mac.wifi, mac.bt,
drm.widevine_id) will NOT show a hardened signature.

---

## §2. Pre-flight

```bash
# §2.1 — Pin-match check (image digest)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L2.compose.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.2 — Forbidden-pattern grep
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L2.compose.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.3 — Results dirs
mkdir -p agents/stability/stack/compose/results/l2/{identity-spoof-module,out}
chmod 0777 agents/stability/stack/compose/results/l2/{identity-spoof-module,out}
```

---

## §3. Bring-up

```bash
# §3.1 — Pull pinned image
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)"

# §3.2 — Compose up the full L0a..L2 stack
cd agents/stability/stack/compose
docker compose \
  -f L0a.yml \
  -f L0b.compose.yml \
  -f L1.compose.yml \
  -f L2.compose.yml \
  up -d

# §3.3 — Wait for boot_completed=1 (36×10s budget)
C=stability-l2-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "[L2] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done

# §3.4 — Once a concrete identity-spoof module lands, install + reboot:
#   docker exec "$C" su -c 'cp -r /data/adb/modules/identity-spoof /data/adb/modules/identity-spoof'
#   docker restart "$C"
```

---

## §4. Acceptance criteria

- `getprop sys.boot_completed` returns `1` within the 36×10s budget.
- L0+L1 modules from the lower stack still load: `magisk --list` shows
  `cpuinfo-overlay`, `DeviceSpoofLab-Magisk`, `DeviceSpoofLab-Hooks` all
  `enabled`.
- Rank-3..6 identity probes (framework.android_id, telephony.imei,
  mac.wifi, mac.bt, drm.widevine_id) score `<0.5` against the Pixel 7
  target profile, OR the gap is documented as "no L2 module in-tree".
- Fail-safe: removing the L2 bind-mount and re-running compose-up MUST
  produce a clean L0a+L0b+L1 cell (no L2 residue).

---

## §5. Rollback

```bash
cd agents/stability/stack/compose
docker compose -f L0a.yml -f L0b.compose.yml -f L1.compose.yml -f L2.compose.yml \
  down --volumes --remove-orphans
rm -rf results/l2/
```

If a future L2 module ships with a `disable` flag, the in-container
rollback is identical to the L1 pattern: `touch /data/adb/modules/identity-spoof/disable`
then `docker restart $C`.

---

## §6. Open questions / known gaps

- No in-tree L2 module. Tracking gap: the bind-mount is a placeholder per
  `layers.md` §L2 "Android Faker" canonical reference. A pin entry in
  `stack/image-pins.yml::modules[id=identity-spoof]` is the prerequisite.
- Per-app-profile persistence model not specified. The canonical Android
  Faker uses an SQLite DB under `/data/adb/identity-spoof/profiles.db`;
  this is not yet pinned by SHA.
- Interaction with L3 attestation backends (TEESimulator / TrickyStore)
  for `MediaDRM unique id (#29)` is not yet measured — the keybox-bound
  DRM ID may shadow the L2 spoof in some compositions.

# L4 Runtime Hiding — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding)
**Compose:** `agents/stability/stack/compose/L4.compose.yml`
**Image pins:** `stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L4 — Runtime Hiding
**In-tree module:** `stack/L4/hide-frida-maps/` (Xposed skeleton, v0.1.0)

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.

---

## §1. Baseline-layer-set decision

**Decision: L0a + L0b + L1 + L2 + L3 + L4.**

L4 sits on top of the full stack including the L3 integrity backend. Per
`agents/stability/stack/layers.md` §L4 the canonical modules are Shamiko
(Zygisk + Magisk + module hiding) and HideMyAppList (package-list
filtering). The in-tree skeleton `hide-frida-maps` (proposal
`019e2f10-37cb-7c8b-bbfb-90e573cfe302`) targets the
`runtime.frida_memory_maps` probe specifically.

The DenyList is configured per Whitelist-Modus, with only DetectorLab in
the list (lab-only — see §"Konfiguration" in layers.md §L4).

---

## §2. Pre-flight

```bash
# §2.1 — Pin-match check (image digest)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L4.compose.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.2 — Forbidden-pattern grep
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L4.compose.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.3 — Verify the in-tree hide-frida-maps skeleton is present
[[ -f stack/L4/hide-frida-maps/README.md ]] \
  || { echo "hide-frida-maps skeleton missing"; exit 78; }
[[ -f stack/L4/hide-frida-maps/src/main/java/dev/cloudphone/hide/frida/maps/HideFridaMapsHook.kt ]] \
  || { echo "hide-frida-maps hook source missing"; exit 78; }

# §2.4 — Results dirs
mkdir -p agents/stability/stack/compose/results/l4/{shamiko-module,hidemyapplist-module,out}
chmod 0777 agents/stability/stack/compose/results/l4/{shamiko-module,hidemyapplist-module,out}
```

---

## §3. Bring-up

```bash
# §3.1 — Pull pinned image
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)"

# §3.2 — Compose up the full L0a..L4 stack
cd agents/stability/stack/compose
docker compose \
  -f L0a.yml \
  -f L0b.compose.yml \
  -f L1.compose.yml \
  -f L2.compose.yml \
  -f L3.compose.yml \
  -f L4.compose.yml \
  up -d

# §3.3 — Wait for boot_completed=1 (36×10s budget)
C=stability-l4-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "[L4] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done

# §3.4 — Install hide-frida-maps via Vector (see stack/L4/hide-frida-maps/README.md §Build)
#   adb push build/outputs/apk/release/hide-frida-maps-release.apk /data/local/tmp/
#   adb shell su -c 'pm install /data/local/tmp/hide-frida-maps-release.apk'
#   adb shell su -c 'cmd vector enable dev.cloudphone.hide.frida.maps'
```

---

## §4. Acceptance criteria

- `getprop sys.boot_completed` returns `1` within the 36×10s budget.
- All lower-layer modules still load (`magisk --list` shows the L1
  modules `enabled`).
- Rank-8 probe `runtime.xposed_lsposed` scores `<0.5` once the Xposed
  hook is wired (currently the in-tree module is a skeleton; the
  acceptance criterion is documented but the measurement is deferred
  until the hook is functional — see `stack/L4/hide-frida-maps/README.md`
  §Status).
- Rank-7 probe `runtime.frida_memory_maps` scores `<0.5` against a
  detector that injects Frida into a scoped target process.
- Fail-safe: removing the L4 bind-mount and re-running compose-up MUST
  produce a clean L0a..L3 cell (no L4 residue).

---

## §5. Rollback

In-container disable (works once the module is functional):

```bash
C=stability-l4-redroid
docker exec "$C" su -c 'echo 0 > /data/adb/modules/vector/conf/hide-frida-maps.enabled'
docker exec "$C" su -c 'cmd vector reload-modules'
```

Full teardown:

```bash
cd agents/stability/stack/compose
docker compose -f L0a.yml -f L0b.compose.yml -f L1.compose.yml \
               -f L2.compose.yml -f L3.compose.yml -f L4.compose.yml \
               down --volumes --remove-orphans
rm -rf results/l4/
```

---

## §6. Open questions / known gaps

- `stack/L4/hide-frida-maps` is a skeleton, not a functional hook (per
  the README's §Status). The compose binds the source so it is staged
  inside the container, but until the hook is wired no measurable
  delta is expected on `runtime.frida_memory_maps`.
- Shamiko and HideMyAppList are NOT yet in-tree as local artefacts.
  The compose bind-mounts are placeholders with `TODO(L4)` comments;
  pin entries in `stack/image-pins.yml::modules` are the prerequisite
  for landing the modules.
- Interaction with L3 keystore binder-intercepts (TEESimulator /
  TrickyStore) and Shamiko's Zygisk hooks is not yet measured. A
  layer-composition stability test is in scope for Detection's cohort
  regression sweep.

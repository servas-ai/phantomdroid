# Owner action kit — turnkey unblock for B1/B2/B3/B5 (2026-05-31)

Each remaining blocker, reduced to the EXACT action Martin takes + how the already-built autonomous
machinery picks it up. Everything else (B4 hardened boot, orchestrator, detection, anti-spoof to internal
CLEAN 0.09, 5/5 detector apps CLEAN) is DONE + E2E + pushed.

## B1 — rooted Magisk ReDroid image (supply-chain decision)
Autonomous sourcing of a third-party security binary is out of scope (safety boundary). Owner action:
```
# build a Magisk-rooted ReDroid 12 image (ayasa520/redroid-script), then SHA-pin it:
git clone https://github.com/ayasa520/redroid-script && cd redroid-script
sudo python3 redroid.py -a 12.0.0 -m         # -m = Magisk; bootanim.rc hijack + magisk --setup-sbin
docker images --digests | grep redroid       # record the sha256, add to agents/stability/stack/image-pins.yml
```
Then it boots HARDENED + NON-privileged immediately via the proven recipe:
`container_lifecycle.build_hardened_run_argv("<rooted-image@sha256:...>", "l0b", <port>, <data>)`
(verified to boot stock ReDroid; same recipe + the rooted image gives a rooted L0b cell).

## B2 — L2–L6 modules (depends on B1 + third-party module assets)
Module trees already staged in-tree; drop the third-party .zip/.apk assets and they mount via Magisk:
- L3 TEE: `TEESimulator`/`TrickyStore` + a keybox.xml → `agents/stability/stack/compose/L3.compose.yml` TODO(L3) bind paths.
- L4 root-hide: `Shamiko` + `HideMyAppList` → L4.compose.yml TODO(L4). (su-binary hide already proven via bind-mount.)
- L5 sensors: needs a **sensor-HAL-enabled ReDroid image** (the stock image has NO sensor HAL — verified live: `dumpsys sensorservice` = "No Sensors", `devInitCheck:-19`, no /dev sensor socket) OR the `VirtualSensor` module. Asset-gated.
- L6 network: a lab LTE-gateway bridge endpoint → L6.compose.yml TODO(L6).
L1 (build props) + L2 (identity: android_id/locale/timezone/display/DNS) are ALREADY done without Magisk.

## B3 — Play Integrity / hardware attestation
Physically impossible in a software container (no TEE / hardware-backed keystore). No owner action makes a
software ReDroid pass STRONG/DEVICE attestation; excluded from scope by definition. The detector SIDE is
implemented (integrity.play_integrity probe); only the bypass is impossible.

## B5 — credential purge + rotation
Run `proof/credential-purge-remediation.sh` (rotates first, then git-filter-repo + force-push origin/main).
This session branch tree is already secret-free.

# cpuinfo-overlay — L1 Magisk module

**Safety boundary.** This module is for lab measurement of detection
resistance in owned test environments. It is not for evading anti-abuse
controls on real services. See `agents/stability/stack/layers.md`
§"Safety boundary".

**Mitigation layer:** L1 (Build/Properties / `/proc/cpuinfo` masking)
**Mutation proposal:** `019e2f12-726b-74b7-bbd9-6d9a17e208ea`
**Probe targeted:** rank-4 `emulator.qemu_artifacts` (a.k.a.
`kernel.cpuinfo_bogomips`), `shared/threat-model.md` §Kernel Layer
`/proc/cpuinfo (#27)`
**Version:** 0.1.0
**Module id:** `cpuinfo-overlay`
**Author:** emulator-builder
**Frozen commit:** `e0aae491678d5ad1b9076def498dd48d1b8646d2`

## What it does

Bind-mounts a synthesised Cortex-A78 / Tensor-G2 `/proc/cpuinfo` over the
host-exposed file at Magisk's `late_start_service` phase. Masks
`bogomips`, `CPU implementer`, `Hardware`, and `Serial` fields to match
the Pixel-7-class spoof profile used elsewhere in the SpoofStack
(target profile: Pixel 7 / panther / Android 14 / security_patch
2024-08-05).

## Files

| File | Purpose |
|---|---|
| `module.prop` | Magisk module manifest (id, version, author, description) |
| `service.sh` | `late_start_service` script that performs `mount --bind` of `system/etc/cpuinfo.spoofed` over `/proc/cpuinfo` |
| `system/etc/cpuinfo.spoofed` | The synthesised cpuinfo content (Cortex-A78 / Tensor G2, 8 cores, `BogoMIPS: 2.00`) |
| `META-INF/com/google/android/update-binary` | Magisk installer entrypoint |
| `tests/` | Per-file shell tests for the overlay content |

The compose file `agents/stability/stack/compose/L1.compose.yml` mounts
this directory read-only at `/data/adb/modules/cpuinfo-overlay` so the
in-container Magisk loader picks it up at first boot without modifying
the source-of-truth.

## Acceptance

- The rank-4 `emulator.qemu_artifacts` probe scores `<0.5` on a container
  with the overlay active.
- `grep -m1 ^Hardware /proc/cpuinfo` inside the container returns
  `Hardware\t: Tensor G2`.
- `grep -m1 ^BogoMIPS /proc/cpuinfo` returns `BogoMIPS\t: 2.00`.
- `magisk --list` shows `cpuinfo-overlay [0.1.0]` with status `enabled`.
- No stability regression: `getprop sys.boot_completed` returns `1` and
  `logcat -d -s ServiceManager:E *:F | head` is empty.

## Threat-model alignment

`shared/threat-model.md` Kernel Layer entry `/proc/cpuinfo (#27)` and the
STRIDE table row "Build Fingerprint" (mitigable by L1).

## Rollback

In-container disable (no reboot needed; takes effect at next boot):

```bash
touch /data/adb/modules/cpuinfo-overlay/disable
umount /proc/cpuinfo 2>/dev/null || true
```

Full removal: simply remove the bind-mount from `L1.compose.yml` and
recreate the container. The source-of-truth at
`agents/stability/stack/modules/cpuinfo-overlay/` is untouched by either
rollback path because the compose bind-mount is read-only.

## Per-file SHA256 fingerprints

Pinned in `stack/image-pins.yml::modules[id=cpuinfo_overlay].file_sha256`.
The Stability container_lifecycle.py preflight fingerprints the in-tree
files against the pins immediately before `docker compose up`.

## Cross-references

- Compose: `agents/stability/stack/compose/L1.compose.yml`
- L1 RUNBOOK: `agents/stability/stack/L1-MAGISK-RUNBOOK.md`
- Layer specs: `agents/stability/stack/layers.md` §L1 — Build Properties
- Mutation proposal: `mutations/proposals/019e2f12-726b-74b7-bbd9-6d9a17e208ea.json`

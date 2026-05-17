# Server reinstall status - 2026-05-17

## Scope

Target: cloud-phone research host reachable on SSH at `195.154.209.133` and iLO/IPMI at `51.159.47.149`.

Do not store or repeat credentials in this repository.

## What was attempted

- Confirmed the local public IPv4 matches the iLO whitelist.
- Connected to iLO 3 over legacy SSH.
- Confirmed the hardware is an HP ProLiant DL120 G7 with iLO 3 Advanced.
- Built a custom Ubuntu 24.04.4 autoinstall netboot ISO locally:
  - `/tmp/cloudphone-autoinstall-iso/ubuntu-24.04.4-cloudphone-autoinstall.iso`
  - SHA256: `085bf49a31e8cd304da45872631ff8c2cff1f7397f16de9843fbc3596071604b`
- Served the ISO temporarily over HTTP from this workstation.
- Inserted the ISO through iLO Virtual Media and tried boot-once/boot-always reset paths.
- Verified the host SSH port returned after reboot attempts.

## Result

The reinstall did not complete.

Evidence:

- The iLO never fetched the ISO from the temporary HTTP server.
- The OS SSH service on `195.154.209.133:22` is reachable again.
- The prepared `root` and `coder` SSH keys are rejected, so the autoinstall user-data was not applied.

## Cleanup performed

iLO Virtual Media was reset to a safe state:

- `Image Connected = No`
- `Image URL = None`
- `Boot Option = NO_BOOT`

The temporary local HTTP server for the ISO was stopped.

## Current constraint

The iLO HTTPS UI is not usable from this environment because TLS negotiation fails. The legacy SSH CLI works, but text console reports unsupported graphics mode and the virtual serial port does not expose useful OS output. Continuing with more blind boot changes would be risky.

## Recommended next step

Use the provider control panel's OS reinstall/rescue workflow, or provide working OS-level SSH access for `195.154.209.133`.

Avoid BIOS, RAID, and iLO configuration changes. The iLO logs already contain historical controller/array warnings, and the provider explicitly warns that recovery may require remote hands.

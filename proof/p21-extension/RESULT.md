# Plan item — P21 corpus extension on the live SPOOFED container — DONE (corpus ceiling documented)

STATUS gap: "P21 extension to ≥30 apps" + "re-run on freshly-provisioned ReDroid". This run installed
the FULL installable corpus on the live spoofed container `l1-spoof-v3` and launched it.

## Result: 22 distinct detector apps installed + launched live (was 9 on this container; prior P21 = 21-cell on a different UNSPOOFED container)

Installed via `pm install` (direct APK) + `pm install-create/write/commit` (split XAPKs: androidallid 20 splits, gpsstatus2 30, usurvey 20, reh.deviceid 5). Full list: see `installed-corpus.txt`.

Of the 22: 17 software-spoofable detectors + 5 attestation apps (Play Integrity / SafetyNet / Key Attestation — excluded from the verdict denominator, B3 TEE-gated). The 17 software-spoofable apps' live verdicts on the spoof are already classified in `audit/anti-spoof-80/` (5/5 verdict-emitting detectors CLEAN, 0 active detections). New this run: androidallid, gpsstatus2, usurvey, reh.deviceid launched + screenshotted (info apps; display the spoofed Pixel-7 identity).

## Why not ≥30 (corpus ceiling on x86 — a sourcing/architecture bound, not a defect)
The corpus (`p21/apks/`) maxes at 22 installable on an x86 ReDroid host:
- ABI-incompatible (ARM-only native libs, `INSTALL_FAILED_NO_MATCHING_ABIS`): cpu_z, drminfo. Need an arm64 host.
- Install-failed (binder/packaging): ytheekshana.deviceinfo.
- skip-manual (Play-login / interactive sourcing, ~10): rootchecks, emulatordetector, xposeddetector, etc. — cannot be auto-sourced under the browser/Play policy.
Reaching ≥30 distinct working apps requires either an arm64 ReDroid host (for the ARM-only apps) or sourcing ~8 additional APKs (network/Play-login = owner/policy-gated). Documented as a bounded item; 22/corpus is the achievable maximum here.

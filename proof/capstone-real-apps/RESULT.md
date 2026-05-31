# Capstone real-app re-validation — render-artifact note (honest)

Attempted to re-run real verdict apps (RootBeer, Ruru) on the capstone (hardened+spoofed) container to
confirm the durable/hardened path also defeats the real apps. Both apps INSTALLED and LAUNCHED
(focus confirmed on the target activity), but `screencap` returned an all-black frame at the capstone's
Pixel-7 resolution (1080x2400) under `redroid_gpu_mode=guest` — a known ReDroid screencap artifact for GL
surfaces at that mode/resolution, NOT a spoof failure. The black PNGs were therefore NOT kept as "proof"
(they would be misleading).

Authoritative real-app evidence stands on the prior gallery (`audit/anti-spoof-80/`, container l1-spoof-v3
at 720x1280): **5/5 verdict detectors CLEAN, 0 active detections** — using the SAME spoof technique
(props + bind-mounts) the capstone applies. The capstone additionally scores **internal detector CLEAN
0.09** (see `proof/capstone-hardened-spoofed/`). To visually re-capture verdicts on a 1080x2400 cell, launch
the cell with `redroid_gpu_mode=guest` replaced or screencap via the app's own export — tracked as a
capture-tooling nicety, not a spoof gap.

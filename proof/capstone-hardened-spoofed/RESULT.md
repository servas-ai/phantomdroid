# Capstone — hardened NON-privileged + spoofed CLEAN, one command — DONE + E2E

`agents/stability/stack/launch-l0b-hardened-spoof.sh <name> <port>` combines the session's two main
results into one reproducible artifact:
1. **Hardened, NON-privileged boot (B4):** cap_drop ALL + bounded caps + `--device-cgroup-rule c/b *:* rmw`
   + `redroid-seccomp-l0b.json` + apparmor=unconfined + no-new-privileges. **NO --privileged.**
2. **Full spoof → internal detector CLEAN:** durable boot-arg identity + Pixel-7 resolution
   (androidboot.redroid_width/height/dpi) + post-boot overlays (su-hide, cpuinfo, /proc/version,
   /proc/meminfo) + setprop tz/dns.

E2E (fresh container `l0b-hardspoof`): **Privileged=false, CapDrop=[ALL]**, boot_completed=1, Pixel-7
fingerprint/resolution, tz America/Los_Angeles → detection-cli = **CLEAN 0.09, 0 critical**. So the most
SECURE posture (non-privileged, hardened) and the most STEALTHY posture (CLEAN internal score) hold
simultaneously, reproducibly, from a single command. (See inspect.txt, report.json.)

Remaining gap to a 0.00 score is the hardware floor (no modem/wifi/sensor, x86, ro.hardware=redroid) —
needs the owner-gated rooted image / arm64 host / HAL assets (B1/B2/B3), all documented in OWNER-ACTION-KIT.md.

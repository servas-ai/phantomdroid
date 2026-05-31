# Plan item — durable one-command spoof launch (reproducibility) — DONE + E2E

The session's CLEAN result (internal detector 0.09) previously depended on a sequence of MANUAL runtime
steps (wm size, setprop tz/dns, four bind-mounts) that are lost on container restart. Consolidated them
into one reproducible script: `agents/stability/stack/launch-l1-spoof.sh <name> <port> [data]`.

- DURABLE boot-arg properties (survive restart): identity (fingerprint/brand/model/tags=release-keys/
  type=user/debuggable=0/board/platform), **Pixel-7 display baked via `androidboot.redroid_width=1080
  height=2400 dpi=420`** (verified: `wm size` = 1080x2400 with NO runtime call), locale en-US.
- Post-boot overlay pass (bind-mounts can't be boot args): su-hide, cpuinfo, /proc/version, /proc/meminfo;
  and setprop for persist.sys.timezone + net.dns1/2 (these do NOT stick as boot args — proven; set post-boot).

E2E: `launch-l1-spoof.sh l1-durable 15575` on a FRESH container → boot_completed=1, fingerprint=google/panther
…release-keys, resolution 1080x2400, tz America/Los_Angeles, dns 1.1.1.1 → detection-cli = **CLEAN 0.09,
0 critical** (see durable-clean-report.json, launch-verify.txt). One command reproduces the full CLEAN spoof.

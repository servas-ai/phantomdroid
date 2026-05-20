#!/system/bin/sh
# SpoofStack service.d/00-spoof.sh
#
# Runs AFTER post-fs-data.sh, in late-boot / service-manager context. This
# is the standard Magisk hook for resetprop calls that don't strictly need
# the pre-Zygote window — they re-assert the same prop values that
# post-fs-data.sh already set, as belt-and-suspenders against any
# init-script that might re-write them.
#
# Idempotency: every call uses `-n` (force-overwrite) so re-running this
# script on later boots is safe.
#
# Source of truth: audit/spoof-stack/production-hooks-spec.md §1.

set -e

# ---------------------------------------------------------------------------
# §1.1 Build-prop family (rank 1 / 7 / 9 / 28)
# ---------------------------------------------------------------------------
resetprop -n ro.build.fingerprint "google/panther/panther:12/SP1A.210812.016.C2/9471150:user/release-keys"
resetprop -n ro.build.display.id "SP1A.210812.016.C2"
resetprop -n ro.build.tags release-keys
resetprop -n ro.build.type user
resetprop -n ro.product.brand google
resetprop -n ro.product.model "Pixel 7"
resetprop -n ro.product.manufacturer Google
resetprop -n ro.product.device panther
resetprop -n ro.product.name panther
resetprop -n ro.hardware panther
resetprop -n ro.product.board panther
resetprop -n ro.board.platform gs201

# ---------------------------------------------------------------------------
# §1.2 CPU ABI family (rank 27) — property side
# ---------------------------------------------------------------------------
resetprop -n ro.product.cpu.abi arm64-v8a
resetprop -n ro.product.cpu.abilist "arm64-v8a,armeabi-v7a,armeabi"
resetprop -n ro.product.cpu.abilist32 "armeabi-v7a,armeabi"
resetprop -n ro.product.cpu.abilist64 arm64-v8a

# ---------------------------------------------------------------------------
# §1.3 Bootloader / verified-boot (rank 13 + rank 71)
# ---------------------------------------------------------------------------
resetprop -n ro.boot.vbmeta.device_state green
resetprop -n ro.boot.verifiedbootstate green
resetprop -n ro.boot.flash.locked 1
resetprop -n ro.oem_unlock_supported 0
resetprop -n ro.secure 1
resetprop -n ro.debuggable 0

# ---------------------------------------------------------------------------
# §1.4 SELinux (rank 14) — property side
# ---------------------------------------------------------------------------
resetprop -n ro.boot.selinux enforcing
resetprop -n ro.build.selinux 1

# ---------------------------------------------------------------------------
# §1.5 DNS properties (rank 37)
# ---------------------------------------------------------------------------
resetprop net.dns1 8.25.203.30
resetprop net.dns2 8.25.203.31

# ---------------------------------------------------------------------------
# §1.6 Locale build-time (rank 36)
# ---------------------------------------------------------------------------
resetprop -n ro.product.locale en-US
resetprop -n ro.product.locale.language en
resetprop -n ro.product.locale.region US

# ---------------------------------------------------------------------------
# §1.7 Serial (rank 12) — property side
# ---------------------------------------------------------------------------
resetprop -n ro.serialno HQ7Y0V3RJL

# ---------------------------------------------------------------------------
# §5.6 DisplayMetrics density (rank 23)
# ---------------------------------------------------------------------------
resetprop -n ro.sf.lcd_density 420

# ---------------------------------------------------------------------------
# §5.2 belt-and-suspenders persist.sys.*  (rank 20, 36)
# ---------------------------------------------------------------------------
resetprop -n persist.sys.timezone America/Los_Angeles
resetprop -n persist.sys.locale en-US

# BLOCKER — L4 fresh-fork root hiding is not durable

## Verdict

L4 is **BLOCKED**. A freshly forked denylisted app sees root before any manual per-PID
`nsenter`/unmount intervention.

Round-3 validation on 2026-05-31 used the live hardened rooted cell `l4-val3`
(`redroid/redroid:12.0.0_magisk`, `Privileged=false`, `CapDrop=[ALL]`, `magisk -V=30600`).
The validator added `com.android.calendar` to the Magisk denylist, force-stopped it, started
`com.android.calendar/.AllInOneActivity`, and inspected the newly forked app PID before applying
any manual namespace edits.

Observed fresh PID:

- package: `com.android.calendar`
- PID: `700122`
- parent: `zygote64` PID `696212`
- app mount namespace: `mnt:[4026537518]`
- denylist entry present: `com.android.calendar|com.android.calendar`
- root control intact: `/system/xbin/su`, `/sbin/su`, `/sbin/.magisk`, `/data/adb/magisk`
  present in the global/root namespace

Fresh app PID before manual unmount saw 4/16 root paths:

- `PRESENT /system/xbin/su`
- `PRESENT /sbin/su`
- `PRESENT /sbin/.magisk`
- `PRESENT /data/adb/magisk`

Full transcript: `proof/b2-l4-zygisk/round3-fresh-fork-durability.txt`.

## Owner / Action

Owner: SpoofStack L4 runtime-hiding owner.

Required action: replace the manual per-PID `nsenter` masking with fork-time automatic masking
for denylisted apps. Acceptable fixes include a ReDroid image/runtime where Magisk/Zygisk
actually applies the denylist unmount at app fork, or an equivalent supervised mechanism that
applies `/sbin`, `/system/xbin`, and `/data/adb` masking before app code can observe root.

Re-run gate: on a fresh launchable denylisted app, with no manual per-PID intervention after
start, the validator must show:

- `paths_present_before_manual_unmount=0`
- Magisk manager package absent or hidden from the app-visible package query
- global/root namespace still proves root is intact (`su -c id` => `uid=0`, `magisk -V=30600`,
  root paths present in control namespace)

Until that passes, the prior 0/16 app-namespace result is only a manual/demo mask and must not
be committed or described as durable L4 proof.

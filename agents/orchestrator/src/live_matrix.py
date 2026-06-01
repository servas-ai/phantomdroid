# agents/orchestrator/src/live_matrix.py
#
# TRUE (non-replay) per-cell matrix execution (SPEC §1 goal 1, partial fill of the
# "TRUE full-matrix Orchestrator run" gap). For each cell it performs a real
# end-to-end run against a LIVE booted ReDroid 12 container:
#
#   docker exec <c> getprop ...   (fresh live capture)
#     -> build a detection snapshot YAML
#       -> detection-cli run --snapshot   (real probe scoring)
#         -> persistence.persist_report    (atomic, schema-gated)
#           -> JournalStore.complete_cell   (resumable record)
#
# This is NOT the `--matrix replay` data projection: every score here comes from
# a freshly-captured live device. It deliberately does NOT manage container
# lifecycle (boot/teardown) — the SPEC §4 hardened container_lifecycle (cap_drop
# + seccomp, refuses privileged) cannot boot ReDroid on binderfs-only kernels
# (proven 2026-05-30); the privileged-vs-hardened posture decision is owner-gated.
# So this runner attaches to already-booted containers.

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from .run_id import compute_run_id, config_id_from_layers
from .persistence import persist_report
from .journal import JournalStore
from .config_loader import load_manifest

# Props the detection snapshot needs (mirrors the live-capture snapshots in p21/).
_PROP_KEYS = [
    "ro.build.fingerprint", "ro.build.display.id", "ro.build.tags", "ro.build.type",
    "ro.build.version.release", "ro.build.version.sdk", "ro.product.brand",
    "ro.product.model", "ro.product.manufacturer", "ro.product.device",
    "ro.product.name", "ro.kernel.qemu", "ro.kernel.qemu.gles", "ro.hardware",
    "ro.product.board", "ro.board.platform", "ro.product.cpu.abi",
    "ro.product.cpu.abilist", "ro.product.cpu.abilist32", "ro.product.cpu.abilist64",
    "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
    "ro.secure", "ro.debuggable", "ro.bootloader", "ro.boot.hardware",
    "ro.boot.selinux", "ro.build.selinux", "sys.boot_completed", "init.svc.zygote",
    # env.language_country (locale) + network.dns_server probe inputs
    "ro.product.locale", "ro.product.locale.language", "ro.product.locale.region",
    "net.dns1", "net.dns2",
]


# Canonical root-detection surface — MUST mirror
# agents/detection/src/probes/root/SuDetectionProbe.kt exactly. The detector's
# SuDetectionProbe scores 1.0 if ANY of these binary/artifact paths exist, or
# 0.85 if only a superuser package is installed. The live capture therefore has
# to probe every one of these paths honestly (recording each present path in
# existingFiles) and enumerate installed superuser packages — otherwise it
# under-reports root and yields a false su_detection=0.0 (the B2 overclaim bug).
# DO NOT special-case / omit Magisk's own /sbin paths: record what is present.
_SU_BINARY_PATHS = [
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/system/sd/xbin/su",
    "/system/bin/failsafe/su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/su",
    "/system/sbin/su",
    "/vendor/bin/su",
    "/su/bin/su",
    "/magisk/.core/bin/su",
]

_MAGISK_ARTIFACT_PATHS = [
    "/sbin/.magisk",
    "/system/bin/magisk",
    "/cache/magisk.log",
    "/data/adb/magisk",
]

# Additional Magisk/superuser filesystem artifacts that SuDetectionProbe does not
# itself enumerate but which the detector's broader root surface (and any honest
# capture) should still record when present. Kept separate so the canonical
# probe-mirroring lists above stay byte-identical to SuDetectionProbe.kt.
_MAGISK_EXTRA_PATHS = [
    "/data/adb/modules",
]

# Mirrors SuDetectionProbe.SUPERUSER_PACKAGES. The capture runs `pm list packages`
# (the same surface the probe's queryPackageManager()/isPackageInstalled() reads)
# and records any present package in installedPackages so the package check is honest.
_SUPERUSER_PACKAGES = [
    "com.topjohnwu.magisk",
    "eu.chainfire.supersu",
    "com.koushikdutta.superuser",
    "com.thirdparty.superuser",
]


def _docker_exec(container: str, shell_cmd: str) -> str:
    out = subprocess.run(
        ["docker", "exec", container, "sh", "-c", shell_cmd],
        capture_output=True, text=True, timeout=60,
    )
    return out.stdout


def _capture_settings(container: str, namespace: str, keys: list[str]) -> dict:
    """Read `settings get <namespace> <key>` for each key; omit null/empty (real-value capture)."""
    out: dict[str, str] = {}
    for k in keys:
        v = _docker_exec(container, f"settings get {namespace} {k}").strip()
        if v and v.lower() != "null":
            out[k] = v
    return out


# getprop keys that carry the live telephony / radio identity surface (set by the
# L2/L6 resetprop spoof pass). These are READS ONLY — we never write or invent values.
_TELEPHONY_PROP_KEYS = [
    "gsm.operator.alpha", "gsm.operator.numeric",
    "gsm.sim.operator.alpha", "gsm.sim.operator.numeric",
    "gsm.sim.state", "gsm.network.type",
    "ril.iccid.sim1", "ro.serialno",
]


def _clean(v: str | None) -> str | None:
    """Normalise a raw getprop / service-call read: empty or literal 'null' -> None."""
    if v is None:
        return None
    s = v.strip()
    if not s or s.lower() == "null":
        return None
    return s


def capture_telephony(container: str) -> dict[str, str | None]:
    """HONEST live read of the device telephony surface.

    Maps real device reads onto the detector's `TelephonyField` enum names
    (IMEI, SERIAL, OPERATOR_NAME, MCC_MNC, SIM_SERIAL). Every value is whatever
    the device actually returns; a field the device does not expose is recorded
    as ``None`` (YAML ``null``). This function MUST NEVER fabricate a value —
    in particular ReDroid exposes no IMEI, so ``IMEI`` stays ``None``.

    The map is intentionally always populated (keys present, values possibly
    ``None``) so the detector's accessor "worked" (matching real-device
    semantics where TelephonyManager exists but returns null IMEI on Android
    10+), driving the benign SCORE_IMEI_NULL_BENIGN path rather than a
    "stripped" tell.
    """
    raw = _docker_exec(
        container, "; ".join(f'echo "{k}|$(getprop {k})"' for k in _TELEPHONY_PROP_KEYS)
    )
    props: dict[str, str] = {}
    for line in raw.splitlines():
        if "|" in line:
            k, _, v = line.partition("|")
            props[k.strip()] = v.strip()

    # IMEI: read it honestly. ReDroid exposes no IMEI, so both the iphonesubinfo
    # binder accessor and any gsm.* prop return null/empty -> recorded as None.
    imei = _clean(_docker_exec(
        container, "service call iphonesubinfo 1 2>/dev/null"
    ))
    # `service call` returns a hex parcel dump, never a bare IMEI; treat any
    # non-digit blob as "no IMEI exposed" (None). Only a literal 15-digit string
    # would ever be honoured, and the device never produces one.
    if imei is not None and not imei.isdigit():
        imei = None
    if imei is None:
        imei = _clean(props.get("gsm.imei")) or _clean(_docker_exec(
            container, "getprop ril.imei"
        ))

    operator_name = _clean(props.get("gsm.operator.alpha")) or _clean(
        props.get("gsm.sim.operator.alpha"))
    mcc_mnc = _clean(props.get("gsm.operator.numeric")) or _clean(
        props.get("gsm.sim.operator.numeric"))
    sim_serial = _clean(props.get("ril.iccid.sim1"))
    serial = _clean(props.get("ro.serialno"))

    return {
        "IMEI": imei,                 # None on ReDroid (no IMEI) — never invented
        "SERIAL": serial,             # ro.serialno (Pixel-class on the spoofed cell)
        "OPERATOR_NAME": operator_name,
        "MCC_MNC": mcc_mnc,
        "SIM_SERIAL": sim_serial,     # ICCID (ril.iccid.sim1)
    }


def capture_root_surface(container: str) -> tuple[list[str], list[str]]:
    """HONEST live read of the device root-detection surface.

    Probes EVERY canonical su-binary, Magisk-artifact and extra-artifact path
    (mirroring SuDetectionProbe.kt) and enumerates installed superuser packages
    via `pm list packages`. Returns ``(present_root_files, present_superuser_packages)``.

    This MUST NOT special-case or omit Magisk's own /sbin paths (e.g. /sbin/su,
    /sbin/.magisk): on a Magisk-rooted container those are PRESENT and recording
    them is exactly what keeps the capture honest. Under-reporting them is the
    root-honesty bug this function exists to prevent (false su_detection=0.0).
    """
    all_paths = _SU_BINARY_PATHS + _MAGISK_ARTIFACT_PATHS + _MAGISK_EXTRA_PATHS
    # One shell round-trip: for each path, emit "PATH" only when it exists (-e).
    probe = "; ".join(f'[ -e "{p}" ] && echo "{p}"' for p in all_paths)
    raw = _docker_exec(container, probe)
    present_files = [ln.strip() for ln in raw.splitlines() if ln.strip()]

    # Package surface: `pm list packages` lists installed packages as
    # "package:<name>". Match against the canonical superuser set.
    pm_raw = _docker_exec(container, "pm list packages 2>/dev/null")
    installed = set()
    for ln in pm_raw.splitlines():
        name = ln.strip()
        if name.startswith("package:"):
            name = name[len("package:"):].strip()
        if name in _SUPERUSER_PACKAGES:
            installed.add(name)
    present_packages = [p for p in _SUPERUSER_PACKAGES if p in installed]
    return present_files, present_packages


def capture_mount_and_uds(
    container: str,
) -> tuple[dict[str, str | None], list[str]]:
    """HONEST live read of the mount-namespace + UDS root-detection surface.

    Captures the dispositive mount-namespace probes' inputs that the prior
    capture left as a conservative NULL (no-observation):

      * ``mountInfo["self"]`` / ``mountInfo["1"]`` — full ``/proc/<pid>/mountinfo``
        content read via ``su -c cat`` (root needed to read ``/proc/1/mountinfo``
        on a hardened container). Consumed by MountNsMismatchProbe (Momo #1),
        OverlayFsPresentProbe and SystemRwMountProbe.
      * ``procNetUnixSockets`` — the socket-name column (field 8) of
        ``/proc/net/unix``. Consumed by MagiskUdsProbe.

    Anti-fabrication: every read is whatever the device actually returns. If a
    read fails (permission denied / file absent / empty), the corresponding
    ``mountInfo`` key is recorded as ``None`` (YAML ``null`` => the probe sees
    "no observation" and scores the conservative 0.0). UDS sockets that cannot
    be read yield an empty list. Nothing is invented.
    """
    mount_info: dict[str, str | None] = {}
    for pid in ("self", "1"):
        # `su -c` so /proc/1/mountinfo is readable on a hardened (non-self)
        # container; fall back to a bare cat if su is unavailable. 2>/dev/null
        # so a denied read produces empty -> recorded as None, never fabricated.
        raw = _docker_exec(
            container, f"su -c 'cat /proc/{pid}/mountinfo' 2>/dev/null"
        )
        if not raw.strip():
            raw = _docker_exec(container, f"cat /proc/{pid}/mountinfo 2>/dev/null")
        content = raw.rstrip("\n")
        # Empty/whitespace-only => unreadable -> NULL (no observation), honest.
        mount_info[pid] = content if content.strip() else None

    # /proc/net/unix: socket name is the last whitespace-delimited field when
    # present (named/abstract sockets); unnamed sockets have no trailing field
    # and are skipped. Header line (starts with "Num") is skipped.
    uds_raw = _docker_exec(container, "su -c 'cat /proc/net/unix' 2>/dev/null")
    if not uds_raw.strip():
        uds_raw = _docker_exec(container, "cat /proc/net/unix 2>/dev/null")
    sockets: list[str] = []
    for ln in uds_raw.splitlines():
        line = ln.rstrip()
        if not line or line.lstrip().startswith("Num"):
            continue
        parts = line.split()
        # Standard /proc/net/unix has 7 fixed columns; an 8th (the path/name)
        # is present only for named/abstract sockets. Only those carry a name.
        if len(parts) >= 8:
            sockets.append(parts[-1])
    return mount_info, sockets


def capture_live_snapshot(container: str, label: str) -> dict:
    """Fresh read-only capture of a booted container's identity surface."""
    raw = _docker_exec(container, "; ".join(f'echo "{k}|$(getprop {k})"' for k in _PROP_KEYS))
    props: dict[str, str] = {}
    for line in raw.splitlines():
        if "|" in line:
            k, _, v = line.partition("|")
            props[k.strip()] = v.strip()
    boot = props.get("sys.boot_completed", "")
    if boot != "1":
        raise RuntimeError(f"container {container} not booted (sys.boot_completed={boot!r})")
    proc_version = _docker_exec(container, "cat /proc/version").strip()
    # HONEST root surface: probe EVERY canonical su/Magisk path (not just
    # /system/xbin/su) and enumerate installed superuser packages, mirroring
    # SuDetectionProbe.kt. Records what is actually present so root is never
    # under-reported (the false su_detection=0.0 bug).
    root_files, superuser_packages = capture_root_surface(container)
    # HONEST mount-namespace + UDS surface: /proc/self|1/mountinfo and
    # /proc/net/unix — the dispositive Momo/RootBeer root probes' inputs that
    # the prior capture left as a conservative NULL. Reads via su -c; a failed
    # read stays NULL (no observation), never fabricated.
    mount_info, proc_net_unix_sockets = capture_mount_and_uds(container)
    # font files present (ui.system_fonts probe fileExists fallback: NotoColorEmoji + Roboto = real set)
    font_files = [ln.strip() for ln in
                  _docker_exec(container, "ls /system/fonts/*.ttf 2>/dev/null").splitlines() if ln.strip()]
    captured_at = _docker_exec(container, "date -u +%Y-%m-%dT%H:%M:%SZ").strip() or "1970-01-01T00:00:00Z"
    secure = _capture_settings(container, "secure",
                               ["android_id", "default_input_method",
                                "mock_location", "allow_mock_location", "mock_location_app"])
    glob = _capture_settings(container, "global",
                             ["adb_enabled", "development_settings_enabled", "boot_count",
                              "data_roaming", "private_dns_mode", "private_dns_specifier"])
    # readable files consumed by network.dns_server + runtime.debugger_tracerpid probes.
    # The YAML emitter now escapes \n/\t (C-style), so multi-line file content round-trips
    # faithfully — /proc/self/status keeps its TracerPid line (TracerPid:0 = not debugged = clean).
    readable = {"/proc/version": proc_version}
    for path in ("/proc/self/status", "/etc/resolv.conf"):
        content = _docker_exec(container, f"cat {path} 2>/dev/null").strip()
        if content:
            readable[path] = content
    # timezone + locale (env.timezone_locale_mismatch) and display (ui.screen_resolution)
    tz = (props.get("persist.sys.timezone") or _docker_exec(container, "getprop persist.sys.timezone").strip()) or None
    locale = props.get("ro.product.locale", "")
    loc_lang = loc_country = None
    if "-" in locale:
        loc_lang, _, loc_country = locale.partition("-")
    elif locale:
        loc_lang = locale
    w = h = dens = None
    msize = _docker_exec(container, "wm size").strip()
    if "x" in msize:
        try:
            wh = msize.split(":")[-1].strip().split("x")
            w, h = int(wh[0]), int(wh[1])
        except Exception:
            pass
    mdens = _docker_exec(container, "wm density").strip()
    if mdens:
        try:
            dens = int(mdens.split(":")[-1].strip())
        except Exception:
            pass
    telephony = capture_telephony(container)
    return {
        "label": label,
        "capturedAt": captured_at,
        "sdkInt": int(props.get("ro.build.version.sdk") or 31),
        "systemProperties": {k: props.get(k, "") for k in _PROP_KEYS},
        "existingFiles": (root_files + font_files),
        "readableFiles": readable,
        "settingsSecure": secure, "settingsGlobal": glob, "settingsSystem": {},
        "telephony": telephony,
        "installedPackages": (["android", "com.android.systemui"] + superuser_packages),
        "mountInfo": mount_info,
        "procNetUnixSockets": proc_net_unix_sockets,
        "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": tz, "localeLanguage": loc_lang, "localeCountry": loc_country,
        "displayWidthPixels": w, "displayHeightPixels": h, "displayDensityDpi": dens,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None,
        "gpsProvider": None, "gpsIsMock": None,
    }


def _yaml_dump_snapshot(snap: dict) -> str:
    """Minimal YAML emitter for the snapshot dict (avoids a PyYAML dependency)."""
    def q(v):
        if v is None:
            return "null"
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        s = (str(v).replace('\\', '\\\\').replace('"', '\\"')
             .replace('\r', '\\r').replace('\n', '\\n').replace('\t', '\\t'))
        return '"' + s + '"'

    lines = [f"label: {q(snap['label'])}", f"capturedAt: {q(snap['capturedAt'])}",
             f"sdkInt: {snap['sdkInt']}", "systemProperties:"]
    for k, v in snap["systemProperties"].items():
        lines.append(f'  {q(k)}: {q(v)}')
    lines.append("existingFiles:" + (" []" if not snap["existingFiles"] else ""))
    for f in snap["existingFiles"]:
        lines.append(f"  - {q(f)}")
    lines.append("readableFiles:")
    for k, v in snap["readableFiles"].items():
        lines.append(f'  {q(k)}: {q(v)}')
    for key in ("settingsSecure", "settingsGlobal", "settingsSystem", "telephony"):
        m = snap.get(key) or {}
        if not m:
            lines.append(f"{key}: {{}}")
        else:
            lines.append(f"{key}:")
            for k, v in m.items():
                lines.append(f'  {q(k)}: {q(v)}')
    lines.append("installedPackages:")
    for p in snap["installedPackages"]:
        lines.append(f"  - {q(p)}")
    # mountInfo: Map<String, String?> — per-PID /proc/<pid>/mountinfo content.
    # A null value means the read failed (no observation) — emitted as YAML null
    # so the scorer's queryMountInfo(pid) returns null, NOT a fabricated string.
    mi = snap.get("mountInfo") or {}
    if not mi:
        lines.append("mountInfo: {}")
    else:
        lines.append("mountInfo:")
        for k, v in mi.items():
            lines.append(f'  {q(k)}: {q(v) if v is not None else "null"}')
    # procNetUnixSockets: List<String> — socket names from /proc/net/unix.
    socks = snap.get("procNetUnixSockets") or []
    if not socks:
        lines.append("procNetUnixSockets: []")
    else:
        lines.append("procNetUnixSockets:")
        for s in socks:
            lines.append(f"  - {q(s)}")
    lines.append("sensorTypes: []")
    lines.append("bluetoothMac: null")
    for key in ("timezoneId", "localeLanguage", "localeCountry"):
        v = snap.get(key)
        lines.append(f"{key}: {q(v) if v is not None else 'null'}")
    for key in ("displayWidthPixels", "displayHeightPixels", "displayDensityDpi"):
        v = snap.get(key)
        lines.append(f"{key}: {v if v is not None else 'null'}")
    for key in ("gpsLat", "gpsLng", "gpsAccuracy", "gpsProvider", "gpsIsMock"):
        lines.append(f"{key}: null")
    return "\n".join(lines) + "\n"


def run_cell(container: str, layers: list[str], label: str, cli: str, out_root: Path,
             run_index: int = 0, manifest: dict | None = None) -> dict:
    """One TRUE cell: live capture -> CLI score -> persist. Returns a summary dict.

    If a validated `manifest` (SPEC §5) is supplied, the run_id derives from the
    manifest + its pinned apk/image hash (SPEC §6 canonical). Otherwise it derives
    from the cell metadata + the stable detection-cli binary sha (snapshot-only mode).
    """
    if manifest is not None:
        config_id = manifest["config_id"]
        layers = config_id.split("-")
    else:
        config_id = config_id_from_layers(layers)
    snap = capture_live_snapshot(container, label)
    out_root.mkdir(parents=True, exist_ok=True)
    snap_path = out_root / f"{config_id}-snapshot.yml"
    snap_path.write_text(_yaml_dump_snapshot(snap))
    report_path = out_root / f"{config_id}-report.json"
    subprocess.run([cli, "run", "--snapshot", str(snap_path), "-o", str(report_path)],
                   capture_output=True, text=True, timeout=120, check=True)
    report = json.loads(report_path.read_text())
    if manifest is not None:
        # SPEC §6 canonical: stable pinned hash from the manifest.
        id_manifest = manifest
        stable_sha = (manifest.get("detector_lab_apk_hash")
                      or manifest.get("container_image_hash") or "")
    else:
        id_manifest = {"layers": layers, "label": label, "container": container}
        # idempotent: stable prober artifact (NOT the snapshot file whose capturedAt is volatile).
        stable_sha = hashlib.sha256(Path(cli).read_bytes()).hexdigest()
    run_id = compute_run_id(id_manifest, stable_sha, run_index)
    persisted = persist_report(report, config_id, run_id, runs_root=out_root / "runs")
    agg = report.get("aggregate", {})
    return {
        "config_id": config_id, "run_id": run_id, "label": label,
        "weightedScore": agg.get("weightedScore"),
        "criticalFailures": agg.get("criticalFailures"),
        "category": agg.get("category"),
        "persisted": str(persisted),
    }


def _completed_index(journal: JournalStore) -> set[tuple[str, int]]:
    """Set of (config_id, run_index) already COMPLETED (for --resume, SPEC §7)."""
    try:
        return {(c.config_id, c.run_index) for c in journal.list_cells(statuses=["COMPLETED"])}
    except Exception:
        return set()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="live_matrix")
    p.add_argument("--cell", action="append", default=[],
                   help="container:layers:label  e.g. l0a-diag2:L0a:unspoofed-baseline")
    p.add_argument("--config", help="run manifest (SPEC §5) driving one cell; needs --container")
    p.add_argument("--container", help="already-booted container to attach to (with --config)")
    p.add_argument("--label", default="manifest-run", help="cell label (with --config)")
    p.add_argument("--cli", default="agents/detection-cli/build/install/detection-cli/bin/detection-cli")
    p.add_argument("--out", default="experiments/live-matrix")
    p.add_argument("--run-index", type=int, default=0)
    p.add_argument("--resume", action="store_true", help="skip cells already COMPLETED in the journal")
    p.add_argument("--journal", default="results/live-matrix-journal.sqlite")
    args = p.parse_args(argv)
    out_root = Path(args.out)
    journal = JournalStore(path=args.journal)
    done = _completed_index(journal) if args.resume else set()

    # Build the work list: --config (manifest-driven single cell) and/or --cell specs.
    work: list[tuple[str, list[str], str, dict | None]] = []
    if args.config:
        if not args.container:
            p.error("--config requires --container (hardened auto-boot is owner-gated; see BLOCKERS)")
        manifest = load_manifest(args.config)
        work.append((args.container, manifest["config_id"].split("-"), args.label, manifest))
    for spec in args.cell:
        container, layers_raw, label = spec.split(":", 2)
        work.append((container, layers_raw.split("+"), label, None))
    if not work:
        p.error("provide --config (+--container) and/or at least one --cell")

    rows = []
    for container, layers, label, manifest in work:
        config_id = manifest["config_id"] if manifest else config_id_from_layers(layers)
        key = (config_id, args.run_index)
        if key in done:
            rows.append({"config_id": config_id, "run_index": args.run_index,
                         "status": "SKIPPED_RESUME", "label": label})
            continue
        # seed (PENDING) then claim (RUNNING); tolerate pre-existing rows
        try:
            journal.seed_cell(config_id=config_id, run_index=args.run_index, layer_set=layers)
        except Exception:
            pass
        try:
            journal.claim_cell(config_id=config_id, run_index=args.run_index)
        except Exception:
            pass
        try:
            row = run_cell(container, layers, label, args.cli, out_root,
                           run_index=args.run_index, manifest=manifest)
            journal.complete_cell(config_id=config_id, run_index=args.run_index, status="COMPLETED")
            row["status"] = "COMPLETED"
            rows.append(row)
        except Exception as exc:  # boot/capture/score failure → terminal, resumable
            status = "BOOT_FAIL" if "not booted" in str(exc) else "FAILED"
            try:
                journal.complete_cell(config_id=config_id, run_index=args.run_index,
                                      status=status, error=str(exc)[:300])
            except Exception:
                pass
            rows.append({"config_id": config_id, "run_index": args.run_index,
                         "status": status, "label": label, "error": str(exc)[:200]})
    print(json.dumps({"matrix": rows}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

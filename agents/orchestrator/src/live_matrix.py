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
    su_present = bool(_docker_exec(container, "test -x /system/xbin/su && echo yes").strip())
    captured_at = _docker_exec(container, "date -u +%Y-%m-%dT%H:%M:%SZ").strip() or "1970-01-01T00:00:00Z"
    secure = _capture_settings(container, "secure", ["android_id", "default_input_method"])
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
    return {
        "label": label,
        "capturedAt": captured_at,
        "sdkInt": int(props.get("ro.build.version.sdk") or 31),
        "systemProperties": {k: props.get(k, "") for k in _PROP_KEYS},
        "existingFiles": (["/system/xbin/su"] if su_present else []),
        "readableFiles": readable,
        "settingsSecure": secure, "settingsGlobal": glob, "settingsSystem": {},
        "telephony": {}, "installedPackages": ["android", "com.android.systemui"],
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

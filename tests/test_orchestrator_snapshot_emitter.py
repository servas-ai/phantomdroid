"""Unit test for live_matrix snapshot YAML emitter (settings maps)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agents.orchestrator.src.live_matrix import _yaml_dump_snapshot  # noqa: E402

def test_emitter_renders_settings_maps_and_empty():
    snap = {
        "label": "x", "capturedAt": "2026-05-31T00:00:00Z", "sdkInt": 31,
        "systemProperties": {"ro.product.brand": "google"},
        "existingFiles": [], "readableFiles": {"/proc/version": "Linux x"},
        "settingsSecure": {"android_id": "fbd37772bd01a050"},
        "settingsGlobal": {"adb_enabled": "0"}, "settingsSystem": {},
        "telephony": {}, "installedPackages": ["android"],
        "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": "America/Los_Angeles", "localeLanguage": "en", "localeCountry": "US",
        "displayWidthPixels": 1080, "displayHeightPixels": 2400, "displayDensityDpi": 420,
        "gpsLat": None, "gpsLng": None,
        "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    out = _yaml_dump_snapshot(snap)
    assert 'settingsSecure:' in out and '"android_id": "fbd37772bd01a050"' in out
    assert 'settingsGlobal:' in out and '"adb_enabled": "0"' in out
    assert 'settingsSystem: {}' in out   # empty map stays inline
    import yaml
    parsed = yaml.safe_load(out)
    assert parsed["settingsSecure"]["android_id"] == "fbd37772bd01a050"
    assert parsed["timezoneId"] == "America/Los_Angeles"
    assert parsed["localeCountry"] == "US"
    assert parsed["displayWidthPixels"] == 1080 and parsed["displayDensityDpi"] == 420


def test_emitter_escapes_multiline_readable_files():
    import yaml
    from agents.orchestrator.src.live_matrix import _yaml_dump_snapshot
    status = "Name:\tcat\nState:\tR (running)\nTracerPid:\t0\n"
    snap = {
        "label": "x", "capturedAt": "t", "sdkInt": 31, "systemProperties": {},
        "existingFiles": [], "readableFiles": {"/proc/self/status": status},
        "settingsSecure": {}, "settingsGlobal": {}, "settingsSystem": {}, "telephony": {},
        "installedPackages": [], "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": None, "localeLanguage": None, "localeCountry": None,
        "displayWidthPixels": None, "displayHeightPixels": None, "displayDensityDpi": None,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    parsed = yaml.safe_load(_yaml_dump_snapshot(snap))
    # newlines round-trip so the TracerPid line survives for the probe
    assert "TracerPid:\t0" in parsed["readableFiles"]["/proc/self/status"]
    assert parsed["readableFiles"]["/proc/self/status"].count("\n") == 3


def test_capture_telephony_reflects_device_and_keeps_null_imei(monkeypatch):
    """Telephony capture maps real device reads onto TelephonyField names and
    NEVER fabricates an IMEI: a device that exposes no IMEI must yield IMEI=null."""
    import yaml
    from agents.orchestrator.src import live_matrix

    # Simulate the live B2 device: real serial/operator/ICCID set by the
    # L2/L6 resetprop pass, but NO IMEI exposed (ReDroid has none).
    props = {
        "gsm.operator.alpha": "AT&T",
        "gsm.operator.numeric": "310410",
        "gsm.sim.operator.alpha": "AT&T",
        "gsm.sim.operator.numeric": "310410",
        "gsm.sim.state": "READY",
        "gsm.network.type": "LTE",
        "ril.iccid.sim1": "8901410329988776652",
        "ro.serialno": "2A111FDH2002KQ",
    }

    def fake_exec(container, shell_cmd):
        if "iphonesubinfo" in shell_cmd:
            # service call returns a hex parcel, never a bare IMEI -> treated as none
            return "Result: Parcel(00000000 00000000 '........')"
        if shell_cmd.startswith("getprop ril.imei"):
            return ""  # no IMEI prop either
        # batch getprop echo block
        lines = []
        for k, v in props.items():
            if f"getprop {k}" in shell_cmd or f'echo "{k}|' in shell_cmd:
                lines.append(f"{k}|{v}")
        return "\n".join(lines)

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    tel = live_matrix.capture_telephony("b2-test")

    # Real device values are reflected faithfully.
    assert tel["OPERATOR_NAME"] == "AT&T"
    assert tel["MCC_MNC"] == "310410"
    assert tel["SIM_SERIAL"] == "8901410329988776652"
    assert tel["SERIAL"] == "2A111FDH2002KQ"
    # CRITICAL anti-fabrication invariant: no IMEI exposed -> IMEI stays null.
    assert tel["IMEI"] is None
    # All TelephonyField keys present so the detector accessor "worked"
    # (benign null-IMEI path, not a stripped tell).
    assert set(tel) == {"IMEI", "SERIAL", "OPERATOR_NAME", "MCC_MNC", "SIM_SERIAL"}

    # The YAML emitter serialises the null IMEI as YAML null (not a fake digit string).
    snap = {
        "label": "x", "capturedAt": "t", "sdkInt": 31, "systemProperties": {},
        "existingFiles": [], "readableFiles": {},
        "settingsSecure": {}, "settingsGlobal": {}, "settingsSystem": {},
        "telephony": tel, "installedPackages": [], "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": None, "localeLanguage": None, "localeCountry": None,
        "displayWidthPixels": None, "displayHeightPixels": None, "displayDensityDpi": None,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    parsed = yaml.safe_load(_yaml_dump_snapshot(snap))
    assert parsed["telephony"]["IMEI"] is None
    assert parsed["telephony"]["SERIAL"] == "2A111FDH2002KQ"
    assert parsed["telephony"]["SIM_SERIAL"] == "8901410329988776652"


def test_capture_root_surface_does_not_underreport_magisk(monkeypatch):
    """ROOT-HONESTY invariant: a Magisk-rooted device where /sbin/su and
    /data/adb/magisk are present MUST have those paths recorded in the captured
    root surface — root is NOT under-reported. This guards the B2 overclaim bug
    where the capture probed only /system/xbin/su and yielded a false
    su_detection=0.0 (false CLEAN) on a rooted container."""
    from agents.orchestrator.src import live_matrix

    # Live Magisk-rooted device: /sbin/su + /sbin/.magisk + /data/adb/magisk
    # present; the Magisk manager APK installed. /system/xbin/su (the path the
    # OLD buggy capture probed) is ABSENT — proving the fix probes the full set.
    present_on_device = {"/sbin/su", "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules"}

    def fake_exec(container, shell_cmd):
        if "pm list packages" in shell_cmd:
            return "package:android\npackage:com.topjohnwu.magisk\npackage:com.android.systemui\n"
        # Root-path probe shell: `[ -e "P" ] && echo "P"` per path. Emit only
        # the paths that actually exist on this simulated device.
        if "[ -e " in shell_cmd:
            return "\n".join(p for p in present_on_device if f'[ -e "{p}" ]' in shell_cmd)
        return ""

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    root_files, pkgs = live_matrix.capture_root_surface("b2-test")

    # The Magisk root artifacts are recorded — NOT hidden / under-reported.
    assert "/sbin/su" in root_files
    assert "/data/adb/magisk" in root_files
    assert "/sbin/.magisk" in root_files
    # The buggy single-path (/system/xbin/su) is absent on this device, yet root
    # is still detected via the OTHER paths — the whole point of the fix.
    assert "/system/xbin/su" not in root_files
    # Superuser package captured honestly so the detector's package check is real.
    assert "com.topjohnwu.magisk" in pkgs


def test_capture_telephony_never_invents_imei_from_garbage(monkeypatch):
    """Even if `service call iphonesubinfo` returns a non-empty hex blob, the
    capture must not coerce it into a fake IMEI — non-digit -> null."""
    from agents.orchestrator.src import live_matrix

    def fake_exec(container, shell_cmd):
        if "iphonesubinfo" in shell_cmd:
            return "Result: Parcel(deadbeef)"
        return ""  # no telephony props at all

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    tel = live_matrix.capture_telephony("b2-test")
    assert tel["IMEI"] is None
    assert tel["OPERATOR_NAME"] is None and tel["MCC_MNC"] is None


def test_capture_mount_and_uds_populates_self_and_init_when_device_provides(monkeypatch):
    """MOUNT-NS HONESTY invariant: when the device exposes /proc/self/mountinfo
    AND /proc/1/mountinfo (read via su -c), the capture MUST populate
    mountInfo["self"] and mountInfo["1"] with the real content — not the
    conservative NULL the prior capture emitted (which made the dispositive
    Momo/RootBeer mount-ns probes score a no-observation 0.0 lower bound).
    /proc/net/unix socket NAMES (field 8) are harvested for MagiskUdsProbe."""
    import yaml
    from agents.orchestrator.src import live_matrix

    self_mountinfo = (
        "1 0 253:0 / / rw,relatime - overlay overlay rw\n"
        "2 1 0:42 / /system ro,relatime - ext4 /dev/block/system ro\n"
    )
    init_mountinfo = (
        "1 0 253:0 / / rw,relatime - overlay overlay rw\n"
        "99 1 0:50 / /sbin/.magisk rw - tmpfs magisk rw\n"
    )
    proc_net_unix = (
        "Num       RefCount Protocol Flags    Type St Inode Path\n"
        "0000: 00000002 00000000 00010000 0001 01 12345 @magisk\n"
        "0000: 00000002 00000000 00010000 0001 01 12346 /dev/socket/installd\n"
        "0000: 00000002 00000000 00010000 0001 01 12347\n"  # unnamed: no field 8
    )

    def fake_exec(container, shell_cmd):
        if "/proc/self/mountinfo" in shell_cmd:
            return self_mountinfo
        if "/proc/1/mountinfo" in shell_cmd:
            return init_mountinfo
        if "/proc/net/unix" in shell_cmd:
            return proc_net_unix
        return ""

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    mount_info, sockets = live_matrix.capture_mount_and_uds("b2-test")

    # Both PIDs populated with the REAL device content — not NULL.
    assert mount_info["self"] is not None
    assert mount_info["1"] is not None
    assert "/system" in mount_info["self"]
    # The Magisk mount-bind in init's namespace is preserved verbatim so
    # MountNsMismatchProbe sees the asymmetry (Momo #1 signal).
    assert "/sbin/.magisk" in mount_info["1"]
    assert "/sbin/.magisk" not in mount_info["self"]

    # Named/abstract sockets harvested; the unnamed socket (no field 8) skipped.
    assert "@magisk" in sockets
    assert "/dev/socket/installd" in sockets
    assert len(sockets) == 2  # header + unnamed line excluded

    # The YAML emitter round-trips mountInfo (map) and procNetUnixSockets (list)
    # into the exact field shape the detection-cli SnapshotDto consumes.
    snap = {
        "label": "x", "capturedAt": "t", "sdkInt": 31, "systemProperties": {},
        "existingFiles": [], "readableFiles": {},
        "settingsSecure": {}, "settingsGlobal": {}, "settingsSystem": {},
        "telephony": {}, "installedPackages": [],
        "mountInfo": mount_info, "procNetUnixSockets": sockets,
        "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": None, "localeLanguage": None, "localeCountry": None,
        "displayWidthPixels": None, "displayHeightPixels": None, "displayDensityDpi": None,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    parsed = yaml.safe_load(_yaml_dump_snapshot(snap))
    assert "/sbin/.magisk" in parsed["mountInfo"]["1"]
    assert "/sbin/.magisk" not in parsed["mountInfo"]["self"]
    assert "@magisk" in parsed["procNetUnixSockets"]


def test_capture_mount_and_uds_records_null_when_unreadable(monkeypatch):
    """ANTI-FABRICATION invariant: when a mountinfo read fails (denied / empty),
    the capture records NULL (no observation) — it MUST NOT invent content.
    The conservative null is the honest answer and keeps the mount-ns probes on
    their no-observation 0.0 path rather than a fabricated detection."""
    from agents.orchestrator.src import live_matrix

    def fake_exec(container, shell_cmd):
        return ""  # every read denied/empty

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    mount_info, sockets = live_matrix.capture_mount_and_uds("b2-test")
    assert mount_info["self"] is None
    assert mount_info["1"] is None
    assert sockets == []


# ── Bucket-B capture-gap closures (2026-06-01) ──────────────────────────────
# These tests pin the HONESTY contract for the four bucket-A probe inputs that
# live_matrix now captures (vendor fingerprint + the touch/audio/network props,
# the /data/adb/modules dir listing, and the init.svc.* map) and the emitter
# fields that carry them. The load-bearing invariant: a GENUINELY-UNSET
# property is OMITTED from systemProperties (-> getSystemProperty null), NEVER
# fabricated as an empty string — otherwise probes that treat a non-null empty
# string as "the key exists" (NetworkIpAsn qemud, Audio HAL) fire from absence.


def test_parse_getprop_dump_distinguishes_unset_from_set_empty():
    """A key ABSENT from the getprop dump must NOT appear in the parsed map
    (-> null/unset), while a key set to the empty string `[k]: []` IS recorded
    with value "". This is the distinction that prevents fabricated presence."""
    from agents.orchestrator.src.live_matrix import _parse_getprop_dump

    dump = (
        "[persist.sys.usb.config]: [adb]\n"
        "[ro.vendor.build.fingerprint]: [redroid/redroid_x86_64_only:12/x]\n"
        "[ro.audio.silent.in]: []\n"          # SET to empty string
        "[init.svc.zygote]: [running]\n"
        "[init.svc.magiskd]: [running]\n"
    )
    parsed = _parse_getprop_dump(dump)
    assert parsed["persist.sys.usb.config"] == "adb"
    assert parsed["ro.vendor.build.fingerprint"] == "redroid/redroid_x86_64_only:12/x"
    # set-empty is recorded as "" (present, value empty)
    assert "ro.audio.silent.in" in parsed and parsed["ro.audio.silent.in"] == ""
    # genuinely-unset keys are simply not in the dump -> absent from the map
    assert "ro.kernel.android.qemud" not in parsed
    assert "ro.hardware.touchscreen" not in parsed
    assert parsed["init.svc.zygote"] == "running"


def test_capture_live_snapshot_omits_unset_props_no_fabrication(monkeypatch):
    """ANTI-FABRICATION: an unset prop (absent from the getprop dump) must be
    OMITTED from systemProperties so getSystemProperty returns null, NOT a
    fabricated "". A set-but-empty prop IS kept. This is what keeps
    NetworkIpAsnProbe (qemudExists = prop != null) and AudioFingerprintProbe
    (noHalNoDevice = hal != null && hal.isEmpty()) off their absence branches."""
    from agents.orchestrator.src import live_matrix

    # Minimal honest dump: ro.boot.hardware=redroid is the only emulator tell;
    # qemud / touchscreen / audio HAL are genuinely UNSET (not in the dump).
    dump = (
        "[sys.boot_completed]: [1]\n"
        "[ro.build.version.sdk]: [31]\n"
        "[ro.boot.hardware]: [redroid]\n"
        "[persist.sys.usb.config]: [adb]\n"
        "[ro.vendor.build.fingerprint]: [redroid/redroid_x86_64_only:12/x]\n"
        "[ro.audio.silent.in]: []\n"
    )

    def fake_exec(container, shell_cmd):
        if shell_cmd == "getprop":
            return dump
        # everything else (files, settings, wm, etc.) is empty/no-observation
        return ""

    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    # capture_telephony is exercised separately; stub it to keep this focused.
    monkeypatch.setattr(live_matrix, "capture_telephony", lambda c: {
        "IMEI": None, "SERIAL": None, "OPERATOR_NAME": None,
        "MCC_MNC": None, "SIM_SERIAL": None,
    })
    snap = live_matrix.capture_live_snapshot("b2-test", "honest")
    sp = snap["systemProperties"]
    # genuinely-unset -> OMITTED (null), never fabricated ""
    assert "ro.kernel.android.qemud" not in sp
    assert "ro.hardware.touchscreen" not in sp
    assert "ro.hardware.audio" not in sp
    # genuinely-set -> present with real value
    assert sp["ro.boot.hardware"] == "redroid"
    assert sp["persist.sys.usb.config"] == "adb"
    assert sp["ro.vendor.build.fingerprint"].startswith("redroid/")
    # set-but-empty -> present with ""
    assert "ro.audio.silent.in" in sp and sp["ro.audio.silent.in"] == ""


def test_capture_dir_entries_modules_present_empty_vs_absent(monkeypatch):
    """queryDirEntries contract: a present-but-empty /data/adb/modules yields
    [] (dir_empty, Magisk installed); an absent/unreadable dir yields the KEY
    OMITTED (queryDirEntries -> null, no_observation 0.0). Never fabricated."""
    from agents.orchestrator.src import live_matrix

    # present + empty
    def fake_present_empty(container, shell_cmd):
        return "__DIR_OK__\n"
    monkeypatch.setattr(live_matrix, "_docker_exec", fake_present_empty)
    de = live_matrix.capture_dir_entries("b2")
    assert de == {"/data/adb/modules": []}

    # present + modules
    def fake_present_modules(container, shell_cmd):
        return "__DIR_OK__\nzygisk_lsposed\nsafetynet-fix\n"
    monkeypatch.setattr(live_matrix, "_docker_exec", fake_present_modules)
    de = live_matrix.capture_dir_entries("b2")
    assert de["/data/adb/modules"] == ["zygisk_lsposed", "safetynet-fix"]

    # absent / unreadable -> key omitted (null observation)
    def fake_absent(container, shell_cmd):
        return ""
    monkeypatch.setattr(live_matrix, "_docker_exec", fake_absent)
    de = live_matrix.capture_dir_entries("b2")
    assert de == {}


def test_capture_init_svc_props_maps_service_states(monkeypatch):
    from agents.orchestrator.src import live_matrix

    def fake_exec(container, shell_cmd):
        return (
            "[init.svc.zygote]: [running]\n"
            "[init.svc.adbd]: [running]\n"
            "[ro.build.type]: [user]\n"          # non-init.svc -> excluded
            "[init.svc.magiskd]: [stopped]\n"
        )
    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    isp = live_matrix.capture_init_svc_props("b2")
    assert isp == {
        "init.svc.zygote": "running",
        "init.svc.adbd": "running",
        "init.svc.magiskd": "stopped",
    }
    assert "ro.build.type" not in isp


def test_capture_probe_filesystem_records_only_present_paths(monkeypatch):
    """fileExists inputs: only paths that ACTUALLY exist are recorded; an
    absent path is omitted (probe sees fileExists == false). No fabrication."""
    from agents.orchestrator.src import live_matrix

    def fake_exec(container, shell_cmd):
        # device exposes touch event0 + the ALSA node, but no KernelSU/APatch
        return "/dev/input/event0\n/dev/snd/controlC0\n"
    monkeypatch.setattr(live_matrix, "_docker_exec", fake_exec)
    files = live_matrix.capture_probe_filesystem("b2")
    assert "/dev/input/event0" in files
    assert "/dev/snd/controlC0" in files
    assert "/data/adb/ksu" not in files
    assert "/data/adb/ap" not in files


def test_emitter_renders_dir_entries_and_init_svc_props():
    import yaml
    from agents.orchestrator.src.live_matrix import _yaml_dump_snapshot
    snap = {
        "label": "x", "capturedAt": "t", "sdkInt": 31,
        "systemProperties": {"ro.boot.hardware": "redroid"},
        "existingFiles": ["/dev/input/event0"], "readableFiles": {},
        "settingsSecure": {}, "settingsGlobal": {}, "settingsSystem": {},
        "telephony": {}, "installedPackages": [],
        "dirEntries": {"/data/adb/modules": []},
        "initSvcProps": {"init.svc.zygote": "running", "init.svc.magiskd": "stopped"},
        "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": None, "localeLanguage": None, "localeCountry": None,
        "displayWidthPixels": None, "displayHeightPixels": None, "displayDensityDpi": None,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    parsed = yaml.safe_load(_yaml_dump_snapshot(snap))
    # present-but-empty modules dir round-trips as []
    assert parsed["dirEntries"]["/data/adb/modules"] == []
    assert parsed["initSvcProps"]["init.svc.zygote"] == "running"
    assert parsed["initSvcProps"]["init.svc.magiskd"] == "stopped"


def test_emitter_dir_entries_with_modules_round_trips():
    import yaml
    from agents.orchestrator.src.live_matrix import _yaml_dump_snapshot
    snap = {
        "label": "x", "capturedAt": "t", "sdkInt": 31, "systemProperties": {},
        "existingFiles": [], "readableFiles": {},
        "settingsSecure": {}, "settingsGlobal": {}, "settingsSystem": {},
        "telephony": {}, "installedPackages": [],
        "dirEntries": {"/data/adb/modules": ["zygisk_lsposed", "safetynet-fix"]},
        "initSvcProps": {},
        "sensorTypes": [], "bluetoothMac": None,
        "timezoneId": None, "localeLanguage": None, "localeCountry": None,
        "displayWidthPixels": None, "displayHeightPixels": None, "displayDensityDpi": None,
        "gpsLat": None, "gpsLng": None, "gpsAccuracy": None, "gpsProvider": None, "gpsIsMock": None,
    }
    parsed = yaml.safe_load(_yaml_dump_snapshot(snap))
    assert parsed["dirEntries"]["/data/adb/modules"] == ["zygisk_lsposed", "safetynet-fix"]
    assert parsed["initSvcProps"] == {}

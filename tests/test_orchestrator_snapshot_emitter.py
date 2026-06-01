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

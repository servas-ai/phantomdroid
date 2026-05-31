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

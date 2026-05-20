#!/system/bin/sh
# SpoofStack service.d/01-settings.sh
#
# Runs AFTER SystemUI / Settings provider is available — `settings put`
# requires the SettingsProvider service to be up. We retry up to 60s in
# case Zygote / system_server is slow to come up on a cold boot.
#
# Source of truth: audit/spoof-stack/production-hooks-spec.md §4.

set -e

# ---------------------------------------------------------------------------
# Wait for SettingsProvider to be ready. `settings get` returns 'null'
# rather than failing once the service is up; we poll for that signal.
# ---------------------------------------------------------------------------
wait_for_settings() {
    i=0
    while [ "$i" -lt 60 ]; do
        if settings get global development_settings_enabled >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    echo "SpoofStack: SettingsProvider did not become ready within 60s" >&2
    return 1
}

wait_for_settings

# ---------------------------------------------------------------------------
# §4.1 Settings.Secure
# ---------------------------------------------------------------------------
settings put secure android_id a1b2c3d4e5f60718
settings put secure default_input_method "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
settings put secure "location.is_from_mock_provider" 0
settings put secure mock_location 0
settings put secure mock_location_app ""
settings put secure enabled_accessibility_services ""
settings put secure location_providers_allowed "gps,network"

# ---------------------------------------------------------------------------
# §4.2 Settings.Global
# ---------------------------------------------------------------------------
settings put global development_settings_enabled 0
settings put global adb_enabled 0
settings put global adb_wifi_enabled 0
settings put global package_verifier_enable 1
settings put global http_proxy ""
settings put global private_dns_mode off

# ---------------------------------------------------------------------------
# §5.6 DisplayMetrics (rank 23) — framework-level density override.
# Pair with the `--display=1080x2400` ReDroid launch flag (documented in
# README.md). `wm` requires SettingsProvider so it lives here, not in
# 00-spoof.sh.
# ---------------------------------------------------------------------------
wm size 1080x2400
wm density 420

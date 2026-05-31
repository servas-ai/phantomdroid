# agents/orchestrator/src/config_loader.py
#
# Load + validate a run manifest (SPEC §4 config_loader.py, §5 manifest format).
# Refuses unknown keys (SPEC: "Refuse unknown keys"). Validation is hand-rolled
# against runner-manifest.schema.json (stdlib ethos — mirrors report_validator.py),
# so the loader has no hard jsonschema dependency. YAML parsing uses PyYAML when
# present, with a minimal flat-key fallback so the loader also works stdlib-only.

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

SCHEMA_PATH = Path(__file__).resolve().parents[1] / "runner-manifest.schema.json"

_REQUIRED = ["schema_version", "config_id", "container_image_hash",
             "compose_file", "seccomp_profile", "target_runs"]
_ALLOWED = set(_REQUIRED) | {
    "created", "detector_lab_apk", "detector_lab_apk_hash", "warmup_seconds",
    "probe_timeout_seconds", "binder_device", "seed", "modules",
}
_SHA256_RE = re.compile(r"^sha256:[0-9a-f]{12,64}$")
_CONFIG_ID_RE = re.compile(r"^[a-zA-Z0-9._-]+$")


class ManifestError(ValueError):
    """Raised when a manifest is malformed or violates the schema (SPEC §7 SCHEMA_FAIL)."""


def _parse_yaml(text: str) -> dict:
    try:
        import yaml  # type: ignore
        data = yaml.safe_load(text)
        if not isinstance(data, dict):
            raise ManifestError("manifest is not a mapping")
        return data
    except ImportError:
        # Minimal stdlib fallback: flat `key: value` lines (scalars only).
        out: dict[str, Any] = {}
        for raw in text.splitlines():
            line = raw.split("#", 1)[0].rstrip()
            if not line.strip() or ":" not in line or line[0].isspace():
                continue
            k, _, v = line.partition(":")
            v = v.strip().strip('"').strip("'")
            if v.lstrip("-").isdigit():
                out[k.strip()] = int(v)
            else:
                out[k.strip()] = v
        return out


def _req(cond: bool, msg: str) -> None:
    if not cond:
        raise ManifestError(msg)


def validate_manifest(m: dict) -> dict:
    """Hand-rolled validation mirroring runner-manifest.schema.json. Returns m on success."""
    _req(isinstance(m, dict), "manifest root is not a mapping")
    unknown = set(m) - _ALLOWED
    _req(not unknown, f"unknown manifest keys (refused): {sorted(unknown)}")
    for key in _REQUIRED:
        _req(key in m, f"required key missing: {key}")
    _req(m["schema_version"] == "runner.v1", f"schema_version must be 'runner.v1', got {m['schema_version']!r}")
    _req(isinstance(m["config_id"], str) and bool(_CONFIG_ID_RE.match(m["config_id"])),
         f"config_id invalid (must match [a-zA-Z0-9._-]+): {m.get('config_id')!r}")
    _req(isinstance(m["container_image_hash"], str) and bool(_SHA256_RE.match(m["container_image_hash"])),
         "container_image_hash must be 'sha256:<hex>' (F20 pin mandatory)")
    for s in ("compose_file", "seccomp_profile"):
        _req(isinstance(m[s], str) and bool(m[s]), f"{s} must be a non-empty string")
    _req(isinstance(m["target_runs"], int) and not isinstance(m["target_runs"], bool)
         and 1 <= m["target_runs"] <= 1000, "target_runs must be an int in [1,1000]")
    if "detector_lab_apk_hash" in m:
        _req(bool(_SHA256_RE.match(str(m["detector_lab_apk_hash"]))),
             "detector_lab_apk_hash must be 'sha256:<hex>'")
    for opt in ("warmup_seconds", "probe_timeout_seconds"):
        if opt in m:
            _req(isinstance(m[opt], int) and not isinstance(m[opt], bool) and m[opt] >= 0,
                 f"{opt} must be a non-negative int")
    return m


def load_manifest(path: Path | str) -> dict:
    """Load + validate a manifest file. Raises ManifestError on any violation."""
    p = Path(path)
    _req(p.is_file(), f"manifest not found: {p}")
    return validate_manifest(_parse_yaml(p.read_text()))

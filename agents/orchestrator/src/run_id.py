# agents/orchestrator/src/run_id.py
#
# Deterministic run-ID generation (SPEC §6).
#
# run_id = base32(HASH(manifest_canonical_json ‖ apk_sha256 ‖ run_index ‖ schema_version))[:16].lower()
#
# Pure function: same inputs → same run_id (idempotent, F11 OOM-resume relies on this).
# SPEC names BLAKE3; the project is stdlib-only (see report_validator.py), so we use
# hashlib.blake2b (stdlib) as the canonical hash. The algorithm name is recorded in
# the output so a future BLAKE3 swap is detectable and non-silent.

from __future__ import annotations

import base64
import hashlib
import json
from typing import Any

HASH_ALGO = "blake2b"
SCHEMA_VERSION = "1.0"


def canonical_json(obj: Any) -> str:
    """Stable canonical JSON: sorted keys, no insignificant whitespace."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def compute_run_id(
    manifest: dict,
    apk_sha256: str,
    run_index: int,
    schema_version: str = SCHEMA_VERSION,
) -> str:
    """Deterministic 16-char lowercase base32 run-ID. Pure function of its inputs."""
    if run_index < 0:
        raise ValueError(f"run_index must be >= 0, got {run_index}")
    material = "‖".join(
        [
            canonical_json(manifest),
            apk_sha256 or "",
            str(run_index),
            schema_version,
        ]
    )
    digest = hashlib.blake2b(material.encode("utf-8"), digest_size=32).digest()
    b32 = base64.b32encode(digest).decode("ascii").rstrip("=").lower()
    return b32[:16]


def config_id_from_layers(layers: list[str]) -> str:
    """Stable config id from an ordered layer set, e.g. ['L0','L1'] -> 'L0-L1'."""
    if not layers:
        raise ValueError("layer set cannot be empty")
    return "-".join(layers)

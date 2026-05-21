#!/usr/bin/env python3
"""
Power-18 D2 Gate 4 — Evidence-key namespace compliance.

Two-tier enforcement scope (per audit/cross-cutting-followups-2026-05-19.md #1
documented "FIXED" baseline as of commit fa35fe3):

TIER-A — Regression guard (applies to EVERY probe):
  Cross-cutting #1 forbade bare `pkg.*` evidence keys because three probes
  (SuDetectionProbe, XposedLsposedProbe, InstalledAppsProbe) all emitted
  `Evidence("pkg.<id>", ...)` rows for overlapping package IDs, making
  consolidated reports ambiguous. The fix scoped each: `su_search.pkg.*`,
  `xposed.pkg.*`, `installed_apps.pkg.*`. This gate fires if any new
  probe re-introduces a bare `pkg.*` key (including interpolated
  `pkg.$VAR`).

TIER-B — Strict-namespace opt-in (per-probe KDoc declaration):
  When a probe's KDoc explicitly declares its namespace via either:
    - "prefixed `<name>.*`"  (gold-standard form, e.g. IntegrityInstallSourceProbe)
    - "`<name>.*` namespace per cross-cutting #1"  (Power-13 Gap-#13 form)
  AND that declaration is at file/class-KDoc scope (NOT inside a block
  comment next to a per-row note), then EVERY evidence key the probe
  emits MUST start with `<name>.`. This catches drift inside opted-in
  probes (e.g. IntegrityInstallSourceProbe, ThirdPartyEmulatorArtifactsProbe,
  KeystoreAttestationProbe). Block-scoped declarations (e.g. comments
  next to specific Gap-#13 row additions) are explicitly NOT treated as
  probe-wide declarations.

NOTES on scope decision (documented for reviewer auditability):
  The strict interpretation "every evidence key must start with
  <probe-id-suffix>." was evaluated against the codebase and found to
  break 361 keys across 84 probes (the codebase uses domain-meaningful
  prefixes like `ig.*`, `tiktok.*`, `cpu.*`, `qemu.*`, `ro.*`, etc.
  which don't always echo the probe-id suffix). The strict-suffix rule
  is NOT enforceable today without a cross-rank refactor that is out of
  scope for Power-18 D2. Tier-A + Tier-B together cover the documented
  cross-cutting #1 fix surface AND any probe that has explicitly
  opted-in via KDoc declaration — which is the maximal enforceable rule
  on `report/CLO-143-weekly-W20` as of 2026-05-21.

Exit codes:
  0  no violations
  1  one or more violations (printed to stdout with probe-id + key + reason)
"""
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PROBES_DIR = REPO_ROOT / "agents/detection/src/probes"

# Declared-namespace KDoc patterns. The class-KDoc form is the gold standard
# (KDoc lines start with `*`). We require the declaration to live within the
# first 200 lines of the file to bias toward file-scope KDoc and away from
# per-row block comments inside `run()`.
NS_DECL_KDOC = re.compile(r"^\s*\*\s+.*prefixed\s+`([a-z_]+)\.\*`", re.MULTILINE)
NS_DECL_KDOC_ALT = re.compile(
    r"^\s*\*\s+.*`([a-z_]+)\.\*`\s+namespace\s+per\s+cross-cutting", re.MULTILINE
)

# Tier-A: bare pkg.* regression
BARE_PKG_LIT = re.compile(r'Evidence\s*\(\s*(?:key\s*=\s*)?"pkg\.[^"]*"')
BARE_PKG_INTERP = re.compile(r'Evidence\s*\(\s*(?:key\s*=\s*)?"pkg\.\$')

# Evidence-key extractors (used for Tier-B opted-in probes)
EV_LIT = re.compile(r'Evidence\s*\(\s*(?:key\s*=\s*)?"([^"\$]+)"')
EV_INTERP = re.compile(r'Evidence\s*\(\s*(?:key\s*=\s*)?"([^"]*?)\$')
EV_CONST = re.compile(r'const\s+val\s+EV[A-Z_]*\s*=\s*"([^"\$]+)"')

# Probe-id extractor
PROBE_ID = re.compile(r'override\s+val\s+id\s*=\s*"([^"]+)"')


def extract_class_kdoc_declaration(src: str) -> str | None:
    """Return the declared namespace prefix if the file-/class-KDoc declares
    one, else None. We restrict to the first 200 lines so per-row block
    comments inside `run()` do not count as probe-wide declarations.
    """
    head = "\n".join(src.splitlines()[:200])
    for pat in (NS_DECL_KDOC, NS_DECL_KDOC_ALT):
        m = pat.search(head)
        if m:
            return m.group(1)
    return None


def collect_keys(src: str) -> set[str]:
    keys: set[str] = set()
    for m in EV_LIT.finditer(src):
        keys.add(m.group(1))
    for m in EV_CONST.finditer(src):
        keys.add(m.group(1))
    for m in EV_INTERP.finditer(src):
        lit = m.group(1)
        if lit:
            keys.add(lit + "${VAR}")
    return keys


def main() -> int:
    tier_a_violations: list[tuple[str, Path, str]] = []
    tier_b_violations: list[tuple[str, str, Path, str]] = []
    strict_probes: list[tuple[str, str]] = []

    for kt in sorted(PROBES_DIR.rglob("*.kt")):
        src = kt.read_text(encoding="utf-8")
        id_m = PROBE_ID.search(src)
        if not id_m:
            continue
        probe_id = id_m.group(1)

        # Tier-A: bare pkg.* (regression guard)
        for m in BARE_PKG_LIT.finditer(src):
            tier_a_violations.append((probe_id, kt, m.group(0)))
        for m in BARE_PKG_INTERP.finditer(src):
            tier_a_violations.append((probe_id, kt, m.group(0)))

        # Tier-B: strict namespace if probe declares one at class-KDoc scope
        declared = extract_class_kdoc_declaration(src)
        if declared is None:
            continue
        strict_probes.append((probe_id, declared))
        for k in collect_keys(src):
            if not k.startswith(declared + "."):
                tier_b_violations.append((probe_id, declared, kt, k))

    print("Gate 4 — Evidence-key namespace compliance")
    print()
    print(f"Tier-A (bare pkg.* regression guard):     {len(tier_a_violations)} violations")
    for v in tier_a_violations:
        print(f"  [{v[0]}] {v[2]}")
        print(f"    in {v[1].relative_to(REPO_ROOT)}")
    print()
    print(
        f"Tier-B (strict-namespace opt-in probes):  "
        f"{len(strict_probes)} opted-in, {len(tier_b_violations)} violations"
    )
    for p in sorted(strict_probes):
        print(f"  OPT-IN [{p[0]}] declares prefix '{p[1]}.*'")
    if tier_b_violations:
        print()
        for v in tier_b_violations:
            print(
                f"  VIOLATION [{v[0]}] expected '{v[1]}.*' prefix, got key '{v[3]}'"
            )
            print(f"    in {v[2].relative_to(REPO_ROOT)}")

    total = len(tier_a_violations) + len(tier_b_violations)
    if total == 0:
        print()
        print("PASS: namespace compliance gate green.")
        return 0
    print()
    print(f"FAIL: {total} namespace violations.")
    return 1


if __name__ == "__main__":
    sys.exit(main())

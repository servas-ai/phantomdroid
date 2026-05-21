#!/usr/bin/env python3
"""
Power-18 D2 Gate 3 — Panel consistency check.

Compares the probe inventory between two test files that BOTH maintain a
manual `private fun allProbes()` registry:

  agents/detection/src/test/kotlin/com/detectorlab/replay/
    FullProbeRunnerSpoofTest.kt     — 82-probe spoof-replay runner
    CoverageMatrixGeneratorTest.kt  — 82-probe matrix generator (audit doc)

Both lists MUST yield the same set of probe class names AND the same
count. If they diverge a new probe was added to one but not the other —
the spoof-stack invariants and the full-coverage matrix would silently
fall out of sync.

Exit codes:
  0  panels in sync
  1  size mismatch OR set difference (set diff printed to stdout)
  2  unable to locate `allProbes()` block in one of the files

This is a static-text gate — it does NOT compile the tests. The
authoritative size assertion lives inside the tests themselves (they
assert `probes.size == 82` at runtime); this script catches the case
where the assertion would fail by checking the source-of-truth lists
BEFORE the gradle test run.
"""
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FULL_PROBE_RUNNER = REPO_ROOT / (
    "agents/detection/src/test/kotlin/com/detectorlab/replay/"
    "FullProbeRunnerSpoofTest.kt"
)
COVERAGE_MATRIX = REPO_ROOT / (
    "agents/detection/src/test/kotlin/com/detectorlab/replay/"
    "CoverageMatrixGeneratorTest.kt"
)


def extract_probes(path: Path) -> list[str]:
    """Extract the probe-class identifier list from `allProbes()` listOf body.

    Uses paren-depth balancing (NOT a regex) to find the closing `)` of the
    `listOf(` expression. Then scans line-by-line for the first identifier
    on each non-comment line ending in `Probe`.
    """
    src = path.read_text(encoding="utf-8")
    start_m = re.search(
        r"private\s+fun\s+allProbes\([^)]*\)\s*:\s*List<Probe>\s*=\s*listOf\s*\(",
        src,
    )
    if not start_m:
        print(
            f"ERROR: cannot locate `allProbes()` listOf block in {path}",
            file=sys.stderr,
        )
        sys.exit(2)
    # Walk from the opening `(` and track depth until the matching close.
    idx = start_m.end() - 1
    depth = 0
    end: int | None = None
    while idx < len(src):
        c = src[idx]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                end = idx
                break
        idx += 1
    if end is None:
        print(
            f"ERROR: unbalanced parens in `allProbes()` block in {path}",
            file=sys.stderr,
        )
        sys.exit(2)
    block = src[start_m.end():end]
    probes: list[str] = []
    for line in block.splitlines():
        s = line.strip()
        if not s or s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            continue
        m = re.match(r"([A-Z][A-Za-z0-9]*Probe)\b", s)
        if m:
            probes.append(m.group(1))
    return probes


def main() -> int:
    full = extract_probes(FULL_PROBE_RUNNER)
    matrix = extract_probes(COVERAGE_MATRIX)
    print(f"Gate 3 — Panel consistency")
    print(f"  FullProbeRunnerSpoofTest.allProbes():    {len(full)} probes")
    print(f"  CoverageMatrixGeneratorTest.allProbes(): {len(matrix)} probes")

    set_full = set(full)
    set_matrix = set(matrix)
    only_full = set_full - set_matrix
    only_matrix = set_matrix - set_full

    if len(full) != len(matrix) or only_full or only_matrix:
        print()
        print("FAIL: panel inconsistency detected")
        if len(full) != len(matrix):
            print(f"  Size mismatch: {len(full)} (Full) vs {len(matrix)} (Matrix)")
        if only_full:
            print(f"  Only in FullProbeRunnerSpoofTest:    {sorted(only_full)}")
        if only_matrix:
            print(f"  Only in CoverageMatrixGeneratorTest: {sorted(only_matrix)}")
        print()
        print(
            "Fix: sync the `allProbes()` registry in BOTH files. Both lists "
            "must contain the same probe class names. The Coverage matrix "
            "documents the live spoof-stack inventory and the runner asserts "
            "spoof-stack invariants — they cannot drift."
        )
        return 1

    print("PASS: panels in sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())

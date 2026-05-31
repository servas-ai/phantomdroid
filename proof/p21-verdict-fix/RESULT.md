# Plan item — P21 verdict-harness substring-overlap bug FIXED + tested — DONE + E2E

`scripts/p21/run-all-checks.py` `extract_verdict()` had no test coverage and a real correctness bug:
FAIL keywords that are SUBSTRINGS of PASS phrases also fired, neutralising clean verdicts to UNKNOWN.
Concretely **"rooted" ⊂ "not rooted"** (and "root" ⊂ "no root") — so a detector app explicitly reporting
"not rooted" (e.g. RootBeer, Root Checker) scored UNKNOWN instead of PASS. This explains a chunk of the
baseline's 9 UNKNOWN verdicts (`p21/report.json`).

Fix (minimal, principled): mask matched PASS-keyword spans out of the text BEFORE scanning FAIL keywords,
so a fail-substring inside a clean phrase cannot fire. All other tuned keyword behaviour is unchanged.

Coverage: `tests/test_p21_verdict.py` — 14 tests over the full decision matrix (launcher→CRASH,
no-focus→UNKNOWN, system-overlay→UNKNOWN, wrong-pkg→CRASH, on-target FAIL/PASS/both/neither) +
`verdict_matches_expected` + the overlap regressions ("not rooted"→PASS, "is rooted"→FAIL, "no root"→PASS).
Full python suite: 97 passed.

Impact: real-app verdicts are now scored correctly — "not rooted"/"no root" clean states report PASS
instead of being swallowed to UNKNOWN, sharpening the anti-spoof gallery's already-CLEAN result.

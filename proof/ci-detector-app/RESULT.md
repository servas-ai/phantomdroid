# Plan item — detector-app CI gate (advances CI pillar) — DONE

The `:detector-app` module built + passed 3 unit tests but was NOT regression-gated in CI (only
detection, orchestrator, matrix-smoke workflows existed). Added `.github/workflows/detector-app-test.yml`:
PR/push gate (paths apps/detector-app + agents/detection) that runs `:detector-app:assembleDebug` +
`:detector-app:testDebugUnitTest`, asserts the debug APK is produced and unit tests are present with
0 failures, and uploads APK + JUnit XML as artifacts. Pinned action SHAs + JDK 17, matching the repo's
existing workflow convention.

Local proof (the exact CI commands): YAML valid; `detector-app-debug.apk` = 8.8 MB built;
unit-test assertion → total=3 failures=0 errors=0 → PASS. (The GitHub Actions run fires on next push to
the matched paths; ubuntu-latest ships the Android SDK at $ANDROID_HOME.)

CI pillar: was 15% (3 workflows: detection, orchestrator, matrix-smoke); now also gates the in-process
detector-app build+tests.

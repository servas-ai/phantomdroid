// settings.gradle.kts — Cloud Phone Research Planner
//
// Root Gradle settings. Declares the canonical detection module under
// agents/detection/. The historical W3 detector-lab scaffold
// (docs/super-action/W3/detector-lab/, com.detectorlab.*) is intentionally
// NOT registered: per CLO-115 the agents/detection tree is the unified home
// for all DetectorLab probes and the namespace has been normalised to
// com.detectorlab.* across all 22 .kt files.
//
// To add a new agent module later:
//   include(":<agent-name>")
//   project(":<agent-name>").projectDir = file("agents/<agent-name>")

rootProject.name = "cloud-phone-research-planner"

include(":detection")
project(":detection").projectDir = file("agents/detection")

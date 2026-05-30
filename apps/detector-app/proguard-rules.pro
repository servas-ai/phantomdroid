# DetectorLab detector-app — ProGuard/R8 rules.
#
# Probes are instantiated reflectively-free (explicit list literal in
# AndroidProbeRegistry), so no keep rules are required for the probe classes.
# Release builds are not minified (isMinifyEnabled = false); this file exists
# so getDefaultProguardFile(...) has a project rules file to merge. Add keep
# rules here only if a future release enables minification.

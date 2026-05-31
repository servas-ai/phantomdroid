# Plan item — detector-lab probe_emit.py test coverage (CLO-21 schema-v2 gate) — DONE + E2E

`apps/detector-lab/scripts/probe_emit.py` (host-side schema-v2 record builder for the droidrun cell
driver) had NO test coverage, despite its own docstring noting it exists so "unit tests target the
normaliser directly" and a CI ajv gate (CLO-21) that fails any record drifting from
`shared/probe-schema.v2.json`. Added `tests/test_probe_emit.py` (6 tests):
- all 9 required schema-v2 fields present + `schema_version == "2.0"`
- built record passes jsonschema validation against `shared/probe-schema.v2.json`
- no additionalProperties leak (envelope is `additionalProperties:false`)
- invalid `category` rejected (SystemExit)
- invalid `layer` rejected (SystemExit)
- the reference fixture `examples/probe-result.fixture.json` still validates (regression)

E2E: `probe_emit.py ... | jsonschema.validate` → PASS (see sample-record.json). 6/6 tests green.

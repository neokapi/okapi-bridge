# Parity suite

This module runs neokapi's Go ports of Okapi filters and steps head-to-head
against the Okapi Framework, through this repository's bridge plugin and, when
a build is reachable, the tikal CLI. It answers one question per filter: given
the same input and the same semantic configuration, do the two implementations
produce the same content?

The suite is a scheduled conformance report, not a gate. It runs weekly from
`.github/workflows/parity.yml`, which installs a released kapi, builds the
plugin from this checkout, runs the suite and uploads the report as an
artifact. No pull request here or in `neokapi/neokapi` waits on it.

## Layout

| Path | Contents |
| --- | --- |
| `*.go` | The harness: sandbox resolution, daemon launch, native and bridge runners, the canonical comparison, the report collector |
| `spec/` | `ParityRunner`, which drives a format's `spec.yaml` through both implementations |
| `formats/` | One spec test per filter, the harvested fixtures, and the `bridge_config` translators |
| `roundtrip/` | The three-engine round-trip harness (see `docs/roundtrip-testing.md`) |
| `tools/` | The step table: every bridge step run for stability |
| `annotations/` | Per-format fixture annotations: severity, issue link and skip directives for known divergences |
| `cmd/testcompare` | Turns the raw report into the per-filter summary |
| `cmd/okapi-test-scan` | Harvests `@Test` snippets from the Okapi Java sources into `formats/fixtures_*_generated.go` |
| `pseudobench/` | Pseudo-translation throughput of kapi with and without the bridge over the Okapi corpus |
| `scripts/sandbox.sh` | Builds the sandbox the suite runs against |

## Dependencies

`go.mod` requires a released `github.com/neokapi/neokapi` and nothing from the
`host` or `cli` modules: the suite compiles from the module proxy, and the
daemon launcher is the plugin protocol's Mode C handshake implemented here.
Format specs (`core/formats/<id>/spec.yaml`) are read from that module's
source directory, which `NeokapiDir` resolves with `go list -m`, so the specs
and the readers under test come from one version.

Bump the pin when a new kapi release is out:

```bash
cd parity && go get github.com/neokapi/neokapi@v1.3.0 && go mod tidy
```

## Running locally

```bash
make parity-test                 # sandbox + full suite
make parity-publish              # suite + per-filter summary under .parity/
KAPI_BIN=/path/to/kapi make parity-test
OKAPI_VERSION=1.47.0 make parity-test
PARITY_FORCE=1 make parity-sandbox   # rebuild the sandbox
```

The sandbox lives under `.parity/` (gitignored):

```
.parity/
├── bin/kapi                  the kapi under test (copied from $KAPI_BIN or PATH)
├── plugins/okapi-bridge/     the plugin tarball built by `make plugin-v2`, unpacked
├── okapi-testdata/<version>/ the okapi-testdata release (binary fixtures)
└── test-comparison.json      the raw report
```

Tests resolve the sandbox from `KAPI_PARITY_SANDBOX`, or by walking up from
the working directory to a `.parity/bin/kapi`, and fail when there is none.
`KAPI_PARITY_SKIP=1` turns that failure into a skip for runs that do not care
about parity. Nothing in the suite reads the user's installed plugins.

One format:

```bash
cd parity && KAPI_PARITY_SANDBOX=$PWD/../.parity \
  go test -tags "fts5,parity" -count=1 -run TestParityHtmlSpec ./formats/
```

Every tag the suite needs is spelled in one `-tags` flag; `go` keeps only the
last occurrence of the flag.

## Reading the report

`test-comparison.json` holds one row per filter or step and pass:

- `format`: the native reader against the bridge reader on the same input,
  compared on the canonical projection (part types, block ids, translatable
  flag, source and target text with structural placeholders for inline codes).
- `format-roundtrip`: read then write, compared on bytes, where both sides
  declare a writer.
- `format-tikal`: the native round trip against an extract-then-merge run of
  the Okapi CLI, when `OKAPI_TIKAL` points at a launcher.
- `format-fixture`: one row per harvested upstream `@Test` fixture.
- `step`: a bridge step run against a fixed input for stability.

`expected_fail` and `parity_warn` are documented divergences, not regressions;
the annotation under `annotations/<format>.yaml` or the `expected_fail` entry
in the format's `spec.yaml` says why. `cmd/testcompare` folds the rows into
`parity-report.json`: one row per filter or step with totals.

## Adding a parity case

1. Add or extend the format's `spec.yaml` in neokapi (`core/formats/<id>/`):
   the bridge filter class, the tikal corner and the parity skips are declared
   there.
2. Add `formats/<id>_spec_test.go` here, wiring the native reader for a
   head-to-head comparison or leaving it unset for a bridge-only snapshot. Add
   `<id>_bridge_config.go` when the bridge's parameter shape differs.
3. For a step, add a row to the table in `tools/spec.go`.
4. Run the suite. A pure default-only difference is converged with explicit
   configuration in the bridge config translator, not recorded as an expected
   failure.

The harvested fixtures are regenerated with `make regen-okapi-fixtures
OKAPI_REPO=/path/to/Okapi`; review the diff before committing.

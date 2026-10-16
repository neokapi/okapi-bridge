// Package parity is the conformance suite that runs neokapi's Go ports of
// Okapi filters and steps head-to-head against the Okapi Framework, through
// this repository's bridge plugin and, where a build is reachable, the tikal
// CLI.
//
// The suite compiles against a released github.com/neokapi/neokapi module and
// drives a released kapi binary. It is gated behind the build tag "parity"
// and needs a sandbox:
//   - a kapi binary at $KAPI_PARITY_SANDBOX/bin/kapi
//   - the okapi-bridge plugin unpacked at $KAPI_PARITY_SANDBOX/plugins/okapi-bridge
//   - optionally, the okapi-testdata release tree under $KAPI_PARITY_SANDBOX/okapi-testdata/<version>
//
// `make parity-sandbox` in this repository builds that layout from the kapi
// on PATH (or $KAPI_BIN) and a freshly built plugin tarball; `make
// parity-test` runs the suite against it. Tests called outside this
// orchestration fail with an explanatory message rather than measuring a
// system-installed plugin.
//
// # Architecture
//
//	┌─────────────────────────────────────────────────────────────────────┐
//	│  parity test (build tag: parity)                                    │
//	│                                                                     │
//	│  RunNative(...) ──────────────► neokapi format reader (in-process)  │
//	│                                          │                          │
//	│                                  []model.Part                       │
//	│                                          ▼                          │
//	│  RunBridge(...) ──────────────► daemon Process RPC                  │
//	│       │                                  │                          │
//	│       └─► StartDaemon ─────────► kapi-okapi-bridge daemon (JVM)     │
//	│                                  []model.Part                       │
//	│                                          ▼                          │
//	│  CompareEvents(t, native, bridge)                                   │
//	│  CompareBytes(t, want, got)                                         │
//	└─────────────────────────────────────────────────────────────────────┘
//
// # Environment isolation
//
// The harness never reads the user's $XDG_DATA_HOME/kapi/plugins/ or honours
// an inherited $KAPI_PLUGINS_DIR. Every test resolves the sandbox through
// RequireSandbox and fails when there is none, so a run always measures the
// plugin that was just built.
//
// # Format specs
//
// The per-format parity knowledge (bridge filter class, tikal corner, skips)
// lives in core/formats/<id>/spec.yaml inside the neokapi module. NeokapiDir
// locates that module's source directory, so the specs and the readers under
// test come from one version. Per-fixture divergence annotations live in this
// module under annotations/<format>.yaml.
//
// # Reporting
//
// Tests opt in to the JSON report via the [Report] helper. The collector
// writes one record per filter or step into $KAPI_PARITY_REPORT (default
// $KAPI_PARITY_SANDBOX/test-comparison.json); cmd/testcompare turns that into
// the per-filter summary.
package parity

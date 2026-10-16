//go:build parity

package formats

import (
	"testing"

	"github.com/neokapi/neokapi/core/format"
	"github.com/neokapi/neokapi/core/formats/csv"
	parityspec "github.com/neokapi/okapi-bridge/parity/spec"
)

// TestParityCsvSpec drives the CSV spec.yaml through the bridge AND
// the native reader, validating both against the spec contract and
// against each other. The same spec file drives the always-on native
// test (core/formats/csv/spec_test.go), so a single source of truth
// describes CSV behavior across implementations.
func TestParityCsvSpec(t *testing.T) {
	specPath := nativeSpecPath(t, "csv")
	s, err := parityspec.LoadSpec(specPath)
	if err != nil {
		t.Fatalf("load spec %s: %v", specPath, err)
	}
	r := &parityspec.ParityRunner{
		Spec:         s,
		NewReader:    func(_ string) format.DataFormatReader { return csv.NewReader() },
		BridgeConfig: csvBridgeConfig,
	}
	r.Run(t)
}

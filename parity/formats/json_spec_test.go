//go:build parity

package formats

import (
	"testing"

	"github.com/neokapi/neokapi/core/format"
	jsonfmt "github.com/neokapi/neokapi/core/formats/json"
	parityspec "github.com/neokapi/okapi-bridge/parity/spec"
)

// TestParityJSONSpec drives the JSON spec.yaml through bridge AND
// native readers, validating both against the spec contract and
// against each other.
func TestParityJSONSpec(t *testing.T) {
	specPath := nativeSpecPath(t, "json")
	s, err := parityspec.LoadSpec(specPath)
	if err != nil {
		t.Fatalf("load spec %s: %v", specPath, err)
	}
	r := &parityspec.ParityRunner{
		Spec:      s,
		NewReader: func(_ string) format.DataFormatReader { return jsonfmt.NewReader() },
	}
	r.Run(t)
}

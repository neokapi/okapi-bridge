//go:build parity

package formats

import (
	"testing"

	"github.com/neokapi/neokapi/core/format"
	"github.com/neokapi/neokapi/core/formats/odf"
	parityspec "github.com/neokapi/okapi-bridge/parity/spec"
)

// TestParityOdfSpec drives the odf spec.yaml through the bridge AND
// the native reader, comparing both to the spec contract and to each
// other (block-text equivalence). The same spec file drives the
// always-on native test (core/formats/odf/spec_test.go), so a single
// source of truth describes ODF's behavior across implementations.
//
// The spec binds to bridge filter `okf_openoffice` (the ZIP-handling
// OpenDocument filter), not `okf_odf` (which is the inner content.xml
// filter). The native reader consumes full .odt/.ods/.odp/.odg ZIP
// archives, matching what `okf_openoffice` accepts.
func TestParityOdfSpec(t *testing.T) {
	specPath := nativeSpecPath(t, "odf")
	s, err := parityspec.LoadSpec(specPath)
	if err != nil {
		t.Fatalf("load spec %s: %v", specPath, err)
	}
	r := &parityspec.ParityRunner{
		Spec:      s,
		NewReader: func(_ string) format.DataFormatReader { return odf.NewReader() },
	}
	r.Run(t)
}
